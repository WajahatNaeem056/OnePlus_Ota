package com.wajahatnaeem.oneplusota.data

import android.util.Log
import com.wajahatnaeem.oneplusota.device.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Talks to OnePlus/Oplus OTA servers directly.
 * The raw decrypted JSON is kept in [lastRawJson] so field names can be calibrated quickly.
 */
class OplusClient(private val http: OkHttpClient) {
    @Volatile var lastRawJson: String? = null

    private fun sha256Hex(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun nvCarrier(region: Int) = when (region) { 1 -> "10010111"; 3 -> "01000100"; else -> "00011011" }

    fun query(dev: DeviceInfo, server: OplusServers.Server, otaVersion: String): JSONObject {
        val now = System.currentTimeMillis()
        val prefix = otaVersion.split("_").take(2).joinToString("_")
        val deviceId = sha256Hex("0") // no IMEI access on purpose; server accepts the placeholder
        val regionCode = when (dev.regionId) { 1 -> "CN"; 2 -> "IN"; 3 -> "EU"; else -> "GL" }
        val lang = if (regionCode == "CN") "zh-CN" else "en-EN"
        val rui = Regex("(\\d+)").find(dev.osVersion)?.value?.toIntOrNull()?.takeIf { it > 0 } ?: dev.androidRelease.toIntOrNull()
        val (androidVer, colorVer) = when {
            rui == null || rui < 11 -> "unknown" to "unknown"
            rui >= 17 -> "Android $rui" to "ColorOS $rui.0"
            else -> "Android$rui.0" to "ColorOS$rui"
        }

        val body = JSONObject()
            .put("language", lang).put("romVersion", prefix).put("otaVersion", otaVersion)
            .put("androidVersion", androidVer).put("colorOSVersion", colorVer)
            .put("model", dev.model).put("productName", dev.model)
            .put("operator", "unknown").put("uRegion", regionCode).put("trackRegion", regionCode)
            .put("imei", "000000000000000").put("imei1", "000000000000000")
            .put("mode", "0").put("registrationId", "unknown").put("deviceId", deviceId)
            .put("version", "3").put("type", "1").put("otaPrefix", prefix)
            .put("isRealme", "0").put("time", now.toString())
            .put("canCheckSelf", "0").put("gray", "0").put("reqMode", "manual")

        val session = OplusCrypto.newSession()
        val req = Request.Builder().url(server.url)
            .post(OplusCrypto.encryptBody(body.toString(), session).toRequestBody("application/json".toMediaType()))
            .header("language", lang).header("romVersion", prefix).header("otaVersion", otaVersion)
            .header("androidVersion", androidVer).header("colorOSVersion", colorVer).header("model", dev.model)
            .header("infVersion", "1").header("operator", "unknown").header("nvCarrier", nvCarrier(server.id))
            .header("uRegion", regionCode).header("trackRegion", regionCode)
            .header("imei", "000000000000000").header("imei1", "000000000000000")
            .header("deviceId", deviceId).header("mode", "manual").header("channel", "pc")
            .header("version", "2").header("Accept", "application/json")
            .header("User-Agent", "NULL")
            .header("protectedKey", OplusCrypto.protectedKeyHeader(session, server, now))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code} from ${server.name} gateway: ${text.take(120)}")
            val outer = JSONObject(text)
            val enc = findCipherObject(outer) ?: error(
                "Unexpected response shape (keys=${outer.keys().asSequence().toList()}, " +
                    "code=${outer.opt("responseCode")}, errMsg=${outer.optString("errMsg")}): ${text.take(160)}",
            )
            val plain = OplusCrypto.decrypt(enc.getString("cipher"), enc.getString("iv"), session)
            lastRawJson = plain
            Log.d("OplusClient", "decrypted: ${plain.take(400)}")
            return JSONObject(plain)
        }
    }

    private fun asObj(v: Any?): JSONObject? = when (v) {
        is JSONObject -> v
        is String -> runCatching { JSONObject(v) }.getOrNull()
        else -> null
    }

    /** {cipher, iv} can sit at the top level or inside body / resps / params (as text or object). */
    private fun findCipherObject(outer: JSONObject): JSONObject? {
        if (outer.has("cipher") && outer.has("iv")) return outer
        for (k in listOf("body", "resps", "params")) {
            val o = asObj(outer.opt(k)) ?: continue
            if (o.has("cipher") && o.has("iv")) return o
        }
        return null
    }

    // ---- parsing -------------------------------------------------------------------------------

    private fun findArray(o: Any?, key: String): JSONArray? {
        when (o) {
            is JSONObject -> {
                o.optJSONArray(key)?.let { return it }
                for (k in o.keys()) findArray(o.opt(k), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until o.length()) findArray(o.opt(i), key)?.let { return it }
        }
        return null
    }

    private fun findString(o: Any?, key: String): String? {
        when (o) {
            is JSONObject -> {
                o.optString(key).takeIf { it.isNotBlank() }?.let { return it }
                for (k in o.keys()) findString(o.opt(k), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until o.length()) findString(o.opt(i), key)?.let { return it }
        }
        return null
    }

    /** Short human-readable reason from the server JSON (errMsg / checkFailReason / code) for the Source log. */
    fun describe(json: JSONObject): String {
        val parts = listOfNotNull(
            findString(json, "responseCode")?.let { "code $it" },
            findString(json, "errMsg")?.let { "errMsg: $it" },
            findString(json, "checkFailReason")?.let { "reason: $it" },
        )
        return parts.ifEmpty { listOf(json.toString().take(160)) }.joinToString(", ")
    }

    /** Picks the biggest component that has a URL (the main OTA zip). Returns null when none. */
    fun parse(json: JSONObject, source: SourceType): OtaPackage? {
        val comps = findArray(json, "components") ?: return null
        var best: OtaPackage? = null
        for (i in 0 until comps.length()) {
            val c = comps.optJSONObject(i) ?: continue
            val p = c.optJSONObject("componentPackets") ?: continue
            val url = p.optString("manualUrl").ifBlank { p.optString("url") }
            if (url.isBlank()) continue
            val size = p.optString("size").toLongOrNull() ?: p.optLong("size", 0L)
            val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "ota_${i}.zip" }
            val kind = p.optString("type").lowercase()
            val incremental = listOf(name.lowercase(), kind).any { s ->
                listOf("patch", "increment", "delta", "diff").any { it in s }
            }
            val pkg = OtaPackage(
                version = findString(json, "realVersionName") ?: findString(json, "versionName")
                    ?: c.optString("componentVersion", "unknown"),
                otaVersion = findString(json, "otaVersion") ?: c.optString("componentVersion").ifBlank { null },
                url = url, sizeBytes = size, md5 = p.optString("md5").ifBlank { null },
                fileName = name,
                changelog = listOfNotNull(findString(json, "securityPatch")?.let { "Security patch: $it" })
                    .joinToString("\n").ifBlank { null },
                // Heuristic: not marked as patch AND big enough. Calibrate with raw JSON on the real device.
                isFull = !incremental && size >= FULL_MIN_BYTES,
                source = source,
            )
            if (best == null || pkg.sizeBytes > best.sizeBytes) best = pkg
        }
        return best
    }

    companion object { const val FULL_MIN_BYTES = 2_000_000_000L }
}

