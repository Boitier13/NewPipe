/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.SystemTray
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import net.newpipe.app.platform.verifyDesktopRecoveryState
import kotlin.system.exitProcess
import net.newpipe.app.platform.DesktopStreamMuxerRegistry

/**
 * Entry point for the Windows PC NewPipe Compose Desktop application.
 */
fun main() {
    DesktopStreamMuxerRegistry.muxer = DesktopStreamMuxer()

    var controllerRef: DesktopAppController? = null
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        System.err.println("Fatal error on thread ${thread.name}: ${throwable.message}")
        throwable.printStackTrace(System.err)
        runCatching { controllerRef?.release?.invoke() }
        exitProcess(1)
    }

    try {
        try {
            verifyDesktopRecoveryState()
        } catch (failure: Exception) {
            JOptionPane.showMessageDialog(null, failure.message, "Récupération BoitierTube", JOptionPane.ERROR_MESSAGE)
            return
        }
        val windowTitle = if (System.getenv("BOITIERTUBE_PORTABLE_ROOT") != null)
            "BoitierTube — mode portable" else "BoitierTube"
        application {
            var isWindowVisible by remember { mutableStateOf(true) }
            var isExiting by remember { mutableStateOf(false) }
            var appController by remember { mutableStateOf<DesktopAppController?>(null) }

            val appIcon = remember {
                runCatching {
                    val stream = DesktopStreamMuxer::class.java.getResourceAsStream("/boitiertube.png")
                        ?: return@runCatching null
                    stream.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
                }.getOrNull()
            }

            val shutdownApp = {
                isExiting = true
                runCatching { appController?.release?.invoke() }
                exitApplication()
            }

            val handleWindowClose = {
                val controller = appController
                if (SystemTray.isSupported() && controller?.shouldMinimizeToTrayOnClose?.invoke() == true) {
                    isWindowVisible = false
                } else {
                    shutdownApp()
                }
            }

            if (!isExiting && SystemTray.isSupported() && appIcon != null) {
                Tray(
                    icon = appIcon,
                    tooltip = windowTitle,
                    onAction = { isWindowVisible = true },
                    menu = {
                        Item("Afficher BoitierTube", onClick = { isWindowVisible = true })
                        Item(
                            "Lecture / Pause",
                            onClick = { appController?.togglePlayPause?.invoke() }
                        )
                        Item(
                            "Arrêter la lecture",
                            onClick = { appController?.stopPlayback?.invoke() }
                        )
                        Separator()
                        Item("Quitter BoitierTube", onClick = shutdownApp)
                    }
                )
            }

            val windowState = rememberWindowState(width = 1280.dp, height = 800.dp)

            Window(
                visible = isWindowVisible,
                onCloseRequest = handleWindowClose,
                title = windowTitle,
                icon = appIcon,
                state = windowState
            ) {
                App(
                    onCloseRequest = handleWindowClose,
                    onAppReady = { controller ->
                        appController = controller
                        controllerRef = controller
                    }
                )
            }
        }
    } catch (t: Throwable) {
        System.err.println("BoitierTube terminated unexpectedly: ${t.message}")
        t.printStackTrace(System.err)
        runCatching { controllerRef?.release?.invoke() }
        exitProcess(1)
    }
}
