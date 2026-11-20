/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import java.io.File

/** Reserve names before a queued job creates its output; audio/video share this allocator. */
internal class DownloadOutputNames {
    private val reserved = mutableSetOf<String>()

    @Synchronized fun reserve(dir: File, name: String, extension: String): File {
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else " ($index)"
            val candidate = File(dir, "$name$suffix.$extension")
            if (!candidate.exists() && reserved.add(candidate.canonicalPath)) return candidate
            index++
        }
    }

    @Synchronized fun release(file: File) { reserved.remove(file.canonicalPath) }
}
