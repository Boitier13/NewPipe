/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.StateFlow
import net.newpipe.app.model.AudioStreamOption
import net.newpipe.app.model.DownloadFormat
import net.newpipe.app.model.DownloadTaskItem
import net.newpipe.app.model.VideoDetails
import net.newpipe.app.model.VideoStreamOption

/**
 * Pure JVM muxing bridge implemented in desktopApp using migrated NewPipe stream writers.
 */
interface StreamMuxer {
    fun muxMp4FromDash(videoPath: String, audioPath: String, outputPath: String)
    fun remuxM4aFromDash(audioPath: String, outputPath: String)
    fun muxWebm(videoPath: String, audioPath: String, outputPath: String)
    fun demuxOggFromWebm(
        sourcePath: String,
        outputPath: String,
        url: String?,
        category: String?,
        uploaderName: String?,
        title: String?,
        isoDate: String?
    )
}

/**
 * Process-wide registry populated by desktopApp at startup.
 */
object DesktopStreamMuxerRegistry {
    @Volatile
    var muxer: StreamMuxer? = null
}

/**
 * Manages MP4, MP3, and native audio downloads on Windows/PC.
 */
interface DownloadController {
    val tasks: StateFlow<List<DownloadTaskItem>>

    fun isFfmpegAvailable(): Boolean

    fun enqueueMp4(details: VideoDetails, videoOption: VideoStreamOption)

    fun enqueueMp3(
        details: VideoDetails,
        audioOption: AudioStreamOption,
        targetBitrateKbps: Int? = null
    )

    fun enqueueNativeAudio(
        details: VideoDetails,
        audioOption: AudioStreamOption,
        targetBitrateKbps: Int? = null
    )

    fun enqueueAudio(
        details: VideoDetails,
        audioOption: AudioStreamOption,
        format: DownloadFormat,
        targetBitrateKbps: Int? = null
    ) {
        when (format) {
            DownloadFormat.MP3 -> enqueueMp3(details, audioOption, targetBitrateKbps)
            else -> enqueueNativeAudio(details, audioOption, targetBitrateKbps)
        }
    }

    fun cancelTask(taskId: String)

    fun removeTask(taskId: String)

    fun clearCompleted()

    fun openDownloadFolder(path: String)
}
