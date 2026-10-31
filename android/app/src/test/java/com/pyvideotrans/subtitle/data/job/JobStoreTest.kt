package com.pyvideotrans.subtitle.data.job

import com.pyvideotrans.subtitle.data.asr.TranscriptionResult
import com.pyvideotrans.subtitle.data.asr.TranscriptionSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JobStoreTest {

    private val transcription = TranscriptionResult(
        language = "zh",
        audioMs = 4_000L,
        segments = listOf(TranscriptionSegment(0L, 1_200L, "第一句"), TranscriptionSegment(1_200L, 4_000L, "第二句")),
    )

    @Test
    fun checkpointRoundTripsTheTranscription() {
        val cache = createTempDir()
        try {
            val jobDir = File(cache, "jobs/123").apply { mkdirs() }
            val video = File(jobDir, "video.mp4").apply { writeText("x") }
            val saved = JobStore.writeTranscription(jobDir, transcription)
            assertTrue(saved != null && saved.isFile)

            val record = JobStore.writeCheckpoint(
                jobDir = jobDir,
                videoFile = video,
                transcriptionFile = saved!!,
                videoName = "video.mp4",
                chinese = true,
            )
            assertEquals(
                transcription,
                JobStore.readTranscription(record),
            )

            val pending = JobStore.pending(cache)
            assertEquals(1, pending.size)
            assertEquals("video.mp4", pending.first().videoName)

            JobStore.dropCheckpoint(jobDir)
            assertTrue(JobStore.pending(cache).isEmpty())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun incompleteCheckpointsAreNotOffered() {
        val cache = createTempDir()
        try {
            val jobDir = File(cache, "jobs/456").apply { mkdirs() }
            val saved = JobStore.writeTranscription(jobDir, transcription)!!
            JobStore.writeCheckpoint(
                jobDir = jobDir,
                videoFile = File(jobDir, "gone.mp4"),
                transcriptionFile = saved,
                videoName = "gone.mp4",
                chinese = true,
            )
            assertTrue(JobStore.pending(cache).isEmpty())
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun createTempDir(): File = Files.createTempDirectory("subtitle-jobs").toFile()
}
