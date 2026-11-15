/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import kotlinx.coroutines.flow.Flow
import net.newpipe.app.model.ChannelDetails
import net.newpipe.app.model.ChannelSummary
import net.newpipe.app.model.PlaylistSummary
import net.newpipe.app.model.SearchFilter
import net.newpipe.app.model.SearchPageResult
import net.newpipe.app.model.StreamSummary
import net.newpipe.app.model.VideoDetails

/**
 * Desktop backend for YouTube extraction (without Google account).
 */
interface YouTubeBackend {
    suspend fun getTrendingVideos(pageToken: String? = null): Result<SearchPageResult>

    suspend fun search(
        query: String,
        filter: SearchFilter,
        pageToken: String? = null
    ): Result<SearchPageResult>

    suspend fun getSuggestions(query: String): List<String>

    suspend fun getVideoDetails(url: String, forceReload: Boolean = false): Result<VideoDetails>

    suspend fun getChannelDetails(
        url: String,
        forceReload: Boolean = false,
        pageToken: String? = null
    ): Result<ChannelDetails>

    suspend fun getPlaylistVideos(
        url: String,
        pageToken: String? = null
    ): Result<Pair<PlaylistSummary, SearchPageResult>>

    suspend fun getChannelLatestVideos(channelUrl: String): Result<List<StreamSummary>>

    fun clearMetadataCache()

    fun clearRecaptchaCookies()

    fun applyLocalizationAndContentPrefs()

    fun applyImageQuality(quality: String)

    fun applyYoutubeRestrictedMode(enabled: Boolean)
}

/**
 * Persistent SQLite gateway for subscriptions, watch history, search history, and playback state.
 */
interface DesktopDatabaseGateway {
    val subscriptions: Flow<List<net.newpipe.app.model.SubscriptionRecord>>
    val watchHistory: Flow<List<net.newpipe.app.model.WatchHistoryRecord>>
    val searchHistory: Flow<List<String>>

    fun isSubscribedFlow(url: String): Flow<Boolean>

    suspend fun subscribe(channel: ChannelDetails)
    suspend fun subscribe(channel: ChannelSummary)
    suspend fun unsubscribe(serviceId: Int, url: String)
    suspend fun getAllSubscriptions(): List<net.newpipe.app.model.SubscriptionRecord>
    suspend fun exportSubscriptionsJson(filePath: String): Result<Unit>
    suspend fun importSubscriptionsJson(filePath: String): Result<Int>

    suspend fun recordView(video: VideoDetails)
    suspend fun savePlaybackProgress(url: String, serviceId: Int, progressMs: Long)
    suspend fun getPlaybackProgress(url: String, serviceId: Int): Long
    suspend fun deleteWatchHistoryEntry(streamId: Long)
    suspend fun clearWatchHistory(): Boolean
    suspend fun clearPlaybackStates(): Boolean

    suspend fun recordSearch(query: String)
    suspend fun getSearchSuggestions(prefix: String, limit: Int = 10): List<String>
    suspend fun deleteSearchEntry(query: String)
    suspend fun clearSearchHistory(): Boolean

    suspend fun exportDatabase(targetPath: String): Result<Unit>
    suspend fun importDatabase(sourcePath: String): Result<Unit>
}
