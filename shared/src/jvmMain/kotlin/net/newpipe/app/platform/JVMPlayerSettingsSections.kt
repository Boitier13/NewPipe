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

        PreferenceCategoryTitle(title = "Data Saver (Desktop)")
        SwitchPreference(
            title = "Data Saver mode",
            summary = "Limit playback resolution, download low-bitrate audio, and lower thumbnail quality",
            checked = isDataSaver,
            onCheckedChange = dataSaverController::setDataSaverEnabled
        )
        ListPreference(
            title = "Data Saver max resolution",
            entries = DesktopPreferences.DATA_SAVER_RESOLUTION_OPTIONS.map { ListPreferenceEntry(it, it) },
            selectedValue = dataSaverMaxRes,
            onValueSelected = dataSaverController::setMaxResolution,
            enabled = isDataSaver
        )
        SwitchPreference(
            title = "Prefer audio-only playback in Data Saver",
            summary = "Start streams in background audio mode when Data Saver is active",
            checked = dataSaverAudioOnly,
            onCheckedChange = dataSaverController::setPreferAudioOnly,
            enabled = isDataSaver
        )

        PreferenceCategoryTitle(title = "Windows Desktop (VLC & FFmpeg)")
        EditTextPreference(
            title = "VLC / libVLC folder path",
            value = vlcPath,
            summary = buildString {
                append(if (vlcPath.isBlank()) "Auto-detect (Program Files\\VideoLAN\\VLC)" else vlcPath)
                append(if (playerState.vlcAvailable) " — VLC detected" else " — VLC not found")
            },
            onValueChange = { newPath ->
                val trimmed = newPath.trim()
                vlcPath = trimmed
                settings.putString(DesktopPreferences.KEY_VLC_PATH, trimmed)
                mediaPlayerController.recheckVlcAvailability()
            }
        )
        EditTextPreference(
            title = "FFmpeg executable path (for MP3 conversion)",
            value = ffmpegPath,
            summary = buildString {
                append(if (ffmpegPath.isBlank()) "Auto-detect on PATH / winget" else ffmpegPath)
                append(if (ffmpegFound) " — FFmpeg detected" else " — FFmpeg not found")
            },
            onValueChange = { newPath ->
                val trimmed = newPath.trim()
                ffmpegPath = trimmed
                settings.putString(DesktopPreferences.KEY_FFMPEG_PATH, trimmed)
                ffmpegFound = downloadController.isFfmpegAvailable()
            }
        )
        ListPreference(
            title = "MP3 download bitrate",
            entries = DesktopPreferences.MP3_BITRATE_OPTIONS.map { ListPreferenceEntry(it, it) },
            selectedValue = mp3Bitrate,
            onValueSelected = { bitrate ->
                mp3Bitrate = bitrate
                settings.putString(DesktopPreferences.KEY_MP3_BITRATE, bitrate)
            }
        )
        SwitchPreference(
            title = "Minimize to system tray during background audio",
            summary = "Keep audio playing in the Windows notification area when closing/minimizing",
            checked = minimizeToTray,
            onCheckedChange = { checked ->
                minimizeToTray = checked
                settings.putBoolean(DesktopPreferences.KEY_MINIMIZE_TO_TRAY_ON_AUDIO, checked)
            }
        )
    }
}
