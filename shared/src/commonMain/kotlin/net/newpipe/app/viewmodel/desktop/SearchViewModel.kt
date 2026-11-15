/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.viewmodel.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.newpipe.app.model.SearchFilter
import net.newpipe.app.model.SearchResultItem
import net.newpipe.app.platform.DesktopDatabaseGateway
import net.newpipe.app.platform.YouTubeBackend
import net.newpipe.app.preferences.HistoryPreferences
import org.koin.core.annotation.KoinViewModel

data class SearchUiState(
    val query: String = "",
    val activeQuery: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val isTrendingMode: Boolean = true,
    val items: List<SearchResultItem> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasNextPage: Boolean = false,
    val nextPageToken: String? = null,
    val errorMessage: String? = null
)

@KoinViewModel
class SearchViewModel(
    private val youTubeBackend: YouTubeBackend,
    private val database: DesktopDatabaseGateway,
    private val settings: Settings
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState(isLoading = true))
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    val searchHistory: StateFlow<List<String>> = database.searchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var suggestionJob: Job? = null
    private var searchJob: Job? = null

    init {
        loadTrending()
    }

    fun loadTrending() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isTrendingMode = true,
                    activeQuery = "",
                    isLoading = true,
                    errorMessage = null,
                    suggestions = emptyList()
                )
            }
            val res = youTubeBackend.getTrendingVideos()
            res.fold(
                onSuccess = { page ->
                    _uiState.update {
                        it.copy(
                            items = page.items,
                            hasNextPage = page.hasNextPage,
                            nextPageToken = page.nextPageToken,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = err.message ?: "Failed to load trending videos"
                        )
                    }
                }
            )
        }
    }

    fun onQueryChanged(newQuery: String) {
        _uiState.update { it.copy(query = newQuery) }
        suggestionJob?.cancel()
        if (newQuery.isBlank()) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(250L)
            val local = database.getSearchSuggestions(newQuery, limit = 5)
            val remote = youTubeBackend.getSuggestions(newQuery)
            val merged = (local + remote).distinct().take(10)
            _uiState.update { it.copy(suggestions = merged) }
        }
    }

    fun selectFilter(filter: SearchFilter) {
        _uiState.update { it.copy(filter = filter) }
        val active = _uiState.value.activeQuery
        if (!_uiState.value.isTrendingMode && active.isNotBlank()) {
            submitSearch(active, recordHistory = false)
        }
    }

    fun submitSearch(explicitQuery: String? = null, recordHistory: Boolean = true) {
        val targetQuery = (explicitQuery ?: _uiState.value.query).trim()
        if (targetQuery.isEmpty()) {
            loadTrending()
            return
        }

        val currentFilter = _uiState.value.filter
        suggestionJob?.cancel()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    query = targetQuery,
                    activeQuery = targetQuery,
                    isTrendingMode = false,
                    suggestions = emptyList(),
                    isLoading = true,
                    errorMessage = null
                )
            }

            if (recordHistory && settings.getBoolean(
                    HistoryPreferences.KEY_ENABLE_SEARCH_HISTORY,
                    HistoryPreferences.DEFAULT_ENABLE_SEARCH_HISTORY
                )
            ) {
                database.recordSearch(targetQuery)
            }

            val res = youTubeBackend.search(targetQuery, currentFilter)
            res.fold(
                onSuccess = { page ->
                    _uiState.update {
                        it.copy(
                            items = page.items,
                            hasNextPage = page.hasNextPage,
                            nextPageToken = page.nextPageToken,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = err.message ?: "Search failed"
                        )
                    }
                }
            )
        }
    }

    fun clearSuggestions() {
        _uiState.update { it.copy(suggestions = emptyList()) }
    }

    fun loadMore() {
        val current = _uiState.value
        val token = current.nextPageToken
        if (current.isLoading || current.isLoadingMore || !current.hasNextPage || token == null) {
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val res = if (current.isTrendingMode) {
                youTubeBackend.getTrendingVideos(pageToken = token)
            } else {
                youTubeBackend.search(current.activeQuery, current.filter, pageToken = token)
            }
            res.fold(
                onSuccess = { page ->
                    _uiState.update {
                        it.copy(
                            items = it.items + page.items,
                            hasNextPage = page.hasNextPage,
                            nextPageToken = page.nextPageToken,
                            isLoadingMore = false
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            errorMessage = err.message
                        )
                    }
                }
            )
        }
    }

    fun deleteSearchHistoryItem(query: String) {
        viewModelScope.launch {
            database.deleteSearchEntry(query)
        }
    }
}
