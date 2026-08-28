[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]
    $DeviceSerial,

    [string[]]
    $CorpusRoots = @('.', 'build/ui-audit'),

    [string]
    $ManifestPath = 'docs/testing/nar-corpus-manifest.json',

    [string]
    $AdbPath,

    [ValidateRange(1, 120)]
    [int]
    $BuildTimeoutMinutes = 45,

    [ValidateRange(1, 30)]
    [int]
    $InstrumentationTimeoutMinutes = 10,

    [switch]
    $DryRun,

    [switch]
    $HostOnlySelfTest
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-StrictMode -Version Latest

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot

$targetPackage = 'com.cattailsw.nanidroid'
$testPackage = 'com.cattailsw.nanidroid.test'
$instrumentationRunner = "$testPackage/androidx.test.runner.AndroidJUnitRunner"
$reportRoot = Join-Path $repoRoot 'build\reports\cross-engine-runtime'
$runId = [guid]::NewGuid().ToString('N')
$tmpRunRoot = "/data/local/tmp/nanidroid-cross-engine/$runId"
$privateRelativeRunRoot = "cache/cross-engine-runtime/$runId"
$externalRunRoot = "/sdcard/Android/data/$targetPackage/files/cross-engine-runtime/$runId"
$debugApk = Join-Path $repoRoot 'build\outputs\apk\debug\Nanidroid-debug.apk'
$testApk = Join-Path $repoRoot 'build\outputs\apk\androidTest\debug\Nanidroid-debug-androidTest.apk'

New-Item -ItemType Directory -Force -Path $reportRoot | Out-Null

try {
    [Text.Encoding]::RegisterProvider([Text.CodePagesEncodingProvider]::Instance)
}
catch {
    # Windows PowerShell already exposes legacy code pages without registration.
}

