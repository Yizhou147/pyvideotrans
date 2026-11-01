package com.pyvideotrans.subtitle.ui.home

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import com.pyvideotrans.subtitle.data.asr.QnnRemoteClient
import com.pyvideotrans.subtitle.data.asr.QnnRequest
import com.pyvideotrans.subtitle.data.asr.QnnStatus
import com.pyvideotrans.subtitle.data.asr.QnnSupport
import com.pyvideotrans.subtitle.data.asr.TranscriptionOutcome
import com.pyvideotrans.subtitle.data.asr.TranscriptionResult
import com.pyvideotrans.subtitle.data.asr.WhisperCppEngine
import com.pyvideotrans.subtitle.data.asr.WhisperNative
import com.pyvideotrans.subtitle.data.job.JobRecord
import com.pyvideotrans.subtitle.data.job.JobStore
import com.pyvideotrans.subtitle.data.media.MediaRepository
import com.pyvideotrans.subtitle.data.model.LocalModel
import com.pyvideotrans.subtitle.data.model.LocalQnnModel
import com.pyvideotrans.subtitle.data.model.ModelRepository
import com.pyvideotrans.subtitle.domain.model.Cue
import com.pyvideotrans.subtitle.domain.model.MediaInfo
import com.pyvideotrans.subtitle.domain.model.SubtitleOptions
import com.pyvideotrans.subtitle.domain.model.SubtitleStyle
import com.pyvideotrans.subtitle.domain.subtitle.AssWriter
import com.pyvideotrans.subtitle.domain.subtitle.SrtWriter
import com.pyvideotrans.subtitle.domain.subtitle.SubtitlePostProcessor
import com.pyvideotrans.subtitle.service.SubtitleService
import com.pyvideotrans.subtitle.util.LogBus
import com.pyvideotrans.subtitle.util.ZhVariants
import com.pyvideotrans.subtitle.util.formatBytes
import com.pyvideotrans.subtitle.util.formatDurationMs
import com.pyvideotrans.subtitle.util.formatTimestampMs
import com.pyvideotrans.subtitle.util.languageIsChinese
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/** Something the task produced, and where it went. */
data class OutputItem(
    val kind: String,
    val file: File,
    val savedTo: String? = null,
)

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
    val simplified: Boolean = true,
    val keepScreenOn: Boolean = true,
    val softSubs: Boolean = true,
    val outputTree: String? = null,
    val outputDirName: String = "",
    val outputs: List<OutputItem> = emptyList(),
    /** Interrupted runs whose recognition output is still on disk. */
    val pending: List<JobRecord> = emptyList(),
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

/**
 * Owns a subtitle run from picking the video to writing the files out.
 *
 * Deliberately not a ViewModel: a long video takes tens of minutes, and the system
 * recreating or reclaiming the Activity must not take the run down with it. [com.pyvideotrans.subtitle.App]
 * holds the one instance for the process, and [com.pyvideotrans.subtitle.service.SubtitleService]
 * keeps the CPU awake and shows progress while it runs.
 */
class SubtitleJob(private val app: Application) {

    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val modelRepository = ModelRepository(app)
    private val mediaRepository = MediaRepository(app)
    private val engine = WhisperCppEngine()
    private val qnnClient = QnnRemoteClient(app)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile
    private var cancelRequested = false

    private var wakeLock: PowerManager.WakeLock? = null

    init {
        refreshModels()
        refreshPending()
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

        val qnnStatus = QnnSupport.status(app)
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
                simplified = prefs.getBoolean(KEY_SIMPLIFIED, true),
                keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
                softSubs = prefs.getBoolean(KEY_SOFT_SUBS, true),
                outputTree = prefs.getString(KEY_OUTPUT_TREE, null),
                outputDirName = prefs.getString(KEY_OUTPUT_NAME, "").orEmpty(),
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

    fun setSimplified(enabled: Boolean) {
        if (_state.value.running) return
        prefs.edit().putBoolean(KEY_SIMPLIFIED, enabled).apply()
        _state.update { it.copy(simplified = enabled) }
    }

    /** Switchable mid-run: the screen may already be asleep on the desk by the time it is noticed. */
    fun setKeepScreenOn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
        _state.update { it.copy(keepScreenOn = enabled) }
    }

