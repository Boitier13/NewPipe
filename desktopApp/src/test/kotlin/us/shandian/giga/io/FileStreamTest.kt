/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package us.shandian.giga.io

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileStreamTest {

    @Test
    fun bufferedWriteFlushesBeforeSeekReadLengthAndClose() {
        val tempDir = createTempDirectory("np_filestream_test").toFile()
        try {
            val file = File(tempDir, "mux_output.mp4")
            val headerSize = 32
            val payloadSize = 700 * 1024 // > 256 KiB buffer to force multiple flushes + tail

            FileStream(file).use { stream ->
                // Reserve header space (simulating ftyp + moov reservation)
                stream.write(ByteArray(headerSize))

                // Many small frame-sized writes (simulating Mp4FromDashWriter sample writes)
                val frame = ByteArray(1000) { idx -> (idx % 251).toByte() }
                var written = 0
                while (written < payloadSize) {
                    val toWrite = minOf(frame.size, payloadSize - written)
                    stream.write(frame, 0, toWrite)
                    written += toWrite
                }

                // Length must reflect buffered data
                assertEquals((headerSize + payloadSize).toLong(), stream.length())

                // Seek back to patch header (simulating auxBuffer moov dump)
                stream.seek(4L)
                val moovTag = "moov_patched_hdr".encodeToByteArray()
                stream.write(moovTag)

                // Rewind and read back before close
                stream.rewind()
                assertEquals((headerSize + payloadSize).toLong(), stream.available())
                assertEquals(4L, stream.skip(4L))
                val readTag = ByteArray(moovTag.size)
                assertEquals(moovTag.size, stream.read(readTag))
                assertContentEquals(moovTag, readTag)
            }

            assertTrue(file.exists())
            assertEquals((headerSize + payloadSize).toLong(), file.length())
            val allBytes = file.readBytes()
            assertEquals("moov_patched_hdr", allBytes.copyOfRange(4, 20).decodeToString())
            // Verify tail bytes survived final close() flush
            val tailStart = headerSize + payloadSize - 10
            for (i in 0 until 10) {
                val expected = (((payloadSize - 10 + i) % 1000) % 251).toByte()
                assertEquals(expected, allBytes[tailStart + i])
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
