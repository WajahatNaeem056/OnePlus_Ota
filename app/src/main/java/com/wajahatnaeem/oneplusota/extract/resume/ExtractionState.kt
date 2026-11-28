package com.wajahatnaeem.oneplusota.extract.resume

import android.content.Context
import org.json.JSONObject
import java.io.File

data class ExtractionState(
    val partitionName: String,
    val lastCompletedOpIndex: Int,
    val bytesWritten: Long,
    val totalBytes: Long
) {
    val progressPercent: Int get() =
        if (totalBytes > 0) ((bytesWritten * 100) / totalBytes).toInt() else 0

    val formattedProgress: String get() = "$progressPercent% (${formatBytes(bytesWritten)} / ${formatBytes(totalBytes)})"

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024L          -> "%.1f KB".format(bytes / 1024.0)
        else                    -> "$bytes B"
    }
}

/**
 * Persists extraction progress to internal storage so extractions can
 * resume after an app restart, network interruption, or crash.
 *
 * State files are stored in filesDir/ota_state/<partitionName>.json
 * and deleted automatically on successful completion.
 */
class ExtractionStateStore(context: Context) {

    private val dir  = File(context.filesDir, "ota_state").also { it.mkdirs() }

    fun save(state: ExtractionState) {
        try {
            val file = File(dir, "${state.partitionName}.json")
            file.writeText(
                JSONObject()
                    .put("partitionName", state.partitionName)
                    .put("lastCompletedOpIndex", state.lastCompletedOpIndex)
                    .put("bytesWritten", state.bytesWritten)
                    .put("totalBytes", state.totalBytes)
                    .toString()
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun load(partitionName: String): ExtractionState? {
        val file = File(dir, "$partitionName.json")
        return if (file.exists()) {
            runCatching {
                val o = JSONObject(file.readText())
                ExtractionState(
                    partitionName = o.getString("partitionName"),
                    lastCompletedOpIndex = o.getInt("lastCompletedOpIndex"),
                    bytesWritten = o.getLong("bytesWritten"),
                    totalBytes = o.getLong("totalBytes")
                )
            }.getOrNull()
        } else null
    }

    fun clear(partitionName: String) {
        File(dir, "$partitionName.json").delete()
    }

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    fun hasSavedState(partitionName: String): Boolean =
        File(dir, "$partitionName.json").exists()
}

