[CmdletBinding(DefaultParameterSetName='Run')]
param(
    [Parameter(ParameterSetName='SelfCheck', Mandatory)][switch]$SelfCheck,
    [Parameter(ParameterSetName='Run', Mandatory)][string]$DeviceSerial,
    [Parameter(ParameterSetName='Run')][string]$CorpusRoot = 'C:/tmp/Nanidroid-corpus-recovery',
    [Parameter(ParameterSetName='Run')][string]$OutputDirectory = (Join-Path $PSScriptRoot ('../.superpowers/sdd/2026-09-27-milestone-5-corpus-polish/task-1-run-' + [guid]::NewGuid().ToString('N'))),
    [Parameter(ParameterSetName='Run')][switch]$SkipBuild,
    [Parameter(ParameterSetName='Run')][string]$Label,
    [Parameter(ParameterSetName='Run')][ValidateSet('Diagnostic','Acceptance')][string]$Mode = 'Diagnostic',
    [Parameter(ParameterSetName='Run')][string]$ExpectationsPath
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'test-outcome-policy.ps1')
if ($SelfCheck) { & (Join-Path $PSScriptRoot 'tests/test-runner-outcomes.ps1') -PolicyOnly; return }
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$adb = 'C:/tools/android.sdk/platform-tools/adb.exe'
$package = 'com.cattailsw.nanidroid'
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$class = "$package.corpus.Milestone5CorpusTest#smokeArchive"
$remoteOutput = "/sdcard/Android/data/$package/files"
$manifestPath = Join-Path $repo 'docs/testing/milestone-5-corpus.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$appApk = Join-Path $repo 'app/build/outputs/apk/debug/app-debug.apk'
$testApk = Join-Path $repo 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
$requested = if ($PSBoundParameters.ContainsKey('Label')) { @($Label) } else { @() }
$resolved = @(Resolve-OutcomeSelection @($manifest.rows | ForEach-Object label) $requested $PSBoundParameters.ContainsKey('Label'))
$selected = @($manifest.rows | Where-Object { $_.label -cin $resolved })
$expectations = $null
$expectationIdentity = $null
if ($Mode -eq 'Acceptance') {
    if ([string]::IsNullOrWhiteSpace($ExpectationsPath)) { throw 'Acceptance requires -ExpectationsPath' }
    $expectations = Read-OutcomeExpectations (Get-Content -LiteralPath $ExpectationsPath -Raw | ConvertFrom-Json) $manifest.rows $selected
    $expectationIdentity = [ordered]@{path=[IO.Path]::GetFullPath($ExpectationsPath);sha256=(Get-FileHash -LiteralPath $ExpectationsPath -Algorithm SHA256).Hash.ToLowerInvariant()}
}
Write-Host "Requested: $(if ($requested.Count) { $requested -join ',' } else { '<all>' }); resolved: $($resolved -join ','); mode=$Mode"
$records = @(); $runReasons=@(); $commit=(& git -C $repo rev-parse HEAD | Out-String).Trim()
function Write-CorpusSummary {
    $summary=Get-CorpusOutcome $selected $records $Mode $expectations
    $summary.schemaVersion=1; $summary.runner='milestone5-corpus'; $summary.mode=$Mode; $summary.sourceCommit=$commit
    $summary.requestedSelection=@($requested); $summary.resolvedSelection=@($resolved); $summary.expectationFile=$expectationIdentity
    $summary.reasons=@($summary.reasons)+@($runReasons)
    if ($summary.reasons.Count) { $summary.outcome='failed' }
    $summary | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'summary.json') -Encoding utf8
    return $summary
}

function Get-RemainingMilliseconds([DateTime]$Deadline, [int]$Cap = 30000) {
    $remaining = [int][Math]::Floor(($Deadline - [DateTime]::UtcNow).TotalMilliseconds)
    if ($remaining -le 0) { throw '180-second row deadline expired' }
    return [Math]::Min($Cap, $remaining)
}