function Fail([string]$Message) {
    throw [InvalidOperationException]::new($Message)
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-Utf8Length([string]$Value) {
    return [Text.Encoding]::UTF8.GetByteCount($Value)
}

function Get-CollisionKey([string]$Value) {
    return $Value.Normalize([Text.NormalizationForm]::FormC).ToLowerInvariant().Normalize([Text.NormalizationForm]::FormC)
}

function Trim-Java([string]$Value) {
    return $Value -replace '^[\x00-\x20]+', '' -replace '[\x00-\x20]+$', ''
}

function Resolve-CorpusRootSet([string[]]$Roots) {
    $requested = @($Roots | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $resolvedDirectories = @()
    $resolvedFiles = @()
    $missing = @()
    $seen = @{}
    foreach ($root in $requested) {
        $candidate = if ([IO.Path]::IsPathRooted($root)) { $root } else { Join-Path $repoRoot $root }
        try {
            $resolved = (Resolve-Path -LiteralPath $candidate -ErrorAction Stop).Path.TrimEnd('\')
        }
        catch {
            $missing += [pscustomobject]@{ requested = $root; absolutePath = [IO.Path]::GetFullPath($candidate) }
            continue
        }
        $key = $resolved.ToLowerInvariant()
        if ($seen.ContainsKey($key)) { continue }
        if (Test-Path -LiteralPath $resolved -PathType Leaf) {
            if ([IO.Path]::GetExtension($resolved) -ieq '.nar') {
                $resolvedFiles += $resolved
                $seen[$key] = $true
            }
            else {
                $missing += [pscustomobject]@{ requested = $root; absolutePath = $resolved; reason = 'not-a-nar' }
            }
            continue
        }
        if (Test-Path -LiteralPath $resolved -PathType Container) {
            $resolvedDirectories += $resolved
            $seen[$key] = $true
        }
    }

    $dedupedDirectories = @()
    foreach ($candidate in @($resolvedDirectories | Sort-Object Length, @{ Expression = { $_ } })) {
        $nested = $false
        foreach ($previous in $dedupedDirectories) {
            if (($candidate + '\').StartsWith($previous.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
                $nested = $true
                break
            }
        }
        if (-not $nested) { $dedupedDirectories += $candidate }
    }
    return [pscustomobject]@{
        requested = $requested
        resolved = @($dedupedDirectories + $resolvedFiles)
        missing = $missing
    }
}

function Collect-Archives([string[]]$Roots) {
    $paths = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($root in $Roots) {
        if (Test-Path -LiteralPath $root -PathType Leaf) {
            [void]$paths.Add((Get-Item -LiteralPath $root).FullName)
            continue
        }
        Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.nar' -ErrorAction Stop | ForEach-Object {
            [void]$paths.Add($_.FullName)
        }
    }
    return @($paths | Sort-Object)
}

function Normalize-ArchivePath([string]$RawName, [bool]$Directory) {
    if ([string]::IsNullOrEmpty($RawName) -or $RawName.Length -gt 4096) {
        Fail "invalid raw ZIP entry name"
    }
    $archiveName = $RawName.Replace('\', '/')
    if ($Directory -ne $archiveName.EndsWith('/')) {
        Fail "directory metadata disagrees with ZIP entry name '$RawName'"
    }
    $original = if ($Directory) { $archiveName.Substring(0, $archiveName.Length - 1) } else { $archiveName }
    if ([string]::IsNullOrEmpty($original) -or $original.StartsWith('/')) {
        Fail "unsafe ZIP entry path '$RawName'"
    }
    $components = @($original.Split('/'))
    if ($components.Count -gt 32) { Fail "ZIP entry exceeds path depth limit '$RawName'" }
    $normalizedComponents = @()
    foreach ($component in $components) {
        if ([string]::IsNullOrEmpty($component) -or $component -in @('.', '..') -or
            $component.Contains(':') -or $component -match '[\x00-\x1f\x7f]') {
            Fail "unsafe ZIP entry path '$RawName'"
        }
        $normalized = $component.Normalize([Text.NormalizationForm]::FormC)
        if ((Get-Utf8Length $normalized) -gt 255) { Fail "ZIP component exceeds 255 UTF-8 bytes '$RawName'" }
        $normalizedComponents += $normalized
    }
    $path = $normalizedComponents -join '/'
    if ((Get-Utf8Length $path) -gt 1024) { Fail "ZIP path exceeds 1024 UTF-8 bytes '$RawName'" }
    return [pscustomobject]@{
        original = $original
        normalized = $path
        key = Get-CollisionKey $path
        directory = $Directory
    }
}

function Read-ZipEntryBytes([IO.Compression.ZipArchiveEntry]$Entry, [int]$MaximumBytes) {
    if ($Entry.Length -gt $MaximumBytes) { Fail "ZIP entry '$($Entry.FullName)' exceeds $MaximumBytes bytes" }
    $stream = $Entry.Open()
    $memory = [IO.MemoryStream]::new()
    try {
        $buffer = [byte[]]::new(8192)
        while ($true) {
            $count = $stream.Read($buffer, 0, $buffer.Length)
            if ($count -le 0) { break }
            if ($memory.Length + $count -gt $MaximumBytes) {
                Fail "ZIP entry '$($Entry.FullName)' exceeds $MaximumBytes bytes while reading"
            }
            $memory.Write($buffer, 0, $count)
        }
        return $memory.ToArray()
    }
    finally {
        $memory.Dispose()
        $stream.Dispose()
    }
}

function Decode-NarText([byte[]]$Bytes) {
    $offset = 0
    $encodingName = $null
    if ($Bytes.Length -ge 3 -and $Bytes[0] -eq 0xef -and $Bytes[1] -eq 0xbb -and $Bytes[2] -eq 0xbf) {
        $encodingName = 'UTF-8'
        $offset = 3
    }
    if ($null -eq $encodingName) {
        $ascii = [Text.Encoding]::ASCII.GetString($Bytes)
        foreach ($line in @($ascii -split "\r?\n")) {
            $trimmed = Trim-Java $line
            if ([string]::IsNullOrEmpty($trimmed) -or $trimmed.StartsWith('//')) { continue }
            $comma = $trimmed.IndexOf(',')
            if ($comma -gt 0 -and (Get-CollisionKey (Trim-Java $trimmed.Substring(0, $comma))) -eq 'charset') {
                $encodingName = Trim-Java $trimmed.Substring($comma + 1)
            }
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($encodingName)) { $encodingName = 'Shift_JIS' }
    try {
        $encoding = [Text.Encoding]::GetEncoding(
            $encodingName,
            [Text.EncoderFallback]::ExceptionFallback,
            [Text.DecoderFallback]::ExceptionFallback
        )
        return $encoding.GetString($Bytes, $offset, $Bytes.Length - $offset)
    }
    catch {
        Fail "descriptor uses an unsupported or invalid charset '$encodingName': $($_.Exception.Message)"
    }
}

function Parse-Descriptor([string]$Text, [switch]$Strict) {
    $values = @{}
    foreach ($line in @($Text -split "\r?\n")) {
        $trimmed = Trim-Java $line
        if ([string]::IsNullOrEmpty($trimmed) -or $trimmed.StartsWith('//')) { continue }
        $comma = $line.IndexOf(',')
        if ($comma -le 0) {
            if ($Strict) { Fail 'malformed install descriptor line' }
            continue
        }
        $key = Get-CollisionKey (Trim-Java $line.Substring(0, $comma))
        $value = (Trim-Java $line.Substring($comma + 1)).Normalize([Text.NormalizationForm]::FormC)
        if ($Strict -and ([string]::IsNullOrEmpty($key) -or $key -match '[\x00-\x1f\x7f]' -or
            $value -match '[\x00-\x1f\x7f]' -or ($key -eq 'charset' -and $values.Count -gt 0) -or
            $values.ContainsKey($key))) {
            Fail 'invalid or duplicate install descriptor metadata'
        }
        if (-not $values.ContainsKey($key)) { $values[$key] = $value }
    }
    return $values
}

function Read-ZipRange([IO.FileStream]$Stream, [long]$Position, [int]$Length) {
    if ($Position -lt 0 -or $Length -lt 0 -or $Position -gt $Stream.Length -or $Length -gt ($Stream.Length - $Position)) {
        Fail 'ZIP bounds'
    }
    [void]$Stream.Seek($Position, [IO.SeekOrigin]::Begin)
    $bytes = [byte[]]::new($Length)
    $read = 0
    while ($read -lt $Length) {
        $count = $Stream.Read($bytes, $read, $Length - $read)
        if ($count -le 0) { Fail 'truncated ZIP range' }
        $read += $count
    }
    return $bytes
}

function Get-ZipUInt16([byte[]]$Source, [int]$Offset) {
    return [long]$Source[$Offset] + ([long]$Source[$Offset + 1] * 256)
}

function Get-ZipUInt32([byte[]]$Source, [int]$Offset) {
    return [long]$Source[$Offset] +
        ([long]$Source[$Offset + 1] * 256) +
        ([long]$Source[$Offset + 2] * 65536) +
        ([long]$Source[$Offset + 3] * 16777216)
}

function Get-ZipUInt64([byte[]]$Source, [int]$Offset) {
    $low = Get-ZipUInt32 $Source $Offset
    $high = Get-ZipUInt32 $Source ($Offset + 4)
    if (($high -band 0x80000000L) -ne 0) { Fail 'ZIP64 value overflow' }
    return [long]($low + ($high * 4294967296L))
}

function Assert-ZipRange([long]$Offset, [long]$Size, [long]$Boundary) {
    if ($Offset -lt 0 -or $Size -lt 0 -or $Boundary -lt 0 -or
        $Offset -gt $Boundary -or $Size -gt ($Boundary - $Offset)) {
        Fail 'ZIP bounds'
    }
}

function Get-ZipCentralPreflight([string]$Path) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    try {
        $length = $stream.Length
        if ($length -lt 22) { Fail 'missing EOCD' }
        $tailLength = [int][Math]::Min($length, 65557L)
        $tailOffset = $length - $tailLength
        [byte[]]$tail = Read-ZipRange $stream $tailOffset $tailLength
        $eocdInTail = -1
        for ($index = $tail.Length - 22; $index -ge 0; $index--) {
            if ((Get-ZipUInt32 $tail $index) -eq 0x06054b50L -and
                ($index + 22 + (Get-ZipUInt16 $tail ($index + 20))) -eq $tail.Length) {
                $eocdInTail = $index
                break
            }
        }
        if ($eocdInTail -lt 0) { Fail 'invalid EOCD tail' }

        $eocdOffset = $tailOffset + $eocdInTail
        [byte[]]$eocd = $tail[$eocdInTail..($eocdInTail + 21)]
        $disk = Get-ZipUInt16 $eocd 4
        $centralDisk = Get-ZipUInt16 $eocd 6
        $entriesOnDisk = Get-ZipUInt16 $eocd 8
        $entries = Get-ZipUInt16 $eocd 10
        $centralSize = Get-ZipUInt32 $eocd 12
        $centralOffset = Get-ZipUInt32 $eocd 16
        $centralBoundary = $eocdOffset
        $zip64 = $entriesOnDisk -eq 0xffffL -or $entries -eq 0xffffL -or
            $centralSize -eq 0xffffffffL -or $centralOffset -eq 0xffffffffL

        if ($zip64) {
            if ($disk -ne 0 -or $centralDisk -ne 0 -or $eocdOffset -lt 20) {
                Fail 'invalid ZIP64 locator or multi-disk archive'
            }
            $locatorOffset = $eocdOffset - 20
            [byte[]]$locator = Read-ZipRange $stream $locatorOffset 20
            if ((Get-ZipUInt32 $locator 0) -ne 0x07064b50L -or
                (Get-ZipUInt32 $locator 4) -ne 0 -or
                (Get-ZipUInt32 $locator 16) -ne 1) {
                Fail 'multi-disk ZIP64 locator'
            }
            $recordOffset = Get-ZipUInt64 $locator 8
            Assert-ZipRange $recordOffset 56 $locatorOffset
            [byte[]]$record = Read-ZipRange $stream $recordOffset 56
            if ((Get-ZipUInt32 $record 0) -ne 0x06064b50L) { Fail 'invalid ZIP64 EOCD signature' }
            $recordSize = Get-ZipUInt64 $record 4
            if ($recordSize -lt 44 -or $recordSize -ne ($locatorOffset - $recordOffset - 12) -or
                (Get-ZipUInt32 $record 16) -ne 0 -or (Get-ZipUInt32 $record 20) -ne 0) {
                Fail 'invalid ZIP64 EOCD'
            }
            $zip64EntriesOnDisk = Get-ZipUInt64 $record 24
            $zip64Entries = Get-ZipUInt64 $record 32
            if ($zip64EntriesOnDisk -ne $zip64Entries) { Fail 'multi-disk ZIP64 entries' }
            $zip64CentralSize = Get-ZipUInt64 $record 40
            $zip64CentralOffset = Get-ZipUInt64 $record 48
            if (($entriesOnDisk -ne 0xffffL -and $entriesOnDisk -ne $zip64Entries) -or
                ($entries -ne 0xffffL -and $entries -ne $zip64Entries) -or
                ($centralSize -ne 0xffffffffL -and $centralSize -ne $zip64CentralSize) -or
                ($centralOffset -ne 0xffffffffL -and $centralOffset -ne $zip64CentralOffset)) {
                Fail 'inconsistent ZIP64 EOCD'
            }
            $entriesOnDisk = $zip64Entries
            $entries = $zip64Entries
            $centralSize = $zip64CentralSize
            $centralOffset = $zip64CentralOffset
            $centralBoundary = $recordOffset
        }
        elseif ($disk -ne 0 -or $centralDisk -ne 0 -or $entriesOnDisk -ne $entries) {
            Fail 'multi-disk archive'
        }

        Assert-ZipRange $centralOffset $centralSize $centralBoundary
        if ($centralSize -ne ($centralBoundary - $centralOffset)) { Fail 'central directory gap' }
        if ($entries -gt 10000) {
            return [pscustomobject]@{ entryCount = 10001; entryCountOverLimit = $true }
        }

        $end = $centralOffset + $centralSize
        $cursor = $centralOffset
        $count = 0
        while ($cursor -lt $end) {
            if ($count -ge 10001 -or ($end - $cursor) -lt 46) {
                Fail 'central entry limit or truncation'
            }
            [byte[]]$header = Read-ZipRange $stream $cursor 46
            if ((Get-ZipUInt32 $header 0) -ne 0x02014b50L -or (Get-ZipUInt16 $header 34) -ne 0) {
                Fail 'invalid central record or multi-disk entry'
            }
            $variableLength = (Get-ZipUInt16 $header 28) +
                (Get-ZipUInt16 $header 30) +
                (Get-ZipUInt16 $header 32)
            $recordLength = 46L + $variableLength
            if ($recordLength -gt ($end - $cursor)) { Fail 'central variable fields' }
            $cursor += $recordLength
            $count++
        }
        if ($cursor -ne $end -or $count -ne $entries) { Fail 'central count mismatch' }
        return [pscustomobject]@{ entryCount = $count; entryCountOverLimit = $false }
    }
    finally {
        $stream.Dispose()
    }
}

function Inspect-Archive([string]$Path) {
    $item = Get-Item -LiteralPath $Path
    $base = [ordered]@{
        path = $item.FullName
        name = $item.Name
        bytes = $item.Length
        sha256 = $null
        status = 'rejected'
        packageRoot = $null
        packageKind = $null
        engine = $null
        shiori = $null
        rejection = $null
    }
    if ($item.Length -gt 544MB) {
        $base.rejection = 'archive-size-limit'
        return [pscustomobject]$base
    }
    $base.sha256 = Get-Sha256 $Path

    $zip = $null
    try {
        $preflight = Get-ZipCentralPreflight $item.FullName
        if ($preflight.entryCountOverLimit) { Fail 'entry-count-limit' }
        $zip = [IO.Compression.ZipFile]::OpenRead($item.FullName)
        if ($zip.Entries.Count -ne $preflight.entryCount) { Fail 'central-count-disagreement' }

        $entriesByKey = @{}
        $implicitDirectories = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $directorySpellings = @{}
        $items = @()
        [long]$totalLength = 0
        [long]$totalCompressed = 0
        foreach ($entry in $zip.Entries) {
            $directory = $entry.FullName.EndsWith('/') -or $entry.FullName.EndsWith('\')
            $normalized = Normalize-ArchivePath $entry.FullName $directory
            if ($entriesByKey.ContainsKey($normalized.key)) {
                $previous = $entriesByKey[$normalized.key]
                if ($previous.directory -ne $directory) { Fail "file-directory-collision:$($normalized.normalized)" }
                Fail "normalized-or-duplicate-entry:$($normalized.normalized)"
            }
            $ancestor = $normalized.key
            $slash = $ancestor.LastIndexOf('/')
            while ($slash -ge 0) {
                $ancestor = $ancestor.Substring(0, $slash)
                if ($entriesByKey.ContainsKey($ancestor) -and -not $entriesByKey[$ancestor].directory) {
                    Fail "file-directory-collision:$($normalized.normalized)"
                }
                [void]$implicitDirectories.Add($ancestor)
                $slash = $ancestor.LastIndexOf('/')
            }
            if (-not $directory -and $implicitDirectories.Contains($normalized.key)) {
                Fail "file-directory-collision:$($normalized.normalized)"
            }

            $rawComponents = @($normalized.original.Split('/'))
            $normalizedComponents = @($normalized.normalized.Split('/'))
            $directoryCount = if ($directory) { $rawComponents.Count } else { $rawComponents.Count - 1 }
            for ($index = 0; $index -lt $directoryCount; $index++) {
                $rawPrefix = ($rawComponents[0..$index] -join '/')
                $normalizedPrefix = ($normalizedComponents[0..$index] -join '/')
                $prefixKey = Get-CollisionKey $normalizedPrefix
                if ($directorySpellings.ContainsKey($prefixKey) -and $directorySpellings[$prefixKey] -cne $rawPrefix) {
                    Fail "normalized-directory-collision:$normalizedPrefix"
                }
                $directorySpellings[$prefixKey] = $rawPrefix
            }

            if ($entry.Length -gt 128MB) { Fail "declared-entry-size-limit:$($normalized.normalized)" }
            if ($entry.Length -gt (512MB - $totalLength)) { Fail "declared-total-size-limit:$($normalized.normalized)" }
            $totalLength += $entry.Length
            $totalCompressed += $entry.CompressedLength
            if ($entry.Length -gt 0 -and
                ($entry.CompressedLength -le 0 -or $entry.Length -gt ($entry.CompressedLength * 1000))) {
                Fail "declared-ratio-limit:$($normalized.normalized)"
            }
            $record = [pscustomobject]@{
                path = $normalized.normalized
                key = $normalized.key
                original = $normalized.original
                directory = $directory
                entry = $entry
            }
            $entriesByKey[$normalized.key] = $record
            $items += $record
        }
        if ($totalLength -gt 0 -and ($totalCompressed -le 0 -or $totalLength -gt ($totalCompressed * 1000))) {
            Fail 'declared-ratio-limit:archive-total'
        }

        $rootDescriptor = @($items | Where-Object { -not $_.directory -and $_.path -ceq 'install.txt' })
        $wrapperDescriptors = @($items | Where-Object {
            -not $_.directory -and $_.path.Split('/').Count -eq 2 -and $_.path.Split('/')[-1] -ceq 'install.txt'
        })
        $deepDescriptors = @($items | Where-Object {
            -not $_.directory -and $_.path.Split('/').Count -gt 2 -and $_.path.Split('/')[-1] -ceq 'install.txt'
        })
        $wrapper = $null
        $descriptor = $null
        if ($rootDescriptor.Count -eq 1) {
            $descriptor = $rootDescriptor[0]
        }
        elseif ($rootDescriptor.Count -gt 1) {
            Fail 'ambiguous-root-install-descriptor'
        }
        elseif ($wrapperDescriptors.Count -eq 1) {
            $descriptor = $wrapperDescriptors[0]
            $wrapper = $descriptor.path.Split('/')[0]
            foreach ($entry in $items) {
                if ($entry.path -cne $wrapper -and -not $entry.path.StartsWith("$wrapper/", [StringComparison]::Ordinal)) {
                    Fail "mixed-package-layout:$($entry.path)"
                }
            }
        }
        elseif ($wrapperDescriptors.Count -gt 1) {
            Fail 'ambiguous-wrapper-install-descriptor'
        }
        elseif ($deepDescriptors.Count -gt 0) {
            Fail 'deep-install-descriptor'
        }
        else {
            Fail 'missing-install-descriptor'
        }

        $relativeEntries = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
        foreach ($entry in $items) {
            $relative = if ($null -eq $wrapper) {
                $entry.path
            }
            elseif ($entry.path -ceq $wrapper) {
                $null
            }
            else {
                $entry.path.Substring($wrapper.Length + 1)
            }
            if ($null -ne $relative) { $relativeEntries[$relative] = $entry }
        }

        $installText = Decode-NarText (Read-ZipEntryBytes $descriptor.entry 65536)
        $installValues = Parse-Descriptor $installText -Strict
        $kind = if ($installValues.ContainsKey('type')) { Get-CollisionKey $installValues['type'] } else { $null }
        $base.packageRoot = if ($null -eq $wrapper) { '<archive-root>' } else { $wrapper }
        $base.packageKind = $kind
        if ($kind -ne 'ghost') { Fail "unsupported-package-kind:$kind" }
        if (-not $installValues.ContainsKey('name') -or [string]::IsNullOrEmpty($installValues['name']) -or
            -not $installValues.ContainsKey('directory') -or [string]::IsNullOrEmpty($installValues['directory'])) {
            Fail 'install descriptor is missing name or directory'
        }
        $installDirectory = $installValues['directory']
        if ($installDirectory.Length -gt 255 -or (Get-Utf8Length $installDirectory) -gt 255 -or
            $installDirectory -in @('.', '..') -or $installDirectory -match '[/\\:\x00-\x1f\x7f]' -or
            $installDirectory -ne $installDirectory.Trim()) {
            Fail 'install descriptor contains an unsafe directory identity'
        }
        if (-not $relativeEntries.ContainsKey('ghost/master/descript.txt')) {
            Fail 'missing-ghost-master-descriptor'
        }
        $ghostDescriptor = $relativeEntries['ghost/master/descript.txt']
        $ghostText = Decode-NarText (Read-ZipEntryBytes $ghostDescriptor.entry 65536)
        $ghostValues = Parse-Descriptor $ghostText
        $shiori = if ($ghostValues.ContainsKey('shiori')) { $ghostValues['shiori'] } else { $null }
        $base.shiori = $shiori
        if ($shiori -ceq 'satori.dll') {
            $base.engine = 'Satori'
        }
        elseif ($shiori -ceq 'yaya.dll') {
            $base.engine = 'YAYA'
        }
        elseif ($shiori -ceq 'shiori.dll' -and $relativeEntries.ContainsKey('ghost/master/kawarirc.kis')) {
            $base.engine = 'Kawari 8'
        }
        else {
            Fail "unsupported-engine:$shiori"
        }
        $base.status = 'candidate'
        return [pscustomobject]$base
    }
    catch {
        $base.rejection = $_.Exception.Message
        return [pscustomobject]$base
    }
    finally {
        if ($null -ne $zip) { $zip.Dispose() }
    }
}

function Read-CanonicalManifest([string]$Path) {
    $absolute = if ([IO.Path]::IsPathRooted($Path)) { $Path } else { Join-Path $repoRoot $Path }
    if (-not (Test-Path -LiteralPath $absolute -PathType Leaf)) { Fail "Corpus manifest not found: $absolute" }
    try { $manifest = Get-Content -LiteralPath $absolute -Raw | ConvertFrom-Json }
    catch { Fail "Corpus manifest is invalid JSON: $($_.Exception.Message)" }
    if ($manifest.schemaVersion -ne 1) { Fail "Unsupported corpus manifest schema: $($manifest.schemaVersion)" }
    $entries = @($manifest.entries)
    if ($entries.Count -ne 23) { Fail "Canonical corpus manifest must contain exactly 23 rows; found $($entries.Count)" }
    $hashes = @{}
    $labels = @{}
    foreach ($entry in $entries) {
        if ([string]::IsNullOrWhiteSpace($entry.label) -or $entry.sha256 -notmatch '^[0-9A-Fa-f]{64}$') {
            Fail 'Canonical corpus manifest contains an invalid label or SHA-256.'
        }
        $hash = $entry.sha256.ToLowerInvariant()
        $label = $entry.label.ToLowerInvariant()
        if ($hashes.ContainsKey($hash) -or $labels.ContainsKey($label)) { Fail 'Canonical corpus manifest contains duplicate identity.' }
        if ($entry.expectedKind -notin @('ghost', 'shell', 'balloon')) { Fail "Invalid expectedKind for $($entry.label)" }
        $hashes[$hash] = $entry
        $labels[$label] = $entry
    }
    return [pscustomobject]@{
        path = (Resolve-Path -LiteralPath $absolute).Path
        sha256 = Get-Sha256 $absolute
        entries = $entries
        byHash = $hashes
    }
}

function Invoke-ProcessResult {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList,
        [int]$TimeoutSeconds,
        [switch]$AllowFailure
    )
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $FilePath
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $ArgumentList) { [void]$start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) { Fail "Could not start $FilePath" }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        try { $process.Kill($true) } catch { }
        $process.WaitForExit()
        Fail "Process timed out after $TimeoutSeconds seconds: $FilePath"
    }
    $stdout = $stdoutTask.GetAwaiter().GetResult()
    $stderr = $stderrTask.GetAwaiter().GetResult()
    $result = [pscustomobject]@{
        exitCode = $process.ExitCode
        stdout = $stdout
        stderr = $stderr
        output = (($stdout, $stderr | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join "`n").Trim()
    }
    $process.Dispose()
    if (-not $AllowFailure -and $result.exitCode -ne 0) {
        Fail "Command failed ($($result.exitCode)): $FilePath $($ArgumentList -join ' ')`n$($result.output)"
    }
    return $result
}

function Resolve-AdbExecutable {
    if (-not [string]::IsNullOrWhiteSpace($AdbPath)) {
        $resolved = Resolve-Path -LiteralPath $AdbPath -ErrorAction Stop
        return $resolved.Path
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    $properties = Join-Path $repoRoot 'local.properties'
    if (Test-Path -LiteralPath $properties) {
        $line = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            $sdk = $line.Substring('sdk.dir='.Length).Replace('\:', ':').Replace('\\', '\')
            $candidate = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path -LiteralPath $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
        }
    }
    Fail 'adb was not found on PATH or under local.properties sdk.dir.'
}

function Invoke-Adb([string[]]$Arguments, [int]$TimeoutSeconds = 60, [switch]$AllowFailure) {
    return Invoke-ProcessResult -FilePath $script:adbExecutable -ArgumentList (@('-s', $DeviceSerial) + $Arguments) -TimeoutSeconds $TimeoutSeconds -AllowFailure:$AllowFailure
}

function Test-PackageInstalled([string]$PackageName) {
    $result = Invoke-Adb @('shell', 'pm', 'list', 'packages', $PackageName) 30
    return @($result.stdout -split "\r?\n") -contains "package:$PackageName"
}

function Assert-InstrumentationSuccess([object]$Result, [string]$Label) {
    if ($Result.exitCode -ne 0 -or $Result.output -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' -or
        $Result.output -notmatch 'OK \(1 test\)') {
        Fail "$Label instrumentation failed:`n$($Result.output)"
    }
}

function Get-PrivateArchiveCopySteps(
    [string]$HostPath,
    [string]$TemporaryPath,
    [string]$PrivateRelativePath
) {
    return @(
        [pscustomobject]@{
            arguments = [string[]]@('push', $HostPath, $TemporaryPath)
            timeoutSeconds = 180
        },
        [pscustomobject]@{
            arguments = [string[]]@('shell', 'chmod', '0644', $TemporaryPath)
            timeoutSeconds = 30
        },
        [pscustomobject]@{
            arguments = [string[]]@('shell', 'run-as', $targetPackage, 'cp', $TemporaryPath, $PrivateRelativePath)
            timeoutSeconds = 60
        }
    )
}

function Get-AvailabilityProblems(
    [int]$ResolvedRootCount,
    [int]$MissingRootCount,
    [int]$ArchiveCount,
    [int]$UnexpectedCount,
    [int]$MissingManifestCount,
    [int]$SelectedCount
) {
    foreach ($count in @(
        $ResolvedRootCount,
        $MissingRootCount,
        $ArchiveCount,
        $UnexpectedCount,
        $MissingManifestCount,
        $SelectedCount
    )) {
        if ($count -lt 0) { Fail 'Availability counts cannot be negative.' }
    }
    $problems = [Collections.Generic.List[string]]::new()
    if ($ResolvedRootCount -eq 0) { $problems.Add('No valid corpus root was resolved.') }
    if ($ArchiveCount -eq 0) { $problems.Add('no NAR files discovered') }
    if ($UnexpectedCount -gt 0) { $problems.Add("$UnexpectedCount discovered file(s) absent from the canonical manifest") }
    if ($MissingManifestCount -gt 0) { $problems.Add("$MissingManifestCount canonical manifest row(s) unavailable") }
    if ($SelectedCount -ne 3) { $problems.Add("canonical real-engine coverage is incomplete ($SelectedCount/3 engines)") }
    return @($problems)
}

function Get-DeviceCleanupPlan(
    [bool]$TargetAbsentBefore,
    [bool]$TargetInstallAttempted,
    [bool]$TestAbsentBefore,
    [bool]$TestInstallAttempted,
    [bool]$PrivateRunRootAttempted,
    [bool]$TemporaryRunRootAttempted,
    [bool]$ExternalRunRootAttempted,
    [bool]$InstrumentationAttempted
) {
    $targetMayBeRunOwned = $TargetAbsentBefore -and $TargetInstallAttempted
    $testMayBeRunOwned = $TestAbsentBefore -and $TestInstallAttempted
    $plan = [ordered]@{
        forceStopTarget = $targetMayBeRunOwned -and $InstrumentationAttempted
        forceStopTest = $testMayBeRunOwned -and $InstrumentationAttempted
        removePrivateRunRoot = $targetMayBeRunOwned -and $PrivateRunRootAttempted
        removeTemporaryRunRoot = $TemporaryRunRootAttempted
        removeExternalRunRoot = $targetMayBeRunOwned -and $ExternalRunRootAttempted
        probeUninstallTarget = $targetMayBeRunOwned
        probeUninstallTest = $testMayBeRunOwned
    }
    $plan.hasActions = @($plan.Values | Where-Object { $_ }).Count -gt 0
    return [pscustomobject]$plan
}

function Uninstall-RunOwnedPackageIfPresent([string]$PackageName) {
    if (-not (Test-PackageInstalled $PackageName)) { return }
    $uninstall = Invoke-Adb @('uninstall', $PackageName) 60 -AllowFailure
    if ($uninstall.exitCode -ne 0 -or $uninstall.output -notmatch 'Success') {
        Fail "$PackageName uninstall failed: $($uninstall.output)"
    }
    if (Test-PackageInstalled $PackageName) {
        Fail "$PackageName remains installed after run-owned uninstall."
    }
}

function Format-SelectedArchive([object]$Choice) {
    return "Selected $($Choice.engine): label=$($Choice.label); path=$($Choice.path); sha256=$($Choice.sha256)"
}

function Get-ArchiveManifestEntry([object]$Archive, [Collections.IDictionary]$ByHash) {
    $hash = [string]$Archive.sha256
    if ($hash -notmatch '^[0-9a-f]{64}$' -or -not $ByHash.Contains($hash)) { return $null }
    return $ByHash[$hash]
}

function Format-ArchiveSha256([object]$Archive) {
    if ([string]::IsNullOrWhiteSpace([string]$Archive.sha256)) { return '<not-computed>' }
    return [string]$Archive.sha256
}

function Write-AuditReport([hashtable]$State) {
    $summaryPath = Join-Path $reportRoot 'summary.json'
    ([pscustomobject]$State | ConvertTo-Json -Depth 16) | Set-Content -LiteralPath $summaryPath -Encoding utf8
    $lines = @(
        '# Cross-engine GhostRuntime audit',
        '',
        "- Status: $($State.status)",
        "- Run ID: $($State.runId)",
        "- Manifest: $($State.manifest.path) ($($State.manifest.sha256))",
        "- Discovered NAR files: $($State.inventory.discoveredFileCount)",
        "- Canonical manifest matches: $($State.inventory.manifestMatchedFileCount)",
        "- Unexpected files: $($State.inventory.unexpectedFileCount)",
        "- Missing canonical rows: $($State.inventory.missingManifestCount)",
        "- Availability: $($State.availabilityReason)",
        '',
        '## Corpus roots',
        ''
    )
    foreach ($root in $State.roots.resolved) { $lines += "- Resolved: $root" }
    foreach ($root in $State.roots.missing) { $lines += "- Missing: $($root.requested) -> $($root.absolutePath)" }
    $lines += @('', '## Discovered archives', '', '| SHA-256 | Engine | Manifest | Status | Path |', '| --- | --- | --- | --- | --- |')
    foreach ($archive in $State.archives) {
        $manifestLabel = if ($archive.manifestLabel) { $archive.manifestLabel } else { 'extra' }
        $engine = if ($archive.engine) { $archive.engine } else { '-' }
        $status = if ($archive.status -eq 'candidate') { 'candidate' } else { "rejected: $($archive.rejection)" }
        $path = $archive.path.Replace('|', '\|')
        $lines += "| $(Format-ArchiveSha256 $archive) | $engine | $manifestLabel | $status | $path |"
    }
    Set-Content -LiteralPath (Join-Path $reportRoot 'summary.md') -Value $lines -Encoding utf8
}

function Set-TestUInt16([byte[]]$Target, [int]$Offset, [long]$Value) {
    for ($index = 0; $index -lt 2; $index++) {
        $Target[$Offset + $index] = [byte](($Value -shr (8 * $index)) -band 0xff)
    }
}

function Set-TestUInt32([byte[]]$Target, [int]$Offset, [long]$Value) {
    for ($index = 0; $index -lt 4; $index++) {
        $Target[$Offset + $index] = [byte](($Value -shr (8 * $index)) -band 0xff)
    }
}

function Set-TestUInt64([byte[]]$Target, [int]$Offset, [long]$Value) {
    for ($index = 0; $index -lt 8; $index++) {
        $Target[$Offset + $index] = [byte](($Value -shr (8 * $index)) -band 0xff)
    }
}

function New-TestZip64CentralFixture([int]$CentralRecords, [long]$DeclaredRecords) {
    $centralSize = 46 * $CentralRecords
    $recordOffset = $centralSize
    $locatorOffset = $recordOffset + 56
    $eocdOffset = $locatorOffset + 20
    $archive = [byte[]]::new($eocdOffset + 22)
    for ($index = 0; $index -lt $CentralRecords; $index++) {
        Set-TestUInt32 $archive ($index * 46) 0x02014b50
    }
    Set-TestUInt32 $archive $recordOffset 0x06064b50
    Set-TestUInt64 $archive ($recordOffset + 4) 44
    Set-TestUInt64 $archive ($recordOffset + 24) $DeclaredRecords
    Set-TestUInt64 $archive ($recordOffset + 32) $DeclaredRecords
    Set-TestUInt64 $archive ($recordOffset + 40) $centralSize
    Set-TestUInt64 $archive ($recordOffset + 48) 0
    Set-TestUInt32 $archive $locatorOffset 0x07064b50
    Set-TestUInt32 $archive ($locatorOffset + 4) 0
    Set-TestUInt64 $archive ($locatorOffset + 8) $recordOffset
    Set-TestUInt32 $archive ($locatorOffset + 16) 1
    Set-TestUInt32 $archive $eocdOffset 0x06054b50
    Set-TestUInt16 $archive ($eocdOffset + 8) 0xffff
    Set-TestUInt16 $archive ($eocdOffset + 10) 0xffff
    Set-TestUInt32 $archive ($eocdOffset + 12) 0xffffffff
    Set-TestUInt32 $archive ($eocdOffset + 16) 0xffffffff
    return $archive
}

function Invoke-HostOnlySelfTest {
    $selfTestRoot = Join-Path $reportRoot ('.self-test-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $selfTestRoot | Out-Null
    try {
        $ordinaryPath = Join-Path $selfTestRoot 'ordinary.zip'
        $ordinaryStream = [IO.File]::Open($ordinaryPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        $ordinaryZip = [IO.Compression.ZipArchive]::new($ordinaryStream, [IO.Compression.ZipArchiveMode]::Create, $false)
        try {
            $entryStream = $ordinaryZip.CreateEntry('entry.txt').Open()
            try { $entryStream.WriteByte(1) }
            finally { $entryStream.Dispose() }
        }
        finally {
            $ordinaryZip.Dispose()
            $ordinaryStream.Dispose()
        }
        $ordinary = Get-ZipCentralPreflight $ordinaryPath
        if ($ordinary.entryCount -ne 1 -or $ordinary.entryCountOverLimit) {
            Fail 'Host-only ordinary ZIP preflight did not return its exact entry count.'
        }

        $zip64Path = Join-Path $selfTestRoot 'zip64.zip'
        [byte[]]$zip64Bytes = New-TestZip64CentralFixture 1 1
        [IO.File]::WriteAllBytes($zip64Path, $zip64Bytes)
        $zip64 = Get-ZipCentralPreflight $zip64Path
        if ($zip64.entryCount -ne 1 -or $zip64.entryCountOverLimit) {
            Fail 'Host-only ZIP64 preflight did not return its exact entry count.'
        }

        $excessivePath = Join-Path $selfTestRoot 'excessive.zip'
        [byte[]]$excessiveBytes = New-TestZip64CentralFixture 0 10001
        [IO.File]::WriteAllBytes($excessivePath, $excessiveBytes)
        $excessive = Get-ZipCentralPreflight $excessivePath
        if ($excessive.entryCount -ne 10001 -or -not $excessive.entryCountOverLimit) {
            Fail 'Host-only excessive-entry declaration was not rejected at the bounded preflight.'
        }

        $multiDiskPath = Join-Path $selfTestRoot 'multi-disk.zip'
        [byte[]]$multiDiskBytes = New-TestZip64CentralFixture 1 1
        Set-TestUInt32 $multiDiskBytes ($multiDiskBytes.Length - 38) 2
        [IO.File]::WriteAllBytes($multiDiskPath, $multiDiskBytes)
        $multiDiskRejected = $false
        try { Get-ZipCentralPreflight $multiDiskPath | Out-Null }
        catch { $multiDiskRejected = $_.Exception.Message -match 'multi-disk' }
        if (-not $multiDiskRejected) { Fail 'Host-only multi-disk ZIP64 fixture was accepted.' }

        $boundsPath = Join-Path $selfTestRoot 'bad-bounds.zip'
        [byte[]]$boundsBytes = New-TestZip64CentralFixture 1 1
        Set-TestUInt64 $boundsBytes 94 1
        [IO.File]::WriteAllBytes($boundsPath, $boundsBytes)
        $boundsRejected = $false
        try { Get-ZipCentralPreflight $boundsPath | Out-Null }
        catch { $boundsRejected = $_.Exception.Message -match 'ZIP bounds' }
        if (-not $boundsRejected) { Fail 'Host-only out-of-bounds ZIP64 central directory was accepted.' }

        $oversizedPath = Join-Path $selfTestRoot 'oversized.nar'
        $oversizedStream = [IO.File]::Open($oversizedPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $oversizedStream.SetLength(544MB + 1) }
        finally { $oversizedStream.Dispose() }
        $oversized = Inspect-Archive $oversizedPath
        if ($oversized.rejection -cne 'archive-size-limit' -or $null -ne $oversized.sha256) {
            Fail 'Host-only oversized archive was not rejected before SHA-256 selection.'
        }
        if ($null -ne (Get-ArchiveManifestEntry $oversized @{})) {
            Fail 'Host-only hashless rejection was matched to a canonical manifest row.'
        }
        if ((Format-ArchiveSha256 $oversized) -cne '<not-computed>') {
            Fail 'Host-only hashless rejection was not rendered explicitly in reports.'
        }

        $optionalMissing = @(Get-AvailabilityProblems `
            -ResolvedRootCount 1 `
            -MissingRootCount 2 `
            -ArchiveCount 23 `
            -UnexpectedCount 0 `
            -MissingManifestCount 0 `
            -SelectedCount 3)
        if ($optionalMissing.Count -ne 0) {
            Fail "Host-only availability oracle treated missing optional roots as fatal: $($optionalMissing -join '; ')"
        }
        $zeroRoot = @(Get-AvailabilityProblems 0 2 0 0 23 0)
        if ($zeroRoot.Count -lt 1 -or $zeroRoot[0] -cne 'No valid corpus root was resolved.') {
            Fail 'Host-only availability oracle did not preserve the zero-resolved-root failure.'
        }
        $strictInventory = @(Get-AvailabilityProblems 1 0 24 1 1 3)
        if ($strictInventory -notcontains '1 discovered file(s) absent from the canonical manifest' -or
            $strictInventory -notcontains '1 canonical manifest row(s) unavailable') {
            Fail 'Host-only availability oracle weakened complete/exclusive canonical manifest acceptance.'
        }

        $copySteps = @(Get-PrivateArchiveCopySteps `
            'C:\corpus\selected.nar' `
            '/data/local/tmp/nanidroid-cross-engine/run/selected.nar' `
            'cache/cross-engine-runtime/run/input/selected.nar')
        $expectedCopySteps = @(
            'push|C:\corpus\selected.nar|/data/local/tmp/nanidroid-cross-engine/run/selected.nar',
            'shell|chmod|0644|/data/local/tmp/nanidroid-cross-engine/run/selected.nar',
            'shell|run-as|com.cattailsw.nanidroid|cp|/data/local/tmp/nanidroid-cross-engine/run/selected.nar|cache/cross-engine-runtime/run/input/selected.nar'
        )
        $expectedCopyTimeouts = @(180, 30, 60)
        if ($copySteps.Count -ne $expectedCopySteps.Count) {
            Fail 'Host-only private-copy oracle returned the wrong command count.'
        }
        for ($index = 0; $index -lt $copySteps.Count; $index++) {
            if (($copySteps[$index].arguments -join '|') -cne $expectedCopySteps[$index]) {
                Fail "Host-only private-copy command $index was not exact or ordered: $($copySteps[$index].arguments -join '|')"
            }
            if ($copySteps[$index].timeoutSeconds -ne $expectedCopyTimeouts[$index]) {
                Fail "Host-only private-copy command $index lost its bounded timeout."
            }
        }

        $preexistingPlan = Get-DeviceCleanupPlan $false $true $false $true $true $false $true $true
        if ($preexistingPlan.hasActions) {
            Fail 'Host-only cleanup oracle would mutate packages after pre-existing-state rejection.'
        }
        $ambiguousInstallPlan = Get-DeviceCleanupPlan $true $true $true $false $false $false $false $false
        if (-not $ambiguousInstallPlan.probeUninstallTarget -or
            $ambiguousInstallPlan.forceStopTarget -or
            $ambiguousInstallPlan.removePrivateRunRoot) {
            Fail 'Host-only cleanup oracle did not isolate an ambiguous target install to a safe package probe.'
        }
        $ownedRunPlan = Get-DeviceCleanupPlan $true $true $true $true $true $true $true $true
        foreach ($property in @(
            'forceStopTarget',
            'forceStopTest',
            'removePrivateRunRoot',
            'removeTemporaryRunRoot',
            'removeExternalRunRoot',
            'probeUninstallTarget',
            'probeUninstallTest'
        )) {
            if (-not $ownedRunPlan.$property) {
                Fail "Host-only cleanup oracle omitted run-owned action '$property'."
            }
        }

        $selectedLine = Format-SelectedArchive ([pscustomobject]@{
            engine = 'Satori'
            label = 'canonical-satori'
            path = 'C:\corpus\satori.nar'
            sha256 = ('a' * 64)
        })
        if ($selectedLine -cne "Selected Satori: label=canonical-satori; path=C:\corpus\satori.nar; sha256=$(('a' * 64))") {
            Fail "Host-only selected-archive display was incomplete: $selectedLine"
        }
    }
    finally {
        $absoluteSelfTestRoot = [IO.Path]::GetFullPath($selfTestRoot)
        if (-not (Split-Path -Parent $absoluteSelfTestRoot).Equals(
            [IO.Path]::GetFullPath($reportRoot),
            [StringComparison]::OrdinalIgnoreCase
        )) {
            Fail "Refusing host-only self-test cleanup outside the exact report root: $absoluteSelfTestRoot"
        }
        if (Test-Path -LiteralPath $absoluteSelfTestRoot) {
            Remove-Item -LiteralPath $absoluteSelfTestRoot -Recurse -Force
        }
    }
    Write-Host 'Host-only self-test passed: ZIP/ZIP64 bounds, oversized-before-hash, optional roots, chmod copy, owned cleanup, selected identity.'
}

if ($HostOnlySelfTest) {
    Invoke-HostOnlySelfTest
    return
}

$state = [ordered]@{
    schemaVersion = 1
    runId = $runId
    status = 'starting'
    availabilityReason = $null
    roots = [ordered]@{ requested = @($CorpusRoots); resolved = @(); missing = @() }
    manifest = [ordered]@{ path = $ManifestPath; sha256 = $null; rowCount = 0 }
    inventory = [ordered]@{
        discoveredFileCount = 0
        uniqueHashCount = 0
        manifestMatchedFileCount = 0
        manifestMatchedUniqueCount = 0
        unexpectedFileCount = 0
        rejectedFileCount = 0
        missingManifestCount = 0
        missingManifest = @()
    }
    engineCoverage = [ordered]@{
        'Satori' = [ordered]@{ discovered = 0; canonical = 0; paths = @() }
        'YAYA' = [ordered]@{ discovered = 0; canonical = 0; paths = @() }
        'Kawari 8' = [ordered]@{ discovered = 0; canonical = 0; paths = @() }
    }
    archives = @()
    selected = @()
    device = [ordered]@{
        status = if ($DryRun) { 'not-inspected-dry-run' } else { 'pending' }
        serial = $DeviceSerial
        api = $null
        abi = $null
        ownership = [ordered]@{
            target = [ordered]@{ absenceChecked = $false; absentBefore = $null; installAttempted = $false; installConfirmed = $false }
            test = [ordered]@{ absenceChecked = $false; absentBefore = $null; installAttempted = $false; installConfirmed = $false }
            privateRunRootAttempted = $false
            temporaryRunRootAttempted = $false
            externalRunRootAttempted = $false
            instrumentationAttempted = $false
        }
    }
    reports = [ordered]@{ lifecycle = $null; transition = $null }
    cleanup = [ordered]@{ attempted = $false; verified = $false; plan = $null; errors = @() }
    error = $null
}

$deviceTrusted = $false
$privateDataRoot = $null
$targetAbsenceChecked = $false
$targetAbsentBefore = $false
$targetInstallAttempted = $false
$targetInstallConfirmed = $false
$testAbsenceChecked = $false
$testAbsentBefore = $false
$testInstallAttempted = $false
$testInstallConfirmed = $false
$privateRunRootAttempted = $false
$temporaryRunRootAttempted = $false
$externalRunRootAttempted = $false
$instrumentationAttempted = $false
$terminalFailure = $null
$cleanupFailureMessage = $null

try {
    $rootSet = Resolve-CorpusRootSet $CorpusRoots
    $state.roots = [ordered]@{
        requested = @($rootSet.requested)
        resolved = @($rootSet.resolved)
        missing = @($rootSet.missing)
    }
    if ($rootSet.resolved.Count -eq 0) {
        $rootProblems = @(Get-AvailabilityProblems $rootSet.resolved.Count $rootSet.missing.Count 0 0 23 0)
        Fail $rootProblems[0]
    }
    $manifest = Read-CanonicalManifest $ManifestPath
    $state.manifest = [ordered]@{ path = $manifest.path; sha256 = $manifest.sha256; rowCount = $manifest.entries.Count }

    $archivePaths = @(Collect-Archives $rootSet.resolved)
    $archives = @()
    foreach ($path in $archivePaths) {
        $archive = Inspect-Archive $path
        $manifestEntry = Get-ArchiveManifestEntry $archive $manifest.byHash
        $archive | Add-Member -NotePropertyName manifestLabel -NotePropertyValue $(if ($manifestEntry) { $manifestEntry.label } else { $null })
        $archive | Add-Member -NotePropertyName manifestExpectedKind -NotePropertyValue $(if ($manifestEntry) { $manifestEntry.expectedKind } else { $null })
        $archives += $archive
    }
    $archives = @($archives | Sort-Object sha256, path)
    $state.archives = $archives

    $matchedHashes = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($archive in $archives) {
        if ($null -ne (Get-ArchiveManifestEntry $archive $manifest.byHash)) { [void]$matchedHashes.Add($archive.sha256) }
    }
    $missingManifest = @($manifest.entries | Where-Object { -not $matchedHashes.Contains($_.sha256) } | ForEach-Object {
        [pscustomobject]@{ label = $_.label; sha256 = $_.sha256.ToLowerInvariant() }
    })
    $unexpected = @($archives | Where-Object { $null -eq (Get-ArchiveManifestEntry $_ $manifest.byHash) })
    $rejected = @($archives | Where-Object { $_.status -ne 'candidate' })
    $state.inventory = [ordered]@{
        discoveredFileCount = $archives.Count
        uniqueHashCount = @($archives | ForEach-Object { $_.sha256 } | Sort-Object -Unique).Count
        manifestMatchedFileCount = @($archives | Where-Object { $null -ne (Get-ArchiveManifestEntry $_ $manifest.byHash) }).Count
        manifestMatchedUniqueCount = $matchedHashes.Count
        unexpectedFileCount = $unexpected.Count
        rejectedFileCount = $rejected.Count
        missingManifestCount = $missingManifest.Count
        missingManifest = $missingManifest
    }

    $coverage = [ordered]@{}
    $selected = @()
    foreach ($engine in @('Satori', 'YAYA', 'Kawari 8')) {
        $discoveredCandidates = @($archives | Where-Object { $_.status -eq 'candidate' -and $_.engine -eq $engine })
        $canonicalCandidates = @($discoveredCandidates | Where-Object { $null -ne (Get-ArchiveManifestEntry $_ $manifest.byHash) })
        $coverage[$engine] = [ordered]@{
            discovered = $discoveredCandidates.Count
            canonical = $canonicalCandidates.Count
            paths = @($discoveredCandidates | ForEach-Object { $_.path })
        }
        $choice = $canonicalCandidates | Sort-Object sha256, path | Select-Object -First 1
        if ($choice) {
            $selected += [pscustomobject]@{
                engine = $engine
                label = $choice.manifestLabel
                path = $choice.path
                sha256 = $choice.sha256
            }
        }
    }
    $state.engineCoverage = $coverage
    $state.selected = $selected

    $availabilityProblems = @(Get-AvailabilityProblems `
        $rootSet.resolved.Count `
        $rootSet.missing.Count `
        $archives.Count `
        $unexpected.Count `
        $missingManifest.Count `
        $selected.Count)
    $state.availabilityReason = if ($availabilityProblems.Count -eq 0) { 'ready' } else { $availabilityProblems -join '; ' }

    Write-Host "Cross-engine corpus roots: $($rootSet.resolved.Count) resolved, $($rootSet.missing.Count) missing"
    foreach ($root in $rootSet.resolved) { Write-Host "  root: $root" }
    Write-Host "Discovered: $($archives.Count); canonical matches: $($state.inventory.manifestMatchedFileCount); extras: $($unexpected.Count); missing rows: $($missingManifest.Count)"
    foreach ($engine in @('Satori', 'YAYA', 'Kawari 8')) {
        Write-Host "  $engine candidates: discovered=$($coverage[$engine].discovered), canonical=$($coverage[$engine].canonical)"
    }
    foreach ($choice in $selected) { Write-Host "  $(Format-SelectedArchive $choice)" }

    if ($DryRun) {
        $state.status = if ($availabilityProblems.Count -eq 0) { 'ready' } else { 'unavailable' }
    }
    else {
        if ($availabilityProblems.Count -gt 0) {
            $state.status = 'unavailable'
            Fail "Cross-engine corpus unavailable: $($state.availabilityReason)"
        }

        $script:adbExecutable = Resolve-AdbExecutable
        $stateResult = Invoke-Adb @('get-state') 30 -AllowFailure
        if ($stateResult.exitCode -ne 0 -or $stateResult.stdout.Trim() -ne 'device') {
            $state.device.status = 'unavailable'
            $state.status = 'unavailable'
            $state.availabilityReason = "Connected Android target '$DeviceSerial' is unavailable: $($stateResult.output)"
            Fail $state.availabilityReason
        }
        $deviceTrusted = $true
        $apiText = (Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk') 30).stdout.Trim()
        $abi = (Invoke-Adb @('shell', 'getprop', 'ro.product.cpu.abi') 30).stdout.Trim()
        [int]$api = 0
        if (-not [int]::TryParse($apiText, [ref]$api) -or $api -lt 31 -or $api -gt 37) {
            Fail "Device API '$apiText' is outside the supported 31-37 range."
        }
        if ($abi -notin @('x86_64', 'arm64-v8a')) {
            Fail "Device ABI '$abi' is not x86_64 or arm64-v8a."
        }
        $state.device.api = $api
        $state.device.abi = $abi
        $state.device.status = 'validated'

        $targetAbsentBefore = -not (Test-PackageInstalled $targetPackage)
        $targetAbsenceChecked = $true
        $testAbsentBefore = -not (Test-PackageInstalled $testPackage)
        $testAbsenceChecked = $true
        if (-not $targetAbsentBefore) { Fail "$targetPackage is already installed; refusing to overwrite non-run-owned app data." }
        if (-not $testAbsentBefore) { Fail "$testPackage is already installed; refusing to overwrite a non-run-owned test package." }

        $cmd = (Get-Command cmd.exe -ErrorAction Stop).Source
        $gradle = Join-Path $repoRoot 'gradlew.bat'
        $build = Invoke-ProcessResult $cmd @('/d', '/c', $gradle, 'assembleDebug', 'assembleDebugAndroidTest', '--no-daemon') ($BuildTimeoutMinutes * 60)
        if (-not (Test-Path -LiteralPath $debugApk) -or -not (Test-Path -LiteralPath $testApk)) {
            Fail 'Gradle completed without both expected debug APKs.'
        }
        $targetInstallAttempted = $true
        Invoke-Adb @('install', '-r', $debugApk) 180 | Out-Null
        $targetInstallConfirmed = $true
        $testInstallAttempted = $true
        Invoke-Adb @('install', '-r', '-t', $testApk) 180 | Out-Null
        $testInstallConfirmed = $true

        $privateDataRoot = (Invoke-Adb @('shell', 'run-as', $targetPackage, 'pwd') 30).stdout.Trim()
        if ($privateDataRoot -notmatch "^/data/(user/0|data)/$([regex]::Escape($targetPackage))$") {
            Fail "Unexpected run-as private root '$privateDataRoot'."
        }
        $temporaryRunRootAttempted = $true
        Invoke-Adb @('shell', 'mkdir', '-p', $tmpRunRoot) 30 | Out-Null
        $privateRunRootAttempted = $true
        Invoke-Adb @('shell', 'run-as', $targetPackage, 'mkdir', '-p', "$privateRelativeRunRoot/input") 30 | Out-Null

        $byEngine = @{}
        foreach ($choice in $selected) { $byEngine[$choice.engine] = $choice }
        $copies = @(
            [pscustomobject]@{ engine = 'Satori'; name = 'satori.nar'; choice = $byEngine['Satori'] },
            [pscustomobject]@{ engine = 'YAYA'; name = 'yaya.nar'; choice = $byEngine['YAYA'] },
            [pscustomobject]@{ engine = 'Kawari 8'; name = 'kawari.nar'; choice = $byEngine['Kawari 8'] },
            [pscustomobject]@{ engine = 'Satori'; name = 'satori-reload.nar'; choice = $byEngine['Satori'] }
        )
        $arguments = [ordered]@{ runtimeAuditRunId = $runId }
        foreach ($copy in $copies) {
            $tmpPath = "$tmpRunRoot/$($copy.name)"
            $privateRelative = "$privateRelativeRunRoot/input/$($copy.name)"
            foreach ($step in @(Get-PrivateArchiveCopySteps $copy.choice.path $tmpPath $privateRelative)) {
                Invoke-Adb -Arguments $step.arguments -TimeoutSeconds $step.timeoutSeconds | Out-Null
            }
            $privateAbsolute = "$privateDataRoot/$privateRelative"
            switch ($copy.name) {
                'satori.nar' { $arguments.satoriNarPath = $privateAbsolute; $arguments.satoriNarSha256 = $copy.choice.sha256 }
                'yaya.nar' { $arguments.yayaNarPath = $privateAbsolute; $arguments.yayaNarSha256 = $copy.choice.sha256 }
                'kawari.nar' { $arguments.kawariNarPath = $privateAbsolute; $arguments.kawariNarSha256 = $copy.choice.sha256 }
                'satori-reload.nar' { $arguments.satoriReloadNarPath = $privateAbsolute; $arguments.satoriReloadNarSha256 = $copy.choice.sha256 }
            }
        }

        $commonArgs = @()
        foreach ($entry in $arguments.GetEnumerator()) { $commonArgs += @('-e', $entry.Key, [string]$entry.Value) }
        $lifecycleClass = 'com.cattailsw.nanidroid.ShioriLifecycleInstrumentationTest#realEnginesHaveSingleOwnerAndQueueConfinedLifecycle'
        $externalRunRootAttempted = $true
        $instrumentationAttempted = $true
        $lifecycleRun = Invoke-Adb (@('shell', 'am', 'instrument', '-w', '-r') + $commonArgs + @('-e', 'class', $lifecycleClass, $instrumentationRunner)) ($InstrumentationTimeoutMinutes * 60) -AllowFailure
        Assert-InstrumentationSuccess $lifecycleRun 'Lifecycle'

        $transitionClass = 'com.cattailsw.nanidroid.CrossEngineRuntimeInstrumentationTest#satoriYayaKawariSatoriUsesOneRuntimeAuthority'
        $transitionRun = Invoke-Adb (@('shell', 'am', 'instrument', '-w', '-r') + $commonArgs + @('-e', 'class', $transitionClass, $instrumentationRunner)) ($InstrumentationTimeoutMinutes * 60) -AllowFailure
        Assert-InstrumentationSuccess $transitionRun 'Cross-engine transition'

        $localLifecycle = Join-Path $reportRoot 'lifecycle-trace.json'
        $localTransition = Join-Path $reportRoot 'transition-trace.json'
        Invoke-Adb @('pull', "$externalRunRoot/lifecycle-trace.json", $localLifecycle) 60 | Out-Null
        Invoke-Adb @('pull', "$externalRunRoot/transition-trace.json", $localTransition) 60 | Out-Null
        $lifecycleReport = Get-Content -LiteralPath $localLifecycle -Raw | ConvertFrom-Json
        $transitionReport = Get-Content -LiteralPath $localTransition -Raw | ConvertFrom-Json
        if ($lifecycleReport.status -ne 'passed' -or @($lifecycleReport.engineCases).Count -ne 3) {
            Fail 'Lifecycle trace report did not prove all three engines.'
        }
        $expectedTrace = @(
            'load:Satori', 'request:Satori', 'unload:Satori',
            'load:YAYA', 'request:YAYA', 'unload:YAYA',
            'load:Kawari 8', 'request:Kawari 8', 'unload:Kawari 8',
            'load:Satori', 'request:Satori', 'unload:Satori'
        )
        if ($transitionReport.status -ne 'passed' -or (@($transitionReport.trace) -join "`n") -cne ($expectedTrace -join "`n")) {
            Fail 'Transition trace report did not contain the exact required sequence.'
        }
        $state.reports.lifecycle = $localLifecycle
        $state.reports.transition = $localTransition
        $state.status = 'passed'
        $state.availabilityReason = "executed on API $api $abi"
    }
}
catch {
    $terminalFailure = $_
    if ($state.status -eq 'starting') { $state.status = 'failed' }
    if ([string]::IsNullOrWhiteSpace($state.availabilityReason)) { $state.availabilityReason = $_.Exception.Message }
    $state.error = $_.Exception.ToString()
}
finally {
    $state.device.ownership = [ordered]@{
        target = [ordered]@{
            absenceChecked = $targetAbsenceChecked
            absentBefore = if ($targetAbsenceChecked) { $targetAbsentBefore } else { $null }
            installAttempted = $targetInstallAttempted
            installConfirmed = $targetInstallConfirmed
        }
        test = [ordered]@{
            absenceChecked = $testAbsenceChecked
            absentBefore = if ($testAbsenceChecked) { $testAbsentBefore } else { $null }
            installAttempted = $testInstallAttempted
            installConfirmed = $testInstallConfirmed
        }
        privateRunRootAttempted = $privateRunRootAttempted
        temporaryRunRootAttempted = $temporaryRunRootAttempted
        externalRunRootAttempted = $externalRunRootAttempted
        instrumentationAttempted = $instrumentationAttempted
    }
    $cleanupPlan = Get-DeviceCleanupPlan `
        $targetAbsentBefore `
        $targetInstallAttempted `
        $testAbsentBefore `
        $testInstallAttempted `
        $privateRunRootAttempted `
        $temporaryRunRootAttempted `
        $externalRunRootAttempted `
        $instrumentationAttempted
    $state.cleanup.plan = $cleanupPlan
    $state.cleanup.attempted = $cleanupPlan.hasActions

    if ($cleanupPlan.hasActions -and -not $deviceTrusted) {
        $state.cleanup.errors += 'Run-owned cleanup was required before device transport became trusted.'
    }
    elseif ($cleanupPlan.hasActions) {
        if ($cleanupPlan.forceStopTest) {
            try {
                $forceStopTest = Invoke-Adb @('shell', 'am', 'force-stop', $testPackage) 30 -AllowFailure
                if ($forceStopTest.exitCode -ne 0) { $state.cleanup.errors += "test package force-stop failed: $($forceStopTest.output)" }
            }
            catch { $state.cleanup.errors += $_.Exception.Message }
        }
        if ($cleanupPlan.forceStopTarget) {
            try {
                $forceStopTarget = Invoke-Adb @('shell', 'am', 'force-stop', $targetPackage) 30 -AllowFailure
                if ($forceStopTarget.exitCode -ne 0) { $state.cleanup.errors += "target package force-stop failed: $($forceStopTarget.output)" }
            }
            catch { $state.cleanup.errors += $_.Exception.Message }
        }
        if ($cleanupPlan.removePrivateRunRoot) {
            if ($null -eq $privateDataRoot) {
                $state.cleanup.errors += 'Private run root was attempted without a verified run-as data root.'
            }
            else {
            try {
                $removePrivate = Invoke-Adb @('shell', 'run-as', $targetPackage, 'rm', '-rf', $privateRelativeRunRoot) 30 -AllowFailure
                if ($removePrivate.exitCode -ne 0) { $state.cleanup.errors += "private cleanup failed: $($removePrivate.output)" }
                $privateProbe = Invoke-Adb @('shell', 'run-as', $targetPackage, 'ls', '-d', $privateRelativeRunRoot) 30 -AllowFailure
                if ($privateProbe.exitCode -eq 0 -or $privateProbe.output -notmatch 'No such file|No such file or directory') {
                    $state.cleanup.errors += "private cleanup residue or unverifiable absence: $($privateProbe.output)"
                }
            }
            catch { $state.cleanup.errors += $_.Exception.Message }
            }
        }
        if ($cleanupPlan.removeExternalRunRoot) {
            try {
                $removeExternal = Invoke-Adb @('shell', 'rm', '-rf', $externalRunRoot) 30 -AllowFailure
                if ($removeExternal.exitCode -ne 0) { $state.cleanup.errors += "external cleanup failed: $($removeExternal.output)" }
                $externalProbe = Invoke-Adb @('shell', 'ls', '-d', $externalRunRoot) 30 -AllowFailure
                if ($externalProbe.exitCode -eq 0 -or $externalProbe.output -notmatch 'No such file|No such file or directory') {
                    $state.cleanup.errors += "external cleanup residue or unverifiable absence: $($externalProbe.output)"
                }
            }
            catch { $state.cleanup.errors += $_.Exception.Message }
        }
        if ($cleanupPlan.removeTemporaryRunRoot) {
            try {
                $removeTmp = Invoke-Adb @('shell', 'rm', '-rf', $tmpRunRoot) 30 -AllowFailure
                if ($removeTmp.exitCode -ne 0) { $state.cleanup.errors += "temporary cleanup failed: $($removeTmp.output)" }
                $tmpProbe = Invoke-Adb @('shell', 'ls', '-d', $tmpRunRoot) 30 -AllowFailure
                if ($tmpProbe.exitCode -eq 0 -or $tmpProbe.output -notmatch 'No such file|No such file or directory') {
                    $state.cleanup.errors += "temporary cleanup residue or unverifiable absence: $($tmpProbe.output)"
                }
            }
            catch { $state.cleanup.errors += $_.Exception.Message }
        }
        if ($cleanupPlan.probeUninstallTest) {
            try { Uninstall-RunOwnedPackageIfPresent $testPackage }
            catch { $state.cleanup.errors += $_.Exception.Message }
        }
        if ($cleanupPlan.probeUninstallTarget) {
            try { Uninstall-RunOwnedPackageIfPresent $targetPackage }
            catch { $state.cleanup.errors += $_.Exception.Message }
        }
    }
    $state.cleanup.verified = $state.cleanup.errors.Count -eq 0
    if (-not $state.cleanup.verified) {
        $cleanupFailureMessage = "Run-owned device cleanup was not verified: $($state.cleanup.errors -join '; ')"
        $state.status = 'failed'
        if ($null -eq $state.error) { $state.error = $cleanupFailureMessage }
    }
    Write-AuditReport $state
}

Write-Host "Cross-engine audit status: $($state.status)"
Write-Host "Report: $(Join-Path $reportRoot 'summary.json')"
if ($null -ne $terminalFailure) { throw $terminalFailure }
if ($null -ne $cleanupFailureMessage) { throw [InvalidOperationException]::new($cleanupFailureMessage) }
