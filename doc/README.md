<p align="center"><img src="/assets/boitiertube/logo.svg" width="150" alt="BoitierTube"></p>
<h2 align="center"><b>BoitierTube Desktop (Windows PC)</b></h2>
<h4 align="center">A libre lightweight YouTube desktop client for Windows built with Kotlin &amp; Compose Desktop.</h4>

BoitierTube is a desktop customization of NewPipe. Original copyrights and GPL-3.0-or-later licensing are preserved.

See [identity implementation and validation report](/doc/boitiertube-identity-report.md).

## Features

* **YouTube Search & Explore**: Browse trending videos and search videos, channels, and playlists with autocomplete suggestions — **no Google account required**.
* **VLC / VLCJ Playback**: Hardware-accelerated embedded video playback via libVLC with resolution selection (`144p`–`2160p`), audio track selection, speed control, volume/mute, fullscreen (`Esc` to exit), and resume playback.
* **Background Audio Mode**: Switch any playing stream into background audio-only playback (with persistent mini-player bar and Windows system tray controls).
* **MP4 & MP3 Downloads**:
  * **MP4**: Progressive downloads and pure-Java DASH video+audio muxing (`Mp4FromDashWriter`), with FFmpeg fallback for WebM/VP9/AV1 streams.
  * **MP3**: Audio extraction and transcoding via FFmpeg (`libmp3lame`, configurable `128k` / `192k` / `320k` bitrate + metadata tags).
  * **Original Audio**: Direct M4A and Ogg/Opus downloads without FFmpeg.
* **Local Subscriptions & Feed**: Subscribe to channels locally in SQLite (`%APPDATA%\NewPipeDesktop\newpipe.db`) and refresh your subscriptions feed without logging in.
* **Watch & Search History**: Local SQLite watch history with resume timestamps and search history, plus NewPipe `.zip` / `.json` backup & restore.
* **Data Saver Mode**: Caps playback resolution (configurable `144p`–`480p`), prefers audio-only/low-bitrate streams, and lowers thumbnail quality.

See also [Windows build and validation guide](/doc/boitiertube-windows-build.md).

## Windows Prerequisites

1. **JDK 21** (e.g., Eclipse Temurin 21)
2. **VLC Media Player (64-bit)** installed in the standard Windows path (`C:\Program Files\VideoLAN\VLC`) or configured under **Settings → Video & Audio → VLC / libVLC folder path**.
3. **FFmpeg** (optional, required for **MP3** conversion and non-MP4 DASH muxing):
   ```powershell
   winget install Gyan.FFmpeg
   ```
   Or configure `ffmpeg.exe` under **Settings → Video & Audio → FFmpeg executable path**.

## Build, Test & Package on Windows

```powershell
# Run shared unit and Compose UI tests
.\gradlew.bat :shared:jvmTest

# Run the desktop application directly
.\gradlew.bat :desktopApp:run

# Build Windows MSI / EXE installers (outputs in desktopApp\build\compose\binaries\main\)
.\gradlew.bat :desktopApp:packageMsi
.\gradlew.bat :desktopApp:packageExe
```

## License

NewPipe is Free Software under the [GNU General Public License v3.0](LICENSE).
