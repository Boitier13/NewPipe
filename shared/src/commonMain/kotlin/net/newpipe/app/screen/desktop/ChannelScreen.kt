/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.screen.desktop

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import net.newpipe.app.viewmodel.desktop.ChannelViewModel
import newpipe.shared.generated.resources.Res
import newpipe.shared.generated.resources.desktop_load_more
import newpipe.shared.generated.resources.desktop_subscribe
import newpipe.shared.generated.resources.desktop_subscribed
import newpipe.shared.generated.resources.ic_add
import newpipe.shared.generated.resources.ic_arrow_back
import newpipe.shared.generated.resources.ic_check
import newpipe.shared.generated.resources.ic_refresh
import newpipe.shared.generated.resources.desktop_retry
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ChannelScreen(
    channelUrl: String,
    initialName: String,
    viewModel: ChannelViewModel = koinViewModel(),
    navigator: Navigator = koinInject()
) {
    LaunchedEffect(channelUrl) {
        viewModel.loadChannel(channelUrl)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = { navigator.navigateUp() }) {
                Icon(painterResource(Res.drawable.ic_arrow_back), contentDescription = stringResource(Res.string.desktop_accessibility_back))
            }
            Text(
                text = state.channel?.name ?: initialName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { viewModel.loadChannel(channelUrl, forceReload = true) }) {
                Icon(painterResource(Res.drawable.ic_refresh), contentDescription = stringResource(Res.string.desktop_accessibility_refresh))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when {
            state.isLoading && state.channel == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.errorMessage != null && state.channel == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(onClick = { viewModel.loadChannel(channelUrl, forceReload = true) }) {
                            Text(stringResource(Res.string.desktop_retry))
                        }
                    }
                }
            }

            state.channel != null -> {
                val ch = state.channel!!
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 280.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!ch.bannerUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = ch.bannerUrl,
                                    contentDescription = ch.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(150.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AsyncImage(
                                    model = ch.avatarUrl,
                                    contentDescription = ch.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ch.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (ch.subscriberCount >= 0L) {
                                        Text(
                                            text = "${formatCount(ch.subscriberCount)} subscribers",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    if (ch.description.isNotBlank()) {
                                        Text(
                                            text = ch.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                if (state.isSubscribed) {
                                    OutlinedButton(onClick = viewModel::toggleSubscription) {
                                        Icon(painterResource(Res.drawable.ic_check), null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(Res.string.desktop_subscribed))
                                    }
                                } else {
                                    Button(onClick = viewModel::toggleSubscription) {
                                        Icon(painterResource(Res.drawable.ic_add), null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(Res.string.desktop_subscribe))
                                    }
                                }
                            }
                        }
                    }

                    items(ch.videos) { video ->
                        StreamSummaryCard(
                            stream = video,
                            onClick = {
                                navigator.navigateTo(Destination.VideoDetail(video.url, video.title))
                            }
                        )
                    }

                    if (ch.hasNextPage) {
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
