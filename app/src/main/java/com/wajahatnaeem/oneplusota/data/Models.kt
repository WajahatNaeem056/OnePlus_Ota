package com.wajahatnaeem.oneplusota.data

import org.json.JSONObject

enum class SourceType(val label: String) {
    OPLUS_DIRECT("OnePlus Server"),
    OXYGEN_UPDATER("Backup server"),
    /** Legacy: cross-region gateway fallback was removed; kept only so old saved downloads still load. */
    OPLUS_GATEWAYS("OnePlus Gateway"),
}

data class OtaPackage(
    val version: String,
    val otaVersion: String?,
    val url: String,
    val sizeBytes: Long,
    val md5: String?,
    val fileName: String,
    val changelog: String?,
    val isFull: Boolean,
    val source: SourceType,
    val securityPatch: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", version).put("otaVersion", otaVersion ?: JSONObject.NULL)
        .put("url", url).put("sizeBytes", sizeBytes).put("md5", md5 ?: JSONObject.NULL)
        .put("fileName", fileName).put("changelog", changelog ?: JSONObject.NULL)
        .put("isFull", isFull).put("source", source.name)
        .put("securityPatch", securityPatch ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = OtaPackage(
            version = o.getString("version"),
            otaVersion = o.optString("otaVersion").takeIf { it.isNotBlank() && it != "null" },
            url = o.getString("url"),
            sizeBytes = o.optLong("sizeBytes", 0L),
            md5 = o.optString("md5").takeIf { it.isNotBlank() && it != "null" },
            fileName = o.getString("fileName"),
            changelog = o.optString("changelog").takeIf { it.isNotBlank() && it != "null" },
            isFull = o.optBoolean("isFull", false),
            source = runCatching { SourceType.valueOf(o.getString("source")) }.getOrDefault(SourceType.OPLUS_DIRECT),
            securityPatch = o.optString("securityPatch").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

/** What one source says. */
sealed interface SourceOutcome {
    data class Found(val pkg: OtaPackage) : SourceOutcome
    data class NoUpdate(val message: String) : SourceOutcome
    data class Error(val message: String) : SourceOutcome
}

/** Final answer after trying the chain. */
sealed interface CheckResult {
    data class Found(val pkg: OtaPackage, val notes: List<String>) : CheckResult
    data class NoPackage(val notes: List<String>) : CheckResult
    data class Failed(val notes: List<String>) : CheckResult
}
