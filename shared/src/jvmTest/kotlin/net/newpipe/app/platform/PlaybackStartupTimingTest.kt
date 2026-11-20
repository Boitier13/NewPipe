/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaybackStartupTimingTest {
    @Test fun timingPreservesResultAndDoesNotHidePlayerErrors() {
        var now = 0L
        val entries = mutableListOf<Pair<String, Long>>()
        val timer = PlaybackStartupTiming(clock = { now.also { now += 12_000_000 } }, sink = { stage, ms -> entries += stage to ms })
        assertEquals(42, timer.measure("vlc_creation_video") { 42 })
        assertFailsWith<IllegalStateException> { timer.measure("youtube_extraction") { error("player error") } }
        assertEquals(listOf("vlc_creation_video" to 12L, "youtube_extraction" to 12L), entries)
        val brokenSink = PlaybackStartupTiming(sink = { _, _ -> error("disk full") })
        assertEquals("playback", brokenSink.measure("test") { "playback" })
    }
}
