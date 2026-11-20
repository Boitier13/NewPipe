/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.theme

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import net.newpipe.app.preferences.AppearancePreferences

@OptIn(ExperimentalTestApi::class)
class BoitierThemeTest {
    @Test
    fun desktopThemesRespectSelectionAndSystemAndLegacyBlack() = runComposeUiTest {
        setContent {
            for ((selection, systemDark, expectedBackground) in listOf(
                Triple(AppearancePreferences.THEME_LIGHT, true, backgroundLight),
                Triple(AppearancePreferences.THEME_DARK, false, backgroundDark),
                Triple(AppearancePreferences.THEME_AUTO_DEVICE, false, backgroundLight),
                Triple(AppearancePreferences.THEME_AUTO_DEVICE, true, backgroundDark),
                Triple(AppearancePreferences.THEME_BLACK, false, backgroundDark)
            )) {
                val settings = MapSettings(AppearancePreferences.KEY_THEME to selection)
                assertEquals(
                    expectedBackground,
                    currentColorScheme(useDarkTheme = systemDark, settings = settings).background
                )
            }
        }
    }
}
