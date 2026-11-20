/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Representative standard Takeout CSV, not a private user export or live Google API test. */
class PostWindowsSubscriptionsTest {
    @Test fun standardTakeoutSupportsBomCrLfQuotesAndMultilineTitles() {
        val csv = "\uFEFFChannel Id,Channel Url,Channel Title\r\n" +
            "UC_x5XG1OV2P6uZZ5FSM9Ttw,https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw,\"Science, \"\"café\"\"\r\nActualités\"\r\n"
        val parsed = JVMDesktopDatabase.parseTakeoutCsvSubscriptions(csv)
        assertEquals(1, parsed.size)
        assertEquals("Science, \"café\"\r\nActualités", parsed.single().name)
    }

    @Test fun malformedCsvAndForeignYoutubeLookalikesAreNotAccepted() {
        assertFailsWith<IllegalArgumentException> { JVMDesktopDatabase.parseTakeoutCsvSubscriptions("id,url,\"unterminated") }
        assertTrue(JVMDesktopDatabase.parseTakeoutCsvSubscriptions(
            "id,https://attacker.example/youtube.com/channel/UCexample,wrong"
        ).isEmpty())
    }
}
