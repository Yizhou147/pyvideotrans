package com.pyvideotrans.subtitle.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyvideotrans.subtitle.data.model.LocalModel
import com.pyvideotrans.subtitle.data.model.ModelSpec
import com.pyvideotrans.subtitle.ui.components.Badge
import com.pyvideotrans.subtitle.ui.components.SectionCard
import com.pyvideotrans.subtitle.ui.components.SectionDivider
import com.pyvideotrans.subtitle.ui.theme.PtAccent
import com.pyvideotrans.subtitle.ui.theme.PtBackground
import com.pyvideotrans.subtitle.ui.theme.PtError
import com.pyvideotrans.subtitle.ui.theme.PtMuted
import com.pyvideotrans.subtitle.ui.theme.PtProgress
import com.pyvideotrans.subtitle.ui.theme.PtSurface
import com.pyvideotrans.subtitle.ui.theme.PtText
import com.pyvideotrans.subtitle.ui.theme.PtWarning
import com.pyvideotrans.subtitle.util.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    viewModel: ModelsViewModel,
    onBack: () -> Unit,
) {
    val models by viewModel.models.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val vadReady by viewModel.vadReady.collectAsState()
    val onnxVadReady by viewModel.onnxVadReady.collectAsState()
    val qnnInstalledIds by viewModel.qnnInstalledIds.collectAsState()
    val importing by viewModel.importing.collectAsState()
    val importingVad by viewModel.importingVad.collectAsState()
    val importingQnn by viewModel.importingQnn.collectAsState()
    val message by viewModel.message.collectAsState()

    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }
    val vadImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importVad(uri)
    }
    val vadOnnxImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importVadOnnx(uri)
    }
    val qnnImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importQnn(uri)
    }

    Scaffold(
        containerColor = PtBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "模型管理",
                            style = MaterialTheme.typography.titleMedium,
                            color = PtText,
                        )
                        Text(
                            text = "GGML 与 NPU 模型 · 支持断点续传",
                            style = MaterialTheme.typography.labelSmall,
                            color = PtMuted,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PtSurface,
                    titleContentColor = PtText,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            message?.let { text ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = PtAccent.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, PtAccent.copy(alpha = 0.55f)),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = text,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = PtText,
                        )
                        TextButton(onClick = viewModel::dismissMessage) { Text("关闭") }
                    }
                }
            }

            SectionCard(title = "语音识别模型") {
                Text(
                    text = "默认推荐 large-v3-turbo q5_0（12GB 内存机型）；8GB 机型建议 small 或 tiny。",
                    style = MaterialTheme.typography.bodySmall,
                    color = PtMuted,
                )
                val asrSpecs = viewModel.manifest.models.filter { it.kind == "asr" }
                asrSpecs.forEachIndexed { index, spec ->
                    if (index > 0) SectionDivider()
                    val installed = models.any { !it.custom && it.file.name == spec.fileName }
                    ManifestModelRow(
                        spec = spec,
                        installed = installed,
                        download = downloads[spec.id],
                        onDownload = { viewModel.download(spec) },
                        onCancel = { viewModel.cancelDownload(spec) },
                        onDelete = { pendingDelete = PendingDelete(spec.name) { viewModel.delete(spec) } },
                    )
                }
            }

            SectionCard(title = "NPU 模型（Qualcomm Hexagon）") {
                val status = viewModel.qnnStatus
                val soc = status.soc
                if (soc == null) {
                    Text(
                        text = status.reason(),
                        style = MaterialTheme.typography.bodySmall,
                        color = PtMuted,
                    )
                    Text(
                        text = "检测到：${status.detectedSoc}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PtMuted,
                    )
                } else {
                    Text(
                        text = "${soc.label} · HTP v${soc.htpArch}。NPU 模型按 SoC 预编译，速度通常明显快于 GPU；模型与设备不匹配时请改用 GPU/CPU。",
                        style = MaterialTheme.typography.bodySmall,
                        color = PtMuted,
                    )
                    if (!status.ready) {
                        Text(
                            text = status.reason(),
                            style = MaterialTheme.typography.bodySmall,
                            color = PtError,
                        )
                    }
                    val qnnSpecs = viewModel.manifest.models.filter {
                        it.isQnn && it.soc.equals(soc.socModel, ignoreCase = true)
                    }
                    if (qnnSpecs.isEmpty()) {
                        Text(
                            text = "模型清单中没有 ${soc.socModel} 的 NPU 模型。",
                            style = MaterialTheme.typography.bodySmall,
                            color = PtError,
                        )
                    }
                    qnnSpecs.forEachIndexed { index, spec ->
                        if (index > 0) SectionDivider()
                        val installed = qnnInstalledIds.contains(spec.id)
                        ManifestModelRow(
                            spec = spec,
                            installed = installed,
                            download = downloads[spec.id],
                            onDownload = { viewModel.download(spec) },
                            onCancel = { viewModel.cancelDownload(spec) },
                            onDelete = { pendingDelete = PendingDelete(spec.name) { viewModel.delete(spec) } },
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            qnnImporter.launch(arrayOf("application/octet-stream", "application/x-bzip2", "*/*"))
                        },
                        enabled = !importingQnn,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (importingQnn) "正在导入 NPU 模型…" else "从文件导入 NPU 模型（.tar.bz2）")
                    }
                    if (importingQnn) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = PtAccent,
                            trackColor = PtProgress.copy(alpha = 0.35f),
                        )
                    }

                    SectionDivider()
                    Text(
                        text = "NPU 识别需要 Silero VAD ONNX 来切分语音；与 whisper.cpp 使用的 GGML VAD 是两个文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = PtMuted,
                    )
                    val onnxVadSpec = viewModel.manifest.models.firstOrNull { it.isVadOnnx }
                    if (onnxVadSpec == null) {
                        Text(
                            text = "模型清单中未配置 Silero VAD ONNX。",
                            style = MaterialTheme.typography.bodySmall,
                            color = PtError,
                        )
                    } else {
                        ManifestModelRow(
                            spec = onnxVadSpec,
                            installed = onnxVadReady,
                            download = downloads[onnxVadSpec.id],
                            onDownload = { viewModel.download(onnxVadSpec) },
                            onCancel = { viewModel.cancelDownload(onnxVadSpec) },
                            onDelete = {
                                pendingDelete = PendingDelete(onnxVadSpec.name) { viewModel.delete(onnxVadSpec) }
                            },
                        )
                        OutlinedButton(
                            onClick = {
                                vadOnnxImporter.launch(
                                    arrayOf("application/octet-stream", "application/x-onnx", "*/*"),
                                )
                            },
                            enabled = !importingVad,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (importingVad) "正在导入 VAD ONNX…" else "从文件导入 Silero VAD ONNX")
                        }
                    }
                }
            }

            SectionCard(title = "静音检测（VAD）") {
                Text(
                    text = "Silero VAD 会在识别前切掉长静音，降低幻听并缩短耗时，强烈建议下载。",
                    style = MaterialTheme.typography.bodySmall,
                    color = PtMuted,
                )
                val vadSpec = viewModel.manifest.models.firstOrNull { it.isVad }
                if (vadSpec == null) {
                    Text("模型清单中未配置 VAD。", color = PtError, style = MaterialTheme.typography.bodySmall)
                } else {
                    ManifestModelRow(
                        spec = vadSpec,
                        installed = vadReady,
                        download = downloads[vadSpec.id],
                        onDownload = { viewModel.download(vadSpec) },
                        onCancel = { viewModel.cancelDownload(vadSpec) },
                        onDelete = { pendingDelete = PendingDelete(vadSpec.name) { viewModel.delete(vadSpec) } },
                    )
                    OutlinedButton(
                        onClick = {
                            vadImporter.launch(arrayOf("application/octet-stream", "application/x-binary", "*/*"))
                        },
                        enabled = !importing && !importingVad,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (importingVad) "正在导入 VAD…" else "从文件导入 VAD 模型")
                    }
                    if (importingVad) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = PtAccent,
                            trackColor = PtProgress.copy(alpha = 0.35f),
                        )
                    }
                }
            }

            SectionCard(title = "导入自定义模型") {
                Text(
                    text = "支持 whisper.cpp v1.9.5 的 GGML 格式 Whisper 模型（例如中文微调模型）。Silero VAD 请在上方 VAD 区域导入。文件会复制到应用私有目录，不上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = PtMuted,
                )
                OutlinedButton(
                    onClick = {
                        importer.launch(arrayOf("application/octet-stream", "application/x-binary", "*/*"))
                    },
                    enabled = !importing && !importingVad,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (importing) "正在导入…" else "选择 Whisper 模型文件导入")
                }
                if (importing) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = PtAccent,
                        trackColor = PtProgress.copy(alpha = 0.35f),
                    )
                }
            }

            SectionCard(title = "自定义模型") {
                val customModels = models.filter { it.custom }
                if (customModels.isEmpty()) {
                    Text(
                        text = "尚未导入自定义模型。导入后可在首页“识别设置”中选择。",
                        style = MaterialTheme.typography.bodySmall,
                        color = PtMuted,
                    )
                } else {
                    customModels.forEachIndexed { index, local ->
                        if (index > 0) SectionDivider()
                        CustomModelRow(
                            local = local,
                            onDelete = {
                                pendingDelete = PendingDelete(local.displayName) { viewModel.deleteLocal(local) }
                            },
                        )
                    }
                }
            }

            Text(
                text = "模型目录：${viewModel.modelRootPath}",
                style = MaterialTheme.typography.labelSmall,
                color = PtMuted,
            )
            Text(
                text = "下载会校验 SHA-256；任务运行期间请勿删除正在使用的模型。",
                style = MaterialTheme.typography.labelSmall,
                color = PtMuted,
            )
        }
    }

    pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除模型") },
            text = { Text("确定删除“${pending.name}”？删除后需要重新下载或导入。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending.onConfirm()
                        pendingDelete = null
                    },
                ) {
                    Text("删除", color = PtError)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ManifestModelRow(
    spec: ModelSpec,
    installed: Boolean,
    download: DownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = spec.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = PtText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (installed) {
                Badge(text = "已安装", color = PtAccent)
            } else if (download != null && download.error == null) {
                Badge(text = "下载中", color = PtWarning)
            }
        }
        Text(
            text = spec.description,
            style = MaterialTheme.typography.bodySmall,
            color = PtMuted,
            lineHeight = 17.sp,
        )
        Text(
            text = "${formatBytes(spec.sizeBytes)} · ${spec.license.ifBlank { "未知许可" }} · ${spec.fileName}",
            style = MaterialTheme.typography.labelSmall,
            color = PtMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        val activeDownload = download?.takeIf { it.error == null }
        if (activeDownload != null) {
            val fraction = activeDownload.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = PtAccent,
                    trackColor = PtProgress.copy(alpha = 0.35f),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = PtAccent,
                    trackColor = PtProgress.copy(alpha = 0.35f),
                )
            }
            Text(
                text = "${formatBytes(activeDownload.received)} / ${formatBytes(activeDownload.total)}",
                style = MaterialTheme.typography.labelSmall,
                color = PtMuted,
            )
            activeDownload.phase?.let { phase ->
                Text(
                    text = phase,
                    style = MaterialTheme.typography.labelSmall,
                    color = PtWarning,
                )
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("取消下载")
            }
        } else {
            download?.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = PtError,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (installed) {
                    OutlinedButton(onClick = onDelete) { Text("删除") }
                } else {
                    Button(onClick = onDownload) {
                        Text(if (download?.error != null) "重试下载" else "下载")
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomModelRow(local: LocalModel, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = local.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = PtText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatBytes(local.file.length()),
                style = MaterialTheme.typography.labelSmall,
                color = PtMuted,
            )
        }
        OutlinedButton(onClick = onDelete) { Text("删除") }
    }
}

private class PendingDelete(
    val name: String,
    val onConfirm: () -> Unit,
)
