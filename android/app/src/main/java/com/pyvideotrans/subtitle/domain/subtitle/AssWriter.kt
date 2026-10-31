package com.pyvideotrans.subtitle.domain.subtitle

import com.pyvideotrans.subtitle.domain.model.Cue
import com.pyvideotrans.subtitle.domain.model.SubtitleOptions
import com.pyvideotrans.subtitle.domain.model.SubtitleStyle
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ASS carries the styling that SRT cannot: font, size, colours, outline, margins.
 * It is what libass reads for burn-in and what MKV keeps as a styled track.
 *
 * The desktop app let FFmpeg turn SRT into ASS and then rewrote the style block; here
 * the file is authored directly, so the play resolution is the real video size instead
 * of FFmpeg's fixed 384x288 canvas.
 */
object AssWriter {

    /**
     * The style's [SubtitleStyle.fontSize] and margins are authored against the 288-row
     * canvas FFmpeg used, because that is the scale the desktop defaults look right at.
     */
    private const val DESIGN_HEIGHT = 288.0

    private const val MIN_FONT_SIZE = 12

    /** Average advance of one character, in ems: full-width for Chinese, ~0.55 for Latin. */
    private const val CJK_ADVANCE = 1.0
    private const val LATIN_ADVANCE = 0.55

    private const val STYLE_FORMAT =
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, " +
            "BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, " +
            "BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding"

    private const val EVENT_FORMAT =
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"

    fun toAss(
        cues: List<Cue>,
        style: SubtitleStyle,
        options: SubtitleOptions,
    ): String {
        val scale = max(1.0, style.playResY.toDouble()) / DESIGN_HEIGHT
        val fontSize = resolveFontSize(style, options)
        val lines = StringBuilder(1024 + cues.size * 96)
        lines.append("[Script Info]\n")
        lines.append("Title: ${sanitize(style.title)}\n")
        lines.append("ScriptType: v4.00+\n")
        lines.append("WrapStyle: 0\n")
        lines.append("ScaledBorderAndShadow: yes\n")
        lines.append("PlayResX: ${style.playResX}\n")
        lines.append("PlayResY: ${style.playResY}\n")
        lines.append("YCbCr Matrix: TV.709\n")
        lines.append("\n")
        lines.append("[V4+ Styles]\n")
        lines.append("$STYLE_FORMAT\n")
        lines.append(
            "Style: Default,${sanitize(style.fontName)},$fontSize," +
                "${style.primaryColour},${style.secondaryColour},${style.outlineColour},${style.backColour}," +
                "${if (style.bold) -1 else 0},${if (style.italic) -1 else 0},0,0," +
                "100,100,0,0,1,${scaled(style.outlineWidth, scale)},${scaled(style.shadowWidth, scale)}," +
                "${style.alignment},${scaled(style.marginL, scale)},${scaled(style.marginR, scale)}," +
                "${scaled(style.marginV, scale)},1\n",
        )
        lines.append("\n")
        lines.append("[Events]\n")
        lines.append("$EVENT_FORMAT\n")
        for (cue in cues) {
            lines.append("Dialogue: 0,")
                .append(timestamp(cue.startMs))
                .append(',')
                .append(timestamp(cue.endMs))
                .append(",Default,,0,0,0,,")
                .append(escape(cue.text))
                .append('\n')
        }
        return lines.toString()
    }

    fun write(file: File, cues: List<Cue>, style: SubtitleStyle, options: SubtitleOptions): File {
        file.writeText(toAss(cues, style, options), Charsets.UTF_8)
        return file
    }

    /**
     * Text height is limited by the video height, but a whole line has to fit the width
     * too — otherwise a portrait video gets a font so wide the sentence runs off screen.
     */
    private fun resolveFontSize(style: SubtitleStyle, options: SubtitleOptions): Int {
        val scale = max(1.0, style.playResY.toDouble()) / DESIGN_HEIGHT
        val byHeight = style.fontSize * scale
        val chars = max(1, options.maxCharsPerLine)
        val advance = if (options.cjk) CJK_ADVANCE else LATIN_ADVANCE
        val usableWidth = max(1.0, style.playResX - (style.marginL + style.marginR) * scale)
        val byWidth = usableWidth * 0.94 / (chars * advance)
        return min(byHeight, byWidth).roundToInt().coerceAtLeast(MIN_FONT_SIZE)
    }

    private fun scaled(value: Int, scale: Double): Int =
        if (value <= 0) 0 else max(1, (value * scale).roundToInt())

    private fun escape(text: String): String =
        text.replace("{", "").replace("}", "").replace("\n", "\\N")

    private fun sanitize(value: String): String =
        value.replace("\r", "").replace("\n", " ").replace(":", "-")

    /** ASS times run in centiseconds; rounding at 995 ms carries into the next second. */
    private fun timestamp(ms: Long): String {
        val safe = if (ms < 0) 0 else ms
        val centis = (safe + 5) / 10
        val totalSeconds = centis / 100
        return String.format(
            Locale.US,
            "%d:%02d:%02d.%02d",
            totalSeconds / 3600,
            (totalSeconds % 3600) / 60,
            totalSeconds % 60,
            centis % 100,
        )
    }
}
