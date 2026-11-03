package com.pyvideotrans.subtitle.data.asr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Recognition results travel two ways: into the job archive and across the NPU process
 * boundary. kotlinx omits a field that still holds its default value, and `pre_segmented`
 * defaults to false — so without writing defaults out, whoever reads the result back would
 * re-merge subtitle cues the engine already cut on real word timestamps.
 */
internal val asrResultJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class TranscriptionSegment(
    val t0: Long = 0L,
    val t1: Long = 0L,
    val text: String = "",
)

@Serializable
data class TranscriptionResult(
    val language: String = "",
    @kotlinx.serialization.SerialName("audio_ms")
    val audioMs: Long = 0L,
    val segments: List<TranscriptionSegment> = emptyList(),
    /**
     * True when the segments are already subtitle cues cut on word-level
     * timestamps; the post-processing must not merge or split them again.
     */
    @kotlinx.serialization.SerialName("pre_segmented")
    val preSegmented: Boolean = false,
)

data class TranscriptionOutcome(
    val result: TranscriptionResult,
    val elapsedMs: Long,
    val modelLoadMs: Long,
)
