/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import org.koin.core.annotation.Singleton

@Singleton(binds = [HistoryActions::class])
class JVMHistoryActions(
    private val database: DesktopDatabaseGateway,
    private val youTubeBackend: YouTubeBackend
) : HistoryActions {

    override fun wipeMetadataCache() {
        youTubeBackend.clearMetadataCache()
    }

    override suspend fun clearWatchHistory(): Boolean = database.clearWatchHistory()

    override suspend fun clearPlaybackStates(): Boolean = database.clearPlaybackStates()

    override suspend fun clearSearchHistory(): Boolean = database.clearSearchHistory()

    override fun clearRecaptchaCookies() {
        youTubeBackend.clearRecaptchaCookies()
    }
}
