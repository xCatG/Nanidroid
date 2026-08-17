Set-StrictMode -Version Latest

$script:DisallowedPayloadFields = @(
    'narPath',
    'archivePath',
    'sha256',
    'bytes',
    'objectKey'
)

function Get-NarPropertyValue {
    param(
        [AllowNull()]
        [object] $Object,
        [Parameter(Mandatory)]
        [string] $Name
    )

    if ($null -eq $Object) {
        return $null
    }

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }

    return $property.Value
}

function Assert-NoNarPayloadFields {
    param(
        [AllowNull()]
        [object] $Value,
        [string] $Location = 'fixture'
    )

    if ($null -eq $Value -or $Value -is [string] -or $Value -is [ValueType]) {
        return
    }

    if ($Value -is [System.Collections.IDictionary]) {
        foreach ($key in $Value.Keys) {
            if ($script:DisallowedPayloadFields -contains [string] $key) {
                throw "Disallowed payload field '$key' at $Location."
            }
            Assert-NoNarPayloadFields -Value $Value[$key] -Location "$Location.$key"
        }
        return
    }

    if ($Value -is [System.Collections.IEnumerable]) {
        $index = 0
        foreach ($item in $Value) {
            Assert-NoNarPayloadFields -Value $item -Location "$Location[$index]"
            $index++
        }
        return
    }

    foreach ($property in $Value.PSObject.Properties) {
        if ($script:DisallowedPayloadFields -contains $property.Name) {
            throw "Disallowed payload field '$($property.Name)' at $Location."
        }
        Assert-NoNarPayloadFields -Value $property.Value -Location "$Location.$($property.Name)"
    }
}

function Normalize-NarIdentityText {
    param([AllowNull()][object] $Value)

    if ($Value -isnot [string]) {
        return $null
    }

    $normalized = ($Value -replace '\s+', ' ').Trim()
    if ([string]::IsNullOrWhiteSpace($normalized)) {
        return $null
    }

    return $normalized.ToLowerInvariant()
}

function ConvertTo-NarCanonicalLandingUrl {
    param([AllowNull()][object] $Value)

    if ($Value -isnot [string] -or [string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }

    $uri = $null
    if (-not [Uri]::TryCreate($Value.Trim(), [UriKind]::Absolute, [ref] $uri) -or
        ($uri.Scheme -ne 'http' -and $uri.Scheme -ne 'https')) {
        return $null
    }

    $path = $uri.GetComponents([UriComponents]::Path, [UriFormat]::UriEscaped)
    if ($path.EndsWith('/')) {
        $path = $path.TrimEnd('/')
    }
    if ($path.Length -gt 0) {
        $path = "/$path"
    }
    $query = $uri.GetComponents([UriComponents]::Query, [UriFormat]::UriEscaped)
    $querySuffix = if ($query.Length -gt 0) { "?$query" } else { '' }
    return '{0}://{1}{2}{3}' -f $uri.Scheme.ToLowerInvariant(), $uri.Authority, $path, $querySuffix
}

function Assert-NarOptionalBooleanProperty {
    param(
        [Parameter(Mandatory)][object] $Object,
        [Parameter(Mandatory)][string] $Name,
        [Parameter(Mandatory)][string] $Location
    )

    $value = Get-NarPropertyValue -Object $Object -Name $Name
    if ($null -ne $value -and $value -isnot [bool]) {
        throw "$Location.$Name must be a boolean when present."
    }
}

function Assert-NarCorpusMetadataRows {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [object[]] $Rows
    )

    Assert-NoNarPayloadFields -Value $Rows
    $ordinals = @{}

    foreach ($row in $Rows) {
        if ($null -eq $row -or $row -is [string] -or $row -is [ValueType]) {
            throw 'Each metadata row must be an object.'
        }

        $snapshotId = Get-NarPropertyValue -Object $row -Name 'snapshotId'
        $ordinal = Get-NarPropertyValue -Object $row -Name 'sourceRowOrdinal'
        $title = Get-NarPropertyValue -Object $row -Name 'title'
        $author = Get-NarPropertyValue -Object $row -Name 'author'
        $landingUrl = Get-NarPropertyValue -Object $row -Name 'landingUrl'
        $evidence = Get-NarPropertyValue -Object $row -Name 'evidence'
        $manifest = Get-NarPropertyValue -Object $row -Name 'manifest'

        if ($snapshotId -isnot [string] -or
            $title -isnot [string] -or
            $author -isnot [string] -or
            [string]::IsNullOrWhiteSpace($snapshotId) -or
            $null -eq $ordinal -or
            [string]::IsNullOrWhiteSpace($title) -or
            [string]::IsNullOrWhiteSpace($author) -or
            $null -eq (ConvertTo-NarCanonicalLandingUrl -Value $landingUrl)) {
            throw 'Each metadata row requires snapshotId, sourceRowOrdinal, title, author, and an absolute HTTP(S) landingUrl.'
        }
        if ($null -eq $evidence -or $evidence -is [string] -or $evidence -is [ValueType] -or $evidence -is [System.Collections.IEnumerable]) {
            throw 'evidence must be an object.'
        }
        if ($null -ne $manifest -and $manifest -isnot [bool]) {
            throw 'manifest must be a boolean when present.'
        }
        foreach ($policyField in @(
            'robotsAllowed',
            'termsAllowed',
            'authorNoticeExcluded',
            'personalUseOnly',
            'accessBoundary',
            'titleSpecificInitialNarLink'
        )) {
            Assert-NarOptionalBooleanProperty -Object $evidence -Name $policyField -Location 'evidence'
        }

        $ordinalNumber = 0
        if (-not [int]::TryParse([string] $ordinal, [ref] $ordinalNumber) -or $ordinalNumber -lt 1) {
            throw "sourceRowOrdinal must be a positive integer for snapshot '$snapshotId'."
        }

        $ordinalKey = "$snapshotId`:$ordinalNumber"
        if ($ordinals.ContainsKey($ordinalKey)) {
            throw "Duplicate sourceRowOrdinal '$ordinalNumber' in snapshot '$snapshotId'."
        }
        $ordinals[$ordinalKey] = $true
    }
}

