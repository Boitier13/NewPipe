/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.di.settings

import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.io.Flushable
import java.util.prefs.Preferences
import org.koin.core.annotation.Singleton

/**
 * Settings for JVM devices based on Java Preferences
 */
@Singleton
fun provideSettings(): Settings = DurablePreferencesSettings(Preferences.userRoot())

/** Keep observable behavior while allowing backup transactions to detect persistence failures. */
internal class DurablePreferencesSettings(
    private val preferences: Preferences
) : ObservableSettings by PreferencesSettings(preferences), Flushable {
    override fun flush() = preferences.flush()
}