    fun setSoftSubs(enabled: Boolean) {
        if (_state.value.running) return
        prefs.edit().putBoolean(KEY_SOFT_SUBS, enabled).apply()
        _state.update { it.copy(softSubs = enabled) }
    }

    /** Where generated files are copied; without it they stay in the app cache. */
    fun setOutputTree(uri: Uri) {
        if (_state.value.running) return
        val name = folderLabel(uri)
        prefs.edit()
            .putString(KEY_OUTPUT_TREE, uri.toString())
            .putString(KEY_OUTPUT_NAME, name)
            .apply()
        _state.update { it.copy(outputTree = uri.toString(), outputDirName = name) }
        LogBus.info("ui", "已选择保存位置：${name.ifBlank { uri.toString() }}")
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    /**
     * Whisper writes Traditional characters for much Chinese audio, and the QNN
     * binaries cannot be biased with a prompt, so the text is normalised here the
     * way the desktop app does it. Segments that are already Simplified are left
     * alone.
     */
    private fun simplifyToHans(result: TranscriptionResult): TranscriptionResult {
        var changed = 0
        val segments = result.segments.map { segment ->
            val converted = ZhVariants.toSimplified(app, segment.text)
            if (converted == segment.text) {
                segment
            } else {
                changed++
                segment.copy(text = converted)
            }
        }
        LogBus.info(
            "subtitle",
            if (changed > 0) "繁体已转简体：$changed/${result.segments.size} 条" else "识别结果已是简体",
        )
        return result.copy(segments = segments)
    }

    /**
     * Recognition output is not a subtitle yet: the text is re-wrapped, the timeline is
     * made non-overlapping, and only then is it written out as SRT/ASS and muxed into a
     * video that carries the subtitle as a selectable track.
     */
    private suspend fun emitSubtitles(
        result: TranscriptionResult,
        videoFile: File,
        media: MediaInfo,
        workDir: File,
        baseName: String,
        chinese: Boolean,
        softSubs: Boolean,
        outputTree: Uri?,
    ): List<OutputItem> {
        val options = if (chinese) {
            SubtitleOptions()
        } else {
            SubtitleOptions(maxCharsPerLine = LATIN_CHARS_PER_LINE, cjk = false)
        }
        val cues = SubtitlePostProcessor.process(
            result.segments.map { Cue(it.t0, it.t1, it.text) },
            options,
        )
        if (cues.isEmpty()) {
            LogBus.warn("subtitle", "后处理之后没有留下可显示的字幕")
            return emptyList()
        }
        LogBus.info(
            "subtitle",
            "字幕整理：${result.segments.size} 段 → ${cues.size} 条，每行最多 ${options.maxCharsPerLine} 字",
        )
        cues.forEach { cue ->
            LogBus.info(
                "srt",
                "${formatTimestampMs(cue.startMs)} --> ${formatTimestampMs(cue.endMs)}  ${cue.text.replace("\n", " / ")}",
            )
        }

        val style = SubtitleStyle(
            playResX = media.width.takeIf { it > 0 } ?: DEFAULT_PLAY_RES_X,
            playResY = media.height.takeIf { it > 0 } ?: DEFAULT_PLAY_RES_Y,
            title = baseName,
        )
        val srtFile = File(workDir, "$baseName.srt")
        val assFile = File(workDir, "$baseName.ass")
        withContext(Dispatchers.IO) {
            SrtWriter.write(srtFile, cues)
            AssWriter.write(assFile, cues, style, options)
        }
        LogBus.info("subtitle", "已写出 ${srtFile.name} (${formatBytes(srtFile.length())})")

        val outputs = mutableListOf(
            OutputItem("字幕文件", srtFile, publishOrNull(srtFile, outputTree)),
            OutputItem("带样式的字幕", assFile, publishOrNull(assFile, outputTree)),
        )

        if (softSubs) {
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(phase = "封装软字幕（FFmpeg）") }
            val muxed = File(workDir, "$baseName.subs.mp4")
            try {
                mediaRepository.muxSoftSubtitles(videoFile, srtFile, muxed, SUBTITLE_LANGUAGE_TAG)
                outputs += OutputItem("软字幕视频", muxed, publishOrNull(muxed, outputTree))
                LogBus.info("media", "已封装软字幕视频 ${muxed.name} (${formatBytes(muxed.length())})")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // An hour of recognition must not be thrown away because one container
                // refused the mux; the subtitle files are already on disk.
                LogBus.warn(
                    "media",
                    "软字幕封装失败：${t.message ?: t.javaClass.simpleName}；字幕文件已保留，可选「烧录字幕」或手动加载 SRT",
                )
            }
        }
        return outputs
    }

