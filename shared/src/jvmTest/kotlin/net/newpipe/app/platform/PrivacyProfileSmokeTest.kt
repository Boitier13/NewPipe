/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.Flushable
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.prefs.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.newpipe.app.di.settings.provideSettings
import net.newpipe.app.model.ChannelSummary
import net.newpipe.app.model.DownloadStatus
import net.newpipe.app.model.VideoDetails
import net.newpipe.app.model.VideoStreamOption
import net.newpipe.app.preferences.DownloadPreferences
import net.newpipe.app.preferences.HistoryPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Executed only in dedicated fresh Windows JVMs with disposable APPDATA, never in a real profile. */
class PrivacyProfileSmokeTest {
    @Test fun emptyFirstStartThenOwnDataSurvivesRestartWithoutReadingLegacyData() = runBlocking {
        val sandbox = System.getenv("BOITIERTUBE_PRIVACY_TEST_ROOT")?.let(::File) ?: return@runBlocking
        check(WindowsAppPaths.isWindows)
        val appData = File(System.getenv("APPDATA")).canonicalFile
        check(appData.toPath().startsWith(sandbox.canonicalFile.toPath()))
        val phase = System.getenv("BOITIERTUBE_PRIVACY_PHASE")
        val portable = System.getenv("BOITIERTUBE_PORTABLE_ROOT") != null
        val mode = if (portable) "portable" else "installed"
        val legacy = File(appData, "NewPipeDesktop/newpipe.db")
        if (!legacy.exists()) seedLegacy(legacy)
        val before = hash(legacy)
        // Explicit fixture setup only. Production code never opens these legacy preferences.
        val root = Preferences.userRoot()
        val keys = listOf(HistoryPreferences.KEY_ENABLE_WATCH_HISTORY, HistoryPreferences.KEY_RECAPTCHA_COOKIES,
            DownloadPreferences.KEY_DOWNLOAD_FOLDER_VIDEO)
        val previous = keys.associateWith { root.get(it, null) }
        root.putBoolean(keys[0], false)
        root.put(keys[1], "PRIVATE_NEWPIPE_COOKIE_CANARY")
        root.put(keys[2], File(sandbox, "private-newpipe-downloads").path)
        root.flush()
        val flat = File(sandbox, "portable/data")
        flat.mkdirs()
        val flatDb = File(flat, "newpipe.db")
        if (!flatDb.exists()) flatDb.writeBytes(legacy.readBytes())
        val flatSettings = File(flat, "preferences.checked-json")
        if (!flatSettings.exists()) flatSettings.writeText("PRIVATE_LEGACY_PORTABLE_SETTINGS")
        val flatBefore = hash(flatDb)
        try {
            val profile = WindowsAppPaths.appDataDir().canonicalFile
            val expected = if (portable) PortableProfile.dataDirectory()!!.canonicalFile else File(appData, "BoitierTube").canonicalFile
            assertEquals(expected, profile)
            assertFalse(profile.toPath().startsWith(File(appData, "NewPipeDesktop").toPath()))
            val settings = provideSettings()
            val database = JVMDesktopDatabase(settings)
            if (phase == "fresh") {
                assertTrue(settings.keys.isEmpty(), "Fresh settings must not inherit any key")
                assertTrue(settings.getBoolean(keys[0], HistoryPreferences.DEFAULT_ENABLE_WATCH_HISTORY))
                assertEquals("", settings.getString(keys[1], HistoryPreferences.DEFAULT_RECAPTCHA_COOKIES))
                assertEquals("", settings.getString(keys[2], ""))
                assertTrue(database.getAllSubscriptions().isEmpty()) // Opens DB and waits for initialization lock.
                assertTrue(database.watchHistory.first().isEmpty())
                assertTrue(database.searchHistory.first().isEmpty())
                assertTrue(database.getSearchSuggestions("").isEmpty())
                val controller = JVMDownloadController(settings, DesktopDownloader(), DataSaverController(settings))
                assertTrue(controller.tasks.value.isEmpty())
                database.recordView(video(mode))
                database.recordSearch("own-$mode-search")
                database.subscribe(ChannelSummary(0, "https://www.youtube.com/channel/own-$mode", "own-$mode", "", null, 0, 0))
                settings.putString("privacy-own-value", mode)
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                val payload = "own-$mode-download".toByteArray()
                server.createContext("/") { exchange ->
                    try {
                        exchange.sendResponseHeaders(200, payload.size.toLong())
                        exchange.responseBody.use { it.write(payload) }
                    } finally { exchange.close() }
                }
                server.start()
                try {
                    // Real destination resolver (Documents); test bytes, not a YouTube/codec validation.
                    controller.enqueueMp4(video(mode), VideoStreamOption("test", "144p", "MP4", "MPEG_4", false,
                        "http://127.0.0.1:${server.address.port}/media", null, null, 25))
                    val completed = withTimeout(15000) {
                        controller.tasks.first { it.firstOrNull()?.status == DownloadStatus.COMPLETED }.first()
                    }
                    assertTrue(File(completed.outputPath).readBytes().contentEquals(payload))
                    assertEquals(WindowsAppPaths.defaultVideoDownloadDir().canonicalFile, File(completed.outputPath).parentFile.canonicalFile)
                } finally { server.stop(0); controller.tasks.value.forEach { controller.cancelTask(it.id) } }
            } else {
                check(phase == "reopen")
                assertEquals(mode, settings.getString("privacy-own-value", ""))
            }
            assertEquals(listOf("own-$mode"), database.getAllSubscriptions().map { it.name })
            assertEquals(listOf("own-$mode"), withTimeout(10000) { database.watchHistory.first { it.isNotEmpty() } }.map { it.title })
            assertEquals(listOf("own-$mode-search"), withTimeout(10000) { database.searchHistory.first { it.isNotEmpty() } })
            assertTrue(database.getSearchSuggestions("PRIVATE").isEmpty())
            (settings as Flushable).flush()
            assertEquals(before, hash(legacy))
            assertEquals(flatBefore, hash(flatDb))
            assertEquals("PRIVATE_LEGACY_PORTABLE_SETTINGS", flatSettings.readText())
            assertEquals("PRIVATE_NEWPIPE_COOKIE_CANARY", root.get(keys[1], ""))
        } finally {
            previous.forEach { (key, value) -> if (value == null) root.remove(key) else root.put(key, value) }
            root.flush()
        }
    }

