/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import kotlin.concurrent.Volatile
import kotlin.math.abs
import net.newpipe.app.preferences.ContentPreferences
import net.newpipe.app.preferences.DownloadPreferences
import net.newpipe.app.preferences.VideoAudioPreferences
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.Image.ResolutionLevel
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.VideoStream

enum class DesktopPreferredImageQuality {
    NONE,
    LOW,
    MEDIUM,
    HIGH;

    fun toResolutionLevel(): ResolutionLevel = when (this) {
        LOW -> ResolutionLevel.LOW
        MEDIUM -> ResolutionLevel.MEDIUM
        HIGH -> ResolutionLevel.HIGH
        NONE -> ResolutionLevel.UNKNOWN
    }

    companion object {
        fun fromPreferenceValue(value: String?): DesktopPreferredImageQuality = when (value) {
            ContentPreferences.IMAGE_QUALITY_NONE -> NONE
            ContentPreferences.IMAGE_QUALITY_LOW -> LOW
            ContentPreferences.IMAGE_QUALITY_HIGH -> HIGH
            else -> MEDIUM
        }
    }
}

/**
 * Pure JVM thumbnail selection strategy migrated from NewPipe's ImageStrategy.
 */
object DesktopImageStrategy {
    private const val BEST_LOW_H = 75
    private const val BEST_MEDIUM_H = 250

    @Volatile
    private var preferredQuality: DesktopPreferredImageQuality = DesktopPreferredImageQuality.MEDIUM

    fun setPreferredImageQuality(quality: DesktopPreferredImageQuality) {
        preferredQuality = quality
    }

    fun setPreferredImageQuality(preferenceValue: String?) {
        preferredQuality = DesktopPreferredImageQuality.fromPreferenceValue(preferenceValue)
    }

    fun shouldLoadImages(): Boolean = preferredQuality != DesktopPreferredImageQuality.NONE

    fun choosePreferredImage(images: List<Image>): String? {
        if (preferredQuality == DesktopPreferredImageQuality.NONE) return null
        return choosePreferredImage(images, preferredQuality)
    }

    fun imageListToDbUrl(images: List<Image>): String? {
        val quality = if (preferredQuality == DesktopPreferredImageQuality.NONE) {
            DesktopPreferredImageQuality.MEDIUM
        } else {
            preferredQuality
        }
        return choosePreferredImage(images, quality)
    }

    fun dbUrlToImageList(url: String?): List<Image> = if (url.isNullOrBlank()) {
        emptyList()
    } else {
        listOf(Image(url, Image.HEIGHT_UNKNOWN, Image.WIDTH_UNKNOWN, ResolutionLevel.UNKNOWN))
    }

    fun choosePreferredImage(
        images: List<Image>,
        nonNoneQuality: DesktopPreferredImageQuality
    ): String? {
        if (images.isEmpty()) return null
        val widthOverHeight = images
            .firstOrNull { it.height != Image.HEIGHT_UNKNOWN && it.width != Image.WIDTH_UNKNOWN }
            ?.let { it.width.toDouble() / it.height }
            ?: 1.0

        val preferredLevel = nonNoneQuality.toResolutionLevel()
        val comparator = Comparator<Image> { a, b ->
            fun levelRank(i: Image) = when (i.estimatedResolutionLevel) {
                ResolutionLevel.UNKNOWN -> 3
                preferredLevel -> 0
                ResolutionLevel.MEDIUM -> 1
                else -> 2
            }
            val rankCmp = levelRank(a) - levelRank(b)
            if (rankCmp != 0) return@Comparator rankCmp

            val aUnknownSize = a.height == Image.HEIGHT_UNKNOWN && a.width == Image.WIDTH_UNKNOWN
            val bUnknownSize = b.height == Image.HEIGHT_UNKNOWN && b.width == Image.WIDTH_UNKNOWN
            val unknownCmp = aUnknownSize.compareTo(bUnknownSize)
            if (unknownCmp != 0) return@Comparator unknownCmp

            val aPx = estimatePixelCount(a, widthOverHeight)
            val bPx = estimatePixelCount(b, widthOverHeight)
            when (nonNoneQuality) {
                DesktopPreferredImageQuality.NONE -> 0
                DesktopPreferredImageQuality.LOW -> abs(aPx - BEST_LOW_H * BEST_LOW_H * widthOverHeight)
                    .compareTo(abs(bPx - BEST_LOW_H * BEST_LOW_H * widthOverHeight))
                DesktopPreferredImageQuality.MEDIUM -> abs(aPx - BEST_MEDIUM_H * BEST_MEDIUM_H * widthOverHeight)
                    .compareTo(abs(bPx - BEST_MEDIUM_H * BEST_MEDIUM_H * widthOverHeight))
                DesktopPreferredImageQuality.HIGH -> (-aPx).compareTo(-bPx)
            }
        }
        return images.minWithOrNull(comparator)?.url
    }

