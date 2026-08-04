param(
    [CmdletBinding()]
    [string]
    $DeviceSerial,

    [string[]]
    $CorpusRoots = @('.', 'build/ui-audit'),

    [string]
    $ManifestPath = 'docs/testing/nar-corpus-manifest.json',

    [int]
    $PerArchiveTimeoutMinutes = 5,

    [int]
    $BuildTimeoutMinutes = 45,

    [long]
    $MinimumFreeBytes = 3GB,

    [string]
    $AdbPath,

    [string]
    $ApkSignerPath,

    [switch]
    $DryRun
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

Set-StrictMode -Version Latest

$scriptRoot = $PSScriptRoot
$repoRoot = Split-Path -Parent $scriptRoot
Set-Location -Path $repoRoot

$targetPackage = 'com.cattailsw.nanidroid'
$testPackage = 'com.cattailsw.nanidroid.test'
$instrumentationRunner = "$testPackage/com.cattailsw.nanidroid.NanidroidTestRunner"
$manifestEntryName = [IO.Path]::GetFileName($ManifestPath)

$reportRoot = Join-Path $repoRoot 'build\reports\nar-corpus'
$failuresRoot = Join-Path $reportRoot 'failures'
$screenshotRoot = Join-Path $reportRoot 'screenshots'
$hostTmpRoot = Join-Path $reportRoot '.tmp'
$runId = [guid]::NewGuid().ToString('N')
$hostRunTmpRoot = Join-Path $hostTmpRoot $runId
$fixedSeed = '20260804T000000Z'
$constantFileName = 'nanidroid-corpus.nar'
$tmpRoot = '/data/local/tmp/nanidroid-corpus'
$tmpRunRoot = "$tmpRoot/$runId"
$tmpRunSafeRoot = "/data/local/tmp/nanidroid-corpus/$runId"
$privateDataRoot = $null
$failureOutputMaxChars = 3200
$adbTransportTimedOut = $false
$adbTransportTimeoutEvidence = $null

New-Item -ItemType Directory -Force -Path $reportRoot, $failuresRoot, $screenshotRoot, $hostTmpRoot, $hostRunTmpRoot | Out-Null

function Clear-RunArtifacts {
    param(
        [string]$FailuresRoot,
        [string]$SummaryPathRoot
    )

    $resolvedFailuresRoot = (Resolve-Path -Path $FailuresRoot).Path.TrimEnd('\')
    $resolvedSummaryRoot = (Resolve-Path -Path $SummaryPathRoot).Path.TrimEnd('\')
    $expectedBaseRoot = (Resolve-Path -Path (Join-Path $repoRoot 'build\reports\nar-corpus')).Path.TrimEnd('\')

    $expectedFailuresRoot = Join-Path $expectedBaseRoot 'failures'
    if (-not $resolvedFailuresRoot.Equals($expectedFailuresRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        ThrowIf "Refusing to clear failures artifacts outside build/reports/nar-corpus: $resolvedFailuresRoot"
    }
    if (-not $resolvedSummaryRoot.Equals($expectedBaseRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        ThrowIf "Refusing to clear report summary outside build/reports/nar-corpus: $resolvedSummaryRoot"
    }
    if ((Split-Path -Leaf $resolvedFailuresRoot) -ne 'failures') {
        ThrowIf "Unexpected failuresRoot name '$($resolvedFailuresRoot)'"
    }
    if ((Split-Path -Parent $resolvedFailuresRoot).TrimEnd('\') -ne $expectedBaseRoot) {
        ThrowIf "Unsafe failures cleanup path; expected parent '$expectedBaseRoot', got '$((Split-Path -Parent $resolvedFailuresRoot).TrimEnd('\'))'."
    }

    $protectedFailureDirs = @('.tools', '.manual', 'manual', 'focused', '.focused')
    Get-ChildItem -LiteralPath $resolvedFailuresRoot -Force | ForEach-Object {
        if ($_.PSIsContainer) {
            if ($_.Name -in $protectedFailureDirs -or $_.Name -like '.*') {
                return
            }
            Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction Stop
            return
        }
        Remove-Item -LiteralPath $_.FullName -Force -ErrorAction Stop
    }

    @(
        (Join-Path $resolvedSummaryRoot 'summary.json'),
        (Join-Path $resolvedSummaryRoot 'summary.md')
    ) | ForEach-Object {
        if (Test-Path -LiteralPath $_) {
            Remove-Item -LiteralPath $_ -Force -ErrorAction Stop
        }
    }
}

function ThrowIf([string]$Message, [string]$Code = 'validation') {
    throw [System.Exception]::new("$Code`: $Message")
}

function Has-Property {
    param(
        [object]$Object,
        [string]$Name
    )

    if ($null -eq $Object -or [string]::IsNullOrWhiteSpace($Name)) {
        return $false
    }

    try {
        return $null -ne $Object.PSObject.Properties[$Name]
    }
    catch {
        return $false
    }
}

function Get-NestedPropertyValue {
    param(
        [object]$Object,
        [string]$Path,
        $Found = $null
    )

    if ($null -eq $Object -or [string]::IsNullOrWhiteSpace($Path)) {
        if ($Found) { $Found.Value = $false }
        return $null
    }

    $current = $Object
    foreach ($segment in $Path -split '\.') {
        if ($null -eq $current) {
            if ($Found) { $Found.Value = $false }
            return $null
        }

        if ($segment -match '^(?<name>[^\[]+)(?:\[(?<index>\d+)\])?$') {
            $name = $matches.name
            $indexText = if ($matches.ContainsKey('index')) { $matches['index'] } else { $null }
            if ([string]::IsNullOrWhiteSpace($name)) {
                if ($Found) { $Found.Value = $false }
                return $null
            }

            if (-not (Has-Property -Object $current -Name $name)) {
                if ($Found) { $Found.Value = $false }
                return $null
            }
            $current = $current.$name

            if ($indexText) {
                if (-not ($current -is [System.Array] -or $current -is [System.Collections.IList])) {
                    if ($Found) { $Found.Value = $false }
                    return $null
                }
                $index = [int]$indexText
                if ($index -lt 0 -or $index -ge $current.Count) {
                    if ($Found) { $Found.Value = $false }
                    return $null
                }
                $current = $current[$index]
            }
        }
        else {
            if ($Found) { $Found.Value = $false }
            return $null
        }
    }

    if ($Found) { $Found.Value = $true }
    if ($current -is [System.Array] -or $current -is [System.Collections.IList]) {
        return ,$current
    }
    return $current
}

function Compare-NumericWithTolerance {
    param(
        [double]$Expected,
        [double]$Actual,
        [double]$Tolerance
    )

    if ($Tolerance -lt 0) {
        ThrowIf 'Numeric tolerance must be zero or positive.'
    }

    $delta = [Math]::Abs($Expected - $Actual)
    return $delta -le $Tolerance
}

function As-NonNullArray {
    param(
        [object]$Value
    )

    [object[]]$items = @()
    if ($null -ne $Value) {
        $items = @($Value) | Where-Object { $null -ne $_ }
    }
    return ,$items
}

function New-SentinelAccumulator {
    return [pscustomobject]@{
        checks = [System.Collections.ArrayList]::new()
        passed = $true
    }
}

function Add-SentinelCheck {
    param(
        [pscustomobject]$Accumulator,
        [string]$Name,
        [bool]$Passed,
        [object]$Expected = $null,
        [object]$Observed = $null,
        [string]$Detail = $null
    )

    $check = [pscustomobject]@{
        name = $Name
        passed = $Passed
        expected = $Expected
        observed = $Observed
        detail = $Detail
    }
    $Accumulator.checks.Add($check) | Out-Null
    if (-not $Passed) {
        $Accumulator.passed = $false
    }
}

function Add-SentinelNestedCheck {
    param(
        [pscustomobject]$Accumulator,
        [string]$Name,
        [object]$Result,
        [string]$Path,
        [object]$Expected,
        [string]$Detail = $null
    )

    $found = $false
    $actual = Get-NestedPropertyValue -Object $Result -Path $Path -Found ([ref]$found)
    $actualText = if ($found) { $actual } else { $null }
    $passed = $found -and ($actual -eq $Expected)
    Add-SentinelCheck -Accumulator $Accumulator -Name $Name -Passed $passed -Expected $Expected -Observed $actualText -Detail $Detail
}

function Test-OnlyExpectedTokenizerDiagnostics {
    param(
        [object]$Diagnostics
    )

    $items = As-NonNullArray -Value $Diagnostics
    foreach ($diagnostic in $items) {
        if (-not [string]::Equals(
            [string]$diagnostic,
            'unsupported-command:*',
            [System.StringComparison]::Ordinal
        )) {
            return $false
        }
    }
    return $true
}

function Test-NativeKawariCrashAllowed {
    param(
        [object]$ManifestEntry
    )

    return $null -ne $ManifestEntry -and
        (Has-Property -Object $ManifestEntry -Name 'allowNativeKawariCrash') -and
        $ManifestEntry.allowNativeKawariCrash -eq $true -and
        ($ManifestEntry.allowedClassifications -contains 'incompatible')
}

function Test-AdbTransportAvailable {
    param(
        [bool]$TransportTimedOut
    )

    return -not $TransportTimedOut
}

function Set-CanonicalArchiveCleanup {
    param(
        [object]$ArchiveResult,
        [object]$Result
    )

    if ($null -eq $ArchiveResult -or $null -eq $Result -or -not (Has-Property -Object $Result -Name 'cleanup') -or $null -eq $Result.cleanup) {
        ThrowIf 'Cannot publish archive cleanup without a canonical result cleanup payload.'
    }
    $ArchiveResult | Add-Member -NotePropertyName cleanup -NotePropertyValue $Result.cleanup -Force
}

function ConvertTo-NarCorpusJson {
    param(
        [object]$Value
    )

    return $Value | ConvertTo-Json -Depth 32
}

function Get-NarCorpusSentinelMarkdownHeader {
    return @(
        '| Name | Passed | Expected | Observed | Detail |',
        '| --- | --- | --- | --- | --- |'
    ) -join [Environment]::NewLine
}

function Truncate-Text([string]$Text, [int]$MaxChars = 4000) {
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return '<empty>'
    }
    $trimmed = $Text.Trim()
    if ($trimmed.Length -le $MaxChars) {
        return $trimmed
    }
    return $trimmed.Substring(0, $MaxChars) + "... (truncated $($trimmed.Length - $MaxChars) chars)"
}

if (-not $DryRun -and [string]::IsNullOrWhiteSpace($DeviceSerial)) {
    ThrowIf 'DeviceSerial is required unless -DryRun is set.'
}

function Unescape-GradlePath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return $Path
    }
    $value = $Path.Trim()
    while ($value.Contains('\\')) {
        $value = $value.Replace('\\', '\')
    }
    return $value.Replace('\:', ':').Replace('\ ', ' ')
}

function New-HostTempFile([string]$Suffix) {
    $name = [guid]::NewGuid().ToString('N') + $Suffix
    return Join-Path $hostRunTmpRoot $name
}

function Format-ProcessArguments([string[]]$Arguments) {
    return @($Arguments | ForEach-Object {
        $argument = [string]$_
        if ($argument.Contains('"')) {
            ThrowIf "Process argument contains an unsupported quote character: $argument"
        }
        if ($argument -match '\s') { '"' + $argument + '"' } else { $argument }
    })
}

function Get-SdkCandidates {
    $candidates = @()
    if (Test-Path -Path 'local.properties') {
        $localLine = Get-Content -Path local.properties | Select-String '^sdk\.dir=' | Select-Object -First 1
        if ($localLine) {
            $localSdk = $localLine.ToString().Substring('sdk.dir='.Length).Trim()
            $localSdk = Unescape-GradlePath -Path $localSdk
            if ($localSdk) { $candidates += $localSdk }
        }
    }
    if ($env:ANDROID_SDK_ROOT) { $candidates += Unescape-GradlePath -Path $env:ANDROID_SDK_ROOT }
    if ($env:ANDROID_HOME) { $candidates += Unescape-GradlePath -Path $env:ANDROID_HOME }
    return $candidates | Select-Object -Unique
}

function Resolve-AdbPath {
    if ($AdbPath) {
        if (-not (Test-Path -Path $AdbPath)) {
            ThrowIf "AdbPath '$AdbPath' does not exist."
        }
        return (Resolve-Path -Path $AdbPath).Path
    }

    $sdkDirCandidates = Get-SdkCandidates

    foreach ($sdkDir in $sdkDirCandidates) {
        if (-not (Test-Path -Path $sdkDir)) { continue }
        $candidate = Join-Path $sdkDir 'platform-tools\adb.exe'
        if (Test-Path -Path $candidate) {
            return (Resolve-Path -Path $candidate).Path
        }
    }

    $commandAdb = Get-Command adb -ErrorAction SilentlyContinue
    if ($commandAdb) {
        return $commandAdb.Source
    }

    ThrowIf 'adb was not found in local.properties sdk.dir, ANDROID_HOME, ANDROID_SDK_ROOT, or PATH.'
}

function Invoke-HostCommand {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [int]$TimeoutSeconds = 120,
        [string]$WorkingDirectory = $repoRoot
    )

    $tempOut = New-HostTempFile '.stdout'
    $tempErr = New-HostTempFile '.stderr'
    $processArguments = Format-ProcessArguments -Arguments $Arguments
    $process = Start-Process -FilePath $FilePath -ArgumentList $processArguments -WorkingDirectory $WorkingDirectory -NoNewWindow -PassThru -RedirectStandardOutput $tempOut -RedirectStandardError $tempErr
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        Remove-Item $tempOut, $tempErr -ErrorAction SilentlyContinue
        ThrowIf "$FilePath timed out after $TimeoutSeconds seconds."
    }
    $stdout = Get-Content -Path $tempOut -Raw -ErrorAction SilentlyContinue
    $stderr = Get-Content -Path $tempErr -Raw -ErrorAction SilentlyContinue
    Remove-Item $tempOut, $tempErr -ErrorAction SilentlyContinue
    if ($process.ExitCode -ne 0) {
        $fullOutput = $stderr + "`n" + $stdout
        ThrowIf "$FilePath failed with exit code $($process.ExitCode): $fullOutput"
    }
    return ($stdout + "`n" + $stderr).Trim()
}

function Invoke-Adb {
    param(
        [string[]]$Arguments,
        [int]$TimeoutSeconds = 120,
        [string]$StdOutFile = $null,
        [string]$StdErrFile = $null,
        [string]$Code = 'adb',
        [switch]$AllowFailure
    )

    if (-not (Test-AdbTransportAvailable -TransportTimedOut $script:adbTransportTimedOut)) {
        ThrowIf "ADB transport disabled after timeout: $script:adbTransportTimeoutEvidence" 'adb-timeout'
    }

    $fullArgs = if ([string]::IsNullOrWhiteSpace($DeviceSerial)) { @() } else { @('-s', $DeviceSerial) }
    $fullArgs += $Arguments
    $tempOut = if ($StdOutFile) { $StdOutFile } else { New-HostTempFile '.stdout' }
    $tempErr = if ($StdErrFile) { $StdErrFile } else { New-HostTempFile '.stderr' }
    $processArguments = Format-ProcessArguments -Arguments $fullArgs
    $process = Start-Process -FilePath $AdbPath -ArgumentList $processArguments -NoNewWindow -PassThru -RedirectStandardOutput $tempOut -RedirectStandardError $tempErr
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        Remove-Item $tempOut, $tempErr -ErrorAction SilentlyContinue
        $script:adbTransportTimedOut = $true
        $script:adbTransportTimeoutEvidence = "adb $($fullArgs -join ' ') exceeded $TimeoutSeconds seconds"
        ThrowIf "adb timed out: adb $($fullArgs -join ' ')" $Code
    }
    $stdout = Get-Content -Path $tempOut -Raw -ErrorAction SilentlyContinue
    $stderr = Get-Content -Path $tempErr -Raw -ErrorAction SilentlyContinue
    Remove-Item $tempOut, $tempErr -ErrorAction SilentlyContinue
    $fullOutput = ($stdout + "`n" + $stderr).Trim()
    if ($process.ExitCode -ne 0) {
        if ($AllowFailure) {
            return @{ exitCode = $process.ExitCode; output = $fullOutput }
        }
        ThrowIf "adb command failed with exit code $($process.ExitCode): $fullOutput" $Code
    }
    if ($AllowFailure) {
        return @{ exitCode = 0; output = $fullOutput }
    }
    return $fullOutput
}

function Invoke-AdbCommand {
    param([string[]]$Arguments, [int]$TimeoutSeconds = 120, [bool]$AllowFailure = $false)
    return (Invoke-Adb -Arguments $Arguments -TimeoutSeconds $TimeoutSeconds -AllowFailure:$AllowFailure)
}

function Invoke-ArgumentListProcess {
    param(
        [string]$FilePath,
        [string[]]$Arguments,
        [int]$TimeoutSeconds = 120
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($arg in $Arguments) {
        $startInfo.ArgumentList.Add($arg)
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $exitCode = 0
    $stdout = $null
    $stderr = $null

    try {
        $null = $process.Start()
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill($true)
            $process.WaitForExit()
            ThrowIf "Timed out while executing: $FilePath " + ($Arguments -join ' ')
        }
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        $exitCode = $process.ExitCode
    }
    finally {
        $process.Dispose()
    }

    $stdoutText = if ($null -eq $stdout) { '' } else { $stdout.TrimEnd("`r", "`n") }
    $stderrText = if ($null -eq $stderr) { '' } else { $stderr.TrimEnd("`r", "`n") }
    return @{
        exitCode = $exitCode
        output = $stdoutText
        error = $stderrText
    }
}

function Get-AdbProperty([string]$Name) {
    $output = Invoke-Adb -Arguments @('shell', 'getprop', $Name) -TimeoutSeconds 10
    return [string]$output.Trim()
}

function Get-DeviceDensity {
    $wmDensity = Invoke-Adb -Arguments @('shell', 'wm', 'density') -TimeoutSeconds 10
    $override = [regex]::Match($wmDensity, '(?im)^Override density:\s*(\d+)\s*$')
    if ($override.Success) {
        return [int]$override.Groups[1].Value
    }
    foreach ($property in @('ro.sf.lcd_density', 'qemu.sf.lcd_density')) {
        $value = Get-AdbProperty $property
        [int]$parsed = 0
        if ([int]::TryParse($value, [ref]$parsed) -and $parsed -gt 0) {
            return $parsed
        }
    }
    $physical = [regex]::Match($wmDensity, '(?im)^Physical density:\s*(\d+)\s*$')
    if ($physical.Success) {
        return [int]$physical.Groups[1].Value
    }
    ThrowIf "Unable to read effective device density: $wmDensity"
}

function Get-AdbPackageVersion([string]$PackageName) {
    $output = Invoke-Adb -Arguments @('shell', 'pm', 'list', 'packages', $PackageName)
    return $output -match [regex]::Escape("package:$PackageName")
}

function Get-AdbPath([string]$ApkPackage) {
    $raw = Invoke-Adb -Arguments @('shell', 'pm', 'path', $ApkPackage)
    if ([string]::IsNullOrWhiteSpace($raw)) {
        return $null
    }
    $pathLine = $raw.Trim().Split("`n") | Where-Object { $_ -match 'package:' } | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($pathLine)) { return $null }
    return $pathLine.Substring('package:'.Length)
}

function Ensure-CommandExists([string]$CommandName, [string]$Purpose) {
    if (-not (Get-Command $CommandName -ErrorAction SilentlyContinue)) {
        ThrowIf "$CommandName is required for $Purpose but was not found."
    }
}

function Ensure-ExecutableExists([string]$Path, [string]$Purpose) {
    if ([string]::IsNullOrWhiteSpace($Path)) {
        ThrowIf "Executable path is required for $Purpose but was empty."
    }
    if (-not (Test-Path -Path $Path)) {
        ThrowIf "Executable '$Path' required for $Purpose was not found."
    }
}

function Compute-ArchiveSha([string]$ArchivePath) {
    return (Get-FileHash -Algorithm SHA256 -Path $ArchivePath).Hash.ToLowerInvariant()
}

function Sanitize-Label([string]$Label) {
    return ($Label -replace '[^A-Za-z0-9._-]', '-').Trim('-')
}

function Read-JsonFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        ThrowIf "Required file not found: $Path"
    }
    $raw = Get-Content -LiteralPath $Path -Raw
    try {
        return $raw | ConvertFrom-Json
    }
    catch {
        ThrowIf "Invalid JSON in ${Path}: $($_.Exception.Message)"
    }
}

function Read-LocalResultJson([string]$SafeLabel) {
    if ([string]::IsNullOrWhiteSpace($SafeLabel)) {
        return $null
    }
    $localReportDir = Join-Path $reportRoot $SafeLabel
    $localResultPath = Join-Path $localReportDir 'result.json'
    if (-not (Test-Path -LiteralPath $localResultPath)) {
        return $null
    }
    try {
        return Read-JsonFile -Path $localResultPath
    }
    catch {
        return $null
    }
}

