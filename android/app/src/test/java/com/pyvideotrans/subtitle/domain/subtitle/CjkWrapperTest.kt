package com.pyvideotrans.subtitle.domain.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class CjkWrapperTest {

    private val sentence = "大家好，这里是测试字幕的内容。第二句话也比较长需要分行显示哦"

    @Test
    fun shortTextIsUntouched() {
        assertEquals("今天天气不错", CjkWrapper.wrap("今天天气不错", 16, 2, true))
    }

    @Test
    fun chineseBreaksAfterPunctuationNearTheLimit() {
        assertEquals(
            "大家好，这里是测试字幕的内容。\n第二句话也比较长需要分行显示哦",
            CjkWrapper.wrap(sentence, 16, 2, true),
        )
    }

    @Test
    fun hardCapKeepsTwoLinesWithoutLosingCharacters() {
        val wrapped = CjkWrapper.wrap("一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五", 16, 2, true)
        val lines = wrapped.split("\n")
        assertEquals(2, lines.size)
        assertEquals(35, wrapped.replace("\n", "").length)
    }

    @Test
    fun latinLinesBreakAtWordEdgesAndKeepTheirSpaces() {
        val source = "the quick brown fox jumps over the lazy dog today"
        val wrapped = CjkWrapper.wrap(source, 20, 2, false)
        val lines = wrapped.split("\n")
        assertEquals(2, lines.size)
        assertEquals(
            source.split(" ").sorted(),
            wrapped.replace("\n", " ").split(" ").sorted(),
        )
        assertEquals(false, wrapped.contains("  "))
    }

    @Test
    fun emptyAndWhitespaceInputYieldsEmptyOutput() {
        assertEquals("", CjkWrapper.wrap("   \n  ", 16, 2, true))
    }
}
