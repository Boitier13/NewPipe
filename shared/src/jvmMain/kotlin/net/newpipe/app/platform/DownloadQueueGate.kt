/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** One admission count for both audio and video, even when the limit changes mid-download.
 * Reducing the limit does not interrupt running work. Cancellation never consumes a permit.
 */
internal class DownloadQueueGate(private val limit: () -> Int) {
    private val lock = Any()
    private val changed = MutableStateFlow(0L)
    private var active = 0

    suspend fun <T> withPermit(block: suspend () -> T): T {
        var acquired = false
        try {
            changed.first {
                synchronized(lock) {
                    if (active < limit().coerceIn(1, 3)) {
                        active++
                        acquired = true
                        true
                    } else false
                }
            }
            return block()
        } finally {
            if (acquired) synchronized(lock) { active--; changed.value = changed.value + 1 }
        }
    }
}
