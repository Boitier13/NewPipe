/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDownloadOptionsHelperTest {

    private fun fakeOption(res: String) = VideoStreamOption(
        id = "v_$res",
        resolution = res,
        formatLabel = "MPEG-4",
        formatKey = "MPEG_4",
        isVideoOnly = true,
        videoUrl = "https://example.com/$res.mp4",
        pairedAudioUrl = "https://example.com/audio.m4a",
        pairedAudioFormatKey = "M4A",
        fps = 30
    )

    @Test
    fun synthesizesMissing360pAnd144pWhenFfmpegAvailable() {
        val native = listOf(fakeOption("1080p"), fakeOption("720p"), fakeOption("480p"))
        val all = DesktopDownloadOptionsHelper.buildVideoDownloadOptions(native, ffmpegAvailable = true)

        assertEquals(listOf("1080p", "720p", "480p", "360p", "144p"), all.map { it.resolution })
        assertNull(all[0].targetScaleHeight)
        assertNull(all[1].targetScaleHeight)
        assertNull(all[2].targetScaleHeight)
        assertEquals(360, all[3].targetScaleHeight)
        assertEquals(144, all[4].targetScaleHeight)
    }

    @Test
    fun keepsNativeStreamsWhenFfmpegUnavailable() {
        val native = listOf(fakeOption("1080p"), fakeOption("720p"), fakeOption("480p"))
        val all = DesktopDownloadOptionsHelper.buildVideoDownloadOptions(native, ffmpegAvailable = false)

        assertEquals(listOf("1080p", "720p", "480p"), all.map { it.resolution })
    }
}
