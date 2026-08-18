[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('BaseBase', 'BaseCandidate')][string]$ComparisonKind,
    [Parameter(Mandatory)][string]$BaseRoot,
    [Parameter(Mandatory)][string]$CandidateRoot,
    [Parameter(Mandatory)][string]$ManifestPath,
    [Parameter(Mandatory)][string]$ContractPath,
    [Parameter(Mandatory)][string]$BaseProductionCommit,
    [Parameter(Mandatory)][string]$BaseDebugApkSha256,
    [Parameter(Mandatory)][string]$CandidateProductionCommit,
    [Parameter(Mandatory)][string]$CandidateDebugApkSha256,
    [Parameter(Mandatory)][string]$HarnessCommit,
    [Parameter(Mandatory)][string]$HarnessTree,
    [Parameter(Mandatory)][string]$HarnessRunnerSha256,
    [Parameter(Mandatory)][string]$HarnessInstrumentationSourceSha256,
    [Parameter(Mandatory)][string]$HarnessTestApkSha256,
    [string]$BaseBaseReportPath,
    [string]$OutputPath = 'nar-corpus-comparison.json'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Read-JsonFile([string]$Path, [string]$Description) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Description does not exist: $Path"
    }
    try {
        return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    }
    catch {
        throw "$Description is not valid JSON: $Path ($($_.Exception.Message))"
    }
}

function Get-RequiredProperty([object]$Object, [string]$Name, [string]$Context) {
    if ($null -eq $Object -or $null -eq $Object.PSObject.Properties[$Name]) {
        throw "$Context is missing required property '$Name'"
    }
    return $Object.$Name
}

function Assert-EqualString([object]$Actual, [string]$Expected, [string]$Context) {
    if ([string]$Actual -cne $Expected) {
        throw "$Context mismatch: expected '$Expected', found '$Actual'"
    }
}

function ConvertTo-SafeLabel([string]$Label) {
    $safe = ($Label -replace '[^A-Za-z0-9._-]', '-').Trim('-')
    if ([string]::IsNullOrEmpty($safe)) { return 'archive' }
    return $safe
}

function Get-Sha256([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-StringSha256([string]$Value) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([Convert]::ToHexString($algorithm.ComputeHash($bytes))).ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
    }
}

