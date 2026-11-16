[CmdletBinding()]
param(
    [string]$OutputDir = "desktopApp/app-resources/windows-x64"
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

$sevenZip = (Get-Command 7z -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1)
if (-not $sevenZip -and (Test-Path "C:\Program Files\7-Zip\7z.exe")) {
    $sevenZip = "C:\Program Files\7-Zip\7z.exe"
}
if (-not $sevenZip) {
    throw "7z executable was not found on PATH or at C:\Program Files\7-Zip\7z.exe"
}
Write-Host "Using 7-Zip: $sevenZip"

$resolvedOut = [System.IO.Path]::GetFullPath($OutputDir)
$ffmpegDest = Join-Path $resolvedOut "ffmpeg"
$vlcDest = Join-Path $resolvedOut "vlc"
if (Test-Path $resolvedOut) {
    Remove-Item -Recurse -Force $resolvedOut
}
New-Item -ItemType Directory -Force -Path $ffmpegDest, $vlcDest | Out-Null

$workBase = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [System.IO.Path]::GetTempPath() }
$workDir = Join-Path $workBase "newpipe-win-bundle"
if (Test-Path $workDir) {
    Remove-Item -Recurse -Force $workDir
}
New-Item -ItemType Directory -Force -Path $workDir | Out-Null

