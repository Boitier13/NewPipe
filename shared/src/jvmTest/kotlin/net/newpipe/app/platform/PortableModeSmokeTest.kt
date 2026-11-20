/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.Flushable
import net.newpipe.app.di.settings.provideSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortableModeSmokeTest {
    @Test
    fun explicitPortableProcessKeepsDefaultPathsAndPreferencesInsideItsRoot() {
        val root = System.getenv("BOITIERTUBE_PORTABLE_ROOT") ?: return
        val data = File(root, "data").canonicalFile
        assertEquals(data, WindowsAppPaths.appDataDir().canonicalFile)
        for (file in listOf(WindowsAppPaths.databaseFile(), WindowsAppPaths.downloadLogFile(),
            WindowsAppPaths.tempDownloadDir())) {
            assertTrue(file.canonicalFile.toPath().startsWith(data.toPath()), file.toString())
        }
        val documents = DownloadDestinations.documentsDirectory()
        assertEquals(File(documents, "BoitierTube/Vidéos").canonicalFile, WindowsAppPaths.defaultVideoDownloadDir().canonicalFile)
        assertEquals(File(documents, "BoitierTube/Audios").canonicalFile, WindowsAppPaths.defaultAudioDownloadDir().canonicalFile)
        val settings = provideSettings()
        settings.putString("portable_smoke", "isolated")
        (settings as Flushable).flush()
        assertEquals("isolated", settings.getString("portable_smoke", ""))
        assertTrue(File(data, "preferences.checked-json").isFile)
        runBlocking {
            val database = JVMDesktopDatabase(settings)
            val csv = File(data, "representative-subscriptions.csv")
            csv.writeText("Channel Id,Channel Url,Channel Title\r\nUC_x5XG1OV2P6uZZ5FSM9Ttw,https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw,Exemple\r\n")
            assertEquals(1, database.importSubscriptionsJson(csv.absolutePath).getOrThrow())
            assertEquals(1, database.importSubscriptionsJson(csv.absolutePath).getOrThrow())
            assertEquals(1, database.getAllSubscriptions().size)
            val json = File(data, "subscriptions.json")
            database.exportSubscriptionsJson(json.absolutePath).getOrThrow()
            assertEquals(1, database.importSubscriptionsJson(json.absolutePath).getOrThrow())
            assertEquals(1, database.getAllSubscriptions().size)
            // Inject a real SQLite write error after one new row: the entire import must roll back.
            BundledSQLiteDriver().open(WindowsAppPaths.databaseFile().absolutePath).use {
                it.execSQL("CREATE TRIGGER fail_subscription BEFORE INSERT ON subscriptions WHEN NEW.name = 'bad' BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
            }
            csv.writeText("Channel Id,Channel Url,Channel Title\nUCgood123456789012345678,https://www.youtube.com/channel/UCgood123456789012345678,good\nUCbad1234567890123456789,https://www.youtube.com/channel/UCbad1234567890123456789,bad\n")
            assertTrue(database.importSubscriptionsJson(csv.absolutePath).isFailure)
            assertEquals(1, database.getAllSubscriptions().size)
        }
    }
}
