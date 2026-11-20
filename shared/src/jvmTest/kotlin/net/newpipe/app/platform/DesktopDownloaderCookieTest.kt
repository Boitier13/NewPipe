/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl

class DesktopDownloaderCookieTest {
    private fun configured() = DesktopDownloader().apply {
        updateYoutubeRestrictedModeCookies(true)
        setCookie(DesktopDownloader.RECAPTCHA_COOKIES_KEY, "SID=private; TOKEN=abc=def")
    }

    @Test
    fun youtubeAndSubdomainsReceiveCookiesOverHttpsOnly() {
        val downloader = configured()
        for (url in listOf("https://youtube.com/", "https://WWW.YouTube.com/watch?v=1", "https://m.youtube.com/")) {
            assertTrue(downloader.getCookies(url).contains("SID=private"), url)
            val cookies = downloader.client.cookieJar.loadForRequest(url.toHttpUrl())
            assertEquals(setOf("PREF", "SID", "TOKEN"), cookies.map { it.name }.toSet())
            assertTrue(cookies.all { it.secure && it.hostOnly && it.domain == url.toHttpUrl().host })
            assertEquals("abc=def", cookies.first { it.name == "TOKEN" }.value)
        }
    }

    @Test
    fun foreignHostsUserInfoPathsAndHttpNeverReceiveAppCookies() {
        val downloader = configured()
        for (url in listOf(
            "https://youtube.com.attacker.example/", "https://notyoutube.com/",
            "https://attacker.example/youtube.com", "https://youtube.com@attacker.example/",
            "https://example.org/?next=youtube.com", "http://youtube.com/", "https://vimeo.com/"
        )) {
            assertEquals("", downloader.getCookies(url), url)
            assertTrue(downloader.client.cookieJar.loadForRequest(url.toHttpUrl()).isEmpty(), url)
        }
        assertEquals("", downloader.getCookies("not a URL"))
    }

    @Test
    fun cookieJarReevaluatesDestinationForEachRedirectHop() {
        val jar = configured().client.cookieJar
        assertTrue(jar.loadForRequest("https://youtube.com/".toHttpUrl()).isNotEmpty())
        assertTrue(jar.loadForRequest("https://external.example/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://youtube.com/".toHttpUrl()).isEmpty())
    }

    @Test
    fun cookieRemovalAndRestrictedModeToggleRemainEffective() {
        val downloader = configured()
        downloader.clearRecaptchaCookies()
        assertEquals("PREF=f2=8000000", downloader.getCookies("https://youtube.com/"))
        downloader.updateYoutubeRestrictedModeCookies(false)
        assertEquals("", downloader.getCookies("https://youtube.com/"))
    }
}
