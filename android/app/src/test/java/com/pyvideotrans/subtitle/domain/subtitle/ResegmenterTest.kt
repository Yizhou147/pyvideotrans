package com.pyvideotrans.subtitle.domain.subtitle

import com.pyvideotrans.subtitle.domain.model.SubtitleOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop `_resegment` rules, checked on synthetic token streams that cross each
 * decision threshold on purpose: punctuation past three seconds, a long silence, a
 * number that must not be cut in half, and the mobile-only character budget.
 */
class ResegmenterTest {

    private val cjkOptions = SubtitleOptions()
    private val latinOptions = SubtitleOptions(maxCharsPerLine = 42, cjk = false)

    @Test
    fun commaAfterTheMinimumDurationCutsTheCue() {
        val stream = TokenStream()
            .addMany(15, "字")
            .add("，")
            .addMany(20, "句")

        val cues = Resegmenter.resegment(stream.tokens(), cjkOptions)

        assertEquals(2, cues.size)
        assertEquals("字".repeat(15) + "，", cues[0].text)
        assertEquals(0L, cues[0].startMs)
        assertEquals(3_200L, cues[0].endMs)
        assertEquals("句".repeat(20), cues[1].text)
        assertEquals(3_200L, cues[1].startMs)
        assertEquals(7_200L, cues[1].endMs)
    }

    @Test
    fun aLongSilenceCutsEvenWithoutPunctuation() {
        val stream = TokenStream()
            .addMany(15, "字")
            .add("句", pauseMs = 500)
            .addMany(15, "句")

        val cues = Resegmenter.resegment(stream.tokens(), cjkOptions)

        assertEquals(2, cues.size)
        assertEquals(3_000L, cues[0].endMs)
        assertEquals(3_500L, cues[1].startMs)
        assertEquals("字".repeat(15), cues[0].text)
    }

    @Test
    fun theModelsOwnSegmentEndCountsAsAWeakBoundary() {
        val stream = TokenStream()
            .addMany(14, "字")
            .add("了", segmentEnd = true)
            .addMany(15, "句")

        val cues = Resegmenter.resegment(stream.tokens(), cjkOptions)

        assertEquals(2, cues.size)
        assertEquals("字".repeat(14) + "了", cues[0].text)
    }

    @Test
    fun digitsAreNeverSplitAcrossTwoCues() {
        // A 500ms silence would cut after "6"; the number continues instead.
        val stream = TokenStream()
            .addMany(15, "字")
            .add("6")
            .add("4", pauseMs = 500)
            .addMany(12, "字")

        val cues = Resegmenter.resegment(stream.tokens(), cjkOptions)

        assertEquals(1, cues.size)
        assertEquals("字".repeat(15) + "64" + "字".repeat(12), cues[0].text)
    }

    @Test
    fun overLongSpeechIsCutAtTheCharacterBudget() {
        val cues = Resegmenter.resegment(TokenStream().addMany(70, "字").tokens(), cjkOptions)

        assertEquals(2, cues.size)
        assertEquals(32, cues[0].text.length)
        assertEquals(70, cues.joinToString("") { it.text }.length)
    }

    @Test
    fun speechPastTheMaximumWindowIsCutEvenWithRoomOnScreen() {
        val wide = SubtitleOptions(maxCharsPerLine = 100, maxLines = 2)
        val cues = Resegmenter.resegment(TokenStream().addMany(60, "字").tokens(), wide)

        assertEquals(2, cues.size)
        // The cut closes the cue at the token before the one that timed it out.
        assertEquals(7_600L, cues[0].durationMs)
    }

    @Test
    fun latinTokensLoseTheBpeMarkerAndKeepTheirSpaces() {
        val stream = TokenStream()
        repeat(10) { stream.add("▁word") }

        val cues = Resegmenter.resegment(stream.tokens(), latinOptions)

        assertEquals(1, cues.size)
        assertEquals(List(10) { "word" }.joinToString(" "), cues[0].text)
        assertTrue(cues[0].text.none { it == '▁' })
    }

    @Test
    fun punctuationDoesNotGetALeadingSpaceInLatinText() {
        val stream = TokenStream()
            .add("▁Hello")
            .add("▁world")
            .add(",")
            .add("▁again")

        val cues = Resegmenter.resegment(stream.tokens(), latinOptions)

        assertEquals("Hello world, again", cues.single().text)
    }

    @Test
    fun mixedLatinInsideChineseKeepsOneSpaceAroundIt() {
        val stream = TokenStream()
            .addMany(14, "字")
            .add("▁AI")
            .add("▁mode", segmentEnd = true)
            .addMany(14, "字")

        val cues = Resegmenter.resegment(stream.tokens(), cjkOptions)

        assertTrue(cues.first().text.contains(" AI mode"))
    }

    @Test
    fun blankTokensYieldNoCues() {
        assertTrue(Resegmenter.resegment(emptyList(), cjkOptions).isEmpty())
        assertTrue(Resegmenter.resegment(listOf(AsrToken("   ", 0, 1_000)), cjkOptions).isEmpty())
    }

    /** Builds a token stream the way a recogniser does: one token after another, optionally paused. */
    private class TokenStream(private val stepMs: Long = 200L) {
        private val items = mutableListOf<AsrToken>()
        private var cursor = 0L

        fun add(text: String, pauseMs: Long = 0, segmentEnd: Boolean = false): TokenStream {
            cursor += pauseMs
            items += AsrToken(text, cursor, cursor + stepMs, segmentEnd)
            cursor += stepMs
            return this
        }

        fun addMany(count: Int, text: String): TokenStream {
            repeat(count) { add(text) }
            return this
        }

        fun tokens(): List<AsrToken> = items.toList()
    }
}
