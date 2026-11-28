package com.wajahatnaeem.oneplusota.data

import com.wajahatnaeem.oneplusota.device.DeviceInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Region / model checks applied to whatever a source returns, so a package is only shown when it belongs
 * to the device (and region build) that was asked about. Nothing here guesses: a package is rejected only
 * when its own metadata names a different model, and it is reported as "unverified" when it names none.
 */
internal object RegionGuard {
    sealed interface Verdict {
        data object Verified : Verdict
        /** The package carries no model number we can read, so compatibility is NOT confirmed. */
        data object Unverifiable : Verdict
        data class Mismatch(val expected: String, val found: String) : Verdict
    }

    /** A Oplus model number: 2-5 letters, 2-5 digits, optional region / NV suffix (CPH2653, PJZ110, NE2210, OPD2403EEA). */
    private val MODEL_TOKEN = Regex("^[A-Z]{2,5}\\d{2,5}[A-Z0-9]{0,8}$")
    private val NV_TAIL = Regex("NV[0-9A-Z]{2}$")

    /** "CPH2653EEA" / "CPH2653NV44" / "CPH2653" -> "CPH2653". Same suffix list the build-string code uses. */
    fun baseModel(raw: String): String {
        var base = raw.trim().uppercase().substringBefore("_11").substringBefore(".")
        repeat(2) {
            REGION_SUFFIXES.firstOrNull { base.endsWith(it, ignoreCase = true) && base.length > it.length }?.let { base = base.dropLast(it.length) }
            base = base.replace(NV_TAIL, "")
        }
        return base
    }

    /** "CPH2723_17.0.0.101(EX01)" -> "CPH2723"; null when the text has no readable model prefix. */
    private fun modelOf(text: String?): String? {
        if (text == null || '_' !in text) return null
        val head = text.trim().substringBefore('_').uppercase()
        return if (MODEL_TOKEN.matches(head)) baseModel(head) else null
    }

    private fun same(a: String, b: String) = a == b || a.startsWith(b) || b.startsWith(a) // e.g. PJE110 vs PJE110YS (Genshin edition)

    fun verify(dev: DeviceInfo, pkg: OtaPackage): Verdict {
        val expected = baseModel(dev.model)
        if (!MODEL_TOKEN.matches(expected)) return Verdict.Unverifiable
        val found = listOf(pkg.version, pkg.otaVersion, pkg.fileName).mapNotNull(::modelOf).distinct()
        if (found.isEmpty()) return Verdict.Unverifiable
        found.firstOrNull { !same(it, expected) }?.let { return Verdict.Mismatch(expected, it) }
        return Verdict.Verified
    }

    // ---- Oxygen Updater device matching -----------------------------------------------------------

    sealed interface OxygenMatch {
        data class Found(val id: Long) : OxygenMatch
        data object None : OxygenMatch
        data class Ambiguous(val names: List<String>) : OxygenMatch
    }

    private fun strings(o: Any?, out: MutableList<String> = mutableListOf()): List<String> {
        when (o) {
            is JSONObject -> o.keys().forEach { strings(o.opt(it), out) }
            is JSONArray -> for (i in 0 until o.length()) strings(o.opt(i), out)
            is String -> out += o
        }
        return out
    }

    /**
     * Picks the Oxygen Updater device for exactly this model / region build.
     * Only model-number identifiers are used (never the marketing name, which every region shares), the
     * region-specific product name (CPH2653EEA, CPH2649IN) is required when the device has one, and when more
     * than one Oxygen device matches the answer is "ambiguous" instead of "the first one in the list".
     */
    fun matchOxygenDevice(devices: JSONArray, dev: DeviceInfo): OxygenMatch {
        val model = dev.model.trim().lowercase()
        val product = dev.product.ifBlank { dev.model }.trim().lowercase()
        val ids = if (product != model) setOf(product) else setOf(model).filter { it.isNotBlank() }.toSet()
        if (ids.isEmpty()) return OxygenMatch.None
        val hits = mutableListOf<Pair<Long, String>>()
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            val values = strings(d).flatMap { it.split(',') }.map { it.trim().lowercase() }
            if (ids.any { it in values }) hits += d.optLong("id", -1L) to d.optString("name", "#${d.optLong("id", -1L)}")
        }
        return when (hits.size) {
            0 -> OxygenMatch.None
            1 -> if (hits[0].first >= 0) OxygenMatch.Found(hits[0].first) else OxygenMatch.None
            else -> OxygenMatch.Ambiguous(hits.map { it.second })
        }
    }
}
