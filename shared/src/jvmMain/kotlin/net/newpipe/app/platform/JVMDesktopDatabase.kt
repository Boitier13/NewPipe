/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package net.newpipe.app.platform

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.newpipe.app.BuildConfig
import net.newpipe.app.model.ChannelDetails
import net.newpipe.app.model.ChannelSummary
import net.newpipe.app.model.SubscriptionRecord
import net.newpipe.app.model.VideoDetails
import net.newpipe.app.model.WatchHistoryRecord
import org.koin.core.annotation.Singleton

@Serializable
private data class NewPipeSubscriptionExportData(
    val subscriptions: List<NewPipeSubscriptionExportItem>,
    @SerialName("app_version")
    val appVersion: String = BuildConfig.VERSION_NAME,
    @SerialName("app_version_int")
    val appVersionInt: Int = 1017
)

@Serializable
internal data class NewPipeSubscriptionExportItem(
    @SerialName("service_id")
    val serviceId: Int = 0,
    val url: String,
    val name: String
)

/**
 * Pure Kotlin SQLite persistence layer backed by [BundledSQLiteDriver] using NewPipe's v9 schema.
 */
@Singleton(binds = [DesktopDatabaseGateway::class])
class JVMDesktopDatabase(
    private val settings: Settings
) : DesktopDatabaseGateway {

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val driver = BundledSQLiteDriver()
    private var connection: SQLiteConnection? = null

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val _subscriptions = MutableStateFlow<List<SubscriptionRecord>>(emptyList())
    override val subscriptions: StateFlow<List<SubscriptionRecord>> = _subscriptions.asStateFlow()

    private val _watchHistory = MutableStateFlow<List<WatchHistoryRecord>>(emptyList())
    override val watchHistory: StateFlow<List<WatchHistoryRecord>> = _watchHistory.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    override val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    init {
        scope.launch {
            runCatching {
                mutex.withLock {
                    val conn = ensureOpenLocked()
                    refreshAllLocked(conn)
                }
            }.onFailure {
                Logger.e(it) { "Failed to initialize desktop SQLite database" }
            }
        }
    }

    private fun ensureOpenLocked(): SQLiteConnection {
        connection?.let { return it }
        val dbFile = WindowsAppPaths.databaseFile()
        dbFile.parentFile?.mkdirs()
        val conn = driver.open(dbFile.absolutePath)
        try {
            conn.execSQL("PRAGMA foreign_keys = ON")
            conn.execSQL("PRAGMA journal_mode = WAL")
            createSchema(conn)
            connection = conn
            return conn
        } catch (failure: Exception) {
            runCatching { conn.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun createSchema(conn: SQLiteConnection) {
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `subscriptions` (
                `uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `service_id` INTEGER NOT NULL,
                `url` TEXT,
                `name` TEXT,
                `avatar_url` TEXT,
                `subscriber_count` INTEGER,
                `description` TEXT,
                `notification_mode` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        conn.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_subscriptions_service_id_url` ON `subscriptions` (`service_id`, `url`)"
        )

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `search_history` (
                `creation_date` INTEGER,
                `service_id` INTEGER NOT NULL,
                `search` TEXT,
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
            )
            """.trimIndent()
        )
        conn.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_search_history_search` ON `search_history` (`search`)"
        )

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `streams` (
                `uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `service_id` INTEGER NOT NULL,
                `url` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `stream_type` TEXT NOT NULL,
                `duration` INTEGER NOT NULL,
                `uploader` TEXT NOT NULL,
                `uploader_url` TEXT,
                `thumbnail_url` TEXT,
                `view_count` INTEGER,
                `textual_upload_date` TEXT,
                `upload_date` INTEGER,
                `is_upload_date_approximation` INTEGER
            )
            """.trimIndent()
        )
        conn.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_streams_service_id_url` ON `streams` (`service_id`, `url`)"
        )

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `stream_history` (
                `stream_id` INTEGER NOT NULL,
                `access_date` INTEGER NOT NULL,
                `repeat_count` INTEGER NOT NULL,
                PRIMARY KEY(`stream_id`, `access_date`),
                FOREIGN KEY(`stream_id`) REFERENCES `streams`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent()
        )
        conn.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_stream_history_stream_id` ON `stream_history` (`stream_id`)"
        )

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `stream_state` (
                `stream_id` INTEGER NOT NULL,
                `progress_time` INTEGER NOT NULL,
                PRIMARY KEY(`stream_id`),
                FOREIGN KEY(`stream_id`) REFERENCES `streams`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE
            )
            """.trimIndent()
        )

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playlists` (
                `uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT,
                `is_thumbnail_permanent` INTEGER NOT NULL,
                `thumbnail_stream_id` INTEGER NOT NULL,
                `display_index` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `playlist_stream_join` (
                `playlist_id` INTEGER NOT NULL,
                `stream_id` INTEGER NOT NULL,
                `join_index` INTEGER NOT NULL,
                PRIMARY KEY(`playlist_id`, `join_index`),
                FOREIGN KEY(`playlist_id`) REFERENCES `playlists`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
                FOREIGN KEY(`stream_id`) REFERENCES `streams`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
            )
            """.trimIndent()
        )
        conn.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_stream_join_playlist_id_join_index` ON `playlist_stream_join` (`playlist_id`, `join_index`)"
        )
        conn.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_playlist_stream_join_stream_id` ON `playlist_stream_join` (`stream_id`)"
        )

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `remote_playlists` (
                `uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `service_id` INTEGER NOT NULL,
                `name` TEXT,
                `url` TEXT,
                `thumbnail_url` TEXT,
                `uploader` TEXT,
                `display_index` INTEGER NOT NULL,
                `stream_count` INTEGER
            )
            """.trimIndent()
        )
        conn.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_remote_playlists_service_id_url` ON `remote_playlists` (`service_id`, `url`)"
        )

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `feed` (
                `stream_id` INTEGER NOT NULL,
                `subscription_id` INTEGER NOT NULL,
                PRIMARY KEY(`stream_id`, `subscription_id`),
                FOREIGN KEY(`stream_id`) REFERENCES `streams`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
                FOREIGN KEY(`subscription_id`) REFERENCES `subscriptions`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
            )
            """.trimIndent()
        )
        conn.execSQL("CREATE INDEX IF NOT EXISTS `index_feed_subscription_id` ON `feed` (`subscription_id`)")

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `feed_group` (
                `uid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `icon_id` INTEGER NOT NULL,
                `sort_order` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        conn.execSQL("CREATE INDEX IF NOT EXISTS `index_feed_group_sort_order` ON `feed_group` (`sort_order`)")

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `feed_group_subscription_join` (
                `group_id` INTEGER NOT NULL,
                `subscription_id` INTEGER NOT NULL,
                PRIMARY KEY(`group_id`, `subscription_id`),
                FOREIGN KEY(`group_id`) REFERENCES `feed_group`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED,
                FOREIGN KEY(`subscription_id`) REFERENCES `subscriptions`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
            )
            """.trimIndent()
        )
        conn.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_feed_group_subscription_join_subscription_id` ON `feed_group_subscription_join` (`subscription_id`)"
        )

        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `feed_last_updated` (
                `subscription_id` INTEGER NOT NULL,
                `last_updated` INTEGER,
                PRIMARY KEY(`subscription_id`),
                FOREIGN KEY(`subscription_id`) REFERENCES `subscriptions`(`uid`) ON UPDATE CASCADE ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED
            )
            """.trimIndent()
        )
        conn.execSQL("PRAGMA user_version = 9")
    }

    private fun refreshAllLocked(conn: SQLiteConnection) {
        val subscriptions = querySubscriptionsLocked(conn)
        val watchHistory = queryWatchHistoryLocked(conn)
        val searchHistory = querySearchHistoryLocked(conn)
        _subscriptions.value = subscriptions
        _watchHistory.value = watchHistory
        _searchHistory.value = searchHistory
    }

    private fun querySubscriptionsLocked(conn: SQLiteConnection): List<SubscriptionRecord> {
        val list = mutableListOf<SubscriptionRecord>()
        conn.prepare(
            """
            SELECT uid, service_id, url, name, avatar_url, subscriber_count, description
            FROM subscriptions
            ORDER BY name COLLATE NOCASE ASC
            """.trimIndent()
        ).use { stmt ->
            while (stmt.step()) {
                val url = stmt.getNullableText(2) ?: continue
                list.add(
                    SubscriptionRecord(
                        uid = stmt.getLong(0),
                        serviceId = stmt.getLong(1).toInt(),
                        url = url,
                        name = stmt.getNullableText(3).orEmpty(),
                        avatarUrl = stmt.getNullableText(4),
                        subscriberCount = stmt.getNullableLong(5),
                        description = stmt.getNullableText(6)
                    )
                )
            }
        }
        return list
    }

    private fun queryWatchHistoryLocked(conn: SQLiteConnection): List<WatchHistoryRecord> {
        val list = mutableListOf<WatchHistoryRecord>()
        conn.prepare(
            """
            SELECT
                s.uid,
                s.service_id,
                s.url,
                s.title,
                s.uploader,
                s.uploader_url,
                s.thumbnail_url,
                s.duration,
                sh.access_date,
                sh.repeat_count,
                COALESCE(ss.progress_time, 0)
            FROM stream_history sh
            INNER JOIN streams s ON s.uid = sh.stream_id
            LEFT JOIN stream_state ss ON ss.stream_id = s.uid
            ORDER BY sh.access_date DESC
            LIMIT 500
            """.trimIndent()
        ).use { stmt ->
            while (stmt.step()) {
                list.add(
                    WatchHistoryRecord(
                        streamId = stmt.getLong(0),
                        serviceId = stmt.getLong(1).toInt(),
                        url = stmt.getNullableText(2).orEmpty(),
                        title = stmt.getNullableText(3).orEmpty(),
                        uploader = stmt.getNullableText(4).orEmpty(),
                        uploaderUrl = stmt.getNullableText(5),
                        thumbnailUrl = stmt.getNullableText(6),
                        durationSeconds = stmt.getLong(7),
                        accessDateMillis = stmt.getLong(8),
                        repeatCount = stmt.getLong(9),
                        progressMillis = stmt.getLong(10)
                    )
                )
            }
        }
        return list
    }

    private fun querySearchHistoryLocked(conn: SQLiteConnection): List<String> {
        val list = mutableListOf<String>()
        conn.prepare(
            """
            SELECT search
            FROM search_history
            WHERE search IS NOT NULL AND search != ''
            ORDER BY creation_date DESC, id DESC
            LIMIT 200
            """.trimIndent()
        ).use { stmt ->
            while (stmt.step()) {
                val text = stmt.getNullableText(0)
                if (!text.isNullOrBlank()) {
                    list.add(text)
                }
            }
        }
        return list
    }

    override fun isSubscribedFlow(url: String): Flow<Boolean> =
        subscriptions.map { list -> list.any { it.url == url } }

    override suspend fun subscribe(channel: ChannelDetails) {
        subscribeInternal(
            serviceId = channel.serviceId,
            url = channel.url,
            name = channel.name,
            avatarUrl = channel.avatarUrl,
            subscriberCount = channel.subscriberCount,
            description = channel.description
        )
    }

    override suspend fun subscribe(channel: ChannelSummary) {
        subscribeInternal(
            serviceId = channel.serviceId,
            url = channel.url,
            name = channel.name,
            avatarUrl = channel.thumbnailUrl,
            subscriberCount = channel.subscriberCount,
            description = channel.description
        )
    }

    private suspend fun subscribeInternal(
        serviceId: Int,
        url: String,
        name: String,
        avatarUrl: String?,
        subscriberCount: Long,
        description: String
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val conn = ensureOpenLocked()
            conn.prepare(
                """
                INSERT INTO subscriptions (service_id, url, name, avatar_url, subscriber_count, description, notification_mode)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT(service_id, url) DO UPDATE SET
                    name = excluded.name,
                    avatar_url = COALESCE(excluded.avatar_url, subscriptions.avatar_url),
                    subscriber_count = excluded.subscriber_count,
                    description = excluded.description
                """.trimIndent()
            ).use { stmt ->
                stmt.bindLong(1, serviceId.toLong())
                stmt.bindText(2, url)
                stmt.bindText(3, name)
                if (avatarUrl != null) stmt.bindText(4, avatarUrl) else stmt.bindNull(4)
                stmt.bindLong(5, subscriberCount)
                stmt.bindText(6, description)
                stmt.step()
            }
            _subscriptions.value = querySubscriptionsLocked(conn)
        }
    }

    override suspend fun unsubscribe(serviceId: Int, url: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val conn = ensureOpenLocked()
            conn.prepare("DELETE FROM subscriptions WHERE service_id = ? AND url = ?").use { stmt ->
                stmt.bindLong(1, serviceId.toLong())
                stmt.bindText(2, url)
                stmt.step()
            }
            _subscriptions.value = querySubscriptionsLocked(conn)
        }
    }

    override suspend fun getAllSubscriptions(): List<SubscriptionRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val conn = ensureOpenLocked()
            querySubscriptionsLocked(conn).also { _subscriptions.value = it }
        }
    }

    override suspend fun exportSubscriptionsJson(filePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val current = getAllSubscriptions()
                val payload = NewPipeSubscriptionExportData(
                    subscriptions = current.map {
                        NewPipeSubscriptionExportItem(
                            serviceId = it.serviceId,
                            url = it.url,
                            name = it.name
                        )
                    }
                )
                val out = File(filePath)
                out.parentFile?.mkdirs()
                out.writeText(
                    json.encodeToString(NewPipeSubscriptionExportData.serializer(), payload),
                    Charsets.UTF_8
                )
            }
        }

    override suspend fun importSubscriptionsJson(filePath: String): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = File(filePath).readText(Charsets.UTF_8).trimStart('\uFEFF')
                val entries: List<NewPipeSubscriptionExportItem> =
                    if (filePath.endsWith(".csv", ignoreCase = true) || !raw.trimStart().startsWith("{")) {
                        parseTakeoutCsvSubscriptions(raw)
                    } else {
                        json.decodeFromString(NewPipeSubscriptionExportData.serializer(), raw).subscriptions
                    }

                var count = 0
                mutex.withLock {
                    val conn = ensureOpenLocked()
                    for (item in entries) {
                        if (item.url.isBlank()) continue
                        conn.prepare(
                            """
                            INSERT INTO subscriptions (service_id, url, name, avatar_url, subscriber_count, description, notification_mode)
                            VALUES (?, ?, ?, NULL, -1, '', 0)
                            ON CONFLICT(service_id, url) DO UPDATE SET name = excluded.name
                            """.trimIndent()
                        ).use { stmt ->
                            stmt.bindLong(1, item.serviceId.toLong())
                            stmt.bindText(2, item.url)
                            stmt.bindText(3, item.name)
                            stmt.step()
                        }
                        count++
                    }
                    _subscriptions.value = querySubscriptionsLocked(conn)
                }
                count
            }
        }

    override suspend fun recordView(video: VideoDetails) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val conn = ensureOpenLocked()
            val streamId = upsertStreamLocked(conn, video)
            val existing = conn.prepare(
                "SELECT access_date, repeat_count FROM stream_history WHERE stream_id = ? ORDER BY access_date DESC LIMIT 1"
            ).use { stmt ->
                stmt.bindLong(1, streamId)
                if (stmt.step()) stmt.getLong(0) to stmt.getLong(1) else null
            }

            val now = System.currentTimeMillis()
            if (existing != null) {
                conn.prepare(
                    "UPDATE stream_history SET access_date = ?, repeat_count = ? WHERE stream_id = ? AND access_date = ?"
                ).use { stmt ->
                    stmt.bindLong(1, now)
                    stmt.bindLong(2, existing.second + 1L)
                    stmt.bindLong(3, streamId)
                    stmt.bindLong(4, existing.first)
                    stmt.step()
                }
            } else {
                conn.prepare(
                    "INSERT INTO stream_history (stream_id, access_date, repeat_count) VALUES (?, ?, 1)"
                ).use { stmt ->
                    stmt.bindLong(1, streamId)
                    stmt.bindLong(2, now)
                    stmt.step()
                }
            }
            _watchHistory.value = queryWatchHistoryLocked(conn)
        }
    }

    override suspend fun savePlaybackProgress(url: String, serviceId: Int, progressMs: Long) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val conn = ensureOpenLocked()
                val streamId = conn.prepare(
                    "SELECT uid FROM streams WHERE service_id = ? AND url = ? LIMIT 1"
                ).use { stmt ->
                    stmt.bindLong(1, serviceId.toLong())
                    stmt.bindText(2, url)
                    if (stmt.step()) stmt.getLong(0) else null
                } ?: return@withLock

                conn.prepare(
                    """
                    INSERT INTO stream_state (stream_id, progress_time) VALUES (?, ?)
                    ON CONFLICT(stream_id) DO UPDATE SET progress_time = excluded.progress_time
                    """.trimIndent()
                ).use { stmt ->
                    stmt.bindLong(1, streamId)
                    stmt.bindLong(2, progressMs)
                    stmt.step()
                }
                _watchHistory.value = queryWatchHistoryLocked(conn)
            }
        }

    override suspend fun getPlaybackProgress(url: String, serviceId: Int): Long =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val conn = ensureOpenLocked()
                conn.prepare(
                    """
                    SELECT ss.progress_time
                    FROM stream_state ss
                    INNER JOIN streams s ON s.uid = ss.stream_id
                    WHERE s.service_id = ? AND s.url = ?
                    LIMIT 1
                    """.trimIndent()
                ).use { stmt ->
                    stmt.bindLong(1, serviceId.toLong())
                    stmt.bindText(2, url)
                    if (stmt.step()) stmt.getLong(0) else 0L
                }
            }
        }

    override suspend fun deleteWatchHistoryEntry(streamId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val conn = ensureOpenLocked()
            conn.prepare("DELETE FROM stream_history WHERE stream_id = ?").use { stmt ->
                stmt.bindLong(1, streamId)
                stmt.step()
            }
            _watchHistory.value = queryWatchHistoryLocked(conn)
        }
    }

    override suspend fun clearWatchHistory(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val conn = ensureOpenLocked()
                conn.execSQL("DELETE FROM stream_history")
                _watchHistory.value = emptyList()
            }
            true
        }.getOrElse { false }
    }

    override suspend fun clearPlaybackStates(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val conn = ensureOpenLocked()
                conn.execSQL("DELETE FROM stream_state")
                _watchHistory.value = queryWatchHistoryLocked(conn)
            }
            true
        }.getOrElse { false }
    }

    override suspend fun recordSearch(query: String) = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext
        mutex.withLock {
            val conn = ensureOpenLocked()
            val now = System.currentTimeMillis()
            conn.prepare("DELETE FROM search_history WHERE service_id = 0 AND search = ?").use { stmt ->
                stmt.bindText(1, trimmed)
                stmt.step()
            }
            conn.prepare(
                "INSERT INTO search_history (creation_date, service_id, search) VALUES (?, 0, ?)"
            ).use { stmt ->
                stmt.bindLong(1, now)
                stmt.bindText(2, trimmed)
                stmt.step()
            }
            _searchHistory.value = querySearchHistoryLocked(conn)
        }
    }

    override suspend fun getSearchSuggestions(prefix: String, limit: Int): List<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val conn = ensureOpenLocked()
                val out = mutableListOf<String>()
                conn.prepare(
                    """
                    SELECT search FROM search_history
                    WHERE search LIKE ? ESCAPE '\'
                    ORDER BY creation_date DESC, id DESC
                    LIMIT ?
                    """.trimIndent()
                ).use { stmt ->
                    val escaped = prefix.replace("\\", "\\\\")
                        .replace("%", "\\%")
                        .replace("_", "\\_")
                    stmt.bindText(1, "%$escaped%")
                    stmt.bindLong(2, limit.toLong())
                    while (stmt.step()) {
                        stmt.getNullableText(0)?.let { out.add(it) }
                    }
                }
                out
            }
        }

    override suspend fun deleteSearchEntry(query: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val conn = ensureOpenLocked()
            conn.prepare("DELETE FROM search_history WHERE search = ?").use { stmt ->
                stmt.bindText(1, query)
                stmt.step()
            }
            _searchHistory.value = querySearchHistoryLocked(conn)
        }
    }

    override suspend fun clearSearchHistory(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                val conn = ensureOpenLocked()
                conn.execSQL("DELETE FROM search_history")
                _searchHistory.value = emptyList()
            }
            true
        }.getOrElse { false }
    }

    private fun backupStoreLocked() = DesktopBackupStore(
        databaseFile = WindowsAppPaths.databaseFile(),
        settings = settings,
        snapshotDatabase = { target ->
            val conn = ensureOpenLocked()
            conn.prepare("VACUUM INTO ?").use { statement ->
                statement.bindText(1, target.absolutePath)
                statement.step()
            }
        },
        prepareDatabase = { candidate ->
            driver.open(candidate.absolutePath).use { conn ->
                conn.execSQL("PRAGMA trusted_schema = OFF")
                val version = conn.prepare("PRAGMA user_version").use { stmt ->
                    check(stmt.step())
                    stmt.getLong(0)
                }
                if (version !in 0L..9L) {
                    throw DesktopBackupException("Cette version de sauvegarde n’est pas compatible avec BoitierTube.")
                }
                // Do not accept an unrelated, empty SQLite file as a NewPipe database.
                for (table in listOf("subscriptions", "streams", "stream_history", "search_history")) {
                    conn.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?").use { stmt ->
                        stmt.bindText(1, table)
                        if (!stmt.step()) throw DesktopBackupException("La sauvegarde ne contient pas une base NewPipe compatible.")
                    }
                }
                conn.execSQL("PRAGMA journal_mode = DELETE")
                createSchema(conn)
                // Also check write-only/less frequently opened features before accepting a schema.
                val requiredColumns = mapOf(
                    "subscriptions" to "uid, service_id, url, name, avatar_url, subscriber_count, description, notification_mode",
                    "streams" to "uid, service_id, url, title, stream_type, duration, uploader, uploader_url, thumbnail_url, view_count, textual_upload_date, upload_date, is_upload_date_approximation",
                    "search_history" to "id, creation_date, service_id, search",
                    "stream_history" to "stream_id, access_date, repeat_count",
                    "stream_state" to "stream_id, progress_time",
                    "playlists" to "uid, name, is_thumbnail_permanent, thumbnail_stream_id, display_index",
                    "playlist_stream_join" to "playlist_id, stream_id, join_index",
                    "remote_playlists" to "uid, service_id, name, url, thumbnail_url, uploader, display_index, stream_count",
                    "feed" to "stream_id, subscription_id",
                    "feed_group" to "uid, name, icon_id, sort_order",
                    "feed_group_subscription_join" to "group_id, subscription_id",
                    "feed_last_updated" to "subscription_id, last_updated"
                )
                requiredColumns.forEach { (table, columns) ->
                    conn.prepare("SELECT $columns FROM $table LIMIT 0").use { it.step() }
                }
                // Query on the isolated copy, before closing or modifying the current database.
                querySubscriptionsLocked(conn)
                queryWatchHistoryLocked(conn)
                querySearchHistoryLocked(conn)
            }
        },
        closeDatabase = {
            connection?.close()
            connection = null
        },
        reopenDatabase = {
            if (!WindowsAppPaths.databaseFile().isFile) {
                throw DesktopBackupException("La base est indisponible. Conservez les copies de secours avant de réessayer.")
            }
            refreshAllLocked(ensureOpenLocked())
        }
    )

    override suspend fun exportDatabase(targetPath: String): Result<Unit> =
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            mutex.withLock {
                runCatching { backupStoreLocked().exportArchive(File(targetPath)) }
            }
        }

    override suspend fun importDatabase(sourcePath: String): Result<Unit> =
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            mutex.withLock {
                runCatching { backupStoreLocked().importArchive(File(sourcePath)) }
            }
        }

    private fun upsertStreamLocked(conn: SQLiteConnection, video: VideoDetails): Long {
        val streamType = if (video.isLive) "LIVE_STREAM" else "VIDEO_STREAM"
        conn.prepare(
            """
            INSERT INTO streams (
                service_id, url, title, stream_type, duration, uploader, uploader_url,
                thumbnail_url, view_count, textual_upload_date, upload_date, is_upload_date_approximation
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0)
            ON CONFLICT(service_id, url) DO UPDATE SET
                title = excluded.title,
                duration = excluded.duration,
                uploader = excluded.uploader,
                uploader_url = COALESCE(excluded.uploader_url, streams.uploader_url),
                thumbnail_url = COALESCE(excluded.thumbnail_url, streams.thumbnail_url),
                view_count = excluded.view_count,
                textual_upload_date = COALESCE(excluded.textual_upload_date, streams.textual_upload_date)
            """.trimIndent()
        ).use { stmt ->
            stmt.bindLong(1, video.serviceId.toLong())
            stmt.bindText(2, video.url)
            stmt.bindText(3, video.title)
            stmt.bindText(4, streamType)
            stmt.bindLong(5, video.durationSeconds.coerceAtLeast(0L))
            stmt.bindText(6, video.uploaderName)
            if (video.uploaderUrl != null) stmt.bindText(7, video.uploaderUrl) else stmt.bindNull(7)
            if (video.thumbnailUrl != null) stmt.bindText(8, video.thumbnailUrl) else stmt.bindNull(8)
            stmt.bindLong(9, video.viewCount)
            if (video.uploadDateText != null) stmt.bindText(10, video.uploadDateText) else stmt.bindNull(10)
            stmt.step()
        }

        return conn.prepare("SELECT uid FROM streams WHERE service_id = ? AND url = ? LIMIT 1").use { stmt ->
            stmt.bindLong(1, video.serviceId.toLong())
            stmt.bindText(2, video.url)
            if (stmt.step()) stmt.getLong(0) else error("Failed to query stream uid")
        }
    }

    private fun SQLiteStatement.getNullableText(index: Int): String? =
        if (isNull(index)) null else getText(index)

    private fun SQLiteStatement.getNullableLong(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    companion object {
        internal fun parseTakeoutCsvSubscriptions(csvText: String): List<NewPipeSubscriptionExportItem> {
            val result = mutableListOf<NewPipeSubscriptionExportItem>()
            for (rawLine in csvText.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val cols = parseCsvRow(line)
                if (cols.size < 2) continue

                val col0 = cols[0].trim()
                val col1 = cols.getOrNull(1)?.trim().orEmpty()
                val col2 = cols.getOrNull(2)?.trim().orEmpty()

                val rawUrl = when {
                    col1.contains("youtube.com/", ignoreCase = true) -> col1
                    col0.contains("youtube.com/", ignoreCase = true) -> col0
                    col0.startsWith("UC") && col0.length >= 20 -> "https://www.youtube.com/channel/$col0"
                    else -> ""
                }
                if (rawUrl.isBlank()) continue

                val normalizedUrl = rawUrl.replaceFirst("http://", "https://", ignoreCase = true)
                val title = col2.ifBlank {
                    col1.takeIf { it != rawUrl && it.isNotBlank() }
                        ?: col0.ifBlank { normalizedUrl }
                }
                result += NewPipeSubscriptionExportItem(
                    serviceId = 0,
                    url = normalizedUrl,
                    name = title
                )
            }
            return result
        }

        private fun parseCsvRow(line: String): List<String> {
            val fields = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    ch == '"' -> {
                        if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                            current.append('"')
                            i++
                        } else {
                            inQuotes = !inQuotes
                        }
                    }
                    ch == ',' && !inQuotes -> {
                        fields += current.toString()
                        current.setLength(0)
                    }
                    else -> current.append(ch)
                }
                i++
            }
            fields += current.toString()
            return fields
        }
    }
}
