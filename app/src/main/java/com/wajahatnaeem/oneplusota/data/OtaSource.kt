package com.wajahatnaeem.oneplusota.data

import com.wajahatnaeem.oneplusota.device.DeviceInfo

interface OtaSource {
    val type: SourceType
    suspend fun fetch(dev: DeviceInfo, fullOnly: Boolean): SourceOutcome
}

/** Tries sources in order. After the first official hit the next source is asked once; the newer version wins. */
class SourceChain(private val sources: List<OtaSource>) {
    suspend fun check(dev: DeviceInfo, fullOnly: Boolean): CheckResult {
        val notes = mutableListOf<String>()
        var sawNoUpdate = false
        var best: OtaPackage? = null
        var backupAsked = false
        for (s in sources) {
            val cur = best
            if (cur != null && (backupAsked || cur.source != SourceType.OPLUS_DIRECT)) break
            if (cur != null) backupAsked = true
            val out = runCatching { s.fetch(dev, fullOnly) }
                .getOrElse { SourceOutcome.Error("${it.javaClass.simpleName}: ${it.message}") }
            when (out) {
                is SourceOutcome.Found -> {
                    if (cur == null) best = out.pkg
                    else if (isNewer(out.pkg.version, cur.version)) {
                        notes += "${s.type.label}: newer than ${cur.source.label}, using it"
                        best = out.pkg
                    }
                }
                is SourceOutcome.NoUpdate -> { sawNoUpdate = true; notes += "${s.type.label}: ${out.message}" }
                is SourceOutcome.Error -> notes += "${s.type.label}: ${out.message}"
            }
        }
        best?.let { return CheckResult.Found(it, notes) }
        return if (sawNoUpdate) CheckResult.NoPackage(notes) else CheckResult.Failed(notes)
    }

    private fun nums(v: String) = Regex("\\d+").findAll(v.substringAfter('_')).map { it.value.toLongOrNull() ?: 0L }.toList()

    private fun isNewer(a: String, b: String): Boolean {
        val x = nums(a); val y = nums(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0L }; val q = y.getOrElse(i) { 0L }
            if (p != q) return p > q
        }
        return false
    }
}
