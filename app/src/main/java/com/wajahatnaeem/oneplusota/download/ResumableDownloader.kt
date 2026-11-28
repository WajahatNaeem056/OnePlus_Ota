package com.wajahatnaeem.oneplusota.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.wajahatnaeem.oneplusota.data.OtaPackage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLongArray
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
    private class RangeUnsupported : IOException("server ignored Range")

    // HTTP/1.1 so every segment gets its own TCP connection (HTTP/2 would multiplex them all on one).
    private val multiHttp: OkHttpClient by lazy { http.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build() }

    /** Swaps in a freshly fetched package. A different build (size/md5) discards the partial file only when [allowDifferent]. */
    fun adopt(job: DlJob, fresh: OtaPackage, part: File, allowDifferent: Boolean): Boolean {
        val sameFile = (job.pkg.sizeBytes <= 0 || fresh.sizeBytes <= 0 || job.pkg.sizeBytes == fresh.sizeBytes) &&
            (job.pkg.md5 == null || fresh.md5 == null || job.pkg.md5.equals(fresh.md5, true))
        if (!sameFile) {
            if (!allowDifferent) return false
            part.delete(); segFile(part).delete(); job.etag = null; job.lastModified = null  // genuinely a different build
        }
        job.pkg = fresh.copy(fileName = job.pkg.fileName) // the .part file on disk keeps its name
        if (fresh.sizeBytes > 0) job.total = fresh.sizeBytes
        return true
    }

    suspend fun run(
        job: DlJob,
        part: File,
        refresh: suspend () -> OtaPackage?,
        onProgress: (done: Long, total: Long, bps: Long, note: String?) -> Unit,
    ): RunResult {
        var failures = 0
        var refreshes = 0
        var bytesAtRefresh = -1L
        part.parentFile?.mkdirs()
        while (true) {
            coroutineContext.ensureActive()
            val before = downloaded(part)
            try {
                val multi = !job.noMulti && job.total > 0 && segmentCount(job.total) >= 2 &&
                    (segFile(part).exists() || !part.exists() || part.length() == 0L)
                if (if (multi) attemptMulti(job, part, onProgress) else attempt(job, part, onProgress)) return RunResult.Completed
            } catch (e: ExpiredLink) {
                if (downloaded(part) > bytesAtRefresh) refreshes = 0 // it moved since the last refresh, so a long download may refresh again
                if (refreshes >= 2) return RunResult.GaveUp("Link expired or was rejected (HTTP ${e.code}). Check for the update again, then tap Retry.")
                onProgress(before, job.total, 0, "Link expired, fetching a new one…")
                val fresh = refresh() ?: return RunResult.GaveUp("Couldn't get a new link (HTTP ${e.code}). Try again later.")
                adopt(job, fresh, part, allowDifferent = true)
                refreshes++
                bytesAtRefresh = downloaded(part)
            } catch (e: RangeUnsupported) {
                job.noMulti = true
                part.delete(); segFile(part).delete()
                onProgress(0, job.total, 0, "Server doesn't allow parallel download, using one connection")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                coroutineContext.ensureActive() // a cancel() on the call surfaces as IOException
                val progressed = downloaded(part) > before
                failures = if (progressed) 1 else failures + 1
                if (failures > MAX_AUTO_RETRIES) {
                    return RunResult.GaveUp("Network error: ${e.message}. Your progress is saved. Tap Retry.")
                }
                val have = downloaded(part)
                onProgress(have, job.total, 0, "Network error — retry $failures/$MAX_AUTO_RETRIES")
                net.awaitOnline(60_000)
                delay(minOf(30_000L, 1_000L shl (failures - 1)))
            }
        }
    }

    /** Several connections at once, each filling its own slice of the one pre-sized file. Progress per slice lives in a small .seg file. */
    private suspend fun attemptMulti(job: DlJob, part: File, onProgress: (Long, Long, Long, String?) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val total = job.total
            val sf = segFile(part)
            val n = segmentCount(total)
            val size = segSize(total, n)
            val initPos = LongArray(n) { it * size }
            var loaded = false
            if (sf.exists()) try {
                val o = JSONObject(sf.readText())
                if (o.getLong("total") == total && o.getInt("n") == n) {
                    val a = o.getJSONArray("pos")
                    for (i in 0 until n) initPos[i] = a.getLong(i)
                    loaded = true
                }
            } catch (e: Exception) {}
            if (!loaded) { part.delete(); sf.delete() }
            val pos = AtomicLongArray(initPos)
            RandomAccessFile(part, "rw").use { if (it.length() != total) it.setLength(total) }

            fun saveSeg() {
                val arr = JSONArray(); for (i in 0 until n) arr.put(pos.get(i))
                val tmp = File(sf.path + ".tmp")
                tmp.writeText(JSONObject().put("total", total).put("n", n).put("pos", arr).toString())
                tmp.renameTo(sf)
            }
            fun doneNow(): Long { var d = 0L; for (i in 0 until n) { val st = i * size; d += (pos.get(i) - st).coerceIn(0L, minOf(size, total - st)) }; return d }
            saveSeg()

            val useCheckHeaders = job.pkg.url.contains("/downloadCheck", ignoreCase = true)
            val client = multiHttp

            suspend fun segment(i: Int) {
                val start = i * size
                val end = minOf(start + size, total) - 1
                while (pos.get(i) <= end) {
                    coroutineContext.ensureActive()
                    val from = pos.get(i)
                    val rb = Request.Builder().url(job.pkg.url)
                        .header("Accept-Encoding", "identity")
                        .header("User-Agent", "OnePlusOTA/0.1")
                        .header("Range", "bytes=$from-$end")
                    if (useCheckHeaders) rb.header("userId", "oplus-ota|16002018").header("User-Agent", "okhttp/3.12.12")
                    val call = client.newCall(rb.build())
                    val handle = coroutineContext[Job]!!.invokeOnCompletion { if (it != null) call.cancel() }
                    try {
                        call.execute().use { resp ->
                            when (resp.code) {
                                206 -> {
                                    val cr = resp.header("Content-Range").orEmpty()
                                    val st = cr.substringAfter("bytes ", "").substringBefore('-').toLongOrNull()
                                    if (st != null && st != from) throw IOException("server returned a wrong offset")
                                    val t = cr.substringAfterLast('/', "").toLongOrNull()
                                    if (t != null && t != total) { job.total = t; throw IOException("file size changed, restarting") }
                                }
                                200 -> throw RangeUnsupported()
                                401, 403, 404, 410 -> throw ExpiredLink(resp.code)
                                else -> throw IOException("HTTP ${resp.code}")
                            }
                            val src = resp.body?.byteStream() ?: throw IOException("empty body")
                            RandomAccessFile(part, "rw").use { raf ->
                                raf.seek(from)
                                val buf = ByteArray(64 * 1024)
                                var p = from
                                var sinceSync = 0L
                                while (p <= end) {
                                    val r = src.read(buf, 0, minOf(buf.size.toLong(), end - p + 1).toInt())
                                    if (r < 0) break
                                    raf.write(buf, 0, r)
                                    p += r; sinceSync += r
                                    pos.set(i, p)
                                    if (sinceSync >= 16L * 1024 * 1024) { raf.fd.sync(); sinceSync = 0 }
                                }
                                raf.fd.sync()
                            }
                        }
                    } finally { handle.dispose() }
                    if (pos.get(i) <= end) throw IOException("connection $i closed early")
                }
            }

            try {
                coroutineScope {
                    val workers = (0 until n).map { i -> async { segment(i) } }
                    val reporter = launch {
                        var lastTick = System.currentTimeMillis(); var lastBytes = doneNow()
                        while (true) {
                            delay(500)
                            val now = System.currentTimeMillis(); val d = doneNow()
                            onProgress(d, total, (d - lastBytes) * 1000 / (now - lastTick).coerceAtLeast(1), null)
                            lastTick = now; lastBytes = d
                            saveSeg()
                        }
                    }
                    workers.awaitAll()
                    reporter.cancel()
                }
            } finally {
                withContext(NonCancellable) { runCatching { saveSeg() } } // keep exact progress on pause / error
            }
            sf.delete(); File(sf.path + ".tmp").delete()
            true
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
            if (job.pkg.url.contains("/downloadCheck", ignoreCase = true)) {
                // OnePlus link-check endpoint: only answers with these headers, then redirects to the CDN.
                rb.header("userId", "oplus-ota|16002018").header("User-Agent", "okhttp/3.12.12")
            }
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
        private const val MAX_SEGMENTS = 6
        private const val MIN_SEGMENT_BYTES = 16L * 1024 * 1024

        fun segFile(part: File) = File(part.path + ".seg")
        fun segmentCount(total: Long): Int = (total / MIN_SEGMENT_BYTES).toInt().coerceIn(1, MAX_SEGMENTS)
        private fun segSize(total: Long, n: Int) = (total + n - 1) / n

        /** Bytes really downloaded: the sum over the slices when downloading in parallel, else the .part length. */
        fun downloaded(part: File): Long {
            val sf = segFile(part)
            if (sf.exists()) try {
                val o = JSONObject(sf.readText())
                val total = o.getLong("total"); val n = o.getInt("n"); val a = o.getJSONArray("pos")
                val size = segSize(total, n)
                var sum = 0L
                for (i in 0 until n) { val st = i * size; sum += (a.getLong(i) - st).coerceIn(0L, minOf(size, total - st)) }
                return sum
            } catch (e: Exception) {}
            return if (part.exists()) part.length() else 0L
        }

        fun deleteAll(part: File) { part.delete(); segFile(part).delete(); File(part.path + ".seg.tmp").delete() }

        fun md5Of(f: File): String {
            val md = MessageDigest.getInstance("MD5")
            f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
