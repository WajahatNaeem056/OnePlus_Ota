package com.king.oneplusota.data

import com.king.oneplusota.device.DeviceInfo

interface OtaSource {
    val type: SourceType
    suspend fun fetch(dev: DeviceInfo, fullOnly: Boolean): SourceOutcome
}

/**
 * Tries sources in order. First Found wins. Errors / "nothing" fall through to the next source,
 * and every step is reported in notes so the UI can show why something failed.
 */
class SourceChain(private val sources: List<OtaSource>) {
    suspend fun check(dev: DeviceInfo, fullOnly: Boolean): CheckResult {
        val notes = mutableListOf<String>()
        var sawNoUpdate = false
        for (s in sources) {
            val out = runCatching { s.fetch(dev, fullOnly) }
                .getOrElse { SourceOutcome.Error("${it.javaClass.simpleName}: ${it.message}") }
            when (out) {
                is SourceOutcome.Found -> return CheckResult.Found(out.pkg, notes)
                is SourceOutcome.NoUpdate -> { sawNoUpdate = true; notes += "${s.type.label}: ${out.message}" }
                is SourceOutcome.Error -> notes += "${s.type.label}: ${out.message}"
            }
        }
        return if (sawNoUpdate) CheckResult.NoPackage(notes) else CheckResult.Failed(notes)
    }
}
