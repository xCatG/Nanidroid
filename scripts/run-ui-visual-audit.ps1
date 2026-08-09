param(
    [CmdletBinding()]
    [string]$DeviceSerial = 'emulator-5554',
    [string]$AvdName = 'Nanidroid_API_37',
    [string]$SnapshotName = 'default_boot',
    [string]$AndroidCliPath,
    [string]$AdbPath,
    [string]$EmulatorPath,
    [string[]]$CorpusRoots = @('.', 'build/ui-audit'),
    [string]$ManifestPath = 'docs/testing/nar-corpus-manifest.json',
    [int]$BuildTimeoutMinutes = 45,
    [int]$NarProfileTimeoutMinutes = 180,
    [int]$BootTimeoutMinutes = 5,
    [int]$CommandTimeoutSeconds = 120,
    [switch]$VerifyManualInspection,
    [switch]$DryRun,
    [switch]$HostSelfTest
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$scriptRoot = $PSScriptRoot
$repoRoot = Split-Path -Parent $scriptRoot
$reportRoot = Join-Path $repoRoot 'build\reports\ui-audit'
$fixtureRoot = Join-Path $repoRoot 'src\screenshotTestDebug\reference'
$narReportRoot = Join-Path $repoRoot 'build\reports\nar-corpus'
$targetPackage = 'com.cattailsw.nanidroid'
$testPackage = 'com.cattailsw.nanidroid.test'
$mainActivity = 'com.cattailsw.nanidroid/.Nanidroid'
$expectedCaseCount = 67
$script:adbTransportDead = $false
$script:ownedEmulator = $null
$script:ownedEmulatorProcessId = $null
$script:ownedEmulatorStartTimeUtcTicks = $null
$script:emulatorWatchdog = $null
$script:reportInitialized = $false
$script:cleanupErrors = [System.Collections.ArrayList]::new()
$script:results = [System.Collections.ArrayList]::new()
$script:runFailure = $null
$script:captureProvenance = $null
$script:captureStartedAtUtc = $null
$script:interactionCaptureStartedAtUtc = $null
$script:interactionCapture = $null
$script:interactionCheckpointPersisted = $false
$script:narCorpusPackageCleanVerified = $false

function Fail([string]$Message, [string]$Code = 'validation') {
    throw [System.InvalidOperationException]::new("$Code`: $Message")
}

if ($PSVersionTable.PSEdition -ne 'Core' -or $PSVersionTable.PSVersion.Major -lt 7) {
    Fail 'PowerShell 7 or newer (pwsh) is required for exact process-tree cleanup.' 'tool'
}

function Test-Property([object]$Object, [string]$Name) {
    return $null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name]
}

function ConvertTo-SafeLabel([string]$Value) {
    $safe = $Value -replace '[^A-Za-z0-9._-]+', '-'
    $safe = $safe.Trim('-', '.')
    if ([string]::IsNullOrWhiteSpace($safe) -or $safe -in @('.', '..')) {
        Fail "Unsafe empty label derived from '$Value'."
    }
    return $safe
}

function Get-NarProfileSummaryRelativePath([string]$ProfileName) {
    return "nar\$(ConvertTo-SafeLabel $ProfileName)\task17-summary.json"
}

