$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$cliPath = Join-Path $repoRoot 'scripts\build-nar-corpus-metadata-ledger.ps1'
$fixtureRoot = Join-Path $PSScriptRoot 'fixtures\nar-corpus-metadata-resolver'
$phaseOneFixture = Join-Path $fixtureRoot 'phase-one-synthetic.json'
$payloadFixture = Join-Path $fixtureRoot 'payload-shaped.json'
$testScratchRoot = Join-Path (Join-Path $repoRoot 'build') ('nar-ledger-test-' + [Guid]::NewGuid().ToString('N'))
$outputRoot = Join-Path $testScratchRoot 'output'
$unsafeOutputRoot = Join-Path $testScratchRoot 'unsafe-output'
$generatedFixtureRoot = Join-Path $testScratchRoot 'fixtures'

function Assert-Equal([object] $Expected, [object] $Actual, [string] $Message) {
    if ($Expected -ne $Actual) {
        throw "$Message Expected '$Expected', got '$Actual'."
    }
}

function Invoke-Resolver([string] $FixturePath, [string] $OutputPath) {
    $captured = @(& pwsh -NoProfile -File $cliPath -FixturePath $FixturePath -OutputRoot $OutputPath 2>&1)
    [PSCustomObject]@{
        ExitCode = if ($null -eq $LASTEXITCODE) { 0 } else { $LASTEXITCODE }
        Output = ($captured -join [Environment]::NewLine)
    }
}

function Write-GeneratedFixture([string] $Name, [object] $Fixture) {
    New-Item -ItemType Directory -Path $generatedFixtureRoot -Force | Out-Null
    $path = Join-Path $generatedFixtureRoot "$Name.json"
    $Fixture | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $path -NoNewline -Encoding utf8
    return $path
}

function Assert-Rejected([object] $Fixture, [string] $Name, [string] $ExpectedMessage) {
    $fixturePath = Write-GeneratedFixture -Name $Name -Fixture $Fixture
    $result = Invoke-Resolver -FixturePath $fixturePath -OutputPath (Join-Path $unsafeOutputRoot $Name)
    if ($result.ExitCode -eq 0) {
        throw "Fixture '$Name' should be rejected with a non-zero exit code."
    }
    if ($result.Output -notmatch $ExpectedMessage) {
        throw "Fixture '$Name' rejection should match '$ExpectedMessage'. Output: $($result.Output)"
    }
}

