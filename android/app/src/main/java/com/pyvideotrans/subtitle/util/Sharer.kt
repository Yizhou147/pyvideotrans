package com.pyvideotrans.subtitle.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a produced file to another app. The job files live in the cache directory, where
 * no other app can read them, so they go out as a content URI from the FileProvider with
 * a read grant that lasts only as long as the receiving component needs it.
 */
object Sharer {
    private const val AUTHORITY_SUFFIX = ".fileprovider"

    fun share(context: Context, file: File) {
        // Android may evict cache files while the app is still running.
        if (!file.isFile) {
            LogBus.error("share", "${file.name} 已不在缓存中，请重新生成字幕")
            return
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        LogBus.info("share", "分享 ${file.name}")
        context.startActivity(Intent.createChooser(intent, "分享 ${file.name}"))
    }
}