function Read-ResultJsonFromSummaryRow([object]$ResultRow) {
    if ($null -eq $ResultRow) {
        return $null
    }

    $resultPath = Get-NestedPropertyValue -Object $ResultRow -Path 'resultPath'
    if (-not [string]::IsNullOrWhiteSpace($resultPath)) {
        if (Test-Path -LiteralPath $resultPath) {
            return Read-JsonFile -Path $resultPath
        }
        if ($resultPath -match '/nar-corpus/(?<safeLabel>[^/]+)/result\.json$') {
            $safeLabel = $matches.safeLabel
            $localResult = Read-LocalResultJson -SafeLabel $safeLabel
            if ($null -ne $localResult) {
                return $localResult
            }
        }
    }

    $safeLabel = Get-NestedPropertyValue -Object $ResultRow -Path 'safeLabel'
    if (-not [string]::IsNullOrWhiteSpace($safeLabel)) {
        return Read-LocalResultJson -SafeLabel $safeLabel
    }

    return $null
}

function Resolve-CorpusRoots([string[]]$Roots) {
    $seen = @{}
    $directoryRoots = @()
    $fileRoots = @()
    foreach ($root in $Roots) {
        if ([string]::IsNullOrWhiteSpace($root)) { continue }
        if ([IO.Path]::IsPathRooted($root)) {
            $rootPath = $root
        }
        else {
            $rootPath = Join-Path $repoRoot $root
        }
        try {
            $rootResolved = (Resolve-Path -Path $rootPath).Path.TrimEnd('\')
            if (Test-Path -LiteralPath $rootResolved -PathType Leaf) {
                $item = Get-Item -LiteralPath $rootResolved
                if ($item.Extension -ieq '.nar') {
                    $rootLower = $rootResolved.ToLowerInvariant()
                    if (-not $seen.ContainsKey($rootLower)) {
                        $fileRoots += $rootResolved
                        $seen[$rootLower] = $true
                    }
                }
                continue
            }
            if (-not (Test-Path -Path $rootResolved -PathType Container)) {
                continue
            }
            $rootLower = $rootResolved.ToLowerInvariant()
            if (-not $seen.ContainsKey($rootLower)) {
                $directoryRoots += $rootResolved
                $seen[$rootLower] = $true
            }
        }
        catch {
            continue
        }
    }
    if (($directoryRoots.Count + $fileRoots.Count) -eq 0) {
        ThrowIf 'No valid CorpusRoots were resolved.'
    }
    $sorted = $directoryRoots | Sort-Object Length
    $deduped = @()
    foreach ($candidate in $sorted) {
        $normalized = $candidate.ToLowerInvariant() + '\'
        $nested = $false
        foreach ($previous in $deduped) {
            if ($normalized.StartsWith($previous.ToLowerInvariant().TrimEnd('\') + '\')) {
                $nested = $true
                break
            }
        }
        if (-not $nested) {
            $deduped += $candidate
        }
    }
    return @($deduped + $fileRoots)
}

function Get-DevicePathPresence([string]$Path, [string]$Context = 'output') {
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return @()
    }

    if ($Context -eq 'run-as') {
        $result = Invoke-Adb -Arguments @('shell', 'run-as', $targetPackage, 'ls', '-d', $Path) -TimeoutSeconds 20 -AllowFailure
        if ($result.exitCode -ne 0 -and $result.output -match 'No such file|No such file or directory') {
            return @()
        }
        if ($result.exitCode -ne 0) {
            ThrowIf "run-as ls failed for ${Path}: $($result.output)"
        }
        return @($result.output.Trim())
    }

    $result = Invoke-Adb -Arguments @('shell', 'ls', '-d', $Path) -TimeoutSeconds 20 -AllowFailure
    if ($result.exitCode -ne 0 -and $result.output -match 'No such file|No such file or directory') {
        return @()
    }
    if ($result.exitCode -ne 0) {
        ThrowIf "ls failed for ${Path}: $($result.output)"
    }
    return @($result.output.Trim())
}

function Assert-DirectoryCleared([string]$Path, [string]$Context = 'output') {
    $remaining = @(Get-DevicePathPresence -Path $Path -Context $Context)
    if ($remaining.Count -ne 0) {
        ThrowIf "Device path '$Path' still contains run-owned residue: $($remaining -join ', ')"
    }
}

function Collect-Archives([string[]]$Roots) {
    $archiveMap = @{}
    foreach ($root in $Roots) {
        if (-not (Test-Path -Path $root)) {
            continue
        }
        if (Test-Path -Path $root -PathType Leaf) {
            $item = Get-Item -LiteralPath $root
            if ($item.Extension -ieq '.nar') {
                $archivePath = $item.FullName
                $archiveSha = Compute-ArchiveSha -ArchivePath $archivePath
                $key = "$archiveSha|$($item.Length)"
                if (-not $archiveMap.ContainsKey($key)) {
                    $archiveMap[$key] = [pscustomobject]@{
                        path = $archivePath
                        name = $item.Name
                        bytes = $item.Length
                        sha256 = $archiveSha
                    }
                }
            }
            continue
        }
        Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.nar' -ErrorAction SilentlyContinue | ForEach-Object {
            $archivePath = $_.FullName
            $archiveSha = Compute-ArchiveSha -ArchivePath $archivePath
            $key = "$archiveSha|$($_.Length)"
            if (-not $archiveMap.ContainsKey($key)) {
                $archiveMap[$key] = [pscustomobject]@{
                    path = $archivePath
                    name = $_.Name
                    bytes = $_.Length
                    sha256 = $archiveSha
                }
            }
        }
    }
    return $archiveMap.Values
}

function Assert-SafeLabel([string]$SafeLabel) {
    if ([string]::IsNullOrWhiteSpace($SafeLabel)) {
        ThrowIf 'SafeLabel is required before performing per-archive device cleanup.'
    }
    if ($SafeLabel -match '(^\.+$)' -or $SafeLabel -match '[\\/]') {
        ThrowIf "Unsafe SafeLabel detected '$SafeLabel'."
    }
}

function Remove-RemotePath([string]$Path, [bool]$TrimParents = $false) {
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return
    }
    if ($Path -notlike '/sdcard/Android/data/*' -and $Path -notlike '/data/local/tmp/*') {
        ThrowIf "Refusing cleanup outside allowed roots for path '$Path'."
    }
    Invoke-Adb -Arguments @('shell', 'rm', '-rf', $Path) -TimeoutSeconds 20 -AllowFailure | Out-Null
    if (-not $TrimParents) {
        return
    }
    if ($Path -eq $tmpRoot) {
        return
    }
    $parent = $Path.Substring(0, $Path.LastIndexOf('/'))
    for ($index = 0; $index -lt 2 -and $parent; $index++) {
        if ($parent -eq $tmpRoot -or $parent -eq "/sdcard/Android/data/$targetPackage") {
            Invoke-Adb -Arguments @('shell', 'rmdir', $parent) -TimeoutSeconds 20 -AllowFailure | Out-Null
            break
        }
        if ($parent -notlike "/sdcard/Android/data/$targetPackage/*" -and $parent -notlike "$tmpRoot/*") {
            break
        }
        Invoke-Adb -Arguments @('shell', 'rmdir', $parent) -TimeoutSeconds 20 -AllowFailure | Out-Null
        $slash = $parent.LastIndexOf('/')
        $parent = if ($slash -gt 0) { $parent.Substring(0, $slash) } else { $null }
    }
}

function Validate-ManifestEntries([object[]]$ManifestEntries) {
    $labels = @{}
    $hashes = @{}
    $safeLabels = @{}
    foreach ($entry in $ManifestEntries) {
        if (-not $entry.label) {
            ThrowIf 'Manifest entry missing label.'
        }
        if ($entry.label -match '["\r\n]') {
            ThrowIf "Manifest entry label '$($entry.label)' contains an unsupported quote or newline."
        }
        if (-not $entry.sha256) {
            ThrowIf "Manifest entry '$($entry.label)' missing sha256."
        }
        if ($entry.sha256 -notmatch '^[0-9A-Fa-f]{64}$') {
            ThrowIf "Manifest entry '$($entry.label)' has invalid sha256 '$($entry.sha256)'."
        }
        if ($entry.expectedKind -notin @('ghost', 'shell', 'balloon')) {
            ThrowIf "Manifest entry '$($entry.label)' has unsupported expectedKind '$($entry.expectedKind)'."
        }
        if (-not $entry.requiredEvidence -or $entry.requiredEvidence.Count -eq 0) {
            ThrowIf "Manifest entry '$($entry.label)' has no requiredEvidence."
        }
        if (-not $entry.allowedClassifications -or $entry.allowedClassifications.Count -eq 0) {
            ThrowIf "Manifest entry '$($entry.label)' has no allowedClassifications."
        }
        if ((Has-Property -Object $entry -Name 'allowNativeKawariCrash') -and
            ($entry.allowNativeKawariCrash -isnot [bool] -or -not $entry.allowNativeKawariCrash)) {
            ThrowIf "Manifest entry '$($entry.label)' has invalid allowNativeKawariCrash; omit it or set it to true."
        }
        if ((Has-Property -Object $entry -Name 'allowNativeKawariCrash') -and
            $entry.allowNativeKawariCrash -and
            ($entry.allowedClassifications -notcontains 'incompatible')) {
            ThrowIf "Manifest entry '$($entry.label)' allows a Kawari crash without allowing incompatible classification."
        }
        $sha = $entry.sha256.ToLowerInvariant()
        $label = $entry.label.ToLowerInvariant()
        $safeLabel = (Sanitize-Label -Label $entry.label).ToLowerInvariant()
        if ($hashes.ContainsKey($sha)) {
            ThrowIf "Duplicate manifest hash '$sha' for '$($entry.label)' and '$($hashes[$sha].label)'."
        }
        if ($labels.ContainsKey($label)) {
            ThrowIf "Duplicate manifest label '$($entry.label)' appears more than once."
        }
        if ($safeLabels.ContainsKey($safeLabel)) {
            ThrowIf "Manifest labels '$($entry.label)' and '$($safeLabels[$safeLabel].label)' collide after path sanitization."
        }
        $hashes[$sha] = $entry
        $labels[$label] = $entry
        $safeLabels[$safeLabel] = $entry
    }
}

function Validate-ManifestMatch([object[]]$ManifestEntries, [object[]]$Archives, [ref]$Missing, [ref]$Unexpected) {
    $manifestByHash = @{}
    foreach ($entry in $ManifestEntries) {
        if (-not $entry.sha256 -or $entry.sha256.Length -ne 64) {
            ThrowIf "Manifest entry '$($entry.label)' has invalid sha256 '$($entry.sha256)'."
        }
        $manifestByHash[$entry.sha256.ToLowerInvariant()] = $entry
    }

    $archiveByHash = @{}
    foreach ($archive in $Archives) {
        $archiveByHash[$archive.sha256] = $archive
    }

    foreach ($entry in $ManifestEntries) {
        if (-not $archiveByHash.ContainsKey($entry.sha256.ToLowerInvariant())) {
            $Missing.Value += [pscustomobject]@{
                type = 'manifest-miss'
                label = $entry.label
                sha256 = $entry.sha256
            }
        }
    }
    foreach ($entry in $Archives) {
        if (-not $manifestByHash.ContainsKey($entry.sha256)) {
            $Unexpected.Value += [pscustomobject]@{
                type = 'discovered-extra'
                path = $entry.path
                sha256 = $entry.sha256
            }
        }
    }
    return [pscustomobject]@{
        ManifestByHash = $manifestByHash
        ArchiveByHash = $archiveByHash
        MissingCount = $Missing.Value.Count
        UnexpectedCount = $Unexpected.Value.Count
    }
}

function Get-RequiredEvidenceValues([object]$Result, [string[]]$RequiredEvidence) {
    $collected = @{}
    foreach ($evidence in $RequiredEvidence) {
        $found = $false
        $value = Get-NestedPropertyValue -Object $Result -Path $evidence -Found ([ref]$found)
        if (-not $found) {
            ThrowIf "Required evidence '$evidence' missing in result payload."
        }
        if ($null -eq $value) {
            ThrowIf "Required evidence '$evidence' is null."
        }
        $collected[$evidence] = $value
    }
    return $collected
}

function Resolve-ApkSigner {
    if ($ApkSignerPath) {
        $explicitSigner = Resolve-Path -Path $ApkSignerPath -ErrorAction SilentlyContinue
        if (-not $explicitSigner) {
            ThrowIf "ApkSignerPath '$ApkSignerPath' does not exist."
        }
        return $explicitSigner.Path
    }

    $commandSigner = Get-Command apksigner -ErrorAction SilentlyContinue
    if ($commandSigner) {
        return $commandSigner.Source
    }
    foreach ($sdkDir in Get-SdkCandidates) {
        $buildToolsDir = Join-Path $sdkDir 'build-tools'
        if (-not (Test-Path -Path $buildToolsDir)) { continue }
        $matches = Get-ChildItem -Path $buildToolsDir -Directory | Sort-Object Name -Descending
        foreach ($entry in $matches) {
            $candidate = Join-Path $entry.FullName 'apksigner.bat'
            if (Test-Path -Path $candidate) {
                return (Resolve-Path -Path $candidate).Path
            }
        }
    }
    ThrowIf 'apksigner was not found in PATH, local.properties sdk.dir, ANDROID_HOME, or ANDROID_SDK_ROOT.'
}

function Verify-DebugSignature([string]$ApkPath, [string]$Label, [string]$ApkSignerPath) {
    if (-not (Test-Path -Path $ApkPath)) {
        ThrowIf "APK file '$ApkPath' was not found for $Label debug signature check."
    }
    if (-not $ApkSignerPath) {
        ThrowIf "apksigner path is required for debug signature check."
    }
    $output = Invoke-HostCommand -FilePath $ApkSignerPath -Arguments @('verify', '--print-certs', $ApkPath) -TimeoutSeconds 90
    if (-not ($output -match 'CN=Android Debug')) {
        ThrowIf "apk $Label did not present an Android Debug signature."
    }
    return $true
}

function Check-DeviceGate {
    $serialState = Invoke-Adb -Arguments @('get-state') -TimeoutSeconds 10
    if ($serialState.Trim() -ne 'device') {
        ThrowIf "Device serial '$DeviceSerial' is not in 'device' state; state=$serialState"
    }
    $qemu = Get-AdbProperty 'ro.kernel.qemu'
    if ($qemu -ne '1') {
        ThrowIf "ro.kernel.qemu=$qemu. A physical device is not allowed."
    }
    $sdk = [int](Get-AdbProperty 'ro.build.version.sdk')
    if ($sdk -lt 31 -or $sdk -gt 37) {
        ThrowIf "Android SDK version $sdk outside allowed range 31-37."
    }
    $abi = Get-AdbProperty 'ro.product.cpu.abi'
    if ($abi -notin @('x86_64', 'arm64-v8a')) {
        ThrowIf "Unsupported ABI $abi. Supported ABIs: x86_64, arm64-v8a."
    }
    Get-DeviceDensity | Out-Null
    if (-not (Get-AdbProperty 'ro.debuggable')) {
        ThrowIf 'Target build must be debuggable.'
    }
}

function Check-DeviceStorage {
    $dfOutput = Invoke-Adb -Arguments @('shell', 'df', '-k', '/data') -TimeoutSeconds 20
    $dataLine = $dfOutput -split "`r?`n" |
        Where-Object { $_ -match '^\S+\s+\d+\s+\d+\s+\d+\s+\d+%\s+/data(?:/user/0)?\s*$' } |
        Select-Object -First 1
    if (-not $dataLine) {
        ThrowIf "Unable to parse /data filesystem capacity: $dfOutput"
    }
    $fields = @($dataLine.Trim() -split '\s+')
    [int64]$availableKiB = 0
    if ($fields.Count -lt 6 -or -not [int64]::TryParse($fields[3], [ref]$availableKiB)) {
        ThrowIf "Unable to parse /data available space from: $dataLine"
    }
    $dataFreeBytes = [int64]$availableKiB * 1024L
    if ($dataFreeBytes -lt $MinimumFreeBytes) {
        ThrowIf "Insufficient free /data space. Available $dataFreeBytes bytes, required $MinimumFreeBytes."
    }
}

function Validate-NoPreexistingDeviceState {
    if (Get-AdbPackageVersion $targetPackage) {
        ThrowIf "Pre-existing package '$targetPackage' is installed. Abort per requirement."
    }
    if (Get-AdbPackageVersion $testPackage) {
        ThrowIf "Pre-existing package '$testPackage' is installed. Abort per requirement."
    }
    $externalRoot = "/sdcard/Android/data/$targetPackage"
    $result = Invoke-Adb -Arguments @('shell', 'ls', $externalRoot) -TimeoutSeconds 20 -AllowFailure
    if ($result.exitCode -ne 0 -and $result.output -match 'No such file|No such file or directory') {
        return
    }
    if ($result.exitCode -ne 0) {
        ThrowIf $result.output
    }
    ThrowIf "Pre-existing app-data was found at $externalRoot."
}

function Build-Apks {
    Ensure-ExecutableExists -Path (Join-Path $repoRoot '.\gradlew.bat') -Purpose 'Gradle wrapper'
    Write-Host 'Building debug and androidTest APKs once.'
    $gradleOutput = Invoke-HostCommand -FilePath '.\gradlew.bat' -Arguments @('assembleDebug', 'assembleDebugAndroidTest', '--no-daemon') -TimeoutSeconds ($BuildTimeoutMinutes * 60) -WorkingDirectory $repoRoot
    Write-Host $gradleOutput
    $debugApk = Get-ChildItem -Path 'build\outputs\apk\debug' -Filter '*-debug.apk' -Recurse -File | Select-Object -First 1
    if (-not $debugApk) {
        ThrowIf 'Unable to locate debug APK under build\outputs\apk\debug.'
    }
    $testApk = Get-ChildItem -Path 'build\outputs\apk\androidTest\debug' -Filter '*.apk' -Recurse -File | Select-Object -First 1
    if (-not $testApk) {
        ThrowIf 'Unable to locate androidTest APK under build\outputs\apk\androidTest\debug.'
    }
    $debugApkPath = $debugApk.FullName
    $testApkPath = $testApk.FullName
    return [pscustomobject]@{
        DebugApkPath = $debugApkPath
        TestApkPath = $testApkPath
        DebugApkSha256 = (Get-FileHash -Algorithm SHA256 -Path $debugApkPath).Hash.ToLowerInvariant()
        TestApkSha256 = (Get-FileHash -Algorithm SHA256 -Path $testApkPath).Hash.ToLowerInvariant()
    }
}

function Verify-RunAs {
    $runAs = Invoke-Adb -Arguments @('shell', 'run-as', $targetPackage, 'id') -TimeoutSeconds 20 -AllowFailure
    if ($runAs.exitCode -ne 0) {
        ThrowIf "run-as $targetPackage failed after install: $($runAs.output)"
    }
}

function Snapshot-NetworkState {
    $wifiState = (Invoke-Adb -Arguments @('shell', 'svc', 'wifi', 'get-state') -TimeoutSeconds 10 -AllowFailure).output
    $dataState = (Invoke-Adb -Arguments @('shell', 'svc', 'data', 'get-state') -TimeoutSeconds 10 -AllowFailure).output
    return @{
        wifi = if ($wifiState) { $wifiState.Trim() } else { 'unknown' }
        data = if ($dataState) { $dataState.Trim() } else { 'unknown' }
    }
}

function Set-NetworkState {
    param(
        [bool]$Disable,
        [hashtable]$Snapshot = $null
    )
    if ($Disable) {
        Invoke-Adb -Arguments @('shell', 'svc', 'wifi', 'disable') -TimeoutSeconds 20 | Out-Null
        Invoke-Adb -Arguments @('shell', 'svc', 'data', 'disable') -TimeoutSeconds 20 | Out-Null
        return
    }

    if (-not $Snapshot) {
        $Snapshot = @{ wifi = 'disabled'; data = 'disabled' }
    }

    if ($Snapshot.wifi -match 'enabled|on') {
        try {
            Invoke-Adb -Arguments @('shell', 'svc', 'wifi', 'enable') -TimeoutSeconds 20 | Out-Null
        }
        catch {
            Write-Warning "Unable to restore wifi state: $($_.Exception.Message)"
        }
    }
    else {
        try {
            Invoke-Adb -Arguments @('shell', 'svc', 'wifi', 'disable') -TimeoutSeconds 20 | Out-Null
        }
        catch {
            Write-Warning "Unable to restore wifi state: $($_.Exception.Message)"
        }
    }

    if ($Snapshot.data -match 'enabled|on') {
        try {
            Invoke-Adb -Arguments @('shell', 'svc', 'data', 'enable') -TimeoutSeconds 20 | Out-Null
        }
        catch {
            Write-Warning "Unable to restore data state: $($_.Exception.Message)"
        }
    }
    else {
        try {
            Invoke-Adb -Arguments @('shell', 'svc', 'data', 'disable') -TimeoutSeconds 20 | Out-Null
        }
        catch {
            Write-Warning "Unable to restore data state: $($_.Exception.Message)"
        }
    }
}

