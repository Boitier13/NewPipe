/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import java.io.File

internal class PlaybackStartupTiming(
    private val clock: () -> Long = System::nanoTime,
    private val sink: (String, Long) -> Unit
) {
    fun <T> measure(stage: String, action: () -> T): T {
        val start = clock()
        try { return action() } finally { runCatching { sink(stage, ((clock() - start) / 1_000_000).coerceAtLeast(0)) } }
    }
}

/** Explicit opt-in; no titles, URLs, cookies or extra native players. Called off the UI thread. */
internal object PlaybackDiagnostics {
    private val enabled get() = System.getenv("BOITIERTUBE_DIAGNOSTICS") == "1"
    private val timing = PlaybackStartupTiming { stage, ms -> write(stage, ms) }
    fun <T> time(stage: String, action: () -> T): T = if (enabled) timing.measure(stage, action) else action()
    fun elapsed(stage: String, start: Long) {
        if (enabled && start != 0L) runCatching { write(stage, ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(0)) }
    }
    private fun write(stage: String, ms: Long) {
        JVMDownloadController.appendLogLine(File(WindowsAppPaths.appDataDir(), "playback_startup.log"), "$stage : ${ms} ms")
    }
}
