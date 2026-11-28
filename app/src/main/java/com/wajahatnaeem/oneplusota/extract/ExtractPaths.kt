package com.wajahatnaeem.oneplusota.extract

import android.content.Context
import java.io.File

object ExtractPaths {
    const val ROOT_DIR = "OnePlusOTA"

    /** Scratch folder inside the app. Partial images live here; finished ones are moved to Downloads. */
    fun workDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "$ROOT_DIR/Extracted").also { it.mkdirs() }

    /** Human-readable place where the finished images end up. */
    fun downloadsHint(folder: String) = "Downloads/$ROOT_DIR/$folder"
}
