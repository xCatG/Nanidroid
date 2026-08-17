[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $FixturePath,
    [Parameter(Mandatory)]
    [string] $OutputRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

try {
    if ($FixturePath.EndsWith('.nar', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'FixturePath must not reference a .nar payload.'
    }
    if (-not (Test-Path -LiteralPath $FixturePath -PathType Leaf)) {
        throw "FixturePath does not exist: $FixturePath"
    }

    Import-Module (Join-Path $PSScriptRoot 'nar-corpus-metadata-resolver.psm1') -Force
    $fixture = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $FixturePath)) | ConvertFrom-Json
    if ($null -eq $fixture -or $null -eq $fixture.PSObject.Properties['rows']) {
        throw 'Fixture must contain a rows array.'
    }

    $rows = @($fixture.rows)
    Assert-NarCorpusMetadataRows -Rows $rows
    $ledger = [PSCustomObject]@{ rows = @(Resolve-NarCorpusMetadataRows -Rows $rows) }
    $json = ConvertTo-NarCanonicalJson -Value $ledger

    New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $OutputRoot 'ledger.json'), $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    exit 0
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
