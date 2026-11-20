/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.newpipe.app.platform

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Uses FOLDERID_Documents, not a localized folder name or USERPROFILE assumption. */
internal object DownloadDestinations {
    interface Shell : StdCallLibrary {
        fun SHGetKnownFolderPath(id: Pointer, flags: Int, token: Pointer?, result: PointerByReference): Int
    }
    interface Ole : StdCallLibrary { fun CoTaskMemFree(pointer: Pointer) }

    fun documentsDirectory(): File {
        if (!WindowsAppPaths.isWindows) return File(System.getProperty("user.home"), "Documents")
        val guid = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xFDD39AD0.toInt()).putShort(0x238F.toShort()).putShort(0x46AF.toShort())
            .put(byteArrayOf(0xAD.toByte(), 0xB4.toByte(), 0x6C, 0x85.toByte(), 0x48, 0x03, 0x69, 0xC7.toByte())).array()
        val id = Memory(16).apply { write(0, guid, 0, 16) }
        val result = PointerByReference()
        val status = Native.load("shell32", Shell::class.java).SHGetKnownFolderPath(id, 0, null, result)
        check(status == 0 && result.value != null) { "Impossible de trouver le dossier Documents de Windows. Choisissez un dossier dans les paramètres de téléchargement." }
        return try { File(result.value.getWideString(0)).also { check(it.isAbsolute) } }
        finally { Native.load("ole32", Ole::class.java).CoTaskMemFree(result.value) }
    }

    fun folder(documents: File, video: Boolean, customPath: String = ""): File {
        val target = if (customPath.isNotBlank()) File(customPath)
            else File(documents, "BoitierTube/${if (video) "Vidéos" else "Audios"}")
        require(target.isAbsolute) { "Le dossier de téléchargement doit être un chemin absolu." }
        Files.createDirectories(target.toPath())
        check(target.isDirectory && target.canWrite()) { "Le dossier de téléchargement est inaccessible : ${target.absolutePath}" }
        return target
    }
}
