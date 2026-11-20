/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A user-facing, French error. Technical causes are retained for diagnostics, not displayed. */
internal class DesktopBackupException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Synchronous transaction coordinator. The caller MUST hold the database mutex throughout.
 * All staging and recovery files are on the database volume. Neither archive paths nor names
 * are used as extraction destinations. Hooks exist only for deterministic failure tests.
 */
internal class DesktopBackupStore(
    private val databaseFile: File,
    private val settings: Settings,
    private val snapshotDatabase: (File) -> Unit,
    private val prepareDatabase: (File) -> Unit,
    private val closeDatabase: () -> Unit,
    private val reopenDatabase: () -> Unit,
    private val checkpoint: (String) -> Unit = {},
    private val limits: Limits = Limits()
) {
    internal data class Limits(
        val archiveBytes: Long = 512L * 1024 * 1024,
        val databaseBytes: Long = 512L * 1024 * 1024,
        val settingsBytes: Long = 4L * 1024 * 1024,
        val reserveBytes: Long = 32L * 1024 * 1024
    )

    private val json = Json
    private val sidecars = listOf("-wal", "-shm", "-journal")

    fun importArchive(source: File) {
        requireBackup(source.isFile && source.canRead(), "Le fichier de sauvegarde est introuvable ou illisible.")
        requireBackup(source.length() in 1..limits.archiveBytes, "La sauvegarde est vide ou dépasse la taille autorisée.")
        val work = createWorkDirectory()
        var preserveRecovery = false
        try {
            val candidate = File(work, "candidate.db")
            val importedSettings = extractAndValidate(source, candidate, work)
            // All validation, including app-specific schema/query checks, precedes any live change.
            validateSqlite(candidate)
            prepareDatabase(candidate)
            validateSqlite(candidate)
            force(candidate)
            val originalSettings = readSettings()
            val original = File(work, "previous.db")
            checkSpace(work, databaseFootprint() * 2 + candidate.length())
            snapshotDatabase(original)
            validateSqlite(original)
            force(original)
            // Durable, typed preferences rescue copy, never stored in the public ZIP format.
            val preferencesRescue = File(work, "previous-settings.json")
            preferencesRescue.writeText(JsonObject(originalSettings.mapValues { (_, value) ->
                JsonObject(mapOf("type" to JsonPrimitive(value.kind.name), "value" to value.jsonValue()))
            }).toString())
            force(preferencesRescue)
            writeRecoveryState(work, "pending")
            checkpoint("before-close")
            val movedOriginals = mutableListOf<Pair<File, File>>()
            var candidateInstalled = false
            try {
                // Mark before close: even a close error must take the recovery/reopen path.
                preserveRecovery = true // Also keep rescue files on fatal JVM errors/process interruption.
                closeDatabase()
                val originals = File(work, "original-files").apply { check(mkdir()) }
                (listOf(databaseFile) + sidecars.map { File(databaseFile.path + it) }).forEach { file ->
                    if (file.exists()) {
                        val saved = File(originals, file.name)
                        move(file, saved, replace = false)
                        movedOriginals.add(saved to file)
                    }
                }
                checkpoint("before-replace")
                move(candidate, databaseFile, replace = false)
                candidateInstalled = true
                checkpoint("after-replace")
                applySettings(importedSettings)
                checkpoint("after-settings")
                reopenDatabase()
                checkpoint("after-reopen")
                writeRecoveryState(work, "completed")
                preserveRecovery = false
            } catch (failure: Exception) {
                val recoveryErrors = mutableListOf<Exception>()
                fun attempt(action: () -> Unit) {
                    try { action() } catch (error: Exception) { recoveryErrors.add(error) }
                }
                var closed = false
                attempt { closeDatabase(); closed = true }
                if (closed) {
                    try {
                        // Never consume the rescue copy: retain it if a subsequent step fails.
                        val rollback = File(work, "rollback.db")
                        checkpoint("rollback-copy")
                        Files.copy(original.toPath(), rollback.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        force(rollback)
                        sidecars.forEach { Files.deleteIfExists(File(databaseFile.path + it).toPath()) }
                        move(rollback, databaseFile, replace = true)
                    } catch (copyFailure: Exception) {
                        // A full disk may prevent the rollback COPY. The archived originals
                        // can be renamed back without allocating a second database file.
                        try {
                            if (candidateInstalled) {
                                sidecars.forEach { Files.deleteIfExists(File(databaseFile.path + it).toPath()) }
                                Files.deleteIfExists(databaseFile.toPath())
                            }
                            movedOriginals.forEach { (saved, live) -> move(saved, live, replace = true) }
                            validateSqlite(databaseFile)
                        } catch (renameFailure: Exception) {
                            copyFailure.addSuppressed(renameFailure)
                            recoveryErrors.add(copyFailure)
                        }
                    }
                }
                attempt { applySettings(originalSettings, restoring = true) }
                attempt { reopenDatabase() }
                if (recoveryErrors.isNotEmpty()) {
                    preserveRecovery = true
                    val error = DesktopBackupException(
                        "La restauration a échoué et le retour arrière n’a pas pu être terminé. " +
                            "Ne supprimez pas les copies de secours conservées dans : ${work.absolutePath}", failure
                    )
                    recoveryErrors.forEach(error::addSuppressed)
                    throw error
                }
                writeRecoveryState(work, "completed")
                preserveRecovery = false
                throw DesktopBackupException(
                    "La restauration a échoué. La base et les paramètres précédents ont été rétablis.", failure
                )
            }
        } catch (error: DesktopBackupException) {
            throw error
        } catch (error: Exception) {
            throw DesktopBackupException(
                "Impossible de lire ou de préparer la sauvegarde. Vérifiez le fichier, les droits d’accès et l’espace disque. Les données existantes n’ont pas été remplacées.", error
            )
        } finally {
            if (!preserveRecovery) cleanup(work)
        }
    }

    fun exportArchive(destination: File) {
        requireBackup(!destination.exists(), "Un fichier existe déjà à cet emplacement. Choisissez un autre nom de sauvegarde.")
        val parent = destination.absoluteFile.parentFile
        requireBackup(parent.isDirectory && parent.canWrite(), "Le dossier de destination est introuvable ou inaccessible.")
        val work = createWorkDirectory()
        var partial: File? = null
        try {
            val snapshot = File(work, "snapshot.db")
            checkSpace(work, databaseFootprint() * 2)
            val preferences = JsonObject(readSettings().mapValues { it.value.jsonValue() }).toString()
            requireBackup(preferences.toByteArray(Charsets.UTF_8).size <= limits.settingsBytes, "Les paramètres dépassent la taille autorisée.")
            // VACUUM INTO includes committed WAL data; the live database stays open even on failure.
            snapshotDatabase(snapshot)
            validateSqlite(snapshot)
            requireBackup(snapshot.length() <= limits.databaseBytes, "La base dépasse la taille autorisée pour une sauvegarde.")
            checkSpace(parent, snapshot.length() + limits.settingsBytes)
            partial = Files.createTempFile(parent.toPath(), ".boitiertube-", ".partial").toFile()
            ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("newpipe.db"))
                snapshot.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                checkpoint("export-database-written")
                zip.putNextEntry(ZipEntry("newpipe.json"))
                zip.write(preferences.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            requireBackup(partial.length() <= limits.archiveBytes, "La sauvegarde dépasse la taille autorisée.")
            force(partial)
            checkpoint("export-before-publish")
            // A hard link publishes the completed file atomically AND refuses an existing target.
            // Unlike ATOMIC_MOVE, it cannot silently replace a concurrent destination on Unix.
            try {
                Files.createLink(destination.toPath(), partial.toPath())
            } catch (exists: java.nio.file.FileAlreadyExistsException) {
                throw DesktopBackupException("Un fichier existe déjà à cet emplacement. Il n’a pas été remplacé.", exists)
            } catch (unsupported: UnsupportedOperationException) {
                Files.move(partial.toPath(), destination.toPath()) // no REPLACE_EXISTING
            } catch (unsupported: java.nio.file.FileSystemException) {
                if (destination.exists()) throw DesktopBackupException("Le fichier de destination n’a pas été remplacé.", unsupported)
                Files.move(partial.toPath(), destination.toPath()) // filesystem without hard links
            }
        } catch (error: DesktopBackupException) {
            throw error
        } catch (error: Exception) {
            throw DesktopBackupException(
                "Impossible de créer la sauvegarde. Vérifiez les droits d’écriture et l’espace disque. La base reste disponible.", error
            )
        } finally {
            partial?.let(::cleanup)
            cleanup(work)
        }
    }

    private fun extractAndValidate(source: File, candidate: File, work: File): Map<String, PreferenceValue> {
        val settingsFile = File(work, "candidate-settings.json")
        ZipFile(source).use { zip ->
            requireBackup(zip.size() == 2, "La sauvegarde doit contenir les deux fichiers attendus, sans fichier supplémentaire.")
            val entries = zip.entries().asSequence().toList()
            requireBackup(entries.size == 2 && entries.map { it.name }.toSet() == setOf("newpipe.db", "newpipe.json"),
                "La sauvegarde doit contenir uniquement newpipe.db et newpipe.json, sans doublon ni sous-dossier.")
            var total = 0L
            for (entry in entries) {
                val limit = if (entry.name == "newpipe.db") limits.databaseBytes else limits.settingsBytes
                requireBackup(!entry.isDirectory && entry.size in 1..limit,
                    "Un fichier de la sauvegarde est vide, incomplet ou trop volumineux.")
                total += entry.size
            }
            checkSpace(work, total + databaseFootprint() * 2)
            entries.forEach { entry ->
                val output = if (entry.name == "newpipe.db") candidate else settingsFile
                val limit = if (entry.name == "newpipe.db") limits.databaseBytes else limits.settingsBytes
                var count = 0L
                val crc = CRC32()
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(output.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            requireBackup(count <= limit && count <= entry.size, "La sauvegarde dépasse la taille autorisée pendant sa lecture.")
                            crc.update(buffer, 0, read)
                            checkpoint("extract-write")
                            out.write(buffer, 0, read)
                        }
                    }
                }
                requireBackup(count == entry.size && crc.value == entry.crc,
                    "La sauvegarde est endommagée ou incomplète. Aucun fichier n’a été remplacé.")
            }
        }
        val text = settingsFile.readBytes().decodeToString(throwOnInvalidSequence = true)
        return parseSettings(text)
    }

    private fun parseSettings(text: String): Map<String, PreferenceValue> {
        val objectValue = json.parseToJsonElement(text) as? JsonObject
            ?: throw DesktopBackupException("Les paramètres de la sauvegarde sont invalides : un objet JSON est attendu.")
        requireBackup(objectValue.size <= 4096, "La sauvegarde contient trop de paramètres.")
        rejectDuplicateKeys(text)
        return objectValue.mapValues { (key, element) ->
            requireBackup(key.isNotEmpty() && key.length <= 80, "Un nom de paramètre est invalide ou trop long.")
            val value = element as? JsonPrimitive
                ?: throw DesktopBackupException("Un paramètre utilise un format non pris en charge. Aucun paramètre n’a été importé.")
            val parsed = when {
                value.isString -> PreferenceValue(Kind.STRING, value.content)
                value.booleanOrNull != null -> PreferenceValue(Kind.BOOLEAN, value.content)
                value.longOrNull != null -> PreferenceValue(
                    if (value.longOrNull!! in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) Kind.INT else Kind.LONG, value.content
                )
                value.doubleOrNull?.isFinite() == true -> PreferenceValue(Kind.DOUBLE, value.content)
                else -> throw DesktopBackupException("Une valeur de paramètre est invalide. Aucun paramètre n’a été importé.")
            }
            requireBackup(parsed.value.length <= 8192, "Une valeur de paramètre est trop longue.")
            parsed
        }
    }

    /** JsonObject keeps only the last duplicate; reject ambiguity instead of silently losing values. */
    private fun rejectDuplicateKeys(text: String) {
        val keys = mutableSetOf<String>()
        var depth = 0
        var expectingKey = true
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '{', '[' -> depth++
                '}', ']' -> depth--
                ':' -> if (depth == 1) expectingKey = false
                ',' -> if (depth == 1) expectingKey = true
                '"' -> {
                    val start = index++
                    while (index < text.length) {
                        if (text[index] == '\\') index++
                        else if (text[index] == '"') break
                        index++
                    }
                    if (depth == 1 && expectingKey) {
                        val key = (json.parseToJsonElement(text.substring(start, index + 1)) as JsonPrimitive).content
                        requireBackup(keys.add(key), "Un paramètre apparaît plusieurs fois dans la sauvegarde. L’importation a été annulée.")
                    }
                }
            }
            index++
        }
    }

    private enum class Kind { STRING, BOOLEAN, INT, LONG, FLOAT, DOUBLE }
    private data class PreferenceValue(val kind: Kind, val value: String) {
        fun jsonValue(): JsonPrimitive = when (kind) {
            Kind.STRING -> JsonPrimitive(value)
            Kind.BOOLEAN -> JsonPrimitive(value.toBooleanStrict())
            Kind.INT, Kind.LONG -> JsonPrimitive(value.toLong())
            Kind.FLOAT, Kind.DOUBLE -> JsonPrimitive(value.toDouble())
        }
        fun matches(settings: Settings, key: String): Boolean = when (kind) {
            Kind.STRING -> settings.getStringOrNull(key) == value
            Kind.BOOLEAN -> settings.getBooleanOrNull(key) == value.toBooleanStrict()
            Kind.INT -> settings.getIntOrNull(key) == value.toInt()
            Kind.LONG -> settings.getLongOrNull(key) == value.toLong()
            Kind.FLOAT -> settings.getFloatOrNull(key) == value.toFloat()
            Kind.DOUBLE -> settings.getDoubleOrNull(key) == value.toDouble()
        }
        fun put(settings: Settings, key: String) = when (kind) {
            Kind.STRING -> settings.putString(key, value)
            Kind.BOOLEAN -> settings.putBoolean(key, value.toBooleanStrict())
            Kind.INT -> settings.putInt(key, value.toInt())
            Kind.LONG -> settings.putLong(key, value.toLong())
            Kind.FLOAT -> settings.putFloat(key, value.toFloat())
            Kind.DOUBLE -> settings.putDouble(key, value.toDouble())
        }
    }

    private fun readSettings(): Map<String, PreferenceValue> = settings.keys.associateWith { key ->
        // PreferencesSettings stores every value as a string. MapSettings and other backends
        // have strict types: do not replace a failed read with the old export's empty string.
        fun <T> read(get: () -> T?): T? = try { get() } catch (_: ClassCastException) { null }
        read { settings.getStringOrNull(key) }?.let { return@associateWith PreferenceValue(Kind.STRING, it) }
        read { settings.getBooleanOrNull(key) }?.let { return@associateWith PreferenceValue(Kind.BOOLEAN, it.toString()) }
        read { settings.getIntOrNull(key) }?.let { return@associateWith PreferenceValue(Kind.INT, it.toString()) }
        read { settings.getLongOrNull(key) }?.let { return@associateWith PreferenceValue(Kind.LONG, it.toString()) }
        read { settings.getFloatOrNull(key) }?.let { return@associateWith PreferenceValue(Kind.FLOAT, it.toString()) }
        read { settings.getDoubleOrNull(key) }?.let { return@associateWith PreferenceValue(Kind.DOUBLE, it.toString()) }
        throw DesktopBackupException("Impossible de lire tous les paramètres actuels. L’opération a été annulée.")
    }

    private fun applySettings(values: Map<String, PreferenceValue>, restoring: Boolean = false) {
        settings.clear()
        values.forEach { (key, value) ->
            if (!restoring) checkpoint("apply-setting")
            value.put(settings, key)
        }
        (settings as? java.io.Flushable)?.flush()
        requireBackup(settings.keys == values.keys && values.all { (key, value) -> value.matches(settings, key) },
            "La vérification des paramètres enregistrés a échoué.")
    }

    private fun writeRecoveryState(work: File, state: String) {
        val marker = File(work, "recovery-state.txt")
        marker.writeText(state, Charsets.UTF_8)
        force(marker)
    }

    companion object {
        /** Fail closed before initial database creation. Never guess which generation to keep. */
        internal fun assertNoPendingRecovery(databaseFile: File) {
            val parent = databaseFile.absoluteFile.parentFile
            val entries = parent.listFiles()
            if (parent.exists() && entries == null) {
                throw DesktopBackupException("Impossible de vérifier les copies de récupération. Vérifiez les droits d’accès.")
            }
            entries.orEmpty().filter { it.isDirectory && it.name.startsWith(".boitiertube-backup-") }.forEach { work ->
                val marker = File(work, "recovery-state.txt")
                val state = if (marker.isFile) runCatching { marker.readText(Charsets.UTF_8) }.getOrNull() else null
                val hasRescue = File(work, "previous.db").exists() && File(work, "previous-settings.json").exists()
                if ((marker.exists() || hasRescue) && state != "completed") {
                    throw DesktopBackupException(
                        "Une restauration interrompue nécessite une récupération avant de rouvrir la base. " +
                            "Ne supprimez et ne déplacez aucun fichier. Copies conservées dans : ${work.absolutePath}"
                    )
                }
            }
        }
    }

    private fun createWorkDirectory(): File = try {
        Files.createTempDirectory(databaseFile.absoluteFile.parentFile.toPath(), ".boitiertube-backup-").toFile()
    } catch (error: Exception) {
        throw DesktopBackupException("Impossible de créer le dossier temporaire. Vérifiez l’espace disque et les droits d’accès.", error)
    }

    private fun databaseFootprint(): Long = (listOf(databaseFile) + sidecars.map { File(databaseFile.path + it) }).sumOf { it.length() }

    private fun checkSpace(folder: File, required: Long) {
        requireBackup(folder.usableSpace >= required + limits.reserveBytes,
            "L’espace disque disponible est insuffisant pour protéger les données et terminer la sauvegarde.")
    }

    private fun force(file: File) {
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
    }

    private fun move(source: File, target: File, replace: Boolean) {
        if (replace) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } else {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun validateSqlite(file: File) {
        requireBackup(file.isFile && file.length() >= 100, "La base de la sauvegarde est vide ou incomplète.")
        BundledSQLiteDriver().open(file.absolutePath).use { conn ->
            conn.execSQL("PRAGMA trusted_schema = OFF")
            conn.execSQL("PRAGMA query_only = ON")
            conn.prepare("PRAGMA integrity_check").use { statement ->
                requireBackup(statement.step() && statement.getText(0) == "ok" && !statement.step(),
                    "La base de la sauvegarde est endommagée.")
            }
            conn.prepare("PRAGMA foreign_key_check").use { statement ->
                requireBackup(!statement.step(), "La base de la sauvegarde contient des données incohérentes.")
            }
        }
    }

    /** Cleanup must not turn an already committed operation into a reported failure. */
    private fun cleanup(file: File) {
        try {
            if (file.exists() && !file.deleteRecursively()) {
                Logger.w { "Backup temporary files could not be removed: ${file.absolutePath}" }
            }
        } catch (error: Exception) {
            Logger.w(error) { "Backup temporary file cleanup failed" }
        }
    }

    private fun requireBackup(condition: Boolean, message: String) {
        if (!condition) throw DesktopBackupException(message)
    }
}

/** Called before showing desktop UI; also checked by the database gateway before initial open. */
fun verifyDesktopRecoveryState() {
    DesktopBackupStore.assertNoPendingRecovery(WindowsAppPaths.databaseFile())
}
