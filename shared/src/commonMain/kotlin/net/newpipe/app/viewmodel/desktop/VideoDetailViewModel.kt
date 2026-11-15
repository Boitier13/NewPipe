/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.viewmodel.desktop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.newpipe.app.model.AudioStreamOption
import net.newpipe.app.model.ChannelSummary
import net.newpipe.app.model.PlayerState
import net.newpipe.app.model.VideoDetails
import net.newpipe.app.model.VideoStreamOption
import net.newpipe.app.platform.DataSaverController
import net.newpipe.app.platform.DesktopDatabaseGateway
import net.newpipe.app.platform.DownloadController
import net.newpipe.app.platform.MediaPlayerController
import net.newpipe.app.platform.YouTubeBackend
import org.koin.core.annotation.KoinViewModel

data class VideoDetailUiState(
    val url: String = "",
    val details: VideoDetails? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSubscribed: Boolean = false
)

@KoinViewModel
class VideoDetailViewModel(
    private val youTubeBackend: YouTubeBackend,
    private val database: DesktopDatabaseGateway,
    val mediaPlayerController: MediaPlayerController,
    private val downloadController: DownloadController,
    val dataSaverController: DataSaverController
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoDetailUiState())
    val uiState: StateFlow<VideoDetailUiState> = _uiState.asStateFlow()

    val playerState: StateFlow<PlayerState> = mediaPlayerController.state

    private var loadJob: Job? = null
    private var subscriptionJob: Job? = null

    fun loadVideo(url: String, forceReload: Boolean = false, autoPlay: Boolean = true) {
        if (!forceReload && _uiState.value.url == url && _uiState.value.details != null) {
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(url = url, isLoading = true, errorMessage = null)
            }
            val result = youTubeBackend.getVideoDetails(url, forceReload = forceReload)
            result.fold(
                onSuccess = { details ->
                    _uiState.update {
                        it.copy(details = details, isLoading = false, errorMessage = null)
                    }
                    observeSubscription(details.uploaderUrl)
                    val currentlyPlayingSame = mediaPlayerController.state.value.currentVideo?.url == details.url
                    if (autoPlay && !currentlyPlayingSame) {
                        mediaPlayerController.playVideo(details)
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = err.message ?: "Failed to load video"
                        )
                    }
                }
            )
        }
    }

    private fun observeSubscription(uploaderUrl: String?) {
        subscriptionJob?.cancel()
        if (uploaderUrl.isNullOrBlank()) {
            _uiState.update { it.copy(isSubscribed = false) }
            return
        }
        subscriptionJob = viewModelScope.launch {
            database.isSubscribedFlow(uploaderUrl).collect { subscribed ->
                _uiState.update { it.copy(isSubscribed = subscribed) }
            }
        }
    }

    fun playVideo() {
        val details = _uiState.value.details ?: return
        mediaPlayerController.playVideo(details)
    }

    fun playBackgroundAudio() {
        val details = _uiState.value.details ?: return
        mediaPlayerController.playBackgroundAudio(details)
    }

    fun switchToBackgroundAudio() = mediaPlayerController.switchToBackgroundAudio()

    fun switchToVideo() = mediaPlayerController.switchToVideo()

    fun selectResolution(option: VideoStreamOption) {
        val details = _uiState.value.details
        val currentPlaying = mediaPlayerController.state.value.currentVideo
        if (details != null && currentPlaying?.url != details.url) {
            mediaPlayerController.playVideo(details, videoOption = option)
        } else {
            mediaPlayerController.selectResolution(option)
        }
    }

    fun selectAudioTrack(option: AudioStreamOption) {
        mediaPlayerController.selectAudioTrack(option)
    }

    fun toggleSubscription() {
        val details = _uiState.value.details ?: return
        val uploaderUrl = details.uploaderUrl ?: return
        viewModelScope.launch {
            if (_uiState.value.isSubscribed) {
                database.unsubscribe(details.serviceId, uploaderUrl)
            } else {
                database.subscribe(
                    ChannelSummary(
                        serviceId = details.serviceId,
                        url = uploaderUrl,
                        name = details.uploaderName,
                        description = "",
                        thumbnailUrl = details.uploaderAvatarUrl,
                        subscriberCount = details.subscriberCount,
                        streamCount = -1L
                    )
                )
            }
        }
    }

    fun isFfmpegAvailable(): Boolean = downloadController.isFfmpegAvailable()

    fun downloadMp4(option: VideoStreamOption) {
        val details = _uiState.value.details ?: return
        downloadController.enqueueMp4(details, option)
    }

    fun downloadMp3(option: AudioStreamOption) {
        val details = _uiState.value.details ?: return
        downloadController.enqueueMp3(details, option)
    }

    fun downloadNativeAudio(option: AudioStreamOption) {
        val details = _uiState.value.details ?: return
        downloadController.enqueueNativeAudio(details, option)
    }
}
