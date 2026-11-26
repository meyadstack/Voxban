package com.voicekanban.app.security

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.voicekanban.app.data.PasswordEntry
import com.voicekanban.app.data.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class ShareMode { LAN_INSTANT_BURN, OFFLINE_TIMED }

@Serializable
data class SharedLoginPayload(
    val title: String,
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
)

data class ActivePasswordShare(
    val shareId: String,
    val mode: ShareMode,
    val qrText: String,
    val qrBitmap: Bitmap,
    val expiresAt: Long,
    val lanAddress: String? = null,
    private val server: OneTimeShareServer? = null,
) {
    val isClaimed: Boolean get() = server?.claimed?.get() == true

    fun burn() {
        server?.burnAndClose()
    }
}

sealed interface ReceiveShareResult {
    data class Success(val shareId: String, val entry: PasswordEntry, val burnedOnSender: Boolean) : ReceiveShareResult
    data class Error(val message: String) : ReceiveShareResult
}

/**
 * Ephemeral local HTTP server that serves an AES-256-GCM encrypted password blob
 * AT MOST ONCE and immediately zeroes it from RAM on the very first scan.
 */
class OneTimeShareServer(
    private val shareId: String,
    private var encryptedBlob: String?,
    private val expiresAt: Long,
    private val onClaimed: () -> Unit,
) {
    val claimed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val socket = ServerSocket(0)
    val port: Int get() = socket.localPort

    private val worker = Thread {
        try {
            socket.soTimeout = 1000
            while (!closed.get() && System.currentTimeMillis() < expiresAt) {
                val client = try {
                    socket.accept()
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                client.use { s ->
                    s.soTimeout = 3000
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val reqLine = reader.readLine().orEmpty()
                    val out = s.getOutputStream()
                    val path = reqLine.split(' ').getOrNull(1).orEmpty()
                    val expectedPath = "/pw-share/$shareId"
                    val expectedDesktopPath = "/api/vault/share/claim/$shareId"

                    val (statusLine, body) = when {
                        path != expectedPath && path != expectedDesktopPath ->
                            "HTTP/1.1 404 Not Found" to """{"ok":false,"error":"Not found"}"""
                        System.currentTimeMillis() > expiresAt -> {
                            encryptedBlob = null
                            "HTTP/1.1 410 Gone" to """{"ok":false,"error":"This one-time QR code has expired."}"""
                        }
                        claimed.compareAndSet(false, true) -> {
                            val payload = encryptedBlob.orEmpty()
                            encryptedBlob = null
                            onClaimed()
                            "HTTP/1.1 200 OK" to """{"ok":true,"d":"$payload"}"""
                        }
                        else ->
                            "HTTP/1.1 410 Gone" to """{"ok":false,"error":"This one-time QR code was already used and burned."}"""
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val headers = "$statusLine\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(headers.toByteArray(Charsets.UTF_8))
                    out.write(bytes)
                    out.flush()
                    if (claimed.get()) {
                        closed.set(true)
                    }
                }
            }
        } catch (_: Exception) {
            // Socket closed cleanly when burned or expired
        } finally {
            encryptedBlob = null
            runCatching { socket.close() }
        }
    }.apply {
        isDaemon = true
        name = "voxban-pwshare-$shareId"
    }

    fun start() = worker.start()

    fun burnAndClose() {
        claimed.set(true)
        closed.set(true)
        encryptedBlob = null
        runCatching { socket.close() }
    }
}

/**
 * End-to-end AES-256-GCM one-time QR password sharing compatible across
 * Voxban Android and Voxban Desktop.
 */
object PasswordShareManager {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val rng = SecureRandom()
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    fun detectLanIpv4(): String? = runCatching {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
            for (addr in iface.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                    return@runCatching addr.hostAddress
                }
            }
        }
        null
    }.getOrNull()

    fun createShare(
        entry: PasswordEntry,
        preferredMode: ShareMode,
        ttlSeconds: Int = 120,
        onBurned: () -> Unit = {},
    ): ActivePasswordShare {
        val shareId = randomToken(16)
        val keyBytes = ByteArray(32).also { rng.nextBytes(it) }
        val keyB64 = b64UrlEncode(keyBytes)
        val expiresAt = System.currentTimeMillis() + ttlSeconds * 1000L

        val plainJson = json.encodeToString(
            SharedLoginPayload.serializer(),
            SharedLoginPayload(
                title = entry.title,
                username = entry.username,
                password = entry.password,
                url = entry.url,
                notes = entry.notes,
            ),
        )
        val encryptedBlob = encryptAesGcm(plainJson.toByteArray(Charsets.UTF_8), keyBytes)

        val lanIp = if (preferredMode == ShareMode.LAN_INSTANT_BURN) detectLanIpv4() else null
        return if (lanIp != null) {
            val server = OneTimeShareServer(shareId, encryptedBlob, expiresAt, onBurned).also { it.start() }
            val url = "voxban://pwshare?v=2&mode=lan" +
                "&host=${enc(lanIp)}&port=${server.port}" +
                "&id=${enc(shareId)}&k=${enc(keyB64)}&exp=$expiresAt"
            ActivePasswordShare(
                shareId = shareId,
                mode = ShareMode.LAN_INSTANT_BURN,
                qrText = url,
                qrBitmap = renderQrBitmap(url),
                expiresAt = expiresAt,
                lanAddress = "$lanIp:${server.port}",
                server = server,
            )
        } else {
            val url = "voxban://pwshare?v=2&mode=direct" +
                "&id=${enc(shareId)}&exp=$expiresAt&k=${enc(keyB64)}&d=${enc(encryptedBlob)}"
            ActivePasswordShare(
                shareId = shareId,
                mode = ShareMode.OFFLINE_TIMED,
                qrText = url,
                qrBitmap = renderQrBitmap(url),
                expiresAt = expiresAt,
                lanAddress = null,
                server = null,
            )
        }
    }

    fun isPasswordShareQr(raw: String): Boolean {
        val t = raw.trim()
        return t.startsWith("voxban://pwshare", ignoreCase = true) ||
            t.startsWith("voicekanban://pwshare", ignoreCase = true)
    }

    /**
     * Parses, verifies expiry and single-use status, fetches & burns if LAN mode,
     * and decrypts a friend's one-time password QR code.
     */
    suspend fun receiveShare(
        rawQr: String,
        isAlreadyConsumed: (String) -> Boolean,
        markConsumed: (String) -> Unit,
    ): ReceiveShareResult = withContext(Dispatchers.IO) {
        val text = rawQr.trim()
        if (!isPasswordShareQr(text)) {
            return@withContext ReceiveShareResult.Error("This QR code is not a Voxban one-time password share.")
        }
        val uri = runCatching { URI(text) }.getOrNull()
            ?: return@withContext ReceiveShareResult.Error("Invalid password share QR format.")
        val params = parseQuery(uri.rawQuery.orEmpty())
        val shareId = params["id"].orEmpty().trim()
        val keyB64 = params["k"].orEmpty().trim()
        val exp = params["exp"]?.toLongOrNull() ?: 0L
        val mode = params["mode"].orEmpty().lowercase()

        if (shareId.isEmpty() || keyB64.isEmpty()) {
            return@withContext ReceiveShareResult.Error("Incomplete one-time password QR code.")
        }
        if (exp > 0L && System.currentTimeMillis() > exp) {
            return@withContext ReceiveShareResult.Error("This one-time QR code has expired. Ask your friend to generate a new one.")
        }
        if (isAlreadyConsumed(shareId)) {
            return@withContext ReceiveShareResult.Error("This one-time QR code was already scanned and burned.")
        }

        val keyBytes = runCatching { b64UrlDecode(keyB64) }.getOrNull()
            ?: return@withContext ReceiveShareResult.Error("Corrupted encryption key in QR code.")

        var burnedOnSender = false
        val encryptedBlob = when (mode) {
            "lan" -> {
                val host = params["host"].orEmpty().trim()
                val port = params["port"]?.toIntOrNull() ?: 0
                val path = params["path"]?.takeIf { it.startsWith("/") } ?: "/pw-share/$shareId"
                if (host.isEmpty() || port !in 1..65535) {
                    return@withContext ReceiveShareResult.Error("Invalid sender LAN address in QR code.")
                }
                val req = Request.Builder().url("http://$host:$port$path").get().build()
                val respResult = runCatching {
                    http.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body.string()
                        if (resp.code == 410) {
                            return@use Result.failure<String>(IllegalStateException("This one-time QR code was already claimed and burned."))
                        }
                        if (!resp.isSuccessful) {
                            return@use Result.failure<String>(IllegalStateException("Sender returned HTTP ${resp.code}."))
                        }
                        @Serializable data class LanBlob(val ok: Boolean = false, val d: String = "", val error: String = "")
                        val parsed = json.decodeFromString(LanBlob.serializer(), bodyStr)
                        if (!parsed.ok || parsed.d.isBlank()) {
                            Result.failure(IllegalStateException(parsed.error.ifBlank { "Sender did not return an encrypted password." }))
                        } else {
                            Result.success(parsed.d)
                        }
                    }
                }.getOrElse {
                    Result.failure(IllegalStateException("Could not reach your friend's device on Wi-Fi. Make sure both devices are on the same Wi-Fi, or ask them to switch to Offline Timed QR."))
                }
                val blob = respResult.getOrElse { err ->
                    return@withContext ReceiveShareResult.Error(err.message ?: "Failed to claim password.")
                }
                burnedOnSender = true
                blob
            }
            "direct" -> {
                params["d"].orEmpty().trim().ifBlank {
                    return@withContext ReceiveShareResult.Error("Missing encrypted password data in QR code.")
                }
            }
            else -> return@withContext ReceiveShareResult.Error("Unsupported password share QR mode.")
        }

        val plainBytes = runCatching { decryptAesGcm(encryptedBlob, keyBytes) }.getOrNull()
            ?: return@withContext ReceiveShareResult.Error("Could not decrypt shared password. The QR code may be invalid.")
        val payload = runCatching { json.decodeFromString(SharedLoginPayload.serializer(), plainBytes.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return@withContext ReceiveShareResult.Error("Shared password data was unreadable.")

        markConsumed(shareId)
        val entry = PasswordEntry(
            id = newId("p"),
            title = payload.title.trim().ifBlank { "Shared login" },
            username = payload.username.trim(),
            password = payload.password,
            url = payload.url.trim(),
            notes = payload.notes.trim(),
        )
        ReceiveShareResult.Success(shareId = shareId, entry = entry, burnedOnSender = burnedOnSender)
    }

    internal fun encryptAesGcm(plain: ByteArray, keyBytes: ByteArray): String {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain)
        val combined = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ct, 0, combined, iv.size, ct.size)
        return b64UrlEncode(combined)
    }

    internal fun decryptAesGcm(encoded: String, keyBytes: ByteArray): ByteArray {
        val combined = b64UrlDecode(encoded)
        require(combined.size > 12) { "Ciphertext too short" }
        val iv = combined.copyOfRange(0, 12)
        val ct = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    fun renderQrBitmap(content: String, sizePx: Int = 640): Bitmap {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        val dark = Color.rgb(27, 32, 28)
        val light = Color.WHITE
        for (y in 0 until sizePx) {
            val rowOffset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) dark else light
            }
        }
        return Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }

    private fun randomToken(bytes: Int): String =
        b64UrlEncode(ByteArray(bytes).also { rng.nextBytes(it) })

    private fun b64UrlEncode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun b64UrlDecode(s: String): ByteArray =
        Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun parseQuery(rawQuery: String): Map<String, String> {
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
}
