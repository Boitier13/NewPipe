/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.CompressionInterceptor
import okhttp3.Dns
import okhttp3.Gzip
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.brotli.Brotli
import org.koin.core.annotation.Singleton
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * Pure JVM OkHttp-backed implementation of NewPipeExtractor's [Downloader].
 */
@Singleton
class DesktopDownloader : Downloader() {

    private val cookies = ConcurrentHashMap<String, String>()

    private val ipv4FirstDns = Dns { hostname ->
        val all = Dns.SYSTEM.lookup(hostname)
        all.sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .dns(ipv4FirstDns)
        .addInterceptor(CompressionInterceptor(Brotli, Gzip))
        .build()

    fun setCookie(key: String, cookie: String) {
        cookies[key] = cookie
    }

    fun removeCookie(key: String) {
        cookies.remove(key)
    }

    fun getCookie(key: String): String? = cookies[key]

    fun clearRecaptchaCookies() {
        cookies.remove(RECAPTCHA_COOKIES_KEY)
    }

    fun updateYoutubeRestrictedModeCookies(enabled: Boolean) {
        if (enabled) {
            setCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY, YOUTUBE_RESTRICTED_MODE_COOKIE)
        } else {
            removeCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY)
        }
    }

    fun getCookies(url: String): String {
        val youtubeCookie = if (url.contains(YOUTUBE_DOMAIN)) {
            getCookie(YOUTUBE_RESTRICTED_MODE_COOKIE_KEY)
        } else {
            null
        }
        return listOfNotNull(youtubeCookie, getCookie(RECAPTCHA_COOKIES_KEY))
            .flatMap { it.split(Regex("; *")) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("; ")
    }

    @Throws(IOException::class)
    fun getContentLength(url: String): Long {
        return try {
            val response = head(url)
            response.getHeader("Content-Length")?.toLongOrNull() ?: -1L
        } catch (e: ReCaptchaException) {
            throw IOException(e)
        }
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBody: RequestBody? = dataToSend?.toRequestBody()

        val builder = okhttp3.Request.Builder()
            .method(httpMethod, requestBody)
            .url(url)
            .addHeader("User-Agent", USER_AGENT)

        val cookieHeader = getCookies(url)
        if (cookieHeader.isNotEmpty()) {
            builder.addHeader("Cookie", cookieHeader)
        }

        headers.forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value ->
                builder.addHeader(name, value)
            }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            val bodyString = response.body.string()
            val latestUrl = response.request.url.toString()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                bodyString,
                latestUrl
            )
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"
        const val YOUTUBE_RESTRICTED_MODE_COOKIE_KEY = "youtube_restricted_mode_key"
        const val YOUTUBE_RESTRICTED_MODE_COOKIE = "PREF=f2=8000000"
        const val RECAPTCHA_COOKIES_KEY = "recaptcha_cookies_key"
        const val YOUTUBE_DOMAIN = "youtube.com"
    }
}
