package com.wajahatnaeem.oneplusota.extract

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import chromeos_update_engine.UpdateMetadata.InstallOperation
import com.wajahatnaeem.oneplusota.extract.engine.OtaExtractor
import com.wajahatnaeem.oneplusota.extract.resume.ExtractionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File

/** Extracts the chosen partitions from a remote or local OTA zip, in the background with a notification. */
class PartitionExtractorWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_SOURCE = "source"
        const val KEY_PARTITION_NAMES = "partition_names"
        const val KEY_VERSION_NAME = "version_name"

        const val PROGRESS_KEY = "progress"
        const val CURRENT_PARTITION_KEY = "current_partition"
        const val OUTPUT_DIR_KEY = "output_dir"

        private const val NOTIFICATION_ID = 4050
        private const val CHANNEL_ID = "partition_extraction_channel"
    }

    private val otaExtractor = OtaExtractor(applicationContext)
    private var lastNotificationTime = 0L

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val source = inputData.getString(KEY_SOURCE) ?: return@withContext Result.failure()
        val partitionNames = inputData.getStringArray(KEY_PARTITION_NAMES) ?: return@withContext Result.failure()
        if (partitionNames.isEmpty()) return@withContext Result.failure()

        val versionName = sanitize(inputData.getString(KEY_VERSION_NAME) ?: "Unknown")
        val extractedDir = ExtractPaths.workDir(applicationContext)
        val targetFolder = File(extractedDir, versionName).also { it.mkdirs() }

        val titleName = if (partitionNames.size == 1) partitionNames[0] else "${partitionNames.size} partitions"
        createNotificationChannel()

        try {
            runCatching { setForeground(createForegroundInfo(titleName, 0)) }
                .onFailure { Log.w("PartitionExtractor", "Could not promote worker to foreground: ${it.message}") }

            val session = otaExtractor.open(source)
            val total = partitionNames.size
            for ((index, partitionName) in partitionNames.withIndex()) {
                val outputFile = File(targetFolder, "$partitionName.img")
                yield()

                // Incremental partitions need the old image; reuse one extracted earlier if there is any.
                if (session.sourceFile == null && isIncrementalPartition(session, partitionName)) {
                    findSourcePartition(extractedDir, partitionName)?.let { session.sourceFile = it }
                }
                try {
                    otaExtractor.extractToFile(session, partitionName, outputFile) { state ->
                        updateProgress(titleName, partitionName, state, index, total)
                    }
                } finally {
                    session.sourceFile = null
                }

                // Move the finished image to public Downloads so the user can actually find it.
                runCatching { setForeground(createForegroundInfo(titleName, ((index + 1) * 100) / total, "Saving $partitionName to Downloads")) }
                exportToDownloads(outputFile, versionName)
            }
            targetFolder.delete() // only removes the scratch folder if it is empty

            val where = ExtractPaths.downloadsHint(versionName)
            setProgress(workDataOf(PROGRESS_KEY to 100, OUTPUT_DIR_KEY to where))
            showResultNotification("Extraction complete", "$titleName saved to $where", 1)
            Result.success(workDataOf(OUTPUT_DIR_KEY to where))
        } catch (e: CancellationException) {
            partitionNames.forEach { otaExtractor.clearExtractionState(it) }
            showResultNotification("Extraction cancelled", titleName, 3)
            throw e
        } catch (e: Exception) {
            Log.e("PartitionExtractor", "Extraction failed for $titleName", e)
            partitionNames.forEach { otaExtractor.clearExtractionState(it) }
            showResultNotification("Extraction failed", "$titleName: ${e.message ?: "unknown error"}", 2)
            Result.failure(workDataOf("error" to (e.message ?: "unknown error")))
        }
    }

    private suspend fun updateProgress(titleName: String, current: String, state: ExtractionState, index: Int, total: Int) {
        val overall = ((index * 100) + state.progressPercent) / total
        setProgress(workDataOf(PROGRESS_KEY to overall, CURRENT_PARTITION_KEY to current))
        val now = System.currentTimeMillis()
        if (now - lastNotificationTime >= 1000L) {
            lastNotificationTime = now
            val text = if (total > 1) "$overall% • $current" else "$overall%"
            runCatching { setForeground(createForegroundInfo(titleName, overall, text)) }
        }
    }

    private fun createForegroundInfo(titleName: String, progress: Int, text: String? = null): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Extracting $titleName")
            .setContentText(text ?: "$progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Partition extraction", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Progress of partition extraction" }
        (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun showResultNotification(title: String, text: String, offset: Int) {
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID + offset, n)
    }

    /** Copies [file] into Downloads/OnePlusOTA/[folder] (no storage permission needed), then deletes the scratch copy. */
    private fun exportToDownloads(file: File, folder: String) {
        val resolver = applicationContext.contentResolver
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/${ExtractPaths.ROOT_DIR}/$folder"
        // Re-extracting the same partition replaces the old file instead of making "abl (1).img".
        runCatching {
            resolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(file.name, "$relative/"),
            )
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create ${file.name} in Downloads")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        file.delete()
    }

    private fun sanitize(value: String): String =
        value.trim()
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('.')
            .ifBlank { "Unknown" }

    private fun isIncrementalPartition(session: OtaExtractor.Session, partitionName: String): Boolean {
        val partition = session.manifest.partitionsList.find { it.partitionName == partitionName } ?: return false
        return partition.operationsList.any { op ->
            op.type == InstallOperation.Type.SOURCE_COPY ||
                op.type == InstallOperation.Type.MOVE ||
                op.type == InstallOperation.Type.SOURCE_BSDIFF ||
                op.type == InstallOperation.Type.BROTLI_BSDIFF
        }
    }

    private fun findSourcePartition(extractedDir: File, partitionName: String): File? =
        extractedDir.listFiles()?.filter { it.isDirectory }
            ?.map { File(it, "$partitionName.img") }
            ?.filter { it.exists() && it.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
}
