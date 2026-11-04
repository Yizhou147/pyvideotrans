package com.pyvideotrans.subtitle.domain.subtitle

import com.pyvideotrans.subtitle.domain.model.Cue
import com.pyvideotrans.subtitle.domain.model.SubtitleOptions

/**
 * One recognised token with real audio time. [segmentEnd] marks the last token
 * of a model segment — the desktop app appends its `origin_end` sentinel in the
 * same spot, demoting raw ASR boundaries to a weak split point.
 */
data class AsrToken(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val segmentEnd: Boolean = false,
)

/**
 * Port of the desktop `_resegment` (`videotrans/process/_stt_utils.py`): a greedy
 * walk over tokens carrying real timestamps, cutting where speech actually stops
 * — punctuation first, silences second, duration last.
 *
 * The mobile twist the desktop does not need: a cue must also stay inside the
 * screen's character budget, so an over-capacity chunk forces a cut too. Counting
 * is the weakest reason to cut, so those cuts back off to the last clause boundary
 * and never land in the middle of a spoken number.
 */
object Resegmenter {

    /** Desktop defaults (`stt_faster.faster_whisper`): max 6000ms, min 3000ms. */
    private const val MAX_MS = 6_000L
    private const val MIN_MS = 3_000L
    /** Over-the-max window (0.3x, capped 2s) past which a cut is forced. */
    private val rcMs = (MAX_MS * 3 / 10).coerceAtMost(2_000L)
    private val midMs = (MAX_MS + rcMs) / 2

    private val STRONG_PUNC = charArrayOf('.', '?', '!', '。', '？', '！')
    private val WEAK_PUNC = charArrayOf(',', ';', ':', '，', '；', '：', '、')

    /** Sentence-final particles: the last real clause boundary before the count ran out. */
    private const val FINAL_PARTICLES = "啊呀哇吧呢吗嘛哦噢哈咯呗嗯呃啦"

    /** A boundary further back than this does not belong to the clause being cut. */
    private const val SOFT_LOOKBACK = 12

    /** Below this the head is a fragment, not a subtitle. */
    private const val MIN_SOFT_CHARS = 6

    /** How far a spoken number may overrun the budget before it is cut anyway. */
    private const val NUMBER_RUN_CEILING = 12

    private class Chunk(val start: Long) {
        val words = mutableListOf<AsrToken>()
        var end: Long = start
        var length: Int = 0

        /** [length] after each token, so a back-off can restore the head's cost. */
        var lengths = mutableListOf<Int>()

        /** Last token that closes a spoken clause, or -1. */
        var softIndex: Int = -1
    }

    /** Keeps the on-screen character budget in step with what [buildText] will emit. */
    private fun Chunk.grow(token: AsrToken, cjk: Boolean) {
        val gap = when {
            words.isEmpty() -> 0
            !cjk -> 1
            isWordStart(token.text) -> 1
            else -> 0
        }
        length += displayed(token.text).length + gap
        words += token
        lengths += length
        if (endsClause(token)) softIndex = words.lastIndex
    }

    /**
     * Cut a chunk at its last clause particle instead of at the character where the
     * budget happened to run out. Returns the remainder, ready for the next token, or
     * null when there is no usable boundary to move to.
     */
    private fun splitAtClause(chunk: Chunk, cjk: Boolean): Chunk? {
        val soft = chunk.softIndex
        if (soft < 0 || soft >= chunk.words.lastIndex) return null
        if (chunk.words.lastIndex - soft > SOFT_LOOKBACK) return null
        val headLength = chunk.lengths[soft]
        if (headLength < MIN_SOFT_CHARS) return null
        val tail = Chunk(chunk.words[soft + 1].startMs)
        for (index in soft + 1..chunk.words.lastIndex) {
            tail.grow(chunk.words[index], cjk)
        }
        chunk.end = chunk.words[soft].endMs
        chunk.words.subList(soft + 1, chunk.words.size).clear()
        chunk.length = headLength
        chunk.lengths = ArrayList(chunk.lengths.subList(0, soft + 1))
        chunk.softIndex = -1
        return tail
    }

