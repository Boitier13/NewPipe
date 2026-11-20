# SPDX-License-Identifier: GPL-3.0-or-later
# Read-only path validation: no downloads, output creation or cleanup.
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
Write-Host 'PASS: expected output accepted, arbitrary output rejected; no files written.'

# Actions propagates LASTEXITCODE: the last rejected child intentionally returned 1.
exit 0
