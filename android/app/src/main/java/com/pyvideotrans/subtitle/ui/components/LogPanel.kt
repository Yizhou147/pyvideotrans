package com.pyvideotrans.subtitle.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.pyvideotrans.subtitle.ui.theme.PtAccent
import com.pyvideotrans.subtitle.ui.theme.PtBorder
import com.pyvideotrans.subtitle.ui.theme.PtError
import com.pyvideotrans.subtitle.ui.theme.PtMuted
import com.pyvideotrans.subtitle.ui.theme.PtSurface
import com.pyvideotrans.subtitle.ui.theme.PtText
import com.pyvideotrans.subtitle.ui.theme.PtWarning
import com.pyvideotrans.subtitle.util.LogBus
import com.pyvideotrans.subtitle.util.LogLevel
import com.pyvideotrans.subtitle.util.LogLine

private val LogTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 15.sp,
)

@Composable
fun LogPanel(
    logs: List<LogLine>,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val scrollState = rememberScrollState()
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2500)
            copied = false
        }
    }

    // Instant rather than animated: new lines keep arriving while a selection
    // handle is held, and an animation under it throws the handle away.
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = PtSurface,
        border = BorderStroke(1.dp, PtBorder),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "运行日志",
                    style = MaterialTheme.typography.titleSmall,
                    color = PtAccent,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(logs.joinToString("\n") { it.format() }))
                            copied = true
                        },
                        enabled = logs.isNotEmpty(),
                    ) {
                        Text(
                            text = if (copied) "已复制" else "复制",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = LogBus::clear, enabled = logs.isNotEmpty()) {
                        Text("清空", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            SectionDivider()
            Box(modifier = Modifier.fillMaxSize()) {
                if (logs.isEmpty()) {
                    Text(
                        text = "暂无日志",
                        color = PtMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    // One selectable block instead of one per row: a lazy list
                    // drops rows from the composition while a handle is held and
                    // selection then crashes the app.
                    SelectionContainer(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = selectableLog(logs),
                            color = PtText,
                            style = LogTextStyle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun selectableLog(logs: List<LogLine>): AnnotatedString = remember(logs) {
    AnnotatedString.Builder(logs.joinToString("\n") { it.format() }).apply {
        var offset = 0
        logs.forEach { line ->
            val length = line.format().length
            addStyle(SpanStyle(color = colorFor(line.level)), offset, offset + length)
            offset += length + 1
        }
    }.toAnnotatedString()
}

private fun colorFor(level: LogLevel): Color = when (level) {
    LogLevel.ERROR -> PtError
    LogLevel.WARN -> PtWarning
    LogLevel.DEBUG -> PtMuted
    LogLevel.INFO -> PtText
}
