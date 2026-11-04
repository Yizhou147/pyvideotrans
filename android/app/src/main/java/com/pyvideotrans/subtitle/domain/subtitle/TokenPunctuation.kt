package com.pyvideotrans.subtitle.domain.subtitle

/**
 * The NPU SenseVoice context binary reports no punctuation at all — measured on a
 * 3.5 h lecture: 24,367 characters, zero marks. That silently disables the two
 * rules [Resegmenter] prefers to cut on, leaving only "count to 32 characters" and
 * "seven seconds elapsed". A local ct-transformer model puts the marks back over
 * the whole run; this aligner hands each mark back to the token that owns the
 * character it was inserted after.
 *
 * Alignment is deliberately strict: if the model's output cannot be traced back to
 * the exact characters we fed it, [attach] returns null and the caller keeps the
 * unpunctuated tokens. A wrong mark is worse than no mark.
 */
internal object TokenPunctuation {

    /** The sentence to feed the model, and which token each of its characters came from. */
    class Plan(val text: String, val owners: IntArray)

    fun plan(tokens: List<String>, cjk: Boolean): Plan {
        val sb = StringBuilder()
        val owners = mutableListOf<Int>()
        for ((index, raw) in tokens.withIndex()) {
            val piece = AsrTokenText.displayed(raw)
            if (piece.isEmpty()) continue
            if (sb.isNotEmpty()) {
                val needsSpace = !cjk || AsrTokenText.isWordStart(raw)
                if (needsSpace) {
                    sb.append(' ')
                    owners += -1
                }
            }
            for (ch in piece) {
                sb.append(ch)
                owners += index
            }
        }
        return Plan(sb.toString(), owners.toIntArray())
    }

    /** Tokens with their mark appended, or null when the model's output does not line up. */
    fun attach(tokens: List<String>, plan: Plan, punctuated: String): List<String>? {
        if (plan.text.isEmpty() || punctuated.isEmpty()) return null
        val marks = arrayOfNulls<Char>(tokens.size)
        var position = 0
        var owner = -1
        for (ch in punctuated) {
            // A separator we inserted that the model did not echo is not a mismatch.
            while (position < plan.text.length && plan.text[position] == ' ' && ch != plan.text[position]) {
                position++
            }
            if (position < plan.text.length && ch == plan.text[position]) {
                if (plan.owners[position] >= 0) owner = plan.owners[position]
                position++
                continue
            }
            // Spaces the model adds between Latin characters carry no information.
            if (ch == ' ') continue
            if (!AsrTokenText.isMark(ch)) return null
            if (owner < 0) continue
            val body = AsrTokenText.displayed(tokens[owner])
            // A token that already ends in a mark keeps it; two marks on one
            // character read as a typo in the subtitle.
            if (body.isNotEmpty() && AsrTokenText.isMark(body[body.lastIndex])) continue
            marks[owner] = ch
        }
        return tokens.mapIndexed { index, raw -> AsrTokenText.withSuffix(raw, marks[index]?.toString().orEmpty()) }
    }
}
