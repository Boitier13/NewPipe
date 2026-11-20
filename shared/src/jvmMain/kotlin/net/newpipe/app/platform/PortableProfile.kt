/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.prefs.AbstractPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Explicit opt-in only. No profile discovery, automatic copy or fallback to installed data. */
object PortableProfile {
    fun dataDirectory(root: String? = System.getenv("BOITIERTUBE_PORTABLE_ROOT")): File? {
        if (root == null) return null
        require(root.isNotBlank()) { "Le chemin du profil portable est vide." }
        val directory = File(root)
        require(directory.isAbsolute && directory.isDirectory) { "Le dossier portable doit être un dossier absolu existant." }
        return File(directory.canonicalFile, "data").also {
            check(it.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) { "Le dossier data ne doit pas sortir du profil portable." }
            Files.createDirectories(it.toPath())
            check(it.isDirectory && it.canWrite()) { "Le profil portable est inaccessible : ${it.absolutePath}" }
        }
    }

    fun resolve(data: File, relative: String): File {
        val result = File(data, relative)
        check(result.canonicalFile.toPath().startsWith(data.canonicalFile.toPath())) {
            "Un chemin du profil portable pointe vers un emplacement extérieur : $relative"
        }
        return result
    }
}

/** Java Preferences API over an isolated file, retaining observable preference listeners.
 * Each mutation is checksummed, forced, then atomically published. No non-atomic fallback:
 * unsupported/read-only USB filesystems must fail instead of risking the last good settings.
 */
internal class PortableFilePreferences(private val file: File, private val beforePublish: () -> Unit = {}) : AbstractPreferences(null, ""), AutoCloseable {
    init { PortableProfile.resolve(file.parentFile, file.name) }
    private val lockChannel = FileChannel.open(PortableProfile.resolve(file.parentFile, ".portable-profile.lock").toPath(),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    private val sessionLock = try {
        checkNotNull(lockChannel.tryLock()) { "Ce profil portable est déjà ouvert dans une autre instance." }
    } catch (error: Exception) { lockChannel.close(); throw error }
    private var values: Map<String, String> = try { load() } catch (error: Exception) {
        sessionLock.release(); lockChannel.close(); throw error
    }

    override fun close() { sessionLock.release(); lockChannel.close() }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun load(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        check(file.length() in 66..(4L * 1024 * 1024)) { "Le fichier des paramètres portables est incomplet ou trop volumineux." }
        val bytes = file.readBytes()
        check(bytes[64] == '\n'.code.toByte()) { "Format de paramètres portables invalide." }
        val body = bytes.copyOfRange(65, bytes.size)
        check(bytes.copyOfRange(0, 64).decodeToString() == digest(body)) { "Les paramètres portables sont endommagés." }
        val parsed = Json.parseToJsonElement(body.decodeToString(throwOnInvalidSequence = true)) as JsonObject
        check(parsed.size <= 4096)
        return parsed.mapValues { (key, value) ->
            check(key.length in 1..80 && value.jsonPrimitive.isString && value.jsonPrimitive.content.length <= 8192)
            value.jsonPrimitive.content
        }
    }

    private fun persist(next: Map<String, String>) {
        val bytes = JsonObject(next.mapValues { JsonPrimitive(it.value) }).toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 4 * 1024 * 1024) { "Les paramètres portables dépassent la taille autorisée." }
        val temp = Files.createTempFile(file.parentFile.toPath(), ".preferences-", ".staged")
        FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap((digest(bytes) + "\n").toByteArray(Charsets.US_ASCII) + bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        beforePublish()
        Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        values = next
    }

    override fun putSpi(key: String, value: String) = persist(values + (key to value))
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) = persist(values - key)
    override fun keysSpi(): Array<String> = values.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = emptyArray()
    override fun childSpi(name: String): AbstractPreferences = error("Les sous-profils portables ne sont pas pris en charge.")
    override fun removeNodeSpi(): Unit = error("La suppression du profil portable est interdite.")
    override fun syncSpi() { values = load() }
    override fun flushSpi() = persist(values)
}
