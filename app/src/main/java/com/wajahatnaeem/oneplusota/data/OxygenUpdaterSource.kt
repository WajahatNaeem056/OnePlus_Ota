package com.wajahatnaeem.oneplusota.data

import com.wajahatnaeem.oneplusota.device.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fallback: community API (v2.10).
 *   GET devices/{filter}
 *   GET updateMethods/{deviceId}
 *   GET mostRecentUpdateData/{deviceId}/{updateMethodId}
 * UpdateData fields: id, version_number, ota_version_number, changelog, description, download_url,
 *   download_size, filename, md5sum, information, update_information_available, system_is_up_to_date.
 * NOTE: device/method JSON field names are matched defensively; verify on a real device.
 */
class OxygenUpdaterSource(private val http: OkHttpClient) : OtaSource {
    override val type = SourceType.OXYGEN_UPDATER
    private val base = "https://oxygenupdater.com/api/v2.10/"

    private fun get(path: String, vararg q: Pair<String, String>): String {
        val url = base.toHttpUrl().newBuilder().addPathSegments(path).apply { q.forEach { addQueryParameter(it.first, it.second) } }.build()
        val req = Request.Builder().url(url).header("User-Agent", "Oxygen_updater_7.1.0").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} on $path")
            return r.body?.string().orEmpty()
        }
    }

    /** Every string value anywhere inside the object (to match model / product names). */
    private fun strings(o: Any?, out: MutableList<String> = mutableListOf()): List<String> {
        when (o) {
            is JSONObject -> o.keys().forEach { strings(o.opt(it), out) }
            is JSONArray -> for (i in 0 until o.length()) strings(o.opt(i), out)
            is String -> out += o
        }
        return out
    }

    override suspend fun fetch(dev: DeviceInfo, fullOnly: Boolean): SourceOutcome = withContext(Dispatchers.IO) {
        val devices = JSONArray(get("devices/enabled"))
        val wanted = setOf(dev.model, dev.product, dev.marketName).filter { it.isNotBlank() }.map { it.lowercase() }
        var deviceId = -1L
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            val values = strings(d).flatMap { it.split(',') }.map { it.trim().lowercase() }
            if (wanted.any { it in values }) { deviceId = d.optLong("id", -1L); break }
        }
        if (deviceId < 0) return@withContext SourceOutcome.NoUpdate("Device not found on the backup server (${dev.model})")

        val methods = JSONArray(get("updateMethods/$deviceId", "language" to "en"))
        var methodId = -1L
        var fallbackId = -1L
        for (i in 0 until methods.length()) {
            val m = methods.optJSONObject(i) ?: continue
            if (fallbackId < 0) fallbackId = m.optLong("id", -1L)
            val name = strings(m).joinToString(" ").lowercase()
            val isFull = "full" in name && "incremental" !in name
            if (isFull) { methodId = m.optLong("id", -1L); break }
        }
        if (methodId < 0) {
            if (fullOnly) return@withContext SourceOutcome.NoUpdate("Backup server has no full update method for this device")
            methodId = fallbackId
        }

        val u = JSONObject(get("mostRecentUpdateData/$deviceId/$methodId"))
        val url = u.optString("download_url")
        if (url.isBlank()) return@withContext SourceOutcome.NoUpdate("Backup server returned no download URL")
        val size = u.optLong("download_size", 0L)
        SourceOutcome.Found(
            OtaPackage(
                version = u.optString("version_number", "unknown"),
                otaVersion = u.optString("ota_version_number").ifBlank { null },
                url = url, sizeBytes = size, md5 = u.optString("md5sum").ifBlank { null },
                fileName = u.optString("filename").ifBlank { url.substringBefore('?').substringAfterLast('/') },
                changelog = u.optString("changelog").ifBlank { null },
                isFull = true, source = type,
            ),
        )
    }
}
