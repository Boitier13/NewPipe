/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.newpipe.app.model.AudioStreamOption
import net.newpipe.app.model.PlayerPlaybackMode
import net.newpipe.app.model.PlayerState
import net.newpipe.app.model.VideoDetails
import net.newpipe.app.model.VideoStreamOption
import net.newpipe.app.preferences.DesktopPreferences
import net.newpipe.app.preferences.HistoryPreferences
import net.newpipe.app.preferences.VideoAudioPreferences
import org.koin.core.annotation.Singleton
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.AudioPlayerComponent
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

/**
 * Desktop media controller powered by VLC / VLCJ (`libVLC`), supporting embedded video
 * playback via SwingPanel and background audio playback without a visible video window.
 */
@Singleton(binds = [MediaPlayerController::class])
class VlcMediaPlayerController(
    private val settings: Settings,
    private val dataSaverController: DataSaverController,
    private val database: DesktopDatabaseGateway
) : MediaPlayerController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vlcExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NewPipe-VLC-Control").apply { isDaemon = true }
    }

    private val _state = MutableStateFlow(
        PlayerState(
            volume = settings.getInt(DesktopPreferences.KEY_PLAYER_VOLUME, DesktopPreferences.DEFAULT_PLAYER_VOLUME),
            playbackRate = settings.getFloat(DesktopPreferences.KEY_PLAYER_SPEED, DesktopPreferences.DEFAULT_PLAYER_SPEED)
        )
    )
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    @Volatile
    private var videoComponent: EmbeddedMediaPlayerComponent? = null

    @Volatile
    private var audioComponent: AudioPlayerComponent? = null

    @Volatile
    private var activeLocalFilePath: String? = null

    @Volatile
    private var lastSavedProgressTimestamp = 0L

    private val discoveryAttempted = AtomicBoolean(false)

    override fun recheckVlcAvailability(): Boolean {
        discoveryAttempted.set(false)
        return ensureVlcDiscovered()
    }

    private fun ensureVlcDiscovered(): Boolean {
        if (discoveryAttempted.compareAndSet(false, true)) {
            val customPath = settings.getString(DesktopPreferences.KEY_VLC_PATH, "")
            val candidateDirs = WindowsAppPaths.candidateVlcDirectories(customPath)
            if (candidateDirs.isNotEmpty()) {
                val existingJna = System.getProperty("jna.library.path").orEmpty()
                val merged = (candidateDirs.map { it.absolutePath } +
                    existingJna.split(File.pathSeparatorChar).filter { it.isNotBlank() })
                    .distinct()
                    .joinToString(File.pathSeparator)
                System.setProperty("jna.library.path", merged)
            }
            val found = runCatching { NativeDiscovery().discover() }.getOrElse {
                Logger.w(it) { "VLC NativeDiscovery failed" }
                false
            }
            _state.update {
                it.copy(
                    vlcAvailable = found,
                    errorMessage = if (found) null else missingVlcMessage()
                )
            }
        }
        return _state.value.vlcAvailable
    }

    private fun getOrCreateVideoSurface(): EmbeddedMediaPlayerComponent? {
        if (!ensureVlcDiscovered()) return null
        videoComponent?.let { return it }
        return synchronized(this) {
            videoComponent ?: runCatching {
                val createBlock = {
                    val comp = EmbeddedMediaPlayerComponent()
                    attachListeners(comp.mediaPlayer(), PlayerPlaybackMode.VIDEO)
                    comp
                }
                val created = if (SwingUtilities.isEventDispatchThread()) {
                    createBlock()
                } else {
                    var holder: EmbeddedMediaPlayerComponent? = null
                    SwingUtilities.invokeAndWait { holder = createBlock() }
                    holder!!
                }
                videoComponent = created
                created
            }.onFailure { err ->
                Logger.e(err) { "Failed to initialize VLC EmbeddedMediaPlayerComponent" }
                _state.update {
                    it.copy(
                        vlcAvailable = false,
                        errorMessage = err.message ?: missingVlcMessage()
                    )
                }
            }.getOrNull()
        }
    }

    private fun getOrCreateAudioPlayer(): AudioPlayerComponent? {
        if (!ensureVlcDiscovered()) return null
        audioComponent?.let { return it }
        return synchronized(this) {
            audioComponent ?: runCatching {
                val comp = AudioPlayerComponent()
                attachListeners(comp.mediaPlayer(), PlayerPlaybackMode.BACKGROUND_AUDIO)
                audioComponent = comp
                comp
            }.onFailure { err ->
                Logger.e(err) { "Failed to initialize VLC AudioPlayerComponent" }
                _state.update {
                    it.copy(
                        vlcAvailable = false,
                        errorMessage = err.message ?: missingVlcMessage()
                    )
                }
            }.getOrNull()
        }
    }

    private fun attachListeners(mediaPlayer: MediaPlayer, componentMode: PlayerPlaybackMode) {
        mediaPlayer.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun buffering(mp: MediaPlayer, newCache: Float) {
                if (_state.value.mode == componentMode) {
                    _state.update { it.copy(isBuffering = newCache < 100f) }
                }
            }

            override fun playing(mp: MediaPlayer) {
                if (_state.value.mode == componentMode) {
                    _state.update {
                        it.copy(
                            isPlaying = true,
                            isBuffering = false,
                            errorMessage = null
                        )
                    }
                    vlcExecutor.execute {
                        runCatching {
                            val currentState = _state.value
                            mp.audio().setVolume(currentState.volume)
                            mp.audio().setMute(currentState.isMuted)
                            mp.controls().setRate(currentState.playbackRate)
                        }
                    }
                }
            }

            override fun paused(mp: MediaPlayer) {
                if (_state.value.mode == componentMode) {
                    _state.update { it.copy(isPlaying = false) }
                    persistCurrentProgress()
                }
            }

            override fun stopped(mp: MediaPlayer) {
                if (_state.value.mode == componentMode) {
                    _state.update { it.copy(isPlaying = false, isBuffering = false) }
                }
            }

            override fun finished(mp: MediaPlayer) {
                if (_state.value.mode == componentMode) {
                    _state.update {
                        it.copy(
                            isPlaying = false,
                            isBuffering = false,
                            positionMs = it.durationMs
                        )
                    }
                    persistCurrentProgress(resetToZero = true)
                }
            }

            override fun error(mp: MediaPlayer) {
                if (_state.value.mode == componentMode) {
                    _state.update {
                        it.copy(
                            isPlaying = false,
                            isBuffering = false,
                            errorMessage = "VLC playback error"
                        )
                    }
                }
            }

            override fun timeChanged(mp: MediaPlayer, newTime: Long) {
                if (_state.value.mode == componentMode) {
                    _state.update { it.copy(positionMs = newTime) }
                    val now = System.currentTimeMillis()
                    if (now - lastSavedProgressTimestamp >= 5_000L) {
                        lastSavedProgressTimestamp = now
                        persistCurrentProgress()
                    }
                }
            }

            override fun lengthChanged(mp: MediaPlayer, newLength: Long) {
                if (_state.value.mode == componentMode && newLength > 0L) {
                    _state.update { it.copy(durationMs = newLength) }
                }
            }
        })
    }

    override fun playVideo(
        details: VideoDetails,
        videoOption: VideoStreamOption?,
        startPositionMs: Long
    ) {
        val forceAudio = dataSaverController.isDataSaverEnabled.value &&
            dataSaverController.preferAudioOnly.value
        if (forceAudio) {
            playBackgroundAudio(details, details.audioStreams.firstOrNull(), startPositionMs)
            return
        }

        val chosenVideo = videoOption ?: pickInitialVideoStream(details)
        val chosenAudio = details.audioStreams.firstOrNull()
        activeLocalFilePath = null

        scope.launch {
            recordWatchIfEnabled(details)
            val resumeMs = resolveInitialPositionMs(details, startPositionMs)

            _state.update {
                it.copy(
                    currentVideo = details,
                    localTitle = null,
                    mode = PlayerPlaybackMode.VIDEO,
                    selectedVideoOption = chosenVideo,
                    selectedAudioOption = chosenAudio,
                    positionMs = resumeMs,
                    durationMs = details.durationSeconds.coerceAtLeast(0L) * 1000L,
                    isBuffering = true,
                    errorMessage = null
                )
            }

            startStreamPlayback(
                videoStream = chosenVideo,
                audioStream = chosenAudio,
                mode = PlayerPlaybackMode.VIDEO,
                startPositionMs = resumeMs
            )
        }
    }

    override fun playBackgroundAudio(
        details: VideoDetails,
        audioOption: AudioStreamOption?,
        startPositionMs: Long
    ) {
        val chosenAudio = audioOption ?: details.audioStreams.firstOrNull()
        val chosenVideo = details.videoStreams.firstOrNull()
        activeLocalFilePath = null

        scope.launch {
            recordWatchIfEnabled(details)
            val resumeMs = resolveInitialPositionMs(details, startPositionMs)

            _state.update {
                it.copy(
                    currentVideo = details,
                    localTitle = null,
                    mode = PlayerPlaybackMode.BACKGROUND_AUDIO,
                    selectedVideoOption = chosenVideo,
                    selectedAudioOption = chosenAudio,
                    positionMs = resumeMs,
                    durationMs = details.durationSeconds.coerceAtLeast(0L) * 1000L,
                    isBuffering = true,
                    errorMessage = null
                )
            }

            startStreamPlayback(
                videoStream = chosenVideo,
                audioStream = chosenAudio,
                mode = PlayerPlaybackMode.BACKGROUND_AUDIO,
                startPositionMs = resumeMs
            )
        }
    }

    override fun playLocalFile(filePath: String, title: String, isAudioOnly: Boolean) {
        val mode = if (isAudioOnly) PlayerPlaybackMode.BACKGROUND_AUDIO else PlayerPlaybackMode.VIDEO
        activeLocalFilePath = filePath
        _state.update {
            it.copy(
                currentVideo = null,
                localTitle = title,
                mode = mode,
                selectedVideoOption = null,
                selectedAudioOption = null,
                positionMs = 0L,
                durationMs = 0L,
                isBuffering = true,
                errorMessage = null
            )
        }
        vlcExecutor.execute {
            if (!ensureVlcDiscovered()) return@execute
            runCatching { videoComponent?.mediaPlayer()?.controls()?.stop() }
            runCatching { audioComponent?.mediaPlayer()?.controls()?.stop() }
            if (isAudioOnly) {
                val player = getOrCreateAudioPlayer()?.mediaPlayer() ?: return@execute
                player.media().play(filePath, ":no-video")
            } else {
                val comp = getOrCreateVideoSurface() ?: return@execute
                waitForDisplayableCanvas(comp)
                comp.mediaPlayer().media().play(filePath)
            }
        }
    }

    override fun switchToBackgroundAudio() {
        val current = _state.value
        if (current.mode == PlayerPlaybackMode.BACKGROUND_AUDIO) return
        val local = activeLocalFilePath
        if (local != null) {
            playLocalFile(local, current.localTitle.orEmpty(), isAudioOnly = true)
            return
        }
        if (current.currentVideo == null) return
        val pos = current.positionMs
        _state.update {
            it.copy(mode = PlayerPlaybackMode.BACKGROUND_AUDIO, isBuffering = true, errorMessage = null)
        }
        startStreamPlayback(
            videoStream = current.selectedVideoOption,
            audioStream = current.selectedAudioOption,
            mode = PlayerPlaybackMode.BACKGROUND_AUDIO,
            startPositionMs = pos
        )
    }

    override fun switchToVideo() {
        val current = _state.value
        if (current.mode == PlayerPlaybackMode.VIDEO) return
        val local = activeLocalFilePath
        if (local != null) {
            playLocalFile(local, current.localTitle.orEmpty(), isAudioOnly = false)
            return
        }
        val details = current.currentVideo ?: return
        val chosenVideo = current.selectedVideoOption ?: pickInitialVideoStream(details)
        val pos = current.positionMs
        _state.update {
            it.copy(
                mode = PlayerPlaybackMode.VIDEO,
                selectedVideoOption = chosenVideo,
                isBuffering = true,
                errorMessage = null
            )
        }
        startStreamPlayback(
            videoStream = chosenVideo,
            audioStream = current.selectedAudioOption,
            mode = PlayerPlaybackMode.VIDEO,
            startPositionMs = pos
        )
    }

    override fun selectResolution(option: VideoStreamOption) {
        val current = _state.value
        val pos = current.positionMs
        _state.update {
            it.copy(
                selectedVideoOption = option,
                mode = PlayerPlaybackMode.VIDEO,
                isBuffering = true,
                errorMessage = null
            )
        }
        startStreamPlayback(
            videoStream = option,
            audioStream = current.selectedAudioOption,
            mode = PlayerPlaybackMode.VIDEO,
            startPositionMs = pos
        )
    }

    override fun selectAudioTrack(option: AudioStreamOption) {
        val current = _state.value
        val pos = current.positionMs
        _state.update {
            it.copy(
                selectedAudioOption = option,
                isBuffering = true,
                errorMessage = null
            )
        }
        startStreamPlayback(
            videoStream = current.selectedVideoOption,
            audioStream = option,
            mode = current.mode,
            startPositionMs = pos
        )
    }

    private fun startStreamPlayback(
        videoStream: VideoStreamOption?,
        audioStream: AudioStreamOption?,
        mode: PlayerPlaybackMode,
        startPositionMs: Long
    ) {
        vlcExecutor.execute {
            if (!ensureVlcDiscovered()) return@execute

            runCatching { videoComponent?.mediaPlayer()?.controls()?.stop() }
            runCatching { audioComponent?.mediaPlayer()?.controls()?.stop() }

            val options = mutableListOf(
                ":http-user-agent=${DesktopDownloader.USER_AGENT}",
                ":network-caching=1500"
            )
            if (startPositionMs > 1000L) {
                options.add(":start-time=${startPositionMs / 1000.0}")
            }

            if (mode == PlayerPlaybackMode.BACKGROUND_AUDIO) {
                val mrl = audioStream?.url ?: videoStream?.pairedAudioUrl ?: videoStream?.videoUrl
                if (mrl.isNullOrBlank()) {
                    _state.update {
                        it.copy(isBuffering = false, errorMessage = "No playable audio stream found")
                    }
                    return@execute
                }
                options.add(":no-video")
                val player = getOrCreateAudioPlayer()?.mediaPlayer() ?: return@execute
                player.media().play(mrl, *options.toTypedArray())
            } else {
                val mrl = videoStream?.videoUrl ?: audioStream?.url
                if (mrl.isNullOrBlank()) {
                    _state.update {
                        it.copy(isBuffering = false, errorMessage = "No playable video stream found")
                    }
                    return@execute
                }
                if (videoStream?.isVideoOnly == true) {
                    val slaveAudio = videoStream.pairedAudioUrl ?: audioStream?.url
                    if (!slaveAudio.isNullOrBlank()) {
                        options.add(":input-slave=$slaveAudio")
                    }
                }
                val comp = getOrCreateVideoSurface() ?: return@execute
                waitForDisplayableCanvas(comp)
                comp.mediaPlayer().media().play(mrl, *options.toTypedArray())
            }
        }
    }

    private fun waitForDisplayableCanvas(comp: EmbeddedMediaPlayerComponent) {
        val deadline = System.currentTimeMillis() + 1500L
        while (!comp.videoSurfaceComponent().isDisplayable && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L)
        }
    }

    private fun activeMediaPlayer(): MediaPlayer? =
        if (_state.value.mode == PlayerPlaybackMode.BACKGROUND_AUDIO) {
            audioComponent?.mediaPlayer()
        } else {
            videoComponent?.mediaPlayer()
        }

    override fun togglePlayPause() {
        vlcExecutor.execute {
            val mp = activeMediaPlayer() ?: return@execute
            if (_state.value.isPlaying) {
                mp.controls().pause()
            } else {
                mp.controls().play()
            }
        }
    }

    override fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceAtLeast(0L)
        _state.update { it.copy(positionMs = clamped) }
        vlcExecutor.execute {
            activeMediaPlayer()?.controls()?.setTime(clamped)
        }
    }

    override fun seekBy(deltaMs: Long) {
        val current = _state.value
        val max = if (current.durationMs > 0L) current.durationMs else Long.MAX_VALUE
        seekTo((current.positionMs + deltaMs).coerceIn(0L, max))
    }

    override fun setVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        settings.putInt(DesktopPreferences.KEY_PLAYER_VOLUME, clamped)
        _state.update { it.copy(volume = clamped, isMuted = clamped == 0) }
        vlcExecutor.execute {
            activeMediaPlayer()?.audio()?.setVolume(clamped)
        }
    }

    override fun toggleMute() {
        val nextMuted = !_state.value.isMuted
        _state.update { it.copy(isMuted = nextMuted) }
        vlcExecutor.execute {
            activeMediaPlayer()?.audio()?.setMute(nextMuted)
        }
    }

    override fun setPlaybackRate(rate: Float) {
        val clamped = rate.coerceIn(0.25f, 3.0f)
        settings.putFloat(DesktopPreferences.KEY_PLAYER_SPEED, clamped)
        _state.update { it.copy(playbackRate = clamped) }
        vlcExecutor.execute {
            activeMediaPlayer()?.controls()?.setRate(clamped)
        }
    }

    override fun toggleFullscreen() {
        _state.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    override fun setFullscreen(fullscreen: Boolean) {
        _state.update { it.copy(isFullscreen = fullscreen) }
    }

    override fun stop() {
        persistCurrentProgress()
        activeLocalFilePath = null
        vlcExecutor.execute {
            runCatching { videoComponent?.mediaPlayer()?.controls()?.stop() }
            runCatching { audioComponent?.mediaPlayer()?.controls()?.stop() }
        }
        _state.update {
            it.copy(
                currentVideo = null,
                localTitle = null,
                selectedVideoOption = null,
                selectedAudioOption = null,
                isPlaying = false,
                isBuffering = false,
                isFullscreen = false,
                positionMs = 0L,
                durationMs = 0L,
                errorMessage = null
            )
        }
    }

    override fun release() {
        persistCurrentProgress()
        vlcExecutor.execute {
            runCatching { videoComponent?.release() }
            runCatching { audioComponent?.release() }
            videoComponent = null
            audioComponent = null
        }
    }

    @Composable
    override fun VideoViewport(modifier: Modifier) {
        val playerState by state.collectAsState()
        val surface = remember(playerState.vlcAvailable) { getOrCreateVideoSurface() }
        if (surface != null) {
            SwingPanel(
                background = Color.Black,
                factory = { surface },
                modifier = modifier
            )
        } else {
            Box(
                modifier = modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = playerState.errorMessage ?: missingVlcMessage(),
                    color = Color.White
                )
            }
        }
    }

    private suspend fun recordWatchIfEnabled(details: VideoDetails) {
        if (settings.getBoolean(
                HistoryPreferences.KEY_ENABLE_WATCH_HISTORY,
                HistoryPreferences.DEFAULT_ENABLE_WATCH_HISTORY
            )
        ) {
            database.recordView(details)
        }
    }

    private suspend fun resolveInitialPositionMs(details: VideoDetails, requestedStartMs: Long): Long {
        if (requestedStartMs > 0L) return requestedStartMs
        val resumeEnabled = settings.getBoolean(
            HistoryPreferences.KEY_ENABLE_PLAYBACK_RESUME,
            HistoryPreferences.DEFAULT_ENABLE_PLAYBACK_RESUME
        )
        return if (resumeEnabled && !details.isLive) {
            database.getPlaybackProgress(details.url, details.serviceId)
        } else {
            0L
        }
    }

    private fun persistCurrentProgress(resetToZero: Boolean = false) {
        val current = _state.value
        val video = current.currentVideo ?: return
        if (video.isLive) return
        val progress = if (resetToZero) 0L else current.positionMs
        scope.launch {
            runCatching {
                database.savePlaybackProgress(video.url, video.serviceId, progress)
            }
        }
    }

    private fun pickInitialVideoStream(details: VideoDetails): VideoStreamOption? {
        val streams = details.videoStreams
        if (streams.isEmpty()) return null

        val preferredRes = settings.getString(
            VideoAudioPreferences.KEY_DEFAULT_RESOLUTION,
            VideoAudioPreferences.DEFAULT_RESOLUTION
        )
        val capRes = dataSaverController.effectiveMaxResolution()
        val target = when {
            capRes != null && (preferredRes == VideoAudioPreferences.BEST_RESOLUTION ||
                DesktopStreamHelper.compareResolution(capRes, preferredRes) < 1) -> capRes
            else -> preferredRes
        }

        if (target == VideoAudioPreferences.BEST_RESOLUTION) return streams.first()
        val targetNoRefresh = target.replace(Regex("p\\d+$"), "p")
        return streams.firstOrNull { it.resolution == target }
            ?: streams.firstOrNull { it.resolution.replace(Regex("p\\d+$"), "p") == targetNoRefresh }
            ?: streams.firstOrNull {
                DesktopStreamHelper.compareResolution(
                    it.resolution.replace(Regex("p\\d+$"), "p"),
                    targetNoRefresh
                ) <= 0
            }
            ?: streams.last()
    }

    private fun missingVlcMessage(): String =
        "VLC (libVLC) not found. Please install 64-bit VLC Media Player or set its folder in Settings."
}
