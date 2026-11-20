/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBackupStoreTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("boitiertube-backup-test-").toFile()
        val db = File(root, "newpipe.db")
        val prefs = MapSettings().apply {
            putString("identity", "avant")
            putBoolean("enabled", true)
            putInt("count", 7)
            putLong("large", 4_000_000_000L)
            putFloat("scale", 1.25f)
            putDouble("precise", 2.5)
        }
        var connection: SQLiteConnection? = null
        var closeCount = 0
        var point: (String) -> Unit = {}

        init {
            createDatabase(db, "avant")
            reopen()
            // Leave a committed row in the live WAL: snapshots must include it.
            connection!!.execSQL("INSERT INTO marker VALUES ('wal')")
        }

        fun reopen() {
            point("reopen")
            if (connection == null) {
                connection = BundledSQLiteDriver().open(db.absolutePath).also {
                    it.execSQL("PRAGMA journal_mode = WAL")
                }
            }
            rows() // force a real query, also on reopen during recovery
        }

        fun rows(): List<String> = connection!!.prepare("SELECT value FROM marker ORDER BY rowid").use { stmt ->
            buildList { while (stmt.step()) add(stmt.getText(0)) }
        }

        fun store(
            preferences: Settings = prefs,
            limits: DesktopBackupStore.Limits = DesktopBackupStore.Limits()
        ) = DesktopBackupStore(
            databaseFile = db,
            settings = preferences,
            snapshotDatabase = { target ->
                point("snapshot")
                connection!!.prepare("VACUUM INTO ?").use { stmt ->
                    stmt.bindText(1, target.absolutePath)
                    stmt.step()
                }
            },
            prepareDatabase = { candidate ->
                BundledSQLiteDriver().open(candidate.absolutePath).use { conn ->
                    conn.prepare("SELECT value FROM marker").use { stmt -> while (stmt.step()) stmt.getText(0) }
                }
            },
            closeDatabase = {
                point("close")
                connection?.close()
                connection = null
                closeCount++
            },
            reopenDatabase = { reopen() },
            checkpoint = { point(it) },
            limits = limits
        )

        fun archive(settings: String = "{\"identity\":\"après\",\"enabled\":false,\"count\":8}"): File {
            val incoming = File(root, "incoming-${System.nanoTime()}.db")
            createDatabase(incoming, "après")
            return zip(mapOf("newpipe.db" to incoming.readBytes(), "newpipe.json" to settings.toByteArray()))
        }

        fun zip(entries: Map<String, ByteArray>): File {
            val file = File(root, "input-${System.nanoTime()}.zip")
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            return file
        }

        fun assertOriginal() {
            assertEquals(listOf("avant", "wal"), rows())
            assertEquals("avant", prefs.getString("identity", ""))
            assertEquals(true, prefs.getBoolean("enabled", false))
            assertEquals(7, prefs.getInt("count", 0))
            assertEquals(4_000_000_000L, prefs.getLong("large", 0))
            assertEquals(1.25f, prefs.getFloat("scale", 0f))
            assertEquals(2.5, prefs.getDouble("precise", 0.0))
            assertEquals(setOf("identity", "enabled", "count", "large", "scale", "precise"), prefs.keys)
            connection!!.execSQL("CREATE TABLE IF NOT EXISTS accessibility_check (value TEXT)")
        }

        override fun close() {
            connection?.close()
            root.deleteRecursively()
        }

        companion object {
            fun createDatabase(file: File, value: String) {
                BundledSQLiteDriver().open(file.absolutePath).use { conn ->
                    conn.execSQL("CREATE TABLE marker (value TEXT NOT NULL)")
                    conn.prepare("INSERT INTO marker VALUES (?)").use { stmt -> stmt.bindText(1, value); stmt.step() }
                }
            }
        }
    }

    @Test
    fun restoresDatabaseAndAllSettingsTogether() = Fixture().use { f ->
        f.store().importArchive(f.archive())
        assertEquals(listOf("après"), f.rows())
        assertEquals("après", f.prefs.getString("identity", ""))
        assertEquals(false, f.prefs.getBoolean("enabled", true))
        assertEquals(8, f.prefs.getInt("count", 0))
        assertFalse(f.prefs.hasKey("scale"))
        assertEquals(1, f.closeCount)
        assertFalse(f.root.listFiles()!!.any { it.name.startsWith(".boitiertube-backup-") })
    }

    @Test
    fun rejectsInvalidArchivesWithoutEvenClosingLiveDatabase() = Fixture().use { f ->
        val valid = f.archive()
        val dbBytes = ZipFile(valid).use { zip -> zip.getInputStream(zip.getEntry("newpipe.db")).use { it.readBytes() } }
        val cases = listOf(
            File(f.root, "missing.zip"),
            File(f.root, "corrupt.zip").apply { writeText("not a zip") },
            f.zip(emptyMap()),
            f.zip(mapOf("newpipe.json" to "{}".toByteArray())),
            f.zip(mapOf("newpipe.db" to dbBytes)),
            f.zip(mapOf("newpipe.db" to ByteArray(0), "newpipe.json" to "{}".toByteArray())),
            f.zip(mapOf("newpipe.db" to ByteArray(256) { 1 }, "newpipe.json" to "{}".toByteArray())),
            f.zip(mapOf("../newpipe.db" to dbBytes, "newpipe.json" to "{}".toByteArray())),
            f.zip(mapOf("/newpipe.db" to dbBytes, "newpipe.json" to "{}".toByteArray())),
            f.zip(mapOf("newpipe.db" to dbBytes, "newpipe.json" to byteArrayOf(0xC3.toByte(), 0x28)))
        ) + listOf("{invalid", "[]", "{\"x\":null}", "{\"x\":{}}", "{\"x\":[]}", "{\"x\":1,\"x\":2}", "").map { f.archive(it) }
        for (file in cases) {
            assertFailsWith<DesktopBackupException>(file.name) { f.store().importArchive(file) }
            f.assertOriginal()
            assertEquals(0, f.closeCount, file.name)
        }
        assertFalse(f.root.listFiles()!!.any { it.name.startsWith(".boitiertube-backup-") })
    }

    @Test
    fun rejectsTruncatedZipAndInvalidCrc() = Fixture().use { f ->
        val file = f.archive()
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))
        assertFailsWith<DesktopBackupException> { f.store().importArchive(file) }
        f.assertOriginal()
        // Change a central-directory CRC without changing the decompressed payload.
        val crcZip = f.archive()
        val raw = crcZip.readBytes()
        val central = (0..raw.size - 4).first { i ->
            raw[i] == 0x50.toByte() && raw[i + 1] == 0x4b.toByte() && raw[i + 2] == 1.toByte() && raw[i + 3] == 2.toByte()
        }
        raw[central + 16] = (raw[central + 16].toInt() xor 1).toByte()
        crcZip.writeBytes(raw)
        assertFailsWith<DesktopBackupException> { f.store().importArchive(crcZip) }
        f.assertOriginal()
        assertEquals(0, f.closeCount)
    }

    @Test
    fun rejectsOversizedEntriesAndInsufficientSpaceBeforeClose() = Fixture().use { f ->
        val file = f.archive()
        assertFailsWith<DesktopBackupException> {
            f.store(limits = DesktopBackupStore.Limits(archiveBytes = file.length() - 1)).importArchive(file)
        }
        assertFailsWith<DesktopBackupException> {
            f.store(limits = DesktopBackupStore.Limits(databaseBytes = 100)).importArchive(file)
        }
        assertFailsWith<DesktopBackupException> {
            f.store(limits = DesktopBackupStore.Limits(settingsBytes = 2)).importArchive(file)
        }
        assertFailsWith<DesktopBackupException> {
            f.store(limits = DesktopBackupStore.Limits(reserveBytes = f.root.usableSpace + 1_000_000_000)).importArchive(file)
        }
        f.assertOriginal()
        assertEquals(0, f.closeCount)
    }

    @Test
    fun rollsBackEverySubstitutionAndSettingsFailure() {
        for (failurePoint in listOf("before-close", "before-replace", "after-replace", "apply-setting", "after-settings", "after-reopen")) {
            Fixture().use { f ->
                f.point = { if (it == failurePoint) throw java.io.IOException("simulated $it") }
                assertFailsWith<DesktopBackupException>(failurePoint) { f.store().importArchive(f.archive()) }
                f.assertOriginal()
            }
        }
    }

    @Test
    fun validationAndStagingIoFailuresNeverCloseLiveDatabase() {
        for (failurePoint in listOf("extract-write", "snapshot")) {
            Fixture().use { f ->
                f.point = { if (it == failurePoint) throw java.io.IOException("write failed") }
                assertFailsWith<DesktopBackupException> { f.store().importArchive(f.archive()) }
                f.assertOriginal()
                assertEquals(0, f.closeCount)
            }
        }
    }

    @Test
    fun closeAndReopenFailuresAreRecovered() {
        for (failurePoint in listOf("close", "reopen")) {
            Fixture().use { f ->
                var failed = false
                f.point = {
                    if (!failed && it == failurePoint) {
                        failed = true
                        throw java.io.IOException("transient $it failure")
                    }
                }
                assertFailsWith<DesktopBackupException> { f.store().importArchive(f.archive()) }
                assertTrue(failed)
                f.assertOriginal()
            }
        }
    }

    @Test
    fun partialSettingsApplicationIsRolledBackWithOriginalTypes() = Fixture().use { f ->
        val throwing = object : Settings by f.prefs {
            override fun putInt(key: String, value: Int) {
                if (key == "count" && value == 8) throw java.io.IOException("third preference failed")
                f.prefs.putInt(key, value)
            }
        }
        assertFailsWith<DesktopBackupException> { f.store(preferences = throwing).importArchive(f.archive()) }
        f.assertOriginal()
    }

    @Test
    fun duplicateAndAdditionalEntriesAreRejected() = Fixture().use { f ->
        val duplicate = f.zip(mapOf("newpipe.db" to byteArrayOf(1), "otherxx.db" to byteArrayOf(2)))
        // Names have equal byte lengths; replace the local and central names to make a duplicate ZIP.
        duplicate.writeBytes(duplicate.readBytes().toString(Charsets.ISO_8859_1)
            .replace("otherxx.db", "newpipe.db").toByteArray(Charsets.ISO_8859_1))
        assertFailsWith<DesktopBackupException> { f.store().importArchive(duplicate) }
        val extra = f.zip(mapOf("newpipe.db" to byteArrayOf(1), "newpipe.json" to "{}".toByteArray(), "unexpected" to byteArrayOf(3)))
        assertFailsWith<DesktopBackupException> { f.store().importArchive(extra) }
        f.assertOriginal()
        assertEquals(0, f.closeCount)
    }

    @Test
    fun rejectsUnrelatedButStructurallyValidSqliteDatabase() = Fixture().use { f ->
        val unrelated = File(f.root, "unrelated.db")
        BundledSQLiteDriver().open(unrelated.absolutePath).use { it.execSQL("CREATE TABLE unrelated (id INTEGER)") }
        val file = f.zip(mapOf("newpipe.db" to unrelated.readBytes(), "newpipe.json" to "{}".toByteArray()))
        assertFailsWith<DesktopBackupException> { f.store().importArchive(file) }
        f.assertOriginal()
        assertEquals(0, f.closeCount)
    }

    @Test
    fun diskFullDuringRollbackCopyFallsBackToRenamingOriginals() = Fixture().use { f ->
        f.point = { if (it == "after-replace" || it == "rollback-copy") throw java.io.IOException("disk full") }
        assertFailsWith<DesktopBackupException> { f.store().importArchive(f.archive()) }
        f.assertOriginal()
    }

    @Test
    fun frenchErrorsDoNotLeakRawBackendMessages() {
        val raw = java.io.IOException("SQLITE_ERROR secret technical details")
        assertTrue(backupErrorMessage(raw, importing = true).startsWith("Impossible de restaurer"))
        assertFalse(backupErrorMessage(raw, importing = false).contains("SQLITE_ERROR"))
        assertEquals("Erreur lisible", backupErrorMessage(DesktopBackupException("Erreur lisible"), true))
    }

    @Test
    fun rollsBackRealSettingsBackendFailure() = Fixture().use { f ->
        var failed = false
        val throwing = object : Settings by f.prefs {
            override fun putString(key: String, value: String) {
                if (!failed && value == "après") {
                    failed = true
                    throw java.io.IOException("preferences write failed")
                }
                f.prefs.putString(key, value)
            }
        }
        assertFailsWith<DesktopBackupException> { f.store(preferences = throwing).importArchive(f.archive()) }
        assertTrue(failed)
        f.assertOriginal()
    }

    @Test
    fun preferencesFlushFailureTriggersRollback() = Fixture().use { f ->
        var failed = false
        val durable = object : Settings by f.prefs, java.io.Flushable {
            override fun flush() {
                if (!failed) {
                    failed = true
                    throw java.io.IOException("flush failed")
                }
            }
        }
        assertFailsWith<DesktopBackupException> { f.store(preferences = durable).importArchive(f.archive()) }
        assertTrue(failed)
        f.assertOriginal()
    }

    @Test
    fun retainsRescueCopiesIfPreferencesCannotBeRolledBack() = Fixture().use { f ->
        var writes = 0
        val throwing = object : Settings by f.prefs {
            override fun clear() {
                writes++
                if (writes >= 2) throw java.io.IOException("persistent preferences failure")
                f.prefs.clear()
            }
            override fun putString(key: String, value: String) { throw java.io.IOException("preferences failure") }
        }
        val error = assertFailsWith<DesktopBackupException> { f.store(preferences = throwing).importArchive(f.archive()) }
        assertTrue(error.message!!.contains("copies de secours"))
        assertEquals(listOf("avant", "wal"), f.rows())
        val rescue = f.root.listFiles()!!.single { it.name.startsWith(".boitiertube-backup-") }
        assertTrue(File(rescue, "previous.db").isFile)
        assertTrue(File(rescue, "previous-settings.json").readText().contains("avant"))
    }

    @Test
    fun exportsCommittedWalDataAndLegacyFilenamesWithoutClosingDatabase() = Fixture().use { f ->
        val target = File(f.root, "output.zip")
        f.store().exportArchive(target)
        ZipFile(target).use { zip ->
            assertEquals(setOf("newpipe.db", "newpipe.json"), zip.entries().asSequence().map { it.name }.toSet())
            val exported = File(f.root, "exported.db")
            zip.getInputStream(zip.getEntry("newpipe.db")).use { input -> exported.outputStream().use { input.copyTo(it) } }
            BundledSQLiteDriver().open(exported.absolutePath).use { conn ->
                conn.prepare("SELECT count(*) FROM marker").use { stmt -> assertTrue(stmt.step()); assertEquals(2L, stmt.getLong(0)) }
            }
            val prefs = zip.getInputStream(zip.getEntry("newpipe.json")).use { it.readBytes().decodeToString() }
            assertTrue(prefs.contains("avant"))
            assertFalse(prefs.contains("\"enabled\":\"\""))
        }
        f.assertOriginal()
        assertEquals(0, f.closeCount)
    }

    @Test
    fun javaPreferencesRoundTripPreservesTypedGetters() = Fixture().use { f ->
        val node = java.util.prefs.Preferences.userRoot().node("boitiertube-backup-test-" + java.util.UUID.randomUUID())
        try {
            val preferences = net.newpipe.app.di.settings.DurablePreferencesSettings(node)
            preferences.putBoolean("enabled", true)
            preferences.putInt("count", 42)
            preferences.putFloat("scale", 1.25f)
            preferences.putString("identity", "avant")
            val archive = File(f.root, "preferences.zip")
            f.store(preferences).exportArchive(archive)
            preferences.putBoolean("enabled", false)
            preferences.putString("identity", "changed")
            f.store(preferences).importArchive(archive)
            assertEquals(true, preferences.getBoolean("enabled", false))
            assertEquals(42, preferences.getInt("count", 0))
            assertEquals(1.25f, preferences.getFloat("scale", 0f))
            assertEquals("avant", preferences.getString("identity", ""))
            f.assertOriginal()
        } finally {
            node.removeNode()
            java.util.prefs.Preferences.userRoot().flush()
        }
    }

    @Test
    fun failedExportsLeaveDatabaseOpenAndNoCompletedArchive() {
        for (failurePoint in listOf("export-database-written", "export-before-publish")) {
            Fixture().use { f ->
                val target = File(f.root, "output.zip")
                f.point = { if (it == failurePoint) throw java.io.IOException("disk full") }
                assertFailsWith<DesktopBackupException> { f.store().exportArchive(target) }
                assertFalse(target.exists())
                assertFalse(f.root.listFiles()!!.any { it.name.endsWith(".partial") })
                f.assertOriginal()
                assertEquals(0, f.closeCount)
            }
        }
    }

    @Test
    fun exportRefusesExistingConcurrentAndUnavailableDestinations() = Fixture().use { f ->
        val target = File(f.root, "existing.zip").apply { writeText("must survive") }
        val before = target.readBytes()
        assertFailsWith<DesktopBackupException> { f.store().exportArchive(target) }
        assertContentEquals(before, target.readBytes())
        assertFailsWith<DesktopBackupException> { f.store().exportArchive(File(f.root, "absent/output.zip")) }
        val concurrent = File(f.root, "race.zip")
        f.point = { if (it == "export-before-publish") concurrent.writeText("other writer") }
        assertFailsWith<DesktopBackupException> { f.store().exportArchive(concurrent) }
        assertEquals("other writer", concurrent.readText())
        f.assertOriginal()
        assertEquals(0, f.closeCount)
    }
}
