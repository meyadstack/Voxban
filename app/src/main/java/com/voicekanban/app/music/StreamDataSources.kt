package com.voicekanban.app.music

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Media3 [BaseDataSource] that fetches audio streams in bounded HTTP byte ranges
 * to avoid stream throttling and support instant seeking.
 */
@OptIn(UnstableApi::class)
internal class ChunkedHttpDataSource(
    private val http: OkHttpClient,
    private val url: String,
    private val headers: Map<String, String>,
    private val totalLength: Long,
    private val chunkSize: Long = 512L * 1024L,
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var position: Long = 0L
    private var remaining: Long = 0L
    private var opened = false
    private var currentResponse: Response? = null
    private var currentStream: InputStream? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        position = dataSpec.position.coerceAtLeast(0L)
        if (totalLength > 0L && position >= totalLength) {
            remaining = 0L
            opened = true
            transferStarted(dataSpec)
            return 0L
        }
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() ->
                if (totalLength > 0L) minOf(dataSpec.length, (totalLength - position).coerceAtLeast(0L))
                else dataSpec.length
            totalLength > 0L -> (totalLength - position).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT

        val maxRead = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        if (maxRead <= 0) return C.RESULT_END_OF_INPUT

        while (true) {
            val stream = ensureChunkStream() ?: return C.RESULT_END_OF_INPUT
            val read = try {
                stream.read(buffer, offset, maxRead)
            } catch (e: IOException) {
                closeChunk()
                throw e
            }
            if (read == -1) {
                closeChunk()
                if (totalLength > 0L && position >= totalLength) return C.RESULT_END_OF_INPUT
                continue
            }
            position += read
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
            bytesTransferred(read)
            return read
        }
    }

    private fun ensureChunkStream(): InputStream? {
        currentStream?.let { return it }
        if (totalLength > 0L && position >= totalLength) return null

        val effectiveChunk = chunkSize.coerceIn(128L * 1024L, 2L * 1024L * 1024L)
        val rangeEnd = if (totalLength > 0L) minOf(position + effectiveChunk - 1L, totalLength - 1L) else position + effectiveChunk - 1L
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) ->
            if (!k.equals("Range", ignoreCase = true)) reqBuilder.header(k, v)
        }
        reqBuilder.header("Range", "bytes=$position-$rangeEnd")

        val resp = try {
            http.newCall(reqBuilder.build()).execute()
        } catch (e: Exception) {
            throw IOException("Network error reading audio stream: ${e.message}", e)
        }
        if (!resp.isSuccessful && resp.code != 206) {
            val code = resp.code
            resp.close()
            if (code == 416) return null
            throw IOException("Audio stream returned HTTP $code")
        }
        currentResponse = resp
        currentStream = resp.body.byteStream()
        return currentStream
    }

    private fun closeChunk() {
        runCatching { currentStream?.close() }
        runCatching { currentResponse?.close() }
        currentStream = null
        currentResponse = null
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closeChunk()
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
    }
}

/**
 * Media3 [BaseDataSource] backed by [SabrAudioStream] for segmented audio streams.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalSabrApi::class)
internal class SabrAudioDataSource(
    private val httpClient: HttpClient,
    private val bootstrap: SabrBootstrap,
) : BaseDataSource(/* isNetwork = */ true) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = LinkedBlockingQueue<ByteArray>(32)
    private var collectorJob: Job? = null
    private var uri: Uri? = null
    private var opened = false
    private var currentChunk: ByteArray? = null
    private var chunkOffset = 0
    @Volatile private var streamError: Throwable? = null
    @Volatile private var closed = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        closed = false
        streamError = null
        currentChunk = null
        chunkOffset = 0
        queue.clear()
        transferInitializing(dataSpec)

        collectorJob?.cancel()
        collectorJob = scope.launch {
            try {
                SabrAudioStream(httpClient = httpClient, bootstrap = bootstrap).bytes().collect { bytes ->
                    if (bytes.isNotEmpty() && !closed) {
                        while (!closed && !queue.offer(bytes, 200, TimeUnit.MILLISECONDS)) {
                            // Backpressure while player buffer is full
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                streamError = t
            } finally {
                queue.offer(EOF)
            }
        }

        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val chunk = currentChunk
            if (chunk != null && chunkOffset < chunk.size) {
                val n = minOf(length, chunk.size - chunkOffset)
                System.arraycopy(chunk, chunkOffset, buffer, offset, n)
                chunkOffset += n
                if (chunkOffset >= chunk.size) {
                    currentChunk = null
                    chunkOffset = 0
                }
                bytesTransferred(n)
                return n
            }
            val next = try {
                queue.poll(30, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return C.RESULT_END_OF_INPUT
            }
            if (next == null) {
                streamError?.let { throw IOException("SABR audio error: ${it.message}", it) }
                throw IOException("Timed out waiting for SABR audio data")
            }
            if (next === EOF) {
                streamError?.let { throw IOException("SABR audio error: ${it.message}", it) }
                return C.RESULT_END_OF_INPUT
            }
            currentChunk = next
            chunkOffset = 0
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closed = true
        collectorJob?.cancel()
        collectorJob = null
        queue.clear()
        queue.offer(EOF)
        currentChunk = null
        chunkOffset = 0
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
    }

    companion object {
        private val EOF = ByteArray(0)
    }
}
