/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.viewmodel.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.newpipe.app.model.ChannelDetails
import net.newpipe.app.platform.DesktopDatabaseGateway
import net.newpipe.app.platform.YouTubeBackend
import org.koin.core.annotation.KoinViewModel

data class ChannelUiState(
    val url: String = "",
    val channel: ChannelDetails? = null,
    val isSubscribed: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null
)

@KoinViewModel
class ChannelViewModel(
    private val youTubeBackend: YouTubeBackend,
    private val database: DesktopDatabaseGateway
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChannelUiState())
    val uiState: StateFlow<ChannelUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var subJob: Job? = null

    fun loadChannel(url: String, forceReload: Boolean = false) {
        if (!forceReload && _uiState.value.url == url && _uiState.value.channel != null) return
        loadJob?.cancel()
        observeSubscription(url)
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(url = url, isLoading = true, errorMessage = null) }
            youTubeBackend.getChannelDetails(url, forceReload = forceReload).fold(
                onSuccess = { details ->
                    _uiState.update {
                        it.copy(channel = details, isLoading = false, errorMessage = null)
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = "Impossible de charger la chaîne. Vérifiez la connexion et réessayez.")
                    }
                }
            )
        }
    }

    fun loadMore() {
        val current = _uiState.value
        val ch = current.channel ?: return
        val token = ch.nextPageToken ?: return
        if (current.isLoading || current.isLoadingMore || !ch.hasNextPage) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            youTubeBackend.getChannelDetails(ch.url, pageToken = token).fold(
                onSuccess = { nextPage ->
                    _uiState.update {
                        it.copy(
                            channel = ch.copy(
                                videos = ch.videos + nextPage.videos,
                                hasNextPage = nextPage.hasNextPage,
                                nextPageToken = nextPage.nextPageToken
                            ),
                            isLoadingMore = false
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isLoadingMore = false, errorMessage = "Impossible de charger la suite de la chaîne. Réessayez.")
                    }
                }
            )
        }
    }

    fun toggleSubscription() {
        val ch = _uiState.value.channel ?: return
        viewModelScope.launch {
            if (_uiState.value.isSubscribed) {
                database.unsubscribe(ch.serviceId, ch.url)
            } else {
                database.subscribe(ch)
            }
        }
    }

    private fun observeSubscription(url: String) {
        subJob?.cancel()
        subJob = viewModelScope.launch {
            database.isSubscribedFlow(url).collect { subscribed ->
                _uiState.update { it.copy(isSubscribed = subscribed) }
            }
        }
    }
}
