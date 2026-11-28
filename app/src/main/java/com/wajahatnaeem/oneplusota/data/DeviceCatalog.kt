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
data class CatalogVariant(val region: String, val productModel: String, val productName: String?, val letter: String, val reqMode: String = "manual",
    /** Another device entry in the list claims this same model/product, so its region can't be trusted: never queried. */
    val conflict: Boolean = false,
) {
    val isChina: Boolean get() = region.equals("CN", true) || region.startsWith("Genshin", true)
    val regionId: Int get() = if (isChina) 1 else when (region.uppercase()) { "IN" -> 2; "EU" -> 3; else -> 0 }
    /** Short tag shown in the list, e.g. "EU", "IN", "GLO", "CN". */
    val code: String get() = if (region.startsWith("Genshin", true)) "CN Genshin" else region.uppercase()
    /** Model number shown under the name, e.g. CPH2645EEA. */
    val modelShown: String get() = productName ?: productModel
    val label: String get() = when (region.uppercase()) { "GLO" -> "Global / NA"; "EU" -> "EU"; "IN" -> "India"; "CN" -> "China"; else -> region }

    /** Region tag used in the build string. */
    val nvTag: String get() = when {
        region.startsWith("Genshin", true) -> "NV97"
        else -> when (region.uppercase()) { "GLO" -> "NVA7"; "CN" -> "NV97"; "IN" -> "NV1B"; "EU" -> "NV44"; else -> "" }
    }

    /** Same tag as the phones report it in ro.build.oplus_nv_id (8 binary digits, e.g. NV1B -> 00011011). */
    val nvId: String get() = nvTag.removePrefix("NV").toIntOrNull(16)?.let { Integer.toBinaryString(it).padStart(8, '0') }.orEmpty()

    /** An "old" build for catalog devices, so the server answers with the newest. */
    val otaVersion: String get() = "$productModel${nvTag}_11.$letter.01_0001_100001010000"

    fun toDeviceInfo(marketName: String, generation: String = "") = DeviceInfo(
        manufacturer = "OnePlus", model = productModel, product = productName ?: productModel,
        marketName = marketName, otaVersion = otaVersion,
        androidRelease = Regex("\\d+").find(generation)?.value.orEmpty(), osVersion = "",
        fingerprint = "", regionId = regionId, reqMode = reqMode, nvId = nvId, probe = true,
    )
}

data class CatalogDevice(val name: String, val android: String, val variants: List<CatalogVariant>)

/**
 * Device list = devices/oneplus.json from this app's own repo, fetched at runtime and cached; a copy ships inside the app as the last fallback.
 * The catalog has no separate "NA" entry: North-America units are served as Global.
 */
class DeviceCatalog(private val http: OkHttpClient) {
    private val url = "https://raw.githubusercontent.com/WajahatNaeem056/OnePlus_Ota/main/devices/oneplus.json"

    suspend fun load(cache: File, bundled: () -> String): Result<List<CatalogDevice>> = withContext(Dispatchers.IO) {
        runCatching {
            val text = try {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    r.body!!.string().also { cache.writeText(it) }
                }
            } catch (e: Exception) {
                if (cache.exists()) cache.readText() else bundled() // offline -> last cached copy -> copy shipped in the app
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

    private fun identity(model: String, productName: String) = "${model.trim()}|${productName.trim()}".uppercase()

    fun parse(text: String): List<CatalogDevice> {
        val arr = JSONArray(text)
        // A model/product identity can belong to exactly one device entry. If two entries claim it the list can't say
        // which is right, so those variants are flagged and refused instead of being served under the wrong device/region.
        val owners = mutableMapOf<String, MutableSet<Int>>()
        for (i in 0 until arr.length()) {
            val vs = arr.optJSONObject(i)?.optJSONArray("variants") ?: continue
            for (j in 0 until vs.length()) {
                val o = vs.optJSONObject(j) ?: continue
                val model = o.optString("productModel")
                if (model.isBlank()) continue
                owners.getOrPut(identity(model, o.optString("productName"))) { mutableSetOf() } += i
            }
        }
        val conflicts = owners.filterValues { it.size > 1 }.keys
        val out = mutableListOf<CatalogDevice>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val variants = mutableListOf<CatalogVariant>()
            val seen = mutableSetOf<String>()
            fun add(o: JSONObject) {
                val model = o.optString("productModel")
                if (model.isBlank()) return
                val region = o.optString("displayName", "GLO")
                if (!seen.add("$region|$model|${o.optString("productName")}")) return
                variants += CatalogVariant(
                    region = region, productModel = model,
                    productName = o.optString("productName").ifBlank { null },
                    letter = "A",
                    reqMode = o.optString("reqMode").ifBlank { "manual" },
                    conflict = identity(model, o.optString("productName")) in conflicts,
                )
            }
            d.optJSONArray("variants")?.let { v -> for (j in 0 until v.length()) v.optJSONObject(j)?.let(::add) }
            out += split(d.optString("name"), "", variants)
        }
        return out
    }
}
