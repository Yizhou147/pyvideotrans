package com.pyvideotrans.subtitle.data.media

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pyvideotrans.subtitle.util.LogBus
import com.pyvideotrans.subtitle.util.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

class MediaRepository(private val context: Context) {

    @Volatile
    private var activeFfmpegSessionId: Long = 0L

    fun createJobDir(): File {
        val dir = File(context.cacheDir, "jobs/${System.currentTimeMillis()}")
        if (!dir.mkdirs()) {
            throw IOException("无法创建缓存目录：${dir.absolutePath}")
        }
        return dir
    }

    fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index) ?: "video.mp4"
            }
        }
        return "video.mp4"
    }

    fun querySize(uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                return cursor.getLong(index)
            }
        }
        return 0L
    }

    fun requireFreeSpace(bytes: Long) {
        val stat = StatFs(context.cacheDir.absolutePath)
        val available = stat.availableBytes
        if (available < bytes) {
            throw IOException(
                "存储空间不足：需要约 ${formatBytes(bytes)}，可用 ${formatBytes(available)}",
            )
        }
    }

    suspend fun copyToCache(
        uri: Uri,
        displayName: String,
        jobDir: File,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val extension = displayName.substringAfterLast('.', "")
            .lowercase(Locale.US)
            .takeIf { it in VIDEO_EXTENSIONS }
            ?: "mp4"
        val target = File(jobDir, "input.$extension")
        val expectedSize = querySize(uri)

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开所选视频，请重新选择")
        input.use { source ->
            target.outputStream().use { sink ->
                val buffer = ByteArray(1024 * 1024)
                var copied = 0L
                var lastPercent = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    copied += read
                    if (expectedSize > 0) {
                        val percent = (copied * 100 / expectedSize).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        }
        if (target.length() <= 0L) {
            throw IOException("复制视频失败：文件为空")
        }
        LogBus.info("media", "已复制视频到缓存 (${formatBytes(target.length())})")
        target
    }

    suspend fun extractAudio(video: File, jobDir: File): File = withContext(Dispatchers.IO) {
        val output = File(jobDir, "audio-16k-mono.wav")
        val arguments = arrayOf(
            "-hide_banner",
            "-loglevel", "error",
            "-nostdin",
            "-y",
            "-i", video.absolutePath,
            "-vn",
            "-map", "0:a:0",
            "-ac", "1",
            "-ar", "16000",
            "-c:a", "pcm_s16le",
            output.absolutePath,
        )

        val session = try {
            FFmpegKit.executeWithArguments(arguments)
        } catch (t: Throwable) {
            throw IOException("FFmpeg 启动失败：${t.message}", t)
        }

        activeFfmpegSessionId = session.sessionId
        try {
            val returnCode = session.returnCode
            if (returnCode == null || !ReturnCode.isSuccess(returnCode)) {
                if (returnCode != null && ReturnCode.isCancel(returnCode)) {
                    throw CancellationException("音频抽取已取消")
                }
                val detail = session.failStackTrace
                    ?: session.allLogsAsString
                    ?: "未知 FFmpeg 错误"
                LogBus.error("ffmpeg", detail.takeLast(2000))
                throw IOException("音频抽取失败，视频可能没有音轨或格式不受支持")
            }
        } finally {
            activeFfmpegSessionId = 0L
        }

        if (!output.isFile || output.length() < 1024) {
            throw IOException("音频抽取失败：输出文件为空")
        }
        LogBus.info("ffmpeg", "已抽取 16kHz 单声道 WAV (${formatBytes(output.length())})")
        output
    }

    fun cancelActive() {
        val sessionId = activeFfmpegSessionId
        if (sessionId > 0L) {
            FFmpegKit.cancel(sessionId)
        }
    }

    private companion object {
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "mov", "webm", "avi", "m4v", "ts", "m2ts", "flv", "wmv", "3gp",
        )
    }
}
