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
    [switch]$DryRun
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
$script:ownedEmulatorStartTimeUtcTicks = $null
$script:emulatorWatchdog = $null
$script:reportInitialized = $false
$script:cleanupErrors = [System.Collections.ArrayList]::new()
$script:results = [System.Collections.ArrayList]::new()
$script:runFailure = $null

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
        @{ name = 'dp-360x720-f100'; widthDp = 360; heightDp = 720; densityMode = '160'; fontScale = 1.0 },
        @{ name = 'dp-720x360-f100'; widthDp = 720; heightDp = 360; densityMode = '160'; fontScale = 1.0 },
        @{ name = 'dp-400x1000-f100'; widthDp = 400; heightDp = 1000; densityMode = '160'; fontScale = 1.0 },
        @{ name = 'dp-610x500-f100'; widthDp = 610; heightDp = 500; densityMode = '160'; fontScale = 1.0 },
        @{ name = 'dp-800x1280-f100'; widthDp = 800; heightDp = 1280; densityMode = '160'; fontScale = 1.0 },
        @{ name = 'dp-1280x800-f100'; widthDp = 1280; heightDp = 800; densityMode = '160'; fontScale = 1.0 },
        @{ name = 'dp-480x230-f100'; widthDp = 480; heightDp = 230; densityMode = '160'; fontScale = 1.0 },
        @{ name = 'dp-230x400-f100'; widthDp = 230; heightDp = 400; densityMode = '160'; fontScale = 1.0 },
        @{ name = 'dp-400x1000-f150'; widthDp = 400; heightDp = 1000; densityMode = '160'; fontScale = 1.5 },
        @{ name = 'dp-400x1000-f200'; widthDp = 400; heightDp = 1000; densityMode = '160'; fontScale = 2.0 },
        @{ name = 'native-density-phone'; widthDp = 400; heightDp = 1000; densityMode = 'native'; fontScale = 1.0 },
        @{ name = 'native-density-tablet'; widthDp = 1280; heightDp = 800; densityMode = 'native'; fontScale = 1.0 }
    )
    foreach ($profile in $liveProfiles) {
        $id = "live-$($profile.name)"
        $requested = [pscustomobject][ordered]@{
            widthDp = $profile.widthDp; heightDp = $profile.heightDp
            density = $profile.densityMode; fontScale = $profile.fontScale
            theme = 'host-current'; locale = 'host-current'; rotation = if ($profile.widthDp -gt $profile.heightDp) { 'landscape' } else { 'portrait' }
        }
        $cases.Add((New-CaseRecord -Id $id -Kind 'live' -Driver 'android-cli-live' `
            -ScreenshotPath "live/$id.png" -LayoutPath "live/$id.layout.json" -AnnotatedPath "live/$id.annotated.png" `
            -Requested $requested -ExpectedInvariants @('single-ghost-stage', 'stage-contained', 'bottom-aligned', 'no-clipped-content'))) | Out-Null
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

    $manifest = [pscustomobject][ordered]@{
        schemaVersion = 1
        caseSetVersion = '2026-08-04.1'
        caseCount = $cases.Count
        generatedBy = 'scripts/run-ui-visual-audit.ps1'
        cases = @($cases)
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

function Assert-UiAuditManifest([object]$Manifest) {
    if ($Manifest.schemaVersion -ne 1 -or $Manifest.caseSetVersion -ne '2026-08-04.1') { Fail 'Unexpected UI audit manifest version.' }
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

function Get-GhostStageBoundsFromUiAutomatorXmlText([string]$XmlText) {
    try { [xml]$document=$XmlText }
    catch { Fail "Invalid UiAutomator XML: $($_.Exception.Message)" 'layout' }
    $nodes=@($document.SelectNodes("//*[@resource-id='ghost-stage']"))
    if ($nodes.Count -ne 1) { Fail "Expected exactly one UiAutomator ghost-stage resource node, found $($nodes.Count)." 'layout' }
    return ConvertFrom-LayoutBounds ([string]$nodes[0].bounds)
}

function Get-WindowBoundsFromUiAutomatorXmlText([string]$XmlText) {
    try { [xml]$document=$XmlText }
    catch { Fail "Invalid UiAutomator XML: $($_.Exception.Message)" 'layout' }
    $rootNode=$document.SelectSingleNode('/hierarchy/node[1]')
    if ($null -eq $rootNode) { Fail 'UiAutomator layout has no root window node.' 'layout' }
    return ConvertFrom-LayoutBounds ([string]$rootNode.bounds)
}

function Find-LayoutGhostStageNodes([object]$Value) {
    $found = [System.Collections.ArrayList]::new()
    function Visit([object]$Node) {
        if ($null -eq $Node) { return }
        if ($Node -is [string] -or $Node -is [ValueType]) { return }
        if ($Node -is [Collections.IDictionary]) {
            $resource = $null
            foreach ($key in @('resourceId','resource-id','id','testTag')) { if ($Node.Contains($key)) { $resource = [string]$Node[$key]; break } }
            if ($resource -eq 'ghost-stage' -or $resource -match '(^|[:/])ghost-stage$') { $found.Add($Node) | Out-Null }
            foreach ($key in $Node.Keys) { Visit $Node[$key] }
            return
        }
        $resource = $null
        foreach ($key in @('resourceId','resource-id','id','testTag')) { if (Test-Property $Node $key) { $resource = [string]$Node.$key; break } }
        if ($resource -eq 'ghost-stage' -or $resource -match '(^|[:/])ghost-stage$') { $found.Add($Node) | Out-Null }
        if ($Node -is [Collections.IEnumerable]) { foreach ($item in $Node) { Visit $item }; return }
        foreach ($property in $Node.PSObject.Properties) { Visit $property.Value }
    }
    Visit $Value
    return ,@($found)
}

function Get-GhostStageBoundsFromLayout([string]$LayoutPath, [string]$UiAutomatorPath) {
    $json = Get-Content -LiteralPath $LayoutPath -Raw | ConvertFrom-Json
    $nodes = @(Find-LayoutGhostStageNodes $json)
    if ($nodes.Count -ne 1) { Fail "Expected exactly one ghost-stage resource node, found $($nodes.Count)." 'layout' }
    $node = $nodes[0]
    $bounds = $null
    foreach ($name in @('bounds','boundsInScreen','visibleBounds')) { if (Test-Property $node $name) { $bounds = $node.$name; break } }
    if ($null -eq $bounds) {
        if ([string]::IsNullOrWhiteSpace($UiAutomatorPath) -or -not(Test-Path -LiteralPath $UiAutomatorPath -PathType Leaf)) { Fail 'ghost-stage node has no supported bounds field and no UiAutomator layout capture is available.' 'layout' }
        return Get-GhostStageBoundsFromUiAutomatorXmlText (Get-Content -LiteralPath $UiAutomatorPath -Raw)
    }
    return ConvertFrom-LayoutBounds $bounds
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
[IO.File]::WriteAllText(`$readyPath, "`$hostProcessId|`$hostStartTimeUtcTicks|`$emulatorProcessId|`$emulatorStartTimeUtcTicks", [Text.UTF8Encoding]::new(`$false))
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

function Start-EmulatorWatchdog([Diagnostics.Process]$EmulatorProcess, [long]$EmulatorStartTimeUtcTicks) {
    $host=[Diagnostics.Process]::GetCurrentProcess()
    $readyPath=Join-Path ([IO.Path]::GetTempPath()) ("nanidroid-ui-audit-watchdog-$([Guid]::NewGuid().ToString('N')).ready")
    $watchdog=$null
    $watchdogStartTimeUtcTicks=0
    try {
        $invocation=New-EmulatorWatchdogInvocation -HostProcessId $host.Id -HostStartTimeUtcTicks $host.StartTime.ToUniversalTime().Ticks -EmulatorProcessId $EmulatorProcess.Id -EmulatorStartTimeUtcTicks $EmulatorStartTimeUtcTicks -ReadyPath $readyPath
        $launch=[Diagnostics.ProcessStartInfo]::new(); $launch.FileName=$script:resolvedPwsh; $launch.UseShellExecute=$true; $launch.WindowStyle=[Diagnostics.ProcessWindowStyle]::Hidden
        $launch.Arguments=(@($invocation.arguments | ForEach-Object { ConvertTo-WindowsCommandLineArgument ([string]$_) }) -join ' ')
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
                break
            }
            Start-Sleep -Milliseconds 100
        } while ((Get-Date) -lt $deadline)
        Fail 'Owned-emulator cleanup watchdog did not establish an exact identity-bound handshake.' 'watchdog'
    } finally {
        $host.Dispose()
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
    param([string]$FilePath, [string[]]$Arguments, [int]$TimeoutSeconds = 120, [switch]$AllowFailure, [ValidateSet('normal','adb','adb-owner')][string]$Transport = 'normal')
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
    return ,@($resolvedRoots)
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

function Write-ReportSummary([object]$Manifest, [string]$ManifestHash, [object]$OriginalState, [string]$Status) {
    if (-not $script:reportInitialized) { return }
    $summary=[pscustomobject][ordered]@{ schemaVersion=1; caseSetVersion=$Manifest.caseSetVersion; manifestSha256=$ManifestHash; expectedCaseCount=$expectedCaseCount; resultCount=$script:results.Count; status=$Status; failure=$script:runFailure; deviceSerial=$DeviceSerial; avdName=$AvdName; snapshotName=$SnapshotName; originalState=$OriginalState; cleanupErrors=@($script:cleanupErrors); manualInspectionComplete=$false; results=@($script:results) }
    $summary | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $reportRoot 'summary.json') -Encoding UTF8
    $lines=@('# UI visual audit summary','',"- Status: $Status","- Case set: $($Manifest.caseSetVersion)","- Manifest SHA-256: $ManifestHash","- Cases expected: $expectedCaseCount","- Results captured: $($script:results.Count)",'- Manual inspection complete: false','', '| Case | Driver | Screenshot SHA-256 | Layout SHA-256 | Requested | Measured | Stage | Result | Defect |','| --- | --- | --- | --- | --- | --- | --- | --- | --- |')
    foreach ($case in $Manifest.cases) { $row=@($script:results | Where-Object id -eq $case.id | Select-Object -First 1); $r=if($row.Count){$row[0]}else{$null}; $lines += "| $($case.id) | $($case.sourceDriver) | $(if($r){$r.screenshotSha256}else{''}) | $(if($r){$r.layoutSha256}else{''}) | $($case.requested | ConvertTo-Json -Compress) | $(if($r){$r.measured}else{''}) | $(if($r){$r.stage}else{''}) |  |  |" }
    $lines | Set-Content -LiteralPath (Join-Path $reportRoot 'summary.md') -Encoding UTF8
}

function New-ManualInspectionTemplate([object]$Manifest, [string]$ManifestHash) {
    $path=Join-Path $reportRoot 'manual-inspection.md'
    if (Test-Path -LiteralPath $path) {
        $existing=Get-Content -LiteralPath $path -Raw
        if ($existing -match '(?im)^Audit status:\s*complete\s*$' -or $existing -match '(?im)^\|[^|]+\|[^|]+\|[^|]+\|\s*(pass|fail)\s*\|') { Fail 'Refusing to overwrite a completed manual-inspection checklist.' 'report' }
        return
    }
    $lines=@('# UI visual audit manual inspection','', 'Audit status: incomplete', '', "Manifest SHA-256: $ManifestHash", "Required case count: $expectedCaseCount", '', 'Automated capture is not manual inspection. Open every PNG and fill Result and Defect.', '', '| Case | Artifact SHA-256 | Requested / measured window and stage | Density / font / theme / locale | Expected invariants | Result | Defect |','| --- | --- | --- | --- | --- | --- | --- |')
    foreach($case in $Manifest.cases){
        $requestedWindow = if ((Test-Property $case.requested 'widthDp') -and (Test-Property $case.requested 'heightDp')) { "$($case.requested.widthDp)x$($case.requested.heightDp)" } else { [string]$case.requested.display }
        $lines += "| $($case.id) |  | $requestedWindow /  | $($case.requested.density) / $($case.requested.fontScale) / $($case.requested.theme) / $($case.requested.locale) | $($case.expectedInvariants -join ', ') |  |  |"
    }
    $lines += @('', '## Required interaction checklist','', '- [ ] Touch named collisions and generic transparent canvas.','- [ ] Mouse primary single-click and double-click.','- [ ] Scroll/click bubbles and reopen choices.','- [ ] Tab, Shift-Tab, arrows, Page Up, Page Down, Enter, Space, Escape, and D-pad.','- [ ] Toggle chrome only through empty stage or its labeled semantic action.','- [ ] Open and close bottom-sheet, side-panel, and full-modal debug presentations.','- [ ] Rotate, resize, and recreate the Activity.','- [ ] TalkBack plus Switch Access or Voice Access; merged and unmerged semantics.','- [ ] Invoke collision custom actions and verify focus recovery.','- [ ] Exercise input IME on Snake and Otacon.','- [ ] Verify passive stall prompt behavior.','- [ ] Verify exact SHIORI coordinate, scope, identifier, button, and source fields; no bubble/surface/chrome leakage.')
    $lines | Set-Content -LiteralPath $path -Encoding UTF8
}

function Add-Result([object]$Case, [string]$ScreenshotSha, [string]$LayoutSha, [object]$Measured, [object]$Stage, [object]$SourceEvidence) {
    $script:results.Add([pscustomobject][ordered]@{ id=$Case.id; sourceDriver=$Case.sourceDriver; screenshotPath=$Case.screenshotPath; screenshotSha256=$ScreenshotSha; layoutPath=$Case.layoutPath; layoutSha256=$LayoutSha; annotatedPath=$Case.annotatedPath; requested=$Case.requested; measured=$Measured; stage=$Stage; theme=$script:originalState.theme; locale=$script:originalState.locale; expectedInvariants=$Case.expectedInvariants; sourceEvidence=$SourceEvidence; manualResult=$null; defect=$null }) | Out-Null
}

function Invoke-DryRunSelfTest([object]$Manifest, [string]$ManifestHash) {
    Assert-UiAuditManifest $Manifest
    $again=New-UiAuditManifest; $againHash=Get-StringSha256 (ConvertTo-CanonicalManifestJson $again)
    if ($ManifestHash -ne $againHash) { Fail 'Manifest generation is not deterministic.' 'dry-run' }
    $bounds=ConvertFrom-LayoutBounds '[1,2][3,5]'; if ($bounds.width -ne 2 -or $bounds.height -ne 3) { Fail 'Bounds parser probe failed.' 'dry-run' }
    foreach($bad in @('..\escape.png','C:\escape.png','bad path.png')) { $failed=$false; try { Assert-SafeReportRelativePath $bad } catch { $failed=$true }; if(-not $failed){Fail "Unsafe path probe unexpectedly passed '$bad'." 'dry-run'} }
    $quoted=ConvertTo-WindowsCommandLineArgument 'label with spaces'; if($quoted -ne '"label with spaces"'){Fail 'Argument quoting probe failed.' 'dry-run'}
    $timeout=[Diagnostics.Stopwatch]::StartNew(); $proc=Invoke-Native -FilePath (Get-Process -Id $PID).Path -Arguments @('-NoProfile','-Command','exit 0') -TimeoutSeconds 20; $timeout.Stop(); if($proc.exitCode -ne 0 -or $timeout.Elapsed.TotalSeconds -ge 20){Fail 'Process/timeout helper probe failed.' 'dry-run'}
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
    $transportBefore=$script:adbTransportDead; if(-not ( -not $transportBefore)){Fail 'Transport-dead initial probe failed.' 'dry-run'}
    $pngSig=[BitConverter]::ToString([byte[]](0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)); if($pngSig -ne '89-50-4E-47-0D-0A-1A-0A'){Fail 'PNG signature probe failed.' 'dry-run'}
    $roundTrip=($Manifest | ConvertTo-Json -Depth 16 | ConvertFrom-Json); Assert-UiAuditManifest $roundTrip
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
    $xmlBounds=Get-GhostStageBoundsFromUiAutomatorXmlText '<hierarchy><node resource-id="ghost-stage" bounds="[1,2][8,9]" /></hierarchy>'
    if ($xmlBounds.width -ne 7 -or $xmlBounds.height -ne 7) { Fail 'UiAutomator bounds probe failed.' 'dry-run' }
    $windowBounds=Get-WindowBoundsFromUiAutomatorXmlText '<hierarchy><node bounds="[0,0][720,360]" /></hierarchy>'
    if ($windowBounds.width -ne 720 -or $windowBounds.height -ne 360) { Fail 'UiAutomator window-bounds probe failed.' 'dry-run' }
    if ((Get-DisplayOrientationFromDump "header`nmCurrentOrientation=3`nfooter") -ne '3') { Fail 'Display-orientation parser probe failed.' 'dry-run' }
    $logical=Get-LogicalDisplaySizeFromWindowDump 'init=1080x2400 base=720x360 cur=720x360 app=720x360'
    if ($logical.width -ne 720 -or $logical.height -ne 360) { Fail 'Logical display-size parser probe failed.' 'dry-run' }
    if ((Select-AuditLocale -PersistLocale '' -ProductLocale 'en-US' -ActivityConfiguration '') -ne 'en-US') { Fail 'Blank persisted-locale product fallback probe failed.' 'dry-run' }
    if ((Select-AuditLocale -PersistLocale '' -ProductLocale '' -ActivityConfiguration 'config: { locales=[en-GB,fr-FR] }') -ne 'en-GB') { Fail 'Blank persisted/product locale configuration fallback probe failed.' 'dry-run' }
    if (-not(Test-Path -LiteralPath $pwshProbe -PathType Leaf)) { Fail 'PowerShell 7 resolver probe failed.' 'dry-run' }
    if ($NarProfileTimeoutMinutes -le ($BuildTimeoutMinutes + (23 * 5))) { Fail 'NAR profile parent timeout does not exceed Task 17 child deadlines.' 'dry-run' }
    if ((Get-NarProfileSummaryRelativePath 'compact-landscape') -ne 'nar\compact-landscape\task17-summary.json') { Fail 'NAR retained-summary path probe failed.' 'dry-run' }
    $narManifest=Get-Content -LiteralPath (Join-Path $repoRoot $ManifestPath) -Raw | ConvertFrom-Json
    foreach($rep in (Get-UiAuditRepresentatives)){if(@($narManifest.entries|Where-Object{$_.label -ceq $rep.label -and $_.sha256 -ceq $rep.sha256}).Count -ne 1){Fail "Dry-run NAR label/SHA probe failed for '$($rep.label)'." 'dry-run'}}
    Write-Host "Dry-run passed: schemaVersion=$($Manifest.schemaVersion), caseSetVersion=$($Manifest.caseSetVersion), cases=$($Manifest.caseCount), sha256=$ManifestHash"
    Write-Host 'Dry-run made no build, device, emulator, or report mutations.'
}

Set-Location -LiteralPath $repoRoot
$uiManifest=New-UiAuditManifest
$canonicalManifest=ConvertTo-CanonicalManifestJson $uiManifest
$uiManifestHash=Get-StringSha256 $canonicalManifest

if ($DryRun) { Invoke-DryRunSelfTest $uiManifest $uiManifestHash; return }

$script:originalState=$null
$installed=$false
try {
    Assert-UiAuditManifest $uiManifest
    $narManifest=Get-Content -LiteralPath (Join-Path $repoRoot $ManifestPath) -Raw | ConvertFrom-Json
    $resolvedCorpusRoots=Assert-CorpusInputs $narManifest
    $manualPath=Join-Path $reportRoot 'manual-inspection.md'
    if(Test-Path -LiteralPath $manualPath){$existing=Get-Content -LiteralPath $manualPath -Raw;if($existing -match '(?im)^Audit status:\s*complete\s*$' -or $existing -match '(?im)^\|[^|]+\|[^|]+\|[^|]+\|\s*(pass|fail)\s*\|'){Fail 'Refusing completed checklist overwrite.' 'report'}}

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

    $gradle=Join-Path $repoRoot 'gradlew.bat'; Invoke-Native -FilePath $gradle -Arguments @('assembleDebug','validateDebugScreenshotTest','--console=plain') -TimeoutSeconds ($BuildTimeoutMinutes*60) | Out-Null
    $debugApk=Resolve-DebugApk

    New-Item -ItemType Directory -Force -Path $reportRoot | Out-Null; $script:reportInitialized=$true
    [IO.File]::WriteAllText((Join-Path $reportRoot 'case-manifest.json'), $canonicalManifest, [Text.UTF8Encoding]::new($false))
    Set-Content -LiteralPath (Join-Path $reportRoot 'case-manifest.sha256') -Value $uiManifestHash -Encoding Ascii
    New-ManualInspectionTemplate $uiManifest $uiManifestHash

    $launch=[Diagnostics.ProcessStartInfo]::new();$launch.FileName=$script:resolvedEmulator;$launch.UseShellExecute=$false;$launch.CreateNoWindow=$true
    $launchArgs=@('-avd',$AvdName,'-snapshot',$SnapshotName,'-no-snapshot-save','-read-only','-port',[string]$port)
    if($null -ne $launch.PSObject.Properties['ArgumentList']){foreach($arg in $launchArgs){[void]$launch.ArgumentList.Add($arg)}}else{$launch.Arguments=(@($launchArgs|ForEach-Object{ConvertTo-WindowsCommandLineArgument $_}) -join ' ')}
    $script:ownedEmulator=[Diagnostics.Process]::Start($launch); if($null -eq $script:ownedEmulator){Fail 'Failed to launch owned emulator process.' 'device'}
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
        $uiAutomatorXmlText=Get-Content -LiteralPath $uiAutomatorLayout -Raw;$stage=Get-GhostStageBoundsFromLayout $layout $uiAutomatorLayout;$windowBounds=Get-WindowBoundsFromUiAutomatorXmlText $uiAutomatorXmlText
        if($windowBounds.width-ne$wm.logicalWidth-or$windowBounds.height-ne$wm.logicalHeight){Fail "UiAutomator window $($windowBounds.width)x$($windowBounds.height) differs from settled logical display $($wm.logicalWidth)x$($wm.logicalHeight)." 'layout'}
        $shotHash=Assert-Png $shot;$null=Assert-Png $annotated;$layoutHash=(Get-FileHash -LiteralPath $layout -Algorithm SHA256).Hash.ToLowerInvariant();$uiAutomatorLayoutHash=(Get-FileHash -LiteralPath $uiAutomatorLayout -Algorithm SHA256).Hash.ToLowerInvariant()
        $measured=[pscustomobject]@{widthPx=$windowBounds.width;heightPx=$windowBounds.height;density=$wm.effectiveDensity;widthDp=[Math]::Round($windowBounds.width*160/$wm.effectiveDensity,2);heightDp=[Math]::Round($windowBounds.height*160/$wm.effectiveDensity,2);fontScale=$case.requested.fontScale}
        Add-Result $case $shotHash $layoutHash $measured $stage ([pscustomobject]@{apkSha256=(Get-FileHash $debugApk -Algorithm SHA256).Hash.ToLowerInvariant();uiAutomatorLayoutPath=Get-RelativePath $reportRoot $uiAutomatorLayout;uiAutomatorLayoutSha256=$uiAutomatorLayoutHash})
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
        $narInvocation = [pscustomobject]@{ script=(Join-Path $scriptRoot 'run-nar-corpus-audit.ps1'); device=$DeviceSerial; manifest=$ManifestPath; adb=$script:resolvedAdb; roots=@($resolvedCorpusRoots) }
        $narPayload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($narInvocation | ConvertTo-Json -Depth 4 -Compress)))
        $narCommand = '$p=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(''' + $narPayload + '''))|ConvertFrom-Json; & $p.script -DeviceSerial $p.device -ManifestPath $p.manifest -AdbPath $p.adb -CorpusRoots @($p.roots)'
        $narEncodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($narCommand))
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
}
catch { $script:runFailure=$_.Exception.Message }
finally {
    if($null-ne$script:emulatorWatchdog){try{if(-not(Stop-EmulatorWatchdog $script:emulatorWatchdog)){$script:cleanupErrors.Add('Owned-emulator cleanup watchdog did not stop cleanly.')|Out-Null}}catch{$script:cleanupErrors.Add("Owned-emulator cleanup watchdog stop failed: $($_.Exception.Message)")|Out-Null}}
    if(-not$script:adbTransportDead){
        try{Restore-DeviceState $script:originalState}catch{$script:cleanupErrors.Add($_.Exception.Message)|Out-Null}
        if($installed){foreach($package in @($targetPackage,$testPackage)){try{Invoke-Adb @('uninstall',$package) 120 -AllowFailure|Out-Null}catch{$script:cleanupErrors.Add("Uninstall $package failed: $($_.Exception.Message)")|Out-Null}}}
        if($null-ne$script:ownedEmulator){try{Invoke-Adb @('emu','kill') 30 -AllowFailure|Out-Null}catch{$script:cleanupErrors.Add("Emulator stop failed: $($_.Exception.Message)")|Out-Null}}
    }
    if($null-ne$script:ownedEmulator){try{if(-not(Stop-OwnedProcessTree -Process $script:ownedEmulator -ExpectedStartTimeUtcTicks $script:ownedEmulatorStartTimeUtcTicks)){$script:cleanupErrors.Add('Owned emulator process tree did not stop cleanly.')|Out-Null}}catch{$script:cleanupErrors.Add("Owned emulator process-tree stop failed: $($_.Exception.Message)")|Out-Null}finally{$script:ownedEmulator.Dispose()}}
    $status=if($script:runFailure-or$script:cleanupErrors.Count-gt 0){'failed'}elseif($script:results.Count-eq$expectedCaseCount){'captured-awaiting-manual-inspection'}else{'failed'}
    Write-ReportSummary $uiManifest $uiManifestHash $script:originalState $status
}
if($script:runFailure){throw $script:runFailure}
if($script:cleanupErrors.Count-gt 0){throw "Cleanup failed: $($script:cleanupErrors -join '; ')"}
Write-Host "Captured $($script:results.Count)/$expectedCaseCount cases. Manual inspection remains incomplete: $(Join-Path $reportRoot 'manual-inspection.md')"
