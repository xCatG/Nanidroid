#requires -Version 7.0

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

class NarCorpusExactJsonNumber {
    [string]$Token

    NarCorpusExactJsonNumber([string]$Token) {
        $this.Token = $Token
    }
}

function ConvertFrom-StrictJsonElement([System.Text.Json.JsonElement]$Element) {
    switch ($Element.ValueKind) {
        ([System.Text.Json.JsonValueKind]::Object) {
            $properties = [ordered]@{}
            foreach ($property in $Element.EnumerateObject()) {
                if ($properties.Contains($property.Name)) { throw "duplicate JSON property '$($property.Name)'" }
                $properties[$property.Name] = ConvertFrom-StrictJsonElement $property.Value
            }
            return [pscustomobject]$properties
        }
        ([System.Text.Json.JsonValueKind]::Array) {
            $items = [Collections.Generic.List[object]]::new()
            foreach ($item in $Element.EnumerateArray()) { $items.Add((ConvertFrom-StrictJsonElement $item)) }
            return ,$items.ToArray()
        }
        ([System.Text.Json.JsonValueKind]::String) { return $Element.GetString() }
        ([System.Text.Json.JsonValueKind]::Number) {
            $numberToken = $Element.GetRawText()
            [long]$integer = 0
            if ($Element.TryGetInt64([ref]$integer)) { return $integer }
            $mantissaDigits = (($numberToken -split '[eE]', 2)[0] -replace '[^0-9]', '').TrimStart('0')
            if ($mantissaDigits.Length -le 28) {
                [decimal]$decimalValue = 0
                if ($Element.TryGetDecimal([ref]$decimalValue)) { return $decimalValue }
            }
            return [NarCorpusExactJsonNumber]::new($numberToken)
        }
        ([System.Text.Json.JsonValueKind]::True) { return $true }
        ([System.Text.Json.JsonValueKind]::False) { return $false }
        ([System.Text.Json.JsonValueKind]::Null) { return $null }
        default { throw "unsupported JSON value kind '$($Element.ValueKind)'" }
    }
}

function ConvertFrom-StrictJsonText([string]$Text) {
    $document = [System.Text.Json.JsonDocument]::Parse($Text)
    try { return ConvertFrom-StrictJsonElement $document.RootElement }
    finally { $document.Dispose() }
}

function Read-JsonFile([string]$Path, [string]$Description) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Description does not exist: $Path"
    }
    try {
        return ConvertFrom-StrictJsonText (Get-Content -LiteralPath $Path -Raw)
    }
    catch {
        throw "$Description is not valid JSON: $Path ($($_.Exception.Message))"
    }
}

function Get-RequiredProperty([object]$Object, [string]$Name, [string]$Context) {
    if ($null -eq $Object -or $null -eq $Object.PSObject.Properties[$Name]) {
        throw "$Context is missing required property '$Name'"
    }
    return ,$Object.$Name
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
        return ([BitConverter]::ToString($algorithm.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
    }
}

function Write-ComparisonReportAtomic([object]$Report) {
    $resolvedOutputPath = [IO.Path]::GetFullPath($OutputPath)
    $outputDirectory = Split-Path -Parent $resolvedOutputPath
    if (-not (Test-Path -LiteralPath $outputDirectory)) { New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null }
    $temporaryPath = Join-Path $outputDirectory ('.' + [IO.Path]::GetFileName($resolvedOutputPath) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        $Report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $temporaryPath -Encoding utf8
        [IO.File]::Move($temporaryPath, $resolvedOutputPath, $true)
    }
    finally {
        if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath -Force }
    }
}

function Get-EvidenceFingerprint([string]$Root, [string[]]$RawFiles, [string[]]$ScreenshotFiles) {
    $items = [Collections.Generic.List[string]]::new()
    $items.Add("summary.json`t$(Get-Sha256 (Join-Path $Root 'summary.json'))")
    foreach ($relativePath in @($RawFiles | Sort-Object -CaseSensitive)) {
        $items.Add("$relativePath`t$(Get-Sha256 (Join-Path $Root $relativePath))")
    }
    foreach ($relativePath in @($ScreenshotFiles | Sort-Object -CaseSensitive)) {
        $items.Add("screenshots/$relativePath`t$(Get-Sha256 (Join-Path (Join-Path $Root 'screenshots') $relativePath))")
    }
    return Get-StringSha256 ($items -join "`n")
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
    if ($Value -is [NarCorpusExactJsonNumber]) { return 'number' }
    if ($Value -is [string]) { return 'string' }
    if ($Value -is [bool]) { return 'boolean' }
    if ($Value -is [System.Collections.IList]) { return 'array' }
    if ($Value -is [byte] -or $Value -is [sbyte] -or $Value -is [short] -or $Value -is [ushort] -or
        $Value -is [int] -or $Value -is [uint] -or $Value -is [long] -or $Value -is [ulong] -or
        $Value -is [float] -or $Value -is [double] -or $Value -is [decimal]) { return 'number' }
    if ($Value -is [ValueType]) { return $Value.GetType().FullName }
    return 'object'
}

