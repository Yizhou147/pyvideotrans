package com.pyvideotrans.subtitle.data.model

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.pyvideotrans.subtitle.util.LogBus
import com.pyvideotrans.subtitle.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
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

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val modelRoot: File = File(context.filesDir, "models").apply { mkdirs() }
    val asrDir: File = File(modelRoot, "asr").apply { mkdirs() }
    val vadDir: File = File(modelRoot, "vad").apply { mkdirs() }
    val vadOnnxDir: File = File(modelRoot, "vad-onnx").apply { mkdirs() }
    val punctDir: File = File(modelRoot, "punct").apply { mkdirs() }
    val qnnDir: File = File(modelRoot, "qnn").apply { mkdirs() }
    private val qnnArchiveDir: File = File(qnnDir, "archives").apply { mkdirs() }
    private val qnnModelRoot: File = File(qnnDir, "models").apply { mkdirs() }

    private val manifestHolder: ModelManifest by lazy {
        val text = context.assets.open("models.json").bufferedReader().use { it.readText() }
        json.decodeFromString(ModelManifest.serializer(), text)
    }

    fun manifest(): ModelManifest = manifestHolder

    /**
     * Optional user-supplied download accelerator prefix. Domestic networks often
     * cannot reach github.com, so the prefix wraps the original URL and is tried
     * first.
     */
    var mirrorPrefix: String
        get() = prefs.getString(KEY_MIRROR_PREFIX, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_MIRROR_PREFIX, value.trim()).apply()
        }

    fun candidateUrls(spec: ModelSpec, mirror: String = mirrorPrefix): List<String> {
        val candidates = LinkedHashSet<String>()
        spec.urls.forEach { candidates += resolveModelUrl(it, mirror) }
        return candidates.toList()
    }

    fun fileFor(spec: ModelSpec): File {
        val dir = when {
            spec.isVad -> vadDir
            spec.isVadOnnx -> vadOnnxDir
            spec.isPunctuation -> punctDir
            spec.isQnn -> qnnArchiveDir
            else -> asrDir
        }
        return File(dir, spec.fileName)
    }

    /** The unpacked punctuation model, or null while it is missing or half-installed. */
    fun punctuationFile(): File? {
        val spec = manifest().models.firstOrNull { it.isPunctuation } ?: return null
        val file = File(punctDir, PUNCT_MODEL_NAME)
        return file.takeIf { it.isFile && it.length() == spec.innerSizeBytes }
    }

    fun qnnModelDir(spec: ModelSpec): File = File(qnnModelRoot, spec.id)

    fun qnnFiles(spec: ModelSpec): QnnModelFiles? {
        if (!spec.isQnn || spec.isSenseVoice) return null
        val dir = qnnModelDir(spec)
        val encoder = File(dir, "encoder.bin")
        val decoder = File(dir, "decoder.bin")
        val tokens = File(dir, "tokens.txt")
        if (!encoder.isFile || !decoder.isFile || !tokens.isFile) return null
        return QnnModelFiles(dir, encoder, decoder, tokens)
    }

    fun qnnSenseVoiceFiles(spec: ModelSpec): QnnSenseVoiceFiles? {
        if (!spec.isSenseVoice) return null
        val dir = qnnModelDir(spec)
        val model = File(dir, "model.bin")
        val tokens = File(dir, "tokens.txt")
        if (!model.isFile || !tokens.isFile) return null
        return QnnSenseVoiceFiles(dir, model, tokens)
    }

    /** Whatever layout the bundle uses, this is the directory once it is unpacked. */
    fun qnnBundleDir(spec: ModelSpec): File? {
        if (!spec.isQnn) return null
        val dir = qnnModelDir(spec)
        return if (qnnFiles(spec) != null || qnnSenseVoiceFiles(spec) != null) dir else null
    }

    fun isDownloaded(spec: ModelSpec): Boolean {
        if (spec.isQnn) return qnnBundleDir(spec) != null
        if (spec.isPunctuation) return punctuationFile() != null
        val file = fileFor(spec)
        return file.isFile && file.length() == spec.sizeBytes
    }

    fun downloadedSize(spec: ModelSpec): Long = when {
        spec.isQnn -> qnnBundleDir(spec)?.let { dirSize(it) } ?: fileFor(spec).length()
        spec.isPunctuation -> punctuationFile()?.length() ?: fileFor(spec).length()
        else -> fileFor(spec).takeIf { it.isFile }?.length() ?: 0L
    }

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun vadFile(): File? {
        val spec = manifest().models.firstOrNull { it.isVad } ?: return null
        val file = fileFor(spec)
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    fun vadOnnxFile(): File? {
        val spec = manifest().models.firstOrNull { it.isVadOnnx } ?: return null
        val file = fileFor(spec)
        return file.takeIf { it.isFile && it.length() == spec.sizeBytes }
    }

    fun listQnnModels(soc: String): List<LocalQnnModel> {
        return manifest().models
            .filter { it.isQnn && it.soc.equals(soc, ignoreCase = true) }
            .mapNotNull { spec ->
                qnnBundleDir(spec)?.let { LocalQnnModel(spec, it) }
            }
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
        onPhase: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        if (isDownloaded(spec)) {
            return@withContext if (spec.isQnn) qnnModelDir(spec) else fileFor(spec)
        }
        if (spec.urls.isEmpty()) {
            throw IOException("模型清单缺少下载地址")
        }
        if (spec.isQnn || spec.isPunctuation) {
            val stat = StatFs(modelRoot.absolutePath)
            // Installing keeps the archive and the unpacked model on disk at the same time.
            val needed =
                if (spec.isQnn) spec.sizeBytes * 3 else spec.sizeBytes + spec.innerSizeBytes
            if (stat.availableBytes < needed) {
                throw IOException(
                    "存储空间不足：安装该模型约需 ${formatBytes(needed)}，可用 ${formatBytes(stat.availableBytes)}",
                )
            }
        }

        val failures = mutableListOf<String>()
        val urls = candidateUrls(spec)
        urls.forEachIndexed { index, url ->
            try {
                downloadFrom(url, spec, onProgress)
                if (spec.isQnn) {
                    onPhase("校验并解压 NPU 模型")
                    installQnn(spec)
                    return@withContext qnnModelDir(spec)
                }
                if (spec.isPunctuation) {
                    onPhase("校验并解压标点模型")
                    installPunctuation(spec)
                    return@withContext punctuationFile()
                        ?: throw IOException("标点模型解压后大小不符，请重新下载")
                }
                return@withContext fileFor(spec)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val host = hostOf(url)
                val reason = t.message ?: t.javaClass.simpleName
                failures += "$host：$reason"
                LogBus.warn("model", "从 $host 下载 ${spec.name} 失败：$reason")
                if (index < urls.lastIndex) {
                    LogBus.info("model", "改用备用地址 ${hostOf(urls[index + 1])}")
                }
            }
        }
        throw IOException(
            "下载失败（${failures.joinToString("；")}）。可在电脑下载 ${spec.fileName} 后通过“导入模型”安装，" +
                "或在“下载镜像”里填写可用的加速前缀。",
        )
    }

    private suspend fun downloadFrom(
        url: String,
        spec: ModelSpec,
        onProgress: (received: Long, total: Long) -> Unit,
    ) {
        val target = fileFor(spec)
        if (target.isFile && target.length() == spec.sizeBytes) {
            onProgress(target.length(), spec.sizeBytes)
            return
        }
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
            copyUriTo(uri, temp)
            if (temp.length() < 1_048_576L) {
                throw IOException(
                    "文件过小，不是有效的 Whisper 模型；Silero VAD（约 885 KB）请在“静音检测（VAD）”区域导入",
                )
            }
            if (!hasGgmlMagic(temp)) {
                throw IOException("不是 whisper.cpp GGML 模型（请使用 whisper.cpp 格式的 .bin 模型）")
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            LogBus.info("model", "已导入自定义模型 ${target.name} (${formatBytes(target.length())})")
            target
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    suspend fun importVad(uri: Uri): File = withContext(Dispatchers.IO) {
        val spec = manifest().models.firstOrNull { it.isVad }
            ?: throw IOException("模型清单中未配置 VAD")
        val target = fileFor(spec)
        val temp = File(vadDir, "${target.name}.importing")

        try {
            copyUriTo(uri, temp)
            if (temp.length() < 100_000L || temp.length() > 10_000_000L) {
                throw IOException(
                    "VAD 文件大小异常（${formatBytes(temp.length())}），请选择 Silero VAD GGML 模型",
                )
            }
            if (!hasGgmlMagic(temp)) {
                throw IOException("不是 whisper.cpp GGML 格式的 VAD 模型")
            }
            val digest = sha256(temp)
            if (!digest.equals(spec.sha256, ignoreCase = true)) {
                throw IOException("SHA-256 与清单不匹配，不是受支持的 Silero VAD 模型")
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            LogBus.info("model", "已导入 VAD 模型 ${target.name} (${formatBytes(target.length())})")
            target
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    suspend fun importVadOnnx(uri: Uri): File = withContext(Dispatchers.IO) {
        val spec = manifest().models.firstOrNull { it.isVadOnnx }
            ?: throw IOException("模型清单中未配置 Silero VAD ONNX")
        val target = fileFor(spec)
        val temp = File(vadOnnxDir, "${target.name}.importing")

        try {
            copyUriTo(uri, temp)
            if (temp.length() < 100_000L || temp.length() > 20_000_000L) {
                throw IOException(
                    "VAD ONNX 文件大小异常（${formatBytes(temp.length())}），请选择 silero_vad.onnx",
                )
            }
            val digest = sha256(temp)
            if (!digest.equals(spec.sha256, ignoreCase = true)) {
                throw IOException("SHA-256 与清单不匹配，不是受支持的 Silero VAD ONNX 模型")
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            LogBus.info("model", "已导入 Silero VAD ONNX (${formatBytes(target.length())})")
            target
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    suspend fun installQnn(spec: ModelSpec): File = withContext(Dispatchers.IO) {
        if (!spec.isQnn) throw IOException("${spec.name} 不是 NPU 模型")
        qnnFiles(spec)?.let { return@withContext it.dir }

        val archive = fileFor(spec)
        if (!archive.isFile) {
            throw IOException("NPU 模型归档不存在，请下载 ${spec.fileName}")
        }
        if (archive.length() != spec.sizeBytes) {
            throw IOException(
                "NPU 模型归档大小不匹配：期望 ${formatBytes(spec.sizeBytes)}，实际 ${formatBytes(archive.length())}",
            )
        }
        val digest = sha256(archive)
        if (!digest.equals(spec.sha256, ignoreCase = true)) {
            archive.delete()
            throw IOException("NPU 模型归档 SHA-256 校验失败，请重新下载")
        }

        extractQnnArchive(spec, archive)
        archive.delete()
        LogBus.info("model", "已安装 NPU 模型 ${spec.name} (${formatBytes(dirSize(qnnModelDir(spec)))})")
        qnnModelDir(spec)
    }

    /**
     * The punctuation download ships as an archive but sherpa wants the single ONNX
     * file inside it, so the copy is verified, moved out, and the archive deleted:
     * 64 MB of tarball is not worth keeping on a phone once it is installed.
     */
    suspend fun installPunctuation(spec: ModelSpec): File = withContext(Dispatchers.IO) {
        if (!spec.isPunctuation) throw IOException("${spec.name} 不是标点模型")
        punctuationFile()?.let { return@withContext it }

        val archive = fileFor(spec)
        if (!archive.isFile) {
            throw IOException("标点模型归档不存在，请下载 ${spec.fileName}")
        }
        if (archive.length() != spec.sizeBytes) {
            throw IOException(
                "标点模型归档大小不匹配：期望 ${formatBytes(spec.sizeBytes)}，实际 ${formatBytes(archive.length())}",
            )
        }
        if (!sha256(archive).equals(spec.sha256, ignoreCase = true)) {
            archive.delete()
            throw IOException("标点模型归档 SHA-256 校验失败，请重新下载")
        }

        extractPunctuationModel(spec, archive)
        archive.delete()
        val installed = punctuationFile()
            ?: throw IOException("标点模型归档中没有可用的 $PUNCT_MODEL_NAME")
        LogBus.info("model", "已安装标点模型 (${formatBytes(installed.length())})")
        installed
    }

    private suspend fun extractPunctuationModel(spec: ModelSpec, archive: File) {
        val staging = File(punctDir, ".extract-${System.nanoTime()}").apply { mkdirs() }
        val output = File(staging, PUNCT_MODEL_NAME)
        try {
            BZip2CompressorInputStream(archive.inputStream().buffered(), true).use { bzip ->
                TarArchiveInputStream(bzip).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        if (!entry.isFile) continue
                        if (entry.name.split('/').any { it == ".." }) {
                            throw IOException("标点模型归档包含非法路径：${entry.name}")
                        }
                        if (entry.name.substringAfterLast('/') != PUNCT_MODEL_NAME) continue
                        if (entry.size != spec.innerSizeBytes) {
                            throw IOException(
                                "标点模型大小不匹配：清单期望 ${formatBytes(spec.innerSizeBytes)}，" +
                                    "归档内是 ${formatBytes(entry.size)}",
                            )
                        }
                        output.outputStream().buffered().use { sink -> tar.copyTo(sink) }
                        break
                    }
                }
            }
            if (!output.isFile) throw IOException("标点模型归档中未找到 $PUNCT_MODEL_NAME")
            if (!sha256(output).equals(spec.innerSha256, ignoreCase = true)) {
                throw IOException("标点模型内容校验失败，归档可能与清单不是同一版本")
            }
            moveIntoPlace(output, File(punctDir, PUNCT_MODEL_NAME))
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun importPunctuation(uri: Uri): File = withContext(Dispatchers.IO) {
        val spec = manifest().models.firstOrNull { it.isPunctuation }
            ?: throw IOException("模型清单中未配置标点模型")
        val temp = File(punctDir, ".import-${System.nanoTime()}.onnx")

        try {
            copyUriTo(uri, temp)
            if (temp.length() != spec.innerSizeBytes) {
                throw IOException(
                    "标点模型大小异常：应为 ${formatBytes(spec.innerSizeBytes)}，" +
                        "实际 ${formatBytes(temp.length())}。请选择解压出来的 model.int8.onnx",
                )
            }
            val digest = sha256(temp)
            if (!digest.equals(spec.innerSha256, ignoreCase = true)) {
                throw IOException("SHA-256 与清单不匹配，不是受支持的 ct-transformer 标点模型")
            }
            val target = File(punctDir, PUNCT_MODEL_NAME)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            LogBus.info("model", "已导入标点模型 (${formatBytes(target.length())})")
            target
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    suspend fun importQnn(uri: Uri): ModelSpec = withContext(Dispatchers.IO) {
        val displayName = sanitizeFileName(queryDisplayName(uri))
        val specs = manifest().models.filter { it.isQnn }
        val byName = specs.firstOrNull { it.fileName.equals(displayName, ignoreCase = true) }
        val temp = File(qnnArchiveDir, ".import-${System.nanoTime()}.tar.bz2")

        try {
            copyUriTo(uri, temp)
            val digest = sha256(temp)
            val spec = byName ?: specs.firstOrNull {
                it.sizeBytes == temp.length() && it.sha256.equals(digest, ignoreCase = true)
            } ?: throw IOException(
                "未识别的 NPU 模型归档：$displayName。请选择与设备 SoC 对应的 .tar.bz2 文件",
            )
            if (!spec.sha256.equals(digest, ignoreCase = true)) {
                throw IOException("SHA-256 与清单不匹配，文件可能不完整或已损坏")
            }

            val target = fileFor(spec)
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            extractQnnArchive(spec, target)
            target.delete()
            LogBus.info("model", "已导入 NPU 模型 ${spec.name}")
            spec
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun extractQnnArchive(spec: ModelSpec, archive: File) {
        val staging = File(qnnModelRoot, ".extract-${spec.id}-${System.nanoTime()}").apply { mkdirs() }
        var extractedBytes = 0L
        try {
            BZip2CompressorInputStream(archive.inputStream().buffered(), true).use { bzip ->
                TarArchiveInputStream(bzip).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        val name = entry.name
                        if (name.isBlank()) continue
                        if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                            throw IOException("NPU 模型归档包含非法路径：$name")
                        }
                        val output = File(staging, name)
                        if (!output.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            throw IOException("NPU 模型归档包含非法路径：$name")
                        }
                        when {
                            entry.isDirectory -> output.mkdirs()
                            entry.isFile -> {
                                if (entry.size < 0L) {
                                    throw IOException("NPU 模型归档条目无效：$name")
                                }
                                extractedBytes += entry.size
                                if (extractedBytes > MAX_QNN_EXTRACTED_BYTES) {
                                    throw IOException("NPU 模型解压后过大，已中止")
                                }
                                output.parentFile?.mkdirs()
                                output.outputStream().buffered().use { sink ->
                                    var remaining = entry.size
                                    val buffer = ByteArray(64 * 1024)
                                    while (remaining > 0L) {
                                        val read = tar.read(
                                            buffer,
                                            0,
                                            minOf(buffer.size.toLong(), remaining).toInt(),
                                        )
                                        if (read < 0) {
                                            throw IOException("NPU 模型归档被截断：$name")
                                        }
                                        sink.write(buffer, 0, read)
                                        remaining -= read
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Whisper bundles ship encoder.bin/decoder.bin; SenseVoice ships a single
            // model.bin. Both keep tokens.txt beside the model they belong to.
            val marker = if (spec.isSenseVoice) "model.bin" else "encoder.bin"
            val anchor = staging.walkTopDown()
                .firstOrNull { it.isFile && it.name == marker }
                ?: throw IOException("NPU 模型归档中未找到 $marker")
            val sourceDir = anchor.parentFile
                ?: throw IOException("NPU 模型归档目录无效")
            val required = if (spec.isSenseVoice) {
                listOf("model.bin", "tokens.txt")
            } else {
                listOf("encoder.bin", "decoder.bin", "tokens.txt")
            }
            val missing = required.filterNot { File(sourceDir, it).isFile }
            if (missing.isNotEmpty()) {
                throw IOException("NPU 模型归档缺少文件：${missing.joinToString("、")}")
            }

            val target = qnnModelDir(spec)
            target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!sourceDir.renameTo(target)) {
                sourceDir.copyRecursively(target, overwrite = true)
                sourceDir.deleteRecursively()
            }
            LogBus.info("model", "NPU 模型已解压到 ${target.absolutePath}")
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun copyUriTo(uri: Uri, temp: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选文件")
        input.use { source ->
            temp.outputStream().use { sink -> source.copyTo(sink) }
        }
    }

    private fun hasGgmlMagic(file: File): Boolean =
        DataInputStream(file.inputStream().buffered()).use { input ->
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

    fun delete(spec: ModelSpec): Boolean {
        if (spec.isQnn) {
            val dir = qnnModelDir(spec)
            val existed = dir.exists()
            dir.deleteRecursively()
            val archive = fileFor(spec)
            File(archive.parentFile, "${archive.name}.part").delete()
            archive.delete()
            if (existed) {
                LogBus.info("model", "已删除 ${spec.name}")
            }
            return existed
        }
        val target = fileFor(spec)
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()
        // For a punctuation model the archive is gone after install, so the file that
        // is actually on disk is the unpacked one.
        val unpackedRemoved =
            if (spec.isPunctuation) File(punctDir, PUNCT_MODEL_NAME).delete() else false
        val deleted = target.delete() || unpackedRemoved
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
        const val MAX_QNN_EXTRACTED_BYTES = 6L * 1024 * 1024 * 1024
        const val PREFS_NAME = "model_prefs"
        const val KEY_MIRROR_PREFIX = "mirror_prefix"

        /** Name sherpa-onnx expects for the ct-transformer punctuation model. */
        const val PUNCT_MODEL_NAME = "model.int8.onnx"
    }
}

private val GITHUB_PROXIES = listOf(
    "https://gh-proxy.com",
    "https://ghproxy.net",
)

/**
 * Download addresses for one manifest URL: the user's own accelerator prefix
 * first, then the built-in GitHub proxies, then the original address.
 */
internal fun resolveModelUrl(url: String, mirror: String): List<String> {
    val result = LinkedHashSet<String>()
    val prefix = mirror.trim().removeSuffix("/")
    if (prefix.isNotBlank()) result += "$prefix/$url"
    if (url.startsWith("https://github.com/")) {
        GITHUB_PROXIES.forEach { result += "$it/$url" }
    }
    result += url
    return result.toList()
}
