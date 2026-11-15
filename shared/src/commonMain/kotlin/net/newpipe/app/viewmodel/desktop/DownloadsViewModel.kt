/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.viewmodel.desktop

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import net.newpipe.app.model.DownloadFormat
import net.newpipe.app.model.DownloadTaskItem
import net.newpipe.app.platform.DownloadController
import net.newpipe.app.platform.MediaPlayerController
import org.koin.core.annotation.KoinViewModel

@KoinViewModel
class DownloadsViewModel(
    private val downloadController: DownloadController,
    private val mediaPlayerController: MediaPlayerController
) : ViewModel() {

    val tasks: StateFlow<List<DownloadTaskItem>> = downloadController.tasks

    fun isFfmpegAvailable(): Boolean = downloadController.isFfmpegAvailable()

    fun cancelTask(taskId: String) = downloadController.cancelTask(taskId)

    fun removeTask(taskId: String) = downloadController.removeTask(taskId)

    fun clearCompleted() = downloadController.clearCompleted()

    fun openDownloadFolder(path: String) = downloadController.openDownloadFolder(path)

    fun playDownloadedTask(task: DownloadTaskItem) {
        val isAudioOnly = task.format != DownloadFormat.MP4
        mediaPlayerController.playLocalFile(task.outputPath, task.title, isAudioOnly)
    }
}