function Invoke-Adb([string[]]$Arguments, [string]$LogPath, [switch]$AllowFailure,
    [int]$MaxMilliseconds = 30000) {
    $deadline = if ($script:rowDeadlineUtc) { $script:rowDeadlineUtc } else { [DateTime]::UtcNow.AddSeconds(30) }
    if ($script:actionDeadlineUtc -and !$script:cleanupPhase -and
        $script:actionDeadlineUtc -lt $deadline) { $deadline = $script:actionDeadlineUtc }
    try { $remaining = Get-RemainingMilliseconds $deadline $MaxMilliseconds }
    catch {
        if ($LogPath) { "error=$($_.Exception.Message)" | Set-Content -LiteralPath $LogPath -Encoding utf8 }
        throw
    }
    $start = [Diagnostics.ProcessStartInfo]::new($adb)
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($part in (@('-s', $DeviceSerial) + $Arguments)) { [void]$start.ArgumentList.Add($part) }
    $process = $null
    try {
        $script:adbStarts++
        $process = [Diagnostics.Process]::Start($start)
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit($remaining)) {
            throw "adb timed out after $remaining ms: $($Arguments -join ' ')"
        }
        $output = @($stdout.GetAwaiter().GetResult(), $stderr.GetAwaiter().GetResult()) |
            Where-Object { $_ }
        $code = $process.ExitCode
    } catch {
        $failure = $_
        if ($process -and !$process.HasExited) {
            try {
                $process.Kill($true)
                if (!$process.WaitForExit(5000)) { throw 'adb process did not exit after kill' }
            } catch {
                $failure = [System.Management.Automation.ErrorRecord]::new(
                    [InvalidOperationException]::new("Unable to reap failed adb process: $($_.Exception.Message)"),
                    'AdbReapFailure', [System.Management.Automation.ErrorCategory]::OperationStopped, $process)
            }
        }
        if ($LogPath) { "error=$($failure.Exception.Message)" | Set-Content -LiteralPath $LogPath -Encoding utf8 }
        throw $failure
    } finally { if ($process) { $process.Dispose() } }
    if ($LogPath) { @("exit=$code") + @($output) | Set-Content -LiteralPath $LogPath -Encoding utf8 }
    if ($code -ne 0 -and !$AllowFailure) { throw "adb $($Arguments -join ' ') exited $code; $output" }
    $script:lastAdbExitCode = $code
    return @($output)
}

function Assert-DeviceIdentity {
    if ($DeviceSerial -notmatch '^emulator-[0-9]+$') { throw "Not an emulator serial: $DeviceSerial" }
    $devices = @(Invoke-Adb @('devices','-l'))
    if (!($devices -match "(?m)^$([regex]::Escape($DeviceSerial))\s+device\b")) {
        throw "Device unavailable: $DeviceSerial; $devices"
    }
    $qemu = (Invoke-Adb @('shell','getprop','ro.kernel.qemu') | Out-String).Trim()
    $boot = (Invoke-Adb @('shell','getprop','sys.boot_completed') | Out-String).Trim()
    $api = (Invoke-Adb @('shell','getprop','ro.build.version.sdk') | Out-String).Trim()
    $abi = (Invoke-Adb @('shell','getprop','ro.product.cpu.abi') | Out-String).Trim()
    if ($qemu -ne '1' -or $boot -ne '1' -or $api -ne '31' -or $abi -ne 'x86_64') {
        throw "Unexpected device identity qemu=$qemu boot=$boot api=$api abi=$abi"
    }
    return [ordered]@{serial=$DeviceSerial;qemu=$qemu;boot=$boot;api=$api;abi=$abi}
}

