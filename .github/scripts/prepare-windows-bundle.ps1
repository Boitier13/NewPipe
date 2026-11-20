[CmdletBinding()]
param(
    [string]$OutputDir = "desktopApp/app-resources/windows-x64",
    [switch]$ValidatePathsOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Invoke-WithRetry {
    param(
        [scriptblock]$Action,
        [int]$MaxAttempts = 3,
        [int]$DelaySeconds = 5,
        [string]$Label = "operation"
    )
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        try {
            return & $Action
        } catch {
            if ($attempt -eq $MaxAttempts) {
                throw "Failed $Label after $MaxAttempts attempts: $($_.Exception.Message)"
            }
            Write-Warning "$Label attempt $attempt failed: $($_.Exception.Message). Retrying in ${DelaySeconds}s..."
            Start-Sleep -Seconds $DelaySeconds
        }
    }
}

# This script removes generated output. Never accept arbitrary user directories, even
# when invoked from another working directory. Reject junctions/symlinks in ancestors.
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../.."))
$expectedOut = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot "desktopApp/app-resources/windows-x64"))
$resolvedOut = if ([System.IO.Path]::IsPathRooted($OutputDir)) {
    [System.IO.Path]::GetFullPath($OutputDir)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $OutputDir))
}
if (-not [string]::Equals($resolvedOut, $expectedOut, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputDir must be the generated directory: $expectedOut"
}
$ancestor = $resolvedOut
while ($ancestor) {
    if (Test-Path -LiteralPath $ancestor) {
        $item = Get-Item -LiteralPath $ancestor -Force
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Refusing linked output directory or ancestor: $ancestor"
        }
    }
    $ancestor = [System.IO.Path]::GetDirectoryName($ancestor)
}
if ($ValidatePathsOnly) {
    Write-Host "Bundle output path validated without writing files: $resolvedOut"
    return
}

$sevenZip = (Get-Command 7z -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1)
if (-not $sevenZip -and (Test-Path "C:\Program Files\7-Zip\7z.exe")) {
    $sevenZip = "C:\Program Files\7-Zip\7z.exe"
}
if (-not $sevenZip) {
    throw "7z executable was not found on PATH or at C:\Program Files\7-Zip\7z.exe"
}
Write-Host "Using 7-Zip: $sevenZip"

# Never erase an existing bundle. A failed verification must leave previous output intact.
if (Test-Path -LiteralPath $resolvedOut) { throw "Output already exists; preserve it and prepare from a clean checkout: $resolvedOut" }
$manifestPath = Join-Path $repositoryRoot '.github/windows-runtimes.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
. (Join-Path $PSScriptRoot 'runtime-integrity.ps1')
$workBase = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [System.IO.Path]::GetTempPath() }
$workDir = Join-Path $workBase ("boitiertube-win-bundle-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $workDir | Out-Null
$stage = Join-Path $workDir 'verified-bundle'
New-Item -ItemType Directory -Path $stage | Out-Null

foreach ($name in @('ffmpeg', 'vlc')) {
    $spec = $manifest.$name
    $uri = [uri]$spec.url
    $allowedHost = if ($name -eq 'ffmpeg') { 'github.com' } else { 'download.videolan.org' }
    if ($uri.Scheme -ne 'https' -or $uri.Host -ne $allowedHost -or $spec.sha256 -notmatch '^[0-9a-f]{64}$') {
        throw "Invalid pinned provenance for $name"
    }
    $archive = Join-Path $workDir "$name.7z"
    Invoke-WithRetry -Label "download pinned $name $($spec.version)" -Action {
        Invoke-WebRequest -Uri $spec.url -OutFile $archive
    }
    $length = (Get-Item -LiteralPath $archive).Length
    if ($length -lt 5MB -or $length -gt 300MB) { throw "Invalid archive length for $name" }
    if ($name -eq 'ffmpeg' -and $length -ne $spec.size) { throw 'Incomplete FFmpeg archive' }
    # This gate precedes ALL extraction and execution of downloaded content.
    $expectedSize = if ($name -eq 'ffmpeg') { [long]$spec.size } else { -1 }
    Assert-RuntimeArchive -Path $archive -Sha256 $spec.sha256 -ExpectedSize $expectedSize
    & $sevenZip t $archive | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Corrupt $name archive" }
    $listing = & $sevenZip l -slt $archive
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect $name archive" }
    Assert-RuntimeListing -Listing ($listing -join "`n")
    $extract = Join-Path $workDir "$name-extracted"
    & $sevenZip x $archive "-o$extract" -y | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Cannot extract $name archive" }
    if (Get-ChildItem -LiteralPath $extract -Recurse -Force | Where-Object { ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 }) {
        throw "Linked content in $name archive is not supported"
    }
    $binary = if ($name -eq 'ffmpeg') { 'ffmpeg.exe' } else { 'libvlc.dll' }
    $found = @(Get-ChildItem -LiteralPath $extract -Recurse -File -Filter $binary)
    if ($found.Count -ne 1) { throw "Expected exactly one $binary" }
    # Preserve the complete upstream distribution, including licenses and notices.
    $root = if ($name -eq 'ffmpeg') { $found[0].Directory.Parent.FullName } else { $found[0].Directory.FullName }
    $destination = Join-Path $stage $name
    New-Item -ItemType Directory -Path $destination | Out-Null
    Copy-Item -Path (Join-Path $root '*') -Destination $destination -Recurse
    if ($name -eq 'ffmpeg') {
        # Keep the existing discovery contract ffmpeg/ffmpeg.exe.
        Copy-Item -LiteralPath $found[0].FullName -Destination (Join-Path $destination 'ffmpeg.exe')
        $version = & (Join-Path $destination 'ffmpeg.exe') -version 2>&1
        if ($LASTEXITCODE -ne 0 -or ($version -join "`n") -notmatch ([regex]::Escape("ffmpeg version $($spec.version)"))) {
            throw 'Pinned FFmpeg executable failed version verification'
        }
    } else {
        foreach ($required in @('libvlccore.dll', 'plugins', 'COPYING.txt')) {
            if (-not (Test-Path -LiteralPath (Join-Path $destination $required))) { throw "Missing VLC component: $required" }
        }
    }
}
Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $stage 'windows-runtimes.json')
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resolvedOut) | Out-Null
# Publish only after BOTH archives have passed verification. No existing output is overwritten.
Move-Item -LiteralPath $stage -Destination $resolvedOut
Write-Host "Pinned, verified runtime bundle published at $resolvedOut. Diagnostic downloads remain in $workDir"
