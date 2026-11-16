/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.navigation

import androidx.compose.runtime.mutableStateListOf
import co.touchlab.kermit.Logger
import net.newpipe.app.screen.about.AboutScreen
import net.newpipe.app.screen.desktop.ChannelScreen
import net.newpipe.app.screen.desktop.DesktopHomeSearchScreen
import net.newpipe.app.screen.desktop.DownloadsScreen
import net.newpipe.app.screen.desktop.HistoryScreen
import net.newpipe.app.screen.desktop.PlaylistScreen
import net.newpipe.app.screen.desktop.SubscriptionsScreen
import net.newpipe.app.screen.desktop.VideoDetailScreen
import net.newpipe.app.screen.settings.AppearanceSettingsScreen
import net.newpipe.app.screen.settings.BackupRestoreSettingsScreen
import net.newpipe.app.screen.settings.ContentSettingsScreen
import net.newpipe.app.screen.settings.DebugSettingsScreen
import net.newpipe.app.screen.settings.DownloadSettingsScreen
import net.newpipe.app.screen.settings.HistorySettingsScreen
import net.newpipe.app.screen.settings.NotificationSettingsScreen
import net.newpipe.app.screen.settings.PlayerSettingsScreen
import net.newpipe.app.screen.settings.SettingsHomeScreen
import net.newpipe.app.screen.settings.UpdateSettingsScreen
import net.newpipe.app.screen.settings.VideoAudioSettingsScreen
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.annotation.Provided
import org.koin.core.annotation.Singleton
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation
import org.koin.plugin.module.dsl.single

/**
 * Navigation module to make navigation easier with nav3
 */
@OptIn(KoinExperimentalAPI::class)
fun navModule() = module {
    single<Navigator>()

    navigation<Destination.Home> {
        DesktopHomeSearchScreen(focusSearchInitially = false)
    }

    navigation<Destination.Search> { dest ->
        DesktopHomeSearchScreen(
            focusSearchInitially = true,
            initialQuery = dest.initialQuery.takeIf { it.isNotBlank() }
        )
    }

    navigation<Destination.Subscriptions> {
        SubscriptionsScreen()
    }

    navigation<Destination.History> {
        HistoryScreen()
    }

    navigation<Destination.Downloads> {
        DownloadsScreen()
    }

    navigation<Destination.VideoDetail> { dest ->
        VideoDetailScreen(
            videoUrl = dest.url,
            initialTitle = dest.initialTitle
        )
    }

    navigation<Destination.ChannelDetail> { dest ->
        ChannelScreen(
            channelUrl = dest.url,
            initialName = dest.initialName
        )
    }

    navigation<Destination.PlaylistDetail> { dest ->
        PlaylistScreen(
            playlistUrl = dest.url,
            initialName = dest.initialName
        )
    }

    navigation<Destination.About> {
        AboutScreen()
    }

    navigation<Destination.Settings> {
        SettingsHomeScreen()
    }

    navigation<Destination.AppearanceSettings> {
        AppearanceSettingsScreen()
    }

    navigation<Destination.VideoAudioSettings> {
        VideoAudioSettingsScreen()
    }

    navigation<Destination.PlayerSettings> {
        PlayerSettingsScreen()
    }

    navigation<Destination.DebugSettings> {
        DebugSettingsScreen()
    }

    navigation<Destination.DownloadSettings> {
        DownloadSettingsScreen()
    }

    navigation<Destination.HistorySettings> {
        HistorySettingsScreen()
    }

    navigation<Destination.ContentSettings> {
        ContentSettingsScreen()
    }

    navigation<Destination.NotificationSettings> {
        NotificationSettingsScreen()
    }

    navigation<Destination.UpdateSettings> {
        UpdateSettingsScreen()
    }

    navigation<Destination.BackupRestoreSettings> {
        BackupRestoreSettingsScreen()
    }
}

/**
 * Helper to navigate up and to different destinations in compose
 */
@Singleton
class Navigator(
    @Provided
    private val startDestination: Destination,

    @Provided
    private val onCloseRequest: () -> Unit
) {

    /**
     * Navigation backstack in compose
     */
    val backstack = mutableStateListOf(startDestination)

    /**
     * Navigates to the given destination
     */
    fun navigateTo(destination: Destination) {
        if (backstack.lastOrNull() != destination) {
            backstack.add(destination)
        }
    }

    /**
     * Switches top-level desktop navigation rail sections cleanly.
     */
    fun navigateRoot(destination: Destination) {
        backstack.clear()
        backstack.add(Destination.Home)
        if (destination != Destination.Home) {
            backstack.add(destination)
        }
    }

    /**
     * Navigates to the previous entry in the backstack
     */
    fun navigateUp() = when {
        backstack.size > 1 -> backstack.removeLastOrNull()

        else -> {
            Logger.i(messageString = "Cannot remove the only entry in backstack!")
            onCloseRequest()
        }
    }
}
