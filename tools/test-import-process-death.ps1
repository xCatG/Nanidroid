param(
    [Parameter(Mandatory)][string] $Serial,
    [string] $Adb,
    [ValidateSet('copy', 'extract', 'pre', 'post')][string[]] $Phases = @('copy', 'extract', 'pre', 'post')
)

$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Require explicit disposable emulator Serial' }
$adbName = if ($IsWindows) { 'adb.exe' } else { 'adb' }
if (!$Adb) {
    foreach ($sdkRoot in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($sdkRoot -and (Test-Path -LiteralPath (Join-Path $sdkRoot "platform-tools/$adbName"))) {
            $Adb = Join-Path $sdkRoot "platform-tools/$adbName"; break
        }
    }
    if (!$Adb) { $command = Get-Command $adbName -ErrorAction SilentlyContinue; if ($command) { $Adb=$command.Source } }
}
if (!$Adb -or !(Test-Path -LiteralPath $Adb -PathType Leaf)) { throw 'Cannot resolve adb: supply -Adb or Android SDK environment/PATH' }
$Adb = (Resolve-Path -LiteralPath $Adb).Path
$platformTools = Split-Path -Parent $Adb
if ((Split-Path -Leaf $platformTools) -eq 'platform-tools') {
    $sdk = Split-Path -Parent $platformTools
    $env:ANDROID_HOME=$sdk; $env:ANDROID_SDK_ROOT=$sdk
}

$package = 'com.cattailsw.nanidroid'
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$class = 'com.cattailsw.nanidroid.install.GhostImportInstrumentationTest'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$evidence = Join-Path $root '.superpowers/sdd/2026-09-24-ghost-import/task5/process-death'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$commands = Join-Path $evidence 'commands.log'

function Invoke-Adb {
    param([string[]] $Arguments, [switch] $AllowFailure)
    Add-Content -LiteralPath $commands -Value ((@($Adb, '-s', $Serial) + $Arguments) -join ' ')
    $output = @(& $Adb -s $Serial @Arguments 2>&1)
    $code = $LASTEXITCODE
    Add-Content -LiteralPath $commands -Value (($output | Out-String) + "exit=$code`n")
    if ($code -ne 0 -and !$AllowFailure) { throw "adb failed ($code): $($Arguments -join ' ')" }
    return $output
}

function Read-PrivateFile([string] $path) {
    return ((Invoke-Adb @('shell', 'run-as', $package, 'cat', $path)) -join "`n")
}

function Private-Exists([string] $path) {
    Invoke-Adb @('shell', 'run-as', $package, 'test', '-e', $path) -AllowFailure | Out-Null
    return $LASTEXITCODE -eq 0
}

function Get-AppPid {
    $result = (Invoke-Adb @('shell', 'pidof', $package) -AllowFailure) -join ' '
    if ($LASTEXITCODE -ne 0) { return '' }
    return $result.Trim()
}

function Assert-Healthy {
    $boot = (Invoke-Adb @('shell', 'getprop', 'sys.boot_completed') -join '').Trim()
    $system = (Invoke-Adb @('shell', 'pidof', 'system_server') -join '').Trim()
    $launcher = ((Invoke-Adb @('shell', 'pidof', 'com.google.android.apps.nexuslauncher') -AllowFailure) -join '').Trim()
    if (!$launcher) { $launcher = ((Invoke-Adb @('shell', 'pidof', 'com.android.launcher3') -AllowFailure) -join '').Trim() }
    if ($boot -ne '1' -or !$system -or !$launcher) {
        throw "Device unhealthy (boot=$boot system_server=$system launcher=$launcher); stopping without retry"
    }
}

function Get-TreeHashes([string] $ghostId) {
    $tree = "files/ghost/$ghostId"
    if (!(Private-Exists $tree)) { return @() }
    $files = @(Invoke-Adb @('shell', 'run-as', $package, 'find', $tree, '-type', 'f')) |
        ForEach-Object { "$($_)".Trim() } | Where-Object { $_ } | Sort-Object
    $hashes = foreach ($file in $files) {
        (Invoke-Adb @('shell', 'run-as', $package, 'sha256sum', $file)) -join ''
    }
    return @($hashes)
}

function Save-Snapshot([string] $phase, [string] $runId, [string] $point, [string] $ghostId) {
    $path = Join-Path $evidence "$phase-$runId-$point-snapshot.txt"
    $stage = (Invoke-Adb @('shell', 'run-as', $package, 'ls', '-laR', 'files/import-staging') -AllowFailure) | Out-String
    $hashes = Get-TreeHashes $ghostId
    @("pid=$(Get-AppPid)", "destinationExists=$(Private-Exists "files/ghost/$ghostId")", 'STAGING:', $stage,
        'DESTINATION SHA256:', ($hashes -join "`n")) | Set-Content -LiteralPath $path
    return ,$hashes
}