    /** Saving is best effort: a read-only or revoked folder cannot undo the subtitles. */
    private suspend fun publishOrNull(source: File, tree: Uri?): String? {
        if (tree == null) return null
        return try {
            mediaRepository.publish(source, tree) { percent ->
                _state.update { it.copy(phase = "保存 ${source.name} $percent%") }
            }.toString()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            LogBus.warn("media", "保存到所选位置失败（${source.name}）：${t.message ?: t.javaClass.simpleName}")
            null
        }
    }

    /** The provider's own name for the folder; a tree URI's segment is the fallback. */
    private fun folderLabel(uri: Uri): String {
        val fromProvider = runCatching { mediaRepository.queryDisplayName(uri) }.getOrDefault("")
        if (fromProvider.isNotBlank() && fromProvider != "video.mp4") return fromProvider
        val segment = Uri.decode(uri.lastPathSegment.orEmpty())
        return segment.substringAfter(':').ifBlank { segment }
    }

    /** The name goes into FFmpeg arguments and into the file system, so strip the rest. */
    private fun baseNameOf(displayName: String): String {
        val stem = displayName.substringBeforeLast('.').ifBlank { "subtitle" }
        return stem.replace(UNSAFE_NAME, "_").trim('_').take(60).ifBlank { "subtitle" }
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
                outputs = emptyList(),
            )
        }
        beginKeepingAwake()
        LogBus.info(
            "job",
            "任务开始：后端=${snapshot.backend.label}，NPU=${qnnModel?.spec?.name ?: "无"}，GGML=${model?.displayName ?: "无"}，线程=${snapshot.threads}",
        )

        job = scope.launch {
            var jobDir: File? = null
            var copiedVideo: File? = null
            var extractedWav: File? = null
            var outputsKept = false
            try {
                if (snapshot.videoSize > 0) {
                    _state.update { it.copy(phase = "检查存储空间") }
                    val copies = if (snapshot.softSubs) 3L else 2L
                    val needed = snapshot.videoSize * copies + 300L * 1024L * 1024L
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
                copiedVideo = videoFile
                ensureActive()
                if (cancelRequested) throw CancellationException("已取消")

                _state.update { it.copy(phase = "读取视频信息") }
                val media = mediaRepository.probe(videoFile)
                if (!media.hasAudio) {
                    throw IOException("这个视频没有音轨，无法识别语音")
                }
                ensureActive()
                if (cancelRequested) throw CancellationException("已取消")

                _state.update { it.copy(phase = "抽取音频（FFmpeg）") }
                val wavFile = mediaRepository.extractAudio(videoFile, workDir)
                extractedWav = wavFile
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
                        resultPath = File(workDir, QNN_RESULT_NAME).absolutePath,
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

                val rawResult = finalOutcome.result
                val chinese = languageIsChinese(rawResult.language, snapshot.language)
                val result = if (snapshot.simplified && chinese) {
                    withContext(Dispatchers.Default) { simplifyToHans(rawResult) }
                } else {
                    rawResult
                }
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
                }

                // Archiving the recognition output is what makes the run resumable: if the
                // process dies while the files are written and muxed, the tail can be redone
                // in seconds instead of re-recognising the whole audio.
                if (result.segments.isNotEmpty()) {
                    JobStore.writeTranscription(workDir, result)?.let { transcriptionFile ->
                        JobStore.writeCheckpoint(
                            jobDir = workDir,
                            videoFile = videoFile,
                            transcriptionFile = transcriptionFile,
                            videoName = snapshot.videoName,
                            chinese = chinese,
                        )
                    }
                }

                _state.update { it.copy(phase = "生成字幕文件") }
                val outputs = if (result.segments.isEmpty()) {
                    emptyList()
                } else {
                    emitSubtitles(
                        result = result,
                        videoFile = videoFile,
                        media = media,
                        workDir = workDir,
                        baseName = baseNameOf(snapshot.videoName),
                        chinese = chinese,
                        softSubs = snapshot.softSubs,
                        outputTree = snapshot.outputTree?.let(Uri::parse),
                    )
                }
                outputsKept = outputs.isNotEmpty()

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
                    if (outputs.isEmpty()) {
                        append("\n没有生成字幕文件")
                    } else {
                        append("\n输出：${outputs.joinToString("、") { it.file.name }}")
                        append(
                            if (snapshot.outputTree == null) {
                                "\n未选择保存位置，文件暂时留在应用缓存中"
                            } else {
                                val saved = outputs.count { it.savedTo != null }
                                "\n已保存到所选位置：$saved/${outputs.size} 个文件"
                            },
                        )
                    }
                }

                _state.update {
                    it.copy(
                        phase = "完成",
                        progress = 100,
                        summary = summary,
                        outputs = outputs,
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
                // Anything the user asked for stays where it is: the share buttons and the
                // saved copies point at these paths. The video copy and the 16 kHz wav are
                // only ever inputs, and they are the gigabyte-sized part.
                jobDir?.let { dir ->
                    if (outputsKept) {
                        listOfNotNull(copiedVideo, extractedWav).forEach { it.delete() }
                        File(dir, QNN_RESULT_NAME).delete()
                        JobStore.dropCheckpoint(dir)
                    } else {
                        dir.deleteRecursively()
                    }
                }
                releaseWakeLock()
                _state.update { it.copy(running = false) }
                SubtitleService.stop(app)
                refreshPending()
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

    /**
     * Finishes a run that survived recognition but lost its process before the files were
     * written: the expensive part is already on disk, so only the tail is repeated.
     */
    fun regenerate(record: JobRecord) {
        val snapshot = _state.value
        if (snapshot.running) return
        val result = JobStore.readTranscription(record)
        if (result == null) {
            _state.update { it.copy(message = "上次的识别结果已不可用，请重新识别") }
            return
        }
        val videoFile = File(record.videoPath)
        if (!videoFile.isFile) {
            _state.update { it.copy(message = "缓存里的视频副本已被清理，请重新识别") }
            return
        }
        _state.update {
            it.copy(
                running = true,
                phase = "读取上次识别结果",
                progress = null,
                summary = null,
                message = null,
                outputs = emptyList(),
            )
        }
        beginKeepingAwake()
        LogBus.info("job", "续跑上次任务：${record.videoName}，已识别 ${result.segments.size} 段")

        job = scope.launch {
            var outputsKept = false
            try {
                _state.update { it.copy(phase = "读取视频信息") }
                val media = mediaRepository.probe(videoFile)
                val outputs = emitSubtitles(
                    result = result,
                    videoFile = videoFile,
                    media = media,
                    workDir = File(record.jobDir),
                    baseName = baseNameOf(record.videoName),
                    chinese = record.chinese,
                    softSubs = snapshot.softSubs,
                    outputTree = snapshot.outputTree?.let(Uri::parse),
                )
                outputsKept = outputs.isNotEmpty()
                val saved = outputs.count { it.savedTo != null }
                _state.update {
                    it.copy(
                        phase = "完成",
                        progress = 100,
                        summary = buildString {
                            append("用上次的识别结果重新生成：${outputs.size} 个文件")
                            if (snapshot.outputTree != null && outputs.isNotEmpty()) {
                                append("\n已保存到所选位置：$saved/${outputs.size} 个文件")
                            }
                        },
                        outputs = outputs,
                    )
                }
            } catch (ce: CancellationException) {
                LogBus.warn("job", "续跑已取消")
                _state.update { it.copy(phase = "已取消", progress = null) }
            } catch (t: Throwable) {
                LogBus.error("job", t.message ?: t.toString())
                _state.update {
                    it.copy(phase = "失败", progress = null, message = t.message ?: "生成字幕失败")
                }
            } finally {
                // On failure the checkpoint stays put: the recognition result is the whole
                // point of the card, and deleting it would force a full re-run.
                if (outputsKept) {
                    videoFile.delete()
                    JobStore.dropCheckpoint(File(record.jobDir))
                }
                releaseWakeLock()
                _state.update { it.copy(running = false) }
                SubtitleService.stop(app)
                refreshPending()
            }
        }
    }

    /** Throws away an interrupted run, its cached video included. */
    fun discardPending(record: JobRecord) {
        JobStore.remove(record)
        LogBus.info("job", "已丢弃未完成的任务：${record.videoName}")
        refreshPending()
    }

    private fun refreshPending() {
        _state.update { it.copy(pending = JobStore.pending(app.cacheDir)) }
    }

    /**
     * These two are OS privileges the device can simply refuse, and neither is worth losing a
     * run over: recognition continues either way, only the background guarantee is weaker.
     */
    private fun beginKeepingAwake() {
        runCatching { acquireWakeLock() }.onFailure {
            LogBus.warn("job", "唤醒锁未获得：${it.javaClass.name}: ${it.message}")
        }
        runCatching { SubtitleService.start(app) }.onFailure {
            LogBus.warn("job", "前台服务未启动，切后台可能被冻结：${it.javaClass.name}: ${it.message}")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = app.getSystemService(PowerManager::class.java) ?: return
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
        val activityManager = app.getSystemService(ActivityManager::class.java)
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
        val activityManager = app.getSystemService(ActivityManager::class.java)
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
        /** Where the NPU worker writes its result; it is an input, not an output. */
        const val QNN_RESULT_NAME = "qnn-result.json"

        const val PREFS_NAME = "subtitle_settings"
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_QNN_MODEL_ID = "qnn_model_id"
        const val KEY_BACKEND = "asr_backend"
        const val KEY_THREADS = "threads"
        const val KEY_LANGUAGE = "language"
        const val KEY_SIMPLIFIED = "zh_hans"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_SOFT_SUBS = "soft_subtitles"
        const val KEY_OUTPUT_TREE = "output_tree"
        const val KEY_OUTPUT_NAME = "output_tree_name"

        /** Latin text needs a wider line than 16 Chinese characters to stay readable. */
        const val LATIN_CHARS_PER_LINE = 42

        /** MP4 carries ISO 639-2/B three-letter language codes. */
        const val SUBTITLE_LANGUAGE_TAG = "zho"

        const val DEFAULT_PLAY_RES_X = 1280
        const val DEFAULT_PLAY_RES_Y = 720

        val UNSAFE_NAME = Regex("""[\\/:*?"<>|\s]+""")
        val DEFAULT_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    }
}
