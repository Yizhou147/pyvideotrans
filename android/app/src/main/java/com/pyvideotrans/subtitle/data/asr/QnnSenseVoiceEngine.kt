package com.pyvideotrans.subtitle.data.asr

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.pyvideotrans.subtitle.data.model.QnnSenseVoiceFiles
import com.pyvideotrans.subtitle.domain.model.SubtitleOptions
import com.pyvideotrans.subtitle.domain.subtitle.AsrToken
import com.pyvideotrans.subtitle.domain.subtitle.Resegmenter
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Runs SenseVoice through the Qualcomm QNN backend and, unlike the Whisper path,
 * hands back word-level timestamps. That is the whole point: the desktop app cuts
 * subtitles on those timestamps (`_resegment`), and without them a long VAD window
 * can only be split by counting characters.
 *
 * Restricted to the dedicated NPU process for the same reason as the Whisper
 * engine — QNN calls exit() from native code when the HTP refuses to start.
 */
class QnnSenseVoiceEngine(private val nativeLibraryDir: File) : QnnInferenceEngine {

    @Volatile
    private var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    fun transcribe(
        files: QnnSenseVoiceFiles,
        vadOnnx: File,
        wavFile: File,
        language: String,
        threads: Int,
        onProgress: (Int) -> Unit,
        onLog: (String) -> Unit,
    ): TranscriptionOutcome {
        cancelled = false
        require(files.model.isFile) { "NPU model.bin 不存在" }
        require(files.tokens.isFile) { "NPU tokens.txt 不存在" }
        require(vadOnnx.isFile) { "缺少 Silero VAD ONNX 模型，NPU 识别需要它来切分语音" }
        require(wavFile.isFile) { "音频文件不存在" }
        require(File(nativeLibraryDir, "libQnnHtp.so").isFile) { "缺少 libQnnHtp.so" }
        require(File(nativeLibraryDir, "libQnnSystem.so").isFile) { "缺少 libQnnSystem.so" }

        val senseVoiceLanguage = normalizeLanguage(language)
        val soc = QnnSoc.detect()
        val socLabel = soc?.label ?: "Qualcomm HTP"
        val archLabel = soc?.let { "HTP v${it.htpArch}" } ?: "HTP"
        onLog("NPU：$socLabel（$archLabel），加载 SenseVoice ${files.dir.name}")

        val qnnConfig = QnnConfig(
            backendLib = File(nativeLibraryDir, "libQnnHtp.so").absolutePath,
            systemLib = File(nativeLibraryDir, "libQnnSystem.so").absolutePath,
            // The bundle carries no libmodel.so; sherpa loads the QNN context
            // binary directly when model.bin already exists.
            contextBinary = files.model.absolutePath,
            htpPerfMode = true,
        )
        val modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = "",
                language = senseVoiceLanguage,
                useInverseTextNormalization = true,
                qnnConfig = qnnConfig,
            ),
            tokens = files.tokens.absolutePath,
            numThreads = threads.coerceIn(1, 8),
            debug = false,
            provider = "qnn",
        )
        val recognizerConfig = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )

        OfflineRecognizer.prependAdspLibraryPath(nativeLibraryDir.absolutePath)

        var vad: Vad? = null
        var recognizer: OfflineRecognizer? = null
        val loadStarted = SystemClock.elapsedRealtime()
        try {
            val createdRecognizer = OfflineRecognizer(assetManager = null, config = recognizerConfig)
            recognizer = createdRecognizer
            val createdVad = Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadOnnx.absolutePath,
                        // Desktop's faster-whisper VAD settings (min_silence_duration_ms=2000):
                        // a silence shorter than this stays inside one segment, so the
                        // resegmenter sees the pause and can cut the subtitle there.
                        threshold = 0.5f,
                        minSilenceDuration = 2.0f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        // The context binary has a fixed input shape; longer audio is
                        // truncated, so the window stays under the model's length.
                        maxSpeechDuration = MAX_SPEECH_SECONDS,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                ),
            )
            vad = createdVad
            val loadMs = SystemClock.elapsedRealtime() - loadStarted
            onLog("NPU 初始化完成（${loadMs}ms），开始 VAD 分段识别")

            val tokens = mutableListOf<AsrToken>()
            // VAD-chunk level results, the shape the whisper route has always produced. Kept for
            // the run in which the model withholds word timings and this one has to fall back.
            val rawSegments = ArrayList<TranscriptionSegment>()
            var decodedSegments = 0
            var silentSegments = 0
            var timestamplessSegments = 0
            var firstSpokenReported = false
            var detectedLanguage = ""
            var totalSamples = 0L
            var processedSamples = 0L
            var lastProgress = -1
            var lastMilestone = 0

            PcmWavReader(wavFile).use { reader ->
                totalSamples = reader.totalSamples
                val chunk = ShortArray(VAD_WINDOW)
                val started = SystemClock.elapsedRealtime()

                fun ensureActive() {
                    if (cancelled) throw CancellationException("NPU 识别已取消")
                }

                fun decode(samples: FloatArray, startSample: Long) {
                    ensureActive()
                    if (samples.isEmpty()) return
                    val stream = createdRecognizer.createStream()
                    try {
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        createdRecognizer.decode(stream)
                        val result = createdRecognizer.getResult(stream)
                        val lang = cleanLang(result.lang)
                        if (lang.isNotEmpty() && detectedLanguage.isEmpty()) {
                            detectedLanguage = lang
                        }
                        val baseMs = startSample * 1000L / SAMPLE_RATE
                        val chunkEndMs = (startSample + samples.size) * 1000L / SAMPLE_RATE
                        // sherpa gives one timestamp per token, counted from the start of
                        // this VAD chunk; the token after it is its end, the chunk end for
                        // the last one.
                        val texts = ArrayList<String>(result.tokens.size)
                        val startsMs = ArrayList<Long>(result.tokens.size)
                        for (index in result.tokens.indices) {
                            val text = result.tokens[index]
                            if (text.isBlank() || isSpecialToken(text) ||
                                index >= result.timestamps.size
                            ) {
                                continue
                            }
                            val startMs = baseMs + (result.timestamps[index] * 1000f).toLong()
                            texts += text
                            startsMs += startMs.coerceIn(baseMs, chunkEndMs)
                        }
                        decodedSegments += 1
                        val spoken = result.text.trim()
                        if (spoken.isNotEmpty() && !firstSpokenReported) {
                            firstSpokenReported = true
                            // The one line that tells the two failures apart from the outside.
                            onLog(
                                "首个有声段：文本 ${spoken.length} 字，" +
                                    "tokens ${result.tokens.size}，时间戳 ${result.timestamps.size}",
                            )
                        }
                        if (spoken.isNotEmpty()) {
                            rawSegments += TranscriptionSegment(
                                t0 = baseMs,
                                t1 = chunkEndMs,
                                text = spoken,
                            )
                        }
                        if (texts.isEmpty()) {
                            // Two different failures land here and they must not share a message:
                            // the model heard nothing, versus it spoke but gave no word timings.
                            if (spoken.isEmpty()) silentSegments += 1 else timestamplessSegments += 1
                            return
                        }
                        for (offset in texts.indices) {
                            val startMs = startsMs[offset]
                            val nextStart = if (offset < startsMs.lastIndex) {
                                startsMs[offset + 1]
                            } else {
                                chunkEndMs
                            }
                            tokens += AsrToken(
                                text = texts[offset],
                                startMs = startMs,
                                endMs = maxOf(nextStart, startMs + 1),
                                segmentEnd = offset == texts.lastIndex,
                            )
                        }
                    } finally {
                        stream.release()
                    }
                }

                fun drainVad() {
                    while (!createdVad.empty()) {
                        val segment = createdVad.front()
                        createdVad.pop()
                        decode(segment.samples, segment.start)
                    }
                }

                while (true) {
                    ensureActive()
                    val read = reader.readSamples(chunk)
                    if (read <= 0) break
                    val floats = FloatArray(read) { chunk[it] / 32768.0f }
                    createdVad.acceptWaveform(floats)
                    drainVad()
                    processedSamples += read
                    val percent = if (totalSamples > 0) {
                        (processedSamples * 100L / totalSamples).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    if (percent != lastProgress) {
                        lastProgress = percent
                        onProgress(percent)
                        // Hours-long runs used to look dead in the log between the first line and
                        // the last, which is how a single silent opening chunk read as a failure.
                        if (percent - lastMilestone >= 10) {
                            lastMilestone = percent / 10 * 10
                            onLog(
                                "NPU 进度 $lastMilestone%：已识别 $decodedSegments 段，" +
                                    "累计 ${tokens.size} 个字/词",
                            )
                        }
                    }
                }

                createdVad.flush()
                drainVad()
                val elapsed = SystemClock.elapsedRealtime() - started
                val audioMs = totalSamples * 1000L / SAMPLE_RATE
                onProgress(100)
                onLog(
                    "NPU 分词完成：${tokens.size} 个字/词，${decodedSegments} 段" +
                        "（静音 $silentSegments，缺时间戳 $timestamplessSegments），" +
                        "音频 ${audioMs / 1000}s，耗时 ${elapsed / 1000}s",
                )

                val finelyCut = isFinelySegmented(tokens.size, timestamplessSegments)
                val segments = if (finelyCut) {
                    val options = SubtitleOptions.forSpokenLanguage(detectedLanguage)
                    val cues = Resegmenter.resegment(tokens, options)
                    if (cues.isEmpty()) {
                        onLog("重断句后没有留下任何字幕")
                    } else {
                        val durations = cues.map { it.durationMs }
                        onLog(
                            "重断句：${cues.size} 条，最短 ${durations.minOrNull()}ms，" +
                                "最长 ${durations.maxOrNull()}ms",
                        )
                    }
                    cues.filter { it.text.isNotBlank() && it.endMs > it.startMs }
                        .sortedBy { it.startMs }
                        .map { TranscriptionSegment(t0 = it.startMs, t1 = it.endMs, text = it.text) }
                } else {
                    // Losing the word timings must not cost the user the text as well: hand the
                    // VAD chunks to the writer exactly as the whisper route always has.
                    onLog(
                        if (tokens.isEmpty()) "本轮一个字的时刻都没拿到，退回按 VAD 段输出字幕"
                        else "$timestamplessSegments 段没有字级时间戳，整轮退回按 VAD 段输出字幕",
                    )
                    rawSegments.sortedBy { it.t0 }
                }
                return TranscriptionOutcome(
                    result = TranscriptionResult(
                        language = detectedLanguage,
                        audioMs = audioMs,
                        segments = segments,
                        preSegmented = finelyCut,
                    ),
                    elapsedMs = elapsed,
                    modelLoadMs = loadMs,
                )
            }
            throw IllegalStateException("未读取到音频数据")
        } finally {
            runCatching { vad?.release() }
            runCatching { recognizer?.release() }
        }
    }

    /** SenseVoice knows these five; anything else has to stay on the Whisper route. */
    private fun normalizeLanguage(language: String): String = when (language) {
        "", "auto", "detect" -> "auto"
        "zh", "en", "ja", "ko", "yue" -> language
        else -> throw IllegalArgumentException("NPU SenseVoice 只支持中、英、日、韩、粤语，请改选 Whisper NPU 模型")
    }

    /** The detection token arrives as `<|zh|>`; the desktop strips those markers too. */
    private fun cleanLang(raw: String): String =
        raw.trim().removePrefix("<|").removeSuffix("|>").lowercase()

    private fun isSpecialToken(text: String): Boolean =
        text.startsWith("<|") || text.startsWith("[") || text.startsWith("(")

    private companion object {
        const val SAMPLE_RATE = 16000
        const val VAD_WINDOW = 512

        /** Keep below the fixed input length of the downloaded context binary (30s). */
        const val MAX_SPEECH_SECONDS = 28.0f
    }
}
