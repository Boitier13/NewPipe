/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app

import java.io.File
import java.nio.ByteBuffer
import net.newpipe.app.platform.StreamMuxer
import org.schabi.newpipe.streams.Mp4DashReader
import org.schabi.newpipe.streams.Mp4FromDashWriter
import org.schabi.newpipe.streams.OggFromWebMWriter
import org.schabi.newpipe.streams.WebMReader.TrackKind
import org.schabi.newpipe.streams.WebMWriter
import us.shandian.giga.io.FileStream

/**
 * Pure Java/JVM stream muxer backed by NewPipe's migrated DASH/WebM/Ogg writers.
 */
class DesktopStreamMuxer : StreamMuxer {

    override fun muxMp4FromDash(
        videoPath: String,
        audioPath: String,
        outputPath: String,
        onProgress: ((fraction: Float) -> Unit)?
    ) {
        File(outputPath).parentFile?.mkdirs()
        FileStream(videoPath).use { videoStream ->
            FileStream(audioPath).use { audioStream ->
                FileStream(outputPath).use { outStream ->
                    val muxer = Mp4FromDashWriter(videoStream, audioStream)
                    muxer.parseSources()
                    muxer.selectTracks(0, 0)
                    muxer.build(outStream) { written, total ->
                        if (onProgress != null) {
                            val fraction = if (total > 0L) {
                                (written.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            onProgress(fraction)
                        }
                    }
                }
            }
        }
    }

    override fun remuxM4aFromDash(
        audioPath: String,
        outputPath: String,
        onProgress: ((fraction: Float) -> Unit)?
    ) {
        File(outputPath).parentFile?.mkdirs()
        FileStream(audioPath).use { source ->
            val reader = Mp4DashReader(source)
            reader.parse()
            val brand = reader.brands.firstOrNull() ?: 0
            val isDash = brand == 0x64617368 || brand == 0x69736F35
            if (!isDash) {
                throw UnsupportedOperationException("Input M4A is not fragmented DASH")
            }
            source.rewind()
            FileStream(outputPath).use { outStream ->
                val muxer = Mp4FromDashWriter(source)
                muxer.setMainBrand(0x4D344120) // "M4A "
                muxer.parseSources()
                muxer.selectTracks(0)
                muxer.build(outStream) { written, total ->
                    if (onProgress != null) {
                        val fraction = if (total > 0L) {
                            (written.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        onProgress(fraction)
                    }
                }
            }
        }
    }

    override fun muxWebm(videoPath: String, audioPath: String, outputPath: String) {
        File(outputPath).parentFile?.mkdirs()
        FileStream(videoPath).use { videoStream ->
            FileStream(audioPath).use { audioStream ->
                FileStream(outputPath).use { outStream ->
                    val muxer = WebMWriter(videoStream, audioStream)
                    muxer.parseSources()
                    val tracks = muxer.getTracksFromSource(1)
                    val audioTrackIdx = tracks.indexOfFirst { it.kind == TrackKind.Audio }
                        .takeIf { it >= 0 } ?: 0
                    muxer.selectTracks(0, audioTrackIdx)
                    muxer.build(outStream)
                }
            }
        }
    }

    override fun demuxOggFromWebm(
        sourcePath: String,
        outputPath: String,
        url: String?,
        category: String?,
        uploaderName: String?,
        title: String?,
        isoDate: String?
    ) {
        File(outputPath).parentFile?.mkdirs()
        FileStream(sourcePath).use { source ->
            val header = ByteBuffer.allocate(4)
            source.read(header.array())
            if (header.getInt(0) != 0x1a45dfa3) {
                throw UnsupportedOperationException("Input stream is not WebM/EBML")
            }
            source.rewind()
            FileStream(outputPath).use { outStream ->
                val meta = OggFromWebMWriter.StreamMetadata(
                    url,
                    category,
                    uploaderName,
                    title,
                    isoDate
                )
                val demuxer = OggFromWebMWriter(source, outStream, meta)
                demuxer.parseSource()
                demuxer.selectTrack(0)
                demuxer.build()
            }
        }
    }
}