function Get-RelativeFileSet([string]$Root, [string]$Filter) {
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) { return @() }
    return @(
        Get-ChildItem -LiteralPath $Root -Recurse -File -Filter $Filter |
            ForEach-Object { [IO.Path]::GetRelativePath($Root, $_.FullName).Replace('\', '/') } |
            Sort-Object -CaseSensitive
    )
}

function Assert-ExactSet([string[]]$Actual, [string[]]$Expected, [string]$Context) {
    $actualText = @($Actual | Sort-Object -CaseSensitive) -join "`n"
    $expectedText = @($Expected | Sort-Object -CaseSensitive) -join "`n"
    if ($actualText -cne $expectedText) {
        throw "$Context mismatch. Expected [$($Expected -join ', ')], found [$($Actual -join ', ')]"
    }
}

function Get-ObjectKind([object]$Value) {
    if ($null -eq $Value) { return 'null' }
    if ($Value -is [string]) { return 'string' }
    if ($Value -is [bool]) { return 'boolean' }
    if ($Value -is [System.Collections.IList]) { return 'array' }
    if ($Value -is [ValueType]) { return $Value.GetType().FullName }
    return 'object'
}

function Find-FirstDifference([object]$Left, [object]$Right, [string]$Path = '$') {
    $leftKind = Get-ObjectKind $Left
    $rightKind = Get-ObjectKind $Right
    if ($leftKind -cne $rightKind) { return "$Path type ($leftKind != $rightKind)" }
    if ($leftKind -eq 'null') { return $null }
    if ($leftKind -eq 'array') {
        if ($Left.Count -ne $Right.Count) { return "$Path length ($($Left.Count) != $($Right.Count))" }
        for ($index = 0; $index -lt $Left.Count; $index++) {
            $difference = Find-FirstDifference $Left[$index] $Right[$index] "$Path[$index]"
            if ($difference) { return $difference }
        }
        return $null
    }
    if ($leftKind -eq 'object') {
        $leftNames = @($Left.PSObject.Properties.Name | Sort-Object -CaseSensitive)
        $rightNames = @($Right.PSObject.Properties.Name | Sort-Object -CaseSensitive)
        $missingOnRight = @($leftNames | Where-Object { $rightNames -cnotcontains $_ })
        if ($missingOnRight.Count -ne 0) { return "$Path.$($missingOnRight[0]) (missing on candidate)" }
        $missingOnLeft = @($rightNames | Where-Object { $leftNames -cnotcontains $_ })
        if ($missingOnLeft.Count -ne 0) { return "$Path.$($missingOnLeft[0]) (candidate-only)" }
        foreach ($name in $leftNames) {
            $difference = Find-FirstDifference $Left.$name $Right.$name "$Path.$name"
            if ($difference) { return $difference }
        }
        return $null
    }
    if ($Left -is [string]) {
        if ([string]$Left -cne [string]$Right) { return $Path }
    }
    elseif ($Left -ne $Right) {
        return $Path
    }
    return $null
}

function Copy-JsonObject([object]$Value) {
    return $Value | ConvertTo-Json -Depth 100 | ConvertFrom-Json
}

function Set-PathPatternValue {
    param(
        [object]$Root,
        [string]$Pattern,
        [scriptblock]$Transform
    )
    $segments = $Pattern.Split('.')
    function Visit([object]$Node, [int]$Index) {
        if ($null -eq $Node) { return }
        $segment = $segments[$Index]
        $arraySegment = $segment.EndsWith('[]')
        $propertyName = if ($arraySegment) { $segment.Substring(0, $segment.Length - 2) } else { $segment }
        $property = $Node.PSObject.Properties[$propertyName]
        if ($null -eq $property) { return }
        if ($Index -eq $segments.Count - 1) {
            if ($arraySegment) {
                for ($itemIndex = 0; $itemIndex -lt @($property.Value).Count; $itemIndex++) {
                    $property.Value[$itemIndex] = & $Transform $property.Value[$itemIndex]
                }
            }
            else {
                $property.Value = & $Transform $property.Value
            }
            return
        }
        if ($arraySegment) {
            foreach ($item in @($property.Value)) { Visit $item ($Index + 1) }
        }
        else {
            Visit $property.Value ($Index + 1)
        }
    }
    Visit $Root 0
}

function Assert-ProductionIdentity([object]$Summary, [string]$Commit, [string]$DebugSha, [string]$Side) {
    $production = Get-RequiredProperty $Summary 'production' "$Side summary"
    Assert-EqualString (Get-RequiredProperty $production 'commit' "$Side production identity") $Commit "$Side production identity commit"
    Assert-EqualString (Get-RequiredProperty $production 'debugApkSha256' "$Side production identity") $DebugSha "$Side production identity debug APK SHA"

    $apks = Get-RequiredProperty $Summary 'apks' "$Side summary"
    Assert-EqualString (Get-RequiredProperty $apks 'debugSha256' "$Side APK identity") $DebugSha "$Side production identity legacy debug APK SHA"
}

function Assert-HarnessIdentity([object]$Summary, [string]$Side) {
    $harness = Get-RequiredProperty $Summary 'harness' "$Side summary"
    Assert-EqualString (Get-RequiredProperty $harness 'commit' "$Side harness identity") $HarnessCommit "$Side harness identity commit"
    Assert-EqualString (Get-RequiredProperty $harness 'tree' "$Side harness identity") $HarnessTree "$Side harness identity tree"
    Assert-EqualString (Get-RequiredProperty $harness 'runnerSha256' "$Side harness identity") $HarnessRunnerSha256 "$Side harness identity runner SHA"
    Assert-EqualString (Get-RequiredProperty $harness 'instrumentationSourceSha256' "$Side harness identity") $HarnessInstrumentationSourceSha256 "$Side harness identity instrumentation source SHA"
    Assert-EqualString (Get-RequiredProperty $harness 'testApkSha256' "$Side harness identity") $HarnessTestApkSha256 "$Side harness identity test APK SHA"

    $git = Get-RequiredProperty $Summary 'git' "$Side summary"
    Assert-EqualString (Get-RequiredProperty $git 'commit' "$Side legacy harness identity") $HarnessCommit "$Side harness identity legacy git commit"
    $apks = Get-RequiredProperty $Summary 'apks' "$Side summary"
    Assert-EqualString (Get-RequiredProperty $apks 'testSha256' "$Side legacy harness identity") $HarnessTestApkSha256 "$Side harness identity legacy test APK SHA"
}

function Assert-SuccessfulRun([object]$Summary, [hashtable]$RawByLabel, [string]$Side) {
    $sentinels = Get-RequiredProperty $Summary 'sentinels' "$Side summary"
    if ((Get-RequiredProperty $sentinels 'passed' "$Side sentinels") -ne $true) { throw "$Side is not a successful run: sentinels.passed is not true" }
    $failedSentinels = @((Get-RequiredProperty $sentinels 'checks' "$Side sentinels") | Where-Object { $_.passed -ne $true })
    if ($failedSentinels.Count -ne 0) { throw "$Side is not a successful run: $($failedSentinels.Count) sentinel checks failed" }
    if (@((Get-RequiredProperty $Summary 'failures' "$Side summary")).Count -ne 0) { throw "$Side is not a successful run: failures is not empty" }
    if ((Get-RequiredProperty $Summary 'unexpectedAbort' "$Side summary") -ne $false) { throw "$Side is not a successful run: unexpectedAbort" }
    if ((Get-RequiredProperty $Summary 'abortedDueToTimeout' "$Side summary") -ne $false) { throw "$Side is not a successful run: abortedDueToTimeout" }
    Assert-EqualString (Get-RequiredProperty $Summary 'cleanupVerification' "$Side summary") 'verified' "$Side successful run cleanupVerification"

    foreach ($row in @($Summary.results)) {
        $label = [string]$row.label
        if ($row.passed -ne $true -or [string]$row.status -cne 'ok') { throw "$Side is not a successful run: summary result '$label' did not pass with status ok" }
        $rowCleanup = Get-RequiredProperty $row 'cleanup' "$Side summary result '$label'"
        if ($rowCleanup.hostVerified -ne $true -or @($rowCleanup.remainingTestOwnedPaths).Count -ne 0) { throw "$Side is not a successful run: summary cleanup residue for '$label'" }
        foreach ($snapshotName in @('postCleanupPrivateSnapshot', 'postCleanupOutputSnapshot', 'postCleanupTmpSnapshot')) {
            if (@((Get-RequiredProperty $row $snapshotName "$Side summary result '$label'")).Count -ne 0) {
                throw "$Side is not a successful run: $snapshotName contains residue for '$label'"
            }
        }
        $raw = $RawByLabel[$label]
        if ($raw.passed -ne $true) { throw "$Side is not a successful run: raw result '$label' did not pass" }
        $rawCleanup = Get-RequiredProperty $raw 'cleanup' "$Side raw result '$label'"
        if ($rawCleanup.hostVerified -ne $true -or @($rawCleanup.remainingTestOwnedPaths).Count -ne 0) { throw "$Side is not a successful run: raw cleanup residue for '$label'" }
    }
}

function Read-AndValidateRun {
    param(
        [string]$Root,
        [string]$Side,
        [string]$ExpectedCommit,
        [string]$ExpectedDebugSha,
        [object[]]$Entries,
        [string[]]$ExpectedLabels,
        [string[]]$ExpectedRawFiles,
        [string[]]$ExpectedScreenshotFiles,
        [string]$ExpectedManifestSha
    )
    $resolvedRoot = [IO.Path]::GetFullPath($Root)
    if (-not (Test-Path -LiteralPath $resolvedRoot -PathType Container)) { throw "$Side evidence root does not exist: $resolvedRoot" }
    $summary = Read-JsonFile (Join-Path $resolvedRoot 'summary.json') "$Side summary"
    Assert-EqualString (Get-RequiredProperty $summary 'manifestSha256' "$Side summary") $ExpectedManifestSha "$Side manifest SHA"
    Assert-ProductionIdentity $summary $ExpectedCommit $ExpectedDebugSha $Side
    Assert-HarnessIdentity $summary $Side

    $rows = @($summary.results)
    $summaryLabels = @($rows | ForEach-Object { [string]$_.label })
    Assert-ExactSet $summaryLabels $ExpectedLabels "$Side summary label set"
    if (@($summaryLabels | Select-Object -Unique).Count -ne $ExpectedLabels.Count) { throw "$Side summary label set contains duplicates" }

    $rawFiles = Get-RelativeFileSet $resolvedRoot 'result.json'
    Assert-ExactSet $rawFiles $ExpectedRawFiles "$Side result.json set"
    $screenshotRoot = Join-Path $resolvedRoot 'screenshots'
    $screenshotFiles = Get-RelativeFileSet $screenshotRoot '*'
    Assert-ExactSet $screenshotFiles $ExpectedScreenshotFiles "$Side screenshot set"

    $rawByLabel = @{}
    $rowByLabel = @{}
    foreach ($entry in $Entries) {
        $label = [string]$entry.label
        $safeLabel = ConvertTo-SafeLabel $label
        $row = @($rows | Where-Object { [string]$_.label -ceq $label })[0]
        Assert-EqualString $row.safeLabel $safeLabel "$Side summary safeLabel for '$label'"
        Assert-EqualString $row.sha256 ([string]$entry.sha256) "$Side summary archive SHA for '$label'"
        $requiredNames = @($entry.requiredEvidence | ForEach-Object { [string]$_ })
        Assert-ExactSet @($row.requiredEvidence | ForEach-Object { [string]$_ }) $requiredNames "$Side required evidence name set for '$label'"

        $rawPath = Join-Path $resolvedRoot "$safeLabel\result.json"
        $raw = Read-JsonFile $rawPath "$Side raw result '$label'"
        Assert-EqualString $raw.label $label "$Side raw label for '$label'"
        Assert-EqualString $raw.sha256 ([string]$entry.sha256) "$Side raw archive SHA for '$label'"
        foreach ($requiredName in $requiredNames) {
            $rawProperty = $raw.PSObject.Properties[$requiredName]
            $mirrorProperty = $row.requiredEvidencePayload.PSObject.Properties[$requiredName]
            if ($null -eq $rawProperty -or $null -eq $mirrorProperty) { throw "$Side required evidence mirror is missing '$requiredName' for '$label'" }
            $difference = Find-FirstDifference $rawProperty.Value $mirrorProperty.Value "requiredEvidencePayload.$requiredName"
            if ($difference) { throw "$Side required evidence mirror mismatch for '$label' at $difference" }
        }
        $rawByLabel[$label] = $raw
        $rowByLabel[$label] = $row
    }
    Assert-SuccessfulRun $summary $rawByLabel $Side
    return [pscustomobject]@{ Root = $resolvedRoot; Summary = $summary; Rows = $rowByLabel; Raw = $rawByLabel }
}

function Assert-StochasticValue {
    param([object]$Run, [object]$Rule, [string]$Side)
    $label = [string]$Rule.label
    $raw = $Run.Raw[$label]
    Assert-EqualString $raw.sha256 ([string]$Rule.archiveSha256) "$Side stochastic archive SHA for '$label'"
    $value = [string]$raw.dialogueProbe.value
    $valueHash = Get-StringSha256 $value
    if (@($Rule.allowedUtf8Sha256 | Where-Object { [string]$_ -ceq $valueHash }).Count -ne 1) {
        throw "$Side unreviewed stochastic dialogueProbe.value for '$label' (decoded UTF-8 SHA-256 $valueHash)"
    }
    if ($null -ne $Rule.PSObject.Properties['summaryMirrors']) {
        $rowValue = [string]$Run.Rows[$label].requiredEvidencePayload.dialogueProbe.value
        if ($rowValue -cne $value) { throw "$Side required evidence mirror mismatch for stochastic '$label'" }
        $mirror = $Rule.summaryMirrors
        $checks = @($Run.Summary.sentinels.checks | Where-Object { [string]$_.name -ceq [string]$mirror.sentinelName })
        if ($checks.Count -ne 1) { throw "$Side required evidence mirror sentinel '$($mirror.sentinelName)' was not unique" }
        $sentinelValue = [string]$checks[0].([string]$mirror.sentinelProperty)
        if ($sentinelValue -cne $value) { throw "$Side required evidence mirror sentinel mismatch for stochastic '$label'" }
    }
}

function ConvertTo-CanonicalRun {
    param([object]$Run, [object]$Contract)
    $summary = Copy-JsonObject $Run.Summary
    $rawByLabel = @{}
    foreach ($label in $Run.Raw.Keys) { $rawByLabel[$label] = Copy-JsonObject $Run.Raw[$label] }

    foreach ($path in @($Contract.normalization.summaryRunIdPaths)) { Set-PathPatternValue $summary ([string]$path) { '<RUN_ID>' } }
    foreach ($path in @($Contract.normalization.summaryTimestampPaths)) { Set-PathPatternValue $summary ([string]$path) { '<TIMESTAMP>' } }
    foreach ($path in @($Contract.normalization.summaryDurationPaths)) { Set-PathPatternValue $summary ([string]$path) { '<DURATION>' } }
    foreach ($path in @($Contract.normalization.summaryReportRootPaths)) { Set-PathPatternValue $summary ([string]$path) { '<REPORT_PATH>' } }
    foreach ($path in @($Contract.normalization.summaryRunOwnedStringPaths)) {
        $pathText = [string]$path
        Set-PathPatternValue $summary $pathText {
            param($value)
            if ($pathText.EndsWith('.output') -or $pathText.EndsWith('.error')) {
                return ([string]$value -replace [regex]::Escape([string]$Run.Summary.runId), '<RUN_ID>' -replace 'Time:\s+[0-9]+(?:\.[0-9]+)?', 'Time: <DURATION>')
            }
            return '<RUN_OWNED_PATH>'
        }
    }

    foreach ($identityPath in @('production.commit', 'production.debugApkSha256', 'harness.commit', 'harness.tree', 'harness.runnerSha256', 'harness.instrumentationSourceSha256', 'harness.testApkSha256', 'git.commit', 'apks.debugSha256', 'apks.testSha256')) {
        Set-PathPatternValue $summary $identityPath { '<VALIDATED_IDENTITY>' }
    }

    foreach ($label in @($rawByLabel.Keys)) {
        $raw = $rawByLabel[$label]
        foreach ($path in @($Contract.normalization.rawRunOwnedStringPaths)) {
            $pathText = [string]$path
            if ($pathText -ceq 'dialogueProbe.value') {
                Set-PathPatternValue $raw $pathText { param($value) ([string]$value).Replace([string]$Run.Summary.runId, '<RUN_ID>') }
            }
            else {
                Set-PathPatternValue $raw $pathText { '<RUN_OWNED_PATH>' }
            }
        }
    }

    foreach ($rule in @($Contract.stochasticDialogueValues)) {
        $label = [string]$rule.label
        $rawByLabel[$label].dialogueProbe.value = '<REVIEWED_STOCHASTIC_VALUE>'
        $summaryRow = @($summary.results | Where-Object { [string]$_.label -ceq $label })[0]
        if ($null -ne $summaryRow.requiredEvidencePayload.PSObject.Properties['dialogueProbe']) {
            $summaryRow.requiredEvidencePayload.dialogueProbe.value = '<REVIEWED_STOCHASTIC_VALUE>'
        }
        if ($null -ne $rule.PSObject.Properties['summaryMirrors']) {
            $mirror = $rule.summaryMirrors
            $check = @($summary.sentinels.checks | Where-Object { [string]$_.name -ceq [string]$mirror.sentinelName })[0]
            $check.([string]$mirror.sentinelProperty) = '<REVIEWED_STOCHASTIC_VALUE>'
        }
    }
    return [pscustomobject]@{ Summary = $summary; Raw = $rawByLabel }
}

function Assert-BaseBasePrerequisite([string]$Path, [string]$ManifestSha, [string]$ContractSha, [object]$Device) {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'BaseCandidate comparison requires -BaseBaseReportPath' }
    $report = Read-JsonFile $Path 'base/base prerequisite report'
    if ($report.passed -ne $true -or [string]$report.comparisonKind -cne 'BaseBase') { throw 'base/base prerequisite report is not a successful BaseBase comparison' }
    Assert-EqualString $report.manifestSha256 $ManifestSha 'base/base prerequisite manifest SHA'
    Assert-EqualString $report.contractSha256 $ContractSha 'base/base prerequisite contract SHA'
    foreach ($identityName in @('commit', 'debugApkSha256')) {
        $expected = if ($identityName -eq 'commit') { $BaseProductionCommit } else { $BaseDebugApkSha256 }
        Assert-EqualString $report.baseIdentity.$identityName $expected "base/base prerequisite base production identity $identityName"
        Assert-EqualString $report.candidateIdentity.$identityName $expected "base/base prerequisite candidate production identity $identityName"
    }
    foreach ($identityName in @('commit', 'tree', 'runnerSha256', 'instrumentationSourceSha256', 'testApkSha256')) {
        $expected = switch ($identityName) {
            'commit' { $HarnessCommit }
            'tree' { $HarnessTree }
            'runnerSha256' { $HarnessRunnerSha256 }
            'instrumentationSourceSha256' { $HarnessInstrumentationSourceSha256 }
            'testApkSha256' { $HarnessTestApkSha256 }
        }
        Assert-EqualString $report.harnessIdentity.$identityName $expected "base/base prerequisite harness identity $identityName"
    }
    $deviceDifference = Find-FirstDifference $report.device $Device 'base/base prerequisite device'
    if ($deviceDifference) { throw "base/base prerequisite mismatch at $deviceDifference" }
}

