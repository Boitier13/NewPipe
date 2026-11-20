# SPDX-License-Identifier: GPL-3.0-or-later
function Assert-RuntimeArchive {
    param([string]$Path, [string]$Sha256, [long]$ExpectedSize = -1)
    if ($Sha256 -notmatch '^[0-9a-f]{64}$') { throw 'Invalid expected SHA-256' }
    $file = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($file.PSIsContainer -or $file.Length -eq 0) { throw 'Empty or invalid runtime archive' }
    if ($ExpectedSize -ge 0 -and $file.Length -ne $ExpectedSize) { throw 'Incomplete runtime archive' }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -cne $Sha256) { throw 'SHA-256 mismatch; runtime archive must not be used' }
}
