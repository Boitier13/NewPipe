/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.newpipe.app.model.AudioStreamOption
import net.newpipe.app.model.DownloadFormat
import net.newpipe.app.model.DownloadStatus
import net.newpipe.app.model.DownloadTaskItem
import net.newpipe.app.model.VideoDetails
import net.newpipe.app.model.VideoStreamOption
import net.newpipe.app.preferences.DesktopPreferences
import net.newpipe.app.preferences.DownloadPreferences
import okhttp3.Request
import org.koin.core.annotation.Singleton

/**
 * Windows/PC download manager supporting MP4 (FFmpeg fast-mux + buffered Java DASH fallback),
 * MP3/FLAC/WAV (FFmpeg conversion), and native M4A/OGG-Opus audio downloads.
 */
@Singleton(binds = [DownloadController::class])
class JVMDownloadController(
    private val settings: Settings,
    private val downloader: DesktopDownloader,
    private val dataSaverController: DataSaverController
) : DownloadController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val serialSemaphore = Semaphore(1)
    private val parallelSemaphore = Semaphore(3)
    private val logLock = Any()

    private fun queueSemaphore(): Semaphore =
        if (settings.getBoolean(
                DownloadPreferences.KEY_LIMIT_QUEUE,
                DownloadPreferences.DEFAULT_LIMIT_QUEUE
            )
        ) serialSemaphore else parallelSemaphore

    // Mesure de vitesse par téléchargement : [instant (ms), octets, vitesse lissée (octets/s)]
    private val speedMarks = ConcurrentHashMap<String, LongArray>()

    private val _tasks = MutableStateFlow<List<DownloadTaskItem>>(emptyList())
    override val tasks: StateFlow<List<DownloadTaskItem>> = _tasks.asStateFlow()

    override fun isFfmpegAvailable(): Boolean {
        val customPath = settings.getString(DesktopPreferences.KEY_FFMPEG_PATH, "")
        return WindowsAppPaths.findFfmpegExecutable(customPath) != null
    }

    override fun enqueueMp4(details: VideoDetails, videoOption: VideoStreamOption) {
        val dir = resolveVideoFolder()
        val ext = DownloadFormat.MP4.extension
        val outputFile = uniqueOutputFile(dir, sanitizeTitle(details.title), ext)
        val taskId = UUID.randomUUID().toString()

        val initial = DownloadTaskItem(
            id = taskId,
            title = details.title,
            uploader = details.uploaderName,
            format = DownloadFormat.MP4,
            resolutionLabel = videoOption.resolution,
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = videoOption.approxSizeBytes.coerceAtLeast(0L),
            outputPath = outputFile.absolutePath,
            status = DownloadStatus.QUEUED
        )
        addTask(initial)

        val job = scope.launch {
            queueSemaphore().withPermit {
                executeMp4Download(taskId, details, videoOption, outputFile)
            }
        }
        jobs[taskId] = job
    }

    override fun enqueueMp3(
        details: VideoDetails,
        audioOption: AudioStreamOption,
        targetBitrateKbps: Int?
    ) {
        enqueueAudio(details, audioOption, DownloadFormat.MP3, targetBitrateKbps)
    }

    override fun enqueueNativeAudio(
        details: VideoDetails,
        audioOption: AudioStreamOption,
        targetBitrateKbps: Int?
    ) {
        val inferredFormat = if (targetBitrateKbps == null &&
            (audioOption.formatKey == "WEBMA_OPUS" || audioOption.formatKey == "WEBMA")
        ) {
            DownloadFormat.OGG_OPUS
        } else {
            DownloadFormat.M4A
        }
        enqueueAudio(details, audioOption, inferredFormat, targetBitrateKbps)
    }

    override fun enqueueAudio(
        details: VideoDetails,
        audioOption: AudioStreamOption,
        format: DownloadFormat,
        targetBitrateKbps: Int?
    ) {
        val dir = resolveAudioFolder()
        val resolvedOption = if (audioOption.url.isNotBlank()) {
            audioOption
        } else if (dataSaverController.isDataSaverEnabled.value && details.audioStreams.isNotEmpty()) {
            details.audioStreams.minByOrNull { it.bitrateKbps } ?: audioOption
        } else {
            details.audioStreams.maxByOrNull { it.bitrateKbps } ?: audioOption
        }

        val matchingNativeM4a = if (format == DownloadFormat.M4A && targetBitrateKbps != null) {
            if (resolvedOption.formatKey == "M4A" && resolvedOption.bitrateKbps == targetBitrateKbps) {
                resolvedOption
            } else {
                details.audioStreams.firstOrNull {
                    it.formatKey == "M4A" && it.bitrateKbps == targetBitrateKbps
                }
            }
        } else {
            null
        }

        val effectiveOption = matchingNativeM4a ?: resolvedOption

        val defaultMp3Kbps = settings.getString(
            DesktopPreferences.KEY_MP3_BITRATE,
            DesktopPreferences.DEFAULT_MP3_BITRATE
        ).removeSuffix("k").toIntOrNull() ?: 128

        val needsFfmpegTranscode = when (format) {
            DownloadFormat.MP3, DownloadFormat.FLAC, DownloadFormat.WAV -> true
            DownloadFormat.M4A -> targetBitrateKbps != null && matchingNativeM4a == null
            DownloadFormat.OGG_OPUS -> targetBitrateKbps != null ||
                (effectiveOption.formatKey != "WEBMA_OPUS" && effectiveOption.formatKey != "WEBMA")
            DownloadFormat.MP4 -> false
        }

        val effectiveKbps = when (format) {
            DownloadFormat.MP3 -> targetBitrateKbps ?: defaultMp3Kbps
            DownloadFormat.M4A, DownloadFormat.OGG_OPUS -> targetBitrateKbps ?: effectiveOption.bitrateKbps
            DownloadFormat.FLAC, DownloadFormat.WAV, DownloadFormat.MP4 -> null
        }

        val qualityLabel = when (format) {
            DownloadFormat.FLAC -> "Lossless"
            DownloadFormat.WAV -> "PCM"
            else -> effectiveKbps?.let { "$it kbps" } ?: "${effectiveOption.bitrateKbps} kbps"
        }

        val outputFile = uniqueOutputFile(dir, sanitizeTitle(details.title), format.extension)
        val taskId = UUID.randomUUID().toString()

        val initial = DownloadTaskItem(
            id = taskId,
            title = details.title,
            uploader = details.uploaderName,
            format = format,
            resolutionLabel = qualityLabel,
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = effectiveOption.approxSizeBytes.coerceAtLeast(0L),
            outputPath = outputFile.absolutePath,
            status = DownloadStatus.QUEUED
        )
        addTask(initial)

        val job = scope.launch {
            queueSemaphore().withPermit {
                executeAudioDownload(
                    taskId = taskId,
                    details = details,
                    audioOption = effectiveOption,
                    format = format,
                    outputFile = outputFile,
                    transcodeBitrateKbps = if (needsFfmpegTranscode) (effectiveKbps ?: 128) else null
                )
            }
        }
        jobs[taskId] = job
    }

    override fun cancelTask(taskId: String) {
        jobs.remove(taskId)?.cancel()
        updateTask(taskId) {
            if (it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.FAILED) {
                it.copy(status = DownloadStatus.CANCELLED, statusMessage = "Annulé")
            } else {
                it
            }
        }
    }

    override fun removeTask(taskId: String) {
        jobs.remove(taskId)?.cancel()
        _tasks.update { list -> list.filterNot { it.id == taskId } }
    }

    override fun clearCompleted() {
        _tasks.update { list ->
            list.filterNot {
                it.status == DownloadStatus.COMPLETED ||
                    it.status == DownloadStatus.CANCELLED ||
                    it.status == DownloadStatus.FAILED
            }
        }
    }

    override fun openDownloadFolder(path: String) {
        scope.launch {
            runCatching {
                val targetDir = resolveTargetDirectoryForOpen(path)
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val openedWithAwt = Desktop.isDesktopSupported() &&
                    Desktop.getDesktop().isSupported(Desktop.Action.OPEN) &&
                    runCatching {
                        Desktop.getDesktop().open(targetDir)
                        true
                    }.getOrElse { false }

                if (!openedWithAwt) {
                    if (WindowsAppPaths.isWindows) {
                        ProcessBuilder("explorer.exe", targetDir.canonicalPath).start()
                    } else {
                        val cmd = if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
                            "open"
                        } else {
                            "xdg-open"
                        }
                        ProcessBuilder(cmd, targetDir.canonicalPath).start()
                    }
                }
            }.onFailure {
                Logger.w(it) { "Unable to open folder for $path" }
            }
        }
    }

    private suspend fun executeMp4Download(
        taskId: String,
        details: VideoDetails,
        option: VideoStreamOption,
        outputFile: File
    ) {
        val tempFiles = mutableListOf<File>()
        logDownload(
            "[$taskId] MP4 start: title='${details.title}', res=${option.resolution}, " +
                "format=${option.formatKey}, videoOnly=${option.isVideoOnly}, " +
                "duration=${details.durationSeconds}s, out='${outputFile.absolutePath}'"
        )
        try {
            updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.DOWNLOADING,
                    progress = 0.01f,
                    statusMessage = "Téléchargement..."
                )
            }

            val fallbackAudio = if (option.isVideoOnly && option.pairedAudioUrl == null) {
                details.audioStreams.firstOrNull()
            } else {
                null
            }
            val pairedAudioUrl = if (option.isVideoOnly) {
                option.pairedAudioUrl ?: fallbackAudio?.url
            } else {
                null
            }
            val pairedAudioFormatKey = option.pairedAudioFormatKey ?: fallbackAudio?.formatKey

            val isHls = option.formatKey == "HLS" || option.videoUrl.contains(".m3u8")
            val targetScaleHeight = option.targetScaleHeight

            if (isHls) {
                val ffmpeg = findAndLogFfmpeg(taskId)
                    ?: throw IOException(
                        "FFmpeg requis pour télécharger un flux HLS. Installez FFmpeg ou choisissez un flux MP4 direct."
                    )
                updateTask(taskId) {
                    it.copy(
                        status = DownloadStatus.TRANSCODING,
                        progress = 0.02f,
                        statusMessage = "Téléchargement et assemblage HLS..."
                    )
                }
                val args = mutableListOf("-y", "-i", option.videoUrl)
                if (!pairedAudioUrl.isNullOrBlank()) {
                    args += listOf("-i", pairedAudioUrl)
                }
                if (targetScaleHeight != null) {
                    args += listOf(
                        "-vf", "scale=-2:$targetScaleHeight",
                        "-c:v", "libx264",
                        "-preset", "veryfast",
                        "-crf", "23",
                        "-c:a", "aac",
                        "-b:a", "128k"
                    )
                } else {
                    args += listOf("-c", "copy")
                }
                args += listOf("-movflags", "+faststart", outputFile.absolutePath)
                var maxProgress = 0.02f
                runFfmpeg(
                    taskId = taskId,
                    ffmpegExe = ffmpeg,
                    args = args,
                    durationSeconds = details.durationSeconds
                ) { fraction, totalSize, outTimeUs ->
                    val p = maxOf(maxProgress, (0.02f + 0.96f * fraction).coerceIn(0.02f, 0.99f))
                    maxProgress = p
                    val detail = formatFfmpegProgressSuffix(
                        fraction = fraction,
                        durationSeconds = details.durationSeconds,
                        outTimeUs = outTimeUs,
                        totalSizeBytes = totalSize
                    )
                    updateTask(taskId) { current ->
                        val bytes = if (totalSize > 0L) totalSize else current.downloadedBytes
                        current.copy(
                            progress = p,
                            downloadedBytes = bytes,
                            totalBytes = if (fraction > 0.05f && bytes > 0L) {
                                (bytes / fraction).toLong().coerceAtLeast(bytes)
                            } else {
                                current.totalBytes
                            },
                            statusMessage = if (detail.isNotEmpty()) {
                                "Téléchargement et assemblage HLS... $detail"
                            } else {
                                "Téléchargement et assemblage HLS..."
                            }
                        )
                    }
                }
            } else if (!option.isVideoOnly || pairedAudioUrl == null) {
                if (option.formatKey == "MPEG_4" && targetScaleHeight == null) {
                    downloadToFile(option.videoUrl, outputFile) { downloaded, total ->
                        updateProgress(taskId, downloaded, total, maxProgress = 0.99f)
                    }
                } else {
                    val tmpVideo = tempFile("video", ".tmp").also(tempFiles::add)
                    val downloadMax = if (targetScaleHeight != null) 0.60f else 0.92f
                    downloadToFile(option.videoUrl, tmpVideo) { downloaded, total ->
                        updateProgress(taskId, downloaded, total, maxProgress = downloadMax)
                    }
                    val ffmpeg = findAndLogFfmpeg(taskId)
                    if (targetScaleHeight != null) {
                        val exe = ffmpeg ?: throw IOException(
                            "FFmpeg requis pour convertir la vidéo en ${targetScaleHeight}p."
                        )
                        var maxProgress = downloadMax
                        updateTask(taskId) {
                            it.copy(
                                status = DownloadStatus.TRANSCODING,
                                progress = downloadMax,
                                statusMessage = "Encodage ${targetScaleHeight}p (peut être très long sur ce PC)..."
                            )
                        }
                        runFfmpeg(
                            taskId = taskId,
                            ffmpegExe = exe,
                            args = listOf(
                                "-y",
                                "-i", tmpVideo.absolutePath,
                                "-vf", "scale=-2:$targetScaleHeight",
                                "-c:v", "libx264",
                                "-preset", "veryfast",
                                "-crf", "23",
                                "-c:a", "aac",
                                "-b:a", "128k",
                                "-movflags", "+faststart",
                                outputFile.absolutePath
                            ),
                            durationSeconds = details.durationSeconds
                        ) { fraction, totalSize, outTimeUs ->
                            val nextP = maxOf(
                                maxProgress,
                                (downloadMax + (0.99f - downloadMax) * fraction).coerceIn(downloadMax, 0.99f)
                            )
                            maxProgress = nextP
                            val detail = formatFfmpegProgressSuffix(
                                fraction = fraction,
                                durationSeconds = details.durationSeconds,
                                outTimeUs = outTimeUs,
                                totalSizeBytes = totalSize
                            )
                            updateTask(taskId) {
                                it.copy(
                                    progress = nextP,
                                    statusMessage = if (detail.isNotEmpty()) {
                                        "Encodage ${targetScaleHeight}p ($detail)"
                                    } else {
                                        "Encodage ${targetScaleHeight}p..."
                                    }
                                )
                            }
                        }
                    } else if (ffmpeg != null) {
                        var maxProgress = 0.92f
                        updateTask(taskId) {
                            it.copy(status = DownloadStatus.MUXING, progress = 0.92f, statusMessage = "Conversion en MP4...")
                        }
                        runFfmpeg(
                            taskId = taskId,
                            ffmpegExe = ffmpeg,
                            args = listOf("-y", "-i", tmpVideo.absolutePath, "-c", "copy", "-movflags", "+faststart", outputFile.absolutePath),
                            durationSeconds = details.durationSeconds
                        ) { fraction, totalSize, outTimeUs ->
                            val nextP = maxOf(maxProgress, (0.92f + 0.07f * fraction).coerceIn(0.92f, 0.99f))
                            maxProgress = nextP
                            val detail = formatFfmpegProgressSuffix(
                                fraction = fraction,
                                durationSeconds = details.durationSeconds,
                                outTimeUs = outTimeUs,
                                totalSizeBytes = totalSize
                            )
                            updateTask(taskId) {
                                it.copy(
                                    progress = nextP,
                                    statusMessage = if (detail.isNotEmpty()) "Conversion en MP4... $detail" else "Conversion en MP4..."
                                )
                            }
                        }
                    } else {
                        val fallbackWebm = uniqueOutputFile(outputFile.parentFile, outputFile.nameWithoutExtension, "webm")
                        tmpVideo.copyTo(fallbackWebm, overwrite = true)
                        updateTask(taskId) { it.copy(outputPath = fallbackWebm.absolutePath) }
                    }
                }
            } else {
                val videoTmp = tempFile("dash_v", ".tmp").also(tempFiles::add)
                val audioTmp = tempFile("dash_a", ".tmp").also(tempFiles::add)

                val vLen = probeContentLength(option.videoUrl)
                val aLen = probeContentLength(pairedAudioUrl)
                val bothLengthsKnown = vLen > 0L && aLen > 0L
                val combinedTotal = if (bothLengthsKnown) vLen + aLen else 0L

                val downloadCeiling = if (targetScaleHeight != null) 0.60f else 0.94f
                val videoCeiling = if (bothLengthsKnown) {
                    downloadCeiling * (vLen.toFloat() / combinedTotal.toFloat())
                } else {
                    downloadCeiling * 0.80f
                }

                var downloadedVideo = 0L
                downloadToFile(option.videoUrl, videoTmp) { d, t ->
                    downloadedVideo = d
                    val videoRatio = if (t > 0L) (d.toFloat() / t.toFloat()).coerceIn(0f, 1f) else 0f
                    val overallProgress = (videoRatio * videoCeiling).coerceIn(0f, videoCeiling)
                    val estTotal = if (combinedTotal > 0L) combinedTotal else if (t > 0L) t else d
                    updateTask(taskId) {
                        it.copy(
                            downloadedBytes = d,
                            totalBytes = estTotal.coerceAtLeast(d),
                            progress = overallProgress,
                            statusMessage = speedLabel(taskId, d)
                        )
                    }
                }

                downloadToFile(pairedAudioUrl, audioTmp) { d, t ->
                    val audioRatio = if (t > 0L) (d.toFloat() / t.toFloat()).coerceIn(0f, 1f) else 0f
                    val overallProgress = (videoCeiling + audioRatio * (downloadCeiling - videoCeiling))
                        .coerceIn(videoCeiling, downloadCeiling)
                    val totalDownloaded = downloadedVideo + d
                    val estTotal = when {
                        combinedTotal > 0L -> combinedTotal
                        t > 0L -> downloadedVideo + t
                        else -> totalDownloaded
                    }
                    updateTask(taskId) {
                        it.copy(
                            downloadedBytes = totalDownloaded,
                            totalBytes = estTotal.coerceAtLeast(totalDownloaded),
                            progress = overallProgress,
                            statusMessage = speedLabel(taskId, totalDownloaded)
                        )
                    }
                }

                if (targetScaleHeight != null) {
                    val ffmpeg = findAndLogFfmpeg(taskId)
                        ?: throw IOException(
                            "FFmpeg requis pour convertir la vidéo en ${targetScaleHeight}p."
                        )
                    var maxProgress = downloadCeiling
                    updateTask(taskId) {
                        it.copy(
                            status = DownloadStatus.TRANSCODING,
                            progress = downloadCeiling,
                            statusMessage = "Encodage ${targetScaleHeight}p (peut être très long sur ce PC)..."
                        )
                    }
                    runFfmpeg(
                        taskId = taskId,
                        ffmpegExe = ffmpeg,
                        args = listOf(
                            "-y",
                            "-i", videoTmp.absolutePath,
                            "-i", audioTmp.absolutePath,
                            "-vf", "scale=-2:$targetScaleHeight",
                            "-c:v", "libx264",
                            "-preset", "veryfast",
                            "-crf", "23",
                            "-c:a", "aac",
                            "-b:a", "128k",
                            "-movflags", "+faststart",
                            outputFile.absolutePath
                        ),
                        durationSeconds = details.durationSeconds
                    ) { fraction, totalSize, outTimeUs ->
                        val nextP = maxOf(
                            maxProgress,
                            (downloadCeiling + (0.99f - downloadCeiling) * fraction).coerceIn(downloadCeiling, 0.99f)
                        )
                        maxProgress = nextP
                        val detail = formatFfmpegProgressSuffix(
                            fraction = fraction,
                            durationSeconds = details.durationSeconds,
                            outTimeUs = outTimeUs,
                            totalSizeBytes = totalSize
                        )
                        updateTask(taskId) {
                            it.copy(
                                progress = nextP,
                                statusMessage = if (detail.isNotEmpty()) {
                                    "Encodage ${targetScaleHeight}p ($detail)"
                                } else {
                                    "Encodage ${targetScaleHeight}p..."
                                }
                            )
                        }
                    }
                } else {
                    var maxProgressReached = 0.94f
                    updateTask(taskId) {
                        it.copy(
                            status = DownloadStatus.MUXING,
                            progress = maxProgressReached,
                            statusMessage = "Assemblage rapide (FFmpeg)..."
                        )
                    }

                    val ffmpeg = findAndLogFfmpeg(taskId)
                    val muxer = DesktopStreamMuxerRegistry.muxer
                    val compatibleMp4Pair = option.formatKey == "MPEG_4" &&
                        (pairedAudioFormatKey == null || pairedAudioFormatKey == "M4A")

                    logDownload(
                        "[$taskId] DASH mux info: videoTmp=${videoTmp.length()}B (${option.formatKey}), " +
                            "audioTmp=${audioTmp.length()}B (${pairedAudioFormatKey ?: "unknown"}), " +
                            "compatibleMp4Pair=$compatibleMp4Pair, muxerAvailable=${muxer != null}"
                    )

                    val fastMuxOk = if (ffmpeg != null) {
                        runCatching {
                            runFfmpeg(
                                taskId = taskId,
                                ffmpegExe = ffmpeg,
                                args = listOf(
                                    "-y",
                                    "-i", videoTmp.absolutePath,
                                    "-i", audioTmp.absolutePath,
                                    "-c:v", "copy",
                                    "-c:a", if (pairedAudioFormatKey == "M4A") "copy" else "aac",
                                    "-movflags", "+faststart",
                                    outputFile.absolutePath
                                ),
                                durationSeconds = details.durationSeconds
                            ) { fraction, totalSize, outTimeUs ->
                                val nextP = maxOf(
                                    maxProgressReached,
                                    (0.94f + 0.05f * fraction).coerceIn(0.94f, 0.99f)
                                )
                                maxProgressReached = nextP
                                val detail = formatFfmpegProgressSuffix(
                                    fraction = fraction,
                                    durationSeconds = details.durationSeconds,
                                    outTimeUs = outTimeUs,
                                    totalSizeBytes = totalSize
                                )
                                updateTask(taskId) {
                                    it.copy(
                                        progress = nextP,
                                        statusMessage = if (detail.isNotEmpty()) {
                                            "Assemblage rapide (FFmpeg)... $detail"
                                        } else {
                                            "Assemblage rapide (FFmpeg)..."
                                        }
                                    )
                                }
                            }
                            true
                        }.getOrElse { err ->
                            if (err is CancellationException) throw err
                            Logger.w(err) { "FFmpeg stream-copy mux failed, trying pure-Java muxer or re-encode" }
                            logDownload(
                                "[$taskId] FFmpeg stream-copy failed (${err.javaClass.simpleName}: ${err.message}) -> fallback"
                            )
                            runCatching { outputFile.delete() }
                            false
                        }
                    } else {
                        logDownload("[$taskId] FFmpeg not found -> skipping FFmpeg fast mux")
                        false
                    }

                    val muxSucceededWithJava = if (!fastMuxOk && compatibleMp4Pair && muxer != null) {
                        val muxFloor = maxProgressReached
                        val javaMuxStartMs = System.currentTimeMillis()
                        logDownload(
                            "[$taskId] Switching to Java Mp4FromDashWriter (floor=${(muxFloor * 100f).toInt()}%)"
                        )
                        updateTask(taskId) {
                            it.copy(
                                status = DownloadStatus.MUXING,
                                progress = muxFloor,
                                statusMessage = "Assemblage Java (secours)... 0 %"
                            )
                        }
                        var lastJavaUiMs = 0L
                        runCatching {
                            runInterruptible(Dispatchers.IO) {
                                muxer.muxMp4FromDash(
                                    videoTmp.absolutePath,
                                    audioTmp.absolutePath,
                                    outputFile.absolutePath
                                ) { frac ->
                                    val now = System.currentTimeMillis()
                                    if (frac >= 1f || now - lastJavaUiMs >= 500L) {
                                        lastJavaUiMs = now
                                        val nextP = maxOf(
                                            maxProgressReached,
                                            (muxFloor + (0.99f - muxFloor) * frac).coerceIn(muxFloor, 0.99f)
                                        )
                                        maxProgressReached = nextP
                                        val pct = (frac * 100f).coerceIn(0f, 100f).toInt()
                                        updateTask(taskId) {
                                            it.copy(
                                                status = DownloadStatus.MUXING,
                                                progress = nextP,
                                                statusMessage = "Assemblage Java (secours)... $pct %"
                                            )
                                        }
                                    }
                                }
                            }
                            logDownload(
                                "[$taskId] Java Mp4FromDashWriter succeeded in ${System.currentTimeMillis() - javaMuxStartMs} ms"
                            )
                            true
                        }.getOrElse { err ->
                            if (err is CancellationException) throw err
                            Logger.w(err) { "Pure-Java MP4 DASH muxer failed" }
                            logDownload(
                                "[$taskId] Java Mp4FromDashWriter FAILED after ${System.currentTimeMillis() - javaMuxStartMs} ms: " +
                                    "${err.javaClass.simpleName}: ${err.message}"
                            )
                            runCatching { outputFile.delete() }
                            false
                        }
                    } else {
                        if (!fastMuxOk) {
                            logDownload(
                                "[$taskId] Skipping Java Mp4FromDashWriter (compatibleMp4Pair=$compatibleMp4Pair, muxerAvailable=${muxer != null})"
                            )
                        }
                        false
                    }

                    if (!fastMuxOk && !muxSucceededWithJava) {
                        val exe = ffmpeg ?: throw IOException(
                            "FFmpeg introuvable pour assembler le flux ${option.formatLabel}. Choisissez un flux MP4 ou installez FFmpeg."
                        )
                        val encodeFloor = maxProgressReached
                        logDownload(
                            "[$taskId] Falling back to full libx264 re-encode (floor=${(encodeFloor * 100f).toInt()}%)"
                        )
                        updateTask(taskId) {
                            it.copy(
                                status = DownloadStatus.TRANSCODING,
                                progress = encodeFloor,
                                statusMessage = "Réencodage MP4 (FFmpeg)... 0 % (peut être très long sur ce PC)"
                            )
                        }
                        runFfmpeg(
                            taskId = taskId,
                            ffmpegExe = exe,
                            args = listOf(
                                "-y",
                                "-i", videoTmp.absolutePath,
                                "-i", audioTmp.absolutePath,
                                "-c:v", "libx264",
                                "-preset", "veryfast",
                                "-c:a", "aac",
                                "-movflags", "+faststart",
                                outputFile.absolutePath
                            ),
                            durationSeconds = details.durationSeconds
                        ) { fraction, totalSize, outTimeUs ->
                            val nextP = maxOf(
                                maxProgressReached,
                                (encodeFloor + (0.99f - encodeFloor) * fraction).coerceIn(encodeFloor, 0.99f)
                            )
                            maxProgressReached = nextP
                            val detail = formatFfmpegProgressSuffix(
                                fraction = fraction,
                                durationSeconds = details.durationSeconds,
                                outTimeUs = outTimeUs,
                                totalSizeBytes = totalSize
                            )
                            updateTask(taskId) {
                                it.copy(
                                    progress = nextP,
                                    statusMessage = if (detail.isNotEmpty()) {
                                        "Réencodage MP4 (FFmpeg)... $detail (peut être très long sur ce PC)"
                                    } else {
                                        "Réencodage MP4 (FFmpeg)... (peut être très long sur ce PC)"
                                    }
                                )
                            }
                        }
                    }
                }
            }

            val finalSize = outputFile.takeIf { it.exists() }?.length() ?: _tasks.value.firstOrNull { it.id == taskId }?.downloadedBytes ?: 0L
            logDownload("[$taskId] MP4 completed: size=${finalSize}B, file='${outputFile.absolutePath}'")
            updateTask(taskId) {
                it.copy(
                    progress = 1f,
                    downloadedBytes = finalSize,
                    totalBytes = finalSize,
                    status = DownloadStatus.COMPLETED,
                    statusMessage = "Terminé"
                )
            }
        } catch (ce: CancellationException) {
            logDownload("[$taskId] MP4 cancelled by user")
            outputFile.delete()
            updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED, statusMessage = "Annulé") }
            throw ce
        } catch (t: Throwable) {
            Logger.e(t) { "MP4 download failed for ${details.title}" }
            logDownload("[$taskId] MP4 FAILED: ${t.javaClass.simpleName}: ${t.message}")
            System.err.println("[NewPipe] MP4 download failed for ${details.title}: ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
            outputFile.delete()
            updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    statusMessage = t.message ?: "Échec du téléchargement"
                )
            }
        } finally {
            tempFiles.forEach { runCatching { it.delete() } }
            speedMarks.remove(taskId)
            jobs.remove(taskId)
        }
    }

    private suspend fun executeAudioDownload(
        taskId: String,
        details: VideoDetails,
        audioOption: AudioStreamOption,
        format: DownloadFormat,
        outputFile: File,
        transcodeBitrateKbps: Int? = null
    ) {
        val tempFiles = mutableListOf<File>()
        logDownload(
            "[$taskId] Audio start: title='${details.title}', format=${format.label}, " +
                "src=${audioOption.formatKey}@${audioOption.bitrateKbps}kbps, " +
                "transcodeBitrateKbps=$transcodeBitrateKbps, duration=${details.durationSeconds}s, " +
                "out='${outputFile.absolutePath}'"
        )
        try {
            updateTask(taskId) {
                it.copy(status = DownloadStatus.DOWNLOADING, progress = 0.01f, statusMessage = "Téléchargement audio...")
            }
            val rawTmp = tempFile("audio_raw", ".tmp").also(tempFiles::add)
            val downloadMax = if (transcodeBitrateKbps != null) 0.65f else 0.95f
            downloadToFile(audioOption.url, rawTmp) { downloaded, total ->
                updateProgress(taskId, downloaded, total, maxProgress = downloadMax)
            }

            if (transcodeBitrateKbps != null) {
                val ffmpeg = findAndLogFfmpeg(taskId)
                    ?: throw IOException(
                        "FFmpeg requis pour convertir l'audio en ${format.label}. Installez avec : winget install Gyan.FFmpeg (ou configurez le chemin dans les Paramètres)."
                    )

                val statusBase = when (format) {
                    DownloadFormat.FLAC -> "Conversion en FLAC"
                    DownloadFormat.WAV -> "Conversion en WAV"
                    else -> "Conversion en ${format.label} (${transcodeBitrateKbps} kbps)"
                }
                var maxProgressReached = downloadMax
                updateTask(taskId) {
                    it.copy(
                        status = DownloadStatus.TRANSCODING,
                        progress = downloadMax,
                        statusMessage = "$statusBase..."
                    )
                }

                val codecArgs: List<String> = when (format) {
                    DownloadFormat.MP3 -> listOf("-codec:a", "libmp3lame", "-b:a", "${transcodeBitrateKbps}k")
                    DownloadFormat.M4A -> listOf("-codec:a", "aac", "-b:a", "${transcodeBitrateKbps}k", "-movflags", "+faststart")
                    DownloadFormat.OGG_OPUS -> listOf("-codec:a", "libopus", "-b:a", "${transcodeBitrateKbps}k")
                    DownloadFormat.FLAC -> listOf("-codec:a", "flac")
                    DownloadFormat.WAV -> listOf("-codec:a", "pcm_s16le")
                    DownloadFormat.MP4 -> listOf("-codec:a", "aac", "-b:a", "${transcodeBitrateKbps}k")
                }

                val ffmpegArgs = listOf("-y", "-i", rawTmp.absolutePath, "-vn") +
                    codecArgs +
                    listOf(
                        "-metadata", "title=${details.title}",
                        "-metadata", "artist=${details.uploaderName}",
                        "-metadata", "comment=${details.url}",
                        outputFile.absolutePath
                    )

                runFfmpeg(
                    taskId = taskId,
                    ffmpegExe = ffmpeg,
                    args = ffmpegArgs,
                    durationSeconds = details.durationSeconds
                ) { fraction, totalSize, outTimeUs ->
                    val nextP = maxOf(
                        maxProgressReached,
                        (downloadMax + (0.99f - downloadMax) * fraction).coerceIn(downloadMax, 0.99f)
                    )
                    maxProgressReached = nextP
                    val detail = formatFfmpegProgressSuffix(
                        fraction = fraction,
                        durationSeconds = details.durationSeconds,
                        outTimeUs = outTimeUs,
                        totalSizeBytes = totalSize
                    )
                    updateTask(taskId) {
                        it.copy(
                            progress = nextP,
                            statusMessage = if (detail.isNotEmpty()) "$statusBase... $detail" else "$statusBase..."
                        )
                    }
                }
            } else {
                val muxer = DesktopStreamMuxerRegistry.muxer
                if (format == DownloadFormat.OGG_OPUS && muxer != null) {
                    logDownload("[$taskId] Demuxing Ogg/Opus from WebM via Java OggFromWebMWriter")
                    updateTask(taskId) {
                        it.copy(status = DownloadStatus.MUXING, progress = 0.97f, statusMessage = "Préparation Ogg/Opus...")
                    }
                    val muxed = runCatching {
                        runInterruptible(Dispatchers.IO) {
                            muxer.demuxOggFromWebm(
                                sourcePath = rawTmp.absolutePath,
                                outputPath = outputFile.absolutePath,
                                url = details.url,
                                category = details.category,
                                uploaderName = details.uploaderName,
                                title = details.title,
                                isoDate = details.isoUploadDate
                            )
                        }
                        true
                    }.getOrElse { err ->
                        if (err is CancellationException) throw err
                        logDownload("[$taskId] Java OggFromWebMWriter fallback to .webm: ${err.javaClass.simpleName}: ${err.message}")
                        false
                    }
                    if (!muxed) {
                        val webmOut = uniqueOutputFile(outputFile.parentFile, outputFile.nameWithoutExtension, "webm")
                        rawTmp.copyTo(webmOut, overwrite = true)
                        updateTask(taskId) { it.copy(outputPath = webmOut.absolutePath) }
                    }
                } else {
                    val remuxed = if (muxer != null && format == DownloadFormat.M4A) {
                        logDownload("[$taskId] Remuxing native M4A via Java Mp4FromDashWriter")
                        var maxRemuxProgress = 0.95f
                        var lastJavaUiMs = 0L
                        updateTask(taskId) {
                            it.copy(status = DownloadStatus.MUXING, progress = 0.95f, statusMessage = "Finalisation M4A... 0 %")
                        }
                        runCatching {
                            runInterruptible(Dispatchers.IO) {
                                muxer.remuxM4aFromDash(rawTmp.absolutePath, outputFile.absolutePath) { frac ->
                                    val now = System.currentTimeMillis()
                                    if (frac >= 1f || now - lastJavaUiMs >= 500L) {
                                        lastJavaUiMs = now
                                        val nextP = maxOf(
                                            maxRemuxProgress,
                                            (0.95f + 0.04f * frac).coerceIn(0.95f, 0.99f)
                                        )
                                        maxRemuxProgress = nextP
                                        val pct = (frac * 100f).coerceIn(0f, 100f).toInt()
                                        updateTask(taskId) {
                                            it.copy(
                                                status = DownloadStatus.MUXING,
                                                progress = nextP,
                                                statusMessage = "Finalisation M4A... $pct %"
                                            )
                                        }
                                    }
                                }
                            }
                            true
                        }.getOrElse { err ->
                            if (err is CancellationException) throw err
                            logDownload("[$taskId] Java M4A remux fallback to raw copy: ${err.javaClass.simpleName}: ${err.message}")
                            false
                        }
                    } else {
                        false
                    }
                    if (!remuxed) {
                        updateTask(taskId) { it.copy(statusMessage = "Finalisation...") }
                        rawTmp.copyTo(outputFile, overwrite = true)
                    }
                }
            }

            val finalSize = outputFile.takeIf { it.exists() }?.length() ?: 0L
            logDownload("[$taskId] Audio completed: size=${finalSize}B, file='${outputFile.absolutePath}'")
            updateTask(taskId) {
                it.copy(
                    progress = 1f,
                    downloadedBytes = finalSize,
                    totalBytes = finalSize,
                    status = DownloadStatus.COMPLETED,
                    statusMessage = "Terminé"
                )
            }
        } catch (ce: CancellationException) {
            logDownload("[$taskId] Audio cancelled by user")
            outputFile.delete()
            updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED, statusMessage = "Annulé") }
            throw ce
        } catch (t: Throwable) {
            Logger.e(t) { "Audio download failed (${format.label}) for ${details.title}" }
            logDownload("[$taskId] Audio FAILED (${format.label}): ${t.javaClass.simpleName}: ${t.message}")
            System.err.println("[NewPipe] Audio download (${format.label}) failed for ${details.title}: ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
            outputFile.delete()
            updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    statusMessage = t.message ?: "Échec du téléchargement"
                )
            }
        } finally {
            tempFiles.forEach { runCatching { it.delete() } }
            speedMarks.remove(taskId)
            jobs.remove(taskId)
        }
    }

    private fun probeContentLength(url: String): Long = runCatching {
        val probeClient = downloader.mediaClient.newBuilder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .head()
            .url(url)
            .addHeader("User-Agent", DesktopDownloader.USER_AGENT)
            .build()
        probeClient.newCall(req).execute().use { resp ->
            resp.header("Content-Length")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        }
    }.getOrElse { 0L }

    private suspend fun downloadToFile(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val maxRetries = settings.getString(
            DownloadPreferences.KEY_MAXIMUM_RETRIES,
            DownloadPreferences.DEFAULT_MAXIMUM_RETRIES
        ).toIntOrNull()?.coerceIn(0, 15) ?: 3

        target.parentFile?.mkdirs()
        target.delete()

        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= maxRetries) {
            try {
                downloadChunkedOrDirect(url, target, onProgress)
                return
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lastError = t
                attempt++
                System.err.println("[NewPipe] Download attempt $attempt/$maxRetries failed (${t.javaClass.simpleName}): ${t.message}")
                if (attempt <= maxRetries) {
                    delay(500L * attempt)
                }
            }
        }
        throw lastError ?: IOException("Failed to download $url")
    }

    private suspend fun downloadChunkedOrDirect(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        val chunkSize = 8L * 1024L * 1024L
        var downloaded = if (target.exists()) target.length().coerceAtLeast(0L) else 0L
        var knownTotal = -1L
        var useRange = true
        val ctx = currentCoroutineContext()

        while (ctx.isActive) {
            val reqBuilder = Request.Builder()
                .url(url)
                .addHeader("User-Agent", DesktopDownloader.USER_AGENT)

            if (useRange) {
                val rangeEnd = if (knownTotal > 0L) {
                    minOf(downloaded + chunkSize - 1L, knownTotal - 1L)
                } else {
                    downloaded + chunkSize - 1L
                }
                if (knownTotal > 0L && downloaded >= knownTotal) {
                    onProgress(downloaded, knownTotal)
                    return
                }
                reqBuilder.addHeader("Range", "bytes=$downloaded-$rangeEnd")
            }

            var shouldContinueChunkLoop = false

            downloader.mediaClient.newCall(reqBuilder.build()).execute().use { response ->
                if (response.code == 416) {
                    val rangeTotal = response.header("Content-Range")
                        ?.substringAfter('/', "")
                        ?.trim()
                        ?.toLongOrNull()
                    val resolvedTotal = rangeTotal?.takeIf { it > 0L } ?: knownTotal
                    if (resolvedTotal > 0L && downloaded >= resolvedTotal) {
                        onProgress(downloaded, resolvedTotal)
                        return
                    }
                    target.delete()
                    downloaded = 0L
                    knownTotal = -1L
                    useRange = false
                    shouldContinueChunkLoop = true
                    return@use
                }

                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: ${response.message}")
                }

                val isPartial = response.code == 206
                if (isPartial) {
                    val contentRangeTotal = response.header("Content-Range")
                        ?.substringAfter('/', "")
                        ?.trim()
                        ?.toLongOrNull()
                    if (contentRangeTotal != null && contentRangeTotal > 0L) {
                        knownTotal = contentRangeTotal
                    }
                } else {
                    // Full 200 response (server ignored Range or Range disabled)
                    target.delete()
                    downloaded = 0L
                    val bodyLen = response.body.contentLength()
                    if (bodyLen > 0L) {
                        knownTotal = bodyLen
                    }
                }

                val body = response.body
                var bytesThisResponse = 0L
                val append = isPartial && downloaded > 0L
                body.byteStream().use { input ->
                    FileOutputStream(target, append).buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastEmit = 0L
                        while (ctx.isActive) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            bytesThisResponse += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= 500L) {
                                lastEmit = now
                                onProgress(downloaded, knownTotal)
                            }
                        }
                        if (!ctx.isActive) throw CancellationException("Download cancelled")
                        output.flush()
                    }
                }

                val finalTotal = if (knownTotal > 0L) knownTotal else downloaded
                onProgress(downloaded, finalTotal)

                if (!isPartial) {
                    return
                }
                if (knownTotal > 0L && downloaded >= knownTotal) {
                    return
                }
                if (bytesThisResponse == 0L || (knownTotal <= 0L && bytesThisResponse < chunkSize)) {
                    return
                }
                shouldContinueChunkLoop = true
            }

            if (!shouldContinueChunkLoop) {
                return
            }
        }
        if (!ctx.isActive) throw CancellationException("Download cancelled")
    }

    private suspend fun runFfmpeg(
        taskId: String,
        ffmpegExe: File,
        args: List<String>,
        durationSeconds: Long = 0L,
        onProgress: ((fraction: Float, totalSizeBytes: Long, outTimeUs: Long) -> Unit)? = null
    ) {
        val command = listOf(ffmpegExe.absolutePath, "-nostdin", "-progress", "pipe:1", "-nostats") + args
        logDownload("[$taskId] FFmpeg exe: ${ffmpegExe.absolutePath}")
        logDownload("[$taskId] FFmpeg cmd: ${command.joinToString(" ")}")

        val startedMs = System.currentTimeMillis()
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
        runCatching { process.outputStream.close() }

        val stderrLock = Any()
        val recentErrors = ArrayDeque<String>(40)
        val stderrThread = Thread({
            runCatching {
                process.errorStream.bufferedReader().use { errReader ->
                    while (true) {
                        val line = errReader.readLine() ?: break
                        if (line.isNotBlank()) {
                            synchronized(stderrLock) {
                                if (recentErrors.size >= 40) recentErrors.removeFirst()
                                recentErrors.addLast(line)
                            }
                        }
                    }
                }
            }
        }, "ffmpeg-stderr-$taskId").apply {
            isDaemon = true
            start()
        }

        val ctx = currentCoroutineContext()
        val cancellationHook = ctx[Job]?.invokeOnCompletion { cause ->
            if (cause != null && process.isAlive) {
                process.destroyForcibly()
            }
        }
        var latestTotalSize = 0L
        var latestOutTimeUs = 0L
        var lastEmitMs = 0L

        try {
            process.inputStream.bufferedReader().use { reader ->
                while (ctx.isActive) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("total_size=") -> {
                            line.substringAfter('=').trim().toLongOrNull()?.let { latestTotalSize = it }
                        }
                        line.startsWith("out_time_us=") -> {
                            line.substringAfter('=').trim().toLongOrNull()?.let { latestOutTimeUs = it }
                        }
                        line.startsWith("out_time_ms=") -> {
                            if (latestOutTimeUs <= 0L) {
                                line.substringAfter('=').trim().toLongOrNull()?.let { latestOutTimeUs = it }
                            }
                        }
                        line.startsWith("out_time=") -> {
                            if (latestOutTimeUs <= 0L) {
                                parseFfmpegOutTimeUs(line.substringAfter('='))?.let { latestOutTimeUs = it }
                            }
                        }
                        line.startsWith("progress=") -> {
                            val state = line.substringAfter('=').trim()
                            val now = System.currentTimeMillis()
                            if (onProgress != null && (state == "end" || now - lastEmitMs >= 500L)) {
                                lastEmitMs = now
                                val fraction = if (state == "end") {
                                    1f
                                } else {
                                    computeFfmpegProgressFraction(latestOutTimeUs, durationSeconds)
                                }
                                onProgress(fraction, latestTotalSize, latestOutTimeUs)
                            }
                        }
                    }
                }
            }
            if (!ctx.isActive) {
                process.destroyForcibly()
                logDownload("[$taskId] FFmpeg cancelled after ${System.currentTimeMillis() - startedMs} ms")
                throw CancellationException("FFmpeg cancelled")
            }
            val code = process.waitFor()
            stderrThread.join(1000L)
            val elapsedMs = System.currentTimeMillis() - startedMs
            val errLines = synchronized(stderrLock) { recentErrors.toList() }
            if (code != 0) {
                logDownload("[$taskId] FFmpeg FAILED (exit=$code, elapsed=${elapsedMs}ms). Last stderr lines:")
                errLines.forEach { logDownload("[$taskId]   stderr: $it") }
                val summary = errLines.takeLast(8).joinToString(" | ")
                throw IOException("FFmpeg failed (exit $code): $summary")
            }
            logDownload("[$taskId] FFmpeg OK (exit=0, elapsed=${elapsedMs}ms, size=${latestTotalSize}B)")
            onProgress?.invoke(1f, latestTotalSize, latestOutTimeUs)
        } catch (ce: CancellationException) {
            if (process.isAlive) process.destroyForcibly()
            throw ce
        } finally {
            cancellationHook?.dispose()
            if (process.isAlive) {
                process.destroyForcibly()
            }
            runCatching { stderrThread.join(500L) }
        }
    }

    private fun findAndLogFfmpeg(taskId: String): File? {
        val customPath = settings.getString(DesktopPreferences.KEY_FFMPEG_PATH, "")
        val found = WindowsAppPaths.findFfmpegExecutable(customPath)
        logDownload(
            "[$taskId] FFmpeg lookup -> ${found?.absolutePath ?: "NOT FOUND"} " +
                "(setting='${customPath.ifBlank { "<auto>" }}')"
        )
        return found
    }

    private fun logDownload(message: String) {
        synchronized(logLock) {
            appendLogLine(WindowsAppPaths.downloadLogFile(), message)
        }
    }

    private fun resolveVideoFolder(): File {
        val custom = settings.getString(DownloadPreferences.KEY_DOWNLOAD_FOLDER_VIDEO, "")
        return if (custom.isNotBlank()) {
            File(custom).apply { mkdirs() }
        } else {
            WindowsAppPaths.defaultVideoDownloadDir()
        }
    }

    private fun resolveAudioFolder(): File {
        val custom = settings.getString(DownloadPreferences.KEY_DOWNLOAD_FOLDER_AUDIO, "")
        return if (custom.isNotBlank()) {
            File(custom).apply { mkdirs() }
        } else {
            WindowsAppPaths.defaultAudioDownloadDir()
        }
    }

    private fun sanitizeTitle(title: String): String {
        val charset = settings.getString(
            DownloadPreferences.KEY_FILE_CHARSET,
            DownloadPreferences.DEFAULT_FILE_CHARSET
        )
        val replacement = settings.getString(
            DownloadPreferences.KEY_REPLACEMENT_CHARACTER,
            DownloadPreferences.DEFAULT_REPLACEMENT_CHARACTER
        )
        return DesktopStreamHelper.sanitizeFilename(title, charset, replacement)
    }

    private fun uniqueOutputFile(dir: File, baseName: String, extension: String): File {
        var candidate = File(dir, "$baseName.$extension")
        var idx = 1
        while (candidate.exists()) {
            candidate = File(dir, "$baseName ($idx).$extension")
            idx++
        }
        return candidate
    }

    private fun tempFile(prefix: String, suffix: String): File =
        File.createTempFile("${prefix}_", suffix, WindowsAppPaths.tempDownloadDir())

    private fun addTask(item: DownloadTaskItem) {
        _tasks.update { list -> listOf(item) + list }
    }

    private fun updateTask(taskId: String, transform: (DownloadTaskItem) -> DownloadTaskItem) {
        _tasks.update { list ->
            list.map { if (it.id == taskId) transform(it) else it }
        }
    }

    private fun updateProgress(
        taskId: String,
        downloaded: Long,
        total: Long,
        maxProgress: Float = 0.99f
    ) {
        val progress = if (total > 0L) {
            ((downloaded.toFloat() / total.toFloat()) * maxProgress).coerceIn(0f, maxProgress)
        } else {
            0f
        }
        updateTask(taskId) {
            it.copy(
                downloadedBytes = downloaded,
                totalBytes = total.coerceAtLeast(downloaded),
                progress = progress,
                statusMessage = speedLabel(taskId, downloaded)
            )
        }
    }

    private fun speedLabel(taskId: String, downloadedNow: Long): String {
        val now = System.currentTimeMillis()
        val mark = speedMarks.getOrPut(taskId) { longArrayOf(now, downloadedNow, 0L) }
        return synchronized(mark) {
            if (downloadedNow < mark[1]) {
                mark[0] = now
                mark[1] = downloadedNow
                "Téléchargement..."
            } else {
                val elapsed = now - mark[0]
                if (elapsed >= 1500L) {
                    mark[2] = (downloadedNow - mark[1]) * 1000L / elapsed
                    mark[0] = now
                    mark[1] = downloadedNow
                }
                val speed = mark[2]
                if (speed > 0L) "Téléchargement... " + formatSpeed(speed) else "Téléchargement..."
            }
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        val mo = bytesPerSecond / 1048576.0
        return if (mo >= 1.0) {
            String.format(Locale.FRANCE, "%.1f Mo/s", mo)
        } else {
            "${bytesPerSecond / 1024} Ko/s"
        }
    }

    private fun formatMegabytes(bytes: Long): String {
        val mo = bytes / 1048576.0
        return if (mo >= 1.0) {
            String.format(Locale.FRANCE, "%.1f Mo", mo)
        } else {
            "${bytes / 1024L} Ko"
        }
    }

    private fun formatElapsedSeconds(totalSec: Long): String {
        val s = totalSec.coerceAtLeast(0L)
        val hours = s / 3600L
        val minutes = (s % 3600L) / 60L
        val secs = s % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, secs)
        }
    }

    private fun formatFfmpegProgressSuffix(
        fraction: Float,
        durationSeconds: Long,
        outTimeUs: Long,
        totalSizeBytes: Long
    ): String {
        val parts = mutableListOf<String>()
        if (durationSeconds > 0L) {
            parts += "${(fraction * 100f).coerceIn(0f, 100f).toInt()} %"
        } else if (outTimeUs > 0L) {
            parts += formatElapsedSeconds(outTimeUs / 1_000_000L)
        }
        if (totalSizeBytes > 0L) {
            parts += formatMegabytes(totalSizeBytes)
        }
        return parts.joinToString(" • ")
    }

    companion object {
        private val LOG_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        private const val MAX_LOG_SIZE_BYTES = 2L * 1024L * 1024L

        internal fun appendLogLine(logFile: File, message: String) {
            runCatching {
                logFile.parentFile?.mkdirs()
                if (logFile.exists() && logFile.length() > MAX_LOG_SIZE_BYTES) {
                    val backup = File(logFile.parentFile, "${logFile.name}.old")
                    if (backup.exists()) backup.delete()
                    logFile.renameTo(backup)
                }
                val timestamp = LocalDateTime.now().format(LOG_TIME_FORMATTER)
                logFile.appendText("[$timestamp] $message\n", Charsets.UTF_8)
            }
        }

        internal fun parseFfmpegOutTimeUs(value: String): Long? {
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed.equals("N/A", ignoreCase = true)) return null
            val parts = trimmed.split(':')
            if (parts.size != 3) return null
            val hours = parts[0].toLongOrNull() ?: return null
            val minutes = parts[1].toLongOrNull() ?: return null
            val seconds = parts[2].toDoubleOrNull() ?: return null
            val totalSeconds = hours * 3600.0 + minutes * 60.0 + seconds
            if (totalSeconds < 0.0) return null
            return (totalSeconds * 1_000_000.0).toLong()
        }

        internal fun resolveTargetDirectoryForOpen(
            path: String,
            fallbackDir: File = WindowsAppPaths.defaultVideoDownloadDir()
        ): File {
            if (path.isBlank()) return fallbackDir
            val file = File(path)
            return when {
                file.isDirectory -> file
                file.parentFile != null -> file.parentFile
                else -> fallbackDir
            }
        }

        internal fun computeFfmpegProgressFraction(outTimeUs: Long, durationSeconds: Long): Float {
            if (outTimeUs <= 0L || durationSeconds <= 0L) return 0f
            val durationUs = durationSeconds.toDouble() * 1_000_000.0
            return (outTimeUs.toDouble() / durationUs).toFloat().coerceIn(0f, 1f)
        }
    }
}
