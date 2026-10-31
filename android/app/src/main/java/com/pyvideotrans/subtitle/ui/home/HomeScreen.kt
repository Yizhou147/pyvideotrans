package com.pyvideotrans.subtitle.ui.home

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyvideotrans.subtitle.ui.components.Badge
import com.pyvideotrans.subtitle.ui.components.KeyValueRow
import com.pyvideotrans.subtitle.ui.components.LogPanel
import com.pyvideotrans.subtitle.ui.components.SectionCard
import com.pyvideotrans.subtitle.ui.theme.PtAccent
import com.pyvideotrans.subtitle.ui.theme.PtBackground
import com.pyvideotrans.subtitle.ui.theme.PtBorder
import com.pyvideotrans.subtitle.ui.theme.PtError
import com.pyvideotrans.subtitle.ui.theme.PtMuted
import com.pyvideotrans.subtitle.ui.theme.PtProgress
import com.pyvideotrans.subtitle.ui.theme.PtSurface
import com.pyvideotrans.subtitle.ui.theme.PtText
import com.pyvideotrans.subtitle.ui.theme.PtWarning
import com.pyvideotrans.subtitle.util.LogBus
import com.pyvideotrans.subtitle.util.formatBytes
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenModels: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val logs by LogBus.lines.collectAsState()
    val context = LocalContext.current

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // Some providers do not grant persistable access; the cached copy is
                // made immediately, so the job can still proceed.
            }
            viewModel.setVideo(uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshModels()
    }

    Scaffold(
        containerColor = PtBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "pyvideotrans 字幕",
                            style = MaterialTheme.typography.titleMedium,
                            color = PtText,
                        )
                        Text(
                            text = "本地识别 · NPU / Vulkan GPU",
                            style = MaterialTheme.typography.labelSmall,
                            color = PtMuted,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onOpenModels) { Text("模型") }
                    TextButton(onClick = onOpenDiagnostics) { Text("诊断") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PtSurface,
                    titleContentColor = PtText,
                    actionIconContentColor = PtAccent,
                ),
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val wide = maxWidth >= 840.dp
            if (wide) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.95f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ConfigSection(
                            state = state,
                            viewModel = viewModel,
                            onPickVideo = { videoPicker.launch(VIDEO_MIME_TYPES) },
                            onOpenModels = onOpenModels,
                        )
                    }
                    LogPanel(
                        logs = logs,
                        modifier = Modifier
                            .weight(1.05f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    ConfigSection(
                        state = state,
                        viewModel = viewModel,
                        onPickVideo = { videoPicker.launch(VIDEO_MIME_TYPES) },
                        onOpenModels = onOpenModels,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LogPanel(
                        logs = logs,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigSection(
    state: HomeUiState,
    viewModel: HomeViewModel,
    onPickVideo: () -> Unit,
    onOpenModels: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        VideoCard(
            state = state,
            onPickVideo = onPickVideo,
            onClearVideo = viewModel::clearVideo,
        )
        SettingsCard(
            state = state,
            viewModel = viewModel,
            onOpenModels = onOpenModels,
        )

        if (state.running || state.phase != "待命") {
            ProgressCard(state = state, onCancel = viewModel::cancel)
        }

        state.summary?.let { summary ->
            SectionCard(title = "识别结果摘要") {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = PtText,
                    lineHeight = 18.sp,
                )
                Text(
                    text = "逐条时间轴已写入右侧日志，可直接查看。",
                    style = MaterialTheme.typography.labelSmall,
                    color = PtMuted,
                )
            }
        }

        state.message?.let { message ->
            MessageCard(message = message, onDismiss = viewModel::dismissMessage)
        }

        Text(
            text = "Phase 0 · 识别后端诊断版。自动模式按 NPU（Hexagon）→ Vulkan GPU → CPU 依次回退；SRT 导出与字幕封装在 Phase 1 提供。",
            style = MaterialTheme.typography.labelSmall,
            color = PtMuted,
        )
    }
}

@Composable
private fun VideoCard(
    state: HomeUiState,
    onPickVideo: () -> Unit,
    onClearVideo: () -> Unit,
) {
    SectionCard(title = "视频") {
        if (state.videoUri == null) {
            Text(
                text = "选择本地视频，应用会复制到私有缓存后抽取音轨；原始视频不会被修改。",
                style = MaterialTheme.typography.bodySmall,
                color = PtMuted,
            )
        } else {
            KeyValueRow(label = "文件", value = state.videoName)
            KeyValueRow(
                label = "大小",
                value = if (state.videoSize > 0) formatBytes(state.videoSize) else "未知",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickVideo, enabled = !state.running) {
                Text(if (state.videoUri == null) "选择视频" else "重新选择")
            }
            if (state.videoUri != null) {
                OutlinedButton(onClick = onClearVideo, enabled = !state.running) {
                    Text("清除")
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    state: HomeUiState,
    viewModel: HomeViewModel,
    onOpenModels: () -> Unit,
) {
    var ggmlMenuExpanded by remember { mutableStateOf(false) }

    SectionCard(title = "识别设置") {
        Column {
            Text(
                text = "识别后端",
                style = MaterialTheme.typography.bodyMedium,
                color = PtText,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AsrBackend.entries.forEach { backend ->
                    FilterChip(
                        selected = state.backend == backend,
                        onClick = { viewModel.setBackend(backend) },
                        label = { Text(backend.label) },
                        enabled = !state.running,
                    )
                }
            }
            Text(
                text = state.backend.detail,
                style = MaterialTheme.typography.labelSmall,
                color = PtMuted,
            )
        }

        if (state.backend == AsrBackend.AUTO || state.backend == AsrBackend.NPU) {
            NpuSettings(
                state = state,
                viewModel = viewModel,
                onOpenModels = onOpenModels,
            )
        }

        if (state.backend != AsrBackend.NPU) {
            Text(
                text = "GGML 模型（Vulkan / CPU）",
                style = MaterialTheme.typography.bodyMedium,
                color = PtText,
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { ggmlMenuExpanded = true },
                    enabled = !state.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = state.selectedModel?.displayName ?: "未选择 GGML 模型",
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                    )
                    Text("▾")
                }
                DropdownMenu(
                    expanded = ggmlMenuExpanded,
                    onDismissRequest = { ggmlMenuExpanded = false },
                ) {
                    if (state.models.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("暂无模型，前往模型管理下载") },
                            onClick = {
                                ggmlMenuExpanded = false
                                onOpenModels()
                            },
                        )
                    }
                    state.models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.displayName) },
                            onClick = {
                                viewModel.selectModel(model.file.absolutePath)
                                ggmlMenuExpanded = false
                            },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Badge(
                    text = if (state.vadReady) "GGML VAD 已就绪" else "GGML VAD 未下载",
                    color = if (state.vadReady) PtAccent else PtWarning,
                )
                Text(
                    text = if (state.vadReady) "自动跳过静音，减少幻听" else "未安装仍可识别，但更容易出现幻听",
                    style = MaterialTheme.typography.labelSmall,
                    color = PtMuted,
                )
            }
        }

        TextButton(
            onClick = onOpenModels,
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("管理模型 / 下载模型")
        }

        Column {
            Text(
                text = "解码线程：${state.threads}",
                style = MaterialTheme.typography.bodyMedium,
                color = PtText,
            )
            Slider(
                value = state.threads.toFloat(),
                onValueChange = { viewModel.setThreads(it.roundToInt()) },
                valueRange = 1f..8f,
                steps = 6,
                enabled = !state.running,
            )
        }

        Column {
            Text("音频语言", style = MaterialTheme.typography.bodyMedium, color = PtText)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LANGUAGE_OPTIONS.forEach { (code, label) ->
                    FilterChip(
                        selected = state.language == code,
                        onClick = { viewModel.setLanguage(code) },
                        label = { Text(label) },
                        enabled = !state.running,
                    )
                }
            }
            Text(
                text = "自动检测适合多语种；固定中文可提高中文稳定性。NPU 支持自动、中、英、日、韩。",
                style = MaterialTheme.typography.labelSmall,
                color = PtMuted,
            )
        }

        val requirement = startRequirement(state)
        Button(
            onClick = viewModel::start,
            enabled = !state.running && state.canStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(if (state.running) "处理中…" else "开始识别")
        }
        if (requirement != null && !state.running) {
            Text(
                text = requirement,
                style = MaterialTheme.typography.labelSmall,
                color = PtWarning,
            )
        }
    }
}

