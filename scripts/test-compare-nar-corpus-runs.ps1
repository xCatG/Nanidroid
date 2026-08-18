param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$comparatorPath = Join-Path $PSScriptRoot 'compare-nar-corpus-runs.ps1'
$manifestPath = Join-Path $repoRoot 'docs\testing\nar-corpus-manifest.json'
$contractPath = Join-Path $repoRoot 'docs\testing\nar-corpus-comparison-contract.json'
$fixtureRoot = Join-Path $repoRoot 'build\reports\nar-corpus-comparator-tests'
$expectedFixtureRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'build\reports\nar-corpus-comparator-tests'))
$resolvedFixtureRoot = [IO.Path]::GetFullPath($fixtureRoot)
if (-not $resolvedFixtureRoot.Equals($expectedFixtureRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Unsafe comparator fixture root: $resolvedFixtureRoot"
}

$comparatorSource = Get-Content -LiteralPath $comparatorPath -Raw
$hostTestSource = Get-Content -LiteralPath $PSCommandPath -Raw
$runnerSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'run-nar-corpus-audit.ps1') -Raw
$unsupportedPs70ApiPatterns = @(
    [regex]::Escape('[Convert]::To' + 'HexString'),
    [regex]::Escape('[Security.Cryptography.SHA256]::Hash' + 'Data')
)
function Test-ContainsUnsupportedPs70Api([string]$Source) {
    foreach ($pattern in $unsupportedPs70ApiPatterns) {
        if ($Source -match $pattern) { return $true }
    }
    return $false
}
$compatibilitySources = @($comparatorSource, $hostTestSource, $runnerSource)
$containsUnsupportedPs70Api = @($compatibilitySources | Where-Object { Test-ContainsUnsupportedPs70Api $_ }).Count -gt 0
if ($comparatorSource -match 'ConvertFrom-Json\s+-DateKind' -or $hostTestSource -match 'ConvertFrom-Json\s+-DateKind' -or
    $containsUnsupportedPs70Api -or
    $comparatorSource -notmatch '(?m)^#requires -Version 7\.0$' -or $comparatorSource -notmatch 'System\.Text\.Json\.JsonDocument') {
    throw 'Corpus comparator host sources are not statically compatible with the documented PowerShell 7.0 floor.'
}
Write-Host 'PASS: PowerShell 7.0-compatible strict JSON parser contract'

function Get-StringSha256([string]$Value) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))
        return ([BitConverter]::ToString($bytes) -replace '-', '').ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
    }
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$manifestSha = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
$baseCommit = '1111111111111111111111111111111111111111'
$candidateCommit = '2222222222222222222222222222222222222222'
$baseDebugSha = 'a' * 64
$candidateDebugSha = 'b' * 64
$harnessCommit = '3333333333333333333333333333333333333333'
$harnessTree = '4444444444444444444444444444444444444444'
$runnerSha = 'c' * 64
$instrumentationSha = 'd' * 64
$testApkSha = 'e' * 64
$reviewedSentinelNameCount = 139
$reviewedSentinelNamesSha256 = '490ef9ecb8d52e7c1ca704fa8bd9dc4194b39d064065040585ff203befb3a74f'
$runnerSentinelNamePattern = 'Add-Sentinel(?:Nested)?Check\s+-Accumulator\s+\$globalSentinels\s+-Name\s+''([^'']+)'''
$sentinelFixtureNames = @(
    [regex]::Matches((Get-Content -Raw -LiteralPath (Join-Path $repoRoot 'scripts\run-nar-corpus-audit.ps1')), $runnerSentinelNamePattern) |
        ForEach-Object { $_.Groups[1].Value } |
        Where-Object { $_ -cne 'slice2-watchdog-expected-stage-geometry' }
)
$sentinelFixtureNameSet = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($name in $sentinelFixtureNames) { [void]$sentinelFixtureNameSet.Add($name) }
[string[]]$sentinelFixtureSortedNames = @($sentinelFixtureNames)
[Array]::Sort($sentinelFixtureSortedNames, [StringComparer]::Ordinal)
$sentinelFixtureCanonical = $sentinelFixtureSortedNames -join "`n"
$sentinelFixtureSha256 = Get-StringSha256 $sentinelFixtureCanonical
if ($sentinelFixtureNames.Count -ne $reviewedSentinelNameCount -or $sentinelFixtureNameSet.Count -ne $reviewedSentinelNameCount -or $sentinelFixtureSha256 -cne $reviewedSentinelNamesSha256) {
    throw 'Synthetic fixture sentinel names do not match the reviewed default runner sentinel set.'
}
$preservedEvidenceRoot = Join-Path $repoRoot 'build\reports\nar-corpus'
$preservedSummaryPath = Join-Path $preservedEvidenceRoot 'summary.json'
if (Test-Path -LiteralPath $preservedSummaryPath -PathType Leaf) {
    $preservedSummary = Get-Content -LiteralPath $preservedSummaryPath -Raw | ConvertFrom-Json
    if (@($preservedSummary.results).Count -ne 23 -or [string]$preservedSummary.runId -notmatch '^[0-9a-f]{32}$') {
        throw 'Preserved successful corpus summary does not have the reviewed 23-row runner shape.'
    }
    foreach ($row in @($preservedSummary.results)) {
        $safeLabel = [string]$row.safeLabel
        $rawPath = Join-Path $preservedEvidenceRoot "$safeLabel\result.json"
        $raw = Get-Content -LiteralPath $rawPath -Raw | ConvertFrom-Json
        $expectedPathPattern = '^/data/(?:user/0|data)/com\.cattailsw\.nanidroid/cache/nar-corpus-host/' + [regex]::Escape([string]$preservedSummary.runId) + '/' + [regex]::Escape($safeLabel) + '/nanidroid-corpus\.nar$'
        if ([string]$raw.narCorpusPath -notmatch $expectedPathPattern) {
            throw "Preserved raw result '$($row.label)' narCorpusPath is not the exact runner shape."
        }
    }
    Write-Host 'PASS: preserved successful corpus narCorpusPath shape'
}
else {
    Write-Host 'SKIP: preserved successful corpus evidence root is unavailable'
}
$twoElfBase = '\1\s[19]\n\n[half]\_w[18]\0\s[103]旅人さん…。\_w[36]\w8\n\n[half]\_w[18]\1\c謝りに来たの？\w8\_w[126]\n\n[half]\_w[18]\0\s[101]え…。\_w[54]\w8\nそ、\_w[36]そんな…私こそ、\_w[144]急に帰ったりして…\w8ごめんね。\_w[252]\w8\n怒ってないから…。\_w[162]\w8\nだけど、\_w[72]もうあんなエッチな事はしないでね…\w8\s[104]お願い。\_w[378]\e'
$twoElfCandidate = '\1\s[19]\n\n[half]\_w[18]\0\s[103]あ…旅人さん…。\_w[72]\w8\nご、\_w[36]ごめんなさい！\w8\_w[126]\n逃げちゃったりして。\_w[180]\w8\n\n[half]\_w[18]\1\cソフィが謝る事じゃないわよ。\_w[252]\w8\n\n[half]\_w[18]\0\s[101]旅人さん…\w8もう、\_w[72]あんな事しないでね。\_w[180]\w8\n私、\_w[36]顔から火が出ちゃいそうなくらい、\_w[288]恥ずかしかったのよ。\_w[180]\w8\n\n[half]\_w[18]\1\n[half]\_w[18]\0\s[106]･\w2･\w2･\w2･\w2･\w2･\w2･\w2･\w2\s[100]はい、\_w[126]おしまい。\_w[90]\w8\n旅人さんは何も見なかった、\_w[162]ね？\w8\_w[36]\e'
$dialogueValues = @{
    '2elf-2.46' = $twoElfBase
    'LOBO' = '\1\s[10]\0\s[0]\1\s[-1]\0Listen here, you little bruin, you listen to me.\w8\w8\w8 The bealusi that pardons, that is your fortune.'
    'Snake and Otacon V1.2.1' = '\0\s[0]\1\s[10]\0\s[5]Miss me?\e'
    'Snake and Otacon V1.3.1' = "\0\s[0]\1\s[10]\0\s[0]Evening. Getting pretty late now. Don't strain yourself, .\1\w8\s[10]Yeah, don't overwork yourself!\e"
    'Snake_Otacon_1.3.1b' = "\0\s[0]\1\s[10]\1\s[10]Have you considered going to bed soon, ? It's getting kind of late...\e"
    'Watchdog Bancho' = '\1\s[10]\0\s[0]\0\s[0]Hey, long time no see!'
}
$loboReviewedAlternative = '\1\s[10]\0\s[0]\1\s[-1]\0Listen here, you little caladbolg, you listen to me.\w8\w8\w8 The sulfur that spits, that is your fortune.'
$snakeV121ReviewedAlternative = '\0\s[0]\1\s[10]\0\s[4]\1\s[13]Yikes. That last mission left you in pretty bad shape, Snake.\1\s[18]Is there any more I can do?\0\s[4]You''ve already done all you can.\1\s[14]\n\n[half]Alright. If you need anything.\n\n Er. Morning, . Or evening, I guess? Mh..\e'
$snakeLateReviewedAlternative = '\0\s[0]\1\s[10]\0\s[0]Your time of night, huh, Otacon?\1\w8\s[10]No kidding, this is when I get my best work done!\e'
$rawSourceLabels = @(
    '2elf-2.46',
    'Big Red Button',
    'Earthquake Rescue Duo',
    'LOBO',
    'Nanika Atsume 1.0.0',
    'Nanika Atsume 1.0.1',
    'Nanika Atsume silent_ALPHA',
    'Snake and Otacon V1.2.1',
    'Snake and Otacon V1.3.1',
    'Snake and Otacon V1.3.2',
    'Snake_Otacon_1.2.1b',
    'Snake_Otacon_1.3.1b',
    'tewire-sen',
    'Watchdog Bancho',
    'Yes Man-2.1.1'
)

