/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

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
            WindowsAppPaths.tempDownloadDir(), WindowsAppPaths.defaultVideoDownloadDir(), WindowsAppPaths.defaultAudioDownloadDir())) {
            assertTrue(file.canonicalFile.toPath().startsWith(data.toPath()), file.toString())
        }
        val settings = provideSettings()
        settings.putString("portable_smoke", "isolated")
        (settings as Flushable).flush()
        assertEquals("isolated", settings.getString("portable_smoke", ""))
        assertTrue(File(data, "preferences.checked-json").isFile)
    }
}
