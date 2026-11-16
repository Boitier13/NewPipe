/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import java.util.Locale
import org.koin.core.annotation.Singleton

@Singleton(binds = [ContentActions::class])
class JVMContentActions(
    private val youTubeBackend: YouTubeBackend
) : ContentActions {

    override val usesSystemAppLanguageSettings = false

    override fun systemAppLanguageName(): String? = null

    override fun openSystemAppLanguageSettings() = Unit

    override fun applyAppLanguage(languageCode: String) {
        if (languageCode == "system") {
            Locale.setDefault(Locale.getDefault())
        } else {
            Locale.setDefault(Locale.forLanguageTag(languageCode))
        }
    }

    override fun applyImageQuality(quality: String) {
        youTubeBackend.applyImageQuality(quality)
    }

    override fun applyYoutubeRestrictedMode(enabled: Boolean) {
        youTubeBackend.applyYoutubeRestrictedMode(enabled)
    }

    override fun applyContentLocalization() {
        youTubeBackend.applyLocalizationAndContentPrefs()
    }
}
