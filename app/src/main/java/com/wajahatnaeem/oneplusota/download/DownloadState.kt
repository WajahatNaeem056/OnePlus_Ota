package com.wajahatnaeem.oneplusota.download

import android.content.Context
import com.wajahatnaeem.oneplusota.data.OtaPackage
import com.wajahatnaeem.oneplusota.device.DeviceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

sealed interface DlState {
    data object Idle : DlState
    data class Active(val pkg: OtaPackage, val done: Long, val total: Long, val bps: Long, val note: String?) : DlState
    data class Paused(val pkg: OtaPackage, val done: Long, val total: Long, val reason: String) : DlState
    data class Failed(val pkg: OtaPackage, val done: Long, val total: Long, val message: String) : DlState
    data class Verifying(val pkg: OtaPackage) : DlState
    data class Done(val pkg: OtaPackage, val path: String, val md5Ok: Boolean?) : DlState
}

/** Mutable job: the url can be swapped when the link expires, etag/total are learned from the server. */
class DlJob(
    var pkg: OtaPackage, var etag: String? = null, var lastModified: String? = null,
    var total: Long = pkg.sizeBytes, var spec: DeviceInfo? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("pkg", pkg.toJson())
        .put("etag", etag ?: JSONObject.NULL).put("lastModified", lastModified ?: JSONObject.NULL).put("total", total)
        .put("spec", spec?.toJson() ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = DlJob(
            pkg = OtaPackage.fromJson(o.getJSONObject("pkg")),
            etag = o.optString("etag").takeIf { it.isNotBlank() && it != "null" },
            lastModified = o.optString("lastModified").takeIf { it.isNotBlank() && it != "null" },
            total = o.optLong("total", 0L),
            spec = o.optJSONObject("spec")?.let { DeviceInfo.fromJson(it) },
        )
    }
}

class DownloadStore(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("ota_download", Context.MODE_PRIVATE)
    val dir: File get() = ctx.getExternalFilesDir("ota") ?: File(ctx.filesDir, "ota").also { it.mkdirs() }

    fun partFile(pkg: OtaPackage) = File(dir, pkg.fileName + ".part")
    fun finalFile(pkg: OtaPackage) = File(dir, pkg.fileName)

    fun load(): DlJob? = prefs.getString("job", null)?.let { runCatching { DlJob.fromJson(JSONObject(it)) }.getOrNull() }
    fun save(job: DlJob) { prefs.edit().putString("job", job.toJson().toString()).apply() }
    fun clear() { prefs.edit().remove("job").putBoolean("active", false).apply() }

    /** True while the service should be downloading (lets us auto-resume if Android kills the process). */
    var active: Boolean
        get() = prefs.getBoolean("active", false)
        set(v) { prefs.edit().putBoolean("active", v).apply() }
}

object DownloadCenter {
    private val _state = MutableStateFlow<DlState>(DlState.Idle)
    val state: StateFlow<DlState> = _state
    fun set(s: DlState) { _state.value = s }

    /** After an app restart, rebuild state from disk: a partial file means "Paused", never "start over". */
    fun restore(store: DownloadStore) {
        if (_state.value !is DlState.Idle) return
        val job = store.load() ?: return
        val done = store.partFile(job.pkg).takeIf { it.exists() }?.length() ?: 0L
        val fin = store.finalFile(job.pkg)
        _state.value = when {
            fin.exists() -> DlState.Done(job.pkg, fin.absolutePath, null)
            else -> DlState.Paused(job.pkg, done, job.total, "Progress is saved, even after the app was closed")
        }
    }
}
