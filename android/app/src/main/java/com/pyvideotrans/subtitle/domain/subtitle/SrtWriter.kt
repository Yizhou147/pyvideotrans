package com.pyvideotrans.subtitle.domain.subtitle

import com.pyvideotrans.subtitle.domain.model.Cue
import java.io.File
import java.util.Locale

/**
 * SRT is the format everything plays and everything edits, so it is always written —
 * even when the user asked for a muxed or burned-in video.
 *
 * Comma before the milliseconds and CRLF between blocks are what players expect; the
 * bytes are UTF-8 without a BOM, matching the desktop app.
 */
object SrtWriter {

    fun toSrt(cues: List<Cue>): String {
        val builder = StringBuilder(cues.size * 96)
        for ((index, cue) in cues.withIndex()) {
            if (index > 0) builder.append("\r\n")
            builder.append(index + 1)
                .append('\n')
                .append(timestamp(cue.startMs))
                .append(" --> ")
                .append(timestamp(cue.endMs))
                .append('\n')
                .append(cue.text)
                .append('\n')
        }
        return builder.toString()
    }

    fun write(file: File, cues: List<Cue>): File {
        file.writeText(toSrt(cues), Charsets.UTF_8)
        return file
    }

    private fun timestamp(ms: Long): String {
        val safe = if (ms < 0) 0 else ms
        val totalSeconds = safe / 1000
        return String.format(
            Locale.US,
            "%02d:%02d:%02d,%03d",
            totalSeconds / 3600,
            (totalSeconds % 3600) / 60,
            totalSeconds % 60,
            safe % 1000,
        )
    }
}