try {
    Write-Host "=== Downloading portable FFmpeg (win64) ==="
    $ghHeaders = @{ "User-Agent" = "NewPipe-Desktop-CI" }
    if ($env:GITHUB_TOKEN) {
        $ghHeaders["Authorization"] = "Bearer $($env:GITHUB_TOKEN)"
    }

    $ffmpegUrls = New-Object System.Collections.Generic.List[string]
    try {
        $gyanRelease = Invoke-WithRetry -Label "query GyanD/codexffmpeg release" -Action {
            Invoke-RestMethod -Uri "https://api.github.com/repos/GyanD/codexffmpeg/releases/latest" -Headers $ghHeaders
        }
        $gyanAsset = @($gyanRelease.assets | Where-Object { $_.name -like "*-essentials_build.7z" }) | Select-Object -First 1
        if ($null -ne $gyanAsset -and $gyanAsset.browser_download_url) {
            $ffmpegUrls.Add($gyanAsset.browser_download_url)
        }
    } catch {
        Write-Warning "GyanD GitHub API lookup failed: $($_.Exception.Message)"
    }
    $ffmpegUrls.Add("https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.7z")

    $ffmpegArchive = Join-Path $workDir "ffmpeg.7z"
    $downloadedFfmpeg = $false
    foreach ($url in $ffmpegUrls) {
        try {
            Write-Host "Fetching FFmpeg from: $url"
            Invoke-WithRetry -Label "download FFmpeg ($url)" -Action {
                Invoke-WebRequest -Uri $url -OutFile $ffmpegArchive -Headers @{ "User-Agent" = "NewPipe-Desktop-CI" }
            }
            if ((Test-Path $ffmpegArchive) -and (Get-Item $ffmpegArchive).Length -gt 5MB) {
                $downloadedFfmpeg = $true
                break
            }
        } catch {
            Write-Warning "FFmpeg download from $url failed: $($_.Exception.Message)"
        }
    }
    if (-not $downloadedFfmpeg) {
        throw "Unable to download portable FFmpeg from any configured source"
    }

    $ffmpegExtract = Join-Path $workDir "ffmpeg-extract"
    & $sevenZip x $ffmpegArchive "-o$ffmpegExtract" -y | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "7z extraction failed for FFmpeg (exit=$LASTEXITCODE)"
    }

    $ffmpegExeSrc = Get-ChildItem -Path $ffmpegExtract -Recurse -Filter "ffmpeg.exe" | Select-Object -First 1
    if ($null -eq $ffmpegExeSrc) {
        throw "ffmpeg.exe not found in extracted archive"
    }
    $ffmpegExeDest = Join-Path $ffmpegDest "ffmpeg.exe"
    Copy-Item -Path $ffmpegExeSrc.FullName -Destination $ffmpegExeDest -Force
    $ffmpegVersionOutput = & $ffmpegExeDest -version 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Bundled ffmpeg.exe smoke check failed (exit=$LASTEXITCODE)"
    }
    $ffmpegVersionOutput | Select-Object -First 2 | ForEach-Object { Write-Host $_ }
    $ffmpegSizeMb = [Math]::Round((Get-Item $ffmpegExeDest).Length / 1MB, 1)
    Write-Host "Bundled ffmpeg.exe ready ($ffmpegSizeMb MB)"

    Write-Host "=== Downloading portable VLC (win64) ==="
    $vlcUrls = New-Object System.Collections.Generic.List[string]
    try {
        $vlcIndex = Invoke-WithRetry -Label "fetch VideoLAN win64 index" -Action {
            (Invoke-WebRequest -Uri "https://download.videolan.org/pub/videolan/vlc/last/win64/" -Headers @{ "User-Agent" = "NewPipe-Desktop-CI" }).Content
        }
        $match = [regex]::Match($vlcIndex, 'href="(vlc-[0-9A-Za-z._-]+-win64\.7z)"')
        if ($match.Success) {
            $vlcFileName = $match.Groups[1].Value
            $vlcUrls.Add("https://download.videolan.org/pub/videolan/vlc/last/win64/$vlcFileName")
        }
    } catch {
        Write-Warning "Failed to inspect VideoLAN last/win64 directory: $($_.Exception.Message)"
    }
    $vlcUrls.Add("https://get.videolan.org/vlc/3.0.21/win64/vlc-3.0.21-win64.7z")

    $vlcArchive = Join-Path $workDir "vlc.7z"
    $downloadedVlc = $false
    foreach ($url in $vlcUrls) {
        try {
            Write-Host "Fetching VLC from: $url"
            Invoke-WithRetry -Label "download VLC ($url)" -Action {
                Invoke-WebRequest -Uri $url -OutFile $vlcArchive -Headers @{ "User-Agent" = "NewPipe-Desktop-CI" }
            }
            if ((Test-Path $vlcArchive) -and (Get-Item $vlcArchive).Length -gt 10MB) {
                $downloadedVlc = $true
                break
            }
        } catch {
            Write-Warning "VLC download from $url failed: $($_.Exception.Message)"
        }
    }
    if (-not $downloadedVlc) {
        throw "Unable to download portable VLC win64 archive from any configured source"
    }

    $vlcExtract = Join-Path $workDir "vlc-extract"
    & $sevenZip x $vlcArchive "-o$vlcExtract" -y | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "7z extraction failed for VLC (exit=$LASTEXITCODE)"
    }

    $libVlcFile = Get-ChildItem -Path $vlcExtract -Recurse -Filter "libvlc.dll" | Select-Object -First 1
    if ($null -eq $libVlcFile) {
        throw "libvlc.dll not found in extracted VLC archive"
    }
    $vlcRoot = $libVlcFile.Directory.FullName
    Write-Host "Extracted VLC root: $vlcRoot"

    $libVlcCoreSrc = Join-Path $vlcRoot "libvlccore.dll"
    $pluginsSrc = Join-Path $vlcRoot "plugins"
    if (-not (Test-Path $libVlcCoreSrc) -or -not (Test-Path $pluginsSrc)) {
        throw "Expected libvlccore.dll and plugins/ alongside libvlc.dll in $vlcRoot"
    }

    Copy-Item -Path $libVlcFile.FullName -Destination (Join-Path $vlcDest "libvlc.dll") -Force
    Copy-Item -Path $libVlcCoreSrc -Destination (Join-Path $vlcDest "libvlccore.dll") -Force
    Copy-Item -Path $pluginsSrc -Destination (Join-Path $vlcDest "plugins") -Recurse -Force

    # Remove desktop Qt/skins GUI plugins unused by embedded libVLC playback.
    $guiPluginsDir = Join-Path $vlcDest "plugins\gui"
    if (Test-Path $guiPluginsDir) {
        Remove-Item -Recurse -Force $guiPluginsDir
    }

    # Regenerate plugins.dat to match the pruned plugin set cleanly and prevent stale-cache delays.
    $cacheGenSrc = Join-Path $vlcRoot "vlc-cache-gen.exe"
    $pluginsDest = Join-Path $vlcDest "plugins"
    $pluginsDat = Join-Path $pluginsDest "plugins.dat"
    if (Test-Path $cacheGenSrc) {
        $cacheGenTmp = Join-Path $vlcDest "vlc-cache-gen.exe"
        Copy-Item -Path $cacheGenSrc -Destination $cacheGenTmp -Force
        try {
            & $cacheGenTmp $pluginsDest
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "vlc-cache-gen.exe returned exit=$LASTEXITCODE; removing plugins.dat"
                if (Test-Path $pluginsDat) { Remove-Item -Force $pluginsDat }
            } else {
                Write-Host "Regenerated VLC plugins.dat"
            }
        } finally {
            if (Test-Path $cacheGenTmp) { Remove-Item -Force $cacheGenTmp }
        }
    } elseif (Test-Path $pluginsDat) {
        Remove-Item -Force $pluginsDat
    }

    $vlcBytes = (Get-ChildItem -Path $vlcDest -Recurse -File | Measure-Object -Property Length -Sum).Sum
    $vlcTotalMb = [Math]::Round($vlcBytes / 1MB, 1)
    Write-Host "Bundled VLC ready ($vlcTotalMb MB across libvlc.dll, libvlccore.dll, plugins/)"
} finally {
    if (Test-Path $workDir) {
        Remove-Item -Recurse -Force $workDir -ErrorAction SilentlyContinue
    }
}
