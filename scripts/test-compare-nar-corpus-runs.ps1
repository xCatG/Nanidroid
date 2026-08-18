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
if ($comparatorSource -match 'ConvertFrom-Json\s+-DateKind' -or $hostTestSource -match 'ConvertFrom-Json\s+-DateKind' -or
    $comparatorSource -match '\[Convert\]::ToHexString' -or
    $comparatorSource -notmatch '(?m)^#requires -Version 7\.0$' -or $comparatorSource -notmatch 'System\.Text\.Json\.JsonDocument') {
    throw 'Comparator JSON parsing is not statically compatible with the documented PowerShell 7.0 floor.'
}
Write-Host 'PASS: PowerShell 7.0-compatible strict JSON parser contract'

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
            narCorpusPath = "/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/$RunId/$safeLabel/nanidroid-corpus.nar"
            passed = $true
            classification = 'compatible'
            dialogueProbe = [pscustomobject]@{ outcome = 'success'; value = $value }
            evidence = [pscustomobject]@{ stable = $true }
            cleanup = [pscustomobject]@{ remainingTestOwnedPaths = @(); hostVerified = $true }
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
            durationSeconds = 1
            classification = 'compatible'
            requiredEvidence = @($entry.requiredEvidence)
            requiredEvidencePayload = $payload
            resultPath = "/sdcard/Android/data/com.cattailsw.nanidroid/files/nar-corpus/$safeLabel/result.json"
            screenshotPath = "/sdcard/Android/data/com.cattailsw.nanidroid/files/nar-corpus/$safeLabel/screenshot.png"
            status = 'ok'
            output = "Time: 1.0 run=$RunId"
            cleanup = [pscustomobject]@{ remainingTestOwnedPaths = @(); hostVerified = $true }
            postCleanupPrivateSnapshot = @()
            postCleanupOutputSnapshot = @()
            postCleanupTmpSnapshot = @()
        })
    }

    $twoElfValue = [string]$dialogueValues['2elf-2.46']
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
            checks = @(
                [pscustomobject]@{ name = 'slice2-2elf-dialogue-value-nonblank'; passed = $true; expected = 'nonblank'; observed = $twoElfValue },
                [pscustomobject]@{ name = 'fixture-all-results'; passed = $true; expected = 23; observed = 23 }
            )
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
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $comparatorPath,
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
    Assert-Pass 'enumerated run metadata normalization' (Invoke-Comparator (Get-ComparatorArguments))

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.results[0].durationSeconds = '1' }
    Assert-Fail 'duration JSON kind mismatch' (Invoke-Comparator (Get-ComparatorArguments)) 'durationSeconds.*kind'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.results[0].resultPath = $null }
    Assert-Fail 'path JSON kind mismatch' (Invoke-Comparator (Get-ComparatorArguments)) 'resultPath.*kind'

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
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.classification = 'partiallyCompatible' }
    Assert-Fail 'undeclared neighboring field change' (Invoke-Comparator (Get-ComparatorArguments)) 'classification'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Watchdog-Bancho\result.json') { param($r) $r.dialogueProbe.value = "\1\s[10]\0\s[0]\0\s[0]Yo, boss! What's the haps?" }
    Assert-Pass 'reviewed Watchdog stochastic value' (Invoke-Comparator (Get-ComparatorArguments))

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
    Save-Json (Join-Path $fixtureRoot 'candidate\Watchdog-Bancho\result.json') { param($r) $r.sha256 = 'f' * 64 }
    Assert-Fail 'stochastic archive SHA mismatch' (Invoke-Comparator (Get-ComparatorArguments)) 'archive SHA'

    Reset-Fixtures
    [IO.File]::AppendAllText((Join-Path $fixtureRoot 'candidate\screenshots\LOBO.png'), 'changed')
    Assert-Fail 'screenshot mismatch' (Invoke-Comparator (Get-ComparatorArguments)) 'screenshot'

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

    Write-Host 'Comparator host tests passed: 39 cases.'
}
finally {
    if (Test-Path -LiteralPath $fixtureRoot) { Remove-Item -LiteralPath $fixtureRoot -Recurse -Force }
}