function New-Fixture([string]$Path, [string]$Kind) {
    Add-Type -AssemblyName System.IO.Compression
    $file = [IO.File]::Create($Path)
    try {
        $zip = [IO.Compression.ZipArchive]::new($file,[IO.Compression.ZipArchiveMode]::Create,$false)
        try {
            $entries = if ($Kind -eq 'ghost') {
                @{'install.txt'="type,ghost`ndirectory,corpus-harness`n";
                  'ghost/master/descript.txt'="name,Corpus harness`nshiori,Nanidroid`n";
                  'ghost/master/ja/content.txt'='OnFirstBoot,\0Harness ready\e';
                  'shell/master/placeholder.txt'='fixture'}
            } else { @{'install.txt'="type,shell`ndirectory,corpus-harness-shell`n"} }
            foreach ($name in $entries.Keys) {
                $entry = $zip.CreateEntry($name)
                $writer = [IO.StreamWriter]::new($entry.Open())
                try { $writer.Write($entries[$name]) } finally { $writer.Dispose() }
            }
        } finally { $zip.Dispose() }
    } finally { $file.Dispose() }
}

function Assert-Result([string]$Path, [bool]$ExpectedPass, [string]$ExpectedLabel,
    [string]$ExpectedHash, [string]$ActualHash) {
    if (!(Test-Path -LiteralPath $Path) -or (Get-Item -LiteralPath $Path).Length -eq 0) {
        throw "Missing or empty instrumentation result: $Path"
    }
    $value = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    if ($value.label -cne $ExpectedLabel -or
        $value.expectedSha256 -cne $ExpectedHash.ToLowerInvariant() -or
        $value.actualSha256 -cne $ActualHash.ToLowerInvariant()) {
        throw "Result label/hash provenance mismatch: $Path"
    }
    if ($ExpectedPass -and $value.testStatus -ne 'completed') { throw "Incomplete result: $Path" }
    if (!$ExpectedPass -and $value.testStatus -ne 'failed') { throw "Expected failed result: $Path" }
    return $value
}

function Assert-RowSafeToContinue($Record) {
    if ($Record.cleanupError -or $Record.healthError) {
        throw "Device quarantined after $($Record.label): cleanup=$($Record.cleanupError) health=$($Record.healthError)"
    }
}

