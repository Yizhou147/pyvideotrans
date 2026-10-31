package com.pyvideotrans.subtitle.domain.subtitle

import com.pyvideotrans.subtitle.domain.model.Cue
import com.pyvideotrans.subtitle.domain.model.SubtitleOptions
import com.pyvideotrans.subtitle.domain.model.SubtitleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssWriterTest {

    private val options = SubtitleOptions()

    private fun ass(cues: List<Cue>, style: SubtitleStyle) =
        AssWriter.toAss(cues, style, options)

    @Test
    fun headerCarriesTheRealVideoResolution() {
        val text = ass(listOf(Cue(0, 1_000, "你好")), SubtitleStyle(playResX = 1920, playResY = 1080))
        assertTrue(text.contains("PlayResX: 1920"))
        assertTrue(text.contains("PlayResY: 1080"))
        assertTrue(text.contains("ScriptType: v4.00+"))
        assertTrue(text.contains("Format: Name, Fontname, Fontsize, PrimaryColour"))
        assertTrue(text.contains("Format: Layer, Start, End, Style, Name"))
    }

    @Test
    fun landscapeFontSizeScalesUpFromTheDesignCanvas() {
        val text = ass(listOf(Cue(0, 1_000, "你好")), SubtitleStyle(playResX = 1920, playResY = 1080))
        // 16 px authored for a 288-row canvas: 16 * 1080/288 = 60, and the width budget allows it.
        assertTrue(text.contains("Style: Default,Noto Sans SC,60,"))
        assertTrue(text.contains(",1,4,0,2,38,38,38,1"))
    }

    @Test
    fun portraitVideoTrimsTheFontSoAShortLineStillFitsTheWidth() {
        val text = ass(listOf(Cue(0, 1_000, "你好")), SubtitleStyle(playResX = 1080, playResY = 1920))
        val match = Regex("Style: Default,Noto Sans SC,(\\d+),").find(text)
        assertTrue(match != null)
        // Height alone would ask for 106 px, which cannot fit 16 Chinese glyphs in 1080 px.
        assertEquals(56, match!!.groupValues[1].toInt())
    }

    @Test
    fun timesRunInCentisecondsAndRoundUpIntoTheNextSecond() {
        val text = ass(
            listOf(
                Cue(1_234, 2_000, "甲"),
                Cue(995, 1_200, "乙"),
                Cue(3_661_000, 3_700_000, "丙"),
            ),
            SubtitleStyle(),
        )
        assertTrue(text.contains("Dialogue: 0,0:00:01.23,0:00:02.00,Default,,0,0,0,,甲"))
        assertTrue(text.contains("Dialogue: 0,0:00:01.00,0:00:01.20,Default,,0,0,0,,乙"))
        assertTrue(text.contains("Dialogue: 0,1:01:01.00,1:01:40.00,Default,,0,0,0,,丙"))
    }

    @Test
    fun lineBreaksBecomeAssHardBreaks() {
        val text = ass(listOf(Cue(0, 1_000, "第一行\n第二行")), SubtitleStyle())
        assertTrue(text.contains(",,第一行\\N第二行"))
        assertFalse(text.contains("\n第二行"))
    }

    @Test
    fun braceOverridesCannotBeInjectedBySubtitleText() {
        val text = ass(listOf(Cue(0, 1_000, "{\\an8}劫持")), SubtitleStyle())
        assertFalse(text.contains("{"))
        assertFalse(text.contains("}"))
        assertTrue(text.contains(",,\\an8劫持"))
    }

    @Test
    fun emptyCueListStillProducesAPlayableScript() {
        val text = ass(emptyList(), SubtitleStyle(playResX = 1280, playResY = 720))
        assertTrue(text.startsWith("[Script Info]"))
        assertTrue(text.contains("[Events]"))
        assertFalse(text.contains("Dialogue:"))
    }
}