try {
    $result = Invoke-Resolver -FixturePath $phaseOneFixture -OutputPath $outputRoot
    Assert-Equal 0 $result.ExitCode 'The metadata ledger CLI should accept the synthetic fixture.'

    $ledgerPath = Join-Path $outputRoot 'ledger.json'
    if (-not (Test-Path -LiteralPath $ledgerPath -PathType Leaf)) {
        throw "Expected ledger output at '$ledgerPath'."
    }

    $ledger = Get-Content -LiteralPath $ledgerPath -Raw | ConvertFrom-Json
    $rows = @($ledger.rows)
    Assert-Equal 5 $rows.Count 'The synthetic fixture should produce five ledger rows.'
    $actual = @($rows | ForEach-Object { $_.disposition })
    $expected = @(
        'nar-downloadable',
        'manifest-only',
        'unavailable',
        'permission-excluded',
        'duplicate-catalog-record'
    )
    Assert-Equal ($expected -join '|') ($actual -join '|') 'Ledger dispositions should follow source-row order.'

    $unsafe = Invoke-Resolver -FixturePath $payloadFixture -OutputPath $unsafeOutputRoot
    if ($unsafe.ExitCode -eq 0) {
        throw 'Payload-shaped fixture should be rejected with a non-zero exit code.'
    }
    if ($unsafe.Output -notmatch '(?i)archivePath') {
        throw "Payload rejection should name the disallowed field archivePath. Output: $($unsafe.Output)"
    }

    $validRow = [PSCustomObject]@{
        snapshotId = 'boundary-fixture'
        sourceRowOrdinal = 1
        title = 'Boundary Ghost'
        author = 'Fixture Author'
        landingUrl = 'https://example.test/boundary'
        manifest = $true
        evidence = [PSCustomObject]@{
            url = 'https://example.test/boundary'
            robotsAllowed = $true
            termsAllowed = $true
            titleSpecificInitialNarLink = $false
        }
    }
    Assert-Rejected -Name 'top-level-payload' -ExpectedMessage '(?i)archivePath' -Fixture ([PSCustomObject]@{
        archivePath = 'fixture.nar'
        rows = @($validRow)
    })
    Assert-Rejected -Name 'null-rows' -ExpectedMessage '(?i)rows array' -Fixture ([PSCustomObject]@{ rows = $null })
    Assert-Rejected -Name 'scalar-rows' -ExpectedMessage '(?i)rows array' -Fixture ([PSCustomObject]@{ rows = 'not-an-array' })

    foreach ($identityField in @('snapshotId', 'title', 'author')) {
        $malformedRow = $validRow.psobject.Copy()
        $malformedRow.$identityField = 7
        Assert-Rejected -Name "non-string-$identityField" -ExpectedMessage '(?i)requires snapshotId' -Fixture ([PSCustomObject]@{ rows = @($malformedRow) })
    }

    $caseFixture = Write-GeneratedFixture -Name 'url-case-identity' -Fixture ([PSCustomObject]@{
        rows = @(
            [PSCustomObject]@{
                snapshotId = 'url-case'
                sourceRowOrdinal = 1
                title = 'Case Ghost'
                author = 'Fixture Author'
                landingUrl = 'https://example.test/CasePath?Mode=UPPER'
                manifest = $true
                evidence = [PSCustomObject]@{
                    url = 'https://example.test/CasePath?Mode=UPPER'
                    robotsAllowed = $true
                    termsAllowed = $true
                    titleSpecificInitialNarLink = $false
                }
            },
            [PSCustomObject]@{
                snapshotId = 'url-case'
                sourceRowOrdinal = 2
                title = 'Case Ghost'
                author = 'Fixture Author'
                landingUrl = 'https://example.test/casepath?mode=upper'
                manifest = $true
                evidence = [PSCustomObject]@{
                    url = 'https://example.test/casepath?mode=upper'
                    robotsAllowed = $true
                    termsAllowed = $true
                    titleSpecificInitialNarLink = $false
                }
            }
        )
    })
    $caseOutputRoot = Join-Path $outputRoot 'url-case'
    $caseResult = Invoke-Resolver -FixturePath $caseFixture -OutputPath $caseOutputRoot
    Assert-Equal 0 $caseResult.ExitCode 'Path/query case variants should be accepted as distinct metadata rows.'
    $caseLedger = Get-Content -LiteralPath (Join-Path $caseOutputRoot 'ledger.json') -Raw | ConvertFrom-Json
    Assert-Equal 'manifest-only|manifest-only' ((@($caseLedger.rows | ForEach-Object { $_.disposition })) -join '|') 'Path/query case variants must not be duplicates.'
    Assert-Equal 'https://example.test/CasePath?Mode=UPPER' $caseLedger.rows[0].evidenceUrls[0] 'Evidence URLs must preserve observed path/query case.'

    $orderingRows = @(
        [PSCustomObject]@{
            snapshotId = 'snapshot-z'
            sourceRowOrdinal = 2
            title = 'Zeta Two'
            author = 'Fixture Author'
            landingUrl = 'https://example.test/zeta-two'
            manifest = $true
            evidence = [PSCustomObject]@{
                url = 'https://example.test/zeta-two'
                robotsAllowed = $true
                termsAllowed = $true
                titleSpecificInitialNarLink = $false
            }
        },
        [PSCustomObject]@{
            snapshotId = 'snapshot-a'
            sourceRowOrdinal = 2
            title = 'Alpha Two'
            author = 'Fixture Author'
            landingUrl = 'https://example.test/alpha-two'
            manifest = $true
            evidence = [PSCustomObject]@{
                url = 'https://example.test/alpha-two'
                robotsAllowed = $true
                termsAllowed = $true
                titleSpecificInitialNarLink = $false
            }
        },
        [PSCustomObject]@{
            snapshotId = 'snapshot-z'
            sourceRowOrdinal = 1
            title = 'Zeta One'
            author = 'Fixture Author'
            landingUrl = 'https://example.test/zeta-one'
            manifest = $true
            evidence = [PSCustomObject]@{
                url = 'https://example.test/zeta-one'
                robotsAllowed = $true
                termsAllowed = $true
                titleSpecificInitialNarLink = $false
            }
        },
        [PSCustomObject]@{
            snapshotId = 'snapshot-a'
            sourceRowOrdinal = 1
            title = 'Alpha One'
            author = 'Fixture Author'
            landingUrl = 'https://example.test/alpha-one'
            manifest = $true
            evidence = [PSCustomObject]@{
                url = 'https://example.test/alpha-one'
                robotsAllowed = $true
                termsAllowed = $true
                titleSpecificInitialNarLink = $false
            }
        }
    )
    $orderingFixtureA = Write-GeneratedFixture -Name 'ordering-a' -Fixture ([PSCustomObject]@{ rows = @($orderingRows) })
    $orderingFixtureB = Write-GeneratedFixture -Name 'ordering-b' -Fixture ([PSCustomObject]@{ rows = @($orderingRows[3], $orderingRows[1], $orderingRows[0], $orderingRows[2]) })
    $orderingOutputA = Join-Path $outputRoot 'ordering-a'
    $orderingOutputB = Join-Path $outputRoot 'ordering-b'
    $orderingResultA = Invoke-Resolver -FixturePath $orderingFixtureA -OutputPath $orderingOutputA
    $orderingResultB = Invoke-Resolver -FixturePath $orderingFixtureB -OutputPath $orderingOutputB
    Assert-Equal 0 $orderingResultA.ExitCode 'The first ordering fixture should be accepted.'
    Assert-Equal 0 $orderingResultB.ExitCode 'The reordered ordering fixture should be accepted.'
    $orderingLedgerAPath = Join-Path $orderingOutputA 'ledger.json'
    $orderingLedgerBPath = Join-Path $orderingOutputB 'ledger.json'
    $orderingLedgerABytes = [IO.File]::ReadAllBytes($orderingLedgerAPath)
    $orderingLedgerBBytes = [IO.File]::ReadAllBytes($orderingLedgerBPath)
    $orderingLedgerABase64 = [Convert]::ToBase64String($orderingLedgerABytes)
    $orderingLedgerBBase64 = [Convert]::ToBase64String($orderingLedgerBBytes)
    Assert-Equal $orderingLedgerABase64 $orderingLedgerBBase64 'Equivalent fixture row orderings must produce byte-identical ledger.json output.'
    $orderingLedgerA = [Text.Encoding]::UTF8.GetString($orderingLedgerABytes)
    $orderingRowsOutput = @((ConvertFrom-Json $orderingLedgerA).rows)
    $orderingKeys = @($orderingRowsOutput | ForEach-Object { '{0}:{1}' -f $_.snapshotId, $_.sourceRowOrdinal })
    Assert-Equal 'snapshot-a:1|snapshot-a:2|snapshot-z:1|snapshot-z:2' ($orderingKeys -join '|') 'Ledger rows must be sorted by snapshotId and sourceRowOrdinal.'
}
finally {
    if (Test-Path -LiteralPath $testScratchRoot) {
        Remove-Item -LiteralPath $testScratchRoot -Recurse -Force
    }
}

Write-Output 'PASS: NAR metadata ledger integration contract.'
