package com.pyvideotrans.subtitle

import android.app.Application
import android.os.Build
import com.pyvideotrans.subtitle.data.asr.WhisperNative
import com.pyvideotrans.subtitle.ui.home.SubtitleJob
import com.pyvideotrans.subtitle.util.LogBus
import com.pyvideotrans.subtitle.util.LogLevel

class App : Application() {

    /**
     * The one job controller for the process. Held here rather than in a ViewModel so a run
     * keeps going when the system recreates or reclaims the Activity.
     */
    val subtitleJob: SubtitleJob by lazy { SubtitleJob(this) }

    override fun onCreate() {
        super.onCreate()

        // The NPU worker process must not load whisper.cpp/Vulkan in addition
        // to sherpa-onnx: that wastes memory and can conflict with QNN.
        if (Application.getProcessName()?.endsWith(":npu") == true) {
            return
        }

        if (WhisperNative.isAvailable) {
            WhisperNative.setLogSink { level, message ->
                val logLevel = when (level) {
                    4 -> LogLevel.ERROR
                    3 -> LogLevel.WARN
                    1 -> LogLevel.DEBUG
                    else -> LogLevel.INFO
                }
                message.lineSequence()
                    .filter { it.isNotBlank() }
                    .forEach { line -> LogBus.append(logLevel, "whisper", line.trimEnd()) }
            }
        } else {
            LogBus.error("native", "whisper_jni 加载失败：${WhisperNative.loadError}")
        }

        LogBus.info(
            "app",
            "启动 ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ABI=${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}",
        )
        LogBus.info(
            "app",
            "构建 ${BuildConfig.BUILD_ID} · whisper.cpp ${BuildConfig.WHISPER_CPP_VERSION} · " +
                "FFmpegKit ${BuildConfig.FFMPEG_KIT_VERSION} · sherpa ${BuildConfig.SHERPA_ONNX_COMMIT} · " +
                "QNN ${BuildConfig.QNN_VERSION}",
        )
    }
}
