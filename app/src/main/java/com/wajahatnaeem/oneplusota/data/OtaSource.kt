package com.wajahatnaeem.oneplusota.data

import com.wajahatnaeem.oneplusota.device.DeviceInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

interface OtaSource {
    val type: SourceType
    suspend fun fetch(dev: DeviceInfo, fullOnly: Boolean): SourceOutcome
}

/** Asks the first group of sources together and keeps the newest package; later groups only run when the earlier ones found nothing. */
class SourceChain(private val groups: List<List<OtaSource>>) {
    suspend fun check(dev: DeviceInfo, fullOnly: Boolean): CheckResult {
        val notes = mutableListOf<String>()
        var sawNoUpdate = false
        for (group in groups) {
            val outs = coroutineScope {
                group.map { s ->
                    async {
                        s to runCatching { s.fetch(dev, fullOnly) }
                            .getOrElse { SourceOutcome.Error("${it.javaClass.simpleName}: ${it.message}") }
                    }
                }.awaitAll()
            }
            var best: OtaPackage? = null
            for ((s, out) in outs) when (out) {
                is SourceOutcome.Found -> {
                    val b = best
                    if (b == null || isNewerVersion(out.pkg.cmpVersion(), b.cmpVersion())) best = out.pkg
                }
                is SourceOutcome.NoUpdate -> { sawNoUpdate = true; notes += "${s.type.label}: ${out.message}" }
                is SourceOutcome.Error -> notes += "${s.type.label}: ${out.message}"
            }
            best?.let { return CheckResult.Found(it, notes) }
        }
        return if (sawNoUpdate) CheckResult.NoPackage(notes) else CheckResult.Failed(notes)
    }
}

private fun OtaPackage.cmpVersion() = version.takeIf { it.any(Char::isDigit) } ?: otaVersion.orEmpty()
