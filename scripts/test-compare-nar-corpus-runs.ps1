param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
$comparatorPath = Join-Path $PSScriptRoot 'compare-nar-corpus-runs.ps1'
$manifestPath = Join-Path $repoRoot 'docs\testing\nar-corpus-manifest.json'
$contractPath = Join-Path $repoRoot 'docs\testing\nar-corpus-comparison-contract.json'
$fixtureLeaf = "nar-corpus-comparator-tests-$PID-$([guid]::NewGuid().ToString('N'))"
$fixtureParent = [IO.Path]::GetFullPath((Join-Path $repoRoot 'build\reports'))
$fixtureRoot = Join-Path $fixtureParent $fixtureLeaf
$expectedFixtureRoot = [IO.Path]::GetFullPath((Join-Path $fixtureParent $fixtureLeaf))
$resolvedFixtureRoot = [IO.Path]::GetFullPath($fixtureRoot)
if (
    -not $resolvedFixtureRoot.Equals($expectedFixtureRoot, [StringComparison]::OrdinalIgnoreCase) -or
    -not $resolvedFixtureRoot.StartsWith($fixtureParent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
    -not ([IO.Path]::GetFileName($resolvedFixtureRoot).StartsWith("nar-corpus-comparator-tests-$PID-", [StringComparison]::Ordinal))
) {
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

$runnerDialogueOutcomeProbe = & (Join-Path $PSHOME 'pwsh.exe') -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-nar-corpus-audit.ps1') -HostOnlyOwnedProcessTest 2>&1
$runnerDialogueOutcomeProbeText = $runnerDialogueOutcomeProbe -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0 -or $runnerDialogueOutcomeProbeText -notmatch 'Host-only dialogue outcome summary mirror probe passed') {
    throw "Audit runner dialogue-outcome summary-mirror probe failed: $($runnerDialogueOutcomeProbe -join [Environment]::NewLine)"
}
Write-Host 'PASS: audit runner preserves validated dialogue outcomes in summary rows'

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

function Get-BytesSha256([byte[]]$Value) {
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $algorithm.ComputeHash($Value)
        return ([BitConverter]::ToString($bytes) -replace '-', '').ToLowerInvariant()
    }
    finally {
        $algorithm.Dispose()
    }
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$manifestSha = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
$reviewedManifestEntryCount = 23
$reviewedManifestTrueAllowCount = 6
$reviewedManifestTupleSha256 = 'bf8398cc1a96f8ec57b0762cb3422ba357819a76bcbb7659c058f5c96bbab8fc'
$reviewedManifestTupleCanonicalization = 'label|sha256|expectedKind|allowToken tuples; allowToken is <absent>, true, or false by exact property presence and JSON boolean kind; StringComparer.Ordinal sort; LF join without trailing LF; UTF-8 SHA-256'
$reviewedManifestObservedTrueAllowCount = 0
[string[]]$reviewedManifestTuples = @($manifest.entries | ForEach-Object {
    $allowProperty = $_.PSObject.Properties['allowNativeKawariCrash']
    $allowToken = if ($null -eq $allowProperty) {
        '<absent>'
    }
    elseif ($allowProperty.Value -isnot [bool]) {
        throw "Reviewed manifest tuple fixture allowNativeKawariCrash must be a JSON boolean when present for '$($_.label)'."
    }
    elseif ($allowProperty.Value) {
        $reviewedManifestObservedTrueAllowCount++
        'true'
    }
    else {
        'false'
    }
    "$($_.label)|$($_.sha256)|$($_.expectedKind)|$allowToken"
})
[Array]::Sort($reviewedManifestTuples, [StringComparer]::Ordinal)
$observedManifestTupleSha256 = Get-StringSha256 ($reviewedManifestTuples -join "`n")
if (
    $reviewedManifestTuples.Count -ne $reviewedManifestEntryCount -or
    $reviewedManifestObservedTrueAllowCount -ne $reviewedManifestTrueAllowCount -or
    $observedManifestTupleSha256 -cne $reviewedManifestTupleSha256
) {
    throw "Reviewed manifest tuple fixture mismatch under '$reviewedManifestTupleCanonicalization': count=$($reviewedManifestTuples.Count), trueAllowCount=$reviewedManifestObservedTrueAllowCount, digest=$observedManifestTupleSha256"
}
Write-Host 'PASS: reviewed 23-entry four-field manifest tuple digest fixture'
$compatibleGhostSuccessRules = [ordered]@{
    '2elf-2.46' = @{ sha256 = 'a50830e18def75be051a3638c7375c7e2d96cb18f7b3f26d0037d84a0fc20be0'; eventId = 'OnBoot'; inputOutcome = 'named-collisions-routed:24' }
    'tewire-sen' = @{ sha256 = '2a57e2272b2314baa59b3d911ed5051ef1fb8f94d1401083ffe4f7602834f7e8'; eventId = 'OnBoot'; inputOutcome = 'named-collisions-routed:6' }
    'Yes Man-2.1.1' = @{ sha256 = 'aa6383f564fc2d89cbbc926cd672f481d2e8aafa48ec235b07ba0cbdf77912e8'; eventId = 'OnBoot'; inputOutcome = 'named-collisions-routed:3' }
    'Big Red Button' = @{ sha256 = '36ad0500958d88175d9e2530f4aa6e085a2d8579bbb200c1e2d2f9ac0785d21d'; eventId = 'OnBoot'; inputOutcome = 'no-named-collisions' }
    'Earthquake Rescue Duo' = @{ sha256 = '06db71e7e8293b4af0b5127dd73402d4ed90fecc5fdcebf4f0d34337ccb66538'; eventId = 'OnBoot'; inputOutcome = 'named-collisions-routed:4' }
    'LOBO' = @{ sha256 = 'f4e90615cf40801d4a7a7170762b6c0d6dddf18324f9ba146f4a700cbe2bebf7'; eventId = 'OnBoot'; inputOutcome = 'no-named-collisions' }
    'Nanika Atsume 1.0.0' = @{ sha256 = '0ddfe156bf29e36522e58fe113ef64d0423cfd841007901a941dda50ed3302f9'; eventId = 'OnBoot'; inputOutcome = 'named-collisions-routed:7' }
    'Nanika Atsume 1.0.1' = @{ sha256 = '9b5ffc161abc489bce332702a1945f3f7d5ec6d66def3b521299ff36d91f290c'; eventId = 'OnBoot'; inputOutcome = 'named-collisions-routed:7' }
    'Nanika Atsume silent_ALPHA' = @{ sha256 = 'be187fb6f51e3b45b5cfa0ab07a8fe46fd6862146a82e8e9dab563e699bf5d17'; eventId = 'OnBoot'; inputOutcome = 'named-collisions-routed:7' }
    'Snake and Otacon V1.2.1' = @{ sha256 = 'a4b89d1c932f5862ca60e8bacf62563dadb65f4dadce5fd1bc7945db652acb6f'; eventId = 'OnBoot'; inputOutcome = 'named-collisions-routed:5' }
    'Snake and Otacon V1.3.1' = @{ sha256 = 'a710ff1f031ffd23d7d61fcf7fabed5d1cb4794eaf06e9eb6cd9d6df5fcc1219'; eventId = 'OnBoot'; inputOutcome = 'named-collisions-routed:5' }
    'Snake and Otacon V1.3.2' = @{ sha256 = '1c62ce50ca0daca3a9e14e6d870b02d4df9511dd5b586a7f4da49b402d56cbd5'; eventId = 'OnFirstBoot'; inputOutcome = 'named-collisions-routed:5' }
    'Snake_Otacon_1.3.1b' = @{ sha256 = '04d7563d65116d14e9e1208586c77cf3a6703dfcc3c10d48a10d581cfa9b8b59'; eventId = 'OnBoot'; inputOutcome = 'named-collisions-routed:5' }
    'Watchdog Bancho' = @{ sha256 = '8a3f1dcaa4c34a625bf16c0a0ada2e3dff2d49fc029e014807aafb164f196dca'; eventId = 'OnBoot'; inputOutcome = 'named-collisions-routed:12' }
}
$installRejectedGhostRules = [ordered]@{
    'Snake and Otacon V1.0.0' = '526b7721103031fb3f28b22fffc54b71fd0b1e279168934a06d8076e20a1cbcc'
    'Snake and Otacon V1.0.1' = '6f44dd039c17093d3f91e47bb9c474e128eb34fa4bfeb5ef3148625bbd613764'
    'Snake And Otacon V1.1.1' = '21253507c17e90073974229ddf8b0d39e36efcae968a27c2569fe5c46c201e4b'
    'Snake_Otacon_1.1.1b' = 'ef1590f766964b1932020abf6e93aa229be12fbc6ba9238a4e5cda90939f4d70'
}
$partialGhostRule = @{ label = 'Snake_Otacon_1.2.1b'; sha256 = '4c925dc0b8a61b41cc91c72589e30e4ece7e6b0b92dcc44eec993b71605aed45' }
$baseCommit = '1111111111111111111111111111111111111111'
$candidateCommit = '2222222222222222222222222222222222222222'
$baseDebugSha = 'a' * 64
$candidateDebugSha = 'b' * 64
$harnessCommit = '3333333333333333333333333333333333333333'
$harnessTree = '4444444444444444444444444444444444444444'
$runnerSha = 'c' * 64
$instrumentationSha = 'd' * 64
$testApkSha = 'e' * 64
$reviewedSentinelNameCount = 143
$reviewedSentinelNamesSha256 = '072d6adec034001985d367a9d8a89ef0db447a76cbc1b9a4a22f580fdabc5b6e'
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
        if ($null -ne $raw.evidence.PSObject.Properties['sourceSyntax']) {
            $scanRoot = [string]$raw.evidence.sourceSyntax.scanRoot
            $archivePrefix = ([string]$row.sha256).Substring(0, 16)
            $expectedScanRoot = '^/data/data/com\.cattailsw\.nanidroid/cache/nar-corpus-host/' + [regex]::Escape([string]$preservedSummary.runId) + '/' + [regex]::Escape($safeLabel) + '/probe-install/corpus-' + [regex]::Escape($archivePrefix) + '$'
            if ($scanRoot -notmatch $expectedScanRoot -or [string]$raw.sakura.source -notmatch ('^' + [regex]::Escape($scanRoot) + '/shell/master/surface(?:0|0000)\.png$') -or [string]$raw.kero.source -notmatch ('^' + [regex]::Escape($scanRoot) + '/shell/master/surface(?:10|0010)\.png$')) {
                throw "Preserved raw result '$($row.label)' source paths are not exact archive-bound scan-root descendants."
            }
        }
    }
    Write-Host 'PASS: preserved successful corpus narCorpusPath shape'
}
else {
    Write-Host 'SKIP: preserved successful corpus evidence root is unavailable'
}
$recoveredCorpusRoot = 'C:\Users\yenchi\.codex\worktrees\27f9\Nanidroid\build\reports\pr393-corpus'
if (Test-Path -LiteralPath $recoveredCorpusRoot -PathType Container) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [Text.Encoding]::RegisterProvider([Text.CodePagesEncodingProvider]::Instance)
    $olderSnakeProvenance = @{
        'a4b89d1c932f5862ca60e8bacf62563dadb65f4dadce5fd1bc7945db652acb6f' = @{ italic = 304; toggle = 306 }
        'a710ff1f031ffd23d7d61fcf7fabed5d1cb4794eaf06e9eb6cd9d6df5fcc1219' = @{ italic = 316; toggle = 318 }
        '04d7563d65116d14e9e1208586c77cf3a6703dfcc3c10d48a10d581cfa9b8b59' = @{ italic = 313; toggle = 315 }
    }
    $foundOlderSnake = @{}
    $foundEarthquake = $false
    foreach ($archive in @(Get-ChildItem -LiteralPath $recoveredCorpusRoot -Recurse -Filter '*.nar')) {
        $archiveSha = (Get-FileHash -LiteralPath $archive.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($archiveSha -ceq '06db71e7e8293b4af0b5127dd73402d4ed90fecc5fdcebf4f0d34337ccb66538') {
            $earthquakeZip = [IO.Compression.ZipFile]::OpenRead($archive.FullName)
            try {
                $earthquakeMember = @($earthquakeZip.Entries | Where-Object { ($_.FullName -replace '\\', '/') -ceq 'ghost/master/duo_bootend.dic' })
                if ($earthquakeMember.Count -ne 1) { throw 'Recovered Earthquake archive lacks exactly one duo_bootend.dic member.' }
                $stream = $earthquakeMember[0].Open()
                try {
                    $memory = [IO.MemoryStream]::new()
                    try { $stream.CopyTo($memory); [byte[]]$rawEntryBytes = $memory.ToArray() } finally { $memory.Dispose() }
                }
                finally { $stream.Dispose() }
                if ((Get-BytesSha256 $rawEntryBytes) -cne 'e34da6717a0bc20145b9ca7d535c886550331eb09910af5e02d5a6cd06523892') {
                    throw 'Recovered Earthquake raw source entry hash mismatch.'
                }
                if ($rawEntryBytes.Count -lt 3 -or $rawEntryBytes[0] -ne 0xEF -or $rawEntryBytes[1] -ne 0xBB -or $rawEntryBytes[2] -ne 0xBF) {
                    throw 'Recovered Earthquake source entry lacks its reviewed UTF-8 BOM.'
                }
                [byte[]]$bomStrippedBytes = @($rawEntryBytes[3..($rawEntryBytes.Count - 1)])
                if ((Get-BytesSha256 $bomStrippedBytes) -cne '1fe69ccc4e2587c848fa0668291cbebc50eb82994fb1be822b086310b9a884b9') {
                    throw 'Recovered Earthquake BOM-stripped source hash mismatch.'
                }
                $earthquakeLines = @([Text.Encoding]::UTF8.GetString($bomStrippedBytes) -split "`r?`n")
                foreach ($slice in @(
                    @{ first = 136; last = 159; sha256 = '49f89d04829adf5d1d62d7064e35d9f596388fd46b021a0483276a8346747413' },
                    @{ first = 174; last = 203; sha256 = '80ae6c266ef6fadd37dcb3269ac88053124426f0ca6051d47754863e8622ce73' },
                    @{ first = 210; last = 260; sha256 = '156f587427813264b7f5eff075163e3ffe001028932266b372e1a82bf0dc16a5' }
                )) {
                    $sliceText = $earthquakeLines[($slice.first - 1)..($slice.last - 1)] -join "`n"
                    if ((Get-StringSha256 $sliceText) -cne $slice.sha256) {
                        throw "Recovered Earthquake LF slice $($slice.first)-$($slice.last) hash mismatch."
                    }
                }
                $foundEarthquake = $true
            }
            finally { $earthquakeZip.Dispose() }
        }
        if (-not $olderSnakeProvenance.ContainsKey($archiveSha)) { continue }
        $zip = [IO.Compression.ZipFile]::OpenRead($archive.FullName)
        try {
            $member = @($zip.Entries | Where-Object { ($_.FullName -replace '\\', '/') -ceq 'ghost/master/Sn_bootend.dic' })
            if ($member.Count -ne 1) { throw "Recovered older-Snake archive $archiveSha lacks exactly one CP932 Sn_bootend.dic member." }
            $reader = [IO.StreamReader]::new($member[0].Open(), [Text.Encoding]::GetEncoding(932), $true)
            try { $lines = @($reader.ReadToEnd() -split "`r?`n") } finally { $reader.Dispose() }
            $rule = $olderSnakeProvenance[$archiveSha]
            if ($lines[$rule.italic - 1] -notmatch [regex]::Escape('\f[italic,true]zzz...') -or $lines[$rule.toggle - 1] -notmatch [regex]::Escape('\f[italic,true]') -or $lines[$rule.toggle - 1] -notmatch [regex]::Escape('\f[italic,false]')) {
                throw "Recovered older-Snake CP932 source lines do not prove reviewed italic/EOF lexer tokens for $archiveSha."
            }
            $foundOlderSnake[$archiveSha] = $true
        }
        finally { $zip.Dispose() }
    }
    if (-not $foundEarthquake) { throw 'Recovered corpus did not authenticate the exact Earthquake source archive.' }
    Write-Host 'PASS: recovered UTF-8 Earthquake source provenance binds time/date branches'
    if ($foundOlderSnake.Count -ne 3) { throw 'Recovered corpus did not authenticate all three exact older-Snake source archives.' }
    Write-Host 'PASS: recovered CP932 older-Snake source provenance binds italic and EOF branches'
}
else {
    Write-Host 'SKIP: recovered corpus source archive root is unavailable'
}
$twoElfBase = '\1\s[19]\n\n[half]\_w[18]\0\s[103]旅人さん…。\_w[36]\w8\n\n[half]\_w[18]\1\c謝りに来たの？\w8\_w[126]\n\n[half]\_w[18]\0\s[101]え…。\_w[54]\w8\nそ、\_w[36]そんな…私こそ、\_w[144]急に帰ったりして…\w8ごめんね。\_w[252]\w8\n怒ってないから…。\_w[162]\w8\nだけど、\_w[72]もうあんなエッチな事はしないでね…\w8\s[104]お願い。\_w[378]\e'
$twoElfCandidate = '\1\s[19]\n\n[half]\_w[18]\0\s[103]あ…旅人さん…。\_w[72]\w8\nご、\_w[36]ごめんなさい！\w8\_w[126]\n逃げちゃったりして。\_w[180]\w8\n\n[half]\_w[18]\1\cソフィが謝る事じゃないわよ。\_w[252]\w8\n\n[half]\_w[18]\0\s[101]旅人さん…\w8もう、\_w[72]あんな事しないでね。\_w[180]\w8\n私、\_w[36]顔から火が出ちゃいそうなくらい、\_w[288]恥ずかしかったのよ。\_w[180]\w8\n\n[half]\_w[18]\1\n[half]\_w[18]\0\s[106]･\w2･\w2･\w2･\w2･\w2･\w2･\w2･\w2\s[100]はい、\_w[126]おしまい。\_w[90]\w8\n旅人さんは何も見なかった、\_w[162]ね？\w8\_w[36]\e'
$dialogueValues = @{
    '2elf-2.46' = $twoElfBase
    'Earthquake Rescue Duo' = '\0\s[0]\1\s[10]\0\s[0]ヾ(•ω•`)o\w8\1\s[10]Haha, seems like Mantle is happy to see you!\e'
    'LOBO' = '\1\s[10]\0\s[0]\1\s[-1]\0Listen here, you little bruin, you listen to me.\w8\w8\w8 The bealusi that pardons, that is your fortune.'
    'Snake and Otacon V1.2.1' = '\0\s[0]\1\s[10]\0\s[5]Miss me?\e'
    'Snake and Otacon V1.3.1' = "\0\s[0]\1\s[10]\0\s[0]Evening. Getting pretty late now. Don't strain yourself, .\1\w8\s[10]Yeah, don't overwork yourself!\e"
    'Snake_Otacon_1.3.1b' = "\0\s[0]\1\s[10]\1\s[10]Have you considered going to bed soon, ? It's getting kind of late...\e"
    'Watchdog Bancho' = '\1\s[10]\0\s[0]\0\s[0]Hey, long time no see!'
}
$earthquakeValues = [ordered]@{
    prefix = '\0\s[0]\1\s[10]'
    earlyFirst = '\0\s[0]\1\s[10]\0\s[0]ヾ(•ω•`)o\w8\1\s[10]Haha, seems like Mantle is happy to see you!\e'
    earlySecond = "\0\s[0]\1\s[10]\1\s[10]You called? What's up ?\w8\0\s[0] *^___^*\e"
    earlyThird = '\0\s[0]\1\s[10]\1\s[10]Boy, it sure is early! Comes in the job description, eh?\w8\0\s[0](=￣ω￣=)\w8.\w8.\8w.\e'
    morningFirst = "\0\s[0]\1\s[10]\0\s[0](｡･∀･)ﾉﾞ\1\s[10]'Morning, !\e"
    morningSecond = "\0\s[0]\1\s[10]\1\s[10]Back on duty! Hey, ... How's life?\w8\0\s[0]o(*^▽^*)┛\e"
    lunchFirst = '\0\s[0]\1\s[10]\0\s[0]This is a lunch boot dialogue.\1\s[10]Sure is.\e'
    lunchSecond = "\0\s[0]\1\s[10]\1\s[10]Feeling hungry yet?\0\s[0]（︶^︶）\1\s[10]\n\n[half]Don't worry, we'll go out somewhere to eat sometime soon, Mantle!\e"
    afternoon = "\0\s[0]\1\s[10]\1\s[10]Oh hey ! Mantle and I were just talking about you! Speak of the devil...\w8\0\s[0](゜▽゜*)♪\e"
}
$earthquakeExpectedHashes = [ordered]@{
    prefix = '3c539a832cea838c1e680ca4a39cf246ad29d5cef0e63a73d11e8f7bf588aebe'
    earlyFirst = '42f216325f9097b96c363eab4487e39aeaad3cd0a3636844d5b0b7169d529f41'
    earlySecond = '98bb8892bf78daf8c7916618ed6bbd67fb8b0f3382a8c1509213032be6c7e3ea'
    earlyThird = '53160d1df44cb47ea1d5ba85d53f2c8d4d5f117193df5c4919eee34af2890f5a'
    morningFirst = '4d0323efee6567ba3ad00f1592d1a8a0be7ed4e7fda342716a90c235f16c2ccc'
    morningSecond = '7603a0d37144c53d365e136bed7812c984a290c52512f0755786276799ad2432'
    lunchFirst = 'cb3a32f1e00f62f87acea8f9b9e39e9fe384bdb2cf4dc28e3cbd8c1777c0e09f'
    lunchSecond = '34e6319406c393117d60cd0150ab82d23c098a41ea368773642d7503818a2826'
    afternoon = '8b224aac942863d28fe519e2640292f4f0a8aa841b602ba6aa9a4b1dadd17f29'
}
foreach ($earthquakeValueName in $earthquakeValues.Keys) {
    if ((Get-StringSha256 $earthquakeValues[$earthquakeValueName]) -cne $earthquakeExpectedHashes[$earthquakeValueName]) {
        throw "Earthquake source-authenticated value hash mismatch for '$earthquakeValueName'."
    }
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
    $updated = $text -replace '"count": 143', ('"count": ' + $Token)
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
        $onBootHour = if ($label -ceq 'Earthquake Rescue Duo') { '05' } else { '01' }
        $raw = [pscustomobject][ordered]@{
            schemaVersion = '2'
            label = $label
            sha256 = [string]$entry.sha256
            observedKind = [string]$entry.expectedKind
            narCorpusPath = "/data/user/0/com.cattailsw.nanidroid/cache/nar-corpus-host/$RunId/$safeLabel/nanidroid-corpus.nar"
            passed = $true
            classification = 'compatible'
            dialogueProbe = [pscustomobject]@{
                outcome = 'success'
                value = $value
                tokenizerDiagnostics = @()
                method = 'GET'
                eventId = 'OnBoot'
                status = 200
                failure = $null
                onBootContext = [pscustomobject]@{
                    profileState = 'fresh'
                    username = ''
                    birthdayConfigured = $false
                    localClockBefore = "2026-08-18T${onBootHour}:00:00-07:00"
                    localClockAfter = "2026-08-18T${onBootHour}:00:01-07:00"
                }
            }
            evidence = [pscustomobject]@{
                stable = $true
            }
            cleanup = [pscustomobject]@{ remainingTestOwnedPaths = @(); hostVerified = $true }
        }
        foreach ($lifecycle in @{
            installOutcome = 'installed'; ghostLoadOutcome = 'loaded'; renderOutcome = 'production-stage-rendered';
            inputOutcome = 'no-named-collisions'; shioriOutcome = 'success'; checkpointPhase = 'complete'
        }.GetEnumerator()) { $raw | Add-Member -NotePropertyName $lifecycle.Key -NotePropertyValue $lifecycle.Value }
        if ([string]$entry.expectedKind -cne 'ghost') {
            $raw.classification = 'unsupported'
            $raw.dialogueProbe = [pscustomobject]@{ outcome = 'not-applicable' }
            $raw.installOutcome = "unsupported:$([string]$entry.expectedKind)"
            $raw.ghostLoadOutcome = 'not-applicable'; $raw.renderOutcome = 'not-applicable'; $raw.inputOutcome = 'not-applicable'; $raw.shioriOutcome = 'not-applicable'; $raw.checkpointPhase = 'not-run'
            $raw | Add-Member -NotePropertyName surfaceCount -NotePropertyValue 0
            $raw | Add-Member -NotePropertyName parserDiagnostics -NotePropertyValue @(
                [pscustomobject]@{
                    observedKind = [string]$entry.expectedKind
                    planSuccess = $false
                    error = 'UNSUPPORTED_TYPE'
                    detail = [string]$entry.expectedKind
                }
            )
        }
        elseif ($compatibleGhostSuccessRules.Contains($label)) {
            $raw.dialogueProbe.eventId = $compatibleGhostSuccessRules[$label].eventId
            $raw.inputOutcome = $compatibleGhostSuccessRules[$label].inputOutcome
        }
        elseif ($installRejectedGhostRules.Contains($label)) {
            $raw.classification = 'incompatible'
            $raw.dialogueProbe = [pscustomobject]@{
                outcome = 'not-applicable:install-rejected'
                value = $null
                tokenizerDiagnostics = @()
                method = $null
                eventId = $null
                status = $null
                failure = $null
            }
            $raw.installOutcome = 'invalid-path'; $raw.ghostLoadOutcome = 'not-applicable:install-rejected'; $raw.renderOutcome = 'not-applicable:install-rejected'; $raw.inputOutcome = 'not-applicable:install-rejected'; $raw.shioriOutcome = 'not-applicable:install-rejected'; $raw.checkpointPhase = 'not-run'
        }
        elseif ($label -ceq $partialGhostRule.label) {
            $raw.classification = 'partiallyCompatible'
            $raw.dialogueProbe.outcome = 'not-supported-shiori'
            $raw.dialogueProbe.failure = $null
            $raw.inputOutcome = 'named-collisions-routed:4'; $raw.shioriOutcome = 'not-supported-shiori'
        }
        else {
            throw "Fixture has no reviewed ghost envelope rule for '$label'."
        }
        if ($label -in @('Snake and Otacon V1.2.1', 'Snake and Otacon V1.3.1', 'Snake_Otacon_1.3.1b')) {
            $canaryValue = '\0\s[0]\1\s[10]First boot canary.\e'
            $raw | Add-Member -NotePropertyName snakeOnBootStructuralSafety -NotePropertyValue ([pscustomobject][ordered]@{
                policy = 'snake-onboot-raw-sakurascript-v1'
                contentCompared = $false
                accepted = $true
                terminal = 'exact-e'
                allowedSurfaces = @(0, 1, 2, 4, 5, 8, 9, 10, 13, 14, 15, 17, 18, 19, 30, 32, 35)
                allowedFormattingTokens = @('\f[italic,true]', '\f[italic,false]')
            })
            $raw | Add-Member -NotePropertyName snakeFirstBootCanary -NotePropertyValue ([pscustomobject][ordered]@{
                freshInstance = $true
                independentInstanceCount = 2
                request = [pscustomobject][ordered]@{ method = 'GET'; eventId = 'OnFirstBoot'; references = @('0') }
                response = [pscustomobject][ordered]@{
                    status = 200
                    outcome = 'success'
                    failure = $null
                    value = $canaryValue
                    valueUtf8Sha256 = Get-StringSha256 $canaryValue
                    valueUtf8ByteLength = [Text.Encoding]::UTF8.GetByteCount($canaryValue)
                    tokenizerDiagnostics = @()
                }
            })
        }
        $scanRoot = "/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/$RunId/$safeLabel/probe-install/corpus-$(([string]$entry.sha256).Substring(0, 16))"
        if ($rawSourceLabels -contains $label) {
            $raw.evidence | Add-Member -NotePropertyName sourceSyntax -NotePropertyValue ([pscustomobject]@{ scanRoot = $scanRoot })
            $raw | Add-Member -NotePropertyName sakura -NotePropertyValue ([pscustomobject]@{ source = "$scanRoot/shell/master/surface0.png" })
            $raw | Add-Member -NotePropertyName kero -NotePropertyValue ([pscustomobject]@{ source = "$scanRoot/shell/master/surface10.png" })
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
        $row = [pscustomobject][ordered]@{
            label = $label
            safeLabel = $safeLabel
            sha256 = [string]$entry.sha256
            runId = $RunId
            passed = $true
            startedAt = $StartedAt
            finishedAt = $StartedAt
            classification = $raw.classification
            installOutcome = $raw.installOutcome
            ghostLoadOutcome = $raw.ghostLoadOutcome
            renderOutcome = $raw.renderOutcome
            inputOutcome = $raw.inputOutcome
            shioriOutcome = $raw.shioriOutcome
            runtimeCheckpointPhase = $raw.checkpointPhase
            requiredEvidence = @($entry.requiredEvidence)
            requiredEvidencePayload = $payload
            resultPath = "/sdcard/Android/data/com.cattailsw.nanidroid/files/nar-corpus/$safeLabel/result.json"
            screenshotPath = "/sdcard/Android/data/com.cattailsw.nanidroid/files/nar-corpus/$safeLabel/screenshot.png"
            crashLogPath = (Join-Path $Root "$safeLabel\crash-log.txt")
            status = 'ok'
            dialogueOutcome = $raw.dialogueProbe.outcome
            output = "Time: 1.0 run=$RunId"
            error = ''
            cleanup = [pscustomobject]@{ remainingTestOwnedPaths = @(); hostVerified = $true }
            postCleanupPrivateSnapshot = @()
            postCleanupOutputSnapshot = @()
            postCleanupTmpSnapshot = @()
            observedPrivateSnapshot = "/data/user/0/com.cattailsw.nanidroid/cache/nar-corpus-host/$RunId/$safeLabel"
            observedTmpSnapshot = "/data/local/tmp/nanidroid-corpus/$RunId/$safeLabel"
        }
        if ($rawSourceLabels -contains $label) {
            $row | Add-Member -NotePropertyName evidence -NotePropertyValue ([pscustomobject]@{
                sourceSyntax = [pscustomobject]@{ scanRoot = $scanRoot }
            })
        }
        if ($null -ne $raw.PSObject.Properties['snakeOnBootStructuralSafety']) {
            $row | Add-Member -NotePropertyName snakeOnBootStructuralSafety -NotePropertyValue $raw.snakeOnBootStructuralSafety
            $row | Add-Member -NotePropertyName snakeFirstBootCanary -NotePropertyValue $raw.snakeFirstBootCanary
        }
        $rows.Add($row)
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
        schemaVersion = '2'
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
        [string]$SelectedManifestPath = $manifestPath,
        [string]$ContractPath = $contractPath,
        [string]$ExpectedCandidateCommit = $baseCommit,
        [string]$ExpectedCandidateDebugSha = $baseDebugSha,
        [string]$OutputPath = (Join-Path $fixtureRoot 'comparison.json')
    )
    $arguments = @(
        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $comparatorPath,
        '-ComparisonKind', $Kind,
        '-BaseRoot', (Join-Path $fixtureRoot 'base'),
        '-CandidateRoot', (Join-Path $fixtureRoot 'candidate'),
        '-ManifestPath', $SelectedManifestPath,
        '-ContractPath', $ContractPath,
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

function Invoke-RunnerDryRun([string]$SelectedManifestPath, [string[]]$CorpusRoots = @()) {
    $relativeManifestPath = [IO.Path]::GetRelativePath($repoRoot, $SelectedManifestPath)
    if ($CorpusRoots.Count -eq 0) { $CorpusRoots = @((Join-Path $fixtureRoot 'intentionally-missing-corpus-root')) }
    $scriptBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((Join-Path $PSScriptRoot 'run-nar-corpus-audit.ps1')))
    $manifestBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($relativeManifestPath))
    $rootsBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(($CorpusRoots | ConvertTo-Json -Compress)))
    $command = @"
