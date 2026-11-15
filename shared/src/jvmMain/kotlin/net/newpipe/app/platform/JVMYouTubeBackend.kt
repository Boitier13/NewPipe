/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import java.io.IOException
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.newpipe.app.model.AudioStreamOption
import net.newpipe.app.model.ChannelDetails
import net.newpipe.app.model.ChannelSummary
import net.newpipe.app.model.PlaylistSummary
import net.newpipe.app.model.SearchFilter
import net.newpipe.app.model.SearchPageResult
import net.newpipe.app.model.SearchResultItem
import net.newpipe.app.model.StreamSummary
import net.newpipe.app.model.VideoDetails
import net.newpipe.app.model.VideoStreamOption
import net.newpipe.app.preferences.ContentPreferences
import net.newpipe.app.preferences.VideoAudioPreferences
import org.jsoup.Jsoup
import org.koin.core.annotation.Singleton
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

@Singleton(binds = [YouTubeBackend::class])
class JVMYouTubeBackend(
    private val settings: Settings,
    private val downloader: DesktopDownloader,
    private val infoCache: DesktopInfoCache,
    private val dataSaverController: DataSaverController
) : YouTubeBackend {

    private data class CachedPageContext(
        val page: Page,
        val query: String = "",
        val filter: SearchFilter = SearchFilter.ALL,
        val url: String = "",
        val tabHandler: ListLinkHandler? = null
    )

    private val pageTokens = ConcurrentHashMap<String, CachedPageContext>()
    private val backendExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "NewPipe-Extractor").apply { isDaemon = true }
    }

    @Volatile
    private var initialized = false

    init {
        dataSaverController.onDataSaverImageQualityChanged = { quality ->
            DesktopImageStrategy.setPreferredImageQuality(quality)
        }
        ensureInitialized()
    }

    @Synchronized
    private fun ensureInitialized() {
        if (!initialized) {
            applyLocalizationAndContentPrefsInternal(initNewPipe = true)
            applyImageQuality(dataSaverController.effectiveImageQuality())
            applyYoutubeRestrictedMode(
                settings.getBoolean(
                    ContentPreferences.KEY_YOUTUBE_RESTRICTED_MODE,
                    ContentPreferences.DEFAULT_YOUTUBE_RESTRICTED_MODE
                )
            )
            initialized = true
        }
    }

    override fun applyLocalizationAndContentPrefs() {
        applyLocalizationAndContentPrefsInternal(initNewPipe = true)
    }

    private fun applyLocalizationAndContentPrefsInternal(initNewPipe: Boolean) {
        val langPref = settings.getString(
            ContentPreferences.KEY_CONTENT_LANGUAGE,
            ContentPreferences.DEFAULT_LOCALIZATION
        )
        val countryPref = settings.getString(
            ContentPreferences.KEY_CONTENT_COUNTRY,
            ContentPreferences.DEFAULT_LOCALIZATION
        )

        val locale = if (langPref == ContentPreferences.DEFAULT_LOCALIZATION) {
            Locale.getDefault()
        } else {
            Locale.forLanguageTag(langPref)
        }

        val country = if (countryPref == ContentPreferences.DEFAULT_LOCALIZATION) {
            ContentCountry(Locale.getDefault().country.ifBlank { "US" })
        } else {
            ContentCountry(countryPref)
        }

        val localization = Localization.fromLocale(locale)
        if (initNewPipe) {
            NewPipe.init(downloader, localization, country)
        } else {
            NewPipe.setPreferredLocalization(localization)
            NewPipe.setPreferredContentCountry(country)
        }
        infoCache.clear()
    }

    override fun applyImageQuality(quality: String) {
        val effective = if (dataSaverController.isDataSaverEnabled.value) {
            ContentPreferences.IMAGE_QUALITY_LOW
        } else {
            quality
        }
        DesktopImageStrategy.setPreferredImageQuality(effective)
    }

    override fun applyYoutubeRestrictedMode(enabled: Boolean) {
        downloader.updateYoutubeRestrictedModeCookies(enabled)
        infoCache.clear()
    }

    override fun clearMetadataCache() {
        infoCache.clear()
        pageTokens.clear()
    }

    override fun clearRecaptchaCookies() {
        downloader.clearRecaptchaCookies()
    }

    override suspend fun getTrendingVideos(pageToken: String?): Result<SearchPageResult> =
        withContext(Dispatchers.IO) {
            ensureInitialized()
            safeBackendCall("trending") {
                val yt = ServiceList.YouTube
                val kioskList = yt.kioskList

                val items: List<InfoItem>
                val nextPage: Page?
                val hasNext: Boolean
                val usedKioskUrl: String

                if (pageToken != null) {
                    val ctx = pageTokens.remove(pageToken)
                        ?: error("Expired page token")
                    usedKioskUrl = ctx.url
                    val page = KioskInfo.getMoreItems(yt, usedKioskUrl, ctx.page)
                    items = page.items
                    nextPage = page.nextPage
                    hasNext = page.hasNextPage()
                } else {
                    val candidateIds = listOf(
                        "Trending",
                        "trending_music",
                        "trending_gaming",
                        "trending_movies_and_shows",
                        kioskList.defaultKioskId
                    ).distinct()

                    var loadedInfo: KioskInfo? = null
                    var loadedUrl: String? = null
                    var lastError: Throwable? = null

                    for (kioskId in candidateIds) {
                        val factory = runCatching {
                            kioskList.getListLinkHandlerFactoryByType(kioskId)
                        }.getOrNull() ?: continue
                        val url = runCatching { factory.fromId(kioskId).url }.getOrNull() ?: continue
                        val cached = infoCache.get(yt.serviceId, url, DesktopInfoCache.Type.KIOSK) as? KioskInfo
                        val attempt = if (cached != null) {
                            Result.success(cached)
                        } else {
                            runCatching {
                                KioskInfo.getInfo(yt, url).also {
                                    infoCache.put(yt.serviceId, url, it, DesktopInfoCache.Type.KIOSK)
                                }
                            }
                        }
                        val info = attempt.getOrNull()
                        if (info != null && info.relatedItems.isNotEmpty()) {
                            loadedInfo = info
                            loadedUrl = url
                            break
                        } else if (attempt.isFailure) {
                            lastError = attempt.exceptionOrNull()
                        }
                    }

                    val info = loadedInfo ?: throw (lastError ?: IllegalStateException("No YouTube kiosk items available"))
                    usedKioskUrl = loadedUrl.orEmpty()
                    items = info.relatedItems
                    nextPage = info.nextPage
                    hasNext = info.hasNextPage()
                }

                val nextToken = if (hasNext && nextPage != null && usedKioskUrl.isNotBlank()) {
                    savePageToken(CachedPageContext(page = nextPage, url = usedKioskUrl))
                } else {
                    null
                }
                SearchPageResult(
                    items = items.mapNotNull(::toSearchResultItem),
                    hasNextPage = hasNext && nextToken != null,
                    nextPageToken = nextToken
                )
            }
        }

    override suspend fun search(
        query: String,
        filter: SearchFilter,
        pageToken: String?
    ): Result<SearchPageResult> = withContext(Dispatchers.IO) {
        ensureInitialized()
        safeBackendCall("search '$query'") {
            val yt = ServiceList.YouTube
            val contentFilters = listOfNotNull(filter.extractorFilter)
            val handler = yt.searchQHFactory.fromQuery(query, contentFilters, "")

            val items: List<InfoItem>
            val nextPage: Page?
            val hasNext: Boolean

            if (pageToken != null) {
                val ctx = pageTokens.remove(pageToken)
                    ?: error("Expired page token")
                val page = SearchInfo.getMoreItems(yt, handler, ctx.page)
                items = page.items
                nextPage = page.nextPage
                hasNext = page.hasNextPage()
            } else {
                val info = SearchInfo.getInfo(yt, handler)
                items = info.relatedItems
                nextPage = info.nextPage
                hasNext = info.hasNextPage()
            }

            val nextToken = if (hasNext && nextPage != null) {
                savePageToken(CachedPageContext(page = nextPage, query = query, filter = filter))
            } else {
                null
            }
            SearchPageResult(
                items = items.mapNotNull(::toSearchResultItem),
                hasNextPage = hasNext && nextToken != null,
                nextPageToken = nextToken
            )
        }
    }

    override suspend fun getSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        ensureInitialized()
        runCatching {
            withTimeout(8_000L) {
                ServiceList.YouTube.suggestionExtractor?.suggestionList(query).orEmpty()
            }
        }.getOrElse { emptyList() }
    }

    override suspend fun getVideoDetails(
        url: String,
        forceReload: Boolean
    ): Result<VideoDetails> = withContext(Dispatchers.IO) {
        ensureInitialized()
        safeBackendCall("video '$url'") {
            println("[NewPipe] Extracting video details: $url")
            val yt = ServiceList.YouTube
            val cached = if (!forceReload) {
                infoCache.get(yt.serviceId, url, DesktopInfoCache.Type.STREAM) as? StreamInfo
            } else {
                infoCache.remove(yt.serviceId, url, DesktopInfoCache.Type.STREAM)
                null
            }
            val info = cached ?: StreamInfo.getInfo(yt, url).also {
                infoCache.put(yt.serviceId, url, it, DesktopInfoCache.Type.STREAM)
            }
            mapStreamInfoToVideoDetails(info).also {
                println("[NewPipe] Extracted '${it.title}': ${it.videoStreams.size} video streams, ${it.audioStreams.size} audio streams")
            }
        }
    }

    private suspend fun <T> safeBackendCall(
        label: String,
        timeoutMs: Long = 25_000L,
        block: () -> T
    ): Result<T> {
        return try {
            val value = withTimeout(timeoutMs) {
                suspendCancellableCoroutine<T> { cont ->
                    val future = backendExecutor.submit {
                        try {
                            val res = block()
                            if (cont.isActive) cont.resume(res)
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resumeWithException(t)
                        }
                    }
                    cont.invokeOnCancellation { future.cancel(true) }
                }
            }
            Result.success(value)
        } catch (t: TimeoutCancellationException) {
            val err = IOException("Timed out after ${timeoutMs / 1000}s while loading $label", t)
            Logger.e(err) { err.message.orEmpty() }
            System.err.println("[NewPipe] ${err.message}")
            Result.failure(err)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Logger.e(t) { "Failed loading $label" }
            System.err.println("[NewPipe] Failed loading $label: ${t.javaClass.simpleName}: ${t.message}")
            t.printStackTrace(System.err)
            Result.failure(t)
        }
    }

    override suspend fun getChannelDetails(
        url: String,
        forceReload: Boolean,
        pageToken: String?
    ): Result<ChannelDetails> = withContext(Dispatchers.IO) {
        ensureInitialized()
        runCatching {
            val yt = ServiceList.YouTube
            if (pageToken != null) {
                val ctx = pageTokens.remove(pageToken) ?: error("Expired channel page token")
                val channelInfo = (infoCache.get(yt.serviceId, url, DesktopInfoCache.Type.CHANNEL) as? ChannelInfo)
                    ?: ChannelInfo.getInfo(yt, url)
                val tabHandler = ctx.tabHandler ?: pickVideosTabHandler(channelInfo)
                val more = tabHandler?.let { ChannelTabInfo.getMoreItems(yt, it, ctx.page) }
                val nextTok = if (more != null && more.hasNextPage() && more.nextPage != null) {
                    savePageToken(CachedPageContext(page = more.nextPage!!, url = url, tabHandler = tabHandler))
                } else {
                    null
                }
                return@runCatching ChannelDetails(
                    serviceId = channelInfo.serviceId,
                    url = channelInfo.url,
                    name = channelInfo.name,
                    description = channelInfo.description.orEmpty(),
                    avatarUrl = DesktopImageStrategy.choosePreferredImage(channelInfo.avatars),
                    bannerUrl = DesktopImageStrategy.choosePreferredImage(channelInfo.banners),
                    subscriberCount = channelInfo.subscriberCount,
                    videos = more?.items?.filterIsInstance<StreamInfoItem>()?.map(::toStreamSummary).orEmpty(),
                    hasNextPage = nextTok != null,
                    nextPageToken = nextTok
                )
            }

            val cachedChannel = if (!forceReload) {
                infoCache.get(yt.serviceId, url, DesktopInfoCache.Type.CHANNEL) as? ChannelInfo
            } else {
                infoCache.remove(yt.serviceId, url, DesktopInfoCache.Type.CHANNEL)
                null
            }
            val channelInfo = cachedChannel ?: ChannelInfo.getInfo(yt, url).also {
                infoCache.put(yt.serviceId, url, it, DesktopInfoCache.Type.CHANNEL)
            }

            val tabHandler = pickVideosTabHandler(channelInfo)
            val tabInfo = tabHandler?.let { handler ->
                runCatching { ChannelTabInfo.getInfo(yt, handler) }.getOrNull()
            }
            val nextTok = if (tabInfo != null && tabInfo.hasNextPage() && tabInfo.nextPage != null) {
                savePageToken(CachedPageContext(page = tabInfo.nextPage!!, url = url, tabHandler = tabHandler))
            } else {
                null
            }

            ChannelDetails(
                serviceId = channelInfo.serviceId,
                url = channelInfo.url,
                name = channelInfo.name,
                description = channelInfo.description.orEmpty(),
                avatarUrl = DesktopImageStrategy.choosePreferredImage(channelInfo.avatars),
                bannerUrl = DesktopImageStrategy.choosePreferredImage(channelInfo.banners),
                subscriberCount = channelInfo.subscriberCount,
                videos = tabInfo?.relatedItems?.filterIsInstance<StreamInfoItem>()?.map(::toStreamSummary).orEmpty(),
                hasNextPage = nextTok != null,
                nextPageToken = nextTok
            )
        }.onFailure {
            Logger.e(it) { "Failed to load channel $url" }
        }
    }

    override suspend fun getPlaylistVideos(
        url: String,
        pageToken: String?
    ): Result<Pair<PlaylistSummary, SearchPageResult>> = withContext(Dispatchers.IO) {
        ensureInitialized()
        runCatching {
            val yt = ServiceList.YouTube
            val info = (infoCache.get(yt.serviceId, url, DesktopInfoCache.Type.PLAYLIST) as? PlaylistInfo)
                ?: PlaylistInfo.getInfo(yt, url).also {
                    infoCache.put(yt.serviceId, url, it, DesktopInfoCache.Type.PLAYLIST)
                }

            val items: List<StreamInfoItem>
            val nextPage: Page?
            val hasNext: Boolean

            if (pageToken != null) {
                val ctx = pageTokens.remove(pageToken) ?: error("Expired playlist page token")
                val more = PlaylistInfo.getMoreItems(yt, url, ctx.page)
                items = more.items
                nextPage = more.nextPage
                hasNext = more.hasNextPage()
            } else {
                items = info.relatedItems
                nextPage = info.nextPage
                hasNext = info.hasNextPage()
            }

            val nextTok = if (hasNext && nextPage != null) {
                savePageToken(CachedPageContext(page = nextPage, url = url))
            } else {
                null
            }

            val summary = PlaylistSummary(
                serviceId = info.serviceId,
                url = info.url,
                name = info.name,
                uploaderName = info.uploaderName,
                thumbnailUrl = DesktopImageStrategy.choosePreferredImage(info.thumbnails),
                streamCount = info.streamCount
            )
            val pageResult = SearchPageResult(
                items = items.map { SearchResultItem.Video(toStreamSummary(it)) },
                hasNextPage = nextTok != null,
                nextPageToken = nextTok
            )
            summary to pageResult
        }
    }

    override suspend fun getChannelLatestVideos(channelUrl: String): Result<List<StreamSummary>> =
        withContext(Dispatchers.IO) {
            ensureInitialized()
            runCatching {
                val yt = ServiceList.YouTube
                val channelInfo = ChannelInfo.getInfo(yt, channelUrl)
                val tabHandler = pickVideosTabHandler(channelInfo) ?: return@runCatching emptyList()
                val tabInfo = ChannelTabInfo.getInfo(yt, tabHandler)
                tabInfo.relatedItems.filterIsInstance<StreamInfoItem>().map(::toStreamSummary)
            }
        }

    private fun pickVideosTabHandler(channelInfo: ChannelInfo): ListLinkHandler? {
        val tabs = channelInfo.tabs
        return tabs.firstOrNull { it.contentFilters.firstOrNull() == ChannelTabs.VIDEOS }
            ?: tabs.firstOrNull { tab ->
                val filter = tab.contentFilters.firstOrNull()
                filter == ChannelTabs.LIVESTREAMS || filter == ChannelTabs.SHORTS || filter == ChannelTabs.TRACKS
            }
            ?: tabs.firstOrNull()
    }

    private fun mapStreamInfoToVideoDetails(info: StreamInfo): VideoDetails {
        val dataSaver = dataSaverController.isDataSaverEnabled.value
        val showHigherRes = settings.getBoolean(
            VideoAudioPreferences.KEY_SHOW_HIGHER_RESOLUTIONS,
            VideoAudioPreferences.DEFAULT_SHOW_HIGHER_RESOLUTIONS
        ) && !dataSaver

        val defaultVideoFormat = DesktopStreamHelper.mediaFormatFromKey(
            settings.getString(
                VideoAudioPreferences.KEY_DEFAULT_VIDEO_FORMAT,
                VideoAudioPreferences.DEFAULT_VIDEO_FORMAT
            )
        )
        val defaultAudioFormat = DesktopStreamHelper.mediaFormatFromKey(
            settings.getString(
                VideoAudioPreferences.KEY_DEFAULT_AUDIO_FORMAT,
                VideoAudioPreferences.DEFAULT_AUDIO_FORMAT
            )
        )
        val preferOriginalAudio = settings.getBoolean(
            VideoAudioPreferences.KEY_PREFER_ORIGINAL_AUDIO,
            VideoAudioPreferences.DEFAULT_PREFER_ORIGINAL_AUDIO
        )
        val preferDescriptiveAudio = settings.getBoolean(
            VideoAudioPreferences.KEY_PREFER_DESCRIPTIVE_AUDIO,
            VideoAudioPreferences.DEFAULT_PREFER_DESCRIPTIVE_AUDIO
        )

        val rawVideoStreams = DesktopStreamHelper.filterPlayableHttpStreams(
            info.videoStreams,
            info.serviceId
        )
        val rawVideoOnlyStreams = DesktopStreamHelper.filterPlayableHttpStreams(
            info.videoOnlyStreams,
            info.serviceId
        )
        val rawAudioStreams = DesktopStreamHelper.filterPlayableHttpStreams(
            info.audioStreams,
            info.serviceId
        )

        val sortedAudio = DesktopStreamHelper.getSortedAudioStreams(
            audioStreams = rawAudioStreams,
            defaultFormat = defaultAudioFormat,
            limitDataUsage = dataSaver,
            preferredLanguageIso3 = Locale.getDefault().isO3Language,
            preferOriginalAudio = preferOriginalAudio,
            preferDescriptiveAudio = preferDescriptiveAudio
        )

        val sortedVideo = DesktopStreamHelper.getSortedVideoStreams(
            defaultFormat = defaultVideoFormat,
            showHigherResolutions = showHigherRes,
            videoStreams = rawVideoStreams,
            videoOnlyStreams = rawVideoOnlyStreams,
            preferVideoOnlyStreams = true
        )

        val videoOptions = sortedVideo.mapIndexed { idx, vs ->
            val pairedAudio = if (vs.isVideoOnly()) {
                DesktopStreamHelper.findCompatibleAudioForVideo(sortedAudio, vs, dataSaver)
            } else {
                null
            }
            toVideoStreamOption(idx, vs, pairedAudio, info.duration)
        }

        val audioOptions = sortedAudio.mapIndexed { idx, audio ->
            toAudioStreamOption(idx, audio, info.duration)
        }

        val plainDescription = extractPlainDescription(info.description)
        val isoDate = runCatching {
            info.uploadDate?.offsetDateTime()?.format(DateTimeFormatter.ISO_DATE)
        }.getOrNull()

        val isLive = info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.AUDIO_LIVE_STREAM

        return VideoDetails(
            serviceId = info.serviceId,
            url = info.url,
            title = info.name,
            description = plainDescription,
            uploaderName = info.uploaderName.orEmpty(),
            uploaderUrl = info.uploaderUrl,
            uploaderAvatarUrl = DesktopImageStrategy.choosePreferredImage(info.uploaderAvatars),
            subscriberCount = info.uploaderSubscriberCount,
            viewCount = info.viewCount,
            uploadDateText = info.textualUploadDate,
            isoUploadDate = isoDate,
            category = info.category.orEmpty(),
            durationSeconds = info.duration,
            thumbnailUrl = DesktopImageStrategy.choosePreferredImage(info.thumbnails),
            isLive = isLive,
            videoStreams = videoOptions,
            audioStreams = audioOptions,
            relatedVideos = info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .map(::toStreamSummary)
        )
    }

    private fun toVideoStreamOption(
        index: Int,
        stream: VideoStream,
        pairedAudio: AudioStream?,
        durationSeconds: Long
    ): VideoStreamOption {
        val format = stream.format ?: MediaFormat.MPEG_4
        val audioBytes = pairedAudio?.let { estimateStreamSize(it.averageBitrate * 1000, durationSeconds) } ?: -1L
        return VideoStreamOption(
            id = "v_${index}_${stream.getResolution()}_${format.name}",
            resolution = stream.getResolution(),
            formatLabel = format.getName(),
            formatKey = format.name,
            isVideoOnly = stream.isVideoOnly(),
            videoUrl = stream.content,
            pairedAudioUrl = pairedAudio?.content,
            pairedAudioFormatKey = pairedAudio?.format?.name,
            fps = stream.fps,
            approxSizeBytes = audioBytes
        )
    }

    private fun toAudioStreamOption(
        index: Int,
        stream: AudioStream,
        durationSeconds: Long
    ): AudioStreamOption {
        val format = stream.format ?: MediaFormat.M4A
        val approxBytes = estimateStreamSize(stream.averageBitrate * 1000, durationSeconds)
        return AudioStreamOption(
            id = "a_${index}_${format.name}_${stream.averageBitrate}",
            bitrateKbps = stream.averageBitrate,
            formatLabel = format.getName(),
            formatKey = format.name,
            url = stream.content,
            localeLabel = stream.audioLocale?.displayLanguage?.takeIf { it.isNotBlank() },
            trackTypeLabel = stream.audioTrackType?.name,
            approxSizeBytes = if (durationSeconds > 0 && stream.averageBitrate > 0) approxBytes else -1L
        )
    }

    private fun estimateStreamSize(bitsPerSecond: Int, durationSeconds: Long): Long {
        if (bitsPerSecond <= 0 || durationSeconds <= 0) return 0L
        return (bitsPerSecond.toLong() * durationSeconds) / 8L
    }

    private fun extractPlainDescription(description: Description?): String {
        if (description == null || description == Description.EMPTY_DESCRIPTION) return ""
        val content = description.content().orEmpty()
        return when (description.type()) {
            Description.Type.HTML -> Jsoup.parse(content).wholeText().trim()
            else -> content.trim()
        }
    }

    private fun toSearchResultItem(item: InfoItem): SearchResultItem? = when (item) {
        is StreamInfoItem -> SearchResultItem.Video(toStreamSummary(item))
        is ChannelInfoItem -> SearchResultItem.Channel(
            ChannelSummary(
                serviceId = item.serviceId,
                url = item.url,
                name = item.name,
                description = item.description.orEmpty(),
                thumbnailUrl = DesktopImageStrategy.choosePreferredImage(item.thumbnails),
                subscriberCount = item.subscriberCount,
                streamCount = item.streamCount
            )
        )
        is PlaylistInfoItem -> SearchResultItem.Playlist(
            PlaylistSummary(
                serviceId = item.serviceId,
                url = item.url,
                name = item.name,
                uploaderName = item.uploaderName,
                thumbnailUrl = DesktopImageStrategy.choosePreferredImage(item.thumbnails),
                streamCount = item.streamCount
            )
        )
        else -> null
    }

    private fun toStreamSummary(item: StreamInfoItem): StreamSummary {
        val isLive = item.streamType == StreamType.LIVE_STREAM ||
            item.streamType == StreamType.AUDIO_LIVE_STREAM
        return StreamSummary(
            serviceId = item.serviceId,
            url = item.url,
            title = item.name,
            uploaderName = item.uploaderName.orEmpty(),
            uploaderUrl = item.uploaderUrl,
            durationSeconds = item.duration,
            viewCount = item.viewCount,
            textualUploadDate = item.textualUploadDate,
            thumbnailUrl = DesktopImageStrategy.choosePreferredImage(item.thumbnails),
            isLive = isLive
        )
    }

    private fun savePageToken(context: CachedPageContext): String {
        val token = UUID.randomUUID().toString()
        if (pageTokens.size > 100) {
            pageTokens.clear()
        }
        pageTokens[token] = context
        return token
    }
}
