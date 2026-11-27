package com.wajahatnaeem.oneplusota.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed interface LinkStatus {
    data object Idle : LinkStatus
    data object Checking : LinkStatus
    /** [finalUrl] is the real direct link: a plain GET works, no special headers. */
    data class Live(val finalUrl: String, val sizeBytes: Long, val resumable: Boolean, val redirected: Boolean) : LinkStatus
    data class Dead(val code: Int) : LinkStatus
    data class Unknown(val reason: String) : LinkStatus
}

/**
 * Turns the URL we got from a source into a link that works in ANY downloader.
 *  - OnePlus "/downloadCheck" URLs only answer with special headers (taken from OTA Pulse) and then redirect
 *    to the real CDN URL. We follow those redirects by hand, with those headers only on that hop.
 *  - The final URL is then requested like a generic browser would (no special headers). If that works,
 *    the copied link works in a browser, 1DM, ADM or IDM at this moment.
 * Uses Range: bytes=0-0, so nothing is actually downloaded.
 */
class LinkResolver(http: OkHttpClient) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun resolve(url: String): LinkStatus = withContext(Dispatchers.IO) {
        var current = url
        var hops = 0
        try {
            while (hops < 6) {
                val special = current.contains("/downloadCheck", ignoreCase = true)
                val rb = Request.Builder().url(current)
                    .header("Range", "bytes=0-0").header("Accept", "*/*").header("Accept-Encoding", "identity")
                if (special) rb.header("userId", "oplus-ota|16002018").header("User-Agent", "okhttp/3.12.12")
                else rb.header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")

                client.newCall(rb.build()).execute().use { r ->
                    when (r.code) {
                        301, 302, 303, 307, 308 -> {
                            val loc = r.header("Location") ?: return@withContext LinkStatus.Dead(r.code)
                            current = r.request.url.resolve(loc)?.toString() ?: loc
                            hops++
                        }
                        206 -> {
                            val total = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                            return@withContext LinkStatus.Live(current, total, true, current != url)
                        }
                        200 -> return@withContext LinkStatus.Live(
                            current, r.body?.contentLength() ?: -1L,
                            r.header("Accept-Ranges").equals("bytes", ignoreCase = true), current != url,
                        )
                        else -> return@withContext if (r.code in 400..499) LinkStatus.Dead(r.code) else LinkStatus.Unknown("HTTP ${r.code}")
                    }
                }
            }
            LinkStatus.Unknown("too many redirects")
        } catch (e: IOException) {
            LinkStatus.Unknown(e.message ?: "network error")
        }
    }
}
