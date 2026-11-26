package com.voicekanban.app.ai

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.voicekanban.app.MainActivity
import com.voicekanban.app.R
import com.voicekanban.app.VoiceKanbanApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class Gemma4ModelSpec(
    val id: String,
    val name: String,
    val shortName: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedSizeBytes: Long,
    val description: String,
    val recommended: Boolean = false,
)

sealed interface GemmaDownloadStatus {
    data object NotDownloaded : GemmaDownloadStatus
    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long = 0L,
    ) : GemmaDownloadStatus {
        val progress: Float
            get() = if (totalBytes > 0L) (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
        val percent: Int get() = (progress * 100f).toInt().coerceIn(0, 100)
    }
    data class Paused(val downloadedBytes: Long, val totalBytes: Long) : GemmaDownloadStatus {
        val progress: Float
            get() = if (totalBytes > 0L) (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
        val percent: Int get() = (progress * 100f).toInt().coerceIn(0, 100)
    }
    data class Ready(val filePath: String, val sizeBytes: Long) : GemmaDownloadStatus
    data class Error(val message: String, val downloadedBytes: Long = 0L, val totalBytes: Long = 0L) : GemmaDownloadStatus
}

object Gemma4Catalog {
    const val E2B_ID = "gemma-4-e2b"
    const val E4B_ID = "gemma-4-e4b"

    val models: List<Gemma4ModelSpec> = listOf(
        Gemma4ModelSpec(
            id = E2B_ID,
            name = "Gemma 4 E2B",
            shortName = "E2B · 2.58 GB",
            fileName = "gemma-4-E2B-it.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            expectedSizeBytes = 2_588_147_712L,
            description = "Recommended for most phones. Fast offline AI with full tool support.",
            recommended = true,
        ),
        Gemma4ModelSpec(
            id = E4B_ID,
            name = "Gemma 4 E4B",
            shortName = "E4B · 3.66 GB",
            fileName = "gemma-4-E4B-it.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            expectedSizeBytes = 3_659_530_240L,
            description = "Higher reasoning accuracy for devices with 8 GB+ RAM.",
            recommended = false,
        ),
    )

    fun byId(id: String?): Gemma4ModelSpec =
        models.firstOrNull { it.id == id } ?: models.first()
}

object Gemma4DownloadManager {
    private const val MIN_VALID_MODEL_BYTES = 100L * 1024L * 1024L

    private val _statuses = MutableStateFlow<Map<String, GemmaDownloadStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, GemmaDownloadStatus>> = _statuses.asStateFlow()

    fun modelsDir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(context: Context, spec: Gemma4ModelSpec): File = File(modelsDir(context), spec.fileName)

    fun partFile(context: Context, spec: Gemma4ModelSpec): File = File(modelsDir(context), "${spec.fileName}.part")

    fun refresh(context: Context) {
        val next = LinkedHashMap<String, GemmaDownloadStatus>()
        val existing = _statuses.value
        for (spec in Gemma4Catalog.models) {
            val cur = existing[spec.id]
            if (cur is GemmaDownloadStatus.Downloading) {
                next[spec.id] = cur
                continue
            }
            val finalFile = modelFile(context, spec)
            val part = partFile(context, spec)
            next[spec.id] = when {
                finalFile.exists() && finalFile.length() >= MIN_VALID_MODEL_BYTES ->
                    GemmaDownloadStatus.Ready(finalFile.absolutePath, finalFile.length())
                part.exists() && part.length() > 0L ->
                    GemmaDownloadStatus.Paused(part.length(), spec.expectedSizeBytes)
                else -> GemmaDownloadStatus.NotDownloaded
            }
        }
        _statuses.value = next
    }

    fun statusFor(modelId: String): GemmaDownloadStatus =
        _statuses.value[modelId] ?: GemmaDownloadStatus.NotDownloaded

    fun readyModelPath(context: Context, modelId: String): String? {
        val spec = Gemma4Catalog.byId(modelId)
        val file = modelFile(context, spec)
        return if (file.exists() && file.length() >= MIN_VALID_MODEL_BYTES) file.absolutePath else null
    }

    fun isAnyDownloading(): Boolean = _statuses.value.values.any { it is GemmaDownloadStatus.Downloading }

    internal fun setStatus(modelId: String, status: GemmaDownloadStatus) {
        _statuses.update { it + (modelId to status) }
    }

    fun startDownload(context: Context, modelId: String) {
        val spec = Gemma4Catalog.byId(modelId)
        if (readyModelPath(context, spec.id) != null) {
            refresh(context)
            return
        }
        val intent = Intent(context, Gemma4DownloadService::class.java).apply {
            action = Gemma4DownloadService.ACTION_START
            putExtra(Gemma4DownloadService.EXTRA_MODEL_ID, spec.id)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun pauseDownload(context: Context, modelId: String) {
        val intent = Intent(context, Gemma4DownloadService::class.java).apply {
            action = Gemma4DownloadService.ACTION_PAUSE
            putExtra(Gemma4DownloadService.EXTRA_MODEL_ID, modelId)
        }
        context.startService(intent)
    }

    fun cancelDownload(context: Context, modelId: String) {
        val intent = Intent(context, Gemma4DownloadService::class.java).apply {
            action = Gemma4DownloadService.ACTION_CANCEL
            putExtra(Gemma4DownloadService.EXTRA_MODEL_ID, modelId)
        }
        context.startService(intent)
    }

    fun deleteModel(context: Context, modelId: String) {
        val spec = Gemma4Catalog.byId(modelId)
        LocalGemmaEngine.unloadIfModel(spec.id)
        if (statusFor(spec.id) is GemmaDownloadStatus.Downloading) {
            cancelDownload(context, spec.id)
        }
        runCatching { modelFile(context, spec).delete() }
        runCatching { partFile(context, spec).delete() }
        setStatus(spec.id, GemmaDownloadStatus.NotDownloaded)
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000L -> "%d KB".format(bytes / 1_000L)
        else -> "$bytes B"
    }
}

/**
 * Foreground service that downloads Gemma 4 (.litertlm) reliably in the background
 * with HTTP Range resume and a live progress bar in the Android notification shade.
 */
class Gemma4DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var activeModelId: String? = null
    private val pauseRequested = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    private val http = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID) ?: activeModelId ?: Gemma4Catalog.E2B_ID
        val spec = Gemma4Catalog.byId(modelId)

        when (action) {
            ACTION_START -> {
                if (activeJob?.isActive == true && activeModelId == spec.id) {
                    return START_NOT_STICKY
                }
                pauseRequested.set(false)
                cancelRequested.set(false)
                activeJob?.cancel()
                activeModelId = spec.id
                val existingPart = Gemma4DownloadManager.partFile(this, spec).takeIf { it.exists() }?.length() ?: 0L
                val initialNotif = buildProgressNotification(spec, existingPart, spec.expectedSizeBytes, 0L)
                startForegroundCompat(initialNotif)
                Gemma4DownloadManager.setStatus(
                    spec.id,
                    GemmaDownloadStatus.Downloading(existingPart, spec.expectedSizeBytes, 0L),
                )
                activeJob = scope.launch { runDownload(spec) }
            }

            ACTION_PAUSE -> {
                pauseRequested.set(true)
                activeJob?.cancel()
                val partLen = Gemma4DownloadManager.partFile(this, spec).takeIf { it.exists() }?.length() ?: 0L
                Gemma4DownloadManager.setStatus(
                    spec.id,
                    if (partLen > 0L) GemmaDownloadStatus.Paused(partLen, spec.expectedSizeBytes)
                    else GemmaDownloadStatus.NotDownloaded,
                )
                finishService()
            }

            ACTION_CANCEL -> {
                cancelRequested.set(true)
                activeJob?.cancel()
                runCatching { Gemma4DownloadManager.partFile(this, spec).delete() }
                Gemma4DownloadManager.setStatus(spec.id, GemmaDownloadStatus.NotDownloaded)
                finishService()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID_PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID_PROGRESS, notification)
        }
    }

    private fun runDownload(spec: Gemma4ModelSpec) {
        val nm = getSystemService(NotificationManager::class.java)
        val finalFile = Gemma4DownloadManager.modelFile(this, spec)
        val partFile = Gemma4DownloadManager.partFile(this, spec)

        try {
            if (finalFile.exists() && finalFile.length() >= 100L * 1024L * 1024L) {
                Gemma4DownloadManager.setStatus(spec.id, GemmaDownloadStatus.Ready(finalFile.absolutePath, finalFile.length()))
                showDoneNotification(nm, spec, success = true, detail = "Ready for offline AI with all tools.")
                finishService()
                return
            }

            var resumeOffset = if (partFile.exists()) partFile.length() else 0L
            // Check available free disk space (leave 250 MB safety margin)
            val neededBytes = (spec.expectedSizeBytes - resumeOffset).coerceAtLeast(0L) + 250L * 1024L * 1024L
            val freeBytes = runCatching { StatFs(filesDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (freeBytes < neededBytes) {
                val msg = "Not enough storage. Need ${Gemma4DownloadManager.formatBytes(neededBytes)} free."
                Gemma4DownloadManager.setStatus(spec.id, GemmaDownloadStatus.Error(msg, resumeOffset, spec.expectedSizeBytes))
                showDoneNotification(nm, spec, success = false, detail = msg)
                finishService()
                return
            }

            val reqBuilder = Request.Builder()
                .url(spec.downloadUrl)
                .header("User-Agent", "Voxban-Android")
            if (resumeOffset > 0L) {
                reqBuilder.header("Range", "bytes=$resumeOffset-")
            }

            http.newCall(reqBuilder.build()).execute().use { resp ->
                // If server rejected the range (416) or ignored Range (200), restart cleanly from 0.
                val restartFromZero = resp.code == 416 || (resumeOffset > 0L && resp.code == 200)
                if (resp.code == 416) {
                    runCatching { partFile.delete() }
                    resumeOffset = 0L
                    val freshReq = Request.Builder().url(spec.downloadUrl).header("User-Agent", "Voxban-Android").build()
                    http.newCall(freshReq).execute().use { freshResp ->
                        streamToDisk(freshResp, spec, partFile, finalFile, 0L, nm)
                    }
                    return
                }
                if (restartFromZero) {
                    runCatching { partFile.delete() }
                    resumeOffset = 0L
                }
                streamToDisk(resp, spec, partFile, finalFile, resumeOffset, nm)
            }
        } catch (_: CancellationException) {
            handleCancelledOrPaused(spec, partFile)
        } catch (e: Exception) {
            if (pauseRequested.get() || cancelRequested.get()) {
                handleCancelledOrPaused(spec, partFile)
            } else {
                Log.w(TAG, "Gemma 4 download failed", e)
                val partLen = partFile.takeIf { it.exists() }?.length() ?: 0L
                val msg = e.message?.take(140) ?: "Download interrupted. Tap Resume to continue."
                Gemma4DownloadManager.setStatus(spec.id, GemmaDownloadStatus.Error(msg, partLen, spec.expectedSizeBytes))
                showDoneNotification(nm, spec, success = false, detail = msg)
                finishService()
            }
        }
    }

    private fun streamToDisk(
        resp: okhttp3.Response,
        spec: Gemma4ModelSpec,
        partFile: File,
        finalFile: File,
        startOffset: Long,
        nm: NotificationManager,
    ) {
        if (!resp.isSuccessful && resp.code != 206) {
            throw IllegalStateException("Download server returned HTTP ${resp.code}")
        }
        val body = resp.body
        val contentLen = body.contentLength()
        val totalBytes = when {
            resp.code == 206 && contentLen > 0L -> startOffset + contentLen
            contentLen > 0L -> contentLen
            else -> spec.expectedSizeBytes
        }

        var downloaded = startOffset
        var lastEmitAt = 0L
        var speedWindowStart = System.currentTimeMillis()
        var speedWindowBytes = 0L
        var speedBps = 0L

        partFile.parentFile?.mkdirs()
        RandomAccessFile(partFile, "rw").use { raf ->
            raf.seek(startOffset)
            body.byteStream().use { input ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    if (pauseRequested.get() || cancelRequested.get()) {
                        throw CancellationException("Stopped by user")
                    }
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    raf.write(buf, 0, n)
                    downloaded += n
                    speedWindowBytes += n

                    val now = System.currentTimeMillis()
                    val elapsed = now - speedWindowStart
                    if (elapsed >= 1000L) {
                        speedBps = (speedWindowBytes * 1000L) / elapsed.coerceAtLeast(1L)
                        speedWindowStart = now
                        speedWindowBytes = 0L
                    }
                    if (now - lastEmitAt >= 450L) {
                        lastEmitAt = now
                        Gemma4DownloadManager.setStatus(
                            spec.id,
                            GemmaDownloadStatus.Downloading(downloaded, totalBytes, speedBps),
                        )
                        nm.notify(NOTIF_ID_PROGRESS, buildProgressNotification(spec, downloaded, totalBytes, speedBps))
                    }
                }
            }
        }

        if (pauseRequested.get() || cancelRequested.get()) {
            handleCancelledOrPaused(spec, partFile)
            return
        }

        if (finalFile.exists()) finalFile.delete()
        val renamed = partFile.renameTo(finalFile)
        if (!renamed) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }

        Gemma4DownloadManager.setStatus(spec.id, GemmaDownloadStatus.Ready(finalFile.absolutePath, finalFile.length()))
        showDoneNotification(nm, spec, success = true, detail = "${spec.name} is ready for offline AI.")
        finishService()
    }

    private fun handleCancelledOrPaused(spec: Gemma4ModelSpec, partFile: File) {
        if (cancelRequested.get()) {
            runCatching { partFile.delete() }
            Gemma4DownloadManager.setStatus(spec.id, GemmaDownloadStatus.NotDownloaded)
        } else {
            val len = partFile.takeIf { it.exists() }?.length() ?: 0L
            Gemma4DownloadManager.setStatus(
                spec.id,
                if (len > 0L) GemmaDownloadStatus.Paused(len, spec.expectedSizeBytes)
                else GemmaDownloadStatus.NotDownloaded,
            )
        }
        finishService()
    }

    private fun finishService() {
        activeModelId = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 4101, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildProgressNotification(
        spec: Gemma4ModelSpec,
        downloaded: Long,
        total: Long,
        speedBps: Long,
    ): Notification {
        val pct = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
        val speedPart = if (speedBps > 0L) " · ${Gemma4DownloadManager.formatBytes(speedBps)}/s" else ""
        val text = "${Gemma4DownloadManager.formatBytes(downloaded)} / ${Gemma4DownloadManager.formatBytes(total)} ($pct%)$speedPart"

        val pauseIntent = PendingIntent.getService(
            this, 4102,
            Intent(this, Gemma4DownloadService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_MODEL_ID, spec.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getService(
            this, 4103,
            Intent(this, Gemma4DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_MODEL_ID, spec.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, VoiceKanbanApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_voicekanban)
            .setContentTitle("Downloading ${spec.name}")
            .setContentText(text)
            .setSubText("On-device AI")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, pct, total <= 0L)
            .setContentIntent(openAppIntent())
            .addAction(0, "Pause", pauseIntent)
            .addAction(0, "Cancel", cancelIntent)
            .build()
    }

    private fun showDoneNotification(nm: NotificationManager, spec: Gemma4ModelSpec, success: Boolean, detail: String) {
        val notif = NotificationCompat.Builder(this, VoiceKanbanApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_stat_voicekanban)
            .setContentTitle(if (success) "${spec.name} downloaded" else "${spec.name} download paused")
            .setContentText(detail)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        nm.notify(NOTIF_ID_DONE, notif)
    }

    override fun onDestroy() {
        activeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Gemma4DownloadService"
        const val ACTION_START = "com.voicekanban.app.action.GEMMA_START"
        const val ACTION_PAUSE = "com.voicekanban.app.action.GEMMA_PAUSE"
        const val ACTION_CANCEL = "com.voicekanban.app.action.GEMMA_CANCEL"
        const val EXTRA_MODEL_ID = "model_id"
        private const val NOTIF_ID_PROGRESS = 4401
        private const val NOTIF_ID_DONE = 4402
    }
}
