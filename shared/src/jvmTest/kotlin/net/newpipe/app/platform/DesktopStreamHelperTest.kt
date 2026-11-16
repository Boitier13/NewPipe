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
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream

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

    @Test
    fun av1AndLowBitrateYoutubeItagsArePreservedAndNonAv1Preferred() {
        val h2641080 = VideoStream.Builder()
            .setId("137")
            .setContent("https://example.com/1080_h264.mp4", true)
            .setMediaFormat(MediaFormat.MPEG_4)
            .setIsVideoOnly(true)
            .setResolution("1080p")
            .setItagItem(ItagItem.getItag(137))
            .build()
        val av11080 = VideoStream.Builder()
            .setId("399")
            .setContent("https://example.com/1080_av1.mp4", true)
            .setMediaFormat(MediaFormat.MPEG_4)
            .setIsVideoOnly(true)
            .setResolution("1080p")
            .setItagItem(ItagItem.getItag(399))
            .build()
        val av1360 = VideoStream.Builder()
            .setId("396")
            .setContent("https://example.com/360_av1.mp4", true)
            .setMediaFormat(MediaFormat.MPEG_4)
            .setIsVideoOnly(true)
            .setResolution("360p")
            .setItagItem(ItagItem.getItag(396))
            .build()
        val av1144 = VideoStream.Builder()
            .setId("394")
            .setContent("https://example.com/144_av1.mp4", true)
            .setMediaFormat(MediaFormat.MPEG_4)
            .setIsVideoOnly(true)
            .setResolution("144p")
            .setItagItem(ItagItem.getItag(394))
            .build()

        val filtered = DesktopStreamHelper.filterPlayableHttpStreams(
            listOf(h2641080, av11080, av1360, av1144),
            ServiceList.YouTube.serviceId
        )
        assertEquals(4, filtered.size)

        val sorted = DesktopStreamHelper.getSortedVideoStreams(
            defaultFormat = MediaFormat.MPEG_4,
            showHigherResolutions = false,
            videoStreams = emptyList(),
            videoOnlyStreams = filtered,
            preferVideoOnlyStreams = true
        )
        assertEquals(listOf("1080p", "360p", "144p"), sorted.map { it.getResolution() })
        assertEquals("137", sorted.first().id)

        val audio599 = AudioStream.Builder()
            .setId("599")
            .setContent("https://example.com/32.m4a", true)
            .setMediaFormat(MediaFormat.M4A)
            .setAverageBitrate(32)
            .setItagItem(ItagItem.getItag(599))
            .build()
        val filteredAudio = DesktopStreamHelper.filterPlayableHttpStreams(
            listOf(audio599),
            ServiceList.YouTube.serviceId
        )
        assertEquals(1, filteredAudio.size)
    }

    @Test
    fun parseHlsMasterPlaylistExtractsTargetResolutionsAndAudio() {
        val m3u8 = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",DEFAULT=YES,URI="audio/main.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=200000,RESOLUTION=256x144,FRAME-RATE=30
            https://cdn.example.com/144.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=640x360,FRAME-RATE=30
            360.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080,FRAME-RATE=60
            1080.m3u8
        """.trimIndent()

        val parsed = DesktopStreamHelper.parseHlsMasterPlaylist(
            m3u8,
            "https://manifest.example.com/master/index.m3u8"
        )
        assertEquals("https://manifest.example.com/master/audio/main.m3u8", parsed.audioUrl)
        assertEquals(listOf("1080p", "360p", "144p"), parsed.variants.map { it.resolution })
        assertEquals("https://manifest.example.com/master/360.m3u8", parsed.variants[1].url)
    }
}
