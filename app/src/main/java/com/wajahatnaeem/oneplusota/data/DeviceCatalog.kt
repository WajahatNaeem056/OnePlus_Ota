package com.wajahatnaeem.oneplusota.data

import com.wajahatnaeem.oneplusota.device.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One region build of a device (e.g. 13R / EU / CPH2645EEA). */
data class CatalogVariant(val region: String, val productModel: String, val productName: String?, val letter: String) {
    val isChina: Boolean get() = region.equals("CN", true) || region.startsWith("Genshin", true)
    val regionId: Int get() = if (isChina) 1 else when (region.uppercase()) { "IN" -> 2; "EU" -> 3; else -> 0 }
    /** Short tag shown in the list, e.g. "EU", "IN", "GLO", "CN". */
    val code: String get() = if (region.startsWith("Genshin", true)) "CN Genshin" else region.uppercase()
    /** Model number shown under the name, e.g. CPH2645EEA. */
    val modelShown: String get() = productName ?: productModel
    val label: String get() = when (region.uppercase()) { "GLO" -> "Global / NA"; "EU" -> "EU"; "IN" -> "India"; "CN" -> "China"; else -> region }

    /** An "old" build for catalog devices, so the server answers with the newest. */
    val otaVersion: String get() = "${productName ?: productModel}_11.$letter.01_0001_100001010000"

    fun toDeviceInfo(marketName: String, generation: String = "") = DeviceInfo(
        manufacturer = "OnePlus", model = productModel, product = productName ?: productModel,
        marketName = marketName, otaVersion = otaVersion,
        androidRelease = Regex("\\d+").find(generation)?.value.orEmpty(), osVersion = "",
        fingerprint = "", regionId = regionId,
    )
}

data class CatalogDevice(val name: String, val android: String, val variants: List<CatalogVariant>)

/**
 * Device list = community catalog (devices/oneplus.json on GitHub), fetched at runtime and cached.
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

    /**
     * The catalog merges a global phone and its China twin into one name ("OnePlus 13R/Ace 5").
     * Split them: non-China builds go to the first name, China builds to the second.
     */
    private fun split(name: String, android: String, variants: List<CatalogVariant>): List<CatalogDevice> {
        val parts = name.split("/").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 2) return if (variants.isEmpty()) emptyList() else listOf(CatalogDevice(name, android, variants))
        val first = parts[0]
        val second = if (parts[1].startsWith("OnePlus", ignoreCase = true)) parts[1] else "OnePlus ${parts[1]}"
        val (cn, other) = variants.partition { it.isChina }
        return listOfNotNull(
            other.takeIf { it.isNotEmpty() }?.let { CatalogDevice(first, android, it) },
            cn.takeIf { it.isNotEmpty() }?.let { CatalogDevice(second, android, it) },
        )
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
            out += split(d.optString("name"), key, variants)
        }
        return out
    }
}
