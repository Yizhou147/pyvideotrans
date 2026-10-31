package com.pyvideotrans.subtitle.util

import android.content.Context
import android.util.Log
import java.io.File

/**
 * A crash takes the process and its in-memory log with it, so the trace is written to disk and
 * replayed into the log panel on the next launch.
 */
object CrashLog {
    private const val PREFIX = "crash-"
    private const val KEEP = 3

    fun install(context: Context) {
        val dir = File(context.filesDir, "crashes")
        replay(dir)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(dir, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(dir: File, thread: Thread, error: Throwable) {
        dir.mkdirs()
        File(dir, "$PREFIX${System.currentTimeMillis()}.txt").writeText(
            "线程 ${thread.name}\n${Log.getStackTraceString(error)}",
        )
        listings(dir).sortedByDescending { it.lastModified() }.drop(KEEP).forEach { it.delete() }
    }

    private fun replay(dir: File) {
        listings(dir).maxByOrNull { it.lastModified() }?.let { latest ->
            LogBus.error("crash", "上次运行异常退出（${latest.name}），以下为堆栈：")
            runCatching { latest.readLines().take(25) }.getOrDefault(emptyList())
                .forEach { LogBus.error("crash", it) }
        }
    }

    private fun listings(dir: File): List<File> =
        dir.listFiles()?.filter { it.name.startsWith(PREFIX) } ?: emptyList()
}
