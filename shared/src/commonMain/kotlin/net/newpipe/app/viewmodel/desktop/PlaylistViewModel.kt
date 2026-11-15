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
import net.newpipe.app.model.PlaylistSummary
import net.newpipe.app.model.SearchResultItem
import net.newpipe.app.model.StreamSummary
import net.newpipe.app.platform.YouTubeBackend
import org.koin.core.annotation.KoinViewModel

data class PlaylistUiState(
    val url: String = "",
    val summary: PlaylistSummary? = null,
    val videos: List<StreamSummary> = emptyList(),
    val hasNextPage: Boolean = false,
    val nextPageToken: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null
)

@KoinViewModel
class PlaylistViewModel(
    private val youTubeBackend: YouTubeBackend
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistUiState())
    val uiState: StateFlow<PlaylistUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun loadPlaylist(url: String) {
        if (_uiState.value.url == url && _uiState.value.summary != null) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(url = url, isLoading = true, errorMessage = null) }
            youTubeBackend.getPlaylistVideos(url).fold(
                onSuccess = { (summary, page) ->
                    _uiState.update {
                        it.copy(
                            summary = summary,
                            videos = page.items.filterIsInstance<SearchResultItem.Video>().map { v -> v.stream },
                            hasNextPage = page.hasNextPage,
                            nextPageToken = page.nextPageToken,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = err.message ?: "Failed to load playlist")
                    }
                }
            )
        }
    }

    fun loadMore() {
        val current = _uiState.value
        val token = current.nextPageToken ?: return
        if (current.isLoading || current.isLoadingMore || !current.hasNextPage) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            youTubeBackend.getPlaylistVideos(current.url, pageToken = token).fold(
                onSuccess = { (_, page) ->
                    val more = page.items.filterIsInstance<SearchResultItem.Video>().map { it.stream }
                    _uiState.update {
                        it.copy(
                            videos = it.videos + more,
                            hasNextPage = page.hasNextPage,
                            nextPageToken = page.nextPageToken,
                            isLoadingMore = false
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isLoadingMore = false, errorMessage = err.message)
                    }
                }
            )
        }
    }
}
