/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.viewmodel.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.newpipe.app.model.WatchHistoryRecord
import net.newpipe.app.platform.DesktopDatabaseGateway
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class HistoryViewModel(
    private val database: DesktopDatabaseGateway
) : ViewModel() {

    val watchHistory: StateFlow<List<WatchHistoryRecord>> = database.watchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchHistory: StateFlow<List<String>> = database.searchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun deleteWatchEntry(streamId: Long) {
        viewModelScope.launch {
            database.deleteWatchHistoryEntry(streamId)
        }
    }

    fun clearWatchHistory() {
        viewModelScope.launch {
            database.clearWatchHistory()
        }
    }

    fun deleteSearchEntry(query: String) {
        viewModelScope.launch {
            database.deleteSearchEntry(query)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            database.clearSearchHistory()
        }
    }
}
