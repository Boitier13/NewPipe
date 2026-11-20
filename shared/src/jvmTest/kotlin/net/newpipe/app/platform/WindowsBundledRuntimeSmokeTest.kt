/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import uk.co.caprica.vlcj.factory.MediaPlayerFactory

/**
 * Verifies the packaged `app/resources` directory produced by `:desktopApp:createDistributable`
 * on Windows CI runners where `NEWPIPE_VERIFY_BUNDLED_DIR` points to the real bundled binaries.
 * Skipped automatically during normal local unit test runs.
 */
class WindowsBundledRuntimeSmokeTest {

    @Test
    fun bundledFfmpegAndVlcInitializeCleanlyWhenConfigured() {
        val rawDir = System.getenv("NEWPIPE_VERIFY_BUNDLED_DIR")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return

        val resourcesDir = File(rawDir)
        assertTrue(resourcesDir.isDirectory, "Expected bundled resources dir at $rawDir")

        val resolvedResources = WindowsAppPaths.bundledResourcesDir(
            resourcesDirProperty = resourcesDir.absolutePath,
            javaHomeProperty = null
        )
        assertNotNull(resolvedResources)
        assertEquals(resourcesDir.canonicalPath, resolvedResources.canonicalPath)

        // 1. Verify bundled FFmpeg executable resolves first and executes `-version`.
        val ffmpegExe = WindowsAppPaths.findFfmpegExecutable(
            customPath = null,
            bundledDir = resourcesDir
        )
        assertNotNull(ffmpegExe, "Bundled ffmpeg.exe not found inside $rawDir")
        assertEquals(
            File(resourcesDir, "ffmpeg/ffmpeg.exe").canonicalPath,
            ffmpegExe.canonicalPath
        )

        val ffmpegProcess = ProcessBuilder(ffmpegExe.absolutePath, "-version")
            .redirectErrorStream(true)
            .start()
        val ffmpegOutput = ffmpegProcess.inputStream.bufferedReader().readText()
        val ffmpegExit = ffmpegProcess.waitFor()
        assertEquals(0, ffmpegExit, "ffmpeg -version failed: $ffmpegOutput")
        assertTrue(ffmpegOutput.contains("ffmpeg version", ignoreCase = true))

        // 2. Verify bundled VLC DLLs + plugins discover and initialize a real LibVLC instance.
        val expectedVlcDir = File(resourcesDir, "vlc")
        assertTrue(File(expectedVlcDir, "plugins").isDirectory, "Bundled VLC plugins/ missing")

        val discovery = VlcMediaPlayerController.discoverVlc(
            customPath = null,
            bundledDir = resourcesDir
        )
        assertTrue(discovery.discovered, "VLC NativeDiscovery failed for $expectedVlcDir")
        assertNotNull(discovery.discoveredPath, "VLC discoveredPath should not be null")
        assertEquals(
            expectedVlcDir.canonicalPath,
            File(discovery.discoveredPath).canonicalPath,
            "Expected bundled VLC directory to be prioritized over any system VLC"
        )

        val factory = MediaPlayerFactory("--no-video-title-show", "--quiet")
        try {
            val version = factory.application().version()
            assertTrue(version.isNotBlank(), "LibVLC version should not be blank")
            println("Verified bundled VLC version=$version at ${discovery.discoveredPath}")
            println("Verified bundled FFmpeg at ${ffmpegExe.absolutePath}: ${ffmpegOutput.lineSequence().firstOrNull().orEmpty()}")
        } finally {
            factory.release()
        }
    }
}