@Composable
private fun NpuSettings(
    state: HomeUiState,
    viewModel: HomeViewModel,
    onOpenModels: () -> Unit,
) {
    var qnnMenuExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("NPU 识别", style = MaterialTheme.typography.bodyMedium, color = PtText)
            Badge(
                text = if (state.npuReady) "就绪" else "未就绪",
                color = if (state.npuReady) PtAccent else PtWarning,
            )
        }

        val status = state.qnnStatus
        when {
            status == null -> Text(
                text = "正在检测设备…",
                style = MaterialTheme.typography.labelSmall,
                color = PtMuted,
            )
            status.soc == null -> {
                Text(
                    text = status.reason(),
                    style = MaterialTheme.typography.bodySmall,
                    color = PtError,
                )
                Text(
                    text = "已检测：${status.detectedSoc}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PtMuted,
                )
                Text(
                    text = "NPU 模式不可用时，可改用 Vulkan GPU 或 CPU 后端。",
                    style = MaterialTheme.typography.labelSmall,
                    color = PtMuted,
                )
            }
            else -> {
                Text(
                    text = "${status.soc.label} · Hexagon HTP v${status.soc.htpArch}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PtText,
                )
                if (status.missingLibs.isNotEmpty()) {
                    Text(
                        text = "缺少 NPU 运行库：${status.missingLibs.joinToString("、")}。请安装包含 NPU 运行库的完整 APK。",
                        style = MaterialTheme.typography.labelSmall,
                        color = PtError,
                    )
                }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { qnnMenuExpanded = true },
                        enabled = !state.running,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = state.selectedQnnModel?.spec?.name ?: "未安装 NPU 模型",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start,
                        )
                        Text("▾")
                    }
                    DropdownMenu(
                        expanded = qnnMenuExpanded,
                        onDismissRequest = { qnnMenuExpanded = false },
                    ) {
                        if (state.qnnModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("前往模型管理下载 NPU 模型") },
                                onClick = {
                                    qnnMenuExpanded = false
                                    onOpenModels()
                                },
                            )
                        }
                        state.qnnModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.spec.name) },
                                onClick = {
                                    viewModel.selectQnnModel(model.spec.id)
                                    qnnMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Badge(
                        text = if (state.onnxVadReady) "ONNX VAD 已就绪" else "ONNX VAD 未下载",
                        color = if (state.onnxVadReady) PtAccent else PtError,
                    )
                    Text(
                        text = if (state.onnxVadReady) "用于切分 30 秒以内的识别片段" else "NPU 模式必须安装",
                        style = MaterialTheme.typography.labelSmall,
                        color = PtMuted,
                    )
                }

                Text(
                    text = "NPU 使用预编译 Whisper context，由 Hexagon HTP 执行；自动模式下若 NPU 初始化或推理失败，会回退 Vulkan / CPU。",
                    style = MaterialTheme.typography.labelSmall,
                    color = PtMuted,
                )
            }
        }
    }
}

