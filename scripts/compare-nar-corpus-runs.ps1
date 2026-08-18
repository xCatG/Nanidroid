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
        ([System.Text.Json.JsonValueKind]::Number) { return [NarCorpusExactJsonNumber]::new($Element.GetRawText()) }
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

function Write-StrictJsonValue([System.Text.Json.Utf8JsonWriter]$Writer, [object]$Value) {
    if ($null -eq $Value) { $Writer.WriteNullValue(); return }
    if ($Value -is [NarCorpusExactJsonNumber]) {
        $numberDocument = [System.Text.Json.JsonDocument]::Parse($Value.Token)
        try { $numberDocument.RootElement.WriteTo($Writer) } finally { $numberDocument.Dispose() }
        return
    }
    if ($Value -is [string]) { $Writer.WriteStringValue([string]$Value); return }
    if ($Value -is [bool]) { $Writer.WriteBooleanValue([bool]$Value); return }
    if ($Value -is [byte] -or $Value -is [sbyte] -or $Value -is [short] -or $Value -is [ushort] -or
        $Value -is [int] -or $Value -is [uint] -or $Value -is [long] -or $Value -is [ulong] -or
        $Value -is [float] -or $Value -is [double] -or $Value -is [decimal]) {
        $numberText = ([IFormattable]$Value).ToString($null, [Globalization.CultureInfo]::InvariantCulture)
        $numberDocument = [System.Text.Json.JsonDocument]::Parse($numberText)
        try { $numberDocument.RootElement.WriteTo($Writer) } finally { $numberDocument.Dispose() }
        return
    }
    if ($Value -is [System.Collections.IList]) {
        $Writer.WriteStartArray()
        foreach ($item in $Value) { Write-StrictJsonValue $Writer $item }
        $Writer.WriteEndArray()
        return
    }
    $Writer.WriteStartObject()
    if ($Value -is [Collections.IDictionary]) {
        foreach ($key in $Value.Keys) {
            $Writer.WritePropertyName([string]$key)
            Write-StrictJsonValue $Writer $Value[$key]
        }
    }
    else {
        foreach ($property in $Value.PSObject.Properties) {
            $Writer.WritePropertyName($property.Name)
            Write-StrictJsonValue $Writer $property.Value
        }
    }
    $Writer.WriteEndObject()
}

function ConvertTo-StrictJsonText([object]$Value) {
    $stream = [IO.MemoryStream]::new()
    $options = [System.Text.Json.JsonWriterOptions]::new()
    $options.Indented = $true
    $writer = [System.Text.Json.Utf8JsonWriter]::new($stream, $options)
    try {
        Write-StrictJsonValue $writer $Value
        $writer.Flush()
        return [Text.Encoding]::UTF8.GetString($stream.ToArray())
    }
    finally {
        $writer.Dispose()
        $stream.Dispose()
    }
}

