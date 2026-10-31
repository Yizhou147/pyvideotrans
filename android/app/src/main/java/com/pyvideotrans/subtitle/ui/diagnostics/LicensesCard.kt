package com.pyvideotrans.subtitle.ui.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyvideotrans.subtitle.ui.components.KeyValueRow
import com.pyvideotrans.subtitle.ui.components.SectionCard
import com.pyvideotrans.subtitle.ui.theme.PtMuted
import com.pyvideotrans.subtitle.ui.theme.PtText
import com.pyvideotrans.subtitle.ui.theme.PtWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** GPLv3 obliges handing these terms to APK recipients, so they ship in assets and stay readable on-device. */
@Composable
fun LicensesCard() {
    val context = LocalContext.current
    val files = remember {
        runCatching {
            context.assets.list(LICENSE_DIR).orEmpty().filter { it.endsWith(".txt") }.sorted()
        }.getOrDefault(emptyList())
    }
    var opened by remember { mutableStateOf<String?>(null) }
    val text by produceState(initialValue = "", key1 = opened) {
        value = opened?.let { name ->
            withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open("$LICENSE_DIR/$name").use { it.readBytes().decodeToString() }
                }.getOrElse { "读取 $name 失败：${it.message}" }
            }
        }.orEmpty()
    }

    SectionCard(title = "第三方组件与许可") {
        KeyValueRow(label = "本应用", value = "GPLv3 · 源码 github.com/Yizhou147/pyvideotrans")
        KeyValueRow(label = "whisper.cpp", value = "MIT")
        KeyValueRow(label = "sherpa-onnx", value = "Apache-2.0")
        KeyValueRow(label = "ONNX Runtime", value = "MIT")
        KeyValueRow(label = "OpenCC 词典", value = "Apache-2.0")
        KeyValueRow(label = "FFmpegKit full-gpl", value = "GPLv3")
        KeyValueRow(label = "Commons Compress", value = "Apache-2.0")
        KeyValueRow(label = "Qualcomm QNN", value = "专有 · AI Model Hub 条款", valueColor = PtWarning)

        if (opened == null) {
            Text(
                text = "QNN 运行库只以目标码形式嵌入本应用分发，不可单独转发；完整条款见下。",
                style = MaterialTheme.typography.labelSmall,
                color = PtMuted,
            )
            Column {
                files.forEach { name ->
                    TextButton(onClick = { opened = name }) {
                        Text(name, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = PtText,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 17.sp,
                )
            }
            TextButton(onClick = { opened = null }) {
                Text("收起 ${opened ?: ""}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private const val LICENSE_DIR = "licenses"
