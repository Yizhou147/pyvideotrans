package com.pyvideotrans.subtitle.ui.home

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pyvideotrans.subtitle.data.asr.WhisperCppEngine
import com.pyvideotrans.subtitle.data.asr.WhisperNative
import com.pyvideotrans.subtitle.data.media.MediaRepository
import com.pyvideotrans.subtitle.data.model.LocalModel
import com.pyvideotrans.subtitle.data.model.ModelRepository
import com.pyvideotrans.subtitle.util.LogBus
import com.pyvideotrans.subtitle.util.formatDurationMs
import com.pyvideotrans.subtitle.util.formatTimestampMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class HomeUiState(
    val videoUri: String? = null,
    val videoName: String = "",
    val videoSize: Long = 0L,
    val models: List<LocalModel> = emptyList(),
    val selectedModelPath: String? = null,
    val vadReady: Boolean = false,
    val useGpu: Boolean = true,
    val threads: Int = 4,
    val language: String = "auto",
    val running: Boolean = false,
    val phase: String = "待命",
    val progress: Int? = null,
    val summary: String? = null,
    val message: String? = null,
) {
    val selectedModel: LocalModel?
        get() = models.firstOrNull { it.file.absolutePath == selectedModelPath }
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelRepository = ModelRepository(application)
    private val mediaRepository = MediaRepository(application)
    private val engine = WhisperCppEngine()

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var cancelRequested = false

    private var wakeLock: PowerManager.WakeLock? = null

    init {
        refreshModels()
    }

    fun refreshModels() {
        val models = modelRepository.listAsrModels()
        val savedPath = prefs.getString(KEY_MODEL_PATH, null)
        val selected = models.firstOrNull { it.file.absolutePath == savedPath }
            ?: models.firstOrNull { it.file.name == defaultModelFileName() }
            ?: models.firstOrNull()
        if (selected != null && selected.file.absolutePath != savedPath) {
            prefs.edit().putString(KEY_MODEL_PATH, selected.file.absolutePath).apply()
        }
        _state.update {
            it.copy(
                models = models,
                selectedModelPath = selected?.file?.absolutePath,
                vadReady = modelRepository.vadFile() != null,
                useGpu = prefs.getBoolean(KEY_USE_GPU, true),
                threads = prefs.getInt(KEY_THREADS, DEFAULT_THREADS).coerceIn(1, 8),
                language = prefs.getString(KEY_LANGUAGE, "auto") ?: "auto",
            )
        }
    }

    fun setVideo(uri: Uri) {
        val name = mediaRepository.queryDisplayName(uri)
        val size = mediaRepository.querySize(uri)
        _state.update {
            it.copy(
                videoUri = uri.toString(),
                videoName = name,
                videoSize = size,
                summary = null,
                message = null,
            )
        }
        LogBus.info("ui", "已选择视频：$name (${size} bytes)")
    }

    fun clearVideo() {
        if (_state.value.running) return
        _state.update { it.copy(videoUri = null, videoName = "", videoSize = 0L, summary = null) }
    }

    fun selectModel(path: String) {
        if (_state.value.running) return
        prefs.edit().putString(KEY_MODEL_PATH, path).apply()
        _state.update { it.copy(selectedModelPath = path) }
    }

    fun setUseGpu(enabled: Boolean) {
        if (_state.value.running) return
        prefs.edit().putBoolean(KEY_USE_GPU, enabled).apply()
        _state.update { it.copy(useGpu = enabled) }
    }

    fun setThreads(value: Int) {
        if (_state.value.running) return
        val threads = value.coerceIn(1, 8)
        prefs.edit().putInt(KEY_THREADS, threads).apply()
        _state.update { it.copy(threads = threads) }
    }

    fun setLanguage(language: String) {
        if (_state.value.running) return
        prefs.edit().putString(KEY_LANGUAGE, language).apply()
        _state.update { it.copy(language = language) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun start() {
        if (_state.value.running) return
        val snapshot = _state.value
        val uri = snapshot.videoUri?.let(Uri::parse)
        if (uri == null) {
            _state.update { it.copy(message = "请先选择视频") }
            return
        }
        val model = snapshot.selectedModel
        if (model == null) {
            _state.update { it.copy(message = "请先下载并选择识别模型") }
            return
        }
        if (!model.file.isFile) {
            _state.update { it.copy(message = "模型文件不存在，请重新下载") }
            return
        }
        if (!WhisperNative.isAvailable) {
            _state.update { it.copy(message = "原生库未加载：${WhisperNative.loadError}") }
            return
        }

        cancelRequested = false
        _state.update {
            it.copy(
                running = true,
                phase = "准备任务",
                progress = null,
                summary = null,
                message = null,
            )
        }
        acquireWakeLock()
        LogBus.info("job", "任务开始：模型=${model.displayName}，GPU=${snapshot.useGpu}，线程=${snapshot.threads}")

        job = viewModelScope.launch {
            var jobDir: File? = null
            try {
                if (snapshot.videoSize > 0) {
                    _state.update { it.copy(phase = "检查存储空间") }
                    val needed = snapshot.videoSize * 2 + 300L * 1024L * 1024L
                    mediaRepository.requireFreeSpace(needed)
                }

                _state.update { it.copy(phase = "创建任务缓存") }
                jobDir = mediaRepository.createJobDir()

                _state.update { it.copy(phase = "复制视频", progress = null) }
                val videoFile = mediaRepository.copyToCache(
                    uri = uri,
                    displayName = snapshot.videoName.ifBlank { "video.mp4" },
                    jobDir = jobDir,
                    onProgress = { percent ->
                        _state.update { it.copy(phase = "复制视频 $percent%") }
                    },
                )
                ensureActive()
                if (cancelRequested) throw CancellationException("已取消")

                _state.update { it.copy(phase = "抽取音频（FFmpeg）") }
                val wavFile = mediaRepository.extractAudio(videoFile, jobDir)
                ensureActive()
                if (cancelRequested) throw CancellationException("已取消")

                _state.update { it.copy(phase = "加载模型 / 识别中", progress = 0) }
                val initialPrompt = if (snapshot.language == "zh") {
                    "以下是普通话的句子，请使用简体中文和标点。"
                } else {
                    null
                }
                var usedGpu = snapshot.useGpu
                var gpuFallbackReason: String? = null
                val gpuBackend = if (usedGpu) {
                    withContext(Dispatchers.IO) { WhisperNative.gpuBackend() }
                } else {
                    null
                }
                if (usedGpu && gpuBackend == null) {
                    LogBus.warn("asr", "未检测到可用的 Vulkan GPU 设备，本次使用 CPU")
                    usedGpu = false
                }
                suspend fun runTranscribe(gpu: Boolean) = engine.transcribe(
                    modelFile = model.file,
                    wavFile = wavFile,
                    vadFile = modelRepository.vadFile(),
                    language = snapshot.language,
                    threads = snapshot.threads,
                    useGpu = gpu,
                    noContext = true,
                    tokenTimestamps = false,
                    initialPrompt = initialPrompt,
                    onProgress = { percent ->
                        _state.update { it.copy(progress = percent.coerceIn(0, 100)) }
                    },
                )
                val outcome = try {
                    runTranscribe(usedGpu)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    currentCoroutineContext().ensureActive()
                    if (!usedGpu) throw t
                    gpuFallbackReason = t.message ?: t.toString()
                    LogBus.warn("asr", "GPU 识别失败，回退 CPU 重试：$gpuFallbackReason")
                    usedGpu = false
                    _state.update { it.copy(phase = "GPU 失败，回退 CPU 识别", progress = 0) }
                    runTranscribe(false)
                }
                ensureActive()
                if (cancelRequested) throw CancellationException("已取消")

                val result = outcome.result
                val audioMs = result.audioMs
                val rtf = if (audioMs > 0) outcome.elapsedMs.toDouble() / audioMs else 0.0
                val languageLabel = when (result.language) {
                    "zh" -> "中文"
                    "en" -> "英语"
                    "ja" -> "日语"
                    "ko" -> "韩语"
                    "" -> "未检测"
                    else -> result.language
                }

                if (result.segments.isEmpty()) {
                    LogBus.warn("asr", "没有识别到语音内容")
                } else {
                    LogBus.info("asr", "识别结果（${result.segments.size} 条，语言=$languageLabel）")
                    result.segments.forEach { segment ->
                        LogBus.info(
                            "srt",
                            "${formatTimestampMs(segment.t0)} --> ${formatTimestampMs(segment.t1)}  ${segment.text.trim()}",
                        )
                    }
                }

                val summary = buildString {
                    append("识别完成：${result.segments.size} 条字幕，音频 ")
                    append(formatDurationMs(audioMs))
                    append('\n')
                    append("语言：$languageLabel · 模型加载 ")
                    append(String.format(Locale.US, "%.1fs", outcome.modelLoadMs / 1000.0))
                    append(" · 识别 ")
                    append(String.format(Locale.US, "%.1fs", outcome.elapsedMs / 1000.0))
                    append(" · RTF ")
                    append(String.format(Locale.US, "%.2f", rtf))
                    append('\n')
                    append("加速：")
                    append(
                        when {
                            usedGpu -> "Vulkan GPU（$gpuBackend）"
                            snapshot.useGpu && gpuFallbackReason != null -> "已回退 CPU（GPU 初始化失败）"
                            snapshot.useGpu -> "CPU（未检测到 Vulkan GPU 设备）"
                            else -> "CPU"
                        },
                    )
                    append(" · 线程 ${snapshot.threads}\n")
                    append("Phase 0 版本将识别结果显示在日志中；SRT 与字幕封装在 Phase 1 加入。")
                }

                _state.update {
                    it.copy(
                        phase = "完成",
                        progress = 100,
                        summary = summary,
                    )
                }
                LogBus.info(
                    "job",
                    "任务完成：${result.segments.size} 段，RTF=${String.format(Locale.US, "%.2f", rtf)}",
                )
            } catch (ce: CancellationException) {
                LogBus.warn("job", "任务已取消")
                _state.update {
                    it.copy(
                        phase = "已取消",
                        progress = null,
                    )
                }
            } catch (t: Throwable) {
                if (cancelRequested) {
                    // The native/FFmpeg call cannot be interrupted mid-flight, so it may
                    // surface as an ordinary exception after cancellation was requested.
                    LogBus.warn("job", "任务已取消")
                    _state.update {
                        it.copy(
                            phase = "已取消",
                            progress = null,
                        )
                    }
                } else {
                    LogBus.error("job", t.message ?: t.toString())
                    _state.update {
                        it.copy(
                            phase = "失败",
                            progress = null,
                            message = t.message ?: "任务失败",
                        )
                    }
                }
            } finally {
                releaseWakeLock()
                jobDir?.deleteRecursively()
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun cancel() {
        if (!_state.value.running) return
        cancelRequested = true
        LogBus.warn("job", "正在取消任务…")
        _state.update { it.copy(phase = "正在取消…") }
        mediaRepository.cancelActive()
        engine.abort()
        job?.cancel()
    }

    override fun onCleared() {
        cancelRequested = true
        engine.abort()
        mediaRepository.cancelActive()
        job?.cancel()
        releaseWakeLock()
        super.onCleared()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getApplication<Application>().getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "pyvideotrans:subtitle-job",
        ).apply {
            setReferenceCounted(false)
            acquire(3L * 60L * 60L * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun defaultModelFileName(): String {
        val activityManager = getApplication<Application>().getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val gib = 1024L * 1024L * 1024L
        return when {
            memoryInfo.totalMem < 6L * gib -> "ggml-tiny.bin"
            memoryInfo.totalMem < 10L * gib -> "ggml-small.bin"
            else -> "ggml-large-v3-turbo-q5_0.bin"
        }
    }

    private companion object {
        const val PREFS_NAME = "subtitle_settings"
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_USE_GPU = "use_gpu"
        const val KEY_THREADS = "threads"
        const val KEY_LANGUAGE = "language"
        val DEFAULT_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