    fun resegment(tokens: List<AsrToken>, options: SubtitleOptions): List<Cue> {
        val spoken = tokens.filter { it.text.isNotBlank() }
        if (spoken.isEmpty()) return emptyList()
        val cjk = options.cjk
        val capacity = (options.maxCharsPerLine * options.maxLines).coerceAtLeast(1)

        val cut = mutableListOf<Chunk>()
        var current: Chunk? = null
        var prevEnd: Long? = null
        var prev: AsrToken? = null

        for (token in spoken) {
            var chunk = current
            if (chunk == null) {
                chunk = Chunk(token.startMs)
                current = chunk
            }
            val futureMs = token.endMs - chunk.start
            val prevChunkEnd = prevEnd

            var shouldSplit: Boolean
            // Did counting, rather than the audio, ask for this cut? Only those cuts are
            // worth moving to a clause boundary; a pause or a mark is where speech ended.
            var counted = false
            if (futureMs >= MAX_MS + rcMs && chunk.words.isNotEmpty()) {
                // Forced cut unless this very token already ends a sentence.
                shouldSplit = !endsSentence(token, cjk)
                counted = shouldSplit
            } else {
                shouldSplit = false
                if (prev != null && prevChunkEnd != null && chunk.words.isNotEmpty()) {
                    val pauseMs = token.startMs - prevChunkEnd
                    val currentMs = prevChunkEnd - chunk.start
                    if (pauseMs > 0 && currentMs >= MIN_MS) {
                        shouldSplit = endsStrong(prev) ||
                            pauseMs >= 400 ||
                            (endsWeak(prev, cjk) && pauseMs >= 100) ||
                            (currentMs >= midMs && endsWeak(prev, cjk)) ||
                            currentMs > MAX_MS * 9 / 10
                    }
                    if (!shouldSplit && currentMs >= MIN_MS &&
                        (endsStrong(prev) || endsWeak(prev, cjk))
                    ) {
                        shouldSplit = true
                    }
                    // On desktop the digit guard only guards elastic cuts.
                    if (shouldSplit && splitsNumberRun(prev, token)) shouldSplit = false
                }
            }
            if (!shouldSplit && chunk.words.isNotEmpty()) {
                // Mobile-only screen budget, the one rule desktop never needed. A number
                // still may not be cut in half by it, but it may not stretch the cue
                // without limit either: past the ceiling the number loses a digit rather
                // than the whole line losing its shape.
                val projected = chunk.length + projectedAdd(token, cjk)
                if (projected > capacity && !splitsNumberRun(prev, token)) {
                    shouldSplit = true
                    counted = true
                } else if (projected > capacity + NUMBER_RUN_CEILING) {
                    shouldSplit = true
                    counted = true
                }
            }

            if (shouldSplit && chunk.words.isNotEmpty()) {
                val remainder = if (counted) splitAtClause(chunk, cjk) else null
                if (remainder != null) {
                    cut += chunk
                    remainder.grow(token, cjk)
                    current = remainder
                } else {
                    chunk.end = prevChunkEnd ?: token.startMs
                    cut += chunk
                    val next = Chunk(token.startMs)
                    next.grow(token, cjk)
                    current = next
                }
            } else {
                chunk.grow(token, cjk)
            }
            prevEnd = token.endMs
            prev = token
        }

        val tail = current ?: return emptyList()
        tail.end = prevEnd ?: tail.end
        if (cut.isNotEmpty() && tail.end - tail.start < MIN_MS && tail.words.isNotEmpty()) {
            val last = cut.last()
            last.words += tail.words
            last.end = tail.end
            last.length += tail.length
        } else if (tail.words.isNotEmpty()) {
            cut += tail
        }

        val merged = mutableListOf<Chunk>()
        for ((index, seg) in cut.withIndex()) {
            val duration = seg.end - seg.start
            if (merged.isEmpty() || duration >= MIN_MS) {
                merged += seg
                continue
            }
            val lastDuration = merged.last().end - merged.last().start
            if (lastDuration >= MAX_MS && index < cut.lastIndex) {
                merged += seg
                continue
            }
            merged.last().words += seg.words
            merged.last().end = seg.end
        }

        return merged.mapNotNull { seg ->
            val text = buildText(seg.words, cjk)
            if (text.isEmpty() || seg.end <= seg.start) null
            else Cue(seg.start, seg.end, text)
        }
    }

    private fun endsStrong(token: AsrToken): Boolean = endsWith(token.text, STRONG_PUNC)

    /** The model's own segment end counts as a weak boundary, as on desktop. */
    private fun endsWeak(token: AsrToken, cjk: Boolean): Boolean =
        token.segmentEnd ||
            endsWith(token.text, WEAK_PUNC) ||
            (cjk && (endsWith(token.text, ' ') || endsWith(token.text, '▁')))

    private fun endsSentence(token: AsrToken, cjk: Boolean): Boolean =
        endsStrong(token) || endsWeak(token, cjk)

    /** SenseVoice marks the start of a Latin word with the BPE separator `▁`. */
    private fun isWordStart(text: String): Boolean = AsrTokenText.isWordStart(text)

    /** A spoken clause closes on a particle even when nothing was punctuated. */
    private fun endsClause(token: AsrToken): Boolean {
        val text = displayed(token.text)
        if (text.isEmpty()) return false
        return text[text.lastIndex] in FINAL_PARTICLES
    }

    /** What a token costs once `▁` is gone, plus the separator [buildText] would add. */
    private fun projectedAdd(token: AsrToken, cjk: Boolean): Int =
        displayed(token.text).length + if (!cjk || isWordStart(token.text)) 1 else 0

    private fun displayed(text: String): String = AsrTokenText.displayed(text)

    private fun endsWith(text: String, chars: CharArray): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        return trimmed[trimmed.lastIndex] in chars ||
            (text.isNotEmpty() && text[text.lastIndex] in chars)
    }

    private fun endsWith(text: String, char: Char): Boolean = endsWith(text, charArrayOf(char))

    /** Never cut between digits: "二〇二" + "四年", "1." + "5" stay one token stream. */
    private fun splitsNumberRun(prev: AsrToken?, next: AsrToken): Boolean {
        if (prev == null) return false
        val before = prev.text.trim()
        val after = next.text.trim()
        if (before.isEmpty() || after.isEmpty()) return false
        val prevEndsNumber = before[before.lastIndex].isDigit() ||
            (before.length >= 2 && before[before.lastIndex] == '.' &&
                before[before.lastIndex - 1].isDigit())
        val nextStartsNumber = after[0].isDigit() ||
            (after.length >= 2 && after[0] == '.' && after[1].isDigit())
        return prevEndsNumber && nextStartsNumber
    }

    private fun buildText(words: List<AsrToken>, cjk: Boolean): String {
        val sb = StringBuilder()
        for (raw in words) {
            val piece = displayed(raw.text)
            if (piece.isEmpty()) continue
            when {
                sb.isEmpty() -> sb.append(piece)
                !cjk -> sb.append(' ').append(piece)
                else -> {
                    // A `▁` inside Chinese text is an embedded Latin word: keep it apart.
                    if (isWordStart(raw.text)) sb.append(' ')
                    sb.append(piece)
                }
            }
        }
        var text = sb.toString()
        if (!cjk) text = text.replace(Regex("\\s+([.,?!:;])"), "$1")
        return text.trim()
    }
}
