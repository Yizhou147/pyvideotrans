package com.pyvideotrans.subtitle.domain.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mark model is handed a string we built from tokens and answers with the same
 * string plus marks. Every mark has to be traceable to the token that owns the
 * character it follows; anything else must be refused rather than guessed at.
 */
class TokenPunctuationTest {

    @Test
    fun planCarriesOneCharacterPerTokenAndMarksTheLatinSpaces() {
        val plan = TokenPunctuation.plan(listOf("今天", "▁hello", "吧"), cjk = true)

        assertEquals("今天 hello吧", plan.text)
        // The inserted space belongs to no token: owners say -1.
        assertEquals(listOf(0, 0, -1, 1, 1, 1, 1, 1, 2), plan.owners.toList())
    }

    @Test
    fun aMarkLandsOnTheTokenThatOwnsTheCharacterBeforeIt() {
        val tokens = listOf("今天", "天", "气", "不", "错")
        val plan = TokenPunctuation.plan(tokens, cjk = true)
        val marked = attached(tokens, plan, "今天，天气不错。")

        assertEquals(listOf("今天，", "天", "气", "不", "错。"), marked)
    }

    /**
     * SenseVoice writes a Latin word as `▁word`; hanging a mark on it must not drop the
     * marker, or the next word loses its boundary.
     */
    @Test
    fun aMarkOnALatinWordKeepsItsWordMarker() {
        val tokens = listOf("今天", "▁hello", "世界")
        val plan = TokenPunctuation.plan(tokens, cjk = true)

        assertEquals(listOf("今天", "▁hello，", "世界"), attached(tokens, plan, "今天 hello，世界"))
    }

    /** The model puts its own spaces between Latin letters; they carry no information. */
    @Test
    fun spacesTheModelAddsAreIgnored() {
        val tokens = listOf("a", "b", "c")
        val plan = TokenPunctuation.plan(tokens, cjk = false)

        assertEquals(listOf("a", "b", "c，"), attached(tokens, plan, "a b c，"))
    }

    /** A separator we inserted may be dropped by the model without breaking alignment. */
    @Test
    fun aSeparatorTheModelDropsIsNotAMismatch() {
        val tokens = listOf("今天", "▁hello", "吧")
        val plan = TokenPunctuation.plan(tokens, cjk = true)

        assertEquals(listOf("今天", "▁hello", "吧。"), attached(tokens, plan, "今天hello吧。"))
    }

    @Test
    fun aTokenThatAlreadyEndsInAMarkKeepsIt() {
        val tokens = listOf("好，", "的")
        val plan = TokenPunctuation.plan(tokens, cjk = true)

        // The model doubled the mark it was given; one per character, never two.
        assertEquals(listOf("好，", "的"), attached(tokens, plan, "好，，的"))
    }

    /** The model rewriting a character means we can no longer say where its marks go. */
    @Test
    fun aChangedCharacterRefusesTheWholeWindow() {
        val tokens = listOf("今", "天")
        val plan = TokenPunctuation.plan(tokens, cjk = true)

        assertNull(TokenPunctuation.attach(tokens, plan, "今天气"))
        assertNull(TokenPunctuation.attach(tokens, plan, "令天"))
    }

    @Test
    fun emptyInputIsNothingToAttach() {
        val plan = TokenPunctuation.plan(emptyList(), cjk = true)

        assertTrue(plan.text.isEmpty())
        assertNull(TokenPunctuation.attach(emptyList(), plan, "。"))
    }

    private fun attached(tokens: List<String>, plan: TokenPunctuation.Plan, punctuated: String): List<String> =
        TokenPunctuation.attach(tokens, plan, punctuated) ?: error("标点回贴失败：$punctuated")
}