function Invoke-Row($row, [string]$source, [bool]$expectedPass, [bool]$wrongHash = $false) {
    $rowDir = Join-Path $OutputDirectory $row.label
    if (Test-Path -LiteralPath $rowDir) { throw "Row directory already exists: $rowDir" }
    [IO.Directory]::CreateDirectory($rowDir) | Out-Null
    $record = [ordered]@{
        label=$row.label; relativePath=$row.relativePath; packageKind=$row.packageKind;
        family=$row.family; archiveSha256=$row.sha256; appApkSha256=$appHash;
        testApkSha256=$testHash; sourceCommit=$commit; device=$identity;
        action='GhostImporter.importArchive, then MainActivity runtime boot/screenshot/close for installed ghosts';
        started=(Get-Date).ToString('o'); deadlineSeconds=180; outcome='unverified'
    }
    $script:rowDeadlineUtc = [DateTime]::UtcNow.AddSeconds(180)
    $script:actionDeadlineUtc = $script:rowDeadlineUtc.AddSeconds(-25)
    $script:cleanupPhase = $false
    try {
        $actual = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $row.sha256) { throw "Host archive hash mismatch: $actual" }
        Assert-DeviceIdentity | ConvertTo-Json -Compress | Set-Content (Join-Path $rowDir 'identity-before-clear.json')
        Invoke-Adb @('shell','pm','clear',$package) (Join-Path $rowDir 'clear.log') | Out-Null
        $remote = "/data/local/tmp/corpus-$($row.label).nar"
        Invoke-Adb @('push',$source,$remote) (Join-Path $rowDir 'push.log') | Out-Null
        Invoke-Adb @('shell','run-as',$package,'mkdir','-p','files') (Join-Path $rowDir 'mkdir.log') | Out-Null
        Invoke-Adb @('shell','run-as',$package,'cp',$remote,'files/corpus-input.nar') (Join-Path $rowDir 'stage.log') | Out-Null
        $deviceHash = (Invoke-Adb @('shell','run-as',$package,'sha256sum','files/corpus-input.nar') | Out-String).Split(' ')[0].Trim().ToLowerInvariant()
        $record.deviceInputSha256 = $deviceHash
        if ($deviceHash -ne $actual) { throw "Staged archive hash mismatch: $deviceHash" }
        $passHash = if ($wrongHash) { '0' * 64 } else { $actual }
        $stdout = Join-Path $rowDir 'instrument.stdout.log'
        $stderr = Join-Path $rowDir 'instrument.stderr.log'
        $arguments = @('-s',$DeviceSerial,'shell','am','instrument','-w','-r','-e','corpusPath','corpus-input.nar',
            '-e','corpusSha256',$passHash,'-e','corpusLabel',$row.label,
            '-e','corpusKind',$row.packageKind,'-e','class',$class,$runner)
        $process = Start-Process -FilePath $adb -ArgumentList $arguments -PassThru -WindowStyle Hidden `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $remaining = Get-RemainingMilliseconds $script:actionDeadlineUtc 155000
        $finished = $process.WaitForExit($remaining)
        $record.instrumentExited = $finished
        $record.instrumentExitCode = if ($finished) { $process.ExitCode } else { $null }
        if (!$finished) {
            $record.timeout = $true
            $process.Kill($true)
            [void]$process.WaitForExit(1000)
            throw 'Instrumentation reached the action deadline; result is not accepted'
        }
        foreach ($name in @('corpus-phase.txt','corpus-result.json')) {
            Invoke-Adb @('pull',"$remoteOutput/$name",(Join-Path $rowDir $name)) `
                (Join-Path $rowDir "$name.pull.log") | Out-Null
        }
        $raw = if (Test-Path -LiteralPath $stdout) { Get-Content -LiteralPath $stdout -Raw } else { '' }
        $record.instrumentOneTestOk = $raw -match 'OK \(1 test\)'
        $record.instrumentStatus = Get-InstrumentationOutcome $raw $process.ExitCode "$package.corpus.Milestone5CorpusTest" 'smokeArchive'
        $result = Assert-Result (Join-Path $rowDir 'corpus-result.json') $expectedPass `
            $row.label $passHash $actual
        $record.deviceResult = $result
        $screenshotPath = Join-Path $rowDir 'corpus-stage.png'
        if ($expectedPass -and $row.packageKind -eq 'ghost' -and $result.importOutcome -eq 'Installed') {
            Invoke-Adb @('pull',"$remoteOutput/corpus-stage.png",$screenshotPath) `
                (Join-Path $rowDir 'corpus-stage.png.pull.log') | Out-Null
            $png = [IO.File]::ReadAllBytes($screenshotPath)
            $signature = [byte[]](137,80,78,71,13,10,26,10)
            if ($png.Length -le 24 -or ($png[0..7] -join ',') -ne ($signature -join ',')) {
                throw 'Stage screenshot is empty or not PNG'
            }
            $record.screenshotPath = $screenshotPath
        } else {
            $record.screenshotPath = $null
            $record.skippedSteps = 'activation, first boot, stage capture and close after import rejection'
        }
        if ($expectedPass -and ($record.instrumentStatus -ne 'passed')) {
            throw 'Instrumentation failed, timed out or lacked OK (1 test)'
        }
        if ($result.classification -notin @('supported-smoke','partial-unsupported','expected-rejection',
            'unsupported-engine','in-scope-failure','native-failure','unverified') -and !$wrongHash) {
            throw "Unknown device classification: $($result.classification)"
        }
        if ($wrongHash) { $record.outcome = 'expected-rejection' }
        else {
            $classification = Get-CorpusClassification $result
            $record.outcome = $classification.classification
            $record.classificationBasis = $classification.basis
        }
    } catch {
        $record.hostError = $_.Exception.Message
        if ($record.hostError -match 'timed out|deadline') { $record.timeout = $true }
        $record.outcome = 'unverified'
    } finally {
        $script:cleanupPhase = $true
        if ($record.timeout) {
            try {
                Invoke-Adb @('pull',"$remoteOutput/corpus-phase.txt",(Join-Path $rowDir 'corpus-phase.txt')) `
                    (Join-Path $rowDir 'timeout-phase.pull.log') -MaxMilliseconds 3000 | Out-Null
            } catch { $record.timeoutPhaseError = $_.Exception.Message }
        }
        try {
            Invoke-Adb @('shell','am','force-stop',$package) (Join-Path $rowDir 'force-stop.log') `
                -MaxMilliseconds 5000 | Out-Null
        } catch { $record.cleanupError = "force-stop: $($_.Exception.Message)" }
        if (!$record.cleanupError) {
            try {
                Invoke-Adb @('shell','rm','-f',"/data/local/tmp/corpus-$($row.label).nar") `
                    (Join-Path $rowDir 'remote-cleanup.log') -MaxMilliseconds 5000 | Out-Null
            } catch { $record.cleanupError = "remote cleanup: $($_.Exception.Message)" }
        }
        if (!$record.cleanupError) {
            try { $record.healthAfter = Assert-DeviceIdentity }
            catch { $record.healthError = $_.Exception.Message }
        }
        if (!$record.cleanupError -and !$record.healthError -and
            [DateTime]::UtcNow -lt $script:rowDeadlineUtc.AddSeconds(-1)) {
            try {
                Invoke-Adb @('logcat','-d','-s','AndroidRuntime:E','libc:F') `
                    (Join-Path $rowDir 'crash-logcat.log') -MaxMilliseconds 1000 | Out-Null
            } catch { $record.logcatError = $_.Exception.Message }
        }
        if ($record.cleanupError -or $record.healthError) {
            $record.outcome = 'unverified'
            $record.quarantined = $true
        }
        $record.ended = (Get-Date).ToString('o')
        $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $rowDir 'row.json') -Encoding utf8
        $script:rowDeadlineUtc = $null
        $script:actionDeadlineUtc = $null
        $script:cleanupPhase = $false
        Assert-RowSafeToContinue $record
    }
    return $record
}

