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
class SourceChain(private val groups: List<List<OtaSource>>, private val links: LinkResolver) {
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
            val found = mutableListOf<OtaPackage>()
            for ((s, out) in outs) when (out) {
                is SourceOutcome.Found -> {
                    found += out.pkg
                    val b = best
                    if (b == null || isNewerVersion(out.pkg.cmpVersion(), b.cmpVersion())) best = out.pkg
                }
                is SourceOutcome.NoUpdate -> { sawNoUpdate = true; notes += "${s.type.label}: ${out.message}" }
                is SourceOutcome.Error -> notes += "${s.type.label}: ${out.message}"
            }
            best?.let { b ->
                val same = found.filter { it !== b && !isNewerVersion(it.cmpVersion(), b.cmpVersion()) && !isNewerVersion(b.cmpVersion(), it.cmpVersion()) }
                val merged = b.copy(
                    changelog = b.changelog ?: same.firstNotNullOfOrNull { it.changelog },
                    securityPatch = b.securityPatch ?: same.firstNotNullOfOrNull { it.securityPatch },
                )
                return CheckResult.Found(withRealName(merged), notes)
            }
        }
        return if (sawNoUpdate) CheckResult.NoPackage(notes) else CheckResult.Failed(notes)
    }

    private suspend fun withRealName(pkg: OtaPackage): OtaPackage {
        if (!pkg.url.contains("/downloadCheck", ignoreCase = true)) return pkg
        val name = links.fileName(pkg.url) ?: return pkg
        return pkg.copy(fileName = name)
    }
}

private fun OtaPackage.cmpVersion() = version.takeIf { it.any(Char::isDigit) } ?: otaVersion.orEmpty()
