/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PostWindowsDownloadTest {
    @Test fun documentsFoldersSupportRedirectedUnicodePathsAndPreserveOldFiles() {
        val root = Files.createTempDirectory("destinations-").toFile()
        val documents = File(root, "Documents redirigés Élodie").apply { mkdirs() }
        val old = File(root, "application/data/downloads/Video/ancien.mp4").apply { parentFile.mkdirs(); writeText("ancien") }
        val video = DownloadDestinations.folder(documents, true)
        val audio = DownloadDestinations.folder(documents, false)
        assertEquals(File(documents, "BoitierTube/Vidéos"), video)
        assertEquals(File(documents, "BoitierTube/Audios"), audio)
        File(video, "vidéo-test.mp4").writeText("destination seulement, pas un média")
        File(audio, "audio-test.mp3").writeText("destination seulement, pas un média")
        assertEquals("ancien", old.readText())
        assertEquals(video, DownloadDestinations.folder(documents, true))
        val custom = File(root, "Choix personnel")
        assertEquals(custom, DownloadDestinations.folder(documents, true, custom.absolutePath))
        assertFailsWith<IllegalArgumentException> { DownloadDestinations.folder(documents, false, "relative") }
    }

    @Test fun nativeDocumentsMatchesWindowsReference() {
        if (!WindowsAppPaths.isWindows) return
        val actual = DownloadDestinations.documentsDirectory()
        assertTrue(actual.isAbsolute)
        val expected = System.getenv("BOITIERTUBE_EXPECTED_DOCUMENTS")
        if (expected != null) assertEquals(File(expected).canonicalFile, actual.canonicalFile)
        assertEquals(File(actual, "BoitierTube/Vidéos").canonicalFile, WindowsAppPaths.defaultVideoDownloadDir().canonicalFile)
        assertEquals(File(actual, "BoitierTube/Audios").canonicalFile, WindowsAppPaths.defaultAudioDownloadDir().canonicalFile)
    }

    @Test fun pendingDownloadsCannotReserveTheSameOutputOrOverwriteAnExistingFile() {
        val root = Files.createTempDirectory("output-names-").toFile()
        val existing = File(root, "Titre.mp4").apply { writeText("ancien") }
        val names = DownloadOutputNames()
        val one = names.reserve(root, "Titre", "mp4")
        val two = names.reserve(root, "Titre", "mp4")
        assertEquals("Titre (1).mp4", one.name)
        assertEquals("Titre (2).mp4", two.name)
        assertEquals("ancien", existing.readText())
        names.release(one)
        assertEquals(one, names.reserve(root, "Titre", "mp4"))
        if (WindowsAppPaths.isWindows) assertEquals("titre (3).mp4", names.reserve(root, "titre", "mp4").name)
    }

    @Test fun singleQueueSharesCapacityBetweenTasksAndReleasesOnCancellation() = runBlocking {
        withTimeout(10000) {
            val gate = DownloadQueueGate { 1 }
            val entered = CompletableDeferred<Unit>()
            val stop = CompletableDeferred<Unit>()
            val first = launch(Dispatchers.Default) { gate.withPermit { entered.complete(Unit); stop.await() } }
            entered.await()
            val waiting = launch(Dispatchers.Default) { gate.withPermit { error("Cancelled job must not run") } }
            waiting.cancelAndJoin()
            stop.complete(Unit); first.join()
            gate.withPermit { assertTrue(true) }
        }
    }

    @Test fun parallelLimitIsSharedAndReductionDoesNotStartAnotherTaskEarly() = runBlocking {
        withTimeout(10000) {
            val limit = AtomicInteger(3)
            val gate = DownloadQueueGate { limit.get() }
            val entered = Channel<Int>(Channel.UNLIMITED)
            val release = Channel<Unit>(Channel.UNLIMITED)
            val jobs = (1..3).map { id -> async(Dispatchers.Default) { gate.withPermit { entered.send(id); release.receive() } } }
            repeat(3) { entered.receive() }
            limit.set(1)
            val fourth = async(Dispatchers.Default) { gate.withPermit { entered.send(4); release.receive() } }
            assertTrue(entered.tryReceive().isFailure)
            repeat(3) { release.send(Unit) }
            assertEquals(4, entered.receive())
            release.send(Unit)
            jobs.awaitAll(); fourth.await()
        }
    }
}
