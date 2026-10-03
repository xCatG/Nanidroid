param(
    [string]$Adb = 'C:/Users/yenchi/AppData/Local/Android/Sdk/platform-tools/adb.exe',
    [ValidateSet('emulator-5554')][string]$Serial = 'emulator-5554',
    [string]$OutputDirectory = (Join-Path $env:TEMP ('nanidroid-persistence-' + [guid]::NewGuid().ToString('N'))),
    [switch]$SkipBuild,
    [string[]]$Only
)
$ErrorActionPreference = 'Stop'
$package = 'com.cattailsw.nanidroid'
$runner = "$package.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = "$package.engine.NativePersistenceTest"
$sources = @(
    @{ name='satori'; archive='C:/tmp/Nanidroid-corpus-recovery/2elf/2elf-2.46.nar'; hash='a50830e18def75be051a3638c7375c7e2d96cb18f7b3f26d0037d84a0fc20be0'; method='satoriOrderlyUnloadRestoresChangedFlag' },
    @{ name='satori-rotation'; archive='C:/tmp/Nanidroid-corpus-recovery/2elf/2elf-2.46.nar'; hash='a50830e18def75be051a3638c7375c7e2d96cb18f7b3f26d0037d84a0fc20be0'; method='twoelfKeepsFlagAndLeaseAcrossRecreationAndDisplayRotation' },
    @{ name='satori-runtime'; archive='C:/tmp/Nanidroid-corpus-recovery/2elf/2elf-2.46.nar'; hash='a50830e18def75be051a3638c7375c7e2d96cb18f7b3f26d0037d84a0fc20be0'; method='twoelfRuntimeSwitchBackAndCloseWritesFlag' },
    @{ name='lobo-minute'; archive='C:/tmp/Nanidroid-corpus-recovery/pcPets/Ukagakas/LOBO/LOBO_1.0.0.nar'; hash='f4e90615cf40801d4a7a7170762b6c0d6dddf18324f9ba146f4a700cbe2bebf7'; method='loboMinuteSaveRestoresTalkInterval' },
    @{ name='lobo-destroy'; archive='C:/tmp/Nanidroid-corpus-recovery/pcPets/Ukagakas/LOBO/LOBO_1.0.0.nar'; hash='f4e90615cf40801d4a7a7170762b6c0d6dddf18324f9ba146f4a700cbe2bebf7'; method='loboDestroyWritesNestedProfile' },
    @{ name='lobo-runtime'; archive='C:/tmp/Nanidroid-corpus-recovery/pcPets/Ukagakas/LOBO/LOBO_1.0.0.nar'; hash='f4e90615cf40801d4a7a7170762b6c0d6dddf18324f9ba146f4a700cbe2bebf7'; method='loboRuntimeSwitchBackAndCloseWritesInterval' },
    @{ name='yaya'; archive='C:/tmp/Nanidroid-corpus-recovery/pcPets/Ukagakas/Earthquake Rescue Duo/Earthquake_duo_1.0.1.nar'; hash='06db71e7e8293b4af0b5127dd73402d4ed90fecc5fdcebf4f0d34337ccb66538'; method='yayaOrderlyUnloadRestoresChangedName' },
    @{ name='yaya-runtime'; archive='C:/tmp/Nanidroid-corpus-recovery/pcPets/Ukagakas/Earthquake Rescue Duo/Earthquake_duo_1.0.1.nar'; hash='06db71e7e8293b4af0b5127dd73402d4ed90fecc5fdcebf4f0d34337ccb66538'; method='yayaRuntimeSwitchBackAndCloseWritesName' },
    @{ name='lobo-kill'; archive='C:/tmp/Nanidroid-corpus-recovery/pcPets/Ukagakas/LOBO/LOBO_1.0.0.nar'; hash='f4e90615cf40801d4a7a7170762b6c0d6dddf18324f9ba146f4a700cbe2bebf7'; method=$null }
)
if ($Only) { $sources = @($sources | Where-Object { $_.name -in $Only }) }

