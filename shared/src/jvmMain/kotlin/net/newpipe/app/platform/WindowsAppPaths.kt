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

    private const val PROP_APP_RESOURCES_DIR = "compose.application.resources.dir"

    val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")

    private val isMac: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

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

    fun bundledResourcesDir(
        resourcesDirProperty: String? = System.getProperty(PROP_APP_RESOURCES_DIR),
        javaHomeProperty: String? = System.getProperty("java.home")
    ): File? {
        resourcesDirProperty?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            val dir = File(raw)
            if (dir.isDirectory) return dir
        }
        javaHomeProperty?.trim()?.takeIf { it.isNotEmpty() }?.let { home ->
            val appRoot = File(home).parentFile
            if (appRoot != null) {
                val candidates = listOf(
                    File(appRoot, "app/resources"),
                    File(appRoot, "lib/app/resources"),
                    File(appRoot, "resources")
                )
                candidates.firstOrNull { it.isDirectory }?.let { return it }
            }
        }
        return null
    }

    fun bundledVlcDirectory(
        bundledDir: File? = bundledResourcesDir(),
        windows: Boolean = isWindows,
        mac: Boolean = isMac
    ): File? {
        if (bundledDir == null || !bundledDir.isDirectory) return null
        return listOf(File(bundledDir, "vlc"), bundledDir)
            .firstOrNull { dir -> dir.isDirectory && hasVlcNativeLibraries(dir, windows, mac) }
    }

    fun bundledFfmpegExecutable(
        bundledDir: File? = bundledResourcesDir(),
        windows: Boolean = isWindows
    ): File? {
        if (bundledDir == null || !bundledDir.isDirectory) return null
        val exeName = if (windows) "ffmpeg.exe" else "ffmpeg"
        return listOf(
            File(bundledDir, "ffmpeg/$exeName"),
            File(bundledDir, exeName)
        ).firstOrNull { isValidExecutable(it, windows) }
    }

    internal fun hasVlcNativeLibraries(
        dir: File,
        windows: Boolean = isWindows,
        mac: Boolean = isMac
    ): Boolean {
        if (!dir.isDirectory) return false
        return when {
            windows -> File(dir, "libvlc.dll").isFile && File(dir, "libvlccore.dll").isFile
            mac -> File(dir, "libvlc.dylib").isFile
            else -> File(dir, "libvlc.so").isFile ||
                dir.listFiles()?.any { it.isFile && it.name.startsWith("libvlc.so.") } == true
        }
    }

    private fun isValidExecutable(file: File, windows: Boolean = isWindows): Boolean =
        file.isFile && (windows || file.canExecute())

    @JvmOverloads
    fun candidateVlcDirectories(
        customPath: String? = null,
        bundledDir: File? = bundledResourcesDir(),
        windows: Boolean = isWindows,
        mac: Boolean = isMac
    ): List<File> {
        val candidates = mutableListOf<File>()
        if (!customPath.isNullOrBlank()) {
            val custom = File(customPath.trim())
            candidates.add(if (custom.isFile) custom.parentFile else custom)
        }
        bundledVlcDirectory(bundledDir, windows = windows, mac = mac)?.let { candidates.add(it) }
        if (windows) {
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
        return candidates
            .filter { it.exists() && it.isDirectory }
            .distinctBy { runCatching { it.canonicalPath }.getOrElse { it.absolutePath } }
    }

    @JvmOverloads
    fun findFfmpegExecutable(
        customPath: String? = null,
        bundledDir: File? = bundledResourcesDir(),
        pathEnv: String? = System.getenv("PATH"),
        windows: Boolean = isWindows
    ): File? {
        val exeName = if (windows) "ffmpeg.exe" else "ffmpeg"
        if (!customPath.isNullOrBlank()) {
            val f = File(customPath.trim())
            if (isValidExecutable(f, windows)) return f
            if (f.isDirectory) {
                val exe = File(f, exeName)
                if (isValidExecutable(exe, windows)) return exe
            }
        }

        bundledFfmpegExecutable(bundledDir, windows)?.let { return it }

        pathEnv.orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { File(it.trim(), exeName) }
            .firstOrNull { isValidExecutable(it, windows) }
            ?.let { return it }

        if (windows) {
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
                .firstOrNull { isValidExecutable(it, windows = false) }
                ?.let { return it }
        }
        return null
    }
}
