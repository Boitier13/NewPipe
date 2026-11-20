/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import com.russhwolf.settings.MapSettings
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.newpipe.app.model.AudioStreamOption
import net.newpipe.app.model.DownloadStatus
import net.newpipe.app.model.VideoDetails
import net.newpipe.app.model.VideoStreamOption
import net.newpipe.app.preferences.DownloadPreferences

/** Real controller + local HTTP, without YouTube, VLC or FFmpeg. Payloads are test bytes, not media. */
class FinalDownloadAuditTest {
    private fun details(title: String) = VideoDetails(
        serviceId = 0, url = "https://www.youtube.com/watch?v=test", title = title,
        description = "", uploaderName = "Test", uploaderUrl = null, uploaderAvatarUrl = null,
        subscriberCount = 0, viewCount = 0, uploadDateText = null, isoUploadDate = null,
        category = "", durationSeconds = 1, thumbnailUrl = null, isLive = false,
        videoStreams = emptyList(), audioStreams = emptyList(), relatedVideos = emptyList()
    )

    private fun option(url: String) = VideoStreamOption(
        id = "test", resolution = "144p", formatLabel = "MP4", formatKey = "MPEG_4",
        isVideoOnly = false, videoUrl = url, pairedAudioUrl = null,
        pairedAudioFormatKey = null, fps = 25
    )

    @Test fun waitingTaskMakesNoHttpRequestAndDoesNotInterruptTheActiveTransfer() = runBlocking {
        val root = Files.createTempDirectory("final-queue-").toFile()
        val old = File(root, "Premier.mp4").apply { writeText("ancien") }
        val settings = MapSettings().apply {
            putString(DownloadPreferences.KEY_DOWNLOAD_FOLDER_VIDEO, root.absolutePath)
        }
        val controller = JVMDownloadController(settings, DesktopDownloader(), DataSaverController(settings))
        val firstStarted = CountDownLatch(1)
        val finishFirst = CountDownLatch(1)
        val requests = AtomicInteger()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        val bytes = ByteArray(128 * 1024) { 7 }
        server.createContext("/") { exchange ->
            val index = requests.incrementAndGet()
            try {
                exchange.sendResponseHeaders(200, bytes.size.toLong()) // Ignore Range deliberately.
                exchange.responseBody.use { out ->
                    if (index == 1) {
                        out.write(bytes, 0, bytes.size / 2)
                        out.flush()
                        firstStarted.countDown()
                        check(finishFirst.await(15, TimeUnit.SECONDS))
                        out.write(bytes, bytes.size / 2, bytes.size / 2)
                    } else out.write(bytes)
                }
            } finally { exchange.close() }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/file"
        try {
            controller.enqueueMp4(details("Premier"), option(url))
            assertTrue(firstStarted.await(10, TimeUnit.SECONDS))
            controller.enqueueMp4(details("Deuxième"), option(url))
            controller.enqueueMp4(details("À annuler"), option(url))
            val cancelled = controller.tasks.value.first { it.title == "À annuler" }
            controller.cancelTask(cancelled.id)
            delay(600) // Leave an observable interval while first HTTP response is deliberately held.
            assertEquals(1, requests.get())
            assertEquals(DownloadStatus.QUEUED, controller.tasks.value.first { it.title == "Deuxième" }.status)
            assertEquals(DownloadStatus.DOWNLOADING, controller.tasks.value.first { it.title == "Premier" }.status)
            assertEquals(DownloadStatus.CANCELLED, controller.tasks.value.first { it.id == cancelled.id }.status)
            finishFirst.countDown()
            val completed = withTimeout(15000) {
                controller.tasks.first { tasks -> tasks.count { it.status == DownloadStatus.COMPLETED } == 2 }
            }
            assertEquals(2, requests.get())
            completed.filter { it.status == DownloadStatus.COMPLETED }.forEach {
                assertTrue(File(it.outputPath).readBytes().contentEquals(bytes))
                assertEquals(1f, it.progress)
            }
            assertEquals("ancien", old.readText())
            assertFalse(File(cancelled.outputPath).exists())
        } finally {
            finishFirst.countDown()
            controller.tasks.value.forEach { controller.cancelTask(it.id) }
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test fun unknownLengthStaysIndeterminateAndEmptyResponseIsNotReportedAsSuccess() = runBlocking {
        val root = Files.createTempDirectory("final-progress-").toFile()
        val settings = MapSettings().apply {
            putString(DownloadPreferences.KEY_DOWNLOAD_FOLDER_VIDEO, root.absolutePath)
            putString(DownloadPreferences.KEY_MAXIMUM_RETRIES, "0")
        }
        val controller = JVMDownloadController(settings, DesktopDownloader(), DataSaverController(settings))
        val finish = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/stream") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0) // Chunked: no declared length.
                exchange.responseBody.use {
                    it.write(ByteArray(65536) { 3 }); it.flush()
                    check(finish.await(15, TimeUnit.SECONDS))
                    it.write(byteArrayOf(4))
                }
            } finally { exchange.close() }
        }
        server.createContext("/empty") { exchange -> exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            controller.enqueueMp4(details("Flux"), option("$base/stream"))
            val downloading = withTimeout(10000) {
                controller.tasks.first { it.first().downloadedBytes > 0 }.first()
            }
            assertEquals(0L, downloading.totalBytes)
            assertEquals(0f, downloading.progress)
            finish.countDown()
            withTimeout(10000) { controller.tasks.first { it.first().status == DownloadStatus.COMPLETED } }
            controller.enqueueMp4(details("Vide"), option("$base/empty"))
            val failed = withTimeout(10000) {
                controller.tasks.first { it.first().status == DownloadStatus.FAILED }.first()
            }
            assertTrue(failed.statusMessage.orEmpty().startsWith("Échec du téléchargement"))
            assertFalse(File(failed.outputPath).exists())
        } finally {
            finish.countDown()
            controller.tasks.value.forEach { controller.cancelTask(it.id) }
            server.stop(0)
        }
    }
    @Test fun nativeAudioCleansItsOwnTemporaryFileAndKeepsExistingFiles() = runBlocking {
        val root = Files.createTempDirectory("final-audio-").toFile()
        val old = File(root, "Audio.m4a").apply { writeText("ancien audio") }
        val tempDir = WindowsAppPaths.tempDownloadDir()
        val before = tempDir.listFiles().orEmpty().map { it.name }.toSet()
        val settings = MapSettings().apply {
            putString(DownloadPreferences.KEY_DOWNLOAD_FOLDER_AUDIO, root.absolutePath)
        }
        val controller = JVMDownloadController(settings, DesktopDownloader(), DataSaverController(settings))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bytes = ByteArray(1024) { 5 }
        server.createContext("/") { exchange ->
            try {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally { exchange.close() }
        }
        server.start()
        try {
            controller.enqueueNativeAudio(details("Audio"), AudioStreamOption(
                id = "audio", bitrateKbps = 96, formatLabel = "M4A", formatKey = "M4A",
                url = "http://127.0.0.1:${server.address.port}/audio"
            ), null)
            val done = withTimeout(10000) {
                controller.tasks.first { it.first().status == DownloadStatus.COMPLETED }.first()
            }
            assertTrue(File(done.outputPath).readBytes().contentEquals(bytes))
            assertEquals("ancien audio", old.readText())
            withTimeout(10000) {
                while (tempDir.listFiles().orEmpty().any { it.name !in before }) delay(20)
            }
        } finally {
            controller.tasks.value.forEach { controller.cancelTask(it.id) }
            server.stop(0)
        }
    }

}