/** "PJZ110_11.C.36_1360_202403011200" -> "PJZ110_11.C.00_0000_000000000000" (asks server for a full package). */
internal fun baselineOtaVersion(current: String): String {
    val parts = current.split("_")
    val m = Regex("^\\d+\\.([A-Z])\\.").find(parts.getOrNull(1).orEmpty()) ?: return current
    return "${parts.first()}_11.${m.groupValues[1]}.00_0000_000000000000"
}

/** Primary: the device's own region gateway. Auto-detects model + OTA version from the phone. */
class OplusDirectSource(private val client: OplusClient) : OtaSource {
    override val type = SourceType.OPLUS_DIRECT

    override suspend fun fetch(dev: DeviceInfo, fullOnly: Boolean): SourceOutcome = withContext(Dispatchers.IO) {
        if (dev.otaVersion.isBlank()) return@withContext SourceOutcome.Error("Couldn't read ro.build.version.ota")
        val server = OplusServers.byId(dev.regionId)
        resolve(client, dev, listOf(server), fullOnly, type)
    }
}

/** The "half" third source: same protocol, other regional gateways, tried one by one. */
class OplusGatewayFallbackSource(private val client: OplusClient) : OtaSource {
    override val type = SourceType.OPLUS_GATEWAYS

    override suspend fun fetch(dev: DeviceInfo, fullOnly: Boolean): SourceOutcome = withContext(Dispatchers.IO) {
        if (dev.otaVersion.isBlank()) return@withContext SourceOutcome.Error("OTA version missing")
        resolve(client, dev, OplusServers.othersThan(dev.regionId), fullOnly, type)
    }
}

private fun resolve(
    client: OplusClient, dev: DeviceInfo, servers: List<OplusServers.Server>,
    fullOnly: Boolean, type: SourceType,
): SourceOutcome {
    val errors = mutableListOf<String>()
    var sawResponse = false
    for (server in servers) {
        try {
            var last = client.query(dev, server, dev.otaVersion)
            var pkg = client.parse(last, type)
            sawResponse = true
            if (fullOnly && pkg?.isFull != true) {
                // Ask again pretending to be on an old baseline -> server should hand out a full package.
                last = client.query(dev, server, baselineOtaVersion(dev.otaVersion))
                pkg = client.parse(last, type) ?: pkg
            }
            if (pkg == null) { errors += "${server.name}: no package (${client.describe(last)})"; continue }
            if (fullOnly && !pkg.isFull) { errors += "${server.name}: only an incremental package was found, no full package"; continue }
            return SourceOutcome.Found(pkg)
        } catch (e: Exception) {
            errors += "${server.name}: ${e.message}"
        }
    }
    val msg = errors.joinToString("; ")
    return if (sawResponse) SourceOutcome.NoUpdate(msg) else SourceOutcome.Error(msg)
}
