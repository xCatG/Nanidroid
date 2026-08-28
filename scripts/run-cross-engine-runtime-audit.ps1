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
    $DryRun
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

function Inspect-Archive([string]$Path) {
    $item = Get-Item -LiteralPath $Path
    $sha256 = Get-Sha256 $Path
    $base = [ordered]@{
        path = $item.FullName
        name = $item.Name
        bytes = $item.Length
        sha256 = $sha256
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

    $zip = $null
    try {
        $zip = [IO.Compression.ZipFile]::OpenRead($item.FullName)
        $central = @($zip.Entries)
        if ($central.Count -gt 10000) { Fail 'entry-count-limit' }

        $entriesByKey = @{}
        $implicitDirectories = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $directorySpellings = @{}
        $items = @()
        [long]$totalLength = 0
        [long]$totalCompressed = 0
        foreach ($entry in $central) {
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
        $lines += "| $($archive.sha256) | $engine | $manifestLabel | $status | $path |"
    }
    Set-Content -LiteralPath (Join-Path $reportRoot 'summary.md') -Value $lines -Encoding utf8
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
    device = [ordered]@{ status = if ($DryRun) { 'not-inspected-dry-run' } else { 'pending' }; serial = $DeviceSerial; api = $null; abi = $null }
    reports = [ordered]@{ lifecycle = $null; transition = $null }
    cleanup = [ordered]@{ attempted = $false; verified = $false; errors = @() }
    error = $null
}

$deviceTrusted = $false
$privateDataRoot = $null
$targetInstalledByRun = $false
$testInstalledByRun = $false
$terminalFailure = $null
$cleanupFailureMessage = $null

try {
    $rootSet = Resolve-CorpusRootSet $CorpusRoots
    $state.roots = [ordered]@{
        requested = @($rootSet.requested)
        resolved = @($rootSet.resolved)
        missing = @($rootSet.missing)
    }
    if ($rootSet.resolved.Count -eq 0) { Fail 'No valid corpus root was resolved.' }
    $manifest = Read-CanonicalManifest $ManifestPath
    $state.manifest = [ordered]@{ path = $manifest.path; sha256 = $manifest.sha256; rowCount = $manifest.entries.Count }

    $archivePaths = @(Collect-Archives $rootSet.resolved)
    $archives = @()
    foreach ($path in $archivePaths) {
        $archive = Inspect-Archive $path
        $manifestEntry = if ($manifest.byHash.ContainsKey($archive.sha256)) { $manifest.byHash[$archive.sha256] } else { $null }
        $archive | Add-Member -NotePropertyName manifestLabel -NotePropertyValue $(if ($manifestEntry) { $manifestEntry.label } else { $null })
        $archive | Add-Member -NotePropertyName manifestExpectedKind -NotePropertyValue $(if ($manifestEntry) { $manifestEntry.expectedKind } else { $null })
        $archives += $archive
    }
    $archives = @($archives | Sort-Object sha256, path)
    $state.archives = $archives

    $matchedHashes = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($archive in $archives) {
        if ($manifest.byHash.ContainsKey($archive.sha256)) { [void]$matchedHashes.Add($archive.sha256) }
    }
    $missingManifest = @($manifest.entries | Where-Object { -not $matchedHashes.Contains($_.sha256) } | ForEach-Object {
        [pscustomobject]@{ label = $_.label; sha256 = $_.sha256.ToLowerInvariant() }
    })
    $unexpected = @($archives | Where-Object { -not $manifest.byHash.ContainsKey($_.sha256) })
    $rejected = @($archives | Where-Object { $_.status -ne 'candidate' })
    $state.inventory = [ordered]@{
        discoveredFileCount = $archives.Count
        uniqueHashCount = @($archives | ForEach-Object { $_.sha256 } | Sort-Object -Unique).Count
        manifestMatchedFileCount = @($archives | Where-Object { $manifest.byHash.ContainsKey($_.sha256) }).Count
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
        $canonicalCandidates = @($discoveredCandidates | Where-Object { $manifest.byHash.ContainsKey($_.sha256) })
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

    $availabilityProblems = @()
    if ($rootSet.missing.Count -gt 0) { $availabilityProblems += "$($rootSet.missing.Count) requested corpus root(s) missing" }
    if ($archives.Count -eq 0) { $availabilityProblems += 'no NAR files discovered' }
    if ($unexpected.Count -gt 0) { $availabilityProblems += "$($unexpected.Count) discovered file(s) absent from the canonical manifest" }
    if ($missingManifest.Count -gt 0) { $availabilityProblems += "$($missingManifest.Count) canonical manifest row(s) unavailable" }
    if ($selected.Count -ne 3) { $availabilityProblems += "canonical real-engine coverage is incomplete ($($selected.Count)/3 engines)" }
    $state.availabilityReason = if ($availabilityProblems.Count -eq 0) { 'ready' } else { $availabilityProblems -join '; ' }

    Write-Host "Cross-engine corpus roots: $($rootSet.resolved.Count) resolved, $($rootSet.missing.Count) missing"
    foreach ($root in $rootSet.resolved) { Write-Host "  root: $root" }
    Write-Host "Discovered: $($archives.Count); canonical matches: $($state.inventory.manifestMatchedFileCount); extras: $($unexpected.Count); missing rows: $($missingManifest.Count)"
    foreach ($engine in @('Satori', 'YAYA', 'Kawari 8')) {
        Write-Host "  $engine candidates: discovered=$($coverage[$engine].discovered), canonical=$($coverage[$engine].canonical)"
    }

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

        if (Test-PackageInstalled $targetPackage) { Fail "$targetPackage is already installed; refusing to overwrite non-run-owned app data." }
        if (Test-PackageInstalled $testPackage) { Fail "$testPackage is already installed; refusing to overwrite a non-run-owned test package." }

        $cmd = (Get-Command cmd.exe -ErrorAction Stop).Source
        $gradle = Join-Path $repoRoot 'gradlew.bat'
        $build = Invoke-ProcessResult $cmd @('/d', '/c', $gradle, 'assembleDebug', 'assembleDebugAndroidTest', '--no-daemon') ($BuildTimeoutMinutes * 60)
        if (-not (Test-Path -LiteralPath $debugApk) -or -not (Test-Path -LiteralPath $testApk)) {
            Fail 'Gradle completed without both expected debug APKs.'
        }
        Invoke-Adb @('install', '-r', $debugApk) 180 | Out-Null
        $targetInstalledByRun = $true
        Invoke-Adb @('install', '-r', '-t', $testApk) 180 | Out-Null
        $testInstalledByRun = $true

        $privateDataRoot = (Invoke-Adb @('shell', 'run-as', $targetPackage, 'pwd') 30).stdout.Trim()
        if ($privateDataRoot -notmatch "^/data/(user/0|data)/$([regex]::Escape($targetPackage))$") {
            Fail "Unexpected run-as private root '$privateDataRoot'."
        }
        Invoke-Adb @('shell', 'mkdir', '-p', $tmpRunRoot) 30 | Out-Null
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
            Invoke-Adb @('push', $copy.choice.path, $tmpPath) 180 | Out-Null
            Invoke-Adb @('shell', 'run-as', $targetPackage, 'cp', $tmpPath, $privateRelative) 60 | Out-Null
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
    if ($deviceTrusted) {
        $state.cleanup.attempted = $true
        try { Invoke-Adb @('shell', 'am', 'force-stop', $targetPackage) 30 -AllowFailure | Out-Null } catch { $state.cleanup.errors += $_.Exception.Message }
        if ($null -ne $privateDataRoot) {
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
        try {
            $removeExternal = Invoke-Adb @('shell', 'rm', '-rf', $externalRunRoot) 30 -AllowFailure
            if ($removeExternal.exitCode -ne 0) { $state.cleanup.errors += "external cleanup failed: $($removeExternal.output)" }
            $externalProbe = Invoke-Adb @('shell', 'ls', '-d', $externalRunRoot) 30 -AllowFailure
            if ($externalProbe.exitCode -eq 0 -or $externalProbe.output -notmatch 'No such file|No such file or directory') {
                $state.cleanup.errors += "external cleanup residue or unverifiable absence: $($externalProbe.output)"
            }
        }
        catch { $state.cleanup.errors += $_.Exception.Message }
        try {
            $removeTmp = Invoke-Adb @('shell', 'rm', '-rf', $tmpRunRoot) 30 -AllowFailure
            if ($removeTmp.exitCode -ne 0) { $state.cleanup.errors += "temporary cleanup failed: $($removeTmp.output)" }
            $tmpProbe = Invoke-Adb @('shell', 'ls', '-d', $tmpRunRoot) 30 -AllowFailure
            if ($tmpProbe.exitCode -eq 0 -or $tmpProbe.output -notmatch 'No such file|No such file or directory') {
                $state.cleanup.errors += "temporary cleanup residue or unverifiable absence: $($tmpProbe.output)"
            }
        }
        catch { $state.cleanup.errors += $_.Exception.Message }
        if ($testInstalledByRun) {
            try {
                $uninstallTest = Invoke-Adb @('uninstall', $testPackage) 60 -AllowFailure
                if ($uninstallTest.exitCode -ne 0 -or $uninstallTest.output -notmatch 'Success') {
                    $state.cleanup.errors += "test package uninstall failed: $($uninstallTest.output)"
                }
            }
            catch { $state.cleanup.errors += $_.Exception.Message }
        }
        if ($targetInstalledByRun) {
            try {
                $uninstallTarget = Invoke-Adb @('uninstall', $targetPackage) 60 -AllowFailure
                if ($uninstallTarget.exitCode -ne 0 -or $uninstallTarget.output -notmatch 'Success') {
                    $state.cleanup.errors += "target package uninstall failed: $($uninstallTarget.output)"
                }
            }
            catch { $state.cleanup.errors += $_.Exception.Message }
        }
        $state.cleanup.verified = $state.cleanup.errors.Count -eq 0
        if (-not $state.cleanup.verified) {
            $cleanupFailureMessage = "Run-owned device cleanup was not verified: $($state.cleanup.errors -join '; ')"
            $state.status = 'failed'
            if ($null -eq $state.error) { $state.error = $cleanupFailureMessage }
        }
    }
    Write-AuditReport $state
}

Write-Host "Cross-engine audit status: $($state.status)"
Write-Host "Report: $(Join-Path $reportRoot 'summary.json')"
if ($null -ne $terminalFailure) { throw $terminalFailure }
if ($null -ne $cleanupFailureMessage) { throw [InvalidOperationException]::new($cleanupFailureMessage) }
