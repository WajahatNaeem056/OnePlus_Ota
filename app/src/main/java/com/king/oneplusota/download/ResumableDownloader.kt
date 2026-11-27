package com.king.oneplusota.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.king.oneplusota.data.OtaPackage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

class NetworkWaiter(ctx: Context) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)

    fun isOnline(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Suspends until a network is available (or timeout). Never throws. */
    suspend fun awaitOnline(timeoutMs: Long) {
        if (isOnline()) return
        val d = CompletableDeferred<Unit>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { d.complete(Unit) }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        try { withTimeoutOrNull(timeoutMs) { d.await() } } finally { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
}

sealed interface RunResult {
    data object Completed : RunResult
    /** Gave up for now. The partial file is KEPT; the user can retry and it continues from the same byte. */
    data class GaveUp(val message: String) : RunResult
}

/**
 * Resume-first downloader.
 *  - never deletes the partial file on an error
 *  - Range: bytes=N-  (+ If-Range with ETag/Last-Modified, so a changed file is detected instead of corrupting)
 *  - auto retry with backoff; waits for network; counter resets whenever bytes actually progressed
 *  - expired/forbidden link (401/403/404/410) -> asks [refresh] for a fresh URL and continues
 */
class ResumableDownloader(private val http: OkHttpClient, private val net: NetworkWaiter) {

    private class ExpiredLink(val code: Int) : IOException("link rejected: HTTP $code")

    suspend fun run(
        job: DlJob,
        part: File,
        refresh: suspend () -> OtaPackage?,
        onProgress: (done: Long, total: Long, bps: Long, note: String?) -> Unit,
    ): RunResult {
        var failures = 0
        var refreshes = 0
        part.parentFile?.mkdirs()
        while (true) {
            coroutineContext.ensureActive()
            val before = if (part.exists()) part.length() else 0L
            try {
                if (attempt(job, part, onProgress)) return RunResult.Completed
            } catch (e: ExpiredLink) {
                if (refreshes >= 2) return RunResult.GaveUp("Link expired or was rejected (HTTP ${e.code}). Check for the update again, then tap Retry.")
                onProgress(before, job.total, 0, "Link expired, fetching a new one…")
                val fresh = refresh() ?: return RunResult.GaveUp("Couldn't get a new link (HTTP ${e.code}). Try again later.")
                val sameFile = (job.pkg.sizeBytes <= 0 || fresh.sizeBytes <= 0 || job.pkg.sizeBytes == fresh.sizeBytes) &&
                    (job.pkg.md5 == null || fresh.md5 == null || job.pkg.md5.equals(fresh.md5, true))
                if (!sameFile) { part.delete(); job.etag = null; job.lastModified = null }  // genuinely a different build
                job.pkg = fresh
                if (fresh.sizeBytes > 0) job.total = fresh.sizeBytes
                refreshes++
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                coroutineContext.ensureActive() // a cancel() on the call surfaces as IOException
                val progressed = (if (part.exists()) part.length() else 0L) > before
                failures = if (progressed) 1 else failures + 1
                if (failures > MAX_AUTO_RETRIES) {
                    return RunResult.GaveUp("Network error: ${e.message}. Your progress is saved. Tap Retry.")
                }
                val have = if (part.exists()) part.length() else 0L
                onProgress(have, job.total, 0, "Network error — retry $failures/$MAX_AUTO_RETRIES")
                net.awaitOnline(60_000)
                delay(minOf(30_000L, 1_000L shl (failures - 1)))
            }
        }
    }

    /** One connection. Returns true when the file is complete. Throws IOException on any recoverable problem. */
    private suspend fun attempt(job: DlJob, part: File, onProgress: (Long, Long, Long, String?) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            var have = if (part.exists()) part.length() else 0L
            if (job.total > 0 && have == job.total) return@withContext true
            if (job.total > 0 && have > job.total) { part.delete(); have = 0L }

            val rb = Request.Builder().url(job.pkg.url)
                .header("Accept-Encoding", "identity") // keep Range / Content-Length byte-exact
                .header("User-Agent", "OnePlusOTA/0.1")
            if (have > 0) {
                rb.header("Range", "bytes=$have-")
                (job.etag ?: job.lastModified)?.let { rb.header("If-Range", it) }
            }
            val call = http.newCall(rb.build())
            val handle = coroutineContext[Job]!!.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                call.execute().use { resp ->
                    var note: String? = null
                    when (resp.code) {
                        206 -> {
                            val cr = resp.header("Content-Range").orEmpty() // bytes START-END/TOTAL
                            val start = cr.substringAfter("bytes ", "").substringBefore('-').toLongOrNull()
                            if (start != null && start != have) { part.delete(); throw IOException("server returned a wrong offset, restarting") }
                            cr.substringAfterLast('/', "").toLongOrNull()?.let { job.total = it }
                        }
                        200 -> {
                            if (have > 0) { part.delete(); have = 0L; note = "Server didn't allow resuming, restarting from the beginning (file changed or Range unsupported)" }
                            val len = resp.body?.contentLength() ?: -1L
                            if (len > 0) job.total = len
                            job.etag = resp.header("ETag"); job.lastModified = resp.header("Last-Modified")
                        }
                        416 -> {
                            if (job.total > 0 && have >= job.total) return@use
                            part.delete(); throw IOException("range rejected (416), restart")
                        }
                        401, 403, 404, 410 -> throw ExpiredLink(resp.code)
                        else -> if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    }
                    val src = resp.body?.byteStream() ?: throw IOException("empty body")
                    RandomAccessFile(part, "rw").use { raf ->
                        raf.seek(have)
                        val buf = ByteArray(64 * 1024)
                        var written = have
                        var sinceSync = 0L
                        var lastTick = System.currentTimeMillis(); var lastBytes = written
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            written += n; sinceSync += n
                            if (sinceSync >= 16L * 1024 * 1024) { raf.fd.sync(); sinceSync = 0 }
                            val now = System.currentTimeMillis()
                            if (now - lastTick >= 500) {
                                coroutineContext.ensureActive()
                                onProgress(written, job.total, (written - lastBytes) * 1000 / (now - lastTick), note)
                                lastTick = now; lastBytes = written
                            }
                        }
                        raf.fd.sync()
                    }
                }
            } finally { handle.dispose() }

            val size = part.length()
            if (job.total > 0 && size < job.total) throw IOException("connection closed early ($size/${job.total})")
            true
        }

    companion object {
        const val MAX_AUTO_RETRIES = 6

        fun md5Of(f: File): String {
            val md = MessageDigest.getInstance("MD5")
            f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
