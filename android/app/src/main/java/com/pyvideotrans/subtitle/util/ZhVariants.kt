package com.pyvideotrans.subtitle.util

import android.content.Context
import android.util.Log
import java.io.Reader
import java.util.Locale

/**
 * Traditional → Simplified Chinese, the same rule the desktop app applies to
 * Whisper output (`zhconv.convert(text, "zh-hans")`). Whisper writes Traditional
 * characters for a lot of Chinese audio and the QNN context binaries have no
 * prompt input to bias them, so conversion has to happen after recognition.
 *
 * Data is OpenCC's `TSPhrases` + `TSCharacters` merged into one longest-match
 * table (Apache-2.0, `assets/opencc/`). Phrases are kept even when they map to
 * themselves, because they are what stops 乾隆 from becoming 干隆.
 */
object ZhVariants {

    private const val TAG = "ZhVariants"
    private const val ASSET = "opencc/t2s.tsv"

    class Table internal constructor(
        val entries: Map<String, String>,
        val maxKeyLength: Int,
        val keyStarts: Set<Char>,
    ) {
        val size: Int get() = entries.size
    }

    @Volatile
    private var cached: Table? = null

    @Volatile
    private var loadAttempted = false

    fun toSimplified(context: Context, text: String): String {
        val table = table(context) ?: return text
        return convert(table, text)
    }

    /** Loads the dictionary once per process; returns null if the asset is unusable. */
    private fun table(context: Context): Table? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            if (loadAttempted) return null
            loadAttempted = true
            val loaded = runCatching {
                context.applicationContext.assets.open(ASSET).bufferedReader().use { parse(it) }
            }.getOrElse { t ->
                Log.w(TAG, "繁简词典不可用", t)
                LogBus.warn("subtitle", "繁简词典读取失败，将保留识别原文：${t.message}")
                return null
            }
            cached = loaded
            return loaded
        }
    }

    internal fun convert(table: Table, text: String): String {
        if (text.isEmpty()) return text
        val entries = table.entries
        val builder = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i] !in table.keyStarts) {
                builder.append(text[i])
                i++
                continue
            }
            var matched = false
            val limit = minOf(table.maxKeyLength, text.length - i)
            for (length in limit downTo 2) {
                val value = entries[text.substring(i, i + length)]
                if (value != null) {
                    builder.append(value)
                    i += length
                    matched = true
                    break
                }
            }
            if (!matched) {
                builder.append(entries[text[i].toString()] ?: text[i])
                i++
            }
        }
        return builder.toString()
    }

    internal fun parse(reader: Reader): Table {
        val entries = HashMap<String, String>(4096)
        reader.readLines().forEach { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab == line.length - 1) return@forEach
            val key = line.substring(0, tab)
            if (!entries.containsKey(key)) entries[key] = line.substring(tab + 1)
        }
        check(entries.isNotEmpty()) { "empty dictionary" }
        return Table(
            entries = entries,
            maxKeyLength = entries.keys.maxOf { it.length },
            keyStarts = entries.keys.mapTo(HashSet()) { it[0] },
        )
    }
}

/** Mirrors the desktop app: convert only when the audio was recognised as Chinese. */
internal fun languageIsChinese(resultLanguage: String, requested: String): Boolean {
    val candidate = resultLanguage.ifBlank { requested }.lowercase(Locale.ROOT)
    return candidate.startsWith("zh") || candidate == "中文"
}
