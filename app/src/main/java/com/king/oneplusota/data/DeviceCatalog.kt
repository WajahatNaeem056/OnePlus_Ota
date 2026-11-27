package com.king.oneplusota.data

import com.king.oneplusota.device.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One region build of a device (e.g. 13R / EU / CPH2645EEA). */
data class CatalogVariant(val region: String, val productModel: String, val productName: String?, val letter: String) {
    val regionId: Int get() = when (region.uppercase()) { "CN" -> 1; "IN" -> 2; "EU" -> 3; else -> 0 }
    val label: String get() = when (region.uppercase()) { "GLO" -> "Global / NA"; "EU" -> "EU"; "IN" -> "India"; "CN" -> "China"; else -> region }

    /** Same shape OTA Pulse sends for catalog devices: an "old" build, so the server answers with the newest. */
    val otaVersion: String get() = "${productName ?: productModel}_11.$letter.01_0001_100001010000"

    fun toDeviceInfo(marketName: String) = DeviceInfo(
        manufacturer = "OnePlus", model = productModel, product = productName ?: productModel,
        marketName = marketName, otaVersion = otaVersion, androidRelease = "", osVersion = "",
        fingerprint = "", regionId = regionId,
    )
}

data class CatalogDevice(val name: String, val android: String, val variants: List<CatalogVariant>)

/**
 * Device list = OTA Pulse's community catalog (devices/oneplus.json on GitHub), fetched at runtime and cached.
 * The catalog has no separate "NA" entry: North-America units are served as Global.
 */
class DeviceCatalog(private val http: OkHttpClient) {
    private val url = "https://raw.githubusercontent.com/RemuruSama/OTA-Pulse/main/devices/oneplus.json"

    suspend fun load(cache: File): Result<List<CatalogDevice>> = withContext(Dispatchers.IO) {
        runCatching {
            val text = try {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    r.body!!.string().also { cache.writeText(it) }
                }
            } catch (e: Exception) {
                if (cache.exists()) cache.readText() else throw e // offline -> last cached copy
            }
            parse(text)
        }
    }

    fun parse(text: String): List<CatalogDevice> {
        val arr = JSONArray(text)
        val out = mutableListOf<CatalogDevice>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val groups = d.optJSONObject("firmwareGroups") ?: continue
            // newest Android generation in the catalog
            val key = groups.keys().asSequence()
                .maxByOrNull { Regex("\\d+").find(it)?.value?.toIntOrNull() ?: 0 } ?: continue
            val variants = mutableListOf<CatalogVariant>()
            fun add(o: JSONObject, groupLetter: String?) {
                val model = o.optString("productModel")
                if (model.isBlank()) return
                variants += CatalogVariant(
                    region = o.optString("displayName", "GLO"), productModel = model,
                    productName = o.optString("productName").ifBlank { null },
                    letter = o.optString("versionLetter").ifBlank { groupLetter ?: "A" },
                )
            }
            when (val g = groups.get(key)) {
                is String -> d.optJSONArray("variants")?.let { v ->
                    for (j in 0 until v.length()) v.optJSONObject(j)?.let { add(it, g) }
                }
                is JSONArray -> for (j in 0 until g.length()) g.optJSONObject(j)?.let { add(it, null) }
            }
            if (variants.isNotEmpty()) out += CatalogDevice(d.optString("name"), key, variants)
        }
        return out
    }
}
