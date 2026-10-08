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
} finally {
    # Only this fresh UUID directory is owned by this invocation.
    if ([IO.Path]::GetFullPath($copy).StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()))) {
        Remove-Item -LiteralPath $copy -Recurse -Force
    }
}
Write-Host "Device suite self-check passed; inventoried $($inventory.Count) methods"
