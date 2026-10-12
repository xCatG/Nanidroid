[CmdletBinding()]
param(
    [string]$Suite = 'self-contained',
    [string]$Serial,
    [string]$Adb,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot ('../.superpowers/sdd/issue-426/device-' + [guid]::NewGuid().ToString('N'))),
    [switch]$SkipBuild,
    [switch]$ListOnly,
    [switch]$SelfCheck
)
$ErrorActionPreference = 'Stop'

function Resolve-SuiteAdb([string]$Explicit) {
    $name = if ($IsWindows) { 'adb.exe' } else { 'adb' }
    if ($Explicit) {
        if (!(Test-Path -LiteralPath $Explicit -PathType Leaf)) { throw "adb missing: $Explicit" }
        return (Resolve-Path -LiteralPath $Explicit).Path
    }
    foreach ($sdk in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($sdk) {
            $candidate = Join-Path $sdk "platform-tools/$name"
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { return (Resolve-Path $candidate).Path }
        }
    }
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw 'Cannot resolve adb: supply -Adb or ANDROID_SDK_ROOT/ANDROID_HOME, or put adb on PATH'
}

function Get-CleanSuiteCommit([string]$Repository) {
    $state = @(& git -C $Repository status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot verify suite source: Git status failed' }
    if ($state.Count) { throw 'Suite execution requires a clean Git worktree (including staged and untracked files); commit or preserve changes before execution' }
    $commit = (& git -C $Repository rev-parse HEAD | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') { throw 'Cannot verify suite source commit' }
    return $commit
}

function Read-DeviceInventory([string]$SourceRoot, $Manifest) {
    if ($Manifest.schemaVersion -ne 1) { throw 'Unsupported device manifest schemaVersion' }
    $discovered = @()
    foreach ($file in Get-ChildItem -LiteralPath $SourceRoot -Recurse -File) {
        if ($file.Extension -notin @('.kt','.java')) { continue }
        $text = Get-Content -LiteralPath $file.FullName -Raw
        # Strip current Kotlin/Java comments and literals, preserving offsets and newlines.
        # This intentionally supports the checked-in conventions, not arbitrary Kotlin.
        $clean = [regex]::Replace($text, '(?s)""".*?"""|"(?:\\.|[^"\\])*"|''(?:\\.|[^''\\])*''|/\*.*?\*/|//[^\r\n]*', {
            param($match) [regex]::Replace($match.Value, '[^\r\n]', ' ')
        })
        if ($clean -notmatch '(?m)^package\s+([A-Za-z0-9_.]+)') { throw "Missing package: $file" }
        $package = $Matches[1]
        if ($clean -match '(?m)^import\s+org\.junit\.(Test|\*)\s+as\b') { throw "Unsupported aliased JUnit discovery: $file" }
        $annotations = [regex]::Matches($clean, '@(?:org\.junit\.)?Test\b')
        if (!$annotations.Count) { continue }
        if ($file.Extension -ne '.kt') { throw "Unsupported Java test discovery: $file" }
        $classes = [regex]::Matches($clean, '(?m)^class\s+([A-Za-z_][A-Za-z0-9_]*)\s*\{')
        foreach ($annotation in $annotations) {
            $tail = $clean.Substring($annotation.Index)
            if ($tail -notmatch '^@Test\s+fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(\s*\)') { throw "Unsupported @Test syntax: $file offset $($annotation.Index)" }
            $method = $Matches[1]
            $class = @($classes | Where-Object { $_.Index -lt $annotation.Index } | Select-Object -Last 1)
            if ($class.Count -ne 1) { throw "Unsupported test class: $file" }
            $prefix = $clean.Substring(0,$annotation.Index)
            $depth = ([regex]::Matches($prefix,'\{').Count - [regex]::Matches($prefix,'\}').Count)
            if ($depth -ne 1) { throw "Unsupported nested test: $file#$method" }
            $discovered += "$package.$($class[0].Groups[1].Value)#$method"
        }
    }
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $entries = @($Manifest.methods)
    foreach ($entry in $entries) {
        $keys = @($entry.PSObject.Properties.Name | Sort-Object)
        if (($keys -join ',') -ne 'class,method,prerequisite,reason,requiredArguments,suite') { throw 'Invalid method entry fields' }
        if ($entry.class -notmatch '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$' -or
            $entry.method -notmatch '^[A-Za-z_][A-Za-z0-9_]*$' -or
            $entry.suite -cnotin @('self-contained','corpus-fixture','host-orchestrated','diagnostic-device') -or
            $entry.requiredArguments -isnot [array] -or !$entry.prerequisite -or !$entry.reason) { throw "Invalid inventory entry: $($entry.class)#$($entry.method)" }
        $id = "$($entry.class)#$($entry.method)"
        if (!$seen.Add($id)) { throw "Duplicate inventory entry: $id" }
        if ($id -cnotin $discovered) { throw "Unknown inventory entry: $id" }
    }
    if (@($discovered | Select-Object -Unique).Count -ne $discovered.Count) { throw 'Duplicate discovered method' }
    foreach ($id in $discovered) { if (!$seen.Contains($id)) { throw "Unclassified method: $id" } }
    if (!$entries.Count) { throw 'Empty inventory' }
    return $entries
}

function Select-DeviceSuite($Inventory, [string]$Name) {
    if ($Name -cnotin @('self-contained','corpus-fixture','host-orchestrated','diagnostic-device')) { throw "Unknown suite: $Name" }
    $ids = @($Inventory | Where-Object { $_.suite -ceq $Name } | ForEach-Object { "$($_.class)#$($_.method)" } | Sort-Object -CaseSensitive)
    if (!$ids.Count) { throw "Empty suite: $Name" }
    return $ids
}

function Read-SuiteTranscript([string]$Text, [string[]]$Selected, [int]$ExitCode, [bool]$TimedOut,
    [string]$StdoutPath, [string]$StderrPath) {
    if (!$Selected.Count -or @($Selected | Select-Object -Unique).Count -ne $Selected.Count) { throw 'Empty or duplicate selection' }
    $records = @(); $bundle = @{}; $reason = ''; $terminal = @()
    foreach ($line in ($Text -split '\r?\n')) {
        if ($line -match '^INSTRUMENTATION_STATUS: ([A-Za-z]+)=(.*)$') { $bundle[$Matches[1]] = $Matches[2] }
        elseif ($line -match '^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$') {
            $code = [int]$Matches[1]
            if ($code -ne 1 -and $code -ne 2) {
                $id = "$($bundle['class'])#$($bundle['test'])"
                $status = switch ($code) { 0 {'passed'} -3 {'skipped'} -4 {'skipped'} -1 {'failed'} -2 {'failed'} default {'incomplete'} }
                $records += [ordered]@{methodId=$id;status=$status;reason=$(if ($status -eq 'passed') {''} else {"JUnit status $code; $($bundle['stack']) $($bundle['stream'])"});stdoutPath=$StdoutPath;stderrPath=$StderrPath}
            }
            $bundle = @{}
        } elseif ($line -match '^INSTRUMENTATION_CODE: (-?\d+)\s*$') { $terminal += [int]$Matches[1] }
    }
    $observed = @($records | ForEach-Object methodId)
    foreach ($record in $records) {
        if ($record.methodId -cnotin $Selected -or @($observed | Where-Object { $_ -ceq $record.methodId }).Count -ne 1) {
            $record.status = 'incomplete'; $record.reason = 'Unexpected identity or duplicate terminal method status'
        }
    }
    foreach ($id in $Selected) {
        if ($id -cnotin $observed) { $records += [ordered]@{methodId=$id;status='incomplete';reason='Selected method has no terminal status';stdoutPath=$StdoutPath;stderrPath=$StderrPath} }
    }
    if ($TimedOut) { $reason = 'Suite instrumentation deadline expired' }
    elseif ($ExitCode -ne 0) { $reason = "adb exited $ExitCode" }
    elseif ($terminal.Count -ne 1 -or $terminal[0] -ne -1 -or $Text -match 'INSTRUMENTATION_FAILED|shortMsg=|Process crashed') { $reason = 'Missing, duplicate or unsuccessful instrumentation terminal result' }
    if ($reason) {
        # Retain observed identities/statuses but never allow a process failure to pass.
        foreach ($record in $records) { if ($record.status -eq 'passed') { $record.status='incomplete';$record.reason=$reason } }
    }
    $counts = [ordered]@{passed=0;skipped=0;failed=0;incomplete=0}
    foreach ($record in $records) { $counts[$record.status]++ }
    $outcome = if ($counts.failed) {'failed'} elseif ($reason -or $counts.skipped -or $counts.incomplete) {'incomplete'} else {'passed'}
    return [ordered]@{observedMethods=$observed;observedCount=$observed.Count;results=$records;counts=$counts;outcome=$outcome}
}

function Set-SuiteDiagnosticFailure($Summary, [string]$Reason) {
    foreach ($record in $Summary.results) {
        if ($record.status -eq 'passed') {
            $record.status='incomplete'
            $record.reason="Suite diagnostics unavailable: $Reason"
            $Summary.counts.passed--
            $Summary.counts.incomplete++
        }
    }
    if ($Summary.outcome -ne 'failed') { $Summary.outcome='incomplete' }
    # cleanupStatus describes force-stop/device health, independently of capture.
}

function Invoke-SuiteProcess([string]$Executable, [string[]]$Arguments, [int]$Seconds, [string]$Prefix) {
    $start = [Diagnostics.ProcessStartInfo]::new($Executable)
    $start.UseShellExecute=$false; $start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
    foreach ($arg in $Arguments) { $start.ArgumentList.Add($arg) }
    $process = [Diagnostics.Process]::Start($start)
    $out = $process.StandardOutput.ReadToEndAsync(); $err = $process.StandardError.ReadToEndAsync()
    $timeout = !$process.WaitForExit($Seconds * 1000)
    try {
        if ($timeout) { $process.Kill($true); if (!$process.WaitForExit(5000)) { throw 'Could not reap timed-out adb' } }
        $stdout=$out.GetAwaiter().GetResult(); $stderr=$err.GetAwaiter().GetResult()
        $stdout | Set-Content -LiteralPath "$Prefix.stdout.log" -Encoding utf8
        $stderr | Set-Content -LiteralPath "$Prefix.stderr.log" -Encoding utf8
        return @{stdout=$stdout;stderr=$stderr;exitCode=$process.ExitCode;timedOut=$timeout}
    } finally { $process.Dispose() }
}

if ($MyInvocation.InvocationName -eq '.') { return }
if ($SelfCheck) { & (Join-Path $PSScriptRoot 'tests/test-device-suite.ps1'); return }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$inventory = @(Read-DeviceInventory (Join-Path $repo 'app/src/androidTest/java') (Get-Content (Join-Path $repo 'docs/testing/device-suites.json') -Raw | ConvertFrom-Json))
$selected = @(Select-DeviceSuite $inventory $Suite)
if ($ListOnly) { $selected; Write-Host "Selected count: $($selected.Count)"; return }
if ($Suite -cne 'self-contained') { throw 'Only self-contained is executable; use the documented fixture/host tools for other suites' }
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Execution requires explicit -Serial emulator-<port> for an authorized disposable emulator' }
$sourceCommit = Get-CleanSuiteCommit $repo
$Adb = Resolve-SuiteAdb $Adb
# A normal SDK adb path also supplies the SDK for Gradle when no local.properties
# or SDK environment was configured. Explicit -Adb takes precedence.
$platformTools = Split-Path -Parent $Adb
if ((Split-Path -Leaf $platformTools) -eq 'platform-tools') {
    $sdk = Split-Path -Parent $platformTools
    $env:ANDROID_HOME=$sdk; $env:ANDROID_SDK_ROOT=$sdk
}
if (Test-Path -LiteralPath $OutputDirectory) { throw "Output directory already exists: $OutputDirectory" }
[IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($OutputDirectory)) | Out-Null
$OutputDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path
$watch = [Diagnostics.Stopwatch]::StartNew()
$summary = [ordered]@{schemaVersion=1;suite=$Suite;sourceCommit=$sourceCommit;serial=$Serial;appApkSha256='';testApkSha256='';device=@{api=0;abi=''};selectedMethods=$selected;expectedCount=$selected.Count;observedMethods=@();observedCount=0;results=@();counts=@{passed=0;skipped=0;failed=0;incomplete=$selected.Count};durationSeconds=0;cleanupStatus='not-run';outcome='incomplete'}
$verifiedDevice=$false; $primary=$null
function Invoke-Device([string[]]$Arguments, [string]$Name, [int]$Seconds=30) {
    $r = Invoke-SuiteProcess $Adb (@('-s',$Serial)+$Arguments) $Seconds (Join-Path $OutputDirectory $Name)
    if ($r.timedOut -or $r.exitCode -ne 0) { throw "adb $Name failed; inspect $Name stdout/stderr" }
    return $r.stdout.Trim()
}
try {
    if ((Invoke-Device @('get-state') 'state') -cne 'device' -or
        (Invoke-Device @('shell','getprop','ro.kernel.qemu') 'qemu') -cne '1' -or
        (Invoke-Device @('shell','getprop','sys.boot_completed') 'boot') -cne '1') { throw 'Named device is not a booted emulator' }
    $api = Invoke-Device @('shell','getprop','ro.build.version.sdk') 'api'
    $abi = Invoke-Device @('shell','getprop','ro.product.cpu.abi') 'abi'
    if ($api -notmatch '^\d+$' -or [int]$api -lt 31 -or $abi -cne 'x86_64') { throw "Require API>=31 x86_64; got $api $abi" }
    $summary.device=@{api=[int]$api;abi=$abi}; $verifiedDevice=$true
    if (!$SkipBuild) {
        Push-Location $repo
        try {
            if ($IsWindows) { & (Join-Path $repo 'gradlew.bat') :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain *> (Join-Path $OutputDirectory 'build.log') }
            else { & bash (Join-Path $repo 'gradlew') :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain *> (Join-Path $OutputDirectory 'build.log') }
            if ($LASTEXITCODE -ne 0) { throw 'APK build failed; inspect build.log' }
        } finally { Pop-Location }
    }
    if ((Get-CleanSuiteCommit $repo) -cne $sourceCommit) { throw 'Suite source commit changed during execution; refusing APK installation' }
    $appApk=Join-Path $repo 'app/build/outputs/apk/debug/app-debug.apk'
    $testApk=Join-Path $repo 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    foreach ($apk in @($appApk,$testApk)) { if (!(Test-Path -LiteralPath $apk -PathType Leaf)) { throw "APK missing: $apk" } }
    $summary.appApkSha256=(Get-FileHash $appApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $summary.testApkSha256=(Get-FileHash $testApk -Algorithm SHA256).Hash.ToLowerInvariant()
    Invoke-Device @('install','-r',$appApk) 'install-app' 180 | Out-Null
    Invoke-Device @('install','-r',$testApk) 'install-test' 180 | Out-Null
    $prefix=Join-Path $OutputDirectory 'instrumentation'
    $r = Invoke-SuiteProcess $Adb @('-s',$Serial,'shell','am','instrument','-w','-r','-e','class',($selected -join ','),'com.cattailsw.nanidroid.test/androidx.test.runner.AndroidJUnitRunner') 1800 $prefix
    $parsed=Read-SuiteTranscript ($r.stdout + "`n" + $r.stderr) $selected $r.exitCode $r.timedOut "$prefix.stdout.log" "$prefix.stderr.log"
    foreach ($key in $parsed.Keys) { $summary[$key]=$parsed[$key] }
} catch { $primary=$_; $_ | Out-String | Set-Content (Join-Path $OutputDirectory 'primary-error.log'); $summary.outcome='incomplete' }
finally {
    if (!$summary.results.Count) {
        $summary.results=@($selected | ForEach-Object { @{methodId=$_;status='incomplete';reason=$(if ($primary) {$primary.Exception.Message} else {'No instrumentation result'});stdoutPath=(Join-Path $OutputDirectory 'instrumentation.stdout.log');stderrPath=(Join-Path $OutputDirectory 'instrumentation.stderr.log')} })
    }
    if ($verifiedDevice) {
        # A diagnostic capture failure must not prevent releasing the test process.
        $diagnosticReason=$null
        try { Invoke-Device @('logcat','-d') 'logcat' 30 | Out-Null }
        catch { $_ | Out-String | Set-Content (Join-Path $OutputDirectory 'diagnostic-error.log'); $diagnosticReason=$_.Exception.Message }
        try {
            Invoke-Device @('shell','am','force-stop','com.cattailsw.nanidroid') 'cleanup' | Out-Null
            if ((Invoke-Device @('get-state') 'cleanup-state') -cne 'device') { throw 'Device unavailable after cleanup' }
            $summary.cleanupStatus='passed'
        } catch { $_ | Out-String | Set-Content (Join-Path $OutputDirectory 'cleanup-error.log'); $summary.cleanupStatus='failed'; $summary.outcome='failed' }
        if ($diagnosticReason) { Set-SuiteDiagnosticFailure $summary $diagnosticReason }
    }
    $summary.durationSeconds=$watch.Elapsed.TotalSeconds
    $summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'summary.json') -Encoding utf8
}
Write-Host "Suite $($summary.outcome); selected=$($selected.Count) observed=$($summary.observedCount); evidence=$OutputDirectory"
if ($summary.outcome -ne 'passed' -or $summary.cleanupStatus -ne 'passed') { exit 1 }
