/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.newpipe.app.preferences.ContentPreferences
import net.newpipe.app.preferences.DesktopPreferences
import org.koin.core.annotation.Singleton

/**
 * Coordinates desktop Data Saver mode across extraction, playback, thumbnails, and downloads.
 */
@Singleton
class DataSaverController(
    private val settings: Settings
) {
    private val _isDataSaverEnabled = MutableStateFlow(
        settings.getBoolean(
            DesktopPreferences.KEY_DATA_SAVER_ENABLED,
            DesktopPreferences.DEFAULT_DATA_SAVER_ENABLED
        )
    )
    val isDataSaverEnabled: StateFlow<Boolean> = _isDataSaverEnabled.asStateFlow()

    private val _maxResolution = MutableStateFlow(
        settings.getString(
            DesktopPreferences.KEY_DATA_SAVER_MAX_RESOLUTION,
            DesktopPreferences.DEFAULT_DATA_SAVER_MAX_RESOLUTION
        )
    )
    val maxResolution: StateFlow<String> = _maxResolution.asStateFlow()

    private val _preferAudioOnly = MutableStateFlow(
        settings.getBoolean(
            DesktopPreferences.KEY_DATA_SAVER_AUDIO_ONLY,
            DesktopPreferences.DEFAULT_DATA_SAVER_AUDIO_ONLY
        )
    )
    val preferAudioOnly: StateFlow<Boolean> = _preferAudioOnly.asStateFlow()

    fun setDataSaverEnabled(enabled: Boolean) {
        settings.putBoolean(DesktopPreferences.KEY_DATA_SAVER_ENABLED, enabled)
        _isDataSaverEnabled.value = enabled
        onDataSaverImageQualityChanged?.invoke(effectiveImageQuality())
    }

    fun setMaxResolution(resolution: String) {
        settings.putString(DesktopPreferences.KEY_DATA_SAVER_MAX_RESOLUTION, resolution)
        _maxResolution.value = resolution
    }

    fun setPreferAudioOnly(audioOnly: Boolean) {
        settings.putBoolean(DesktopPreferences.KEY_DATA_SAVER_AUDIO_ONLY, audioOnly)
        _preferAudioOnly.value = audioOnly
    }

    fun effectiveImageQuality(): String = if (_isDataSaverEnabled.value) {
        ContentPreferences.IMAGE_QUALITY_LOW
    } else {
        settings.getString(
            ContentPreferences.KEY_IMAGE_QUALITY,
            ContentPreferences.DEFAULT_IMAGE_QUALITY
        )
    }

    fun effectiveMaxResolution(): String? = if (_isDataSaverEnabled.value) {
        _maxResolution.value
    } else {
        null
    }

    internal var onDataSaverImageQualityChanged: ((String) -> Unit)? = null
}