try {
    $manifest = Read-JsonFile $ManifestPath 'corpus manifest'
    $contract = Read-JsonFile $ContractPath 'comparison contract'
    $entries = @($manifest.entries)
    if ($entries.Count -ne 23) { throw "manifest must contain exactly 23 entries, found $($entries.Count)" }
    $expectedLabels = @($entries | ForEach-Object { [string]$_.label })
    if (@($expectedLabels | Select-Object -Unique).Count -ne 23) { throw 'manifest labels must be unique' }
    $expectedSafeLabels = @($expectedLabels | ForEach-Object { ConvertTo-SafeLabel $_ })
    if (@($expectedSafeLabels | Select-Object -Unique).Count -ne 23) { throw 'manifest safe labels must be unique' }
    $expectedRawFiles = @($expectedSafeLabels | ForEach-Object { "$_/result.json" })
    $expectedScreenshotFiles = @($expectedSafeLabels | ForEach-Object { "$_.png" })

    $stochasticLabels = @($contract.stochasticDialogueValues | ForEach-Object { [string]$_.label })
    $requiredStochasticLabels = @('2elf-2.46', 'LOBO', 'Snake and Otacon V1.2.1', 'Snake and Otacon V1.3.1', 'Snake_Otacon_1.3.1b', 'Watchdog Bancho')
    Assert-ExactSet $stochasticLabels $requiredStochasticLabels 'comparison contract stochastic label set'
    $requiredNormalization = @{
        summaryRunIdPaths = @('runId', 'results[].runId')
        summaryTimestampPaths = @('startedAt', 'finishedAt', 'results[].startedAt', 'results[].finishedAt')
        summaryDurationPaths = @('durationSeconds', 'results[].durationSeconds')
        summaryReportRootPaths = @('git.manifestFile', 'apks.debugPath', 'apks.testPath')
        summaryRunOwnedStringPaths = @('results[].resultPath', 'results[].screenshotPath', 'results[].crashLogPath', 'results[].output', 'results[].error')
        rawRunOwnedStringPaths = @('narCorpusPath', 'evidence.sourceSyntax.scanRoot', 'sakura.source', 'kero.source', 'dialogueProbe.value')
    }
    foreach ($normalizationName in $requiredNormalization.Keys) {
        $actualNormalization = Get-RequiredProperty $contract.normalization $normalizationName 'comparison contract normalization'
        Assert-ExactSet @($actualNormalization | ForEach-Object { [string]$_ }) $requiredNormalization[$normalizationName] "comparison contract normalization $normalizationName"
    }
    foreach ($rule in @($contract.stochasticDialogueValues)) {
        $label = [string]$rule.label
        Assert-EqualString $rule.jsonPath 'dialogueProbe.value' "comparison contract stochastic JSON path for '$label'"
        $manifestEntry = @($entries | Where-Object { [string]$_.label -ceq $label })[0]
        Assert-EqualString $rule.archiveSha256 ([string]$manifestEntry.sha256) "comparison contract archive SHA for '$label'"
        $allowedHashes = @($rule.allowedUtf8Sha256 | ForEach-Object { [string]$_ })
        if ($allowedHashes.Count -lt 1 -or @($allowedHashes | Select-Object -Unique).Count -ne $allowedHashes.Count -or @($allowedHashes | Where-Object { $_ -notmatch '^[0-9a-f]{64}$' }).Count -ne 0) {
            throw "comparison contract contains invalid or duplicate decoded UTF-8 hashes for '$label'"
        }
        if ([string]::IsNullOrWhiteSpace([string]$rule.source.archiveEntry) -or @($rule.source.lineRanges).Count -lt 1 -or @($rule.source.reviewedEvidence).Count -lt 1) {
            throw "comparison contract source provenance is incomplete for '$label'"
        }
    }

    $manifestSha = Get-Sha256 $ManifestPath
    $contractSha = Get-Sha256 $ContractPath
    $base = Read-AndValidateRun $BaseRoot 'base' $BaseProductionCommit $BaseDebugApkSha256 $entries $expectedLabels $expectedRawFiles $expectedScreenshotFiles $manifestSha
    if ($ComparisonKind -eq 'BaseCandidate') {
        Assert-BaseBasePrerequisite $BaseBaseReportPath $manifestSha $contractSha $base.Summary.device
    }
    $candidate = Read-AndValidateRun $CandidateRoot 'candidate' $CandidateProductionCommit $CandidateDebugApkSha256 $entries $expectedLabels $expectedRawFiles $expectedScreenshotFiles $manifestSha

    if ($ComparisonKind -eq 'BaseBase') {
        if ($BaseProductionCommit -cne $CandidateProductionCommit -or $BaseDebugApkSha256 -cne $CandidateDebugApkSha256) {
            throw 'BaseBase comparison requires identical base and candidate production identities'
        }
    }

    $deviceDifference = Find-FirstDifference $base.Summary.device $candidate.Summary.device 'device'
    if ($deviceDifference) { throw "device mismatch at $deviceDifference" }
    foreach ($rule in @($contract.stochasticDialogueValues)) {
        Assert-StochasticValue $base $rule 'base'
        Assert-StochasticValue $candidate $rule 'candidate'
    }

    $canonicalBase = ConvertTo-CanonicalRun $base $contract
    $canonicalCandidate = ConvertTo-CanonicalRun $candidate $contract
    $summaryDifference = Find-FirstDifference $canonicalBase.Summary $canonicalCandidate.Summary 'summary'
    if ($summaryDifference) { throw "summary behavioral difference at $summaryDifference" }
    foreach ($entry in $entries) {
        $label = [string]$entry.label
        $rawDifference = Find-FirstDifference $canonicalBase.Raw[$label] $canonicalCandidate.Raw[$label] "raw[$label]"
        if ($rawDifference) { throw "raw behavioral difference at $rawDifference" }
        $safeLabel = ConvertTo-SafeLabel $label
        $baseScreenshotSha = Get-Sha256 (Join-Path $base.Root "screenshots\$safeLabel.png")
        $candidateScreenshotSha = Get-Sha256 (Join-Path $candidate.Root "screenshots\$safeLabel.png")
        if ($baseScreenshotSha -cne $candidateScreenshotSha) { throw "screenshot hash mismatch for '$label'" }
    }

    $report = [pscustomobject][ordered]@{
        schemaVersion = '1'
        passed = $true
        comparisonKind = $ComparisonKind
        manifestSha256 = $manifestSha
        contractSha256 = $contractSha
        baseIdentity = [pscustomobject]@{ commit = $BaseProductionCommit; debugApkSha256 = $BaseDebugApkSha256 }
        candidateIdentity = [pscustomobject]@{ commit = $CandidateProductionCommit; debugApkSha256 = $CandidateDebugApkSha256 }
        harnessIdentity = [pscustomobject]@{ commit = $HarnessCommit; tree = $HarnessTree; runnerSha256 = $HarnessRunnerSha256; instrumentationSourceSha256 = $HarnessInstrumentationSourceSha256; testApkSha256 = $HarnessTestApkSha256 }
        device = $base.Summary.device
        comparedLabels = 23
        rawResultsCompared = 23
        screenshotsCompared = 23
        differences = @()
    }
    $outputDirectory = Split-Path -Parent ([IO.Path]::GetFullPath($OutputPath))
    if (-not (Test-Path -LiteralPath $outputDirectory)) { New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null }
    $report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $OutputPath -Encoding utf8
    Write-Host "NAR corpus $ComparisonKind comparison passed: 23 raw results and 23 screenshots matched."
}
catch {
    Write-Error $_.Exception.Message
    exit 1
}