`$scriptPath = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$scriptBase64'))
`$manifestPath = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$manifestBase64'))
`$rootsJson = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$rootsBase64'))
`$roots = @(`$rootsJson | ConvertFrom-Json)
try {
    & `$scriptPath -DryRun -ManifestPath `$manifestPath -CorpusRoots `$roots
    exit `$LASTEXITCODE
}
catch {
    [Console]::Error.WriteLine(`$_.Exception.Message)
    exit 1
}
"@
    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $output = & pwsh -NoProfile -NonInteractive -OutputFormat Text -EncodedCommand $encodedCommand 2>&1 | Out-String
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

function Assert-FailBeforeCorpusRootResolution([string]$Name, [object]$Result, [string]$ExpectedDiagnostic) {
    if ($Result.ExitCode -eq 0 -or -not $Result.Output.Contains($ExpectedDiagnostic, [StringComparison]::Ordinal)) {
        throw "$Name expected exact diagnostic '$ExpectedDiagnostic', exit $($Result.ExitCode): $($Result.Output)"
    }
    $rootResolutionDiagnostic = 'No valid CorpusRoots were resolved.'
    if ($Result.Output.Contains($rootResolutionDiagnostic, [StringComparison]::Ordinal)) {
        throw "$Name reached corpus-root resolution after the expected reviewed-manifest diagnostic: $($Result.Output)"
    }
    Write-Host "PASS: $Name"
}