function Write-ComparisonReportAtomic([object]$Report, [string]$ResolvedOutputPath, [bool]$AllowOverwrite) {
    $outputDirectory = Split-Path -Parent $resolvedOutputPath
    if (-not (Test-Path -LiteralPath $outputDirectory)) { New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null }
    $temporaryPath = Join-Path $outputDirectory ('.' + [IO.Path]::GetFileName($resolvedOutputPath) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    try {
        ConvertTo-StrictJsonText $Report | Set-Content -LiteralPath $temporaryPath -Encoding utf8
        [IO.File]::Move($temporaryPath, $resolvedOutputPath, $AllowOverwrite)
    }
    catch [IO.IOException] {
        if (-not $AllowOverwrite -and (Test-Path -LiteralPath $resolvedOutputPath)) { throw 'BaseCandidate OutputPath must be a fresh path' }
        throw
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

function Assert-ExactJsonValue([object]$Actual, [object]$Expected, [string]$Context) {
    $expectedKind = Get-ObjectKind $Expected
    Assert-JsonKind $Actual @($expectedKind) $Context | Out-Null
    if ($expectedKind -eq 'string') {
        Assert-EqualString $Actual ([string]$Expected) $Context
    }
    elseif ($expectedKind -eq 'number') {
        $actualToken = if ($Actual -is [NarCorpusExactJsonNumber]) { $Actual.Token } else { [string]$Actual }
        $expectedToken = if ($Expected -is [NarCorpusExactJsonNumber]) { $Expected.Token } else { [string]$Expected }
        if ($actualToken -cne $expectedToken) { throw "$Context mismatch: expected exact JSON number '$expectedToken', found '$actualToken'" }
    }
    elseif ($expectedKind -ne 'null') {
        $difference = Find-FirstDifference $Actual $Expected $Context
        if ($difference) { throw "$Context mismatch at $difference" }
    }
}

function Get-GhostEnvelopeRuleKey([object]$Rule, [string]$Context) {
    Assert-JsonKind $Rule @('object') $Context | Out-Null
    $label = Get-RequiredProperty $Rule 'label' $Context
    $archiveSha256 = Get-RequiredProperty $Rule 'archiveSha256' $Context
    $classification = Get-RequiredProperty $Rule 'classification' $Context
    $method = Get-RequiredProperty $Rule 'method' $Context
    $eventId = Get-RequiredProperty $Rule 'eventId' $Context
    $status = Get-RequiredProperty $Rule 'status' $Context
    $outcome = Get-RequiredProperty $Rule 'outcome' $Context
    $failure = Get-RequiredProperty $Rule 'failure' $Context
    foreach ($field in @(
        @{ name = 'label'; value = $label },
        @{ name = 'archiveSha256'; value = $archiveSha256 },
        @{ name = 'classification'; value = $classification },
        @{ name = 'outcome'; value = $outcome }
    )) {
        Assert-JsonKind $field.value @('string') "$Context.$($field.name)" | Out-Null
    }
    Assert-JsonKind $method @('string', 'null') "$Context.method" | Out-Null
    Assert-JsonKind $eventId @('string', 'null') "$Context.eventId" | Out-Null
    Assert-JsonKind $status @('number', 'null') "$Context.status" | Out-Null
    Assert-JsonKind $failure @('string', 'null') "$Context.failure" | Out-Null
    $statusToken = if ($status -is [NarCorpusExactJsonNumber]) { $status.Token } elseif ($null -eq $status) { '<null>' } else { [string]$status }
    $methodText = if ($null -eq $method) { '<null>' } else { [string]$method }
    $eventText = if ($null -eq $eventId) { '<null>' } else { [string]$eventId }
    $failureText = if ($null -eq $failure) { '<null>' } else { [string]$failure }
    return "$label|$archiveSha256|$classification|$methodText|$eventText|$statusToken|$outcome|$failureText"
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
    if ($leftKind -eq 'number') {
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
        $leftNames = @($Left.PSObject.Properties | ForEach-Object { [string]$_.Name } | Sort-Object -CaseSensitive)
        $rightNames = @($Right.PSObject.Properties | ForEach-Object { [string]$_.Name } | Sort-Object -CaseSensitive)
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
        [scriptblock]$Transform,
        [string]$Context = 'summary',
        [int]$ExpectedMatchCount = 1
    )
    $segments = $Pattern.Split('.')
    $state = @{ MatchCount = 0 }
    function Get-NodeContext([object]$Node) {
        if ($Context -ceq 'summary' -and $null -ne $Node -and $null -ne $Node.PSObject.Properties['label']) {
            return "summary result '$([string]$Node.label)'"
        }
        return $Context
    }
    function Visit([object]$Node, [int]$Index, [string]$ResolvedParentPath) {
        if ($null -eq $Node) {
            $missingPath = if ([string]::IsNullOrEmpty($ResolvedParentPath)) { $segments[$Index] } else { "$ResolvedParentPath.$($segments[$Index])" }
            throw "$Context normalization parent is null at $missingPath"
        }
        $segment = $segments[$Index]
        $arraySegment = $segment.EndsWith('[]')
        $propertyName = if ($arraySegment) { $segment.Substring(0, $segment.Length - 2) } else { $segment }
        $propertyPath = if ([string]::IsNullOrEmpty($ResolvedParentPath)) { $propertyName } else { "$ResolvedParentPath.$propertyName" }
        $property = $Node.PSObject.Properties[$propertyName]
        if ($null -eq $property) {
            throw "$(Get-NodeContext $Node) normalization property is missing at $propertyPath"
        }
        if ($arraySegment -and (Get-ObjectKind $property.Value) -cne 'array') {
            throw "$(Get-NodeContext $Node) normalization selector must be an array at $propertyPath"
        }
        if ($Index -eq $segments.Count - 1) {
            if ($arraySegment) {
                for ($itemIndex = 0; $itemIndex -lt @($property.Value).Count; $itemIndex++) {
                    $property.Value[$itemIndex] = & $Transform $property.Value[$itemIndex]
                    $state.MatchCount++
                }
            }
            else {
                $property.Value = & $Transform $property.Value
                $state.MatchCount++
            }
            return
        }
        if ($arraySegment) {
            for ($itemIndex = 0; $itemIndex -lt @($property.Value).Count; $itemIndex++) {
                Visit $property.Value[$itemIndex] ($Index + 1) "$propertyPath[$itemIndex]"
            }
        }
        else {
            Visit $property.Value ($Index + 1) $propertyPath
        }
    }
    Visit $Root 0 ''
    if ($state.MatchCount -ne $ExpectedMatchCount) {
        throw "$Context normalization selector resolved $($state.MatchCount) instances; expected $ExpectedMatchCount at $Pattern"
    }
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

function Get-SentinelCheckNameDigest([object[]]$SentinelChecks, [string]$Side) {
    $names = [Collections.Generic.List[string]]::new()
    $nameSet = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($check in $SentinelChecks) {
        Assert-JsonKind $check @('object') "$Side sentinel check" | Out-Null
        $name = Get-RequiredProperty $check 'name' "$Side sentinel check"
        Assert-JsonKind $name @('string') "$Side sentinel check name" | Out-Null
        $nameText = [string]$name
        if ([string]::IsNullOrWhiteSpace($nameText)) {
            throw "$Side sentinel check name must be nonblank"
        }
        if (-not $nameSet.Add($nameText)) {
            throw "$Side sentinel check names must be unique"
        }
        $names.Add($nameText)
    }
    [string[]]$sortedNames = $names.ToArray()
    [Array]::Sort($sortedNames, [StringComparer]::Ordinal)
    return Get-StringSha256 ($sortedNames -join "`n")
}

function Test-AcceptedNativeCheckpoint([object]$Row, [object]$Raw, [object]$Entry, [string]$Side, [string]$Label) {
    $allow = if ($null -eq $Entry) { $null } else { $Entry.PSObject.Properties['allowNativeKawariCrash'] }
    if ($null -eq $allow -or $allow.Value -ne $true) { return $false }

    $nativeCrashProperty = $Row.PSObject.Properties['nativeCrash']
    if ($null -eq $nativeCrashProperty) { return $false }
    $nativeCrash = $nativeCrashProperty.Value
    Assert-JsonKind $nativeCrash @('boolean') "$Side summary result '$Label'.nativeCrash" | Out-Null
    if ($nativeCrash -ne $true) { return $false }
    Assert-EqualString (Get-RequiredProperty $Row 'runtimeCheckpointPhase' "$Side summary result '$Label'") 'before-real-shiori' "$Side summary result '$Label'.runtimeCheckpointPhase"
    Assert-EqualString (Get-RequiredProperty $Raw 'checkpointPhase' "$Side raw result '$Label'") 'before-real-shiori' "$Side raw result '$Label'.checkpointPhase"
    Assert-EqualString (Get-RequiredProperty $Row 'classification' "$Side summary result '$Label'") 'incompatible' "$Side summary result '$Label'.classification"
    Assert-EqualString (Get-RequiredProperty $Raw 'classification' "$Side raw result '$Label'") 'incompatible' "$Side raw result '$Label'.classification"
    Assert-EqualString (Get-RequiredProperty $Row 'status' "$Side summary result '$Label'") 'ok' "$Side summary result '$Label'.status"

    $probe = Get-RequiredProperty $Raw 'dialogueProbe' "$Side raw result '$Label'"
    Assert-JsonKind $probe @('object') "$Side raw result '$Label'.dialogueProbe" | Out-Null
    Assert-EqualString (Get-RequiredProperty $probe 'method' "$Side raw result '$Label'.dialogueProbe") 'GET' "$Side raw result '$Label'.dialogueProbe.method"
    Assert-EqualString (Get-RequiredProperty $probe 'eventId' "$Side raw result '$Label'.dialogueProbe") 'OnBoot' "$Side raw result '$Label'.dialogueProbe.eventId"
    Assert-EqualString (Get-RequiredProperty $probe 'outcome' "$Side raw result '$Label'.dialogueProbe") 'pending-real-shiori' "$Side raw result '$Label'.dialogueProbe.outcome"
    if ($null -ne (Get-RequiredProperty $probe 'status' "$Side raw result '$Label'.dialogueProbe")) { throw "$Side raw result '$Label'.dialogueProbe.status must be null at the native checkpoint" }
    if ($null -ne (Get-RequiredProperty $probe 'failure' "$Side raw result '$Label'.dialogueProbe")) { throw "$Side raw result '$Label'.dialogueProbe.failure must be null at the native checkpoint" }
    foreach ($snapshotName in @('observedPrivateSnapshot', 'observedTmpSnapshot')) {
        $snapshot = Get-RequiredProperty $Row $snapshotName "$Side summary result '$Label'"
        Assert-JsonKind $snapshot @('array') "$Side accepted native checkpoint '$Label'.$snapshotName" | Out-Null
        if (@($snapshot).Count -ne 0) { throw "$Side accepted native checkpoint '$Label'.$snapshotName must be empty" }
    }
    return $true
}

function Assert-DialogueOutcomeMirror([object]$Row, [object]$Raw, [string]$Side, [string]$Label) {
    $probe = Get-RequiredProperty $Raw 'dialogueProbe' "$Side raw result '$Label'"
    Assert-JsonKind $probe @('object') "$Side raw result '$Label'.dialogueProbe" | Out-Null
    $rawOutcome = Get-RequiredProperty $probe 'outcome' "$Side raw result '$Label'.dialogueProbe"
    Assert-JsonKind $rawOutcome @('string') "$Side raw result '$Label'.dialogueProbe.outcome" | Out-Null
    if ([string]::IsNullOrWhiteSpace([string]$rawOutcome)) {
        throw "$Side raw result '$Label'.dialogueProbe.outcome must be nonblank"
    }

    $summaryOutcome = Get-RequiredProperty $Row 'dialogueOutcome' "$Side summary result '$Label'"
    Assert-JsonKind $summaryOutcome @('string') "$Side summary result '$Label'.dialogueOutcome" | Out-Null
    if ([string]::IsNullOrWhiteSpace([string]$summaryOutcome)) {
        throw "$Side summary result '$Label'.dialogueOutcome must be nonblank"
    }
    Assert-EqualString $summaryOutcome $rawOutcome "$Side summary result '$Label'.dialogueOutcome raw mirror"
}

function Assert-NonGhostOutcomeEnvelope([object]$Row, [object]$Raw, [object]$Entry, [string]$Side, [string]$Label) {
    $expectedKind = [string]$Entry.expectedKind
    if ($expectedKind -ceq 'ghost') { return }

    Assert-EqualString (Get-RequiredProperty $Raw 'observedKind' "$Side raw result '$Label'") $expectedKind "$Side raw result '$Label'.observedKind"
    Assert-EqualString (Get-RequiredProperty $Raw 'classification' "$Side raw result '$Label'") 'unsupported' "$Side raw result '$Label'.classification"
    Assert-EqualString (Get-RequiredProperty $Row 'classification' "$Side summary result '$Label'") 'unsupported' "$Side summary result '$Label'.classification"
    Assert-EqualString (Get-RequiredProperty $Raw 'installOutcome' "$Side raw result '$Label'") "unsupported:$expectedKind" "$Side raw result '$Label'.installOutcome"
    foreach ($outcomeName in @('ghostLoadOutcome', 'renderOutcome', 'inputOutcome', 'shioriOutcome')) {
        Assert-EqualString (Get-RequiredProperty $Raw $outcomeName "$Side raw result '$Label'") 'not-applicable' "$Side raw result '$Label'.$outcomeName"
    }
    $surfaceCount = Get-RequiredProperty $Raw 'surfaceCount' "$Side raw result '$Label'"
    Assert-JsonKind $surfaceCount @('number') "$Side raw result '$Label'.surfaceCount" | Out-Null
    if ($surfaceCount.Token -cne '0') { throw "$Side raw result '$Label'.surfaceCount must be exact JSON number 0" }
    $probe = Get-RequiredProperty $Raw 'dialogueProbe' "$Side raw result '$Label'"
    Assert-EqualString (Get-RequiredProperty $probe 'outcome' "$Side raw result '$Label'.dialogueProbe") 'not-applicable' "$Side raw result '$Label'.dialogueProbe.outcome"
}

function Assert-GhostEnvelope([object]$Row, [object]$Raw, [object]$Entry, [hashtable]$RulesByLabel, [string]$Side, [string]$Label) {
    $expectedKind = Get-RequiredProperty $Entry 'expectedKind' "manifest entry '$Label'"
    Assert-JsonKind $expectedKind @('string') "manifest entry '$Label'.expectedKind" | Out-Null
    if ([string]$expectedKind -cne 'ghost') { return }

    $observedKind = Get-RequiredProperty $Raw 'observedKind' "$Side raw result '$Label'"
    Assert-JsonKind $observedKind @('string') "$Side raw result '$Label'.observedKind" | Out-Null
    Assert-EqualString $observedKind 'ghost' "$Side raw result '$Label'.observedKind"

    if (Test-AcceptedNativeCheckpoint $Row $Raw $Entry $Side $Label) { return }
    $rule = $RulesByLabel[$Label]
    if ($null -eq $rule) { throw "$Side ghost result '$Label' has no exact manifest/SHA-bound envelope rule" }

    $rawClassification = Get-RequiredProperty $Raw 'classification' "$Side raw result '$Label'"
    $summaryClassification = Get-RequiredProperty $Row 'classification' "$Side summary result '$Label'"
    Assert-JsonKind $rawClassification @('string') "$Side raw result '$Label'.classification" | Out-Null
    Assert-JsonKind $summaryClassification @('string') "$Side summary result '$Label'.classification" | Out-Null
    Assert-ExactJsonValue $rawClassification $rule.classification "$Side raw result '$Label'.classification"
    Assert-ExactJsonValue $summaryClassification $rule.classification "$Side summary result '$Label'.classification"

    $probe = Get-RequiredProperty $Raw 'dialogueProbe' "$Side raw result '$Label'"
    Assert-JsonKind $probe @('object') "$Side raw result '$Label'.dialogueProbe" | Out-Null
    foreach ($fieldName in @('method', 'eventId', 'status', 'outcome', 'failure')) {
        Assert-ExactJsonValue (Get-RequiredProperty $probe $fieldName "$Side raw result '$Label'.dialogueProbe") $rule.$fieldName "$Side raw result '$Label'.dialogueProbe.$fieldName"
    }
}

function Assert-SuccessfulRun([object]$Summary, [hashtable]$RawByLabel, [hashtable]$EntriesByLabel, [hashtable]$GhostEnvelopeRulesByLabel, [int]$ExpectedSentinelCheckCount, [string]$ExpectedSentinelCheckDigest, [string]$Side) {
    $sentinels = Get-RequiredProperty $Summary 'sentinels' "$Side summary"
    $sentinelsPassed = Get-RequiredProperty $sentinels 'passed' "$Side sentinels"
    Assert-JsonKind $sentinelsPassed @('boolean') "$Side sentinels.passed" | Out-Null
    if ($sentinelsPassed -ne $true) { throw "$Side is not a successful run: sentinels.passed is not true" }
    $sentinelChecks = Get-RequiredProperty $sentinels 'checks' "$Side sentinels"
    Assert-JsonKind $sentinelChecks @('array') "$Side sentinels.checks" | Out-Null
    if (@($sentinelChecks).Count -ne $ExpectedSentinelCheckCount) {
        throw "$Side sentinel check count must be $ExpectedSentinelCheckCount, found $(@($sentinelChecks).Count)"
    }
    $sentinelCheckDigest = Get-SentinelCheckNameDigest @($sentinelChecks) $Side
    if ($sentinelCheckDigest -cne $ExpectedSentinelCheckDigest) {
        throw "$Side sentinel check digest does not match the reviewed exact name set"
    }
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
        Assert-DialogueOutcomeMirror $row $raw $Side $label
        Assert-NonGhostOutcomeEnvelope $row $raw $EntriesByLabel[$label] $Side $label
        Assert-GhostEnvelope $row $raw $EntriesByLabel[$label] $GhostEnvelopeRulesByLabel $Side $label
        $rawCleanup = if ($null -eq $raw.PSObject.Properties['cleanup']) { $null } else { $raw.cleanup }
        $acceptedNativeCheckpoint = Test-AcceptedNativeCheckpoint $row $raw $EntriesByLabel[$label] $Side $label
        if ($null -eq $rawCleanup) {
            if (-not $acceptedNativeCheckpoint) { throw "$Side raw result '$label' is missing cleanup outside the exact accepted native-crash checkpoint" }
        }
        else {
            Assert-JsonKind $rawCleanup.remainingTestOwnedPaths @('array') "$Side raw result '$label'.cleanup.remainingTestOwnedPaths" | Out-Null
            if (@($rawCleanup.remainingTestOwnedPaths).Count -ne 0) { throw "$Side is not a successful run: raw cleanup residue for '$label'" }
        }
    }
}

function Assert-RunIdentityMirrors([object]$Summary, [hashtable]$RowsByLabel, [hashtable]$RawByLabel, [hashtable]$EntriesByLabel, [string[]]$RawSourceMirrorLabels, [string]$Side) {
    $runId = Get-RequiredProperty $Summary 'runId' "$Side summary"
    Assert-JsonKind $runId @('string') "$Side summary runId" | Out-Null
    if ([string]$runId -notmatch '^[0-9a-f]{32}$') {
        throw "$Side summary runId is not the runner-emitted 32-character lowercase run identity"
    }

    $privateDataRoot = $null
    foreach ($label in @($RowsByLabel.Keys | Sort-Object -CaseSensitive)) {
        $row = $RowsByLabel[$label]
        $rowRunId = Get-RequiredProperty $row 'runId' "$Side summary result '$label'"
        Assert-JsonKind $rowRunId @('string') "$Side summary result '$label'.runId" | Out-Null
        if ([string]$rowRunId -cne [string]$runId) {
            throw "$Side summary result '$label'.runId must mirror the summary run identity"
        }

        $safeLabel = [string](Get-RequiredProperty $row 'safeLabel' "$Side summary result '$label'")
        $raw = $RawByLabel[$label]
        $narCorpusPath = Get-RequiredProperty $raw 'narCorpusPath' "$Side raw result '$label'"
        Assert-JsonKind $narCorpusPath @('string') "$Side raw result '$label'.narCorpusPath" | Out-Null
        $narCorpusPathPattern = '^(?<privateRoot>/data/(?:user/0|data)/com\.cattailsw\.nanidroid)/cache/nar-corpus-host/' + [regex]::Escape([string]$runId) + '/' + [regex]::Escape($safeLabel) + '/nanidroid-corpus\.nar\z'
        $narCorpusPathMatch = [regex]::Match([string]$narCorpusPath, $narCorpusPathPattern)
        if (-not $narCorpusPathMatch.Success) {
            throw "$Side raw result '$label'.narCorpusPath must match the exact runner path for the summary run identity"
        }
        if ($null -eq $privateDataRoot) {
            $privateDataRoot = $narCorpusPathMatch.Groups['privateRoot'].Value
        }
        elseif ($privateDataRoot -cne $narCorpusPathMatch.Groups['privateRoot'].Value) {
            throw "$Side raw result '$label'.narCorpusPath must use the same private data root as every raw result"
        }

        $nativeCheckpoint = Test-AcceptedNativeCheckpoint $row $raw $EntriesByLabel[$label] $Side $label
        $expectedPrivateSnapshot = "$($narCorpusPathMatch.Groups['privateRoot'].Value)/cache/nar-corpus-host/$runId/$safeLabel"
        $observedPrivateSnapshot = Get-RequiredProperty $row 'observedPrivateSnapshot' "$Side summary result '$label'"
        if ($nativeCheckpoint) {
            Assert-JsonKind $observedPrivateSnapshot @('array') "$Side accepted native checkpoint '$label'.observedPrivateSnapshot" | Out-Null
            if (@($observedPrivateSnapshot).Count -ne 0) { throw "$Side accepted native checkpoint '$label'.observedPrivateSnapshot must be empty" }
        } else {
            Assert-JsonKind $observedPrivateSnapshot @('string') "$Side summary result '$label'.observedPrivateSnapshot" | Out-Null
            Assert-EqualString $observedPrivateSnapshot $expectedPrivateSnapshot "$Side summary result '$label'.observedPrivateSnapshot"
        }
        $expectedTmpSnapshot = "/data/local/tmp/nanidroid-corpus/$runId/$safeLabel"
        $observedTmpSnapshot = Get-RequiredProperty $row 'observedTmpSnapshot' "$Side summary result '$label'"
        if ($nativeCheckpoint) {
            Assert-JsonKind $observedTmpSnapshot @('array') "$Side accepted native checkpoint '$label'.observedTmpSnapshot" | Out-Null
            if (@($observedTmpSnapshot).Count -ne 0) { throw "$Side accepted native checkpoint '$label'.observedTmpSnapshot must be empty" }
        } else {
            Assert-JsonKind $observedTmpSnapshot @('string') "$Side summary result '$label'.observedTmpSnapshot" | Out-Null
            Assert-EqualString $observedTmpSnapshot $expectedTmpSnapshot "$Side summary result '$label'.observedTmpSnapshot"
        }

        if ($RawSourceMirrorLabels -cnotcontains $label) {
            $summarySource = if ($null -eq $row.PSObject.Properties['evidence']) { $null } else { $row.evidence }
            $summarySourceSyntax = if ($null -eq $summarySource -or $null -eq $summarySource.PSObject.Properties['sourceSyntax']) { $null } else { $summarySource.sourceSyntax }
            if ($null -ne $summarySourceSyntax -and $null -ne $summarySourceSyntax.PSObject.Properties['scanRoot']) {
                throw "$Side summary result '$label'.evidence.sourceSyntax.scanRoot is not declared for this manifest/SHA-bound label"
            }
            $rawSource = if ($null -eq $raw.PSObject.Properties['evidence']) { $null } else { $raw.evidence }
            $rawSourceSyntax = if ($null -eq $rawSource -or $null -eq $rawSource.PSObject.Properties['sourceSyntax']) { $null } else { $rawSource.sourceSyntax }
            if ($null -ne $rawSourceSyntax -and $null -ne $rawSourceSyntax.PSObject.Properties['scanRoot']) {
                throw "$Side raw result '$label'.evidence.sourceSyntax.scanRoot is not declared for this manifest/SHA-bound label"
            }
        }

        foreach ($mirrorPath in $(if ($RawSourceMirrorLabels -contains $label) { @('evidence.sourceSyntax.scanRoot', 'sakura.source', 'kero.source') } else { @() })) {
            $node = $raw
            $found = $true
            foreach ($segment in $mirrorPath.Split('.')) {
                $property = if ($null -eq $node) { $null } else { $node.PSObject.Properties[$segment] }
                if ($null -eq $property) {
                    $found = $false
                    break
                }
                $node = $property.Value
            }
            if (-not $found) { throw "$Side raw result '$label'.$mirrorPath must be present for its manifest/SHA-bound label" }
            Assert-JsonKind $node @('string') "$Side raw result '$label'.$mirrorPath" | Out-Null
            if ($mirrorPath -ceq 'evidence.sourceSyntax.scanRoot') {
                $summaryScanRoot = Get-RequiredProperty (Get-RequiredProperty (Get-RequiredProperty $row 'evidence' "$Side summary result '$label'") 'sourceSyntax' "$Side summary result '$label'.evidence") 'scanRoot' "$Side summary result '$label'.evidence.sourceSyntax"
                Assert-JsonKind $summaryScanRoot @('string') "$Side summary result '$label'.evidence.sourceSyntax.scanRoot" | Out-Null
                $expectedArchivePrefix = ([string]$row.sha256).Substring(0, 16)
                $scanRootPattern = '^/data/data/com\.cattailsw\.nanidroid/cache/nar-corpus-host/' + [regex]::Escape([string]$runId) + '/' + [regex]::Escape($safeLabel) + '/probe-install/corpus-' + [regex]::Escape($expectedArchivePrefix) + '\z'
                if ([string]$node -notmatch $scanRootPattern) { throw "$Side raw result '$label'.evidence.sourceSyntax.scanRoot must match the exact runner scan-root path" }
                Assert-EqualString $summaryScanRoot ([string]$node) "$Side summary result '$label'.evidence.sourceSyntax.scanRoot raw mirror"
            }
            else {
                $surfaceName = if ($mirrorPath -ceq 'sakura.source') { 'surface(?:0|0000)' } else { 'surface(?:10|0010)' }
                $expectedSourcePattern = '^' + [regex]::Escape([string]$raw.evidence.sourceSyntax.scanRoot) + '/shell/master/' + $surfaceName + '\.png\z'
                if ([string]$node -notmatch $expectedSourcePattern) {
                    throw "$Side raw result '$label'.$mirrorPath must be an exact Sakura/Kero source descendant of its validated scan root"
                }
            }
        }
    }
    return [string]$runId
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
        [string[]]$RawSourceMirrorLabels,
        [hashtable]$GhostEnvelopeRulesByLabel,
        [int]$ExpectedSentinelCheckCount,
        [string]$ExpectedSentinelCheckDigest,
        [string]$ExpectedManifestSha
    )
    $resolvedRoot = [IO.Path]::GetFullPath($Root)
    if (-not (Test-Path -LiteralPath $resolvedRoot -PathType Container)) { throw "$Side evidence root does not exist: $resolvedRoot" }
    $summary = Read-JsonFile (Join-Path $resolvedRoot 'summary.json') "$Side summary"
    $summarySchemaVersion = Get-RequiredProperty $summary 'schemaVersion' "$Side summary"
    Assert-JsonKind $summarySchemaVersion @('string') "$Side summary schemaVersion" | Out-Null
    Assert-EqualString $summarySchemaVersion '2' "$Side summary schemaVersion"
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
    $entriesByLabel = @{}
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
        $rawSchemaVersion = Get-RequiredProperty $raw 'schemaVersion' "$Side raw result '$label'"
        Assert-JsonKind $rawSchemaVersion @('string') "$Side raw result '$label' schemaVersion" | Out-Null
        Assert-EqualString $rawSchemaVersion '2' "$Side raw result '$label' schemaVersion"
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
        $entriesByLabel[$label] = $entry
        if ($null -ne $raw.PSObject.Properties['dialogueProbe'] -and $null -ne $raw.dialogueProbe.PSObject.Properties['onBootContext']) {
            Get-ValidatedOnBootContext $raw $Side $label | Out-Null
            foreach ($mirror in @($row.PSObject.Properties['dialogueProbe'], $row.requiredEvidencePayload.PSObject.Properties['dialogueProbe'])) {
                if ($null -ne $mirror) {
                    $difference = Find-FirstDifference $raw.dialogueProbe.onBootContext $mirror.Value.onBootContext 'dialogueProbe.onBootContext'
                    if ($difference) { throw "$Side raw/summary OnBoot context mirror mismatch for '$label' at $difference" }
                }
            }
        }
    }
    Assert-SuccessfulRun $summary $rawByLabel $entriesByLabel $GhostEnvelopeRulesByLabel $ExpectedSentinelCheckCount $ExpectedSentinelCheckDigest $Side
    $runIdentity = Assert-RunIdentityMirrors $summary $rowByLabel $rawByLabel $entriesByLabel $RawSourceMirrorLabels $Side
    $evidenceFingerprint = Get-EvidenceFingerprint $resolvedRoot $ExpectedRawFiles $ExpectedScreenshotFiles
    return [pscustomobject]@{ Root = $resolvedRoot; Summary = $summary; Rows = $rowByLabel; Raw = $rawByLabel; Entries = $entriesByLabel; RunIdentity = $runIdentity; EvidenceFingerprint = $evidenceFingerprint }
}

function Get-ValidatedOnBootContext([object]$Raw, [string]$Side, [string]$Label) {
    $probe = Get-RequiredProperty $Raw 'dialogueProbe' "$Side raw result '$Label'"
    Assert-JsonKind $probe @('object') "$Side raw result '$Label'.dialogueProbe" | Out-Null
    $context = Get-RequiredProperty $probe 'onBootContext' "$Side raw result '$Label'.dialogueProbe"
    Assert-JsonKind $context @('object') "$Side raw result '$Label'.dialogueProbe.onBootContext" | Out-Null
    Assert-EqualString (Get-RequiredProperty $context 'profileState' "$Side raw result '$Label'.dialogueProbe.onBootContext") 'fresh' "$Side raw result '$Label'.dialogueProbe.onBootContext.profileState"
    Assert-EqualString (Get-RequiredProperty $context 'username' "$Side raw result '$Label'.dialogueProbe.onBootContext") '' "$Side raw result '$Label'.dialogueProbe.onBootContext.username"
    $birthdayConfigured = Get-RequiredProperty $context 'birthdayConfigured' "$Side raw result '$Label'.dialogueProbe.onBootContext"
    Assert-JsonKind $birthdayConfigured @('boolean') "$Side raw result '$Label'.dialogueProbe.onBootContext.birthdayConfigured" | Out-Null
    if ($birthdayConfigured -ne $false) { throw "$Side raw result '$Label'.dialogueProbe.onBootContext.birthdayConfigured must be false" }
    $beforeText = Get-RequiredProperty $context 'localClockBefore' "$Side raw result '$Label'.dialogueProbe.onBootContext"
    $afterText = Get-RequiredProperty $context 'localClockAfter' "$Side raw result '$Label'.dialogueProbe.onBootContext"
    Assert-JsonKind $beforeText @('string') "$Side raw result '$Label'.dialogueProbe.onBootContext.localClockBefore" | Out-Null
    Assert-JsonKind $afterText @('string') "$Side raw result '$Label'.dialogueProbe.onBootContext.localClockAfter" | Out-Null
    if ([string]$beforeText -notmatch '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]+(?:\.[0-9]+)?[+-][0-9]{2}:[0-9]{2}$' -or [string]$afterText -notmatch '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]+(?:\.[0-9]+)?[+-][0-9]{2}:[0-9]{2}$') {
        throw "$Side raw result '$Label'.dialogueProbe.onBootContext must use device-local ISO-8601 offsets"
    }
    $before = [DateTimeOffset]::Parse([string]$beforeText, [Globalization.CultureInfo]::InvariantCulture)
    $after = [DateTimeOffset]::Parse([string]$afterText, [Globalization.CultureInfo]::InvariantCulture)
    if ($after -lt $before) { throw "$Side raw result '$Label' OnBoot clock bracket is reversed" }
    if ($before.Year -ne $after.Year -or $before.Month -ne $after.Month -or $before.Day -ne $after.Day -or $before.Hour -ne $after.Hour -or $before.Minute -ne $after.Minute) {
        throw "$Side raw result '$Label' OnBoot clock bracket crosses a predicate boundary"
    }
    return $before
}

function Assert-SuccessfulOnBootEnvelope([object]$Raw, [string]$Side, [string]$Label) {
    $probe = Get-RequiredProperty $Raw 'dialogueProbe' "$Side raw result '$Label'"
    Assert-JsonKind $probe @('object') "$Side raw result '$Label'.dialogueProbe" | Out-Null
    Assert-EqualString (Get-RequiredProperty $probe 'method' "$Side raw result '$Label'.dialogueProbe") 'GET' "$Side raw result '$Label'.dialogueProbe.method"
    Assert-EqualString (Get-RequiredProperty $probe 'eventId' "$Side raw result '$Label'.dialogueProbe") 'OnBoot' "$Side raw result '$Label'.dialogueProbe.eventId"
    $status = Get-RequiredProperty $probe 'status' "$Side raw result '$Label'.dialogueProbe"
    Assert-JsonKind $status @('number') "$Side raw result '$Label'.dialogueProbe.status" | Out-Null
    if ($status.Token -cne '200') { throw "$Side raw result '$Label'.dialogueProbe.status must be exact JSON number 200" }
    Assert-EqualString (Get-RequiredProperty $probe 'outcome' "$Side raw result '$Label'.dialogueProbe") 'success' "$Side raw result '$Label'.dialogueProbe.outcome"
    if ($null -ne (Get-RequiredProperty $probe 'failure' "$Side raw result '$Label'.dialogueProbe")) { throw "$Side raw result '$Label'.dialogueProbe.failure must be null" }
}

function Test-EarthquakePredicate([string]$Predicate, [DateTimeOffset]$Clock) {
    switch ($Predicate) {
        'early-not-special-date' { return $Clock.Hour -lt 6 -and -not (($Clock.Month -eq 6 -and $Clock.Day -eq 6) -or ($Clock.Month -eq 7 -and $Clock.Day -eq 4)) }
        'date-06-06' { return $Clock.Month -eq 6 -and $Clock.Day -eq 6 }
        'date-07-04' { return $Clock.Month -eq 7 -and $Clock.Day -eq 4 }
        default { throw "unsupported Earthquake predicate '$Predicate'" }
    }
}

$script:LoboSingularWords = @('butterfly','larva','phaeton','lobo','angle','cerberus','felid','chordate','product','knight','spittle','ass','bullion','minerall','armour','ark','beacon','meadowgrass','strawberry','pachyderm','advent','memory','fruit','needle','thread','fate','crocodile','acetaminophen','sulfur','aegis','aglaeca','bealusi','bruin','watership','hazelnut','quid','skyscraper','artifact','acursed-amulet','bellows','dread','fear','joy','love','flesh','tack','psychopomp','bug','Mojito','psyche','caladbolg')
$script:LoboPluralWords = @('butterflies','larvae','phaetons','lobos','angles','cerberi','felidae','chordates','products','knights','bullion','mineralls','armours','arks','beacons','meadowgrass','strawberries','pachydermata','advents','devils','memories','fruits','cryptids','beads','apple-cars','hydroflasks','chainsaws','fiends','cheese wheels','spinning wheels','aerospace engineers','thing-a-ma-jigs','wings','recursive bows','rottenous eggs','wounds','gouges','maws','psychopomps','flowers','bluebells','commies')
$script:LoboAdjectives = @('frightening','devasting','incomprehensible','sordid','aweful','abhorrent','calamitous','unpropitious','harrowing','unpreventable','portentious','ineluctable','decadent','effete','languorous','licentious','bacchanalian','lascivious','summery','redolent','pestilential','putrefactive','effervescing','vituperative','auriferous','virulent','pernicious','recrudescent','loathesome','misogynous','caustic','atavistic','belluine','hesperian','diaphanous','heterobasidiomycetous','sphenoid','elfin','morphous','justified','star-crossed','aluminous','argentine','goth','viscous','long','hylic','presidential','fruity','bougie','Icarus-like','dreadful','lackidasical','ensouled','Greek','Roman','wet')
$script:LoboPresentVerbs = @('hark','look','wish','finish','exercise','revolve','arrive','hoist','render','pardon','liquify','hydrate','offend','beg','sniff','resolve','prowl','self-flagellate','grovel','kneel','spit','sum','divide','multiply','fruit','growl','affix','ambulate','manipulate','sculpt','fraternize')
$script:LoboPresentSingularVerbs = @('harks','looks','wishes','finishes','exercises','revolves','arrives','hoists','renders','pardons','liquifies','hydrates','offends','begs','sniffs','resolves','prowls','self-flagellates','grovels','kneels','spits','sums','divides','multiplies','fruits','growls','affixes','ambulates','manipulates','sculpts','fraternizes')

function Test-LoboNoun([string]$Value, [bool]$Plural) {
    $words = if ($Plural) { $script:LoboPluralWords } else { $script:LoboSingularWords }
    if ($words -ccontains $Value) { return $true }
    $prefix = if ($Plural) { 'cans of ' } else { 'bottle of compressed ' }
    if (-not $Value.StartsWith($prefix, [StringComparison]::Ordinal)) { return $false }
    $remainder = $Value.Substring($prefix.Length)
    if ([string]::IsNullOrEmpty($remainder) -or $remainder.Length -ge $Value.Length) { return $false }
    return Test-LoboNoun $remainder (-not $Plural)
}

function Test-LoboOnBootValue([string]$Value) {
    $prefix = '\1\s[10]\0\s[0]\1\s[-1]\0'
    if (-not $Value.StartsWith($prefix, [StringComparison]::Ordinal)) { return $false }
    $body = $Value.Substring($prefix.Length)
    $harkPrefix = 'Hark! What brings this '
    $harkSuffix = ' to grace my presence?'
    if ($body.StartsWith($harkPrefix, [StringComparison]::Ordinal) -and $body.EndsWith($harkSuffix, [StringComparison]::Ordinal)) {
        $middle = $body.Substring($harkPrefix.Length, $body.Length - $harkPrefix.Length - $harkSuffix.Length)
        $space = $middle.IndexOf(' ', [StringComparison]::Ordinal)
        return $space -gt 0 -and ($script:LoboAdjectives -ccontains $middle.Substring(0, $space)) -and (Test-LoboNoun $middle.Substring($space + 1) $false)
    }
    $listenPrefix = 'Listen here, you little '
    $listenSeparator = ', you listen to me.\w8\w8\w8 The '
    $listenSuffix = ', that is your fortune.'
    if ($body.StartsWith($listenPrefix, [StringComparison]::Ordinal) -and $body.EndsWith($listenSuffix, [StringComparison]::Ordinal)) {
        $middle = $body.Substring($listenPrefix.Length, $body.Length - $listenPrefix.Length - $listenSuffix.Length)
        $separatorIndex = $middle.IndexOf($listenSeparator, [StringComparison]::Ordinal)
        if ($separatorIndex -le 0) { return $false }
        $firstNoun = $middle.Substring(0, $separatorIndex)
        $tail = $middle.Substring($separatorIndex + $listenSeparator.Length)
        $verbSeparator = ' that '
        $verbIndex = $tail.LastIndexOf($verbSeparator, [StringComparison]::Ordinal)
        return $verbIndex -gt 0 -and (Test-LoboNoun $firstNoun $false) -and (Test-LoboNoun $tail.Substring(0, $verbIndex) $false) -and ($script:LoboPresentSingularVerbs -ccontains $tail.Substring($verbIndex + $verbSeparator.Length))
    }
    return $script:LoboPresentVerbs -ccontains ($body.TrimEnd('!')) -and $body.EndsWith('!', [StringComparison]::Ordinal) -and $body.IndexOf('!', [StringComparison]::Ordinal) -eq ($body.Length - 1)
}

function Assert-SnakeStructuralOnlyValue {
    param([object]$Run, [object]$Rule, [string]$Side)
    $label = [string]$Rule.label
    $raw = $Run.Raw[$label]
    $row = $Run.Rows[$label]
    $probe = Get-RequiredProperty $raw 'dialogueProbe' "$Side raw result '$label'"
    Assert-JsonKind $probe @('object') "$Side raw result '$label'.dialogueProbe" | Out-Null
    Get-ValidatedOnBootContext $raw $Side $label | Out-Null
    $value = Get-RequiredProperty $probe 'value' "$Side raw result '$label'.dialogueProbe"
    Assert-JsonKind $value @('string') "$Side raw result '$label'.dialogueProbe.value" | Out-Null
    if ([string]::IsNullOrWhiteSpace([string]$value)) { throw "$Side raw result '$label'.dialogueProbe.value must contain nonblank lexer-validated speaker text" }

    $safety = Get-RequiredProperty $raw 'snakeOnBootStructuralSafety' "$Side raw result '$label'"
    Assert-JsonKind $safety @('object') "$Side raw result '$label'.snakeOnBootStructuralSafety" | Out-Null
    Assert-EqualString (Get-RequiredProperty $safety 'policy' "$Side raw result '$label'.snakeOnBootStructuralSafety") 'snake-onboot-raw-sakurascript-v1' "$Side raw result '$label'.snakeOnBootStructuralSafety.policy"
    $contentCompared = Get-RequiredProperty $safety 'contentCompared' "$Side raw result '$label'.snakeOnBootStructuralSafety"
    Assert-JsonKind $contentCompared @('boolean') "$Side raw result '$label'.snakeOnBootStructuralSafety.contentCompared" | Out-Null
    if ($contentCompared -ne $false) { throw "$Side raw result '$label'.snakeOnBootStructuralSafety.contentCompared must be false" }
    $accepted = Get-RequiredProperty $safety 'accepted' "$Side raw result '$label'.snakeOnBootStructuralSafety"
    Assert-JsonKind $accepted @('boolean') "$Side raw result '$label'.snakeOnBootStructuralSafety.accepted" | Out-Null
    if ($accepted -ne $true) { throw "$Side raw result '$label'.snakeOnBootStructuralSafety.accepted must be true" }
    $terminal = Get-RequiredProperty $safety 'terminal' "$Side raw result '$label'.snakeOnBootStructuralSafety"
    Assert-JsonKind $terminal @('string') "$Side raw result '$label'.snakeOnBootStructuralSafety.terminal" | Out-Null
    if (@('exact-e', 'eof') -cnotcontains [string]$terminal) { throw "$Side raw result '$label'.snakeOnBootStructuralSafety.terminal is not allowed by exact-e-or-eof" }
    Assert-EqualString (Get-RequiredProperty $Rule 'terminalPolicy' "comparison contract Snake rule '$label'") 'exact-e-or-eof' "comparison contract Snake rule '$label' terminal policy"

    $expectedSurfaceTokens = @('0', '1', '2', '4', '5', '8', '9', '10', '13', '14', '15', '17', '18', '19', '30', '32', '35')
    $allowedSurfaces = Get-RequiredProperty $safety 'allowedSurfaces' "$Side raw result '$label'.snakeOnBootStructuralSafety"
    Assert-JsonKind $allowedSurfaces @('array') "$Side raw result '$label'.snakeOnBootStructuralSafety.allowedSurfaces" | Out-Null
    $actualSurfaceTokens = @($allowedSurfaces | ForEach-Object {
        Assert-JsonKind $_ @('number') "$Side raw result '$label'.snakeOnBootStructuralSafety.allowedSurfaces" | Out-Null
        [string]$_.Token
    })
    Assert-ExactSet $actualSurfaceTokens $expectedSurfaceTokens "$Side raw result '$label'.snakeOnBootStructuralSafety.allowedSurfaces"
    $ruleSurfaces = Get-RequiredProperty $Rule 'allowedSurfaces' "comparison contract Snake rule '$label'"
    Assert-JsonKind $ruleSurfaces @('array') "comparison contract Snake rule '$label'.allowedSurfaces" | Out-Null
    $ruleSurfaceTokens = @($ruleSurfaces | ForEach-Object {
        Assert-JsonKind $_ @('number') "comparison contract Snake rule '$label'.allowedSurfaces" | Out-Null
        [string]$_.Token
    })
    Assert-ExactSet $ruleSurfaceTokens $expectedSurfaceTokens "comparison contract Snake rule '$label'.allowedSurfaces"

    $expectedFormattingTokens = @('\f[italic,true]', '\f[italic,false]')
    $allowedFormattingTokens = Get-RequiredProperty $safety 'allowedFormattingTokens' "$Side raw result '$label'.snakeOnBootStructuralSafety"
    Assert-JsonKind $allowedFormattingTokens @('array') "$Side raw result '$label'.snakeOnBootStructuralSafety.allowedFormattingTokens" | Out-Null
    $actualFormattingTokens = @($allowedFormattingTokens | ForEach-Object {
        Assert-JsonKind $_ @('string') "$Side raw result '$label'.snakeOnBootStructuralSafety.allowedFormattingTokens" | Out-Null
        [string]$_
    })
    Assert-ExactSet $actualFormattingTokens $expectedFormattingTokens "$Side raw result '$label'.snakeOnBootStructuralSafety.allowedFormattingTokens"
    $ruleFormattingTokens = Get-RequiredProperty $Rule 'allowedFormattingTokens' "comparison contract Snake rule '$label'"
    Assert-JsonKind $ruleFormattingTokens @('array') "comparison contract Snake rule '$label'.allowedFormattingTokens" | Out-Null
    $ruleFormattingTokens = @($ruleFormattingTokens | ForEach-Object {
        Assert-JsonKind $_ @('string') "comparison contract Snake rule '$label'.allowedFormattingTokens" | Out-Null
        [string]$_
    })
    Assert-ExactSet $ruleFormattingTokens $expectedFormattingTokens "comparison contract Snake rule '$label'.allowedFormattingTokens"

    $canary = Get-RequiredProperty $raw 'snakeFirstBootCanary' "$Side raw result '$label'"
    Assert-JsonKind $canary @('object') "$Side raw result '$label'.snakeFirstBootCanary" | Out-Null
    $freshInstance = Get-RequiredProperty $canary 'freshInstance' "$Side raw result '$label'.snakeFirstBootCanary"
    Assert-JsonKind $freshInstance @('boolean') "$Side raw result '$label'.snakeFirstBootCanary.freshInstance" | Out-Null
    if ($freshInstance -ne $true) { throw "$Side raw result '$label'.snakeFirstBootCanary.freshInstance must be true" }
    $independentInstanceCount = Get-RequiredProperty $canary 'independentInstanceCount' "$Side raw result '$label'.snakeFirstBootCanary"
    Assert-JsonKind $independentInstanceCount @('number') "$Side raw result '$label'.snakeFirstBootCanary.independentInstanceCount" | Out-Null
    if ($independentInstanceCount.Token -cne '2') { throw "$Side raw result '$label'.snakeFirstBootCanary.independentInstanceCount must be exact JSON number 2" }
    $request = Get-RequiredProperty $canary 'request' "$Side raw result '$label'.snakeFirstBootCanary"
    Assert-JsonKind $request @('object') "$Side raw result '$label'.snakeFirstBootCanary.request" | Out-Null
    Assert-EqualString (Get-RequiredProperty $request 'method' "$Side raw result '$label'.snakeFirstBootCanary.request") 'GET' "$Side raw result '$label'.snakeFirstBootCanary.request.method"
    Assert-EqualString (Get-RequiredProperty $request 'eventId' "$Side raw result '$label'.snakeFirstBootCanary.request") 'OnFirstBoot' "$Side raw result '$label'.snakeFirstBootCanary.request.eventId"
    $references = Get-RequiredProperty $request 'references' "$Side raw result '$label'.snakeFirstBootCanary.request"
    Assert-JsonKind $references @('array') "$Side raw result '$label'.snakeFirstBootCanary.request.references" | Out-Null
    if ((Find-FirstDifference @($references) @('0') 'references')) { throw "$Side raw result '$label'.snakeFirstBootCanary.request.references must be exactly ['0']" }
    $response = Get-RequiredProperty $canary 'response' "$Side raw result '$label'.snakeFirstBootCanary"
    Assert-JsonKind $response @('object') "$Side raw result '$label'.snakeFirstBootCanary.response" | Out-Null
    $status = Get-RequiredProperty $response 'status' "$Side raw result '$label'.snakeFirstBootCanary.response"
    Assert-JsonKind $status @('number') "$Side raw result '$label'.snakeFirstBootCanary.response.status" | Out-Null
    if ($status.Token -cne '200') { throw "$Side raw result '$label'.snakeFirstBootCanary.response.status must be exact JSON number 200" }
    Assert-EqualString (Get-RequiredProperty $response 'outcome' "$Side raw result '$label'.snakeFirstBootCanary.response") 'success' "$Side raw result '$label'.snakeFirstBootCanary.response.outcome"
    if ($null -ne (Get-RequiredProperty $response 'failure' "$Side raw result '$label'.snakeFirstBootCanary.response")) { throw "$Side raw result '$label'.snakeFirstBootCanary.response.failure must be null" }
    $canaryValue = Get-RequiredProperty $response 'value' "$Side raw result '$label'.snakeFirstBootCanary.response"
    Assert-JsonKind $canaryValue @('string') "$Side raw result '$label'.snakeFirstBootCanary.response.value" | Out-Null
    $canaryHash = Get-RequiredProperty $response 'valueUtf8Sha256' "$Side raw result '$label'.snakeFirstBootCanary.response"
    Assert-JsonKind $canaryHash @('string') "$Side raw result '$label'.snakeFirstBootCanary.response.valueUtf8Sha256" | Out-Null
    Assert-EqualString $canaryHash (Get-StringSha256 ([string]$canaryValue)) "$Side raw result '$label'.snakeFirstBootCanary.response.valueUtf8Sha256"
    $canaryLength = Get-RequiredProperty $response 'valueUtf8ByteLength' "$Side raw result '$label'.snakeFirstBootCanary.response"
    Assert-JsonKind $canaryLength @('number') "$Side raw result '$label'.snakeFirstBootCanary.response.valueUtf8ByteLength" | Out-Null
    if ($canaryLength.Token -cne [string][Text.Encoding]::UTF8.GetByteCount([string]$canaryValue)) { throw "$Side raw result '$label'.snakeFirstBootCanary.response.valueUtf8ByteLength mismatch" }
    $diagnostics = Get-RequiredProperty $response 'tokenizerDiagnostics' "$Side raw result '$label'.snakeFirstBootCanary.response"
    Assert-JsonKind $diagnostics @('array') "$Side raw result '$label'.snakeFirstBootCanary.response.tokenizerDiagnostics" | Out-Null
    foreach ($diagnostic in @($diagnostics)) { Assert-JsonKind $diagnostic @('string') "$Side raw result '$label'.snakeFirstBootCanary.response.tokenizerDiagnostics" | Out-Null }

    foreach ($mirrorName in @('snakeOnBootStructuralSafety', 'snakeFirstBootCanary')) {
        $summaryMirror = Get-RequiredProperty $row $mirrorName "$Side summary result '$label'"
        $difference = Find-FirstDifference $raw.$mirrorName $summaryMirror "summary.$mirrorName"
        if ($difference) { throw "$Side raw/summary $mirrorName mirror mismatch for '$label' at $difference" }
    }
}

function Assert-StochasticValue {
    param([object]$Run, [object]$Rule, [string]$Side)
    $label = [string]$Rule.label
    $raw = $Run.Raw[$label]
    Assert-SuccessfulOnBootEnvelope $raw $Side $label
    Assert-EqualString $raw.sha256 ([string]$Rule.archiveSha256) "$Side stochastic archive SHA for '$label'"
    $value = [string]$raw.dialogueProbe.value
    $valueHash = Get-StringSha256 $value
    if ($null -ne $Rule.PSObject.Properties['specializedValidator']) {
        if ([string]$Rule.specializedValidator -ceq 'snake-onboot-structural-v1') {
            Assert-SnakeStructuralOnlyValue $Run $Rule $Side
        }
        elseif ([string]$Rule.specializedValidator -cne 'lobo-onboot-v1' -or -not (Test-LoboOnBootValue $value)) {
            throw "$Side unreviewed specialized stochastic dialogueProbe.value for '$label'"
        }
    }
    elseif ($null -ne $Rule.PSObject.Properties['allowedVariants']) {
        $clock = Get-ValidatedOnBootContext $raw $Side $label
        $diagnostics = Get-RequiredProperty (Get-RequiredProperty $raw 'dialogueProbe' "$Side raw result '$label'") 'tokenizerDiagnostics' "$Side raw result '$label'.dialogueProbe"
        Assert-JsonKind $diagnostics @('array') "$Side raw result '$label'.dialogueProbe.tokenizerDiagnostics" | Out-Null
        $matchingVariants = @($Rule.allowedVariants | Where-Object {
            if ([string]$_.valueUtf8Sha256 -cne $valueHash) { return $false }
            $expected = @($_.tokenizerDiagnostics | ForEach-Object { [string]$_ })
            $actual = @($diagnostics | ForEach-Object { [string]$_ })
            return (Find-FirstDifference $actual $expected 'tokenizerDiagnostics') -eq $null -and (Test-EarthquakePredicate ([string]$_.predicate) $clock)
        })
        if ($matchingVariants.Count -lt 1) {
            throw "$Side unreviewed stochastic dialogueProbe.value/tokenizerDiagnostics pair for '$label' (decoded UTF-8 SHA-256 $valueHash)"
        }
    }
    elseif (@($Rule.allowedUtf8Sha256 | Where-Object { [string]$_ -ceq $valueHash }).Count -ne 1) {
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
        $expectedMatches = if ($pathText.Contains('[]')) { $Run.Rows.Count } else { 1 }
        Set-PathPatternValue $summary $pathText { param($value) $kind = Assert-NormalizationKind $Contract 'summary' $pathText $value; "<RUN_ID:$kind>" } -Context 'summary' -ExpectedMatchCount $expectedMatches
    }
    foreach ($path in @($Contract.normalization.summaryTimestampPaths)) {
        $pathText = [string]$path
        $expectedMatches = if ($pathText.Contains('[]')) { $Run.Rows.Count } else { 1 }
        Set-PathPatternValue $summary $pathText { param($value) $kind = Assert-NormalizationKind $Contract 'summary' $pathText $value; "<TIMESTAMP:$kind>" } -Context 'summary' -ExpectedMatchCount $expectedMatches
    }
    foreach ($path in @($Contract.normalization.summaryDurationPaths)) {
        $pathText = [string]$path
        $expectedMatches = if ($pathText.Contains('[]')) { $Run.Rows.Count } else { 1 }
        Set-PathPatternValue $summary $pathText { param($value) $kind = Assert-NormalizationKind $Contract 'summary' $pathText $value; "<DURATION:$kind>" } -Context 'summary' -ExpectedMatchCount $expectedMatches
    }
    foreach ($path in @($Contract.normalization.summaryReportRootPaths)) {
        $pathText = [string]$path
        Set-PathPatternValue $summary $pathText { param($value) $kind = Assert-NormalizationKind $Contract 'summary' $pathText $value; "<REPORT_PATH:$kind>" } -Context 'summary'
    }
    foreach ($path in @($Contract.normalization.summaryRunOwnedStringPaths)) {
        $pathText = [string]$path
        $expectedMatches = if ($pathText.Contains('[]')) { $Run.Rows.Count } else { 1 }
        Set-PathPatternValue $summary $pathText {
            param($value)
            $kind = Assert-NormalizationKind $Contract 'summary' $pathText $value
            if ($pathText.EndsWith('.output') -or $pathText.EndsWith('.error')) {
                return ([string]$value -replace [regex]::Escape([string]$Run.Summary.runId), '<RUN_ID>' -replace 'Time:\s+[0-9]+(?:\.[0-9]+)?', 'Time: <DURATION>')
            }
            return "<RUN_OWNED_PATH:$kind>"
        } -Context 'summary' -ExpectedMatchCount $expectedMatches
    }
    foreach ($row in @($summary.results)) {
        $label = [string]$row.label
        if (Test-AcceptedNativeCheckpoint $row $Run.Raw[$label] $Run.Entries[$label] 'canonical' $label) { continue }
        foreach ($propertyName in @('observedPrivateSnapshot', 'observedTmpSnapshot')) {
            $value = Get-RequiredProperty $row $propertyName "summary result '$($row.label)'"
            $pathText = if ($propertyName -ceq 'observedPrivateSnapshot') { [string]$Contract.normalization.summaryObservedPrivateSnapshotPath } else { [string]$Contract.normalization.summaryObservedTmpSnapshotPath }
            $kind = Assert-NormalizationKind $Contract 'summary' $pathText $value
            $row.$propertyName = "<RUN_OWNED_PATH:$kind>"
        }
    }
    foreach ($label in @($Contract.normalization.summarySourceArchiveSha256.PSObject.Properties | ForEach-Object { $_.Name })) {
        Set-PathPatternValue (@($summary.results | Where-Object { [string]$_.label -ceq $label })[0]) 'evidence.sourceSyntax.scanRoot' { param($value) $kind = Assert-NormalizationKind $Contract 'summary' 'results[].evidence.sourceSyntax.scanRoot' $value; "<RUN_OWNED_PATH:$kind>" } -Context "summary result '$label'" -ExpectedMatchCount 1
    }

    foreach ($identityPath in @('production.commit', 'production.debugApkSha256', 'harness.commit', 'harness.tree', 'harness.runnerSha256', 'harness.instrumentationSourceSha256', 'harness.testApkSha256', 'git.commit', 'apks.debugSha256', 'apks.testSha256')) {
        Set-PathPatternValue $summary $identityPath { '<VALIDATED_IDENTITY>' } -Context 'summary'
    }

    foreach ($label in @($rawByLabel.Keys)) {
        $raw = $rawByLabel[$label]
        foreach ($path in @($Contract.normalization.rawRunOwnedStringPaths)) {
            $pathText = [string]$path
            Set-PathPatternValue $raw $pathText { param($value) $kind = Assert-NormalizationKind $Contract 'raw' $pathText $value; "<RUN_OWNED_PATH:$kind>" } -Context "raw[$label]"
        }
        if ($null -ne $raw.dialogueProbe -and $null -ne $raw.dialogueProbe.PSObject.Properties['onBootContext']) {
            $raw.dialogueProbe.onBootContext = '<VALIDATED_ONBOOT_CONTEXT>'
        }
    }

    foreach ($path in @($Contract.normalization.rawRunOwnedStringSelectors)) {
        $pathText = [string]$path
        foreach ($label in @($Contract.normalization.rawSourceArchiveSha256.PSObject.Properties | ForEach-Object { $_.Name })) {
            Set-PathPatternValue $rawByLabel[$label] $pathText { param($value) $kind = Assert-NormalizationKind $Contract 'raw' $pathText $value; "<RUN_OWNED_PATH:$kind>" } -Context "raw[$label]" -ExpectedMatchCount 1
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
        } -Context "raw[$label]"
    }

    foreach ($rule in @($Contract.stochasticDialogueValues)) {
        $label = [string]$rule.label
        $rawByLabel[$label].dialogueProbe.value = '<REVIEWED_STOCHASTIC_VALUE>'
        if ($null -ne $rule.PSObject.Properties['allowedVariants']) {
            $rawByLabel[$label].dialogueProbe.tokenizerDiagnostics = @('<REVIEWED_STOCHASTIC_DIAGNOSTICS>')
            $rawByLabel[$label].dialogueProbe.onBootContext = '<VALIDATED_ONBOOT_CONTEXT>'
        }
        $summaryRow = @($summary.results | Where-Object { [string]$_.label -ceq $label })[0]
        if ($null -ne $summaryRow.requiredEvidencePayload.PSObject.Properties['dialogueProbe']) {
            $summaryRow.requiredEvidencePayload.dialogueProbe.value = '<REVIEWED_STOCHASTIC_VALUE>'
            if ($null -ne $summaryRow.requiredEvidencePayload.dialogueProbe.PSObject.Properties['onBootContext']) {
                $summaryRow.requiredEvidencePayload.dialogueProbe.onBootContext = '<VALIDATED_ONBOOT_CONTEXT>'
            }
        }
        if ($null -ne $rule.PSObject.Properties['summaryMirrors']) {
            $mirror = $rule.summaryMirrors
            $check = @($summary.sentinels.checks | Where-Object { [string]$_.name -ceq [string]$mirror.sentinelName })[0]
            $check.([string]$mirror.sentinelProperty) = '<REVIEWED_STOCHASTIC_VALUE>'
        }
        if ($null -ne $rule.PSObject.Properties['specializedValidator'] -and [string]$rule.specializedValidator -ceq 'snake-onboot-structural-v1') {
            $rawByLabel[$label].snakeOnBootStructuralSafety.terminal = '<VALIDATED_SNAKE_TERMINAL>'
            $summaryRow.snakeOnBootStructuralSafety.terminal = '<VALIDATED_SNAKE_TERMINAL>'
        }
    }
    foreach ($summaryRow in @($summary.results)) {
        if ($null -ne $summaryRow.PSObject.Properties['dialogueProbe'] -and $null -ne $summaryRow.dialogueProbe.PSObject.Properties['onBootContext']) {
            $summaryRow.dialogueProbe.onBootContext = '<VALIDATED_ONBOOT_CONTEXT>'
        }
        if ($null -ne $summaryRow.requiredEvidencePayload.PSObject.Properties['dialogueProbe'] -and $null -ne $summaryRow.requiredEvidencePayload.dialogueProbe.PSObject.Properties['onBootContext']) {
            $summaryRow.requiredEvidencePayload.dialogueProbe.onBootContext = '<VALIDATED_ONBOOT_CONTEXT>'
        }
    }
    return [pscustomobject]@{ Summary = $summary; Raw = $rawByLabel }
}

function Assert-ComparisonCategoryClaims([object]$Claims, [string]$Context) {
    Assert-JsonKind $Claims @('object') "$Context comparisonCategories" | Out-Null
    $expectedTokens = [ordered]@{
        rawEnvelopeValidatedCount = '23'
        literalEqualityCount = '16'
        stochasticDialogueContractCount = '4'
        snakeStructuralOnlyCount = '3'
        snakeCanaryExactCount = '3'
        screenshotHashEqualityCount = '23'
        dialogueContentContractValidated = '20'
    }
    foreach ($name in $expectedTokens.Keys) {
        $value = Get-RequiredProperty $Claims $name "$Context comparisonCategories"
        Assert-JsonKind $value @('number') "$Context comparisonCategories.$name" | Out-Null
        if ($value.Token -cne $expectedTokens[$name]) { throw "$Context comparisonCategories.$name does not match the reviewed exact value" }
    }
    Assert-ExactSet @($Claims.stochasticDialogueLabels | ForEach-Object { [string]$_ }) @('2elf-2.46', 'Watchdog Bancho', 'Earthquake Rescue Duo', 'LOBO') "$Context stochastic dialogue label set"
    Assert-ExactSet @($Claims.snakeStructuralOnlyLabels | ForEach-Object { [string]$_ }) @('Snake and Otacon V1.2.1', 'Snake and Otacon V1.3.1', 'Snake_Otacon_1.3.1b') "$Context Snake structural-only label set"
}

function Assert-BaseBasePrerequisite([string]$Path, [string]$ManifestSha, [string]$ContractSha, [object]$Device, [string]$BaseEvidenceFingerprint) {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'BaseCandidate comparison requires -BaseBaseReportPath' }
    $report = Read-JsonFile $Path 'base/base prerequisite report'
    $reportSchemaVersion = Get-RequiredProperty $report 'schemaVersion' 'base/base prerequisite report'
    Assert-JsonKind $reportSchemaVersion @('string') 'base/base prerequisite report schemaVersion' | Out-Null
    Assert-EqualString $reportSchemaVersion '2' 'base/base prerequisite report schemaVersion'
    if ($report.passed -ne $true -or [string]$report.comparisonKind -cne 'BaseBase') { throw 'base/base prerequisite report is not a successful BaseBase comparison' }
    Assert-EqualString $report.manifestSha256 $ManifestSha 'base/base prerequisite manifest SHA'
    Assert-EqualString $report.contractSha256 $ContractSha 'base/base prerequisite contract SHA'
    Assert-ComparisonCategoryClaims (Get-RequiredProperty $report 'comparisonCategories' 'base/base prerequisite report') 'base/base prerequisite report'
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

$resolvedOutputPath = [IO.Path]::GetFullPath($OutputPath)
$resolvedBaseBaseReportPath = $null
$allowOutputOverwrite = $ComparisonKind -eq 'BaseBase'
if ($ComparisonKind -eq 'BaseCandidate') {
    if ([string]::IsNullOrWhiteSpace($BaseBaseReportPath)) { throw 'BaseCandidate comparison requires -BaseBaseReportPath' }
    $resolvedBaseBaseReportPath = [IO.Path]::GetFullPath($BaseBaseReportPath)
    $pathComparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    if ($resolvedBaseBaseReportPath.Equals($resolvedOutputPath, $pathComparison)) {
        throw 'BaseBaseReportPath and OutputPath must resolve to distinct files for BaseCandidate comparison'
    }
}

try {
    $reviewedSentinelCheckCount = 143
    $reviewedSentinelCheckDigest = '072d6adec034001985d367a9d8a89ef0db447a76cbc1b9a4a22f580fdabc5b6e'
    $reviewedSentinelCanonicalization = 'ordinal-sort names, join LF, UTF-8 SHA-256'
    $manifest = Read-JsonFile $ManifestPath 'corpus manifest'
    $contract = Read-JsonFile $ContractPath 'comparison contract'
    $contractSchemaVersion = Get-RequiredProperty $contract 'schemaVersion' 'comparison contract'
    Assert-JsonKind $contractSchemaVersion @('string') 'comparison contract schemaVersion' | Out-Null
    Assert-EqualString $contractSchemaVersion '2' 'comparison contract schemaVersion'
    $sentinelContract = Get-RequiredProperty $contract 'sentinelChecks' 'comparison contract'
    Assert-JsonKind $sentinelContract @('object') 'comparison contract sentinelChecks' | Out-Null
    $contractSentinelCount = Get-RequiredProperty $sentinelContract 'count' 'comparison contract sentinelChecks'
    $contractSentinelDigest = Get-RequiredProperty $sentinelContract 'namesSha256' 'comparison contract sentinelChecks'
    $contractSentinelCanonicalization = Get-RequiredProperty $sentinelContract 'canonicalization' 'comparison contract sentinelChecks'
    Assert-JsonKind $contractSentinelCount @('number') 'comparison contract sentinel check count' | Out-Null
    Assert-JsonKind $contractSentinelDigest @('string') 'comparison contract sentinel check digest' | Out-Null
    Assert-JsonKind $contractSentinelCanonicalization @('string') 'comparison contract sentinel check canonicalization' | Out-Null
    if ($contractSentinelCount.Token -cne [string]$reviewedSentinelCheckCount) { throw 'comparison contract sentinel check count does not match the reviewed exact set' }
    Assert-EqualString $contractSentinelDigest $reviewedSentinelCheckDigest 'comparison contract sentinel check digest'
    Assert-EqualString $contractSentinelCanonicalization $reviewedSentinelCanonicalization 'comparison contract sentinel check canonicalization'
    $entries = @($manifest.entries)
    if ($entries.Count -ne 23) { throw "manifest must contain exactly 23 entries, found $($entries.Count)" }
    $expectedLabels = @($entries | ForEach-Object { [string]$_.label })
    if (@($expectedLabels | Select-Object -Unique).Count -ne 23) { throw 'manifest labels must be unique' }
    $expectedSafeLabels = @($expectedLabels | ForEach-Object { ConvertTo-SafeLabel $_ })
    if (@($expectedSafeLabels | Select-Object -Unique).Count -ne 23) { throw 'manifest safe labels must be unique' }
    $expectedRawFiles = @($expectedSafeLabels | ForEach-Object { "$_/result.json" })
    $expectedScreenshotFiles = @($expectedSafeLabels | ForEach-Object { "$_.png" })

    $reviewedGhostEnvelopeRuleKeys = @(
        '2elf-2.46|a50830e18def75be051a3638c7375c7e2d96cb18f7b3f26d0037d84a0fc20be0|compatible|GET|OnBoot|200|success|<null>',
        'tewire-sen|2a57e2272b2314baa59b3d911ed5051ef1fb8f94d1401083ffe4f7602834f7e8|compatible|GET|OnBoot|200|success|<null>',
        'Yes Man-2.1.1|aa6383f564fc2d89cbbc926cd672f481d2e8aafa48ec235b07ba0cbdf77912e8|compatible|GET|OnBoot|200|success|<null>',
        'Big Red Button|36ad0500958d88175d9e2530f4aa6e085a2d8579bbb200c1e2d2f9ac0785d21d|compatible|GET|OnBoot|200|success|<null>',
        'Earthquake Rescue Duo|06db71e7e8293b4af0b5127dd73402d4ed90fecc5fdcebf4f0d34337ccb66538|compatible|GET|OnBoot|200|success|<null>',
        'LOBO|f4e90615cf40801d4a7a7170762b6c0d6dddf18324f9ba146f4a700cbe2bebf7|compatible|GET|OnBoot|200|success|<null>',
        'Nanika Atsume 1.0.0|0ddfe156bf29e36522e58fe113ef64d0423cfd841007901a941dda50ed3302f9|compatible|GET|OnBoot|200|success|<null>',
        'Nanika Atsume 1.0.1|9b5ffc161abc489bce332702a1945f3f7d5ec6d66def3b521299ff36d91f290c|compatible|GET|OnBoot|200|success|<null>',
        'Nanika Atsume silent_ALPHA|be187fb6f51e3b45b5cfa0ab07a8fe46fd6862146a82e8e9dab563e699bf5d17|compatible|GET|OnBoot|200|success|<null>',
        'Snake and Otacon V1.0.0|526b7721103031fb3f28b22fffc54b71fd0b1e279168934a06d8076e20a1cbcc|incompatible|<null>|<null>|<null>|not-applicable:install-rejected|<null>',
        'Snake and Otacon V1.0.1|6f44dd039c17093d3f91e47bb9c474e128eb34fa4bfeb5ef3148625bbd613764|incompatible|<null>|<null>|<null>|not-applicable:install-rejected|<null>',
        'Snake And Otacon V1.1.1|21253507c17e90073974229ddf8b0d39e36efcae968a27c2569fe5c46c201e4b|incompatible|<null>|<null>|<null>|not-applicable:install-rejected|<null>',
        'Snake and Otacon V1.2.1|a4b89d1c932f5862ca60e8bacf62563dadb65f4dadce5fd1bc7945db652acb6f|compatible|GET|OnBoot|200|success|<null>',
        'Snake and Otacon V1.3.1|a710ff1f031ffd23d7d61fcf7fabed5d1cb4794eaf06e9eb6cd9d6df5fcc1219|compatible|GET|OnBoot|200|success|<null>',
        'Snake and Otacon V1.3.2|1c62ce50ca0daca3a9e14e6d870b02d4df9511dd5b586a7f4da49b402d56cbd5|compatible|GET|OnFirstBoot|200|success|<null>',
        'Snake_Otacon_1.1.1b|ef1590f766964b1932020abf6e93aa229be12fbc6ba9238a4e5cda90939f4d70|incompatible|<null>|<null>|<null>|not-applicable:install-rejected|<null>',
        'Snake_Otacon_1.2.1b|4c925dc0b8a61b41cc91c72589e30e4ece7e6b0b92dcc44eec993b71605aed45|partiallyCompatible|GET|OnBoot|200|not-supported-shiori|<null>',
        'Snake_Otacon_1.3.1b|04d7563d65116d14e9e1208586c77cf3a6703dfcc3c10d48a10d581cfa9b8b59|compatible|GET|OnBoot|200|success|<null>',
        'Watchdog Bancho|8a3f1dcaa4c34a625bf16c0a0ada2e3dff2d49fc029e014807aafb164f196dca|compatible|GET|OnBoot|200|success|<null>'
    )
    $ghostEnvelopeRules = Get-RequiredProperty $contract 'ghostEnvelopeRules' 'comparison contract'
    Assert-JsonKind $ghostEnvelopeRules @('array') 'comparison contract ghostEnvelopeRules' | Out-Null
    $actualGhostEnvelopeRuleKeys = @($ghostEnvelopeRules | ForEach-Object { Get-GhostEnvelopeRuleKey $_ 'comparison contract ghost envelope rule' })
    Assert-ExactSet $actualGhostEnvelopeRuleKeys $reviewedGhostEnvelopeRuleKeys 'comparison contract ghost envelope rule set'
    $ghostEnvelopeRulesByLabel = @{}
    foreach ($rule in $ghostEnvelopeRules) {
        $label = [string]$rule.label
        $entry = @($entries | Where-Object { [string]$_.label -ceq $label })
        if ($entry.Count -ne 1) { throw "comparison contract ghost envelope rule '$label' has no unique manifest entry" }
        Assert-EqualString (Get-RequiredProperty $entry[0] 'expectedKind' "manifest entry '$label'") 'ghost' "comparison contract ghost envelope expected kind for '$label'"
        Assert-EqualString $rule.archiveSha256 ([string]$entry[0].sha256) "comparison contract ghost envelope archive SHA for '$label'"
        $ghostEnvelopeRulesByLabel[$label] = $rule
    }

    $stochasticLabels = @($contract.stochasticDialogueValues | ForEach-Object { [string]$_.label })
    $requiredStochasticLabels = @('2elf-2.46', 'Earthquake Rescue Duo', 'LOBO', 'Snake and Otacon V1.2.1', 'Snake and Otacon V1.3.1', 'Snake_Otacon_1.3.1b', 'Watchdog Bancho')
    Assert-ExactSet $stochasticLabels $requiredStochasticLabels 'comparison contract stochastic label set'
    $categories = Get-RequiredProperty $contract 'comparisonCategories' 'comparison contract'
    Assert-JsonKind $categories @('object') 'comparison contract comparisonCategories' | Out-Null
    $reviewedCategoryCounts = [ordered]@{
        rawEnvelopeValidatedCount = '23'
        literalEqualityCount = '16'
        stochasticDialogueContractCount = '4'
        snakeStructuralOnlyCount = '3'
        snakeCanaryExactCount = '3'
        screenshotHashEqualityCount = '23'
        dialogueContentContractValidated = '20'
    }
    foreach ($categoryName in $reviewedCategoryCounts.Keys) {
        $categoryValue = Get-RequiredProperty $categories $categoryName 'comparison contract comparisonCategories'
        Assert-JsonKind $categoryValue @('number') "comparison contract comparisonCategories.$categoryName" | Out-Null
        if ($categoryValue.Token -cne $reviewedCategoryCounts[$categoryName]) { throw "comparison contract comparisonCategories.$categoryName does not match the reviewed exact value" }
    }
    Assert-ExactSet @($categories.stochasticDialogueLabels | ForEach-Object { [string]$_ }) @('2elf-2.46', 'Watchdog Bancho', 'Earthquake Rescue Duo', 'LOBO') 'comparison contract stochastic dialogue label set'
    Assert-ExactSet @($categories.snakeStructuralOnlyLabels | ForEach-Object { [string]$_ }) @('Snake and Otacon V1.2.1', 'Snake and Otacon V1.3.1', 'Snake_Otacon_1.3.1b') 'comparison contract Snake structural-only label set'
    Assert-ComparisonCategoryClaims $categories 'comparison contract'
    $requiredNormalization = @{
        summaryRunIdPaths = @('runId', 'results[].runId')
        summaryTimestampPaths = @('startedAt', 'finishedAt', 'results[].startedAt', 'results[].finishedAt')
        summaryDurationPaths = @('durationSeconds')
        summaryReportRootPaths = @('git.manifestFile', 'apks.debugPath', 'apks.testPath')
        summaryRunOwnedStringPaths = @('results[].resultPath', 'results[].screenshotPath', 'results[].crashLogPath', 'results[].output', 'results[].error')
        summaryObservedPrivateSnapshotPath = @('results[].observedPrivateSnapshot')
        summaryObservedTmpSnapshotPath = @('results[].observedTmpSnapshot')
        summarySourceScanRootPath = @('results[].evidence.sourceSyntax.scanRoot')
        rawRunOwnedStringPaths = @('narCorpusPath')
        rawRunOwnedStringSelectors = @('evidence.sourceSyntax.scanRoot', 'sakura.source', 'kero.source')
    }
    foreach ($normalizationName in $requiredNormalization.Keys) {
        $actualNormalization = Get-RequiredProperty $contract.normalization $normalizationName 'comparison contract normalization'
        Assert-ExactSet @($actualNormalization | ForEach-Object { [string]$_ }) $requiredNormalization[$normalizationName] "comparison contract normalization $normalizationName"
    }
    $requiredRawSourceLabels = @(
        '2elf-2.46', 'Big Red Button', 'Earthquake Rescue Duo', 'LOBO',
        'Nanika Atsume 1.0.0', 'Nanika Atsume 1.0.1', 'Nanika Atsume silent_ALPHA',
        'Snake and Otacon V1.2.1', 'Snake and Otacon V1.3.1', 'Snake and Otacon V1.3.2',
        'Snake_Otacon_1.2.1b', 'Snake_Otacon_1.3.1b', 'tewire-sen', 'Watchdog Bancho',
        'Yes Man-2.1.1'
    )
    if ($null -eq $contract.normalization.PSObject.Properties['rawSourceArchiveSha256']) {
        throw "comparison contract normalization is missing required property 'rawSourceArchiveSha256'"
    }
    $rawSourceArchiveSha256 = $contract.normalization.rawSourceArchiveSha256
    $actualRawSourceLabels = @($rawSourceArchiveSha256.PSObject.Properties | ForEach-Object { $_.Name })
    Assert-ExactSet $actualRawSourceLabels $requiredRawSourceLabels 'comparison contract normalization rawSourceArchiveSha256 labels'
    $summarySourceArchiveSha256 = Get-RequiredProperty $contract.normalization 'summarySourceArchiveSha256' 'comparison contract normalization'
    $actualSummarySourceLabels = @($summarySourceArchiveSha256.PSObject.Properties | ForEach-Object { $_.Name })
    Assert-ExactSet $actualSummarySourceLabels $requiredRawSourceLabels 'comparison contract normalization summary source labels'
    foreach ($label in $requiredRawSourceLabels) {
        $manifestEntry = @($entries | Where-Object { [string]$_.label -ceq $label })
        if ($manifestEntry.Count -ne 1) { throw "comparison contract raw source label '$label' is not unique in manifest" }
        Assert-EqualString ([string]$rawSourceArchiveSha256.$label) ([string]$manifestEntry[0].sha256) "comparison contract raw source archive SHA for '$label'"
        Assert-EqualString ([string]$summarySourceArchiveSha256.$label) ([string]$manifestEntry[0].sha256) "comparison contract summary source archive SHA for '$label'"
    }
    $expectedKindRules = @(
        'summary|runId|runId|string',
        'summary|results[].runId|runId|string',
        'summary|startedAt|timestamp|string',
        'summary|finishedAt|timestamp|string',
        'summary|results[].startedAt|timestamp|string',
        'summary|results[].finishedAt|timestamp|string',
        'summary|durationSeconds|duration|number',
        'summary|git.manifestFile|reportPath|string',
        'summary|apks.debugPath|reportPath|string',
        'summary|apks.testPath|reportPath|string',
        'summary|results[].resultPath|runOwnedPath|string',
        'summary|results[].screenshotPath|runOwnedPath|string',
        'summary|results[].crashLogPath|runOwnedPath|string',
        'summary|results[].output|runOwnedText|string',
        'summary|results[].error|runOwnedText|string',
        'summary|results[].observedPrivateSnapshot|runOwnedPath|array,string',
        'summary|results[].observedTmpSnapshot|runOwnedPath|array,string',
        'summary|results[].evidence.sourceSyntax.scanRoot|runOwnedPath|string',
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
        if ($null -ne $rule.PSObject.Properties['specializedValidator']) {
            if ($label -ceq 'LOBO') {
                Assert-EqualString $rule.specializedValidator 'lobo-onboot-v1' "comparison contract specialized stochastic validator for '$label'"
                $grammarDigest = Get-RequiredProperty $rule 'grammarDigest' "comparison contract specialized stochastic validator for '$label'"
                Assert-JsonKind $grammarDigest @('string') "comparison contract specialized stochastic grammar digest for '$label'" | Out-Null
                Assert-EqualString $grammarDigest '98935130a9797d4e34ad05607d1345c48d8fbc73b32192102e8f1484b64dd1b1' "comparison contract specialized stochastic grammar digest for '$label'"
            }
            elseif (@('Snake and Otacon V1.2.1', 'Snake and Otacon V1.3.1', 'Snake_Otacon_1.3.1b') -ccontains $label) {
                Assert-EqualString $rule.specializedValidator 'snake-onboot-structural-v1' "comparison contract specialized stochastic validator for '$label'"
                $contentCompared = Get-RequiredProperty $rule 'contentCompared' "comparison contract Snake structural rule '$label'"
                Assert-JsonKind $contentCompared @('boolean') "comparison contract Snake structural rule '$label'.contentCompared" | Out-Null
                if ($contentCompared -ne $false) { throw "comparison contract Snake structural rule '$label'.contentCompared must be false" }
                Assert-EqualString (Get-RequiredProperty $rule 'terminalPolicy' "comparison contract Snake structural rule '$label'") 'exact-e-or-eof' "comparison contract Snake structural rule '$label'.terminalPolicy"
            }
            else { throw "comparison contract specialized stochastic validator is not declared for '$label'" }
        }
        elseif ($null -ne $rule.PSObject.Properties['allowedVariants']) {
            $variants = @($rule.allowedVariants)
            if ($variants.Count -lt 1) { throw "comparison contract contains no stochastic variants for '$label'" }
            foreach ($variant in $variants) {
                Assert-JsonKind $variant @('object') "comparison contract stochastic variant for '$label'" | Out-Null
                $variantHash = Get-RequiredProperty $variant 'valueUtf8Sha256' "comparison contract stochastic variant for '$label'"
                Assert-JsonKind $variantHash @('string') "comparison contract stochastic variant hash for '$label'" | Out-Null
                if ([string]$variantHash -notmatch '^[0-9a-f]{64}$') { throw "comparison contract contains invalid decoded UTF-8 variant hash for '$label'" }
                $variantDiagnostics = Get-RequiredProperty $variant 'tokenizerDiagnostics' "comparison contract stochastic variant for '$label'"
                Assert-JsonKind $variantDiagnostics @('array') "comparison contract stochastic variant diagnostics for '$label'" | Out-Null
                foreach ($diagnostic in @($variantDiagnostics)) { Assert-JsonKind $diagnostic @('string') "comparison contract stochastic variant diagnostic for '$label'" | Out-Null }
                $predicate = Get-RequiredProperty $variant 'predicate' "comparison contract stochastic variant for '$label'"
                Assert-JsonKind $predicate @('string') "comparison contract stochastic variant predicate for '$label'" | Out-Null
            }
        }
        else {
            $allowedHashes = @($rule.allowedUtf8Sha256 | ForEach-Object { [string]$_ })
            if ($allowedHashes.Count -lt 1 -or @($allowedHashes | Select-Object -Unique).Count -ne $allowedHashes.Count -or @($allowedHashes | Where-Object { $_ -notmatch '^[0-9a-f]{64}$' }).Count -ne 0) {
                throw "comparison contract contains invalid or duplicate decoded UTF-8 hashes for '$label'"
            }
        }
        if ([string]::IsNullOrWhiteSpace([string]$rule.source.archiveEntry) -or @($rule.source.lineRanges).Count -lt 1 -or @($rule.source.reviewedEvidence).Count -lt 1) {
            throw "comparison contract source provenance is incomplete for '$label'"
        }
    }

    $manifestSha = Get-Sha256 $ManifestPath
    $contractSha = Get-Sha256 $ContractPath
    $base = Read-AndValidateRun $BaseRoot 'base' $BaseProductionCommit $BaseDebugApkSha256 $entries $expectedLabels $expectedRawFiles $expectedScreenshotFiles $actualRawSourceLabels $ghostEnvelopeRulesByLabel $reviewedSentinelCheckCount $reviewedSentinelCheckDigest $manifestSha
    if ($ComparisonKind -eq 'BaseCandidate') {
        Assert-BaseBasePrerequisite $resolvedBaseBaseReportPath $manifestSha $contractSha $base.Summary.device $base.EvidenceFingerprint
    }
    $candidate = Read-AndValidateRun $CandidateRoot 'candidate' $CandidateProductionCommit $CandidateDebugApkSha256 $entries $expectedLabels $expectedRawFiles $expectedScreenshotFiles $actualRawSourceLabels $ghostEnvelopeRulesByLabel $reviewedSentinelCheckCount $reviewedSentinelCheckDigest $manifestSha

    if ($base.EvidenceFingerprint -ceq $candidate.EvidenceFingerprint) {
        throw "$ComparisonKind comparison requires distinct base and candidate evidence fingerprints"
    }
    if ($base.RunIdentity -ceq $candidate.RunIdentity) {
        throw "$ComparisonKind comparison requires distinct base and candidate run identities"
    }

    if ($ComparisonKind -eq 'BaseBase') {
        if ($BaseProductionCommit -cne $CandidateProductionCommit -or $BaseDebugApkSha256 -cne $CandidateDebugApkSha256) {
            throw 'BaseBase comparison requires identical base and candidate production identities'
        }
    }
    elseif ($BaseProductionCommit -ceq $CandidateProductionCommit -and $BaseDebugApkSha256 -ceq $CandidateDebugApkSha256) {
        throw 'BaseCandidate comparison requires distinct base and candidate production identities'
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
        schemaVersion = '2'
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
        comparisonCategories = $categories
        differences = @()
    }
    Write-ComparisonReportAtomic $report $resolvedOutputPath $allowOutputOverwrite
    Write-Host "NAR corpus $ComparisonKind comparison passed: 23 raw envelopes validated; 16 dialogue literals equal; 4 archive-bound stochastic dialogue contracts validated; 3 Snake structural-only contents; 3 exact Snake canaries; 23 screenshot hashes equal."
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
        schemaVersion = '2'
        passed = $false
        comparisonKind = $ComparisonKind
        failure = [pscustomobject][ordered]@{
            artifact = $artifact
            label = $label
            path = $failurePath
            reason = $reason
        }
    }
    try { Write-ComparisonReportAtomic $failureReport $resolvedOutputPath $allowOutputOverwrite } catch { Write-Warning "Unable to write failure comparison report: $($_.Exception.Message)" }
    Write-Error $reason
    exit 1
}