    private fun estimatePixelCount(image: Image, widthOverHeight: Double): Double = when {
        image.height == Image.HEIGHT_UNKNOWN && image.width == Image.WIDTH_UNKNOWN -> 0.0
        image.height == Image.HEIGHT_UNKNOWN -> image.width * image.width / widthOverHeight
        image.width == Image.WIDTH_UNKNOWN -> image.height * image.height * widthOverHeight
        else -> (image.height * image.width).toDouble()
    }
}

/**
 * Pure JVM stream selection, sorting, resolution capping, and filename sanitization helpers.
 */
object DesktopStreamHelper {
    private val VIDEO_FORMAT_QUALITY_RANKING =
        listOf(MediaFormat.v3GPP, MediaFormat.WEBM, MediaFormat.MPEG_4)
    private val AUDIO_FORMAT_QUALITY_RANKING =
        listOf(MediaFormat.MP3, MediaFormat.WEBMA, MediaFormat.M4A)
    private val AUDIO_FORMAT_EFFICIENCY_RANKING =
        listOf(MediaFormat.MP3, MediaFormat.M4A, MediaFormat.WEBMA)
    private val HIGH_RESOLUTION_SET = setOf("1440p", "2160p")

    private val AUDIO_TRACK_RANKING = listOf(
        AudioTrackType.DESCRIPTIVE,
        AudioTrackType.SECONDARY,
        AudioTrackType.DUBBED,
        AudioTrackType.ORIGINAL
    )
    private val AUDIO_TRACK_RANKING_DESCRIPTIVE = listOf(
        AudioTrackType.SECONDARY,
        AudioTrackType.DUBBED,
        AudioTrackType.ORIGINAL,
        AudioTrackType.DESCRIPTIVE
    )

    private val SUPPORTED_YOUTUBE_ITAGS = setOf(
        17, 36,
        18, 34, 35, 59, 78, 22, 37, 38,
        43, 44, 45, 46,
        171, 172, 139, 140, 141, 249, 250, 251,
        160, 133, 134, 135, 212, 136, 298, 137, 299, 266,
        278, 242, 243, 244, 245, 246, 247, 248, 271, 272, 302, 303, 308, 313, 315
    )

    fun <S : Stream> filterPlayableHttpStreams(streams: List<S>?, serviceId: Int): List<S> {
        if (streams.isNullOrEmpty()) return emptyList()
        val ytId = ServiceList.YouTube.serviceId
        return streams.filter { stream ->
            stream.isUrl &&
                stream.deliveryMethod != DeliveryMethod.TORRENT &&
                (stream.deliveryMethod != DeliveryMethod.HLS || stream.format != MediaFormat.OPUS) &&
                (serviceId != ytId || stream.itagItem == null || SUPPORTED_YOUTUBE_ITAGS.contains(stream.itagItem!!.id))
        }
    }

