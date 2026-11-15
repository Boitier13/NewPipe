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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.newpipe.app.model.ChannelSummary
import net.newpipe.app.model.PlayerPlaybackMode
import net.newpipe.app.model.PlayerState
import net.newpipe.app.model.PlaylistSummary
import net.newpipe.app.model.StreamSummary
import net.newpipe.app.platform.MediaPlayerController
import newpipe.shared.generated.resources.Res
import newpipe.shared.generated.resources.desktop_background_audio_active
import newpipe.shared.generated.resources.desktop_switch_to_video
import newpipe.shared.generated.resources.ic_forward_10
import newpipe.shared.generated.resources.ic_headset
import newpipe.shared.generated.resources.ic_movie
import newpipe.shared.generated.resources.ic_pause
import newpipe.shared.generated.resources.ic_play_arrow
import newpipe.shared.generated.resources.ic_replay_10
import newpipe.shared.generated.resources.ic_smart_display
import newpipe.shared.generated.resources.ic_stop
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun StreamSummaryCard(
    stream: StreamSummary,
    onClick: () -> Unit,
    onChannelClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                if (!stream.thumbnailUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = stream.thumbnailUrl,
                        contentDescription = stream.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                } else {
                    Icon(
                        painter = painterResource(Res.drawable.ic_smart_display),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp).align(Alignment.Center)
                    )
                }

                val badgeText = formatDurationSeconds(stream.durationSeconds, stream.isLive)
                if (badgeText.isNotEmpty()) {
                    Surface(
                        color = if (stream.isLive) MaterialTheme.colorScheme.error else Color.Black.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stream.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stream.uploaderName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onChannelClick != null && !stream.uploaderUrl.isNullOrBlank()) {
                        Modifier.clickable(onClick = onChannelClick)
                    } else {
                        Modifier
                    }
                )
                val metaParts = listOfNotNull(
                    formatCount(stream.viewCount).takeIf { it.isNotEmpty() }?.let { "$it views" },
                    stream.textualUploadDate?.takeIf { it.isNotBlank() }
                )
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun ChannelSummaryCard(
    channel: ChannelSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = channel.thumbnailUrl,
                contentDescription = channel.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (channel.subscriberCount >= 0L) {
                    Text(
                        text = "${formatCount(channel.subscriberCount)} subscribers",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (channel.description.isNotBlank()) {
                    Text(
                        text = channel.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistSummaryCard(
    playlist: PlaylistSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                AsyncImage(
                    model = playlist.thumbnailUrl,
                    contentDescription = playlist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                playlist.uploaderName?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (playlist.streamCount >= 0L) {
                    Text(
                        text = "${playlist.streamCount} videos",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun MiniPlayerBar(
    playerState: PlayerState,
    mediaPlayerController: MediaPlayerController,
    onOpenVideo: (url: String, title: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeVideo = playerState.currentVideo
    val title = activeVideo?.title ?: playerState.localTitle ?: return

    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            val progress = if (playerState.durationMs > 0L) {
                (playerState.positionMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = activeVideo != null) {
                        activeVideo?.let { onOpenVideo(it.url, it.title) }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (!activeVideo?.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = activeVideo.thumbnailUrl,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize()
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                if (playerState.mode == PlayerPlaybackMode.BACKGROUND_AUDIO) {
                                    Res.drawable.ic_headset
                                } else {
                                    Res.drawable.ic_movie
                                }
                            ),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = buildString {
                        if (playerState.mode == PlayerPlaybackMode.BACKGROUND_AUDIO) {
                            append(stringResource(Res.string.desktop_background_audio_active))
                        } else {
                            append(activeVideo?.uploaderName.orEmpty())
                        }
                        if (playerState.durationMs > 0L) {
                            append(" • ")
                            append(formatMillis(playerState.positionMs))
                            append(" / ")
                            append(formatMillis(playerState.durationMs))
                        }
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (playerState.mode == PlayerPlaybackMode.BACKGROUND_AUDIO && activeVideo != null) {
                    TextButton(
                        onClick = {
                            mediaPlayerController.switchToVideo()
                            onOpenVideo(activeVideo.url, activeVideo.title)
                        }
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_movie),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(Res.string.desktop_switch_to_video))
                    }
                }

                IconButton(onClick = { mediaPlayerController.seekBy(-10_000L) }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_replay_10),
                        contentDescription = "-10s"
                    )
                }
                IconButton(onClick = { mediaPlayerController.togglePlayPause() }) {
                    Icon(
                        painter = painterResource(
                            if (playerState.isPlaying) Res.drawable.ic_pause else Res.drawable.ic_play_arrow
                        ),
                        contentDescription = if (playerState.isPlaying) "Pause" else "Play"
                    )
                }
                IconButton(onClick = { mediaPlayerController.seekBy(10_000L) }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_forward_10),
                        contentDescription = "+10s"
                    )
                }
                IconButton(onClick = { mediaPlayerController.stop() }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_stop),
                        contentDescription = "Stop"
                    )
                }
            }
        }
    }
}
