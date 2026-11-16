/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsAppPathsTest {

    @Test
    fun bundledResourcesDirResolvesExplicitPropertyAndJavaHomeFallback() {
        val tempRoot = createTempDirectory("np_res_test").toFile()
        try {
            val explicitDir = File(tempRoot, "explicit-resources").apply { mkdirs() }
            assertEquals(
                explicitDir.canonicalPath,
                WindowsAppPaths.bundledResourcesDir(
                    resourcesDirProperty = explicitDir.absolutePath,
                    javaHomeProperty = null
                )?.canonicalPath
            )

            val appRoot = File(tempRoot, "NewPipeApp")
            val runtimeDir = File(appRoot, "runtime").apply { mkdirs() }
            val appResourcesDir = File(appRoot, "app/resources").apply { mkdirs() }

            assertEquals(
                appResourcesDir.canonicalPath,
                WindowsAppPaths.bundledResourcesDir(
                    resourcesDirProperty = File(tempRoot, "missing").absolutePath,
                    javaHomeProperty = runtimeDir.absolutePath
                )?.canonicalPath
            )
            assertNull(
                WindowsAppPaths.bundledResourcesDir(
                    resourcesDirProperty = "   ",
                    javaHomeProperty = File(tempRoot, "empty/runtime").absolutePath
                )
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun findFfmpegExecutablePrioritizesCustomOverBundledAndPathOnWindows() {
        val tempRoot = createTempDirectory("np_ffmpeg_win_test").toFile()
        try {
            val bundledDir = File(tempRoot, "resources")
            val bundledFfmpeg = File(bundledDir, "ffmpeg/ffmpeg.exe").apply {
                parentFile.mkdirs()
                writeText("stub")
            }
            val pathDir = File(tempRoot, "path-bin").apply { mkdirs() }
            val pathFfmpeg = File(pathDir, "ffmpeg.exe").apply { writeText("stub") }
            val customDir = File(tempRoot, "custom").apply { mkdirs() }
            val customFfmpeg = File(customDir, "ffmpeg.exe").apply { writeText("stub") }

            val customSelected = WindowsAppPaths.findFfmpegExecutable(
                customPath = customFfmpeg.absolutePath,
                bundledDir = bundledDir,
                pathEnv = pathDir.absolutePath,
                windows = true
            )
            assertEquals(customFfmpeg.canonicalPath, customSelected?.canonicalPath)

            val customDirSelected = WindowsAppPaths.findFfmpegExecutable(
                customPath = customDir.absolutePath,
                bundledDir = bundledDir,
                pathEnv = pathDir.absolutePath,
                windows = true
            )
            assertEquals(customFfmpeg.canonicalPath, customDirSelected?.canonicalPath)

            val bundledSelected = WindowsAppPaths.findFfmpegExecutable(
                customPath = "",
                bundledDir = bundledDir,
                pathEnv = pathDir.absolutePath,
                windows = true
            )
            assertEquals(bundledFfmpeg.canonicalPath, bundledSelected?.canonicalPath)

            val pathSelected = WindowsAppPaths.findFfmpegExecutable(
                customPath = "",
                bundledDir = null,
                pathEnv = pathDir.absolutePath,
                windows = true
            )
            assertEquals(pathFfmpeg.canonicalPath, pathSelected?.canonicalPath)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun findFfmpegExecutableSupportsBundledRootAndSubdir() {
        val tempRoot = createTempDirectory("np_ffmpeg_root_test").toFile()
        try {
            val bundledDir = File(tempRoot, "resources").apply { mkdirs() }
            val rootFfmpeg = File(bundledDir, "ffmpeg.exe").apply { writeText("stub") }
            assertEquals(
                rootFfmpeg.canonicalPath,
                WindowsAppPaths.bundledFfmpegExecutable(bundledDir, windows = true)?.canonicalPath
            )

            val subFfmpeg = File(bundledDir, "ffmpeg/ffmpeg.exe").apply {
                parentFile.mkdirs()
                writeText("stub")
            }
            assertEquals(
                subFfmpeg.canonicalPath,
                WindowsAppPaths.bundledFfmpegExecutable(bundledDir, windows = true)?.canonicalPath
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun candidateVlcDirectoriesPrioritizesCustomThenBundledAndDeduplicates() {
        val tempRoot = createTempDirectory("np_vlc_test").toFile()
        try {
            val bundledDir = File(tempRoot, "resources")
            val bundledVlc = File(bundledDir, "vlc").apply { mkdirs() }
            // Incomplete directory without libvlccore.dll must not be treated as bundled VLC.
            File(bundledVlc, "libvlc.dll").writeText("dll")
            assertNull(WindowsAppPaths.bundledVlcDirectory(bundledDir, windows = true, mac = false))

            File(bundledVlc, "libvlccore.dll").writeText("dll")
            val resolvedBundled = WindowsAppPaths.bundledVlcDirectory(bundledDir, windows = true, mac = false)
            assertNotNull(resolvedBundled)
            assertEquals(bundledVlc.canonicalPath, resolvedBundled.canonicalPath)

            val customVlc = File(tempRoot, "custom-vlc").apply { mkdirs() }
            val candidates = WindowsAppPaths.candidateVlcDirectories(
                customPath = customVlc.absolutePath,
                bundledDir = bundledDir,
                windows = true,
                mac = false
            )
            assertTrue(candidates.size >= 2)
            assertEquals(customVlc.canonicalPath, candidates[0].canonicalPath)
            assertEquals(bundledVlc.canonicalPath, candidates[1].canonicalPath)

            val deduped = WindowsAppPaths.candidateVlcDirectories(
                customPath = bundledVlc.absolutePath,
                bundledDir = bundledDir,
                windows = true,
                mac = false
            )
            assertEquals(1, deduped.count { it.canonicalPath == bundledVlc.canonicalPath })
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
