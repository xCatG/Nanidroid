param([switch]$PolicyOnly)
$ErrorActionPreference = 'Stop'
$pwsh = (Get-Process -Id $PID).Path
. (Join-Path $PSScriptRoot '../test-outcome-policy.ps1')
$script:checks=0
function Check([bool]$Value,[string]$Name) { if (!$Value) { throw "CHECK FAILED: $Name" }; $script:checks++ }
function Reject([scriptblock]$Action,[string]$Name) { $rejected=$false; try { & $Action | Out-Null } catch { $rejected=$true }; Check $rejected $Name }
$available=@('satori','lobo-kill')
foreach ($selection in @(@('unknown'),@('satori','unknown'),@(''),@(' '))) { Reject { Resolve-OutcomeSelection $available $selection $true } "reject selection $selection" }
Reject { Resolve-OutcomeSelection $available @() $true } 'empty array'
Check ((@(Resolve-OutcomeSelection $available @('satori','satori') $true) -join ',') -eq 'satori') 'deduplicate'
Check ((@(Resolve-OutcomeSelection $available @() $false)).Count -eq 2) 'omitted all'
Check ((@(Resolve-OutcomeSelection $available @('lobo-kill') $true)).Count -eq 1) 'valid selection'
Reject { Resolve-OutcomeSelection @('row') @('unknown') $true } 'unknown corpus label'
$hash='a'*64
$row=[pscustomobject]@{label='row';sha256=$hash}
function Expect([string]$Category='supported-smoke') { [pscustomobject]@{schemaVersion=1;rows=@([pscustomobject]@{label='row';sha256=$hash;expectedClassification=$Category;basis='https://example.test/review scope rationale'})} }
function Record([string]$Category='supported-smoke') {
    $device=DeviceResult
    $device | Add-Member label 'row'; $device | Add-Member actualSha256 $hash; $device | Add-Member expectedSha256 $hash
    if ($Category -eq 'partial-unsupported') { $device.firstBootText=''; $device.bootReplayStatus=204 }
    elseif ($Category -eq 'unsupported-engine') { $device.detectedEngineKind='UNSUPPORTED' }
    else { $device.classification=$Category }
    [pscustomobject]@{label='row';archiveSha256=$hash;outcome=$Category;deviceResult=$device;instrumentExited=$true;instrumentExitCode=0;instrumentOneTestOk=$true;instrumentStatus='passed';ended='now';healthAfter=@{serial='synthetic'}}
}
function DeviceResult {
    [pscustomobject]@{classification='supported-smoke';testStatus='completed';importOutcome='Installed';directoryId='owned';displayName='Observed ghost';activeGhost='Observed ghost';activationError=$null;closeState='Finished';detectedEngineKind='YAYA';firstBootText='Authored boot';laterText='';bootReplayStatus=$null;bootReplayValue=$null}
}
$device=DeviceResult
Check ((Get-CorpusClassification $device).classification -eq 'supported-smoke') 'actual authored supported smoke'
$device.detectedEngineKind='UNSUPPORTED'
Check ((Get-CorpusClassification $device).classification -eq 'unsupported-engine') 'actual detected unsupported engine'
$device=DeviceResult; $device.firstBootText=''; $device.bootReplayStatus=204
Check ((Get-CorpusClassification $device).classification -eq 'partial-unsupported') 'actual native silent boot limitation'
$device.bootReplayStatus=200; $device.bootReplayValue='Replayed authored text'
Check ((Get-CorpusClassification $device).classification -eq 'partial-unsupported') 'nonempty replay cannot prove initial activation boot'
Check ((Get-CorpusClassification $device).basis -match 'initial boot dialogue was not observed.*separate diagnostic replay.*value=nonempty') 'nonempty replay limited basis preserves boundary'
$record=Record 'supported-smoke'; $record.deviceResult=$device
$device | Add-Member label 'row'; $device | Add-Member actualSha256 $hash; $device | Add-Member expectedSha256 $hash
$supportedMap=Read-OutcomeExpectations (Expect) @($row) @($row)
Check ((Get-CorpusOutcome @($row) @($record) Acceptance $supportedMap).outcome -eq 'failed') 'nonempty replay cannot satisfy supported expectation'
$device=DeviceResult; $device.firstBootText=''; $device.laterText='Visible later activation dialogue'
Check ((Get-CorpusClassification $device).classification -eq 'supported-smoke') 'actual later activation dialogue supports boot render'
$device=DeviceResult; $device.firstBootText=''; $device.bootReplayStatus=200; $device.bootReplayValue=42
Reject { Get-CorpusClassification $device } 'malformed diagnostic replay cannot establish limited pass'
$device=DeviceResult; $device.classification='in-scope-failure'; $device.detectedEngineKind='UNSUPPORTED'
Check ((Get-CorpusClassification $device).classification -eq 'in-scope-failure') 'hard failure never becomes limited'
foreach ($field in @('testStatus','importOutcome','directoryId','activeGhost','closeState','detectedEngineKind')) {
    $device=DeviceResult; $device.$field=''
    Reject { Get-CorpusClassification $device } "missing actual evidence $field"
}
$device=DeviceResult; $device.activationError='Native load failed: -1'; $device.detectedEngineKind='UNSUPPORTED'
Reject { Get-CorpusClassification $device } 'activation failure cannot become limited'
$device=DeviceResult; $device.classification='unsupported-engine'
Reject { Get-CorpusClassification $device } 'raw limited label alone cannot establish limitation'
$device=DeviceResult; $device.firstBootText=''; $device.bootReplayStatus='no-native-lease'
Reject { Get-CorpusClassification $device } 'no-native-lease cannot establish limited smoke'
foreach ($status in @($null,500,'204')) {
    $device=DeviceResult; $device.firstBootText=''; $device.bootReplayStatus=$status
    Reject { Get-CorpusClassification $device } "missing/failed/non-numeric native replay $status"
}
$record=Record 'unsupported-engine'; $record.deviceResult.activationError='Native load failed: -1'
$limitedMap=Read-OutcomeExpectations (Expect 'unsupported-engine') @($row) @($row)
Check ((Get-CorpusOutcome @($row) @($record) Acceptance $limitedMap).outcome -eq 'failed') 'actual limited error cannot pass expectation'
$record=Record 'partial-unsupported'; $record.deviceResult.closeState='Ready'
$limitedMap=Read-OutcomeExpectations (Expect 'partial-unsupported') @($row) @($row)
Check ((Get-CorpusOutcome @($row) @($record) Acceptance $limitedMap).outcome -eq 'failed') 'actual limited incomplete close cannot pass expectation'
$record=Record 'partial-unsupported'; $record.deviceResult.detectedEngineKind=''
Check ((Get-CorpusOutcome @($row) @($record) Acceptance $limitedMap).outcome -eq 'failed') 'actual limited missing kind cannot pass expectation'
foreach ($category in @('supported-smoke','expected-rejection','partial-unsupported','unsupported-engine')) {
    $map=Read-OutcomeExpectations (Expect $category) @($row) @($row)
    Check ((Get-CorpusOutcome @($row) @((Record $category)) Acceptance $map).outcome -eq 'passed') "exact $category"
}
$map=Read-OutcomeExpectations (Expect) @($row) @($row)
Check ((Get-CorpusOutcome @($row) @((Record 'partial-unsupported')) Acceptance $map).outcome -eq 'failed') 'supported downgrade'
foreach ($category in @('in-scope-failure','native-failure','unverified','unknown')) { Reject { Read-OutcomeExpectations (Expect $category) @($row) @($row) } "prohibited expectation $category" }
Reject { Read-OutcomeExpectations $null @($row) @($row) } 'absent expectations'
$doc=Expect; $doc.rows=@(); Reject { Read-OutcomeExpectations $doc @($row) @($row) } 'incomplete expectations'
$doc=Expect; $doc.rows+= $doc.rows[0]; Reject { Read-OutcomeExpectations $doc @($row) @($row) } 'duplicate expectations'
$doc=Expect; $doc.rows[0].sha256='b'*64; Reject { Read-OutcomeExpectations $doc @($row) @($row) } 'stale expectations'
$doc=Expect; $doc.rows[0].label='unknown'; Reject { Read-OutcomeExpectations $doc @($row) @($row) } 'unknown expectation label'
$doc=Expect; $doc.rows[0].basis=' '; Reject { Read-OutcomeExpectations $doc @($row) @($row) } 'empty basis'
Check ((Get-CorpusOutcome @($row) @() Diagnostic $null).outcome -eq 'failed') 'missing records'
foreach ($property in @('timeout','cleanupError','healthError','hostError')) {
    $record=Record; $record | Add-Member $property 'synthetic failure'
    Check ((Get-CorpusOutcome @($row) @($record) Diagnostic $null).outcome -eq 'failed') "$property fails"
}
$record=Record; $record.archiveSha256='b'*64
Check ((Get-CorpusOutcome @($row) @($record) Diagnostic $null).outcome -eq 'failed') 'stale record'
$record=Record; $record.instrumentExited=$false
Check ((Get-CorpusOutcome @($row) @($record) Diagnostic $null).outcome -eq 'failed') 'incomplete instrumentation'
Check ((Get-CorpusOutcome @($row) @((Record 'unknown')) Diagnostic $null).outcome -eq 'failed') 'unknown actual'
Check ((Get-CorpusOutcome @($row) @((Record 'unverified')) Diagnostic $null).outcome -eq 'failed') 'unverified actual'
Check ((Get-CorpusOutcome @($row) @((Record 'in-scope-failure')) Diagnostic $null).outcome -eq 'diagnostic-complete') 'diagnostic findings'
$text="INSTRUMENTATION_STATUS: class=C`nINSTRUMENTATION_STATUS: test=m`nINSTRUMENTATION_STATUS_CODE: 0`nOK (1 test)"
Check ((Get-InstrumentationOutcome $text 0 C m) -eq 'passed') 'terminal pass'
Check ((Get-InstrumentationOutcome ($text -replace 'CODE: 0','CODE: -4') 0 C m) -eq 'skipped') 'assumption terminal skip'
Check ((Get-InstrumentationOutcome ($text+"`nAssumptionViolatedException") 0 C m) -eq 'skipped') 'assumption text'
Check ((Get-InstrumentationOutcome 'OK (1 test)' 0 C m) -eq 'incomplete') 'OK alone incomplete'
Check ((Get-InstrumentationOutcome $text 1 C m) -eq 'failed') 'transport failure'
Check ((Get-InstrumentationOutcome ($text+"`n"+$text) 0 C m) -eq 'incomplete') 'duplicate terminal'
Assert-NativeMethodCompleteness @('C#m') @(@{methodId='C#m';status='passed'})
Check $true 'native method complete'
Reject { Assert-NativeMethodCompleteness @('C#m') @() } 'missing native method'
Reject { Assert-NativeMethodCompleteness @('C#m') @(@{methodId='C#m';status='skipped'}) } 'selected native skip'
Reject { Assert-NativeMethodCompleteness @('C#m','C#reader') @(@{methodId='C#m';status='passed'}) } 'incomplete writer/read boundary'
Reject { Assert-NativeMethodCompleteness @('C#m') @(@{methodId='C#m';status='passed'},@{methodId='C#m';status='passed'}) } 'duplicate native terminal'
Write-Host "Outcome policy checks passed: $script:checks"
if (!$PolicyOnly) {
    $root=Join-Path ([IO.Path]::GetTempPath()) ('runner-sentinels-'+[guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($root) | Out-Null
    try {
        $sentinel=Join-Path $root 'started.txt'; $fake=Join-Path $root 'adb.cmd'
        # Instrument process/build entry points in temporary copies. A positive
        # control proves the sentinel is reachable; preflight must not reach it.
        $mirrorTools=Join-Path $root 'mirror/tools'
        [IO.Directory]::CreateDirectory((Join-Path $mirrorTools 'tests')) | Out-Null
        [IO.Directory]::CreateDirectory((Join-Path $root 'mirror/docs/testing')) | Out-Null
        Copy-Item (Join-Path $PSScriptRoot '../test-outcome-policy.ps1') $mirrorTools
        Copy-Item $PSCommandPath (Join-Path $mirrorTools 'tests')
        Copy-Item (Join-Path $PSScriptRoot '../../docs/testing/milestone-5-corpus.json') (Join-Path $root 'mirror/docs/testing')
        foreach ($name in @('test-native-persistence.ps1','test-milestone5-corpus.ps1')) {
            $text=Get-Content (Join-Path $PSScriptRoot "../$name") -Raw
            $tokens=$null; $errors=$null
            $ast=[Management.Automation.Language.Parser]::ParseInput($text,[ref]$tokens,[ref]$errors)
            if ($errors.Count) { throw 'Runner parse failed' }
            $entries=@($ast.FindAll({param($node)
                ($node -is [Management.Automation.Language.InvokeMemberExpressionAst] -and $node.Member.Value -eq 'Start') -or
                ($node -is [Management.Automation.Language.CommandAst] -and ($node.GetCommandName() -eq 'Start-Process' -or $node.Extent.Text -match 'gradlew\.bat'))
            },$true) | Sort-Object { $_.Extent.StartOffset } -Descending)
            Check ($entries.Count -ge 3) "process entries for $name"
            foreach ($entry in $entries) {
                $replacement='(Start-ForbiddenProcess)'
                if ($entry -is [Management.Automation.Language.CommandAst]) { $replacement += "`n" }
                $text=$text.Remove($entry.Extent.StartOffset,$entry.Extent.EndOffset-$entry.Extent.StartOffset).Insert($entry.Extent.StartOffset,$replacement)
            }
            Set-Content (Join-Path $mirrorTools $name) $text
        }
        $probe=Join-Path $root 'probe.ps1'
        @'
param($Runner,$Case,$Sentinel,$Output)
$ErrorActionPreference='Stop'
function global:Start-ForbiddenProcess { [IO.File]::WriteAllText($Sentinel,'started'); throw 'PROCESS START SENTINEL' }
switch ($Case) {
 'self' { & $Runner -SelfCheck }
 'mixed' { & $Runner -Only @('satori','unknown') -OutputDirectory $Output }
 'empty' { & $Runner -Only @() -OutputDirectory $Output }
 'duplicate-positive' { & $Runner -Only @('satori','satori') -OutputDirectory $Output }
 'unknown-corpus' { & $Runner -DeviceSerial emulator-5554 -Label unknown -OutputDirectory $Output }
 'missing-map' { & $Runner -DeviceSerial emulator-5554 -Mode Acceptance -OutputDirectory $Output }
}
'@ | Set-Content $probe
        $mirrorNative=Join-Path $mirrorTools 'test-native-persistence.ps1'
        $mirrorCorpus=Join-Path $mirrorTools 'test-milestone5-corpus.ps1'
        foreach ($case in @('self','mixed','empty')) {
            & $pwsh -NoProfile -File $probe $mirrorNative $case $sentinel (Join-Path $root 'probe-output') *> (Join-Path $root 'probe.log')
            if ($case -eq 'self' -and $LASTEXITCODE -ne 0) { Get-Content (Join-Path $root 'probe.log') | Write-Host }
            Check (($LASTEXITCODE -eq 0) -eq ($case -eq 'self')) "sentinel native $case exit"
            Check (!(Test-Path $sentinel)) "sentinel native $case no process"
        }
        foreach ($case in @('self','unknown-corpus','missing-map')) {
            & $pwsh -NoProfile -File $probe $mirrorCorpus $case $sentinel (Join-Path $root 'probe-output') *> (Join-Path $root 'probe.log')
            if ($case -eq 'self' -and $LASTEXITCODE -ne 0) { Get-Content (Join-Path $root 'probe.log') | Write-Host }
            Check (($LASTEXITCODE -eq 0) -eq ($case -eq 'self')) "sentinel corpus $case exit"
            Check (!(Test-Path $sentinel)) "sentinel corpus $case no process"
        }
        & $pwsh -NoProfile -File $probe $mirrorNative duplicate-positive $sentinel (Join-Path $root 'probe-output') *> (Join-Path $root 'positive.log')
        Check ($LASTEXITCODE -ne 0 -and (Test-Path $sentinel)) 'process sentinel positive control'
        $summary=Get-Content (Join-Path $root 'probe-output/summary.json') -Raw | ConvertFrom-Json
        Check ($summary.selectedCount -eq 1 -and $summary.counts.incomplete -eq 1 -and $summary.outcome -eq 'failed') 'early failure summary and duplicate selection'
        Remove-Item -LiteralPath $sentinel
        "@echo started>>`"$sentinel`"`r`n@exit /b 99" | Set-Content $fake
        $native=Join-Path $PSScriptRoot '../test-native-persistence.ps1'
        $corpus=Join-Path $PSScriptRoot '../test-milestone5-corpus.ps1'
        foreach ($runner in @($native,$corpus)) {
            & $pwsh -NoProfile -File $runner -SelfCheck *> (Join-Path $root 'selfcheck.log')
            Check ($LASTEXITCODE -eq 0) "selfcheck $runner"
        }
        foreach ($selection in @('unknown','',' ')) {
            & $pwsh -NoProfile -File $native -Only $selection -Adb $fake -OutputDirectory (Join-Path $root 'native-output') *> (Join-Path $root 'negative.log')
            Check ($LASTEXITCODE -ne 0) 'native preflight nonzero'
        }
        & $pwsh -NoProfile -File $corpus -DeviceSerial emulator-5554 -Label unknown -OutputDirectory (Join-Path $root 'corpus-output') *> (Join-Path $root 'negative.log')
        Check ($LASTEXITCODE -ne 0) 'corpus preflight nonzero'
        & $pwsh -NoProfile -File $corpus -DeviceSerial emulator-5554 -Label '02-2elf-2-46-nar' -Mode Acceptance -OutputDirectory (Join-Path $root 'acceptance-output') *> (Join-Path $root 'acceptance.log')
        Check ($LASTEXITCODE -ne 0) 'missing acceptance map preflight nonzero'
        Check (!(Test-Path (Join-Path $root 'acceptance-output'))) 'acceptance no output side effect'
        $badMap=Join-Path $root 'stale.json'
        @{schemaVersion=1;rows=@(@{label='02-2elf-2-46-nar';sha256=('b'*64);expectedClassification='supported-smoke';basis='synthetic host-only stale map'})} | ConvertTo-Json -Depth 4 | Set-Content $badMap
        & $pwsh -NoProfile -File $corpus -DeviceSerial emulator-5554 -Label '02-2elf-2-46-nar' -Mode Acceptance -ExpectationsPath $badMap -OutputDirectory (Join-Path $root 'acceptance-output') *> (Join-Path $root 'acceptance.log')
        Check ($LASTEXITCODE -ne 0) 'stale acceptance map preflight nonzero'
        Check (!(Test-Path (Join-Path $root 'acceptance-output'))) 'stale acceptance no output side effect'
        Check (!(Test-Path $sentinel)) 'no adb process started'
        Check (!(Test-Path (Join-Path $root 'native-output'))) 'native no output side effect'
        Check (!(Test-Path (Join-Path $root 'corpus-output'))) 'corpus no output side effect'
        Write-Host "Runner preflight/selfcheck sentinels passed; total host assertions=$script:checks"
    } finally { Remove-Item -LiteralPath $root -Recurse -Force }
}

