package com.pyvideotrans.subtitle.data.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.pyvideotrans.subtitle.util.LogBus
import com.pyvideotrans.subtitle.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class ModelRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    val modelRoot: File = File(context.filesDir, "models").apply { mkdirs() }
    val asrDir: File = File(modelRoot, "asr").apply { mkdirs() }
    val vadDir: File = File(modelRoot, "vad").apply { mkdirs() }

    private val manifestHolder: ModelManifest by lazy {
        val text = context.assets.open("models.json").bufferedReader().use { it.readText() }
        json.decodeFromString(ModelManifest.serializer(), text)
    }

    fun manifest(): ModelManifest = manifestHolder

    fun fileFor(spec: ModelSpec): File {
        val dir = if (spec.isVad) vadDir else asrDir
        return File(dir, spec.fileName)
    }

    fun isDownloaded(spec: ModelSpec): Boolean {
        val file = fileFor(spec)
        return file.isFile && file.length() == spec.sizeBytes
    }

    fun downloadedSize(spec: ModelSpec): Long = fileFor(spec).takeIf { it.isFile }?.length() ?: 0L

    fun vadFile(): File? {
        val spec = manifest().models.firstOrNull { it.isVad } ?: return null
        val file = fileFor(spec)
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    fun listAsrModels(): List<LocalModel> {
        val specs = manifest().models.filter { it.kind == "asr" }
        val installed = specs.mapNotNull { spec ->
            val file = fileFor(spec)
            if (file.isFile && file.length() > 0) {
                LocalModel(file, spec.name, spec, custom = false)
            } else {
                null
            }
        }
        val knownNames = specs.map { it.fileName }.toSet()
        val custom = asrDir.listFiles { file ->
            file.isFile && file.extension.equals("bin", ignoreCase = true) && file.name !in knownNames
        }.orEmpty()
            .sortedBy { it.name.lowercase() }
            .map { file ->
                LocalModel(
                    file = file,
                    displayName = file.name.removeSuffix(".bin"),
                    spec = null,
                    custom = true,
                )
            }
        return installed + custom
    }

    suspend fun download(
        spec: ModelSpec,
        onProgress: (received: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val target = fileFor(spec)
        if (target.isFile && target.length() == spec.sizeBytes) {
            return@withContext target
        }
        if (spec.urls.isEmpty()) {
            throw IOException("模型清单缺少下载地址")
        }

        val failures = mutableListOf<String>()
        spec.urls.forEachIndexed { index, url ->
            try {
                downloadFrom(url, spec, onProgress)
                return@withContext target
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val host = hostOf(url)
                val reason = t.message ?: t.javaClass.simpleName
                failures += "$host：$reason"
                LogBus.warn("model", "从 $host 下载 ${spec.name} 失败：$reason")
                if (index < spec.urls.lastIndex) {
                    LogBus.info("model", "改用备用地址 ${hostOf(spec.urls[index + 1])}")
                }
            }
        }
        throw IOException(
            "下载失败（${failures.joinToString("；")}）。可在电脑下载 ${spec.fileName} 后通过“导入模型”安装。",
        )
    }

    private suspend fun downloadFrom(
        url: String,
        spec: ModelSpec,
        onProgress: (received: Long, total: Long) -> Unit,
    ) {
        val target = fileFor(spec)
        val part = File(target.parentFile, "${target.name}.part")
        if (part.isFile && part.length() == spec.sizeBytes) {
            if (sha256(part).equals(spec.sha256, ignoreCase = true)) {
                moveIntoPlace(part, target)
                LogBus.info("model", "已校验已有下载 ${spec.name} (${formatBytes(target.length())})")
                return
            }
            part.delete()
        }
        if (part.isFile && part.length() > spec.sizeBytes) {
            part.delete()
        }

        var existing = part.takeIf { it.isFile }?.length() ?: 0L
        var connection: HttpURLConnection? = null

        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "pyvideotrans-subtitle-android/0.1")
                if (existing > 0) {
                    setRequestProperty("Range", "bytes=$existing-")
                }
            }

            val code = connection.responseCode
            if (code == 416) {
                part.delete()
                throw IOException("服务器拒绝续传请求，请重试下载")
            }
            val append = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !append) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.take(300)?.replace('\n', ' ')
                throw IOException("下载失败：HTTP $code${if (detail.isNullOrBlank()) "" else "，$detail"}")
            }
            if (!append) {
                existing = 0L
                part.delete()
            }

            val contentLength = connection.contentLengthLong
            val total = if (contentLength > 0) existing + contentLength else spec.sizeBytes
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var downloaded = existing
                    var lastReported = -1L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val bucket = if (total > 0) downloaded * 100 / total else -1L
                        if (bucket != lastReported) {
                            lastReported = bucket
                            onProgress(downloaded, total)
                        }
                    }
                }
            }

            if (part.length() != spec.sizeBytes) {
                throw IOException(
                    "模型大小不匹配：期望 ${formatBytes(spec.sizeBytes)}，实际 ${formatBytes(part.length())}",
                )
            }
            val digest = sha256(part)
            if (!digest.equals(spec.sha256, ignoreCase = true)) {
                part.delete()
                throw IOException("SHA-256 校验失败，下载内容可能损坏")
            }

            moveIntoPlace(part, target)
            LogBus.info(
                "model",
                "已从 ${hostOf(url)} 下载并校验 ${spec.name} (${formatBytes(target.length())})",
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun moveIntoPlace(part: File, target: File) {
        Files.move(
            part.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrNull().takeUnless { it.isNullOrBlank() } ?: url

    suspend fun importAsr(uri: Uri): File = withContext(Dispatchers.IO) {
        val sanitized = sanitizeFileName(queryDisplayName(uri))
        val baseName = sanitized.substringBeforeLast('.', sanitized).ifBlank { "imported-model" }
        val target = uniqueTarget("$baseName.bin")
        val temp = File(asrDir, "${target.name}.importing")

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("无法读取所选文件")
            input.use { source ->
                temp.outputStream().use { sink -> source.copyTo(sink) }
            }
            if (temp.length() < 1_048_576L) {
                throw IOException("文件过小，不是有效的 Whisper GGML 模型")
            }
            val isGgml = DataInputStream(temp.inputStream().buffered()).use { input ->
                val magic = ByteArray(4)
                input.readFully(magic)
                // whisper.cpp writes GGML_FILE_MAGIC (0x67676d6c, ASCII "ggml" as a
                // little-endian integer), i.e. the file starts with bytes "lmgg".
                val value = (magic[0].toInt() and 0xFF) or
                    ((magic[1].toInt() and 0xFF) shl 8) or
                    ((magic[2].toInt() and 0xFF) shl 16) or
                    ((magic[3].toInt() and 0xFF) shl 24)
                value == GGML_FILE_MAGIC
            }
            if (!isGgml) {
                throw IOException("不是 whisper.cpp GGML 模型（请使用 whisper.cpp 格式的 .bin 模型）")
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            LogBus.info("model", "已导入自定义模型 ${target.name} (${formatBytes(target.length())})")
            target
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun delete(spec: ModelSpec): Boolean {
        val target = fileFor(spec)
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()
        val deleted = target.delete()
        if (deleted) {
            LogBus.info("model", "已删除 ${spec.name}")
        }
        return deleted
    }

    fun delete(local: LocalModel): Boolean {
        val part = File(local.file.parentFile, "${local.file.name}.part")
        part.delete()
        val deleted = local.file.delete()
        if (deleted) {
            LogBus.info("model", "已删除 ${local.displayName}")
        }
        return deleted
    }

    private fun uniqueTarget(name: String): File {
        var candidate = File(asrDir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "bin")
        var index = 2
        while (candidate.exists()) {
            candidate = File(asrDir, "$stem-$index.$ext")
            index++
        }
        return candidate
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return null
    }

    private fun sanitizeFileName(raw: String?): String {
        val cleaned = (raw ?: "imported-model")
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
            .trim()
            .take(80)
        return cleaned.ifBlank { "imported-model" }
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val GGML_FILE_MAGIC = 0x67676d6c
    }
}
