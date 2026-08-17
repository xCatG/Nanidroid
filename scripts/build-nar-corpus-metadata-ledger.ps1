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
    $fixtureJson = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $FixturePath))
    $fixtureDocument = [Text.Json.JsonDocument]::Parse($fixtureJson)
    if ($fixtureDocument.RootElement.ValueKind -ne [Text.Json.JsonValueKind]::Object) {
        throw 'Fixture must be an object containing a rows array.'
    }
    try {
        $fixtureRowsElement = $fixtureDocument.RootElement.GetProperty('rows')
    }
    catch {
        throw 'Fixture must contain a rows array.'
    }
    if ($fixtureRowsElement.ValueKind -ne [Text.Json.JsonValueKind]::Array) {
        throw 'Fixture must contain a non-null rows array.'
    }
    $fixtureDocument.Dispose()
    $fixture = $fixtureJson | ConvertFrom-Json
    Assert-NarCorpusMetadataFixture -Fixture $fixture
    $rows = @($fixture.rows)
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
