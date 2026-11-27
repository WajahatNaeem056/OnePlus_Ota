package com.wajahatnaeem.oneplusota.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.wajahatnaeem.oneplusota.data.OtaPackage

object LinkTools {
    fun bytes(b: Long) = if (b <= 0) "?" else if (b >= 1L shl 30) "%.2f GB".format(b / 1073741824.0) else "%.0f MB".format(b / 1048576.0)

    fun copy(ctx: Context, label: String, text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "$label copied", Toast.LENGTH_SHORT).show()
    }

    fun shareText(deviceLabel: String, pkg: OtaPackage, url: String): String = buildString {
        appendLine(deviceLabel)
        appendLine(pkg.version)
        appendLine("${if (pkg.isFull) "Full OTA" else "OTA"} · ${bytes(pkg.sizeBytes)}")
        pkg.md5?.let { appendLine("MD5: $it") }
        appendLine("Download link: $url")
        append("Link may expire in 15–30 minutes. Verify the MD5 after downloading.")
    }

    fun share(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(send, "Share OTA link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openWith(ctx: Context, url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            ctx.startActivity(Intent.createChooser(view, "Open link with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }
}