function Invoke-BoundedAdb([string[]]$Arguments, [int]$TimeoutSeconds) {
    $quoted = @('-s', $Serial) + @($Arguments | ForEach-Object {
        if ($_ -match '\s') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ }
    })
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $Adb
    $start.Arguments = $quoted -join ' '
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    try {
        if (!$process.Start()) { throw "Could not start adb -s $Serial $($Arguments -join ' ')" }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill()
            throw "adb -s $Serial $($Arguments -join ' ') exceeded $TimeoutSeconds seconds"
        }
        return @{ ExitCode = $process.ExitCode; Stdout = $stdout.Result; Stderr = $stderr.Result }
    } finally {
        $process.Dispose()
    }
}

function Invoke-Adb([string[]]$Arguments, [int]$TimeoutSeconds = 30, [switch]$AllowFailure) {
    $result = Invoke-BoundedAdb -Arguments $Arguments -TimeoutSeconds $TimeoutSeconds
    $output = @(($result.Stdout + $result.Stderr) -split "`r?`n" | Where-Object { $_ -ne '' })
    if ($result.ExitCode -ne 0 -and !$AllowFailure) {
        throw "adb -s $Serial $($Arguments -join ' ') failed (exit $($result.ExitCode)): $output"
    }
    return $output
}

function Assert-DeviceHealth([string]$LogName) {
    $state = (Invoke-Adb -Arguments @('get-state') -TimeoutSeconds 15 | Out-String).Trim()
    $state | Out-File (Join-Path $OutputDirectory $LogName)
    if ($state -ne 'device') { throw "Unexpected $Serial state: '$state'" }
}

function Stage-Fixture($source, [string]$runId) {
    if (!(Test-Path -LiteralPath $source.archive)) { throw "Archive missing: $($source.archive)" }
    $actual = (Get-FileHash -LiteralPath $source.archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $source.hash) { throw "Archive hash mismatch: $($source.archive): $actual" }
    $extract = Join-Path $OutputDirectory "extract-$runId"
    [System.IO.Directory]::CreateDirectory($extract) | Out-Null
    [System.IO.Compression.ZipFile]::ExtractToDirectory($source.archive, $extract)
    $master = Get-ChildItem -LiteralPath $extract -Directory -Recurse | Where-Object {
        $_.Name -eq 'master' -and $_.Parent.Name -eq 'ghost' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'descript.txt'))
    } | Select-Object -First 1
    if (!$master) { throw "No ghost/master in $($source.archive)" }
    $root = $master.Parent.Parent.FullName
    if (!(Test-Path -LiteralPath (Join-Path $root 'shell/master'))) { throw "No shell/master in $root" }
    $fixtureId = "persist-$runId"
    $remote = "/data/local/tmp/$fixtureId"
    $remoteArchive = "$remote.nar"
    Invoke-Adb -Arguments @('push', $source.archive, $remoteArchive) -TimeoutSeconds 180 | Out-File -FilePath (Join-Path $OutputDirectory "$runId-archive-push.log")
    $deviceHashLine = Invoke-Adb -Arguments @('shell','sha256sum',$remoteArchive) | Out-String
    $deviceHash = ($deviceHashLine -split '\s+')[0].ToLowerInvariant()
    "$($source.archive) host=$actual device=$deviceHash path=$remoteArchive" | Out-File (Join-Path $OutputDirectory "$runId-nar-hashes.log")
    if ($deviceHash -ne $actual) { throw "Device archive hash mismatch: $deviceHash expected $actual" }
    Invoke-Adb -Arguments @('push', $root, $remote) -TimeoutSeconds 180 | Out-File -FilePath (Join-Path $OutputDirectory "$runId-push.log")
    Invoke-Adb -Arguments @('shell','run-as',$package,'mkdir','-p','files/ghost') | Out-Null
    Invoke-Adb -Arguments @('shell','run-as',$package,'cp','-r',$remote,"files/ghost/$fixtureId") | Out-Null
    $path = "files/ghost/$fixtureId/ghost/master"
    Invoke-Adb -Arguments @('shell','run-as',$package,'ls','-ld',$path) | Out-File -FilePath (Join-Path $OutputDirectory "$runId-device-path.log")
    Write-Host "Staged $($source.name): $path from SHA-256 $actual"
    return $fixtureId
}

