package com.pyvideotrans.subtitle.domain.subtitle

/**
 * How one recognised token reads once it reaches the screen, and how tokens are
 * glued back into a sentence. [Resegmenter] cuts with these rules and the
 * punctuation pass feeds the model a string built the same way, so a mark the
 * model inserts can be traced back to the token that owns the character.
 */
internal object AsrTokenText {

    /** SenseVoice marks the start of a Latin word with the BPE separator `▁`. */
    fun isWordStart(text: String): Boolean = text.trimStart().startsWith("▁")

    /** What the viewer sees of one token: no BPE marker, no surrounding spaces. */
    fun displayed(text: String): String = text.trim().removePrefix("▁").trim()

    /**
     * A token with extra text appended, keeping the BPE marker that says a Latin
     * word begins here. Used for hanging a punctuation mark on the last token of
     * a clause without losing the word boundary.
     */
    fun withSuffix(text: String, suffix: String): String {
        val body = displayed(text)
        if (suffix.isEmpty()) return text
        return if (isWordStart(text)) "▁$body$suffix" else "$body$suffix"
    }

    /** Characters the punctuation model is known to insert. */
    val MARKS = setOf('、', '，', '。', '？', '！', '；', '：', ',', '.', '?', '!', ';', ':')

    fun isMark(ch: Char): Boolean = ch in MARKS
}
