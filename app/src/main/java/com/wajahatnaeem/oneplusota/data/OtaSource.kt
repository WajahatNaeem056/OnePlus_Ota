package com.wajahatnaeem.oneplusota.data

import com.wajahatnaeem.oneplusota.device.DeviceInfo

interface OtaSource {
    val type: SourceType
    suspend fun fetch(dev: DeviceInfo, fullOnly: Boolean): SourceOutcome
}

/** Tries sources in order. The first source that returns a package wins; later sources are only asked when the earlier ones gave none. */
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
