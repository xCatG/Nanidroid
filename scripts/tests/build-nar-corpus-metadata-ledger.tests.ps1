$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$cliPath = Join-Path $repoRoot 'scripts\build-nar-corpus-metadata-ledger.ps1'
$fixtureRoot = Join-Path $PSScriptRoot 'fixtures\nar-corpus-metadata-resolver'
$phaseOneFixture = Join-Path $fixtureRoot 'phase-one-synthetic.json'
$payloadFixture = Join-Path $fixtureRoot 'payload-shaped.json'
$outputRoot = Join-Path ([IO.Path]::GetTempPath()) ('nanidroid-nar-ledger-' + [Guid]::NewGuid().ToString('N'))
$unsafeOutputRoot = Join-Path ([IO.Path]::GetTempPath()) ('nanidroid-nar-ledger-unsafe-' + [Guid]::NewGuid().ToString('N'))

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
}
finally {
    if (Test-Path -LiteralPath $outputRoot) {
        Remove-Item -LiteralPath $outputRoot -Recurse -Force
    }
    if (Test-Path -LiteralPath $unsafeOutputRoot) {
        Remove-Item -LiteralPath $unsafeOutputRoot -Recurse -Force
    }
}

Write-Output 'PASS: NAR metadata ledger integration contract.'
