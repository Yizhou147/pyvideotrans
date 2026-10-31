package com.pyvideotrans.subtitle.data.asr

import android.util.Log
import java.io.File

/**
 * Native diagnostics for the NPU process. QNN failures end in `_Exit()` inside
 * sherpa-onnx, so no Java exception is ever produced; these probes capture what
 * the dynamic loader reported and keep native stderr in a file that outlives
 * the killed process.
 */
object QnnNativeProbe {

    private const val TAG = "QnnNativeProbe"

    @JvmStatic
    private external fun nativeRedirectStderr(path: String): Boolean

    @JvmStatic
    private external fun nativeProbeLibraries(paths: Array<String>): String

    val isAvailable: Boolean
        get() = WhisperNative.isAvailable

    /** Truncates [file] and points native stderr at it. Returns false if that failed. */
    fun startNativeLog(file: File): Boolean {
        if (!isAvailable) return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText("")
            nativeRedirectStderr(file.absolutePath)
        }.getOrElse {
            Log.w(TAG, "无法重定向 native stderr", it)
            false
        }
    }

    fun probe(nativeDir: File, libNames: List<String>): List<String> {
        if (!isAvailable) {
            return listOf("whisper_jni 未加载：${WhisperNative.loadError}")
        }
        return runCatching {
            val paths = libNames.map { File(nativeDir, it).absolutePath }
            nativeProbeLibraries(paths.toTypedArray())
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        }.getOrElse { t -> listOf("运行库探针失败：${t.message}") }
    }

    init {
        // The symbols live in libwhisper_jni.so; loading it is what makes the
        // external declarations resolvable in the NPU process as well.
        if (!isAvailable) {
            Log.w(TAG, "whisper_jni 不可用，NPU 诊断降级：${WhisperNative.loadError}")
        }
    }
}
