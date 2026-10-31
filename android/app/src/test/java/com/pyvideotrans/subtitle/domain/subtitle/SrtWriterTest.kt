package com.pyvideotrans.subtitle.domain.subtitle

import com.pyvideotrans.subtitle.domain.model.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtWriterTest {

    @Test
    fun blocksUseCommaMillisecondsAndBlankLines() {
        val srt = SrtWriter.toSrt(
            listOf(Cue(0, 1_500, "第一行\n第二行"), Cue(1_500, 3_600, "hi")),
        )
        assertEquals(
            "1\n00:00:00,000 --> 00:00:01,500\n第一行\n第二行\n\r\n" +
                "2\n00:00:01,500 --> 00:00:03,600\nhi\n",
            srt,
        )
    }

    @Test
    fun numberingStartsAtOneAndCountsEveryCue() {
        val cues = (0 until 5).map { Cue(it * 1_000L, it * 1_000L + 900, "第$it 句") }
        val srt = SrtWriter.toSrt(cues)
        assertEquals(
            listOf("1", "2", "3", "4", "5"),
            srt.lineSequence().filter { it.toIntOrNull() != null }.toList(),
        )
    }

    @Test
    fun hoursAreKeptAndNegativeTimeIsClamped() {
        val srt = SrtWriter.toSrt(
            listOf(Cue(-5, 3_661_001, "很久以后")),
        )
        assertTrue(srt.contains("00:00:00,000 --> 01:01:01,001"))
    }

    @Test
    fun noCuesMeansNoFileContent() {
        assertEquals("", SrtWriter.toSrt(emptyList()))
    }

    @Test
    fun textIsNotHtmlEscaped() {
        val srt = SrtWriter.toSrt(listOf(Cue(0, 1_000, "价格 & 数量 <测试>")))
        assertTrue(srt.contains("价格 & 数量 <测试>"))
        assertFalse(srt.contains("&amp;"))
    }
}