    fun getSortedVideoStreams(
        defaultFormat: MediaFormat?,
        showHigherResolutions: Boolean,
        videoStreams: List<VideoStream>?,
        videoOnlyStreams: List<VideoStream>?,
        preferVideoOnlyStreams: Boolean = true
    ): List<VideoStream> {
        val orderedLists = if (preferVideoOnlyStreams) {
            listOfNotNull(videoStreams, videoOnlyStreams)
        } else {
            listOfNotNull(videoOnlyStreams, videoStreams)
        }

        val initial = orderedLists.flatten().filter { stream ->
            showHigherResolutions ||
                !HIGH_RESOLUTION_SET.contains(stream.resolution.replace(Regex("p\\d+$"), "p"))
        }

        val byResolution = LinkedHashMap<String, VideoStream>()
        for (stream in initial) {
            byResolution[stream.resolution] = stream
        }
        for (stream in initial) {
            if (stream.format == defaultFormat) {
                byResolution[stream.resolution] = stream
            }
        }

        val cmp = Comparator<VideoStream> { a, b ->
            val resCmp = compareResolution(a.resolution, b.resolution)
            if (resCmp != 0) resCmp else {
                VIDEO_FORMAT_QUALITY_RANKING.indexOf(a.format) - VIDEO_FORMAT_QUALITY_RANKING.indexOf(b.format)
            }
        }
        return byResolution.values.sortedWith(cmp.reversed())
    }

    fun pickDefaultVideoStreamIndex(
        sortedStreams: List<VideoStream>,
        preferredResolution: String,
        defaultFormat: MediaFormat?,
        maxResolutionLimit: String?
    ): Int {
        if (sortedStreams.isEmpty()) return -1
        var targetResolution = preferredResolution
        if (maxResolutionLimit != null &&
            (targetResolution == VideoAudioPreferences.BEST_RESOLUTION ||
                compareResolution(maxResolutionLimit, targetResolution) < 1)
        ) {
            targetResolution = maxResolutionLimit
        }
        if (targetResolution == VideoAudioPreferences.BEST_RESOLUTION) {
            return 0
        }

        val targetNoRefresh = targetResolution.replace(Regex("p\\d+$"), "p")
        var fullMatch = -1
        var fullMatchNoRefresh = -1
        var resOnly = -1
        var resOnlyNoRefresh = -1
        var lowerResNoRefresh = -1

        for ((idx, stream) in sortedStreams.withIndex()) {
            val res = stream.resolution
            val resNoRefresh = res.replace(Regex("p\\d+$"), "p")
            val formatMatch = defaultFormat != null && stream.format == defaultFormat

            if (formatMatch && res == targetResolution && fullMatch == -1) fullMatch = idx
            if (formatMatch && resNoRefresh == targetNoRefresh && fullMatchNoRefresh == -1) fullMatchNoRefresh = idx
            if (resOnly == -1 && res == targetResolution) resOnly = idx
            if (resOnlyNoRefresh == -1 && resNoRefresh == targetNoRefresh) resOnlyNoRefresh = idx
            if (lowerResNoRefresh == -1 && compareResolution(resNoRefresh, targetNoRefresh) <= 0) {
                lowerResNoRefresh = idx
            }
        }

        return when {
            fullMatch != -1 -> fullMatch
            fullMatchNoRefresh != -1 -> fullMatchNoRefresh
            resOnly != -1 -> resOnly
            resOnlyNoRefresh != -1 -> resOnlyNoRefresh
            lowerResNoRefresh != -1 -> lowerResNoRefresh
            else -> sortedStreams.lastIndex
        }
    }