private fun startRequirement(state: HomeUiState): String? {
    if (state.videoUri == null) return "请先选择视频"
    if (state.running) return null
    return when (state.backend) {
        AsrBackend.NPU ->
            if (state.npuReady) null else "NPU 未就绪：需要受支持的骁龙设备、NPU 模型与 Silero VAD ONNX"
        AsrBackend.GPU, AsrBackend.CPU ->
            if (state.whisperReady) null else "请先下载并选择 GGML 模型"
        AsrBackend.AUTO ->
            if (state.npuReady || state.whisperReady) null else "请先下载 NPU 模型或 GGML 模型"
    }
}

@Composable
private fun ProgressCard(state: HomeUiState, onCancel: () -> Unit) {
    SectionCard(title = "任务状态") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.phase,
                style = MaterialTheme.typography.bodyMedium,
                color = PtText,
                modifier = Modifier.weight(1f),
            )
            state.progress?.let { progress ->
                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.labelMedium,
                    color = PtAccent,
                )
            }
        }

        val progress = state.progress
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = PtAccent,
                trackColor = PtProgress.copy(alpha = 0.35f),
            )
        } else if (state.running) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = PtAccent,
                trackColor = PtProgress.copy(alpha = 0.35f),
            )
        }

        if (state.running) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("取消任务")
            }
        }
    }
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = PtError.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, PtError.copy(alpha = 0.55f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "错误",
                    style = MaterialTheme.typography.titleSmall,
                    color = PtError,
                )
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = PtText,
            )
        }
    }
}

private val VIDEO_MIME_TYPES = arrayOf("video/*", "application/octet-stream")
private val LANGUAGE_OPTIONS = listOf(
    "auto" to "自动检测",
    "zh" to "中文",
    "en" to "英语",
    "ja" to "日语",
    "ko" to "韩语",
)