function ConvertTo-SafeLabel([string]$Label) {
    $safe = ($Label -replace '[^A-Za-z0-9._-]', '-').Trim('-')
    if ([string]::IsNullOrEmpty($safe)) { return 'archive' }
    return $safe
}

function Write-Json([string]$Path, [object]$Value) {
    $Value | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $Path -Encoding utf8
}

function Get-Json([string]$Path) {
    Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function Set-SentinelContractCountToken([string]$Path, [string]$Token) {
    $text = Get-Content -Raw -LiteralPath $Path
    $updated = $text -replace '"count": 139', ('"count": ' + $Token)
    if ($updated -ceq $text) { throw 'Unable to replace the sentinel contract count token.' }
    Set-Content -LiteralPath $Path -Value $updated -Encoding utf8
}

function New-ReportFixture {
    param(
        [string]$Root,
        [string]$RunId,
        [string]$ProductionCommit,
        [string]$DebugSha,
        [string]$StartedAt
    )

    New-Item -ItemType Directory -Force -Path $Root, (Join-Path $Root 'screenshots') | Out-Null
    $rows = [Collections.Generic.List[object]]::new()
    foreach ($entry in $manifest.entries) {
        $label = [string]$entry.label
        $safeLabel = ConvertTo-SafeLabel $label
        $value = if ($dialogueValues.ContainsKey($label)) {
            $dialogueValues[$label]
        }
        elseif ($label -ceq 'Yes Man-2.1.1') {
            "/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/$RunId/Yes-Man-2.1.1/probe-install/file.nar"
        }
        else {
            "stable:$label"
        }
        $raw = [pscustomobject][ordered]@{
            schemaVersion = '1'
            label = $label
            sha256 = [string]$entry.sha256
            narCorpusPath = "/data/user/0/com.cattailsw.nanidroid/cache/nar-corpus-host/$RunId/$safeLabel/nanidroid-corpus.nar"
            passed = $true
            classification = 'compatible'
            dialogueProbe = [pscustomobject]@{ outcome = 'success'; value = $value }
            evidence = [pscustomobject]@{
                stable = $true
            }
            cleanup = [pscustomobject]@{ remainingTestOwnedPaths = @(); hostVerified = $true }
        }
        if ($rawSourceLabels -contains $label) {
            $raw.evidence | Add-Member -NotePropertyName sourceSyntax -NotePropertyValue ([pscustomobject]@{ scanRoot = "/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/$RunId/$safeLabel/source" })
            $raw | Add-Member -NotePropertyName sakura -NotePropertyValue ([pscustomobject]@{ source = "/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/$RunId/$safeLabel/sakura" })
            $raw | Add-Member -NotePropertyName kero -NotePropertyValue ([pscustomobject]@{ source = "/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/$RunId/$safeLabel/kero" })
        }
        foreach ($requiredName in @($entry.requiredEvidence)) {
            if ($null -eq $raw.PSObject.Properties[[string]$requiredName]) {
                $raw | Add-Member -NotePropertyName ([string]$requiredName) -NotePropertyValue "evidence:$requiredName"
            }
        }
        $payload = [pscustomobject][ordered]@{}
        foreach ($requiredName in @($entry.requiredEvidence)) {
            $payload | Add-Member -NotePropertyName ([string]$requiredName) -NotePropertyValue $raw.([string]$requiredName)
        }
        $resultDir = Join-Path $Root $safeLabel
        New-Item -ItemType Directory -Force -Path $resultDir | Out-Null
        Write-Json -Path (Join-Path $resultDir 'result.json') -Value $raw
        [IO.File]::WriteAllBytes((Join-Path $Root "screenshots\$safeLabel.png"), [Text.Encoding]::UTF8.GetBytes("screenshot:$label"))
        $rows.Add([pscustomobject][ordered]@{
            label = $label
            safeLabel = $safeLabel
            sha256 = [string]$entry.sha256
            runId = $RunId
            passed = $true
            startedAt = $StartedAt
            finishedAt = $StartedAt
            classification = 'compatible'
            requiredEvidence = @($entry.requiredEvidence)
            requiredEvidencePayload = $payload
            resultPath = "/sdcard/Android/data/com.cattailsw.nanidroid/files/nar-corpus/$safeLabel/result.json"
            screenshotPath = "/sdcard/Android/data/com.cattailsw.nanidroid/files/nar-corpus/$safeLabel/screenshot.png"
            crashLogPath = (Join-Path $Root "$safeLabel\crash-log.txt")
            status = 'ok'
            output = "Time: 1.0 run=$RunId"
            error = ''
            cleanup = [pscustomobject]@{ remainingTestOwnedPaths = @(); hostVerified = $true }
            postCleanupPrivateSnapshot = @()
            postCleanupOutputSnapshot = @()
            postCleanupTmpSnapshot = @()
        })
    }

    $twoElfValue = [string]$dialogueValues['2elf-2.46']
    $sentinelChecks = @($sentinelFixtureNames | ForEach-Object {
        if ($_ -ceq 'slice2-2elf-dialogue-value-nonblank') {
            [pscustomobject]@{ name = $_; passed = $true; expected = 'nonblank'; observed = $twoElfValue }
        }
        else {
            [pscustomobject]@{ name = $_; passed = $true; expected = 'fixture'; observed = 'fixture' }
        }
    })
    $summary = [pscustomobject][ordered]@{
        runId = $RunId
        manifest = 'nar-corpus-manifest.json'
        manifestSha256 = $manifestSha
        startedAt = $StartedAt
        finishedAt = $StartedAt
        durationSeconds = 23
        production = [pscustomobject]@{ commit = $ProductionCommit; debugApkSha256 = $DebugSha }
        harness = [pscustomobject]@{ commit = $harnessCommit; tree = $harnessTree; runnerSha256 = $runnerSha; instrumentationSourceSha256 = $instrumentationSha; testApkSha256 = $testApkSha }
        git = [pscustomobject]@{ commit = $harnessCommit; manifestFile = (Join-Path $Root 'nar-corpus-manifest.json') }
        apks = [pscustomobject]@{ debugPath = (Join-Path $Root 'app-debug.apk'); debugSha256 = $DebugSha; testPath = (Join-Path $Root 'app-debug-androidTest.apk'); testSha256 = $testApkSha }
        device = [pscustomobject]@{ fingerprint = 'fixture/device'; api = 37; abi = 'x86_64'; density = 240 }
        results = $rows
        failures = @()
        unexpectedAbort = $false
        abortedDueToTimeout = $false
        cleanupVerification = 'verified'
        sentinels = [pscustomobject]@{
            passed = $true
            checks = $sentinelChecks
        }
    }
    Write-Json -Path (Join-Path $Root 'summary.json') -Value $summary
}

function Reset-Fixtures {
    if (Test-Path -LiteralPath $fixtureRoot) { Remove-Item -LiteralPath $fixtureRoot -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $fixtureRoot | Out-Null
    New-ReportFixture -Root (Join-Path $fixtureRoot 'base') -RunId ('1' * 32) -ProductionCommit $baseCommit -DebugSha $baseDebugSha -StartedAt '2026-08-17T00:00:00Z'
    New-ReportFixture -Root (Join-Path $fixtureRoot 'candidate') -RunId ('2' * 32) -ProductionCommit $baseCommit -DebugSha $baseDebugSha -StartedAt '2026-08-17T00:01:00Z'
}

function Get-ComparatorArguments {
    param(
        [ValidateSet('BaseBase', 'BaseCandidate')][string]$Kind = 'BaseBase',
        [string]$Prerequisite,
        [string]$ExpectedCandidateCommit = $baseCommit,
        [string]$ExpectedCandidateDebugSha = $baseDebugSha,
        [string]$OutputPath = (Join-Path $fixtureRoot 'comparison.json')
    )
    $arguments = @(
        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $comparatorPath,
        '-ComparisonKind', $Kind,
        '-BaseRoot', (Join-Path $fixtureRoot 'base'),
        '-CandidateRoot', (Join-Path $fixtureRoot 'candidate'),
        '-ManifestPath', $manifestPath,
        '-ContractPath', $contractPath,
        '-BaseProductionCommit', $baseCommit,
        '-BaseDebugApkSha256', $baseDebugSha,
        '-CandidateProductionCommit', $ExpectedCandidateCommit,
        '-CandidateDebugApkSha256', $ExpectedCandidateDebugSha,
        '-HarnessCommit', $harnessCommit,
        '-HarnessTree', $harnessTree,
        '-HarnessRunnerSha256', $runnerSha,
        '-HarnessInstrumentationSourceSha256', $instrumentationSha,
        '-HarnessTestApkSha256', $testApkSha,
        '-OutputPath', $OutputPath
    )
    if ($Prerequisite) { $arguments += @('-BaseBaseReportPath', $Prerequisite) }
    return $arguments
}

function Invoke-Comparator([string[]]$Arguments) {
    $output = & pwsh @Arguments 2>&1 | Out-String
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output }
}

function Assert-Pass([string]$Name, [object]$Result) {
    if ($Result.ExitCode -ne 0) { throw "$Name expected success, exit $($Result.ExitCode): $($Result.Output)" }
    Write-Host "PASS: $Name"
}

function Assert-Fail([string]$Name, [object]$Result, [string]$Pattern) {
    if ($Result.ExitCode -eq 0 -or $Result.Output -notmatch $Pattern) {
        throw "$Name expected failure matching '$Pattern', exit $($Result.ExitCode): $($Result.Output)"
    }
    Write-Host "PASS: $Name"
}

function Save-Json([string]$Path, [scriptblock]$Mutation) {
    $value = Get-Json $Path
    & $Mutation $value
    Write-Json -Path $Path -Value $value
}

function Add-RawNumberEvidence([string]$Path, [string]$Token) {
    if ($Token -notmatch '^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$') { throw "Unsafe JSON number token: $Token" }
    $text = [IO.File]::ReadAllText($Path)
    $updated = $text.Replace('"stable": true', "`"stable`": true, `"precise`": $Token")
    if ($updated -ceq $text) { throw "Stable evidence marker missing from $Path" }
    [IO.File]::WriteAllText($Path, $updated, [Text.UTF8Encoding]::new($false))
}

function Get-Row([object]$Summary, [string]$Label) {
    @($Summary.results | Where-Object label -CEQ $Label)[0]
}

try {
    Reset-Fixtures
    Assert-Pass 'exact successful base/base evidence' (Invoke-Comparator (Get-ComparatorArguments))

    Reset-Fixtures
    Remove-Item -LiteralPath (Join-Path $fixtureRoot 'candidate') -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $fixtureRoot 'base') -Destination (Join-Path $fixtureRoot 'candidate') -Recurse
    Assert-Fail 'copied evidence root cannot satisfy base/base' (Invoke-Comparator (Get-ComparatorArguments)) 'distinct base and candidate evidence fingerprints'

    Reset-Fixtures
    Remove-Item -LiteralPath (Join-Path $fixtureRoot 'candidate') -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $fixtureRoot 'base') -Destination (Join-Path $fixtureRoot 'candidate') -Recurse
    Add-Content -LiteralPath (Join-Path $fixtureRoot 'candidate\summary.json') -Value ''
    Assert-Fail 'whitespace-modified copied evidence cannot satisfy base/base' (Invoke-Comparator (Get-ComparatorArguments)) 'distinct base and candidate run identities'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.results[0].runId = ('3' * 32) }
    Assert-Fail 'summary row run identity must mirror the run identity' (Invoke-Comparator (Get-ComparatorArguments)) 'summary result.*runId.*run identity'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Haiidrate\result.json') { param($raw) $raw.narCorpusPath = '/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/33333333333333333333333333333333/Haiidrate/nanidroid-corpus.nar' }
    Assert-Fail 'raw run-owned path must mirror the run identity' (Invoke-Comparator (Get-ComparatorArguments)) 'raw result.*narCorpusPath.*run identity'

    Reset-Fixtures
    $wrongIdentity = Get-ComparatorArguments
    $wrongIdentity[($wrongIdentity.IndexOf('-BaseProductionCommit') + 1)] = $candidateCommit
    Assert-Fail 'wrong declared production identity' (Invoke-Comparator $wrongIdentity) 'base production identity'

    Reset-Fixtures
    $missingIdentity = @(Get-ComparatorArguments)
    $identityIndex = $missingIdentity.IndexOf('-HarnessTestApkSha256')
    $missingIdentity = @($missingIdentity[0..($identityIndex - 1)] + $missingIdentity[($identityIndex + 2)..($missingIdentity.Count - 1)])
    Assert-Fail 'missing harness identity' (Invoke-Comparator $missingIdentity) 'HarnessTestApkSha256'

    Reset-Fixtures
    $missingProductionIdentity = @(Get-ComparatorArguments)
    $identityIndex = $missingProductionIdentity.IndexOf('-BaseDebugApkSha256')
    $missingProductionIdentity = @($missingProductionIdentity[0..($identityIndex - 1)] + $missingProductionIdentity[($identityIndex + 2)..($missingProductionIdentity.Count - 1)])
    Assert-Fail 'missing production identity' (Invoke-Comparator $missingProductionIdentity) 'BaseDebugApkSha256'

    Reset-Fixtures
    $swappedIdentities = Get-ComparatorArguments
    $swappedIdentities[($swappedIdentities.IndexOf('-BaseProductionCommit') + 1)] = $candidateCommit
    $swappedIdentities[($swappedIdentities.IndexOf('-CandidateProductionCommit') + 1)] = $baseCommit
    Assert-Fail 'swapped production identity' (Invoke-Comparator $swappedIdentities) 'base production identity'

    Reset-Fixtures
    $wrongHarness = Get-ComparatorArguments
    $wrongHarness[($wrongHarness.IndexOf('-HarnessRunnerSha256') + 1)] = 'f' * 64
    Assert-Fail 'mismatched harness identity' (Invoke-Comparator $wrongHarness) 'harness identity'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'base\summary.json') { param($s) $s.sentinels.passed = $false; $s.sentinels.checks[0].passed = $false }
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.sentinels.passed = $false; $s.sentinels.checks[0].passed = $false }
    Assert-Fail 'identically failed runs' (Invoke-Comparator (Get-ComparatorArguments)) 'successful run'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'base\summary.json') { param($s) $s.unexpectedAbort = 0 }
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.unexpectedAbort = 0 }
    Assert-Fail 'success boolean must retain JSON kind' (Invoke-Comparator (Get-ComparatorArguments)) 'unexpectedAbort.*boolean'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'base\summary.json') { param($s) $s.sentinels.checks = $s.sentinels.checks[0] }
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.sentinels.checks = $s.sentinels.checks[0] }
    Assert-Fail 'sentinel checks must remain a JSON array' (Invoke-Comparator (Get-ComparatorArguments)) 'checks.*array'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'base\summary.json') { param($s) $s.sentinels.checks = @($s.sentinels.checks | Where-Object { $_.name -ceq 'slice2-2elf-dialogue-value-nonblank' }) }
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.sentinels.checks = @($s.sentinels.checks | Where-Object { $_.name -ceq 'slice2-2elf-dialogue-value-nonblank' }) }
    Assert-Fail 'sentinel checks cannot be omitted from both successful summaries' (Invoke-Comparator (Get-ComparatorArguments)) 'sentinel.*count'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.sentinels.checks[0].name = 'renamed-sentinel' }
    Assert-Fail 'renamed sentinel check is rejected' (Invoke-Comparator (Get-ComparatorArguments)) 'sentinel.*digest'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.sentinels.checks[1].name = $s.sentinels.checks[0].name }
    Assert-Fail 'duplicate sentinel check name is rejected' (Invoke-Comparator (Get-ComparatorArguments)) 'sentinel.*name.*unique'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.sentinels.checks[$s.sentinels.checks.Count - 1].name = $s.sentinels.checks[0].name }
    Assert-Fail 'nonadjacent duplicate sentinel check name is rejected' (Invoke-Comparator (Get-ComparatorArguments)) 'sentinel.*name.*unique'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.sentinels.checks[0].name = $s.sentinels.checks[1].name.ToUpperInvariant() }
    Assert-Fail 'case-distinct sentinel name remains distinct before digest validation' (Invoke-Comparator (Get-ComparatorArguments)) 'sentinel.*digest'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.sentinels.checks[0].name = ' ' }
    Assert-Fail 'blank sentinel check name is rejected' (Invoke-Comparator (Get-ComparatorArguments)) 'sentinel.*name.*nonblank'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.sentinels.checks[0].name = 1 }
    Assert-Fail 'non-string sentinel check name is rejected' (Invoke-Comparator (Get-ComparatorArguments)) 'sentinel.*name.*string'

    Reset-Fixtures
    $sentinelContractPath = Join-Path $fixtureRoot 'sentinel-count-contract.json'
    Copy-Item -LiteralPath $contractPath -Destination $sentinelContractPath
    Save-Json $sentinelContractPath { param($c) $c.sentinelChecks.count = 1 }
    $sentinelContractArguments = Get-ComparatorArguments
    $sentinelContractArguments[($sentinelContractArguments.IndexOf('-ContractPath') + 1)] = $sentinelContractPath
    Assert-Fail 'sentinel contract count mutation cannot bless a reduced set' (Invoke-Comparator $sentinelContractArguments) 'sentinel.*count'

    foreach ($countTokenCase in @('139.0', '1.39e2', 'true', '"139"')) {
        Reset-Fixtures
        $sentinelContractPath = Join-Path $fixtureRoot ('sentinel-count-token-' + ($countTokenCase -replace '[^A-Za-z0-9]', '_') + '.json')
        Copy-Item -LiteralPath $contractPath -Destination $sentinelContractPath
        Set-SentinelContractCountToken -Path $sentinelContractPath -Token $countTokenCase
        $sentinelContractArguments = Get-ComparatorArguments
        $sentinelContractArguments[($sentinelContractArguments.IndexOf('-ContractPath') + 1)] = $sentinelContractPath
        Assert-Fail "sentinel contract count token '$countTokenCase' is rejected" (Invoke-Comparator $sentinelContractArguments) 'sentinel.*count'
    }

    Reset-Fixtures
    $sentinelContractPath = Join-Path $fixtureRoot 'sentinel-digest-contract.json'
    Copy-Item -LiteralPath $contractPath -Destination $sentinelContractPath
    Save-Json $sentinelContractPath { param($c) $c.sentinelChecks.namesSha256 = 'f' * 64 }
    $sentinelContractArguments = Get-ComparatorArguments
    $sentinelContractArguments[($sentinelContractArguments.IndexOf('-ContractPath') + 1)] = $sentinelContractPath
    Assert-Fail 'sentinel contract digest mutation cannot bless a renamed set' (Invoke-Comparator $sentinelContractArguments) 'sentinel.*digest'

    Reset-Fixtures
    $staleSuccess = Invoke-Comparator (Get-ComparatorArguments)
    if ($staleSuccess.ExitCode -ne 0) { throw "failed to seed stale success report: $($staleSuccess.Output)" }
    $hiddenRawPath = Join-Path $fixtureRoot 'candidate\LOBO\result.json'
    Save-Json $hiddenRawPath { param($r) $r.evidence | Add-Member -NotePropertyName hidden -NotePropertyValue 'candidate-only' }
    Assert-Fail 'hidden raw difference' (Invoke-Comparator (Get-ComparatorArguments)) 'evidence.hidden'
    $failureReport = Get-Json (Join-Path $fixtureRoot 'comparison.json')
    if ($failureReport.passed -ne $false -or [string]$failureReport.failure.label -cne 'LOBO' -or [string]::IsNullOrWhiteSpace([string]$failureReport.failure.reason) -or
        [string]::IsNullOrWhiteSpace([string]$failureReport.failure.artifact) -or [string]::IsNullOrWhiteSpace([string]$failureReport.failure.path)) {
        throw 'raw-difference failure did not atomically replace output with bounded structured failure evidence.'
    }
    Write-Host 'PASS: structured failure report replaces stale output'

    Reset-Fixtures
    $basePrecisePath = Join-Path $fixtureRoot 'base\tewire-sen\result.json'
    $candidatePrecisePath = Join-Path $fixtureRoot 'candidate\tewire-sen\result.json'
    Add-RawNumberEvidence $basePrecisePath '0.1234567890123456789012345678901'
    Add-RawNumberEvidence $candidatePrecisePath '0.1234567890123456789012345678902'
    Assert-Fail 'adjacent high-precision JSON numbers remain distinct' (Invoke-Comparator (Get-ComparatorArguments)) 'evidence.precise'

    foreach ($numericPolicyCase in @(
        @{ name = 'tiny exponent JSON numbers remain distinct'; base = '1e-29'; candidate = '2e-29' },
        @{ name = 'negative high-precision JSON numbers remain distinct'; base = '-0.1234567890123456789012345678901'; candidate = '-0.1234567890123456789012345678902' },
        @{ name = 'numeric exponent spelling is lexical'; base = '1e2'; candidate = '100' },
        @{ name = 'integer and decimal number spelling is lexical'; base = '1'; candidate = '1.0' }
    )) {
        Reset-Fixtures
        Add-RawNumberEvidence (Join-Path $fixtureRoot 'base\tewire-sen\result.json') $numericPolicyCase.base
        Add-RawNumberEvidence (Join-Path $fixtureRoot 'candidate\tewire-sen\result.json') $numericPolicyCase.candidate
        Assert-Fail $numericPolicyCase.name (Invoke-Comparator (Get-ComparatorArguments)) 'evidence.precise'
    }

    Reset-Fixtures
    Move-Item -LiteralPath (Join-Path $fixtureRoot 'candidate\LOBO') -Destination (Join-Path $fixtureRoot 'candidate\LOBO-renamed')
    Assert-Fail 'raw result path mismatch' (Invoke-Comparator (Get-ComparatorArguments)) 'result.json set'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.results[0].label = 'wrong-label' }
    Assert-Fail 'summary label mismatch' (Invoke-Comparator (Get-ComparatorArguments)) 'label set'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.narCorpusPath = '/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/ffffffffffffffffffffffffffffffff/LOBO/nanidroid-corpus.nar' }
    Assert-Fail 'raw run metadata must mirror the declared run identity' (Invoke-Comparator (Get-ComparatorArguments)) 'narCorpusPath.*run identity'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.narCorpusPath = '/prefix/data/user/0/com.cattailsw.nanidroid/cache/nar-corpus-host/22222222222222222222222222222222/LOBO/nanidroid-corpus.nar' }
    Assert-Fail 'narCorpusPath prefix cannot surround a valid runner path' (Invoke-Comparator (Get-ComparatorArguments)) 'narCorpusPath.*exact runner path'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.narCorpusPath = '/data/user/0/com.cattailsw.nanidroid/cache/nar-corpus-host/22222222222222222222222222222222/LOBO/nanidroid-corpus.nar/suffix' }
    Assert-Fail 'narCorpusPath suffix cannot follow the runner filename' (Invoke-Comparator (Get-ComparatorArguments)) 'narCorpusPath.*exact runner path'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.narCorpusPath = '/data/user/0/com.cattailsw.nanidroid/cache/nar-corpus-host/22222222222222222222222222222222/LOBO/not-nanidroid-corpus.nar' }
    Assert-Fail 'narCorpusPath requires the exact runner filename' (Invoke-Comparator (Get-ComparatorArguments)) 'narCorpusPath.*exact runner path'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.narCorpusPath = '/data/user/0/com.cattailsw.nanidroid/cache/nar-corpus-host/22222222222222222222222222222222/Haiidrate/nanidroid-corpus.nar' }
    Assert-Fail 'narCorpusPath requires its row safe label' (Invoke-Comparator (Get-ComparatorArguments)) 'narCorpusPath.*exact runner path'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.narCorpusPath = '/data/user/0/com.cattailsw.nanidroid/cache/nar-corpus-host/22222222222222222222222222222222/LOBO/../LOBO/nanidroid-corpus.nar' }
    Assert-Fail 'narCorpusPath rejects traversal segments' (Invoke-Comparator (Get-ComparatorArguments)) 'narCorpusPath.*exact runner path'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.narCorpusPath = '/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/22222222222222222222222222222222/LOBO/nanidroid-corpus.nar' }
    Assert-Fail 'narCorpusPath private roots cannot vary within one run' (Invoke-Comparator (Get-ComparatorArguments)) 'narCorpusPath.*same private data root'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.durationSeconds = '23' }
    Assert-Fail 'duration JSON kind mismatch' (Invoke-Comparator (Get-ComparatorArguments)) 'durationSeconds.*kind'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.results[0].resultPath = $null }
    Assert-Fail 'path JSON kind mismatch' (Invoke-Comparator (Get-ComparatorArguments)) 'resultPath.*kind'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'base\summary.json') { param($s) foreach ($row in $s.results) { $row.PSObject.Properties.Remove('resultPath') } }
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) foreach ($row in $s.results) { $row.PSObject.Properties.Remove('resultPath') } }
    Assert-Fail 'normalization path missing from both summaries' (Invoke-Comparator (Get-ComparatorArguments)) 'normalization property is missing.*results\[0\]\.resultPath'
    $missingBothReport = Get-Json (Join-Path $fixtureRoot 'comparison.json')
    if ([string]$missingBothReport.failure.artifact -cne 'summary' -or [string]$missingBothReport.failure.label -cne '2elf-2.46' -or
        [string]$missingBothReport.failure.path -cne 'results[0].resultPath') {
        throw 'missing-both normalization failure did not report summary/label/path evidence.'
    }
    Write-Host 'PASS: missing-both normalization failure is structured'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.results[0].PSObject.Properties.Remove('resultPath') }
    Assert-Fail 'normalization path missing from one summary' (Invoke-Comparator (Get-ComparatorArguments)) 'normalization property is missing.*results\[0\]\.resultPath'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'base\Yes-Man-2.1.1\result.json') { param($r) $r.dialogueProbe.PSObject.Properties.Remove('value') }
    Save-Json (Join-Path $fixtureRoot 'candidate\Yes-Man-2.1.1\result.json') { param($r) $r.dialogueProbe.PSObject.Properties.Remove('value') }
    Assert-Fail 'label-scoped Yes Man normalization path missing from both raws' (Invoke-Comparator (Get-ComparatorArguments)) 'raw\[Yes Man-2\.1\.1\].*normalization property is missing.*dialogueProbe\.value'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'base\LOBO\result.json') { param($r) $r.evidence.sourceSyntax.PSObject.Properties.Remove('scanRoot') }
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.evidence.sourceSyntax.PSObject.Properties.Remove('scanRoot') }
    Assert-Fail 'declared raw source normalization path missing from both raws' (Invoke-Comparator (Get-ComparatorArguments)) 'raw\[LOBO\].*normalization property is missing.*evidence\.sourceSyntax\.scanRoot'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Haiidrate\result.json') { param($r) $r.evidence | Add-Member -NotePropertyName sourceSyntax -NotePropertyValue ([pscustomobject]@{ scanRoot = '/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/candidate/Haiidrate/source' }); $r | Add-Member -NotePropertyName sakura -NotePropertyValue ([pscustomobject]@{ source = '/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/candidate/Haiidrate/sakura' }); $r | Add-Member -NotePropertyName kero -NotePropertyValue ([pscustomobject]@{ source = '/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/candidate/Haiidrate/kero' }) }
    Assert-Fail 'undeclared raw source selectors remain behavioral' (Invoke-Comparator (Get-ComparatorArguments)) 'raw\[Haiidrate\]\.(kero|sakura|evidence\.sourceSyntax)'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'base\tewire-sen\result.json') { param($r) $r.dialogueProbe.value = "behavior:$('1' * 32)" }
    Save-Json (Join-Path $fixtureRoot 'candidate\tewire-sen\result.json') { param($r) $r.dialogueProbe.value = "behavior:$('2' * 32)" }
    Assert-Fail 'non-Yes-Man run-id-bearing dialogue remains behavioral' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe.value'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'base\Yes-Man-2.1.1\result.json') { param($r) $r.dialogueProbe.value = "/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/$('1' * 32)/Yes-Man-2.1.1/probe-install/file.nar" }
    Save-Json (Join-Path $fixtureRoot 'candidate\Yes-Man-2.1.1\result.json') { param($r) $r.dialogueProbe.value = "/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/$('2' * 32)/Yes-Man-2.1.1/probe-install/file.nar" }
    Assert-Pass 'scoped Yes Man run-owned dialogue path normalization' (Invoke-Comparator (Get-ComparatorArguments))

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'base\Yes-Man-2.1.1\result.json') { param($r) $r.dialogueProbe.value += "|behavior:$('1' * 32)" }
    Save-Json (Join-Path $fixtureRoot 'candidate\Yes-Man-2.1.1\result.json') { param($r) $r.dialogueProbe.value += "|behavior:$('2' * 32)" }
    Assert-Fail 'Yes Man bare run ID outside declared path remains behavioral' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe.value'

    Reset-Fixtures
    $expandedContractPath = Join-Path $fixtureRoot 'expanded-contract.json'
    Copy-Item -LiteralPath $contractPath -Destination $expandedContractPath
    Save-Json $expandedContractPath { param($c) $c.normalization.rawRunOwnedStringPaths += 'classification' }
    $expandedContractArguments = Get-ComparatorArguments
    $expandedContractArguments[($expandedContractArguments.IndexOf('-ContractPath') + 1)] = $expandedContractPath
    Assert-Fail 'unexpected normalization contract expansion' (Invoke-Comparator $expandedContractArguments) 'normalization rawRunOwnedStringPaths'

    Reset-Fixtures
    $expandedContractPath = Join-Path $fixtureRoot 'expanded-source-selector-contract.json'
    Copy-Item -LiteralPath $contractPath -Destination $expandedContractPath
    Save-Json $expandedContractPath { param($c) $c.normalization.rawSourceArchiveSha256 | Add-Member -NotePropertyName Haiidrate -NotePropertyValue ('f' * 64) }
    $expandedContractArguments = Get-ComparatorArguments
    $expandedContractArguments[($expandedContractArguments.IndexOf('-ContractPath') + 1)] = $expandedContractPath
    Assert-Fail 'unexpected raw source selector label expansion' (Invoke-Comparator $expandedContractArguments) 'normalization rawSourceArchiveSha256 labels'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.classification = 'partiallyCompatible' }
    Assert-Fail 'undeclared neighboring field change' (Invoke-Comparator (Get-ComparatorArguments)) 'classification'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Watchdog-Bancho\result.json') { param($r) $r.dialogueProbe.value = "\1\s[10]\0\s[0]\0\s[0]Yo, boss! What's the haps?" }
    Assert-Pass 'reviewed Watchdog stochastic value' (Invoke-Comparator (Get-ComparatorArguments))

    foreach ($reviewedAlternative in @(
        @{ name = 'reviewed LOBO stochastic alternative'; safeLabel = 'LOBO'; value = $loboReviewedAlternative },
        @{ name = 'reviewed Snake V1.2.1 stochastic alternative'; safeLabel = 'Snake-and-Otacon-V1.2.1'; value = $snakeV121ReviewedAlternative },
        @{ name = 'reviewed Snake V1.3.1 stochastic alternative'; safeLabel = 'Snake-and-Otacon-V1.3.1'; value = $snakeLateReviewedAlternative },
        @{ name = 'reviewed Snake 1.3.1b stochastic alternative'; safeLabel = 'Snake_Otacon_1.3.1b'; value = $snakeLateReviewedAlternative }
    )) {
        Reset-Fixtures
        Save-Json (Join-Path $fixtureRoot "candidate\$($reviewedAlternative.safeLabel)\result.json") { param($r) $r.dialogueProbe.value = $reviewedAlternative.value }
        Assert-Pass $reviewedAlternative.name (Invoke-Comparator (Get-ComparatorArguments))
    }

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\2elf-2.46\result.json') { param($r) $r.dialogueProbe.value = $twoElfCandidate }
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s '2elf-2.46').requiredEvidencePayload.dialogueProbe.value = $twoElfCandidate; ($s.sentinels.checks | Where-Object name -CEQ 'slice2-2elf-dialogue-value-nonblank').observed = $twoElfCandidate }
    Assert-Pass 'reviewed 2elf raw and named mirrors' (Invoke-Comparator (Get-ComparatorArguments))

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\2elf-2.46\result.json') { param($r) $r.dialogueProbe.value = $twoElfCandidate }
    Assert-Fail 'stale 2elf required-evidence mirror' (Invoke-Comparator (Get-ComparatorArguments)) 'required evidence mirror'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Watchdog-Bancho\result.json') { param($r) $r.dialogueProbe.value = 'unreviewed stochastic output' }
    Assert-Fail 'unlisted stochastic value' (Invoke-Comparator (Get-ComparatorArguments)) 'unreviewed stochastic'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Snake-and-Otacon-V1.2.1\result.json') { param($r) $r.dialogueProbe.value = $snakeLateReviewedAlternative }
    Assert-Fail 'cross-archive stochastic value' (Invoke-Comparator (Get-ComparatorArguments)) 'unreviewed stochastic'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Watchdog-Bancho\result.json') { param($r) $r.sha256 = 'f' * 64 }
    Assert-Fail 'stochastic archive SHA mismatch' (Invoke-Comparator (Get-ComparatorArguments)) 'archive SHA'

    Reset-Fixtures
    [IO.File]::AppendAllText((Join-Path $fixtureRoot 'candidate\screenshots\LOBO.png'), 'changed')
    Assert-Fail 'screenshot mismatch' (Invoke-Comparator (Get-ComparatorArguments)) 'screenshot'

    Reset-Fixtures
    $prerequisite = Join-Path $fixtureRoot 'base-base.json'
    Assert-Pass 'base/base prerequisite report' (Invoke-Comparator (Get-ComparatorArguments -OutputPath $prerequisite))
    Remove-Item -LiteralPath (Join-Path $fixtureRoot 'candidate') -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $fixtureRoot 'base') -Destination (Join-Path $fixtureRoot 'candidate') -Recurse
    Assert-Fail 'copied evidence root cannot satisfy base/candidate' (Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite)) 'distinct base and candidate evidence fingerprints'

    Reset-Fixtures
    $prerequisite = Join-Path $fixtureRoot 'base-base.json'
    Assert-Pass 'base/base prerequisite report' (Invoke-Comparator (Get-ComparatorArguments -OutputPath $prerequisite))
    Remove-Item -LiteralPath (Join-Path $fixtureRoot 'candidate') -Recurse -Force
    Copy-Item -LiteralPath (Join-Path $fixtureRoot 'base') -Destination (Join-Path $fixtureRoot 'candidate') -Recurse
    Add-Content -LiteralPath (Join-Path $fixtureRoot 'candidate\summary.json') -Value ''
    Assert-Fail 'whitespace-modified copied evidence cannot satisfy base/candidate' (Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite)) 'distinct base and candidate run identities'

    Reset-Fixtures
    $prerequisite = Join-Path $fixtureRoot 'base-base.json'
    Assert-Pass 'base/base prerequisite report' (Invoke-Comparator (Get-ComparatorArguments -OutputPath $prerequisite))
    Assert-Fail 'base/candidate requires a distinct production identity tuple' (Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite)) 'BaseCandidate comparison requires distinct base and candidate production identities'

    Reset-Fixtures
    $prerequisite = Join-Path $fixtureRoot 'base-base.json'
    Assert-Pass 'base/base prerequisite report' (Invoke-Comparator (Get-ComparatorArguments -OutputPath $prerequisite))
    New-ReportFixture -Root (Join-Path $fixtureRoot 'candidate') -RunId ('5' * 32) -ProductionCommit $candidateCommit -DebugSha $baseDebugSha -StartedAt '2026-08-17T00:02:00Z'
    Assert-Pass 'candidate may reuse a debug APK from a distinct commit' (Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $baseDebugSha))

    Reset-Fixtures
    $prerequisite = Join-Path $fixtureRoot 'base-base.json'
    Assert-Pass 'base/base prerequisite report' (Invoke-Comparator (Get-ComparatorArguments -OutputPath $prerequisite))
    New-ReportFixture -Root (Join-Path $fixtureRoot 'candidate') -RunId ('4' * 32) -ProductionCommit $candidateCommit -DebugSha $candidateDebugSha -StartedAt '2026-08-17T00:02:00Z'
    Assert-Fail 'missing base/base prerequisite' (Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $candidateDebugSha)) 'BaseBaseReportPath'
    Assert-Pass 'bound base/candidate comparison' (Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $candidateDebugSha))
    Save-Json (Join-Path $fixtureRoot 'base\summary.json') { param($s) $s.startedAt = '2026-08-17T00:03:00Z' }
    Assert-Fail 'replaced base evidence rejects stale prerequisite' (Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $candidateDebugSha)) 'evidence fingerprint'
    Save-Json (Join-Path $fixtureRoot 'base\summary.json') { param($s) $s.startedAt = '2026-08-17T00:00:00Z' }
    $mismatchedPrerequisite = Get-Json $prerequisite
    $mismatchedPrerequisite.device.abi = 'arm64-v8a'
    Write-Json $prerequisite $mismatchedPrerequisite
    Assert-Fail 'mismatched base/base prerequisite' (Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $candidateDebugSha)) 'prerequisite mismatch'
    $mismatchedPrerequisite.device.abi = 'x86_64'
    Write-Json $prerequisite $mismatchedPrerequisite
    $failedPrerequisite = Get-Json $prerequisite
    $failedPrerequisite.passed = $false
    Write-Json $prerequisite $failedPrerequisite
    Assert-Fail 'failed base/base prerequisite' (Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $candidateDebugSha)) 'prerequisite'

    Reset-Fixtures
    $defaultPrerequisite = Join-Path $fixtureRoot 'comparison.json'
    Assert-Pass 'base/base prerequisite at default output path' (Invoke-Comparator (Get-ComparatorArguments))
    New-ReportFixture -Root (Join-Path $fixtureRoot 'candidate') -RunId ('4' * 32) -ProductionCommit $candidateCommit -DebugSha $candidateDebugSha -StartedAt '2026-08-17T00:02:00Z'
    $defaultPrerequisiteSha = (Get-FileHash -LiteralPath $defaultPrerequisite -Algorithm SHA256).Hash
    $defaultCollisionResult = Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $defaultPrerequisite -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $candidateDebugSha)
    if ((Get-FileHash -LiteralPath $defaultPrerequisite -Algorithm SHA256).Hash -cne $defaultPrerequisiteSha) { throw 'base/candidate default output collision altered its prerequisite' }
    Assert-Fail 'base/candidate default output cannot overwrite its prerequisite' $defaultCollisionResult 'BaseBaseReportPath.*OutputPath.*distinct'

    Reset-Fixtures
    $prerequisite = Join-Path $fixtureRoot 'base-base.json'
    Assert-Pass 'base/base prerequisite for explicit output collision' (Invoke-Comparator (Get-ComparatorArguments -OutputPath $prerequisite))
    New-ReportFixture -Root (Join-Path $fixtureRoot 'candidate') -RunId ('4' * 32) -ProductionCommit $candidateCommit -DebugSha $candidateDebugSha -StartedAt '2026-08-17T00:02:00Z'
    Assert-Fail 'base/candidate explicit output cannot overwrite its prerequisite' (Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $candidateDebugSha -OutputPath $prerequisite)) 'BaseBaseReportPath.*OutputPath.*distinct'

    Reset-Fixtures
    $prerequisite = Join-Path $fixtureRoot 'base-base.json'
    Assert-Pass 'base/base prerequisite for relative output collision' (Invoke-Comparator (Get-ComparatorArguments -OutputPath $prerequisite))
    New-ReportFixture -Root (Join-Path $fixtureRoot 'candidate') -RunId ('4' * 32) -ProductionCommit $candidateCommit -DebugSha $candidateDebugSha -StartedAt '2026-08-17T00:02:00Z'
    $relativePrerequisiteAlias = [IO.Path]::GetRelativePath((Get-Location).Path, $prerequisite)
    $relativePrerequisiteSha = (Get-FileHash -LiteralPath $prerequisite -Algorithm SHA256).Hash
    $relativeAliasCollisionResult = Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $candidateDebugSha -OutputPath $relativePrerequisiteAlias)
    if ((Get-FileHash -LiteralPath $prerequisite -Algorithm SHA256).Hash -cne $relativePrerequisiteSha) { throw 'base/candidate relative output collision altered its prerequisite' }
    Assert-Fail 'base/candidate relative output alias cannot overwrite its prerequisite' $relativeAliasCollisionResult 'BaseBaseReportPath.*OutputPath.*distinct'

    Reset-Fixtures
    $prerequisite = Join-Path $fixtureRoot 'base-base.json'
    $candidateOutput = Join-Path $fixtureRoot 'base-candidate.json'
    Assert-Pass 'base/base prerequisite for distinct base/candidate output' (Invoke-Comparator (Get-ComparatorArguments -OutputPath $prerequisite))
    New-ReportFixture -Root (Join-Path $fixtureRoot 'candidate') -RunId ('4' * 32) -ProductionCommit $candidateCommit -DebugSha $candidateDebugSha -StartedAt '2026-08-17T00:02:00Z'
    Assert-Pass 'base/candidate may use a distinct output path' (Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $candidateDebugSha -OutputPath $candidateOutput))

    Write-Host 'Comparator host tests passed.'
}
finally {
    if (Test-Path -LiteralPath $fixtureRoot) { Remove-Item -LiteralPath $fixtureRoot -Recurse -Force }
}
