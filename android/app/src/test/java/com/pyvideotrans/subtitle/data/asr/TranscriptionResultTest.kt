package com.pyvideotrans.subtitle.data.asr

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionResultTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesNativePayload() {
        val payload = """
            {"language":"zh","audio_ms":12345,
             "segments":[{"t0":0,"t1":2500,"text":"你好，世界"},
                         {"t0":2600,"t1":5000,"text":"第二句"}]}
        """.trimIndent()
        val result = json.decodeFromString(TranscriptionResult.serializer(), payload)
        assertEquals("zh", result.language)
        assertEquals(12_345L, result.audioMs)
        assertEquals(2, result.segments.size)
        assertEquals(0L, result.segments[0].t0)
        assertEquals(2500L, result.segments[0].t1)
        assertEquals("你好，世界", result.segments[0].text)
        assertEquals("第二句", result.segments[1].text)
    }

    @Test
    fun toleratesMissingOptionalFields() {
        val result = json.decodeFromString(TranscriptionResult.serializer(), "{}")
        assertEquals("", result.language)
        assertEquals(0L, result.audioMs)
        assertEquals(0, result.segments.size)
    }
}
