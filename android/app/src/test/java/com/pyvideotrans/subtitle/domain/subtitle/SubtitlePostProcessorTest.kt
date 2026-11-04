package com.pyvideotrans.subtitle.domain.subtitle

import com.pyvideotrans.subtitle.domain.model.Cue
import com.pyvideotrans.subtitle.domain.model.SubtitleOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePostProcessorTest {

    private val options = SubtitleOptions()

    @Test
    fun nearbyFragmentsBecomeOneCue() {
        val result = SubtitlePostProcessor.process(
            listOf(Cue(0, 1_000, "今天"), Cue(1_100, 2_200, "天气不错")),
            options,
        )
        assertEquals(1, result.size)
        assertEquals("今天天气不错", result[0].text)
        assertEquals(0L, result[0].startMs)
        assertEquals(2_200L, result[0].endMs)
    }

    @Test
    fun chineseLineBreakInsideASegmentIsNotTreatedAsASpace() {
        val result = SubtitlePostProcessor.process(
            listOf(Cue(0, 2_000, "第一行\n第二行")),
            options,
        )
        assertEquals("第一行第二行", result.single().text)
    }

    @Test
    fun latinFragmentsJoinWithASpace() {
        val result = SubtitlePostProcessor.process(
            listOf(Cue(0, 1_000, "hello"), Cue(1_100, 2_200, "world")),
            options.copy(cjk = false, maxCharsPerLine = 42),
        )
        assertEquals("hello world", result.single().text)
    }

    @Test
    fun overlappingCuesStopOverlapping() {
        val long = "一二三四五六七八九十一二三四五六七八九十"
        val result = SubtitlePostProcessor.process(
            listOf(Cue(0, 3_000, long), Cue(2_000, 4_000, long)),
            options,
        )
        assertEquals(2, result.size)
        assertEquals(1_920L, result[0].endMs)
        assertEquals(2_000L, result[1].startMs)
    }

    @Test
    fun briefCuesLingerLongEnoughToRead() {
        val result = SubtitlePostProcessor.process(
            listOf(Cue(10_000, 10_200, "短")),
            options,
        )
        assertEquals(10_000L, result.single().startMs)
        assertEquals(10_800L, result.single().endMs)
    }

    @Test
    fun longSegmentIsCutIntoSeveralCuesAcrossItsOwnWindow() {
        val sentence = "大家好，这里是测试字幕的内容。第二句话也比较长需要分行显示哦"
        val result = SubtitlePostProcessor.process(
            listOf(Cue(0, 20_000, sentence)),
            options,
        )
        assertEquals(3, result.size)
        assertEquals(sentence, result.joinToString("") { it.text })
        assertEquals(0L, result.first().startMs)
        assertEquals(20_000L, result.last().endMs)
        assertTimeline(result)
    }

    @Test
    fun textThatNeedsThreeLinesBecomesTwoCuesInstead() {
        val text = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十"
        val result = SubtitlePostProcessor.process(
            listOf(Cue(0, 1_000, text)),
            options,
        )
        assertEquals(2, result.size)
        assertEquals(text, result.joinToString("") { it.text.replace("\n", "") })
        for (cue in result) {
            assertTrue(cue.text.lines().size <= options.maxLines)
        }
        assertTimeline(result)
    }

    @Test
    fun resegmentedCuesAreNeitherMergedNorSplit() {
        val cues = listOf(Cue(0, 3_000, "第一句字幕"), Cue(3_100, 6_400, "第二句字幕"))

        val result = SubtitlePostProcessor.processResegmented(cues, options)

        assertEquals(2, result.size)
        assertEquals("第一句字幕", result[0].text)
        assertEquals("第二句字幕", result[1].text)
        // The plain path sees a 100ms gap and welds the two sentences back together.
        assertEquals(1, SubtitlePostProcessor.process(cues, options).size)
    }

    /**
     * The resegmenter cuts where the speech stops and cannot know the reading limit, so
     * the ceiling is applied here: a shortened display window, never a reason to split
     * the sentence by counting characters again.
     */
    @Test
    fun aResegmentedCueIsNotAllowedToStayUpPastTheReadingLimit() {
        val cue = Cue(0, 12_000, "这是一句按语音切出来的长字幕，不要拆")

        val result = SubtitlePostProcessor.processResegmented(listOf(cue), options)

        assertEquals(1, result.size)
        assertEquals(options.maxDurationMs, result.single().endMs)
        assertEquals(cue.text, result.single().text.replace("\n", ""))
    }

    @Test
    fun blankAndInvertedCuesAreDropped() {
        val result = SubtitlePostProcessor.process(
            listOf(Cue(0, 1_000, "   "), Cue(5_000, 5_000, "无效")),
            options,
        )
        assertTrue(result.isEmpty())
    }

    /** Subtitles must never fight over the same millisecond of video. */
    private fun assertTimeline(cues: List<Cue>) {
        for (index in 0 until cues.lastIndex) {
            assertTrue(cues[index].startMs < cues[index].endMs)
            assertTrue(cues[index].endMs + options.gapMs <= cues[index + 1].startMs)
        }
        assertTrue(cues.last().startMs < cues.last().endMs)
    }
}
