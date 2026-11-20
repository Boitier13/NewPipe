/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.russhwolf.settings.PreferencesSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableProfileTest {
    @Test
    fun portableIsOptInAndInvalidRootsNeverFallBack() {
        assertNull(PortableProfile.dataDirectory(null))
        assertFailsWith<IllegalArgumentException> { PortableProfile.dataDirectory("") }
        assertFailsWith<IllegalArgumentException> { PortableProfile.dataDirectory("relative") }
        assertFailsWith<IllegalArgumentException> { PortableProfile.dataDirectory("/missing-boitiertube-root-9cfa") }
    }

    @Test
    fun defaultProfilePathsCannotEscapeThroughParentTraversal() {
        val data = PortableProfile.dataDirectory(Files.createTempDirectory("portable-paths-").toString())!!
        assertFailsWith<IllegalStateException> { PortableProfile.resolve(data, "../installed.db") }
        assertTrue(PortableProfile.resolve(data, "downloads/Video").canonicalFile.toPath().startsWith(data.toPath()))
    }

    @Test
    fun twoProfilesStayIsolatedAndRetainValuesOnReopen() {
        val first = PortableProfile.dataDirectory(Files.createTempDirectory("portable-one-").toString())!!
        val second = PortableProfile.dataDirectory(Files.createTempDirectory("portable-two-").toString())!!
        val file1 = File(first, "preferences.checked-json")
        val file2 = File(second, "preferences.checked-json")
        PortableFilePreferences(file1).use { one ->
            PortableFilePreferences(file2).use { two ->
                val a = PreferencesSettings(one)
                val b = PreferencesSettings(two)
                a.putString("theme", "dark_theme")
                a.putBoolean("enabled", true)
                a.putInt("count", 17)
                assertFalse(b.hasKey("theme"))
                b.putString("theme", "light_theme")
                assertEquals("dark_theme", a.getString("theme", ""))
            }
        }
        PortableFilePreferences(file1).use { reopened ->
            val settings = PreferencesSettings(reopened)
            assertEquals("dark_theme", settings.getString("theme", ""))
            assertEquals(17, settings.getInt("count", 0))
            assertTrue(settings.getBoolean("enabled", false))
        }
    }

    @Test
    fun corruptSettingsFailClosedAndRemainUntouched() {
        val root = Files.createTempDirectory("portable-corrupt-").toFile()
        val file = File(root, "preferences.checked-json").apply { writeText("corrupted") }
        assertFailsWith<IllegalStateException> { PortableFilePreferences(file) }
        assertEquals("corrupted", file.readText())
    }

    @Test
    fun checksumRejectsSameLengthTamperingWithoutOverwritingFile() {
        val file = File(Files.createTempDirectory("portable-tamper-").toFile(), "preferences.checked-json")
        PortableFilePreferences(file).use { it.put("theme", "dark_theme") }
        val bytes = file.readBytes()
        bytes[bytes.lastIndex - 2] = 'X'.code.toByte()
        file.writeBytes(bytes)
        assertFailsWith<IllegalStateException> { PortableFilePreferences(file) }
        assertTrue(file.readBytes().contentEquals(bytes))
    }

    @Test
    fun failedPublicationLeavesLastGoodFileAndInMemorySettingsIntact() {
        val file = File(Files.createTempDirectory("portable-write-failure-").toFile(), "preferences.checked-json")
        PortableFilePreferences(file).use { it.put("theme", "old") }
        val before = file.readBytes()
        PortableFilePreferences(file, beforePublish = { throw java.io.IOException("simulated disk failure") }).use {
            assertFailsWith<java.io.IOException> { it.put("theme", "new") }
            assertEquals("old", it.get("theme", ""))
            assertTrue(before.contentEquals(file.readBytes()))
        }
        PortableFilePreferences(file).use { assertEquals("old", it.get("theme", "")) }
    }

    @Test
    fun secondConcurrentInstanceIsRejected() {
        val file = File(Files.createTempDirectory("portable-lock-").toFile(), "preferences.checked-json")
        PortableFilePreferences(file).use {
            assertFailsWith<Exception> { PortableFilePreferences(file) }
        }
        PortableFilePreferences(file).use { assertEquals(0, it.keys().size) }
    }

    @Test
    fun listenersRemainObservableForThemeChanges() {
        val file = File(Files.createTempDirectory("portable-listener-").toFile(), "preferences.checked-json")
        PortableFilePreferences(file).use {
            val signal = CountDownLatch(1)
            val settings = PreferencesSettings(it)
            val listener = settings.addStringListener("theme", "auto_device_theme") { value ->
                if (value == "dark_theme") signal.countDown()
            }
            settings.putString("theme", "dark_theme")
            assertTrue(signal.await(5, TimeUnit.SECONDS))
            listener.deactivate()
        }
    }
}
