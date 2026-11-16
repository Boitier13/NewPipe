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
 * Windows/PC download manager supporting MP4 (DASH muxing + FFmpeg fallback),
 * MP3 (FFmpeg libmp3lame conversion), and native M4A/OGG-Opus audio downloads.
 */
@Singleton(binds = [DownloadController::class])
class JVMDownloadController(
    private val settings: Settings,
    private val downloader: DesktopDownloader,
    private val dataSaverController: DataSaverController
) : DownloadController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val concurrencySemaphore = Semaphore(3)

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
            val permitLimit = if (settings.getBoolean(
                    DownloadPreferences.KEY_LIMIT_QUEUE,
                    DownloadPreferences.DEFAULT_LIMIT_QUEUE
                )
            ) 1 else 0

            val block: suspend () -> Unit = {
                executeMp4Download(taskId, details, videoOption, outputFile)
            }
            if (permitLimit > 0) {
                concurrencySemaphore.withPermit { block() }
            } else {
                block()
            }
        }
        jobs[taskId] = job
    }

    override fun enqueueMp3(
        details: VideoDetails,
        audioOption: AudioStreamOption,
        targetBitrateKbps: Int?
    ) {
        val dir = resolveAudioFolder()
        val outputFile = uniqueOutputFile(dir, sanitizeTitle(details.title), DownloadFormat.MP3.extension)
        val taskId = UUID.randomUUID().toString()

        val effectiveOption = if (dataSaverController.isDataSaverEnabled.value && details.audioStreams.isNotEmpty()) {
            details.audioStreams.minByOrNull { it.bitrateKbps } ?: audioOption
        } else {
            details.audioStreams.maxByOrNull { it.bitrateKbps } ?: audioOption
        }
        val bitrateArg = targetBitrateKbps?.let { "${it}k" }
            ?: settings.getString(DesktopPreferences.KEY_MP3_BITRATE, DesktopPreferences.DEFAULT_MP3_BITRATE)

        val initial = DownloadTaskItem(
            id = taskId,
            title = details.title,
            uploader = details.uploaderName,
            format = DownloadFormat.MP3,
            resolutionLabel = bitrateArg.replace("k", " kbps"),
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = effectiveOption.approxSizeBytes.coerceAtLeast(0L),
            outputPath = outputFile.absolutePath,
            status = DownloadStatus.QUEUED
        )
        addTask(initial)

        val job = scope.launch {
            concurrencySemaphore.withPermit {
                executeMp3Download(taskId, details, effectiveOption, bitrateArg, outputFile)
            }
        }
        jobs[taskId] = job
    }

    override fun enqueueNativeAudio(
        details: VideoDetails,
        audioOption: AudioStreamOption,
        targetBitrateKbps: Int?
    ) {
        val dir = resolveAudioFolder()
        val matchingNativeM4a = if (targetBitrateKbps != null) {
            details.audioStreams.firstOrNull {
                it.formatKey == "M4A" && it.bitrateKbps == targetBitrateKbps
            }
        } else {
            null
        }
        val willTranscodeM4a = targetBitrateKbps != null && matchingNativeM4a == null

        val effectiveOption = when {
            matchingNativeM4a != null -> matchingNativeM4a
            willTranscodeM4a -> details.audioStreams.maxByOrNull { it.bitrateKbps } ?: audioOption
            dataSaverController.isDataSaverEnabled.value && details.audioStreams.isNotEmpty() ->
                details.audioStreams.minByOrNull { it.bitrateKbps } ?: audioOption
            else -> audioOption
        }

        val isOpus = !willTranscodeM4a &&
            matchingNativeM4a == null &&
            (effectiveOption.formatKey == "WEBMA_OPUS" || effectiveOption.formatKey == "WEBMA")
        val format = if (isOpus) DownloadFormat.OGG_OPUS else DownloadFormat.M4A
        val outputFile = uniqueOutputFile(dir, sanitizeTitle(details.title), format.extension)
        val taskId = UUID.randomUUID().toString()
        val labelKbps = targetBitrateKbps ?: effectiveOption.bitrateKbps

        val initial = DownloadTaskItem(
            id = taskId,
            title = details.title,
            uploader = details.uploaderName,
            format = format,
            resolutionLabel = "$labelKbps kbps",
            progress = 0f,
            downloadedBytes = 0L,
            totalBytes = effectiveOption.approxSizeBytes.coerceAtLeast(0L),
            outputPath = outputFile.absolutePath,
            status = DownloadStatus.QUEUED
        )
        addTask(initial)

        val job = scope.launch {
            concurrencySemaphore.withPermit {
                executeNativeAudioDownload(
                    taskId = taskId,
                    details = details,
                    audioOption = effectiveOption,
                    format = format,
                    outputFile = outputFile,
                    transcodeBitrateKbps = if (willTranscodeM4a) targetBitrateKbps else null
                )
            }
        }
        jobs[taskId] = job
    }

    override fun cancelTask(taskId: String) {
        jobs.remove(taskId)?.cancel()
        updateTask(taskId) {
            if (it.status != DownloadStatus.COMPLETED && it.status != DownloadStatus.FAILED) {
                it.copy(status = DownloadStatus.CANCELLED, statusMessage = "Cancelled")
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
        runCatching {
            val file = File(path)
            if (WindowsAppPaths.isWindows && file.exists()) {
                ProcessBuilder("explorer.exe", "/select,\"${file.absolutePath}\"").start()
            } else {
                val target = if (file.isDirectory) file else (file.parentFile ?: WindowsAppPaths.defaultVideoDownloadDir())
                if (target.exists() && Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(target)
                }
            }
        }.onFailure {
            Logger.w(it) { "Unable to open folder for $path" }
        }
    }

    private suspend fun executeMp4Download(
        taskId: String,
        details: VideoDetails,
        option: VideoStreamOption,
        outputFile: File
    ) {
        val tempFiles = mutableListOf<File>()
        try {
            updateTask(taskId) { it.copy(status = DownloadStatus.DOWNLOADING, statusMessage = "Downloading...") }

            val pairedAudioUrl = if (option.isVideoOnly) {
                option.pairedAudioUrl ?: details.audioStreams.firstOrNull()?.url
            } else {
                null
            }

            val isHls = option.formatKey == "HLS" || option.videoUrl.contains(".m3u8")
            val targetScaleHeight = option.targetScaleHeight

            if (isHls) {
                val ffmpeg = WindowsAppPaths.findFfmpegExecutable(
                    settings.getString(DesktopPreferences.KEY_FFMPEG_PATH, "")
                ) ?: throw IOException(
                    "FFmpeg required for HLS stream download. Install FFmpeg or choose a direct MP4 stream."
                )
                updateTask(taskId) {
                    it.copy(
                        status = DownloadStatus.TRANSCODING,
                        progress = 0.25f,
                        statusMessage = "Downloading & muxing HLS..."
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
                runFfmpeg(ffmpeg, args)
            } else if (!option.isVideoOnly || pairedAudioUrl == null) {
                if (option.formatKey == "MPEG_4" && targetScaleHeight == null) {
                    downloadToFile(option.videoUrl, outputFile) { downloaded, total ->
                        updateProgress(taskId, downloaded, total)
                    }
                } else {
                    val tmpVideo = tempFile("video", ".tmp").also(tempFiles::add)
                    downloadToFile(option.videoUrl, tmpVideo) { downloaded, total ->
                        updateProgress(taskId, downloaded, total)
                    }
                    val ffmpeg = WindowsAppPaths.findFfmpegExecutable(
                        settings.getString(DesktopPreferences.KEY_FFMPEG_PATH, "")
                    )
                    if (targetScaleHeight != null) {
                        val exe = ffmpeg ?: throw IOException(
                            "FFmpeg required to convert video to ${targetScaleHeight}p."
                        )
                        updateTask(taskId) {
                            it.copy(
                                status = DownloadStatus.TRANSCODING,
                                progress = 0.95f,
                                statusMessage = "Encoding ${targetScaleHeight}p MP4..."
                            )
                        }
                        runFfmpeg(
                            exe,
                            listOf(
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
                            )
                        )
                    } else if (ffmpeg != null) {
                        updateTask(taskId) { it.copy(status = DownloadStatus.MUXING, statusMessage = "Converting to MP4...") }
                        runFfmpeg(
                            ffmpeg,
                            listOf("-y", "-i", tmpVideo.absolutePath, "-c", "copy", "-movflags", "+faststart", outputFile.absolutePath)
                        )
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
                val combinedTotal = vLen + aLen

                var downloadedVideo = 0L
                downloadToFile(option.videoUrl, videoTmp) { d, t ->
                    downloadedVideo = d
                    val estTotal = if (combinedTotal > 0) combinedTotal else t
                    updateProgress(taskId, d, estTotal)
                }

                downloadToFile(pairedAudioUrl, audioTmp) { d, t ->
                    val estTotal = if (combinedTotal > 0) combinedTotal else (downloadedVideo + t)
                    updateProgress(taskId, downloadedVideo + d, estTotal)
                }

                if (targetScaleHeight != null) {
                    val ffmpeg = WindowsAppPaths.findFfmpegExecutable(
                        settings.getString(DesktopPreferences.KEY_FFMPEG_PATH, "")
                    ) ?: throw IOException(
                        "FFmpeg required to convert video to ${targetScaleHeight}p."
                    )
                    updateTask(taskId) {
                        it.copy(
                            status = DownloadStatus.TRANSCODING,
                            progress = 0.95f,
                            statusMessage = "Encoding ${targetScaleHeight}p MP4..."
                        )
                    }
                    runFfmpeg(
                        ffmpeg,
                        listOf(
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
                        )
                    )
                } else {
                    updateTask(taskId) {
                        it.copy(
                            status = DownloadStatus.MUXING,
                            progress = 0.98f,
                            statusMessage = "Muxing video & audio..."
                        )
                    }

                    val muxer = DesktopStreamMuxerRegistry.muxer
                    val compatibleMp4Pair = option.formatKey == "MPEG_4" &&
                        (option.pairedAudioFormatKey == null || option.pairedAudioFormatKey == "M4A")

                    val muxSucceededWithJava = compatibleMp4Pair && muxer != null && runCatching {
                        muxer.muxMp4FromDash(
                            videoTmp.absolutePath,
                            audioTmp.absolutePath,
                            outputFile.absolutePath
                        )
                        true
                    }.getOrElse {
                        Logger.w(it) { "Pure-Java MP4 DASH muxer failed, falling back to FFmpeg" }
                        false
                    }

                    if (!muxSucceededWithJava) {
                        val ffmpeg = WindowsAppPaths.findFfmpegExecutable(
                            settings.getString(DesktopPreferences.KEY_FFMPEG_PATH, "")
                        ) ?: throw IOException(
                            "FFmpeg not found for muxing ${option.formatLabel} stream. Select an MP4 stream or install FFmpeg."
                        )

                        val fastMuxOk = runCatching {
                            runFfmpeg(
                                ffmpeg,
                                listOf(
                                    "-y",
                                    "-i", videoTmp.absolutePath,
                                    "-i", audioTmp.absolutePath,
                                    "-c:v", "copy",
                                    "-c:a", if (option.pairedAudioFormatKey == "M4A") "copy" else "aac",
                                    "-movflags", "+faststart",
                                    outputFile.absolutePath
                                )
                            )
                            true
                        }.getOrElse { false }

                        if (!fastMuxOk) {
                            runFfmpeg(
                                ffmpeg,
                                listOf(
                                    "-y",
                                    "-i", videoTmp.absolutePath,
                                    "-i", audioTmp.absolutePath,
                                    "-c:v", "libx264",
                                    "-preset", "veryfast",
                                    "-c:a", "aac",
                                    "-movflags", "+faststart",
                                    outputFile.absolutePath
                                )
                            )
                        }
                    }
                }
            }

            val finalSize = outputFile.takeIf { it.exists() }?.length() ?: _tasks.value.firstOrNull { it.id == taskId }?.downloadedBytes ?: 0L
            updateTask(taskId) {
                it.copy(
                    progress = 1f,
                    downloadedBytes = finalSize,
                    totalBytes = finalSize,
                    status = DownloadStatus.COMPLETED,
                    statusMessage = "Completed"
                )
            }
        } catch (ce: CancellationException) {
            outputFile.delete()
            updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED, statusMessage = "Cancelled") }
            throw ce
        } catch (t: Throwable) {
            Logger.e(t) { "MP4 download failed for ${details.title}" }
            System.err.println("[NewPipe] MP4 download failed for ${details.title}: ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
            outputFile.delete()
            updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    statusMessage = t.message ?: "Download failed"
                )
            }
        } finally {
            tempFiles.forEach { runCatching { it.delete() } }
            jobs.remove(taskId)
        }
    }

    private suspend fun executeMp3Download(
        taskId: String,
        details: VideoDetails,
        audioOption: AudioStreamOption,
        bitrateArg: String,
        outputFile: File
    ) {
        val tempFiles = mutableListOf<File>()
        try {
            val customPath = settings.getString(DesktopPreferences.KEY_FFMPEG_PATH, "")
            val ffmpeg = WindowsAppPaths.findFfmpegExecutable(customPath)
                ?: throw IOException(
                    "FFmpeg required for MP3 conversion. Install on Windows with: winget install Gyan.FFmpeg (or configure path in Settings)."
                )

            updateTask(taskId) { it.copy(status = DownloadStatus.DOWNLOADING, statusMessage = "Downloading audio...") }
            val audioTmp = tempFile("mp3_src", ".tmp").also(tempFiles::add)
            downloadToFile(audioOption.url, audioTmp) { downloaded, total ->
                updateProgress(taskId, downloaded, total)
            }

            updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.TRANSCODING,
                    progress = 0.95f,
                    statusMessage = "Converting to MP3 ($bitrateArg)..."
                )
            }

            runFfmpeg(
                ffmpeg,
                listOf(
                    "-y",
                    "-i", audioTmp.absolutePath,
                    "-vn",
                    "-codec:a", "libmp3lame",
                    "-b:a", bitrateArg,
                    "-metadata", "title=${details.title}",
                    "-metadata", "artist=${details.uploaderName}",
                    "-metadata", "comment=${details.url}",
                    outputFile.absolutePath
                )
            )

            val finalSize = outputFile.takeIf { it.exists() }?.length() ?: 0L
            updateTask(taskId) {
                it.copy(
                    progress = 1f,
                    downloadedBytes = finalSize,
                    totalBytes = finalSize,
                    status = DownloadStatus.COMPLETED,
                    statusMessage = "Completed"
                )
            }
        } catch (ce: CancellationException) {
            outputFile.delete()
            updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED, statusMessage = "Cancelled") }
            throw ce
        } catch (t: Throwable) {
            Logger.e(t) { "MP3 download failed for ${details.title}" }
            System.err.println("[NewPipe] MP3 download failed for ${details.title}: ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
            outputFile.delete()
            updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    statusMessage = t.message ?: "MP3 conversion failed"
                )
            }
        } finally {
            tempFiles.forEach { runCatching { it.delete() } }
            jobs.remove(taskId)
        }
    }

    private suspend fun executeNativeAudioDownload(
        taskId: String,
        details: VideoDetails,
        audioOption: AudioStreamOption,
        format: DownloadFormat,
        outputFile: File,
        transcodeBitrateKbps: Int? = null
    ) {
        val tempFiles = mutableListOf<File>()
        try {
            updateTask(taskId) { it.copy(status = DownloadStatus.DOWNLOADING, statusMessage = "Downloading audio...") }
            val rawTmp = tempFile("audio_raw", ".tmp").also(tempFiles::add)
            downloadToFile(audioOption.url, rawTmp) { downloaded, total ->
                updateProgress(taskId, downloaded, total)
            }

            if (transcodeBitrateKbps != null) {
                val ffmpeg = WindowsAppPaths.findFfmpegExecutable(
                    settings.getString(DesktopPreferences.KEY_FFMPEG_PATH, "")
                ) ?: throw IOException(
                    "FFmpeg required to transcode audio to ${transcodeBitrateKbps} kbps."
                )
                updateTask(taskId) {
                    it.copy(
                        status = DownloadStatus.TRANSCODING,
                        progress = 0.95f,
                        statusMessage = "Converting to M4A (${transcodeBitrateKbps} kbps)..."
                    )
                }
                runFfmpeg(
                    ffmpeg,
                    listOf(
                        "-y",
                        "-i", rawTmp.absolutePath,
                        "-vn",
                        "-codec:a", "aac",
                        "-b:a", "${transcodeBitrateKbps}k",
                        "-movflags", "+faststart",
                        "-metadata", "title=${details.title}",
                        "-metadata", "artist=${details.uploaderName}",
                        outputFile.absolutePath
                    )
                )
            } else {
                val muxer = DesktopStreamMuxerRegistry.muxer
                if (format == DownloadFormat.OGG_OPUS && muxer != null) {
                    updateTask(taskId) { it.copy(status = DownloadStatus.MUXING, statusMessage = "Packaging Ogg/Opus...") }
                    val muxed = runCatching {
                        muxer.demuxOggFromWebm(
                            sourcePath = rawTmp.absolutePath,
                            outputPath = outputFile.absolutePath,
                            url = details.url,
                            category = details.category,
                            uploaderName = details.uploaderName,
                            title = details.title,
                            isoDate = details.isoUploadDate
                        )
                        true
                    }.getOrElse { false }
                    if (!muxed) {
                        val webmOut = uniqueOutputFile(outputFile.parentFile, outputFile.nameWithoutExtension, "webm")
                        rawTmp.copyTo(webmOut, overwrite = true)
                        updateTask(taskId) { it.copy(outputPath = webmOut.absolutePath) }
                    }
                } else {
                    val remuxed = if (muxer != null && format == DownloadFormat.M4A) {
                        runCatching {
                            muxer.remuxM4aFromDash(rawTmp.absolutePath, outputFile.absolutePath)
                            true
                        }.getOrElse { false }
                    } else {
                        false
                    }
                    if (!remuxed) {
                        rawTmp.copyTo(outputFile, overwrite = true)
                    }
                }
            }

            val finalSize = outputFile.takeIf { it.exists() }?.length() ?: 0L
            updateTask(taskId) {
                it.copy(
                    progress = 1f,
                    downloadedBytes = finalSize,
                    totalBytes = finalSize,
                    status = DownloadStatus.COMPLETED,
                    statusMessage = "Completed"
                )
            }
        } catch (ce: CancellationException) {
            outputFile.delete()
            updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED, statusMessage = "Cancelled") }
            throw ce
        } catch (t: Throwable) {
            Logger.e(t) { "Audio download failed for ${details.title}" }
            System.err.println("[NewPipe] Audio download failed for ${details.title}: ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
            outputFile.delete()
            updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    statusMessage = t.message ?: "Download failed"
                )
            }
        } finally {
            tempFiles.forEach { runCatching { it.delete() } }
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
        val chunkSize = 2L * 1024L * 1024L
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
                            if (now - lastEmit >= 120L) {
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

    private fun runFfmpeg(ffmpegExe: File, args: List<String>) {
        val command = listOf(ffmpegExe.absolutePath) + args
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val logTail = process.inputStream.bufferedReader().use { reader ->
            reader.lineSequence().toList().takeLast(12).joinToString("\n")
        }
        val code = process.waitFor()
        if (code != 0) {
            throw IOException("FFmpeg failed (exit $code): $logTail")
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

    private fun updateProgress(taskId: String, downloaded: Long, total: Long) {
        val progress = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 0.99f) else 0f
        updateTask(taskId) {
            it.copy(
                downloadedBytes = downloaded,
                totalBytes = total.coerceAtLeast(downloaded),
                progress = progress
            )
        }
    }
}
