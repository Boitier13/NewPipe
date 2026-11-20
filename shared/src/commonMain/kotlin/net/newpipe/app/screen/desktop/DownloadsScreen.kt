/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.screen.desktop

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import net.newpipe.app.model.DownloadStatus
import net.newpipe.app.viewmodel.desktop.DownloadsViewModel
import newpipe.shared.generated.resources.Res
import newpipe.shared.generated.resources.desktop_clear_completed
import newpipe.shared.generated.resources.desktop_accessibility_play
import newpipe.shared.generated.resources.desktop_accessibility_cancel
import newpipe.shared.generated.resources.desktop_accessibility_remove
import newpipe.shared.generated.resources.desktop_ffmpeg_missing_hint
import newpipe.shared.generated.resources.desktop_nav_downloads
import newpipe.shared.generated.resources.desktop_no_downloads
import newpipe.shared.generated.resources.desktop_open_folder
import newpipe.shared.generated.resources.ic_close
import newpipe.shared.generated.resources.ic_delete
import newpipe.shared.generated.resources.ic_folder
import newpipe.shared.generated.resources.ic_play_arrow
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = koinViewModel()
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val ffmpegOk = viewModel.isFfmpegAvailable()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Res.string.desktop_nav_downloads),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (tasks.isNotEmpty()) {
                OutlinedButton(onClick = viewModel::clearCompleted) {
                    Text(stringResource(Res.string.desktop_clear_completed))
                }
            }
        }

        if (!ffmpegOk) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(Res.string.desktop_ffmpeg_missing_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (tasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.desktop_no_downloads),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(tasks, key = { it.id }) { task ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = task.format.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = task.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sub = buildString {
                                        append(task.uploader)
                                        task.resolutionLabel?.let { append(" • $it") }
                                        task.statusMessage?.let { append(" • $it") }
                                        if (task.status == DownloadStatus.COMPLETED && task.totalBytes > 0L) {
                                            append(" (${formatBytes(task.totalBytes)})")
                                        }
                                    }
                                    Text(
                                        text = sub,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (task.status == DownloadStatus.FAILED) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (task.status == DownloadStatus.COMPLETED) {
                                    IconButton(onClick = { viewModel.playDownloadedTask(task) }) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_play_arrow),
                                            contentDescription = stringResource(Res.string.desktop_accessibility_play)
                                        )
                                    }
                                }

                                IconButton(onClick = { viewModel.openDownloadFolder(task.outputPath) }) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_folder),
                                        contentDescription = stringResource(Res.string.desktop_open_folder)
                                    )
                                }

                                if (task.status == DownloadStatus.QUEUED ||
                                    task.status == DownloadStatus.DOWNLOADING ||
                                    task.status == DownloadStatus.MUXING ||
                                    task.status == DownloadStatus.TRANSCODING
                                ) {
                                    IconButton(onClick = { viewModel.cancelTask(task.id) }) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_close),
                                            contentDescription = stringResource(Res.string.desktop_accessibility_cancel)
                                        )
                                    }
                                } else {
                                    IconButton(onClick = { viewModel.removeTask(task.id) }) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_delete),
                                            contentDescription = stringResource(Res.string.desktop_accessibility_remove)
                                        )
                                    }
                                }
                            }

                            if (task.status == DownloadStatus.DOWNLOADING ||
                                task.status == DownloadStatus.MUXING ||
                                task.status == DownloadStatus.TRANSCODING ||
                                task.status == DownloadStatus.QUEUED
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    val barModifier = Modifier
                                        .weight(1f)
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                    if (task.progress > 0f) {
                                        LinearProgressIndicator(
                                            progress = { task.progress.coerceIn(0f, 1f) },
                                            modifier = barModifier
                                        )
                                    } else {
                                        LinearProgressIndicator(modifier = barModifier)
                                    }
                                    val pct = (task.progress.coerceIn(0f, 1f) * 100f).roundToInt()
                                    val bytesInfo = buildString {
                                        if (task.progress > 0f) {
                                            append("$pct%")
                                        }
                                        if (task.downloadedBytes > 0L) {
                                            if (isNotEmpty()) append(" • ")
                                            append(formatBytes(task.downloadedBytes))
                                            if (task.totalBytes > task.downloadedBytes) {
                                                append(" / ${formatBytes(task.totalBytes)}")
                                            }
                                        }
                                    }
                                    if (bytesInfo.isNotEmpty()) {
                                        Text(
                                            text = bytesInfo,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Medium
                                        )
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