function Run-Instrumentation([string]$fixtureId, [string]$method, [string]$runId, [string]$logName, [string]$className = $testClass) {
    $stdout = Join-Path $OutputDirectory $logName
    $arguments = @('shell','am','instrument','-w','-e','fixtureId',$fixtureId,'-e','runId',$runId,'-e','class',"$className#$method",$runner)
    $result = Invoke-BoundedAdb -Arguments $arguments -TimeoutSeconds 180
    $result.Stdout | Set-Content -LiteralPath $stdout -Encoding utf8
    $result.Stderr | Set-Content -LiteralPath "$stdout.stderr" -Encoding utf8
    if ($result.ExitCode -ne 0 -or !($result.Stdout -match 'OK \(1 test\)')) {
        throw "Instrumentation $method failed; inspect $logName (exit $($result.ExitCode)): $($result.Stdout) $($result.Stderr)"
    }
}

function Run-KillScenario([string]$fixtureId, [string]$runId) {
    $stdout = Join-Path $OutputDirectory "$runId-write.stdout.log"
    $stderr = Join-Path $OutputDirectory "$runId-write.stderr.log"
    $scenarioLog = Join-Path $OutputDirectory "$runId-host-kill.log"
    function Write-KillTrace([string]$message) {
        $entry = "$(Get-Date -Format o) $message"
        Add-Content -LiteralPath $scenarioLog -Value $entry
        Write-Host $entry
    }
    $args = @('-s',$Serial,'shell','am','instrument','-w','-e','fixtureId',$fixtureId,'-e','runId',$runId,'-e','class',"$testClass#abruptWriteWaitsForHostKill",$runner)
    $process = Start-Process -FilePath $Adb -ArgumentList $args -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    Write-KillTrace "write-start runId=$runId fixtureId=$fixtureId adbPid=$($process.Id) readinessDeadlineSeconds=45 exitDeadlineSeconds=15"
    try {
    $markerPath = "files/native-persistence-$runId.ready"
    $deadline = (Get-Date).AddSeconds(45)
    $marker = $null
    while ((Get-Date) -lt $deadline) {
        if ($process.HasExited) {
            Write-KillTrace "invalid early-writer-exit code=$($process.ExitCode)"
            throw "Write instrumentation ended before readiness; inspect $stdout and $stderr"
        }
        $raw = Invoke-Adb -Arguments @('shell','run-as',$package,'cat',$markerPath) -TimeoutSeconds 5 -AllowFailure
        if ($raw -match "runId=$runId") { $marker = $raw; break }
        Start-Sleep -Milliseconds 250
    }
    if (!$marker) {
        Write-KillTrace 'invalid readiness-timeout'
        throw "Readiness timeout; write scenario invalid. Inspect $stdout and $stderr"
    }
    $marker | Out-File -FilePath (Join-Path $OutputDirectory "$runId-marker.log")
    $values = @{}
    foreach ($line in $marker) { if ($line -match '^([^=]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] } }
    Write-KillTrace "marker-observed runId=$($values['runId']) targetPid=$($values['pid']) value=$($values['value']) sha256=$($values['sha256']) unloads=$($values['unloads']) destroys=$($values['destroys'])"
    if ($values['runId'] -ne $runId -or $values['value'] -ne '60' -or $values['sha256'] -notmatch '^[0-9a-f]{64}$' -or
        $values['unloads'] -ne '0' -or $values['destroys'] -ne '0') { throw "Invalid readiness marker: $marker" }
    $pidBefore = (Invoke-Adb -Arguments @('shell','pidof',$package) | Out-String).Trim()
    $writerLive = !$process.HasExited
    Write-KillTrace "pre-stop markerPid=$($values['pid']) pidof=$pidBefore writerLive=$writerLive savedSha256=$($values['sha256'])"
    if ($pidBefore -ne $values['pid'] -or !$writerLive) { throw "Target PID mismatch or writer exited: marker=$($values['pid']) current=$pidBefore" }
    Write-KillTrace "force-stop-issued package=$package targetPid=$pidBefore"
    Invoke-Adb -Arguments @('shell','am','force-stop',$package) | Out-Null
    $exitDeadline = (Get-Date).AddSeconds(15)
    do {
        $pidAfter = (Invoke-Adb -Arguments @('shell','pidof',$package) -TimeoutSeconds 5 -AllowFailure | Out-String).Trim()
        if ($pidAfter -ne $pidBefore) { break }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $exitDeadline)
    Write-KillTrace "post-stop pidof=$(if ($pidAfter) { $pidAfter } else { '<none>' }) originalPid=$pidBefore exited=$($pidAfter -ne $pidBefore)"
    if ($pidAfter -eq $pidBefore) { throw "Original PID $pidBefore did not exit; kill scenario invalid" }
    $writerExited = $process.WaitForExit(15000)
    Write-KillTrace "writer-exit observed=$writerExited exitCode=$(if ($writerExited) { $process.ExitCode } else { '<running>' })"
    if (!$writerExited) { throw "Writer adb did not terminate after force-stop; kill scenario invalid" }
    Run-Instrumentation $fixtureId 'abruptReadUsesFreshProcessAndSavedBytes' $runId "$runId-read.log"
    Write-KillTrace "fresh-read-pass log=$runId-read.log"
    } catch {
        Write-KillTrace "invalid scenario reason=$($_.Exception.Message)"
        # A failed readiness/PID/exit check is never a pass; release a still-live test gate.
        if (!$process.HasExited) {
            Invoke-Adb -Arguments @('shell','am','force-stop',$package) -TimeoutSeconds 15 | Out-File (Join-Path $OutputDirectory "$runId-failure-cleanup.log")
            Write-KillTrace "failure-cleanup force-stop-issued package=$package"
        }
        throw
    }
}

function Capture-SaveMarker([string]$runId, [string]$kind) {
    $markerPath = "files/native-persistence-$runId-$kind.saved"
    $marker = Invoke-Adb -Arguments @('shell','run-as',$package,'cat',$markerPath)
    $marker | Out-File -FilePath (Join-Path $OutputDirectory "$runId-runtime-save-marker.log")
    if (!($marker -match "runId=$runId") -or !($marker -match "kind=$kind") -or
        !($marker -match 'switchHash=[0-9a-f]{64}') -or !($marker -match 'backHash=[0-9a-f]{64}')) {
        throw "Runtime save marker invalid: $markerPath"
    }
}

[System.IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
"Started $(Get-Date -Format o); output=$OutputDirectory" | Out-File (Join-Path $OutputDirectory 'run.log')
"serial=$Serial commit=$(git rev-parse HEAD)" | Add-Content (Join-Path $OutputDirectory 'run.log')
Assert-DeviceHealth 'device-state.log'
if (!$SkipBuild) {
    & "$PSScriptRoot/../gradlew.bat" :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain *> (Join-Path $OutputDirectory 'build.log')
    if ($LASTEXITCODE -ne 0) { throw 'APK build failed; inspect build.log' }
}
Invoke-Adb -Arguments @('install','-r',"$PSScriptRoot/../app/build/outputs/apk/debug/app-debug.apk") -TimeoutSeconds 180 | Out-Null
Invoke-Adb -Arguments @('install','-r',"$PSScriptRoot/../app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk") -TimeoutSeconds 180 | Out-Null
Invoke-Adb -Arguments @('shell','getprop','ro.build.version.sdk') | Out-File (Join-Path $OutputDirectory 'device-api.log')
Invoke-Adb -Arguments @('shell','getprop','ro.product.cpu.abi') | Out-File (Join-Path $OutputDirectory 'device-abi.log')
Get-FileHash "$PSScriptRoot/../app/build/outputs/apk/debug/app-debug.apk","$PSScriptRoot/../app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" -Algorithm SHA256 |
    ForEach-Object { "$($_.Path) $($_.Hash)" } | Out-File (Join-Path $OutputDirectory 'apk-sha256.log')
foreach ($source in $sources) {
    $runId = [guid]::NewGuid().ToString('N')
    $fixtureId = $null
    try {
    $fixtureId = Stage-Fixture $source $runId
    if ($source.name -eq 'lobo-kill') { Run-KillScenario $fixtureId $runId }
    elseif ($source.name -eq 'satori-rotation') {
        Run-Instrumentation $fixtureId $source.method $runId "$runId-$($source.name).log" "$package.ui.NativeRotationTest"
    } elseif ($source.name -eq 'satori-runtime') {
        Run-Instrumentation $fixtureId $source.method $runId "$runId-runtime-write.log" "$package.ui.NativeRotationTest"
        Capture-SaveMarker $runId 'satori'
        Invoke-Adb -Arguments @('shell','am','force-stop',$package) | Out-Null
        Run-Instrumentation $fixtureId 'twoelfReadsAfterRuntimeBackInFreshProcess' $runId "$runId-runtime-read.log" "$package.ui.NativeRotationTest"
    } elseif ($source.name -eq 'lobo-runtime' -or $source.name -eq 'yaya-runtime') {
        Run-Instrumentation $fixtureId $source.method $runId "$runId-runtime-write.log" "$package.ui.NativeRotationTest"
        Capture-SaveMarker $runId $(if ($source.name -eq 'lobo-runtime') { 'lobo' } else { 'yaya' })
        Invoke-Adb -Arguments @('shell','am','force-stop',$package) | Out-Null
        $reader = if ($source.name -eq 'lobo-runtime') { 'loboReadsAfterRuntimeBackInFreshProcess' } else { 'yayaReadsAfterRuntimeBackInFreshProcess' }
        Run-Instrumentation $fixtureId $reader $runId "$runId-runtime-read.log" "$package.ui.NativeRotationTest"
    } else { Run-Instrumentation $fixtureId $source.method $runId "$runId-$($source.name).log" }
    } finally {
        Invoke-Adb -Arguments @('shell','am','force-stop',$package) -TimeoutSeconds 15 | Out-Null
        $cleanupId = "persist-$runId"
        Invoke-Adb -Arguments @('shell','run-as',$package,'rm','-rf',"files/ghost/$cleanupId") -TimeoutSeconds 15 | Out-Null
        Invoke-Adb -Arguments @('shell','rm','-rf',"/data/local/tmp/$cleanupId") -TimeoutSeconds 15 | Out-Null
        Invoke-Adb -Arguments @('shell','rm','-f',"/data/local/tmp/$cleanupId.nar") -TimeoutSeconds 15 | Out-Null
        Assert-DeviceHealth "$runId-health.log"
        "$(Get-Date -Format o) CLEANUP $($source.name) runId=$runId" | Add-Content (Join-Path $OutputDirectory 'run.log')
    }
    "$(Get-Date -Format o) PASS $($source.name) runId=$runId fixtureId=$fixtureId source=$($source.archive) sha256=$($source.hash)" | Add-Content (Join-Path $OutputDirectory 'run.log')
}
Invoke-Adb -Arguments @('logcat','-d','-s','System.out:I') -TimeoutSeconds 30 | Select-String 'PERSIST ' |
    ForEach-Object { $_.Line } | Set-Content -Encoding utf8 (Join-Path $OutputDirectory 'fixture-logcat.log')
Write-Host "Native persistence host tests passed; raw logs: $OutputDirectory"