function Run-TestArchive {
    param(
        [string]$ArchivePath,
        [string]$ArchiveSha,
        [string]$Label,
        [int64]$ArchiveBytes,
        [string]$SafeLabel,
        [object]$ManifestEntry,
        [string]$PrivateArchivePath
    )

    $preRun = Get-Date
    if ($Label.Contains('"')) {
        ThrowIf "Archive label contains an unsupported quote character: $Label"
    }
    $privateInputPath = "$privateDataRoot/cache/nar-corpus-host/$runId/$SafeLabel"
    $externalResultPath = "/sdcard/Android/data/$targetPackage/files/nar-corpus/$SafeLabel"
    $tmpArchiveDir = "$tmpRunRoot/$SafeLabel"
    $privatePathTmp = "$tmpArchiveDir/$constantFileName"
    $resultJsonPath = "$externalResultPath/result.json"
    $resultScreenshotPath = "$externalResultPath/screenshot.png"
    $localReportDir = Join-Path $reportRoot $SafeLabel
    New-Item -ItemType Directory -Force -Path $localReportDir | Out-Null
    $resultJsonLocal = Join-Path $localReportDir 'result.json'
    $screenshotLocal = Join-Path $screenshotRoot "$SafeLabel.png"
    $crashLogLocal = Join-Path $localReportDir 'crash-log.txt'
    $archiveResult = $null
    $nativeCrashAccepted = $false
    $nativeCrashEvidence = $null
    $hostCleanupEvidence = 'host cleanup not yet attempted'
    $dialogueOutcomeFromResult = $null
    $runtimeCheckpointPhase = $null
    $postPrivateSnapshot = @()
    $postOutputSnapshot = @()
    $postTmpSnapshot = @()

    Write-Host "Running archive: $Label"
    Assert-SafeLabel -SafeLabel $SafeLabel

    if ($PrivateArchivePath -ne "$privateInputPath/$constantFileName") {
        ThrowIf "Private archive path escaped the exact run-owned input root: $PrivateArchivePath"
    }
    $prePrivateSnapshot = Get-DevicePathPresence $privateInputPath 'run-as'
    $preOutputSnapshot = Get-DevicePathPresence $externalResultPath
    $preRunSnapshot = Get-DevicePathPresence $tmpArchiveDir

    try {
        Invoke-Adb -Arguments @('logcat', '-b', 'crash', '-c') -TimeoutSeconds 20 | Out-Null
        Invoke-Adb -Arguments @('shell', 'mkdir', '-p', $tmpArchiveDir) -TimeoutSeconds 20 | Out-Null
        Invoke-Adb -Arguments @('push', $ArchivePath, $privatePathTmp) -TimeoutSeconds 120 | Out-Null
        Invoke-Adb -Arguments @('shell', 'chmod', '0644', $privatePathTmp) -TimeoutSeconds 20 | Out-Null
        Invoke-Adb -Arguments @('shell', 'run-as', $targetPackage, 'mkdir', '-p', $privateInputPath) -TimeoutSeconds 20 | Out-Null
        Invoke-Adb -Arguments @('shell', 'run-as', $targetPackage, 'cp', $privatePathTmp, $PrivateArchivePath) -TimeoutSeconds 20 | Out-Null
        $pathCheck = Invoke-Adb -Arguments @('shell', 'run-as', $targetPackage, 'stat', $PrivateArchivePath) -TimeoutSeconds 20 -AllowFailure
        if ($pathCheck.exitCode -ne 0) {
            ThrowIf "Unable to verify private archive path: $($pathCheck.output)"
        }

        $instrumentationArgs = @(
            'shell','am','instrument','-w','-r',
            '-e','class','com.cattailsw.nanidroid.corpus.NarCorpusRuntimeTest#probesArchive',
            '-e','narCorpusPath',$PrivateArchivePath,
            '-e','narCorpusSha256',$ArchiveSha,
            '-e','narCorpusLabelBase64',([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Label))),
            '-e','narCorpusSeed',$fixedSeed,
            '-e','disableNetwork','true',
            $instrumentationRunner
        )
        $processArguments = @('-s', $DeviceSerial) + $instrumentationArgs
        try {
            $instrumentProcessResult = Invoke-ArgumentListProcess -FilePath $AdbPath -Arguments $processArguments -TimeoutSeconds ($PerArchiveTimeoutMinutes * 60)
        }
        catch {
            if ($_.Exception.Message -like 'Timed out while executing:*') {
                $script:adbTransportTimedOut = $true
                $script:adbTransportTimeoutEvidence = "instrumentation for '$Label' exceeded $PerArchiveTimeoutMinutes minutes"
                ThrowIf "adb timed out during instrumentation for $Label; no further ADB commands will be issued." 'adb-timeout'
            }
            throw
        }
        $rawOut = $instrumentProcessResult.output
        $rawErr = $instrumentProcessResult.error
        $instrumentProcess = @{ ExitCode = $instrumentProcessResult.exitCode }
        $instrumentationReportedFailure = $rawOut -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE:\s*-2'
        $processCrashed = $rawOut -match 'Process crashed'
        $crashBuffer = Invoke-Adb -Arguments @('logcat', '-b', 'crash', '-d') -TimeoutSeconds 20 -AllowFailure
        $crashBufferText = if ($crashBuffer.output) { $crashBuffer.output } else { '' }
        $crashEvidence = Truncate-Text -Text $crashBufferText -MaxChars $failureOutputMaxChars
        Set-Content -Path $crashLogLocal -Value $crashEvidence -Encoding UTF8
        $nativeCrashEvidence = "Crash buffer captured for ${Label}: $(if ($processCrashed) { 'raw output matched Process crashed' } else { 'raw output had no crash marker' })"

        $pullResult = Invoke-Adb -Arguments @('pull', $resultJsonPath, $resultJsonLocal) -TimeoutSeconds 60 -AllowFailure
        $pullScreenshot = Invoke-Adb -Arguments @('pull', $resultScreenshotPath, $screenshotLocal) -TimeoutSeconds 60 -AllowFailure
        $artifactContext = @()
        if ($pullResult.exitCode -ne 0 -or -not (Test-Path $resultJsonLocal)) {
            $artifactContext += "result.json missing (pullExit=$($pullResult.exitCode), pullOutput=$((Truncate-Text -Text $pullResult.output -MaxChars $failureOutputMaxChars)) )"
        }
        if ($pullScreenshot.exitCode -ne 0 -or -not (Test-Path $screenshotLocal)) {
            $artifactContext += "screenshot missing (pullExit=$($pullScreenshot.exitCode), pullOutput=$((Truncate-Text -Text $pullScreenshot.output -MaxChars $failureOutputMaxChars)) )"
        }
        if ($artifactContext.Count -gt 0) {
            $artifactError = ($artifactContext -join '; ')
            $rawContext = "stdout:`n$((Truncate-Text -Text $rawOut -MaxChars $failureOutputMaxChars))`nstderr:`n$((Truncate-Text -Text $rawErr -MaxChars $failureOutputMaxChars))"
            ThrowIf "${artifactError} for $Label. $rawContext"
        }

        $result = Get-Content -LiteralPath $resultJsonLocal -Raw | ConvertFrom-Json
        $postRunResult = Get-RequiredEvidenceValues -Result $result -RequiredEvidence $ManifestEntry.requiredEvidence
        if (-not $result.passed) {
            ThrowIf "Result for $Label reported passed=$($result.passed)."
        }
        if ($result.observedKind -ne $ManifestEntry.expectedKind) {
            ThrowIf "Package kind mismatch for ${Label}: expected '$($ManifestEntry.expectedKind)', observed '$($result.observedKind)'."
        }
        if ($result.sha256 -and $result.sha256.ToLowerInvariant() -ne $ArchiveSha.ToLowerInvariant()) {
            ThrowIf "Result SHA mismatch for ${Label}: expected ${ArchiveSha}, got $($result.sha256)."
        }
        if ($result.label -and $result.label -ne $Label) {
            ThrowIf "Result label mismatch for ${Label}: expected ${Label}, got $($result.label)."
        }
        if ($result.narCorpusPath -and $result.narCorpusPath -ne $PrivateArchivePath) {
            ThrowIf "Result path mismatch for ${Label}: expected $($PrivateArchivePath), got $($result.narCorpusPath)."
        }

        $acceptedCrashPredicate = $processCrashed -and
            $result.passed -and
            ($result.checkpointPhase -eq 'before-real-shiori') -and
            ($crashBufferText -match [regex]::Escape($targetPackage)) -and
            ($crashBufferText -match 'SIGSEGV') -and
            ($crashBufferText -match 'libkawari8\.so')

        if ($acceptedCrashPredicate -and -not ($ManifestEntry.allowedClassifications -contains 'incompatible')) {
            ThrowIf "Accepted native-crash criteria met for $Label but manifest does not allow 'incompatible'."
        }
        $acceptedNativeCrash = $acceptedCrashPredicate -and (Test-NativeKawariCrashAllowed -ManifestEntry $ManifestEntry)
        if ($acceptedNativeCrash) {
            $nativeCrashAccepted = $true
            $nativeCrashEvidence = "Accepted native crash marker for ${Label}: target process+SIGSEGV+libkawari8"
        }
        if ($processCrashed -and -not $acceptedNativeCrash) {
            $crashContext = "stdout:`n$((Truncate-Text -Text $rawOut -MaxChars $failureOutputMaxChars))`nstderr:`n$((Truncate-Text -Text $rawErr -MaxChars $failureOutputMaxChars))"
            ThrowIf "Process crashed for ${Label} but did not satisfy accepted native crash criteria. $crashContext"
        }

        if ($instrumentationReportedFailure -and -not $nativeCrashAccepted) {
            $instrumentationContext = "stdout:`n$((Truncate-Text -Text $rawOut -MaxChars $failureOutputMaxChars))`nstderr:`n$((Truncate-Text -Text $rawErr -MaxChars $failureOutputMaxChars))"
            ThrowIf "Instrumentation reported a test failure for ${Label}. $instrumentationContext"
        }
        if (($instrumentProcess.ExitCode -ne 0) -and -not $nativeCrashAccepted) {
            if (-not [string]::IsNullOrWhiteSpace($rawErr)) {
                ThrowIf ('Instrumentation failed for ' + $Label + ': ' + $rawErr)
            }
            ThrowIf "Instrumentation failed for $Label with exit code $($instrumentProcess.ExitCode)."
        }
        if (-not $nativeCrashAccepted -and ($null -eq $result.cleanup -or $result.cleanup.remainingTestOwnedPaths.Count -ne 0)) {
            ThrowIf "Probe cleanup reported residue for ${Label}: $($result.cleanup.remainingTestOwnedPaths -join ', ')"
        }
        if ($nativeCrashAccepted) {
            $archiveResultClassification = 'incompatible'
        }
        else {
            if ($result.classification -notin $ManifestEntry.allowedClassifications) {
                ThrowIf "Unexpected classification '$($result.classification)' for $Label; expected one of $($ManifestEntry.allowedClassifications -join ', ')."
            }
            $archiveResultClassification = $result.classification
        }
        $runtimeCheckpointPhase = if ($null -ne $result.checkpointPhase) { $result.checkpointPhase } else { $null }

        if ($nativeCrashAccepted) {
            $observedPrivateSnapshot = @()
            $observedOutputSnapshot = @()
            $observedTmpSnapshot = @()
        }
        else {
            $observedPrivateSnapshot = Get-DevicePathPresence $privateInputPath 'run-as'
            $observedOutputSnapshot = Get-DevicePathPresence $externalResultPath
            $observedTmpSnapshot = Get-DevicePathPresence $tmpArchiveDir
        }
        $postRun = Get-Date
        $archiveResult = [pscustomobject]@{
            label = $Label
            safeLabel = $SafeLabel
            sha256 = $ArchiveSha
            runId = $runId
            passed = $result.passed
            startedAt = $preRun.ToUniversalTime().ToString('o')
            finishedAt = $postRun.ToUniversalTime().ToString('o')
            archiveBytes = $ArchiveBytes
            preOutputSnapshot = $preOutputSnapshot
            observedOutputSnapshot = $observedOutputSnapshot
            prePrivateSnapshot = $prePrivateSnapshot
            observedPrivateSnapshot = $observedPrivateSnapshot
            preTmpSnapshot = $preRunSnapshot
            observedTmpSnapshot = $observedTmpSnapshot
            installOutcome = $result.installOutcome
            ghostLoadOutcome = $result.ghostLoadOutcome
            renderOutcome = $result.renderOutcome
            inputOutcome = $result.inputOutcome
            shioriOutcome = $result.shioriOutcome
            dialogueOutcome = $dialogueOutcomeFromResult
            classification = $archiveResultClassification
            parserDiagnostics = $result.parserDiagnostics
            evidence = $result.evidence
            requiredEvidence = $ManifestEntry.requiredEvidence
            requiredEvidencePayload = $postRunResult
            resultPath = $resultJsonPath
            screenshotPath = $resultScreenshotPath
            allowedClassifications = $ManifestEntry.allowedClassifications
            crashLogPath = $crashLogLocal
            crashEvidence = $crashEvidence
            nativeCrash = $nativeCrashAccepted
            nativeCrashEvidence = $nativeCrashEvidence
            hostCleanupEvidence = $hostCleanupEvidence
            runtimeCheckpointPhase = $runtimeCheckpointPhase
            status = 'ok'
            statusText = 'PASS'
            exitCode = $instrumentProcess.ExitCode
            output = $rawOut
            error = $rawErr
            cleanup = if ((Has-Property -Object $result -Name 'cleanup') -and $null -ne $result.cleanup) { $result.cleanup } else { [pscustomobject]@{ remainingTestOwnedPaths = @() ; hostVerified = $false } }
        }
    }
    finally {
        if (Test-AdbTransportAvailable -TransportTimedOut $script:adbTransportTimedOut) {
            Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $targetPackage) -TimeoutSeconds 10 -AllowFailure | Out-Null
            Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $testPackage) -TimeoutSeconds 10 -AllowFailure | Out-Null
            if ($privateInputPath -like "$privateDataRoot/cache/nar-corpus-host/$runId/*") {
                Invoke-Adb -Arguments @('shell', 'run-as', $targetPackage, 'rm', '-rf', $privateInputPath) -TimeoutSeconds 20 -AllowFailure | Out-Null
            }
            Remove-RemotePath -Path $externalResultPath -TrimParents $true
            Remove-RemotePath -Path $tmpArchiveDir
            $postPrivateSnapshot = @(Get-DevicePathPresence $privateInputPath 'run-as')
            $postOutputSnapshot = @(Get-DevicePathPresence $externalResultPath)
            $postTmpSnapshot = @(Get-DevicePathPresence $tmpArchiveDir)
            if ($postPrivateSnapshot.Count -eq 0 -and $postOutputSnapshot.Count -eq 0 -and $postTmpSnapshot.Count -eq 0) {
                $hostCleanupEvidence = 'host cleanup completed; no remaining paths'
            }
            else {
                $hostCleanupEvidence = "host cleanup detected residue: private=$($postPrivateSnapshot -join ', '), external=$($postOutputSnapshot -join ', '), tmp=$($postTmpSnapshot -join ', ')"
                ThrowIf $hostCleanupEvidence
            }
        }
        else {
            $hostCleanupEvidence = "host cleanup not attempted after ADB transport timeout: $script:adbTransportTimeoutEvidence"
        }
    }

    if ($null -eq $archiveResult) {
        ThrowIf "Archive $Label completed without a structured result."
    }
    if (-not (Has-Property -Object $result -Name 'cleanup') -or $null -eq $result.cleanup) {
        $result | Add-Member -NotePropertyName cleanup -NotePropertyValue ([pscustomobject]@{}) -Force
    }
    if ($nativeCrashAccepted) {
        $result | Add-Member -NotePropertyName runtimeCheckpointPhase -NotePropertyValue 'before-real-shiori' -Force
        $result | Add-Member -NotePropertyName checkpointPhase -NotePropertyValue 'host-classified-native-crash' -Force
        $result | Add-Member -NotePropertyName classification -NotePropertyValue 'incompatible' -Force
        $result | Add-Member -NotePropertyName passed -NotePropertyValue $true -Force
        $result | Add-Member -NotePropertyName shioriOutcome -NotePropertyValue 'native-crash:libkawari8' -Force
        $result | Add-Member -NotePropertyName nativeCrashEvidence -NotePropertyValue $nativeCrashEvidence -Force
        $result | Add-Member -NotePropertyName crashEvidence -NotePropertyValue $crashEvidence -Force
        $result | Add-Member -NotePropertyName crashLogPath -NotePropertyValue $crashLogLocal -Force
        if (-not (Has-Property -Object $result -Name 'dialogueProbe') -or $null -eq $result.dialogueProbe) {
            $result | Add-Member -NotePropertyName dialogueProbe -NotePropertyValue ([pscustomobject]@{}) -Force
        }
        $result.dialogueProbe | Add-Member -NotePropertyName outcome -NotePropertyValue 'native-crash:libkawari8' -Force
        $result.cleanup | Add-Member -NotePropertyName remainingTestOwnedPaths -NotePropertyValue @() -Force
    }
    else {
        if (-not (Has-Property -Object $result -Name 'nativeCrashEvidence')) {
            $result | Add-Member -NotePropertyName nativeCrashEvidence -NotePropertyValue $nativeCrashEvidence -Force
        }
        else {
            $result.nativeCrashEvidence = $nativeCrashEvidence
        }
    }
    $result | Add-Member -NotePropertyName hostCleanupEvidence -NotePropertyValue $hostCleanupEvidence -Force
    $result.cleanup | Add-Member -NotePropertyName hostVerified -NotePropertyValue $true -Force
    Set-CanonicalArchiveCleanup -ArchiveResult $archiveResult -Result $result
    $dialogueOutcomeFromResult = if (
        $result.dialogueProbe -and
        (Has-Property -Object $result.dialogueProbe -Name 'outcome')
    ) {
        $result.dialogueProbe.outcome
    } else {
        $null
    }

    $archiveResult.shioriOutcome = $result.shioriOutcome
    $archiveResult.dialogueOutcome = $dialogueOutcomeFromResult
    $archiveResult.classification = $archiveResultClassification
    $archiveResult.runtimeCheckpointPhase = $runtimeCheckpointPhase

    $enrichedResultJson = ConvertTo-NarCorpusJson -Value $result
    Set-Content -Path $resultJsonLocal -Value $enrichedResultJson -Encoding UTF8
    if (-not $archiveResult.hostCleanupEvidence -or $archiveResult.hostCleanupEvidence -eq 'host cleanup not yet attempted') {
        $archiveResult | Add-Member -NotePropertyName hostCleanupEvidence -NotePropertyValue $hostCleanupEvidence -Force
    }
    $archiveResult | Add-Member -NotePropertyName postCleanupPrivateSnapshot -NotePropertyValue $postPrivateSnapshot
    $archiveResult | Add-Member -NotePropertyName postCleanupOutputSnapshot -NotePropertyValue $postOutputSnapshot
    $archiveResult | Add-Member -NotePropertyName postCleanupTmpSnapshot -NotePropertyValue $postTmpSnapshot
    return $archiveResult
}

$runStart = Get-Date
$manifestEntryName = [IO.Path]::GetFileName($ManifestPath)
$manifest = Read-JsonFile -Path (Join-Path $repoRoot $ManifestPath)
if ($manifest.schemaVersion -notlike '1*') {
    ThrowIf "Unsupported nar-corpus manifest schemaVersion $($manifest.schemaVersion)"
}
if (-not $manifest.entries -or $manifest.entries.Count -lt 1) {
    ThrowIf 'Manifest entries are empty.'
}
Validate-ManifestEntries -ManifestEntries $manifest.entries
$manifestSha = (Get-FileHash -Algorithm SHA256 -Path (Join-Path $repoRoot $ManifestPath)).Hash.ToLowerInvariant()

$resolvedCorpusRoots = Resolve-CorpusRoots -Roots $CorpusRoots
$archives = Collect-Archives -Roots $resolvedCorpusRoots
if (-not $archives -or $archives.Count -eq 0) {
    ThrowIf 'No .nar archives found in CorpusRoots.'
}

$missingManifest = [System.Collections.ArrayList]::new()
$unexpectedArchives = [System.Collections.ArrayList]::new()
$matchPlan = Validate-ManifestMatch -ManifestEntries $manifest.entries -Archives $archives -Missing ([ref]$missingManifest) -Unexpected ([ref]$unexpectedArchives)

if ($missingManifest.Count -gt 0 -or $unexpectedArchives.Count -gt 0) {
    Write-Host 'Manifest consistency check failed before execution.'
    Write-Host "Missing manifest entries: $($missingManifest.Count)"
    Write-Host ($missingManifest | ConvertTo-Json -Depth 5)
    Write-Host "Unexpected archives: $($unexpectedArchives.Count)"
    Write-Host ($unexpectedArchives | ConvertTo-Json -Depth 5)
    ThrowIf 'Abort: archive set does not match manifest hashes.'
}

