package com.pyvideotrans.subtitle.ui.diagnostics

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyvideotrans.subtitle.BuildConfig
import com.pyvideotrans.subtitle.data.asr.WhisperNative
import com.pyvideotrans.subtitle.ui.components.KeyValueRow
import com.pyvideotrans.subtitle.ui.components.LogPanel
import com.pyvideotrans.subtitle.ui.components.SectionCard
import com.pyvideotrans.subtitle.ui.theme.PtAccent
import com.pyvideotrans.subtitle.ui.theme.PtBackground
import com.pyvideotrans.subtitle.ui.theme.PtError
import com.pyvideotrans.subtitle.ui.theme.PtMuted
import com.pyvideotrans.subtitle.ui.theme.PtSurface
import com.pyvideotrans.subtitle.ui.theme.PtText
import com.pyvideotrans.subtitle.ui.theme.PtWarning
import com.pyvideotrans.subtitle.util.LogBus
import com.pyvideotrans.subtitle.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val logs by LogBus.lines.collectAsState()
    val packageManager = context.packageManager
    val activityManager = context.getSystemService(ActivityManager::class.java)

    val memory = remember {
        ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
    }
    val pageSize = remember {
        runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(-1L)
    }
    val freeBytes = remember {
        runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
    }

    val vulkanBase = packageManager.hasSystemFeature(VULKAN_FEATURE)
    val vulkan13 = packageManager.hasSystemFeature(VULKAN_FEATURE, 0x00403000)
    val vulkanCompute = runCatching {
        packageManager.hasSystemFeature("android.hardware.vulkan.compute")
    }.getOrDefault(false)

    val nativeProbe by produceState(initialValue = NativeProbe()) {
        value = withContext(Dispatchers.IO) {
            NativeProbe(
                info = WhisperNative.systemInfo(),
                devices = WhisperNative.devicesInfo(),
            )
        }
    }
    val nativeInfo = nativeProbe.info
    val nativeDevices = nativeProbe.devices

    val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
    } else {
        Build.HARDWARE
    }
    val pageLabel = when (pageSize) {
        16384L -> "16384 B · 16 KB 页设备"
        4096L -> "4096 B · 4 KB 页设备"
        else -> "$pageSize B"
    }

    Scaffold(
        containerColor = PtBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "诊断与 GPU 信息",
                            style = MaterialTheme.typography.titleMedium,
                            color = PtText,
                        )
                        Text(
                            text = "确认 Vulkan 与设备环境",
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
            SectionCard(title = "设备") {
                KeyValueRow(label = "型号", value = "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                KeyValueRow(label = "SoC", value = soc)
                KeyValueRow(label = "系统", value = androidVersion)
                KeyValueRow(label = "ABI", value = Build.SUPPORTED_ABIS.joinToString(", "))
                KeyValueRow(label = "CPU 核心", value = Runtime.getRuntime().availableProcessors().toString())
                KeyValueRow(
                    label = "内存",
                    value = "${formatBytes(memory.totalMem)} 总 / ${formatBytes(memory.availMem)} 可用",
                )
                KeyValueRow(label = "页大小", value = pageLabel)
                KeyValueRow(label = "可用存储", value = formatBytes(freeBytes))
            }

            SectionCard(title = "Vulkan / GPU") {
                KeyValueRow(
                    label = "Vulkan 硬件",
                    value = if (vulkanBase) "支持" else "不支持",
                    valueColor = if (vulkanBase) PtAccent else PtError,
                )
                KeyValueRow(
                    label = "Vulkan 1.3",
                    value = if (vulkan13) "支持" else "不支持或低于 1.3",
                    valueColor = if (vulkan13) PtAccent else PtWarning,
                )
                KeyValueRow(
                    label = "Vulkan 计算",
                    value = if (vulkanCompute) "支持" else "不支持",
                    valueColor = if (vulkanCompute) PtAccent else PtWarning,
                )
                Text(
                    text = "ggml 后端设备（读取诊断时已初始化）",
                    style = MaterialTheme.typography.labelSmall,
                    color = PtMuted,
                )
                Text(
                    text = nativeDevices,
                    style = MaterialTheme.typography.bodySmall,
                    color = PtText,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                )
                Text(
                    text = "若列表中没有 Vulkan 设备，识别会回退 CPU。Vulkan 初始化失败原因会写入下方日志。",
                    style = MaterialTheme.typography.labelSmall,
                    color = PtMuted,
                )
            }

            SectionCard(title = "运行环境") {
                KeyValueRow(label = "whisper.cpp", value = BuildConfig.WHISPER_CPP_VERSION)
                KeyValueRow(label = "FFmpegKit", value = BuildConfig.FFMPEG_KIT_VERSION)
                KeyValueRow(
                    label = "原生库",
                    value = if (WhisperNative.isAvailable) "已加载 whisper_jni" else "加载失败：${WhisperNative.loadError}",
                    valueColor = if (WhisperNative.isAvailable) PtAccent else PtError,
                )
                Text(
                    text = "ggml 系统信息",
                    style = MaterialTheme.typography.labelSmall,
                    color = PtMuted,
                )
                Text(
                    text = nativeInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = PtText,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                )
            }

            OutlinedButton(
                onClick = {
                    val report = buildString {
                        appendLine("pyvideotrans 字幕诊断")
                        appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                        appendLine("SoC：$soc")
                        appendLine("系统：$androidVersion")
                        appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}")
                        appendLine("CPU：${Runtime.getRuntime().availableProcessors()} 核心")
                        appendLine("内存：${formatBytes(memory.totalMem)} 总 / ${formatBytes(memory.availMem)} 可用")
                        appendLine("页大小：$pageLabel")
                        appendLine("Vulkan：base=$vulkanBase, 1.3=$vulkan13, compute=$vulkanCompute")
                        appendLine("whisper.cpp：${BuildConfig.WHISPER_CPP_VERSION}")
                        appendLine("FFmpegKit：${BuildConfig.FFMPEG_KIT_VERSION}")
                        appendLine("ggml 系统信息：$nativeInfo")
                        appendLine("ggml 设备：")
                        appendLine(nativeDevices)
                        appendLine("日志：")
                        LogBus.lines.value.takeLast(100).forEach { appendLine(it.format()) }
                    }
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("pyvideotrans-diagnostics", report))
                    Toast.makeText(context, "诊断信息已复制", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("复制诊断信息")
            }

            LogPanel(
                logs = logs,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
            )
        }
    }
}

private const val VULKAN_FEATURE = "android.hardware.vulkan.version"

private data class NativeProbe(
    val info: String = "读取中…",
    val devices: String = "读取中…",
)
