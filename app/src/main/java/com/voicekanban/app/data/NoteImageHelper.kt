package com.voicekanban.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Compresses, encodes, and caches images attached to Notes.
 * Stores resized JPEG data URIs so note images survive updates, JSON backups,
 * and Android <-> Desktop LAN sync with zero broken file paths.
 */
object NoteImageHelper {
    private const val MAX_DIM = 1024
    private const val JPEG_QUALITY = 78

    private val bitmapCache = object : LruCache<Int, ImageBitmap>(48) {}

    /** Decodes [uri] respecting EXIF orientation, scales to max 1024px, and returns a JPEG data URI. */
    suspend fun encodeUriToDataUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val maxSide = maxOf(w, h)
                if (maxSide > MAX_DIM && maxSide > 0) {
                    val scale = MAX_DIM.toFloat() / maxSide.toFloat()
                    decoder.setTargetSize(
                        (w * scale).roundToInt().coerceAtLeast(1),
                        (h * scale).roundToInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$b64"
        }.getOrNull()
    }

    /** Decodes a cached [ImageBitmap] from a data URI or local file path. */
    fun decodeImageBitmap(encoded: String): ImageBitmap? {
        val key = encoded.hashCode()
        bitmapCache.get(key)?.let { return it }
        val bmp = runCatching {
            if (encoded.startsWith("data:image", ignoreCase = true)) {
                val comma = encoded.indexOf(',')
                if (comma < 0) return@runCatching null
                val bytes = Base64.decode(encoded.substring(comma + 1), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                val f = File(encoded)
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
            }
        }.getOrNull() ?: return null
        val img = bmp.asImageBitmap()
        bitmapCache.put(key, img)
        return img
    }

    /** Creates a FileProvider content URI in cache/camera/ for taking a photo directly into a note. */
    fun createCameraUri(context: Context): Uri {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "note_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