function Assert-GuardRejects([scriptblock]$Probe, [string]$Name) {
    try { & $Probe; throw "Host guard unexpectedly passed: $Name" }
    catch {
        if ($_.Exception.Message -eq "Host guard unexpectedly passed: $Name") { throw }
        return "rejected: $($_.Exception.Message)"
    }
}

if (Test-Path -LiteralPath $OutputDirectory) { throw "Output directory already exists: $OutputDirectory" }
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
try {
$guardRoot = Join-Path $OutputDirectory 'host-guards'
[IO.Directory]::CreateDirectory($guardRoot) | Out-Null
$absent = Join-Path $guardRoot 'absent.json'
$stale = Join-Path $guardRoot 'stale.json'
@{label='old-label';expectedSha256=('a'*64);actualSha256=('a'*64);testStatus='completed'} |
    ConvertTo-Json | Set-Content -LiteralPath $stale
$hostGuards = [ordered]@{
    missingResult = Assert-GuardRejects { Assert-Result $absent $true 'current-label' ('a'*64) ('a'*64) | Out-Null } 'missing result'
    staleResult = Assert-GuardRejects { Assert-Result $stale $true 'current-label' ('a'*64) ('a'*64) | Out-Null } 'stale result'
    deadline = Assert-GuardRejects { Get-RemainingMilliseconds ([DateTime]::UtcNow.AddSeconds(-1)) | Out-Null } 'expired deadline'
    cleanup = Assert-GuardRejects { Assert-RowSafeToContinue ([pscustomobject]@{label='simulated';cleanupError='simulated failure';healthError=$null}) } 'failed cleanup'
}
$startsBeforeExpiredProbe = $script:adbStarts
$script:rowDeadlineUtc = [DateTime]::UtcNow.AddSeconds(-1)
try {
    $hostGuards.expiredAdbNoStart = Assert-GuardRejects {
        Invoke-Adb @('version') (Join-Path $guardRoot 'expired-adb.log') | Out-Null
    } 'expired adb invocation'
} finally { $script:rowDeadlineUtc = $null }
if ($script:adbStarts -ne $startsBeforeExpiredProbe) {
    throw 'Expired Invoke-Adb attempted to start a process'
}
$hostGuards | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $guardRoot 'results.json')

