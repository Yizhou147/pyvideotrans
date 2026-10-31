package com.pyvideotrans.subtitle.data.media

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.pyvideotrans.subtitle.domain.model.MediaInfo
import com.pyvideotrans.subtitle.util.LogBus
import com.pyvideotrans.subtitle.util.formatBytes
import com.pyvideotrans.subtitle.util.formatDurationMs
import com.pyvideotrans.subtitle.util.mimeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt
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

        runFfmpeg(arguments, "音频抽取失败，视频可能没有音轨或格式不受支持")

        if (!output.isFile || output.length() < 1024) {
            throw IOException("音频抽取失败：输出文件为空")
        }
        LogBus.info("ffmpeg", "已抽取 16kHz 单声道 WAV (${formatBytes(output.length())})")
        output
    }

    /**
     * Runs FFmpeg with an argument array — never a shell string — so file names with
     * spaces or quotes cannot change what the command does.
     */
    private suspend fun runFfmpeg(arguments: Array<String>, failMessage: String) =
        withContext(Dispatchers.IO) {
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
                        throw CancellationException("FFmpeg 任务已取消")
                    }
                    val detail = session.failStackTrace
                        ?: session.allLogsAsString
                        ?: "未知 FFmpeg 错误"
                    LogBus.error("ffmpeg", detail.takeLast(2000))
                    throw IOException(failMessage)
                }
            } finally {
                activeFfmpegSessionId = 0L
            }
        }

    /**
     * Reads duration and the real display size. The rotation is asked for separately
     * because FFmpegKit's typed media information does not expose it.
     */
    suspend fun probe(video: File): MediaInfo = withContext(Dispatchers.IO) {
        val session = try {
            FFprobeKit.getMediaInformation(video.absolutePath)
        } catch (t: Throwable) {
            throw IOException("FFprobe 启动失败：${t.message}", t)
        }
        val information = session.mediaInformation
            ?: run {
                val detail = session.allLogsAsString?.takeLast(400).orEmpty()
                LogBus.error("ffprobe", detail)
                throw IOException("无法读取视频信息，文件可能已损坏或格式不受支持")
            }
        val streams = information.streams.orEmpty()
        val videoStream = streams.firstOrNull { it.type == "video" }
        var width = videoStream?.width?.toInt() ?: 0
        var height = videoStream?.height?.toInt() ?: 0
        val rotation = readRotation(video.absolutePath)
        if ((rotation == 90 || rotation == 270) && width > 0 && height > 0) {
            val swapped = width
            width = height
            height = swapped
        }
        val durationMs = information.duration?.toDoubleOrNull()?.let { (it * 1000).roundToInt().toLong() } ?: 0L
        val info = MediaInfo(
            durationMs = durationMs,
            width = width,
            height = height,
            hasVideo = videoStream != null,
            hasAudio = streams.any { it.type == "audio" },
        )
        LogBus.info(
            "ffprobe",
            "视频信息：${info.width}x${info.height}（旋转 $rotation°），时长 ${formatDurationMs(info.durationMs)}，" +
                "音轨=${if (info.hasAudio) "有" else "无"}，视频轨=${if (info.hasVideo) "有" else "无"}",
        )
        info
    }

    private fun readRotation(path: String): Int {
        val session = try {
            FFprobeKit.executeWithArguments(
                arrayOf(
                    "-v", "error",
                    "-select_streams", "v:0",
                    "-show_entries", "stream_side_data=rotation",
                    "-of", "default=noprint_wrappers=1:nokey=0",
                    path,
                ),
            )
        } catch (t: Throwable) {
            return 0
        }
        val line = session.allLogsAsString
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("rotation=") }
            ?: return 0
        val value = line.substringAfter('=').trim().toDoubleOrNull() ?: return 0
        return ((value.roundToInt() % 360) + 360) % 360
    }

    /**
     * Adds the SRT as a selectable track without re-encoding anything: the video and
     * audio streams are copied byte for byte, so a two hour film takes seconds instead
     * of an hour.
     */
    suspend fun muxSoftSubtitles(video: File, srt: File, output: File, language: String): File =
        withContext(Dispatchers.IO) {
            val arguments = arrayOf(
                "-hide_banner",
                "-loglevel", "error",
                "-nostdin",
                "-y",
                "-i", video.absolutePath,
                "-i", srt.absolutePath,
                "-map", "0:v?",
                "-map", "0:a?",
                "-map", "1:s:0",
                "-c", "copy",
                "-c:s", "mov_text",
                "-metadata:s:s:0", "language=$language",
                "-disposition:s:0", "default",
                "-movflags", "+faststart",
                output.absolutePath,
            )
            runFfmpeg(
                arguments,
                "软字幕封装失败：这个视频或音轨可能不适合装进 MP4，请改用「烧录字幕」",
            )
            if (!output.isFile || output.length() <= 0L) {
                throw IOException("软字幕封装失败：输出文件为空")
            }
            LogBus.info("ffmpeg", "已封装软字幕 (${formatBytes(output.length())})")
            output
        }

    /**
     * Copies a finished file into the folder the user picked. SAF is the only way out
     * of the app sandbox, and a half-written file is deleted rather than left behind.
     */
    suspend fun publish(source: File, treeUri: Uri, onProgress: (Int) -> Unit = {}): Uri =
        withContext(Dispatchers.IO) {
            val documentId = try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (t: IllegalArgumentException) {
                throw IOException("所选保存位置无效，请重新选择")
            }
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val created = DocumentsContract.createDocument(
                context.contentResolver,
                parent,
                mimeFor(source.name),
                source.name,
            ) ?: throw IOException("无法在所选位置创建 ${source.name}，该位置可能是只读的")
            try {
                copyToFile(source, created, onProgress)
            } catch (t: Throwable) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, created) }
                throw t
            }
            LogBus.info("media", "已保存 ${source.name} (${formatBytes(source.length())})")
            created
        }

    private suspend fun copyToFile(source: File, target: Uri, onProgress: (Int) -> Unit) {
        val total = source.length()
        source.inputStream().use { input ->
            val output = context.contentResolver.openOutputStream(target, "w")
                ?: throw IOException("无法写入 ${source.name}")
            output.use { sink ->
                val buffer = ByteArray(1024 * 1024)
                var copied = 0L
                var lastPercent = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    copied += read
                    if (total > 0) {
                        val percent = (copied * 100 / total).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        }
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
