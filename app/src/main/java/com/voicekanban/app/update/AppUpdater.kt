package com.voicekanban.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.voicekanban.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val version: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val sha256: String? = null,
    val htmlUrl: String = "",
    val publishedAt: String = "",
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val downloadedBytes: Long, val totalBytes: Long) : UpdateState {
        val progress: Float get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else -1f
    }
    data class ReadyToInstall(val release: ReleaseInfo, val apkFile: File) : UpdateState
    data class Error(val message: String, val release: ReleaseInfo? = null) : UpdateState
}

/**
 * Checks GitHub Releases (`meyadstack/Voxban`) for newer APKs, downloads them
 * into app cache with progress and optional SHA-256 verification, and launches
 * Android's package installer in-place.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    const val REPO_OWNER = "meyadstack"
    const val REPO_NAME = "Voxban"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val PREFS = "app_updater"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60_000L // 6 hours

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var activeCall: Call? = null

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Checks GitHub Releases for a newer version. Automatic checks are throttled and fail silently. */
    fun checkForUpdate(
        context: Context,
        manual: Boolean = false,
        dismissedTag: String = "",
    ) {
        val cur = _state.value
        if (cur is UpdateState.Checking || cur is UpdateState.Downloading) return
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual) {
            val last = prefs.getLong(KEY_LAST_CHECK, 0L)
            if (now - last < AUTO_CHECK_INTERVAL_MS && cur !is UpdateState.Idle) return
        }

        activeJob?.cancel()
        activeJob = scope.launch {
            if (manual) _state.value = UpdateState.Checking
            try {
                val release = fetchLatestRelease()
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                if (release != null && isNewerVersion(release.version, currentVersion)) {
                    if (!manual && dismissedTag.equals(release.tagName, ignoreCase = true)) {
                        _state.value = UpdateState.Idle
                    } else {
                        // Reuse already-downloaded verified APK if present
                        val cached = cachedApkFile(appCtx, release)
                        if (cached.exists() && isValidApkForApp(appCtx, cached) && verifySha256IfPresent(cached, release.sha256)) {
                            _state.value = UpdateState.ReadyToInstall(release, cached)
                        } else {
                            _state.value = UpdateState.Available(release)
                        }
                    }
                } else {
                    _state.value = if (manual) UpdateState.UpToDate(System.currentTimeMillis()) else UpdateState.Idle
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                if (manual) {
                    _state.value = UpdateState.Error(e.message?.take(140) ?: "Couldn’t reach GitHub.")
                }
            }
        }
    }

    /** Downloads the release APK into cache and validates it before offering installation. */
    fun downloadUpdate(context: Context, release: ReleaseInfo, onReady: ((File) -> Unit)? = null) {
        if (_state.value is UpdateState.Downloading) return
        val appCtx = context.applicationContext
        activeJob?.cancel()
        activeJob = scope.launch {
            _state.value = UpdateState.Downloading(release, 0L, release.apkSize)
            val dir = File(appCtx.cacheDir, "updates").apply { mkdirs() }
            // Clean up old APKs to keep cache tidy.
            dir.listFiles()?.forEach { f -> if (f.name != release.apkName) f.delete() }
            val target = File(dir, release.apkName)
            val part = File(dir, "${release.apkName}.part")

            try {
                val req = Request.Builder()
                    .url(release.apkUrl)
                    .header("User-Agent", "Voxban/$currentVersion")
                    .header("Accept", "application/octet-stream")
                    .build()
                val call = http.newCall(req)
                activeCall = call
                call.execute().use { resp ->
                    if (!resp.isSuccessful) error("Download failed (HTTP ${resp.code})")
                    val body = resp.body
                    val total = body.contentLength().takeIf { it > 0L } ?: release.apkSize
                    val digest = MessageDigest.getInstance("SHA-256")
                    var readTotal = 0L
                    var lastEmit = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(part).use { out ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                if (!isActive) throw CancellationException("Cancelled")
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                readTotal += n
                                val t = System.currentTimeMillis()
                                if (t - lastEmit >= 120L) {
                                    lastEmit = t
                                    _state.value = UpdateState.Downloading(release, readTotal, total)
                                }
                            }
                            out.flush()
                        }
                    }
                    _state.value = UpdateState.Downloading(release, readTotal, total)

                    // Optional SHA-256 verification against GitHub asset digest
                    release.sha256?.let { expected ->
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!actual.equals(expected, ignoreCase = true)) {
                            part.delete()
                            error("Downloaded file checksum didn’t match the release.")
                        }
                    }

                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    if (!isValidApkForApp(appCtx, target)) {
                        target.delete()
                        error("Downloaded APK is invalid or for a different app.")
                    }

                    _state.value = UpdateState.ReadyToInstall(release, target)
                    if (onReady != null) withContext(Dispatchers.Main) { onReady(target) }
                }
            } catch (e: CancellationException) {
                part.delete()
                _state.value = UpdateState.Available(release)
                throw e
            } catch (e: Exception) {
                part.delete()
                Log.w(TAG, "APK download failed", e)
                _state.value = UpdateState.Error(e.message?.take(140) ?: "Download failed.", release)
            } finally {
                activeCall = null
            }
        }
    }

    fun cancelDownload() {
        activeCall?.cancel()
        activeJob?.cancel()
        val cur = _state.value
        if (cur is UpdateState.Downloading) _state.value = UpdateState.Available(cur.release)
    }

    fun dismissBanner() {
        if (_state.value is UpdateState.Available || _state.value is UpdateState.Error || _state.value is UpdateState.UpToDate) {
            _state.value = UpdateState.Idle
        }
    }

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Launches Android's system package installer for the downloaded APK. */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) return false
        if (!canInstallPackages(context)) {
            openUnknownSourcesSettings(context)
            return false
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.onFailure { Log.e(TAG, "Failed to start APK installer", it) }.getOrDefault(false)
    }

    private fun cachedApkFile(context: Context, release: ReleaseInfo): File =
        File(File(context.cacheDir, "updates"), release.apkName)

    private fun isValidApkForApp(context: Context, file: File): Boolean = runCatching {
        if (!file.exists() || file.length() < 100_000L) return false
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }
        info != null && info.packageName == context.packageName
    }.getOrDefault(false)

    private fun verifySha256IfPresent(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return true
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun fetchLatestRelease(): ReleaseInfo? {
        val req = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Voxban/$currentVersion")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) error("GitHub returned HTTP ${resp.code}")
            val body = resp.body.string()
            return parseReleaseJson(body)
        }
    }

    /** Pure parser for a GitHub `/releases/latest` payload (unit-tested). */
    fun parseReleaseJson(raw: String): ReleaseInfo? {
        val root = json.parseToJsonElement(raw).jsonObject
        if (root["draft"]?.jsonPrimitive?.booleanOrNull == true) return null
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (tag.isEmpty()) return null
        val version = normalizeVersion(tag)
        val title = root["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "Voxban $version" }
        val notes = root["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val htmlUrl = root["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val publishedAt = root["published_at"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val assets = root["assets"] as? JsonArray ?: return null
        val apkAsset = assets.mapNotNull { it as? JsonObject }
            .firstOrNull { a -> a["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", ignoreCase = true) == true }
            ?: return null
        val apkUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (apkUrl.isEmpty()) return null
        val apkName = apkAsset["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "Voxban-$version.apk" }
        val apkSize = apkAsset["size"]?.jsonPrimitive?.longOrNull ?: 0L
        val digestRaw = apkAsset["digest"]?.jsonPrimitive?.contentOrNull?.trim()
        val sha256 = digestRaw?.removePrefix("sha256:")?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
        return ReleaseInfo(
            tagName = tag,
            version = version,
            title = title,
            notes = notes,
            apkUrl = apkUrl,
            apkName = apkName,
            apkSize = apkSize,
            sha256 = sha256,
            htmlUrl = htmlUrl,
            publishedAt = publishedAt,
        )
    }

    fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V")

    /** Returns true when [remote] is strictly newer than [current] using semantic version comparison. */
    fun isNewerVersion(remote: String, current: String): Boolean {
        val r = normalizeVersion(remote)
        val c = normalizeVersion(current)
        val rCore = r.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val cCore = c.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(rCore.size, cCore.size)
        for (i in 0 until n) {
            val rv = rCore.getOrElse(i) { 0 }
            val cv = cCore.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        val rPre = r.substringAfter('-', "")
        val cPre = c.substringAfter('-', "")
        // Stable release (no pre-release suffix) is newer than a pre-release of the same core version.
        if (rPre.isEmpty() && cPre.isNotEmpty()) return true
        if (rPre.isNotEmpty() && cPre.isEmpty()) return false
        return rPre > cPre
    }

    fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024L * 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
