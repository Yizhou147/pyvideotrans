package com.pyvideotrans.subtitle.data.asr

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.pyvideotrans.subtitle.data.model.QnnModelFiles
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Runs Whisper through sherpa-onnx's Qualcomm QNN backend. This class may only
 * be loaded in the dedicated NPU process: QNN errors and missing context
 * binaries call exit() inside the native library, so a crash must not take the
 * UI process down with it.
 */
class QnnWhisperEngine(private val nativeLibraryDir: File) : QnnInferenceEngine {

    @Volatile
    private var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    fun transcribe(
        files: QnnModelFiles,
        vadOnnx: File,
        wavFile: File,
        language: String,
        threads: Int,
        onProgress: (Int) -> Unit,
        onLog: (String) -> Unit,
    ): TranscriptionOutcome {
        cancelled = false
        require(files.encoder.isFile) { "NPU encoder.bin 不存在" }
        require(files.decoder.isFile) { "NPU decoder.bin 不存在" }
        require(files.tokens.isFile) { "NPU tokens.txt 不存在" }
        require(vadOnnx.isFile) { "缺少 Silero VAD ONNX 模型，NPU 识别需要它来切分语音" }
        require(wavFile.isFile) { "音频文件不存在" }
        require(File(nativeLibraryDir, "libQnnHtp.so").isFile) { "缺少 libQnnHtp.so" }
        require(File(nativeLibraryDir, "libQnnSystem.so").isFile) { "缺少 libQnnSystem.so" }

        val whisperLanguage = normalizeLanguage(language)
        val soc = QnnSoc.detect()
        val socLabel = soc?.label ?: "Qualcomm HTP"
        val archLabel = soc?.let { "HTP v${it.htpArch}" } ?: "HTP"
        onLog("NPU：$socLabel（$archLabel），加载 localcontext 模型 ${files.dir.name}")

        val qnnConfig = QnnConfig(
            backendLib = File(nativeLibraryDir, "libQnnHtp.so").absolutePath,
            systemLib = File(nativeLibraryDir, "libQnnSystem.so").absolutePath,
            contextBinary = "${files.encoder.absolutePath},${files.decoder.absolutePath}",
            htpPerfMode = true,
        )
        val whisperConfig = OfflineWhisperModelConfig(
            encoder = files.encoder.absolutePath,
            decoder = files.decoder.absolutePath,
            language = whisperLanguage,
            task = "transcribe",
            tailPaddings = 1000,
            enableTokenTimestamps = false,
            enableSegmentTimestamps = false,
            qnnConfig = qnnConfig,
        )
        val modelConfig = OfflineModelConfig(
            whisper = whisperConfig,
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
                        threshold = 0.5f,
                        minSilenceDuration = 0.35f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 28.0f,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                ),
            )
            vad = createdVad
            val loadMs = SystemClock.elapsedRealtime() - loadStarted
            onLog("NPU 初始化完成（${loadMs}ms），开始 VAD 分段识别")

            val segments = mutableListOf<TranscriptionSegment>()
            var detectedLanguage = whisperLanguage
            var totalSamples = 0L
            var processedSamples = 0L
            var lastProgress = -1

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
                        if (result.lang.isNotBlank() && detectedLanguage.isBlank()) {
                            detectedLanguage = result.lang
                        }
                        val text = cleanText(result.text)
                        if (text.isNotBlank()) {
                            val t0 = startSample * 1000L / SAMPLE_RATE
                            val t1 = (startSample + samples.size) * 1000L / SAMPLE_RATE
                            segments += TranscriptionSegment(t0 = t0, t1 = t1, text = text)
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
                    }
                }

                createdVad.flush()
                drainVad()
                val elapsed = SystemClock.elapsedRealtime() - started
                val audioMs = totalSamples * 1000L / SAMPLE_RATE
                onProgress(100)
                onLog("NPU 识别结束：${segments.size} 段，音频 ${audioMs / 1000}s，耗时 ${elapsed / 1000}s")

                val sorted = segments
                    .filter { it.text.isNotBlank() && it.t1 > it.t0 }
                    .sortedBy { it.t0 }
                return TranscriptionOutcome(
                    result = TranscriptionResult(
                        language = detectedLanguage,
                        audioMs = audioMs,
                        segments = sorted,
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

    private fun normalizeLanguage(language: String): String = when (language) {
        "", "auto", "detect" -> ""
        "zh", "en", "ja", "ko" -> language
        else -> throw IllegalArgumentException("NPU 识别暂不支持语言：$language")
    }

    private fun cleanText(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        val lower = text.lowercase()
        if (lower == "[blank_audio]" || lower == "(silence)" || lower == "[silence]") return ""
        if (text.startsWith("[") && text.endsWith("]")) return ""
        if (text.startsWith("(") && text.endsWith(")") && text.length < 24) return ""
        return text
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val VAD_WINDOW = 512
    }
}
