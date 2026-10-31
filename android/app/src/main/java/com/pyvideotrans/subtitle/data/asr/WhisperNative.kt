package com.pyvideotrans.subtitle.data.asr

import android.util.Log

object WhisperNative {

    fun interface LogSink {
        fun log(level: Int, message: String)
    }

    fun interface ProgressSink {
        fun onProgress(percent: Int)
    }

    val loadError: String? by lazy {
        try {
            System.loadLibrary("whisper_jni")
            null
        } catch (t: Throwable) {
            Log.e("WhisperNative", "Failed to load whisper_jni", t)
            t.message ?: t.toString()
        }
    }

    val isAvailable: Boolean
        get() = loadError == null

    @JvmStatic
    external fun nativeSystemInfo(): String

    @JvmStatic
    external fun nativeDevices(): String

    @JvmStatic
    external fun nativeGpuBackend(): String

    @JvmStatic
    external fun nativeInit(modelPath: String, useGpu: Boolean): Long

    @JvmStatic
    external fun nativeTranscribe(
        contextPtr: Long,
        wavPath: String,
        vadPath: String?,
        language: String,
        threads: Int,
        noContext: Boolean,
        tokenTimestamps: Boolean,
        initialPrompt: String?,
        progress: ProgressSink?,
    ): String

    @JvmStatic
    external fun nativeAbort(contextPtr: Long)

    @JvmStatic
    external fun nativeFree(contextPtr: Long)

    @JvmStatic
    external fun nativeSetLogSink(sink: LogSink?)

    fun systemInfo(): String =
        if (isAvailable) nativeSystemInfo() else "原生库未加载：$loadError"

    fun devicesInfo(): String =
        if (isAvailable) nativeDevices() else "原生库未加载：$loadError"

    fun gpuBackend(): String? {
        if (!isAvailable) return null
        return runCatching { nativeGpuBackend() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun setLogSink(sink: LogSink?) {
        if (isAvailable) {
            nativeSetLogSink(sink)
        }
    }
}
