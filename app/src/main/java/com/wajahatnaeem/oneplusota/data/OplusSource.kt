package com.wajahatnaeem.oneplusota.data

import android.util.Log
import com.wajahatnaeem.oneplusota.device.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    /** Device's own tag if it has one, otherwise the default for its region (not the gateway's). */
    private fun nvCarrier(dev: DeviceInfo) = dev.nvId.trim().ifBlank {
        when (dev.regionId) { 1 -> "10010111"; 3 -> "01000100"; else -> "00011011" }
    }

    fun query(dev: DeviceInfo, server: OplusServers.Server, otaVersion: String): JSONObject {
        val now = System.currentTimeMillis()
        val modelName = dev.product.ifBlank { dev.model } // e.g. CPH2747EEA (region build), not just CPH2747
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
            .put("model", modelName).put("productName", modelName)
            .put("operator", "unknown").put("uRegion", regionCode).put("trackRegion", regionCode)
            .put("imei", "000000000000000").put("imei1", "000000000000000")
            .put("mode", "0").put("registrationId", "unknown").put("deviceId", deviceId)
            .put("version", "3").put("type", "1").put("otaPrefix", prefix)
            .put("isRealme", "0").put("time", now.toString())
            .put("canCheckSelf", "0").put("gray", "0").put("reqMode", dev.reqMode)

        val session = OplusCrypto.newSession()
        val req = Request.Builder().url(server.url)
            .post(OplusCrypto.encryptBody(body.toString(), session).toRequestBody("application/json".toMediaType()))
            .header("language", lang).header("romVersion", prefix).header("otaVersion", otaVersion)
            .header("androidVersion", androidVer).header("colorOSVersion", colorVer).header("model", modelName)
            .header("infVersion", "1").header("operator", "unknown").header("nvCarrier", nvCarrier(dev))
            .header("uRegion", regionCode).header("trackRegion", regionCode)
            .header("imei", "000000000000000").header("imei1", "000000000000000")
            .header("deviceId", deviceId).header("mode", dev.reqMode).header("channel", "pc")
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

private val REGION_SUFFIXES = listOf("EEA", "IN", "RU", "TR", "CN", "EU", "TW", "MEA", "SA", "SG", "TH", "LATAM", "BR", "MY", "ID", "KZ", "OCA", "VN", "GLO", "PH", "LK")

/**
 * An "old" build string for this device, e.g. "CPH2747NV44_11.C.01_0001_100001010000".
 * Asking with it makes the server answer with the newest FULL package even when the phone is already up to date.
 * Null when the build letter can't be read from the phone.
 */
<<<<<<< ours
internal fun syntheticOtaVersion(dev: DeviceInfo): String? {
    val letterRx = Regex("_11[._]([A-Z])\\.", RegexOption.IGNORE_CASE)
    val letter = listOf(dev.otaVersion, dev.displayVersion)
=======
internal fun syntheticOtaVersion(dev: DeviceInfo, letterOverride: String? = null): String? {
    val letterRx = Regex("_11[._]([A-Z])\\.", RegexOption.IGNORE_CASE)
    val letter = letterOverride ?: listOf(dev.otaVersion, dev.displayVersion)
>>>>>>> theirs
        .firstNotNullOfOrNull { letterRx.find(it)?.groupValues?.get(1)?.uppercase() } ?: return null
    var base = dev.model.substringBefore("_11").substringBefore(".")
    REGION_SUFFIXES.firstOrNull { base.endsWith(it, ignoreCase = true) }?.let { base = base.dropLast(it.length) }
    base = base.replace(Regex("NV[0-9A-Z]{2}$", RegexOption.IGNORE_CASE), "")
    val nv = dev.nvId.trim().takeIf { it.length == 4 && it.startsWith("NV", ignoreCase = true) }?.uppercase().orEmpty()
    return "${base}${nv}_11.${letter}.01_0001_100001010000"
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
        resolve(client, dev.copy(probe = false), OplusServers.othersThan(dev.regionId), fullOnly, type)
    }
}

private fun versionNums(v: String) =
    Regex("\\d+").findAll(v.substringAfter('_')).map { it.value.toLongOrNull() ?: 0L }.toList()

/** Both strings come from the same OnePlus server, e.g. "CPH2645_17.0.0.105(EX01)" vs "CPH2645_16.0.10.600(EX01)". */
private fun isNewerVersion(a: String, b: String): Boolean {
    val x = versionNums(a); val y = versionNums(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val p = x.getOrElse(i) { 0L }; val q = y.getOrElse(i) { 0L }
        if (p != q) return p > q
    }
    return false
}

private class Probe(val json: JSONObject?, val pkg: OtaPackage?, val desc: String?, val err: String?)

private suspend fun resolve(
    client: OplusClient, dev: DeviceInfo, servers: List<OplusServers.Server>,
    fullOnly: Boolean, type: SourceType,
<<<<<<< ours
): SourceOutcome {
    // Full-only: first ask as an old build (server hands out the newest full zip), then as the phone's real build.
    val versions = listOfNotNull(if (fullOnly) syntheticOtaVersion(dev) else null, dev.otaVersion).distinct()
    val errors = mutableListOf<String>()
    var sawResponse = false
    for (server in servers) {
        var onlyIncremental = false
        var lastDesc: String? = null
        for (v in versions) {
            try {
                val json = client.query(dev, server, v)
                sawResponse = true
                val pkg = client.parse(json, type)
                if (pkg == null) { lastDesc = client.describe(json); continue }
                if (!fullOnly || pkg.isFull) return SourceOutcome.Found(pkg)
                onlyIncremental = true
            } catch (e: Exception) {
                errors += "${server.name}: ${e.message}"
            }
=======
): SourceOutcome = coroutineScope {
    // Full-only: ask as old builds (server hands out the newest full zip) and as the phone's real build.
    // Catalog devices don't know their build letter, so every letter is tried and the newest full package wins.
    val versions = buildList {
        if (fullOnly) {
            syntheticOtaVersion(dev)?.let { add(it) }
            if (dev.probe) ('A'..'Z').forEach { c -> syntheticOtaVersion(dev, c.toString())?.let { add(it) } }
        }
        add(dev.otaVersion)
    }.distinct()
    val errors = mutableListOf<String>()
    var sawResponse = false
    for (server in servers) {
        val gate = Semaphore(6)
        val probes = versions.map { v ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    try {
                        val json = client.query(dev, server, v)
                        Probe(json, client.parse(json, type), client.describe(json), null)
                    } catch (e: Exception) {
                        Probe(null, null, null, e.message)
                    }
                }
            }
        }.awaitAll()
        if (probes.any { it.err == null }) sawResponse = true
        val fulls = probes.filter { it.pkg != null && (!fullOnly || it.pkg.isFull) }
        val best = fulls.reduceOrNull { x, y -> if (isNewerVersion(y.pkg!!.version, x.pkg!!.version)) y else x }
        if (best != null) {
            client.lastRawJson = best.json.toString()
            return@coroutineScope SourceOutcome.Found(best.pkg!!)
        }
        if (probes.any { it.pkg != null }) errors += "${server.name}: only an incremental package was found, no full package"
        else {
            val desc = probes.firstNotNullOfOrNull { it.desc }
            if (desc != null) errors += "${server.name}: no package ($desc)"
            probes.mapNotNull { it.err }.distinct().take(2).forEach { errors += "${server.name}: $it" }
>>>>>>> theirs
        }
        if (onlyIncremental) errors += "${server.name}: only an incremental package was found, no full package"
        else if (lastDesc != null) errors += "${server.name}: no package ($lastDesc)"
    }
    val msg = errors.joinToString("; ")
    if (sawResponse) SourceOutcome.NoUpdate(msg) else SourceOutcome.Error(msg)
}
