/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class JVMSubscriptionsImportTest {

    @Test
    fun parsesGoogleTakeoutCsvSubscriptionsWithQuotesAndHeaders() {
        val csv = listOf(
            "ID de la chaîne,URL de la chaîne,Titre de la chaîne",
            "UC_x5XG1OV2P6uZZ5FSM9Ttw,http://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw,Google for Developers",
            "UCabcdef1234567890abcdef,https://www.youtube.com/channel/UCabcdef1234567890abcdef,\"Science, Tech & \"\"More\"\"\""
        ).joinToString("\n")

        val parsed = JVMDesktopDatabase.parseTakeoutCsvSubscriptions(csv)
        assertEquals(2, parsed.size)
        assertEquals("https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw", parsed[0].url)
        assertEquals("Google for Developers", parsed[0].name)
        assertEquals("https://www.youtube.com/channel/UCabcdef1234567890abcdef", parsed[1].url)
        assertEquals("Science, Tech & \"More\"", parsed[1].name)
    }
}
