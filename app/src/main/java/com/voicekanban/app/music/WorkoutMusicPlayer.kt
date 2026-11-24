package com.voicekanban.app.music

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.voicekanban.app.MainActivity
import com.voicekanban.app.R
import com.voicekanban.app.VoiceKanbanApp
import com.voicekanban.app.data.WorkoutTrack
import com.voicekanban.app.reminders.Reminders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

enum class MusicRepeatMode { OFF, ALL, ONE }

data class MusicPlayerState(
    val currentTrack: WorkoutTrack? = null,
    val queue: List<WorkoutTrack> = emptyList(),
    val queueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: MusicRepeatMode = MusicRepeatMode.OFF,
    val error: String? = null,
)

/**
 * Workout music player controller backed by Media3 [ExoPlayer] + persistent 150 MB disk caching
 * ([MusicAudioCache]), in-memory stream caching, next-track pre-buffering, and a foreground
 * media notification bar ([MusicPlaybackService]) with lock-screen & Bluetooth controls.
 */
object WorkoutMusicPlayer {
    private const val TAG = "WorkoutMusicPlayer"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(MusicPlayerState())
    val state: StateFlow<MusicPlayerState> = _state.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var exoPlayer: ExoPlayer? = null
    private var loadJob: Job? = null
    private var radioJob: Job? = null
    private var precacheJob: Job? = null
    private var progressJob: Job? = null
    private var retriedFreshStream = false

