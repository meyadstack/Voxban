package com.voicekanban.app.music

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.voicekanban.app.data.DownloadedTrack
import com.voicekanban.app.data.WorkoutTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads workout music tracks to permanent local app storage (for instant 100% offline playback)
 * and exports a copy into the device's public Music/Voxban folder via MediaStore on Android 10+.
 */
object MusicDownloadManager {
    private const val TAG = "MusicDownloadManager"
    private const val CHUNK_BYTES = 512L * 1024L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** Active download progress (0f..1f) keyed by videoId. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress.asStateFlow()

    fun downloadsDir(context: Context): File =
        File(context.applicationContext.filesDir, "downloaded_music").apply { mkdirs() }

    /** Returns the existing downloaded audio file for [videoId], if present on disk. */
    fun localFileFor(context: Context, videoId: String): File? {
        val dir = downloadsDir(context)
        val candidates = listOf("m4a", "webm", "opus", "mp3", "ogg", "mp4")
        for (ext in candidates) {
            val f = File(dir, "$videoId.$ext")
            if (f.exists() && f.length() > 1024L) return f
        }
        return null
    }

    fun isDownloading(videoId: String): Boolean = activeJobs.containsKey(videoId)

    fun cancelDownload(videoId: String) {
        activeJobs.remove(videoId)?.cancel()
        _progress.update { it - videoId }
    }

    fun deleteDownload(context: Context, downloaded: DownloadedTrack) {
        cancelDownload(downloaded.track.videoId)
        runCatching { File(downloaded.localPath).delete() }
        localFileFor(context, downloaded.track.videoId)?.let { runCatching { it.delete() } }
    }

    /** Downloads [track] in the background, reporting progress in [progress] and invoking [onDone]/[onError] on Main. */
    fun startDownload(
        context: Context,
        track: WorkoutTrack,
        onDone: (DownloadedTrack) -> Unit,
        onError: (String) -> Unit,
    ) {
        val ctx = context.applicationContext
        if (activeJobs.containsKey(track.videoId)) return

        _progress.update { it + (track.videoId to 0.02f) }
        val job = scope.launch {
            try {
                val stream = WorkoutMusicEngine.extractAudioStream(track.videoId)
                if (stream.audioUrl.isBlank() || !stream.audioUrl.startsWith("http")) {
                    throw IOException("This track uses a segmented stream that cannot be saved directly.")
                }
                val mime = stream.mimeType?.substringBefore(';')?.trim()?.ifBlank { "audio/webm" } ?: "audio/webm"
                val ext = extensionForMime(mime)
                val dest = File(downloadsDir(ctx), "${track.videoId}.$ext")
                val tmp = File(downloadsDir(ctx), "${track.videoId}.$ext.part")

                val totalLen = stream.contentLengthBytes ?: -1L
                downloadToFile(
                    url = stream.audioUrl,
                    headers = stream.headers,
                    totalLength = totalLen,
                    target = tmp,
                ) { written, total ->
                    val pct = if (total > 0L) (written.toFloat() / total.toFloat()).coerceIn(0.03f, 0.96f) else 0.5f
                    _progress.update { it + (track.videoId to pct) }
                }

                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                if (dest.length() < 1024L) {
                    dest.delete()
                    throw IOException("Downloaded audio file was empty.")
                }

                val exported = exportToPublicMusicFolder(ctx, dest, track, mime, ext)
                val saved = DownloadedTrack(
                    track = track,
                    localPath = dest.absolutePath,
                    mimeType = mime,
                    sizeBytes = dest.length(),
                    downloadedAt = System.currentTimeMillis(),
                    exportedToMusicFolder = exported,
                )
                withContext(Dispatchers.Main) { onDone(saved) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download failed for ${track.videoId}", e)
                withContext(Dispatchers.Main) {
                    onError(e.message?.take(120) ?: "Couldn’t download this track.")
                }
            } finally {
                activeJobs.remove(track.videoId)
                _progress.update { it - track.videoId }
            }
        }
        activeJobs[track.videoId] = job
    }

    private fun downloadToFile(
        url: String,
        headers: Map<String, String>,
        totalLength: Long,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val http = WorkoutMusicEngine.mediaOkHttp
        FileOutputStream(target).use { out ->
            val buf = ByteArray(32 * 1024)
            if (totalLength > 0L) {
                var pos = 0L
                while (pos < totalLength) {
                    val end = minOf(pos + CHUNK_BYTES - 1L, totalLength - 1L)
                    val req = Request.Builder().url(url).apply {
                        headers.forEach { (k, v) -> if (!k.equals("Range", ignoreCase = true)) header(k, v) }
                        header("Range", "bytes=$pos-$end")
                    }.build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful && resp.code != 206) {
                            throw IOException("Audio server returned HTTP ${resp.code}")
                        }
                        val stream = resp.body.byteStream()
                        while (true) {
                            val n = stream.read(buf)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            pos += n
                            onProgress(pos, totalLength)
                        }
                    }
                }
            } else {
                val req = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Audio server returned HTTP ${resp.code}")
                    val len = resp.body.contentLength()
                    val stream = resp.body.byteStream()
                    var pos = 0L
                    while (true) {
                        val n = stream.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        pos += n
                        onProgress(pos, len)
                    }
                }
            }
            out.flush()
        }
    }

    private fun exportToPublicMusicFolder(
        context: Context,
        source: File,
        track: WorkoutTrack,
        mime: String,
        ext: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            val baseName = sanitizeFileName(
                if (track.artist.isNotBlank()) "${track.title} - ${track.artist}" else track.title,
            )
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, "$baseName.$ext")
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.TITLE, track.title)
                if (track.artist.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, track.artist)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Voxban")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { os ->
                source.inputStream().use { ins -> ins.copyTo(os) }
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.onFailure { Log.d(TAG, "Public Music export skipped: ${it.message}") }.getOrDefault(false)
    }

    fun extensionForMime(mime: String): String = when {
        mime.contains("mp4", true) || mime.contains("m4a", true) || mime.contains("aac", true) -> "m4a"
        mime.contains("mpeg", true) || mime.contains("mp3", true) -> "mp3"
        mime.contains("ogg", true) -> "ogg"
        else -> "webm"
    }

    fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("""[\\/:*?"<>|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)
            .ifBlank { "voxban-track" }

    fun formatFileSize(bytes: Long): String = when {
        bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024L -> "%d KB".format(bytes / 1_024L)
        else -> "$bytes B"
    }
}
