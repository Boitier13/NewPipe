# SPDX-License-Identifier: GPL-3.0-or-later
# Read-only output-path validation plus generated temporary hash fixtures; no downloads or cleanup.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = Split-Path -Parent $PSScriptRoot
$script = Join-Path $root '.github/scripts/prepare-windows-bundle.ps1'
$engine = (Get-Process -Id $PID).Path
# Test from an unrelated working directory: relative defaults must still resolve to the repo.
Push-Location ([System.IO.Path]::GetTempPath())
try {
    & $engine -NoProfile -File $script -ValidatePathsOnly
    if ($LASTEXITCODE -ne 0) { throw 'Default output path was rejected.' }
    & $engine -NoProfile -File $script -OutputDir (Join-Path $root 'desktopApp/app-resources/windows-x64') -ValidatePathsOnly
    if ($LASTEXITCODE -ne 0) { throw 'Expected absolute output path was rejected.' }
    foreach ($unsafe in @($root, $PSScriptRoot, '..', '.', 'desktopApp/app-resources/windows-x64/../..', ([System.IO.Path]::GetTempPath()))) {
        & $engine -NoProfile -File $script -OutputDir $unsafe -ValidatePathsOnly
        if ($LASTEXITCODE -eq 0) { throw "Unsafe output path was accepted: $unsafe" }
    }
} finally {
    Pop-Location
}
# SHA verification tests use synthetic bytes only; no downloaded content is executed.
. (Join-Path $root '.github/scripts/runtime-integrity.ps1')
$fixture = [IO.Path]::GetTempFileName()
[IO.File]::WriteAllText($fixture, 'verified runtime fixture')
$hash = (Get-FileHash $fixture -Algorithm SHA256).Hash.ToLowerInvariant()
$length = (Get-Item $fixture).Length
Assert-RuntimeArchive -Path $fixture -Sha256 $hash -ExpectedSize $length
foreach ($case in @('checksum', 'truncated', 'empty')) {
    $rejected = $false
    try {
        if ($case -eq 'checksum') { Assert-RuntimeArchive -Path $fixture -Sha256 ('0' * 64) }
        elseif ($case -eq 'truncated') { Assert-RuntimeArchive -Path $fixture -Sha256 $hash -ExpectedSize ($length + 1) }
        else { [IO.File]::WriteAllText($fixture, ''); Assert-RuntimeArchive -Path $fixture -Sha256 $hash }
    } catch { $rejected = $true }
    if (-not $rejected) { throw "Invalid archive accepted: $case" }
}
Write-Host 'PASS: expected output accepted, arbitrary output rejected; hash validation rejects corrupt/incomplete fixtures.'

# Actions propagates LASTEXITCODE: the last rejected child intentionally returned 1.
exit 0
