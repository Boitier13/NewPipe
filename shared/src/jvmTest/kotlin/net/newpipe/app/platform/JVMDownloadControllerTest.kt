/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JVMDownloadControllerTest {

    @Test
    fun resolveTargetDirectoryForOpenReturnsParentFolderOfFile() {
        val tempRoot = createTempDirectory("np_dl_test").toFile()
        try {
            val videoDir = File(tempRoot, "NewPipe/Video").apply { mkdirs() }
            val downloadedFile = File(videoDir, "My Test Video (1080p).mp4").apply {
                writeText("test")
            }

            assertEquals(
                videoDir.canonicalPath,
                JVMDownloadController.resolveTargetDirectoryForOpen(
                    downloadedFile.absolutePath,
                    tempRoot
                ).canonicalPath
            )
            assertEquals(
                videoDir.canonicalPath,
                JVMDownloadController.resolveTargetDirectoryForOpen(
                    videoDir.absolutePath,
                    tempRoot
                ).canonicalPath
            )
            assertEquals(
                tempRoot.canonicalPath,
                JVMDownloadController.resolveTargetDirectoryForOpen("", tempRoot).canonicalPath
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun computeFfmpegProgressFractionClampsCorrectly() {
        assertEquals(0f, JVMDownloadController.computeFfmpegProgressFraction(0L, 120L))
        assertEquals(0f, JVMDownloadController.computeFfmpegProgressFraction(10_000_000L, 0L))
        assertEquals(0.5f, JVMDownloadController.computeFfmpegProgressFraction(60_000_000L, 120L), 0.001f)
        assertEquals(1f, JVMDownloadController.computeFfmpegProgressFraction(200_000_000L, 120L), 0.001f)
    }

    @Test
    fun parseFfmpegOutTimeUsHandlesTimestampsAndNA() {
        assertNull(JVMDownloadController.parseFfmpegOutTimeUs("N/A"))
        assertNull(JVMDownloadController.parseFfmpegOutTimeUs("invalid"))
        assertEquals(
            3_723_500_000L,
            JVMDownloadController.parseFfmpegOutTimeUs("01:02:03.500000")
        )
        assertEquals(
            90_000_000L,
            JVMDownloadController.parseFfmpegOutTimeUs("00:01:30.000000")
        )
    }

    @Test
    fun appendLogLineCreatesLogFileInTargetDirectory() {
        val tempRoot = createTempDirectory("np_log_test").toFile()
        try {
            val logFile = File(tempRoot, "NewPipeDesktop/download_mux.log")
            JVMDownloadController.appendLogLine(logFile, "FFmpeg lookup -> C:\\ffmpeg\\bin\\ffmpeg.exe")
            JVMDownloadController.appendLogLine(logFile, "FFmpeg FAILED (exit=1)")

            assertTrue(logFile.exists())
            val content = logFile.readText(Charsets.UTF_8)
            assertTrue(content.contains("FFmpeg lookup ->"))
            assertTrue(content.contains("FFmpeg FAILED (exit=1)"))
            assertEquals("download_mux.log", WindowsAppPaths.downloadLogFile().name)
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