if ($DryRun) {
    Write-Host 'Dry-run preflight validation passed.'
    Write-Host "Manifest entries: $($manifest.entries.Count)"
    Write-Host "Corpus archives discovered: $($archives.Count)"
    Write-Host "Resolved roots: $(@($resolvedCorpusRoots).Count)"
    $pwshPath = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
    if (-not $pwshPath) {
        ThrowIf 'Dry-run argument probe requires pwsh in PATH.'
    }
    $probeScript = New-HostTempFile '.argv-probe.ps1'
    @'
param([Parameter(ValueFromRemainingArguments = $true)] [string[]]$ProbeArgs)
foreach ($arg in $ProbeArgs) {
    Write-Output $arg
}
'@ | Set-Content -Path $probeScript -Encoding UTF8

    $probeExpected = @('label with spaces', "token`twith`ttabs", 'plain-token')
    $probeResult = Invoke-ArgumentListProcess -FilePath $pwshPath -Arguments (@(
        '-NoProfile',
        '-NoLogo',
        '-File',
        $probeScript
    ) + $probeExpected) -TimeoutSeconds 15
    $probeLines = @(
        $probeResult.output -split "`r`n|`n|`r" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
    if ($probeResult.exitCode -ne 0) {
        ThrowIf "Dry-run argv probe failed with exit code $($probeResult.exitCode)."
    }
    if ($probeLines.Count -ne $probeExpected.Count) {
        ThrowIf "Dry-run argv probe dropped arguments: expected $($probeExpected.Count), got $($probeLines.Count)."
    }
    for ($i = 0; $i -lt $probeExpected.Count; $i++) {
        if ($probeLines[$i] -ne $probeExpected[$i]) {
            ThrowIf "Dry-run argv probe mismatch at index $($i): expected '$($probeExpected[$i])', got '$($probeLines[$i])'."
        }
    }
    Write-Host 'Dry-run argv boundary probe passed.'

    $emptyDialogueProbe = [pscustomobject]@{}
    if ($null -ne $emptyDialogueProbe -and (Has-Property -Object $emptyDialogueProbe -Name 'outcome')) {
        ThrowIf 'Dry-run unsupported-result helper exposed outcome for empty dialogueProbe.'
    }
    $unsupportedResult = [pscustomobject]@{
        cleanup = [pscustomobject]@{}
        dialogueProbe = [pscustomobject]@{}
        label = 'unsupported with empty dialogueProbe'
        classification = 'incompatible'
        passed = $true
    }
    $unsupportedResultEvidence = Get-RequiredEvidenceValues -Result $unsupportedResult -RequiredEvidence @('cleanup', 'dialogueProbe', 'label', 'classification', 'passed')
    if ($unsupportedResultEvidence.Count -ne 5) {
        ThrowIf "Dry-run unsupported-result probe returned unexpected evidence count: $($unsupportedResultEvidence.Count)."
    }
    if (-not (Has-Property -Object $unsupportedResult -Name 'cleanup')) {
        ThrowIf 'Dry-run unsupported-result helper failed for cleanup property.'
    }
    if (-not (Has-Property -Object $unsupportedResult -Name 'dialogueProbe')) {
        ThrowIf 'Dry-run unsupported-result helper failed for dialogueProbe property.'
    }
    Write-Host 'Dry-run unsupported-result property probe passed.'

    $nestedProbe = [pscustomobject]@{
        outer = [pscustomobject]@{
            inner = [pscustomobject]@{
                number = 42
                values = @(
                    [pscustomobject]@{ label = 'first'; value = 1.25 },
                    [pscustomobject]@{ label = 'second'; value = 2.75 }
                )
            }
        }
    }
    $foundNested = $false
    $outerInnerNumber = Get-NestedPropertyValue -Object $nestedProbe -Path 'outer.inner.number' -Found ([ref]$foundNested)
    if (-not $foundNested -or $outerInnerNumber -ne 42) {
        ThrowIf 'Dry-run nested property access did not retrieve outer.inner.number.'
    }
    $foundMissing = $false
    $null = Get-NestedPropertyValue -Object $nestedProbe -Path 'outer.inner.missing' -Found ([ref]$foundMissing)
    if ($foundMissing) {
        ThrowIf 'Dry-run nested property access reported a missing path as found.'
    }
    $foundIndexedValue = $false
    $indexedValue = Get-NestedPropertyValue -Object $nestedProbe -Path 'outer.inner.values[1].label' -Found ([ref]$foundIndexedValue)
    if (-not $foundIndexedValue -or $indexedValue -ne 'second') {
        ThrowIf 'Dry-run nested indexed property access did not resolve expected element.'
    }
    if (-not (Compare-NumericWithTolerance -Expected 2.0 -Actual 2.001 -Tolerance 0.01)) {
        ThrowIf 'Dry-run numeric tolerance helper unexpectedly rejected near-equality.'
    }
    if (Compare-NumericWithTolerance -Expected 2.0 -Actual 2.2 -Tolerance 0.1) {
        ThrowIf 'Dry-run numeric tolerance helper unexpectedly accepted out-of-range values.'
    }

    $dryRunSentinelAccumulator = New-SentinelAccumulator
    Add-SentinelCheck -Accumulator $dryRunSentinelAccumulator -Name 'nested-property-access' -Passed $true -Expected 'nested lookup resolves value' -Observed $outerInnerNumber
    Add-SentinelCheck -Accumulator $dryRunSentinelAccumulator -Name 'numeric-tolerance' -Passed $true -Expected '2.0~2.001 tolerance .01' -Observed 'ok'
    if (-not $dryRunSentinelAccumulator.passed) {
        ThrowIf 'Dry-run sentinel helper check failed.'
    }
    Write-Host 'Dry-run helper sentinel probes passed.'

    $knownPresentationDiagnostics = @(
        'unsupported-command:*',
        'unsupported-command:*',
        'unsupported-command:*'
    )
    if (-not (Test-OnlyExpectedTokenizerDiagnostics -Diagnostics $knownPresentationDiagnostics)) {
        ThrowIf 'Dry-run tokenizer diagnostic probe rejected known unsupported presentation markers.'
    }
    if (Test-OnlyExpectedTokenizerDiagnostics -Diagnostics @('unsupported-command:*', 'truncated-command')) {
        ThrowIf 'Dry-run tokenizer diagnostic probe accepted an unexpected parser diagnostic.'
    }

    $dryRunCheckpointResult = [pscustomobject]@{
        cleanup = [pscustomobject]@{
            remainingTestOwnedPaths = @()
            hostVerified = $true
        }
    }
    $dryRunSummaryRow = [pscustomobject]@{
        cleanup = [pscustomobject]@{
            remainingTestOwnedPaths = @()
            hostVerified = $false
        }
    }
    Set-CanonicalArchiveCleanup -ArchiveResult $dryRunSummaryRow -Result $dryRunCheckpointResult
    if (-not [object]::ReferenceEquals($dryRunSummaryRow.cleanup, $dryRunCheckpointResult.cleanup)) {
        ThrowIf 'Dry-run cleanup probe retained a stale summary cleanup object.'
    }
    if (-not $dryRunSummaryRow.cleanup.hostVerified) {
        ThrowIf 'Dry-run cleanup probe did not preserve host verification.'
    }
    Write-Host 'Dry-run result normalization probes passed.'

    $explicitKawariCrashEntry = [pscustomobject]@{
        allowedClassifications = @('compatible', 'incompatible')
        allowNativeKawariCrash = $true
    }
    $genericIncompatibleEntry = [pscustomobject]@{
        allowedClassifications = @('compatible', 'incompatible')
    }
    if (-not (Test-NativeKawariCrashAllowed -ManifestEntry $explicitKawariCrashEntry)) {
        ThrowIf 'Dry-run native-crash probe rejected an explicitly allowlisted Kawari row.'
    }
    if (Test-NativeKawariCrashAllowed -ManifestEntry $genericIncompatibleEntry) {
        ThrowIf 'Dry-run native-crash probe accepted a generic incompatible-capable row.'
    }
    if (Test-AdbTransportAvailable -TransportTimedOut $true) {
        ThrowIf 'Dry-run ADB transport probe allowed commands after a transport timeout.'
    }
    if (-not (Test-AdbTransportAvailable -TransportTimedOut $false)) {
        ThrowIf 'Dry-run ADB transport probe rejected a healthy transport.'
    }
    $script:adbTransportTimedOut = $true
    $script:adbTransportTimeoutEvidence = 'dry-run simulated transport deadline'
    try {
        try {
            Invoke-Adb -Arguments @('version') | Out-Null
            ThrowIf 'Dry-run ADB cutoff probe unexpectedly launched a command.'
        }
        catch {
            if ($_.Exception.Message -notlike 'adb-timeout: ADB transport disabled after timeout:*') {
                throw
            }
        }
    }
    finally {
        $script:adbTransportTimedOut = $false
        $script:adbTransportTimeoutEvidence = $null
    }
    Write-Host 'Dry-run crash allowlist and transport cutoff probes passed.'

    $dryRunMarkdownHeader = Get-NarCorpusSentinelMarkdownHeader
    if ($dryRunMarkdownHeader -ne "| Name | Passed | Expected | Observed | Detail |$([Environment]::NewLine)| --- | --- | --- | --- | --- |") {
        ThrowIf 'Dry-run Markdown probe did not render the sentinel header on two lines.'
    }
    $dryRunManifestSha = 'manifest-sha-probe'
    $dryRunSentinels = [pscustomobject]@{ passed = $true }
    $dryRunMarkdownMetadata = @"
- Manifest: manifest.json ($dryRunManifestSha)
- Sentinel checks passed: $($dryRunSentinels.passed)
"@
    if ($dryRunMarkdownMetadata -notmatch [regex]::Escape('(manifest-sha-probe)') -or
        $dryRunMarkdownMetadata -notmatch [regex]::Escape('Sentinel checks passed: True')) {
        ThrowIf 'Dry-run Markdown probe did not interpolate summary metadata.'
    }
    Write-Host 'Dry-run Markdown rendering probe passed.'

    $dryRunNestedEvidence = [pscustomobject]@{
        level1 = [pscustomobject]@{
            level2 = [pscustomobject]@{
                level3 = [pscustomobject]@{
                    level4 = [pscustomobject]@{
                        level5 = [pscustomobject]@{
                            level6 = [pscustomobject]@{
                                level7 = [pscustomobject]@{
                                    level8 = [pscustomobject]@{
                                        level9 = [pscustomobject]@{ leaf = 'preserved' }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    $dryRunNestedRoundTrip = ConvertTo-NarCorpusJson -Value $dryRunNestedEvidence | ConvertFrom-Json
    if ($dryRunNestedRoundTrip.level1.level2.level3.level4.level5.level6.level7.level8.level9.leaf -ne 'preserved') {
        ThrowIf 'Dry-run JSON probe truncated nested evidence.'
    }
    Write-Host 'Dry-run JSON depth probe passed.'

    Remove-Item -LiteralPath $hostRunTmpRoot -Recurse -Force -ErrorAction SilentlyContinue
    return
}

Clear-RunArtifacts -FailuresRoot $failuresRoot -SummaryPathRoot $reportRoot

$AdbPath = Resolve-AdbPath
Ensure-ExecutableExists -Path $AdbPath -Purpose 'ADB'

$installed = $false
    $networkState = $null
    $apkInfo = $null
    try {
    Check-DeviceGate
    Validate-NoPreexistingDeviceState
    $apkInfo = Build-Apks

    $apksigner = Resolve-ApkSigner
    Verify-DebugSignature -ApkPath $apkInfo.DebugApkPath -Label 'debug' -ApkSignerPath $apksigner | Out-Null
    Verify-DebugSignature -ApkPath $apkInfo.TestApkPath -Label 'androidTest' -ApkSignerPath $apksigner | Out-Null

    Write-Host "Installing $(Split-Path -Leaf $apkInfo.DebugApkPath)"
    Invoke-Adb -Arguments @('install', '-r', '-d', '-g', $apkInfo.DebugApkPath) -TimeoutSeconds 600 | Out-Null
    $installed = $true
    Write-Host "Installing $(Split-Path -Leaf $apkInfo.TestApkPath)"
    Invoke-Adb -Arguments @('install', '-r', '-d', '-g', $apkInfo.TestApkPath) -TimeoutSeconds 600 | Out-Null
    Verify-RunAs
    $privateDataRoot = (Invoke-Adb -Arguments @('shell', 'run-as', $targetPackage, 'pwd') -TimeoutSeconds 20).Trim()
    if ($privateDataRoot -notmatch "^/data/(user/0|data)/$([regex]::Escape($targetPackage))$") {
        ThrowIf "Unexpected run-as private root '$privateDataRoot'."
    }

    $deviceFingerprint = Get-AdbProperty 'ro.build.fingerprint'
    $deviceDensity = Get-DeviceDensity
    $deviceAbi = Get-AdbProperty 'ro.product.cpu.abi'
    $deviceApi = Get-AdbProperty 'ro.build.version.sdk'

    Check-DeviceStorage

    $networkState = Snapshot-NetworkState
    Set-NetworkState -Disable $true

    $results = [System.Collections.ArrayList]::new()
    $failures = [System.Collections.ArrayList]::new()
    $abortedDueToTimeout = $false
    $unexpectedAbort = $false
    $unexpectedAbortMetadata = $null
    $abortedEntryLabel = $null
    $cleanupVerification = 'verified'

    foreach ($entry in $manifest.entries) {
        $archive = $matchPlan.ArchiveByHash[$entry.sha256.ToLowerInvariant()]
        $safeLabel = Sanitize-Label -Label $entry.label
        $archivePath = $archive.path
        try {
            $privateArchivePath = "$privateDataRoot/cache/nar-corpus-host/$runId/$safeLabel/$constantFileName"
            $result = Run-TestArchive -ArchivePath $archivePath -ArchiveSha $archive.sha256 -Label $entry.label -ArchiveBytes $archive.bytes -SafeLabel $safeLabel -ManifestEntry $entry -PrivateArchivePath $privateArchivePath
            $results.Add($result) | Out-Null
            if ($result.classification -ne 'incompatible') {
                Write-Host "PASS: $($entry.label) => $($result.classification)"
            }
            else {
                Write-Host "INCOMPATIBLE: $($entry.label)"
            }
        }
        catch {
            $failureReason = $_.Exception.Message
            $failureMessage = "Archive failed: $($_.Exception.Message)"
            $failurePath = Join-Path $failuresRoot "$safeLabel.txt"
            $_.Exception | Out-File -FilePath $failurePath -Encoding UTF8
            $errorResult = [pscustomobject]@{
                label = $entry.label
                safeLabel = $safeLabel
                sha256 = $archive.sha256
                classification = 'error'
                passed = $false
                installOutcome = 'not-run'
                ghostLoadOutcome = 'not-run'
                renderOutcome = 'not-run'
                inputOutcome = 'not-run'
                shioriOutcome = 'not-run'
                screenshotPath = $null
                cleanup = [pscustomobject]@{ remainingTestOwnedPaths = @('not-run'); hostVerified = $false }
                status = 'error'
                statusText = $failureMessage
                output = $failureReason
                error = $failureReason
                startedAt = (Get-Date).ToUniversalTime().ToString('o')
                finishedAt = (Get-Date).ToUniversalTime().ToString('o')
                postCleanupPrivateSnapshot = @('not-run')
                postCleanupOutputSnapshot = @('not-run')
                postCleanupTmpSnapshot = @('not-run')
            }
            $results.Add($errorResult) | Out-Null
            $failures.Add($failurePath) | Out-Null
            Write-Host "ERROR: $($entry.label) failed"
            if ($script:adbTransportTimedOut) {
                $abortedDueToTimeout = $true
                $abortedEntryLabel = $entry.label
                Write-Host "TIMEOUT: aborting after $($entry.label)"
                break
            }
            else {
                $unexpectedAbort = $true
                $unexpectedAbortMetadata = [pscustomobject]@{
                    type = 'unexpected-archive-error'
                    label = $entry.label
                    safeLabel = $safeLabel
                    reason = $failureReason
                    message = $failureMessage
                }
                break
            }
        }
    }

    if ($abortedDueToTimeout) {
        $cleanupVerification = 'not-verified-device-timeout'
        Write-Host "SKIP: timeout-aborted run; skipping device cleanup+verification steps before summary."
    }
    else {
        Invoke-Adb -Arguments @('shell', 'run-as', $targetPackage, 'rm', '-rf', "cache/nar-corpus-host/$runId") -TimeoutSeconds 20 -AllowFailure | Out-Null
        Remove-RemotePath -Path "/sdcard/Android/data/$targetPackage/files/nar-corpus" -TrimParents $true
        Remove-RemotePath -Path $tmpRunSafeRoot -TrimParents $true
        Assert-DirectoryCleared -Path $tmpRunRoot
        Assert-DirectoryCleared -Path "/sdcard/Android/data/$targetPackage/files/nar-corpus"
    }

    $expectedResultCount = $manifest.entries.Count
    $globalSentinels = New-SentinelAccumulator
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'result-count' -Passed ($results.Count -eq $expectedResultCount) -Expected $expectedResultCount -Observed $results.Count -Detail "Expected $expectedResultCount result rows."
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'zero-failures' -Passed ($failures.Count -eq 0) -Expected 0 -Observed $failures.Count -Detail 'Expected no archive execution failures.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'no-abort' -Passed (-not $abortedDueToTimeout -and -not $unexpectedAbort) -Expected 'false/false' -Observed "$abortedDueToTimeout/$unexpectedAbort" -Detail 'Expected run to complete without timeout and without unexpected abort.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'cleanup-verified' -Passed ($cleanupVerification -eq 'verified') -Expected 'verified' -Observed $cleanupVerification

    $twoElfSummaryRows = @($results | Where-Object { $_.label -eq '2elf-2.46' })
    $twoElfSummaryRow = if ($twoElfSummaryRows.Count -eq 1) { $twoElfSummaryRows[0] } else { $null }
    $twoElfSummaryResultPath = Get-NestedPropertyValue -Object $twoElfSummaryRow -Path 'resultPath'
    $twoElfSummarySafeLabel = Get-NestedPropertyValue -Object $twoElfSummaryRow -Path 'safeLabel'
    $twoElfResult = Read-ResultJsonFromSummaryRow -ResultRow $twoElfSummaryRow
    $twoElfNamedCollisionCount = if ($null -ne $twoElfResult -and (Has-Property -Object $twoElfResult -Name 'namedCollisionProbes')) {
        @($twoElfResult.namedCollisionProbes).Count
    } else {
        $null
    }
    $twoElfFaceProbes = @()
    if ($twoElfNamedCollisionCount -gt 0) {
        $twoElfFaceProbes = @(
            $twoElfResult.namedCollisionProbes | Where-Object {
                $_.speaker -eq 'SAKURA' -and
                $_.surfaceId -eq 0 -and
                $_.authoredIdentifier -eq 'Face'
            }
        )
    }
    $foundTwoElfFaceProbe = $twoElfFaceProbes.Count -eq 1
    $twoElfFaceProbe = if ($foundTwoElfFaceProbe) { $twoElfFaceProbes[0] } else { $null }
    $twoElfFaceAuthoredId = Get-NestedPropertyValue -Object $twoElfFaceProbe -Path 'authoredId'
    $twoElfFaceShapeKind = Get-NestedPropertyValue -Object $twoElfFaceProbe -Path 'shapeKind'
    $twoElfFaceOverlayContainsPoint = Get-NestedPropertyValue -Object $twoElfFaceProbe -Path 'overlayContainsPoint'
    $twoElfFaceOverlayExact = Get-NestedPropertyValue -Object $twoElfFaceProbe -Path 'overlayExact'
    $twoElfFaceIntendedCollisionWasDirectlyHit = Get-NestedPropertyValue -Object $twoElfFaceProbe -Path 'intendedCollisionWasDirectlyHit'
    $twoElfFaceResolutionOutcome = Get-NestedPropertyValue -Object $twoElfFaceProbe -Path 'resolutionOutcome'
    $twoElfFaceHitIdentifier = Get-NestedPropertyValue -Object $twoElfFaceProbe -Path 'hitIdentifier'
    $twoElfFaceRoutedId = Get-NestedPropertyValue -Object $twoElfFaceProbe -Path 'routedCollision.routedId'
    $twoElfFaceRoutedIdentifier = Get-NestedPropertyValue -Object $twoElfFaceProbe -Path 'routedCollision.routedIdentifier'
    $twoElfFaceEffectCollisionIdentifier = Get-NestedPropertyValue -Object $twoElfFaceProbe -Path 'effect.collisionIdentifier'
    $twoElfFaceEffectRef4 = Get-NestedPropertyValue -Object $twoElfFaceProbe -Path 'effect.references[4]'
    $twoElfFaceEffectReferences = Get-NestedPropertyValue -Object $twoElfFaceProbe -Path 'effect.references'
    $twoElfFaceEffectReferenceCount = if ($null -ne $twoElfFaceEffectReferences) { @($twoElfFaceEffectReferences).Count } else { $null }
    $twoElfDialogueMethod = Get-NestedPropertyValue -Object $twoElfResult -Path 'dialogueProbe.method'
    $twoElfDialogueValue = Get-NestedPropertyValue -Object $twoElfResult -Path 'dialogueProbe.value'
    $twoElfDialogueFailure = Get-NestedPropertyValue -Object $twoElfResult -Path 'dialogueProbe.failure'
    $twoElfDialogueTokenizerDiagnostics = Get-NestedPropertyValue -Object $twoElfResult -Path 'dialogueProbe.tokenizerDiagnostics'
    $twoElfDialogueValueText = if ($null -eq $twoElfDialogueValue) { '' } else { [string]$twoElfDialogueValue }
    $twoElfDialogueTokenizerCount = if ($null -ne $twoElfDialogueTokenizerDiagnostics) { @($twoElfDialogueTokenizerDiagnostics).Count } else { $null }
    $twoElfDialogueValueNonBlank = -not [string]::IsNullOrWhiteSpace($twoElfDialogueValueText)

   $nanikaSummaryRows = @($results | Where-Object { $_.label -eq 'Nanika Atsume 1.0.1' })
    $nanikaSummaryRow = if ($nanikaSummaryRows.Count -eq 1) { $nanikaSummaryRows[0] } else { $null }
    $nanikaSummaryResultPath = Get-NestedPropertyValue -Object $nanikaSummaryRow -Path 'resultPath'
    $nanikaSummarySafeLabel = Get-NestedPropertyValue -Object $nanikaSummaryRow -Path 'safeLabel'
    $nanikaResult = Read-ResultJsonFromSummaryRow -ResultRow $nanikaSummaryRow
    $nanikaSourceSyntaxEvidence = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.sourceSyntax'
    $nanikaSourceSyntaxScanErrorFound = $false
    $nanikaSourceSyntaxScanError = Get-NestedPropertyValue -Object $nanikaSourceSyntaxEvidence -Path 'scanError' -Found ([ref]$nanikaSourceSyntaxScanErrorFound)
    $nanikaSourceSyntaxFilesTruncated = Get-NestedPropertyValue -Object $nanikaSourceSyntaxEvidence -Path 'filesTruncated'
    $nanikaSourceSyntaxBytesTruncated = Get-NestedPropertyValue -Object $nanikaSourceSyntaxEvidence -Path 'bytesTruncated'
    $nanikaSourceSyntaxSelectorIdsTruncated = Get-NestedPropertyValue -Object $nanikaSourceSyntaxEvidence -Path 'surfaceSelectorIdsTruncated'
    $nanikaSourceSyntaxHasRangeSelectors = Get-NestedPropertyValue -Object $nanikaSourceSyntaxEvidence -Path 'hasRangeSelectors'
    $nanikaSourceSyntaxHasExclusionSelectors = Get-NestedPropertyValue -Object $nanikaSourceSyntaxEvidence -Path 'hasExclusionSelectors'
    $nanikaSourceSyntaxSurfaceKeysIncluded = As-NonNullArray -Value (Get-NestedPropertyValue -Object $nanikaSourceSyntaxEvidence -Path 'surfaceKeys.included')
    $nanikaSourceSyntaxSurfaceKeysExcluded = As-NonNullArray -Value (Get-NestedPropertyValue -Object $nanikaSourceSyntaxEvidence -Path 'surfaceKeys.excluded')
    $nanikaSourceSyntaxSurfaceKeysIncludedIds = @()
    foreach ($surfaceKey in @($nanikaSourceSyntaxSurfaceKeysIncluded)) {
        $surfaceKeyText = Get-NestedPropertyValue -Object $surfaceKey -Path 'surfaceId'
        $surfaceKeyId = 0
        if ($null -eq $surfaceKeyText) {
            if ([int]::TryParse([string]$surfaceKey, [ref]$surfaceKeyId)) {
                $nanikaSourceSyntaxSurfaceKeysIncludedIds += $surfaceKeyId
            }
        }
        elseif ([int]::TryParse([string]$surfaceKeyText, [ref]$surfaceKeyId)) {
            $nanikaSourceSyntaxSurfaceKeysIncludedIds += $surfaceKeyId
        }
    }
    $nanikaSourceSyntaxSurfaceKeysExcludedIds = @()
    foreach ($surfaceKey in @($nanikaSourceSyntaxSurfaceKeysExcluded)) {
        $surfaceKeyText = Get-NestedPropertyValue -Object $surfaceKey -Path 'surfaceId'
        $surfaceKeyId = 0
        if ($null -eq $surfaceKeyText) {
            if ([int]::TryParse([string]$surfaceKey, [ref]$surfaceKeyId)) {
                $nanikaSourceSyntaxSurfaceKeysExcludedIds += $surfaceKeyId
            }
        }
        elseif ([int]::TryParse([string]$surfaceKeyText, [ref]$surfaceKeyId)) {
            $nanikaSourceSyntaxSurfaceKeysExcludedIds += $surfaceKeyId
        }
    }
    $nanikaSurfaceKeys = As-NonNullArray -Value (Get-NestedPropertyValue -Object $nanikaResult -Path 'surfaceKeys')
    $nanikaSurfaceCount = Get-NestedPropertyValue -Object $nanikaResult -Path 'surfaceCount'
    $nanikaSurfaceKeyCount = $nanikaSurfaceKeys.Count
    $nanikaSurfaceKeysInRuntimeRange = @()
    foreach ($surfaceKey in @($nanikaSurfaceKeys)) {
        $surfaceKeyText = [string]$surfaceKey
        $surfaceKeyValue = 0
        if (-not [int]::TryParse($surfaceKeyText, [ref]$surfaceKeyValue)) { continue }
        if ($surfaceKeyValue -ge 6000 -and $surfaceKeyValue -le 6559) {
            $nanikaSurfaceKeysInRuntimeRange += $surfaceKeyValue
        }
    }
    $nanikaSourceSyntaxScanOk = (-not $nanikaSourceSyntaxScanErrorFound) -or ($null -eq $nanikaSourceSyntaxScanError) -or ($nanikaSourceSyntaxScanError -eq $false)
    $nanikaExpectedIncludedSurfaceKeys = @(6000, 6002, 6003, 6005, 6559)
    $nanikaExpectedExcludedSurfaceKeys = @(6001, 6004, 6021, 6024, 6551, 6554)
    $nanikaExpectedIncludedSurfaceKeysFound = $true
    foreach ($expectedSurfaceKey in @($nanikaExpectedIncludedSurfaceKeys)) {
        if (-not ($nanikaSourceSyntaxSurfaceKeysIncludedIds -contains $expectedSurfaceKey)) {
            $nanikaExpectedIncludedSurfaceKeysFound = $false
            break
        }
    }
    $nanikaExpectedExcludedSurfaceKeysFound = $true
    foreach ($expectedSurfaceKey in @($nanikaExpectedExcludedSurfaceKeys)) {
        if (-not ($nanikaSourceSyntaxSurfaceKeysExcludedIds -contains $expectedSurfaceKey)) {
            $nanikaExpectedExcludedSurfaceKeysFound = $false
            break
        }
    }
    $nanikaRuntimeExpectedIncludedSurfaceKeys = @()
    foreach ($expectedSurfaceKey in @($nanikaExpectedIncludedSurfaceKeys)) {
        if ($nanikaSurfaceKeys -contains $expectedSurfaceKey) {
            $nanikaRuntimeExpectedIncludedSurfaceKeys += $expectedSurfaceKey
        }
    }
    $nanikaRuntimeExpectedExcludedSurfaceKeys = @()
    foreach ($expectedSurfaceKey in @($nanikaExpectedExcludedSurfaceKeys)) {
        if ($nanikaSurfaceKeys -contains $expectedSurfaceKey) {
            $nanikaRuntimeExpectedExcludedSurfaceKeys += $expectedSurfaceKey
        }
    }
    $nanikaRuntimeSurfaceKeysInExpectedIncludedObserved = ($nanikaRuntimeExpectedIncludedSurfaceKeys -join ',')
    $nanikaRuntimeSurfaceKeysInExpectedExcludedObserved = ($nanikaRuntimeExpectedExcludedSurfaceKeys -join ',')
    $nanikaExpectedIncludedSurfaceKeysFoundInRuntime = $true
    $nanikaExpectedExcludedSurfaceKeysAbsentInRuntime = $true
    foreach ($expectedSurfaceKey in @($nanikaExpectedIncludedSurfaceKeys)) {
        if (-not ($nanikaSurfaceKeys -contains $expectedSurfaceKey)) {
            $nanikaExpectedIncludedSurfaceKeysFoundInRuntime = $false
            break
        }
    }
    foreach ($expectedSurfaceKey in @($nanikaExpectedExcludedSurfaceKeys)) {
        if ($nanikaSurfaceKeys -contains $expectedSurfaceKey) {
            $nanikaExpectedExcludedSurfaceKeysAbsentInRuntime = $false
            break
        }
    }
    $nanikaSourceSyntaxSurfaceKeysIncludedObserved = ($nanikaSourceSyntaxSurfaceKeysIncludedIds | Sort-Object | ForEach-Object { $_ }) -join ','
    $nanikaSourceSyntaxSurfaceKeysExcludedObserved = ($nanikaSourceSyntaxSurfaceKeysExcludedIds | Sort-Object | ForEach-Object { $_ }) -join ','
    $nanikaSakuraIntrinsicWidth = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.intrinsic.width'
    $nanikaSakuraIntrinsicHeight = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.intrinsic.height'
    $nanikaSakuraRenderedLeft = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.renderedBounds.left'
    $nanikaSakuraRenderedTop = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.renderedBounds.top'
    $nanikaSakuraRenderedRight = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.renderedBounds.right'
    $nanikaSakuraRenderedBottom = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.renderedBounds.bottom'
    $nanikaSakuraVisiblePixelLeft = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.visiblePixelBounds.left'
    $nanikaSakuraVisiblePixelTop = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.visiblePixelBounds.top'
    $nanikaSakuraVisiblePixelRight = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.visiblePixelBounds.right'
    $nanikaSakuraVisiblePixelBottom = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.visiblePixelBounds.bottom'
    $nanikaSakuraVisiblePixelBounds = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.visiblePixelBounds'
    $nanikaSakuraOpticalLeft = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.opticalBounds.left'
    $nanikaSakuraOpticalTop = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.opticalBounds.top'
    $nanikaSakuraOpticalRight = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.opticalBounds.right'
    $nanikaSakuraOpticalBottom = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.opticalBounds.bottom'
    $nanikaSakuraOpticalBounds = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.sakura.opticalBounds'
    $nanikaKeroCollisionCount = Get-NestedPropertyValue -Object $nanikaResult -Path 'kero.collisionCount'
    $nanikaKeroIntrinsicWidth = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.kero.intrinsic.width'
    $nanikaKeroIntrinsicHeight = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.kero.intrinsic.height'
    $nanikaKeroVisiblePixelBoundsFound = $false
    $nanikaKeroVisiblePixelBounds = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.kero.visiblePixelBounds' -Found ([ref]$nanikaKeroVisiblePixelBoundsFound)
    $nanikaProductionStageKero = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.kero'
    $nanikaLayoutSakuraSurfaceWidth = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.layoutDp.sakuraSurface.width'
    $nanikaLayoutKeroSurfaceWidth = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.layoutDp.keroSurface.width'
    $nanikaSharedAuthoredScale = Get-NestedPropertyValue -Object $nanikaResult -Path 'evidence.productionStage.layoutDp.sizingBaseline.sharedAuthoredScale'
    $nanikaSakuraLayoutWidthRatio = if (($null -ne $nanikaLayoutSakuraSurfaceWidth) -and (0 -ne $nanikaLayoutSakuraSurfaceWidth)) { $nanikaLayoutSakuraSurfaceWidth / 93 } else { $null }
    $nanikaKeroLayoutWidthRatio = if (($null -ne $nanikaLayoutKeroSurfaceWidth) -and (0 -ne $nanikaLayoutKeroSurfaceWidth)) { $nanikaLayoutKeroSurfaceWidth / 200 } else { $null }
    $nanikaSakuraSurfaceRatioMatchesSharedScale = (
        $null -ne $nanikaSharedAuthoredScale -and
        $null -ne $nanikaSakuraLayoutWidthRatio -and
        (Compare-NumericWithTolerance -Expected ($nanikaSharedAuthoredScale * 2) -Actual $nanikaSakuraLayoutWidthRatio -Tolerance 0.02)
    )
    $nanikaKeroSurfaceRatioMatchesSharedScale = (
        $null -ne $nanikaSharedAuthoredScale -and
        $null -ne $nanikaKeroLayoutWidthRatio -and
        (Compare-NumericWithTolerance -Expected $nanikaSharedAuthoredScale -Actual $nanikaKeroLayoutWidthRatio -Tolerance 0.02)
    )
    $nanikaSakuraSurfaceWithinRenderedBounds = (
        $null -ne $nanikaSakuraOpticalBounds -and
        $null -ne $nanikaSakuraRenderedLeft -and
        $null -ne $nanikaSakuraRenderedTop -and
        $null -ne $nanikaSakuraRenderedRight -and
        $null -ne $nanikaSakuraRenderedBottom -and
        $nanikaSakuraOpticalLeft -ge $nanikaSakuraRenderedLeft -and
        $nanikaSakuraOpticalTop -ge $nanikaSakuraRenderedTop -and
        $nanikaSakuraOpticalRight -le $nanikaSakuraRenderedRight -and
        $nanikaSakuraOpticalBottom -le $nanikaSakuraRenderedBottom
    )

    $watchdogSummaryRows = @($results | Where-Object { $_.label -eq 'Watchdog Bancho' })
    $watchdogSummaryRow = if ($watchdogSummaryRows.Count -eq 1) { $watchdogSummaryRows[0] } else { $null }
    $watchdogSummaryResultPath = Get-NestedPropertyValue -Object $watchdogSummaryRow -Path 'resultPath'
    $watchdogSummarySafeLabel = Get-NestedPropertyValue -Object $watchdogSummaryRow -Path 'safeLabel'
    $watchdogResult = Read-ResultJsonFromSummaryRow -ResultRow $watchdogSummaryRow
    $watchdogProbes = As-NonNullArray -Value (Get-NestedPropertyValue -Object $watchdogResult -Path 'namedCollisionProbes')
    $watchdogExpectedProbes = @(
        [pscustomobject]@{ id = 0; name = 'Head' },
        [pscustomobject]@{ id = 1; name = 'Forehead' },
        [pscustomobject]@{ id = 2; name = 'Mouth' },
        [pscustomobject]@{ id = 3; name = 'Ear' },
        [pscustomobject]@{ id = 4; name = 'Ear' },
        [pscustomobject]@{ id = 5; name = 'Collar' },
        [pscustomobject]@{ id = 6; name = 'Bust' },
        [pscustomobject]@{ id = 7; name = 'Hand' },
        [pscustomobject]@{ id = 8; name = 'Hand' },
        [pscustomobject]@{ id = 9; name = 'Stomach' },
        [pscustomobject]@{ id = 11; name = 'Leg' },
        [pscustomobject]@{ id = 12; name = 'Tail' }
    )
    $watchdogProbeMismatches = [System.Collections.ArrayList]::new()
    if ($watchdogProbes.Count -eq $watchdogExpectedProbes.Count) {
        for ($probeIndex = 0; $probeIndex -lt $watchdogExpectedProbes.Count; $probeIndex++) {
            $expectedProbe = $watchdogExpectedProbes[$probeIndex]
            $probe = $watchdogProbes[$probeIndex]
            $probePrefix = "index=$probeIndex,id=$($expectedProbe.id),name=$($expectedProbe.name)"
            $probePoints = As-NonNullArray -Value (Get-NestedPropertyValue -Object $probe -Path 'authoredGeometry.points')
            $probeReferences = As-NonNullArray -Value (Get-NestedPropertyValue -Object $probe -Path 'effect.references')
            $probeChecks = [ordered]@{
                speaker = ((Get-NestedPropertyValue -Object $probe -Path 'speaker') -eq 'SAKURA')
                surfaceId = ((Get-NestedPropertyValue -Object $probe -Path 'surfaceId') -eq 0)
                authoredId = ((Get-NestedPropertyValue -Object $probe -Path 'authoredId') -eq $expectedProbe.id)
                authoredIdentifier = ((Get-NestedPropertyValue -Object $probe -Path 'authoredIdentifier') -eq $expectedProbe.name)
                shapeKind = ((Get-NestedPropertyValue -Object $probe -Path 'shapeKind') -eq 'polygon')
                geometryKind = ((Get-NestedPropertyValue -Object $probe -Path 'authoredGeometry.kind') -eq 'polygon')
                geometryPoints = ($probePoints.Count -ge 3)
                geometryBoundsWidth = ((Get-NestedPropertyValue -Object $probe -Path 'authoredGeometry.bounds.width') -gt 0)
                geometryBoundsHeight = ((Get-NestedPropertyValue -Object $probe -Path 'authoredGeometry.bounds.height') -gt 0)
                representable = ((Get-NestedPropertyValue -Object $probe -Path 'representable') -eq $true)
                overlayContainsPoint = ((Get-NestedPropertyValue -Object $probe -Path 'overlayContainsPoint') -eq $true)
                overlayExact = ((Get-NestedPropertyValue -Object $probe -Path 'overlayExact') -eq $true)
                intendedDirectHit = ((Get-NestedPropertyValue -Object $probe -Path 'intendedCollisionWasDirectlyHit') -eq $true)
                resolutionOutcome = ((Get-NestedPropertyValue -Object $probe -Path 'resolutionOutcome') -eq 'direct-hit')
                hitIdentifier = ((Get-NestedPropertyValue -Object $probe -Path 'hitIdentifier') -eq $expectedProbe.name)
                routedId = ((Get-NestedPropertyValue -Object $probe -Path 'routedCollision.routedId') -eq $expectedProbe.id)
                routedIdentifier = ((Get-NestedPropertyValue -Object $probe -Path 'routedCollision.routedIdentifier') -eq $expectedProbe.name)
                effectCollisionIdentifier = ((Get-NestedPropertyValue -Object $probe -Path 'effect.collisionIdentifier') -eq $expectedProbe.name)
                effectDiagnosticCollisionId = ((Get-NestedPropertyValue -Object $probe -Path 'effect.diagnosticCollisionId') -eq $expectedProbe.id)
                effectReference4 = ($probeReferences.Count -gt 4 -and $probeReferences[4] -eq $expectedProbe.name)
            }
            foreach ($probeCheck in $probeChecks.GetEnumerator()) {
                if (-not $probeCheck.Value) {
                    $watchdogProbeMismatches.Add("${probePrefix}:$($probeCheck.Key)") | Out-Null
                }
            }
        }
    }
    else {
        $watchdogProbeMismatches.Add("probe-count=$($watchdogProbes.Count),expected=$($watchdogExpectedProbes.Count)") | Out-Null
    }
    $watchdogSakuraIntrinsicWidth = Get-NestedPropertyValue -Object $watchdogResult -Path 'evidence.productionStage.sakura.intrinsic.width'
    $watchdogSakuraIntrinsicHeight = Get-NestedPropertyValue -Object $watchdogResult -Path 'evidence.productionStage.sakura.intrinsic.height'
    $watchdogKeroIntrinsicWidth = Get-NestedPropertyValue -Object $watchdogResult -Path 'evidence.productionStage.kero.intrinsic.width'
    $watchdogKeroIntrinsicHeight = Get-NestedPropertyValue -Object $watchdogResult -Path 'evidence.productionStage.kero.intrinsic.height'
    $watchdogProductionKero = Get-NestedPropertyValue -Object $watchdogResult -Path 'evidence.productionStage.kero'
    $watchdogSakuraSurfaceWidth = Get-NestedPropertyValue -Object $watchdogResult -Path 'evidence.productionStage.layoutDp.sakuraSurface.width'
    $watchdogSakuraSurfaceRegionWidth = Get-NestedPropertyValue -Object $watchdogResult -Path 'evidence.productionStage.layoutDp.sakuraSurfaceRegion.width'
    $watchdogSharedAuthoredScale = Get-NestedPropertyValue -Object $watchdogResult -Path 'evidence.productionStage.layoutDp.sizingBaseline.sharedAuthoredScale'
    $watchdogSakuraWidthRatio = if ($null -ne $watchdogSakuraSurfaceWidth) { $watchdogSakuraSurfaceWidth / 427 } else { $null }
    $watchdogSakuraRatioMatchesSharedScale = (
        $null -ne $watchdogSakuraWidthRatio -and
        $null -ne $watchdogSharedAuthoredScale -and
        (Compare-NumericWithTolerance -Expected $watchdogSharedAuthoredScale -Actual $watchdogSakuraWidthRatio -Tolerance 0.02)
    )
    $watchdogSakuraFillsRegion = (
        $null -ne $watchdogSakuraSurfaceWidth -and
        $null -ne $watchdogSakuraSurfaceRegionWidth -and
        $watchdogSakuraSurfaceRegionWidth -gt 0 -and
        (Compare-NumericWithTolerance -Expected $watchdogSakuraSurfaceRegionWidth -Actual $watchdogSakuraSurfaceWidth -Tolerance 0.5) -and
        $watchdogSakuraSurfaceWidth -ge ($watchdogSakuraSurfaceRegionWidth * 0.99)
    )

    $bigRedSummaryRows = @($results | Where-Object { $_.label -eq 'Big Red Button' })
    $bigRedSummaryRow = if ($bigRedSummaryRows.Count -eq 1) { $bigRedSummaryRows[0] } else { $null }
    $bigRedSummaryResultPath = Get-NestedPropertyValue -Object $bigRedSummaryRow -Path 'resultPath'
    $bigRedSummarySafeLabel = Get-NestedPropertyValue -Object $bigRedSummaryRow -Path 'safeLabel'
    $bigRedResult = Read-ResultJsonFromSummaryRow -ResultRow $bigRedSummaryRow
    $bigRedProductionKeroFound = $false
    $bigRedProductionKero = Get-NestedPropertyValue -Object $bigRedResult -Path 'evidence.productionStage.kero' -Found ([ref]$bigRedProductionKeroFound)
    $bigRedLayoutKeroSurfaceFound = $false
    $bigRedLayoutKeroSurface = Get-NestedPropertyValue -Object $bigRedResult -Path 'evidence.productionStage.layoutDp.keroSurface' -Found ([ref]$bigRedLayoutKeroSurfaceFound)
    $bigRedSakuraIntrinsicWidth = Get-NestedPropertyValue -Object $bigRedResult -Path 'evidence.productionStage.sakura.intrinsic.width'
    $bigRedSakuraIntrinsicHeight = Get-NestedPropertyValue -Object $bigRedResult -Path 'evidence.productionStage.sakura.intrinsic.height'
    $bigRedSakuraSurfaceWidth = Get-NestedPropertyValue -Object $bigRedResult -Path 'evidence.productionStage.layoutDp.sakuraSurface.width'
    $bigRedSakuraSurfaceRegionWidth = Get-NestedPropertyValue -Object $bigRedResult -Path 'evidence.productionStage.layoutDp.sakuraSurfaceRegion.width'
    $bigRedSakuraFillsRegion = (
        $null -ne $bigRedSakuraSurfaceWidth -and
        $null -ne $bigRedSakuraSurfaceRegionWidth -and
        $bigRedSakuraSurfaceRegionWidth -gt 0 -and
        (Compare-NumericWithTolerance -Expected $bigRedSakuraSurfaceRegionWidth -Actual $bigRedSakuraSurfaceWidth -Tolerance 0.5) -and
        $bigRedSakuraSurfaceWidth -ge ($bigRedSakuraSurfaceRegionWidth * 0.99)
    )

    $unsupportedPackageContracts = @(
        [pscustomobject]@{ label = 'Haiidrate'; safeLabel = 'Haiidrate'; kind = 'shell' },
        [pscustomobject]@{ label = 'Hareraiser'; safeLabel = 'Hareraiser'; kind = 'balloon' },
        [pscustomobject]@{ label = 'Kitsune no Ocha'; safeLabel = 'Kitsune-no-Ocha'; kind = 'shell' },
        [pscustomobject]@{ label = 'The Petpet Puddle'; safeLabel = 'The-Petpet-Puddle'; kind = 'shell' }
    )
    $unsupportedPackageMismatches = [System.Collections.ArrayList]::new()
    foreach ($unsupportedContract in $unsupportedPackageContracts) {
        $unsupportedRows = @($results | Where-Object { $_.label -eq $unsupportedContract.label })
        if ($unsupportedRows.Count -ne 1) {
            $unsupportedPackageMismatches.Add("$($unsupportedContract.label):summary-row-count=$($unsupportedRows.Count)") | Out-Null
            continue
        }
        $unsupportedRow = $unsupportedRows[0]
        $unsupportedResultPath = Get-NestedPropertyValue -Object $unsupportedRow -Path 'resultPath'
        $unsupportedSafeLabel = Get-NestedPropertyValue -Object $unsupportedRow -Path 'safeLabel'
        $unsupportedResult = Read-ResultJsonFromSummaryRow -ResultRow $unsupportedRow
        if ([string]::IsNullOrWhiteSpace($unsupportedResultPath)) {
            $unsupportedPackageMismatches.Add("$($unsupportedContract.label):missing-result-path") | Out-Null
        }
        if ($unsupportedSafeLabel -ne $unsupportedContract.safeLabel) {
            $unsupportedPackageMismatches.Add("$($unsupportedContract.label):safe-label=$unsupportedSafeLabel") | Out-Null
        }
        if ($null -eq $unsupportedResult) {
            $unsupportedPackageMismatches.Add("$($unsupportedContract.label):missing-result") | Out-Null
            continue
        }
        $unsupportedDiagnostics = As-NonNullArray -Value (Get-NestedPropertyValue -Object $unsupportedResult -Path 'parserDiagnostics')
        $unsupportedDiagnostic = if ($unsupportedDiagnostics.Count -eq 1) { $unsupportedDiagnostics[0] } else { $null }
        $unsupportedChecks = [ordered]@{
            label = ((Get-NestedPropertyValue -Object $unsupportedResult -Path 'label') -eq $unsupportedContract.label)
            observedKind = ((Get-NestedPropertyValue -Object $unsupportedResult -Path 'observedKind') -eq $unsupportedContract.kind)
            classification = ((Get-NestedPropertyValue -Object $unsupportedResult -Path 'classification') -eq 'unsupported')
            installOutcome = ((Get-NestedPropertyValue -Object $unsupportedResult -Path 'installOutcome') -eq "unsupported:$($unsupportedContract.kind)")
            ghostLoadOutcome = ((Get-NestedPropertyValue -Object $unsupportedResult -Path 'ghostLoadOutcome') -eq 'not-applicable')
            renderOutcome = ((Get-NestedPropertyValue -Object $unsupportedResult -Path 'renderOutcome') -eq 'not-applicable')
            inputOutcome = ((Get-NestedPropertyValue -Object $unsupportedResult -Path 'inputOutcome') -eq 'not-applicable')
            shioriOutcome = ((Get-NestedPropertyValue -Object $unsupportedResult -Path 'shioriOutcome') -eq 'not-applicable')
            surfaceCount = ((Get-NestedPropertyValue -Object $unsupportedResult -Path 'surfaceCount') -eq 0)
            passed = ((Get-NestedPropertyValue -Object $unsupportedResult -Path 'passed') -eq $true)
            diagnosticCount = ($unsupportedDiagnostics.Count -eq 1)
            diagnosticObservedKind = ((Get-NestedPropertyValue -Object $unsupportedDiagnostic -Path 'observedKind') -eq $unsupportedContract.kind)
            diagnosticPlanSuccess = ((Get-NestedPropertyValue -Object $unsupportedDiagnostic -Path 'planSuccess') -eq $false)
            diagnosticError = ((Get-NestedPropertyValue -Object $unsupportedDiagnostic -Path 'error') -eq 'UNSUPPORTED_TYPE')
            diagnosticDetail = ((Get-NestedPropertyValue -Object $unsupportedDiagnostic -Path 'detail') -eq $unsupportedContract.kind)
        }
        foreach ($unsupportedCheck in $unsupportedChecks.GetEnumerator()) {
            if (-not $unsupportedCheck.Value) {
                $unsupportedPackageMismatches.Add("$($unsupportedContract.label):$($unsupportedCheck.Key)") | Out-Null
            }
        }
    }

    $snakeSummaryRows = @($results | Where-Object { $_.label -eq 'Snake and Otacon V1.3.2' })
    $snakeSummaryRow = if ($snakeSummaryRows.Count -eq 1) { $snakeSummaryRows[0] } else { $null }
    $snakeSummaryResultPath = Get-NestedPropertyValue -Object $snakeSummaryRow -Path 'resultPath'
    $snakeSummarySafeLabel = Get-NestedPropertyValue -Object $snakeSummaryRow -Path 'safeLabel'
    $snakeResult = Read-ResultJsonFromSummaryRow -ResultRow $snakeSummaryRow
    $snakeProductionSurfaceReader = Get-NestedPropertyValue -Object $snakeResult -Path 'evidence.productionSurfaceReader'
    $snakeSurfaceReaderError = Get-NestedPropertyValue -Object $snakeProductionSurfaceReader -Path 'error'
    $snakeSurfaceReaderDiagnostics = Get-NestedPropertyValue -Object $snakeProductionSurfaceReader -Path 'diagnostics'
    $snakeSourceSyntaxEvidence = Get-NestedPropertyValue -Object $snakeResult -Path 'evidence.sourceSyntax'
    $snakeSourceSyntaxScanErrorFound = $false
    $snakeSourceSyntaxScanError = Get-NestedPropertyValue -Object $snakeSourceSyntaxEvidence -Path 'scanError' -Found ([ref]$snakeSourceSyntaxScanErrorFound)
    $snakeSourceSyntaxFilesTruncated = Get-NestedPropertyValue -Object $snakeSourceSyntaxEvidence -Path 'filesTruncated'
    $snakeSourceSyntaxBytesTruncated = Get-NestedPropertyValue -Object $snakeSourceSyntaxEvidence -Path 'bytesTruncated'
    $snakeSourceSyntaxSelectorIdsTruncated = Get-NestedPropertyValue -Object $snakeSourceSyntaxEvidence -Path 'surfaceSelectorIdsTruncated'
    $snakeSourceSyntaxScanOk = (-not $snakeSourceSyntaxScanErrorFound) -or ($null -eq $snakeSourceSyntaxScanError) -or ($snakeSourceSyntaxScanError -eq $false)
    $snakeParsedSurfaceEntries = Get-NestedPropertyValue -Object $snakeProductionSurfaceReader -Path 'parsedSurfaceEntries.surfaces'
    $snakeSurfaceLine239FoundForSurface0Or9 = $false
    $snakeSurfaceLine285FoundForSurface8 = $false
    $snakeSurfaceLine394FoundForSurface19Or40 = $false
    if ($null -ne $snakeParsedSurfaceEntries) {
        foreach ($surfaceEntry in @($snakeParsedSurfaceEntries)) {
            $surfaceIdText = Get-NestedPropertyValue -Object $surfaceEntry -Path 'surfaceId'
            $surfaceId = 0
            if (-not [int]::TryParse([string]$surfaceIdText, [ref]$surfaceId)) { continue }
            $parsedEntries = Get-NestedPropertyValue -Object $surfaceEntry -Path 'entries'
            foreach ($entry in @($parsedEntries)) {
                $entryLineText = Get-NestedPropertyValue -Object $entry -Path 'line'
                $entryLine = 0
                if (-not [int]::TryParse([string]$entryLineText, [ref]$entryLine)) { continue }
                switch ($surfaceId) {
                    0 { if ($entryLine -eq 239) { $snakeSurfaceLine239FoundForSurface0Or9 = $true } }
                    9 { if ($entryLine -eq 239) { $snakeSurfaceLine239FoundForSurface0Or9 = $true } }
                    8 { if ($entryLine -eq 285) { $snakeSurfaceLine285FoundForSurface8 = $true } }
                    19 { if ($entryLine -eq 394) { $snakeSurfaceLine394FoundForSurface19Or40 = $true } }
                    40 { if ($entryLine -eq 394) { $snakeSurfaceLine394FoundForSurface19Or40 = $true } }
                }
            }
        }
    }

    $snakeReaderDiagnosticContainsProhibited = $false
    $snakeReaderDiagnosticReasonValues = @()
    foreach ($diag in @($snakeSurfaceReaderDiagnostics)) {
        $diagReason = Get-NestedPropertyValue -Object $diag -Path 'reason'
        if ($null -ne $diagReason) {
            $snakeReaderDiagnosticReasonValues += [string]$diagReason
            if ($diagReason -eq 'DECODE' -or $diagReason -eq 'SELECTOR') {
                $snakeReaderDiagnosticContainsProhibited = $true
            }
        }
    }

    $snakeSequence = Get-NestedPropertyValue -Object $snakeResult -Path 'dialogueProbe.sequence'
    $snakeSequenceSteps = @()
    if ($null -ne $snakeSequence) {
        $snakeSequenceSteps = @($snakeSequence)
    }
    $snakeSequenceCount = $snakeSequenceSteps.Count
    $snakeStepOnBoot = if ($snakeSequenceCount -gt 0) { $snakeSequenceSteps[0] } else { $null }
    $snakeStepOnFirstBoot = if ($snakeSequenceCount -gt 1) { $snakeSequenceSteps[1] } else { $null }
    $snakeStepFirstChoiceSelect = if ($snakeSequenceCount -gt 2) { $snakeSequenceSteps[2] } else { $null }
    $snakeStepSecondChoiceSelect = if ($snakeSequenceCount -gt 3) { $snakeSequenceSteps[3] } else { $null }
    $snakeOnBootStatus = Get-NestedPropertyValue -Object $snakeStepOnBoot -Path 'status'
    $snakeOnBootOutcome = Get-NestedPropertyValue -Object $snakeStepOnBoot -Path 'outcome'
    $snakeOnBootValue = Get-NestedPropertyValue -Object $snakeStepOnBoot -Path 'value'
    $snakeOnBootFailure = Get-NestedPropertyValue -Object $snakeStepOnBoot -Path 'failure'
    $snakeOnBootTokenizerDiagnostics = Get-NestedPropertyValue -Object $snakeStepOnBoot -Path 'tokenizerDiagnostics'
    $snakeOnBootMethod = Get-NestedPropertyValue -Object $snakeStepOnBoot -Path 'method'
    $snakeOnBootRefs = As-NonNullArray -Value (Get-NestedPropertyValue -Object $snakeStepOnBoot -Path 'references')
    $snakeOnBootRef0 = if ($snakeOnBootRefs.Count -gt 0) { $snakeOnBootRefs[0] } else { $null }
    $snakeOnBootStatusInt = 0
    $snakeOnBootStatus2xx = [int]::TryParse([string]$snakeOnBootStatus, [ref]$snakeOnBootStatusInt) -and $snakeOnBootStatusInt -ge 200 -and $snakeOnBootStatusInt -le 299

    $snakeOnFirstBootStatus = Get-NestedPropertyValue -Object $snakeStepOnFirstBoot -Path 'status'
    $snakeOnFirstBootOutcome = Get-NestedPropertyValue -Object $snakeStepOnFirstBoot -Path 'outcome'
    $snakeOnFirstBootValue = Get-NestedPropertyValue -Object $snakeStepOnFirstBoot -Path 'value'
    $snakeOnFirstBootFailure = Get-NestedPropertyValue -Object $snakeStepOnFirstBoot -Path 'failure'
    $snakeOnFirstBootTokenizerDiagnostics = Get-NestedPropertyValue -Object $snakeStepOnFirstBoot -Path 'tokenizerDiagnostics'
    $snakeOnFirstBootMethod = Get-NestedPropertyValue -Object $snakeStepOnFirstBoot -Path 'method'
    $snakeOnFirstBootRefs = As-NonNullArray -Value (Get-NestedPropertyValue -Object $snakeStepOnFirstBoot -Path 'references')
    $snakeOnFirstBootRef0 = if ($snakeOnFirstBootRefs.Count -gt 0) { $snakeOnFirstBootRefs[0] } else { $null }
    $snakeOnFirstBootChoiceIds = As-NonNullArray -Value (Get-NestedPropertyValue -Object $snakeStepOnFirstBoot -Path 'choiceIds')
    $snakeOnFirstBootPassiveTransitions = As-NonNullArray -Value (Get-NestedPropertyValue -Object $snakeStepOnFirstBoot -Path 'passiveTransitions')
    $snakeOnFirstBootStatusInt = 0
    $snakeOnFirstBootStatus2xx = [int]::TryParse([string]$snakeOnFirstBootStatus, [ref]$snakeOnFirstBootStatusInt) -and $snakeOnFirstBootStatusInt -ge 200 -and $snakeOnFirstBootStatusInt -le 299

    $snakeFirstChoiceSelectStatus = Get-NestedPropertyValue -Object $snakeStepFirstChoiceSelect -Path 'status'
    $snakeFirstChoiceSelectOutcome = Get-NestedPropertyValue -Object $snakeStepFirstChoiceSelect -Path 'outcome'
    $snakeFirstChoiceSelectValue = Get-NestedPropertyValue -Object $snakeStepFirstChoiceSelect -Path 'value'
    $snakeFirstChoiceSelectFailure = Get-NestedPropertyValue -Object $snakeStepFirstChoiceSelect -Path 'failure'
    $snakeFirstChoiceSelectTokenizerDiagnostics = Get-NestedPropertyValue -Object $snakeStepFirstChoiceSelect -Path 'tokenizerDiagnostics'
    $snakeFirstChoiceSelectMethod = Get-NestedPropertyValue -Object $snakeStepFirstChoiceSelect -Path 'method'
    $snakeFirstChoiceSelectRefs = As-NonNullArray -Value (Get-NestedPropertyValue -Object $snakeStepFirstChoiceSelect -Path 'references')
    $snakeFirstChoiceSelectRef0 = if ($snakeFirstChoiceSelectRefs.Count -gt 0) { $snakeFirstChoiceSelectRefs[0] } else { $null }
    $snakeFirstChoiceSelectPassiveTransitions = As-NonNullArray -Value (Get-NestedPropertyValue -Object $snakeStepFirstChoiceSelect -Path 'passiveTransitions')
    $snakeFirstChoiceSelectInputSpecs = As-NonNullArray -Value (Get-NestedPropertyValue -Object $snakeStepFirstChoiceSelect -Path 'inputSpecs')
    $snakeFirstChoiceSelectInput = if ($snakeFirstChoiceSelectInputSpecs.Count -gt 0) { $snakeFirstChoiceSelectInputSpecs[0] } else { $null }
    $snakeFirstChoiceSelectInputDispatchId = Get-NestedPropertyValue -Object $snakeFirstChoiceSelectInput -Path 'dispatchId'
    $snakeFirstChoiceSelectInputTimeout = Get-NestedPropertyValue -Object $snakeFirstChoiceSelectInput -Path 'timeout'
    $snakeFirstChoiceSelectStatusInt = 0
    $snakeFirstChoiceSelectStatus2xx = [int]::TryParse([string]$snakeFirstChoiceSelectStatus, [ref]$snakeFirstChoiceSelectStatusInt) -and $snakeFirstChoiceSelectStatusInt -ge 200 -and $snakeFirstChoiceSelectStatusInt -le 299

    $snakeFaqStatus = Get-NestedPropertyValue -Object $snakeStepSecondChoiceSelect -Path 'status'
    $snakeFaqOutcome = Get-NestedPropertyValue -Object $snakeStepSecondChoiceSelect -Path 'outcome'
    $snakeFaqValue = Get-NestedPropertyValue -Object $snakeStepSecondChoiceSelect -Path 'value'
    $snakeFaqFailure = Get-NestedPropertyValue -Object $snakeStepSecondChoiceSelect -Path 'failure'
    $snakeFaqTokenizerDiagnostics = Get-NestedPropertyValue -Object $snakeStepSecondChoiceSelect -Path 'tokenizerDiagnostics'
    $snakeFaqMethod = Get-NestedPropertyValue -Object $snakeStepSecondChoiceSelect -Path 'method'
    $snakeFaqRefs = As-NonNullArray -Value (Get-NestedPropertyValue -Object $snakeStepSecondChoiceSelect -Path 'references')
    $snakeFaqRef0 = if ($snakeFaqRefs.Count -gt 0) { $snakeFaqRefs[0] } else { $null }
    $snakeFaqAnchorIds = As-NonNullArray -Value (Get-NestedPropertyValue -Object $snakeStepSecondChoiceSelect -Path 'anchorIds')
    $snakeFaqStatusInt = 0
    $snakeFaqStatus2xx = [int]::TryParse([string]$snakeFaqStatus, [ref]$snakeFaqStatusInt) -and $snakeFaqStatusInt -ge 200 -and $snakeFaqStatusInt -le 299
    $snakeOnBootTokenizerCount = if ($null -ne $snakeOnBootTokenizerDiagnostics) { @($snakeOnBootTokenizerDiagnostics).Count } else { $null }
    $snakeFirstChoiceSelectTokenizerCount = if ($null -ne $snakeFirstChoiceSelectTokenizerDiagnostics) { @($snakeFirstChoiceSelectTokenizerDiagnostics).Count } else { $null }
    $snakeOnFirstBootTokenizerDiagnosticsExpected = Test-OnlyExpectedTokenizerDiagnostics -Diagnostics $snakeOnFirstBootTokenizerDiagnostics
    $snakeFaqTokenizerDiagnosticsExpected = Test-OnlyExpectedTokenizerDiagnostics -Diagnostics $snakeFaqTokenizerDiagnostics
    $snakeOnBootValueText = if ($null -eq $snakeOnBootValue) { '' } else { [string]$snakeOnBootValue }
    $snakeOnBootValueNonBlank = -not [string]::IsNullOrWhiteSpace($snakeOnBootValueText)
    $snakeOnFirstBootValueText = if ($null -eq $snakeOnFirstBootValue) { '' } else { [string]$snakeOnFirstBootValue }
    $snakeOnFirstBootValueNonBlank = -not [string]::IsNullOrWhiteSpace($snakeOnFirstBootValueText)
    $snakeFirstChoiceSelectValueText = if ($null -eq $snakeFirstChoiceSelectValue) { '' } else { [string]$snakeFirstChoiceSelectValue }
    $snakeFirstChoiceSelectValueNonBlank = -not [string]::IsNullOrWhiteSpace($snakeFirstChoiceSelectValueText)
    $snakeFaqValueText = if ($null -eq $snakeFaqValue) { '' } else { [string]$snakeFaqValue }
    $snakeFaqValueNonBlank = -not [string]::IsNullOrWhiteSpace($snakeFaqValueText)

    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-summary-row-count' -Passed ($twoElfSummaryRows.Count -eq 1) -Expected 1 -Observed $twoElfSummaryRows.Count -Detail 'Expected exactly one summary row for label 2elf-2.46.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-summary-result-path' -Passed (-not [string]::IsNullOrWhiteSpace($twoElfSummaryResultPath)) -Expected 'non-empty' -Observed $twoElfSummaryResultPath -Detail 'Expected summary row resultPath to be non-empty.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-summary-result-safe-label' -Passed ($twoElfSummarySafeLabel -eq '2elf-2.46') -Expected '2elf-2.46' -Observed $twoElfSummarySafeLabel -Detail 'Expected summary row safeLabel to be 2elf-2.46.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-summary-label' -Result $twoElfSummaryRow -Path 'label' -Expected '2elf-2.46' -Detail 'Expected summary row label to be exactly 2elf-2.46.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-result-loaded' -Passed ($null -ne $twoElfResult) -Expected 'true' -Observed ([string]($null -ne $twoElfResult)) -Detail "Expected report payload from summary row resultPath '$twoElfSummaryResultPath'."
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-classification' -Result $twoElfResult -Path 'classification' -Expected 'compatible' -Detail 'Expected 2elf-2.46 classification compatible.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-install' -Result $twoElfResult -Path 'installOutcome' -Expected 'installed' -Detail 'Expected 2elf-2.46 installOutcome=installed.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-ghost-load' -Result $twoElfResult -Path 'ghostLoadOutcome' -Expected 'loaded' -Detail 'Expected 2elf-2.46 ghostLoadOutcome=loaded.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-render' -Result $twoElfResult -Path 'renderOutcome' -Expected 'production-stage-rendered' -Detail 'Expected 2elf-2.46 renderOutcome=production-stage-rendered.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-input' -Result $twoElfResult -Path 'inputOutcome' -Expected 'named-collisions-routed:24' -Detail 'Expected 2elf-2.46 inputOutcome=named-collisions-routed:24.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-shiori' -Result $twoElfResult -Path 'shioriOutcome' -Expected 'success' -Detail 'Expected 2elf-2.46 shioriOutcome=success.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-default-surface-sakura' -Result $twoElfResult -Path 'evidence.productionStage.exactDefaultSurfaceIds.sakura' -Expected 0 -Detail 'Expected 2elf-2.46 default sakura surfaceId 0.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-default-surface-kero' -Result $twoElfResult -Path 'evidence.productionStage.exactDefaultSurfaceIds.kero' -Expected 10 -Detail 'Expected 2elf-2.46 default kero surfaceId 10.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-sakura-intrinsic' -Result $twoElfResult -Path 'evidence.productionStage.sakura.intrinsic.width' -Expected 270 -Detail 'Expected 2elf-2.46 sakura intrinsic width 270.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-kero-intrinsic' -Result $twoElfResult -Path 'evidence.productionStage.kero.intrinsic.width' -Expected 239 -Detail 'Expected 2elf-2.46 kero intrinsic width 239.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-sakura-intrinsic-height' -Result $twoElfResult -Path 'evidence.productionStage.sakura.intrinsic.height' -Expected 378 -Detail 'Expected 2elf-2.46 sakura intrinsic height 378.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-kero-intrinsic-height' -Result $twoElfResult -Path 'evidence.productionStage.kero.intrinsic.height' -Expected 380 -Detail 'Expected 2elf-2.46 kero intrinsic height 380.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-collision-count' -Passed ($twoElfNamedCollisionCount -eq 24) -Expected 24 -Observed $twoElfNamedCollisionCount -Detail 'Expected 24 named-collision probes for 2elf-2.46.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-sakura-face-probe-count' -Passed ($twoElfFaceProbes.Count -eq 1) -Expected 1 -Observed $twoElfFaceProbes.Count -Detail 'Expected exactly one Sakura surface-0 Face named-collision probe for 2elf-2.46.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-sakura-face-authored-id' -Passed ($foundTwoElfFaceProbe -and $twoElfFaceAuthoredId -eq 2) -Expected 2 -Observed $twoElfFaceAuthoredId -Detail 'Expected 2elf-2.46 Face probe authoredId=2.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-sakura-face-shape-kind' -Passed ($foundTwoElfFaceProbe -and $twoElfFaceShapeKind -eq 'rectangle') -Expected 'rectangle' -Observed $twoElfFaceShapeKind -Detail 'Expected 2elf-2.46 Face probe shapeKind=rectangle.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-sakura-face-overlay-contains-point' -Passed ($foundTwoElfFaceProbe -and $twoElfFaceOverlayContainsPoint -eq $true) -Expected $true -Observed $twoElfFaceOverlayContainsPoint -Detail 'Expected 2elf-2.46 Face probe overlayContainsPoint=true.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-sakura-face-overlay-exact' -Passed ($foundTwoElfFaceProbe -and $twoElfFaceOverlayExact -eq $true) -Expected $true -Observed $twoElfFaceOverlayExact -Detail 'Expected 2elf-2.46 Face probe overlayExact=true.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-sakura-face-direct-hit' -Passed ($foundTwoElfFaceProbe -and $twoElfFaceIntendedCollisionWasDirectlyHit -eq $true) -Expected $true -Observed $twoElfFaceIntendedCollisionWasDirectlyHit -Detail 'Expected 2elf-2.46 Face probe intendedCollisionWasDirectlyHit=true.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-sakura-face-resolution-outcome' -Passed ($foundTwoElfFaceProbe -and $twoElfFaceResolutionOutcome -eq 'direct-hit') -Expected 'direct-hit' -Observed $twoElfFaceResolutionOutcome -Detail 'Expected 2elf-2.46 Face probe resolutionOutcome=direct-hit.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-sakura-face-hit-identifier' -Passed ($foundTwoElfFaceProbe -and $twoElfFaceHitIdentifier -eq 'Face') -Expected 'Face' -Observed $twoElfFaceHitIdentifier -Detail 'Expected 2elf-2.46 Face probe hitIdentifier=Face.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-sakura-face-route' -Passed ($foundTwoElfFaceProbe -and $twoElfFaceRoutedId -eq 2) -Expected 2 -Observed $twoElfFaceRoutedId -Detail 'Expected 2elf-2.46 Face probe routed id=2.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-sakura-face-route-identifier' -Passed ($foundTwoElfFaceProbe -and $twoElfFaceRoutedIdentifier -eq 'Face') -Expected 'Face' -Observed $twoElfFaceRoutedIdentifier -Detail 'Expected 2elf-2.46 Face probe routed identifier=Face.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-sakura-face-collision-identifier' -Passed ($foundTwoElfFaceProbe -and $twoElfFaceEffectCollisionIdentifier -eq 'Face') -Expected 'Face' -Observed $twoElfFaceEffectCollisionIdentifier -Detail 'Expected 2elf-2.46 face probe effect collisionIdentifier=Face.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-sakura-face-effect-ref-4' -Passed ($foundTwoElfFaceProbe -and $twoElfFaceEffectRef4 -eq 'Face') -Expected 'Face' -Observed $twoElfFaceEffectRef4 -Detail 'Expected 2elf-2.46 Face probe effect.references[4]=Face.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-sakura-face-effect-reference-count' -Passed ($foundTwoElfFaceProbe -and $null -ne $twoElfFaceEffectReferenceCount -and $twoElfFaceEffectReferenceCount -gt 4) -Expected '>4' -Observed $twoElfFaceEffectReferenceCount -Detail 'Expected 2elf-2.46 Face probe effect.references to have at least 5 entries.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-dialogue-module' -Result $twoElfResult -Path 'dialogueProbe.module' -Expected 'Satori' -Detail 'Expected 2elf-2.46 dialogue module Satori.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-dialogue-status' -Result $twoElfResult -Path 'dialogueProbe.status' -Expected 200 -Detail 'Expected 2elf-2.46 OnBoot status 200.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-dialogue-outcome' -Result $twoElfResult -Path 'dialogueProbe.outcome' -Expected 'success' -Detail 'Expected 2elf-2.46 OnBoot outcome success.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-dialogue-event-id' -Result $twoElfResult -Path 'dialogueProbe.eventId' -Expected 'OnBoot' -Detail 'Expected 2elf-2.46 dialogue event id OnBoot.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-2elf-default-reference' -Result $twoElfResult -Path 'dialogueProbe.references[0]' -Expected 'デフォルト' -Detail 'Expected 2elf-2.46 dialogue reference at index 0 to be デフォルト.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-dialogue-method' -Passed (($null -ne $twoElfResult) -and $twoElfDialogueMethod -eq 'GET') -Expected 'GET' -Observed $twoElfDialogueMethod -Detail 'Expected 2elf-2.46 dialogue method GET.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-dialogue-value-nonblank' -Passed (($null -ne $twoElfResult) -and $twoElfDialogueValueNonBlank) -Expected 'nonblank' -Observed $twoElfDialogueValueText -Detail 'Expected 2elf-2.46 dialogue value to be present and nonblank.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-dialogue-tokenizer-diagnostics-empty' -Passed (($null -ne $twoElfResult) -and $twoElfDialogueTokenizerCount -eq 0) -Expected 0 -Observed $twoElfDialogueTokenizerCount -Detail 'Expected 2elf-2.46 dialogue tokenizerDiagnostics to be empty.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-2elf-dialogue-failure-null' -Passed (($null -ne $twoElfResult) -and $null -eq $twoElfDialogueFailure) -Expected $null -Observed $twoElfDialogueFailure -Detail 'Expected 2elf-2.46 dialogue failure to be null.'
   Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-summary-row-count' -Passed ($nanikaSummaryRows.Count -eq 1) -Expected 1 -Observed $nanikaSummaryRows.Count -Detail 'Expected exactly one summary row for label Nanika Atsume 1.0.1.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-summary-result-path' -Passed (-not [string]::IsNullOrWhiteSpace($nanikaSummaryResultPath)) -Expected 'non-empty' -Observed $nanikaSummaryResultPath -Detail 'Expected summary row resultPath to be non-empty.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-summary-result-safe-label' -Passed ($nanikaSummarySafeLabel -eq 'Nanika-Atsume-1.0.1') -Expected 'Nanika-Atsume-1.0.1' -Observed $nanikaSummarySafeLabel -Detail 'Expected summary row safeLabel to be Nanika-Atsume-1.0.1.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-nanika-summary-label' -Result $nanikaSummaryRow -Path 'label' -Expected 'Nanika Atsume 1.0.1' -Detail 'Expected summary row label to be exactly Nanika Atsume 1.0.1.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-result-loaded' -Passed ($null -ne $nanikaResult) -Expected 'true' -Observed ([string]($null -ne $nanikaResult)) -Detail "Expected report payload from summary row resultPath '$nanikaSummaryResultPath'."
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-surface-count' -Passed ($nanikaSurfaceCount -eq 256) -Expected 256 -Observed $nanikaSurfaceCount -Detail 'Expected 256 entries in Nanika Atsume 1.0.1 surfaceKeys.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-surface-key-count-in-range' -Passed ($nanikaSurfaceKeysInRuntimeRange.Count -eq 246) -Expected 246 -Observed $nanikaSurfaceKeysInRuntimeRange.Count -Detail 'Expected exactly 246 surfaceKeys in Nanika Atsume 1.0.1 range [6000,6559].'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-runtime-contains-expected-surface-keys-included' -Passed $nanikaExpectedIncludedSurfaceKeysFoundInRuntime -Expected '6000,6002,6003,6005,6559' -Observed $nanikaRuntimeSurfaceKeysInExpectedIncludedObserved -Detail 'Expected runtime surfaceKeys to include 6000,6002,6003,6005,6559.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-runtime-excludes-expected-surface-keys' -Passed $nanikaExpectedExcludedSurfaceKeysAbsentInRuntime -Expected 'none' -Observed $nanikaRuntimeSurfaceKeysInExpectedExcludedObserved -Detail 'Expected runtime surfaceKeys to exclude 6001,6004,6021,6024,6551,6554.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-source-syntax-exists' -Passed ($null -ne $nanikaSourceSyntaxEvidence) -Expected '$true' -Observed $null -eq $nanikaSourceSyntaxEvidence -Detail 'Expected Nanika Atsume 1.0.1 sourceSyntax evidence to exist.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-source-syntax-scan-ok' -Passed $nanikaSourceSyntaxScanOk -Expected '$false/null' -Observed $nanikaSourceSyntaxScanError -Detail 'Expected Nanika source syntax scanError to be absent or false.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-nanika-source-scan-files-not-truncated' -Result $nanikaSourceSyntaxEvidence -Path 'filesTruncated' -Expected $false -Detail 'Expected Nanika source syntax to avoid file truncation.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-nanika-source-scan-bytes-not-truncated' -Result $nanikaSourceSyntaxEvidence -Path 'bytesTruncated' -Expected $false -Detail 'Expected Nanika source syntax to avoid byte truncation.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-nanika-source-scan-selector-ids-not-truncated' -Result $nanikaSourceSyntaxEvidence -Path 'surfaceSelectorIdsTruncated' -Expected $false -Detail 'Expected Nanika source syntax selector-id scan to avoid truncation.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-nanika-source-flag-range-selectors' -Result $nanikaSourceSyntaxEvidence -Path 'hasRangeSelectors' -Expected $true -Detail 'Expected Nanika source syntax hasRangeSelectors=true.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-nanika-source-flag-exclusion-selectors' -Result $nanikaSourceSyntaxEvidence -Path 'hasExclusionSelectors' -Expected $true -Detail 'Expected Nanika source syntax hasExclusionSelectors=true.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-source-contains-expected-surface-keys-included' -Passed $nanikaExpectedIncludedSurfaceKeysFound -Expected '6000,6002,6003,6005,6559' -Observed $nanikaSourceSyntaxSurfaceKeysIncludedObserved -Detail 'Expected sourceSyntax.surfaceKeys.included to contain 6000,6002,6003,6005,6559.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-source-contains-expected-surface-keys-excluded' -Passed $nanikaExpectedExcludedSurfaceKeysFound -Expected '6001,6004,6021,6024,6551,6554' -Observed $nanikaSourceSyntaxSurfaceKeysExcludedObserved -Detail 'Expected sourceSyntax.surfaceKeys.excluded to contain 6001,6004,6021,6024,6551,6554.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-sakura-intrinsic' -Passed (($nanikaSakuraIntrinsicWidth -eq 93) -and ($nanikaSakuraIntrinsicHeight -eq 95)) -Expected '93x95' -Observed "${nanikaSakuraIntrinsicWidth}x${nanikaSakuraIntrinsicHeight}" -Detail 'Expected Nanika sakura intrinsic to be 93x95.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-sakura-visible-bounds' -Passed (($nanikaSakuraVisiblePixelLeft -eq 33) -and ($nanikaSakuraVisiblePixelTop -eq 73) -and ($nanikaSakuraVisiblePixelRight -eq 59) -and ($nanikaSakuraVisiblePixelBottom -eq 92)) -Expected '33,73,59,92' -Observed "${nanikaSakuraVisiblePixelLeft},${nanikaSakuraVisiblePixelTop},${nanikaSakuraVisiblePixelRight},${nanikaSakuraVisiblePixelBottom}" -Detail 'Expected Nanika sakura visiblePixelBounds to be exactly left=33 top=73 right=59 bottom=92.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-sakura-visible-bounds-exists' -Passed ($null -ne $nanikaSakuraVisiblePixelBounds) -Expected '$true' -Observed $null -eq $nanikaSakuraVisiblePixelBounds -Detail 'Expected Nanika sakura visiblePixelBounds to be present.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-sakura-optical-bounds-exists' -Passed ($null -ne $nanikaSakuraOpticalBounds) -Expected '$true' -Observed $null -eq $nanikaSakuraOpticalBounds -Detail 'Expected Nanika sakura opticalBounds to be present.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-sakura-optical-in-rendered' -Passed $nanikaSakuraSurfaceWithinRenderedBounds -Expected $true -Observed $nanikaSakuraSurfaceWithinRenderedBounds -Detail 'Expected Nanika sakura opticalBounds to be within sakura.renderedBounds.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-kero-intrinsic' -Passed (($nanikaKeroIntrinsicWidth -eq 200) -and ($nanikaKeroIntrinsicHeight -eq 200)) -Expected '200x200' -Observed "${nanikaKeroIntrinsicWidth}x${nanikaKeroIntrinsicHeight}" -Detail 'Expected Nanika kero intrinsic to be 200x200.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-kero-visible-bounds-null' -Passed ($nanikaKeroVisiblePixelBoundsFound -and ($null -eq $nanikaKeroVisiblePixelBounds)) -Expected '$true,null' -Observed "$nanikaKeroVisiblePixelBoundsFound,$nanikaKeroVisiblePixelBounds" -Detail 'Expected Nanika evidence.productionStage.kero.visiblePixelBounds to be present and null.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-top-level-kero-collision-count' -Passed ($nanikaKeroCollisionCount -eq 7) -Expected 7 -Observed $nanikaKeroCollisionCount -Detail 'Expected Nanika top-level kero collisionCount to be 7.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-production-stage-kero-exists' -Passed ($null -ne $nanikaProductionStageKero) -Expected '$true' -Observed $null -eq $nanikaProductionStageKero -Detail 'Expected Nanika evidence.productionStage.kero to be present.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-layout-sakura-surface-ratio' -Passed $nanikaSakuraSurfaceRatioMatchesSharedScale -Expected ($nanikaSharedAuthoredScale * 2) -Observed $nanikaSakuraLayoutWidthRatio -Detail 'Expected Nanika layoutDp.sakuraSurface.width / 93 within 0.02 of sharedAuthoredScale * 2.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-nanika-layout-kero-surface-ratio' -Passed $nanikaKeroSurfaceRatioMatchesSharedScale -Expected $nanikaSharedAuthoredScale -Observed $nanikaKeroLayoutWidthRatio -Detail 'Expected Nanika layoutDp.keroSurface.width / 200 within 0.02 of sharedAuthoredScale.'

    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-watchdog-summary-row-count' -Passed ($watchdogSummaryRows.Count -eq 1) -Expected 1 -Observed $watchdogSummaryRows.Count -Detail 'Expected exactly one summary row for Watchdog Bancho.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-watchdog-summary-result-path' -Passed (-not [string]::IsNullOrWhiteSpace($watchdogSummaryResultPath)) -Expected 'non-empty' -Observed $watchdogSummaryResultPath -Detail 'Expected Watchdog summary row resultPath.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-watchdog-summary-safe-label' -Passed ($watchdogSummarySafeLabel -eq 'Watchdog-Bancho') -Expected 'Watchdog-Bancho' -Observed $watchdogSummarySafeLabel -Detail 'Expected Watchdog safe label.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-watchdog-summary-label' -Result $watchdogSummaryRow -Path 'label' -Expected 'Watchdog Bancho' -Detail 'Expected exact Watchdog summary label.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-watchdog-result-loaded' -Passed ($null -ne $watchdogResult) -Expected $true -Observed ($null -ne $watchdogResult) -Detail 'Expected Watchdog local result payload.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-watchdog-probe-count' -Passed ($watchdogProbes.Count -eq 12) -Expected 12 -Observed $watchdogProbes.Count -Detail 'Expected exactly twelve ordered Sakura surface-0 polygon probes.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-watchdog-probe-contracts' -Passed ($watchdogProbeMismatches.Count -eq 0) -Expected 'no mismatches' -Observed ($watchdogProbeMismatches -join '; ') -Detail 'Expected every ordered polygon probe to preserve authored geometry, routing, hit identity, diagnostic id, and Reference4.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-watchdog-sakura-intrinsic' -Passed ($watchdogSakuraIntrinsicWidth -eq 427 -and $watchdogSakuraIntrinsicHeight -eq 640) -Expected '427x640' -Observed "${watchdogSakuraIntrinsicWidth}x${watchdogSakuraIntrinsicHeight}" -Detail 'Expected Watchdog Sakura intrinsic canvas.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-watchdog-kero-intrinsic' -Passed ($watchdogKeroIntrinsicWidth -eq 1 -and $watchdogKeroIntrinsicHeight -eq 1) -Expected '1x1' -Observed "${watchdogKeroIntrinsicWidth}x${watchdogKeroIntrinsicHeight}" -Detail 'Expected Watchdog 1x1 Kero intrinsic canvas.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-watchdog-production-kero-present' -Passed ($null -ne $watchdogProductionKero) -Expected $true -Observed ($null -ne $watchdogProductionKero) -Detail 'Expected the production stage to retain the 1x1 Kero surface.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-watchdog-sakura-shared-scale' -Passed $watchdogSakuraRatioMatchesSharedScale -Expected $watchdogSharedAuthoredScale -Observed $watchdogSakuraWidthRatio -Detail 'Expected Sakura layout width / 427 within 0.02 of shared authored scale.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-watchdog-sakura-fills-region' -Passed $watchdogSakuraFillsRegion -Expected $watchdogSakuraSurfaceRegionWidth -Observed $watchdogSakuraSurfaceWidth -Detail 'Expected the 1x1 Kero not to shrink Sakura: width must be within 0.5dp and at least 99% of its surface region.'

    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-big-red-summary-row-count' -Passed ($bigRedSummaryRows.Count -eq 1) -Expected 1 -Observed $bigRedSummaryRows.Count -Detail 'Expected exactly one Big Red Button summary row.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-big-red-summary-result-path' -Passed (-not [string]::IsNullOrWhiteSpace($bigRedSummaryResultPath)) -Expected 'non-empty' -Observed $bigRedSummaryResultPath -Detail 'Expected Big Red Button resultPath.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-big-red-summary-safe-label' -Passed ($bigRedSummarySafeLabel -eq 'Big-Red-Button') -Expected 'Big-Red-Button' -Observed $bigRedSummarySafeLabel -Detail 'Expected Big Red Button safe label.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-big-red-summary-label' -Result $bigRedSummaryRow -Path 'label' -Expected 'Big Red Button' -Detail 'Expected exact Big Red Button summary label.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-big-red-result-loaded' -Passed ($null -ne $bigRedResult) -Expected $true -Observed ($null -ne $bigRedResult) -Detail 'Expected Big Red Button local result.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-big-red-production-kero-null' -Passed ($bigRedProductionKeroFound -and $null -eq $bigRedProductionKero) -Expected 'found,null' -Observed "$bigRedProductionKeroFound,$bigRedProductionKero" -Detail 'Expected productionStage.kero to be explicitly null.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-big-red-layout-kero-null' -Passed ($bigRedLayoutKeroSurfaceFound -and $null -eq $bigRedLayoutKeroSurface) -Expected 'found,null' -Observed "$bigRedLayoutKeroSurfaceFound,$bigRedLayoutKeroSurface" -Detail 'Expected layoutDp.keroSurface to be explicitly null.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-big-red-sakura-intrinsic' -Passed ($bigRedSakuraIntrinsicWidth -eq 210 -and $bigRedSakuraIntrinsicHeight -eq 140) -Expected '210x140' -Observed "${bigRedSakuraIntrinsicWidth}x${bigRedSakuraIntrinsicHeight}" -Detail 'Expected Big Red Button Sakura intrinsic canvas.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-big-red-sakura-fills-region' -Passed $bigRedSakuraFillsRegion -Expected $bigRedSakuraSurfaceRegionWidth -Observed $bigRedSakuraSurfaceWidth -Detail 'Expected absent Kero not to shrink Sakura: width must be within 0.5dp and at least 99% of its surface region.'

    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-unsupported-package-count' -Passed ($unsupportedPackageContracts.Count -eq 4) -Expected 4 -Observed $unsupportedPackageContracts.Count -Detail 'Expected four explicitly unsupported non-ghost packages.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-unsupported-package-contracts' -Passed ($unsupportedPackageMismatches.Count -eq 0) -Expected 'no mismatches' -Observed ($unsupportedPackageMismatches -join '; ') -Detail 'Expected shell/balloon packages to be rejected as UNSUPPORTED_TYPE with structured not-applicable outcomes.'

    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-snake-summary-row-count' -Passed ($snakeSummaryRows.Count -eq 1) -Expected 1 -Observed $snakeSummaryRows.Count -Detail 'Expected exactly one summary row for label Snake and Otacon V1.3.2.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-snake-summary-result-path' -Passed (-not [string]::IsNullOrWhiteSpace($snakeSummaryResultPath)) -Expected 'non-empty' -Observed $snakeSummaryResultPath -Detail 'Expected summary row resultPath to be non-empty.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-snake-summary-result-safe-label' -Passed ($snakeSummarySafeLabel -eq 'Snake-and-Otacon-V1.3.2') -Expected 'Snake-and-Otacon-V1.3.2' -Observed $snakeSummarySafeLabel -Detail 'Expected summary row safeLabel to be Snake-and-Otacon-V1.3.2.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-summary-label' -Result $snakeSummaryRow -Path 'label' -Expected 'Snake and Otacon V1.3.2' -Detail 'Expected summary row label to be exactly Snake and Otacon V1.3.2.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-snake-result-loaded' -Passed ($null -ne $snakeResult) -Expected 'true' -Observed ([string]($null -ne $snakeResult)) -Detail "Expected report payload from summary row resultPath '$snakeSummaryResultPath'."
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-classification' -Result $snakeResult -Path 'classification' -Expected 'compatible' -Detail 'Expected Snake and Otacon V1.3.2 classification compatible.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-install' -Result $snakeResult -Path 'installOutcome' -Expected 'installed' -Detail 'Expected Snake and Otacon V1.3.2 installOutcome=installed.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-ghost-load' -Result $snakeResult -Path 'ghostLoadOutcome' -Expected 'loaded' -Detail 'Expected Snake and Otacon V1.3.2 ghostLoadOutcome=loaded.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-render' -Result $snakeResult -Path 'renderOutcome' -Expected 'production-stage-rendered' -Detail 'Expected Snake and Otacon V1.3.2 renderOutcome=production-stage-rendered.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-input' -Result $snakeResult -Path 'inputOutcome' -Expected 'named-collisions-routed:5' -Detail 'Expected Snake and Otacon V1.3.2 to route all five named collisions on its default Sakura/Kero pair.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-shiori' -Result $snakeResult -Path 'shioriOutcome' -Expected 'success' -Detail 'Expected Snake and Otacon V1.3.2 shioriOutcome=success.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-snake-source-scan-ok' -Passed $snakeSourceSyntaxScanOk -Expected '$false/null' -Observed $snakeSourceSyntaxScanError -Detail 'Expected Snake and Otacon source syntax scanError to be absent or false.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-source-scan-files-not-truncated' -Result $snakeSourceSyntaxEvidence -Path 'filesTruncated' -Expected $false -Detail 'Expected Snake and Otacon source syntax scan to avoid file truncation.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-source-scan-bytes-not-truncated' -Result $snakeSourceSyntaxEvidence -Path 'bytesTruncated' -Expected $false -Detail 'Expected Snake and Otacon source syntax scan to avoid byte truncation.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-source-scan-selector-ids-not-truncated' -Result $snakeSourceSyntaxEvidence -Path 'surfaceSelectorIdsTruncated' -Expected $false -Detail 'Expected Snake and Otacon source syntax selector-id scan to avoid truncation.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-source-flag-surface-line-comments' -Result $snakeSourceSyntaxEvidence -Path 'hasSurfaceLineComment' -Expected $true -Detail 'Expected Snake and Otacon source syntax hasSurfaceLineComment=true.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-source-flag-comma-selectors' -Result $snakeSourceSyntaxEvidence -Path 'hasCommaSelectors' -Expected $true -Detail 'Expected Snake and Otacon source syntax hasCommaSelectors=true.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-source-flag-range-selectors' -Result $snakeSourceSyntaxEvidence -Path 'hasRangeSelectors' -Expected $true -Detail 'Expected Snake and Otacon source syntax hasRangeSelectors=true.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-source-flag-surface-append' -Result $snakeSourceSyntaxEvidence -Path 'hasSurfaceAppend' -Expected $true -Detail 'Expected Snake and Otacon source syntax hasSurfaceAppend=true.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-source-flag-anchor' -Result $snakeSourceSyntaxEvidence -Path 'hasAnchor' -Expected $true -Detail 'Expected Snake and Otacon source syntax hasAnchor=true.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-source-flag-passive-mode' -Result $snakeSourceSyntaxEvidence -Path 'hasPassiveMode' -Expected $true -Detail 'Expected Snake and Otacon source syntax hasPassiveMode=true.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-source-flag-structured-inputbox' -Result $snakeSourceSyntaxEvidence -Path 'hasStructuredInputbox' -Expected $true -Detail 'Expected Snake and Otacon source syntax hasStructuredInputbox=true.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-snake-production-reader-error' -Passed ((-not $snakeSurfaceReaderError) -or $snakeSurfaceReaderError -eq $false) -Expected '$null/$false' -Observed $snakeSurfaceReaderError -Detail 'Expected Snake and Otacon productionSurfaceReader.error to be null or false.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-snake-reader-diagnostics-no-prohibited-errors' -Passed (-not $snakeReaderDiagnosticContainsProhibited) -Expected $false -Observed $snakeReaderDiagnosticContainsProhibited -Detail "Expected Snake and Otacon reader diagnostics to avoid DECODE/SELECTOR reasons. Found: $($snakeReaderDiagnosticReasonValues -join ', ')"
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-snake-surface-provenance-line-239-surface-0-or-9' -Passed $snakeSurfaceLine239FoundForSurface0Or9 -Expected $true -Observed $snakeSurfaceLine239FoundForSurface0Or9 -Detail 'Expected Snake and Otacon parsed surface entry provenance to include line 239 for surface 0 or 9.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-snake-surface-provenance-line-285-surface-8' -Passed $snakeSurfaceLine285FoundForSurface8 -Expected $true -Observed $snakeSurfaceLine285FoundForSurface8 -Detail 'Expected Snake and Otacon parsed surface entry provenance to include line 285 for surface 8.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-snake-surface-provenance-line-394-surface-19-or-40' -Passed $snakeSurfaceLine394FoundForSurface19Or40 -Expected $true -Observed $snakeSurfaceLine394FoundForSurface19Or40 -Detail 'Expected Snake and Otacon parsed surface entry provenance to include line 394 for surface 19 or 40.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-snake-dialogue-sequence-count' -Passed ($snakeSequenceCount -eq 4) -Expected 4 -Observed $snakeSequenceCount -Detail 'Expected Snake and Otacon dialogue sequence to include exactly 4 events.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-dialogue-step-0-event-id' -Result $snakeStepOnBoot -Path 'eventId' -Expected 'OnBoot' -Detail 'Expected Snake and Otacon dialogue step 1 eventId OnBoot.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-dialogue-step-1-event-id' -Result $snakeStepOnFirstBoot -Path 'eventId' -Expected 'OnFirstBoot' -Detail 'Expected Snake and Otacon dialogue step 2 eventId OnFirstBoot.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-dialogue-step-2-event-id' -Result $snakeStepFirstChoiceSelect -Path 'eventId' -Expected 'OnChoiceSelect' -Detail 'Expected Snake and Otacon dialogue step 3 eventId OnChoiceSelect.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-dialogue-step-3-event-id' -Result $snakeStepSecondChoiceSelect -Path 'eventId' -Expected 'OnChoiceSelect' -Detail 'Expected Snake and Otacon dialogue step 4 eventId OnChoiceSelect.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-snake-dialogue-step-0-ref-shell' -Passed (($null -ne $snakeOnBootRefs -and $snakeOnBootRefs.Count -eq 1 -and $snakeOnBootRef0 -eq 'Snake and Otacon - Solid Shell') -and ($snakeOnBootStatus2xx -and $snakeOnBootOutcome -eq 'success' -and $snakeOnBootValueNonBlank -and $null -eq $snakeOnBootFailure -and $snakeOnBootTokenizerCount -eq 0) ) -Expected $true -Observed (($snakeOnBootRefs.Count -eq 1) -and $snakeOnBootRef0) -Detail 'Expected Snake and Otacon step 1 to be GET, success, 2xx, nonblank value, no failure, no tokenizer diagnostics, and shell-name reference exact "Snake and Otacon - Solid Shell".'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-dialogue-step-0-method' -Result $snakeStepOnBoot -Path 'method' -Expected 'GET' -Detail 'Expected Snake and Otacon OnBoot method GET.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-dialogue-step-1-ref-0' -Result $snakeStepOnFirstBoot -Path 'references[0]' -Expected '0' -Detail 'Expected Snake and Otacon OnFirstBoot refs[0] = 0.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-snake-dialogue-step-1-passive-true-choice-id' -Passed (($snakeOnFirstBootPassiveTransitions -contains $true) -and ($snakeOnFirstBootChoiceIds -contains 'choicefirsthehim') -and $snakeOnFirstBootStatus2xx -and $snakeOnFirstBootOutcome -eq 'success' -and $snakeOnFirstBootValueNonBlank -and $null -eq $snakeOnFirstBootFailure -and $snakeOnFirstBootTokenizerDiagnosticsExpected) -Expected $true -Observed ([pscustomobject]@{ passiveAndChoice = (($snakeOnFirstBootPassiveTransitions -contains $true) -and ($snakeOnFirstBootChoiceIds -contains 'choicefirsthehim')); tokenizerDiagnostics = @($snakeOnFirstBootTokenizerDiagnostics); tokenizerDiagnosticsExpected = $snakeOnFirstBootTokenizerDiagnosticsExpected }) -Detail 'Expected Snake and Otacon OnFirstBoot to expose passive=true and choicefirsthehim with no tokenizer diagnostics beyond its known presentation markers.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-dialogue-step-1-method' -Result $snakeStepOnFirstBoot -Path 'method' -Expected 'GET' -Detail 'Expected Snake and Otacon OnFirstBoot method GET.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-snake-dialogue-step-2-ref-choice-first' -Passed (($snakeFirstChoiceSelectRefs.Count -eq 1) -and ($snakeFirstChoiceSelectRef0 -eq 'choicefirsthehim') -and ($snakeFirstChoiceSelectPassiveTransitions -contains $false) -and ($snakeFirstChoiceSelectStatus2xx -and $snakeFirstChoiceSelectOutcome -eq 'success' -and $snakeFirstChoiceSelectValueNonBlank -and $null -eq $snakeFirstChoiceSelectFailure -and $snakeFirstChoiceSelectTokenizerCount -eq 0) -and $snakeFirstChoiceSelectInputDispatchId -eq 'OnNameTeach' -and [string]::Equals([string]$snakeFirstChoiceSelectInputTimeout, '-1')) -Expected $true -Observed ($snakeFirstChoiceSelectRefs.Count -eq 1 -and $snakeFirstChoiceSelectRef0) -Detail 'Expected Snake and Otacon first OnChoiceSelect to transition passive=false, reference choicefirsthehim, expose OnNameTeach timeout=-1, and be successful GET.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-dialogue-step-2-method' -Result $snakeStepFirstChoiceSelect -Path 'method' -Expected 'GET' -Detail 'Expected Snake and Otacon first OnChoiceSelect method GET.'
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'slice2-snake-dialogue-step-3-ref-faq' -Passed (($snakeFaqRefs.Count -eq 1) -and ($snakeFaqRef0 -eq 'faq') -and $snakeFaqStatus2xx -and $snakeFaqOutcome -eq 'success' -and $snakeFaqValueNonBlank -and $null -eq $snakeFaqFailure -and $snakeFaqTokenizerDiagnosticsExpected -and ($snakeFaqAnchorIds -contains 'whoSnake') -and ($snakeFaqAnchorIds -contains 'whoHal')) -Expected $true -Observed ([pscustomobject]@{ faqReference = (($snakeFaqRefs.Count -eq 1) -and ($snakeFaqRef0 -eq 'faq')); anchors = @($snakeFaqAnchorIds); tokenizerDiagnostics = @($snakeFaqTokenizerDiagnostics); tokenizerDiagnosticsExpected = $snakeFaqTokenizerDiagnosticsExpected }) -Detail 'Expected Snake and Otacon second OnChoiceSelect to target faq, be successful GET, include whoSnake/whoHal anchors, and have no tokenizer diagnostics beyond its known presentation markers.'
    Add-SentinelNestedCheck -Accumulator $globalSentinels -Name 'slice2-snake-dialogue-step-3-method' -Result $snakeStepSecondChoiceSelect -Path 'method' -Expected 'GET' -Detail 'Expected Snake and Otacon second OnChoiceSelect method GET.'

    $runEnd = Get-Date
    $runSeconds = [int]($runEnd - $runStart).TotalSeconds

    $invalidResultRows = @()
    foreach ($result in $results) {
        $resultLabel = if ($result.label) { $result.label } else { '<missing>' }
        $postCleanupPrivate = [System.Collections.ArrayList]::new()
        $postCleanupOutput = [System.Collections.ArrayList]::new()
        $postCleanupTmp = [System.Collections.ArrayList]::new()
        $remainingPaths = @()
        $hostVerified = $false
        $passedValue = $null
        $status = $null
        $foundPostPrivate = $false
        $foundPostOutput = $false
        $foundPostTmp = $false
        $foundCleanupPaths = $false
        $foundHostVerified = $false
        $foundPassed = $false
        $foundStatus = $false

        $temp = Get-NestedPropertyValue -Object $result -Path 'postCleanupPrivateSnapshot' -Found ([ref]$foundPostPrivate)
        if ($foundPostPrivate -and $null -ne $temp) { $temp | ForEach-Object { $postCleanupPrivate.Add($_) | Out-Null } }
        $temp = Get-NestedPropertyValue -Object $result -Path 'postCleanupOutputSnapshot' -Found ([ref]$foundPostOutput)
        if ($foundPostOutput -and $null -ne $temp) { $temp | ForEach-Object { $postCleanupOutput.Add($_) | Out-Null } }
        $temp = Get-NestedPropertyValue -Object $result -Path 'postCleanupTmpSnapshot' -Found ([ref]$foundPostTmp)
        if ($foundPostTmp -and $null -ne $temp) { $temp | ForEach-Object { $postCleanupTmp.Add($_) | Out-Null } }
        $remainingPaths = Get-NestedPropertyValue -Object $result -Path 'cleanup.remainingTestOwnedPaths' -Found ([ref]$foundCleanupPaths)
        if ($null -eq $remainingPaths) { $remainingPaths = @() }

        $hostVerified = Get-NestedPropertyValue -Object $result -Path 'cleanup.hostVerified' -Found ([ref]$foundHostVerified)
        $statusValue = Get-NestedPropertyValue -Object $result -Path 'status' -Found ([ref]$foundStatus)
        $passedValue = Get-NestedPropertyValue -Object $result -Path 'passed' -Found ([ref]$foundPassed)

        if (
            -not $foundPostPrivate -or
            -not $foundPostOutput -or
            -not $foundPostTmp -or
            -not $foundCleanupPaths -or
            -not $foundHostVerified -or
            -not $foundStatus -or
            -not $foundPassed -or
            $statusValue -ne 'ok' -or
            $postCleanupPrivate.Count -ne 0 -or
            $postCleanupOutput.Count -ne 0 -or
            $postCleanupTmp.Count -ne 0 -or
            -not (Compare-NumericWithTolerance -Expected 0 -Actual $remainingPaths.Count -Tolerance 0) -or
            -not $hostVerified -or
            -not ([bool]$passedValue)
        ) {
            $invalidResultRows += "$resultLabel"
        }
    }
    Add-SentinelCheck -Accumulator $globalSentinels -Name 'result-rows' -Passed ($invalidResultRows.Count -eq 0) -Expected 0 -Observed $invalidResultRows.Count -Detail "Rows failing host cleanup/status constraints: $($invalidResultRows -join ', ')"
    $failedSentinelChecks = @($globalSentinels.checks | Where-Object { -not $_.passed }).Count

    $summary = [pscustomobject]@{
        runId = $runId
        manifest = $manifestEntryName
        manifestSha256 = $manifestSha
        startedAt = $runStart.ToUniversalTime().ToString('o')
        finishedAt = $runEnd.ToUniversalTime().ToString('o')
        durationSeconds = $runSeconds
        git = @{
            commit = (git rev-parse HEAD).Trim()
            manifestFile = (Resolve-Path (Join-Path $repoRoot $ManifestPath)).Path
            manifestBytes = (Get-Item (Join-Path $repoRoot $ManifestPath)).Length
        }
        device = @{
            serial = $DeviceSerial
            fingerprint = $deviceFingerprint
            api = [int]$deviceApi
            abi = $deviceAbi
            density = [int]$deviceDensity
        }
        host = @{
            fixedSeed = $fixedSeed
            networkDisabled = $true
            runAs = $true
            timeoutMinutes = $PerArchiveTimeoutMinutes
        }
        apks = @{
            debugPath = $apkInfo.DebugApkPath
            debugSha256 = $apkInfo.DebugApkSha256
            testPath = $apkInfo.TestApkPath
            testSha256 = $apkInfo.TestApkSha256
            debugSigned = -not [string]::IsNullOrWhiteSpace($apksigner)
            runAsVerified = $true
        }
        results = $results
        failures = $failures
        corpusRoots = $resolvedCorpusRoots
        unexpectedAbort = $unexpectedAbort
        unexpectedAbortMetadata = $unexpectedAbortMetadata
        abortedDueToTimeout = $abortedDueToTimeout
        abortedEntryLabel = $abortedEntryLabel
        cleanupVerification = $cleanupVerification
        sentinels = $globalSentinels
    }

    $summaryPath = Join-Path $reportRoot 'summary.json'
    ConvertTo-NarCorpusJson -Value $summary | Set-Content -Path $summaryPath -Encoding UTF8

    $sentinelMarkdownHeader = Get-NarCorpusSentinelMarkdownHeader

    $summaryMd = @"
# NAR corpus audit summary

- Run ID: $runId
- Device: $DeviceSerial ($deviceFingerprint)
- Android API: $deviceApi, ABI: $deviceAbi, density: $deviceDensity
- Fixed seed: $fixedSeed
- APKs:
  - debug sha256: $($apkInfo.DebugApkSha256)
  - test sha256: $($apkInfo.TestApkSha256)
- Manifest: $manifestEntryName ($manifestSha)
- Duration: $runSeconds seconds
- Corpus entries: $($manifest.entries.Count)
- Results: $($results.Count)
- Failures: $($failures.Count)
- Aborted due to timeout: $abortedDueToTimeout
- Aborted entry: $abortedEntryLabel
- Cleanup verification: $cleanupVerification
- Sentinel checks passed: $($globalSentinels.passed)
- Sentinels failed: $failedSentinelChecks

## Result rows

| Label | Classification | Install | Ghost | Render | Input | SHIORI | Screenshot |
| --- | --- | --- | --- | --- | --- | --- | --- |
$(
  $results | ForEach-Object {
      "| $($_.label) | $($_.classification) | $($_.installOutcome) | $($_.ghostLoadOutcome) | $($_.renderOutcome) | $($_.inputOutcome) | $($_.shioriOutcome) | $($null -ne $_.screenshotPath) |"
  } | Out-String
)

## Sentinel checks

$sentinelMarkdownHeader
$(
  $globalSentinels.checks | ForEach-Object {
      "| $($_.name) | $($_.passed) | $($_.expected) | $($_.observed) | $($_.detail) |"
  } | Out-String
)
"@

    $summaryMd | Set-Content -Path (Join-Path $reportRoot 'summary.md') -Encoding UTF8

    Write-Host "Summary: $(Join-Path $reportRoot 'summary.json')"

    if (-not $globalSentinels.passed -or $failures.Count -gt 0) {
        ThrowIf "Run finished with $($failures.Count) failed archives and $failedSentinelChecks sentinel failures. Check summary/failure files."
    }
}
finally {
    if (Test-AdbTransportAvailable -TransportTimedOut $script:adbTransportTimedOut) {
        if ($networkState) {
            Set-NetworkState -Disable $false -Snapshot $networkState
        }
        if ($installed) {
            try { Invoke-Adb -Arguments @('uninstall', $targetPackage) -TimeoutSeconds 120 | Out-Null } catch {}
            try { Invoke-Adb -Arguments @('uninstall', $testPackage) -TimeoutSeconds 120 | Out-Null } catch {}
            try {
                Remove-RemotePath -Path "/sdcard/Android/data/$targetPackage/files/nar-corpus" -TrimParents $true
                Remove-RemotePath -Path "/sdcard/Android/data/$targetPackage/files"
                Remove-RemotePath -Path "/sdcard/Android/data/$targetPackage"
            } catch {}
        }
        try { Remove-RemotePath -Path $tmpRunSafeRoot -TrimParents $true } catch {}
    }
    Remove-Item -LiteralPath $hostRunTmpRoot -Recurse -Force -ErrorAction SilentlyContinue
}
