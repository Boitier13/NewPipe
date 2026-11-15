/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.viewmodel.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.newpipe.app.model.StreamSummary
import net.newpipe.app.model.SubscriptionRecord
import net.newpipe.app.platform.DesktopDatabaseGateway
import net.newpipe.app.platform.YouTubeBackend
import org.koin.core.annotation.KoinViewModel

data class SubscriptionsFeedState(
    val feedVideos: List<StreamSummary> = emptyList(),
    val isRefreshing: Boolean = false,
    val statusMessage: String? = null
)

@KoinViewModel
class SubscriptionsViewModel(
    private val database: DesktopDatabaseGateway,
    private val youTubeBackend: YouTubeBackend
) : ViewModel() {

    val subscriptions: StateFlow<List<SubscriptionRecord>> = database.subscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _feedState = MutableStateFlow(SubscriptionsFeedState())
    val feedState: StateFlow<SubscriptionsFeedState> = _feedState.asStateFlow()

    fun unsubscribe(record: SubscriptionRecord) {
        viewModelScope.launch {
            database.unsubscribe(record.serviceId, record.url)
        }
    }

    fun refreshFeed() {
        viewModelScope.launch {
            val subs = database.getAllSubscriptions()
            if (subs.isEmpty()) {
                _feedState.update { it.copy(feedVideos = emptyList(), isRefreshing = false, statusMessage = null) }
                return@launch
            }
            _feedState.update { it.copy(isRefreshing = true, statusMessage = null) }
            val fetched = coroutineScope {
                subs.take(25).map { sub ->
                    async {
                        youTubeBackend.getChannelLatestVideos(sub.url).getOrElse { emptyList() }
                    }
                }.awaitAll().flatten()
            }
            _feedState.update {
                it.copy(
                    feedVideos = fetched.distinctBy { item -> item.url },
                    isRefreshing = false,
                    statusMessage = if (fetched.isEmpty()) "No videos fetched" else null
                )
            }
        }
    }
}
