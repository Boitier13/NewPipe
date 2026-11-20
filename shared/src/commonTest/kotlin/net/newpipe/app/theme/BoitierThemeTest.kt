/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.theme

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import net.newpipe.app.preferences.AppearancePreferences

class BoitierThemeTest {
    @Test
    fun themeSelectionMatrixIncludesSystemFallbackAndLegacyBlack() {
        for ((selection, systemDark, expected) in listOf(
            Triple(AppearancePreferences.THEME_LIGHT, false, lightScheme),
            Triple(AppearancePreferences.THEME_LIGHT, true, lightScheme),
            Triple(AppearancePreferences.THEME_DARK, false, darkScheme),
            Triple(AppearancePreferences.THEME_DARK, true, darkScheme),
            Triple(AppearancePreferences.THEME_AUTO_DEVICE, false, lightScheme),
            Triple(AppearancePreferences.THEME_AUTO_DEVICE, true, darkScheme),
            Triple(AppearancePreferences.THEME_BLACK, false, darkScheme),
            Triple(AppearancePreferences.THEME_BLACK, true, darkScheme),
            Triple("unknown-imported-value", false, lightScheme),
            Triple("unknown-imported-value", true, darkScheme)
        )) {
            assertEquals(expected, resolveDesktopColorScheme(selection, systemDark))
        }
    }

    @Test
    fun everyServiceUsesTheFullDesktopPalette() {
        for (service in Service.entries) {
            assertEquals(lightScheme, service.lightScheme)
            assertEquals(darkScheme, service.darkScheme)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun composeReadsTheSuppliedSettings() = runComposeUiTest {
        setContent {
            val lightSettings = MapSettings(
                AppearancePreferences.KEY_THEME to AppearancePreferences.THEME_LIGHT
            )
            assertEquals(lightScheme, currentColorScheme(true, lightSettings))
            val darkSettings = MapSettings(
                AppearancePreferences.KEY_THEME to AppearancePreferences.THEME_DARK
            )
            assertEquals(darkScheme, currentColorScheme(false, darkSettings))
        }
    }
}
