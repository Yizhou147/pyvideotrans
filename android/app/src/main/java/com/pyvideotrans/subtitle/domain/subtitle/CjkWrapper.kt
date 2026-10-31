package com.pyvideotrans.subtitle.domain.subtitle

/**
 * Line wrapping for subtitle text, ported from the desktop app's
 * `videotrans/util/_srt_wrap.py:simple_wrap`. Broken the way a viewer reads the
 * line — at a punctuation mark near the width limit — rather than at a fixed
 * column, which is what cuts Chinese text in half.
 */
object CjkWrapper {

    private val FLAGS = setOf(',', '.', '?', '!', ';', '，', '。', '？', '；', '！', ' ')

    private val NEWLINE = Regex("\\r?\\n|\\\\n")

    fun wrap(text: String, maxCharsPerLine: Int, maxLines: Int, cjk: Boolean): String {
        val single = text.replace(NEWLINE, " ").trim()
        if (single.isEmpty()) return ""
        val perLine = maxCharsPerLine.coerceAtLeast(3)
        val lines = breakLines(single, perLine, cjk)
        if (lines.size <= maxLines) return lines.joinToString("\n")
        // Too many lines: widen the budget until the text fits in maxLines rather
        // than dropping content or letting a third line overflow the frame.
        val widened = ((single.length + maxLines - 1) / maxLines).coerceAtLeast(perLine)
        val retried = breakLines(single, widened, cjk)
        if (retried.size <= maxLines) return retried.joinToString("\n")
        // Still too many lines: glue the overflow together. Losing a line break is
        // acceptable, losing a space between English words is not.
        val separator = if (cjk) "" else " "
        return (retried.take(maxLines - 1) + retried.drop(maxLines - 1).joinToString(separator))
            .joinToString("\n")
    }

    private fun breakLines(text: String, maxCharsPerLine: Int, cjk: Boolean): List<String> {
        val maxlen = maxCharsPerLine.coerceAtLeast(3)
        if (text.length < maxlen + 4) return listOf(text)
        val offset = minOf(if (cjk) 2 else 8, maxlen / 2)
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            current.ensureLeadingSpaceFree()
            if (i >= text.length - offset) {
                current.append(text, i, text.length)
                break
            }
            if (current.length < maxlen - offset) {
                current.append(text[i])
                i++
                continue
            }
            if (current.length <= maxlen && isFlag(text[i])) {
                current.append(text[i])
                i++
                lines += current.toString()
                current.setLength(0)
                continue
            }
            var hit = -1
            for (ahead in 1..offset) {
                val index = i + ahead
                if (index >= text.length) break
                if (isFlag(text[index])) {
                    hit = index + 1
                    break
                }
            }
            if (hit >= 0) {
                current.append(text, i, hit)
                lines += current.toString()
                current.setLength(0)
                i = hit
                continue
            }
            current.append(text[i])
            if (current.length >= maxlen) {
                lines += current.toString()
                current.setLength(0)
            }
            i++
        }
        val tail = current.toString()
        if (tail.isNotEmpty()) {
            if (tail.length * 3 < maxlen && lines.isNotEmpty()) {
                lines[lines.lastIndex] += tail
            } else {
                lines += tail
            }
        }
        return lines.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun StringBuilder.ensureLeadingSpaceFree() {
        var start = 0
        while (start < length && this[start] == ' ') start++
        if (start > 0) delete(0, start)
    }

    private fun isFlag(ch: Char): Boolean = ch in FLAGS
}