    private fun video(mode: String) = VideoDetails(0, "https://www.youtube.com/watch?v=own-$mode", "own-$mode", "",
        "Test", null, null, 0, 0, null, null, "", 1, null, false, emptyList(), emptyList(), emptyList())

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()

    private fun seedLegacy(file: File) {
        file.parentFile.mkdirs()
        BundledSQLiteDriver().open(file.path).use { conn ->
            conn.execSQL("CREATE TABLE subscriptions(uid INTEGER PRIMARY KEY,service_id INTEGER,url TEXT,name TEXT,avatar_url TEXT,subscriber_count INTEGER,description TEXT,notification_mode INTEGER)")
            conn.execSQL("INSERT INTO subscriptions VALUES(1,0,'https://www.youtube.com/channel/PRIVATE','PRIVATE_NEWPIPE_SUB',NULL,0,'',0)")
            conn.execSQL("CREATE TABLE search_history(id INTEGER PRIMARY KEY,creation_date INTEGER,service_id INTEGER,search TEXT)")
            conn.execSQL("INSERT INTO search_history VALUES(1,1,0,'PRIVATE_NEWPIPE_SEARCH')")
            conn.execSQL("CREATE TABLE streams(uid INTEGER PRIMARY KEY,service_id INTEGER,url TEXT,title TEXT,stream_type TEXT,duration INTEGER,uploader TEXT,uploader_url TEXT,thumbnail_url TEXT,view_count INTEGER,textual_upload_date TEXT,upload_date INTEGER,is_upload_date_approximation INTEGER)")
            conn.execSQL("INSERT INTO streams VALUES(1,0,'https://www.youtube.com/watch?v=PRIVATE','PRIVATE_NEWPIPE_VIDEO','VIDEO_STREAM',1,'PRIVATE',NULL,NULL,0,NULL,NULL,0)")
            conn.execSQL("CREATE TABLE stream_history(stream_id INTEGER,access_date INTEGER,repeat_count INTEGER,PRIMARY KEY(stream_id,access_date))")
            conn.execSQL("INSERT INTO stream_history VALUES(1,1,1)")
        }
    }
}
