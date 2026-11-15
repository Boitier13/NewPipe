/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.newpipe.app.preferences.DownloadPreferences
import org.schabi.newpipe.extractor.MediaFormat

class DesktopStreamHelperTest {

    @Test
    fun sanitizeFilenameRemovesWindowsReservedChars() {
        val cleaned = DesktopStreamHelper.sanitizeFilename(
            "My Video: Part <1> | Test/Path*?",
            DownloadPreferences.CHARSET_MOST_SPECIAL,
            "_"
        )
        assertEquals("My Video_ Part _1_ _ Test_Path_", cleaned)
    }

    @Test
    fun compareResolutionOrdersCorrectly() {
        assertTrue(DesktopStreamHelper.compareResolution("1080p60", "720p") > 0)
        assertTrue(DesktopStreamHelper.compareResolution("360p", "720p") < 0)
        assertEquals(MediaFormat.MPEG_4, DesktopStreamHelper.mediaFormatFromKey("video_mp4"))
        assertEquals(MediaFormat.M4A, DesktopStreamHelper.mediaFormatFromKey("audio_m4a"))
    }
}