    fun ensurePlayer(context: Context): ExoPlayer {
        val ctx = context.applicationContext
        appContext = ctx
        exoPlayer?.let { return it }
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        val player = ExoPlayer.Builder(ctx)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) startProgressLoop() else progressJob?.cancel()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> _state.update { it.copy(isLoading = true) }
                    Player.STATE_READY -> {
                        val dur = player.duration.takeIf { it > 0L }
                            ?: (_state.value.currentTrack?.durationSec?.times(1000L) ?: 0L)
                        _state.update { it.copy(isLoading = false, durationMs = dur, error = null) }
                        // Silently pre-fetch and pre-cache the next track for instant transitions!
                        scheduleNextTrackPrecache()
                    }
                    Player.STATE_ENDED -> onTrackEnded()
                    Player.STATE_IDLE -> _state.update { it.copy(isLoading = false) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "ExoPlayer playback error", error)
                val track = _state.value.currentTrack
                val c = appContext
                // If a cached stream URL expired or errored, transparently retry once with a fresh stream.
                if (!retriedFreshStream && track != null && c != null) {
                    retriedFreshStream = true
                    WorkoutMusicEngine.invalidateStreamCache(track.videoId)
                    startPlaybackOf(c, track, forceFreshStream = true)
                    return
                }
                _state.update {
                    it.copy(
                        isPlaying = false,
                        isLoading = false,
                        error = "Playback interrupted (${error.errorCodeName}). Tap play to retry.",
                    )
                }
            }
        })
        exoPlayer = player
        return player
    }

    /** Plays [track], optionally replacing [queue] and auto-populating radio tracks. */
    fun playTrack(
        context: Context,
        track: WorkoutTrack,
        queue: List<WorkoutTrack> = listOf(track),
        autoLoadRadio: Boolean = true,
    ) {
        val ctx = context.applicationContext
        appContext = ctx
        val normalizedQueue = if (queue.any { it.videoId == track.videoId }) queue else listOf(track) + queue
        val idx = normalizedQueue.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0)
        retriedFreshStream = false
        _state.update {
            it.copy(
                currentTrack = track,
                queue = normalizedQueue,
                queueIndex = idx,
                isLoading = true,
                isPlaying = false,
                positionMs = 0L,
                durationMs = track.durationSec * 1000L,
                error = null,
            )
        }
        startPlaybackOf(ctx, track)

        // Populate radio Up Next tracks when playing a single search result
        if (autoLoadRadio && normalizedQueue.size <= 1) {
            radioJob?.cancel()
            radioJob = scope.launch {
                val radio = WorkoutMusicEngine.fetchRadioQueue(track.videoId)
                if (radio.isNotEmpty()) {
                    _state.update { st ->
                        if (st.currentTrack?.videoId != track.videoId) st
                        else {
                            val existing = st.queue.map { it.videoId }.toSet()
                            st.copy(queue = st.queue + radio.filterNot { it.videoId in existing })
                        }
                    }
                    scheduleNextTrackPrecache()
                }
            }
        }
    }

    fun playQueueIndex(context: Context, index: Int) {
        val st = _state.value
        val track = st.queue.getOrNull(index) ?: return
        retriedFreshStream = false
        _state.update {
            it.copy(
                currentTrack = track,
                queueIndex = index,
                isLoading = true,
                isPlaying = false,
                positionMs = 0L,
                durationMs = track.durationSec * 1000L,
                error = null,
            )
        }
        startPlaybackOf(context.applicationContext, track)
    }

    fun addToQueue(track: WorkoutTrack) {
        _state.update { st ->
            if (st.queue.any { it.videoId == track.videoId }) st
            else st.copy(queue = st.queue + track)
        }
    }

    fun removeFromQueue(index: Int) {
        _state.update { st ->
            if (index !in st.queue.indices || index == st.queueIndex) st
            else {
                val nextQueue = st.queue.toMutableList().apply { removeAt(index) }
                val nextIdx = if (index < st.queueIndex) st.queueIndex - 1 else st.queueIndex
                st.copy(queue = nextQueue, queueIndex = nextIdx)
            }
        }
    }

    fun togglePlayPause(context: Context) {
        val st = _state.value
        val player = ensurePlayer(context)
        if (st.error != null && st.currentTrack != null) {
            retriedFreshStream = false
            WorkoutMusicEngine.invalidateStreamCache(st.currentTrack.videoId)
            startPlaybackOf(context.applicationContext, st.currentTrack, forceFreshStream = true)
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else if (player.playbackState == Player.STATE_IDLE && st.currentTrack != null) {
            startPlaybackOf(context.applicationContext, st.currentTrack)
        } else {
            MusicPlaybackService.start(context.applicationContext)
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val p = exoPlayer ?: return
        val clamped = positionMs.coerceAtLeast(0L)
        p.seekTo(clamped)
        _state.update { it.copy(positionMs = clamped) }
    }

    fun next(context: Context) {
        val st = _state.value
        if (st.queue.isEmpty()) return
        val nextIdx = peekNextIndex(st) ?: return
        playQueueIndex(context, nextIdx)
    }

    fun previous(context: Context) {
        val p = exoPlayer
        if (p != null && p.currentPosition > 3500L) {
            seekTo(0L)
            return
        }
        val st = _state.value
        if (st.queue.isEmpty()) return
        val prevIdx = when {
            st.queueIndex - 1 >= 0 -> st.queueIndex - 1
            st.repeatMode == MusicRepeatMode.ALL -> st.queue.lastIndex
            else -> 0
        }
        playQueueIndex(context, prevIdx)
    }

    fun hasNext(): Boolean = peekNextIndex(_state.value) != null
    fun hasPrevious(): Boolean = _state.value.queue.isNotEmpty()

    private fun peekNextIndex(st: MusicPlayerState): Int? = when {
        st.queue.isEmpty() -> null
        st.shuffle && st.queue.size > 1 -> (st.queue.indices - st.queueIndex).random()
        st.queueIndex + 1 < st.queue.size -> st.queueIndex + 1
        st.repeatMode == MusicRepeatMode.ALL -> 0
        else -> null
    }

    fun toggleShuffle() {
        _state.update { it.copy(shuffle = !it.shuffle) }
    }

    fun cycleRepeat() {
        _state.update {
            it.copy(
                repeatMode = when (it.repeatMode) {
                    MusicRepeatMode.OFF -> MusicRepeatMode.ALL
                    MusicRepeatMode.ALL -> MusicRepeatMode.ONE
                    MusicRepeatMode.ONE -> MusicRepeatMode.OFF
                },
            )
        }
    }

    fun stop(context: Context? = appContext) {
        loadJob?.cancel()
        radioJob?.cancel()
        precacheJob?.cancel()
        progressJob?.cancel()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        _state.value = MusicPlayerState()
        context?.let { MusicPlaybackService.stop(it.applicationContext) }
    }

    private fun onTrackEnded() {
        val ctx = appContext ?: return
        val st = _state.value
        when {
            st.repeatMode == MusicRepeatMode.ONE && st.currentTrack != null -> {
                exoPlayer?.seekTo(0L)
                exoPlayer?.play()
            }
            hasNext() -> next(ctx)
            else -> _state.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
        }
    }

    @OptIn(UnstableApi::class)
    private fun startPlaybackOf(ctx: Context, track: WorkoutTrack, forceFreshStream: Boolean = false) {
        loadJob?.cancel()
        precacheJob?.cancel()
        val player = ensurePlayer(ctx)
        player.stop()

        // Immediately start foreground notification service (promotes synchronously in onStartCommand -> zero crash!)
        MusicPlaybackService.start(ctx)

        loadJob = scope.launch {
            try {
                // Fast path: if the full audio file is already in local disk cache, play instantly with zero network delay!
                if (!forceFreshStream && MusicAudioCache.isFullyCached(ctx, track.videoId)) {
                    val source = buildCachedOnlyMediaSource(ctx, track)
                    player.setMediaSource(source)
                    player.prepare()
                    player.play()
                    return@launch
                }

                val stream = WorkoutMusicEngine.extractAudioStream(track.videoId, forceRefresh = forceFreshStream)
                val source = buildMediaSource(ctx, track, stream)
                val durFromMeta = stream.mediaMetadata?.durationSeconds?.times(1000L)
                    ?: (track.durationSec * 1000L)
                _state.update {
                    it.copy(
                        durationMs = if (durFromMeta > 0L) durFromMeta else it.durationMs,
                        error = null,
                    )
                }
                player.setMediaSource(source)
                player.prepare()
                player.play()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract or play ${track.videoId}", e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        isPlaying = false,
                        error = e.message?.take(140) ?: "Couldn’t play this track.",
                    )
                }
            }
        }
    }

    /** Pre-resolves the next song's stream URL and pre-caches its first 1 MB on disk. */
    @OptIn(UnstableApi::class)
    private fun scheduleNextTrackPrecache() {
        val ctx = appContext ?: return
        val st = _state.value
        val nextTrack = if (st.queueIndex + 1 in st.queue.indices) st.queue[st.queueIndex + 1] else return
        if (MusicAudioCache.isFullyCached(ctx, nextTrack.videoId)) return

        precacheJob?.cancel()
        precacheJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val stream = WorkoutMusicEngine.extractAudioStream(nextTrack.videoId)
                val isHls = stream.audioUrl.contains(".m3u8", ignoreCase = true) ||
                    stream.mimeType?.contains("mpegurl", ignoreCase = true) == true
                if (!isHls && stream.audioUrl.startsWith("http")) {
                    val upstream = upstreamFactoryFor(ctx, stream)
                    MusicAudioCache.precacheHead(
                        context = ctx,
                        videoId = nextTrack.videoId,
                        url = stream.audioUrl,
                        upstream = upstream,
                        totalLength = stream.contentLengthBytes ?: -1L,
                    )
                }
            }
        }
    }

    private fun trackMetadata(track: WorkoutTrack): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist.ifBlank { "Workout Music" })
            .setAlbumTitle(track.album.ifBlank { "Voxban Workout" })
            .apply {
                if (track.thumbnailUrl.isNotBlank()) setArtworkUri(Uri.parse(track.thumbnailUrl))
            }
            .build()

    @OptIn(UnstableApi::class)
    private fun buildCachedOnlyMediaSource(ctx: Context, track: WorkoutTrack): MediaSource {
        val mediaItem = MediaItem.Builder()
            .setMediaId(track.videoId)
            .setCustomCacheKey(MusicAudioCache.cacheKey(track.videoId))
            .setUri("voxban://cached/${track.videoId}")
            .setMediaMetadata(trackMetadata(track))
            .build()
        return ProgressiveMediaSource.Factory(MusicAudioCache.cacheOnlyFactory(ctx))
            .setContinueLoadingCheckIntervalBytes(65_536)
            .createMediaSource(mediaItem)
    }

    @OptIn(UnstableApi::class)
    private fun upstreamFactoryFor(ctx: Context, stream: ExtractedStream): DataSource.Factory {
        val totalLen = stream.contentLengthBytes ?: -1L
        val chunk = stream.rangeChunkSizeBytes.takeIf { it > 0L } ?: (512L * 1024L)
        return if ((stream.requireBoundedRange || stream.useRangeChunks || totalLen > 0L) && stream.audioUrl.startsWith("http")) {
            DataSource.Factory {
                ChunkedHttpDataSource(
                    http = WorkoutMusicEngine.mediaOkHttp,
                    url = stream.audioUrl,
                    headers = stream.headers,
                    totalLength = totalLen,
                    chunkSize = chunk,
                )
            }
        } else {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(stream.headers)
            DefaultDataSource.Factory(ctx, httpFactory)
        }
    }

    @OptIn(UnstableApi::class)
    @kotlin.OptIn(ExperimentalSabrApi::class)
    private fun buildMediaSource(ctx: Context, track: WorkoutTrack, stream: ExtractedStream): MediaSource {
        val sabr = stream.sabrBootstrap
        val isHls = stream.audioUrl.contains(".m3u8", ignoreCase = true) ||
            stream.mimeType?.contains("mpegurl", ignoreCase = true) == true
        val isSabrOnly = sabr != null && (stream.audioUrl.isBlank() || stream.audioUrl.startsWith("sabr:"))
        val uri = if (isSabrOnly) "voxban://sabr/${track.videoId}" else stream.audioUrl

        val mediaItem = MediaItem.Builder()
            .setMediaId(track.videoId)
            .setCustomCacheKey(MusicAudioCache.cacheKey(track.videoId))
            .setUri(uri)
            .setMimeType(stream.mimeType?.substringBefore(';')?.trim())
            .setMediaMetadata(trackMetadata(track))
            .build()

        if (isSabrOnly) {
            val sabrFactory = DataSource.Factory { SabrAudioDataSource(WorkoutMusicEngine.httpClient, sabr!!) }
            return ProgressiveMediaSource.Factory(sabrFactory)
                .setContinueLoadingCheckIntervalBytes(65_536)
                .createMediaSource(mediaItem)
        }

        val upstream = upstreamFactoryFor(ctx, stream)
        return if (isHls) {
            DefaultMediaSourceFactory(upstream).createMediaSource(mediaItem)
        } else {
            // Wrap progressive streams with our 150 MB persistent disk cache!
            val cachedFactory = MusicAudioCache.wrapFactory(ctx, upstream)
            ProgressiveMediaSource.Factory(cachedFactory)
                .setContinueLoadingCheckIntervalBytes(65_536)
                .createMediaSource(mediaItem)
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val p = exoPlayer
                if (p != null) {
                    val pos = p.currentPosition.coerceAtLeast(0L)
                    val dur = p.duration.takeIf { it > 0L } ?: _state.value.durationMs
                    _state.update { it.copy(positionMs = pos, durationMs = dur) }
                }
                delay(500)
            }
        }
    }
}