function Assert-JsonKind([object]$Value, [string[]]$AllowedKinds, [string]$Context) {
    $kind = Get-ObjectKind $Value
    if ($AllowedKinds -cnotcontains $kind) {
        throw "$Context has JSON kind '$kind'; expected $($AllowedKinds -join ' or ')"
    }
    return $kind
}

function Assert-NormalizationKind([object]$Contract, [string]$Scope, [string]$Path, [object]$Value) {
    $rules = @($Contract.normalization.expectedKinds | Where-Object { [string]$_.scope -ceq $Scope -and [string]$_.path -ceq $Path })
    if ($rules.Count -ne 1) { throw "comparison contract normalization kind rule is not unique for $Scope $Path" }
    return Assert-JsonKind $Value @($rules[0].kinds | ForEach-Object { [string]$_ }) "normalization path $Scope.$Path"
}

function Find-FirstDifference([object]$Left, [object]$Right, [string]$Path = '$') {
    $leftKind = Get-ObjectKind $Left
    $rightKind = Get-ObjectKind $Right
    if ($leftKind -cne $rightKind) { return "$Path type ($leftKind != $rightKind)" }
    if ($leftKind -eq 'null') { return $null }
    if ($leftKind -eq 'number' -and ($Left -is [NarCorpusExactJsonNumber] -or $Right -is [NarCorpusExactJsonNumber])) {
        $leftToken = if ($Left -is [NarCorpusExactJsonNumber]) { $Left.Token } else { ([IFormattable]$Left).ToString($null, [Globalization.CultureInfo]::InvariantCulture) }
        $rightToken = if ($Right -is [NarCorpusExactJsonNumber]) { $Right.Token } else { ([IFormattable]$Right).ToString($null, [Globalization.CultureInfo]::InvariantCulture) }
        if ($leftToken -cne $rightToken) { return $Path }
        return $null
    }
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
    if ($null -eq $Value -or $Value -is [string] -or $Value -is [ValueType]) { return $Value }
    if ($Value -is [NarCorpusExactJsonNumber]) { return [NarCorpusExactJsonNumber]::new($Value.Token) }
    if ($Value -is [System.Collections.IList]) {
        $items = [Collections.Generic.List[object]]::new()
        foreach ($item in $Value) { $items.Add((Copy-JsonObject $item)) }
        return ,$items.ToArray()
    }
    $copy = [ordered]@{}
    foreach ($property in $Value.PSObject.Properties) { $copy[$property.Name] = Copy-JsonObject $property.Value }
    return [pscustomobject]$copy
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
    $sentinelsPassed = Get-RequiredProperty $sentinels 'passed' "$Side sentinels"
    Assert-JsonKind $sentinelsPassed @('boolean') "$Side sentinels.passed" | Out-Null
    if ($sentinelsPassed -ne $true) { throw "$Side is not a successful run: sentinels.passed is not true" }
    $sentinelChecks = Get-RequiredProperty $sentinels 'checks' "$Side sentinels"
    Assert-JsonKind $sentinelChecks @('array') "$Side sentinels.checks" | Out-Null
    foreach ($check in @($sentinelChecks)) { Assert-JsonKind $check.passed @('boolean') "$Side sentinel check '$($check.name)'.passed" | Out-Null }
    $failedSentinels = @($sentinelChecks | Where-Object { $_.passed -ne $true })
    if ($failedSentinels.Count -ne 0) { throw "$Side is not a successful run: $($failedSentinels.Count) sentinel checks failed" }
    $failures = Get-RequiredProperty $Summary 'failures' "$Side summary"
    Assert-JsonKind $failures @('array') "$Side failures" | Out-Null
    if (@($failures).Count -ne 0) { throw "$Side is not a successful run: failures is not empty" }
    $unexpectedAbort = Get-RequiredProperty $Summary 'unexpectedAbort' "$Side summary"
    Assert-JsonKind $unexpectedAbort @('boolean') "$Side unexpectedAbort" | Out-Null
    if ($unexpectedAbort -ne $false) { throw "$Side is not a successful run: unexpectedAbort" }
    $abortedDueToTimeout = Get-RequiredProperty $Summary 'abortedDueToTimeout' "$Side summary"
    Assert-JsonKind $abortedDueToTimeout @('boolean') "$Side abortedDueToTimeout" | Out-Null
    if ($abortedDueToTimeout -ne $false) { throw "$Side is not a successful run: abortedDueToTimeout" }
    Assert-EqualString (Get-RequiredProperty $Summary 'cleanupVerification' "$Side summary") 'verified' "$Side successful run cleanupVerification"

    foreach ($row in @($Summary.results)) {
        $label = [string]$row.label
        Assert-JsonKind $row.passed @('boolean') "$Side summary result '$label'.passed" | Out-Null
        Assert-JsonKind $row.status @('string') "$Side summary result '$label'.status" | Out-Null
        if ($row.passed -ne $true -or [string]$row.status -cne 'ok') { throw "$Side is not a successful run: summary result '$label' did not pass with status ok" }
        $rowCleanup = Get-RequiredProperty $row 'cleanup' "$Side summary result '$label'"
        Assert-JsonKind $rowCleanup.hostVerified @('boolean') "$Side summary result '$label'.cleanup.hostVerified" | Out-Null
        Assert-JsonKind $rowCleanup.remainingTestOwnedPaths @('array') "$Side summary result '$label'.cleanup.remainingTestOwnedPaths" | Out-Null
        if ($rowCleanup.hostVerified -ne $true -or @($rowCleanup.remainingTestOwnedPaths).Count -ne 0) { throw "$Side is not a successful run: summary cleanup residue for '$label'" }
        foreach ($snapshotName in @('postCleanupPrivateSnapshot', 'postCleanupOutputSnapshot', 'postCleanupTmpSnapshot')) {
            $snapshot = Get-RequiredProperty $row $snapshotName "$Side summary result '$label'"
            Assert-JsonKind $snapshot @('array') "$Side summary result '$label'.$snapshotName" | Out-Null
            if (@($snapshot).Count -ne 0) {
                throw "$Side is not a successful run: $snapshotName contains residue for '$label'"
            }
        }
        $raw = $RawByLabel[$label]
        Assert-JsonKind $raw.passed @('boolean') "$Side raw result '$label'.passed" | Out-Null
        if ($raw.passed -ne $true) { throw "$Side is not a successful run: raw result '$label' did not pass" }
        $rawCleanup = Get-RequiredProperty $raw 'cleanup' "$Side raw result '$label'"
        Assert-JsonKind $rawCleanup.hostVerified @('boolean') "$Side raw result '$label'.cleanup.hostVerified" | Out-Null
        Assert-JsonKind $rawCleanup.remainingTestOwnedPaths @('array') "$Side raw result '$label'.cleanup.remainingTestOwnedPaths" | Out-Null
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

    $summaryResults = Get-RequiredProperty $summary 'results' "$Side summary"
    Assert-JsonKind $summaryResults @('array') "$Side summary.results" | Out-Null
    $rows = @($summaryResults)
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
        Assert-JsonKind $row.requiredEvidence @('array') "$Side requiredEvidence for '$label'" | Out-Null
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
    $evidenceFingerprint = Get-EvidenceFingerprint $resolvedRoot $ExpectedRawFiles $ExpectedScreenshotFiles
    return [pscustomobject]@{ Root = $resolvedRoot; Summary = $summary; Rows = $rowByLabel; Raw = $rawByLabel; EvidenceFingerprint = $evidenceFingerprint }
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

    foreach ($path in @($Contract.normalization.summaryRunIdPaths)) {
        $pathText = [string]$path
        Set-PathPatternValue $summary $pathText { param($value) $kind = Assert-NormalizationKind $Contract 'summary' $pathText $value; "<RUN_ID:$kind>" }
    }
    foreach ($path in @($Contract.normalization.summaryTimestampPaths)) {
        $pathText = [string]$path
        Set-PathPatternValue $summary $pathText { param($value) $kind = Assert-NormalizationKind $Contract 'summary' $pathText $value; "<TIMESTAMP:$kind>" }
    }
    foreach ($path in @($Contract.normalization.summaryDurationPaths)) {
        $pathText = [string]$path
        Set-PathPatternValue $summary $pathText { param($value) $kind = Assert-NormalizationKind $Contract 'summary' $pathText $value; "<DURATION:$kind>" }
    }
    foreach ($path in @($Contract.normalization.summaryReportRootPaths)) {
        $pathText = [string]$path
        Set-PathPatternValue $summary $pathText { param($value) $kind = Assert-NormalizationKind $Contract 'summary' $pathText $value; "<REPORT_PATH:$kind>" }
    }
    foreach ($path in @($Contract.normalization.summaryRunOwnedStringPaths)) {
        $pathText = [string]$path
        Set-PathPatternValue $summary $pathText {
            param($value)
            $kind = Assert-NormalizationKind $Contract 'summary' $pathText $value
            if ($pathText.EndsWith('.output') -or $pathText.EndsWith('.error')) {
                return ([string]$value -replace [regex]::Escape([string]$Run.Summary.runId), '<RUN_ID>' -replace 'Time:\s+[0-9]+(?:\.[0-9]+)?', 'Time: <DURATION>')
            }
            return "<RUN_OWNED_PATH:$kind>"
        }
    }

    foreach ($identityPath in @('production.commit', 'production.debugApkSha256', 'harness.commit', 'harness.tree', 'harness.runnerSha256', 'harness.instrumentationSourceSha256', 'harness.testApkSha256', 'git.commit', 'apks.debugSha256', 'apks.testSha256')) {
        Set-PathPatternValue $summary $identityPath { '<VALIDATED_IDENTITY>' }
    }

    foreach ($label in @($rawByLabel.Keys)) {
        $raw = $rawByLabel[$label]
        foreach ($path in @($Contract.normalization.rawRunOwnedStringPaths)) {
            $pathText = [string]$path
            Set-PathPatternValue $raw $pathText { param($value) $kind = Assert-NormalizationKind $Contract 'raw' $pathText $value; "<RUN_OWNED_PATH:$kind>" }
        }
    }

    foreach ($rule in @($Contract.normalization.scopedRunOwnedValues)) {
        $label = [string]$rule.label
        $pathText = [string]$rule.path
        $raw = $rawByLabel[$label]
        Set-PathPatternValue $raw $pathText {
            param($value)
            $kind = Assert-JsonKind $value @($rule.kinds | ForEach-Object { [string]$_ }) "scoped normalization path raw[$label].$pathText"
            $requiredShape = ([string]$rule.requiredShape).Replace('{runId}', [string]$Run.Summary.runId)
            if (-not ([string]$value).Contains($requiredShape, [StringComparison]::Ordinal)) {
                throw "scoped normalization path raw[$label].$pathText does not match its declared run-owned shape"
            }
            $normalizedShape = ([string]$rule.requiredShape).Replace('{runId}', "<RUN_ID:$kind>")
            return ([string]$value).Replace($requiredShape, $normalizedShape)
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

function Assert-BaseBasePrerequisite([string]$Path, [string]$ManifestSha, [string]$ContractSha, [object]$Device, [string]$BaseEvidenceFingerprint) {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'BaseCandidate comparison requires -BaseBaseReportPath' }
    $report = Read-JsonFile $Path 'base/base prerequisite report'
    if ($report.passed -ne $true -or [string]$report.comparisonKind -cne 'BaseBase') { throw 'base/base prerequisite report is not a successful BaseBase comparison' }
    Assert-EqualString $report.manifestSha256 $ManifestSha 'base/base prerequisite manifest SHA'
    Assert-EqualString $report.contractSha256 $ContractSha 'base/base prerequisite contract SHA'
    Assert-EqualString (Get-RequiredProperty $report 'baseEvidenceFingerprint' 'base/base prerequisite report') $BaseEvidenceFingerprint 'base/base prerequisite base evidence fingerprint'
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
        rawRunOwnedStringPaths = @('narCorpusPath', 'evidence.sourceSyntax.scanRoot', 'sakura.source', 'kero.source')
    }
    foreach ($normalizationName in $requiredNormalization.Keys) {
        $actualNormalization = Get-RequiredProperty $contract.normalization $normalizationName 'comparison contract normalization'
        Assert-ExactSet @($actualNormalization | ForEach-Object { [string]$_ }) $requiredNormalization[$normalizationName] "comparison contract normalization $normalizationName"
    }
    $expectedKindRules = @(
        'summary|runId|runId|string',
        'summary|results[].runId|runId|string',
        'summary|startedAt|timestamp|string',
        'summary|finishedAt|timestamp|string',
        'summary|results[].startedAt|timestamp|string',
        'summary|results[].finishedAt|timestamp|string',
        'summary|durationSeconds|duration|number',
        'summary|results[].durationSeconds|duration|null,number',
        'summary|git.manifestFile|reportPath|string',
        'summary|apks.debugPath|reportPath|string',
        'summary|apks.testPath|reportPath|string',
        'summary|results[].resultPath|runOwnedPath|string',
        'summary|results[].screenshotPath|runOwnedPath|string',
        'summary|results[].crashLogPath|runOwnedPath|string',
        'summary|results[].output|runOwnedText|string',
        'summary|results[].error|runOwnedText|string',
        'raw|narCorpusPath|runOwnedPath|string',
        'raw|evidence.sourceSyntax.scanRoot|runOwnedPath|string',
        'raw|sakura.source|runOwnedPath|string',
        'raw|kero.source|runOwnedPath|string'
    )
    $actualKindRules = @($contract.normalization.expectedKinds | ForEach-Object {
        $kinds = @($_.kinds | ForEach-Object { [string]$_ } | Sort-Object -CaseSensitive) -join ','
        "$([string]$_.scope)|$([string]$_.path)|$([string]$_.category)|$kinds"
    })
    Assert-ExactSet $actualKindRules $expectedKindRules 'comparison contract normalization expected-kind rules'
    $scopedRules = @($contract.normalization.scopedRunOwnedValues)
    if ($scopedRules.Count -ne 1) { throw 'comparison contract must contain exactly one scoped run-owned value rule' }
    $scopedRule = $scopedRules[0]
    Assert-EqualString $scopedRule.scope 'raw' 'comparison contract scoped normalization scope'
    Assert-EqualString $scopedRule.label 'Yes Man-2.1.1' 'comparison contract scoped normalization label'
    Assert-EqualString $scopedRule.path 'dialogueProbe.value' 'comparison contract scoped normalization path'
    Assert-EqualString $scopedRule.category 'runOwnedEmbeddedPath' 'comparison contract scoped normalization category'
    Assert-ExactSet @($scopedRule.kinds | ForEach-Object { [string]$_ }) @('string') 'comparison contract scoped normalization kinds'
    Assert-EqualString $scopedRule.requiredShape '/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/{runId}/Yes-Man-2.1.1/' 'comparison contract scoped normalization shape'
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
        Assert-BaseBasePrerequisite $BaseBaseReportPath $manifestSha $contractSha $base.Summary.device $base.EvidenceFingerprint
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
        baseEvidenceFingerprint = $base.EvidenceFingerprint
        candidateEvidenceFingerprint = $candidate.EvidenceFingerprint
        device = $base.Summary.device
        comparedLabels = 23
        rawResultsCompared = 23
        screenshotsCompared = 23
        differences = @()
    }
    Write-ComparisonReportAtomic $report
    Write-Host "NAR corpus $ComparisonKind comparison passed: 23 raw results and 23 screenshots matched."
}
catch {
    $reason = [string]$_.Exception.Message
    if ($reason.Length -gt 2000) { $reason = $reason.Substring(0, 2000) }
    $artifact = if ($reason -match '(?i)raw|result\.json') { 'raw-result' }
        elseif ($reason -match '(?i)screenshot') { 'screenshot' }
        elseif ($reason -match '(?i)summary') { 'summary' }
        elseif ($reason -match '(?i)prerequisite') { 'base-base-prerequisite' }
        elseif ($reason -match '(?i)identity|APK SHA|commit') { 'identity' }
        elseif ($reason -match '(?i)contract') { 'comparison-contract' }
        elseif ($reason -match '(?i)manifest') { 'manifest' }
        else { 'comparison' }
    $label = if ($reason -match "'(?<label>[^'\r\n]{1,200})'") {
        [string]$Matches.label
    }
    elseif ($reason -match 'raw\[(?<label>[^\]\r\n]{1,200})\]') {
        [string]$Matches.label
    }
    else {
        '<none>'
    }
    $failurePath = if ($reason -match '(?i)\bat (?<path>.+)$') { [string]$Matches.path } else { '<none>' }
    $failureReport = [pscustomobject][ordered]@{
        schemaVersion = '1'
        passed = $false
        comparisonKind = $ComparisonKind
        failure = [pscustomobject][ordered]@{
            artifact = $artifact
            label = $label
            path = $failurePath
            reason = $reason
        }
    }
    try { Write-ComparisonReportAtomic $failureReport } catch { Write-Warning "Unable to write failure comparison report: $($_.Exception.Message)" }
    Write-Error $reason
    exit 1
}
