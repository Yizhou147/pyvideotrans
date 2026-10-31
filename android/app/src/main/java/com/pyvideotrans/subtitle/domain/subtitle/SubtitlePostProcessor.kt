package com.pyvideotrans.subtitle.domain.subtitle

import com.pyvideotrans.subtitle.domain.model.Cue
import com.pyvideotrans.subtitle.domain.model.SubtitleOptions

/**
 * Turns raw recognition segments into readable cues: short fragments are merged,
 * over-long ones are split, overlaps are removed, and the text is wrapped the way
 * the desktop app does it.
 *
 * Pure function of time and text — no Android types, so it is unit-testable and
 * shared with the burn-in path.
 */
object SubtitlePostProcessor {

    /** Ceiling on how many pieces one segment may become, so bad input cannot explode memory. */
    private const val MAX_PARTS = 64

    /** How far back a length-based split looks for a word boundary before giving up. */
    private const val BREAK_WINDOW = 12

    private val SPACES = Regex("[ \\t]{2,}")

    fun process(cues: List<Cue>, options: SubtitleOptions): List<Cue> {
        val cleaned = cues
            .map { it.copy(text = normalize(it.text)) }
            .filter { it.text.isNotEmpty() && it.endMs > it.startMs }
            .sortedWith(compareBy({ it.startMs }, { it.endMs }))
        val bounded = mergeAdjacent(cleaned, options).flatMap { splitLong(it, options) }
        return fixTimeline(bounded, options)
            .map { it.copy(text = CjkWrapper.wrap(it.text, options.maxCharsPerLine, options.maxLines, options.cjk)) }
            .filter { it.text.isNotEmpty() }
    }

    /**
     * Collapses the hard line breaks the recogniser emits into one sentence. A Chinese
     * break is a display artefact and disappears; a Latin one was a word boundary, so a
     * space takes its place.
     */
    private fun normalize(text: String): String {
        var result = ""
        for (piece in text.split('\n', '\r')) {
            val flattened = piece.replace('\t', ' ')
            val trimmed = SPACES.replace(flattened, " ").trim()
            if (trimmed.isEmpty()) continue
            result = if (result.isEmpty()) trimmed else join(result, trimmed)
        }
        return result
    }

    private fun mergeAdjacent(cues: List<Cue>, options: SubtitleOptions): List<Cue> {
        if (cues.isEmpty()) return emptyList()
        val capacity = options.maxCharsPerLine * options.maxLines
        val result = mutableListOf(cues.first())
        for (next in cues.drop(1)) {
            val current = result.last()
            val gap = next.startMs - current.endMs
            val combined = join(current.text, next.text)
            if (gap <= options.mergeGapMs &&
                combined.length <= capacity &&
                next.endMs - current.startMs <= options.maxDurationMs
            ) {
                result[result.lastIndex] = Cue(current.startMs, next.endMs, normalize(combined))
            } else {
                result += next
            }
        }
        return result
    }

    /** Chinese text joins directly; Latin text needs a space to stay readable. */
    private fun join(first: String, second: String): String {
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first
        val a = first.last()
        val b = second.first()
        return if (isCjk(a) || isCjk(b) || a == ' ' || b == ' ') first + second else "$first $second"
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3000..0x303F || code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF ||
            code in 0xF900..0xFAFF || code in 0xFF00..0xFF60
    }

    /**
     * A segment that is either too long to read on one screen or too long to display
     * within [SubtitleOptions.maxDurationMs] becomes several cues, sharing the original
     * window in proportion to how much text each one carries.
     */
    private fun splitLong(cue: Cue, options: SubtitleOptions): List<Cue> {
        val capacity = (options.maxCharsPerLine * options.maxLines).coerceAtLeast(1)
        val parts = maxOf(
            ceilDiv(cue.durationMs, options.maxDurationMs),
            ceilDiv(cue.text.length.toLong(), capacity.toLong()),
        )
        if (parts <= 1) return listOf(cue)

        val texts = chunkEqually(cue.text, parts).filter { it.isNotBlank() }
        if (texts.size <= 1) {
            // Nothing to cut (one unbreakable word): keep the text, stop overstaying.
            return listOf(cue.copy(endMs = minOf(cue.endMs, cue.startMs + options.maxDurationMs)))
        }
        val count = texts.size
        val duration = cue.durationMs
        return texts.mapIndexed { index, text ->
            val start = cue.startMs + duration * index / count
            val end = if (index == count - 1) {
                cue.endMs
            } else {
                cue.startMs + duration * (index + 1) / count
            }
            Cue(start, maxOf(end, start + 1), text)
        }
    }

    private fun ceilDiv(value: Long, by: Long): Int {
        if (by <= 0L || value <= 0L) return 1
        return ((value + by - 1) / by).coerceAtMost(MAX_PARTS.toLong()).toInt()
    }

    /** Cuts `text` into about `parts` pieces, never in the middle of a Latin word. */
    private fun chunkEqually(text: String, parts: Int): List<String> {
        if (parts <= 1 || text.length <= parts) return listOf(text)
        val size = (text.length + parts - 1) / parts
        val chunks = ArrayList<String>(parts)
        var from = 0
        while (from < text.length) {
            val preferred = minOf(from + size, text.length)
            val cut = if (preferred < text.length) breakBefore(text, preferred) else preferred
            val usable = if (cut > from) cut else preferred
            chunks += text.substring(from, usable).trim()
            from = usable
        }
        return chunks.filter { it.isNotEmpty() }
    }

    /** Backs off from `limit` to the nearest split point, or keeps `limit` if none is near. */
    private fun breakBefore(text: String, limit: Int): Int {
        val floor = maxOf(limit - BREAK_WINDOW, 1)
        var index = limit
        while (index > floor && !isSplitPoint(text, index)) index--
        return if (isSplitPoint(text, index)) index else limit
    }

    private fun isSplitPoint(text: String, index: Int): Boolean {
        if (index <= 0 || index >= text.length) return true
        val before = text[index - 1]
        val after = text[index]
        if (before == ' ' || after == ' ') return true
        return isCjk(before) || isCjk(after)
    }

    /**
     * Makes the timeline displayable: every cue ends before the next one starts, and
     * every cue stays on screen long enough to be read.
     */
    private fun fixTimeline(cues: List<Cue>, options: SubtitleOptions): List<Cue> {
        if (cues.isEmpty()) return emptyList()
        val result = ArrayList<Cue>(cues.size)
        for ((index, cue) in cues.withIndex()) {
            val isLast = index == cues.lastIndex
            // The neighbour owns everything from its own start minus the gap onwards.
            val ceiling = if (isLast) Long.MAX_VALUE else cues[index + 1].startMs - options.gapMs
            // Backwards there is room up to the previous cue's end plus the gap.
            val floor = if (index == 0) 0L else result.last().endMs + options.gapMs

            var start = maxOf(cue.startMs, floor)
            var end = minOf(maxOf(cue.endMs, start + 1), ceiling)
            if (end - start < options.minDurationMs) {
                val missing = options.minDurationMs - (end - start)
                val forward = minOf(end + missing, ceiling)
                if (forward > end) {
                    // Linger after the speech: a subtitle that stays too long is readable,
                    // one that appears before the words are spoken looks out of sync.
                    end = forward
                } else {
                    start = maxOf(floor, start - missing)
                    end = maxOf(end, minOf(start + options.minDurationMs, ceiling))
                }
            }
            if (end <= start) end = start + 1
            result += Cue(start, end, cue.text)
        }
        return result
    }
}
