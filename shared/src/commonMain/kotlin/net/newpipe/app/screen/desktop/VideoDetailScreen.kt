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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import net.newpipe.app.model.AudioStreamOption
import net.newpipe.app.model.DesktopDownloadOptionsHelper
import net.newpipe.app.model.DownloadFormat
import net.newpipe.app.model.PlayerPlaybackMode
import net.newpipe.app.model.PlayerState
import net.newpipe.app.model.VideoDetails
import net.newpipe.app.model.VideoStreamOption
import net.newpipe.app.navigation.Destination
import net.newpipe.app.navigation.Navigator
import net.newpipe.app.preferences.DesktopPreferences
import net.newpipe.app.viewmodel.desktop.VideoDetailViewModel
import newpipe.shared.generated.resources.Res
import newpipe.shared.generated.resources.cancel
import newpipe.shared.generated.resources.desktop_audio_bitrate
import newpipe.shared.generated.resources.desktop_audio_track
import newpipe.shared.generated.resources.desktop_background_audio_active
import newpipe.shared.generated.resources.desktop_data_saver_on
import newpipe.shared.generated.resources.desktop_description
import newpipe.shared.generated.resources.desktop_download
import newpipe.shared.generated.resources.desktop_download_dialog_title
import newpipe.shared.generated.resources.desktop_download_mp3
import newpipe.shared.generated.resources.desktop_download_mp4
import newpipe.shared.generated.resources.desktop_download_native_audio
import newpipe.shared.generated.resources.desktop_ffmpeg_missing_hint
import newpipe.shared.generated.resources.desktop_play_background
import newpipe.shared.generated.resources.desktop_play_video
import newpipe.shared.generated.resources.desktop_related_videos
import newpipe.shared.generated.resources.desktop_resolution
import newpipe.shared.generated.resources.desktop_speed
import newpipe.shared.generated.resources.desktop_subscribe
import newpipe.shared.generated.resources.desktop_subscribed
import newpipe.shared.generated.resources.desktop_switch_to_video
import newpipe.shared.generated.resources.ic_add
import newpipe.shared.generated.resources.ic_arrow_back
import newpipe.shared.generated.resources.ic_check
import newpipe.shared.generated.resources.ic_file_download
import newpipe.shared.generated.resources.ic_forward_10
import newpipe.shared.generated.resources.ic_fullscreen
import newpipe.shared.generated.resources.ic_fullscreen_exit
import newpipe.shared.generated.resources.ic_headset
import newpipe.shared.generated.resources.ic_movie
import newpipe.shared.generated.resources.ic_pause
import newpipe.shared.generated.resources.ic_play_arrow
import newpipe.shared.generated.resources.ic_refresh
import newpipe.shared.generated.resources.ic_replay_10
import newpipe.shared.generated.resources.ic_settings
import newpipe.shared.generated.resources.ic_volume_off
import newpipe.shared.generated.resources.ic_volume_up
import newpipe.shared.generated.resources.desktop_retry
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoDetailScreen(
    videoUrl: String,
    initialTitle: String,
    viewModel: VideoDetailViewModel = koinViewModel(),
    navigator: Navigator = koinInject()
) {
    LaunchedEffect(videoUrl) {
        viewModel.loadVideo(videoUrl)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val isDataSaver by viewModel.dataSaverController.isDataSaverEnabled.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showDownloadDialog by remember { mutableStateOf(false) }

    if (playerState.isFullscreen) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { evt ->
                    if (evt.type == KeyEventType.KeyDown && evt.key == Key.Escape) {
                        viewModel.mediaPlayerController.setFullscreen(false)
                        true
                    } else {
                        false
                    }
                }
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                viewModel.mediaPlayerController.VideoViewport(modifier = Modifier.fillMaxSize())
            }
            DesktopPlayerControlsBar(
                details = state.details,
                playerState = playerState,
                viewModel = viewModel
            )
        }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Top bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { navigator.navigateUp() }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_arrow_back),
                        contentDescription = "Back"
                    )
                }
                Text(
                    text = state.details?.title ?: initialTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isDataSaver) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.desktop_data_saver_on),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                IconButton(onClick = { viewModel.loadVideo(videoUrl, forceReload = true) }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_refresh),
                        contentDescription = "Reload"
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                state.isLoading && state.details == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = videoUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                state.errorMessage != null && state.details == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = state.errorMessage.orEmpty(),
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { viewModel.loadVideo(videoUrl, forceReload = true) }) {
                                Text(stringResource(Res.string.desktop_retry))
                            }
                        }
                    }
                }

                state.details != null -> {
                    val details = state.details!!
                    val isPlayingThisVideo = playerState.currentVideo?.url == details.url

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Left column: Player + Metadata
                        Column(
                            modifier = Modifier
                                .weight(2f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        ) {
                            // Player area
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Black,
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            ) {
                                when {
                                    isPlayingThisVideo && playerState.mode == PlayerPlaybackMode.BACKGROUND_AUDIO -> {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (!details.thumbnailUrl.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = details.thumbnailUrl,
                                                    contentDescription = details.title,
                                                    contentScale = ContentScale.Crop,
                                                    alpha = 0.35f,
                                                    modifier = Modifier.matchParentSize()
                                                )
                                            }
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    painter = painterResource(Res.drawable.ic_headset),
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(48.dp)
                                                )
                                                Text(
                                                    text = stringResource(Res.string.desktop_background_audio_active),
                                                    color = Color.White,
                                                    style = MaterialTheme.typography.titleSmall
                                                )
                                                FilledTonalButton(onClick = viewModel::switchToVideo) {
                                                    Icon(
                                                        painter = painterResource(Res.drawable.ic_movie),
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(stringResource(Res.string.desktop_switch_to_video))
                                                }
                                            }
                                        }
                                    }

                                    !playerState.vlcAvailable -> {
                                        Box(
                                            modifier = Modifier.fillMaxSize().padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Text(
                                                    text = playerState.errorMessage ?: "VLC / libVLC not detected",
                                                    color = Color.White
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Button(onClick = { viewModel.mediaPlayerController.recheckVlcAvailability() }) {
                                                        Text("Re-detect VLC")
                                                    }
                                                    OutlinedButton(
                                                        onClick = { navigator.navigateTo(Destination.VideoAudioSettings) }
                                                    ) {
                                                        Icon(
                                                            painter = painterResource(Res.drawable.ic_settings),
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Settings")
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    else -> {
                                        viewModel.mediaPlayerController.VideoViewport(
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }

                            // Non-overlapping controls bar BELOW SwingPanel so Windows HWND never occludes Compose UI
                            DesktopPlayerControlsBar(
                                details = details,
                                playerState = playerState,
                                viewModel = viewModel
                            )

                            playerState.errorMessage?.let { err ->
                                if (playerState.vlcAvailable) {
                                    Text(
                                        text = err,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = details.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            val stats = listOfNotNull(
                                formatCount(details.viewCount).takeIf { it.isNotEmpty() }?.let { "$it views" },
                                details.uploadDateText?.takeIf { it.isNotBlank() }
                            )
                            if (stats.isNotEmpty()) {
                                Text(
                                    text = stats.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Channel row + actions
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        AsyncImage(
                                            model = details.uploaderAvatarUrl,
                                            contentDescription = details.uploaderName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                                .clickable(enabled = !details.uploaderUrl.isNullOrBlank()) {
                                                    details.uploaderUrl?.let { chUrl ->
                                                        navigator.navigateTo(
                                                            Destination.ChannelDetail(chUrl, details.uploaderName)
                                                        )
                                                    }
                                                }
                                        )
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable(enabled = !details.uploaderUrl.isNullOrBlank()) {
                                                    details.uploaderUrl?.let { chUrl ->
                                                        navigator.navigateTo(
                                                            Destination.ChannelDetail(chUrl, details.uploaderName)
                                                        )
                                                    }
                                                }
                                        ) {
                                            Text(
                                                text = details.uploaderName,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (details.subscriberCount >= 0L) {
                                                Text(
                                                    text = "${formatCount(details.subscriberCount)} subscribers",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        if (!details.uploaderUrl.isNullOrBlank()) {
                                            if (state.isSubscribed) {
                                                OutlinedButton(onClick = viewModel::toggleSubscription) {
                                                    Icon(
                                                        painter = painterResource(Res.drawable.ic_check),
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(stringResource(Res.string.desktop_subscribed))
                                                }
                                            } else {
                                                Button(onClick = viewModel::toggleSubscription) {
                                                    Icon(
                                                        painter = painterResource(Res.drawable.ic_add),
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(stringResource(Res.string.desktop_subscribe))
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        FilledTonalButton(onClick = viewModel::playVideo) {
                                            Icon(
                                                painter = painterResource(Res.drawable.ic_play_arrow),
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(stringResource(Res.string.desktop_play_video))
                                        }

                                        FilledTonalButton(onClick = viewModel::playBackgroundAudio) {
                                            Icon(
                                                painter = painterResource(Res.drawable.ic_headset),
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(stringResource(Res.string.desktop_play_background))
                                        }

                                        Button(onClick = { showDownloadDialog = true }) {
                                            Icon(
                                                painter = painterResource(Res.drawable.ic_file_download),
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(stringResource(Res.string.desktop_download))
                                        }
                                    }
                                }
                            }

                            if (details.description.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = stringResource(Res.string.desktop_description),
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = details.description,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }

                        // Right column: Related Videos
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            Text(
                                text = stringResource(Res.string.desktop_related_videos),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(details.relatedVideos) { related ->
                                    StreamSummaryCard(
                                        stream = related,
                                        onClick = {
                                            navigator.navigateTo(
                                                Destination.VideoDetail(related.url, related.title)
                                            )
                                        },
                                        onChannelClick = {
                                            related.uploaderUrl?.let { chUrl ->
                                                navigator.navigateTo(
                                                    Destination.ChannelDetail(chUrl, related.uploaderName)
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (showDownloadDialog) {
                        DownloadMediaDialog(
                            details = details,
                            ffmpegAvailable = viewModel.isFfmpegAvailable(),
                            onDownloadMp4 = { option ->
                                viewModel.downloadMp4(option)
                                showDownloadDialog = false
                                scope.launch {
                                    snackbarHostState.showSnackbar("MP4 download started (${option.resolution})")
                                }
                            },
                            onDownloadMp3 = { option, bitrateKbps ->
                                viewModel.downloadMp3(option, bitrateKbps)
                                showDownloadDialog = false
                                scope.launch {
                                    snackbarHostState.showSnackbar("MP3 download started (${bitrateKbps} kbps)")
                                }
                            },
                            onDownloadNativeAudio = { option, bitrateKbps ->
                                viewModel.downloadNativeAudio(option, bitrateKbps)
                                showDownloadDialog = false
                                val msg = bitrateKbps?.let { "Audio download started (${it} kbps)" }
                                    ?: "Audio download started"
                                scope.launch {
                                    snackbarHostState.showSnackbar(msg)
                                }
                            },
                            onDismiss = { showDownloadDialog = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopPlayerControlsBar(
    details: VideoDetails?,
    playerState: PlayerState,
    viewModel: VideoDetailViewModel
) {
    var showResMenu by remember { mutableStateOf(false) }
    var showAudioMenu by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    val videoStreams = details?.videoStreams.orEmpty()
    val audioStreams = details?.audioStreams.orEmpty()

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            if (playerState.durationMs > 0L) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = formatMillis(playerState.positionMs),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Slider(
                        value = playerState.positionMs.toFloat().coerceIn(0f, playerState.durationMs.toFloat()),
                        onValueChange = { viewModel.mediaPlayerController.seekTo(it.toLong()) },
                        valueRange = 0f..playerState.durationMs.toFloat().coerceAtLeast(1f),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatMillis(playerState.durationMs),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = { viewModel.mediaPlayerController.seekBy(-10_000L) }) {
                    Icon(painterResource(Res.drawable.ic_replay_10), contentDescription = "-10s")
                }
                IconButton(onClick = { viewModel.mediaPlayerController.togglePlayPause() }) {
                    Icon(
                        painterResource(
                            if (playerState.isPlaying) Res.drawable.ic_pause else Res.drawable.ic_play_arrow
                        ),
                        contentDescription = if (playerState.isPlaying) "Pause" else "Play"
                    )
                }
                IconButton(onClick = { viewModel.mediaPlayerController.seekBy(10_000L) }) {
                    Icon(painterResource(Res.drawable.ic_forward_10), contentDescription = "+10s")
                }

                IconButton(onClick = { viewModel.mediaPlayerController.toggleMute() }) {
                    Icon(
                        painterResource(
                            if (playerState.isMuted || playerState.volume == 0) {
                                Res.drawable.ic_volume_off
                            } else {
                                Res.drawable.ic_volume_up
                            }
                        ),
                        contentDescription = "Mute"
                    )
                }
                Slider(
                    value = if (playerState.isMuted) 0f else playerState.volume.toFloat(),
                    onValueChange = { viewModel.mediaPlayerController.setVolume(it.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.width(96.dp)
                )

                Spacer(modifier = Modifier.weight(1f))

                if (videoStreams.isNotEmpty()) {
                    Box {
                        TextButton(onClick = { showResMenu = true }) {
                            Text(
                                text = playerState.selectedVideoOption?.let {
                                    "${it.resolution} (${it.formatLabel})"
                                } ?: stringResource(Res.string.desktop_resolution)
                            )
                        }
                        DropdownMenu(
                            expanded = showResMenu,
                            onDismissRequest = { showResMenu = false }
                        ) {
                            videoStreams.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        val suffix = if (option.isVideoOnly) "DASH" else "Progressive"
                                        Text("${option.resolution} • ${option.formatLabel} ($suffix)")
                                    },
                                    onClick = {
                                        showResMenu = false
                                        viewModel.selectResolution(option)
                                    }
                                )
                            }
                        }
                    }
                }

                if (audioStreams.isNotEmpty()) {
                    Box {
                        TextButton(onClick = { showAudioMenu = true }) {
                            Text(
                                text = playerState.selectedAudioOption?.let {
                                    "${it.bitrateKbps}k ${it.localeLabel.orEmpty()}".trim()
                                } ?: stringResource(Res.string.desktop_audio_track)
                            )
                        }
                        DropdownMenu(
                            expanded = showAudioMenu,
                            onDismissRequest = { showAudioMenu = false }
                        ) {
                            audioStreams.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        val lang = option.localeLabel?.let { " • $it" } ?: ""
                                        Text("${option.formatLabel} ${option.bitrateKbps} kbps$lang")
                                    },
                                    onClick = {
                                        showAudioMenu = false
                                        viewModel.selectAudioTrack(option)
                                    }
                                )
                            }
                        }
                    }
                }

                Box {
                    TextButton(onClick = { showSpeedMenu = true }) {
                        Text("${playerState.playbackRate}x")
                    }
                    DropdownMenu(
                        expanded = showSpeedMenu,
                        onDismissRequest = { showSpeedMenu = false }
                    ) {
                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { rate ->
                            DropdownMenuItem(
                                text = { Text("${rate}x") },
                                onClick = {
                                    showSpeedMenu = false
                                    viewModel.mediaPlayerController.setPlaybackRate(rate)
                                }
                            )
                        }
                    }
                }

                IconButton(
                    onClick = {
                        if (playerState.mode == PlayerPlaybackMode.BACKGROUND_AUDIO) {
                            viewModel.switchToVideo()
                        } else {
                            viewModel.switchToBackgroundAudio()
                        }
                    }
                ) {
                    Icon(
                        painterResource(
                            if (playerState.mode == PlayerPlaybackMode.BACKGROUND_AUDIO) {
                                Res.drawable.ic_movie
                            } else {
                                Res.drawable.ic_headset
                            }
                        ),
                        contentDescription = stringResource(Res.string.desktop_play_background)
                    )
                }

                IconButton(onClick = { viewModel.mediaPlayerController.toggleFullscreen() }) {
                    Icon(
                        painterResource(
                            if (playerState.isFullscreen) {
                                Res.drawable.ic_fullscreen_exit
                            } else {
                                Res.drawable.ic_fullscreen
                            }
                        ),
                        contentDescription = "Fullscreen"
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadMediaDialog(
    details: VideoDetails,
    ffmpegAvailable: Boolean,
    onDownloadMp4: (VideoStreamOption) -> Unit,
    onDownloadMp3: (AudioStreamOption, Int) -> Unit,
    onDownloadNativeAudio: (AudioStreamOption, Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMode by remember { mutableStateOf(DownloadFormat.MP4) }
    val orderedVideos = remember(details.videoStreams, ffmpegAvailable) {
        DesktopDownloadOptionsHelper.buildVideoDownloadOptions(details.videoStreams, ffmpegAvailable)
    }
    var selectedVideo by remember(orderedVideos) { mutableStateOf(orderedVideos.firstOrNull()) }
    var selectedAudio by remember(details.audioStreams) {
        mutableStateOf(
            details.audioStreams.maxByOrNull { it.bitrateKbps } ?: details.audioStreams.firstOrNull()
        )
    }
    var selectedAudioBitrateKbps by remember { mutableStateOf(128) }
    var useCustomAudioBitrate by remember(ffmpegAvailable) { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.desktop_download_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = details.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                HorizontalDivider()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { selectedMode = DownloadFormat.MP4 }
                ) {
                    RadioButton(
                        selected = selectedMode == DownloadFormat.MP4,
                        onClick = { selectedMode = DownloadFormat.MP4 }
                    )
                    Text(stringResource(Res.string.desktop_download_mp4))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { selectedMode = DownloadFormat.MP3 }
                ) {
                    RadioButton(
                        selected = selectedMode == DownloadFormat.MP3,
                        onClick = { selectedMode = DownloadFormat.MP3 }
                    )
                    Text(stringResource(Res.string.desktop_download_mp3))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { selectedMode = DownloadFormat.M4A }
                ) {
                    RadioButton(
                        selected = selectedMode == DownloadFormat.M4A,
                        onClick = { selectedMode = DownloadFormat.M4A }
                    )
                    Text(stringResource(Res.string.desktop_download_native_audio))
                }

                if (selectedMode == DownloadFormat.MP3 && !ffmpegAvailable) {
                    Text(
                        text = stringResource(Res.string.desktop_ffmpeg_missing_hint),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                HorizontalDivider()

                when (selectedMode) {
                    DownloadFormat.MP4 -> {
                        Text(
                            text = stringResource(Res.string.desktop_resolution),
                            style = MaterialTheme.typography.labelLarge
                        )
                        orderedVideos.forEach { option ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedVideo = option }
                            ) {
                                RadioButton(
                                    selected = selectedVideo?.id == option.id,
                                    onClick = { selectedVideo = option }
                                )
                                val muxTag = when {
                                    option.targetScaleHeight != null -> "FFmpeg"
                                    option.formatKey == "HLS" -> "HLS"
                                    option.isVideoOnly -> "DASH + audio mux"
                                    else -> "Direct"
                                }
                                Text(
                                    text = "${option.resolution} • ${option.formatLabel} ($muxTag)",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    DownloadFormat.MP3 -> {
                        Text(
                            text = stringResource(Res.string.desktop_audio_bitrate),
                            style = MaterialTheme.typography.labelLarge
                        )
                        DesktopPreferences.AUDIO_DOWNLOAD_BITRATES_KBPS.forEach { kbps ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedAudioBitrateKbps = kbps }
                            ) {
                                RadioButton(
                                    selected = selectedAudioBitrateKbps == kbps,
                                    onClick = { selectedAudioBitrateKbps = kbps }
                                )
                                Text(
                                    text = "MP3 • $kbps kbps",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        if (details.audioStreams.distinctBy { it.localeLabel.orEmpty() }.size > 1) {
                            HorizontalDivider()
                            Text(
                                text = stringResource(Res.string.desktop_audio_track),
                                style = MaterialTheme.typography.labelLarge
                            )
                            details.audioStreams.forEach { option ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedAudio = option }
                                ) {
                                    RadioButton(
                                        selected = selectedAudio?.id == option.id,
                                        onClick = { selectedAudio = option }
                                    )
                                    val lang = option.localeLabel?.let { " • $it" } ?: ""
                                    Text(
                                        text = "${option.formatLabel} ${option.bitrateKbps} kbps$lang",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        if (ffmpegAvailable || details.audioStreams.any { it.formatKey == "M4A" && it.bitrateKbps == 128 }) {
                            Text(
                                text = stringResource(Res.string.desktop_audio_bitrate),
                                style = MaterialTheme.typography.labelLarge
                            )
                            DesktopPreferences.AUDIO_DOWNLOAD_BITRATES_KBPS.forEach { kbps ->
                                val hasNativeMatch = details.audioStreams.any {
                                    it.formatKey == "M4A" && it.bitrateKbps == kbps
                                }
                                if (ffmpegAvailable || hasNativeMatch) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                useCustomAudioBitrate = true
                                                selectedAudioBitrateKbps = kbps
                                            }
                                    ) {
                                        RadioButton(
                                            selected = useCustomAudioBitrate && selectedAudioBitrateKbps == kbps,
                                            onClick = {
                                                useCustomAudioBitrate = true
                                                selectedAudioBitrateKbps = kbps
                                            }
                                        )
                                        val suffix = if (hasNativeMatch) "M4A (Direct)" else "M4A / AAC (FFmpeg)"
                                        Text(
                                            text = "$kbps kbps • $suffix",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                        Text(
                            text = stringResource(Res.string.desktop_audio_track),
                            style = MaterialTheme.typography.labelLarge
                        )
                        details.audioStreams.forEach { option ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        useCustomAudioBitrate = false
                                        selectedAudio = option
                                    }
                            ) {
                                RadioButton(
                                    selected = !useCustomAudioBitrate && selectedAudio?.id == option.id,
                                    onClick = {
                                        useCustomAudioBitrate = false
                                        selectedAudio = option
                                    }
                                )
                                val lang = option.localeLabel?.let { " • $it" } ?: ""
                                val size = formatBytes(option.approxSizeBytes).takeIf { it.isNotEmpty() }?.let { " (~$it)" } ?: ""
                                Text(
                                    text = "${option.formatLabel} ${option.bitrateKbps} kbps$lang$size",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = when (selectedMode) {
                    DownloadFormat.MP4 -> selectedVideo != null
                    DownloadFormat.MP3 -> selectedAudio != null && ffmpegAvailable
                    else -> selectedAudio != null
                },
                onClick = {
                    when (selectedMode) {
                        DownloadFormat.MP4 -> selectedVideo?.let(onDownloadMp4)
                        DownloadFormat.MP3 -> selectedAudio?.let { onDownloadMp3(it, selectedAudioBitrateKbps) }
                        else -> selectedAudio?.let {
                            val bitrate = if (useCustomAudioBitrate) selectedAudioBitrateKbps else null
                            onDownloadNativeAudio(it, bitrate)
                        }
                    }
                }
            ) {
                Text(stringResource(Res.string.desktop_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}
