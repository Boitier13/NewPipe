/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.russhwolf.settings.Settings
import net.newpipe.app.di.KoinApp
import net.newpipe.app.model.DownloadStatus
import net.newpipe.app.model.PlayerPlaybackMode
import net.newpipe.app.navigation.Destination
import net.newpipe.app.navigation.NavDisplay
import net.newpipe.app.navigation.Navigator
import net.newpipe.app.navigation.navModule
import net.newpipe.app.platform.DataSaverController
import net.newpipe.app.platform.DownloadController
import net.newpipe.app.platform.MediaPlayerController
import net.newpipe.app.preferences.DesktopPreferences
import net.newpipe.app.screen.desktop.ActiveDownloadBanner
import net.newpipe.app.screen.desktop.MiniPlayerBar
import net.newpipe.app.theme.AppTheme
import newpipe.shared.generated.resources.Res
import newpipe.shared.generated.resources.desktop_data_saver
import newpipe.shared.generated.resources.desktop_nav_downloads
import newpipe.shared.generated.resources.desktop_nav_explore
import newpipe.shared.generated.resources.desktop_nav_history
import newpipe.shared.generated.resources.desktop_nav_search
import newpipe.shared.generated.resources.desktop_nav_settings
import newpipe.shared.generated.resources.desktop_nav_subscriptions
import newpipe.shared.generated.resources.ic_data_saver
import newpipe.shared.generated.resources.ic_file_download
import newpipe.shared.generated.resources.ic_history
import newpipe.shared.generated.resources.ic_home
import newpipe.shared.generated.resources.ic_info_outline
import newpipe.shared.generated.resources.ic_newpipe_logo
import newpipe.shared.generated.resources.ic_search
import newpipe.shared.generated.resources.ic_settings
import newpipe.shared.generated.resources.ic_subscriptions
import newpipe.shared.generated.resources.desktop_nav_about
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import org.koin.plugin.module.dsl.koinConfiguration

/**
 * Window-level lifecycle and playback bridge exposed to the desktop window/tray host.
 */
class DesktopAppController(
    val togglePlayPause: () -> Unit,
    val stopPlayback: () -> Unit,
    val release: () -> Unit,
    val shouldMinimizeToTrayOnClose: () -> Boolean
)

/**
 * Entry point for the multiplatform compose desktop application
 */
@Composable
fun App(
    startDestination: Destination = Destination.Home,
    onCloseRequest: () -> Unit,
    onAppReady: (DesktopAppController) -> Unit = {},
    withKoin: @Composable () -> Unit = {}
) {
    KoinApplication(
        configuration = koinConfiguration<KoinApp>(
            appDeclaration = {
                modules(navModule())
            }
        )
    ) {
        AppTheme {
            DesktopShell(
                startDestination = startDestination,
                onCloseRequest = onCloseRequest,
                onAppReady = onAppReady
            )
            withKoin()
        }
    }
}