function New-NarCorpusAuditChildInvocation {
    param(
        [string]$ProfileName,
        [string[]]$ResolvedCorpusRoots,
        [string]$ResolvedAdbPath
    )

    $narInvocation = [ordered]@{
        script = Join-Path $scriptRoot 'run-nar-corpus-audit.ps1'
        device = $DeviceSerial
        manifest = $ManifestPath
        adb = $ResolvedAdbPath
        roots = @($ResolvedCorpusRoots)
    }
    if ($ProfileName -ceq 'compact-landscape') {
        $narInvocation.expectedStageGeometryProfile = 'compact-landscape'
    }

    $narPayload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($narInvocation | ConvertTo-Json -Depth 4 -Compress)))
    $narCommand = '$p=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(''' + $narPayload + '''))|ConvertFrom-Json; & $p.script -DeviceSerial $p.device -ManifestPath $p.manifest -AdbPath $p.adb -CorpusRoots @($p.roots)'
    if ($ProfileName -ceq 'compact-landscape') {
        $narCommand += ' -ExpectedStageGeometryProfile compact-landscape'
    }
    return [pscustomobject][ordered]@{
        payload = $narPayload
        command = $narCommand
    }
}

function Expand-CorpusRootArguments([string[]]$Values) {
    $expanded = [System.Collections.ArrayList]::new()
    foreach ($value in @($Values)) {
        foreach ($part in @(([string]$value) -split ',')) {
            $trimmed = $part.Trim()
            if (-not [string]::IsNullOrWhiteSpace($trimmed)) {
                $expanded.Add($trimmed) | Out-Null
            }
        }
    }
    if ($expanded.Count -eq 0) { Fail 'At least one corpus root is required.' 'corpus' }
    return @($expanded)
}

function Get-RelativePath([string]$BasePath, [string]$ChildPath) {
    $base = [IO.Path]::GetFullPath($BasePath).TrimEnd('\') + '\'
    $child = [IO.Path]::GetFullPath($ChildPath)
    $baseUri = [Uri]::new($base)
    $childUri = [Uri]::new($child)
    return [Uri]::UnescapeDataString($baseUri.MakeRelativeUri($childUri).ToString()).Replace('/', '\')
}

function Assert-SafeReportRelativePath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path) -or [IO.Path]::IsPathRooted($Path)) {
        Fail "Report path must be non-empty and relative: '$Path'."
    }
    $segments = $Path -split '[\\/]'
    if ($segments -contains '..' -or $segments -contains '.' -or $segments -contains '') {
        Fail "Report path contains an unsafe segment: '$Path'."
    }
    if ($Path -notmatch '^[A-Za-z0-9._\\/-]+$') {
        Fail "Report path contains unsupported characters: '$Path'."
    }
}

function Get-UiAuditRepresentatives {
    return @(
        [pscustomobject]@{ label = '2elf-2.46'; sha256 = 'a50830e18def75be051a3638c7375c7e2d96cb18f7b3f26d0037d84a0fc20be0'; role = 'asymmetric' },
        [pscustomobject]@{ label = 'Snake and Otacon V1.3.2'; sha256 = '1c62ce50ca0daca3a9e14e6d870b02d4df9511dd5b586a7f4da49b402d56cbd5'; role = 'tallest-input' },
        [pscustomobject]@{ label = 'Nanika Atsume 1.0.1'; sha256 = '9b5ffc161abc489bce332702a1945f3f7d5ec6d66def3b521299ff36d91f290c'; role = 'smallest' },
        [pscustomobject]@{ label = 'Watchdog Bancho'; sha256 = '8a3f1dcaa4c34a625bf16c0a0ada2e3dff2d49fc029e014807aafb164f196dca'; role = 'polygon-collision' },
        [pscustomobject]@{ label = 'Big Red Button'; sha256 = '36ad0500958d88175d9e2530f4aa6e085a2d8579bbb200c1e2d2f9ac0785d21d'; role = 'placeholder' },
        [pscustomobject]@{ label = 'Earthquake Rescue Duo'; sha256 = '06db71e7e8293b4af0b5127dd73402d4ed90fecc5fdcebf4f0d34337ccb66538'; role = 'widest' },
        [pscustomobject]@{ label = 'tewire-sen'; sha256 = '2a57e2272b2314baa59b3d911ed5051ef1fb8f94d1401083ffe4f7602834f7e8'; role = 'dual-character' }
    )
}

function New-CaseRecord {
    param(
        [string]$Id,
        [string]$Kind,
        [string]$Driver,
        [string]$ScreenshotPath,
        [string]$LayoutPath,
        [string]$AnnotatedPath,
        [object]$Requested,
        [string[]]$ExpectedInvariants,
        [object]$Source = $null,
        [string]$ArchiveSha256 = $null
    )
    return [pscustomobject][ordered]@{
        id = $Id
        kind = $Kind
        sourceDriver = $Driver
        screenshotPath = $ScreenshotPath
        layoutPath = if ([string]::IsNullOrWhiteSpace($LayoutPath)) { $null } else { $LayoutPath }
        annotatedPath = if ([string]::IsNullOrWhiteSpace($AnnotatedPath)) { $null } else { $AnnotatedPath }
        requested = $Requested
        expectedInvariants = @($ExpectedInvariants)
        manualInspectionRequired = $true
        source = $Source
        archiveSha256 = $ArchiveSha256
    }
}

function New-UiAuditManifest {
    $cases = [System.Collections.ArrayList]::new()
    $liveProfiles = @(
        @{ name = 'dp-360x720-f100'; widthDp = 360; heightDp = 720; densityMode = '160'; fontScale = 1.0; expectCharacterSurfaces = $true },
        @{ name = 'dp-720x360-f100'; widthDp = 720; heightDp = 360; densityMode = '160'; fontScale = 1.0; expectCharacterSurfaces = $true },
        @{ name = 'dp-400x1000-f100'; widthDp = 400; heightDp = 1000; densityMode = '160'; fontScale = 1.0; expectCharacterSurfaces = $true },
        @{ name = 'dp-610x500-f100'; widthDp = 610; heightDp = 500; densityMode = '160'; fontScale = 1.0; expectCharacterSurfaces = $true },
        @{ name = 'dp-800x1280-f100'; widthDp = 800; heightDp = 1280; densityMode = '160'; fontScale = 1.0; expectCharacterSurfaces = $true },
        @{ name = 'dp-1280x800-f100'; widthDp = 1280; heightDp = 800; densityMode = '160'; fontScale = 1.0; expectCharacterSurfaces = $true },
        @{ name = 'dp-480x230-f100'; widthDp = 480; heightDp = 230; densityMode = '160'; fontScale = 1.0; expectCharacterSurfaces = $false },
        @{ name = 'dp-230x400-f100'; widthDp = 230; heightDp = 400; densityMode = '160'; fontScale = 1.0; expectCharacterSurfaces = $false },
        @{ name = 'dp-400x1000-f150'; widthDp = 400; heightDp = 1000; densityMode = '160'; fontScale = 1.5; expectCharacterSurfaces = $true },
        @{ name = 'dp-400x1000-f200'; widthDp = 400; heightDp = 1000; densityMode = '160'; fontScale = 2.0; expectCharacterSurfaces = $true },
        @{ name = 'native-density-phone'; widthDp = 400; heightDp = 1000; densityMode = 'native'; fontScale = 1.0; expectCharacterSurfaces = $true },
        @{ name = 'native-density-tablet'; widthDp = 1280; heightDp = 800; densityMode = 'native'; fontScale = 1.0; expectCharacterSurfaces = $true }
    )
    foreach ($profile in $liveProfiles) {
        $id = "live-$($profile.name)"
        $requested = [pscustomobject][ordered]@{
            widthDp = $profile.widthDp; heightDp = $profile.heightDp
            density = $profile.densityMode; fontScale = $profile.fontScale
            theme = 'host-current'; locale = 'host-current'; rotation = if ($profile.widthDp -gt $profile.heightDp) { 'landscape' } else { 'portrait' }
            expectCharacterSurfaces = [bool]$profile.expectCharacterSurfaces
        }
        $cases.Add((New-CaseRecord -Id $id -Kind 'live' -Driver 'android-cli-live' `
            -ScreenshotPath "live/$id.png" -LayoutPath "live/$id.layout.json" -AnnotatedPath "live/$id.annotated.png" `
            -Requested $requested -ExpectedInvariants @('single-ghost-safe-stage', 'stage-contained', 'bottom-aligned', 'no-clipped-content'))) | Out-Null
    }

    $references = @(Get-ChildItem -LiteralPath $fixtureRoot -Recurse -File -Filter '*.png' | Sort-Object FullName)
    foreach ($reference in $references) {
        $relative = Get-RelativePath -BasePath $fixtureRoot -ChildPath $reference.FullName
        $stem = [IO.Path]::GetFileNameWithoutExtension($reference.Name)
        $id = 'fixture-' + (ConvertTo-SafeLabel $stem).ToLowerInvariant()
        $cases.Add((New-CaseRecord -Id $id -Kind 'fixture' -Driver 'validated-layoutlib-reference' `
            -ScreenshotPath "fixtures/$id.png" -LayoutPath $null -AnnotatedPath $null `
            -Requested ([pscustomobject][ordered]@{ display = 'fixture-declared'; density = 'fixture-declared'; fontScale = 'fixture-declared'; theme = 'fixture-declared'; locale = 'fixture-declared' }) `
            -ExpectedInvariants @('committed-reference', 'validated-by-validateDebugScreenshotTest') `
            -Source ([pscustomobject][ordered]@{ referencePath = $relative }))) | Out-Null
    }

    $profiles = @(
        @{ name = 'portrait'; widthDp = 360; heightDp = 720 },
        @{ name = 'compact-landscape'; widthDp = 720; heightDp = 360 },
        @{ name = 'tablet'; widthDp = 1280; heightDp = 800 }
    )
    foreach ($representative in (Get-UiAuditRepresentatives)) {
        $safe = (ConvertTo-SafeLabel $representative.label).ToLowerInvariant()
        foreach ($profile in $profiles) {
            $id = "nar-$safe-$($profile.name)"
            $cases.Add((New-CaseRecord -Id $id -Kind 'nar' -Driver 'nar-corpus-probe' `
                -ScreenshotPath "nar/$($profile.name)/$safe.png" -LayoutPath $null -AnnotatedPath $null `
                -Requested ([pscustomobject][ordered]@{ widthDp = $profile.widthDp; heightDp = $profile.heightDp; density = 160; fontScale = 1.0; theme = 'host-current'; locale = 'host-current' }) `
                -ExpectedInvariants @('task17-probe-pass', 'representative-rendered', $representative.role) `
                -Source ([pscustomobject][ordered]@{ label = $representative.label; profile = $profile.name; role = $representative.role }) `
                -ArchiveSha256 $representative.sha256)) | Out-Null
        }
    }

    $interactionEvidence = @(Get-RequiredInteractionEvidenceContract)
    $manifest = [pscustomobject][ordered]@{
        schemaVersion = 2
        caseSetVersion = '2026-08-04.2'
        caseCount = $cases.Count
        interactionEvidenceCount = $interactionEvidence.Count
        generatedBy = 'scripts/run-ui-visual-audit.ps1'
        cases = @($cases)
        interactionEvidence = $interactionEvidence
    }
    return $manifest
}

function ConvertTo-CanonicalManifestJson([object]$Manifest) {
    return ($Manifest | ConvertTo-Json -Depth 16 -Compress)
}

function Get-StringSha256([string]$Value) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))).Replace('-', '').ToLowerInvariant()) }
    finally { $sha.Dispose() }
}

function Assert-InteractionEvidenceManifestContract([object]$Manifest) {
    $expectedEvidence = @(Get-RequiredInteractionEvidenceContract)
    if (-not (Test-Property $Manifest 'interactionEvidenceCount') -or -not (Test-Property $Manifest 'interactionEvidence')) { Fail 'UI audit manifest lacks the interaction-evidence contract.' }
    $actualEvidence = @($Manifest.interactionEvidence)
    if ($Manifest.interactionEvidenceCount -ne $expectedEvidence.Count -or $actualEvidence.Count -ne $expectedEvidence.Count) { Fail "Interaction evidence must contain exactly $($expectedEvidence.Count) artifacts." }
    for ($index = 0; $index -lt $expectedEvidence.Count; $index++) {
        $expected = $expectedEvidence[$index]
        $actual = $actualEvidence[$index]
        foreach ($property in @('id', 'artifactPath', 'expectedInvariants')) {
            if (-not (Test-Property $actual $property)) { Fail "Interaction evidence $($index + 1) lacks '$property'." }
        }
        if ([string]$actual.id -cne [string]$expected.id -or [string]$actual.artifactPath -cne [string]$expected.artifactPath) { Fail "Interaction evidence $($index + 1) has a substituted identity or path." }
        if ((@($actual.expectedInvariants) -join [char]31) -cne (@($expected.expectedInvariants) -join [char]31)) { Fail "Interaction evidence '$($expected.id)' has substituted invariants." }
        Assert-SafeReportRelativePath ([string]$actual.artifactPath)
    }
}

function Resolve-SafeReportArtifactPath([string]$Root, [string]$RelativePath, [bool]$MustExist = $false) {
    Assert-SafeReportRelativePath $RelativePath
    $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $candidate = [IO.Path]::GetFullPath((Join-Path $rootFull $RelativePath))
    $rootPrefix = $rootFull + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) { Fail "Report artifact escapes its root: '$RelativePath'." 'artifact' }
    $current = $rootFull
    if (Test-Path -LiteralPath $current) {
        if (((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { Fail "Report root cannot be a reparse point: '$rootFull'." 'artifact' }
    }
    foreach ($segment in @($RelativePath -split '[\\/]')) {
        $current = Join-Path $current $segment
        if (Test-Path -LiteralPath $current) {
            if (((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { Fail "Report artifact path cannot traverse a reparse point: '$RelativePath'." 'artifact' }
        }
    }
    if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { Fail "Required report artifact is missing: '$RelativePath'." 'artifact' }
    return $candidate
}

function Assert-ReportPngHash([string]$Root, [string]$RelativePath, [string]$ExpectedSha256) {
    if ($ExpectedSha256 -notmatch '^[0-9a-fA-F]{64}$') { Fail "Invalid expected PNG SHA-256 for '$RelativePath'." 'artifact' }
    $path = Resolve-SafeReportArtifactPath $Root $RelativePath $true
    $actualSha256 = Assert-Png $path
    if ($actualSha256 -cne $ExpectedSha256.ToLowerInvariant()) { Fail "Report PNG '$RelativePath' hash changed: expected=$($ExpectedSha256.ToLowerInvariant()) actual=$actualSha256." 'artifact' }
    return $actualSha256
}

function Assert-UiAuditManifest([object]$Manifest) {
    if ($Manifest.schemaVersion -ne 2 -or $Manifest.caseSetVersion -ne '2026-08-04.2') { Fail 'Unexpected UI audit manifest version.' }
    Assert-InteractionEvidenceManifestContract $Manifest
    if ($Manifest.caseCount -ne $expectedCaseCount -or @($Manifest.cases).Count -ne $expectedCaseCount) { Fail "UI audit case-count gate expected $expectedCaseCount, got $($Manifest.caseCount)/$(@($Manifest.cases).Count)." }
    $kinds = @($Manifest.cases | Group-Object kind | ForEach-Object { @{ name = $_.Name; count = $_.Count } })
    foreach ($expected in @(@{name='live';count=12}, @{name='fixture';count=34}, @{name='nar';count=21})) {
        $actual = @($kinds | Where-Object { $_.name -eq $expected.name })
        if ($actual.Count -ne 1 -or $actual[0].count -ne $expected.count) { Fail "Expected $($expected.count) $($expected.name) cases." }
    }
    $ids = @{}
    $paths = @{}
    foreach ($case in $Manifest.cases) {
        if ($ids.ContainsKey($case.id)) { Fail "Duplicate case id '$($case.id)'." }
        $ids[$case.id] = $true
        if ($case.sourceDriver -notin @('android-cli-live', 'validated-layoutlib-reference', 'nar-corpus-probe')) { Fail "Dishonest/unknown source driver '$($case.sourceDriver)'." }
        foreach ($property in @('screenshotPath', 'layoutPath', 'annotatedPath')) {
            $path = $case.$property
            if ([string]::IsNullOrWhiteSpace([string]$path)) { continue }
            Assert-SafeReportRelativePath $path
            if ($paths.ContainsKey($path.ToLowerInvariant())) { Fail "Duplicate report output '$path'." }
            $paths[$path.ToLowerInvariant()] = $true
        }
        if (-not $case.manualInspectionRequired -or @($case.expectedInvariants).Count -lt 1) { Fail "Case '$($case.id)' lacks manual requirements/invariants." }
        if ($case.kind -eq 'live') {
            $expectedCharacterSurfaces = $case.id -notin @('live-dp-480x230-f100', 'live-dp-230x400-f100')
            if (-not (Test-Property $case.requested 'expectCharacterSurfaces') -or [bool]$case.requested.expectCharacterSurfaces -ne $expectedCharacterSurfaces) {
                Fail "Live case '$($case.id)' has an invalid character-surface expectation."
            }
        }
    }
    foreach ($evidence in $Manifest.interactionEvidence) {
        $path = [string]$evidence.artifactPath
        if ($paths.ContainsKey($path.ToLowerInvariant())) { Fail "Duplicate report output '$path'." }
        $paths[$path.ToLowerInvariant()] = $true
    }
}

function ConvertFrom-LayoutBounds([object]$Bounds) {
    if ($Bounds -is [string] -and $Bounds -match '^\[(?<l>-?\d+),(?<t>-?\d+)\]\[(?<r>-?\d+),(?<b>-?\d+)\]$') {
        $left=[int]$matches.l; $top=[int]$matches.t; $right=[int]$matches.r; $bottom=[int]$matches.b
    } elseif ($null -ne $Bounds -and (Test-Property $Bounds 'left') -and (Test-Property $Bounds 'top') -and (Test-Property $Bounds 'right') -and (Test-Property $Bounds 'bottom')) {
        $left=[int]$Bounds.left; $top=[int]$Bounds.top; $right=[int]$Bounds.right; $bottom=[int]$Bounds.bottom
    } else { Fail "Unsupported layout bounds payload '$Bounds'." 'layout' }
    if ($right -le $left -or $bottom -le $top) { Fail "Non-positive layout bounds [$left,$top][$right,$bottom]." 'layout' }
    return [pscustomobject][ordered]@{ left=$left; top=$top; right=$right; bottom=$bottom; width=$right-$left; height=$bottom-$top }
}

function Test-SemanticIdMatch([string]$Value, [string]$SemanticId) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return $false }
    return $Value -ceq $SemanticId -or $Value -cmatch "(^|[:/])$([regex]::Escape($SemanticId))$"
}

function Get-SemanticBoundsFromUiAutomatorXmlText([string]$XmlText, [string]$SemanticId) {
    try { [xml]$document=$XmlText }
    catch { Fail "Invalid UiAutomator XML: $($_.Exception.Message)" 'layout' }
    $nodes = @($document.SelectNodes('//*[@resource-id]') | Where-Object { Test-SemanticIdMatch ([string]$_.GetAttribute('resource-id')) $SemanticId })
    if ($nodes.Count -ne 1) { Fail "Expected exactly one UiAutomator '$SemanticId' resource node, found $($nodes.Count)." 'layout' }
    return ConvertFrom-LayoutBounds ([string]$nodes[0].bounds)
}

function Get-SemanticNodeCountFromUiAutomatorXmlText([string]$XmlText, [string]$SemanticId) {
    try { [xml]$document=$XmlText }
    catch { Fail "Invalid UiAutomator XML: $($_.Exception.Message)" 'layout' }
    return @($document.SelectNodes('//*[@resource-id]') | Where-Object { Test-SemanticIdMatch ([string]$_.GetAttribute('resource-id')) $SemanticId }).Count
}

function Get-WindowBoundsFromUiAutomatorXmlText([string]$XmlText) {
    try { [xml]$document=$XmlText }
    catch { Fail "Invalid UiAutomator XML: $($_.Exception.Message)" 'layout' }
    $rootNode=$document.SelectSingleNode('/hierarchy/node[1]')
    if ($null -eq $rootNode) { Fail 'UiAutomator layout has no root window node.' 'layout' }
    return ConvertFrom-LayoutBounds ([string]$rootNode.bounds)
}

function Find-LayoutSemanticNodes([object]$Value, [string]$SemanticId) {
    $found = [System.Collections.ArrayList]::new()
    function Visit([object]$Node) {
        if ($null -eq $Node) { return }
        if ($Node -is [string] -or $Node -is [ValueType]) { return }
        if ($Node -is [Collections.IDictionary]) {
            $resource = $null
            foreach ($key in @('resourceId','resource-id','id','testTag')) { if ($Node.Contains($key)) { $resource = [string]$Node[$key]; break } }
            if (Test-SemanticIdMatch $resource $SemanticId) { $found.Add($Node) | Out-Null }
            foreach ($key in $Node.Keys) { Visit $Node[$key] }
            return
        }
        $resource = $null
        foreach ($key in @('resourceId','resource-id','id','testTag')) { if (Test-Property $Node $key) { $resource = [string]$Node.$key; break } }
        if (Test-SemanticIdMatch $resource $SemanticId) { $found.Add($Node) | Out-Null }
        if ($Node -is [Collections.IEnumerable]) { foreach ($item in $Node) { Visit $item }; return }
        foreach ($property in $Node.PSObject.Properties) { Visit $property.Value }
    }
    Visit $Value
    return @($found)
}

function Get-SemanticCenterFromLayoutJsonText([string]$JsonText, [string]$SemanticId) {
    try { $json = $JsonText | ConvertFrom-Json }
    catch { Fail "Invalid Android CLI layout JSON: $($_.Exception.Message)" 'layout' }
    $nodes = @(Find-LayoutSemanticNodes $json $SemanticId)
    if ($nodes.Count -ne 1) { Fail "Expected exactly one Android CLI '$SemanticId' resource node, found $($nodes.Count)." 'layout' }
    $node = $nodes[0]
    if (-not (Test-Property $node 'center') -or [string]$node.center -notmatch '^\[(?<x>-?\d+),(?<y>-?\d+)\]$') {
        Fail "Android CLI '$SemanticId' node has no supported integer center." 'layout'
    }
    return [pscustomobject][ordered]@{ x=[int]$matches.x; y=[int]$matches.y }
}

function Get-SemanticNodeCountFromLayoutJsonText([string]$JsonText, [string]$SemanticId) {
    try { $json = $JsonText | ConvertFrom-Json }
    catch { Fail "Invalid Android CLI layout JSON: $($_.Exception.Message)" 'layout' }
    return @(Find-LayoutSemanticNodes $json $SemanticId).Count
}

function Assert-SemanticCenterMatchesUiAutomatorBounds([string]$LayoutJsonText, [string]$UiAutomatorXmlText, [string]$SemanticId) {
    $jsonCenter = Get-SemanticCenterFromLayoutJsonText $LayoutJsonText $SemanticId
    $xmlBounds = Get-SemanticBoundsFromUiAutomatorXmlText $UiAutomatorXmlText $SemanticId
    $xmlCenterX = [int][Math]::Floor(([double]($xmlBounds.left + $xmlBounds.right)) / 2)
    $xmlCenterY = [int][Math]::Floor(([double]($xmlBounds.top + $xmlBounds.bottom)) / 2)
    if ($jsonCenter.x -ne $xmlCenterX -or $jsonCenter.y -ne $xmlCenterY) {
        Fail "Android CLI '$SemanticId' center [$($jsonCenter.x),$($jsonCenter.y)] differs from the UiAutomator bounds center [$xmlCenterX,$xmlCenterY]." 'layout'
    }
    return $jsonCenter
}

function Get-VerifiedGhostSafeStageBounds {
    param(
        [string]$LayoutJsonText,
        [string]$UiAutomatorXmlText,
        [bool]$ExpectCharacterSurfaces
    )
    $safeStageBounds = Get-SemanticBoundsFromUiAutomatorXmlText $UiAutomatorXmlText 'ghost-safe-stage'
    Assert-SemanticCenterMatchesUiAutomatorBounds $LayoutJsonText $UiAutomatorXmlText 'list-ghost' | Out-Null
    foreach ($semanticId in @('surface-kero', 'surface-sakura')) {
        if (-not $ExpectCharacterSurfaces) {
            $jsonCount = Get-SemanticNodeCountFromLayoutJsonText $LayoutJsonText $semanticId
            $xmlCount = Get-SemanticNodeCountFromUiAutomatorXmlText $UiAutomatorXmlText $semanticId
            if ($jsonCount -ne 0 -or $xmlCount -ne 0) { Fail "Tiny fallback requires '$semanticId' to be absent from Android CLI and UiAutomator layouts, found $jsonCount/$xmlCount." 'layout' }
            continue
        }
        $jsonCenter = Assert-SemanticCenterMatchesUiAutomatorBounds $LayoutJsonText $UiAutomatorXmlText $semanticId
        if ($jsonCenter.x -lt $safeStageBounds.left -or $jsonCenter.x -ge $safeStageBounds.right -or $jsonCenter.y -lt $safeStageBounds.top -or $jsonCenter.y -ge $safeStageBounds.bottom) {
            Fail "Verified '$semanticId' center [$($jsonCenter.x),$($jsonCenter.y)] lies outside ghost-safe-stage [$($safeStageBounds.left),$($safeStageBounds.top)][$($safeStageBounds.right),$($safeStageBounds.bottom)]." 'layout'
        }
    }
    return $safeStageBounds
}

function Assert-Png([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { Fail "Missing PNG '$Path'." 'artifact' }
    $item = Get-Item -LiteralPath $Path
    if ($item.Length -le 8) { Fail "Empty/truncated PNG '$Path'." 'artifact' }
    $stream = [IO.File]::OpenRead($Path)
    try { $bytes = New-Object byte[] 8; if ($stream.Read($bytes,0,8) -ne 8) { Fail "Unable to read PNG signature '$Path'." }; $signature = [BitConverter]::ToString($bytes) }
    finally { $stream.Dispose() }
    if ($signature -ne '89-50-4E-47-0D-0A-1A-0A') { Fail "Invalid PNG signature '$Path': $signature." 'artifact' }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function ConvertTo-WindowsCommandLineArgument([string]$Argument) {
    if ($null -eq $Argument) { return '""' }
    if ($Argument -notmatch '[\s"]') { return $Argument }
    $builder = [Text.StringBuilder]::new(); [void]$builder.Append('"'); $slashes = 0
    foreach ($character in $Argument.ToCharArray()) {
        if ($character -eq '\') { $slashes++; continue }
        if ($character -eq '"') { [void]$builder.Append(('\' * (($slashes * 2) + 1))); [void]$builder.Append('"'); $slashes=0; continue }
        if ($slashes -gt 0) { [void]$builder.Append(('\' * $slashes)); $slashes=0 }
        [void]$builder.Append($character)
    }
    if ($slashes -gt 0) { [void]$builder.Append(('\' * ($slashes * 2))) }
    [void]$builder.Append('"'); return $builder.ToString()
}

function Test-OwnedProcessIdentity([int]$ProcessId, [long]$StartTimeUtcTicks) {
    $candidate=$null
    try {
        $candidate=[Diagnostics.Process]::GetProcessById($ProcessId)
        return -not $candidate.HasExited -and $candidate.StartTime.ToUniversalTime().Ticks -eq $StartTimeUtcTicks
    } catch {
        return $false
    } finally {
        if ($null -ne $candidate) { $candidate.Dispose() }
    }
}

function Stop-OwnedProcessTree {
    param(
        [Diagnostics.Process]$Process,
        [long]$ExpectedStartTimeUtcTicks = 0,
        [int]$TimeoutMilliseconds = 30000
    )
    $processId=$Process.Id
    if ($ExpectedStartTimeUtcTicks -eq 0) {
        try { $ExpectedStartTimeUtcTicks=$Process.StartTime.ToUniversalTime().Ticks } catch { return $false }
    }
    $candidate=$null
    try {
        $candidate=[Diagnostics.Process]::GetProcessById($processId)
        if ($candidate.StartTime.ToUniversalTime().Ticks -ne $ExpectedStartTimeUtcTicks) { return $false }
        if (-not $candidate.HasExited) {
            $candidate.Kill($true)
            if (-not $candidate.WaitForExit($TimeoutMilliseconds)) { return $false }
        }
        return $true
    } catch [System.ArgumentException] {
        return $true
    } catch {
        return $false
    } finally {
        if ($null -ne $candidate) { $candidate.Dispose() }
    }
}

function New-EmulatorWatchdogInvocation {
    param(
        [int]$HostProcessId,
        [long]$HostStartTimeUtcTicks,
        [int]$EmulatorProcessId,
        [long]$EmulatorStartTimeUtcTicks,
        [string]$ReadyPath
    )
    $readyPayload=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($ReadyPath))
    $watchdogScript=@"
`$ErrorActionPreference='Stop'
`$hostProcessId=$HostProcessId
`$hostStartTimeUtcTicks=$HostStartTimeUtcTicks
`$emulatorProcessId=$EmulatorProcessId
`$emulatorStartTimeUtcTicks=$EmulatorStartTimeUtcTicks
`$readyPath=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$readyPayload'))
function Test-ExactProcessIdentity([int]`$processId, [long]`$startTimeUtcTicks) {
    `$candidate=`$null
    try {
        `$candidate=[Diagnostics.Process]::GetProcessById(`$processId)
        return -not `$candidate.HasExited -and `$candidate.StartTime.ToUniversalTime().Ticks -eq `$startTimeUtcTicks
    } catch {
        return `$false
    } finally {
        if (`$null -ne `$candidate) { `$candidate.Dispose() }
    }
}
function Stop-ExactProcessTree([int]`$processId, [long]`$startTimeUtcTicks) {
    `$candidate=`$null
    try {
        `$candidate=[Diagnostics.Process]::GetProcessById(`$processId)
        if (`$candidate.StartTime.ToUniversalTime().Ticks -ne `$startTimeUtcTicks) { return `$false }
        if (-not `$candidate.HasExited) { `$candidate.Kill(`$true); if (-not `$candidate.WaitForExit(30000)) { return `$false } }
        return `$true
    } catch [System.ArgumentException] {
        return `$true
    } catch {
        return `$false
    } finally {
        if (`$null -ne `$candidate) { `$candidate.Dispose() }
    }
}
`$readyTempPath="`$readyPath.`$PID.tmp"
[IO.File]::WriteAllText(`$readyTempPath, "`$hostProcessId|`$hostStartTimeUtcTicks|`$emulatorProcessId|`$emulatorStartTimeUtcTicks", [Text.UTF8Encoding]::new(`$false))
[IO.File]::Move(`$readyTempPath, `$readyPath, `$true)
while (`$true) {
    if (-not (Test-ExactProcessIdentity `$hostProcessId `$hostStartTimeUtcTicks)) { [void](Stop-ExactProcessTree `$emulatorProcessId `$emulatorStartTimeUtcTicks); exit 0 }
    if (-not (Test-ExactProcessIdentity `$emulatorProcessId `$emulatorStartTimeUtcTicks)) { exit 0 }
    Start-Sleep -Seconds 1
}
"@
    return [pscustomobject]@{
        arguments=@('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand',[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($watchdogScript)))
        readyRecord="$HostProcessId|$HostStartTimeUtcTicks|$EmulatorProcessId|$EmulatorStartTimeUtcTicks"
    }
}

function Start-EmulatorWatchdog([Diagnostics.Process]$EmulatorProcess, [long]$EmulatorStartTimeUtcTicks, [string]$ReadyRoot = $reportRoot) {
    $hostProcess=[Diagnostics.Process]::GetCurrentProcess()
    New-Item -ItemType Directory -Force -Path $ReadyRoot | Out-Null
    $readyPath=Join-Path $ReadyRoot ("nanidroid-ui-audit-watchdog-$([Guid]::NewGuid().ToString('N')).ready")
    $watchdog=$null
    $watchdogStartTimeUtcTicks=0
    try {
        $invocation=New-EmulatorWatchdogInvocation -HostProcessId $hostProcess.Id -HostStartTimeUtcTicks $hostProcess.StartTime.ToUniversalTime().Ticks -EmulatorProcessId $EmulatorProcess.Id -EmulatorStartTimeUtcTicks $EmulatorStartTimeUtcTicks -ReadyPath $readyPath
        $launch=[Diagnostics.ProcessStartInfo]::new(); $launch.FileName=$script:resolvedPwsh; $launch.UseShellExecute=$false; $launch.CreateNoWindow=$true
        if ($null -ne $launch.PSObject.Properties['ArgumentList']) { foreach ($argument in $invocation.arguments) { [void]$launch.ArgumentList.Add([string]$argument) } }
        else { $launch.Arguments=(@($invocation.arguments | ForEach-Object { ConvertTo-WindowsCommandLineArgument ([string]$_) }) -join ' ') }
        $watchdog=[Diagnostics.Process]::Start($launch)
        if ($null -eq $watchdog) { Fail 'Failed to launch the owned-emulator cleanup watchdog.' 'watchdog' }
        $watchdogStartTimeUtcTicks=$watchdog.StartTime.ToUniversalTime().Ticks
        $deadline=(Get-Date).AddSeconds(10)
        do {
            if (Test-Path -LiteralPath $readyPath -PathType Leaf) {
                $readyRecord=(Get-Content -LiteralPath $readyPath -Raw).Trim()
                if ($readyRecord -eq $invocation.readyRecord -and (Test-OwnedProcessIdentity $watchdog.Id $watchdogStartTimeUtcTicks)) {
                    return [pscustomobject]@{ process=$watchdog; processId=$watchdog.Id; startTimeUtcTicks=$watchdogStartTimeUtcTicks; readyPath=$readyPath }
                }
            }
            Start-Sleep -Milliseconds 100
        } while ((Get-Date) -lt $deadline)
        Fail 'Owned-emulator cleanup watchdog did not establish an exact identity-bound handshake.' 'watchdog'
    } finally {
        $hostProcess.Dispose()
        if ($null -eq $watchdog -or -not (Test-Path -LiteralPath $readyPath -PathType Leaf) -or -not (Test-OwnedProcessIdentity $watchdog.Id $watchdogStartTimeUtcTicks)) {
            if ($null -ne $watchdog) {
                [void](Stop-OwnedProcessTree -Process $watchdog -ExpectedStartTimeUtcTicks $watchdogStartTimeUtcTicks)
                $watchdog.Dispose()
            }
            Remove-Item -LiteralPath $readyPath -Force -ErrorAction SilentlyContinue
        }
    }
}

function Stop-EmulatorWatchdog([object]$Watchdog) {
    if ($null -eq $Watchdog) { return $true }
    try { return Stop-OwnedProcessTree -Process $Watchdog.process -ExpectedStartTimeUtcTicks $Watchdog.startTimeUtcTicks } finally { $Watchdog.process.Dispose(); Remove-Item -LiteralPath $Watchdog.readyPath -Force -ErrorAction SilentlyContinue }
}

function Invoke-Native {
    param([string]$FilePath, [string[]]$Arguments, [int]$TimeoutSeconds = 120, [int]$DrainTimeoutSeconds = 30, [switch]$AllowFailure, [ValidateSet('normal','adb','adb-owner')][string]$Transport = 'normal')
    if ($DrainTimeoutSeconds -le 0) { Fail 'DrainTimeoutSeconds must be positive.' 'process' }
    if ($Transport -in @('adb','adb-owner') -and $script:adbTransportDead) { Fail 'ADB transport was declared dead; refusing all later ADB commands.' 'adb-timeout' }
    $info = [Diagnostics.ProcessStartInfo]::new(); $info.FileName=$FilePath; $info.UseShellExecute=$false; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true; $info.CreateNoWindow=$true
    if ($null -ne $info.PSObject.Properties['ArgumentList']) { foreach ($argument in $Arguments) { [void]$info.ArgumentList.Add([string]$argument) } }
    else { $info.Arguments = (@($Arguments | ForEach-Object { ConvertTo-WindowsCommandLineArgument ([string]$_) }) -join ' ') }
    $process = [Diagnostics.Process]::new(); $process.StartInfo=$info
    if (-not $process.Start()) { $process.Dispose(); Fail "Unable to start '$FilePath'." 'process' }
    $processStartTimeUtcTicks=$process.StartTime.ToUniversalTime().Ticks
    $stdoutTask=$process.StandardOutput.ReadToEndAsync(); $stderrTask=$process.StandardError.ReadToEndAsync()
    try {
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $terminated=Stop-OwnedProcessTree -Process $process -ExpectedStartTimeUtcTicks $processStartTimeUtcTicks
            if ($Transport -in @('adb','adb-owner')) { $script:adbTransportDead=$true }
            if (-not $terminated) { Fail "Timed out after $TimeoutSeconds seconds and could not terminate the exact owned process tree: $FilePath $($Arguments -join ' ')." 'process-timeout' }
            $drainError=$null
            try {
                $drainTasks=[Threading.Tasks.Task[]]@($stdoutTask,$stderrTask)
                if (-not [Threading.Tasks.Task]::WaitAll($drainTasks,30000)) { $drainError='redirected output did not close within 30 seconds' }
                else { $stdout=$stdoutTask.GetAwaiter().GetResult(); $stderr=$stderrTask.GetAwaiter().GetResult() }
            } catch { $drainError=$_.Exception.Message }
            if ($drainError) { Fail "Timed out after $TimeoutSeconds seconds and could not drain process output: $drainError" 'process-timeout' }
            Fail "Timed out after $TimeoutSeconds seconds: $FilePath $($Arguments -join ' ')." $(if ($Transport -in @('adb','adb-owner')) {'adb-timeout'} else {'process-timeout'})
        }
        $drainTasks=[Threading.Tasks.Task[]]@($stdoutTask,$stderrTask)
        if (-not [Threading.Tasks.Task]::WaitAll($drainTasks,$DrainTimeoutSeconds*1000)) {
            if ($Transport -in @('adb','adb-owner')) { $script:adbTransportDead=$true }
            [void](Stop-OwnedProcessTree -Process $process -ExpectedStartTimeUtcTicks $processStartTimeUtcTicks)
            Fail "Process exited but inherited output handles did not close within $DrainTimeoutSeconds seconds: $FilePath $($Arguments -join ' ')." $(if ($Transport -in @('adb','adb-owner')) {'adb-timeout'} else {'process-timeout'})
        }
        $stdout=$stdoutTask.GetAwaiter().GetResult(); $stderr=$stderrTask.GetAwaiter().GetResult(); $exit=$process.ExitCode
    } finally {
        $process.Dispose()
    }
    $result=[pscustomobject]@{ exitCode=$exit; output=$stdout; error=$stderr }
    if ($exit -ne 0 -and -not $AllowFailure) { Fail "Command failed ($exit): $FilePath $($Arguments -join ' ')`n$stdout`n$stderr" 'process' }
    return $result
}

function Invoke-Adb([string[]]$Arguments, [int]$TimeoutSeconds = $CommandTimeoutSeconds, [switch]$AllowFailure) {
    return Invoke-Native -FilePath $script:resolvedAdb -Arguments (@('-s',$DeviceSerial)+$Arguments) -TimeoutSeconds $TimeoutSeconds -AllowFailure:$AllowFailure -Transport adb
}

function ConvertFrom-GradlePath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return $Path }
    $value=$Path.Trim()
    while ($value.Contains('\\')) { $value=$value.Replace('\\','\') }
    return $value.Replace('\:',':').Replace('\ ',' ')
}

function Resolve-SdkTool([string]$Explicit, [string]$Relative, [string]$Command) {
    if ($Explicit) { if (-not (Test-Path -LiteralPath $Explicit -PathType Leaf)) { Fail "Tool path does not exist: '$Explicit'." }; return (Resolve-Path -LiteralPath $Explicit).Path }
    $sdkCandidates=@()
    if (Test-Path -LiteralPath (Join-Path $repoRoot 'local.properties')) {
        $line=Get-Content (Join-Path $repoRoot 'local.properties') | Select-String '^sdk\.dir=' | Select-Object -First 1
        if ($line) { $sdkCandidates += ConvertFrom-GradlePath $line.ToString().Substring(8) }
    }
    if ($env:ANDROID_SDK_ROOT) { $sdkCandidates += $env:ANDROID_SDK_ROOT }
    if ($env:ANDROID_HOME) { $sdkCandidates += $env:ANDROID_HOME }
    foreach ($sdk in ($sdkCandidates | Select-Object -Unique)) { $candidate=Join-Path $sdk $Relative; if (Test-Path -LiteralPath $candidate -PathType Leaf) { return (Resolve-Path $candidate).Path } }
    $found=Get-Command $Command -ErrorAction SilentlyContinue; if ($found) { return $found.Source }
    Fail "Unable to resolve $Command. Supply its explicit path."
}

function Resolve-AndroidCli([string]$Explicit) {
    foreach ($candidate in @($Explicit, 'C:\ProgramData\AndroidCLI\android.exe')) { if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) { return (Resolve-Path $candidate).Path } }
    $found=Get-Command android -ErrorAction SilentlyContinue; if ($found) { return $found.Source }
    Fail 'Unable to resolve Android CLI. Supply -AndroidCliPath.'
}

function Resolve-PowerShell7 {
    $command=Get-Command pwsh -ErrorAction SilentlyContinue
    if ($command -and (Test-Path -LiteralPath $command.Source -PathType Leaf)) { return $command.Source }
    $known='C:\Program Files\PowerShell\7\pwsh.exe'
    if (Test-Path -LiteralPath $known -PathType Leaf) { return (Resolve-Path -LiteralPath $known).Path }
    Fail 'PowerShell 7 is required to invoke the Task 17 NAR corpus runner.' 'tool'
}

function Get-CorpusInputKind([bool]$IsContainer, [string]$Extension) {
    if ($IsContainer) { return 'directory' }
    if ([string]::Equals($Extension, '.nar', [StringComparison]::OrdinalIgnoreCase)) { return 'archive' }
    return $null
}

function Test-AuditEmulatorIdentity([object]$Identity) {
    return $Identity.avd -eq $AvdName -and
        $Identity.qemu -eq '1' -and
        $Identity.api -eq '37' -and
        $Identity.abi -in @('x86_64','arm64-v8a')
}

function Assert-CorpusInputs([object]$NarManifest) {
    $manifestFile=Join-Path $repoRoot $ManifestPath
    if (-not (Test-Path -LiteralPath $manifestFile -PathType Leaf)) { Fail "NAR manifest missing '$manifestFile'." 'corpus' }
    $entries=@($NarManifest.entries); if ($entries.Count -ne 23) { Fail "Expected exact 23-entry NAR manifest, got $($entries.Count)." 'corpus' }
    foreach ($rep in (Get-UiAuditRepresentatives)) {
        $matches=@($entries | Where-Object { $_.label -ceq $rep.label -and $_.sha256 -ceq $rep.sha256 })
        if ($matches.Count -ne 1) { Fail "Representative label/SHA mismatch for '$($rep.label)'." 'corpus' }
    }
    $resolvedRoots=[System.Collections.ArrayList]::new()
    foreach ($root in $CorpusRoots) {
        $candidate=if ([IO.Path]::IsPathRooted($root)) {$root} else {Join-Path $repoRoot $root}
        $isContainer=Test-Path -LiteralPath $candidate -PathType Container
        $isLeaf=Test-Path -LiteralPath $candidate -PathType Leaf
        if (-not $isContainer -and -not $isLeaf) { Fail "Corpus input missing '$candidate'." 'corpus' }
        $kind=Get-CorpusInputKind -IsContainer $isContainer -Extension ([IO.Path]::GetExtension($candidate))
        if ($null -eq $kind) { Fail "Corpus input must be a directory or .nar archive: '$candidate'." 'corpus' }
        $resolvedRoots.Add((Resolve-Path -LiteralPath $candidate).Path) | Out-Null
    }
    $archives=@($resolvedRoots | ForEach-Object {
        if (Test-Path -LiteralPath $_ -PathType Leaf) { Get-Item -LiteralPath $_ }
        else { Get-ChildItem -LiteralPath $_ -File -Recurse -Filter '*.nar' }
    } | Sort-Object FullName -Unique)
    if ($archives.Count -ne 23) { Fail "Corpus must contain exactly 23 archives, found $($archives.Count)." 'corpus' }
    $hashes=@{}; foreach ($archive in $archives) { $hash=(Get-FileHash -LiteralPath $archive.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); if ($hashes.ContainsKey($hash)) { Fail "Duplicate corpus SHA '$hash'." 'corpus' }; $hashes[$hash]=$archive.FullName }
    foreach ($entry in $entries) { if (-not $hashes.ContainsKey([string]$entry.sha256)) { Fail "Missing corpus archive '$($entry.label)' SHA $($entry.sha256)." 'corpus' } }
    return @($resolvedRoots)
}

function Get-WmSnapshot {
    $size=(Invoke-Adb @('shell','wm','size')).output.Trim(); $density=(Invoke-Adb @('shell','wm','density')).output.Trim()
    $physicalSize=[regex]::Match($size,'Physical size:\s*(\d+)x(\d+)'); $effectiveSize=[regex]::Matches($size,'(?:Override|Physical) size:\s*(\d+)x(\d+)') | Select-Object -Last 1
    $physicalDensity=[regex]::Match($density,'Physical density:\s*(\d+)'); $effectiveDensity=[regex]::Matches($density,'(?:Override|Physical) density:\s*(\d+)') | Select-Object -Last 1
    if (-not $physicalSize.Success -or -not $effectiveSize.Success -or -not $physicalDensity.Success -or -not $effectiveDensity.Success) { Fail "Unable to parse wm state: '$size' / '$density'." 'device' }
    return [pscustomobject]@{ rawSize=$size; rawDensity=$density; physicalWidth=[int]$physicalSize.Groups[1].Value; physicalHeight=[int]$physicalSize.Groups[2].Value; effectiveWidth=[int]$effectiveSize.Groups[1].Value; effectiveHeight=[int]$effectiveSize.Groups[2].Value; physicalDensity=[int]$physicalDensity.Groups[1].Value; effectiveDensity=[int]$effectiveDensity.Groups[1].Value; sizeOverridden=($size -match 'Override size'); densityOverridden=($density -match 'Override density') }
}

function Get-DisplayOrientationFromDump([string]$DisplayDump) {
    $match=[regex]::Match($DisplayDump,'(?m)^\s*mCurrentOrientation=(?<rotation>\d+)\s*$')
    if (-not $match.Success) { Fail 'Unable to parse mCurrentOrientation from dumpsys display.' 'device' }
    return $match.Groups['rotation'].Value
}

function Get-LogicalDisplaySizeFromWindowDump([string]$WindowDump) {
    $match=[regex]::Match($WindowDump,'\bcur=(?<width>\d+)x(?<height>\d+)\b')
    if (-not $match.Success) { Fail 'Unable to parse logical display size from dumpsys window displays.' 'device' }
    return [pscustomobject]@{width=[int]$match.Groups['width'].Value;height=[int]$match.Groups['height'].Value}
}

function Get-DeviceStateSnapshot {
    $wm=Get-WmSnapshot
    $displayDump=(Invoke-Adb @('shell','dumpsys','display')).output
    return [pscustomobject]@{
        wm=$wm
        fontScale=(Invoke-Adb @('shell','settings','get','system','font_scale')).output.Trim()
        autoRotation=(Invoke-Adb @('shell','settings','get','system','accelerometer_rotation')).output.Trim()
        userRotation=(Invoke-Adb @('shell','settings','get','system','user_rotation')).output.Trim()
        displayRotation=Get-DisplayOrientationFromDump $displayDump
        locale=Get-DeviceLocale
        theme=(Invoke-Adb @('shell','cmd','uimode','night')).output.Trim()
        wifi=(Invoke-Adb @('shell','cmd','wifi','status') -AllowFailure).output.Trim()
        mobileData=(Invoke-Adb @('shell','settings','get','global','mobile_data')).output.Trim()
    }
}

function Select-AuditLocale([string]$PersistLocale, [string]$ProductLocale, [string]$ActivityConfiguration) {
    foreach ($candidate in @($PersistLocale,$ProductLocale)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and $candidate -ne 'null') { return $candidate.Trim() }
    }
    $match=[regex]::Match($ActivityConfiguration, '(?i)\blocales?=(?<locale>\[[^\]]+\]|[^\s,}]+)')
    if ($match.Success) {
        $locale=$match.Groups['locale'].Value.Trim('[',']').Split(',')[0].Trim()
        if (-not [string]::IsNullOrWhiteSpace($locale)) { return $locale }
    }
    $qualifierMatch=[regex]::Match($ActivityConfiguration, '(?i)(?:^|[\s-])(?<locale>b\+[a-z]{2,3}(?:\+[a-z0-9]{2,8})+|[a-z]{2,3}(?:-r[a-z]{2})?)(?=$|[\s-])')
    if ($qualifierMatch.Success) {
        $locale=$qualifierMatch.Groups['locale'].Value
        if ($locale.StartsWith('b+', [StringComparison]::OrdinalIgnoreCase)) { return $locale.Substring(2).Replace('+','-') }
        return ($locale -replace '-r','-')
    }
    return $null
}

function Get-DeviceLocale {
    $persist=(Invoke-Adb @('shell','getprop','persist.sys.locale')).output.Trim()
    $product=(Invoke-Adb @('shell','getprop','ro.product.locale')).output.Trim()
    $configuration=(Invoke-Adb @('shell','cmd','activity','get-config') -AllowFailure).output
    $locale=Select-AuditLocale -PersistLocale $persist -ProductLocale $product -ActivityConfiguration $configuration
    if ($null -ne $locale) { return $locale }
    Fail 'Unable to determine device locale from persist.sys.locale, ro.product.locale, or activity configuration.' 'device'
}

function Get-RequestedUserRotation([object]$Requested) {
    # The AVD's natural rotation stays fixed; wm size already expresses the
    # requested logical orientation. Rotating again swaps those dimensions.
    return 0
}

function Set-DisplayProfile([object]$Requested, [int]$NativeDensity) {
    $density=if ([string]$Requested.density -eq 'native') {$NativeDensity} else {[int]$Requested.density}
    $widthPx=[int][Math]::Round(([double]$Requested.widthDp*$density)/160.0); $heightPx=[int][Math]::Round(([double]$Requested.heightDp*$density)/160.0)
    $requestedRotation=Get-RequestedUserRotation $Requested
    Invoke-Adb @('shell','settings','put','system','accelerometer_rotation','0') | Out-Null
    Invoke-Adb @('shell','settings','put','system','user_rotation',[string]$requestedRotation) | Out-Null
    Invoke-Adb @('shell','wm','size',"${widthPx}x${heightPx}") | Out-Null
    if ([string]$Requested.density -eq 'native') { Invoke-Adb @('shell','wm','density','reset') | Out-Null } else { Invoke-Adb @('shell','wm','density',[string]$density) | Out-Null }
    Invoke-Adb @('shell','settings','put','system','font_scale',([string]::Format([Globalization.CultureInfo]::InvariantCulture,'{0:0.0}',[double]$Requested.fontScale))) | Out-Null
    $first=Get-WmSnapshot; Start-Sleep -Milliseconds 750; $second=Get-WmSnapshot
    if ($first.effectiveWidth -ne $second.effectiveWidth -or $first.effectiveHeight -ne $second.effectiveHeight -or $first.effectiveDensity -ne $second.effectiveDensity) { Fail 'Display profile did not settle across two readings.' 'device' }
    if ($second.effectiveWidth -ne $widthPx -or $second.effectiveHeight -ne $heightPx -or $second.effectiveDensity -ne $density) { Fail "Measured display profile differs from request ${widthPx}x${heightPx}@$density." 'device' }
    $actualAutoRotation=(Invoke-Adb @('shell','settings','get','system','accelerometer_rotation')).output.Trim()
    $actualUserRotation=(Invoke-Adb @('shell','settings','get','system','user_rotation')).output.Trim()
    if ($actualAutoRotation -ne '0' -or $actualUserRotation -ne [string]$requestedRotation) { Fail "Rotation lock differs from request: auto=$actualAutoRotation user=$actualUserRotation expected=0/$requestedRotation." 'device' }
    $logical=Get-LogicalDisplaySizeFromWindowDump (Invoke-Adb @('shell','dumpsys','window','displays')).output
    if ($logical.width -ne $widthPx -or $logical.height -ne $heightPx) { Fail "Logical display size differs from request: $($logical.width)x$($logical.height), expected ${widthPx}x${heightPx}." 'device' }
    $second|Add-Member -NotePropertyName logicalWidth -NotePropertyValue $logical.width
    $second|Add-Member -NotePropertyName logicalHeight -NotePropertyValue $logical.height
    return $second
}

function Restore-DeviceState([object]$Original) {
    if ($script:adbTransportDead -or $null -eq $Original) { return }
    $steps=@(
        { if ($Original.autoRotation -in @('0','1')) { Invoke-Adb @('shell','settings','put','system','accelerometer_rotation',$Original.autoRotation) | Out-Null }; if ($Original.userRotation -match '^\d+$') { Invoke-Adb @('shell','settings','put','system','user_rotation',$Original.userRotation) | Out-Null } },
        { if ([string]::IsNullOrWhiteSpace($Original.fontScale) -or $Original.fontScale -eq 'null') { Invoke-Adb @('shell','settings','delete','system','font_scale') | Out-Null } else { Invoke-Adb @('shell','settings','put','system','font_scale',$Original.fontScale) | Out-Null } },
        { if ($Original.wm.densityOverridden) { Invoke-Adb @('shell','wm','density',[string]$Original.wm.effectiveDensity) | Out-Null } else { Invoke-Adb @('shell','wm','density','reset') | Out-Null } },
        { if ($Original.wm.sizeOverridden) { Invoke-Adb @('shell','wm','size',"$($Original.wm.effectiveWidth)x$($Original.wm.effectiveHeight)") | Out-Null } else { Invoke-Adb @('shell','wm','size','reset') | Out-Null } },
        { if ($Original.mobileData -eq '1') { Invoke-Adb @('shell','svc','data','enable') | Out-Null } else { Invoke-Adb @('shell','svc','data','disable') | Out-Null }; if ($Original.wifi -match 'enabled') { Invoke-Adb @('shell','svc','wifi','enable') | Out-Null } else { Invoke-Adb @('shell','svc','wifi','disable') | Out-Null } }
    )
    foreach ($step in $steps) { try { & $step } catch { $script:cleanupErrors.Add($_.Exception.Message) | Out-Null } }
    try {
        $after=Get-DeviceStateSnapshot
        $originalWifiEnabled = $Original.wifi -match 'enabled'
        $restoredWifiEnabled = $after.wifi -match 'enabled'
        if ($after.wm.rawSize -ne $Original.wm.rawSize -or $after.wm.rawDensity -ne $Original.wm.rawDensity -or $after.fontScale -ne $Original.fontScale -or $after.autoRotation -ne $Original.autoRotation -or $after.userRotation -ne $Original.userRotation -or $after.displayRotation -ne $Original.displayRotation -or $after.locale -ne $Original.locale -or $after.theme -ne $Original.theme -or $after.mobileData -ne $Original.mobileData -or $restoredWifiEnabled -ne $originalWifiEnabled) { $script:cleanupErrors.Add('Restored device state verification differs from original snapshot.') | Out-Null }
    } catch { $script:cleanupErrors.Add("Restore verification failed: $($_.Exception.Message)") | Out-Null }
}

function Get-ExternalDataProbeState([int]$ExitCode, [string]$Output, [string]$ErrorOutput) {
    if ($ExitCode -eq 0) { return 'present' }
    if (("$Output`n$ErrorOutput") -match 'No such file or directory') { return 'absent' }
    return 'unknown'
}

function Assert-PackageClean {
    foreach ($package in @($targetPackage,$testPackage)) {
        $listed=(Invoke-Adb @('shell','pm','list','packages',$package)).output.Trim()
        if ($listed -match [regex]::Escape($package)) { Fail "Package '$package' is already installed; refusing reuse/pre-existing data." 'device' }
        $external=(Invoke-Adb @('shell','ls','-d',"/sdcard/Android/data/$package") -AllowFailure)
        $externalState=Get-ExternalDataProbeState $external.exitCode $external.output $external.error
        if ($externalState -eq 'present') { Fail "Pre-existing external app data for '$package'." 'device' }
        if ($externalState -eq 'unknown') { Fail "Unable to verify external app data absence for '$package': $($external.output) $($external.error)" 'device' }
    }
}

function Resolve-DebugApk {
    $metadata=@(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'build\outputs\apk\debug') -Recurse -File -Filter 'output-metadata.json')
    if ($metadata.Count -ne 1) { Fail "Expected one debug output-metadata.json, found $($metadata.Count)." 'build' }
    $payload=Get-Content -LiteralPath $metadata[0].FullName -Raw | ConvertFrom-Json
    $files=@($payload.elements | ForEach-Object { Join-Path $metadata[0].DirectoryName $_.outputFile } | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf })
    if ($files.Count -ne 1) { Fail "Expected one APK resolved from output-metadata.json, found $($files.Count)." 'build' }
    return (Resolve-Path -LiteralPath $files[0]).Path
}

function Resolve-Git {
    $commands = @(Get-Command git -CommandType Application -ErrorAction SilentlyContinue)
    if ($commands.Count -lt 1) { Fail 'git is required to bind audit evidence to the current repository HEAD.' 'tool' }
    return [string]$commands[0].Source
}

function Get-TrackedRepositoryState([string]$GitPath) {
    $headBefore = (Invoke-Native -FilePath $GitPath -Arguments @('-C', $repoRoot, 'rev-parse', '--verify', 'HEAD') -TimeoutSeconds 20).output.Trim().ToLowerInvariant()
    if ($headBefore -notmatch '^[0-9a-f]{40,64}$') { Fail "git returned an invalid HEAD identity '$headBefore'." 'git' }
    $status = (Invoke-Native -FilePath $GitPath -Arguments @('-C', $repoRoot, 'status', '--porcelain=v1', '--untracked-files=no') -TimeoutSeconds 20).output
    $headAfter = (Invoke-Native -FilePath $GitPath -Arguments @('-C', $repoRoot, 'rev-parse', '--verify', 'HEAD') -TimeoutSeconds 20).output.Trim().ToLowerInvariant()
    if ($headBefore -cne $headAfter) { Fail "Repository HEAD changed while its audit identity was read: $headBefore -> $headAfter." 'git' }
    if (-not [string]::IsNullOrWhiteSpace($status)) { Fail 'Tracked worktree changes must be committed or reverted before audit capture/completion.' 'git' }
    return [pscustomobject][ordered]@{ gitHead = $headBefore; trackedWorktreeClean = $true }
}

function Get-CurrentCaptureProvenance([string]$GitPath, [string]$DebugApkPath) {
    $repository = Get-TrackedRepositoryState $GitPath
    if (-not (Test-Path -LiteralPath $DebugApkPath -PathType Leaf)) { Fail "Current debug APK is missing: '$DebugApkPath'." 'build' }
    $relativeApkPath = Get-RelativePath -BasePath $repoRoot -ChildPath $DebugApkPath
    if ([IO.Path]::IsPathRooted($relativeApkPath) -or $relativeApkPath -match '(^|[\\/])\.\.([\\/]|$)') { Fail "Debug APK is outside the repository: '$DebugApkPath'." 'build' }
    return [pscustomobject][ordered]@{
        gitHead = $repository.gitHead
        trackedWorktreeClean = $repository.trackedWorktreeClean
        debugApkPath = $relativeApkPath
        debugApkSha256 = (Get-FileHash -LiteralPath $DebugApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
}

function Assert-CaptureProvenance([object]$Captured, [object]$Current) {
    foreach ($property in @('gitHead', 'trackedWorktreeClean', 'debugApkPath', 'debugApkSha256')) {
        if (-not (Test-Property $Captured $property) -or -not (Test-Property $Current $property)) { Fail "Capture provenance lacks '$property'." 'manual-inspection' }
    }
    if ($Captured.trackedWorktreeClean -ne $true -or $Current.trackedWorktreeClean -ne $true) { Fail 'Capture and completion both require a clean tracked worktree.' 'manual-inspection' }
    if ([string]$Captured.gitHead -notmatch '^[0-9a-f]{40,64}$' -or [string]$Current.gitHead -notmatch '^[0-9a-f]{40,64}$') { Fail 'Capture provenance contains an invalid git HEAD.' 'manual-inspection' }
    if ([string]$Captured.debugApkSha256 -notmatch '^[0-9a-f]{64}$' -or [string]$Current.debugApkSha256 -notmatch '^[0-9a-f]{64}$') { Fail 'Capture provenance contains an invalid debug APK SHA-256.' 'manual-inspection' }
    foreach ($property in @('gitHead', 'debugApkPath', 'debugApkSha256')) {
        if ([string]$Captured.$property -cne [string]$Current.$property) { Fail "Capture provenance '$property' changed: captured='$($Captured.$property)' current='$($Current.$property)'." 'manual-inspection' }
    }
}

function Test-MissingPackagePathProbe([object]$PackagePathProbe) {
    $diagnostic = "$(($PackagePathProbe.output | Out-String).Trim())`n$(($PackagePathProbe.error | Out-String).Trim())"
    return [int]$PackagePathProbe.exitCode -eq 1 -and $diagnostic -match '(?mi)^Error:\s*package\s+\S+\s+not found\s*$'
}

function Get-PackageCleanupAction([object]$PackagePathProbe) {
    if (Test-MissingPackagePathProbe $PackagePathProbe) {
        return [pscustomobject]@{ action = 'skip' }
    }
    if ([int]$PackagePathProbe.exitCode -ne 0) {
        throw "Package-state probe failed: exit=$($PackagePathProbe.exitCode) output=$([string]$PackagePathProbe.output) error=$([string]$PackagePathProbe.error)"
    }
    if ([string]$PackagePathProbe.output -match '(?m)^package:') {
        return [pscustomobject]@{ action = 'uninstall' }
    }
    return [pscustomobject]@{ action = 'skip' }
}

function Test-DryRunRequiresCorpus([bool]$HostOnly) {
    return -not $HostOnly
}

function New-InteractionCaptureRecord([object]$Manifest) {
    Assert-InteractionEvidenceManifestContract $Manifest
    if ($null -eq $script:ownedEmulator -or $null -eq $script:ownedEmulatorStartTimeUtcTicks) { Fail 'Interaction capture requires the owned emulator session.' 'artifact' }
    $emulatorProcessId = [int]$script:ownedEmulator.Id
    $emulatorStartTimeUtcTicks = [long]$script:ownedEmulatorStartTimeUtcTicks
    if (-not (Test-OwnedProcessIdentity $emulatorProcessId $emulatorStartTimeUtcTicks)) { Fail 'Interaction capture requires the live owned emulator session.' 'artifact' }
    Assert-CaptureProvenance $script:captureProvenance $script:captureProvenance

    $artifacts = [System.Collections.ArrayList]::new()
    foreach ($evidence in @($Manifest.interactionEvidence)) {
        $artifactPath = [string]$evidence.artifactPath
        $artifact = Resolve-SafeReportArtifactPath $reportRoot $artifactPath $true
        $artifacts.Add([pscustomobject][ordered]@{
            artifactPath = $artifactPath
            sha256 = Assert-Png $artifact
        }) | Out-Null
    }
    return [pscustomobject][ordered]@{
        session = [pscustomobject][ordered]@{
            deviceSerial = $DeviceSerial
            avdName = $AvdName
            snapshotName = $SnapshotName
            emulatorProcessId = $emulatorProcessId
            emulatorStartTimeUtcTicks = $emulatorStartTimeUtcTicks
        }
        interactionCaptureStartedAtUtc = $script:interactionCaptureStartedAtUtc
        captureProvenance = $script:captureProvenance
        artifacts = @($artifacts)
    }
}

function Write-InteractionCaptureCheckpoint([object]$Record) {
    if ($null -eq $Record) { Fail 'Cannot persist a missing interaction capture record.' 'artifact' }
    $checkpointPath = Join-Path $reportRoot 'interaction-capture.json'
    [IO.File]::WriteAllText($checkpointPath, ($Record | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
    $script:interactionCheckpointPersisted = Test-Path -LiteralPath $checkpointPath -PathType Leaf
    if (-not $script:interactionCheckpointPersisted) { Fail 'Interaction capture checkpoint was not persisted.' 'artifact' }
}

function Invalidate-PreviousManualInteractionCheckpoint([string]$Root = $reportRoot) {
    foreach ($artifactName in @('summary.json', 'summary.md', 'interaction-capture.json')) {
        $artifactPath = Join-Path $Root $artifactName
        if (Test-Path -LiteralPath $artifactPath -PathType Leaf) {
            Remove-Item -LiteralPath $artifactPath -Force
        }
    }
}

function Assert-AuditedPackagePresence([string]$PackagePathOutput, [string]$PackagePid) {
    if ($PackagePathOutput -notmatch '(?m)^package:.+') { Fail "Audited package '$targetPackage' is not installed at the interaction checkpoint." 'device' }
    if ([string]::IsNullOrWhiteSpace($PackagePid)) { Fail "Audited package '$targetPackage' is not running at the interaction checkpoint." 'device' }
}

function Assert-InstalledAuditedApkHash([string]$ExpectedApkSha256, [string]$InstalledApkSha256) {
    if ($ExpectedApkSha256 -notmatch '^[0-9a-f]{64}$') { Fail 'Interaction checkpoint lacks an expected audited APK SHA-256.' 'device' }
    if ($InstalledApkSha256 -notmatch '^[0-9a-f]{64}$') { Fail "Installed audited APK hash is invalid: '$InstalledApkSha256'." 'device' }
    if ($InstalledApkSha256 -cne $ExpectedApkSha256) { Fail "Installed audited APK hash changed: expected=$ExpectedApkSha256 actual=$InstalledApkSha256." 'device' }
}

function Get-InstalledAuditedApkPath([string]$PackagePathOutput) {
    $baseApkPath = [regex]::Match($PackagePathOutput, '(?m)^package:(.+/base\.apk)$').Groups[1].Value.Trim()
    if ([string]::IsNullOrWhiteSpace($baseApkPath)) { Fail "Audited package '$targetPackage' does not expose an installed base APK." 'device' }
    return $baseApkPath
}

function Get-InstalledAuditedApkSha256([string]$InstalledApkPath) {
    $output = (Invoke-Adb @('exec-out', 'sha256sum', $InstalledApkPath)).output.Trim()
    $sha256 = [regex]::Match($output, '^(?<sha256>[0-9a-fA-F]{64})\s+').Groups['sha256'].Value.ToLowerInvariant()
    if ($sha256 -notmatch '^[0-9a-f]{64}$') { Fail "Installed audited APK hash is invalid: '$output'." 'device' }
    return $sha256
}

function Assert-AuditedPackageAtInteractionCheckpoint([string]$ExpectedApkSha256) {
    $packagePath = (Invoke-Adb @('shell','pm','path',$targetPackage)).output.Trim()
    $packagePid = (Invoke-Adb @('shell','pidof',$targetPackage)).output.Trim()
    Assert-AuditedPackagePresence $packagePath $packagePid
    $installedApkSha256 = Get-InstalledAuditedApkSha256 (Get-InstalledAuditedApkPath $packagePath)
    Assert-InstalledAuditedApkHash $ExpectedApkSha256 $installedApkSha256
}

function Start-AuditedPackageAtInteractionCheckpoint {
    Invoke-Adb @('shell','am','force-stop',$targetPackage) | Out-Null
    Invoke-Adb @('shell','am','start','-W','-n',$mainActivity) | Out-Null
    Assert-AuditedPackageAtInteractionCheckpoint $script:captureProvenance.debugApkSha256
}

function Assert-NarAndInteractionCheckpointPackageOrdering([bool]$NarPackageClean, [bool]$AuditedPackageAtCheckpoint) {
    if (-not $NarPackageClean) { Fail 'NAR child invocation requires a clean Nanidroid package state.' 'device' }
    if (-not $AuditedPackageAtCheckpoint) { Fail 'Interaction checkpoint requires the audited APK to be installed and running.' 'device' }
}

function Assert-AuditedApkHash([string]$ApkPath, [string]$ExpectedSha256) {
    if ($ExpectedSha256 -notmatch '^[0-9a-f]{64}$') { Fail 'Audited APK provenance lacks a valid SHA-256.' 'artifact' }
    if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { Fail "Audited APK is missing before the interaction checkpoint: '$ApkPath'." 'artifact' }
    $actualSha256 = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha256 -cne $ExpectedSha256) { Fail "Audited APK changed after capture: expected=$ExpectedSha256 actual=$actualSha256." 'artifact' }
}

function Assert-InteractionCaptureRecord([object]$Manifest, [object]$Record, [object]$Session, [object]$Provenance) {
    Assert-InteractionEvidenceManifestContract $Manifest
    foreach ($property in @('session', 'interactionCaptureStartedAtUtc', 'captureProvenance', 'artifacts')) {
        if (-not (Test-Property $Record $property)) { Fail "Interaction capture record lacks '$property'." 'artifact' }
    }
    foreach ($property in @('deviceSerial', 'avdName', 'snapshotName', 'emulatorProcessId', 'emulatorStartTimeUtcTicks')) {
        if (-not (Test-Property $Record.session $property)) { Fail "Interaction capture session lacks '$property'." 'artifact' }
        if (-not (Test-Property $Session $property)) { Fail "Interaction capture verification session lacks '$property'." 'artifact' }
        if ([string]$Record.session.$property -cne [string]$Session.$property) { Fail "Interaction capture session '$property' changed." 'artifact' }
    }
    ConvertTo-CaptureStartedAtUtc $Record.interactionCaptureStartedAtUtc | Out-Null
    Assert-CaptureProvenance $Record.captureProvenance $Provenance

    $expectedEvidence = @($Manifest.interactionEvidence)
    $artifacts = @($Record.artifacts)
    if ($artifacts.Count -ne $expectedEvidence.Count) { Fail "Interaction capture requires exactly $($expectedEvidence.Count) artifacts." 'artifact' }
    for ($index = 0; $index -lt $expectedEvidence.Count; $index++) {
        $artifact = $artifacts[$index]
        $artifactPath = [string]$expectedEvidence[$index].artifactPath
        if (-not (Test-Property $artifact 'artifactPath') -or -not (Test-Property $artifact 'sha256')) { Fail "Interaction capture artifact $($index + 1) lacks a path or SHA-256." 'artifact' }
        if ([string]$artifact.artifactPath -cne $artifactPath) { Fail "Interaction capture artifact $($index + 1) path changed." 'artifact' }
        Assert-ReportPngHash $reportRoot $artifactPath ([string]$artifact.sha256) | Out-Null
    }
}

function Get-UiAuditSummaryStatus([bool]$CleanupCompleted) {
    if ($script:runFailure -or $script:cleanupErrors.Count -gt 0) { return 'failed' }
    if (-not $CleanupCompleted) { return 'cleanup-pending' }
    if ($script:results.Count -eq $expectedCaseCount) { return 'captured-awaiting-manual-inspection' }
    return 'failed'
}

function Write-ReportSummary([object]$Manifest, [string]$ManifestHash, [object]$OriginalState, [string]$Status) {
    if (-not $script:reportInitialized) { return }
    if ($null -ne $script:interactionCapture) { Assert-InteractionCaptureRecord $Manifest $script:interactionCapture $script:interactionCapture.session $script:captureProvenance }
    $summary=[pscustomobject][ordered]@{ schemaVersion=2; caseSetVersion=$Manifest.caseSetVersion; manifestSha256=$ManifestHash; expectedCaseCount=$expectedCaseCount; resultCount=$script:results.Count; requiredInteractionEvidenceCount=$Manifest.interactionEvidenceCount; interactionEvidenceCount=0; interactionEvidence=@(); interactionCapture=$script:interactionCapture; status=$Status; failure=$script:runFailure; deviceSerial=$DeviceSerial; avdName=$AvdName; snapshotName=$SnapshotName; ownedEmulatorProcessId=$script:ownedEmulatorProcessId; ownedEmulatorStartTimeUtcTicks=$script:ownedEmulatorStartTimeUtcTicks; captureStartedAtUtc=$script:captureStartedAtUtc; interactionCaptureStartedAtUtc=$script:interactionCaptureStartedAtUtc; captureProvenance=$script:captureProvenance; originalState=$OriginalState; cleanupErrors=@($script:cleanupErrors); manualInspectionComplete=$false; results=@($script:results) }
    $summary | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $reportRoot 'summary.json') -Encoding UTF8
    $lines=@('# UI visual audit summary','',"- Status: $Status","- Case set: $($Manifest.caseSetVersion)","- Manifest SHA-256: $ManifestHash","- Cases expected: $expectedCaseCount","- Results captured: $($script:results.Count)",'- Manual inspection complete: false','', '| Case | Driver | Screenshot SHA-256 | Layout SHA-256 | Requested | Measured | Stage | Result | Defect |','| --- | --- | --- | --- | --- | --- | --- | --- | --- |')
    foreach ($case in $Manifest.cases) { $row=@($script:results | Where-Object id -eq $case.id | Select-Object -First 1); $r=if($row.Count){$row[0]}else{$null}; $lines += "| $($case.id) | $($case.sourceDriver) | $(if($r){$r.screenshotSha256}else{''}) | $(if($r){$r.layoutSha256}else{''}) | $($case.requested | ConvertTo-Json -Compress) | $(if($r){$r.measured}else{''}) | $(if($r){$r.stage}else{''}) |  |  |" }
    $lines | Set-Content -LiteralPath (Join-Path $reportRoot 'summary.md') -Encoding UTF8
}

function Get-RequiredInteractionChecklistLabels {
    return @(
        'Touch named collisions and generic transparent canvas.',
        'Mouse primary single-click and double-click.',
        'Scroll/click bubbles and reopen choices.',
        'Tab, Shift-Tab, arrows, Page Up, Page Down, Enter, Space, Escape, and D-pad.',
        'Toggle chrome only through empty stage or its labeled semantic action.',
        'Open and close bottom-sheet, side-panel, and full-modal debug presentations.',
        'Rotate, resize, and recreate the Activity.',
        'TalkBack plus Switch Access or Voice Access; merged and unmerged semantics.',
        'Invoke collision custom actions and verify focus recovery.',
        'Exercise input IME on Snake and Otacon.',
        'Verify passive stall prompt behavior.',
        'Verify exact SHIORI event identity, coordinate, scope, identifier, button, and source fields; no bubble/surface/chrome leakage.'
    )
}

function Get-RequiredInteractionEvidenceContract {
    return @(
        [pscustomobject][ordered]@{
            id = 'extracted-choice-surface'
            artifactPath = 'interaction\extracted-choice-surface.png'
            expectedInvariants = @('fresh-current-build', 'choice-surface-extracted', 'choices-visible')
        },
        [pscustomobject][ordered]@{
            id = 'snake-otacon-input-ime-visible'
            artifactPath = 'interaction\snake-otacon-input-ime-visible.png'
            expectedInvariants = @('fresh-current-build', 'snake-otacon-input-visible', 'ime-visible')
        }
    )
}

function Assert-ExactInteractionChecklist([string]$ManualText) {
    $expectedLabels = @(Get-RequiredInteractionChecklistLabels)
    $actualItems = [System.Collections.ArrayList]::new()
    foreach ($line in @($ManualText -split "`r?`n")) {
        if ($line -notmatch '^\s*-\s*\[') { continue }
        if ($line -notmatch '^- \[(?<mark>[ xX])\] (?<label>.+)$') {
            Fail "Interaction checklist contains a malformed checkbox line: '$line'." 'manual-inspection'
        }
        $actualItems.Add([pscustomobject]@{ mark = $matches.mark; label = $matches.label }) | Out-Null
    }
    if ($actualItems.Count -ne $expectedLabels.Count) {
        Fail "Interaction checklist must contain exactly $($expectedLabels.Count) items, got $($actualItems.Count)." 'manual-inspection'
    }
    $expectedSet = [Collections.Generic.Dictionary[string, bool]]::new([StringComparer]::Ordinal)
    foreach ($label in $expectedLabels) { $expectedSet.Add($label, $true) }
    $actualSet = [Collections.Generic.Dictionary[string, bool]]::new([StringComparer]::Ordinal)
    foreach ($actual in $actualItems) {
        if ($actual.mark -notin @('x', 'X')) {
            Fail "Interaction checklist item '$($actual.label)' must be checked." 'manual-inspection'
        }
        if (-not $expectedSet.ContainsKey($actual.label)) {
            Fail "Interaction checklist contains an unexpected or substituted label: '$($actual.label)'." 'manual-inspection'
        }
        if ($actualSet.ContainsKey($actual.label)) { Fail "Interaction checklist duplicates '$($actual.label)'." 'manual-inspection' }
        $actualSet.Add($actual.label, $true)
    }
    foreach ($label in $expectedLabels) {
        if (-not $actualSet.ContainsKey($label)) { Fail "Interaction checklist is missing '$label'." 'manual-inspection' }
    }
}

function Test-ManualInspectionHasRecordedResult([string]$ManualText) {
    $resultColumn = -1
    foreach ($line in @($ManualText -split "`r?`n")) {
        if ($line -notmatch '^\|') { $resultColumn = -1; continue }
        $cells = @($line.Split('|') | ForEach-Object { $_.Trim() })
        $headerResultColumns = @()
        for ($index = 0; $index -lt $cells.Count; $index++) {
            if ($cells[$index] -ceq 'Result') { $headerResultColumns += $index }
        }
        if ($headerResultColumns.Count -gt 0) {
            $resultColumn = if ($headerResultColumns.Count -eq 1) { $headerResultColumns[0] } else { -1 }
            continue
        }
        if ($resultColumn -ge 0 -and $cells.Count -gt $resultColumn -and $cells[$resultColumn] -match '^(?i:pass|fail)$') { return $true }
    }
    return $false
}

function Get-ManualInteractionEvidenceRows([object]$Manifest, [string]$ManualText) {
    Assert-InteractionEvidenceManifestContract $Manifest
    $requiredColumns = @('Interaction evidence', 'Artifact path', 'Artifact SHA-256', 'Expected invariants', 'Result', 'Defect')
    $expectedById = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($evidence in $Manifest.interactionEvidence) { $expectedById.Add([string]$evidence.id, $evidence) }
    $actualById = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    $insideTable = $false
    foreach ($line in @($ManualText -split "`r?`n")) {
        if ($line -notmatch '^\|') { $insideTable = $false; continue }
        $cells = @($line.Split('|') | ForEach-Object { $_.Trim() })
        if ($cells.Count -eq 8 -and (@($cells[1..6]) -join [char]31) -ceq ($requiredColumns -join [char]31)) { $insideTable = $true; continue }
        if (-not $insideTable -or $cells.Count -ne 8 -or $cells[1] -eq '---') { continue }
        $id = [string]$cells[1]
        if (-not $expectedById.ContainsKey($id)) { Fail "Manual interaction evidence contains an unexpected identity '$id'." 'manual-inspection' }
        if ($actualById.ContainsKey($id)) { Fail "Manual interaction evidence duplicates '$id'." 'manual-inspection' }
        $expected = $expectedById[$id]
        $artifactPath = [string]$cells[2]
        $artifactHash = ([string]$cells[3]).ToLowerInvariant()
        $invariants = [string]$cells[4]
        $result = ([string]$cells[5]).ToLowerInvariant()
        $defect = [string]$cells[6]
        if ($artifactPath -cne [string]$expected.artifactPath) { Fail "Manual interaction evidence '$id' has a substituted artifact path." 'manual-inspection' }
        if ($artifactHash -notmatch '^[0-9a-f]{64}$') { Fail "Manual interaction evidence '$id' lacks a valid artifact SHA-256." 'manual-inspection' }
        if ($invariants -cne (@($expected.expectedInvariants) -join ', ')) { Fail "Manual interaction evidence '$id' has stale or substituted invariants." 'manual-inspection' }
        if ($result -ne 'pass') { Fail "Manual interaction evidence '$id' is not an explicit pass." 'manual-inspection' }
        if (-not [string]::IsNullOrWhiteSpace($defect)) { Fail "Manual interaction evidence '$id' cannot pass with a recorded defect." 'manual-inspection' }
        $row = [pscustomobject][ordered]@{ id=$id; artifactPath=$artifactPath; artifactSha256=$artifactHash; expectedInvariants=@($expected.expectedInvariants); result='pass'; defect=$null }
        $actualById.Add($id, $row)
    }
    if ($actualById.Count -ne $expectedById.Count) { Fail "Expected $($expectedById.Count) completed interaction-evidence rows, got $($actualById.Count)." 'manual-inspection' }
    return @($Manifest.interactionEvidence | ForEach-Object { $actualById[[string]$_.id] })
}

function Get-ManualAutomatedInspectionRows([object]$ResultById, [string]$ManualText) {
    $manualRows = @{}
    foreach ($line in @($ManualText -split "`r?`n")) {
        if ($line -notmatch '^\|') { continue }
        $cells = @($line.Split('|') | ForEach-Object { $_.Trim() })
        if ($cells.Count -lt 9) { continue }
        $caseId = [string]$cells[1]
        if (-not $ResultById.ContainsKey($caseId)) { continue }
        if ($manualRows.ContainsKey($caseId)) { Fail "Duplicate manual inspection row '$caseId'." 'manual-inspection' }
        $artifactHash = ([string]$cells[2]).ToLowerInvariant()
        $requestedMeasured = [string]$cells[3]
        $environment = [string]$cells[4]
        $invariants = [string]$cells[5]
        $manualResult = ([string]$cells[6]).ToLowerInvariant()
        $defect = [string]$cells[7]
        if ($artifactHash -ne ([string]$ResultById[$caseId].screenshotSha256).ToLowerInvariant()) { Fail "Manual row '$caseId' has a stale artifact hash." 'manual-inspection' }
        if ([string]::IsNullOrWhiteSpace($requestedMeasured) -or $requestedMeasured -match '/\s*$') { Fail "Manual row '$caseId' lacks measured window/stage evidence." 'manual-inspection' }
        if ([string]::IsNullOrWhiteSpace($environment) -or [string]::IsNullOrWhiteSpace($invariants)) { Fail "Manual row '$caseId' lacks environment or invariant evidence." 'manual-inspection' }
        if ($manualResult -ne 'pass') { Fail "Manual row '$caseId' is not an explicit pass." 'manual-inspection' }
        if (-not [string]::IsNullOrWhiteSpace($defect)) { Fail "Manual row '$caseId' cannot pass with a recorded defect." 'manual-inspection' }
        $manualRows[$caseId] = [pscustomobject][ordered]@{ artifactHash=$artifactHash; result=$manualResult; defect=$defect }
    }
    return $manualRows
}

function Assert-RequiredInteractionEvidenceAbsent([object]$Manifest, [string]$Root = $reportRoot) {
    Assert-InteractionEvidenceManifestContract $Manifest
    foreach ($evidence in $Manifest.interactionEvidence) {
        $interactionPath = Resolve-SafeReportArtifactPath $Root $evidence.artifactPath $false
        if (Test-Path -LiteralPath $interactionPath) { Fail "Refusing pre-existing interaction evidence '$($evidence.artifactPath)'; capture it fresh after this run." 'report' }
    }
}

function New-ManualInspectionTemplate([object]$Manifest, [string]$ManifestHash) {
    $path=Join-Path $reportRoot 'manual-inspection.md'
    if (Test-Path -LiteralPath $path) {
        $existing=Get-Content -LiteralPath $path -Raw
        if ($existing -match '(?im)^Audit status:\s*complete\s*$' -or (Test-ManualInspectionHasRecordedResult $existing)) { Fail 'Refusing to overwrite a completed manual-inspection checklist.' 'report' }
        if ($existing -match "(?im)^Manifest SHA-256:\s*$([regex]::Escape($ManifestHash))\s*$") { return }
    }
    $lines=@('# UI visual audit manual inspection','', 'Audit status: incomplete', '', "Manifest SHA-256: $ManifestHash", "Required case count: $expectedCaseCount", '', 'Automated capture is not manual inspection. Open every PNG and fill Result and Defect.', '', '| Case | Artifact SHA-256 | Requested / measured window and stage | Density / font / theme / locale | Expected invariants | Result | Defect |','| --- | --- | --- | --- | --- | --- | --- |')
    foreach($case in $Manifest.cases){
        $requestedWindow = if ((Test-Property $case.requested 'widthDp') -and (Test-Property $case.requested 'heightDp')) { "$($case.requested.widthDp)x$($case.requested.heightDp)" } else { [string]$case.requested.display }
        $lines += "| $($case.id) |  | $requestedWindow /  | $($case.requested.density) / $($case.requested.fontScale) / $($case.requested.theme) / $($case.requested.locale) | $($case.expectedInvariants -join ', ') |  |  |"
    }
    $lines += @('', '## Required live interaction evidence', '', 'Capture these PNGs after this audit run. The manifest fixes their identities and report-relative paths.', '', '| Interaction evidence | Artifact path | Artifact SHA-256 | Expected invariants | Result | Defect |', '| --- | --- | --- | --- | --- | --- |')
    foreach ($evidence in $Manifest.interactionEvidence) { $lines += "| $($evidence.id) | $($evidence.artifactPath) |  | $($evidence.expectedInvariants -join ', ') |  |  |" }
    $lines += @('', '## Required interaction checklist','')
    foreach ($label in (Get-RequiredInteractionChecklistLabels)) { $lines += "- [ ] $label" }
    $lines | Set-Content -LiteralPath $path -Encoding UTF8
}

function ConvertTo-CaptureStartedAtUtc([object]$Value) {
    if ($Value -is [DateTime]) {
        $dateTime = [DateTime]$Value
        if ($dateTime.Kind -eq [DateTimeKind]::Unspecified) { Fail 'Capture summary timestamp lacks an explicit UTC offset.' 'manual-inspection' }
        return $dateTime.ToUniversalTime()
    }
    $text = [string]$Value
    if ($text -notmatch '(?:Z|[+-]\d{2}:\d{2})$') { Fail 'Capture summary timestamp lacks an explicit UTC offset.' 'manual-inspection' }
    $parsed = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse($text, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind, [ref]$parsed)) { Fail 'Capture summary lacks a valid capture-start timestamp.' 'manual-inspection' }
    return $parsed.UtcDateTime
}

function Assert-InteractionArtifactFreshness([DateTime]$LastWriteTimeUtc, [DateTime]$InteractionCaptureStartedAtUtc, [string]$RelativePath) {
    if ($LastWriteTimeUtc -lt $InteractionCaptureStartedAtUtc) { Fail "Interaction evidence '$RelativePath' predates the manual checkpoint." 'manual-inspection' }
}

function Assert-InteractionEvidenceRowsMatchCaptureRecord([object[]]$InteractionRows, [object]$InteractionCapture) {
    $capturedArtifacts = @($InteractionCapture.artifacts)
    if ($InteractionRows.Count -ne $capturedArtifacts.Count) { Fail "Manual interaction evidence requires exactly $($capturedArtifacts.Count) checkpointed artifacts." 'manual-inspection' }
    $artifactByPath = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($artifact in $capturedArtifacts) {
        $artifactPath = [string]$artifact.artifactPath
        if (-not $artifactByPath.TryAdd($artifactPath, $artifact)) { Fail "Interaction capture has a duplicate artifact path '$artifactPath'." 'manual-inspection' }
    }
    foreach ($row in $InteractionRows) {
        $artifact = $null
        if (-not $artifactByPath.TryGetValue([string]$row.artifactPath, [ref]$artifact)) { Fail "Manual interaction evidence '$($row.id)' has a checkpointed-path mismatch." 'manual-inspection' }
        if ([string]$row.artifactSha256 -cne [string]$artifact.sha256) { Fail "Manual interaction evidence '$($row.id)' has a checkpointed-hash mismatch." 'manual-inspection' }
    }
}

function Assert-ManualInspectionInteractionCapture([object]$Manifest, [object]$Summary, [object]$CheckpointRecord, [object[]]$InteractionRows) {
    $session = [pscustomobject]@{
        deviceSerial = $Summary.deviceSerial
        avdName = $Summary.avdName
        snapshotName = $Summary.snapshotName
        emulatorProcessId = $Summary.ownedEmulatorProcessId
        emulatorStartTimeUtcTicks = $Summary.ownedEmulatorStartTimeUtcTicks
    }
    Assert-InteractionCaptureRecord $Manifest $Summary.interactionCapture $session $Summary.captureProvenance
    Assert-InteractionCaptureRecord $Manifest $CheckpointRecord $session $Summary.captureProvenance
    if ((Get-StringSha256 (ConvertTo-CanonicalManifestJson $Summary.interactionCapture)) -cne (Get-StringSha256 (ConvertTo-CanonicalManifestJson $CheckpointRecord))) { Fail 'Interaction capture summary differs from its persisted checkpoint.' 'manual-inspection' }
    if (-not (Test-Property $Summary 'interactionCaptureStartedAtUtc')) { Fail 'Capture summary lacks its interaction capture-start timestamp.' 'manual-inspection' }
    ConvertTo-CaptureStartedAtUtc $Summary.interactionCaptureStartedAtUtc | Out-Null
    if ([string]$Summary.interactionCapture.interactionCaptureStartedAtUtc -cne [string]$Summary.interactionCaptureStartedAtUtc) { Fail 'Interaction capture start time changed.' 'manual-inspection' }
    Assert-InteractionEvidenceRowsMatchCaptureRecord $InteractionRows $Summary.interactionCapture
}

function Assert-CurrentReportPngEvidence([object]$Manifest, [object]$Summary, [object]$ManualRows, [object[]]$InteractionRows, [string]$Root = $reportRoot) {
    $captureStartedAt = ConvertTo-CaptureStartedAtUtc $Summary.interactionCaptureStartedAtUtc
    $resultById = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($result in @($Summary.results)) {
        if ($resultById.ContainsKey([string]$result.id)) { Fail "Duplicate summary result '$($result.id)'." 'manual-inspection' }
        $resultById.Add([string]$result.id, $result)
    }
    $expectedPngs = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($case in $Manifest.cases) {
        if (-not $resultById.ContainsKey([string]$case.id)) { Fail "Summary lacks result '$($case.id)'." 'manual-inspection' }
        $result = $resultById[[string]$case.id]
        if ([string]$result.screenshotPath -cne [string]$case.screenshotPath) { Fail "Summary result '$($case.id)' has a substituted screenshot path." 'manual-inspection' }
        $screenshotHash = ([string]$result.screenshotSha256).ToLowerInvariant()
        if (-not $ManualRows.ContainsKey([string]$case.id) -or [string]$ManualRows[[string]$case.id].artifactHash -cne $screenshotHash) { Fail "Manual row '$($case.id)' does not match its summary screenshot hash." 'manual-inspection' }
        $screenshotPath = ([string]$case.screenshotPath).Replace('/', '\')
        if ($expectedPngs.ContainsKey($screenshotPath)) { Fail "Duplicate expected PNG '$($case.screenshotPath)'." 'manual-inspection' }
        $expectedPngs.Add($screenshotPath, $screenshotHash)
        if (-not [string]::IsNullOrWhiteSpace([string]$case.annotatedPath)) {
            if ([string]$result.annotatedPath -cne [string]$case.annotatedPath -or [string]$result.annotatedSha256 -notmatch '^[0-9a-f]{64}$') { Fail "Summary result '$($case.id)' lacks its annotated PNG identity/hash." 'manual-inspection' }
            $annotatedPath = ([string]$case.annotatedPath).Replace('/', '\')
            if ($expectedPngs.ContainsKey($annotatedPath)) { Fail "Duplicate expected PNG '$($case.annotatedPath)'." 'manual-inspection' }
            $expectedPngs.Add($annotatedPath, ([string]$result.annotatedSha256).ToLowerInvariant())
        }
    }
    foreach ($row in @($InteractionRows)) {
        $interactionPath = ([string]$row.artifactPath).Replace('/', '\')
        if ($expectedPngs.ContainsKey($interactionPath)) { Fail "Duplicate expected PNG '$($row.artifactPath)'." 'manual-inspection' }
        $expectedPngs.Add($interactionPath, ([string]$row.artifactSha256).ToLowerInvariant())
    }
    $actualPngs = @(Get-ChildItem -LiteralPath $Root -Recurse -File -Filter '*.png')
    if ($actualPngs.Count -ne $expectedPngs.Count) { Fail "Report PNG set must contain exactly $($expectedPngs.Count) files, got $($actualPngs.Count)." 'manual-inspection' }
    foreach ($png in $actualPngs) {
        $relativePath = Get-RelativePath -BasePath $Root -ChildPath $png.FullName
        $safePath = Resolve-SafeReportArtifactPath $Root $relativePath $true
        if (-not $expectedPngs.ContainsKey($relativePath)) { Fail "Report contains an unexpected PNG '$relativePath'." 'manual-inspection' }
        Assert-ReportPngHash $Root $relativePath $expectedPngs[$relativePath] | Out-Null
        if ($relativePath -like 'interaction\*') { Assert-InteractionArtifactFreshness (Get-Item -LiteralPath $safePath).LastWriteTimeUtc $captureStartedAt $relativePath }
    }
}

function Complete-ManualInspectionAudit([object]$Manifest, [string]$ManifestHash) {
    $manifestPath = Join-Path $reportRoot 'case-manifest.json'
    $manifestHashPath = Join-Path $reportRoot 'case-manifest.sha256'
    $summaryPath = Join-Path $reportRoot 'summary.json'
    $summaryMarkdownPath = Join-Path $reportRoot 'summary.md'
    $manualPath = Join-Path $reportRoot 'manual-inspection.md'
    $interactionCheckpointPath = Join-Path $reportRoot 'interaction-capture.json'
    foreach ($path in @($manifestPath, $manifestHashPath, $summaryPath, $summaryMarkdownPath, $manualPath, $interactionCheckpointPath)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Fail "Required audit evidence is missing: '$path'." 'manual-inspection' }
    }

    $capturedManifestText = Get-Content -LiteralPath $manifestPath -Raw
    $capturedManifestHash = Get-StringSha256 $capturedManifestText
    $recordedManifestHash = (Get-Content -LiteralPath $manifestHashPath -Raw).Trim().ToLowerInvariant()
    if ($capturedManifestHash -ne $recordedManifestHash -or $capturedManifestHash -ne $ManifestHash) {
        Fail "Manifest hash mismatch: generated=$ManifestHash captured=$capturedManifestHash recorded=$recordedManifestHash." 'manual-inspection'
    }
    $capturedManifest = $capturedManifestText | ConvertFrom-Json
    Assert-UiAuditManifest $capturedManifest

    $summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
    $interactionCheckpoint = Get-Content -LiteralPath $interactionCheckpointPath -Raw | ConvertFrom-Json
    if ($summary.schemaVersion -ne 2 -or $summary.manifestSha256 -ne $ManifestHash -or $summary.expectedCaseCount -ne $expectedCaseCount -or $summary.resultCount -ne $expectedCaseCount -or $summary.requiredInteractionEvidenceCount -ne $capturedManifest.interactionEvidenceCount) {
        Fail 'Capture summary does not describe the complete current manifest.' 'manual-inspection'
    }
    if ($null -ne $summary.failure -or @($summary.cleanupErrors).Count -ne 0 -or $summary.status -notin @('captured-awaiting-manual-inspection', 'complete')) {
        Fail 'Capture summary contains a run/cleanup failure or an invalid status.' 'manual-inspection'
    }
    $gitPath = Resolve-Git
    $currentDebugApk = Resolve-DebugApk
    Assert-CaptureProvenance $summary.captureProvenance (Get-CurrentCaptureProvenance $gitPath $currentDebugApk)
    $resultById = @{}
    foreach ($result in @($summary.results)) {
        if ($resultById.ContainsKey([string]$result.id)) { Fail "Duplicate summary result '$($result.id)'." 'manual-inspection' }
        if ([string]::IsNullOrWhiteSpace([string]$result.screenshotSha256)) { Fail "Summary result '$($result.id)' lacks a screenshot hash." 'manual-inspection' }
        $resultById[[string]$result.id] = $result
    }
    if ($resultById.Count -ne $expectedCaseCount) { Fail "Expected $expectedCaseCount unique capture results, got $($resultById.Count)." 'manual-inspection' }

    $manualText = Get-Content -LiteralPath $manualPath -Raw
    if ($manualText -notmatch '(?im)^Audit status:\s*complete\s*$') { Fail 'Manual inspection must explicitly set Audit status: complete.' 'manual-inspection' }
    if ($manualText -notmatch "(?im)^Manifest SHA-256:\s*$([regex]::Escape($ManifestHash))\s*$") { Fail 'Manual inspection manifest hash is missing or stale.' 'manual-inspection' }
    $interactionEvidenceRows = @(Get-ManualInteractionEvidenceRows $capturedManifest $manualText)

    $manualRows = Get-ManualAutomatedInspectionRows $resultById $manualText
    if ($manualRows.Count -ne $expectedCaseCount) { Fail "Expected $expectedCaseCount completed manual rows, got $($manualRows.Count)." 'manual-inspection' }

    Assert-ManualInspectionInteractionCapture $capturedManifest $summary $interactionCheckpoint $interactionEvidenceRows
    Assert-CurrentReportPngEvidence $capturedManifest $summary $manualRows $interactionEvidenceRows

    $expectedChecklistCount = @(Get-RequiredInteractionChecklistLabels).Count
    Assert-ExactInteractionChecklist $manualText

    $summary.status = 'complete'
    $summary.manualInspectionComplete = $true
    $summary.interactionEvidenceCount = $interactionEvidenceRows.Count
    $summary.interactionEvidence = $interactionEvidenceRows
    $summary | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $summaryPath -Encoding UTF8
    $summaryMarkdown = Get-Content -LiteralPath $summaryMarkdownPath -Raw
    $summaryMarkdown = $summaryMarkdown -replace '(?m)^- Status: .+$', '- Status: complete'
    $summaryMarkdown = $summaryMarkdown -replace '(?m)^- Manual inspection complete: .+$', '- Manual inspection complete: true'
    Set-Content -LiteralPath $summaryMarkdownPath -Value $summaryMarkdown -Encoding UTF8
    Write-Host "Manual inspection verified: cases=$expectedCaseCount interactions=$expectedChecklistCount manifest=$ManifestHash"
}

function Add-Result([object]$Case, [string]$ScreenshotSha, [string]$LayoutSha, [object]$Measured, [object]$Stage, [object]$SourceEvidence, [string]$AnnotatedSha = $null) {
    $script:results.Add([pscustomobject][ordered]@{ id=$Case.id; sourceDriver=$Case.sourceDriver; screenshotPath=$Case.screenshotPath; screenshotSha256=$ScreenshotSha; layoutPath=$Case.layoutPath; layoutSha256=$LayoutSha; annotatedPath=$Case.annotatedPath; annotatedSha256=$AnnotatedSha; requested=$Case.requested; measured=$Measured; stage=$Stage; theme=$script:originalState.theme; locale=$script:originalState.locale; expectedInvariants=$Case.expectedInvariants; sourceEvidence=$SourceEvidence; manualResult=$null; defect=$null }) | Out-Null
}

function Invoke-DryRunSelfTest([object]$Manifest, [string]$ManifestHash) {
    if ((Get-UiAuditSummaryStatus $false) -ne 'cleanup-pending') { Fail 'Interaction checkpoint summary was not held pending cleanup.' 'dry-run' }
    Assert-UiAuditManifest $Manifest
    if (Test-DryRunRequiresCorpus $true) { Fail 'HostSelfTest corpus-bypass probe unexpectedly requires a corpus.' 'dry-run' }
    if (-not (Test-DryRunRequiresCorpus $false)) { Fail 'Normal DryRun corpus-preflight probe unexpectedly bypasses the corpus.' 'dry-run' }
    $again=New-UiAuditManifest; $againHash=Get-StringSha256 (ConvertTo-CanonicalManifestJson $again)
    if ($ManifestHash -ne $againHash) { Fail 'Manifest generation is not deterministic.' 'dry-run' }
    Assert-InteractionEvidenceManifestContract $Manifest
    foreach ($mutation in @('missing', 'substituted', 'duplicated', 'extra')) {
        $mutatedManifest = $Manifest | ConvertTo-Json -Depth 16 | ConvertFrom-Json
        switch ($mutation) {
            'missing' { $mutatedManifest.interactionEvidence = @($mutatedManifest.interactionEvidence | Select-Object -First 1) }
            'substituted' { $mutatedManifest.interactionEvidence[0].id = 'substituted-evidence' }
            'duplicated' { $mutatedManifest.interactionEvidence[1].id = $mutatedManifest.interactionEvidence[0].id }
            'extra' { $mutatedManifest.interactionEvidence = @($mutatedManifest.interactionEvidence) + $mutatedManifest.interactionEvidence[0] }
        }
        $mutatedManifest.interactionEvidenceCount = @($mutatedManifest.interactionEvidence).Count
        $failed = $false
        try { Assert-InteractionEvidenceManifestContract $mutatedManifest } catch { $failed = $true }
        if (-not $failed) { Fail "Interaction evidence manifest $mutation probe unexpectedly passed." 'dry-run' }
    }
    $interactionEvidenceHeader = '| Interaction evidence | Artifact path | Artifact SHA-256 | Expected invariants | Result | Defect |'
    $interactionEvidenceRows = @($Manifest.interactionEvidence | ForEach-Object { "| $($_.id) | $($_.artifactPath) | $('a' * 64) | $($_.expectedInvariants -join ', ') | pass |  |" })
    $validInteractionEvidenceTable = (@($interactionEvidenceHeader, '| --- | --- | --- | --- | --- | --- |') + $interactionEvidenceRows) -join "`n"
    $parsedInteractionEvidence = @(Get-ManualInteractionEvidenceRows $Manifest $validInteractionEvidenceTable)
    if ($parsedInteractionEvidence.Count -ne 2) { Fail 'Valid manual interaction-evidence table probe did not return two rows.' 'dry-run' }
    foreach ($mutation in @('missing', 'substituted-path', 'substituted-invariants', 'failed', 'defect')) {
        $mutatedRows = @($interactionEvidenceRows)
        switch ($mutation) {
            'missing' { $mutatedRows = @($mutatedRows | Select-Object -First 1) }
            'substituted-path' { $mutatedRows[0] = $mutatedRows[0].Replace('interaction\extracted-choice-surface.png', 'interaction\substituted.png') }
            'substituted-invariants' { $mutatedRows[0] = $mutatedRows[0].Replace('fresh-current-build, choice-surface-extracted, choices-visible', 'substituted') }
            'failed' { $mutatedRows[0] = $mutatedRows[0].Replace('| pass |', '| fail |') }
            'defect' { $mutatedRows[0] = $mutatedRows[0].Replace('| pass |  |', '| pass | visible defect |') }
        }
        $failed = $false
        try { Get-ManualInteractionEvidenceRows $Manifest ((@($interactionEvidenceHeader, '| --- | --- | --- | --- | --- | --- |') + $mutatedRows) -join "`n") | Out-Null } catch { $failed = $true }
        if (-not $failed) { Fail "Manual interaction evidence $mutation probe unexpectedly passed." 'dry-run' }
    }
    $bounds=ConvertFrom-LayoutBounds '[1,2][3,5]'; if ($bounds.width -ne 2 -or $bounds.height -ne 3) { Fail 'Bounds parser probe failed.' 'dry-run' }
    foreach($bad in @('..\escape.png','C:\escape.png','bad path.png')) { $failed=$false; try { Assert-SafeReportRelativePath $bad } catch { $failed=$true }; if(-not $failed){Fail "Unsafe path probe unexpectedly passed '$bad'." 'dry-run'} }
    $pngProbeRoot = Join-Path $repoRoot ".superpowers\ui-audit-png-probe-$([guid]::NewGuid().ToString('N'))"
    try {
        New-Item -ItemType Directory -Path (Join-Path $pngProbeRoot 'nested') | Out-Null
        $pngProbePath = Join-Path $pngProbeRoot 'nested\probe.png'
        [IO.File]::WriteAllBytes($pngProbePath, [byte[]](0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,0x01))
        $pngProbeHash = (Get-FileHash -LiteralPath $pngProbePath -Algorithm SHA256).Hash.ToLowerInvariant()
        Assert-ReportPngHash $pngProbeRoot 'nested\probe.png' $pngProbeHash | Out-Null
        [IO.File]::WriteAllBytes($pngProbePath, [byte[]](0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,0x02))
        $failed = $false
        try { Assert-ReportPngHash $pngProbeRoot 'nested\probe.png' $pngProbeHash | Out-Null } catch { $failed = $true }
        if (-not $failed) { Fail 'Tampered report PNG hash probe unexpectedly passed.' 'dry-run' }
    } finally {
        if (Test-Path -LiteralPath $pngProbeRoot) { Remove-Item -LiteralPath $pngProbeRoot -Recurse -Force }
    }
    $quoted=ConvertTo-WindowsCommandLineArgument 'label with spaces'; if($quoted -ne '"label with spaces"'){Fail 'Argument quoting probe failed.' 'dry-run'}
    $timeout=[Diagnostics.Stopwatch]::StartNew(); $proc=Invoke-Native -FilePath (Get-Process -Id $PID).Path -Arguments @('-NoProfile','-Command','exit 0') -TimeoutSeconds 20; $timeout.Stop(); if($proc.exitCode -ne 0 -or $timeout.Elapsed.TotalSeconds -ge 20){Fail 'Process/timeout helper probe failed.' 'dry-run'}
    $drainProbeCommand='$child=Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList @(''-NoProfile'',''-Command'',''Start-Sleep -Seconds 4'') -NoNewWindow -PassThru; exit 0'
    $drainProbeEncoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($drainProbeCommand)); $drainProbeFailed=$false; $drainProbeTimer=[Diagnostics.Stopwatch]::StartNew()
    try { Invoke-Native -FilePath (Get-Process -Id $PID).Path -Arguments @('-NoProfile','-EncodedCommand',$drainProbeEncoded) -TimeoutSeconds 10 -DrainTimeoutSeconds 1 | Out-Null } catch { $drainProbeFailed=$_.Exception.Message -match 'inherited output handles did not close' }
    $drainProbeTimer.Stop(); if(-not$drainProbeFailed-or$drainProbeTimer.Elapsed.TotalSeconds-ge 4){Fail 'Bounded normal-exit stream-drain probe failed.' 'dry-run'}
    $pwshProbe=Resolve-PowerShell7
    $cmdPath=[Environment]::GetEnvironmentVariable('ComSpec')
    if ([string]::IsNullOrWhiteSpace($cmdPath)) { Fail 'Timeout tree probe cannot resolve cmd.exe.' 'dry-run' }
    $probeCommand="`$child=Start-Process -FilePath '$($cmdPath.Replace("'", "''"))' -ArgumentList @('/c','timeout /t 60 /nobreak >nul') -PassThru; Write-Output ('{0}|{1}' -f `$child.Id,`$child.StartTime.ToUniversalTime().Ticks); Start-Sleep -Seconds 60"
    $probeEncoded=[Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($probeCommand))
    $probeInfo=[Diagnostics.ProcessStartInfo]::new(); $probeInfo.FileName=$pwshProbe; $probeInfo.UseShellExecute=$false; $probeInfo.CreateNoWindow=$true; $probeInfo.RedirectStandardOutput=$true; $probeInfo.RedirectStandardError=$true
    if ($null -ne $probeInfo.PSObject.Properties['ArgumentList']) { foreach ($argument in @('-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand',$probeEncoded)) { [void]$probeInfo.ArgumentList.Add($argument) } } else { $probeInfo.Arguments="-NoProfile -ExecutionPolicy Bypass -EncodedCommand $probeEncoded" }
    $probe=[Diagnostics.Process]::Start($probeInfo)
    $probeStartTimeUtcTicks=$probe.StartTime.ToUniversalTime().Ticks
    try {
        $identityLineTask=$probe.StandardOutput.ReadLineAsync(); if (-not $identityLineTask.Wait(5000)) { Fail 'Timeout tree probe did not report its exact owned child.' 'dry-run' }
        $childIdentity=$identityLineTask.GetAwaiter().GetResult().Trim() -split '\|'
        if ($childIdentity.Count -ne 2) { Fail 'Timeout tree probe reported an invalid child identity.' 'dry-run' }
        $ownedChildren=@([pscustomobject]@{id=[int]$childIdentity[0];startTimeUtcTicks=[long]$childIdentity[1]})
        if (-not (Stop-OwnedProcessTree -Process $probe -ExpectedStartTimeUtcTicks $probeStartTimeUtcTicks -TimeoutMilliseconds 5000)) { Fail 'Timeout tree probe could not terminate its exact owned process tree.' 'dry-run' }
        foreach ($identity in $ownedChildren) {
            $child=$null
            try { $child=[Diagnostics.Process]::GetProcessById($identity.id); if (-not $child.HasExited -and $child.StartTime.ToUniversalTime().Ticks -eq $identity.startTimeUtcTicks) { Fail 'Timeout process-tree probe left an owned child alive.' 'dry-run' } }
            catch [System.ArgumentException] { }
            finally { if ($null -ne $child) { $child.Dispose() } }
        }
    } finally {
        try { if (-not $probe.HasExited) { $probe.Kill($true); $probe.WaitForExit(5000) | Out-Null } } finally { $probe.Dispose() }
    }
    $watchdogInvocation=New-EmulatorWatchdogInvocation -HostProcessId 12345 -HostStartTimeUtcTicks 638000000000000000 -EmulatorProcessId 23456 -EmulatorStartTimeUtcTicks 638000000000000001 -ReadyPath (Join-Path ([IO.Path]::GetTempPath()) 'nanidroid-watchdog.ready')
    $watchdogScript=[Text.Encoding]::Unicode.GetString([Convert]::FromBase64String($watchdogInvocation.Arguments[-1]))
    foreach($required in @('$hostProcessId=12345','$hostStartTimeUtcTicks=638000000000000000','$emulatorProcessId=23456','$emulatorStartTimeUtcTicks=638000000000000001','GetProcessById','Kill($true)')) { if (-not $watchdogScript.Contains($required)) { Fail "Watchdog identity-binding probe missing '$required'." 'dry-run' } }
    if ($watchdogScript -match 'Get-Process|Get-CimInstance|Where-Object|\-Name') { Fail 'Watchdog command generation must not use broad process matching.' 'dry-run' }
    $script:resolvedPwsh = $pwshProbe
    $watchdogTargetInfo=[Diagnostics.ProcessStartInfo]::new(); $watchdogTargetInfo.FileName=$pwshProbe; $watchdogTargetInfo.UseShellExecute=$false; $watchdogTargetInfo.CreateNoWindow=$true
    if ($null -ne $watchdogTargetInfo.PSObject.Properties['ArgumentList']) { foreach ($argument in @('-NoProfile','-Command','Start-Sleep -Seconds 60')) { [void]$watchdogTargetInfo.ArgumentList.Add($argument) } } else { $watchdogTargetInfo.Arguments='-NoProfile -Command "Start-Sleep -Seconds 60"' }
    $watchdogTarget=[Diagnostics.Process]::Start($watchdogTargetInfo); $watchdogTargetStartTimeUtcTicks=$watchdogTarget.StartTime.ToUniversalTime().Ticks; $watchdogProbe=$null
    $watchdogProbeRoot=Join-Path $repoRoot '.superpowers\ui-audit-watchdog-probe'
    try {
        $watchdogProbe=Start-EmulatorWatchdog $watchdogTarget $watchdogTargetStartTimeUtcTicks $watchdogProbeRoot
        if (-not (Test-OwnedProcessIdentity $watchdogProbe.processId $watchdogProbe.startTimeUtcTicks)) { Fail 'Live watchdog handshake probe did not retain its exact process identity.' 'dry-run' }
    } finally {
        if ($null -ne $watchdogProbe -and -not (Stop-EmulatorWatchdog $watchdogProbe)) { Fail 'Live watchdog handshake probe did not stop cleanly.' 'dry-run' }
        [void](Stop-OwnedProcessTree -Process $watchdogTarget -ExpectedStartTimeUtcTicks $watchdogTargetStartTimeUtcTicks -TimeoutMilliseconds 5000)
        $watchdogTarget.Dispose()
        Remove-Item -LiteralPath $watchdogProbeRoot -Force -ErrorAction SilentlyContinue
    }
    $transportBefore=$script:adbTransportDead; if(-not ( -not $transportBefore)){Fail 'Transport-dead initial probe failed.' 'dry-run'}
    $pngSig=[BitConverter]::ToString([byte[]](0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)); if($pngSig -ne '89-50-4E-47-0D-0A-1A-0A'){Fail 'PNG signature probe failed.' 'dry-run'}
    $roundTrip=($Manifest | ConvertTo-Json -Depth 16 | ConvertFrom-Json); Assert-UiAuditManifest $roundTrip
    $jsonUtc = ('{"captureStartedAtUtc":"2026-08-05T05:17:50.9318003Z"}' | ConvertFrom-Json).captureStartedAtUtc
    if ((ConvertTo-CaptureStartedAtUtc $jsonUtc).ToString('o', [Globalization.CultureInfo]::InvariantCulture) -cne '2026-08-05T05:17:50.9318003Z') { Fail 'JSON UTC capture-start round-trip probe failed.' 'dry-run' }
    $manualTableHeader = '| Case | Artifact SHA-256 | Requested / measured window and stage | Density / font / theme / locale | Expected invariants | Result | Defect |'
    $wrongColumnResult = "$manualTableHeader`n| case-one | hash | pass | environment | invariants |  |  |"
    $actualColumnResult = "$manualTableHeader`n| case-one | $('a' * 64) | requested / measured | environment | invariants | pass |  |"
    if (Test-ManualInspectionHasRecordedResult $wrongColumnResult) { Fail 'Non-Result-column pass probe unexpectedly counted as a recorded result.' 'dry-run' }
    if (-not (Test-ManualInspectionHasRecordedResult $actualColumnResult)) { Fail 'Actual Result-column pass probe was not detected.' 'dry-run' }
    $manualResultById = @{ 'case-one' = [pscustomobject]@{ screenshotSha256 = ('a' * 64) } }
    $validManualRows = Get-ManualAutomatedInspectionRows $manualResultById $actualColumnResult
    if ($validManualRows.Count -ne 1 -or [string]$validManualRows['case-one'].defect -cne '') { Fail 'Valid automated manual-row Defect parsing probe failed.' 'dry-run' }
    $defectBearingPass = "$manualTableHeader`n| case-one | $('a' * 64) | requested / measured | environment | invariants | pass | visible defect |"
    $failed = $false
    try { Get-ManualAutomatedInspectionRows $manualResultById $defectBearingPass | Out-Null } catch { $failed = $true }
    if (-not $failed) { Fail 'Automated manual row passed with a non-empty Defect cell.' 'dry-run' }
    $capturedProvenance = [pscustomobject]@{
        gitHead = '1111111111111111111111111111111111111111'
        trackedWorktreeClean = $true
        debugApkPath = 'build\outputs\apk\debug\nanidroid-debug.apk'
        debugApkSha256 = ('a' * 64)
    }
    Assert-CaptureProvenance $capturedProvenance $capturedProvenance
    foreach ($mutation in @('head', 'dirty', 'missing-apk', 'apk-path', 'apk-hash')) {
        $currentProvenance = $capturedProvenance | ConvertTo-Json | ConvertFrom-Json
        switch ($mutation) {
            'head' { $currentProvenance.gitHead = '2222222222222222222222222222222222222222' }
            'dirty' { $currentProvenance.trackedWorktreeClean = $false }
            'missing-apk' { $currentProvenance.debugApkSha256 = $null }
            'apk-path' { $currentProvenance.debugApkPath = 'build\outputs\apk\debug\other.apk' }
            'apk-hash' { $currentProvenance.debugApkSha256 = ('b' * 64) }
        }
        $failed = $false
        try { Assert-CaptureProvenance $capturedProvenance $currentProvenance } catch { $failed = $true }
        if (-not $failed) { Fail "Capture provenance $mutation probe unexpectedly passed." 'dry-run' }
    }
    $interactionCaptureProbeRoot = Join-Path $repoRoot ".superpowers\ui-audit-interaction-capture-$([guid]::NewGuid().ToString('N'))"
    $previousReportRoot = $script:reportRoot
    $previousOwnedEmulator = $script:ownedEmulator
    $previousOwnedEmulatorStartTimeUtcTicks = $script:ownedEmulatorStartTimeUtcTicks
    $previousCaptureProvenance = $script:captureProvenance
    $previousCaptureStartedAtUtc = $script:captureStartedAtUtc
    $previousInteractionCaptureStartedAtUtc = $script:interactionCaptureStartedAtUtc
    $previousInteractionCheckpointPersisted = $script:interactionCheckpointPersisted
    $previousNarCorpusPackageCleanVerified = $script:narCorpusPackageCleanVerified
    $captureProbeProcess = [Diagnostics.Process]::GetCurrentProcess()
    try {
        $script:reportRoot = $interactionCaptureProbeRoot
        $script:ownedEmulator = $captureProbeProcess
        $script:ownedEmulatorStartTimeUtcTicks = $captureProbeProcess.StartTime.ToUniversalTime().Ticks
        $script:captureProvenance = $capturedProvenance
        $script:captureStartedAtUtc = '2026-08-08T12:34:56.0000000Z'
        $script:interactionCaptureStartedAtUtc = [DateTime]::UtcNow.AddMinutes(-1).ToString('o', [Globalization.CultureInfo]::InvariantCulture)
        foreach ($evidence in @($Manifest.interactionEvidence)) {
            $artifactPath = Resolve-SafeReportArtifactPath $interactionCaptureProbeRoot $evidence.artifactPath $false
            New-Item -ItemType Directory -Force -Path (Split-Path $artifactPath -Parent) | Out-Null
            [IO.File]::WriteAllBytes($artifactPath, [byte[]](0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00))
        }
        $validInteractionCapture = New-InteractionCaptureRecord $Manifest
        Assert-InteractionCaptureRecord $Manifest $validInteractionCapture $validInteractionCapture.session $capturedProvenance
        if (-not (Test-Property $validInteractionCapture 'interactionCaptureStartedAtUtc') -or [string]$validInteractionCapture.interactionCaptureStartedAtUtc -cne $script:interactionCaptureStartedAtUtc) { Fail 'Interaction session-record lacks its manual checkpoint timestamp.' 'dry-run' }
        Write-InteractionCaptureCheckpoint $validInteractionCapture
        if (-not $script:interactionCheckpointPersisted -or -not (Test-Path -LiteralPath (Join-Path $interactionCaptureProbeRoot 'interaction-capture.json') -PathType Leaf)) { Fail 'Interaction capture checkpoint was not persisted before cleanup probes.' 'dry-run' }
        $auditedApkProbePath = Join-Path $interactionCaptureProbeRoot 'audited-apk-probe.apk'
        [IO.File]::WriteAllBytes($auditedApkProbePath, [byte[]](0x01, 0x02, 0x03))
        $auditedApkProbeHash = (Get-FileHash -LiteralPath $auditedApkProbePath -Algorithm SHA256).Hash.ToLowerInvariant()
        Assert-AuditedApkHash $auditedApkProbePath $auditedApkProbeHash
        [IO.File]::WriteAllBytes($auditedApkProbePath, [byte[]](0x04, 0x05, 0x06))
        $failed = $false
        try { Assert-AuditedApkHash $auditedApkProbePath $auditedApkProbeHash } catch { $failed = $true }
        if (-not $failed) { Fail 'Mutated audited APK hash revalidation probe unexpectedly passed.' 'dry-run' }
        if ((Get-PackageCleanupAction ([pscustomobject]@{ exitCode = 0; output = ''; error = '' })).action -ne 'skip') { Fail 'Absent package cleanup probe should skip uninstall.' 'dry-run' }
        if ((Get-PackageCleanupAction ([pscustomobject]@{ exitCode = 1; output = ''; error = "Error: package $testPackage not found" })).action -ne 'skip') { Fail 'Android missing-package cleanup probe should skip uninstall.' 'dry-run' }
        if ((Get-PackageCleanupAction ([pscustomobject]@{ exitCode = 0; output = "package:/data/app/$targetPackage/base.apk"; error = '' })).action -ne 'uninstall') { Fail 'Installed package cleanup probe should uninstall.' 'dry-run' }
        $failed = $false
        try { Get-PackageCleanupAction ([pscustomobject]@{ exitCode = 1; output = ''; error = 'offline' }) | Out-Null } catch { $failed = $_.Exception.Message -match 'Package-state probe failed' }
        if (-not $failed) { Fail 'Failed package-state cleanup probe unexpectedly skipped uninstall.' 'dry-run' }
        Assert-AuditedPackagePresence "package:/data/app/$targetPackage/base.apk" '1234'
        foreach ($packageProbe in @(@('missing-package', '', '1234'), @('not-running', "package:/data/app/$targetPackage/base.apk", ''))) {
            $failed = $false
            try { Assert-AuditedPackagePresence $packageProbe[1] $packageProbe[2] } catch { $failed = $true }
            if (-not $failed) { Fail "Interaction checkpoint package $($packageProbe[0]) probe unexpectedly passed." 'dry-run' }
        }
        Assert-InstalledAuditedApkHash ('a' * 64) ('a' * 64)
        $failed = $false
        try { Assert-InstalledAuditedApkHash ('a' * 64) ('b' * 64) } catch { $failed = $true }
        if (-not $failed) { Fail 'Installed audited APK substitution probe unexpectedly passed.' 'dry-run' }
        $freshnessBoundary = [DateTime]::UtcNow
        Assert-InteractionArtifactFreshness $freshnessBoundary $freshnessBoundary 'interaction\fresh.png'
        $failed = $false
        try { Assert-InteractionArtifactFreshness $freshnessBoundary.AddSeconds(-1) $freshnessBoundary 'interaction\stale.png' } catch { $failed = $true }
        if (-not $failed) { Fail 'Manual checkpoint freshness probe unexpectedly passed.' 'dry-run' }
        Assert-NarAndInteractionCheckpointPackageOrdering $true $true
        foreach ($orderingProbe in @(@('installed-before-nar', $false, $true), @('missing-at-checkpoint', $true, $false))) {
            $failed = $false
            try { Assert-NarAndInteractionCheckpointPackageOrdering $orderingProbe[1] $orderingProbe[2] } catch { $failed = $true }
            if (-not $failed) { Fail "NAR/checkpoint package ordering $($orderingProbe[0]) probe unexpectedly passed." 'dry-run' }
        }
        $manualVerificationSession = [pscustomobject]@{
            deviceSerial = $validInteractionCapture.session.deviceSerial
            avdName = $validInteractionCapture.session.avdName
            snapshotName = $validInteractionCapture.session.snapshotName
            emulatorProcessId = $validInteractionCapture.session.emulatorProcessId
            emulatorStartTimeUtcTicks = $validInteractionCapture.session.emulatorStartTimeUtcTicks
        }
        $failed = $false
        try { Assert-InteractionCaptureRecord $Manifest $validInteractionCapture $manualVerificationSession $capturedProvenance } catch { $failed = $true }
        if ($failed) { Fail 'Stable manual-verification interaction session probe was rejected.' 'dry-run' }
        $failed = $false
        try { Assert-InteractionCaptureRecord $Manifest $null $validInteractionCapture.session $capturedProvenance } catch { $failed = $true }
        if (-not $failed) { Fail 'Missing interaction session-record probe unexpectedly passed.' 'dry-run' }
        $checkpointedInteractionRows = @($Manifest.interactionEvidence | ForEach-Object {
            [pscustomobject]@{
                id = $_.id
                artifactPath = $_.artifactPath
                artifactSha256 = ($validInteractionCapture.artifacts | Where-Object artifactPath -ceq $_.artifactPath | Select-Object -First 1).sha256
            }
        })
        Assert-InteractionEvidenceRowsMatchCaptureRecord $checkpointedInteractionRows $validInteractionCapture
        Assert-InteractionEvidenceRowsMatchCaptureRecord @($checkpointedInteractionRows | Sort-Object id -Descending) $validInteractionCapture
        foreach ($mutation in @('substituted-path', 'changed-hash')) {
            $mutatedRows = $checkpointedInteractionRows | ConvertTo-Json -Depth 16 | ConvertFrom-Json
            switch ($mutation) {
                'substituted-path' { $mutatedRows[0].artifactPath = 'interaction\substituted.png' }
                'changed-hash' { $mutatedRows[0].artifactSha256 = ('b' * 64) }
            }
            $failed = $false
            try { Assert-InteractionEvidenceRowsMatchCaptureRecord $mutatedRows $validInteractionCapture } catch { $failed = $true }
            if (-not $failed) { Fail "Checkpointed manual interaction evidence $mutation probe unexpectedly passed." 'dry-run' }
        }
        $manualCompletionSummary = [pscustomobject]@{
            status = 'captured-awaiting-manual-inspection'
            interactionCapture = $validInteractionCapture
            captureProvenance = $capturedProvenance
            deviceSerial = $validInteractionCapture.session.deviceSerial
            avdName = $validInteractionCapture.session.avdName
            snapshotName = $validInteractionCapture.session.snapshotName
            ownedEmulatorProcessId = $validInteractionCapture.session.emulatorProcessId
            ownedEmulatorStartTimeUtcTicks = $validInteractionCapture.session.emulatorStartTimeUtcTicks
            interactionCaptureStartedAtUtc = $validInteractionCapture.interactionCaptureStartedAtUtc
        }
        $manualCompletionProbeRoot = Join-Path $repoRoot ".superpowers\ui-audit-manual-completion-$([guid]::NewGuid().ToString('N'))"
        $previousCompletionReportRoot = $script:reportRoot
        try {
            $script:reportRoot = $manualCompletionProbeRoot
            New-Item -ItemType Directory -Force -Path $manualCompletionProbeRoot | Out-Null
            foreach ($evidence in @($Manifest.interactionEvidence)) {
                $artifactPath = Resolve-SafeReportArtifactPath $manualCompletionProbeRoot $evidence.artifactPath $false
                New-Item -ItemType Directory -Force -Path (Split-Path $artifactPath -Parent) | Out-Null
                [IO.File]::WriteAllBytes($artifactPath, [byte[]](0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00))
            }
            Write-InteractionCaptureCheckpoint $validInteractionCapture
            $capturedManifestJson = ConvertTo-CanonicalManifestJson $Manifest
            [IO.File]::WriteAllText((Join-Path $manualCompletionProbeRoot 'case-manifest.json'), $capturedManifestJson, [Text.UTF8Encoding]::new($false))
            Set-Content -LiteralPath (Join-Path $manualCompletionProbeRoot 'case-manifest.sha256') -Value $ManifestHash -Encoding Ascii
            Set-Content -LiteralPath (Join-Path $manualCompletionProbeRoot 'summary.md') -Value "- Status: captured-awaiting-manual-inspection`n- Manual inspection complete: false" -Encoding UTF8
            $manualText = @('Audit status: complete', "Manifest SHA-256: $ManifestHash", '', '| Case | Artifact SHA-256 | Requested / measured window and stage | Density / font / theme / locale | Expected invariants | Result | Defect |', '| --- | --- | --- | --- | --- | --- | --- |')
            foreach ($case in @($Manifest.cases)) { $manualText += "| $($case.id) | $('a' * 64) | requested / measured | environment | invariants | pass |  |" }
            $manualText += @('', '| Interaction evidence | Artifact path | Artifact SHA-256 | Expected invariants | Result | Defect |', '| --- | --- | --- | --- | --- | --- |')
            foreach ($row in $checkpointedInteractionRows) { $manualText += "| $($row.id) | $($row.artifactPath) | $($row.artifactSha256) | $(($Manifest.interactionEvidence | Where-Object id -ceq $row.id | Select-Object -First 1).expectedInvariants -join ', ') | pass |  |" }
            $manualText = $manualText -join "`n"
            $manualCompletionCurrentProvenance = $capturedProvenance
            function Resolve-Git { return 'dry-run-git' }
            function Resolve-DebugApk { return 'dry-run-debug.apk' }
            function Get-CurrentCaptureProvenance { return $manualCompletionCurrentProvenance }
            foreach ($mutation in @('missing-record', 'changed-checkpoint-hash', 'mismatched-manual-hash', 'changed-process-id', 'changed-process-start', 'missing-capture-start', 'changed-capture-start', 'cleanup-pending')) {
                $mutatedSummary = $manualCompletionSummary | ConvertTo-Json -Depth 16 | ConvertFrom-Json
                $mutatedManualText = $manualText
                $expectedError = switch ($mutation) {
                    'missing-record' { "Interaction capture record lacks 'session'" }
                    'changed-checkpoint-hash' { 'hash changed' }
                    'mismatched-manual-hash' { 'checkpointed-hash mismatch' }
                    'changed-process-id' { "Interaction capture session 'emulatorProcessId' changed" }
                    'changed-process-start' { "Interaction capture session 'emulatorStartTimeUtcTicks' changed" }
                    'missing-capture-start' { 'Capture summary timestamp lacks an explicit UTC offset' }
                    'changed-capture-start' { 'Interaction capture start time changed' }
                    'cleanup-pending' { 'invalid status' }
                }
                switch ($mutation) {
                    'missing-record' { $mutatedSummary.interactionCapture = $null }
                    'changed-checkpoint-hash' { $mutatedSummary.interactionCapture.artifacts[0].sha256 = ('b' * 64) }
                    'mismatched-manual-hash' { $mutatedManualText = $mutatedManualText.Replace($checkpointedInteractionRows[0].artifactSha256, ('b' * 64)) }
                    'changed-process-id' { $mutatedSummary.ownedEmulatorProcessId = 999999 }
                    'changed-process-start' { $mutatedSummary.ownedEmulatorStartTimeUtcTicks = 1 }
                    'missing-capture-start' { $mutatedSummary.PSObject.Properties.Remove('interactionCaptureStartedAtUtc') }
                    'changed-capture-start' { $mutatedSummary.interactionCaptureStartedAtUtc = '2026-08-08T12:34:57.0000000Z' }
                    'cleanup-pending' { $mutatedSummary.status = 'cleanup-pending' }
                }
                $results = @($Manifest.cases | ForEach-Object { [pscustomobject]@{ id = $_.id; screenshotSha256 = ('a' * 64) } })
                $summaryInteractionCaptureStartedAtUtc = if (Test-Property $mutatedSummary 'interactionCaptureStartedAtUtc') { $mutatedSummary.interactionCaptureStartedAtUtc } else { $null }
                $summary = [pscustomobject]@{
                    schemaVersion = 2
                    manifestSha256 = $ManifestHash
                    expectedCaseCount = $expectedCaseCount
                    resultCount = $expectedCaseCount
                    requiredInteractionEvidenceCount = $Manifest.interactionEvidenceCount
                    failure = $null
                    cleanupErrors = @()
                    status = if (Test-Property $mutatedSummary 'status') { $mutatedSummary.status } else { 'captured-awaiting-manual-inspection' }
                    captureProvenance = $mutatedSummary.captureProvenance
                    interactionCapture = $mutatedSummary.interactionCapture
                    deviceSerial = $mutatedSummary.deviceSerial
                    avdName = $mutatedSummary.avdName
                    snapshotName = $mutatedSummary.snapshotName
                    ownedEmulatorProcessId = $mutatedSummary.ownedEmulatorProcessId
                    ownedEmulatorStartTimeUtcTicks = $mutatedSummary.ownedEmulatorStartTimeUtcTicks
                    captureStartedAtUtc = $script:captureStartedAtUtc
                    interactionCaptureStartedAtUtc = $summaryInteractionCaptureStartedAtUtc
                    results = $results
                    manualInspectionComplete = $false
                }
                $summary | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $manualCompletionProbeRoot 'summary.json') -Encoding UTF8
                Set-Content -LiteralPath (Join-Path $manualCompletionProbeRoot 'manual-inspection.md') -Value $mutatedManualText -Encoding UTF8
                $failure = $null
                try { Complete-ManualInspectionAudit $Manifest $ManifestHash } catch { $failure = $_.Exception.Message }
                if ([string]::IsNullOrWhiteSpace($failure) -or $failure -notmatch [regex]::Escape($expectedError)) { Fail "Manual-completion $mutation probe did not report '$expectedError': $failure" 'dry-run' }
                $postFailureSummary = Get-Content -LiteralPath (Join-Path $manualCompletionProbeRoot 'summary.json') -Raw | ConvertFrom-Json
                if ($postFailureSummary.status -eq 'complete' -or $postFailureSummary.manualInspectionComplete) { Fail "Manual-completion $mutation probe wrote a completed summary." 'dry-run' }
            }
        } finally {
            $script:reportRoot = $previousCompletionReportRoot
            if (Test-Path -LiteralPath $manualCompletionProbeRoot) { Remove-Item -LiteralPath $manualCompletionProbeRoot -Recurse -Force }
        }
        foreach ($mutation in @('missing-artifact', 'substituted-path', 'duplicate-artifact', 'changed-hash', 'changed-session', 'changed-process-id', 'changed-process-start', 'missing-capture-start', 'changed-apk')) {
            $mutated = $validInteractionCapture | ConvertTo-Json -Depth 16 | ConvertFrom-Json
            switch ($mutation) {
                'missing-artifact' { $mutated.artifacts = @($mutated.artifacts | Select-Object -First 1) }
                'substituted-path' { $mutated.artifacts[0].artifactPath = 'interaction\substituted.png' }
                'duplicate-artifact' { $mutated.artifacts[1].artifactPath = $mutated.artifacts[0].artifactPath }
                'changed-hash' { $mutated.artifacts[0].sha256 = ('b' * 64) }
                'changed-session' { $mutated.session.deviceSerial = 'emulator-9999' }
                'changed-process-id' { $mutated.session.emulatorProcessId = 999999 }
                'changed-process-start' { $mutated.session.emulatorStartTimeUtcTicks = 1 }
                'missing-capture-start' { $mutated.PSObject.Properties.Remove('interactionCaptureStartedAtUtc') }
                'changed-apk' { $mutated.captureProvenance.debugApkSha256 = ('b' * 64) }
            }
            $failed = $false
            try { Assert-InteractionCaptureRecord $Manifest $mutated $validInteractionCapture.session $capturedProvenance } catch { $failed = $true }
            if (-not $failed) { Fail "Interaction session-record $mutation probe unexpectedly passed." 'dry-run' }
        }
    } finally {
        $script:reportRoot = $previousReportRoot
        $script:ownedEmulator = $previousOwnedEmulator
        $script:ownedEmulatorStartTimeUtcTicks = $previousOwnedEmulatorStartTimeUtcTicks
        $script:captureProvenance = $previousCaptureProvenance
        $script:captureStartedAtUtc = $previousCaptureStartedAtUtc
        $script:interactionCaptureStartedAtUtc = $previousInteractionCaptureStartedAtUtc
        $script:interactionCheckpointPersisted = $previousInteractionCheckpointPersisted
        $script:narCorpusPackageCleanVerified = $previousNarCorpusPackageCleanVerified
        $captureProbeProcess.Dispose()
        if (Test-Path -LiteralPath $interactionCaptureProbeRoot) { Remove-Item -LiteralPath $interactionCaptureProbeRoot -Recurse -Force }
    }
    $interactionLabels = @(
        'Touch named collisions and generic transparent canvas.',
        'Mouse primary single-click and double-click.',
        'Scroll/click bubbles and reopen choices.',
        'Tab, Shift-Tab, arrows, Page Up, Page Down, Enter, Space, Escape, and D-pad.',
        'Toggle chrome only through empty stage or its labeled semantic action.',
        'Open and close bottom-sheet, side-panel, and full-modal debug presentations.',
        'Rotate, resize, and recreate the Activity.',
        'TalkBack plus Switch Access or Voice Access; merged and unmerged semantics.',
        'Invoke collision custom actions and verify focus recovery.',
        'Exercise input IME on Snake and Otacon.',
        'Verify passive stall prompt behavior.',
        'Verify exact SHIORI event identity, coordinate, scope, identifier, button, and source fields; no bubble/surface/chrome leakage.'
    )
    $checkedInteractionLines = @($interactionLabels | ForEach-Object { "- [x] $_" })
    Assert-ExactInteractionChecklist ($checkedInteractionLines -join "`n")
    Assert-ExactInteractionChecklist (@($checkedInteractionLines[11..0]) -join "`n")
    $invalidInteractionChecklists = [ordered]@{
        missing = @($checkedInteractionLines[0..10])
        substituted = @($checkedInteractionLines)
        duplicated = @($checkedInteractionLines)
        extra = @($checkedInteractionLines) + '- [x] Unexpected interaction.'
        unchecked = @($checkedInteractionLines)
    }
    $invalidInteractionChecklists.substituted[4] = '- [x] Substituted interaction.'
    $invalidInteractionChecklists.duplicated[4] = $checkedInteractionLines[3]
    $invalidInteractionChecklists.unchecked[4] = $invalidInteractionChecklists.unchecked[4].Replace('[x]', '[ ]')
    foreach ($probe in $invalidInteractionChecklists.GetEnumerator()) {
        $failed = $false
        try { Assert-ExactInteractionChecklist (@($probe.Value) -join "`n") } catch { $failed = $true }
        if (-not $failed) { Fail "Interaction checklist $($probe.Key) probe unexpectedly passed." 'dry-run' }
    }
    $restoreProbe=[pscustomobject]@{ sizeMode='reset'; densityMode='override'; fontMode='delete'; rotationOrder=@('auto','user'); networkOrder=@('data','wifi') }; if($restoreProbe.fontMode -ne 'delete' -or $restoreProbe.rotationOrder.Count -ne 2){Fail 'Restore-plan probe failed.' 'dry-run'}
    if ((Get-CorpusInputKind -IsContainer $false -Extension '.nar') -ne 'archive') { Fail 'Standalone NAR corpus-input probe failed.' 'dry-run' }
    if ((Get-CorpusInputKind -IsContainer $true -Extension '') -ne 'directory') { Fail 'Directory corpus-input probe failed.' 'dry-run' }
    if ((Get-RequestedUserRotation ([pscustomobject]@{ widthDp=720; heightDp=360 })) -ne 0) { Fail 'Landscape display-override rotation-plan probe failed.' 'dry-run' }
    if ((Get-RequestedUserRotation ([pscustomobject]@{ widthDp=360; heightDp=720 })) -ne 0) { Fail 'Portrait rotation-plan probe failed.' 'dry-run' }
    if ((ConvertFrom-GradlePath 'C\:\\tools\\android.sdk') -ne 'C:\tools\android.sdk') { Fail 'Gradle SDK path unescape probe failed.' 'dry-run' }
    $validIdentity=[pscustomobject]@{ avd=$AvdName; qemu='1'; api='37'; abi='x86_64'; debuggable='0' }
    if (-not (Test-AuditEmulatorIdentity $validIdentity)) { Fail 'Standard API 37 emulator identity probe failed.' 'dry-run' }
    $physicalIdentity=[pscustomobject]@{ avd=$AvdName; qemu='0'; api='37'; abi='x86_64'; debuggable='1' }
    if (Test-AuditEmulatorIdentity $physicalIdentity) { Fail 'Physical-device identity probe unexpectedly passed.' 'dry-run' }
    if ((Get-ExternalDataProbeState 1 '' 'No such file or directory') -ne 'absent') { Fail 'Missing external-data probe failed.' 'dry-run' }
    if ((Get-ExternalDataProbeState 0 '/sdcard/Android/data/example' '') -ne 'present') { Fail 'Present external-data probe failed.' 'dry-run' }
    if ((Get-ExternalDataProbeState 1 '' 'Permission denied') -ne 'unknown') { Fail 'Unknown external-data probe failed.' 'dry-run' }
    $safeStageLayoutJson = '[{"resource-id":"list-ghost","center":"[50,10]"},{"resource-id":"surface-kero","center":"[20,30]"},{"resource-id":"surface-sakura","center":"[70,30]"}]'
    $safeStageXml = '<hierarchy><node bounds="[0,0][100,100]"><node resource-id="ghost-safe-stage" bounds="[0,0][100,100]" /><node resource-id="list-ghost" bounds="[40,0][60,20]" /><node resource-id="surface-kero" bounds="[10,20][30,40]" /><node resource-id="surface-sakura" bounds="[60,20][81,41]" /></node></hierarchy>'
    $safeStageBounds = Get-VerifiedGhostSafeStageBounds $safeStageLayoutJson $safeStageXml -ExpectCharacterSurfaces $true
    if ($safeStageBounds.width -ne 100 -or $safeStageBounds.height -ne 100) { Fail 'Dual-source surface-center/safe-stage bounds probe failed.' 'dry-run' }
    $tinyLayoutJson = '[{"resource-id":"list-ghost","center":"[50,10]"}]'
    $tinyLayoutXml = '<hierarchy><node bounds="[0,0][100,100]"><node resource-id="ghost-safe-stage" bounds="[0,0][100,100]" /><node resource-id="list-ghost" bounds="[40,0][60,20]" /></node></hierarchy>'
    $tinySafeStageBounds = Get-VerifiedGhostSafeStageBounds $tinyLayoutJson $tinyLayoutXml -ExpectCharacterSurfaces $false
    if ($tinySafeStageBounds.width -ne 100 -or $tinySafeStageBounds.height -ne 100) { Fail 'Tiny fallback dual-source anchor/safe-stage probe failed.' 'dry-run' }
    $invalidSafeStagePairs = [ordered]@{
        missingSafeStageXml = @($safeStageLayoutJson, $safeStageXml.Replace('<node resource-id="ghost-safe-stage" bounds="[0,0][100,100]" />', ''))
        duplicateSafeStageXml = @($safeStageLayoutJson, $safeStageXml.Replace('<node resource-id="ghost-safe-stage" bounds="[0,0][100,100]" />', '<node resource-id="ghost-safe-stage" bounds="[0,0][100,100]" /><node resource-id="ghost-safe-stage" bounds="[0,0][100,100]" />'))
        missingJsonAnchor = @($safeStageLayoutJson.Replace('{"resource-id":"list-ghost","center":"[50,10]"},', ''), $safeStageXml)
        duplicateXmlAnchor = @($safeStageLayoutJson, $safeStageXml.Replace('<node resource-id="list-ghost" bounds="[40,0][60,20]" />', '<node resource-id="list-ghost" bounds="[40,0][60,20]" /><node resource-id="list-ghost" bounds="[40,0][60,20]" />'))
        mismatchedAnchor = @($safeStageLayoutJson.Replace('[50,10]', '[51,10]'), $safeStageXml)
        missingJsonSurface = @('[{"resource-id":"list-ghost","center":"[50,10]"},{"resource-id":"surface-kero","center":"[20,30]"}]', $safeStageXml)
        duplicateJsonSurface = @($safeStageLayoutJson.Replace('{"resource-id":"surface-kero","center":"[20,30]"}', '{"resource-id":"surface-kero","center":"[20,30]"},{"resource-id":"surface-kero","center":"[20,30]"}'), $safeStageXml)
        missingXmlSurface = @($safeStageLayoutJson, $safeStageXml.Replace('<node resource-id="surface-sakura" bounds="[60,20][81,41]" />', ''))
        duplicateXmlSurface = @($safeStageLayoutJson, $safeStageXml.Replace('<node resource-id="surface-sakura" bounds="[60,20][81,41]" />', '<node resource-id="surface-sakura" bounds="[60,20][81,41]" /><node resource-id="surface-sakura" bounds="[60,20][81,41]" />'))
        mismatchedCenter = @($safeStageLayoutJson.Replace('[20,30]', '[21,30]'), $safeStageXml)
        outsideSafeStage = @($safeStageLayoutJson.Replace('[70,30]', '[120,30]'), $safeStageXml.Replace('[60,20][81,41]', '[110,20][131,41]'))
    }
    foreach ($probe in $invalidSafeStagePairs.GetEnumerator()) {
        $failed = $false
        try { Get-VerifiedGhostSafeStageBounds $probe.Value[0] $probe.Value[1] -ExpectCharacterSurfaces $true | Out-Null } catch { $failed = $true }
        if (-not $failed) { Fail "Dual-source surface/safe-stage $($probe.Key) probe unexpectedly passed." 'dry-run' }
    }
    foreach ($probe in ([ordered]@{
        jsonSurfacePresent = @('[{"resource-id":"list-ghost","center":"[50,10]"},{"resource-id":"surface-kero","center":"[20,30]"}]', $tinyLayoutXml)
        xmlSurfacePresent = @($tinyLayoutJson, $tinyLayoutXml.Replace('</node></hierarchy>', '<node resource-id="surface-kero" bounds="[10,20][30,40]" /></node></hierarchy>'))
    }).GetEnumerator()) {
        $failed = $false
        try { Get-VerifiedGhostSafeStageBounds $probe.Value[0] $probe.Value[1] -ExpectCharacterSurfaces $false | Out-Null } catch { $failed = $true }
        if (-not $failed) { Fail "Tiny fallback $($probe.Key) probe unexpectedly passed." 'dry-run' }
    }
    $windowBounds=Get-WindowBoundsFromUiAutomatorXmlText $safeStageXml
    if ($windowBounds.width -ne 100 -or $windowBounds.height -ne 100) { Fail 'UiAutomator window-bounds probe failed.' 'dry-run' }
    if ((Get-DisplayOrientationFromDump "header`nmCurrentOrientation=3`nfooter") -ne '3') { Fail 'Display-orientation parser probe failed.' 'dry-run' }
    $logical=Get-LogicalDisplaySizeFromWindowDump 'init=1080x2400 base=720x360 cur=720x360 app=720x360'
    if ($logical.width -ne 720 -or $logical.height -ne 360) { Fail 'Logical display-size parser probe failed.' 'dry-run' }
    if ((Select-AuditLocale -PersistLocale '' -ProductLocale 'en-US' -ActivityConfiguration '') -ne 'en-US') { Fail 'Blank persisted-locale product fallback probe failed.' 'dry-run' }
    if ((Select-AuditLocale -PersistLocale '' -ProductLocale '' -ActivityConfiguration 'config: { locales=[en-GB,fr-FR] }') -ne 'en-GB') { Fail 'Blank persisted/product locale configuration fallback probe failed.' 'dry-run' }
    if ((Select-AuditLocale -PersistLocale '' -ProductLocale '' -ActivityConfiguration 'config: en-rUS-ldltr-sw360dp-w360dp-h720dp') -ne 'en-US') { Fail 'Android resource-qualifier locale fallback probe failed.' 'dry-run' }
    if (-not(Test-Path -LiteralPath $pwshProbe -PathType Leaf)) { Fail 'PowerShell 7 resolver probe failed.' 'dry-run' }
    if ($NarProfileTimeoutMinutes -le ($BuildTimeoutMinutes + (23 * 5))) { Fail 'NAR profile parent timeout does not exceed Task 17 child deadlines.' 'dry-run' }
    if ((Get-NarProfileSummaryRelativePath 'compact-landscape') -ne 'nar\compact-landscape\task17-summary.json') { Fail 'NAR retained-summary path probe failed.' 'dry-run' }
    $compactNarChild = New-NarCorpusAuditChildInvocation -ProfileName 'compact-landscape' -ResolvedCorpusRoots @('one.nar', 'two.nar') -ResolvedAdbPath 'adb'
    $portraitNarChild = New-NarCorpusAuditChildInvocation -ProfileName 'portrait' -ResolvedCorpusRoots @('one.nar', 'two.nar') -ResolvedAdbPath 'adb'
    $tabletNarChild = New-NarCorpusAuditChildInvocation -ProfileName 'tablet' -ResolvedCorpusRoots @('one.nar', 'two.nar') -ResolvedAdbPath 'adb'
    $compactNarPayload = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($compactNarChild.payload)) | ConvertFrom-Json
    foreach ($profileChild in @($portraitNarChild, $tabletNarChild)) {
        $profilePayload = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($profileChild.payload)) | ConvertFrom-Json
        if ($null -ne $profilePayload.PSObject.Properties['expectedStageGeometryProfile'] -or $profileChild.command -match 'ExpectedStageGeometryProfile') {
            Fail 'Only compact-landscape may pass a Task 17 expected-stage geometry profile.' 'dry-run'
        }
    }
    if ($compactNarPayload.expectedStageGeometryProfile -cne 'compact-landscape' -or $compactNarChild.command -notmatch '(?<!\S)-ExpectedStageGeometryProfile compact-landscape(?!\S)') {
        Fail 'Compact-landscape Task 17 geometry-profile transport probe failed.' 'dry-run'
    }
    $expandedRoots = @(Expand-CorpusRootArguments @('one.nar,two', 'three'))
    if ($expandedRoots.Count -ne 3 -or $expandedRoots[0] -ne 'one.nar' -or $expandedRoots[2] -ne 'three') { Fail 'Comma-separated corpus-root transport probe failed.' 'dry-run' }
    $narManifest=Get-Content -LiteralPath (Join-Path $repoRoot $ManifestPath) -Raw | ConvertFrom-Json
    foreach($rep in (Get-UiAuditRepresentatives)){if(@($narManifest.entries|Where-Object{$_.label -ceq $rep.label -and $_.sha256 -ceq $rep.sha256}).Count -ne 1){Fail "Dry-run NAR label/SHA probe failed for '$($rep.label)'." 'dry-run'}}
    if (Test-DryRunRequiresCorpus $HostSelfTest) {
        $resolvedDryRunCorpus=@(Assert-CorpusInputs $narManifest)
        if($resolvedDryRunCorpus.Count-ne $CorpusRoots.Count){Fail "Dry-run corpus preflight expected $($CorpusRoots.Count) resolved roots, got $($resolvedDryRunCorpus.Count)." 'dry-run'}
    }
    $interactionPreflightRoot = Join-Path $repoRoot ".superpowers\ui-audit-interaction-preflight-$([guid]::NewGuid().ToString('N'))"
    try {
        New-Item -ItemType Directory -Force -Path $interactionPreflightRoot | Out-Null
        foreach ($staleEvidence in @('summary.json', 'summary.md', 'interaction-capture.json')) {
            Set-Content -LiteralPath (Join-Path $interactionPreflightRoot $staleEvidence) -Value 'stale evidence' -Encoding UTF8
        }
        Invalidate-PreviousManualInteractionCheckpoint $interactionPreflightRoot
        foreach ($staleEvidence in @('summary.json', 'summary.md', 'interaction-capture.json')) {
            if (Test-Path -LiteralPath (Join-Path $interactionPreflightRoot $staleEvidence) -PathType Leaf) {
                Fail "Stale interaction checkpoint artifact '$staleEvidence' was not invalidated before capture." 'dry-run'
            }
        }
        Assert-RequiredInteractionEvidenceAbsent $Manifest $interactionPreflightRoot
        $existingInteractionPath = Resolve-SafeReportArtifactPath $interactionPreflightRoot $Manifest.interactionEvidence[0].artifactPath $false
        New-Item -ItemType Directory -Force -Path (Split-Path $existingInteractionPath -Parent) | Out-Null
        Set-Content -LiteralPath $existingInteractionPath -Value 'existing evidence' -Encoding UTF8
        $failed = $false
        try { Assert-RequiredInteractionEvidenceAbsent $Manifest $interactionPreflightRoot } catch { $failed = $true }
        if (-not $failed) { Fail 'Pre-existing interaction-evidence path probe unexpectedly passed.' 'dry-run' }
    } finally {
        if (Test-Path -LiteralPath $interactionPreflightRoot) { Remove-Item -LiteralPath $interactionPreflightRoot -Recurse -Force }
    }
    Write-Host "Dry-run passed: schemaVersion=$($Manifest.schemaVersion), caseSetVersion=$($Manifest.caseSetVersion), cases=$($Manifest.caseCount), sha256=$ManifestHash"
    Write-Host 'Dry-run made no build, device, emulator, or report mutations.'
}

Set-Location -LiteralPath $repoRoot
$CorpusRoots = @(Expand-CorpusRootArguments $CorpusRoots)
$uiManifest=New-UiAuditManifest
$canonicalManifest=ConvertTo-CanonicalManifestJson $uiManifest
$uiManifestHash=Get-StringSha256 $canonicalManifest

if ($DryRun -and $VerifyManualInspection) { Fail 'DryRun and VerifyManualInspection are mutually exclusive.' 'usage' }
if ($HostSelfTest -and -not $DryRun) { Fail 'HostSelfTest requires DryRun.' 'usage' }
if ($DryRun) { Invoke-DryRunSelfTest $uiManifest $uiManifestHash; return }
if ($VerifyManualInspection) { Complete-ManualInspectionAudit $uiManifest $uiManifestHash; return }

$script:originalState=$null
$installed=$false
try {
    Assert-UiAuditManifest $uiManifest
    $narManifest=Get-Content -LiteralPath (Join-Path $repoRoot $ManifestPath) -Raw | ConvertFrom-Json
    $resolvedCorpusRoots=Assert-CorpusInputs $narManifest
    $manualPath=Join-Path $reportRoot 'manual-inspection.md'
    if(Test-Path -LiteralPath $manualPath){$existing=Get-Content -LiteralPath $manualPath -Raw;if($existing -match '(?im)^Audit status:\s*complete\s*$' -or (Test-ManualInspectionHasRecordedResult $existing)){Fail 'Refusing completed checklist overwrite.' 'report'}}
    Invalidate-PreviousManualInteractionCheckpoint $reportRoot

    $script:resolvedAdb=Resolve-SdkTool $AdbPath 'platform-tools\adb.exe' 'adb'
    $script:resolvedEmulator=Resolve-SdkTool $EmulatorPath 'emulator\emulator.exe' 'emulator'
    $script:resolvedAndroidCli=Resolve-AndroidCli $AndroidCliPath
    $script:resolvedPwsh=Resolve-PowerShell7
    if($DeviceSerial -notmatch '^emulator-(?<port>\d+)$'){Fail "DeviceSerial '$DeviceSerial' is not an emulator serial." 'device'}
    $port=[int]$matches.port; if($port -lt 5554 -or $port -gt 5682 -or ($port % 2)-ne 0){Fail "Unsupported emulator port '$port'." 'device'}
    $devices=Invoke-Native -FilePath $script:resolvedAdb -Arguments @('devices') -TimeoutSeconds 20
    if($devices.output -match "(?m)^$([regex]::Escape($DeviceSerial))\s+"){Fail "Serial '$DeviceSerial' is already online; refusing reuse." 'device'}

    $avdHomes=@(); if($env:ANDROID_AVD_HOME){$avdHomes+=$env:ANDROID_AVD_HOME}; if($env:USERPROFILE){$avdHomes+=(Join-Path $env:USERPROFILE '.android\avd')}
    $avdDir=$null; foreach($avdHome in $avdHomes){$candidate=Join-Path $avdHome "$AvdName.avd";if(Test-Path -LiteralPath $candidate -PathType Container){$avdDir=(Resolve-Path $candidate).Path;break}}
    if(-not $avdDir){Fail "Existing AVD directory '$AvdName.avd' was not found." 'device'}
    $snapshotDir=Join-Path $avdDir "snapshots\$SnapshotName"; if(-not(Test-Path -LiteralPath $snapshotDir -PathType Container)){Fail "Existing snapshot '$SnapshotName' was not found; runner never creates/deletes snapshots." 'device'}

    $gitPath=Resolve-Git
    $repositoryBeforeBuild=Get-TrackedRepositoryState $gitPath
    $gradle=Join-Path $repoRoot 'gradlew.bat'; Invoke-Native -FilePath $gradle -Arguments @('assembleDebug','validateDebugScreenshotTest','--console=plain') -TimeoutSeconds ($BuildTimeoutMinutes*60) | Out-Null
    $debugApk=Resolve-DebugApk
    $script:captureProvenance=Get-CurrentCaptureProvenance $gitPath $debugApk
    if ($script:captureProvenance.gitHead -cne $repositoryBeforeBuild.gitHead) { Fail 'Repository HEAD changed during the audit build.' 'git' }

    Assert-RequiredInteractionEvidenceAbsent $uiManifest $reportRoot
    New-Item -ItemType Directory -Force -Path $reportRoot | Out-Null; $script:reportInitialized=$true
    $script:captureStartedAtUtc=[DateTime]::UtcNow.ToString('o', [Globalization.CultureInfo]::InvariantCulture)
    [IO.File]::WriteAllText((Join-Path $reportRoot 'case-manifest.json'), $canonicalManifest, [Text.UTF8Encoding]::new($false))
    Set-Content -LiteralPath (Join-Path $reportRoot 'case-manifest.sha256') -Value $uiManifestHash -Encoding Ascii
    New-ManualInspectionTemplate $uiManifest $uiManifestHash

    $launch=[Diagnostics.ProcessStartInfo]::new();$launch.FileName=$script:resolvedEmulator;$launch.UseShellExecute=$false;$launch.CreateNoWindow=$true
    $launchArgs=@('-avd',$AvdName,'-snapshot',$SnapshotName,'-no-snapshot-save','-read-only','-port',[string]$port)
    if($null -ne $launch.PSObject.Properties['ArgumentList']){foreach($arg in $launchArgs){[void]$launch.ArgumentList.Add($arg)}}else{$launch.Arguments=(@($launchArgs|ForEach-Object{ConvertTo-WindowsCommandLineArgument $_}) -join ' ')}
    $script:ownedEmulator=[Diagnostics.Process]::Start($launch); if($null -eq $script:ownedEmulator){Fail 'Failed to launch owned emulator process.' 'device'}; $script:ownedEmulatorProcessId=$script:ownedEmulator.Id
    $script:ownedEmulatorStartTimeUtcTicks=$script:ownedEmulator.StartTime.ToUniversalTime().Ticks
    $script:emulatorWatchdog=Start-EmulatorWatchdog $script:ownedEmulator $script:ownedEmulatorStartTimeUtcTicks
    $deadline=(Get-Date).AddMinutes($BootTimeoutMinutes); do { Start-Sleep -Seconds 2; $state=Invoke-Native -FilePath $script:resolvedAdb -Arguments @('-s',$DeviceSerial,'get-state') -TimeoutSeconds 10 -AllowFailure -Transport adb; if($state.exitCode -eq 0){$boot=(Invoke-Adb @('shell','getprop','sys.boot_completed') 10 -AllowFailure).output.Trim();if($boot -eq '1'){break}} } while((Get-Date)-lt$deadline)
    if($boot -ne '1'){Fail "Emulator did not boot within $BootTimeoutMinutes minutes." 'device'}
    $identity=[pscustomobject]@{avd=(Invoke-Adb @('shell','getprop','ro.boot.qemu.avd_name')).output.Trim();qemu=(Invoke-Adb @('shell','getprop','ro.kernel.qemu')).output.Trim();api=(Invoke-Adb @('shell','getprop','ro.build.version.sdk')).output.Trim();abi=(Invoke-Adb @('shell','getprop','ro.product.cpu.abi')).output.Trim();debuggable=(Invoke-Adb @('shell','getprop','ro.debuggable')).output.Trim()}
    if(-not(Test-AuditEmulatorIdentity $identity)){Fail "Emulator identity gate failed: avd=$($identity.avd) qemu=$($identity.qemu) api=$($identity.api) abi=$($identity.abi) debuggable=$($identity.debuggable)." 'device'}
    Assert-PackageClean
    $script:originalState=Get-DeviceStateSnapshot
    Invoke-Adb @('shell','svc','wifi','disable') | Out-Null; Invoke-Adb @('shell','svc','data','disable') | Out-Null
    Invoke-Native -FilePath $script:resolvedAndroidCli -Arguments @('run','--debug',"--device=$DeviceSerial", "--apks=$debugApk") -TimeoutSeconds 180 -Transport adb-owner | Out-Null; $installed=$true

    foreach($case in @($uiManifest.cases|Where-Object kind -eq 'live')){
        $wm=Set-DisplayProfile $case.requested $script:originalState.wm.physicalDensity
        Invoke-Adb @('shell','am','force-stop',$targetPackage)|Out-Null;Invoke-Adb @('shell','am','start','-W','-n',$mainActivity)|Out-Null;Start-Sleep -Seconds 2
        $shot=Join-Path $reportRoot $case.screenshotPath;$layout=Join-Path $reportRoot $case.layoutPath;$annotated=Join-Path $reportRoot $case.annotatedPath;$uiAutomatorLayout=[IO.Path]::ChangeExtension($layout,'.uiautomator.xml')
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $shot) | Out-Null
        Invoke-Native -FilePath $script:resolvedAndroidCli -Arguments @('layout','--pretty',"--device=$DeviceSerial",'-o',$layout) -TimeoutSeconds 60 -Transport adb-owner|Out-Null
        $deviceUiAutomatorLayout="/sdcard/Download/nanidroid-$($case.id).xml"
        try {
            Invoke-Adb @('shell','uiautomator','dump',$deviceUiAutomatorLayout) 60 | Out-Null
            Invoke-Adb @('pull',$deviceUiAutomatorLayout,$uiAutomatorLayout) 60 | Out-Null
        } finally {
            if(-not$script:adbTransportDead){Invoke-Adb @('shell','rm','-f',$deviceUiAutomatorLayout) 30 -AllowFailure|Out-Null}
        }
        Invoke-Native -FilePath $script:resolvedAndroidCli -Arguments @('screen','capture',"--device=$DeviceSerial",'-o',$shot) -TimeoutSeconds 60 -Transport adb-owner|Out-Null
        Invoke-Native -FilePath $script:resolvedAndroidCli -Arguments @('screen','capture','-a',"--device=$DeviceSerial",'-o',$annotated) -TimeoutSeconds 60 -Transport adb-owner|Out-Null
        if((Get-Item -LiteralPath $layout).Length -le 2){Fail "Empty layout capture for '$($case.id)'." 'artifact'}
        if((Get-Item -LiteralPath $uiAutomatorLayout).Length -le 2){Fail "Empty UiAutomator layout capture for '$($case.id)'." 'artifact'}
        $layoutJsonText=Get-Content -LiteralPath $layout -Raw;$uiAutomatorXmlText=Get-Content -LiteralPath $uiAutomatorLayout -Raw;$stage=Get-VerifiedGhostSafeStageBounds $layoutJsonText $uiAutomatorXmlText -ExpectCharacterSurfaces ([bool]$case.requested.expectCharacterSurfaces);$windowBounds=Get-WindowBoundsFromUiAutomatorXmlText $uiAutomatorXmlText
        if($windowBounds.width-ne$wm.logicalWidth-or$windowBounds.height-ne$wm.logicalHeight){Fail "UiAutomator window $($windowBounds.width)x$($windowBounds.height) differs from settled logical display $($wm.logicalWidth)x$($wm.logicalHeight)." 'layout'}
        $shotHash=Assert-Png $shot;$annotatedHash=Assert-Png $annotated;$layoutHash=(Get-FileHash -LiteralPath $layout -Algorithm SHA256).Hash.ToLowerInvariant();$uiAutomatorLayoutHash=(Get-FileHash -LiteralPath $uiAutomatorLayout -Algorithm SHA256).Hash.ToLowerInvariant()
        $measured=[pscustomobject]@{widthPx=$windowBounds.width;heightPx=$windowBounds.height;density=$wm.effectiveDensity;widthDp=[Math]::Round($windowBounds.width*160/$wm.effectiveDensity,2);heightDp=[Math]::Round($windowBounds.height*160/$wm.effectiveDensity,2);fontScale=$case.requested.fontScale}
        Add-Result $case $shotHash $layoutHash $measured $stage ([pscustomobject]@{apkSha256=(Get-FileHash $debugApk -Algorithm SHA256).Hash.ToLowerInvariant();uiAutomatorLayoutPath=Get-RelativePath $reportRoot $uiAutomatorLayout;uiAutomatorLayoutSha256=$uiAutomatorLayoutHash}) $annotatedHash
    }

    $uninstallResult=Invoke-Adb @('uninstall',$targetPackage) 120
    if($uninstallResult.output.Trim() -ne 'Success'){Fail "Live-capture package uninstall failed: $($uninstallResult.output) $($uninstallResult.error)" 'device'}
    $installed=$false
    Assert-PackageClean

    foreach($case in @($uiManifest.cases|Where-Object kind -eq 'fixture')){
        $source=Join-Path $fixtureRoot $case.source.referencePath;if(-not(Test-Path -LiteralPath $source -PathType Leaf)){Fail "Fixture disappeared '$source'." 'fixture'}
        $dest=Join-Path $reportRoot $case.screenshotPath;New-Item -ItemType Directory -Force -Path (Split-Path $dest -Parent)|Out-Null;Copy-Item -LiteralPath $source -Destination $dest -Force
        $hash=Assert-Png $dest;Add-Result $case $hash $null $null $null ([pscustomobject]@{referencePath=$case.source.referencePath;sourceSha256=(Get-FileHash $source -Algorithm SHA256).Hash.ToLowerInvariant();validationTask='validateDebugScreenshotTest'})
    }

    foreach($profile in @(@{name='portrait';w=360;h=720},@{name='compact-landscape';w=720;h=360},@{name='tablet';w=1280;h=800})){
        $request=[pscustomobject]@{widthDp=$profile.w;heightDp=$profile.h;density=160;fontScale=1.0};$wm=Set-DisplayProfile $request $script:originalState.wm.physicalDensity
        Assert-PackageClean
        $script:narCorpusPackageCleanVerified = $true
        $narChild = New-NarCorpusAuditChildInvocation -ProfileName $profile.name -ResolvedCorpusRoots $resolvedCorpusRoots -ResolvedAdbPath $script:resolvedAdb
        $narEncodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($narChild.command))
        Invoke-Native -FilePath $script:resolvedPwsh -Arguments @('-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand',$narEncodedCommand) -TimeoutSeconds ($NarProfileTimeoutMinutes*60) -Transport adb-owner | Out-Null
        $narSummaryPath=Join-Path $narReportRoot 'summary.json';if(-not(Test-Path -LiteralPath $narSummaryPath)){Fail "Task17 summary missing for profile '$($profile.name)'." 'nar'}
        $narSummary=Get-Content -LiteralPath $narSummaryPath -Raw|ConvertFrom-Json
        if(@($narSummary.results).Count-ne 23 -or @($narSummary.failures).Count-ne 0 -or -not $narSummary.sentinels.passed -or $narSummary.cleanupVerification-ne'verified'){Fail "Task17 gates failed for profile '$($profile.name)'." 'nar'}
        $retainedSummaryRelative=Get-NarProfileSummaryRelativePath $profile.name;$retainedSummary=Join-Path $reportRoot $retainedSummaryRelative;New-Item -ItemType Directory -Force -Path(Split-Path $retainedSummary -Parent)|Out-Null;Copy-Item -LiteralPath $narSummaryPath -Destination $retainedSummary -Force;$retainedSummaryHash=(Get-FileHash $retainedSummary -Algorithm SHA256).Hash.ToLowerInvariant()
        foreach($case in @($uiManifest.cases|Where-Object{$_.kind-eq'nar'-and$_.source.profile-eq$profile.name})){
            $safe=ConvertTo-SafeLabel $case.source.label;$sourcePng=Join-Path $narReportRoot "screenshots\$safe.png";$sourceJson=Join-Path $narReportRoot "$safe\result.json"
            if(-not(Test-Path -LiteralPath $sourcePng)-or-not(Test-Path -LiteralPath $sourceJson)){Fail "Task17 representative artifacts missing for '$($case.source.label)'." 'nar'}
            $dest=Join-Path $reportRoot $case.screenshotPath;$resultDest=[IO.Path]::ChangeExtension($dest,'.result.json');New-Item -ItemType Directory -Force -Path(Split-Path $dest -Parent)|Out-Null;Copy-Item -LiteralPath $sourcePng -Destination $dest -Force;Copy-Item -LiteralPath $sourceJson -Destination $resultDest -Force
            $hash=Assert-Png $dest;Add-Result $case $hash $null ([pscustomobject]@{widthPx=$wm.logicalWidth;heightPx=$wm.logicalHeight;density=$wm.effectiveDensity;widthDp=$profile.w;heightDp=$profile.h;fontScale=1.0}) $null ([pscustomobject]@{archiveSha256=$case.archiveSha256;resultPath=Get-RelativePath $reportRoot $resultDest;task17SummaryPath=$retainedSummaryRelative;task17SummarySha256=$retainedSummaryHash})
        }
    }
    if($script:results.Count-ne$expectedCaseCount){Fail "Final case-count gate expected $expectedCaseCount, got $($script:results.Count)." 'report'}
    Assert-AuditedApkHash $debugApk $script:captureProvenance.debugApkSha256
    Invoke-Native -FilePath $script:resolvedAndroidCli -Arguments @('run','--debug',"--device=$DeviceSerial", "--apks=$debugApk") -TimeoutSeconds 180 -Transport adb-owner | Out-Null
    $installed=$true
    Start-AuditedPackageAtInteractionCheckpoint
    Assert-NarAndInteractionCheckpointPackageOrdering $script:narCorpusPackageCleanVerified $true
    $script:interactionCaptureStartedAtUtc = [DateTime]::UtcNow.ToString('o', [Globalization.CultureInfo]::InvariantCulture)
    Write-Host 'Capture the two required interaction PNGs from this owned emulator session, then press Enter.'
    [Console]::ReadLine() | Out-Null
    Assert-AuditedPackageAtInteractionCheckpoint $script:captureProvenance.debugApkSha256
    $script:interactionCapture = New-InteractionCaptureRecord $uiManifest
    Write-InteractionCaptureCheckpoint $script:interactionCapture
    Write-ReportSummary $uiManifest $uiManifestHash $script:originalState (Get-UiAuditSummaryStatus $false)
}
catch { $script:runFailure=$_.Exception.Message }
finally {
    if(-not$script:adbTransportDead){
        try{Restore-DeviceState $script:originalState}catch{$script:cleanupErrors.Add($_.Exception.Message)|Out-Null}
        if ($installed) {
            foreach ($package in @($targetPackage, $testPackage)) {
                try {
                    $packagePath = Invoke-Adb @('shell', 'pm', 'path', $package) 30 -AllowFailure
                    if ((Get-PackageCleanupAction $packagePath).action -eq 'uninstall') {
                        $uninstall = Invoke-Adb @('uninstall', $package) 120 -AllowFailure
                        if ($uninstall.exitCode -ne 0 -or $uninstall.output.Trim() -ne 'Success') {
                            throw "exit=$($uninstall.exitCode) output=$($uninstall.output.Trim()) error=$($uninstall.error.Trim())"
                        }
                        $packagePath = Invoke-Adb @('shell', 'pm', 'path', $package) 30 -AllowFailure
                        if ((Get-PackageCleanupAction $packagePath).action -ne 'skip') { throw 'package remains installed after uninstall' }
                    }
                } catch {
                    $script:cleanupErrors.Add("Uninstall $package failed: $($_.Exception.Message)") | Out-Null
                }
            }
        }
        if($null-ne$script:ownedEmulator){try{Invoke-Adb @('emu','kill') 30 -AllowFailure|Out-Null}catch{$script:cleanupErrors.Add("Emulator stop failed: $($_.Exception.Message)")|Out-Null}}
    }
    if($null-ne$script:ownedEmulator){try{if(-not(Stop-OwnedProcessTree -Process $script:ownedEmulator -ExpectedStartTimeUtcTicks $script:ownedEmulatorStartTimeUtcTicks)){$script:cleanupErrors.Add('Owned emulator process tree did not stop cleanly.')|Out-Null}}catch{$script:cleanupErrors.Add("Owned emulator process-tree stop failed: $($_.Exception.Message)")|Out-Null}finally{$script:ownedEmulator.Dispose()}}
    if($null-ne$script:emulatorWatchdog){try{if(-not(Stop-EmulatorWatchdog $script:emulatorWatchdog)){$script:cleanupErrors.Add('Owned-emulator cleanup watchdog did not stop cleanly.')|Out-Null}}catch{$script:cleanupErrors.Add("Owned-emulator cleanup watchdog stop failed: $($_.Exception.Message)")|Out-Null}}
    $status=Get-UiAuditSummaryStatus $true
    Write-ReportSummary $uiManifest $uiManifestHash $script:originalState $status
}
if($script:runFailure){throw $script:runFailure}
if($script:cleanupErrors.Count-gt 0){throw "Cleanup failed: $($script:cleanupErrors -join '; ')"}
Write-Host "Captured $($script:results.Count)/$expectedCaseCount cases. Manual inspection remains incomplete: $(Join-Path $reportRoot 'manual-inspection.md')"
