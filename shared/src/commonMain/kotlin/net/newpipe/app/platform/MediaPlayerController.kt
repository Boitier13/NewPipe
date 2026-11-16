/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow
import net.newpipe.app.model.AudioStreamOption
import net.newpipe.app.model.PlayerState
import net.newpipe.app.model.VideoDetails
import net.newpipe.app.model.VideoStreamOption

/**
 * Controls desktop video and background audio playback via VLCJ.
 */
interface MediaPlayerController {
    val state: StateFlow<PlayerState>

    fun recheckVlcAvailability(): Boolean

    fun playVideo(
        details: VideoDetails,
        videoOption: VideoStreamOption? = null,
        startPositionMs: Long = 0L
    )

    fun playBackgroundAudio(
        details: VideoDetails,
        audioOption: AudioStreamOption? = null,
        startPositionMs: Long = 0L
    )

    fun playLocalFile(filePath: String, title: String, isAudioOnly: Boolean)

    fun switchToBackgroundAudio()

    fun switchToVideo()

    fun selectResolution(option: VideoStreamOption)

    fun selectAudioTrack(option: AudioStreamOption)

    fun togglePlayPause()

    fun seekTo(positionMs: Long)

    fun seekBy(deltaMs: Long)

    fun setVolume(volume: Int)

    fun toggleMute()

    fun setPlaybackRate(rate: Float)

    fun toggleFullscreen()

    fun setFullscreen(fullscreen: Boolean)

    fun stop()

    fun release()

    @Composable
    fun VideoViewport(modifier: Modifier)
}
