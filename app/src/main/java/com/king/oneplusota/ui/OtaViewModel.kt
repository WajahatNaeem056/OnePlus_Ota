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
import com.king.oneplusota.data.CatalogDevice
import com.king.oneplusota.data.CatalogVariant
import com.king.oneplusota.data.CheckResult
import com.king.oneplusota.data.LinkStatus
import com.king.oneplusota.data.OtaPackage
import com.king.oneplusota.device.DeviceInfo
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
    /** Only full OTA packages are ever shown (no toggle). */
    val fullOnly = true
    var check by mutableStateOf<CheckUi>(CheckUi.Idle)
        private set
    var message by mutableStateOf<String?>(null)

    // ---- link tools: resolve + verify the link before it is copied / shared / opened -------------------
    var linkFor by mutableStateOf<String?>(null)
        private set
    var linkStatus by mutableStateOf<LinkStatus>(LinkStatus.Idle)
        private set

    /**
     * Always copies a FRESH link: asks the sources again (same device, same region), swaps the new package into the card,
     * resolves OnePlus "/downloadCheck" links to the real direct link, then runs [action].
     * If a fresh link can't be fetched, the current link is used and a message says so.
     */
    fun copyFreshLink(pkg: OtaPackage, other: Boolean, action: (String) -> Unit) {
        viewModelScope.launch {
            linkFor = pkg.url; linkStatus = LinkStatus.Checking
            val spec = if (other) otherSpec else updateSpec
            var use = pkg
            if (spec != null) {
                val res = ServiceLocator.chain.check(spec, fullOnly)
                if (res is CheckResult.Found) {
                    use = res.pkg
                    if (other) otherCheck = CheckUi.Result(res) else check = CheckUi.Result(res)
                } else message = "Couldn't get a fresh link, copied the last one."
            }
            linkFor = use.url
            val s = if (use.url.contains("/downloadCheck", ignoreCase = true)) ServiceLocator.linkResolver.resolve(use.url) else LinkStatus.Idle
            linkStatus = LinkStatus.Idle
            action((s as? LinkStatus.Live)?.finalUrl ?: use.url)
        }
    }

    init { DownloadCenter.restore(store) }

    fun setRegion(id: Int?) { regionId = id }

    /** Device as it will be queried on the Update tab (region override applied). */
    val updateSpec: DeviceInfo get() = device.copy(regionId = regionId ?: device.regionId)

    // ---- "Other" tab: any OnePlus device from the catalog ----------------------------------------
    var catalog by mutableStateOf<List<CatalogDevice>>(emptyList())
        private set
    var catalogLoading by mutableStateOf(false)
        private set
    var catalogError by mutableStateOf<String?>(null)
        private set
    var query by mutableStateOf("")
    var selected by mutableStateOf<CatalogDevice?>(null)
        private set
    var variant by mutableStateOf<CatalogVariant?>(null)
        private set
    var otherCheck by mutableStateOf<CheckUi>(CheckUi.Idle)
        private set

    fun loadCatalog() {
        if (catalogLoading || catalog.isNotEmpty()) return
        viewModelScope.launch {
            catalogLoading = true; catalogError = null
            val cache = File(app.filesDir, "oneplus_catalog.json")
            ServiceLocator.catalog.load(cache)
                .onSuccess { catalog = it }
                .onFailure { catalogError = "Couldn't load the device list (${it.message}). Check your connection and try again." }
            catalogLoading = false
        }
    }

    fun reloadCatalog() { catalog = emptyList(); loadCatalog() }

    fun select(d: CatalogDevice?) {
        selected = d; variant = d?.variants?.firstOrNull(); otherCheck = CheckUi.Idle
    }

    /** Opens one exact device + region page. */
    fun pick(d: CatalogDevice, v: CatalogVariant) { selected = d; variant = v; otherCheck = CheckUi.Idle; message = null }

    fun selectVariant(v: CatalogVariant) { variant = v; otherCheck = CheckUi.Idle }

    val otherSpec: DeviceInfo?
        get() = variant?.let { v -> selected?.let { v.toDeviceInfo(it.name) } }

    fun checkOther() {
        val spec = otherSpec ?: return
        viewModelScope.launch {
            otherCheck = CheckUi.Loading; linkFor = null; linkStatus = LinkStatus.Idle
            otherCheck = CheckUi.Result(ServiceLocator.chain.check(spec, fullOnly))
        }
    }

    fun checkUpdate() {
        viewModelScope.launch {
            check = CheckUi.Loading; linkFor = null; linkStatus = LinkStatus.Idle
            check = CheckUi.Result(ServiceLocator.chain.check(updateSpec, fullOnly))
        }
    }

    fun download(pkg: OtaPackage, spec: DeviceInfo) {
        val existing = store.load()
        val sameFile = existing != null && existing.pkg.fileName == pkg.fileName
        if (existing != null && !sameFile) store.partFile(existing.pkg).delete()
        // Same file + partial data on disk -> keep it. The download continues from that byte.
        val job = if (sameFile) existing!!.also { it.pkg = pkg; it.spec = spec } else DlJob(pkg, spec = spec)
        store.save(job)
        DownloadService.send(app, DownloadService.ACTION_START)
    }

    /** Retry when nothing was downloaded yet: start the same package again with its original device context. */
    fun restart(pkg: OtaPackage) = download(pkg, store.load()?.spec ?: updateSpec)

    fun resume() = DownloadService.send(app, DownloadService.ACTION_RESUME)
    fun pause() = DownloadService.send(app, DownloadService.ACTION_PAUSE)
    fun cancel() = DownloadService.send(app, DownloadService.ACTION_CANCEL)

    fun copyRawResponse() {
        val raw = ServiceLocator.oplusClient.lastRawJson
        if (raw == null) { message = "No raw OnePlus response yet"; return }
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("oplus-raw", raw))
        message = "Raw JSON copied"
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
                    "Saved to Downloads: ${f.name}"
                }.getOrElse { "Export fail: ${it.message}" }
            }
        }
    }
}
