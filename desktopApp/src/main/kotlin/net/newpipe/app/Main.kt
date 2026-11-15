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
import net.newpipe.app.platform.DesktopStreamMuxerRegistry

/**
 * Entry point for the Windows PC NewPipe Compose Desktop application.
 */
fun main() {
    DesktopStreamMuxerRegistry.muxer = DesktopStreamMuxer()

    application {
        var isWindowVisible by remember { mutableStateOf(true) }
        var appController by remember { mutableStateOf<DesktopAppController?>(null) }

        val appIcon = remember {
            runCatching {
                val stream = DesktopStreamMuxer::class.java.getResourceAsStream("/newpipe.png")
                    ?: return@runCatching null
                stream.use { BitmapPainter(ImageIO.read(it).toComposeImageBitmap()) }
            }.getOrNull()
        }

        val shutdownApp = {
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

        if (SystemTray.isSupported() && appIcon != null) {
            Tray(
                icon = appIcon,
                tooltip = "NewPipe Desktop",
                onAction = { isWindowVisible = true },
                menu = {
                    Item("Show NewPipe", onClick = { isWindowVisible = true })
                    Item(
                        "Play / Pause",
                        onClick = { appController?.togglePlayPause?.invoke() }
                    )
                    Item(
                        "Stop Playback",
                        onClick = { appController?.stopPlayback?.invoke() }
                    )
                    Separator()
                    Item("Quit NewPipe", onClick = shutdownApp)
                }
            )
        }

        val windowState = rememberWindowState(width = 1280.dp, height = 800.dp)

        Window(
            visible = isWindowVisible,
            onCloseRequest = handleWindowClose,
            title = "NewPipe Desktop",
            icon = appIcon,
            state = windowState
        ) {
            App(
                onCloseRequest = handleWindowClose,
                onAppReady = { controller -> appController = controller }
            )
        }
    }
}
