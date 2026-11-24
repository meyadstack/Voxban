package com.voicekanban.app.sync

import android.os.Build
import com.voicekanban.app.BuildConfig
import com.voicekanban.app.data.AppState
import com.voicekanban.app.data.PairedDesktop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class PairingPayload(
    val host: String,
    val port: Int,
    val token: String,
    val pcName: String,
    val code: String = "",
)

data class PairOutcome(
    val desktop: PairedDesktop,
    val mergedState: AppState?,
    val message: String,
)

@Serializable
private data class PairConfirmRequest(
    val token: String,
    val code: String = "",
    val deviceName: String,
    val appVersion: String,
    val state: AppState,
)

@Serializable
private data class PairConfirmResponse(
    val ok: Boolean = false,
    val pcName: String = "Voxban PC",
    val token: String = "",
    val message: String = "",
    val state: AppState? = null,
)

/**
 * Handles QR pairing and bidirectional LAN sync between the Voxban Android APK
 * and the Voxban Windows PC desktop app (Rust backend).
 */
object DesktopSyncManager {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /**
     * Parses a QR code scanned from the Voxban Windows PC app.
     * Supports:
     * - https://github.com/meyadstack/Voxban/releases/latest?voxban_pair=1&host=...&port=...&token=...&name=...
     * - voicekanban://pair?host=...&port=...&token=...&name=...
     * - JSON object {"host":"...","port":43821,"token":"...","name":"..."}
     */
    fun parseQrPayload(raw: String): PairingPayload? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // 1. Query-string URL (GitHub releases redirect URL or voicekanban://pair)
        if (text.startsWith("http://", true) || text.startsWith("https://", true) || text.startsWith("voicekanban://", true)) {
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            val params = parseQueryParams(uri.rawQuery.orEmpty())
            val host = params["host"]?.trim().orEmpty()
            val token = params["token"]?.trim().orEmpty()
            val code = params["code"]?.trim().orEmpty()
            val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 43821
            val name = params["name"]?.trim()?.ifBlank { "Voxban PC" } ?: "Voxban PC"
            if (host.isNotBlank() && (token.isNotBlank() || code.isNotBlank())) {
                return PairingPayload(host = host, port = port, token = token, pcName = name, code = code)
            }
            return null
        }

        // 2. JSON payload fallback
        if (text.startsWith("{")) {
            return runCatching {
                @Serializable data class JsonQr(
                    val host: String = "",
                    val port: Int = 43821,
                    val token: String = "",
                    val code: String = "",
                    val name: String = "Voxban PC",
                )
                val parsed = json.decodeFromString(JsonQr.serializer(), text)
                if (parsed.host.isNotBlank() && (parsed.token.isNotBlank() || parsed.code.isNotBlank())) {
                    PairingPayload(parsed.host, parsed.port, parsed.token, parsed.name.ifBlank { "Voxban PC" }, parsed.code)
                } else null
            }.getOrNull()
        }

        return null
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        rawQuery.split('&').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val k = runCatching { URLDecoder.decode(pair.substring(0, idx), "UTF-8") }.getOrDefault(pair.substring(0, idx))
                val v = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8") }.getOrDefault(pair.substring(idx + 1))
                out[k] = v
            }
        }
        return out
    }

    /** Confirms a scanned QR code with the Windows PC Rust server and exchanges merged state. */
    suspend fun pairWithDesktop(payload: PairingPayload, localState: AppState): Result<PairOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
            val reqBody = json.encodeToString(
                PairConfirmRequest.serializer(),
                PairConfirmRequest(
                    token = payload.token,
                    code = payload.code,
                    deviceName = deviceName.ifBlank { "Android Phone" },
                    appVersion = BuildConfig.VERSION_NAME,
                    state = stripLocalOnlyPaths(localState),
                ),
            )
            val endpoint = if (payload.token.isNotBlank()) "api/pair/confirm" else "api/pair/code"
            val url = "http://${payload.host}:${payload.port}/$endpoint"
            val req = Request.Builder()
                .url(url)
                .post(reqBody.toRequestBody(JSON_MEDIA))
                .build()

            http.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) {
                    throw IOException("PC returned HTTP ${resp.code}: ${text.take(120)}")
                }
                val parsed = json.decodeFromString(PairConfirmResponse.serializer(), text)
                if (!parsed.ok) throw IOException(parsed.message.ifBlank { "Pairing was rejected by PC." })
                val finalToken = parsed.token.ifBlank { payload.token }
                val desktop = PairedDesktop(
                    host = payload.host,
                    port = payload.port,
                    token = finalToken,
                    pcName = parsed.pcName.ifBlank { payload.pcName },
                    lastSyncedAt = System.currentTimeMillis(),
                )
                PairOutcome(
                    desktop = desktop,
                    mergedState = parsed.state,
                    message = parsed.message.ifBlank { "Paired with ${desktop.pcName}" },
                )
            }
        }
    }

    /** Syncs current local Android state with an already-paired Windows PC over LAN. */
    suspend fun syncWithDesktop(desktop: PairedDesktop, localState: AppState): Result<AppState> = withContext(Dispatchers.IO) {
        runCatching {
            val reqBody = json.encodeToString(AppState.serializer(), stripLocalOnlyPaths(localState))
            val url = "http://${desktop.host}:${desktop.port}/api/sync"
            val req = Request.Builder()
                .url(url)
                .header("X-Voxban-Token", desktop.token)
                .post(reqBody.toRequestBody(JSON_MEDIA))
                .build()

            http.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) throw IOException("Sync failed (HTTP ${resp.code})")
                json.decodeFromString(AppState.serializer(), text)
            }
        }
    }

    /** Keeps Android-only local filesystem download paths local while syncing everything else cleanly. */
    private fun stripLocalOnlyPaths(st: AppState): AppState =
        st.copy(downloadedWorkoutTracks = emptyList())
}
