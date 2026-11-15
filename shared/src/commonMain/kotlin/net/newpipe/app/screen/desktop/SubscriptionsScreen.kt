/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.screen.desktop

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import net.newpipe.app.navigation.Destination
import net.newpipe.app.navigation.Navigator
import net.newpipe.app.viewmodel.desktop.SubscriptionsViewModel
import newpipe.shared.generated.resources.Res
import newpipe.shared.generated.resources.desktop_feed_tab
import newpipe.shared.generated.resources.desktop_nav_subscriptions
import newpipe.shared.generated.resources.desktop_no_subscriptions
import newpipe.shared.generated.resources.desktop_refresh_feed
import newpipe.shared.generated.resources.desktop_subscriptions_tab
import newpipe.shared.generated.resources.ic_delete
import newpipe.shared.generated.resources.ic_refresh
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SubscriptionsScreen(
    viewModel: SubscriptionsViewModel = koinViewModel(),
    navigator: Navigator = koinInject()
) {
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val feedState by viewModel.feedState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Res.string.desktop_nav_subscriptions),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (selectedTab == 1) {
                FilledTonalButton(
                    enabled = !feedState.isRefreshing,
                    onClick = viewModel::refreshFeed
                ) {
                    Icon(painterResource(Res.drawable.ic_refresh), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.desktop_refresh_feed))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        SecondaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("${stringResource(Res.string.desktop_subscriptions_tab)} (${subscriptions.size})") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = {
                    selectedTab = 1
                    if (feedState.feedVideos.isEmpty() && !feedState.isRefreshing && subscriptions.isNotEmpty()) {
                        viewModel.refreshFeed()
                    }
                },
                text = { Text(stringResource(Res.string.desktop_feed_tab)) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (subscriptions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.desktop_no_subscriptions),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (selectedTab == 0) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(subscriptions, key = { it.url }) { sub ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navigator.navigateTo(Destination.ChannelDetail(sub.url, sub.name))
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AsyncImage(
                                model = sub.avatarUrl,
                                contentDescription = sub.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = sub.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                sub.subscriberCount?.takeIf { it >= 0L }?.let { count ->
                                    Text(
                                        text = "${formatCount(count)} subscribers",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            IconButton(onClick = { viewModel.unsubscribe(sub) }) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_delete),
                                    contentDescription = "Unsubscribe"
                                )
                            }
                        }
                    }
                }
            }
        } else {
            when {
                feedState.isRefreshing -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                feedState.feedVideos.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        FilledTonalButton(onClick = viewModel::refreshFeed) {
                            Text(stringResource(Res.string.desktop_refresh_feed))
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
                        items(feedState.feedVideos, key = { it.url }) { stream ->
                            StreamSummaryCard(
                                stream = stream,
                                onClick = {
                                    navigator.navigateTo(Destination.VideoDetail(stream.url, stream.title))
                                },
                                onChannelClick = {
                                    stream.uploaderUrl?.let { chUrl ->
                                        navigator.navigateTo(Destination.ChannelDetail(chUrl, stream.uploaderName))
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
