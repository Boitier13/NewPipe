/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import java.io.File

/**
 * Resolves Windows and cross-platform PC directories and external tool executables (VLC, FFmpeg).
 */
object WindowsAppPaths {

    val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

    fun appDataDir(): File {
        val dir = if (isWindows) {
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                ?: File(System.getProperty("user.home"), "AppData/Roaming").absolutePath
            File(appData, "NewPipeDesktop")
        } else {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            if (os.contains("mac")) {
                File(System.getProperty("user.home"), "Library/Application Support/NewPipeDesktop")
            } else {
                File(System.getProperty("user.home"), ".local/share/NewPipeDesktop")
            }
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun databaseFile(): File = File(appDataDir(), "newpipe.db")

    fun downloadLogFile(): File = File(appDataDir(), "download_mux.log")

    fun defaultVideoDownloadDir(): File {
        val dir = File(System.getProperty("user.home"), "Downloads/NewPipe/Video")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun defaultAudioDownloadDir(): File {
        val dir = File(System.getProperty("user.home"), "Downloads/NewPipe/Audio")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun tempDownloadDir(): File {
        val dir = File(appDataDir(), "tmp_downloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun candidateVlcDirectories(customPath: String? = null): List<File> {
        val candidates = mutableListOf<File>()
        if (!customPath.isNullOrBlank()) {
            val custom = File(customPath.trim())
            candidates.add(if (custom.isFile) custom.parentFile else custom)
        }
        if (isWindows) {
            listOfNotNull(
                System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)"),
                "C:\\Program Files",
                "C:\\Program Files (x86)"
            ).distinct().forEach { base ->
                candidates.add(File(base, "VideoLAN\\VLC"))
            }
            System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { local ->
                candidates.add(File(local, "Programs\\VideoLAN\\VLC"))
            }
        } else {
            candidates.add(File("/usr/lib"))
            candidates.add(File("/usr/lib/x86_64-linux-gnu"))
            candidates.add(File("/Applications/VLC.app/Contents/MacOS/lib"))
        }
        return candidates.filter { it.exists() && it.isDirectory }
    }

    fun findFfmpegExecutable(customPath: String? = null): File? {
        if (!customPath.isNullOrBlank()) {
            val f = File(customPath.trim())
            if (f.isFile && f.canExecute()) return f
            if (f.isDirectory) {
                val exe = File(f, if (isWindows) "ffmpeg.exe" else "ffmpeg")
                if (exe.isFile && exe.canExecute()) return exe
            }
        }

        val exeName = if (isWindows) "ffmpeg.exe" else "ffmpeg"
        val pathEnv = System.getenv("PATH").orEmpty()
        pathEnv.split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { File(it.trim(), exeName) }
            .firstOrNull { it.isFile && it.canExecute() }
            ?.let { return it }

        if (isWindows) {
            val home = System.getProperty("user.home")
            val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
            val windowsCandidates = listOfNotNull(
                File("C:\\ffmpeg\\bin\\ffmpeg.exe"),
                File("C:\\Program Files\\ffmpeg\\bin\\ffmpeg.exe"),
                File("C:\\ProgramData\\chocolatey\\bin\\ffmpeg.exe"),
                if (localAppData.isNotBlank()) File(localAppData, "Microsoft\\WinGet\\Links\\ffmpeg.exe") else null,
                File(home, "scoop\\shims\\ffmpeg.exe")
            )
            windowsCandidates.firstOrNull { it.isFile }?.let { return it }
        } else {
            listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/homebrew/bin/ffmpeg")
                .map(::File)
                .firstOrNull { it.isFile && it.canExecute() }
                ?.let { return it }
        }
        return null
    }
}
