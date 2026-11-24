package com.voicekanban.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.voicekanban.app.data.WorkoutTrack
import com.voicekanban.app.music.MusicDownloadManager
import com.voicekanban.app.music.MusicPlaybackService
import com.voicekanban.app.music.MusicPlayerState
import com.voicekanban.app.music.MusicRepeatMode
import com.voicekanban.app.music.WorkoutMusicEngine
import com.voicekanban.app.music.WorkoutMusicPlayer
import com.voicekanban.app.reminders.Reminders
import com.voicekanban.app.ui.MainViewModel
import com.voicekanban.app.ui.components.Chip
import com.voicekanban.app.ui.theme.VK
import kotlinx.coroutines.launch

private val WORKOUT_VIBES: List<Triple<String, String, String>> = listOf(
    Triple("🔥", "HIIT Energy", "high energy hiit workout music mix"),
    Triple("🏃", "Running", "running tempo upbeat workout songs"),
    Triple("🏋️", "Gym Pump", "gym motivation bass workout music"),
    Triple("🚴", "Cycling", "cycling cardio electronic workout music"),
    Triple("🧘", "Yoga & Flow", "calm yoga stretching ambient music"),
    Triple("🚶", "Power Walk", "feel good walking pop workout music"),
)

/**
 * Embedded workout music card with background playback, disk caching, and notification controls.
 */
@Composable
fun WorkoutMusicCard(
    vm: MainViewModel,
    activeWorkoutId: String? = null,
    onOpenBrowser: (initialQuery: String?) -> Unit,
) {
    val c = VK.colors
    val ctx = LocalContext.current
    val app by vm.state.collectAsState()
    val player by WorkoutMusicPlayer.state.collectAsState()
    val dlProgress by MusicDownloadManager.progress.collectAsState()
    val track = player.currentTrack
    val isFav = track != null && app.savedWorkoutTracks.any { it.videoId == track.videoId }
    val isDownloaded = track != null && (app.downloadedWorkoutTracks.any { it.track.videoId == track.videoId } || MusicDownloadManager.localFileFor(ctx, track.videoId) != null)
    val activeProgress = track?.let { dlProgress[it.videoId] }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(c.raised)
            .border(1.dp, c.border, RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(c.voicePrimary, c.voiceSecondary))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (player.isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote,
                    null,
                    tint = c.onLime,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Workout Music", style = MaterialTheme.typography.titleMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Background playback · Cached audio", fontSize = 11.sp, color = c.inkTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                Modifier.heightIn(min = 36.dp).clip(CircleShape).background(c.limeTile)
                    .clickable { onOpenBrowser(null) }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.Search, "Search music", tint = c.onLime, modifier = Modifier.size(15.dp))
                Text("Browse", color = c.onLime, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }

        // Now playing panel (when a track is selected)
        if (track != null) {
            NowPlayingBody(
                player = player,
                track = track,
                isFav = isFav,
                isDownloaded = isDownloaded,
                downloadProgress = activeProgress,
                onToggleFav = { vm.toggleFavoriteTrack(track) },
                onDownload = {
                    if (isDownloaded) {
                        onOpenBrowser("__DOWNLOADS__")
                    } else if (activeProgress == null) {
                        MusicDownloadManager.startDownload(
                            context = ctx,
                            track = track,
                            onDone = { vm.addDownloadedTrack(it) },
                            onError = { vm.showToast(com.voicekanban.app.ui.ToastKind.ERROR, "Download failed", it) },
                        )
                    }
                },
                onPlayPause = { WorkoutMusicPlayer.togglePlayPause(ctx) },
                onPrev = { WorkoutMusicPlayer.previous(ctx) },
                onNext = { WorkoutMusicPlayer.next(ctx) },
                onSeek = { WorkoutMusicPlayer.seekTo(it) },
                onShuffle = { WorkoutMusicPlayer.toggleShuffle() },
                onRepeat = { WorkoutMusicPlayer.cycleRepeat() },
                onOpenQueue = { onOpenBrowser("__QUEUE__") },
            )
        } else {
            Text(
                "Stream ad-free workout tracks in the background with screen-off controls — pick a vibe or search any song.",
                fontSize = 12.sp,
                color = c.inkSecondary,
            )
        }

        // Quick workout vibe chips (horizontally scrollable so they never crowd narrow screens)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (app.downloadedWorkoutTracks.isNotEmpty()) {
                Chip("⬇️ Offline (${app.downloadedWorkoutTracks.size})", selected = false, tint = c.low) {
                    onOpenBrowser("__DOWNLOADS__")
                }
            }
            if (app.savedWorkoutTracks.isNotEmpty()) {
                Chip("❤️ Saved (${app.savedWorkoutTracks.size})", selected = false, tint = c.voiceTertiary) {
                    onOpenBrowser("__SAVED__")
                }
            }
            WORKOUT_VIBES.forEach { (emoji, label, query) ->
                val matchesActive = activeWorkoutId != null && label.lowercase().contains(activeWorkoutId.lowercase())
                Chip("$emoji $label", selected = matchesActive, tint = if (matchesActive) c.voiceTertiary else null) {
                    onOpenBrowser(query)
                }
            }
        }
    }
}