if (!(Test-Path -LiteralPath $Adb)) { throw "adb missing: $Adb" }
$device = (Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk') -join '').Trim()
$abi = (Invoke-Adb @('shell', 'getprop', 'ro.product.cpu.abi') -join '').Trim()
if ($Serial -notmatch '^emulator-[0-9]+$' -or $device -notmatch '^\d+$' -or [int]$device -lt 31 -or $abi -ne 'x86_64' -or (Invoke-Adb @('shell','getprop','ro.kernel.qemu') -join '').Trim() -ne '1') {
    throw "Require booted disposable API>=31 x86_64 emulator; got $Serial API$device $abi"
}
Assert-Healthy
$apk = Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk'
$testApk = Join-Path $root 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
if (!(Test-Path $apk) -or !(Test-Path $testApk)) { throw 'Build both debug APKs first' }
Invoke-Adb @('install', '-r', $apk) | Out-Null
Invoke-Adb @('install', '-r', $testApk) | Out-Null

# A completed, disposable-root provider test checks the APK/runner before kill testing.
$dry = Invoke-Adb @('shell', 'am', 'instrument', '-w', '-e', 'class',
    "$class#unknownSizeProviderSuppliesExactBytesForFreshInstall", $runner)
$dry | Set-Content -LiteralPath (Join-Path $evidence 'dry-instrumentation.log')
if (($dry -join "`n") -notmatch 'OK \(1 test\)') { throw 'Dry instrumentation failed' }

foreach ($phase in $Phases) {
    Assert-Healthy
    $runId = [guid]::NewGuid().ToString('N')
    $ghostId = "kill$phase$($runId.Substring(0, 12))"
    $marker = "files/process-death-$runId.marker"
    $sentinel = "files/import-staging/sentinel-$runId"
    $prefix = Join-Path $evidence "$phase-$runId"
    Invoke-Adb @('shell', 'am', 'force-stop', $package) | Out-Null
    if (Private-Exists "files/ghost/$ghostId") { throw "Synthetic destination already exists: $ghostId" }
    if (Private-Exists $marker) { throw "Marker already exists: $marker" }
    $before = Save-Snapshot $phase $runId 'before' $ghostId
    $instrumentArgs = @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-e', 'class',
        "$class#holdAtProcessDeathBoundary", '-e', 'phase', $phase, '-e', 'runId', $runId, $runner)
    Add-Content -LiteralPath $commands -Value ((@($Adb) + $instrumentArgs) -join ' ')
    $client = Start-Process -FilePath $Adb -ArgumentList $instrumentArgs -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput "$prefix-instrument-stdout.log" -RedirectStandardError "$prefix-instrument-stderr.log"
    $observed = $false
    for ($i = 0; $i -lt 100; $i++) {
        if (Private-Exists $marker) { $observed = $true; break }
        if ($client.HasExited) { throw "Instrumentation exited before $phase marker; see $prefix-instrument-stdout.log" }
        Start-Sleep -Milliseconds 100
    }
    if (!$observed) { throw "Timed out waiting for live $phase marker" }
    $text = Read-PrivateFile $marker
    $text | Set-Content -LiteralPath "$prefix-marker.txt"
    $match = [regex]::Match($text, '(?m)^pid=(\d+)$')
    if (!$match.Success -or $text -notmatch "(?m)^runId=$runId$" -or
        $text -notmatch "(?m)^phase=$phase$" -or $text -notmatch "(?m)^ghostId=$ghostId$") {
        throw "Marker identity mismatch: $text"
    }
    $pidAtMarker = $match.Groups[1].Value
    $livePids = @((Get-AppPid) -split '\s+')
    if ($pidAtMarker -notin $livePids -or $client.HasExited) { throw "Marker PID $pidAtMarker is not live" }
    if ($phase -eq 'extract') {
        $expected = [regex]::Match($text, '(?m)^expectedBytes=(\d+)$')
        if (!$expected.Success -or [long]$expected.Groups[1].Value -ne 120L * 1024 * 1024) {
            throw "Extraction marker has no valid expected size: $text"
        }
        $sources = @(@(Invoke-Adb @('shell', 'run-as', $package, 'find', 'files/import-staging',
            '-name', 'source.nar', '-type', 'f')) | ForEach-Object { "$($_)".Trim() } | Where-Object { $_ })
        if ($sources.Count -ne 1) { throw "Expected one validated source.nar, found $($sources.Count)" }
        $attempt = $sources[0].Substring(0, $sources[0].LastIndexOf('/'))
        $partial = "$attempt/tree/$ghostId/shell/master/payload.bin"
        if (!(Private-Exists $sentinel) -or (Private-Exists "files/ghost/$ghostId")) {
            throw 'Extraction pre-release sentinel or destination mismatch'
        }
        Invoke-Adb @('shell', 'run-as', $package, 'touch', "files/process-death-$runId.release") | Out-Null
        $partialSize = 0L
        for ($i = 0; $i -lt 100; $i++) {
            if ($client.HasExited) { throw "Instrumentation finished before partial extraction was observed; see $prefix-instrument-stdout.log" }
            $rawSize = ((Invoke-Adb @('shell', 'run-as', $package, 'stat', '-c', '%s', $partial) -AllowFailure) -join '').Trim()
            if ($rawSize -match '^\d+$') { $partialSize = [long]$rawSize }
            if ($partialSize -gt 0 -and $partialSize -lt ([long]$expected.Groups[1].Value / 2)) { break }
        }
        if ($partialSize -le 0 -or $partialSize -ge ([long]$expected.Groups[1].Value / 2)) {
            throw "No early partial extraction observed (path=$partial size=$partialSize); no mid-extraction claim"
        }
        if ($client.HasExited -or $pidAtMarker -notin @((Get-AppPid) -split '\s+')) {
            throw 'Instrumentation or matching app PID ended before extraction force-stop'
        }
        if (Private-Exists "files/ghost/$ghostId") {
            throw 'Destination was published before extraction force-stop'
        }
        @("runId=$runId", "phase=extract", "pid=$pidAtMarker", "stagedPath=$partial",
            "stagedBytes=$partialSize", "expectedBytes=$($expected.Groups[1].Value)",
            'destinationExists=False',
            "sentinelExists=True", "clientHasExited=$($client.HasExited)") |
            Set-Content -LiteralPath "$prefix-pre-stop.txt"
        $stageHashes = @()
    } else {
        $stageHashes = Save-Snapshot $phase $runId 'held' $ghostId
        $attempts = @(Invoke-Adb @('shell', 'run-as', $package, 'ls', 'files/import-staging')) -join "`n"
        if ($attempts -notmatch 'attempt-' -or !(Private-Exists $sentinel)) { throw 'Held attempt or unrelated sentinel missing' }
    }
    if ($phase -eq 'copy') {
        $sources = @(Invoke-Adb @('shell', 'run-as', $package, 'find', 'files/import-staging', '-name', 'source.nar'))
        if ($sources.Count -ne 1) { throw "Expected one partial source.nar; found $($sources.Count)" }
        $sourceSize = ((Invoke-Adb @('shell', 'run-as', $package, 'stat', '-c', '%s', "$($sources[0])")) -join '').Trim()
        if ($sourceSize -ne '8192') { throw "Copy boundary source size was $sourceSize, expected one 8192-byte chunk" }
    }
    if (($phase -eq 'post') -ne (Private-Exists "files/ghost/$ghostId")) { throw 'Publication boundary mismatch' }
    if ($phase -eq 'post' -and $stageHashes.Count -eq 0) { throw 'Committed tree has no hashed files' }
    Invoke-Adb @('shell', 'am', 'force-stop', $package) | Out-Null
    for ($i = 0; $i -lt 50 -and (((Get-AppPid) -split '\s+') -contains $pidAtMarker); $i++) {
        Start-Sleep -Milliseconds 100
    }
    if (((Get-AppPid) -split '\s+') -contains $pidAtMarker) { throw "Target PID $pidAtMarker survived force-stop" }
    "killedPid=$pidAtMarker`nclientPid=$($client.Id)" | Set-Content -LiteralPath "$prefix-pids.txt"
    $client.WaitForExit(5000) | Out-Null
    Assert-Healthy
    Invoke-Adb @('shell', 'am', 'start', '-n', "$package/.MainActivity") | Out-Null
    $newPid = ''
    for ($i = 0; $i -lt 100; $i++) {
        $newPid = Get-AppPid
        if ($newPid) { break }
        Start-Sleep -Milliseconds 100
    }
    if (!$newPid -or (($newPid -split '\s+') -contains $pidAtMarker)) { throw 'Fresh app process was not observed' }
    for ($i = 0; $i -lt 100; $i++) {
        $names = @(Invoke-Adb @('shell', 'run-as', $package, 'ls', 'files/import-staging')) -join "`n"
        if ($names -notmatch 'attempt-') { break }
        Start-Sleep -Milliseconds 100
    }
    if ($names -match 'attempt-') { throw "Startup did not recover owned $phase attempt" }
    $after = Save-Snapshot $phase $runId 'after' $ghostId
    if (!(Private-Exists $sentinel)) { throw 'Startup recovery deleted unrelated sentinel' }
    if ($phase -eq 'post') {
        if (($stageHashes -join "`n") -ne ($after -join "`n")) { throw 'Committed byte hashes changed after restart' }
    } elseif ($after.Count -ne 0 -or (Private-Exists "files/ghost/$ghostId")) {
        throw 'Precommit destination became visible after restart'
    }
    $verification = Invoke-Adb @('shell', 'am', 'instrument', '-w', '-e', 'class',
        "$class#verifyProcessDeathRecovery", '-e', 'phase', $phase, '-e', 'runId', $runId, $runner)
    $verification | Set-Content -LiteralPath "$prefix-verification.log"
    if (($verification -join "`n") -notmatch 'OK \(1 test\)') { throw "Recovery verification failed for $phase" }
    Assert-Healthy
}

"Live-PID process-death boundaries passed: $($Phases -join ', ')." |
    Tee-Object -FilePath (Join-Path $evidence 'result.txt')
