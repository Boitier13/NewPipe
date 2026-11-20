/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.russhwolf.settings.Settings
import net.newpipe.app.composable.EditTextPreference
import net.newpipe.app.composable.ListPreference
import net.newpipe.app.composable.ListPreferenceEntry
import net.newpipe.app.composable.PreferenceCategoryTitle
import net.newpipe.app.composable.SwitchPreference
import net.newpipe.app.preferences.DesktopPreferences
import org.jetbrains.compose.resources.stringResource
import newpipe.shared.generated.resources.Res
import newpipe.shared.generated.resources.desktop_runtime_data_saver_desktop
import newpipe.shared.generated.resources.desktop_runtime_data_saver_mode
import newpipe.shared.generated.resources.desktop_runtime_data_saver_summary
import newpipe.shared.generated.resources.desktop_runtime_data_saver_resolution
import newpipe.shared.generated.resources.desktop_runtime_data_saver_audio
import newpipe.shared.generated.resources.desktop_runtime_data_saver_audio_summary
import newpipe.shared.generated.resources.desktop_runtime_windows_runtimes
import newpipe.shared.generated.resources.desktop_runtime_vlc_folder
import newpipe.shared.generated.resources.desktop_runtime_auto_bundled
import newpipe.shared.generated.resources.desktop_runtime_auto_vlc
import newpipe.shared.generated.resources.desktop_runtime_vlc_detected
import newpipe.shared.generated.resources.desktop_runtime_vlc_missing
import newpipe.shared.generated.resources.desktop_runtime_ffmpeg_path
import newpipe.shared.generated.resources.desktop_runtime_auto_path
import newpipe.shared.generated.resources.desktop_runtime_ffmpeg_detected
import newpipe.shared.generated.resources.desktop_runtime_ffmpeg_missing
import newpipe.shared.generated.resources.desktop_runtime_mp3_bitrate
import newpipe.shared.generated.resources.desktop_runtime_tray_audio
import newpipe.shared.generated.resources.desktop_runtime_tray_audio_summary
import org.koin.core.annotation.Singleton

