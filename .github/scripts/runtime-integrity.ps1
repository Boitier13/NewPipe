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

function Assert-RuntimeListing {
    param([string]$Listing)
    $sections = $Listing -split '(?m)^----------\r?$'
    if ($sections.Count -lt 2) { throw 'Unrecognized 7-Zip listing' }
    $entries = $sections[1]
    $paths = @([regex]::Matches($entries, '(?m)^Path = (.+)\r?$'))
    if ($paths.Count -lt 1 -or $paths.Count -gt 20000) { throw 'Invalid archive entry count' }
    foreach ($match in $paths) {
        $path = $match.Groups[1].Value.TrimEnd("`r")
        if ($path -match '(^[\\/]|:|(^|[\\/])\.\.([\\/]|$))') { throw "Unsafe archive path: $path" }
    }
    if ($entries -match '(?m)^(Symbolic Link|Hard Link) = ') { throw 'Archive links are not allowed' }
    [long]$expanded = 0
    foreach ($match in [regex]::Matches($entries, '(?m)^Size = ([0-9]+)\r?$')) {
        $expanded += [long]$match.Groups[1].Value
        if ($expanded -gt 2GB) { throw 'Expanded runtime archive is too large' }
    }
}
