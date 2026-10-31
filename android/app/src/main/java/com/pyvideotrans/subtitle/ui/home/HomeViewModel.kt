package com.pyvideotrans.subtitle.ui.home

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pyvideotrans.subtitle.data.asr.QnnRemoteClient
import com.pyvideotrans.subtitle.data.asr.QnnRequest
import com.pyvideotrans.subtitle.data.asr.QnnStatus
import com.pyvideotrans.subtitle.data.asr.QnnSupport
import com.pyvideotrans.subtitle.data.asr.TranscriptionOutcome
import com.pyvideotrans.subtitle.data.asr.WhisperCppEngine
import com.pyvideotrans.subtitle.data.asr.WhisperNative
import com.pyvideotrans.subtitle.data.media.MediaRepository
import com.pyvideotrans.subtitle.data.model.LocalModel
import com.pyvideotrans.subtitle.data.model.LocalQnnModel
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

enum class AsrBackend(val label: String, val detail: String) {
    AUTO("自动", "优先 NPU，其次 Vulkan GPU，最后 CPU"),
    NPU("NPU", "Qualcomm Hexagon，使用按 SoC 编译的模型"),
    GPU("GPU", "whisper.cpp Vulkan"),
    CPU("CPU", "whisper.cpp CPU"),
}

data class HomeUiState(
    val videoUri: String? = null,
    val videoName: String = "",
    val videoSize: Long = 0L,
    val models: List<LocalModel> = emptyList(),
    val selectedModelPath: String? = null,
    val qnnModels: List<LocalQnnModel> = emptyList(),
    val selectedQnnModelId: String? = null,
    val qnnStatus: QnnStatus? = null,
    val onnxVadReady: Boolean = false,
    val backend: AsrBackend = AsrBackend.AUTO,
    val vadReady: Boolean = false,
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

    val selectedQnnModel: LocalQnnModel?
        get() = qnnModels.firstOrNull { it.spec.id == selectedQnnModelId }

    val whisperReady: Boolean
        get() = selectedModel != null && WhisperNative.isAvailable

    val npuReady: Boolean
        get() = backend != AsrBackend.GPU &&
            backend != AsrBackend.CPU &&
            qnnStatus?.ready == true &&
            selectedQnnModel != null &&
            onnxVadReady

    val canStart: Boolean
        get() = videoUri != null && when (backend) {
            AsrBackend.AUTO -> npuReady || whisperReady
            AsrBackend.NPU -> npuReady
            AsrBackend.GPU, AsrBackend.CPU -> whisperReady
        }
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelRepository = ModelRepository(application)
    private val mediaRepository = MediaRepository(application)
    private val engine = WhisperCppEngine()
    private val qnnClient = QnnRemoteClient(application)

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

        val qnnStatus = QnnSupport.status(getApplication())
        val qnnModels = qnnStatus.soc?.let { modelRepository.listQnnModels(it.socModel) }.orEmpty()
        val savedQnnId = prefs.getString(KEY_QNN_MODEL_ID, null)
        val selectedQnn = qnnModels.firstOrNull { it.spec.id == savedQnnId }
            ?: qnnModels.firstOrNull { it.spec.id == preferredQnnId(qnnModels) }
            ?: qnnModels.firstOrNull()
        if (selectedQnn != null && selectedQnn.spec.id != savedQnnId) {
            prefs.edit().putString(KEY_QNN_MODEL_ID, selectedQnn.spec.id).apply()
        }

        val backend = runCatching {
            AsrBackend.valueOf(prefs.getString(KEY_BACKEND, AsrBackend.AUTO.name).orEmpty())
        }.getOrDefault(AsrBackend.AUTO)

        _state.update {
            it.copy(
                models = models,
                selectedModelPath = selected?.file?.absolutePath,
                qnnModels = qnnModels,
                selectedQnnModelId = selectedQnn?.spec?.id,
                qnnStatus = qnnStatus,
                onnxVadReady = modelRepository.vadOnnxFile() != null,
                backend = backend,
                vadReady = modelRepository.vadFile() != null,
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

    fun selectQnnModel(id: String) {
        if (_state.value.running) return
        prefs.edit().putString(KEY_QNN_MODEL_ID, id).apply()
        _state.update { it.copy(selectedQnnModelId = id) }
    }

    fun setBackend(backend: AsrBackend) {
        if (_state.value.running) return
        prefs.edit().putString(KEY_BACKEND, backend.name).apply()
        _state.update { it.copy(backend = backend) }
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
        val qnnModel = snapshot.selectedQnnModel
        val hasWhisper = model != null && model.file.isFile && WhisperNative.isAvailable
        val hasQnn = snapshot.qnnStatus?.ready == true && qnnModel != null && snapshot.onnxVadReady

        when (snapshot.backend) {
            AsrBackend.NPU -> {
                val problem = when {
                    snapshot.qnnStatus == null || !snapshot.qnnStatus.ready ->
                        snapshot.qnnStatus?.reason() ?: "NPU 运行库未加载"
                    qnnModel == null -> "请先在模型管理中下载并选择 NPU 模型"
                    !snapshot.onnxVadReady -> "请先在模型管理中下载 Silero VAD ONNX 模型"
                    else -> null
                }
                if (problem != null) {
                    _state.update { it.copy(message = problem) }
                    return
                }
            }
            AsrBackend.GPU, AsrBackend.CPU -> {
                if (model == null) {
                    _state.update { it.copy(message = "请先下载并选择 GGML 识别模型") }
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
            }
            AsrBackend.AUTO -> {
                if (!hasWhisper && !hasQnn) {
                    val detail = snapshot.qnnStatus?.reason().orEmpty()
                    _state.update {
                        it.copy(
                            message = "请至少准备一个识别模型：下载 NPU 模型（${detail.ifBlank { "需要骁龙 8 Gen 3 及以上" }}）或 GGML 模型",
                        )
                    }
                    return
                }
            }
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
        LogBus.info(
            "job",
            "任务开始：后端=${snapshot.backend.label}，NPU=${qnnModel?.spec?.name ?: "无"}，GGML=${model?.displayName ?: "无"}，线程=${snapshot.threads}",
        )

        job = viewModelScope.launch {
            var jobDir: File? = null
            try {
                if (snapshot.videoSize > 0) {
                    _state.update { it.copy(phase = "检查存储空间") }
                    val needed = snapshot.videoSize * 2 + 300L * 1024L * 1024L
                    mediaRepository.requireFreeSpace(needed)
                }

                _state.update { it.copy(phase = "创建任务缓存") }
                val workDir = mediaRepository.createJobDir()
                jobDir = workDir

                _state.update { it.copy(phase = "复制视频", progress = null) }
                val videoFile = mediaRepository.copyToCache(
                    uri = uri,
                    displayName = snapshot.videoName.ifBlank { "video.mp4" },
                    jobDir = workDir,
                    onProgress = { percent ->
                        _state.update { it.copy(phase = "复制视频 $percent%") }
                    },
                )
                ensureActive()
                if (cancelRequested) throw CancellationException("已取消")

                _state.update { it.copy(phase = "抽取音频（FFmpeg）") }
                val wavFile = mediaRepository.extractAudio(videoFile, workDir)
                ensureActive()
                if (cancelRequested) throw CancellationException("已取消")

                val initialPrompt = if (snapshot.language == "zh") {
                    "以下是普通话的句子，请使用简体中文和标点。"
                } else {
                    null
                }
                val gpuBackend = if (hasWhisper && snapshot.backend != AsrBackend.NPU) {
                    withContext(Dispatchers.IO) { WhisperNative.gpuBackend() }
                } else {
                    null
                }

                val attempts = mutableListOf<AsrBackend>()
                fun addWhisperAttempts(preferGpu: Boolean) {
                    if (!hasWhisper) return
                    if (preferGpu && gpuBackend != null) attempts += AsrBackend.GPU
                    attempts += AsrBackend.CPU
                }
                when (snapshot.backend) {
                    AsrBackend.AUTO -> {
                        if (hasQnn) attempts += AsrBackend.NPU
                        addWhisperAttempts(preferGpu = true)
                    }
                    AsrBackend.NPU -> {
                        if (hasQnn) attempts += AsrBackend.NPU
                    }
                    AsrBackend.GPU -> addWhisperAttempts(preferGpu = true)
                    AsrBackend.CPU -> if (hasWhisper) attempts += AsrBackend.CPU
                }
                if (attempts.isEmpty()) {
                    throw IllegalStateException("没有可用的识别后端，请先下载模型")
                }
                if (snapshot.backend == AsrBackend.GPU && gpuBackend == null) {
                    LogBus.warn("asr", "未检测到可用的 Vulkan GPU 设备，本次使用 CPU")
                }
                if (hasQnn) {
                    val soc = snapshot.qnnStatus?.soc
                    LogBus.info(
                        "npu",
                        "NPU 就绪：${soc?.label ?: "Qualcomm"} · HTP v${soc?.htpArch ?: "?"} · 模型 ${qnnModel?.spec?.name}",
                    )
                } else if (snapshot.backend != AsrBackend.GPU && snapshot.backend != AsrBackend.CPU) {
                    LogBus.warn("npu", snapshot.qnnStatus?.reason() ?: "NPU 未就绪")
                }

                suspend fun runWhisper(gpu: Boolean): TranscriptionOutcome {
                    val ggml = model ?: throw IllegalStateException("没有可用的 GGML 模型")
                    return engine.transcribe(
                        modelFile = ggml.file,
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
                }

                suspend fun runQnn(): TranscriptionOutcome {
                    val local = qnnModel ?: throw IllegalStateException("没有可用的 NPU 模型")
                    val files = modelRepository.qnnFiles(local.spec)
                        ?: throw IllegalStateException("NPU 模型文件不完整，请重新下载")
                    val vadOnnx = modelRepository.vadOnnxFile()
                        ?: throw IllegalStateException("缺少 Silero VAD ONNX 模型")
                    val request = QnnRequest(
                        modelName = local.spec.name,
                        encoderPath = files.encoder.absolutePath,
                        decoderPath = files.decoder.absolutePath,
                        tokensPath = files.tokens.absolutePath,
                        vadPath = vadOnnx.absolutePath,
                        wavPath = wavFile.absolutePath,
                        resultPath = File(workDir, "qnn-result.json").absolutePath,
                        language = snapshot.language,
                        threads = snapshot.threads,
                    )
                    return qnnClient.transcribe(
                        request = request,
                        onProgress = { percent ->
                            _state.update { it.copy(progress = percent.coerceIn(0, 100)) }
                        },
                        onLog = { line -> LogBus.info("npu", line) },
                    )
                }

                var outcome: TranscriptionOutcome? = null
                var usedBackend: AsrBackend? = null
                var fallbackNote: String? = null
                var lastFailure: Throwable? = null

                for ((index, attempt) in attempts.withIndex()) {
                    if (cancelRequested) throw CancellationException("已取消")
                    try {
                        when (attempt) {
                            AsrBackend.NPU -> {
                                _state.update { it.copy(phase = "NPU 识别中", progress = 0) }
                                LogBus.info("npu", "开始 NPU 识别")
                                outcome = runQnn()
                                usedBackend = AsrBackend.NPU
                            }
                            AsrBackend.GPU -> {
                                _state.update { it.copy(phase = "Vulkan GPU 识别中", progress = 0) }
                                outcome = runWhisper(gpu = true)
                                usedBackend = AsrBackend.GPU
                            }
                            AsrBackend.CPU -> {
                                _state.update { it.copy(phase = "CPU 识别中", progress = 0) }
                                outcome = runWhisper(gpu = false)
                                usedBackend = AsrBackend.CPU
                            }
                            AsrBackend.AUTO -> Unit
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        currentCoroutineContext().ensureActive()
                        if (cancelRequested) throw CancellationException("已取消")
                        lastFailure = t
                        fallbackNote = "${attempt.label}：${t.message ?: t.javaClass.simpleName}"
                        LogBus.warn("job", "${attempt.label} 识别失败：${t.message}")
                        if (index == attempts.lastIndex) throw t
                        val next = attempts[index + 1]
                        _state.update {
                            it.copy(phase = "${attempt.label} 失败，回退 ${next.label}", progress = 0)
                        }
                    }
                    if (outcome != null) break
                }
                val finalOutcome = outcome
                    ?: lastFailure?.let { throw it }
                    ?: throw IllegalStateException("没有可用的识别后端")
                ensureActive()
                if (cancelRequested) throw CancellationException("已取消")

                val result = finalOutcome.result
                val audioMs = result.audioMs
                val rtf = if (audioMs > 0) finalOutcome.elapsedMs.toDouble() / audioMs else 0.0
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

                val backendLabel = when (usedBackend) {
                    AsrBackend.NPU -> {
                        val soc = snapshot.qnnStatus?.soc
                        "NPU（${soc?.label ?: "Qualcomm HTP"} · HTP v${soc?.htpArch ?: "?"}）"
                    }
                    AsrBackend.GPU -> "Vulkan GPU（${gpuBackend ?: "GPU"}）"
                    else -> "CPU"
                }

                val summary = buildString {
                    append("识别完成：${result.segments.size} 条字幕，音频 ")
                    append(formatDurationMs(audioMs))
                    append('\n')
                    append("语言：$languageLabel · 模型加载 ")
                    append(String.format(Locale.US, "%.1fs", finalOutcome.modelLoadMs / 1000.0))
                    append(" · 识别 ")
                    append(String.format(Locale.US, "%.1fs", finalOutcome.elapsedMs / 1000.0))
                    append(" · RTF ")
                    append(String.format(Locale.US, "%.2f", rtf))
                    append('\n')
                    append("加速：$backendLabel")
                    if (fallbackNote != null && usedBackend != attempts.firstOrNull()) {
                        append("\n回退：$fallbackNote")
                    }
                    append("\n线程 ${snapshot.threads}")
                    append("\nPhase 0 版本将识别结果显示在日志中；SRT 与字幕封装在 Phase 1 加入。")
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
                    "任务完成：${result.segments.size} 段，后端=$backendLabel，RTF=${String.format(Locale.US, "%.2f", rtf)}",
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

    private fun preferredQnnId(models: List<LocalQnnModel>): String? {
        if (models.isEmpty()) return null
        val activityManager = getApplication<Application>().getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val gib = 1024L * 1024L * 1024L
        val order = if (memoryInfo.totalMem >= 12L * gib) {
            listOf("whisper-small", "whisper-medium", "whisper-tiny")
        } else {
            listOf("whisper-tiny", "whisper-small", "whisper-medium")
        }
        return order.firstNotNullOfOrNull { suffix ->
            models.firstOrNull { it.spec.id.endsWith(suffix) }?.spec?.id
        } ?: models.first().spec.id
    }

    private companion object {
        const val PREFS_NAME = "subtitle_settings"
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_QNN_MODEL_ID = "qnn_model_id"
        const val KEY_BACKEND = "asr_backend"
        const val KEY_THREADS = "threads"
        const val KEY_LANGUAGE = "language"
        val DEFAULT_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