function Assert-NarCorpusMetadataFixture {
    param([AllowNull()][object] $Fixture)

    if ($null -eq $Fixture -or $Fixture -is [string] -or $Fixture -is [ValueType]) {
        throw 'Fixture must be an object containing a rows array.'
    }

    Assert-NoNarPayloadFields -Value $Fixture
    $rows = Get-NarPropertyValue -Object $Fixture -Name 'rows'
    if ($null -eq $rows -or $rows -is [string]) {
        throw 'Fixture must contain a non-null rows array.'
    }

    Assert-NarCorpusMetadataRows -Rows @($rows)
}

function Get-NarEvidenceUrls {
    param([Parameter(Mandatory)][object] $Row)

    $urls = [System.Collections.Generic.List[string]]::new()
    foreach ($candidate in @(
        (Get-NarPropertyValue -Object $Row -Name 'landingUrl'),
        (Get-NarPropertyValue -Object (Get-NarPropertyValue -Object $Row -Name 'evidence') -Name 'url')
    )) {
        $url = ConvertTo-NarCanonicalLandingUrl -Value $candidate
        if ($null -ne $url -and -not $urls.Contains($url)) {
            $urls.Add($url)
        }
    }
    return @($urls | Sort-Object)
}

function Resolve-NarCorpusMetadataRows {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [object[]] $Rows
    )

    Assert-NarCorpusMetadataRows -Rows $Rows
    $orderedRows = @($Rows | Sort-Object @{ Expression = { [string] (Get-NarPropertyValue -Object $_ -Name 'snapshotId') } }, @{ Expression = { [int] (Get-NarPropertyValue -Object $_ -Name 'sourceRowOrdinal') } })
    $canonicalRecords = [System.Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
    $results = [System.Collections.Generic.List[object]]::new()

    foreach ($row in $orderedRows) {
        $snapshotId = [string] (Get-NarPropertyValue -Object $row -Name 'snapshotId')
        $ordinal = [int] (Get-NarPropertyValue -Object $row -Name 'sourceRowOrdinal')
        $catalogRecordId = "$snapshotId`:$ordinal"
        $title = Normalize-NarIdentityText -Value (Get-NarPropertyValue -Object $row -Name 'title')
        $author = Normalize-NarIdentityText -Value (Get-NarPropertyValue -Object $row -Name 'author')
        $landingUrl = ConvertTo-NarCanonicalLandingUrl -Value (Get-NarPropertyValue -Object $row -Name 'landingUrl')
        $identityKey = '{0}{1}{2}{1}{3}' -f $title, [char] 0x1f, $author, $landingUrl
        $evidence = Get-NarPropertyValue -Object $row -Name 'evidence'
        $robotsAllowed = Get-NarPropertyValue -Object $evidence -Name 'robotsAllowed'
        $termsAllowed = Get-NarPropertyValue -Object $evidence -Name 'termsAllowed'
        $authorNoticeExcluded = Get-NarPropertyValue -Object $evidence -Name 'authorNoticeExcluded'
        $personalUseOnly = Get-NarPropertyValue -Object $evidence -Name 'personalUseOnly'
        $accessBoundary = Get-NarPropertyValue -Object $evidence -Name 'accessBoundary'
        $manifest = Get-NarPropertyValue -Object $row -Name 'manifest'
        $initialNarLink = Get-NarPropertyValue -Object $evidence -Name 'titleSpecificInitialNarLink'

        $disposition = $null
        $reasonCode = $null
        $confidence = $null
        $canonicalRecordId = $catalogRecordId
        $duplicateOf = $null

        if ($robotsAllowed -eq $false -or $termsAllowed -eq $false -or $authorNoticeExcluded -eq $true -or $personalUseOnly -eq $true -or $accessBoundary -eq $true) {
            $disposition = 'permission-excluded'
            $reasonCode = 'retrieval-not-permitted'
            $confidence = 'high'
        }
        elseif ($canonicalRecords.ContainsKey($identityKey)) {
            $canonicalRecordId = $canonicalRecords[$identityKey]
            $duplicateOf = $canonicalRecordId
            $disposition = 'duplicate-catalog-record'
            $reasonCode = 'matching-title-author-landing-url'
            $confidence = 'high'
        }
        elseif ($initialNarLink -eq $true) {
            $disposition = 'nar-downloadable'
            $reasonCode = 'observed-title-specific-initial-nar-link'
            $confidence = 'high'
        }
        elseif ($manifest -eq $true) {
            $disposition = 'manifest-only'
            $reasonCode = 'catalog-manifest-without-observed-initial-nar-link'
            $confidence = 'medium'
        }
        else {
            $disposition = 'unavailable'
            $reasonCode = 'no-observed-public-acquisition-metadata'
            $confidence = 'low'
        }

        if (-not $canonicalRecords.ContainsKey($identityKey)) {
            $canonicalRecords[$identityKey] = $catalogRecordId
        }

        $results.Add([PSCustomObject]@{
            catalogRecordId = $catalogRecordId
            canonicalRecordId = $canonicalRecordId
            confidence = $confidence
            disposition = $disposition
            duplicateOf = $duplicateOf
            evidenceUrls = @(Get-NarEvidenceUrls -Row $row)
            reasonCode = $reasonCode
            snapshotId = $snapshotId
            sourceRowOrdinal = $ordinal
        })
    }

    return @($results)
}

function ConvertTo-NarCanonicalJson {
    param([AllowNull()][object] $Value)

    if ($null -eq $Value) { return 'null' }
    if ($Value -is [string]) { return ($Value | ConvertTo-Json -Compress) }
    if ($Value -is [bool]) { return $Value.ToString().ToLowerInvariant() }
    if ($Value -is [ValueType]) { return ([System.Convert]::ToString($Value, [Globalization.CultureInfo]::InvariantCulture)) }

    if ($Value -is [System.Collections.IEnumerable]) {
        return '[' + (($Value | ForEach-Object { ConvertTo-NarCanonicalJson -Value $_ }) -join ',') + ']'
    }

    $properties = @($Value.PSObject.Properties | Sort-Object Name)
    return '{' + (($properties | ForEach-Object {
        (ConvertTo-NarCanonicalJson -Value $_.Name) + ':' + (ConvertTo-NarCanonicalJson -Value $_.Value)
    }) -join ',') + '}'
}

Export-ModuleMember -Function Assert-NarCorpusMetadataFixture, Assert-NarCorpusMetadataRows, ConvertTo-NarCanonicalJson, Resolve-NarCorpusMetadataRows
