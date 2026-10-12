$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../test-device-suite.ps1')
function Assert($condition, $message) { if (!$condition) { throw $message } }
function Reject([scriptblock]$action, $message) {
    $rejected = $false
    try { & $action | Out-Null } catch { $rejected = $true }
    Assert $rejected $message
}
function Transcript([string[]]$ids, [int]$code = 0) {
    (@($ids | ForEach-Object {
        $parts = $_ -split '#'
        "INSTRUMENTATION_STATUS: class=$($parts[0])`nINSTRUMENTATION_STATUS: test=$($parts[1])`nINSTRUMENTATION_STATUS_CODE: $code"
    }) + 'INSTRUMENTATION_CODE: -1') -join "`n"
}
$ids = @('example.Test#alpha','example.Test#beta')
$normal = Read-SuiteTranscript (Transcript @($ids[1],$ids[0])) $ids 0 $false 'out' 'err'
Assert ($normal.outcome -eq 'passed') 'JUnit order must not be selector order'
Assert (($normal.observedMethods -join ',') -eq ($ids[1],$ids[0] -join ',')) 'Retain actual order'
Assert ($normal.observedCount -eq 2 -and $normal.counts.passed -eq 2 -and $normal.counts.skipped -eq 0) 'Exact counts'
foreach ($code in @(-3,-4)) {
    $r = Read-SuiteTranscript (Transcript $ids $code) $ids 0 $false 'out' 'err'
    Assert ($r.outcome -eq 'incomplete' -and $r.counts.skipped -eq 2) 'Selected skip cannot pass'
}
foreach ($case in @(@($ids[0]), @($ids[0],$ids[1],$ids[0]), @($ids[0],$ids[1],'extra.Test#extra'), @())) {
    $r = Read-SuiteTranscript (Transcript $case) $ids 0 $false 'out' 'err'
    Assert ($r.outcome -ne 'passed') 'Missing/duplicate/extra/zero must reject'
}
Assert ((Read-SuiteTranscript (Transcript $ids -2) $ids 0 $false 'out' 'err').outcome -eq 'failed') 'Failure must fail'
Assert ((Read-SuiteTranscript (Transcript $ids) $ids 1 $false 'out' 'err').outcome -ne 'passed') 'Crash must fail'
Assert ((Read-SuiteTranscript (Transcript $ids) $ids 0 $true 'out' 'err').outcome -eq 'incomplete') 'Timeout must stay incomplete'
Assert ((Read-SuiteTranscript ((Transcript $ids) -replace 'INSTRUMENTATION_CODE: -1','') $ids 0 $false 'out' 'err').outcome -ne 'passed') 'Missing terminal result'
Assert ((Read-SuiteTranscript ((Transcript $ids)+"`nINSTRUMENTATION_CODE: -1") $ids 0 $false 'out' 'err').outcome -ne 'passed') 'Duplicate instrumentation terminal'
Assert ((Read-SuiteTranscript 'INSTRUMENTATION_CODE: -1' $ids 0 $false 'out' 'err').counts.incomplete -eq 2) 'Zero methods retain both missing results'
Assert ((Read-SuiteTranscript ((Transcript $ids)+"`nINSTRUMENTATION_RESULT: shortMsg=Process crashed") $ids 0 $false 'out' 'err').outcome -ne 'passed') 'Crash message cannot pass'
Reject { Read-SuiteTranscript (Transcript $ids) @() 0 $false 'out' 'err' } 'Empty selection'
$diagnostic = Read-SuiteTranscript (Transcript $ids) $ids 0 $false 'out' 'err'
$diagnostic.cleanupStatus='passed'
Set-SuiteDiagnosticFailure $diagnostic 'synthetic logcat failure'
Assert ($diagnostic.cleanupStatus -eq 'passed') 'Diagnostic failure must preserve successful cleanup'
Assert ($diagnostic.outcome -eq 'incomplete' -and $diagnostic.counts.incomplete -eq 2 -and $diagnostic.counts.passed -eq 0) 'Lost diagnostics must stay nonpassing with explicit incomplete reasons'
Assert ($diagnostic.results[0].reason -match 'synthetic logcat failure') 'Retain separate diagnostic reason'
$failed = Read-SuiteTranscript (Transcript $ids -2) $ids 0 $false 'out' 'err'
$failed.cleanupStatus='failed'
$primaryReason=$failed.results[0].reason
Set-SuiteDiagnosticFailure $failed 'synthetic diagnostic failure during cleanup failure'
Assert ($failed.outcome -eq 'failed' -and $failed.cleanupStatus -eq 'failed' -and $failed.results[0].reason -eq $primaryReason) 'Diagnostics must retain primary failure and actual failed cleanup'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$manifest = Get-Content (Join-Path $repo 'docs/testing/device-suites.json') -Raw | ConvertFrom-Json
$source = Join-Path $repo 'app/src/androidTest/java'
$inventory = @(Read-DeviceInventory $source $manifest)
Assert ($inventory.Count -gt 0) 'Complete inventory'
Reject { Select-DeviceSuite $inventory 'unknown' } 'Unknown suite'
$copy = Join-Path ([IO.Path]::GetTempPath()) ('nanidroid-suite-check-' + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory $copy | Out-Null
    Copy-Item (Join-Path $source '*') $copy -Recurse
    $probe = Join-Path $copy 'NewTest.kt'
    "package example`nclass NewTest { @Test fun extra() {} }" | Set-Content $probe
    Reject { Read-DeviceInventory $copy $manifest } 'Unclassified method drift'
    'package example; class NewTest { @Test fun `unsupported name`() {} }' | Set-Content $probe
    Reject { Read-DeviceInventory $copy $manifest } 'Unsupported syntax'
    "package example`nimport org.junit.Test as Probe`nclass NewTest { @Probe fun extra() {} }" | Set-Content $probe
    Reject { Read-DeviceInventory $copy $manifest } 'Aliased annotation syntax'
    Remove-Item $probe
    $duplicate = [pscustomobject]@{schemaVersion=1;methods=@($manifest.methods)+@($manifest.methods[0])}
    Reject { Read-DeviceInventory $copy $duplicate } 'Duplicate manifest'
    $missing = [pscustomobject]@{schemaVersion=1;methods=@($manifest.methods | Select-Object -Skip 1)}
    Reject { Read-DeviceInventory $copy $missing } 'Missing manifest entry'
    $stale = [pscustomobject]@{schemaVersion=1;methods=@($manifest.methods)+@([pscustomobject]@{class='example.Gone';method='removed';suite='self-contained';requiredArguments=@();prerequisite='synthetic';reason='synthetic'})}
    Reject { Read-DeviceInventory $copy $stale } 'Unknown stale manifest entry'

    # Exercise the actual execution entry point against synthetic Git metadata.
    # The process sentinel is a positive control, never a real build or adb call.
    $mirror=Join-Path $copy 'runner'
    [IO.Directory]::CreateDirectory((Join-Path $mirror 'tools')) | Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $mirror 'docs/testing')) | Out-Null
    [IO.Directory]::CreateDirectory((Join-Path $mirror 'app/src/androidTest/java')) | Out-Null
    Copy-Item (Join-Path $source '*') (Join-Path $mirror 'app/src/androidTest/java') -Recurse
    Copy-Item (Join-Path $repo 'docs/testing/device-suites.json') (Join-Path $mirror 'docs/testing')
    foreach ($apk in @('app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
        $path=Join-Path $mirror $apk
        [IO.Directory]::CreateDirectory((Split-Path -Parent $path)) | Out-Null
        'synthetic APK bytes: never installed' | Set-Content $path
    }
    $runnerText=Get-Content (Join-Path $PSScriptRoot '../test-device-suite.ps1') -Raw
    $tokens=$null; $errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseInput($runnerText,[ref]$tokens,[ref]$errors)
    Assert (!$errors.Count) 'Runner parse'
    $processFunction=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-SuiteProcess'},$true))
    Assert ($processFunction.Count -eq 1) 'Exact process boundary'
    $extent=$processFunction[0].Extent
    $replacement=@'
function Invoke-SuiteProcess($Executable,$Arguments,$Seconds,$Prefix) {
    [IO.File]::WriteAllText($env:SUITE_PROCESS_SENTINEL,'started')
    if ($env:SUITE_PROBE_STATE -ne 'diagnostic-failure') { throw 'PROCESS START SENTINEL' }
    $name=Split-Path -Leaf $Prefix
    $stdout=switch ($name) {
        'state' {'device'} 'cleanup-state' {'device'} 'qemu' {'1'} 'boot' {'1'} 'api' {'31'} 'abi' {'x86_64'}
        'instrumentation' {
            (@($selected | ForEach-Object {
                $parts=$_ -split '#'
                "INSTRUMENTATION_STATUS: class=$($parts[0])`nINSTRUMENTATION_STATUS: test=$($parts[1])`nINSTRUMENTATION_STATUS_CODE: 0"
            })+'INSTRUMENTATION_CODE: -1') -join "`n"
        }
        'logcat' { throw 'SYNTHETIC LOGCAT FAILURE' }
        default {'Success'}
    }
    $stdout | Set-Content "$Prefix.stdout.log"
    '' | Set-Content "$Prefix.stderr.log"
    return @{stdout=$stdout;stderr='';exitCode=0;timedOut=$false}
}
'@
    $runnerText=$runnerText.Remove($extent.StartOffset,$extent.EndOffset-$extent.StartOffset).Insert($extent.StartOffset,$replacement)
    $mirrorRunner=Join-Path $mirror 'tools/test-device-suite.ps1'
    $runnerText | Set-Content $mirrorRunner
    $probeScript=Join-Path $copy 'provenance-probe.ps1'
    @'
param($Runner,$State,$Sentinel,$Output)
$env:SUITE_PROCESS_SENTINEL=$Sentinel
$env:SUITE_PROBE_STATE=$State
function global:git {
    $global:LASTEXITCODE=0
    if ($args -contains 'status') {
        switch ($State) {
            'modified' { ' M source.kt' }
            'staged' { 'M  source.kt' }
            'untracked' { '?? NewTest.kt' }
            'git-error' { $global:LASTEXITCODE=128 }
        }
    } elseif ($args -contains 'rev-parse') { '0123456789abcdef0123456789abcdef01234567' }
    else { throw 'Unexpected Git call' }
}
$pwshName=if ($IsWindows) {'pwsh.exe'} else {'pwsh'}
& $Runner -Serial emulator-5554 -Adb (Join-Path $PSHOME $pwshName) -OutputDirectory $Output -SkipBuild
exit $LASTEXITCODE
'@ | Set-Content $probeScript
    $pwsh=Join-Path $PSHOME $(if ($IsWindows) {'pwsh.exe'} else {'pwsh'})
    foreach ($state in @('modified','staged','untracked','git-error','clean','diagnostic-failure')) {
        $sentinel=Join-Path $copy "$state-process"
        $output=Join-Path $copy "$state-output"
        & $pwsh -NoProfile -File $probeScript $mirrorRunner $state $sentinel $output *> (Join-Path $copy "$state.log")
        Assert ($LASTEXITCODE -ne 0) "Synthetic $state execution exits nonzero"
        if ($state -in @('clean','diagnostic-failure')) {
            Assert (Test-Path $sentinel) 'Clean provenance must reach process positive control'
            $summary=Get-Content (Join-Path $output 'summary.json') -Raw | ConvertFrom-Json
            Assert ($summary.sourceCommit -eq '0123456789abcdef0123456789abcdef01234567') 'Clean commit identity'
            if ($state -eq 'diagnostic-failure') {
                Assert ($summary.cleanupStatus -eq 'passed' -and $summary.outcome -eq 'incomplete') 'Actual finally must preserve successful cleanup on diagnostic failure'
                Assert ($summary.observedCount -eq $summary.expectedCount -and $summary.counts.incomplete -eq $summary.expectedCount) 'Actual diagnostic failure count contract'
                Assert ($summary.results[0].reason -match 'SYNTHETIC LOGCAT FAILURE') 'Actual diagnostic reason'
                Assert (Test-Path (Join-Path $output 'diagnostic-error.log')) 'Actual diagnostic log'
            }
        } else {
            Assert (!(Test-Path $sentinel)) "Dirty/failed Git $state must reject before build/adb"
            Assert (!(Test-Path $output)) "Dirty/failed Git $state must reject before output creation"
        }
    }
} finally {
    # Only this fresh UUID directory is owned by this invocation.
    if ([IO.Path]::GetFullPath($copy).StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()))) {
        Remove-Item -LiteralPath $copy -Recurse -Force
    }
}
Write-Host "Device suite self-check passed; inventoried $($inventory.Count) methods"