@Composable
private fun DesktopShell(
    startDestination: Destination,
    onCloseRequest: () -> Unit,
    onAppReady: (DesktopAppController) -> Unit
) {
    val navigator: Navigator = koinInject {
        parametersOf(startDestination, onCloseRequest)
    }
    val mediaPlayerController: MediaPlayerController = koinInject()
    val downloadController: DownloadController = koinInject()
    val dataSaverController: DataSaverController = koinInject()
    val settings: Settings = koinInject()

    LaunchedEffect(mediaPlayerController, settings) {
        onAppReady(
            DesktopAppController(
                togglePlayPause = mediaPlayerController::togglePlayPause,
                stopPlayback = mediaPlayerController::stop,
                release = mediaPlayerController::release,
                shouldMinimizeToTrayOnClose = {
                    val state = mediaPlayerController.state.value
                    val minimizeOnAudio = settings.getBoolean(
                        DesktopPreferences.KEY_MINIMIZE_TO_TRAY_ON_AUDIO,
                        DesktopPreferences.DEFAULT_MINIMIZE_TO_TRAY_ON_AUDIO
                    )
                    minimizeOnAudio &&
                        state.mode == PlayerPlaybackMode.BACKGROUND_AUDIO &&
                        state.isPlaying
                }
            )
        )
    }

    val playerState by mediaPlayerController.state.collectAsStateWithLifecycle()
    val downloads by downloadController.tasks.collectAsStateWithLifecycle()
    val isDataSaver by dataSaverController.isDataSaverEnabled.collectAsStateWithLifecycle()

    val activeDownloads = downloads.filter {
        it.status == DownloadStatus.QUEUED ||
            it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.MUXING ||
            it.status == DownloadStatus.TRANSCODING
    }
    val activeDownloadCount = activeDownloads.size

    val currentDest = navigator.backstack.lastOrNull()
    val showMiniPlayer = (playerState.currentVideo != null || playerState.localTitle != null) &&
        !playerState.isFullscreen &&
        (playerState.mode == PlayerPlaybackMode.BACKGROUND_AUDIO ||
            currentDest !is Destination.VideoDetail ||
            currentDest.url != playerState.currentVideo?.url)

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (!playerState.isFullscreen) {
                NavigationRail(
                    header = {
                        Image(
                            painter = painterResource(Res.drawable.ic_newpipe_logo),
                            contentDescription = "BoitierTube",
                            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp).size(36.dp)
                        )
                    },
                    modifier = Modifier.fillMaxHeight()
                ) {
                    NavigationRailItem(
                        selected = currentDest == Destination.Home,
                        onClick = { navigator.navigateRoot(Destination.Home) },
                        icon = { Icon(painterResource(Res.drawable.ic_home), null) },
                        label = { Text(stringResource(Res.string.desktop_nav_explore)) }
                    )
                    NavigationRailItem(
                        selected = currentDest is Destination.Search,
                        onClick = { navigator.navigateRoot(Destination.Search()) },
                        icon = { Icon(painterResource(Res.drawable.ic_search), null) },
                        label = { Text(stringResource(Res.string.desktop_nav_search)) }
                    )
                    NavigationRailItem(
                        selected = currentDest == Destination.Subscriptions,
                        onClick = { navigator.navigateRoot(Destination.Subscriptions) },
                        icon = { Icon(painterResource(Res.drawable.ic_subscriptions), null) },
                        label = { Text(stringResource(Res.string.desktop_nav_subscriptions)) }
                    )
                    NavigationRailItem(
                        selected = currentDest == Destination.History,
                        onClick = { navigator.navigateRoot(Destination.History) },
                        icon = { Icon(painterResource(Res.drawable.ic_history), null) },
                        label = { Text(stringResource(Res.string.desktop_nav_history)) }
                    )
                    NavigationRailItem(
                        selected = currentDest == Destination.Downloads,
                        onClick = { navigator.navigateRoot(Destination.Downloads) },
                        icon = {
                            if (activeDownloadCount > 0) {
                                BadgedBox(badge = { Badge { Text(activeDownloadCount.toString()) } }) {
                                    Icon(painterResource(Res.drawable.ic_file_download), null)
                                }
                            } else {
                                Icon(painterResource(Res.drawable.ic_file_download), null)
                            }
                        },
                        label = { Text(stringResource(Res.string.desktop_nav_downloads)) }
                    )
                    NavigationRailItem(
                        selected = currentDest == Destination.Settings,
                        onClick = { navigator.navigateRoot(Destination.Settings) },
                        icon = { Icon(painterResource(Res.drawable.ic_settings), null) },
                        label = { Text(stringResource(Res.string.desktop_nav_settings)) }
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    NavigationRailItem(
                        selected = isDataSaver,
                        onClick = { dataSaverController.setDataSaverEnabled(!isDataSaver) },
                        icon = { Icon(painterResource(Res.drawable.ic_data_saver), null) },
                        label = { Text(stringResource(Res.string.desktop_data_saver)) }
                    )
                    NavigationRailItem(
                        selected = currentDest == Destination.About,
                        onClick = { navigator.navigateRoot(Destination.About) },
                        icon = { Icon(painterResource(Res.drawable.ic_info_outline), null) },
                        label = { Text(stringResource(Res.string.desktop_nav_about)) }
                    )
                }
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavDisplay(
                        startDestination = startDestination,
                        onCloseRequest = onCloseRequest,
                        navigator = navigator
                    )
                }
                if (activeDownloads.isNotEmpty() && currentDest != Destination.Downloads && !playerState.isFullscreen) {
                    ActiveDownloadBanner(
                        activeTasks = activeDownloads,
                        onOpenDownloads = { navigator.navigateRoot(Destination.Downloads) }
                    )
                }
                if (showMiniPlayer) {
                    MiniPlayerBar(
                        playerState = playerState,
                        mediaPlayerController = mediaPlayerController,
                        onOpenVideo = { url, title ->
                            navigator.navigateTo(Destination.VideoDetail(url, title))
                        }
                    )
                }
            }
        }
    }
}
