package com.pyvideotrans.subtitle.data.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recognition result is written to the job archive and carried across the NPU process
 * boundary, so what comes back has to be what went in — especially the flag that says the
 * cues were already cut on word timestamps.
 */
class TranscriptionArchiveTest {

    @Test
    fun thePreSegmentedFlagSurvivesTheRoundTrip() {
        val result = TranscriptionResult(
            language = "zh",
            audioMs = 5_000,
            segments = listOf(
                TranscriptionSegment(0, 3_000, "第一句"),
                TranscriptionSegment(3_400, 5_000, "第二句"),
            ),
            preSegmented = true,
        )

        val text = asrResultJson.encodeToString(TranscriptionResult.serializer(), result)

        assertTrue(text, text.contains("\"pre_segmented\":true"))
        assertEquals(result, asrResultJson.decodeFromString(TranscriptionResult.serializer(), text))
    }

    /** An archive written before the flag existed still reads, and reads as unsegmented. */
    @Test
    fun anOlderArchiveWithoutTheFlagStillReads() {
        val text = """{"language":"zh","audio_ms":1000,"segments":[{"t0":0,"t1":900,"text":"句子"}]}"""

        val result = asrResultJson.decodeFromString(TranscriptionResult.serializer(), text)

        assertFalse(result.preSegmented)
        assertEquals(1, result.segments.size)
        assertEquals("句子", result.segments.single().text)
    }
}