@Composable
private fun NowPlayingBody(
    player: MusicPlayerState,
    track: WorkoutTrack,
    isFav: Boolean,
    isDownloaded: Boolean,
    downloadProgress: Float?,
    onToggleFav: () -> Unit,
    onDownload: () -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val c = VK.colors
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos by remember { mutableFloatStateOf(0f) }
    val dur = player.durationMs.coerceAtLeast(1L)
    val shownPos = if (scrubbing) (scrubPos * dur).toLong() else player.positionMs

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(18.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TrackArt(track.thumbnailUrl, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    track.artist.ifBlank { "Workout Music" },
                    color = c.inkSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    downloadProgress != null -> CircularProgressIndicator(
                        progress = { downloadProgress.coerceIn(0.05f, 1f) },
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = c.accent,
                    )
                    isDownloaded -> Icon(Icons.Rounded.DownloadDone, "Downloaded offline", tint = c.low, modifier = Modifier.size(20.dp))
                    else -> Icon(Icons.Rounded.Download, "Download offline", tint = c.inkSecondary, modifier = Modifier.size(20.dp))
                }
            }
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onToggleFav),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (isFav) "Remove favourite" else "Save favourite",
                    tint = if (isFav) c.high else c.inkSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onOpenQueue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Queue", tint = c.inkSecondary, modifier = Modifier.size(20.dp))
            }
        }

        player.error?.let { err ->
            Text(err, color = c.danger, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        // Progress bar
        Column {
            Slider(
                value = if (scrubbing) scrubPos else (player.positionMs.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { scrubbing = true; scrubPos = it },
                onValueChangeFinished = {
                    scrubbing = false
                    onSeek((scrubPos * dur).toLong())
                },
                modifier = Modifier.fillMaxWidth().height(26.dp),
                colors = SliderDefaults.colors(
                    thumbColor = c.ink,
                    activeTrackColor = c.voiceTertiary,
                    inactiveTrackColor = c.surfaceWell,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(WorkoutMusicEngine.formatDuration(shownPos), fontSize = 10.sp, color = c.inkTertiary)
                Text(
                    if (player.durationMs > 0L) WorkoutMusicEngine.formatDuration(player.durationMs) else "--:--",
                    fontSize = 10.sp,
                    color = c.inkTertiary,
                )
            }
        }

        // Transport controls (evenly distributed so they fit comfortably on any phone width)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onShuffle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Shuffle,
                    "Shuffle",
                    tint = if (player.shuffle) c.accentText else c.inkTertiary,
                    modifier = Modifier.size(19.dp),
                )
            }
            Box(
                Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onPrev),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.SkipPrevious, "Previous", tint = c.ink, modifier = Modifier.size(24.dp))
            }
            Box(
                Modifier.size(50.dp).clip(CircleShape).background(c.ink).clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                if (player.isLoading) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = c.limeTile)
                } else {
                    Icon(
                        if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (player.isPlaying) "Pause" else "Play",
                        tint = c.limeTile,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Box(
                Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onNext),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.SkipNext, "Next", tint = c.ink, modifier = Modifier.size(24.dp))
            }
            Box(
                Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onRepeat),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (player.repeatMode == MusicRepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Repeat",
                    tint = if (player.repeatMode != MusicRepeatMode.OFF) c.accentText else c.inkTertiary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

private enum class MusicTab { SEARCH, QUEUE, SAVED, DOWNLOADS }

/** Full bottom sheet for searching workout music, downloading tracks offline, and managing queue & saved favourites. */
@Composable
fun WorkoutMusicSheet(
    vm: MainViewModel,
    initialQuery: String?,
    onDismiss: () -> Unit,
) {
    val c = VK.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val app by vm.state.collectAsState()
    val player by WorkoutMusicPlayer.state.collectAsState()
    val dlProgress by MusicDownloadManager.progress.collectAsState()
    var downloadNotice by remember { mutableStateOf<String?>(null) }

    val downloadedIds = remember(app.downloadedWorkoutTracks) {
        app.downloadedWorkoutTracks.map { it.track.videoId }.toSet()
    }

    fun triggerDownload(t: WorkoutTrack) {
        if (t.videoId in downloadedIds) {
            downloadNotice = "“${t.title}” is already downloaded for offline playback."
            return
        }
        if (dlProgress.containsKey(t.videoId)) return
        downloadNotice = "Downloading “${t.title}”…"
        MusicDownloadManager.startDownload(
            context = ctx,
            track = t,
            onDone = { saved ->
                vm.addDownloadedTrack(saved)
                downloadNotice = "Downloaded “${t.title}” (${MusicDownloadManager.formatFileSize(saved.sizeBytes)})" +
                    if (saved.exportedToMusicFolder) " · also saved to Music/Voxban" else ""
            },
            onError = { err ->
                downloadNotice = "Download failed: $err"
                vm.showToast(com.voicekanban.app.ui.ToastKind.ERROR, "Download failed", err)
            },
        )
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && WorkoutMusicPlayer.state.value.currentTrack != null) {
            MusicPlaybackService.start(ctx.applicationContext)
        }
    }
    fun requestNotifIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && !Reminders.canPost(ctx)) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var tab by remember(initialQuery) {
        mutableStateOf(
            when (initialQuery) {
                "__QUEUE__" -> MusicTab.QUEUE
                "__SAVED__" -> MusicTab.SAVED
                "__DOWNLOADS__" -> MusicTab.DOWNLOADS
                else -> MusicTab.SEARCH
            },
        )
    }
    var query by remember(initialQuery) {
        mutableStateOf(if (initialQuery != null && !initialQuery.startsWith("__")) initialQuery else "")
    }
    var results by remember { mutableStateOf<List<WorkoutTrack>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    fun runSearch(q: String) {
        val clean = q.trim()
        if (clean.isEmpty()) return
        query = clean
        tab = MusicTab.SEARCH
        searching = true
        searchError = null
        scope.launch {
            runCatching { WorkoutMusicEngine.searchSongs(clean) }
                .onSuccess { list ->
                    results = list
                    if (list.isEmpty()) searchError = "No songs found for “$clean”."
                }
                .onFailure { e ->
                    searchError = e.message?.take(120) ?: "Search failed. Check your internet connection."
                }
            searching = false
        }
    }

    LaunchedEffect(initialQuery) {
        if (initialQuery != null && !initialQuery.startsWith("__")) {
            runSearch(initialQuery)
        }
    }

    AppSheet("Workout Music", "Search, stream & download offline tracks", onDismiss = onDismiss) {
        // Tabs (horizontally scrollable on very compact widths)
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Pill("Search", tab == MusicTab.SEARCH, icon = Icons.Rounded.Search) { tab = MusicTab.SEARCH }
            Pill("Queue (${player.queue.size})", tab == MusicTab.QUEUE, icon = Icons.AutoMirrored.Rounded.QueueMusic) { tab = MusicTab.QUEUE }
            Pill("Saved (${app.savedWorkoutTracks.size})", tab == MusicTab.SAVED, icon = Icons.Rounded.LibraryMusic) { tab = MusicTab.SAVED }
            Pill("Offline (${app.downloadedWorkoutTracks.size})", tab == MusicTab.DOWNLOADS, icon = Icons.Rounded.DownloadDone) { tab = MusicTab.DOWNLOADS }
        }

        downloadNotice?.let { msg ->
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceWell).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(msg, fontSize = 11.sp, color = c.ink, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Close, "Dismiss", tint = c.inkSecondary, modifier = Modifier.size(15.dp).clickable { downloadNotice = null })
            }
        }

        Spacer(Modifier.height(12.dp))

        when (tab) {
            MusicTab.SEARCH -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InputField(
                        value = query,
                        onValue = { query = it },
                        placeholder = "Search songs, artists, workout mixes…",
                        modifier = Modifier.weight(1f),
                        leading = Icons.Rounded.Search,
                        onDone = { runSearch(query) },
                    )
                    Box(
                        Modifier.size(48.dp).clip(CircleShape)
                            .background(if (query.isNotBlank() && !searching) c.limeTile else c.surfaceWell)
                            .clickable(enabled = query.isNotBlank() && !searching) { runSearch(query) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.ink)
                        else Icon(Icons.Rounded.Search, "Search", tint = if (query.isNotBlank()) c.onLime else c.inkTertiary)
                    }
                }

                // Vibe quick-picks
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WORKOUT_VIBES.forEach { (emoji, label, q) ->
                        Chip("$emoji $label", selected = query == q) { runSearch(q) }
                    }
                }

                searchError?.let { err ->
                    Text(err, color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                }

                if (results.isNotEmpty()) {
                    Text(
                        "${results.size} tracks · tap to play (radio queue loads automatically)",
                        fontSize = 11.sp,
                        color = c.inkTertiary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    )
                    results.forEach { t ->
                        val active = player.currentTrack?.videoId == t.videoId
                        val fav = app.savedWorkoutTracks.any { it.videoId == t.videoId }
                        TrackRow(
                            track = t,
                            active = active,
                            isPlaying = active && player.isPlaying,
                            isFav = fav,
                            isDownloaded = t.videoId in downloadedIds,
                            downloadProgress = dlProgress[t.videoId],
                            onPlay = {
                                requestNotifIfNeeded()
                                WorkoutMusicPlayer.playTrack(ctx, t, listOf(t), autoLoadRadio = true)
                            },
                            onQueue = {
                                WorkoutMusicPlayer.addToQueue(t)
                                vm.showToast(com.voicekanban.app.ui.ToastKind.INFO, "Added to queue", t.title)
                            },
                            onDownload = { triggerDownload(t) },
                            onToggleFav = { vm.toggleFavoriteTrack(t) },
                        )
                    }
                } else if (!searching && searchError == null) {
                    Text(
                        "Search for any song, artist, or workout playlist above — or tap a vibe chip to load workout tracks instantly.",
                        fontSize = 13.sp,
                        color = c.inkSecondary,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }

            MusicTab.QUEUE -> {
                if (player.queue.isEmpty()) {
                    Text("Queue is empty. Search and tap a song to start playing.", color = c.inkSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Up next (${player.queue.size})", style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.weight(1f))
                        Chip("Stop & clear", tint = c.danger) { WorkoutMusicPlayer.stop(ctx) }
                    }
                    player.queue.forEachIndexed { i, t ->
                        val active = i == player.queueIndex
                        val fav = app.savedWorkoutTracks.any { it.videoId == t.videoId }
                        TrackRow(
                            track = t,
                            active = active,
                            isPlaying = active && player.isPlaying,
                            isFav = fav,
                            isDownloaded = t.videoId in downloadedIds,
                            downloadProgress = dlProgress[t.videoId],
                            onPlay = { WorkoutMusicPlayer.playQueueIndex(ctx, i) },
                            onDownload = { triggerDownload(t) },
                            onRemove = if (!active) ({ WorkoutMusicPlayer.removeFromQueue(i) }) else null,
                            onToggleFav = { vm.toggleFavoriteTrack(t) },
                        )
                    }
                }
            }

            MusicTab.SAVED -> {
                if (app.savedWorkoutTracks.isEmpty()) {
                    Text("No saved workout tracks yet. Tap the heart icon on any song to save it here for quick access.", color = c.inkSecondary, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${app.savedWorkoutTracks.size} saved tracks", style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.weight(1f))
                        Chip("▶ Play all", selected = true, tint = c.voiceTertiary) {
                            requestNotifIfNeeded()
                            WorkoutMusicPlayer.playTrack(ctx, app.savedWorkoutTracks.first(), app.savedWorkoutTracks, autoLoadRadio = false)
                        }
                    }
                    app.savedWorkoutTracks.forEach { t ->
                        val active = player.currentTrack?.videoId == t.videoId
                        TrackRow(
                            track = t,
                            active = active,
                            isPlaying = active && player.isPlaying,
                            isFav = true,
                            isDownloaded = t.videoId in downloadedIds,
                            downloadProgress = dlProgress[t.videoId],
                            onPlay = {
                                requestNotifIfNeeded()
                                WorkoutMusicPlayer.playTrack(ctx, t, app.savedWorkoutTracks, autoLoadRadio = false)
                            },
                            onQueue = {
                                WorkoutMusicPlayer.addToQueue(t)
                                vm.showToast(com.voicekanban.app.ui.ToastKind.INFO, "Added to queue", t.title)
                            },
                            onDownload = { triggerDownload(t) },
                            onToggleFav = { vm.toggleFavoriteTrack(t) },
                        )
                    }
                }
            }

            MusicTab.DOWNLOADS -> {
                val downloads = app.downloadedWorkoutTracks
                if (downloads.isEmpty()) {
                    Text(
                        "No downloaded songs yet. Tap the download icon on any track to save it for 100% offline playback (also saved to Music/Voxban).",
                        color = c.inkSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    val totalSize = downloads.sumOf { it.sizeBytes }
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${downloads.size} offline · ${MusicDownloadManager.formatFileSize(totalSize)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = c.ink,
                            modifier = Modifier.weight(1f),
                        )
                        Chip("▶ Play offline", selected = true, tint = c.low) {
                            requestNotifIfNeeded()
                            val list = downloads.map { it.track }
                            WorkoutMusicPlayer.playTrack(ctx, list.first(), list, autoLoadRadio = false)
                        }
                    }
                    downloads.forEach { d ->
                        val t = d.track
                        val active = player.currentTrack?.videoId == t.videoId
                        val fav = app.savedWorkoutTracks.any { it.videoId == t.videoId }
                        TrackRow(
                            track = t,
                            active = active,
                            isPlaying = active && player.isPlaying,
                            isFav = fav,
                            isDownloaded = true,
                            extraMeta = MusicDownloadManager.formatFileSize(d.sizeBytes),
                            onPlay = {
                                requestNotifIfNeeded()
                                val list = downloads.map { it.track }
                                WorkoutMusicPlayer.playTrack(ctx, t, list, autoLoadRadio = false)
                            },
                            onDeleteDownload = {
                                vm.removeDownloadedTrack(t.videoId)
                                downloadNotice = "Deleted offline copy of “${t.title}”."
                            },
                            onToggleFav = { vm.toggleFavoriteTrack(t) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: WorkoutTrack,
    active: Boolean,
    isPlaying: Boolean,
    isFav: Boolean,
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    extraMeta: String? = null,
    onPlay: () -> Unit,
    onQueue: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onDeleteDownload: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onToggleFav: () -> Unit,
) {
    val c = VK.colors
    Row(
        Modifier.padding(bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (active) c.limeTile else c.raised)
            .border(1.dp, if (active) Color.Transparent else c.border, RoundedCornerShape(16.dp))
            .clickable(onClick = onPlay)
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArt(track.thumbnailUrl, 44.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (active) c.onLime else c.ink,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                track.artist.ifBlank { null },
                track.durationSec.takeIf { it > 0L }?.let { WorkoutMusicEngine.formatDuration(it, isMillis = false) },
                extraMeta,
                if (isDownloaded && extraMeta == null) "Offline" else null,
                if (active && isPlaying) "Playing" else null,
            ).joinToString(" · ")
            Text(
                meta.ifBlank { "Music" },
                color = if (active) c.onLime.copy(alpha = 0.72f) else c.inkSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onDownload != null) {
            Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onDownload), contentAlignment = Alignment.Center) {
                when {
                    downloadProgress != null -> CircularProgressIndicator(
                        progress = { downloadProgress.coerceIn(0.05f, 1f) },
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = if (active) c.onLime else c.accent,
                    )
                    isDownloaded -> Icon(
                        Icons.Rounded.DownloadDone,
                        "Downloaded",
                        tint = if (active) c.onLime else c.low,
                        modifier = Modifier.size(18.dp),
                    )
                    else -> Icon(
                        Icons.Rounded.Download,
                        "Download",
                        tint = if (active) c.onLime else c.inkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        if (onDeleteDownload != null) {
            Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onDeleteDownload), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DeleteOutline, "Delete download", tint = if (active) c.onLime else c.danger, modifier = Modifier.size(18.dp))
            }
        }
        Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onToggleFav), contentAlignment = Alignment.Center) {
            Icon(
                if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                "Favourite",
                tint = if (isFav) (if (active) c.onLime else c.high) else (if (active) c.onLime.copy(alpha = 0.7f) else c.inkTertiary),
                modifier = Modifier.size(18.dp),
            )
        }
        if (onQueue != null) {
            Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onQueue), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to queue", tint = if (active) c.onLime else c.inkSecondary, modifier = Modifier.size(19.dp))
            }
        }
        if (onRemove != null) {
            Box(Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Remove from queue", tint = c.inkTertiary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun TrackArt(url: String, size: Dp) {
    val c = VK.colors
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(c.surfaceWell),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = c.inkTertiary, modifier = Modifier.size(size * 0.48f))
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Slim mini-player shown above the nav bar when workout music is active on other tabs. */
@Composable
fun MiniMusicBar(
    onOpenWorkout: () -> Unit,
) {
    val c = VK.colors
    val ctx = LocalContext.current
    val player by WorkoutMusicPlayer.state.collectAsState()
    val track = player.currentTrack ?: return

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.raised)
            .border(1.dp, c.buttonBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenWorkout)
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArt(track.thumbnailUrl, 36.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                track.artist.ifBlank { "Workout music" },
                color = c.inkSecondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(c.limeTile)
                .clickable { WorkoutMusicPlayer.togglePlayPause(ctx) },
            contentAlignment = Alignment.Center,
        ) {
            if (player.isLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.onLime)
            else Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause", tint = c.onLime, modifier = Modifier.size(20.dp))
        }
        Box(
            Modifier.size(34.dp).clip(CircleShape).clickable { WorkoutMusicPlayer.next(ctx) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.SkipNext, "Next track", tint = c.ink, modifier = Modifier.size(20.dp))
        }
        Box(
            Modifier.size(32.dp).clip(CircleShape).clickable { WorkoutMusicPlayer.stop(ctx) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, "Stop music", tint = c.inkTertiary, modifier = Modifier.size(16.dp))
        }
    }
}
