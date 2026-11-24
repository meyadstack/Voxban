package com.voicekanban.app.music

import android.util.Log
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogEvent
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.models.YouTubeClient
import com.voicekanban.app.data.WorkoutTrack
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Workout music search, radio queue, and audio stream extraction with multi-layer caching
 * (search query cache, radio queue cache, and stream URL TTL cache) to minimise loading latency.
 */
object WorkoutMusicEngine {
    private const val TAG = "WorkoutMusic"
    private const val SONGS_FILTER_PARAM = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
    private const val SEARCH_CACHE_TTL_MS = 15 * 60_000L
    private const val RADIO_CACHE_TTL_MS = 30 * 60_000L
    private const val DEFAULT_STREAM_TTL_MS = 25 * 60_000L
    private const val STREAM_EXPIRY_MARGIN_MS = 5 * 60_000L

    private data class CacheEntry<T>(val value: T, val expiresAtMs: Long) {
        fun isValid(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < expiresAtMs
    }

    private val searchCache = ConcurrentHashMap<String, CacheEntry<List<WorkoutTrack>>>()
    private val radioCache = ConcurrentHashMap<String, CacheEntry<List<WorkoutTrack>>>()
    private val streamCache = ConcurrentHashMap<String, CacheEntry<ExtractedStream>>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val logger = object : InnerTubeLogger {
        override fun log(event: InnerTubeLogEvent) {
            runCatching { Log.d(TAG, event.toString()) }
        }
    }

    val mediaOkHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            engine {
                preconfigured = mediaOkHttp
            }
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    val innerTube: InnerTube by lazy {
        InnerTube(httpClient = httpClient, logger = logger)
    }

    private val configStore: RemotePlayerConfigStore by lazy {
        RemotePlayerConfigStore(
            httpClient = httpClient,
            repository = PlayerConfigRepository.disabled(),
            logger = logger,
        )
    }

    private val cipherService: YouTubeCipherService by lazy {
        YouTubeCipherService(
            httpClient = httpClient,
            remotePlayerConfigStore = configStore,
            logger = logger,
        )
    }

    private val extractor: InnerTubeExtractor by lazy {
        InnerTubeExtractor(
            configParser = YtConfigParserImpl(
                httpClient = httpClient,
                innerTube = innerTube,
                remotePlayerConfigStore = configStore,
                logger = logger,
                cipherService = cipherService,
            ),
            cipherService = cipherService,
            innerTube = innerTube,
            logger = logger,
        )
    }

    /** Warms up client & extractor caches off the main thread before playback begins. */
    suspend fun prewarm() = withContext(Dispatchers.IO) {
        runCatching { extractor.prewarm() }
    }

    /** Searches music tracks with an in-memory 15-minute query cache. */
    suspend fun searchSongs(query: String, forceRefresh: Boolean = false): List<WorkoutTrack> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val key = q.lowercase()
        if (!forceRefresh) {
            searchCache[key]?.takeIf { it.isValid() }?.let { return@withContext it.value }
        }
        val songs = runSearch(q, SONGS_FILTER_PARAM).ifEmpty { runSearch(q, null) }
        if (songs.isNotEmpty()) {
            if (searchCache.size > 40) searchCache.clear()
            searchCache[key] = CacheEntry(songs, System.currentTimeMillis() + SEARCH_CACHE_TTL_MS)
        }
        songs
    }

    private suspend fun runSearch(query: String, params: String?): List<WorkoutTrack> {
        val resp = innerTube.search(
            client = YouTubeClient.WEB_REMIX,
            query = query,
            params = params,
            setLogin = false,
        )
        if (!resp.status.isSuccess()) {
            error("Search failed (HTTP ${resp.status.value})")
        }
        val root = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        return parseSearchTracks(root)
    }

    /** Loads radio / "Up Next" tracks for a seed song (cached for 30 minutes). */
    suspend fun fetchRadioQueue(videoId: String): List<WorkoutTrack> = withContext(Dispatchers.IO) {
        radioCache[videoId]?.takeIf { it.isValid() }?.let { return@withContext it.value }
        runCatching {
            val resp = innerTube.next(
                client = YouTubeClient.WEB_REMIX,
                videoId = videoId,
                playlistId = "RDAMVM$videoId",
            )
            if (!resp.status.isSuccess()) return@runCatching emptyList()
            val root = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val tracks = parseNextQueueTracks(root).filter { it.videoId != videoId }
            if (tracks.isNotEmpty()) {
                if (radioCache.size > 40) radioCache.clear()
                radioCache[videoId] = CacheEntry(tracks, System.currentTimeMillis() + RADIO_CACHE_TTL_MS)
            }
            tracks
        }.onFailure { runCatching { Log.w(TAG, "Radio queue fetch failed for $videoId", it) } }.getOrDefault(emptyList())
    }

