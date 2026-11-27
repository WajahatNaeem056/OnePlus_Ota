package com.king.oneplusota.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.king.oneplusota.ServiceLocator
import com.king.oneplusota.data.CheckResult
import com.king.oneplusota.data.OtaPackage
import com.king.oneplusota.device.DeviceReader
import com.king.oneplusota.download.DlJob
import com.king.oneplusota.download.DownloadCenter
import com.king.oneplusota.download.DownloadService
import com.king.oneplusota.download.DownloadStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface CheckUi {
    data object Idle : CheckUi
    data object Loading : CheckUi
    data class Result(val r: CheckResult) : CheckUi
}

class OtaViewModel(private val app: Application) : AndroidViewModel(app) {
    private val store = DownloadStore(app)
    val device = DeviceReader.read()
    val dl = DownloadCenter.state

    /** null = auto-detect */
    var regionId by mutableStateOf<Int?>(null)
        private set
    var fullOnly by mutableStateOf(true)
    var check by mutableStateOf<CheckUi>(CheckUi.Idle)
        private set
    var message by mutableStateOf<String?>(null)

    init { DownloadCenter.restore(store) }

    fun setRegion(id: Int?) { regionId = id; ServiceLocator.regionOverride = id }

    fun checkUpdate() {
        viewModelScope.launch {
            check = CheckUi.Loading
            check = CheckUi.Result(ServiceLocator.chain.check(device, fullOnly))
        }
    }

    fun download(pkg: OtaPackage) {
        val existing = store.load()
        val sameFile = existing != null && existing.pkg.fileName == pkg.fileName
        if (existing != null && !sameFile) store.partFile(existing.pkg).delete()
        // Same file + partial data on disk -> keep it. The download continues from that byte.
        val job = if (sameFile) existing!!.also { it.pkg = pkg } else DlJob(pkg)
        store.save(job)
        DownloadService.send(app, DownloadService.ACTION_START)
    }

    fun resume() = DownloadService.send(app, DownloadService.ACTION_RESUME)
    fun pause() = DownloadService.send(app, DownloadService.ACTION_PAUSE)
    fun cancel() = DownloadService.send(app, DownloadService.ACTION_CANCEL)

    fun copyRawResponse() {
        val raw = ServiceLocator.oplusClient.lastRawJson
        if (raw == null) { message = "Abhi koi raw OnePlus response nahi hai"; return }
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("oplus-raw", raw))
        message = "Raw JSON copy ho gaya"
    }

    /** Copies the finished zip into public Downloads so the system updater's "local install" picker can see it. */
    fun exportToDownloads(path: String) {
        viewModelScope.launch {
            val f = File(path)
            message = withContext(Dispatchers.IO) {
                runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                        put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                    app.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out, 1 shl 16) } }
                    "Downloads folder mein save ho gaya: ${f.name}"
                }.getOrElse { "Export fail: ${it.message}" }
            }
        }
    }
}
