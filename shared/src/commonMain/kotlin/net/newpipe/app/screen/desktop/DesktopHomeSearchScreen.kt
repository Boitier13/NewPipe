/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.screen.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.newpipe.app.model.SearchFilter
import net.newpipe.app.model.SearchResultItem
import net.newpipe.app.navigation.Destination
import net.newpipe.app.navigation.Navigator
import net.newpipe.app.platform.DataSaverController
import net.newpipe.app.viewmodel.desktop.SearchViewModel
import newpipe.shared.generated.resources.Res
import newpipe.shared.generated.resources.desktop_accountless_badge
import newpipe.shared.generated.resources.desktop_data_saver
import newpipe.shared.generated.resources.desktop_data_saver_on
import newpipe.shared.generated.resources.desktop_filter_all
import newpipe.shared.generated.resources.desktop_filter_channels
import newpipe.shared.generated.resources.desktop_filter_playlists
import newpipe.shared.generated.resources.desktop_filter_videos
import newpipe.shared.generated.resources.desktop_load_more
import newpipe.shared.generated.resources.desktop_search_hint
import newpipe.shared.generated.resources.desktop_trending_title
import newpipe.shared.generated.resources.ic_close
import newpipe.shared.generated.resources.ic_data_saver
import newpipe.shared.generated.resources.ic_refresh
import newpipe.shared.generated.resources.ic_search
import newpipe.shared.generated.resources.desktop_retry
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DesktopHomeSearchScreen(
    focusSearchInitially: Boolean = false,
    initialQuery: String? = null,
    viewModel: SearchViewModel = koinViewModel(),
    dataSaverController: DataSaverController = koinInject(),
    navigator: Navigator = koinInject()
) {
    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) {
            viewModel.submitSearch(initialQuery)
        }
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recentSearches by viewModel.searchHistory.collectAsStateWithLifecycle()
    val isDataSaver by dataSaverController.isDataSaverEnabled.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header: Search box + Accountless indicator + Data Saver quick switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChanged,
                    placeholder = { Text(stringResource(Res.string.desktop_search_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    leadingIcon = {
                        Icon(
                            painter = painterResource(Res.drawable.ic_search),
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        Row {
                            if (state.query.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        viewModel.onQueryChanged("")
                                        viewModel.loadTrending()
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_close),
                                        contentDescription = "Clear"
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.submitSearch() }) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_search),
                                    contentDescription = "Search"
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown &&
                                (event.key == Key.Enter || event.key == Key.NumPadEnter)
                            ) {
                                viewModel.submitSearch()
                                true
                            } else {
                                false
                            }
                        }
                )

                DropdownMenu(
                    expanded = state.suggestions.isNotEmpty(),
                    onDismissRequest = viewModel::clearSuggestions
                ) {
                    state.suggestions.forEach { suggestion ->
                        DropdownMenuItem(
                            text = { Text(suggestion) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_search),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = { viewModel.submitSearch(suggestion) }
                        )
                    }
                }
            }

            FilterChip(
                selected = isDataSaver,
                onClick = { dataSaverController.setDataSaverEnabled(!isDataSaver) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(Res.drawable.ic_data_saver),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                label = {
                    Text(
                        stringResource(
                            if (isDataSaver) Res.string.desktop_data_saver_on else Res.string.desktop_data_saver
                        )
                    )
                }
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = stringResource(Res.string.desktop_accountless_badge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Filter chips + recent search pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                SearchFilter.ALL to stringResource(Res.string.desktop_filter_all),
                SearchFilter.VIDEOS to stringResource(Res.string.desktop_filter_videos),
                SearchFilter.CHANNELS to stringResource(Res.string.desktop_filter_channels),
                SearchFilter.PLAYLISTS to stringResource(Res.string.desktop_filter_playlists)
            ).forEach { (filter, label) ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { viewModel.selectFilter(filter) },
                    label = { Text(label) }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(
                onClick = {
                    if (state.isTrendingMode) viewModel.loadTrending()
                    else viewModel.submitSearch(state.activeQuery, recordHistory = false)
                }
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_refresh),
                    contentDescription = "Refresh"
                )
            }
        }

        if (recentSearches.isNotEmpty() && (focusSearchInitially || state.isTrendingMode)) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 6.dp)
            ) {
                items(recentSearches.take(10)) { pastQuery ->
                    AssistChip(
                        onClick = { viewModel.submitSearch(pastQuery) },
                        label = { Text(pastQuery) },
                        trailingIcon = {
                            Icon(
                                painter = painterResource(Res.drawable.ic_close),
                                contentDescription = "Remove",
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { viewModel.deleteSearchHistoryItem(pastQuery) }
                            )
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (state.isTrendingMode) {
                stringResource(Res.string.desktop_trending_title)
            } else {
                "\"${state.activeQuery}\""
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        when {
            state.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.errorMessage != null && state.items.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (state.isTrendingMode) viewModel.loadTrending()
                                else viewModel.submitSearch(state.activeQuery, recordHistory = false)
                            }
                        ) {
                            Text(stringResource(Res.string.desktop_retry))
                        }
                    }
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 280.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.items) { item ->
                        when (item) {
                            is SearchResultItem.Video -> {
                                StreamSummaryCard(
                                    stream = item.stream,
                                    onClick = {
                                        navigator.navigateTo(
                                            Destination.VideoDetail(item.stream.url, item.stream.title)
                                        )
                                    },
                                    onChannelClick = {
                                        val chUrl = item.stream.uploaderUrl
                                        if (!chUrl.isNullOrBlank()) {
                                            navigator.navigateTo(
                                                Destination.ChannelDetail(chUrl, item.stream.uploaderName)
                                            )
                                        }
                                    }
                                )
                            }

                            is SearchResultItem.Channel -> {
                                ChannelSummaryCard(
                                    channel = item.channel,
                                    onClick = {
                                        navigator.navigateTo(
                                            Destination.ChannelDetail(item.channel.url, item.channel.name)
                                        )
                                    }
                                )
                            }

                            is SearchResultItem.Playlist -> {
                                PlaylistSummaryCard(
                                    playlist = item.playlist,
                                    onClick = {
                                        navigator.navigateTo(
                                            Destination.PlaylistDetail(item.playlist.url, item.playlist.name)
                                        )
                                    }
                                )
                            }
                        }
                    }

                    if (state.hasNextPage) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (state.isLoadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                } else {
                                    OutlinedButton(onClick = viewModel::loadMore) {
                                        Text(stringResource(Res.string.desktop_load_more))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
