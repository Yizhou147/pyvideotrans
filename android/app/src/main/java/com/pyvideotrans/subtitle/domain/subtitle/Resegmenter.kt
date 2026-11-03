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
 * screen's character budget, so an over-capacity chunk forces a cut too.
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

    private class Chunk(val start: Long) {
        val words = mutableListOf<AsrToken>()
        var end: Long = start
        var length: Int = 0
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
            if (futureMs >= MAX_MS + rcMs && chunk.words.isNotEmpty()) {
                // Forced cut unless this very token already ends a sentence.
                shouldSplit = !endsSentence(token, cjk)
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
                // still may not be cut in half by it.
                if (chunk.length + projectedAdd(token, cjk) > capacity &&
                    !splitsNumberRun(prev, token)
                ) {
                    shouldSplit = true
                }
            }

            if (shouldSplit && chunk.words.isNotEmpty()) {
                chunk.end = prevChunkEnd ?: token.startMs
                cut += chunk
                chunk = Chunk(token.startMs)
                chunk.grow(token, cjk)
                current = chunk
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
    private fun isWordStart(text: String): Boolean = text.trimStart().startsWith("▁")

    /** What a token costs once `▁` is gone, plus the separator [buildText] would add. */
    private fun projectedAdd(token: AsrToken, cjk: Boolean): Int =
        displayed(token.text).length + if (!cjk || isWordStart(token.text)) 1 else 0

    private fun displayed(text: String): String = text.trim().removePrefix("▁").trim()

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
