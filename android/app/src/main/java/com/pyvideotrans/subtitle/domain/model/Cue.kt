package com.pyvideotrans.subtitle.domain.model

/** One subtitle line pair: timing plus already-wrapped text. */
data class Cue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
) {
    val durationMs: Long get() = endMs - startMs
}

data class SubtitleOptions(
    /** Maximum characters per displayed line. */
    val maxCharsPerLine: Int = 16,
    val maxLines: Int = 2,
    val minDurationMs: Long = 800L,
    val maxDurationMs: Long = 7_000L,
    /** Silence kept between neighbouring cues. */
    val gapMs: Long = 80L,
    /** Fragments closer than this are one sentence that was cut in half. */
    val mergeGapMs: Long = 250L,
    val cjk: Boolean = true,
)

/** Styling needed to emit ASS; mirrors the desktop app's `ass.json` defaults. */
data class SubtitleStyle(
    val fontName: String = "Noto Sans SC",
    val fontSize: Int = 16,
    val primaryColour: String = "&H00FFFFFF&",
    val outlineColour: String = "&H00000000&",
    val backColour: String = "&H00000000&",
    val secondaryColour: String = "&H00FFFFFF&",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val outlineWidth: Int = 1,
    val shadowWidth: Int = 0,
    val alignment: Int = 2,
    val marginL: Int = 10,
    val marginR: Int = 10,
    val marginV: Int = 10,
    val playResX: Int = 1280,
    val playResY: Int = 720,
    val title: String = "pyvideotrans",
)