function Save-Json([string]$Path, [scriptblock]$Mutation) {
    $value = Get-Json $Path
    & $Mutation $value
    Write-Json -Path $Path -Value $value
}

function Set-EarthquakeFixture {
    param(
        [string]$Side,
        [string]$DialogueValue,
        [string[]]$Diagnostics,
        [string]$Before,
        [string]$After
    )
    $path = Join-Path $fixtureRoot "$Side\Earthquake-Rescue-Duo\result.json"
    $raw = Get-Json $path
    $raw.dialogueProbe.value = $DialogueValue
    $raw.dialogueProbe.tokenizerDiagnostics = @($Diagnostics)
    $raw.dialogueProbe.onBootContext.localClockBefore = $Before
    $raw.dialogueProbe.onBootContext.localClockAfter = $After
    Write-Json -Path $path -Value $raw
}

function Set-OnBootClockMirrors([string]$Side, [string]$Label, [string]$Before, [string]$After) {
    $safeLabel = ConvertTo-SafeLabel $Label
    Save-Json (Join-Path $fixtureRoot "$Side\$safeLabel\result.json") { param($raw)
        $raw.dialogueProbe.onBootContext.localClockBefore = $Before
        $raw.dialogueProbe.onBootContext.localClockAfter = $After
    }
    $mirrorCount = 0
    $summaryPath = Join-Path $fixtureRoot "$Side\summary.json"
    $summary = Get-Json $summaryPath
    $row = Get-Row $summary $Label
    foreach ($probeProperty in @(
        $row.PSObject.Properties['dialogueProbe'],
        $row.requiredEvidencePayload.PSObject.Properties['dialogueProbe']
    )) {
        if ($null -eq $probeProperty) { continue }
        $probeProperty.Value.onBootContext.localClockBefore = $Before
        $probeProperty.Value.onBootContext.localClockAfter = $After
        $mirrorCount++
    }
    if ($mirrorCount -lt 1) { throw "OnBoot timestamp fixture '$Label' has no summary evidence mirror." }
    Write-Json -Path $summaryPath -Value $summary
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

function Set-ManifestAndEvidenceLabel([string]$SelectedManifestPath, [string]$OldLabel, [string]$NewLabel) {
    $oldSafeLabel = ConvertTo-SafeLabel $OldLabel
    $newSafeLabel = ConvertTo-SafeLabel $NewLabel
    Save-Json $SelectedManifestPath { param($m)
        @($m.entries | Where-Object label -CEQ $OldLabel)[0].label = $NewLabel
    }
    $selectedManifestSha = (Get-FileHash -LiteralPath $SelectedManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    foreach ($side in @('base', 'candidate')) {
        $oldRawRoot = Join-Path $fixtureRoot "$side\$oldSafeLabel"
        $newRawRoot = Join-Path $fixtureRoot "$side\$newSafeLabel"
        Move-Item -LiteralPath $oldRawRoot -Destination $newRawRoot
        Move-Item -LiteralPath (Join-Path $fixtureRoot "$side\screenshots\$oldSafeLabel.png") -Destination (Join-Path $fixtureRoot "$side\screenshots\$newSafeLabel.png")
        Save-Json (Join-Path $newRawRoot 'result.json') { param($raw)
            $raw.label = $NewLabel
            $raw.narCorpusPath = $raw.narCorpusPath -replace ([regex]::Escape("/$oldSafeLabel/")), "/$newSafeLabel/"
        }
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($summary)
            $summary.manifestSha256 = $selectedManifestSha
            $row = Get-Row $summary $OldLabel
            $row.label = $NewLabel
            $row.safeLabel = $newSafeLabel
            $row.resultPath = $row.resultPath -replace ([regex]::Escape("/$oldSafeLabel/")), "/$newSafeLabel/"
            $row.screenshotPath = $row.screenshotPath -replace ([regex]::Escape("/$oldSafeLabel/")), "/$newSafeLabel/"
            $row.crashLogPath = $row.crashLogPath -replace ([regex]::Escape("\$oldSafeLabel\")), "\$newSafeLabel\"
            $row.observedPrivateSnapshot = $row.observedPrivateSnapshot -replace ([regex]::Escape("/$oldSafeLabel") + '$'), "/$newSafeLabel"
            $row.observedTmpSnapshot = $row.observedTmpSnapshot -replace ([regex]::Escape("/$oldSafeLabel") + '$'), "/$newSafeLabel"
        }
    }
}

function Set-ManifestAllowAndEvidenceHash([string]$SelectedManifestPath, [string]$Label, [scriptblock]$Mutation) {
    $allowMutation = $Mutation
    Save-Json $SelectedManifestPath { param($m)
        $entry = @($m.entries | Where-Object label -CEQ $Label)[0]
        & $allowMutation $entry
    }
    $selectedManifestSha = (Get-FileHash -LiteralPath $SelectedManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($summary)
            $summary.manifestSha256 = $selectedManifestSha
        }
    }
}

function Set-AcceptedNativeCheckpointFixture([string]$Side, [string]$Label, [string]$ShellName) {
    $safeLabel = ConvertTo-SafeLabel $Label
    $rawPath = Join-Path $fixtureRoot "$Side\$safeLabel\result.json"
    $raw = Get-Json $rawPath
    $checkpoint = [pscustomobject][ordered]@{
        schemaVersion = '2'
        label = $Label
        sha256 = [string]$raw.sha256
        archiveBytes = 4096
        narCorpusPath = [string]$raw.narCorpusPath
        passed = $true
        classification = 'incompatible'
        installOutcome = 'installed'
        ghostLoadOutcome = 'surface-loaded'
        renderOutcome = 'production-stage-rendered'
        inputOutcome = [string]$compatibleGhostSuccessRules[$Label].inputOutcome
        shioriOutcome = 'pending-real-shiori'
        surfaceCount = 2
        namedCollisionProbes = @()
        dialogueProbe = [pscustomobject][ordered]@{
            outcome = 'pending-real-shiori'
            module = $null
            status = $null
            value = $null
            observedAnchorId = $null
            observedInputId = $null
            passiveTransitions = @()
            method = 'GET'
            eventId = 'OnBoot'
            references = @($ShellName)
            tokenizerDiagnostics = @()
            failure = $null
        }
        checkpointPhase = 'before-real-shiori'
        evidence = $raw.evidence
        observedKind = 'ghost'
        parserDiagnostics = @([pscustomobject]@{ observedKind = 'ghost'; planSuccess = $true; error = $null; detail = 'fixture' })
        installProgress = @([pscustomobject]@{ phase = 'extract'; completed = 4096 })
        installedTargetId = "corpus-$(([string]$raw.sha256).Substring(0, 16))"
        surfaceKeys = @('0', '10')
        sakura = $raw.sakura
        kero = $raw.kero
    }
    Write-Json -Path $rawPath -Value $checkpoint

    Save-Json (Join-Path $fixtureRoot "$Side\summary.json") { param($summary)
        $row = Get-Row $summary $Label
        $row.passed = $true
        $row | Add-Member -NotePropertyName archiveBytes -NotePropertyValue $checkpoint.archiveBytes -Force
        $row.classification = $checkpoint.classification
        foreach ($field in @('installOutcome', 'ghostLoadOutcome', 'renderOutcome', 'inputOutcome', 'shioriOutcome')) {
            $row.$field = $checkpoint.$field
        }
        $row.runtimeCheckpointPhase = $checkpoint.checkpointPhase
        $row.dialogueOutcome = $checkpoint.dialogueProbe.outcome
        $row | Add-Member -NotePropertyName parserDiagnostics -NotePropertyValue $checkpoint.parserDiagnostics -Force
        $row | Add-Member -NotePropertyName evidence -NotePropertyValue $checkpoint.evidence -Force
        $payload = [pscustomobject][ordered]@{}
        foreach ($requiredName in @($row.requiredEvidence)) {
            $payload | Add-Member -NotePropertyName ([string]$requiredName) -NotePropertyValue $checkpoint.([string]$requiredName)
        }
        $row.requiredEvidencePayload = $payload
        $row | Add-Member -NotePropertyName nativeCrash -NotePropertyValue $true -Force
        $row | Add-Member -NotePropertyName nativeCrashEvidence -NotePropertyValue "Accepted native crash marker for ${Label}: target process+SIGSEGV+libkawari8" -Force
        $row | Add-Member -NotePropertyName crashEvidence -NotePropertyValue 'com.cattailsw.nanidroid SIGSEGV libkawari8.so' -Force
        $row.observedPrivateSnapshot = @()
        $row | Add-Member -NotePropertyName observedOutputSnapshot -NotePropertyValue @() -Force
        $row.observedTmpSnapshot = @()
    }
}

try {
    Reset-Fixtures
    Assert-Pass 'exact successful base/base evidence' (Invoke-Comparator (Get-ComparatorArguments))

    Reset-Fixtures
    $comparatorAddedAllowManifestPath = Join-Path $fixtureRoot 'comparator-added-native-allow-manifest.json'
    Copy-Item -LiteralPath $manifestPath -Destination $comparatorAddedAllowManifestPath
    Set-ManifestAllowAndEvidenceHash $comparatorAddedAllowManifestPath 'tewire-sen' { param($entry)
        $entry | Add-Member -NotePropertyName allowNativeKawariCrash -NotePropertyValue $true
    }
    $comparatorAddedAllowResult = Invoke-Comparator (Get-ComparatorArguments -SelectedManifestPath $comparatorAddedAllowManifestPath)

    Reset-Fixtures
    $runnerAddedAllowManifestPath = Join-Path $fixtureRoot 'runner-added-native-allow-manifest.json'
    Copy-Item -LiteralPath $manifestPath -Destination $runnerAddedAllowManifestPath
    Save-Json $runnerAddedAllowManifestPath { param($m)
        @($m.entries | Where-Object label -CEQ 'tewire-sen')[0] |
            Add-Member -NotePropertyName allowNativeKawariCrash -NotePropertyValue $true
    }
    $missingAddedAllowCorpusRoot = Join-Path $fixtureRoot 'intentionally-missing-added-native-allow-corpus-root'
    $runnerAddedAllowResult = Invoke-RunnerDryRun $runnerAddedAllowManifestPath @($missingAddedAllowCorpusRoot)

    Reset-Fixtures
    $comparatorRemovedAllowManifestPath = Join-Path $fixtureRoot 'comparator-removed-native-allow-manifest.json'
    Copy-Item -LiteralPath $manifestPath -Destination $comparatorRemovedAllowManifestPath
    Set-ManifestAllowAndEvidenceHash $comparatorRemovedAllowManifestPath 'LOBO' { param($entry)
        $entry.PSObject.Properties.Remove('allowNativeKawariCrash')
    }
    $comparatorRemovedAllowResult = Invoke-Comparator (Get-ComparatorArguments -SelectedManifestPath $comparatorRemovedAllowManifestPath)

    Reset-Fixtures
    $runnerRemovedAllowManifestPath = Join-Path $fixtureRoot 'runner-removed-native-allow-manifest.json'
    Copy-Item -LiteralPath $manifestPath -Destination $runnerRemovedAllowManifestPath
    Save-Json $runnerRemovedAllowManifestPath { param($m)
        @($m.entries | Where-Object label -CEQ 'LOBO')[0].PSObject.Properties.Remove('allowNativeKawariCrash')
    }
    $missingRemovedAllowCorpusRoot = Join-Path $fixtureRoot 'intentionally-missing-removed-native-allow-corpus-root'
    $runnerRemovedAllowResult = Invoke-RunnerDryRun $runnerRemovedAllowManifestPath @($missingRemovedAllowCorpusRoot)

    Reset-Fixtures
    $comparatorFalseAllowManifestPath = Join-Path $fixtureRoot 'comparator-false-native-allow-manifest.json'
    Copy-Item -LiteralPath $manifestPath -Destination $comparatorFalseAllowManifestPath
    Set-ManifestAllowAndEvidenceHash $comparatorFalseAllowManifestPath 'tewire-sen' { param($entry)
        $entry | Add-Member -NotePropertyName allowNativeKawariCrash -NotePropertyValue $false
    }
    $comparatorFalseAllowResult = Invoke-Comparator (Get-ComparatorArguments -SelectedManifestPath $comparatorFalseAllowManifestPath)

    Reset-Fixtures
    $runnerFalseAllowManifestPath = Join-Path $fixtureRoot 'runner-false-native-allow-manifest.json'
    Copy-Item -LiteralPath $manifestPath -Destination $runnerFalseAllowManifestPath
    Save-Json $runnerFalseAllowManifestPath { param($m)
        @($m.entries | Where-Object label -CEQ 'tewire-sen')[0] |
            Add-Member -NotePropertyName allowNativeKawariCrash -NotePropertyValue $false
    }
    $missingFalseAllowCorpusRoot = Join-Path $fixtureRoot 'intentionally-missing-false-native-allow-corpus-root'
    $runnerFalseAllowResult = Invoke-RunnerDryRun $runnerFalseAllowManifestPath @($missingFalseAllowCorpusRoot)

    Reset-Fixtures
    $comparatorArrayAllowManifestPath = Join-Path $fixtureRoot 'comparator-array-native-allow-manifest.json'
    Copy-Item -LiteralPath $manifestPath -Destination $comparatorArrayAllowManifestPath
    Set-ManifestAllowAndEvidenceHash $comparatorArrayAllowManifestPath 'tewire-sen' { param($entry)
        $entry | Add-Member -NotePropertyName allowNativeKawariCrash -NotePropertyValue ([object[]]@($true))
    }
    $comparatorArrayAllowResult = Invoke-Comparator (Get-ComparatorArguments -SelectedManifestPath $comparatorArrayAllowManifestPath)

    Reset-Fixtures
    $runnerArrayAllowManifestPath = Join-Path $fixtureRoot 'runner-array-native-allow-manifest.json'
    Copy-Item -LiteralPath $manifestPath -Destination $runnerArrayAllowManifestPath
    Save-Json $runnerArrayAllowManifestPath { param($m)
        @($m.entries | Where-Object label -CEQ 'tewire-sen')[0] |
            Add-Member -NotePropertyName allowNativeKawariCrash -NotePropertyValue ([object[]]@($true))
    }
    $missingArrayAllowCorpusRoot = Join-Path $fixtureRoot 'intentionally-missing-array-native-allow-corpus-root'
    $runnerArrayAllowResult = Invoke-RunnerDryRun $runnerArrayAllowManifestPath @($missingArrayAllowCorpusRoot)

    Assert-Fail 'comparator binds an added true native-crash allow flag with matching manifest evidence' $comparatorAddedAllowResult 'reviewed manifest true allowNativeKawariCrash count mismatch: expected 6, found 7'
    Assert-FailBeforeCorpusRootResolution `
        'runner binds an added true native-crash allow flag before corpus-root resolution' `
        $runnerAddedAllowResult `
        'validation: Reviewed manifest true allowNativeKawariCrash count mismatch: expected 6, found 7.'
    Assert-Fail 'comparator binds removal of a reviewed true native-crash allow flag' $comparatorRemovedAllowResult 'reviewed manifest true allowNativeKawariCrash count mismatch: expected 6, found 5'
    Assert-FailBeforeCorpusRootResolution `
        'runner binds removal of a reviewed true native-crash allow flag before corpus-root resolution' `
        $runnerRemovedAllowResult `
        'validation: Reviewed manifest true allowNativeKawariCrash count mismatch: expected 6, found 5.'
    Assert-Fail 'comparator distinguishes an explicit false native-crash allow flag from absence' $comparatorFalseAllowResult 'reviewed manifest tuple digest mismatch'
    Assert-FailBeforeCorpusRootResolution `
        'runner distinguishes an explicit false native-crash allow flag from absence before corpus-root resolution' `
        $runnerFalseAllowResult `
        'validation: Reviewed manifest tuple digest mismatch: expected bf8398cc1a96f8ec57b0762cb3422ba357819a76bcbb7659c058f5c96bbab8fc, found 569ee6ead55af028b370c15bb5089de6ce0f99e0b21e54fab6b1708fb6c24316.'
    Assert-Fail 'comparator rejects a nonboolean native-crash allow flag during manifest preflight' $comparatorArrayAllowResult "reviewed manifest entry.*allowNativeKawariCrash has JSON kind 'array'; expected boolean"
    Assert-FailBeforeCorpusRootResolution `
        'runner rejects a nonboolean native-crash allow flag before corpus-root resolution' `
        $runnerArrayAllowResult `
        "validation: Reviewed manifest entry 'tewire-sen' allowNativeKawariCrash must be a JSON boolean when present."

    Reset-Fixtures
    $substitutedManifestPath = Join-Path $fixtureRoot 'substituted-non-ghost-manifest.json'
    Copy-Item -LiteralPath $manifestPath -Destination $substitutedManifestPath
    $substitutedLabel = 'Substituted Haiidrate'
    $substitutedSafeLabel = ConvertTo-SafeLabel $substitutedLabel
    $substitutedSha = 'f' * 64
    $substitutedKind = 'balloon'
    Save-Json $substitutedManifestPath { param($m)
        $entry = @($m.entries | Where-Object label -CEQ 'Haiidrate')[0]
        $entry.label = $substitutedLabel
        $entry.sha256 = $substitutedSha
        $entry.expectedKind = $substitutedKind
    }
    $substitutedManifestSha = (Get-FileHash -LiteralPath $substitutedManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    foreach ($side in @('base', 'candidate')) {
        $oldRawRoot = Join-Path $fixtureRoot "$side\Haiidrate"
        $newRawRoot = Join-Path $fixtureRoot "$side\$substitutedSafeLabel"
        Move-Item -LiteralPath $oldRawRoot -Destination $newRawRoot
        Move-Item -LiteralPath (Join-Path $fixtureRoot "$side\screenshots\Haiidrate.png") -Destination (Join-Path $fixtureRoot "$side\screenshots\$substitutedSafeLabel.png")
        Save-Json (Join-Path $newRawRoot 'result.json') { param($raw)
            $raw.label = $substitutedLabel
            $raw.sha256 = $substitutedSha
            $raw.observedKind = $substitutedKind
            $raw.narCorpusPath = $raw.narCorpusPath -replace '/Haiidrate/', "/$substitutedSafeLabel/"
            $raw.installOutcome = "unsupported:$substitutedKind"
            $raw.parserDiagnostics[0].observedKind = $substitutedKind
            $raw.parserDiagnostics[0].detail = $substitutedKind
        }
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($summary)
            $summary.manifestSha256 = $substitutedManifestSha
            $row = Get-Row $summary 'Haiidrate'
            $row.label = $substitutedLabel
            $row.safeLabel = $substitutedSafeLabel
            $row.sha256 = $substitutedSha
            $row.installOutcome = "unsupported:$substitutedKind"
            $row.resultPath = $row.resultPath -replace '/Haiidrate/', "/$substitutedSafeLabel/"
            $row.screenshotPath = $row.screenshotPath -replace '/Haiidrate/', "/$substitutedSafeLabel/"
            $row.crashLogPath = $row.crashLogPath -replace '\\Haiidrate\\', "\$substitutedSafeLabel\"
            $row.observedPrivateSnapshot = $row.observedPrivateSnapshot -replace '/Haiidrate$', "/$substitutedSafeLabel"
            $row.observedTmpSnapshot = $row.observedTmpSnapshot -replace '/Haiidrate$', "/$substitutedSafeLabel"
            $row.requiredEvidencePayload.installOutcome = "unsupported:$substitutedKind"
            $row.requiredEvidencePayload.parserDiagnostics[0].observedKind = $substitutedKind
            $row.requiredEvidencePayload.parserDiagnostics[0].detail = $substitutedKind
        }
    }
    Assert-Fail 'matching substituted non-ghost manifest and evidence cannot replace the reviewed tuple' (Invoke-Comparator (Get-ComparatorArguments -SelectedManifestPath $substitutedManifestPath)) 'reviewed manifest tuple digest mismatch'

    Reset-Fixtures
    $softHyphenManifestPath = Join-Path $fixtureRoot 'comparator-soft-hyphen-manifest.json'
    Copy-Item -LiteralPath $manifestPath -Destination $softHyphenManifestPath
    $softHyphenHaiidrate = "Hai$([char]0x00AD)idrate"
    Set-ManifestAndEvidenceLabel $softHyphenManifestPath 'Haiidrate' $softHyphenHaiidrate
    Assert-Fail 'comparator manifest tuple binding is ordinal for a soft-hyphen label mutation' (Invoke-Comparator (Get-ComparatorArguments -SelectedManifestPath $softHyphenManifestPath)) 'reviewed manifest tuple digest mismatch'

    foreach ($nonGhostManifestKindMutation in @(
        @{ name = 'label'; mutation = { param($entry) $entry.label = @('Haiidrate') } },
        @{ name = 'archive SHA'; mutation = { param($entry) $entry.sha256 = @('ba7f9b6d191a47491721892ce69ce4fa7cd8dbe61c8cbf28a23ede666937b685') } },
        @{ name = 'expected kind'; mutation = { param($entry) $entry.expectedKind = @('shell') } }
    )) {
        Reset-Fixtures
        $kindManifestPath = Join-Path $fixtureRoot ("comparator-non-ghost-kind-$($nonGhostManifestKindMutation.name -replace '[^A-Za-z0-9]', '-').json")
        Copy-Item -LiteralPath $manifestPath -Destination $kindManifestPath
        Save-Json $kindManifestPath { param($m)
            $entry = @($m.entries | Where-Object { [string]$_.label -ceq 'Haiidrate' })[0]
            & $nonGhostManifestKindMutation.mutation $entry
        }
        Assert-Fail "comparator rejects non-string non-ghost $($nonGhostManifestKindMutation.name)" (Invoke-Comparator (Get-ComparatorArguments -SelectedManifestPath $kindManifestPath)) 'reviewed manifest entry.*has JSON kind.*array'
    }

    foreach ($runnerManifestMutation in @(
        @{ name = 'label'; mutation = { param($entry) $entry.label = 'Substituted Haiidrate' } },
        @{ name = 'archive SHA'; mutation = { param($entry) $entry.sha256 = 'f' * 64 } },
        @{ name = 'expected kind'; mutation = { param($entry) $entry.expectedKind = 'balloon' } }
    )) {
        Reset-Fixtures
        $runnerManifestPath = Join-Path $fixtureRoot ("runner-wrong-non-ghost-$($runnerManifestMutation.name -replace '[^A-Za-z0-9]', '-').json")
        Copy-Item -LiteralPath $manifestPath -Destination $runnerManifestPath
        Save-Json $runnerManifestPath { param($m)
            $entry = @($m.entries | Where-Object label -CEQ 'Haiidrate')[0]
            & $runnerManifestMutation.mutation $entry
        }
        Assert-Fail "runner DryRun preflight rejects wrong non-ghost $($runnerManifestMutation.name)" (Invoke-RunnerDryRun $runnerManifestPath) 'reviewed non-ghost manifest tuple set'
    }

    Reset-Fixtures
    $runnerSoftHyphenManifestPath = Join-Path $fixtureRoot 'runner-soft-hyphen-manifest.json'
    Copy-Item -LiteralPath $manifestPath -Destination $runnerSoftHyphenManifestPath
    Save-Json $runnerSoftHyphenManifestPath { param($m)
        @($m.entries | Where-Object label -CEQ 'Haiidrate')[0].label = $softHyphenHaiidrate
    }
    Assert-Fail 'runner DryRun manifest tuple binding is ordinal for a soft-hyphen label mutation' (Invoke-RunnerDryRun $runnerSoftHyphenManifestPath) 'reviewed non-ghost manifest tuple set'

    Reset-Fixtures
    $removedGhostManifestPath = Join-Path $fixtureRoot 'runner-removed-ghost-manifest.json'
    Copy-Item -LiteralPath $manifestPath -Destination $removedGhostManifestPath
    Save-Json $removedGhostManifestPath { param($m)
        $m.entries = @($m.entries | Where-Object label -CNE 'tewire-sen')
    }
    $missingRemovedGhostCorpusRoot = Join-Path $fixtureRoot 'intentionally-missing-removed-ghost-corpus-root'
    Assert-FailBeforeCorpusRootResolution `
        'runner reviewed preflight rejects a removed ghost before corpus-root resolution' `
        (Invoke-RunnerDryRun $removedGhostManifestPath @($missingRemovedGhostCorpusRoot)) `
        'validation: Reviewed manifest entry count mismatch: expected 23, found 22.'

    Reset-Fixtures
    $substitutedGhostManifestPath = Join-Path $fixtureRoot 'runner-substituted-ghost-manifest.json'
    Copy-Item -LiteralPath $manifestPath -Destination $substitutedGhostManifestPath
    Save-Json $substitutedGhostManifestPath { param($m)
        $entry = @($m.entries | Where-Object label -CEQ 'tewire-sen')[0]
        $entry.label = 'Substituted tewire-sen'
        $entry.sha256 = 'f' * 64
        $entry.expectedKind = 'ghost'
    }
    $missingSubstitutedGhostCorpusRoot = Join-Path $fixtureRoot 'intentionally-missing-substituted-ghost-corpus-root'
    Assert-FailBeforeCorpusRootResolution `
        'runner reviewed preflight rejects a substituted ghost tuple before corpus-root resolution' `
        (Invoke-RunnerDryRun $substitutedGhostManifestPath @($missingSubstitutedGhostCorpusRoot)) `
        'validation: Reviewed manifest tuple digest mismatch: expected bf8398cc1a96f8ec57b0762cb3422ba357819a76bcbb7659c058f5c96bbab8fc, found 62eef3f2d010c9886c67cc9340d1e7a50ad983118e3452e25f1e7d88689416bc.'

    foreach ($runnerManifestKindMutation in @(
        @{ name = 'label'; mutation = { param($entry) $entry.label = @('Haiidrate') } },
        @{ name = 'archive SHA'; mutation = { param($entry) $entry.sha256 = @('ba7f9b6d191a47491721892ce69ce4fa7cd8dbe61c8cbf28a23ede666937b685') } },
        @{ name = 'expected kind'; mutation = { param($entry) $entry.expectedKind = @('shell') } }
    )) {
        Reset-Fixtures
        $runnerManifestPath = Join-Path $fixtureRoot ("runner-non-ghost-kind-$($runnerManifestKindMutation.name -replace '[^A-Za-z0-9]', '-').json")
        Copy-Item -LiteralPath $manifestPath -Destination $runnerManifestPath
        Save-Json $runnerManifestPath { param($m)
            $entry = @($m.entries | Where-Object { [string]$_.label -ceq 'Haiidrate' })[0]
            & $runnerManifestKindMutation.mutation $entry
        }
        Assert-Fail "runner DryRun preflight rejects non-string non-ghost $($runnerManifestKindMutation.name)" (Invoke-RunnerDryRun $runnerManifestPath) 'reviewed non-ghost manifest entry.*must be a JSON string'
    }

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) (Get-Row $s 'Haiidrate').label = @('Haiidrate') }
    }
    Assert-Fail 'summary labels reject matching singleton arrays on both sides' (Invoke-Comparator (Get-ComparatorArguments)) "summary\.results\[\]\.label has JSON kind 'array'"

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s 'Haiidrate').label = @('Haiidrate') }
    Assert-Fail 'summary labels reject a singleton array on one side' (Invoke-Comparator (Get-ComparatorArguments)) "summary\.results\[\]\.label has JSON kind 'array'"

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.results[1].label = [string]$s.results[0].label }
    Assert-Fail 'summary labels reject duplicate exact strings' (Invoke-Comparator (Get-ComparatorArguments)) 'summary label set|duplicates'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s 'Haiidrate').label = 1 }
    Assert-Fail 'summary labels reject non-string scalars' (Invoke-Comparator (Get-ComparatorArguments)) "summary\.results\[\]\.label has JSON kind 'number'"

    foreach ($summaryIdentityKindMutation in @(
        @{ name = 'production commit'; field = 'production identity commit'; mutation = { param($s) $s.production.commit = @([string]$s.production.commit) } },
        @{ name = 'harness runner SHA'; field = 'harness identity runner SHA'; mutation = { param($s) $s.harness.runnerSha256 = @([string]$s.harness.runnerSha256) } },
        @{ name = 'legacy harness commit'; field = 'legacy harness identity commit'; mutation = { param($s) $s.git.commit = @([string]$s.git.commit) } },
        @{ name = 'legacy test APK SHA'; field = 'legacy test APK SHA'; mutation = { param($s) $s.apks.testSha256 = @([string]$s.apks.testSha256) } },
        @{ name = 'manifest SHA'; field = 'manifest SHA'; mutation = { param($s) $s.manifestSha256 = @([string]$s.manifestSha256) } },
        @{ name = 'cleanup verification'; field = 'cleanupVerification'; mutation = { param($s) $s.cleanupVerification = @([string]$s.cleanupVerification) } }
    )) {
        Reset-Fixtures
        Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) & $summaryIdentityKindMutation.mutation $s }
        Assert-Fail "summary identity rejects singleton-array $($summaryIdentityKindMutation.name)" (Invoke-Comparator (Get-ComparatorArguments)) "$($summaryIdentityKindMutation.field).*has JSON kind 'array'"
    }

    foreach ($rowIdentityKindMutation in @(
        @{ name = 'summary SHA'; field = 'summary archive SHA'; summary = { param($row) $row.sha256 = @([string]$row.sha256) }; raw = $null },
        @{ name = 'summary safe label'; field = 'summary safeLabel'; summary = { param($row) $row.safeLabel = @([string]$row.safeLabel) }; raw = $null },
        @{ name = 'raw label'; field = 'raw label'; summary = $null; raw = { param($raw) $raw.label = @([string]$raw.label) } },
        @{ name = 'raw SHA'; field = 'raw archive SHA'; summary = $null; raw = { param($raw) $raw.sha256 = @([string]$raw.sha256) } },
        @{ name = 'required-evidence member'; field = 'requiredEvidence member'; summary = { param($row) $row.requiredEvidence[0] = @([string]$row.requiredEvidence[0]) }; raw = $null }
    )) {
        Reset-Fixtures
        foreach ($side in @('base', 'candidate')) {
            if ($null -ne $rowIdentityKindMutation.summary) {
                Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) & $rowIdentityKindMutation.summary (Get-Row $s 'Haiidrate') }
            }
            if ($null -ne $rowIdentityKindMutation.raw) {
                Save-Json (Join-Path $fixtureRoot "$side\Haiidrate\result.json") { param($r) & $rowIdentityKindMutation.raw $r }
            }
        }
        Assert-Fail "evidence identity rejects singleton-array $($rowIdentityKindMutation.name)" (Invoke-Comparator (Get-ComparatorArguments)) "$($rowIdentityKindMutation.field).*has JSON kind 'array'"
    }

    foreach ($nonGhostLabel in @('Haiidrate', 'Hareraiser', 'Kitsune no Ocha', 'The Petpet Puddle')) {
        Reset-Fixtures
        $safeLabel = ConvertTo-SafeLabel $nonGhostLabel
        foreach ($side in @('base', 'candidate')) {
            Save-Json (Join-Path $fixtureRoot "$side\$safeLabel\result.json") { param($r) $r.checkpointPhase = 'complete' }
            Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) (Get-Row $s $nonGhostLabel).runtimeCheckpointPhase = 'complete' }
        }
        Assert-Fail "non-ghost envelope requires not-run checkpoint phase for $nonGhostLabel" (Invoke-Comparator (Get-ComparatorArguments)) "raw result '$([regex]::Escape($nonGhostLabel))'\.checkpointPhase"
    }

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Haiidrate\result.json') { param($r) $r.PSObject.Properties.Remove('checkpointPhase') }
    Assert-Fail 'non-ghost envelope requires raw checkpoint phase' (Invoke-Comparator (Get-ComparatorArguments)) "missing.*checkpointPhase"

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s 'Haiidrate').PSObject.Properties.Remove('runtimeCheckpointPhase') }
    Assert-Fail 'non-ghost envelope requires summary checkpoint phase' (Invoke-Comparator (Get-ComparatorArguments)) "missing.*runtimeCheckpointPhase"

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\Haiidrate\result.json") { param($r) $r.checkpointPhase = @('not-run') }
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) (Get-Row $s 'Haiidrate').runtimeCheckpointPhase = @('not-run') }
    }
    Assert-Fail 'non-ghost envelope rejects non-string raw checkpoint phase' (Invoke-Comparator (Get-ComparatorArguments)) "checkpointPhase has JSON kind 'array'"

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) (Get-Row $s 'Haiidrate').runtimeCheckpointPhase = @('not-run') }
    }
    Assert-Fail 'non-ghost envelope rejects non-string summary checkpoint phase' (Invoke-Comparator (Get-ComparatorArguments)) "runtimeCheckpointPhase.*has JSON kind 'array'"

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s 'Haiidrate').runtimeCheckpointPhase = 'complete' }
    Assert-Fail 'non-ghost envelope requires summary checkpoint phase to mirror raw' (Invoke-Comparator (Get-ComparatorArguments)) 'runtimeCheckpointPhase raw mirror'

    foreach ($lifecycleName in @('installOutcome', 'ghostLoadOutcome', 'renderOutcome', 'inputOutcome', 'shioriOutcome')) {
        Reset-Fixtures
        Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s 'Haiidrate').$lifecycleName = 'forged-summary-value' }
        Assert-Fail "non-ghost envelope requires summary $lifecycleName to mirror raw" (Invoke-Comparator (Get-ComparatorArguments)) "$lifecycleName raw mirror"
    }

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\Haiidrate\result.json") { param($r) $r.shioriOutcome = @('not-applicable') }
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) (Get-Row $s 'Haiidrate').shioriOutcome = @('not-applicable') }
    }
    Assert-Fail 'non-ghost envelope rejects non-string lifecycle values' (Invoke-Comparator (Get-ComparatorArguments)) "shioriOutcome has JSON kind 'array'"

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\Haiidrate\result.json") { param($r) $r.dialogueProbe | Add-Member -NotePropertyName forged -NotePropertyValue 'ignored' }
    }
    Assert-Fail 'non-ghost dialogue probe rejects extra properties' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe property set'

    foreach ($earthquakeContractKindMutation in @(
        @{ name = 'label'; field = 'label'; mutation = { param($r) $r.label = @('Earthquake Rescue Duo') } },
        @{ name = 'archive SHA'; field = 'archiveSha256'; mutation = { param($r) $r.archiveSha256 = @('06db71e7e8293b4af0b5127dd73402d4ed90fecc5fdcebf4f0d34337ccb66538') } },
        @{ name = 'JSON path'; field = 'jsonPath'; mutation = { param($r) $r.jsonPath = @('dialogueProbe.value') } },
        @{ name = 'variant predicate'; field = 'predicate'; mutation = { param($r) $r.allowedVariants[0].predicate = @('date-06-06') } },
        @{ name = 'source raw hash'; field = 'rawEntrySha256'; mutation = { param($r) $r.source.rawEntrySha256 = @('e34da6717a0bc20145b9ca7d535c886550331eb09910af5e02d5a6cd06523892') } },
        @{ name = 'source line-range member'; field = 'lineRanges'; mutation = { param($r) $r.source.lineRanges[0] = @('136-159') } },
        @{ name = 'source LF-slice hash'; field = 'lfSlices'; mutation = { param($r) $r.source.lfSlices[0].sha256 = @('49f89d04829adf5d1d62d7064e35d9f596388fd46b021a0483276a8346747413') } },
        @{ name = 'source reviewed-evidence member'; field = 'reviewedEvidence'; mutation = { param($r) $r.source.reviewedEvidence[0] = @('production tokenizer characterization') } }
    )) {
        Reset-Fixtures
        $kindContractPath = Join-Path $fixtureRoot ("earthquake-kind-$($earthquakeContractKindMutation.name -replace '[^A-Za-z0-9]', '-').json")
        Copy-Item -LiteralPath $contractPath -Destination $kindContractPath
        Save-Json $kindContractPath {
            param($contract)
            $rule = @($contract.stochasticDialogueValues | Where-Object { [string]$_.label -ceq 'Earthquake Rescue Duo' })[0]
            & $earthquakeContractKindMutation.mutation $rule
        }
        Assert-Fail "Earthquake contract rejects one-element array $($earthquakeContractKindMutation.name)" (Invoke-Comparator (Get-ComparatorArguments -ContractPath $kindContractPath)) "$($earthquakeContractKindMutation.field).*has JSON kind 'array'"
    }

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Watchdog-Bancho\result.json') { param($r) $r.dialogueProbe.value = @($dialogueValues['Watchdog Bancho']) }
    Assert-Fail 'stochastic dialogue value rejects a one-element JSON array lookalike' (Invoke-Comparator (Get-ComparatorArguments)) "dialogueProbe.value has JSON kind 'array'"

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') {
        param($s)
        ($s.sentinels.checks | Where-Object name -CEQ 'slice2-2elf-dialogue-value-nonblank').observed = @($dialogueValues['2elf-2.46'])
    }
    Assert-Fail 'stochastic sentinel mirror rejects a one-element JSON array lookalike' (Invoke-Comparator (Get-ComparatorArguments)) "required evidence mirror sentinel.*has JSON kind 'array'"

    foreach ($contextMutation in @(
        @{ name = 'profileState array'; pattern = "profileState has JSON kind 'array'"; mutation = { param($c) $c.profileState = @('fresh') } },
        @{ name = 'username array'; pattern = "username has JSON kind 'array'"; mutation = { param($c) $c.username = @('') } },
        @{ name = 'username empty array'; pattern = "username has JSON kind 'array'"; mutation = { param($c) $c.username = @() } },
        @{ name = 'birthday string'; pattern = 'birthdayConfigured has JSON kind'; mutation = { param($c) $c.birthdayConfigured = 'false' } },
        @{ name = 'before-clock array'; pattern = 'localClockBefore has JSON kind'; mutation = { param($c) $c.localClockBefore = @('2026-08-18T05:00:00-07:00') } },
        @{ name = 'after-clock number'; pattern = 'localClockAfter has JSON kind'; mutation = { param($c) $c.localClockAfter = 1 } },
        @{ name = 'missing property'; pattern = 'onBootContext property set'; mutation = { param($c) $c.PSObject.Properties.Remove('username') } },
        @{ name = 'extra property'; pattern = 'onBootContext property set'; mutation = { param($c) $c | Add-Member -NotePropertyName forged -NotePropertyValue 'ignored' } }
    )) {
        Reset-Fixtures
        Save-Json (Join-Path $fixtureRoot 'candidate\Earthquake-Rescue-Duo\result.json') {
            param($r)
            & $contextMutation.mutation $r.dialogueProbe.onBootContext
        }
        Assert-Fail "OnBoot context rejects $($contextMutation.name)" (Invoke-Comparator (Get-ComparatorArguments)) $contextMutation.pattern
    }

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Earthquake-Rescue-Duo\result.json') {
        param($r)
        $r.dialogueProbe.value = $earthquakeValues.earlyThird
        $r.dialogueProbe.tokenizerDiagnostics = @(, @('unsupported-command:8'))
    }
    Assert-Fail 'Earthquake tokenizer diagnostics reject a nested JSON array lookalike' (Invoke-Comparator (Get-ComparatorArguments)) "tokenizerDiagnostics.*has JSON kind 'array'"

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.dialogueProbe.tokenizerDiagnostics = @(, @('unsupported-command:8')) }
    Assert-Fail 'specialized stochastic tokenizer diagnostics reject nested JSON arrays' (Invoke-Comparator (Get-ComparatorArguments)) "tokenizerDiagnostics.*has JSON kind 'array'"

    Reset-Fixtures
    Assert-Pass 'all fourteen reviewed compatible ghost envelopes use their exact event map' (Invoke-Comparator (Get-ComparatorArguments))

    Reset-Fixtures
    Assert-Pass 'reviewed partial and install-rejected ghost envelopes pass without a compatible claim' (Invoke-Comparator (Get-ComparatorArguments))

    Reset-Fixtures
    $missingGhostEnvelopeContractPath = Join-Path $fixtureRoot 'contract-missing-tewire-envelope.json'
    Copy-Item -LiteralPath $contractPath -Destination $missingGhostEnvelopeContractPath
    Save-Json $missingGhostEnvelopeContractPath { param($c) $c.ghostEnvelopeRules = @($c.ghostEnvelopeRules | Where-Object { $_.label -cne 'tewire-sen' }) }
    Assert-Fail 'compatible ghost envelope contract cannot omit a reviewed label' (Invoke-Comparator (Get-ComparatorArguments -ContractPath $missingGhostEnvelopeContractPath)) 'ghost envelope rule set'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s)
            (Get-Row $s 'Big Red Button').PSObject.Properties.Remove('dialogueOutcome')
        }
    }
    Assert-Fail 'summary dialogue outcome mirror is required' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueOutcome'

    Reset-Fixtures
    Assert-Pass 'all four non-ghost rows use the explicit not-applicable dialogue envelope' (Invoke-Comparator (Get-ComparatorArguments))

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\tewire-sen\result.json") { param($r) $r.dialogueProbe.outcome = 'pending-real-shiori' }
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) (Get-Row $s 'tewire-sen').dialogueOutcome = 'pending-real-shiori' }
    }
    Assert-Fail 'compatible ghost cannot claim pending-real-shiori dialogue outcome' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe.outcome'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\tewire-sen\result.json") { param($r) $r.dialogueProbe.outcome = 'not-supported-shiori' }
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) (Get-Row $s 'tewire-sen').dialogueOutcome = 'not-supported-shiori' }
    }
    Assert-Fail 'compatible ghost cannot claim not-supported-shiori dialogue outcome' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe.outcome'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\tewire-sen\result.json") { param($r) $r.dialogueProbe.status = 500 }
    }
    Assert-Fail 'compatible ghost requires exact successful status' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe.status'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\tewire-sen\result.json") { param($r) $r.dialogueProbe.method = 'POST' }
    }
    Assert-Fail 'compatible ghost requires GET method' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe.method'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\tewire-sen\result.json") { param($r) $r.dialogueProbe.eventId = 'OnFirstBoot' }
    }
    Assert-Fail 'compatible ghost requires its exact manifest/SHA-bound event' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe.eventId'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\tewire-sen\result.json") { param($r) $r.dialogueProbe.failure = 'not-supported-shiori' }
    }
    Assert-Fail 'compatible ghost requires null failure' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe.failure'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\tewire-sen\result.json") { param($r) $r.classification = 'partiallyCompatible' }
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) (Get-Row $s 'tewire-sen').classification = 'partiallyCompatible' }
    }
    Assert-Fail 'compatible ghost cannot downgrade its reviewed classification' (Invoke-Comparator (Get-ComparatorArguments)) 'classification'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\Big-Red-Button\result.json") { param($r) $r.dialogueProbe.PSObject.Properties.Remove('outcome') }
    }
    Assert-Fail 'raw dialogue outcome is required' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe.*outcome'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\Big-Red-Button\result.json") { param($r) $r.dialogueProbe.outcome = ' ' }
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) (Get-Row $s 'Big Red Button').dialogueOutcome = ' ' }
    }
    Assert-Fail 'blank raw dialogue outcome is rejected' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe.outcome'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\Big-Red-Button\result.json") { param($r) $r.dialogueProbe.outcome = $true }
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) (Get-Row $s 'Big Red Button').dialogueOutcome = $true }
    }
    Assert-Fail 'boolean raw dialogue outcome is rejected' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe.outcome'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) (Get-Row $s 'Big Red Button').dialogueOutcome = $true }
    }
    Assert-Fail 'summary dialogue outcome must be a string' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueOutcome'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s 'Big Red Button').dialogueOutcome = 'not-supported-shiori' }
    Assert-Fail 'summary dialogue outcome must exactly mirror raw evidence' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueOutcome raw mirror'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\Haiidrate\result.json") { param($r) $r.dialogueProbe.outcome = 'success' }
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) (Get-Row $s 'Haiidrate').dialogueOutcome = 'success' }
    }
    Assert-Fail 'non-ghost dialogue outcome cannot be forged as success' (Invoke-Comparator (Get-ComparatorArguments)) 'not-applicable'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\Haiidrate\result.json") { param($r) $r.shioriOutcome = 'success' }
    }
    Assert-Fail 'non-ghost shiori outcome cannot be forged as success' (Invoke-Comparator (Get-ComparatorArguments)) 'shioriOutcome'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'Big Red Button' 'Functional' }
    Assert-Pass 'accepted native Kawari checkpoint preserves the exact producer envelope and host cleanup evidence' (Invoke-Comparator (Get-ComparatorArguments))

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
    Assert-Pass 'accepted native LOBO checkpoint skips only stochastic content validation' (Invoke-Comparator (Get-ComparatorArguments))

    foreach ($checkpointManifestKindMutation in @(
        @{ name = 'native-crash allow flag'; field = 'allowNativeKawariCrash'; mutation = { param($entry) $entry.allowNativeKawariCrash = @($true) } },
        @{ name = 'manifest label'; field = 'label'; mutation = { param($entry) $entry.label = @('LOBO') } }
    )) {
        Reset-Fixtures
        foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
        $checkpointManifestPath = Join-Path $fixtureRoot ("checkpoint-manifest-$($checkpointManifestKindMutation.name -replace '[^A-Za-z0-9]', '-').json")
        Copy-Item -LiteralPath $manifestPath -Destination $checkpointManifestPath
        Save-Json $checkpointManifestPath {
            param($m)
            $entry = @($m.entries | Where-Object { [string]$_.label -ceq 'LOBO' })[0]
            & $checkpointManifestKindMutation.mutation $entry
        }
        $checkpointManifestSha = (Get-FileHash -LiteralPath $checkpointManifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
        foreach ($side in @('base', 'candidate')) {
            Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) $s.manifestSha256 = $checkpointManifestSha }
        }
        Assert-Fail "accepted native checkpoint rejects singleton-array $($checkpointManifestKindMutation.name)" (Invoke-Comparator (Get-ComparatorArguments -SelectedManifestPath $checkpointManifestPath)) "$($checkpointManifestKindMutation.field).*has JSON kind 'array'"
    }

    foreach ($checkpointScalarKindMutation in @(
        @{ name = 'summary native crash evidence'; field = 'nativeCrashEvidence'; mutation = { param($raw, $row) $row.nativeCrashEvidence = @([string]$row.nativeCrashEvidence) } },
        @{ name = 'installed target ID'; field = 'installedTargetId'; mutation = { param($raw, $row) $raw.installedTargetId = @([string]$raw.installedTargetId) } },
        @{ name = 'dialogue method'; field = 'dialogueProbe.method'; mutation = { param($raw, $row) $raw.dialogueProbe.method = @([string]$raw.dialogueProbe.method) } },
        @{ name = 'dialogue event ID'; field = 'dialogueProbe.eventId'; mutation = { param($raw, $row) $raw.dialogueProbe.eventId = @([string]$raw.dialogueProbe.eventId) } },
        @{ name = 'dialogue reference member'; field = 'dialogueProbe.references\[0\]'; mutation = { param($raw, $row) $raw.dialogueProbe.references = @(, @([string]$raw.dialogueProbe.references[0])) } },
        @{ name = 'observed kind'; field = 'observedKind'; mutation = { param($raw, $row) $raw.observedKind = @([string]$raw.observedKind) } },
        @{ name = 'classification mirror'; field = 'classification'; mutation = { param($raw, $row) $raw.classification = @([string]$raw.classification); $row.classification = @([string]$row.classification) } }
    )) {
        Reset-Fixtures
        foreach ($side in @('base', 'candidate')) {
            Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master'
            $rawPath = Join-Path $fixtureRoot "$side\LOBO\result.json"
            $summaryPath = Join-Path $fixtureRoot "$side\summary.json"
            $raw = Get-Json $rawPath
            $summary = Get-Json $summaryPath
            & $checkpointScalarKindMutation.mutation $raw (Get-Row $summary 'LOBO')
            Write-Json -Path $rawPath -Value $raw
            Write-Json -Path $summaryPath -Value $summary
        }
        Assert-Fail "accepted native checkpoint rejects singleton-array $($checkpointScalarKindMutation.name)" (Invoke-Comparator (Get-ComparatorArguments)) "$($checkpointScalarKindMutation.field).*has JSON kind 'array'"
    }

    foreach ($field in @('value', 'module', 'observedAnchorId', 'observedInputId')) {
        Reset-Fixtures
        foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
        Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.dialogueProbe.$field = 'post-request residue' }
        Assert-Fail "accepted native LOBO checkpoint rejects non-null $field" (Invoke-Comparator (Get-ComparatorArguments)) $field
    }

    foreach ($field in @('passiveTransitions', 'tokenizerDiagnostics')) {
        Reset-Fixtures
        foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
        Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.dialogueProbe.$field = @('post-request residue') }
        Assert-Fail "accepted native LOBO checkpoint rejects nonempty $field" (Invoke-Comparator (Get-ComparatorArguments)) $field
    }

    foreach ($postRequestResidue in @(
        @{ name = 'onBootContext'; value = [pscustomobject]@{ profileState = 'fresh'; username = ''; birthdayConfigured = $false; localClockBefore = '2026-08-18T01:00:00-07:00'; localClockAfter = '2026-08-18T01:00:01-07:00' } },
        @{ name = 'sequence'; value = @([pscustomobject]@{ eventId = 'OnBoot' }) },
        @{ name = 'postInteractionEvidence'; value = @([pscustomobject]@{ eventId = 'OnChoiceSelect' }) }
    )) {
        Reset-Fixtures
        foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
        Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.dialogueProbe | Add-Member -NotePropertyName $postRequestResidue.name -NotePropertyValue $postRequestResidue.value }
        Assert-Fail "accepted native LOBO checkpoint rejects post-request $($postRequestResidue.name)" (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe property set'
    }

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.dialogueProbe.PSObject.Properties.Remove('module') }
    Assert-Fail 'accepted native checkpoint rejects a missing dialogue key' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe property set'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.dialogueProbe | Add-Member -NotePropertyName futureResult -NotePropertyValue $true }
    Assert-Fail 'accepted native checkpoint rejects an extra dialogue key' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe property set'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.dialogueProbe.PSObject.Properties.Remove('method'); $r.dialogueProbe | Add-Member -NotePropertyName requestMethod -NotePropertyValue 'GET' }
    Assert-Fail 'accepted native checkpoint rejects a renamed dialogue key' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe property set'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.dialogueProbe.references = @('Functional') }
    Assert-Fail 'accepted native checkpoint requires the label-bound shell reference' (Invoke-Comparator (Get-ComparatorArguments)) 'references\[0\]'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.installedTargetId = 'corpus-forged' }
    Assert-Fail 'accepted native checkpoint requires the archive-bound installed target' (Invoke-Comparator (Get-ComparatorArguments)) 'installedTargetId'

    foreach ($field in @('cleanup', 'error', 'unloadError')) {
        Reset-Fixtures
        foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
        Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r | Add-Member -NotePropertyName $field -NotePropertyValue 'forbidden' }
        Assert-Fail "accepted native checkpoint rejects forbidden top-level $field" (Invoke-Comparator (Get-ComparatorArguments)) "raw property set|cleanup-less|$field.*absent"
    }

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
    foreach ($side in @('base', 'candidate')) { Save-Json (Join-Path $fixtureRoot "$side\LOBO\result.json") { param($r) $r.PSObject.Properties.Remove('parserDiagnostics') } }
    Assert-Fail 'accepted native checkpoint rejects a missing producer property' (Invoke-Comparator (Get-ComparatorArguments)) 'raw property set'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
    foreach ($side in @('base', 'candidate')) { Save-Json (Join-Path $fixtureRoot "$side\LOBO\result.json") { param($r) $r | Add-Member -NotePropertyName completedAt -NotePropertyValue 'after-request' } }
    Assert-Fail 'accepted native checkpoint rejects an extra producer property' (Invoke-Comparator (Get-ComparatorArguments)) 'raw property set'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.narCorpusPath = '/data/user/0/com.cattailsw.nanidroid/cache/nar-corpus-host/forged/LOBO/nanidroid-corpus.nar' }
    Assert-Fail 'accepted native checkpoint requires the exact run-owned source path' (Invoke-Comparator (Get-ComparatorArguments)) 'narCorpusPath'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
    foreach ($side in @('base', 'candidate')) { Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) (Get-Row $s 'LOBO').archiveBytes = 4097 } }
    Assert-Fail 'accepted native checkpoint requires the summary archive-byte mirror' (Invoke-Comparator (Get-ComparatorArguments)) 'archiveBytes raw mirror'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
    foreach ($side in @('base', 'candidate')) { Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) (Get-Row $s 'LOBO').parserDiagnostics = @() } }
    Assert-Fail 'accepted native checkpoint requires the summary parser-diagnostics mirror' (Invoke-Comparator (Get-ComparatorArguments)) 'parserDiagnostics raw mirror'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
    foreach ($side in @('base', 'candidate')) { Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) (Get-Row $s 'LOBO').nativeCrashEvidence = 'accepted by assertion only' } }
    Assert-Fail 'accepted native checkpoint requires exact native crash evidence' (Invoke-Comparator (Get-ComparatorArguments)) 'nativeCrashEvidence'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s 'LOBO').nativeCrash = $false }
    Assert-Fail 'a pre-request raw checkpoint is not accepted without a native crash' (Invoke-Comparator (Get-ComparatorArguments)) 'classification'

    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) { Set-AcceptedNativeCheckpointFixture $side 'LOBO' 'Master' }
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\LOBO\result.json") { param($r) $r.classification = 'compatible'; $r.ghostLoadOutcome = 'loaded'; $r.shioriOutcome = 'success'; $r.checkpointPhase = 'complete'; $r.dialogueProbe.outcome = 'success'; $r.dialogueProbe.status = 200; $r.dialogueProbe.value = 'completed dialogue' }
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s) $row = Get-Row $s 'LOBO'; $row.classification = 'compatible'; $row.ghostLoadOutcome = 'loaded'; $row.shioriOutcome = 'success'; $row.runtimeCheckpointPhase = 'complete'; $row.dialogueOutcome = 'success' }
    }
    Assert-Fail 'a completed success result cannot be laundered as a cleanup-less native checkpoint' (Invoke-Comparator (Get-ComparatorArguments)) 'accepted native checkpoint.*classification|runtimeCheckpointPhase|missing cleanup'

    # A matching pair must still be rejected when it claims an impossible
    # compatible-ghost lifecycle: canonical equality alone is not evidence.
    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\Yes-Man-2.1.1\result.json") { param($r)
            $r.installOutcome = 'forged-installed'
        }
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s)
            $row = Get-Row $s 'Yes Man-2.1.1'
            $row.installOutcome = 'forged-installed'
            $row.requiredEvidencePayload.installOutcome = 'forged-installed'
        }
    }
    Assert-Fail 'exact ghost lifecycle rejects matching forged install outcome' (Invoke-Comparator (Get-ComparatorArguments)) 'installOutcome'

    # A context is structural runner evidence even when the actual OnBoot
    # response is the supported non-success/not-supported-shiori envelope.
    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\Snake_Otacon_1.2.1b\result.json") { param($r)
            $r.classification = 'partiallyCompatible'
            $r.dialogueProbe.status = 200
            $r.dialogueProbe.outcome = 'not-supported-shiori'
            $r.dialogueProbe.failure = $null
            if ($side -ceq 'candidate') {
                $r.dialogueProbe.onBootContext.localClockBefore = '2026-08-18T02:00:00-07:00'
                $r.dialogueProbe.onBootContext.localClockAfter = '2026-08-18T02:00:01-07:00'
            }
        }
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s)
            $row = Get-Row $s 'Snake_Otacon_1.2.1b'
            $row.classification = 'partiallyCompatible'
            $row.dialogueOutcome = 'not-supported-shiori'
        }
    }
    Assert-Pass 'non-success OnBoot envelope still validates and normalizes its clock context' (Invoke-Comparator (Get-ComparatorArguments))

    # The native crash escape hatch is one exact predicate, never a loose
    # combination of manifest, raw, and summary fields.
    Reset-Fixtures
    foreach ($side in @('base', 'candidate')) {
        Save-Json (Join-Path $fixtureRoot "$side\Big-Red-Button\result.json") { param($r)
            $r.PSObject.Properties.Remove('cleanup')
            $r.checkpointPhase = 'before-real-shiori'
        }
        Save-Json (Join-Path $fixtureRoot "$side\summary.json") { param($s)
            $row = Get-Row $s 'Big Red Button'
            $row | Add-Member -NotePropertyName nativeCrash -NotePropertyValue 'true'
            $row.runtimeCheckpointPhase = 'before-real-shiori'
            $row.classification = 'incompatible'
            $row.observedPrivateSnapshot = @()
            $row.observedTmpSnapshot = @()
        }
    }
    Assert-Fail 'native checkpoint rejects a string nativeCrash flag' (Invoke-Comparator (Get-ComparatorArguments)) 'nativeCrash'

    Reset-Fixtures
    Set-AcceptedNativeCheckpointFixture 'candidate' 'Big Red Button' 'Functional'
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s)
        $row = Get-Row $s 'Big Red Button'
        $row.runtimeCheckpointPhase = 'after-real-shiori'
    }
    Assert-Fail 'native checkpoint requires the exact raw summary checkpoint predicate' (Invoke-Comparator (Get-ComparatorArguments)) 'runtimeCheckpointPhase'

    Reset-Fixtures
    Set-AcceptedNativeCheckpointFixture 'candidate' 'Big Red Button' 'Functional'
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s 'Big Red Button').cleanup.remainingTestOwnedPaths = @('residue') }
    Assert-Fail 'accepted native Kawari checkpoint still requires exact host cleanup evidence' (Invoke-Comparator (Get-ComparatorArguments)) 'summary cleanup residue'

    foreach ($invalidCleanupCase in @(
        [pscustomobject]@{ name = 'null'; value = $null; kind = 'null' },
        [pscustomobject]@{ name = 'scalar'; value = 'not-an-array'; kind = 'string' }
    )) {
        Reset-Fixtures
        Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s 'LOBO').cleanup.remainingTestOwnedPaths = $invalidCleanupCase.value }
        Assert-Fail "summary cleanup rejects $($invalidCleanupCase.name) remainingTestOwnedPaths" (Invoke-Comparator (Get-ComparatorArguments)) "remainingTestOwnedPaths has JSON kind '$($invalidCleanupCase.kind)'"
    }

    Reset-Fixtures
    Assert-Pass 'fresh baseline report after native checkpoint coverage' (Invoke-Comparator (Get-ComparatorArguments))
    $baselineReport = Get-Json (Join-Path $fixtureRoot 'comparison.json')
    if (
        $baselineReport.comparisonCategories.literalEqualityCount -ne 16 -or
        $baselineReport.comparisonCategories.stochasticDialogueContractCount -ne 4 -or
        $baselineReport.comparisonCategories.snakeStructuralOnlyCount -ne 3 -or
        $baselineReport.comparisonCategories.snakeCanaryExactCount -ne 3 -or
        $baselineReport.comparisonCategories.rawEnvelopeValidatedCount -ne 23 -or
        $baselineReport.comparisonCategories.screenshotHashEqualityCount -ne 23
    ) { throw 'comparison report did not bind the reviewed dialogue/raw/screenshot category counts.' }
    Write-Host 'PASS: comparison report binds reviewed category counts'

    # This is deliberately RED until the schema-2 comparator binds the three
    # older Snake structural witnesses and their exact raw/summary canaries.
    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'base\Snake-and-Otacon-V1.2.1\result.json') { param($r) $r.PSObject.Properties.Remove('snakeOnBootStructuralSafety') }
    Assert-Fail 'older Snake results require a structural safety witness before literal normalization' (Invoke-Comparator (Get-ComparatorArguments)) 'snakeOnBootStructuralSafety'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s 'Snake and Otacon V1.2.1').snakeOnBootStructuralSafety.accepted = $false }
    Assert-Fail 'older Snake summary safety witness must exactly mirror raw evidence' (Invoke-Comparator (Get-ComparatorArguments)) 'raw/summary snakeOnBootStructuralSafety mirror mismatch'

    Reset-Fixtures
    $snakeCanaryValue = '\0\s[0]\1\s[10]Changed canary.\e'
    Save-Json (Join-Path $fixtureRoot 'candidate\Snake-and-Otacon-V1.2.1\result.json') { param($r) $r.snakeFirstBootCanary.response.value = $snakeCanaryValue; $r.snakeFirstBootCanary.response.valueUtf8Sha256 = Get-StringSha256 $snakeCanaryValue; $r.snakeFirstBootCanary.response.valueUtf8ByteLength = [Text.Encoding]::UTF8.GetByteCount($snakeCanaryValue) }
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $row = Get-Row $s 'Snake and Otacon V1.2.1'; $row.snakeFirstBootCanary.response.value = $snakeCanaryValue; $row.snakeFirstBootCanary.response.valueUtf8Sha256 = Get-StringSha256 $snakeCanaryValue; $row.snakeFirstBootCanary.response.valueUtf8ByteLength = [Text.Encoding]::UTF8.GetByteCount($snakeCanaryValue) }
    Assert-Fail 'older Snake first-boot canary remains exactly compared' (Invoke-Comparator (Get-ComparatorArguments)) 'snakeFirstBootCanary.response.value'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Snake-and-Otacon-V1.2.1\result.json') { param($r) $r.dialogueProbe.value = '\0\s[0]A different but instrumented-safe literal.\e' }
    Assert-Pass 'older Snake literal is structural-only after its safety witness validates' (Invoke-Comparator (Get-ComparatorArguments))

    Reset-Fixtures
    $earthquakeDatePrefix = $earthquakeValues.prefix
    Save-Json (Join-Path $fixtureRoot 'candidate\2elf-2.46\result.json') { param($r) $r.dialogueProbe.value = $twoElfCandidate }
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $row = Get-Row $s '2elf-2.46'; $row.requiredEvidencePayload.dialogueProbe.value = $twoElfCandidate; ($s.sentinels.checks | Where-Object name -CEQ 'slice2-2elf-dialogue-value-nonblank').observed = $twoElfCandidate }
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.dialogueProbe.value = $loboReviewedAlternative }
    Save-Json (Join-Path $fixtureRoot 'candidate\Watchdog-Bancho\result.json') { param($r) $r.dialogueProbe.value = "\1\s[10]\0\s[0]\0\s[0]Yo, boss! What's the haps?" }
    Save-Json (Join-Path $fixtureRoot 'candidate\Earthquake-Rescue-Duo\result.json') { param($r) $r.dialogueProbe.value = $earthquakeDatePrefix; $r.dialogueProbe.onBootContext.localClockBefore = '2026-06-06T01:00:00-07:00'; $r.dialogueProbe.onBootContext.localClockAfter = '2026-06-06T01:00:01-07:00' }
    Assert-Pass 'all four independently validated stochastic contracts permit different allowed candidate values' (Invoke-Comparator (Get-ComparatorArguments))
    $stochasticReport = Get-Json (Join-Path $fixtureRoot 'comparison.json')
    if ($stochasticReport.comparisonCategories.literalEqualityCount -ne 16) { throw 'different allowed stochastic values must not increase literalEqualityCount above 16.' }
    Write-Host 'PASS: four stochastic contracts retain literalEqualityCount 16'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.schemaVersion = '1' }
    Assert-Fail 'schema 2 rejects a legacy summary schema version' (Invoke-Comparator (Get-ComparatorArguments)) 'schemaVersion.*expected.*2'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.schemaVersion = 2 }
    Assert-Fail 'schema 2 requires an exact string rather than a numeric lookalike' (Invoke-Comparator (Get-ComparatorArguments)) "schemaVersion has JSON kind 'number'"

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'base\LOBO\result.json') { param($r) $r.schemaVersion = '1' }
    Assert-Fail 'schema 2 rejects a legacy raw result schema version' (Invoke-Comparator (Get-ComparatorArguments)) "raw result 'LOBO'.*schemaVersion.*expected.*2"

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'base\summary.json') { param($s) $s.results | ForEach-Object { $_.PSObject.Properties.Remove('observedPrivateSnapshot') } }
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) $s.results | ForEach-Object { $_.PSObject.Properties.Remove('observedPrivateSnapshot') } }
    Assert-Fail 'all 23 summary private snapshots are required before normalization' (Invoke-Comparator (Get-ComparatorArguments)) 'observedPrivateSnapshot'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'base\summary.json') { param($s) (Get-Row $s 'LOBO').evidence.sourceSyntax.PSObject.Properties.Remove('scanRoot') }
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s 'LOBO').evidence.sourceSyntax.PSObject.Properties.Remove('scanRoot') }
    Assert-Fail 'all 15 summary source scan roots are required before normalization' (Invoke-Comparator (Get-ComparatorArguments)) 'scanRoot'

    Reset-Fixtures
    Get-ChildItem -LiteralPath (Join-Path $fixtureRoot 'base') -Recurse -Filter result.json | ForEach-Object { Save-Json $_.FullName { param($r) $r.cleanup.PSObject.Properties.Remove('hostVerified') } }
    Get-ChildItem -LiteralPath (Join-Path $fixtureRoot 'candidate') -Recurse -Filter result.json | ForEach-Object { Save-Json $_.FullName { param($r) $r.cleanup.PSObject.Properties.Remove('hostVerified') } }
    Assert-Pass 'device raw evidence does not require host-only cleanup enrichment' (Invoke-Comparator (Get-ComparatorArguments))

    $earthquakeBoundaryCases = @(
        @{ name = 'ordinary 00 boundary'; value = $earthquakeValues.prefix; diagnostics = @(); before = '2026-08-18T00:00:00-07:00'; after = '2026-08-18T00:00:01-07:00' },
        @{ name = 'ordinary 04 boundary'; value = $earthquakeValues.prefix; diagnostics = @(); before = '2026-08-18T04:59:58-07:00'; after = '2026-08-18T04:59:59-07:00' },
        @{ name = 'ordinary 05 first early variant'; value = $earthquakeValues.earlyFirst; diagnostics = @(); before = '2026-08-18T05:00:00-07:00'; after = '2026-08-18T05:00:01-07:00' },
        @{ name = 'ordinary 06 second early variant'; value = $earthquakeValues.earlySecond; diagnostics = @(); before = '2026-08-18T06:00:00-07:00'; after = '2026-08-18T06:00:01-07:00' },
        @{ name = 'ordinary 08 third early variant'; value = $earthquakeValues.earlyThird; diagnostics = @('unsupported-command:8'); before = '2026-08-18T08:59:58-07:00'; after = '2026-08-18T08:59:59-07:00' },
        @{ name = 'ordinary 09 first morning variant'; value = $earthquakeValues.morningFirst; diagnostics = @(); before = '2026-08-18T09:00:00-07:00'; after = '2026-08-18T09:00:01-07:00' },
        @{ name = 'ordinary 11 second morning variant'; value = $earthquakeValues.morningSecond; diagnostics = @(); before = '2026-08-18T11:59:58-07:00'; after = '2026-08-18T11:59:59-07:00' },
        @{ name = 'ordinary 12 first lunch variant'; value = $earthquakeValues.lunchFirst; diagnostics = @(); before = '2026-08-18T12:00:00-07:00'; after = '2026-08-18T12:00:01-07:00' },
        @{ name = 'ordinary 14 second lunch variant'; value = $earthquakeValues.lunchSecond; diagnostics = @(); before = '2026-08-18T14:59:58-07:00'; after = '2026-08-18T14:59:59-07:00' },
        @{ name = 'ordinary 15 afternoon boundary'; value = $earthquakeValues.afternoon; diagnostics = @(); before = '2026-08-18T15:00:00-07:00'; after = '2026-08-18T15:00:01-07:00' },
        @{ name = 'ordinary 17 afternoon boundary'; value = $earthquakeValues.afternoon; diagnostics = @(); before = '2026-08-18T17:59:58-07:00'; after = '2026-08-18T17:59:59-07:00' },
        @{ name = 'ordinary 18 prefix boundary'; value = $earthquakeValues.prefix; diagnostics = @(); before = '2026-08-18T18:00:00-07:00'; after = '2026-08-18T18:00:01-07:00' },
        @{ name = 'ordinary 20 prefix boundary'; value = $earthquakeValues.prefix; diagnostics = @(); before = '2026-08-18T20:59:58-07:00'; after = '2026-08-18T20:59:59-07:00' },
        @{ name = 'ordinary 21 prefix boundary'; value = $earthquakeValues.prefix; diagnostics = @(); before = '2026-08-18T21:00:00-07:00'; after = '2026-08-18T21:00:01-07:00' },
        @{ name = 'ordinary 23 prefix boundary'; value = $earthquakeValues.prefix; diagnostics = @(); before = '2026-08-18T23:59:58-07:00'; after = '2026-08-18T23:59:59-07:00' },
        @{ name = 'June 6 overrides lunch'; value = $earthquakeValues.prefix; diagnostics = @(); before = '2026-06-06T12:00:00-07:00'; after = '2026-06-06T12:00:01-07:00' },
        @{ name = 'July 4 overrides early morning'; value = $earthquakeValues.prefix; diagnostics = @(); before = '2026-07-04T08:00:00-07:00'; after = '2026-07-04T08:00:01-07:00' }
    )
    foreach ($case in $earthquakeBoundaryCases) {
        Reset-Fixtures
        Set-EarthquakeFixture -Side 'candidate' -DialogueValue $case.value -Diagnostics $case.diagnostics -Before $case.before -After $case.after
        Assert-Pass "Earthquake accepts $($case.name)" (Invoke-Comparator (Get-ComparatorArguments))
    }

    foreach ($invalidEarthquakeCase in @(
        @{ name = 'an early value in the morning bucket'; value = $earthquakeValues.earlyFirst; diagnostics = @(); before = '2026-08-18T09:00:00-07:00'; after = '2026-08-18T09:00:01-07:00' },
        @{ name = 'an early value on June 6'; value = $earthquakeValues.earlyFirst; diagnostics = @(); before = '2026-06-06T05:00:00-07:00'; after = '2026-06-06T05:00:01-07:00' },
        @{ name = 'a morning value on July 4'; value = $earthquakeValues.morningFirst; diagnostics = @(); before = '2026-07-04T09:00:00-07:00'; after = '2026-07-04T09:00:01-07:00' },
        @{ name = 'a normal lunch value on June 6'; value = $earthquakeValues.lunchFirst; diagnostics = @(); before = '2026-06-06T12:00:00-07:00'; after = '2026-06-06T12:00:01-07:00' },
        @{ name = 'an afternoon value on July 4'; value = $earthquakeValues.afternoon; diagnostics = @(); before = '2026-07-04T15:00:00-07:00'; after = '2026-07-04T15:00:01-07:00' },
        @{ name = 'missing diagnostics for the third early value'; value = $earthquakeValues.earlyThird; diagnostics = @(); before = '2026-08-18T05:00:00-07:00'; after = '2026-08-18T05:00:01-07:00' },
        @{ name = 'forged diagnostics for the first early value'; value = $earthquakeValues.earlyFirst; diagnostics = @('unsupported-command:8'); before = '2026-08-18T05:00:00-07:00'; after = '2026-08-18T05:00:01-07:00' }
    )) {
        Reset-Fixtures
        Set-EarthquakeFixture -Side 'candidate' -DialogueValue $invalidEarthquakeCase.value -Diagnostics $invalidEarthquakeCase.diagnostics -Before $invalidEarthquakeCase.before -After $invalidEarthquakeCase.after
        Assert-Fail "Earthquake rejects $($invalidEarthquakeCase.name)" (Invoke-Comparator (Get-ComparatorArguments)) 'unreviewed stochastic dialogueProbe.value/tokenizerDiagnostics pair'
    }

    foreach ($crossingCase in @(
        @{ name = 'hour bucket'; before = '2026-08-18T08:59:59-07:00'; after = '2026-08-18T09:00:00-07:00' },
        @{ name = 'ordinary-to-special date'; before = '2026-06-05T23:59:59-07:00'; after = '2026-06-06T00:00:00-07:00' }
    )) {
        Reset-Fixtures
        Set-EarthquakeFixture -Side 'candidate' -DialogueValue $earthquakeValues.prefix -Diagnostics @() -Before $crossingCase.before -After $crossingCase.after
        Assert-Fail "Earthquake rejects a clock bracket crossing the $($crossingCase.name) boundary" (Invoke-Comparator (Get-ComparatorArguments)) 'clock bracket.*boundary'
    }

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Earthquake-Rescue-Duo\result.json') { param($r) $r.dialogueProbe.onBootContext.username = 'User' }
    Assert-Fail 'Earthquake rejects a nonempty fresh-profile username' (Invoke-Comparator (Get-ComparatorArguments)) 'onBootContext.username'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Earthquake-Rescue-Duo\result.json') { param($r) $r.dialogueProbe.onBootContext.birthdayConfigured = $true }
    Assert-Fail 'Earthquake rejects a configured birthday wildcard' (Invoke-Comparator (Get-ComparatorArguments)) 'birthdayConfigured must be false'

    foreach ($contractMutation in @(
        @{ name = 'variant hash'; pattern = 'Earthquake.*catalogue'; mutation = { param($r) $r.allowedVariants[0].valueUtf8Sha256 = 'f' * 64 } },
        @{ name = 'variant diagnostics'; pattern = 'Earthquake.*catalogue'; mutation = { param($r) $r.allowedVariants[0].tokenizerDiagnostics = @('forged') } },
        @{ name = 'empty variant diagnostic collision'; pattern = 'Earthquake.*catalogue'; mutation = { param($r) $r.allowedVariants[0].tokenizerDiagnostics = @('') } },
        @{ name = 'variant predicate'; pattern = 'Earthquake.*catalogue'; mutation = { param($r) $r.allowedVariants[0].predicate = 'early-not-special-date' } },
        @{ name = 'variant catalogue digest'; pattern = 'variantCatalogueSha256'; mutation = { param($r) $r.variantCatalogueSha256 = 'f' * 64 } },
        @{ name = 'generic hash-list validator bypass'; pattern = 'Earthquake.*catalogue'; mutation = { param($r) $r | Add-Member -NotePropertyName allowedUtf8Sha256 -NotePropertyValue @($earthquakeExpectedHashes.earlyFirst); $r.PSObject.Properties.Remove('allowedVariants'); $r.PSObject.Properties.Remove('variantCatalogueSha256') } },
        @{ name = 'source entry hash'; pattern = 'rawEntrySha256'; mutation = { param($r) $r.source.rawEntrySha256 = 'f' * 64 } },
        @{ name = 'source BOM-stripped hash'; pattern = 'bomStrippedSha256'; mutation = { param($r) $r.source.bomStrippedSha256 = 'f' * 64 } },
        @{ name = 'source LF slice hash'; pattern = 'lfSlices'; mutation = { param($r) $r.source.lfSlices[0].sha256 = 'f' * 64 } },
        @{ name = 'source archive entry'; pattern = 'archiveEntry'; mutation = { param($r) $r.source.archiveEntry = 'ghost/master/forged.dic' } },
        @{ name = 'source line range'; pattern = 'lineRanges'; mutation = { param($r) $r.source.lineRanges[0] = '1' } },
        @{ name = 'source reviewed evidence'; pattern = 'reviewedEvidence'; mutation = { param($r) $r.source.reviewedEvidence = @('forged review') } }
    )) {
        Reset-Fixtures
        $mutatedContractPath = Join-Path $fixtureRoot ("earthquake-$($contractMutation.name -replace '[^A-Za-z0-9]', '-').json")
        Copy-Item -LiteralPath $contractPath -Destination $mutatedContractPath
        Save-Json $mutatedContractPath {
            param($contract)
            $rule = @($contract.stochasticDialogueValues | Where-Object label -CEQ 'Earthquake Rescue Duo')[0]
            & $contractMutation.mutation $rule
        }
        Assert-Fail "Earthquake rejects a mutated $($contractMutation.name) contract" (Invoke-Comparator (Get-ComparatorArguments -ContractPath $mutatedContractPath)) $contractMutation.pattern
    }

    Reset-Fixtures
    $reorderedEarthquakeContractPath = Join-Path $fixtureRoot 'earthquake-reordered-catalogue.json'
    Copy-Item -LiteralPath $contractPath -Destination $reorderedEarthquakeContractPath
    Save-Json $reorderedEarthquakeContractPath {
        param($contract)
        $rule = @($contract.stochasticDialogueValues | Where-Object label -CEQ 'Earthquake Rescue Duo')[0]
        [Array]::Reverse($rule.allowedVariants)
    }
    Assert-Pass 'Earthquake catalogue digest is independent of variant order' (Invoke-Comparator (Get-ComparatorArguments -ContractPath $reorderedEarthquakeContractPath))

    Reset-Fixtures
    $loboAuthored = '\1\s[10]\0\s[0]\1\s[-1]\0Hark! What brings this goth love to grace my presence?'
    Save-Json (Join-Path $fixtureRoot 'base\LOBO\result.json') { param($r) $r.dialogueProbe.value = $loboAuthored }
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.dialogueProbe.value = $loboAuthored }
    Assert-Pass 'LOBO accepts a fully consumed source-authored OnBoot template' (Invoke-Comparator (Get-ComparatorArguments))

    Reset-Fixtures
    $loboUnreviewed = '\1\s[10]\0\s[0]\1\s[-1]\0Hark! What brings this goth arbitrary substitution to grace my presence?'
    Save-Json (Join-Path $fixtureRoot 'base\LOBO\result.json') { param($r) $r.dialogueProbe.value = $loboUnreviewed }
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.dialogueProbe.value = $loboUnreviewed }
    Assert-Fail 'LOBO rejects an unlisted template substitution' (Invoke-Comparator (Get-ComparatorArguments)) 'specialized stochastic'

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

    foreach ($countTokenCase in @('143.0', '1.43e2', 'true', '"143"')) {
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

    foreach ($lineEndingCase in @(
        @{ name = 'LF'; suffix = "`n" },
        @{ name = 'CRLF'; suffix = "`r`n" }
    )) {
        Reset-Fixtures
        foreach ($side in @('base', 'candidate')) {
            Save-Json (Join-Path $fixtureRoot "$side\LOBO\result.json") { param($r) $r.narCorpusPath += $lineEndingCase.suffix }
        }
        Assert-Fail "narCorpusPath rejects a final $($lineEndingCase.name)" (Invoke-Comparator (Get-ComparatorArguments)) 'narCorpusPath.*exact runner path'
    }

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
    Assert-Fail 'declared raw source normalization path missing from both raws' (Invoke-Comparator (Get-ComparatorArguments)) "raw result 'LOBO'.evidence.sourceSyntax.scanRoot must be present"

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Haiidrate\result.json') { param($r) $r.evidence | Add-Member -NotePropertyName sourceSyntax -NotePropertyValue ([pscustomobject]@{ scanRoot = '/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/candidate/Haiidrate/source' }); $r | Add-Member -NotePropertyName sakura -NotePropertyValue ([pscustomobject]@{ source = '/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/candidate/Haiidrate/sakura' }); $r | Add-Member -NotePropertyName kero -NotePropertyValue ([pscustomobject]@{ source = '/data/data/com.cattailsw.nanidroid/cache/nar-corpus-host/candidate/Haiidrate/kero' }) }
    Assert-Fail 'undeclared raw source selectors remain behavioral' (Invoke-Comparator (Get-ComparatorArguments)) "raw result 'Haiidrate'.evidence.sourceSyntax.scanRoot is not declared"

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
    Assert-Pass 'structural-only Snake permits inert cross-archive prose without claiming literal equality' (Invoke-Comparator (Get-ComparatorArguments))
    $crossArchiveSnakeReport = Get-Json (Join-Path $fixtureRoot 'comparison.json')
    $crossArchiveSnakeBase = Get-Json (Join-Path $fixtureRoot 'base\Snake-and-Otacon-V1.2.1\result.json')
    $crossArchiveSnakeCandidate = Get-Json (Join-Path $fixtureRoot 'candidate\Snake-and-Otacon-V1.2.1\result.json')
    if (
        $crossArchiveSnakeReport.comparisonCategories.literalEqualityCount -ne 16 -or
        $crossArchiveSnakeReport.comparisonCategories.snakeStructuralOnlyCount -ne 3 -or
        $crossArchiveSnakeReport.comparisonCategories.snakeCanaryExactCount -ne 3 -or
        $crossArchiveSnakeReport.comparisonCategories.rawEnvelopeValidatedCount -ne 23 -or
        @($crossArchiveSnakeReport.comparisonCategories.snakeStructuralOnlyLabels) -notcontains 'Snake and Otacon V1.2.1' -or
        $crossArchiveSnakeCandidate.snakeOnBootStructuralSafety.contentCompared -ne $false -or
        (($crossArchiveSnakeBase.snakeFirstBootCanary | ConvertTo-Json -Depth 20) -cne ($crossArchiveSnakeCandidate.snakeFirstBootCanary | ConvertTo-Json -Depth 20))
    ) { throw 'cross-archive structural-only Snake coverage lost its reviewed report partition.' }
    Write-Host 'PASS: cross-archive structural-only Snake preserves the reviewed report partition'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Snake-and-Otacon-V1.2.1\result.json') { param($r) $r.snakeOnBootStructuralSafety.terminal = 'eof' }
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s 'Snake and Otacon V1.2.1').snakeOnBootStructuralSafety.terminal = 'eof' }
    Assert-Pass 'validated older-Snake exact-e and EOF terminal witnesses normalize together' (Invoke-Comparator (Get-ComparatorArguments))

    Reset-Fixtures
    Set-OnBootClockMirrors 'candidate' '2elf-2.46' '2026-08-18T01:02:00.123456789Z' '2026-08-18T01:02:00.223456789Z'
    $uppercaseZTimestampResult = Invoke-Comparator (Get-ComparatorArguments)

    Reset-Fixtures
    Set-OnBootClockMirrors 'candidate' '2elf-2.46' '2026-08-18T01:03-07:00' '2026-08-18T01:03-07:00'
    $minuteOnlyTimestampResult = Invoke-Comparator (Get-ComparatorArguments)

    $invalidOffsetTimestampResults = [Collections.Generic.List[object]]::new()
    foreach ($invalidTimestamp in @(
        @{ name = 'missing UTC offset'; before = '2026-08-18T01:04:00'; after = '2026-08-18T01:04:01' },
        @{ name = 'lowercase UTC designator'; before = '2026-08-18T01:04:00z'; after = '2026-08-18T01:04:01z' },
        @{ name = 'malformed UTC offset'; before = '2026-08-18T01:04:00+07:0'; after = '2026-08-18T01:04:01+07:0' },
        @{ name = 'trailing LF after UTC timestamp'; before = "2026-08-18T01:04:00Z`n"; after = "2026-08-18T01:04:01Z`n" }
    )) {
        Reset-Fixtures
        Set-OnBootClockMirrors 'candidate' '2elf-2.46' $invalidTimestamp.before $invalidTimestamp.after
        $invalidOffsetTimestampResults.Add([pscustomobject]@{
            name = $invalidTimestamp.name
            result = Invoke-Comparator (Get-ComparatorArguments)
        })
    }

    Assert-Pass 'uppercase-Z OffsetDateTime OnBoot clocks and mirrors normalize after validation' $uppercaseZTimestampResult
    Assert-Pass 'minute-only OffsetDateTime OnBoot clocks and mirrors normalize after validation' $minuteOnlyTimestampResult
    foreach ($invalidResult in $invalidOffsetTimestampResults) {
        Assert-Fail "OnBoot clocks reject $($invalidResult.name)" $invalidResult.result 'must use device-local ISO-8601 offsets'
    }

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.dialogueProbe.onBootContext.localClockBefore = '2026-08-18T01:01:00-07:00'; $r.dialogueProbe.onBootContext.localClockAfter = '2026-08-18T01:01:01-07:00' }
    Assert-Pass 'distinct valid legacy OnBoot clocks normalize after validation' (Invoke-Comparator (Get-ComparatorArguments))

    Reset-Fixtures
    Set-OnBootClockMirrors 'candidate' '2elf-2.46' '2026-08-18T01:02:00-07:00' '2026-08-18T01:02:01-07:00'
    Assert-Pass 'distinct valid 2elf raw and required-evidence OnBoot context mirrors normalize together' (Invoke-Comparator (Get-ComparatorArguments))

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Snake-and-Otacon-V1.2.1\result.json') { param($r) $r.snakeFirstBootCanary.PSObject.Properties.Remove('independentInstanceCount') }
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s 'Snake and Otacon V1.2.1').snakeFirstBootCanary.PSObject.Properties.Remove('independentInstanceCount') }
    Assert-Fail 'Snake canary requires independent instance count' (Invoke-Comparator (Get-ComparatorArguments)) 'independentInstanceCount'

    foreach ($invalidCanaryCount in @('2', 1)) {
        Reset-Fixtures
        Save-Json (Join-Path $fixtureRoot 'candidate\Snake-and-Otacon-V1.2.1\result.json') { param($r) $r.snakeFirstBootCanary.independentInstanceCount = $invalidCanaryCount }
        Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s 'Snake and Otacon V1.2.1').snakeFirstBootCanary.independentInstanceCount = $invalidCanaryCount }
        Assert-Fail "Snake canary rejects invalid independent instance count '$invalidCanaryCount'" (Invoke-Comparator (Get-ComparatorArguments)) 'independentInstanceCount'
    }

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.evidence.sourceSyntax.scanRoot = $r.evidence.sourceSyntax.scanRoot -replace 'corpus-[0-9a-f]{16}', 'corpus-ffffffffffffffff' }
    Save-Json (Join-Path $fixtureRoot 'candidate\summary.json') { param($s) (Get-Row $s 'LOBO').evidence.sourceSyntax.scanRoot = (Get-Row $s 'LOBO').evidence.sourceSyntax.scanRoot -replace 'corpus-[0-9a-f]{16}', 'corpus-ffffffffffffffff' }
    Assert-Fail 'source scan root binds the exact archive SHA prefix' (Invoke-Comparator (Get-ComparatorArguments)) 'scan-root path'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.sakura.source = "$($r.evidence.sourceSyntax.scanRoot)/shell/master/surface0.png/neighbor" }
    Assert-Fail 'source syntax descendants cannot escape their exact scan-root shape' (Invoke-Comparator (Get-ComparatorArguments)) 'exact Sakura/Kero source descendant'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Snake-and-Otacon-V1.2.1\result.json') { param($r) $r.dialogueProbe.method = 'POST' }
    Assert-Fail 'structural-only Snake still requires the exact GET OnBoot invariant' (Invoke-Comparator (Get-ComparatorArguments)) 'dialogueProbe.method'

    Reset-Fixtures
    Save-Json (Join-Path $fixtureRoot 'candidate\Snake-and-Otacon-V1.2.1\result.json') { param($r) $r.snakeOnBootStructuralSafety.allowedSurfaces = @(0) }
    Assert-Fail 'structural-only Snake binds the complete reviewed surface union' (Invoke-Comparator (Get-ComparatorArguments)) 'allowedSurfaces'

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
    Assert-Pass 'bound base/candidate comparison' (Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $candidateDebugSha -OutputPath (Join-Path $fixtureRoot 'bound-base-candidate.json')))
    Save-Json (Join-Path $fixtureRoot 'base\summary.json') { param($s) $s.startedAt = '2026-08-17T00:03:00Z' }
    Assert-Fail 'replaced base evidence rejects stale prerequisite' (Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $candidateDebugSha -OutputPath (Join-Path $fixtureRoot 'replaced-base-evidence.json'))) 'evidence fingerprint'
    Save-Json (Join-Path $fixtureRoot 'base\summary.json') { param($s) $s.startedAt = '2026-08-17T00:00:00Z' }
    $mismatchedPrerequisite = Get-Json $prerequisite
    $mismatchedPrerequisite.device.abi = 'arm64-v8a'
    Write-Json $prerequisite $mismatchedPrerequisite
    Assert-Fail 'mismatched base/base prerequisite' (Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $candidateDebugSha -OutputPath (Join-Path $fixtureRoot 'mismatched-prerequisite.json'))) 'prerequisite mismatch'
    $mismatchedPrerequisite.device.abi = 'x86_64'
    Write-Json $prerequisite $mismatchedPrerequisite
    $failedPrerequisite = Get-Json $prerequisite
    $failedPrerequisite.passed = $false
    Write-Json $prerequisite $failedPrerequisite
    Assert-Fail 'failed base/base prerequisite' (Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $candidateDebugSha -OutputPath (Join-Path $fixtureRoot 'failed-prerequisite.json'))) 'prerequisite'

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

    Reset-Fixtures
    $prerequisite = Join-Path $fixtureRoot 'base-base.json'
    $preexistingOutput = Join-Path $fixtureRoot 'preexisting-base-candidate.json'
    Assert-Pass 'base/base prerequisite for existing base/candidate output' (Invoke-Comparator (Get-ComparatorArguments -OutputPath $prerequisite))
    New-ReportFixture -Root (Join-Path $fixtureRoot 'candidate') -RunId ('4' * 32) -ProductionCommit $candidateCommit -DebugSha $candidateDebugSha -StartedAt '2026-08-17T00:02:00Z'
    Save-Json (Join-Path $fixtureRoot 'candidate\LOBO\result.json') { param($r) $r.evidence | Add-Member -NotePropertyName hidden -NotePropertyValue 'candidate-only' }
    [IO.File]::WriteAllText($preexistingOutput, 'pre-existing-base-candidate-output', [Text.UTF8Encoding]::new($false))
    $preexistingOutputSha = (Get-FileHash -LiteralPath $preexistingOutput -Algorithm SHA256).Hash
    $preexistingOutputResult = Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $candidateDebugSha -OutputPath $preexistingOutput)
    if ((Get-FileHash -LiteralPath $preexistingOutput -Algorithm SHA256).Hash -cne $preexistingOutputSha) { throw 'base/candidate pre-existing output was altered after a behavioral failure' }
    Assert-Fail 'base/candidate behavioral failure preserves a pre-existing output' $preexistingOutputResult 'raw behavioral difference.*evidence.hidden'

    if ($IsWindows) {
        Reset-Fixtures
        $prerequisiteParent = Join-Path $fixtureRoot 'junction-prerequisite-parent'
        New-Item -ItemType Directory -Force -Path $prerequisiteParent | Out-Null
        $prerequisite = Join-Path $prerequisiteParent 'base-base.json'
        Assert-Pass 'base/base prerequisite for junction output alias' (Invoke-Comparator (Get-ComparatorArguments -OutputPath $prerequisite))
        New-ReportFixture -Root (Join-Path $fixtureRoot 'candidate') -RunId ('4' * 32) -ProductionCommit $candidateCommit -DebugSha $candidateDebugSha -StartedAt '2026-08-17T00:02:00Z'
        $junctionPath = Join-Path $fixtureRoot 'junction-output-alias'
        New-Item -ItemType Junction -Path $junctionPath -Target $prerequisiteParent | Out-Null
        try {
            $junctionPrerequisiteSha = (Get-FileHash -LiteralPath $prerequisite -Algorithm SHA256).Hash
            $junctionCollisionResult = Invoke-Comparator (Get-ComparatorArguments -Kind BaseCandidate -Prerequisite $prerequisite -ExpectedCandidateCommit $candidateCommit -ExpectedCandidateDebugSha $candidateDebugSha -OutputPath (Join-Path $junctionPath 'base-base.json'))
            if ((Get-FileHash -LiteralPath $prerequisite -Algorithm SHA256).Hash -cne $junctionPrerequisiteSha) { throw 'base/candidate junction output alias altered its prerequisite' }
            Assert-Fail 'base/candidate junction output alias cannot overwrite its prerequisite' $junctionCollisionResult 'OutputPath.*fresh'
        }
        finally {
            Remove-Item -LiteralPath $junctionPath -Force -ErrorAction SilentlyContinue
        }
    }
    else {
        Write-Host 'SKIP: junction output alias test requires Windows'
    }

    Write-Host 'Comparator host tests passed.'
}
finally {
    if (Test-Path -LiteralPath $fixtureRoot) { Remove-Item -LiteralPath $fixtureRoot -Recurse -Force }
}
