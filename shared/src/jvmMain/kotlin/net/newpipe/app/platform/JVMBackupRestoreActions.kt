/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.newpipe.app.model.BackupFile
import net.newpipe.app.model.DataExportResult
import net.newpipe.app.model.DataImportResult
import net.newpipe.app.model.ErrorReport
import net.newpipe.app.model.SettingsFormat
import org.koin.core.annotation.Singleton

@Singleton(binds = [BackupRestoreActions::class])
class JVMBackupRestoreActions(
    private val settings: Settings,
    private val database: DesktopDatabaseGateway
) : BackupRestoreActions {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Composable
    override fun rememberFilePicker(
        file: BackupFile,
        onNoFileManager: () -> Unit,
        onPicked: (String) -> Unit
    ): () -> Unit = remember(file, onNoFileManager, onPicked) {
        {
            SwingUtilities.invokeLater {
                runCatching {
                    val isSave = file == BackupFile.DATA_EXPORT || file == BackupFile.SUBSCRIPTIONS_EXPORT
                    val ext = if (file == BackupFile.DATA_EXPORT || file == BackupFile.DATA_IMPORT) "zip" else "json"
                    val dialogTitle = when (file) {
                        BackupFile.DATA_EXPORT -> "Exporter une sauvegarde BoitierTube"
                        BackupFile.DATA_IMPORT -> "Importer une sauvegarde BoitierTube / NewPipe"
                        BackupFile.SUBSCRIPTIONS_EXPORT -> "Exporter les abonnements (JSON)"
                        BackupFile.SUBSCRIPTIONS_IMPORT -> "Importer les abonnements (CSV / JSON)"
                    }
                    val dialog = FileDialog(
                        null as Frame?,
                        dialogTitle,
                        if (isSave) FileDialog.SAVE else FileDialog.LOAD
                    ).apply {
                        directory = File(System.getProperty("user.home"), "Downloads").absolutePath
                        if (isSave) {
                            this.file = if (ext == "zip") "newpipe_backup.zip" else "newpipe_subscriptions.json"
                        }
                        setFilenameFilter { _, name ->
                            if (file == BackupFile.SUBSCRIPTIONS_IMPORT) {
                                name.endsWith(".json", ignoreCase = true) || name.endsWith(".csv", ignoreCase = true)
                            } else {
                                name.endsWith(".$ext", ignoreCase = true)
                            }
                        }
                        isVisible = true
                    }
                    val chosenDir = dialog.directory
                    val chosenFile = dialog.file
                    if (!chosenDir.isNullOrBlank() && !chosenFile.isNullOrBlank()) {
                        var fullPath = File(chosenDir, chosenFile).absolutePath
                        if (isSave && !fullPath.endsWith(".$ext", ignoreCase = true)) {
                            fullPath = "$fullPath.$ext"
                        }
                        onPicked(fullPath)
                    }
                }.onFailure {
                    onNoFileManager()
                }
            }
        }
    }

    override suspend fun exportData(file: String): DataExportResult {
        val result = database.exportDatabase(file)
        return result.fold(
            onSuccess = { DataExportResult.Success },
            onFailure = { err ->
                DataExportResult.Failed(
                    ErrorReport(message = backupErrorMessage(err, importing = false), open = {})
                )
            }
        )
    }

    override suspend fun importDatabase(file: String): DataImportResult {
        val result = database.importDatabase(file)
        return result.fold(
            onSuccess = {
                DataImportResult.Imported(
                    allFilesImported = true,
                    settingsFormat = null // Database and preferences have already committed together.
                )
            },
            onFailure = { err ->
                DataImportResult.Failed(
                    ErrorReport(message = backupErrorMessage(err, importing = true), open = {})
                )
            }
        )
    }

    override suspend fun importSettings(file: String, format: SettingsFormat) {
        // Preferences from newpipe.json are already restored during importDatabase(file)
    }

    override fun finishImport(file: String) = Unit

    override fun resetSettings() {
        settings.clear()
    }

    override fun exportSubscriptions(file: String) {
        scope.launch {
            database.exportSubscriptionsJson(file)
        }
    }

    override fun importSubscriptions(file: String) {
        scope.launch {
            database.importSubscriptionsJson(file)
        }
    }
}

/** Never expose raw SQLite, ZIP, filesystem or backend exception text to the user. */
internal fun backupErrorMessage(error: Throwable, importing: Boolean): String {
    Logger.e(error) { "Desktop backup operation failed" }
    return (error as? DesktopBackupException)?.message ?: if (importing) {
        "Impossible de restaurer la sauvegarde. Vérifiez le fichier, les droits d’accès et l’espace disque."
    } else {
        "Impossible de créer la sauvegarde. Vérifiez les droits d’écriture et l’espace disque."
    }
}
