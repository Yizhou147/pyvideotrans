package com.pyvideotrans.subtitle.data.job

import com.pyvideotrans.subtitle.data.asr.TranscriptionResult
import com.pyvideotrans.subtitle.util.LogBus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What a finished recognition step leaves in its job folder: enough to write the subtitles
 * and mux the video without recognising again, if the process was killed in between.
 */
@Serializable
data class JobRecord(
    val jobDir: String,
    val videoPath: String,
    val transcriptionPath: String,
    val videoName: String,
    val chinese: Boolean,
    val savedAtMs: Long = 0L,
) {
    val canFinish: Boolean
        get() = File(videoPath).isFile && File(transcriptionPath).isFile
}

object JobStore {
    const val TRANSCRIPTION_NAME = "transcription.json"
    private const val CHECKPOINT_NAME = "checkpoint.json"
    private val json = Json { ignoreUnknownKeys = true }

    /** A checkpoint is convenience, never a reason to lose a completed recognition run. */
    fun writeTranscription(jobDir: File, result: TranscriptionResult): File? = runCatching {
        val file = File(jobDir, TRANSCRIPTION_NAME)
        file.writeText(json.encodeToString(TranscriptionResult.serializer(), result))
        file
    }.onFailure { LogBus.warn("job", "识别结果未能存档：${it.message}") }.getOrNull()

    fun writeCheckpoint(
        jobDir: File,
        videoFile: File,
        transcriptionFile: File,
        videoName: String,
        chinese: Boolean,
    ): JobRecord = JobRecord(
        jobDir = jobDir.absolutePath,
        videoPath = videoFile.absolutePath,
        transcriptionPath = transcriptionFile.absolutePath,
        videoName = videoName,
        chinese = chinese,
        savedAtMs = System.currentTimeMillis(),
    ).also {
        runCatching {
            File(jobDir, CHECKPOINT_NAME).writeText(json.encodeToString(JobRecord.serializer(), it))
        }.onFailure { error -> LogBus.warn("job", "任务检查点未能写入：${error.message}") }
    }

    fun readTranscription(record: JobRecord): TranscriptionResult? = runCatching {
        json.decodeFromString(
            TranscriptionResult.serializer(),
            File(record.transcriptionPath).readText(),
        )
    }.onFailure { LogBus.warn("job", "读取上次的识别结果失败：${it.message}") }.getOrNull()

    /** Everything in the cache that can still be turned into subtitles. */
    fun pending(cacheDir: File): List<JobRecord> {
        val root = File(cacheDir, "jobs")
        val dirs = root.listFiles().orEmpty().filter { it.isDirectory }
        return dirs.mapNotNull { dir ->
            val file = File(dir, CHECKPOINT_NAME)
            if (!file.isFile) return@mapNotNull null
            runCatching {
                json.decodeFromString(JobRecord.serializer(), file.readText())
            }.onFailure { LogBus.warn("job", "检查点损坏（${dir.name}）：${it.message}") }
                .getOrNull()
        }.filter { it.canFinish }
    }

    /** Outputs stay; only the resume hook goes away. */
    fun dropCheckpoint(jobDir: File) {
        File(jobDir, CHECKPOINT_NAME).delete()
    }

    fun remove(record: JobRecord) {
        File(record.jobDir).deleteRecursively()
    }
}
