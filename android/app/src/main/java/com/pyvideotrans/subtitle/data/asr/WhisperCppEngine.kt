package com.pyvideotrans.subtitle.data.asr

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class WhisperCppEngine {

    private val json = Json { ignoreUnknownKeys = true }
    private val contextLock = Any()

    private var activeContext: Long = 0L

    suspend fun transcribe(
        modelFile: File,
        wavFile: File,
        vadFile: File?,
        language: String,
        threads: Int,
        useGpu: Boolean,
        noContext: Boolean = true,
        tokenTimestamps: Boolean = false,
        initialPrompt: String? = null,
        onProgress: (Int) -> Unit,
    ): TranscriptionOutcome = withContext(Dispatchers.IO) {
        check(WhisperNative.isAvailable) {
            "原生库未加载：${WhisperNative.loadError ?: "unknown"}"
        }
        check(modelFile.isFile) { "模型文件不存在：${modelFile.absolutePath}" }
        check(wavFile.isFile) { "音频文件不存在：${wavFile.absolutePath}" }

        val loadStarted = SystemClock.elapsedRealtime()
        val contextPtr = WhisperNative.nativeInit(modelFile.absolutePath, useGpu)
        if (contextPtr == 0L) {
            throw IllegalStateException("Whisper 模型加载失败，请确认模型是 GGML 格式且文件完整")
        }
        val modelLoadMs = SystemClock.elapsedRealtime() - loadStarted

        synchronized(contextLock) { activeContext = contextPtr }
        try {
            currentCoroutineContext().ensureActive()
            val started = SystemClock.elapsedRealtime()
            val payload = WhisperNative.nativeTranscribe(
                contextPtr = contextPtr,
                wavPath = wavFile.absolutePath,
                vadPath = vadFile?.absolutePath,
                language = language,
                threads = threads,
                noContext = noContext,
                tokenTimestamps = tokenTimestamps,
                initialPrompt = initialPrompt,
                progress = WhisperNative.ProgressSink { percent -> onProgress(percent) },
            )
            val elapsed = SystemClock.elapsedRealtime() - started
            val result = json.decodeFromString(TranscriptionResult.serializer(), payload)
            TranscriptionOutcome(
                result = result,
                elapsedMs = elapsed,
                modelLoadMs = modelLoadMs,
            )
        } finally {
            synchronized(contextLock) {
                activeContext = 0L
                WhisperNative.nativeFree(contextPtr)
            }
        }
    }

    fun abort() {
        synchronized(contextLock) {
            val contextPtr = activeContext
            if (contextPtr != 0L) {
                WhisperNative.nativeAbort(contextPtr)
            }
        }
    }
}
