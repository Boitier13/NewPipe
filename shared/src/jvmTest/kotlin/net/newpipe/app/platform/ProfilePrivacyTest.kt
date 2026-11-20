/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProfilePrivacyTest {
    @Test fun portableAccountsAndLegacyFlatProfileStaySeparate() {
        val root = Files.createTempDirectory("privacy-portable-").toFile()
        val oldDb = File(root, "data/newpipe.db").apply { parentFile.mkdirs(); writeText("legacy-private-db") }
        val oldSettings = File(root, "data/preferences.checked-json").apply { writeText("legacy-private-settings") }
        val alice = PortableProfile.dataDirectory(root.path, File(root, "users/alice").path, "alice")!!
        val bob = PortableProfile.dataDirectory(root.path, File(root, "users/bob").path, "bob")!!
        assertNotEquals(alice, bob)
        assertNotEquals(
            PortableProfile.dataDirectory(root.path, File(root, "same-home").path, "same-name", "PC-A"),
            PortableProfile.dataDirectory(root.path, File(root, "same-home").path, "same-name", "PC-B")
        )
        assertTrue(alice.toPath().startsWith(File(root, "data/profiles").canonicalFile.toPath()))
        assertFalse(File(alice, "newpipe.db").exists())
        PortableFilePreferences(File(alice, "preferences.checked-json")).use { one ->
            one.put("private", "alice-only")
            PortableFilePreferences(File(bob, "preferences.checked-json")).use { two ->
                assertEquals(null, two.get("private", null))
            }
        }
        assertEquals("legacy-private-db", oldDb.readText())
        assertEquals("legacy-private-settings", oldSettings.readText())
        assertEquals(alice, PortableProfile.dataDirectory(root.path, File(root, "users/alice").path, "alice"))
    }
}
