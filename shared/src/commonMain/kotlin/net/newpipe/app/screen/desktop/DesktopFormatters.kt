/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.screen.desktop

import kotlin.math.roundToInt

fun formatDurationSeconds(seconds: Long, isLive: Boolean = false): String {
    if (isLive || seconds < 0L) return if (isLive) "LIVE" else ""
    val hrs = seconds / 3600L
    val mins = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return if (hrs > 0L) {
        "$hrs:${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}"
    } else {
        "$mins:${secs.toString().padStart(2, '0')}"
    }
}

fun formatMillis(millis: Long): String {
    if (millis <= 0L) return "0:00"
    return formatDurationSeconds(millis / 1000L, isLive = false)
}

fun formatCount(count: Long): String {
    if (count < 0L) return ""
    return when {
        count >= 1_000_000_000L -> "${((count / 100_000_000.0).roundToInt() / 10.0)}B"
        count >= 1_000_000L -> "${((count / 100_000.0).roundToInt() / 10.0)}M"
        count >= 1_000L -> "${((count / 100.0).roundToInt() / 10.0)}K"
        else -> count.toString()
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> "${((bytes / gb) * 10.0).roundToInt() / 10.0} GB"
        bytes >= mb -> "${((bytes / mb) * 10.0).roundToInt() / 10.0} MB"
        bytes >= kb -> "${((bytes / kb) * 10.0).roundToInt() / 10.0} KB"
        else -> "$bytes B"
    }
}
