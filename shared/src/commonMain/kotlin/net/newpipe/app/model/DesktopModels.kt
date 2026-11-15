/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.model

import androidx.compose.runtime.Immutable

enum class SearchFilter(val extractorFilter: String?) {
    ALL(null),
    VIDEOS("videos"),
    CHANNELS("channels"),
    PLAYLISTS("playlists")
}

@Immutable
data class StreamSummary(
    val serviceId: Int,
    val url: String,
    val title: String,
    val uploaderName: String,
    val uploaderUrl: String?,
    val durationSeconds: Long,
    val viewCount: Long,
    val textualUploadDate: String?,
    val thumbnailUrl: String?,
    val isLive: Boolean = false
)

@Immutable
data class ChannelSummary(
    val serviceId: Int,
    val url: String,
    val name: String,
    val description: String,
    val thumbnailUrl: String?,
    val subscriberCount: Long,
    val streamCount: Long
)

@Immutable
data class PlaylistSummary(
    val serviceId: Int,
    val url: String,
    val name: String,
    val uploaderName: String?,
    val thumbnailUrl: String?,
    val streamCount: Long
)

sealed interface SearchResultItem {
    data class Video(val stream: StreamSummary) : SearchResultItem
    data class Channel(val channel: ChannelSummary) : SearchResultItem
    data class Playlist(val playlist: PlaylistSummary) : SearchResultItem
}

@Immutable
data class SearchPageResult(
    val items: List<SearchResultItem>,
    val hasNextPage: Boolean,
    val nextPageToken: String?
)

@Immutable
data class VideoStreamOption(
    val id: String,
    val resolution: String,
    val formatLabel: String,
    val formatKey: String,
    val isVideoOnly: Boolean,
    val videoUrl: String,
    val pairedAudioUrl: String?,
    val pairedAudioFormatKey: String?,
    val fps: Int,
    val approxSizeBytes: Long = -1L
)

@Immutable
data class AudioStreamOption(
    val id: String,
    val bitrateKbps: Int,
    val formatLabel: String,
    val formatKey: String,
    val url: String,
    val localeLabel: String? = null,
    val trackTypeLabel: String? = null,
    val approxSizeBytes: Long = -1L
)

@Immutable
data class VideoDetails(
    val serviceId: Int,
    val url: String,
    val title: String,
    val description: String,
    val uploaderName: String,
    val uploaderUrl: String?,
    val uploaderAvatarUrl: String?,
    val subscriberCount: Long,
    val viewCount: Long,
    val uploadDateText: String?,
    val isoUploadDate: String?,
    val category: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val isLive: Boolean,
    val videoStreams: List<VideoStreamOption>,
    val audioStreams: List<AudioStreamOption>,
    val relatedVideos: List<StreamSummary>
)

@Immutable
data class ChannelDetails(
    val serviceId: Int,
    val url: String,
    val name: String,
    val description: String,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val subscriberCount: Long,
    val videos: List<StreamSummary>,
    val hasNextPage: Boolean,
    val nextPageToken: String?
)

@Immutable
data class SubscriptionRecord(
    val uid: Long = 0,
    val serviceId: Int,
    val url: String,
    val name: String,
    val avatarUrl: String?,
    val subscriberCount: Long?,
    val description: String?
)

@Immutable
data class WatchHistoryRecord(
    val streamId: Long,
    val serviceId: Int,
    val url: String,
    val title: String,
    val uploader: String,
    val uploaderUrl: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val accessDateMillis: Long,
    val repeatCount: Long,
    val progressMillis: Long
)

enum class DownloadFormat(val extension: String, val label: String) {
    MP4("mp4", "MP4"),
    MP3("mp3", "MP3"),
    M4A("m4a", "M4A"),
    OGG_OPUS("ogg", "OGG/Opus")
}

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    MUXING,
    TRANSCODING,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Immutable
data class DownloadTaskItem(
    val id: String,
    val title: String,
    val uploader: String,
    val format: DownloadFormat,
    val resolutionLabel: String?,
    val progress: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val outputPath: String,
    val status: DownloadStatus,
    val statusMessage: String? = null
)

enum class PlayerPlaybackMode {
    VIDEO,
    BACKGROUND_AUDIO
}

@Immutable
data class PlayerState(
    val currentVideo: VideoDetails? = null,
    val localTitle: String? = null,
    val mode: PlayerPlaybackMode = PlayerPlaybackMode.VIDEO,
    val selectedVideoOption: VideoStreamOption? = null,
    val selectedAudioOption: AudioStreamOption? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Int = 100,
    val isMuted: Boolean = false,
    val playbackRate: Float = 1.0f,
    val isFullscreen: Boolean = false,
    val vlcAvailable: Boolean = true,
    val errorMessage: String? = null
)
