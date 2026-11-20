/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.russhwolf.settings.MapSettings
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopRecoveryJournalTest {
    @Test
    fun pendingEmptyAndUnknownMarkersBlockInitialCreationWithoutTouchingFiles() {
        for (state in listOf("pending", "", "partial-write")) {
            val root = Files.createTempDirectory("recovery-journal-").toFile()
            val work = File(root, ".boitiertube-backup-test").apply { mkdir() }
            val marker = File(work, "recovery-state.txt").apply { writeText(state) }
            val db = File(root, "newpipe.db")
            assertFailsWith<DesktopBackupException> { DesktopBackupStore.assertNoPendingRecovery(db) }
            assertFalse(db.exists())
            assertEquals(state, marker.readText())
        }
    }

    @Test
    fun completedJournalAndPreparationOnlyDirectoryAllowStartup() {
        val root = Files.createTempDirectory("recovery-complete-").toFile()
        File(root, ".boitiertube-backup-preparation").mkdir()
        val completed = File(root, ".boitiertube-backup-completed").apply { mkdir() }
        File(completed, "recovery-state.txt").writeText("completed")
        DesktopBackupStore.assertNoPendingRecovery(File(root, "newpipe.db"))
    }

    @Test
    fun legacyRescueWithoutJournalBlocksRatherThanGuessing() {
        val root = Files.createTempDirectory("recovery-legacy-").toFile()
        val work = File(root, ".boitiertube-backup-old").apply { mkdir() }
        File(work, "previous.db").writeText("rescue")
        File(work, "previous-settings.json").writeText("{}")
        assertFailsWith<DesktopBackupException> { DesktopBackupStore.assertNoPendingRecovery(File(root, "newpipe.db")) }
    }

    @Test
    fun fatalInterruptionAtEverySubstitutionPhaseRetainsRescueAndBlocksNextStartup() {
        class SimulatedTermination : Error()
        for (point in listOf("before-replace", "after-replace", "after-settings", "after-reopen")) {
            val root = Files.createTempDirectory("recovery-interrupted-").toFile()
            val db = File(root, "newpipe.db")
            var conn: SQLiteConnection? = BundledSQLiteDriver().open(db.absolutePath)
            conn!!.execSQL("CREATE TABLE marker(value TEXT)")
            conn!!.execSQL("INSERT INTO marker VALUES ('original')")
            val candidate = File(root, "incoming.db")
            BundledSQLiteDriver().open(candidate.absolutePath).use {
                it.execSQL("CREATE TABLE marker(value TEXT)")
                it.execSQL("INSERT INTO marker VALUES ('incoming')")
            }
            val archive = File(root, "restore.zip")
            ZipOutputStream(archive.outputStream()).use {
                it.putNextEntry(ZipEntry("newpipe.db")); it.write(candidate.readBytes()); it.closeEntry()
                it.putNextEntry(ZipEntry("newpipe.json")); it.write("{}".toByteArray()); it.closeEntry()
            }
            val store = DesktopBackupStore(db, MapSettings(),
                snapshotDatabase = { target ->
                    conn!!.prepare("VACUUM INTO ?").use { it.bindText(1, target.absolutePath); it.step() }
                },
                prepareDatabase = {},
                closeDatabase = { conn?.close(); conn = null },
                reopenDatabase = { conn = BundledSQLiteDriver().open(db.absolutePath) },
                checkpoint = { if (it == point) throw SimulatedTermination() }
            )
            try {
                assertFailsWith<SimulatedTermination>(point) { store.importArchive(archive) }
                assertFailsWith<DesktopBackupException>(point) { DesktopBackupStore.assertNoPendingRecovery(db) }
                val work = root.listFiles()!!.single { it.name.startsWith(".boitiertube-backup-") }
                assertTrue(File(work, "previous-settings.json").isFile)
                BundledSQLiteDriver().open(File(work, "previous.db").absolutePath).use {
                    it.prepare("SELECT value FROM marker").use { row ->
                        assertTrue(row.step()); assertEquals("original", row.getText(0))
                    }
                }
            } finally { conn?.close() }
        }
    }
}