/**
 * Foreground service hosting the [MediaSession] and rich Android media notification bar
 * (showing active track title, artist, album art, seek bar, Previous / Play-Pause / Next,
 * and Close controls). Promotes to foreground synchronously in [onStartCommand] so Android
 * never throws ForegroundServiceDidNotStartInTimeException while audio streams load.
 */
@OptIn(UnstableApi::class)
class MusicPlaybackService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaSession: MediaSession? = null
    private var isForeground = false
    private var loadedArtUrl: String? = null
    private var loadedArtBitmap: Bitmap? = null
    private var artJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val rawPlayer = WorkoutMusicPlayer.ensurePlayer(this)
        val queuePlayer = object : ForwardingPlayer(rawPlayer) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()

            override fun isCommandAvailable(command: Int): Boolean = when (command) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                else -> super.isCommandAvailable(command)
            }

            override fun hasNextMediaItem(): Boolean = WorkoutMusicPlayer.hasNext()
            override fun hasPreviousMediaItem(): Boolean = WorkoutMusicPlayer.hasPrevious()

            override fun seekToNext() { WorkoutMusicPlayer.next(applicationContext) }
            override fun seekToNextMediaItem() { WorkoutMusicPlayer.next(applicationContext) }
            override fun seekToPrevious() { WorkoutMusicPlayer.previous(applicationContext) }
            override fun seekToPreviousMediaItem() { WorkoutMusicPlayer.previous(applicationContext) }
        }

        val openWorkout = PendingIntent.getActivity(
            this,
            7801,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("voicekanban://workout")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, queuePlayer)
            .setId("voxban-workout-music")
            .setSessionActivity(openWorkout)
            .build()

        // Keep notification bar synchronized with every track / play / pause / loading change
        serviceScope.launch {
            WorkoutMusicPlayer.state
                .map { Triple(it.currentTrack, it.isPlaying, it.isLoading) }
                .distinctUntilChanged()
                .collect { (track, _, _) ->
                    if (track == null) {
                        stopForegroundAndSelf()
                    } else {
                        maybeLoadArtwork(track.thumbnailUrl)
                        updateNotification()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always satisfy startForegroundService() immediately before handling actions!
        promoteToForeground()

        when (intent?.action) {
            ACTION_PREV -> WorkoutMusicPlayer.previous(applicationContext)
            ACTION_PLAY_PAUSE -> WorkoutMusicPlayer.togglePlayPause(applicationContext)
            ACTION_NEXT -> WorkoutMusicPlayer.next(applicationContext)
            ACTION_STOP -> {
                WorkoutMusicPlayer.stop(applicationContext)
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
        }

        val track = WorkoutMusicPlayer.state.value.currentTrack
        if (track == null) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        maybeLoadArtwork(track.thumbnailUrl)
        updateNotification()
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        val notif = buildNotification()
        val fgType = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, fgType)
            isForeground = true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn’t promote music service to foreground", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification() {
        val notif = buildNotification()
        if (!isForeground) {
            promoteToForeground()
            return
        }
        if (Reminders.canPost(this)) {
            runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, notif) }
        }
    }

    private fun stopForegroundAndSelf() {
        isForeground = false
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        NotificationManagerCompat.from(this).cancel(NOTIF_ID)
        stopSelf()
    }

    private fun actionIntent(action: String, reqCode: Int): PendingIntent {
        val intent = Intent(this, MusicPlaybackService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(this, reqCode, intent, flags)
        } else {
            PendingIntent.getService(this, reqCode, intent, flags)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!WorkoutMusicPlayer.state.value.isPlaying && !WorkoutMusicPlayer.state.value.isLoading) {
            WorkoutMusicPlayer.stop(applicationContext)
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(): Notification {
        val st = WorkoutMusicPlayer.state.value
        val track = st.currentTrack
        val title = track?.title?.ifBlank { "Workout Music" } ?: "Workout Music"
        val artist = track?.artist?.ifBlank { "Voxban Workout" } ?: "Loading…"
        val status = when {
            st.isLoading -> "Loading…"
            st.isPlaying -> "Playing"
            else -> "Paused"
        }

        val openWorkout = PendingIntent.getActivity(
            this,
            7801,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("voicekanban://workout")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, VoiceKanbanApp.CHANNEL_MUSIC)
            .setSmallIcon(R.drawable.ic_stat_voicekanban)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(status)
            .setLargeIcon(loadedArtBitmap)
            .setContentIntent(openWorkout)
            .setDeleteIntent(actionIntent(ACTION_STOP, 7814))
            .setOngoing(st.isPlaying || st.isLoading)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                actionIntent(ACTION_PREV, 7811),
            )
            .addAction(
                if (st.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (st.isPlaying) "Pause" else "Play",
                actionIntent(ACTION_PLAY_PAUSE, 7812),
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                actionIntent(ACTION_NEXT, 7813),
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                actionIntent(ACTION_STOP, 7814),
            )

        mediaSession?.let { session ->
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2),
            )
        }
        return builder.build()
    }

    private fun maybeLoadArtwork(url: String) {
        if (url.isBlank()) {
            loadedArtUrl = null
            loadedArtBitmap = null
            return
        }
        if (url == loadedArtUrl && loadedArtBitmap != null) return
        artworkCache.get(url)?.let { cached ->
            loadedArtUrl = url
            loadedArtBitmap = cached
            return
        }
        loadedArtUrl = url
        loadedArtBitmap = null
        artJob?.cancel()
        artJob = serviceScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url(url).build()
                    WorkoutMusicEngine.mediaOkHttp.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching null
                        val bytes = resp.body.bytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }.getOrNull()
            }
            if (bmp != null && loadedArtUrl == url) {
                artworkCache.put(url, bmp)
                loadedArtBitmap = bmp
                updateNotification()
            }
        }
    }

    override fun onDestroy() {
        artJob?.cancel()
        serviceScope.cancel()
        mediaSession?.release()
        mediaSession = null
        isForeground = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MusicPlaybackService"
        private const val NOTIF_ID = 7802
        private const val ACTION_PREV = "com.voicekanban.app.MUSIC_PREV"
        private const val ACTION_PLAY_PAUSE = "com.voicekanban.app.MUSIC_PLAY_PAUSE"
        private const val ACTION_NEXT = "com.voicekanban.app.MUSIC_NEXT"
        private const val ACTION_STOP = "com.voicekanban.app.MUSIC_STOP"

        private val artworkCache = LruCache<String, Bitmap>(16)

        fun start(context: Context) {
            val intent = Intent(context, MusicPlaybackService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { e ->
                Log.w(TAG, "startForegroundService failed, trying startService", e)
                runCatching { context.startService(intent) }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MusicPlaybackService::class.java)) }
            NotificationManagerCompat.from(context).cancel(NOTIF_ID)
        }
    }
}