$identity = Assert-DeviceIdentity
$identity | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutputDirectory 'device.json')
if (!$SkipBuild) {
    $env:GRADLE_USER_HOME = 'C:/Users/yenchi/.gradle'
    & (Join-Path $repo 'gradlew.bat') :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain `
        *> (Join-Path $OutputDirectory 'build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Build failed; inspect build.log' }
}
$appHash = (Get-FileHash -LiteralPath $appApk -Algorithm SHA256).Hash.ToLowerInvariant()
$testHash = (Get-FileHash -LiteralPath $testApk -Algorithm SHA256).Hash.ToLowerInvariant()
$commit = (& git -C $repo rev-parse HEAD | Out-String).Trim()
Invoke-Adb @('install','-r',$appApk) (Join-Path $OutputDirectory 'install-app.log') | Out-Null
Invoke-Adb @('install','-r',$testApk) (Join-Path $OutputDirectory 'install-test.log') | Out-Null

$fixtureGhost = Join-Path $OutputDirectory 'harness-ghost.nar'
$fixtureShell = Join-Path $OutputDirectory 'harness-shell.nar'
New-Fixture $fixtureGhost 'ghost'
New-Fixture $fixtureShell 'shell'
$gateRows = @(
    [pscustomobject]@{label='gate-success';relativePath='synthetic';packageKind='ghost';family='harness';sha256=(Get-FileHash $fixtureGhost -Algorithm SHA256).Hash.ToLowerInvariant()},
    [pscustomobject]@{label='gate-unsupported-kind';relativePath='synthetic';packageKind='shell';family='harness';sha256=(Get-FileHash $fixtureShell -Algorithm SHA256).Hash.ToLowerInvariant()},
    [pscustomobject]@{label='gate-wrong-hash';relativePath='synthetic';packageKind='ghost';family='harness';sha256=(Get-FileHash $fixtureGhost -Algorithm SHA256).Hash.ToLowerInvariant()}
)
$gates = @(
    (Invoke-Row $gateRows[0] $fixtureGhost $true),
    (Invoke-Row $gateRows[1] $fixtureShell $true),
    (Invoke-Row $gateRows[2] $fixtureGhost $false $true)
)
$gates | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'gates.json')
if ($gates[0].outcome -ne 'supported-smoke' -or
    $gates[1].outcome -ne 'expected-rejection' -or $gates[2].outcome -ne 'expected-rejection' -or
    $gates[2].instrumentOneTestOk -or $gates[2].instrumentStatus -ne 'failed') {
    throw 'Harness gates failed; inspect gates.json and host-guards/results.json'
}

foreach ($row in $selected) {
    $source = Join-Path $CorpusRoot $row.relativePath
    if (!(Test-Path -LiteralPath $source)) { throw "Corpus input missing: $source" }
    $records += Invoke-Row $row $source $true
}
$records | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $OutputDirectory 'rows.json')
} catch {
    $runReasons += $_.Exception.Message
    # Quarantined records were written before Invoke-Row stopped the run.
    foreach ($row in $selected) {
        $path=Join-Path $OutputDirectory "$($row.label)/row.json"
        if (Test-Path -LiteralPath $path) {
            if (!@($records | Where-Object label -eq $row.label).Count) { $records += Get-Content -LiteralPath $path -Raw | ConvertFrom-Json }
        }
    }
} finally { $summary=Write-CorpusSummary }
if ($summary.outcome -eq 'failed') { throw "Corpus $Mode failed: $($summary.reasons -join '; '); evidence=$OutputDirectory" }
Write-Host "Corpus $(if ($Mode -eq 'Acceptance') {'acceptance passed'} else {'diagnostic complete (classifications are findings)'}); rows=$($records.Count); evidence=$OutputDirectory"
