package com.wajahatnaeem.oneplusota.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.wajahatnaeem.oneplusota.extract.PartitionExtractorWorker
import com.wajahatnaeem.oneplusota.extract.engine.OtaExtractor
import com.wajahatnaeem.oneplusota.extract.payload.PartitionInfo
import kotlinx.coroutines.launch
import java.util.UUID

/** What the partition picker sheet shows. */
data class PartitionSheet(val source: String, val versionName: String, val partitions: List<PartitionInfo>)

class ExtractViewModel(private val app: Application) : AndroidViewModel(app) {
    private val extractor = OtaExtractor(app)
    private val work = WorkManager.getInstance(app)

    var url by mutableStateOf("")
    var localUri by mutableStateOf<Uri?>(null)
        private set
    var localName by mutableStateOf("")
        private set

    /** The source (link or uri) whose partitions are being read right now. */
    var loadingSource by mutableStateOf<String?>(null)
        private set
    var sheet by mutableStateOf<PartitionSheet?>(null)
        private set

    var workId by mutableStateOf<UUID?>(null)
        private set
    var workNames by mutableStateOf<List<String>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)

    fun pickLocal(uri: Uri?) {
        localUri = uri
        localName = uri?.let { displayName(it) }.orEmpty()
    }

    fun loadFromUrl() {
        val link = url.trim()
        if (link.isBlank()) { message = "Paste an OTA link first"; return }
        load(link, guessName(link))
    }

    fun loadFromLocal() {
        val uri = localUri ?: run { message = "Choose a zip first"; return }
        load(uri.toString(), localName.removeSuffix(".zip").ifBlank { "Local OTA" })
    }

    private fun load(source: String, versionName: String) {
        if (loadingSource != null) return
        loadingSource = source
        viewModelScope.launch {
            try {
                val session = extractor.open(source)
                val list = extractor.listPartitions(session)
                // Links often end in a random hash, so prefer the real build id from the zip when there is one.
                val nice = if (source.startsWith("http", ignoreCase = true))
                    session.metadataMap.otaId.takeIf { it.isNotBlank() } ?: versionName
                else versionName
                sheet = PartitionSheet(source, nice, list)
            } catch (e: Exception) {
                message = "Could not read partitions: ${e.message ?: "unknown error"}"
            } finally {
                loadingSource = null
            }
        }
    }

    fun closeSheet() { sheet = null }

    fun extract(names: List<String>) {
        val s = sheet ?: return
        if (names.isEmpty()) return
        val request = OneTimeWorkRequestBuilder<PartitionExtractorWorker>()
            .setInputData(workDataOf(
                PartitionExtractorWorker.KEY_SOURCE to s.source,
                PartitionExtractorWorker.KEY_VERSION_NAME to s.versionName,
                PartitionExtractorWorker.KEY_PARTITION_NAMES to names.toTypedArray(),
            ))
            .build()
        work.enqueue(request)
        workId = request.id
        workNames = names
        sheet = null
    }

    fun cancel() { workId?.let { work.cancelWorkById(it) } }

    fun dismissStatus() { workId = null; workNames = emptyList() }

    private fun guessName(link: String): String {
        val file = link.substringBefore("?").substringAfterLast("/")
        return file.removeSuffix(".zip").ifBlank { "Full OTA" }
    }

    private fun displayName(uri: Uri): String {
        var name = ""
        if (uri.scheme == "content") {
            runCatching {
                app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) name = c.getString(i) ?: ""
                    }
                }
            }
        }
        return name.ifBlank { uri.path?.substringAfterLast("/") ?: "OTA.zip" }
    }
}
