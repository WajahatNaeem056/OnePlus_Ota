package com.wajahatnaeem.oneplusota.extract

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/** Where extracted partition images are written. */
object ExtractPaths {
    const val ROOT_DIR = "OnePlusOTA"

    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** Public folder when "all files access" is granted, otherwise the app's own external folder. */
    fun baseDir(ctx: Context): File =
        if (hasAllFilesAccess()) File(Environment.getExternalStorageDirectory(), ROOT_DIR)
        else File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, ROOT_DIR)

    fun extractedDir(ctx: Context): File = File(baseDir(ctx), "Extracted")
}
