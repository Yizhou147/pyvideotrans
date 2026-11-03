package com.pyvideotrans.subtitle.data.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTimingFallbackTest {
    @Test
    fun wordTimestampsInEveryChunkKeepTheFineCut() {
        assertTrue(isFinelySegmented(tokenCount = 512, timestamplessChunks = 0))
    }

    @Test
    fun oneChunkWithoutTimestampsSendsTheWholeRunBack() {
        assertFalse(isFinelySegmented(tokenCount = 512, timestamplessChunks = 1))
    }

    @Test
    fun aRunThatProducedNoTokensAtAllIsNotAFineCut() {
        assertFalse(isFinelySegmented(tokenCount = 0, timestamplessChunks = 0))
    }
}
