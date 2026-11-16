/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import java.util.concurrent.TimeUnit
import org.koin.core.annotation.Singleton
import org.schabi.newpipe.extractor.Info
import org.schabi.newpipe.extractor.ServiceList

/**
 * Thread-safe LRU cache with TTL for NewPipeExtractor results (replaces Android LruCache InfoCache).
 */
@Singleton
class DesktopInfoCache {

    enum class Type {
        STREAM,
        CHANNEL,
        CHANNEL_TAB,
        COMMENTS,
        PLAYLIST,
        KIOSK
    }

    private class CacheData(val info: Info, timeoutMillis: Long) {
        private val expireTimestamp = System.currentTimeMillis() + timeoutMillis
        fun isExpired(): Boolean = System.currentTimeMillis() > expireTimestamp
    }

    private val lock = Any()
    private val cache = object : LinkedHashMap<String, CacheData>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheData>?): Boolean {
            return size > MAX_ITEMS
        }
    }

    private fun keyOf(serviceId: Int, url: String, type: Type): String =
        "$serviceId:${type.ordinal}:$url"

    fun get(serviceId: Int, url: String, type: Type): Info? = synchronized(lock) {
        val key = keyOf(serviceId, url, type)
        val entry = cache[key] ?: return null
        if (entry.isExpired()) {
            cache.remove(key)
            return null
        }
        entry.info
    }

    fun put(serviceId: Int, url: String, info: Info, type: Type) = synchronized(lock) {
        val ttl = if (info.serviceId == ServiceList.SoundCloud.serviceId) {
            TimeUnit.MILLISECONDS.convert(5, TimeUnit.MINUTES)
        } else {
            TimeUnit.MILLISECONDS.convert(1, TimeUnit.HOURS)
        }
        cache[keyOf(serviceId, url, type)] = CacheData(info, ttl)
    }

    fun remove(serviceId: Int, url: String, type: Type) = synchronized(lock) {
        cache.remove(keyOf(serviceId, url, type))
        Unit
    }

    fun clear() = synchronized(lock) {
        cache.clear()
    }

    companion object {
        private const val MAX_ITEMS = 60
    }
}