    fun getSortedAudioStreams(
        audioStreams: List<AudioStream>?,
        defaultFormat: MediaFormat?,
        limitDataUsage: Boolean,
        preferredLanguageIso3: String,
        preferOriginalAudio: Boolean,
        preferDescriptiveAudio: Boolean
    ): List<AudioStream> {
        if (audioStreams.isNullOrEmpty()) return emptyList()
        val formatRanking = if (limitDataUsage) AUDIO_FORMAT_EFFICIENCY_RANKING else AUDIO_FORMAT_QUALITY_RANKING
        val trackRanking = if (preferDescriptiveAudio) AUDIO_TRACK_RANKING_DESCRIPTIVE else AUDIO_TRACK_RANKING

        val comparator = Comparator<AudioStream> { a, b ->
            if (preferOriginalAudio) {
                val origCmp = (a.audioTrackType == AudioTrackType.ORIGINAL)
                    .compareTo(b.audioTrackType == AudioTrackType.ORIGINAL)
                if (origCmp != 0) return@Comparator origCmp
            }
            val aLang = a.audioLocale?.isO3Language == preferredLanguageIso3
            val bLang = b.audioLocale?.isO3Language == preferredLanguageIso3
            val langCmp = aLang.compareTo(bLang)
            if (langCmp != 0) return@Comparator langCmp

            val trackCmp = trackRanking.indexOf(a.audioTrackType) - trackRanking.indexOf(b.audioTrackType)
            if (trackCmp != 0) return@Comparator trackCmp

            val fmtMatchCmp = (a.format == defaultFormat).compareTo(b.format == defaultFormat)
            if (fmtMatchCmp != 0) return@Comparator fmtMatchCmp

            val bitrateCmp = if (limitDataUsage) {
                b.averageBitrate.compareTo(a.averageBitrate)
            } else {
                a.averageBitrate.compareTo(b.averageBitrate)
            }
            if (bitrateCmp != 0) return@Comparator bitrateCmp

            formatRanking.indexOf(a.format) - formatRanking.indexOf(b.format)
        }
        return audioStreams.sortedWith(comparator.reversed())
    }

    fun findCompatibleAudioForVideo(
        audioStreams: List<AudioStream>,
        videoStream: VideoStream,
        limitDataUsage: Boolean
    ): AudioStream? {
        if (!videoStream.isVideoOnly) return null
        val candidates = when (videoStream.format) {
            MediaFormat.MPEG_4 -> audioStreams.filter { it.format == MediaFormat.M4A }
            MediaFormat.WEBM -> audioStreams.filter {
                it.format == MediaFormat.WEBMA || it.format == MediaFormat.WEBMA_OPUS
            }
            else -> emptyList()
        }.ifEmpty { audioStreams }

        return if (limitDataUsage) {
            candidates.minByOrNull { it.averageBitrate }
        } else {
            candidates.maxByOrNull { it.averageBitrate }
        }
    }

    fun mediaFormatFromKey(key: String?): MediaFormat? = when (key) {
        VideoAudioPreferences.VIDEO_MP4 -> MediaFormat.MPEG_4
        VideoAudioPreferences.VIDEO_WEBM -> MediaFormat.WEBM
        VideoAudioPreferences.VIDEO_3GP -> MediaFormat.v3GPP
        VideoAudioPreferences.AUDIO_M4A -> MediaFormat.M4A
        VideoAudioPreferences.AUDIO_WEBM -> MediaFormat.WEBMA
        else -> null
    }

    fun compareResolution(r1: String, r2: String): Int {
        return try {
            val n1 = r1.replace(Regex("0p\\d+$"), "1").replace(Regex("[^\\d.]"), "").toInt()
            val n2 = r2.replace(Regex("0p\\d+$"), "1").replace(Regex("[^\\d.]"), "").toInt()
            n1 - n2
        } catch (_: NumberFormatException) {
            1
        }
    }

    fun sanitizeFilename(
        title: String,
        charsetSetting: String = DownloadPreferences.DEFAULT_FILE_CHARSET,
        replacementChar: String = DownloadPreferences.DEFAULT_REPLACEMENT_CHARACTER
    ): String {
        val pattern = when (charsetSetting) {
            DownloadPreferences.CHARSET_LETTERS_AND_DIGITS -> "[^\\w\\d]+"
            DownloadPreferences.CHARSET_MOST_SPECIAL -> "[\\n\\r|?*<\":\\\\>/']+"
            else -> charsetSetting
        }
        val safeReplacement = Regex.escapeReplacement(replacementChar.ifEmpty { "_" })
        val cleaned = title.replace(Regex(pattern), safeReplacement).trim().trimEnd('.')
        return cleaned.ifBlank { "newpipe_download" }
    }
}
