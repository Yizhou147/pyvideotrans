package com.pyvideotrans.subtitle.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class LogLevel(val label: String) {
    DEBUG("D"),
    INFO("I"),
    WARN("W"),
    ERROR("E"),
}

data class LogLine(
    val time: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    fun format(): String = "$time ${level.label}/$tag: $message"
}

object LogBus {
    private const val MAX_LINES = 800
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val lock = Any()

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    fun debug(tag: String, message: String) = append(LogLevel.DEBUG, tag, message)

    fun info(tag: String, message: String) = append(LogLevel.INFO, tag, message)

    fun warn(tag: String, message: String) = append(LogLevel.WARN, tag, message)

    fun error(tag: String, message: String) = append(LogLevel.ERROR, tag, message)

    fun append(level: LogLevel, tag: String, message: String) {
        val line = LogLine(
            time = LocalTime.now().format(timeFormat),
            level = level,
            tag = tag,
            message = message,
        )
        synchronized(lock) {
            val next = _lines.value + line
            _lines.value = if (next.size > MAX_LINES) next.takeLast(MAX_LINES) else next
        }
    }

    fun clear() {
        synchronized(lock) {
            _lines.value = emptyList()
        }
    }
}