    /** Returns a cached or freshly resolved audio stream for [videoId]. */
    suspend fun extractAudioStream(videoId: String, forceRefresh: Boolean = false): ExtractedStream = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            streamCache[videoId]?.takeIf { it.isValid() }?.let { return@withContext it.value }
        }
        val hints = ContentHints(wantVideo = false).withStreamCapabilities(
            allowHls = true,
            allowSabr = true,
            allowBoundedRange = true,
            allowProgressive = true,
        )
        val stream = extractor.extract(
            videoId = videoId,
            hints = hints,
            audioQuality = AudioQuality.HIGH,
        ) ?: error("Couldn’t resolve an audio stream for this track.")

        val now = System.currentTimeMillis()
        val expMs = stream.expiresAt?.toEpochMilliseconds()
            ?.let { (it - STREAM_EXPIRY_MARGIN_MS).coerceAtLeast(now + 60_000L) }
            ?: (now + DEFAULT_STREAM_TTL_MS)
        if (streamCache.size > 60) streamCache.clear()
        streamCache[videoId] = CacheEntry(stream, expMs)
        stream
    }

    fun invalidateStreamCache(videoId: String) {
        streamCache.remove(videoId)
    }

    // ------------------------------------------------------------ JSON parsing helpers

    internal fun parseSearchTracks(root: JsonObject): List<WorkoutTrack> {
        val tracks = LinkedHashMap<String, WorkoutTrack>()
        collectTracksRecursive(root, tracks, limit = 30)
        return tracks.values.toList()
    }

    internal fun parseNextQueueTracks(root: JsonObject): List<WorkoutTrack> {
        val tracks = LinkedHashMap<String, WorkoutTrack>()
        collectPlaylistPanelTracks(root, tracks, limit = 25)
        return tracks.values.toList()
    }

    private fun collectTracksRecursive(el: JsonElement, out: LinkedHashMap<String, WorkoutTrack>, limit: Int) {
        if (out.size >= limit) return
        when (el) {
            is JsonObject -> {
                el["musicResponsiveListItemRenderer"]?.let { r ->
                    (r as? JsonObject)?.let(::parseResponsiveListItem)?.let { t ->
                        out.putIfAbsent(t.videoId, t)
                    }
                }
                el["musicCardShelfRenderer"]?.let { r ->
                    (r as? JsonObject)?.let(::parseCardShelf)?.let { t ->
                        out.putIfAbsent(t.videoId, t)
                    }
                }
                if (out.size >= limit) return
                for ((k, v) in el) {
                    if (k != "musicResponsiveListItemRenderer" && k != "musicCardShelfRenderer") {
                        collectTracksRecursive(v, out, limit)
                        if (out.size >= limit) return
                    }
                }
            }
            is JsonArray -> {
                for (item in el) {
                    collectTracksRecursive(item, out, limit)
                    if (out.size >= limit) return
                }
            }
            else -> Unit
        }
    }

    private fun collectPlaylistPanelTracks(el: JsonElement, out: LinkedHashMap<String, WorkoutTrack>, limit: Int) {
        if (out.size >= limit) return
        when (el) {
            is JsonObject -> {
                el["playlistPanelVideoRenderer"]?.let { r ->
                    (r as? JsonObject)?.let(::parsePlaylistPanelItem)?.let { t ->
                        out.putIfAbsent(t.videoId, t)
                    }
                }
                if (out.size >= limit) return
                for ((k, v) in el) {
                    if (k != "playlistPanelVideoRenderer") {
                        collectPlaylistPanelTracks(v, out, limit)
                        if (out.size >= limit) return
                    }
                }
            }
            is JsonArray -> {
                for (item in el) {
                    collectPlaylistPanelTracks(item, out, limit)
                    if (out.size >= limit) return
                }
            }
            else -> Unit
        }
    }

    private fun parseResponsiveListItem(item: JsonObject): WorkoutTrack? {
        val videoId = findVideoId(item) ?: return null
        val cols = item["flexColumns"]?.jsonArray ?: return null
        val title = cols.getOrNull(0)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text")?.jsonObject?.let(::runsText)?.trim().orEmpty()
        if (title.isEmpty()) return null

        val subtitleRuns = cols.getOrNull(1)?.jsonObject
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text")?.jsonObject?.get("runs")?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
            .orEmpty()

        val parts = subtitleRuns
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "•" && it != "·" && !it.equals("Song", true) && !it.equals("Video", true) }

        var durationSec = 0L
        val nonDuration = mutableListOf<String>()
        for (p in parts) {
            val d = parseDurationSeconds(p)
            if (d > 0L) durationSec = d
            else if (!p.endsWith("plays", true) && !p.endsWith("views", true)) nonDuration += p
        }
        val artist = nonDuration.firstOrNull().orEmpty()
        val album = nonDuration.getOrNull(1).orEmpty()
        val thumb = extractBestThumbnail(item["thumbnail"])
        return WorkoutTrack(videoId, title, artist, album, durationSec, thumb)
    }

    private fun parseCardShelf(card: JsonObject): WorkoutTrack? {
        val videoId = findVideoId(card) ?: return null
        val title = card["title"]?.jsonObject?.let(::runsText)?.trim().orEmpty()
        if (title.isEmpty()) return null
        val subText = card["subtitle"]?.jsonObject?.let(::runsText).orEmpty()
        val parts = subText.split("•", "·").map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("Song", true) && !it.equals("Video", true) }
        var durationSec = 0L
        val meta = mutableListOf<String>()
        for (p in parts) {
            val d = parseDurationSeconds(p)
            if (d > 0L) durationSec = d else if (!p.endsWith("plays", true) && !p.endsWith("views", true)) meta += p
        }
        val thumb = extractBestThumbnail(card["thumbnail"])
        return WorkoutTrack(videoId, title, meta.firstOrNull().orEmpty(), meta.getOrNull(1).orEmpty(), durationSec, thumb)
    }

    private fun parsePlaylistPanelItem(item: JsonObject): WorkoutTrack? {
        val videoId = item["videoId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: findVideoId(item) ?: return null
        val title = item["title"]?.jsonObject?.let(::runsText)?.trim().orEmpty()
        if (title.isEmpty()) return null
        val artist = item["shortBylineText"]?.jsonObject?.let(::runsText)?.trim()
            ?: item["longBylineText"]?.jsonObject?.let(::runsText)?.substringBefore("•")?.trim().orEmpty()
        val durText = item["lengthText"]?.jsonObject?.let(::runsText).orEmpty()
        val thumb = extractBestThumbnail(item["thumbnail"])
        return WorkoutTrack(videoId, title, artist, "", parseDurationSeconds(durText), thumb)
    }

    private fun findVideoId(obj: JsonElement): String? {
        when (obj) {
            is JsonObject -> {
                obj["watchEndpoint"]?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.length in 8..16 }?.let { return it }
                obj["playlistItemData"]?.jsonObject?.get("videoId")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.length in 8..16 }?.let { return it }
                for ((_, v) in obj) {
                    findVideoId(v)?.let { return it }
                }
            }
            is JsonArray -> {
                for (item in obj) findVideoId(item)?.let { return it }
            }
            else -> Unit
        }
        return null
    }

    private fun runsText(obj: JsonObject): String {
        obj["simpleText"]?.jsonPrimitive?.contentOrNull?.let { return it }
        return obj["runs"]?.jsonArray
            ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
            .orEmpty()
    }

    private fun extractBestThumbnail(el: JsonElement?): String {
        if (el == null) return ""
        val urls = mutableListOf<Pair<Int, String>>()
        fun scan(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    val u = node["url"]?.jsonPrimitive?.contentOrNull
                    val w = node["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    if (!u.isNullOrBlank() && (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("//"))) {
                        urls += w to if (u.startsWith("//")) "https:$u" else u
                    }
                    node.values.forEach(::scan)
                }
                is JsonArray -> node.forEach(::scan)
                else -> Unit
            }
        }
        scan(el)
        return urls.maxByOrNull { it.first }?.second.orEmpty()
    }

    fun parseDurationSeconds(raw: String): Long {
        val parts = raw.trim().split(":")
        if (parts.size !in 2..3) return 0L
        val nums = parts.map { it.toLongOrNull() ?: return 0L }
        return when (nums.size) {
            2 -> nums[0] * 60L + nums[1]
            3 -> nums[0] * 3600L + nums[1] * 60L + nums[2]
            else -> 0L
        }
    }

    fun formatDuration(msOrSec: Long, isMillis: Boolean = true): String {
        val totalSec = (if (isMillis) msOrSec / 1000L else msOrSec).coerceAtLeast(0L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
