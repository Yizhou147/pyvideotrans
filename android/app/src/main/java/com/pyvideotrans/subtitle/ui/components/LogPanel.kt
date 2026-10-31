package com.pyvideotrans.subtitle.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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

@Composable
fun LogPanel(
    logs: List<LogLine>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2500)
            copied = false
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(logs) { line ->
                            Text(
                                text = line.format(),
                                color = colorFor(line.level),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun colorFor(level: LogLevel): androidx.compose.ui.graphics.Color = when (level) {
    LogLevel.ERROR -> PtError
    LogLevel.WARN -> PtWarning
    LogLevel.DEBUG -> PtMuted
    LogLevel.INFO -> PtText
}
