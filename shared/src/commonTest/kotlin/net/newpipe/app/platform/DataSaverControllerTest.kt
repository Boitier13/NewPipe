/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.newpipe.app.preferences.ContentPreferences

class DataSaverControllerTest {

    @Test
    fun dataSaverOverridesImageQualityAndResolutionCap() {
        val settings = MapSettings()
        settings.putString(ContentPreferences.KEY_IMAGE_QUALITY, ContentPreferences.IMAGE_QUALITY_HIGH)
        val controller = DataSaverController(settings)

        assertFalse(controller.isDataSaverEnabled.value)
        assertEquals(ContentPreferences.IMAGE_QUALITY_HIGH, controller.effectiveImageQuality())
        assertNull(controller.effectiveMaxResolution())

        var notifiedQuality: String? = null
        controller.onDataSaverImageQualityChanged = { notifiedQuality = it }

        controller.setDataSaverEnabled(true)
        assertTrue(controller.isDataSaverEnabled.value)
        assertEquals(ContentPreferences.IMAGE_QUALITY_LOW, controller.effectiveImageQuality())
        assertEquals(ContentPreferences.IMAGE_QUALITY_LOW, notifiedQuality)
        assertEquals("360p", controller.effectiveMaxResolution())

        controller.setMaxResolution("240p")
        assertEquals("240p", controller.effectiveMaxResolution())
    }
}
