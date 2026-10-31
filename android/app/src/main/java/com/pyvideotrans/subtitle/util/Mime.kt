package com.pyvideotrans.subtitle.util

import java.util.Locale

/**
 * The MIME type a file name implies. SAF folders need it when creating a document and
 * the share intent needs it to pick sensible receiver apps.
 */
fun mimeFor(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
        "mp4", "m4v", "mov" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "srt", "ass", "vtt" -> "text/plain"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }
