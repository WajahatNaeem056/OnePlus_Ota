package com.wajahatnaeem.oneplusota.extract.arb

import android.content.Context
import android.util.Log
import com.wajahatnaeem.oneplusota.extract.engine.OtaExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Reads the ARB (anti-rollback) index straight from the OTA's xbl_config via range requests. */
class ArbChecker(context: Context) {
    private val extractor = OtaExtractor(context.applicationContext)
    private val cache = ConcurrentHashMap<String, XblConfigParser.ArbResult>()

    suspend fun check(url: String): XblConfigParser.ArbResult? {
        cache[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { withTimeout(60_000L) { doCheck(url) } }
                .onFailure { Log.w(TAG, "ARB check failed: ${it.message}") }
                .getOrNull()
                ?.also { cache[url] = it }
        }
    }

    private suspend fun doCheck(url: String): XblConfigParser.ArbResult? {
        val session = extractor.open(url)
        val parts = session.manifest.partitionsList
        for (name in CANDIDATES) {
            val p = parts.firstOrNull { it.partitionName == name } ?: continue
            if (p.newPartitionInfo.size > 10L * 1024 * 1024) continue
            try {
                val out = ByteArrayOutputStream()
                extractor.extract(session, name, out)
                val data = out.toByteArray()
                if (data.isEmpty()) continue
                XblConfigParser().parse(data)?.let { return it }
            } catch (e: Exception) {
                Log.w(TAG, "$name failed: ${e.message}")
            }
        }
        return null
    }

    private companion object {
        const val TAG = "ArbChecker"
        val CANDIDATES = listOf("xbl_config", "xbl", "abl", "xbl_ramdump")
    }
}
