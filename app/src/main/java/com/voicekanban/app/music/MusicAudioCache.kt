package com.voicekanban.app.music

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persistent 150 MB LRU disk cache for workout audio tracks (keyed by stable videoId),
 * enabling instant replays, fast seeks, and silent pre-caching of the next track in queue.
 */
@OptIn(UnstableApi::class)
object MusicAudioCache {
    private const val TAG = "MusicAudioCache"
    private const val MAX_CACHE_BYTES = 150L * 1024L * 1024L // 150 MB
    private const val PRECACHE_HEAD_BYTES = 1024L * 1024L // Pre-buffer first 1 MB of next track

    @Volatile private var simpleCache: SimpleCache? = null

    fun cacheKey(videoId: String): String = "audio_$videoId"

    fun get(context: Context): SimpleCache {
        simpleCache?.let { return it }
        return synchronized(this) {
            simpleCache ?: run {
                val appCtx = context.applicationContext
                val dir = File(appCtx.cacheDir, "music_audio_cache").apply { mkdirs() }
                val db = StandaloneDatabaseProvider(appCtx)
                SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES), db).also {
                    simpleCache = it
                }
            }
        }
    }

    /** Wraps an upstream [DataSource.Factory] with read/write disk caching. */
    fun wrapFactory(context: Context, upstream: DataSource.Factory): DataSource.Factory {
        val cache = get(context)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Cache-only [DataSource.Factory] for tracks already fully cached on disk. */
    fun cacheOnlyFactory(context: Context): DataSource.Factory {
        val cache = get(context)
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(null)
            .setCacheWriteDataSinkFactory(null)
    }

    /** True when the entire audio file for [videoId] is already stored in the local disk cache. */
    fun isFullyCached(context: Context, videoId: String): Boolean = runCatching {
        val cache = get(context)
        val key = cacheKey(videoId)
        val len = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        len > 0L && cache.isCached(key, 0L, len)
    }.getOrDefault(false)

    /** True when at least the initial audio header/seconds are cached on disk. */
    fun hasCachedBytes(context: Context, videoId: String): Boolean = runCatching {
        get(context).getCachedBytes(cacheKey(videoId), 0L, 64L * 1024L) > 0L
    }.getOrDefault(false)

    /** Pre-downloads the head of [url] into the disk cache so the next track starts immediately. */
    suspend fun precacheHead(
        context: Context,
        videoId: String,
        url: String,
        upstream: DataSource.Factory,
        totalLength: Long,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val cache = get(context)
            val key = cacheKey(videoId)
            val want = if (totalLength > 0L) minOf(PRECACHE_HEAD_BYTES, totalLength) else PRECACHE_HEAD_BYTES
            if (cache.isCached(key, 0L, want)) return@runCatching
            val cacheDs = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
            val spec = DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setKey(key)
                .setPosition(0L)
                .setLength(want)
                .build()
            CacheWriter(cacheDs, spec, ByteArray(32 * 1024), null).cache()
        }.onFailure { Log.d(TAG, "Precache skipped for $videoId: ${it.message}") }
    }
}
