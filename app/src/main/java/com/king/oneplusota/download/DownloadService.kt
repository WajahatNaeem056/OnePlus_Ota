package com.king.oneplusota.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.king.oneplusota.ServiceLocator
import com.king.oneplusota.data.CheckResult
import com.king.oneplusota.data.OtaPackage
import com.king.oneplusota.device.DeviceReader
import com.king.oneplusota.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service: the download keeps running with the screen off / app in background. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private lateinit var store: DownloadStore
    private lateinit var downloader: ResumableDownloader
    private var lastNotify = 0L

    override fun onCreate() {
        super.onCreate()
        store = DownloadStore(this)
        downloader = ResumableDownloader(ServiceLocator.http, NetworkWaiter(this))
        val ch = NotificationChannel(CHANNEL, "OTA download", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pause("Tumne pause kiya")
            ACTION_CANCEL -> cancel()
            ACTION_START, ACTION_RESUME -> begin()
            // null intent = Android restarted us after killing the process
            else -> if (store.active) begin() else stopSelf()
        }
        return START_STICKY
    }

    private fun begin() {
        val job = store.load() ?: run { stopSelf(); return }
        store.active = true
        val part = store.partFile(job.pkg)
        startFg(buildNotif(job.pkg, part.takeIf { it.exists() }?.length() ?: 0L, job.total, "Starting…", running = true))
        worker?.cancel()
        worker = scope.launch {
            DownloadCenter.set(DlState.Active(job.pkg, part.takeIf { it.exists() }?.length() ?: 0L, job.total, 0, null))
            val result = downloader.run(
                job = job, part = part,
                refresh = {
                    val r = ServiceLocator.chain.check(job.spec ?: DeviceReader.read(), fullOnly = true)
                    (r as? CheckResult.Found)?.pkg
                },
                onProgress = { done, total, bps, note ->
                    DownloadCenter.set(DlState.Active(job.pkg, done, total, bps, note))
                    store.save(job) // keeps etag / refreshed url / total across process death
                    val now = System.currentTimeMillis()
                    if (now - lastNotify > 1000) { lastNotify = now; notify(buildNotif(job.pkg, done, total, speedText(bps, note), true)) }
                },
            )
            when (result) {
                is RunResult.Completed -> finish(job)
                is RunResult.GaveUp -> {
                    val done = part.takeIf { it.exists() }?.length() ?: 0L
                    DownloadCenter.set(DlState.Failed(job.pkg, done, job.total, result.message))
                    store.active = false
                    stopForeground(STOP_FOREGROUND_DETACH)
                    notify(buildNotif(job.pkg, done, job.total, result.message, running = false, failed = true))
                    stopSelf()
                }
            }
        }
    }

    private suspend fun finish(job: DlJob) {
        val pkg = job.pkg
        val part = store.partFile(pkg); val fin = store.finalFile(pkg)
        DownloadCenter.set(DlState.Verifying(pkg))
        notify(buildNotif(pkg, job.total, job.total, "MD5 verify ho raha hai…", true))
        fin.delete(); part.renameTo(fin)
        val ok: Boolean? = pkg.md5?.let { ResumableDownloader.md5Of(fin).equals(it, ignoreCase = true) }
        store.active = false
        if (ok == false) {
            // Corrupt: resuming can't fix it, so this is the one case where we discard the file.
            fin.delete(); store.clear()
            DownloadCenter.set(DlState.Failed(pkg, 0, job.total, "MD5 match nahi hua — file corrupt thi, dobara download karo"))
        } else {
            DownloadCenter.set(DlState.Done(pkg, fin.absolutePath, ok))
        }
        stopForeground(STOP_FOREGROUND_DETACH)
        notify(buildNotif(pkg, job.total, job.total, if (ok == false) "Verify fail" else "Download complete ✓", false))
        stopSelf()
    }

    private fun pause(reason: String) {
        val job = store.load() ?: return
        worker?.cancel(); store.active = false
        val done = store.partFile(job.pkg).takeIf { it.exists() }?.length() ?: 0L
        DownloadCenter.set(DlState.Paused(job.pkg, done, job.total, reason))
        stopForeground(STOP_FOREGROUND_DETACH)
        notify(buildNotif(job.pkg, done, job.total, "Paused", running = false))
        stopSelf()
    }

    private fun cancel() {
        val job = store.load()
        worker?.cancel(); store.active = false
        job?.let { store.partFile(it.pkg).delete() }
        store.clear(); DownloadCenter.set(DlState.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    /** Android 15 limits dataSync foreground services to ~6h; pause cleanly so progress stays resumable. */
    override fun onTimeout(startId: Int, fgsType: Int) { pause("Android ne service time-limit laga di — Resume dabao") }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    // ---- notification ------------------------------------------------------------------------

    private fun speedText(bps: Long, note: String?) =
        note ?: if (bps > 0) "%.1f MB/s".format(bps / 1_048_576.0) else "Downloading…"

    private fun action(label: String, act: String, code: Int) = NotificationCompat.Action(
        0, label, PendingIntent.getForegroundService(
            this, code, Intent(this, DownloadService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )

    private fun buildNotif(pkg: OtaPackage, done: Long, total: Long, text: String, running: Boolean, failed: Boolean = false): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(if (running) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
            .setContentTitle(pkg.fileName).setContentText("$pct% · $text")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(running)
        if (running) {
            b.setProgress(100, pct, total <= 0)
            b.addAction(action("Pause", ACTION_PAUSE, 1)).addAction(action("Cancel", ACTION_CANCEL, 2))
        } else if (failed || text == "Paused") {
            b.setProgress(100, pct, false)
            b.addAction(action(if (failed) "Retry" else "Resume", ACTION_RESUME, 3))
        }
        return b.build()
    }

    private fun startFg(n: Notification) {
        startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notify(n: Notification) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)

    companion object {
        private const val CHANNEL = "ota_download"
        private const val NOTIF_ID = 4101
        const val ACTION_START = "com.king.oneplusota.START"
        const val ACTION_RESUME = "com.king.oneplusota.RESUME"
        const val ACTION_PAUSE = "com.king.oneplusota.PAUSE"
        const val ACTION_CANCEL = "com.king.oneplusota.CANCEL"

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, DownloadService::class.java).setAction(action)
            if (action == ACTION_START || action == ACTION_RESUME) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
