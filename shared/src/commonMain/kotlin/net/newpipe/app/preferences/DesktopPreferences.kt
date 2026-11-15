/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.preferences

/**
 * Preference keys and defaults specific to the Windows / PC desktop application.
 */
object DesktopPreferences {
    const val KEY_DATA_SAVER_ENABLED = "desktop_data_saver_enabled"
    const val KEY_DATA_SAVER_MAX_RESOLUTION = "desktop_data_saver_max_resolution"
    const val KEY_DATA_SAVER_AUDIO_ONLY = "desktop_data_saver_audio_only"
    const val KEY_VLC_PATH = "desktop_vlc_path"
    const val KEY_FFMPEG_PATH = "desktop_ffmpeg_path"
    const val KEY_MP3_BITRATE = "desktop_mp3_bitrate"
    const val KEY_MINIMIZE_TO_TRAY_ON_AUDIO = "desktop_minimize_to_tray_on_audio"
    const val KEY_PLAYER_VOLUME = "desktop_player_volume"
    const val KEY_PLAYER_SPEED = "desktop_player_speed"

    const val DEFAULT_DATA_SAVER_ENABLED = false
    const val DEFAULT_DATA_SAVER_MAX_RESOLUTION = "360p"
    const val DEFAULT_DATA_SAVER_AUDIO_ONLY = false
    const val DEFAULT_VLC_PATH = ""
    const val DEFAULT_FFMPEG_PATH = ""
    const val DEFAULT_MP3_BITRATE = "192k"
    const val DEFAULT_MINIMIZE_TO_TRAY_ON_AUDIO = true
    const val DEFAULT_PLAYER_VOLUME = 100
    const val DEFAULT_PLAYER_SPEED = 1.0f

    val DATA_SAVER_RESOLUTION_OPTIONS = listOf("480p", "360p", "240p", "144p")
    val MP3_BITRATE_OPTIONS = listOf("128k", "192k", "320k")
}
