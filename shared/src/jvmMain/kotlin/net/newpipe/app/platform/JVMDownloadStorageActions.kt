/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import org.koin.core.annotation.Singleton

@Singleton(binds = [DownloadStorageActions::class])
class JVMDownloadStorageActions : DownloadStorageActions {

    override val isAvailable = true

    override val useStorageAccessFramework = false

    override val isStorageAccessFrameworkConfigurable = false

    override fun defaultDownloadFolders() = DownloadFolders(
        video = WindowsAppPaths.defaultVideoDownloadDir().absolutePath,
        audio = WindowsAppPaths.defaultAudioDownloadDir().absolutePath
    )

    override fun displayFolder(storedValue: String): String? =
        storedValue.takeIf { it.isNotBlank() }

    @Composable
    override fun rememberFolderPicker(onResult: (FolderPickResult) -> Unit): () -> Unit = remember(onResult) {
        {
            SwingUtilities.invokeLater {
                runCatching {
                    val chooser = JFileChooser(WindowsAppPaths.defaultVideoDownloadDir()).apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        isAcceptAllFileFilterUsed = false
                    }
                    val res = chooser.showOpenDialog(null)
                    if (res == JFileChooser.APPROVE_OPTION) {
                        val selected: File? = chooser.selectedFile
                        if (selected == null || !selected.exists() || !selected.isDirectory) {
                            onResult(FolderPickResult.Failed(DownloadFolderError.INVALID_DIRECTORY))
                        } else if (!selected.canWrite()) {
                            onResult(FolderPickResult.Failed(DownloadFolderError.NOT_WRITABLE))
                        } else {
                            onResult(FolderPickResult.Picked(selected.absolutePath))
                        }
                    }
                }.onFailure {
                    onResult(FolderPickResult.Failed(DownloadFolderError.NO_FILE_MANAGER))
                }
            }
        }
    }
}
