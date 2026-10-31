package com.pyvideotrans.subtitle.domain.model

/**
 * What FFprobe learned about the video. [width]/[height] are the *displayed* size —
 * a phone video is often coded landscape with a 90-degree rotation flag, and styling
 * that ignores it puts the subtitles across the sky.
 */
data class MediaInfo(
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val hasVideo: Boolean = false,
    val hasAudio: Boolean = false,
) {
    val isPortrait: Boolean get() = height > width
}