@Singleton(binds = [PlayerSettingsSections::class])
class JVMPlayerSettingsSections(
    private val settings: Settings,
    private val dataSaverController: DataSaverController,
    private val mediaPlayerController: MediaPlayerController,
    private val downloadController: DownloadController
) : PlayerSettingsSections {

    override val isAvailable = true

    @Composable
    override fun Render() {
        val isDataSaver by dataSaverController.isDataSaverEnabled.collectAsState()
        val dataSaverMaxRes by dataSaverController.maxResolution.collectAsState()
        val dataSaverAudioOnly by dataSaverController.preferAudioOnly.collectAsState()
        val playerState by mediaPlayerController.state.collectAsState()

        var vlcPath by remember {
            mutableStateOf(settings.getString(DesktopPreferences.KEY_VLC_PATH, DesktopPreferences.DEFAULT_VLC_PATH))
        }
        var ffmpegPath by remember {
            mutableStateOf(settings.getString(DesktopPreferences.KEY_FFMPEG_PATH, DesktopPreferences.DEFAULT_FFMPEG_PATH))
        }
        var mp3Bitrate by remember {
            mutableStateOf(settings.getString(DesktopPreferences.KEY_MP3_BITRATE, DesktopPreferences.DEFAULT_MP3_BITRATE))
        }
        var minimizeToTray by remember {
            mutableStateOf(
                settings.getBoolean(
                    DesktopPreferences.KEY_MINIMIZE_TO_TRAY_ON_AUDIO,
                    DesktopPreferences.DEFAULT_MINIMIZE_TO_TRAY_ON_AUDIO
                )
            )
        }
        var ffmpegFound by remember { mutableStateOf(downloadController.isFfmpegAvailable()) }

        PreferenceCategoryTitle(title = stringResource(Res.string.desktop_runtime_data_saver_desktop))
        SwitchPreference(
            title = stringResource(Res.string.desktop_runtime_data_saver_mode),
            summary = stringResource(Res.string.desktop_runtime_data_saver_summary),
            checked = isDataSaver,
            onCheckedChange = dataSaverController::setDataSaverEnabled
        )
        ListPreference(
            title = stringResource(Res.string.desktop_runtime_data_saver_resolution),
            entries = DesktopPreferences.DATA_SAVER_RESOLUTION_OPTIONS.map { ListPreferenceEntry(it, it) },
            selectedValue = dataSaverMaxRes,
            onValueSelected = dataSaverController::setMaxResolution,
            enabled = isDataSaver
        )
        SwitchPreference(
            title = stringResource(Res.string.desktop_runtime_data_saver_audio),
            summary = stringResource(Res.string.desktop_runtime_data_saver_audio_summary),
            checked = dataSaverAudioOnly,
            onCheckedChange = dataSaverController::setPreferAudioOnly,
            enabled = isDataSaver
        )

        val hasBundledVlc = remember { WindowsAppPaths.bundledVlcDirectory() != null }
        val hasBundledFfmpeg = remember { WindowsAppPaths.bundledFfmpegExecutable() != null }

        PreferenceCategoryTitle(title = stringResource(Res.string.desktop_runtime_windows_runtimes))
        EditTextPreference(
            title = stringResource(Res.string.desktop_runtime_vlc_folder),
            value = vlcPath,
            summary = buildString {
                append(
                    when {
                        vlcPath.isNotBlank() -> vlcPath
                        hasBundledVlc -> stringResource(Res.string.desktop_runtime_auto_bundled)
                        else -> stringResource(Res.string.desktop_runtime_auto_vlc)
                    }
                )
                append(if (playerState.vlcAvailable) stringResource(Res.string.desktop_runtime_vlc_detected) else stringResource(Res.string.desktop_runtime_vlc_missing))
            },
            onValueChange = { newPath ->
                val trimmed = newPath.trim()
                vlcPath = trimmed
                settings.putString(DesktopPreferences.KEY_VLC_PATH, trimmed)
                mediaPlayerController.recheckVlcAvailability()
            }
        )
        EditTextPreference(
            title = stringResource(Res.string.desktop_runtime_ffmpeg_path),
            value = ffmpegPath,
            summary = buildString {
                append(
                    when {
                        ffmpegPath.isNotBlank() -> ffmpegPath
                        hasBundledFfmpeg -> stringResource(Res.string.desktop_runtime_auto_bundled)
                        else -> stringResource(Res.string.desktop_runtime_auto_path)
                    }
                )
                append(if (ffmpegFound) stringResource(Res.string.desktop_runtime_ffmpeg_detected) else stringResource(Res.string.desktop_runtime_ffmpeg_missing))
            },
            onValueChange = { newPath ->
                val trimmed = newPath.trim()
                ffmpegPath = trimmed
                settings.putString(DesktopPreferences.KEY_FFMPEG_PATH, trimmed)
                ffmpegFound = downloadController.isFfmpegAvailable()
            }
        )
        ListPreference(
            title = stringResource(Res.string.desktop_runtime_mp3_bitrate),
            entries = DesktopPreferences.MP3_BITRATE_OPTIONS.map { ListPreferenceEntry(it, it) },
            selectedValue = mp3Bitrate,
            onValueSelected = { bitrate ->
                mp3Bitrate = bitrate
                settings.putString(DesktopPreferences.KEY_MP3_BITRATE, bitrate)
            }
        )
        SwitchPreference(
            title = stringResource(Res.string.desktop_runtime_tray_audio),
            summary = stringResource(Res.string.desktop_runtime_tray_audio_summary),
            checked = minimizeToTray,
            onCheckedChange = { checked ->
                minimizeToTray = checked
                settings.putBoolean(DesktopPreferences.KEY_MINIMIZE_TO_TRAY_ON_AUDIO, checked)
            }
        )
    }
}
