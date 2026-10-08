# Pure host policy. No process, fixture, output or device operations.
function Resolve-OutcomeSelection([string[]]$Available, [string[]]$Requested, [bool]$Explicit) {
    if (!$Explicit) { return @($Available) }
    if (!$Requested -or @($Requested | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count) { throw 'Selection must be nonempty with no blank names' }
    foreach ($name in $Requested) { if ($name -cnotin $Available) { throw "Unknown selection: $name" } }
    return @($Available | Where-Object { $_ -cin $Requested })
}
function Read-OutcomeExpectations($Document, $ManifestRows, $SelectedRows) {
    if (!$Document -or ($Document.schemaVersion -isnot [long] -and $Document.schemaVersion -isnot [int]) -or $Document.schemaVersion -ne 1 -or !$Document.rows -or $Document.rows -isnot [array]) { throw 'Invalid expectation schemaVersion/rows' }
    $map = @{}
    foreach ($row in $Document.rows) {
        if ([string]::IsNullOrWhiteSpace($row.label) -or $map.ContainsKey($row.label)) { throw 'Blank or duplicate expectation label' }
        $manifest = @($ManifestRows | Where-Object { $_.label -ceq $row.label })
        if ($manifest.Count -ne 1) { throw "Unknown expectation label: $($row.label)" }
        if ($row.sha256 -cnotmatch '^[0-9a-f]{64}$' -or $row.sha256 -cne $manifest[0].sha256) { throw "Stale expectation SHA: $($row.label)" }
        if ($row.expectedClassification -cnotin @('supported-smoke','expected-rejection','partial-unsupported','unsupported-engine')) { throw "Prohibited expected classification: $($row.expectedClassification)" }
        if ([string]::IsNullOrWhiteSpace($row.basis)) { throw "Missing reviewed basis: $($row.label)" }
        $map[$row.label] = $row
    }
    foreach ($row in $SelectedRows) { if (!$map.ContainsKey($row.label)) { throw "Missing expectation: $($row.label)" } }
    return $map
}
function Get-InstrumentationOutcome([string]$Text, [int]$ExitCode, [string]$Class, [string]$Method) {
    $events = @(); $bundle = @{}
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -match '^INSTRUMENTATION_STATUS: ([^=]+)=(.*)$') { $bundle[$Matches[1]] = $Matches[2] }
        if ($line -match '^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$') {
            $events += [pscustomobject]@{class=$bundle['class'];method=$bundle['test'];code=[int]$Matches[1]}; $bundle=@{}
        }
    }
    $terminal = @($events | Where-Object { $_.code -ne 1 })
    $target = @($terminal | Where-Object { $_.class -ceq $Class -and $_.method -ceq $Method })
    if (@($target | Where-Object { $_.code -in @(-3,-4) }).Count -or $Text -match '(?i)assumption|AssumptionViolated|\bignored\b') { return 'skipped' }
    if (@($target | Where-Object { $_.code -in @(-1,-2) }).Count -or $ExitCode -ne 0 -or $Text -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed') { return 'failed' }
    if ($target.Count -ne 1 -or $terminal.Count -ne 1 -or $target[0].code -ne 0 -or $Text -notmatch 'OK \(1 test\)') { return 'incomplete' }
    return 'passed'
}
function Assert-NativeMethodCompleteness([string[]]$Expected, $Observed) {
    if (!$Expected.Count -or $Observed.Count -ne $Expected.Count) { throw 'Missing/extra native instrumentation methods' }
    foreach ($method in $Expected) {
        $matches=@($Observed | Where-Object { $_.methodId -ceq $method })
        if ($matches.Count -ne 1 -or $matches[0].status -ne 'passed') { throw "Native method did not pass exactly once: $method" }
    }
}
function Get-CorpusClassification($Result) {
    # Preserve the device's failure/rejection categories; expectations are not input.
    if ($Result.classification -cnotin @('supported-smoke','partial-unsupported','unsupported-engine')) {
        return @{classification=$Result.classification;basis='Device classification retained without refinement'}
    }
    if ($Result.testStatus -cne 'completed' -or $Result.importOutcome -cne 'Installed' -or
        [string]::IsNullOrWhiteSpace($Result.directoryId) -or [string]::IsNullOrWhiteSpace($Result.displayName) -or
        $Result.activeGhost -cne $Result.displayName -or $Result.activationError -or $Result.closeState -cne 'Finished' -or
        $Result.detectedEngineKind -cnotin @('BUILTIN','SATORI','KAWARI','YAYA','UNSUPPORTED') -or
        $Result.firstBootText -isnot [string] -or $Result.laterText -isnot [string]) {
        throw 'Incomplete/error/own-stage evidence cannot establish corpus smoke or a limited classification'
    }
    if ($Result.detectedEngineKind -ceq 'UNSUPPORTED') {
        if ($Result.classification -ceq 'partial-unsupported') { throw 'Recorded limited classification contradicts detected engine' }
        return @{classification='unsupported-engine';basis='Current EngineSelector detected UNSUPPORTED; installed own stage rendered and closed without activation error'}
    }
    if ($Result.detectedEngineKind -cin @('SATORI','KAWARI','YAYA') -and
        $Result.firstBootText.Length -eq 0 -and $Result.laterText.Length -eq 0) {
        if (($Result.bootReplayStatus -isnot [int] -and $Result.bootReplayStatus -isnot [long]) -or
            $Result.bootReplayStatus -notin @(200,204) -or
            ($null -ne $Result.bootReplayValue -and $Result.bootReplayValue -isnot [string])) {
            throw 'Silent native stage lacks a successful real-lease boot replay result'
        }
        if ($Result.classification -ceq 'unsupported-engine') { throw 'Recorded unsupported-engine classification contradicts native evidence' }
        $replayValue = if ([string]::IsNullOrEmpty($Result.bootReplayValue)) { 'empty' } else { 'nonempty' }
        return @{classification='partial-unsupported';basis="Known native engine rendered/closed own stage, but initial boot dialogue was not observed in first/later stage text; separate diagnostic replay status=$($Result.bootReplayStatus), value=$replayValue does not establish earlier activation rendering (intentional initial silence is possible)"}
    }
    if ($Result.classification -cne 'supported-smoke') { throw 'Recorded limited classification lacks matching actual limitation evidence' }
    return @{classification='supported-smoke';basis='Installed own-stage render/close completed without activation error; no recorded engine/boot-dialogue limitation'}
}
function Get-CorpusOutcome($SelectedRows, $Records, [string]$Mode, $Expectations) {
    $categories = @('supported-smoke','partial-unsupported','expected-rejection','unsupported-engine','in-scope-failure','native-failure','unverified')
    $counts = [ordered]@{}; foreach ($category in $categories) { $counts[$category]=0 }
    $reasons = @(); $rows = @(); $completed=0
    if (!$SelectedRows.Count) { $reasons += 'Zero selection' }
    foreach ($selected in $SelectedRows) {
        $found = @($Records | Where-Object { $_.label -ceq $selected.label })
        $actual = if ($found.Count -eq 1) { $found[0].outcome } else { 'unverified' }
        $expected = if ($Expectations -and $Expectations.ContainsKey($selected.label)) { $Expectations[$selected.label].expectedClassification } else { $null }
        $rows += [ordered]@{label=$selected.label;sha256=$selected.sha256;expectedClassification=$expected;actualClassification=$actual}
        if ($actual -cin $categories) { $counts[$actual]++ } else { $reasons += "Unknown classification: $($selected.label)" }
        if ($found.Count -ne 1) { $reasons += "Missing/duplicate record: $($selected.label)"; continue }
        $record=$found[0]
        try { $classification=Get-CorpusClassification $record.deviceResult }
        catch { $reasons += "Invalid classification evidence: $($selected.label): $($_.Exception.Message)"; continue }
        $rows[-1].classificationBasis=$classification.basis
        if ($record.archiveSha256 -cne $selected.sha256 -or !$record.deviceResult -or $record.deviceResult.label -cne $selected.label -or
            $record.deviceResult.actualSha256 -cne $selected.sha256 -or $record.deviceResult.expectedSha256 -cne $selected.sha256 -or
            $classification.classification -cne $actual -or $record.deviceResult.testStatus -ne 'completed' -or !$record.instrumentExited -or $record.instrumentExitCode -ne 0 -or !$record.instrumentOneTestOk -or
            $record.instrumentStatus -ne 'passed' -or !$record.ended -or !$record.healthAfter -or $record.hostError -or $record.timeout -or $record.cleanupError -or $record.healthError -or $actual -ceq 'unverified') {
            $reasons += "Untrustworthy/incomplete evidence: $($selected.label)"; continue
        }
        $completed++
        if ($Mode -eq 'Acceptance' -and (!$expected -or $actual -cne $expected -or $actual -cin @('in-scope-failure','native-failure','unverified'))) { $reasons += "Expectation mismatch: $($selected.label) expected=$expected actual=$actual" }
    }
    if ($Records.Count -ne $SelectedRows.Count) { $reasons += 'Record count mismatch' }
    return [ordered]@{selectedCount=$SelectedRows.Count;completedCount=$completed;classificationCounts=$counts;rows=$rows;outcome=$(if ($reasons.Count) {'failed'} elseif ($Mode -eq 'Acceptance') {'passed'} else {'diagnostic-complete'});reasons=@($reasons)}
}
