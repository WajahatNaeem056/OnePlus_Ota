package com.king.oneplusota.device

import android.os.Build
import org.json.JSONObject

object SysProps {
    private val getter by lazy {
        runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
        }.getOrNull()
    }

    fun get(key: String, def: String = ""): String =
        runCatching { getter?.invoke(null, key, def) as? String ?: def }.getOrDefault(def)
}

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val product: String,
    val marketName: String,
    val otaVersion: String,
    val androidRelease: String,
    val osVersion: String,
    val fingerprint: String,
    val regionId: Int,
) {
    val isOnePlus: Boolean
        get() = manufacturer.equals("OnePlus", true) || Build.BRAND.equals("OnePlus", true)

    fun toJson(): JSONObject = JSONObject().put("manufacturer", manufacturer).put("model", model)
        .put("product", product).put("marketName", marketName).put("otaVersion", otaVersion)
        .put("androidRelease", androidRelease).put("osVersion", osVersion)
        .put("fingerprint", fingerprint).put("regionId", regionId)

    companion object {
        fun fromJson(o: JSONObject) = DeviceInfo(
            o.optString("manufacturer"), o.optString("model"), o.optString("product"),
            o.optString("marketName"), o.optString("otaVersion"), o.optString("androidRelease"),
            o.optString("osVersion"), o.optString("fingerprint"), o.optInt("regionId", 0),
        )
    }
}

object DeviceReader {
    fun read(): DeviceInfo {
        val regionMark = listOf(
            "ro.vendor.oplus.regionmark", "ro.oplus.regionmark", "persist.sys.oplus.region",
        ).map { SysProps.get(it) }.firstOrNull { it.isNotBlank() }.orEmpty().uppercase()

        val product = SysProps.get("ro.product.name", Build.PRODUCT).uppercase()
        val region = when {
            product.endsWith("EEA") -> 3
            product.endsWith("IN") -> 2
            Regex("^P[A-Z]{2}\\d{3}$").matches(Build.MODEL.uppercase()) -> 1
            regionMark.startsWith("CN") -> 1
            regionMark.startsWith("IN") -> 2
            regionMark.startsWith("EU") -> 3
            else -> 0
        }
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            product = SysProps.get("ro.product.name", Build.PRODUCT),
            marketName = SysProps.get("ro.vendor.oplus.market.name", Build.MODEL),
            otaVersion = SysProps.get("ro.build.version.ota", ""),
            androidRelease = Build.VERSION.RELEASE,
            osVersion = SysProps.get("ro.build.version.oplusrom", ""),
            fingerprint = Build.FINGERPRINT,
            regionId = region,
        )
    }
}
