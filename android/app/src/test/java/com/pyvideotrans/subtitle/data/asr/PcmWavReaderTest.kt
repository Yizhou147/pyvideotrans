package com.pyvideotrans.subtitle.data.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

class PcmWavReaderTest {

    @Test
    fun readsMonoPcm16InChunks() {
        val samples = ShortArray(5000) { (it * 7 - 3000).toShort() }
        val file = wavFile(samples)
        try {
            PcmWavReader(file).use { reader ->
                assertEquals(16000, reader.sampleRate)
                assertEquals(1, reader.channels)
                assertEquals(16, reader.bitsPerSample)
                assertEquals(samples.size.toLong(), reader.totalSamples)

                val actual = ShortArray(samples.size)
                var written = 0
                val buffer = ShortArray(777)
                while (true) {
                    val read = reader.readSamples(buffer)
                    if (read <= 0) break
                    buffer.copyInto(actual, written, 0, read)
                    written += read
                }
                assertEquals(samples.size, written)
                assertArrayEquals(samples, actual)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun skipsUnknownChunksBeforeData() {
        val samples = ShortArray(600) { (it * 3).toShort() }
        val file = wavFile(samples, extraChunkBeforeData = true)
        try {
            PcmWavReader(file).use { reader ->
                assertEquals(samples.size.toLong(), reader.totalSamples)
                val actual = ShortArray(samples.size)
                assertEquals(samples.size, reader.readSamples(actual))
                assertArrayEquals(samples, actual)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun acceptsWaveFormatExtensible() {
        val samples = ShortArray(256) { it.toShort() }
        val file = wavFile(samples, extensible = true)
        try {
            PcmWavReader(file).use { reader ->
                assertEquals(samples.size.toLong(), reader.totalSamples)
                val actual = ShortArray(samples.size)
                assertEquals(samples.size, reader.readSamples(actual))
                assertArrayEquals(samples, actual)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsNonMonoOrWrongRate() {
        val file = wavFile(ShortArray(128), channels = 2, sampleRate = 44100)
        try {
            var failed = false
            try {
                PcmWavReader(file).use { }
            } catch (e: IOException) {
                failed = true
                assertTrue(e.message.orEmpty().contains("16kHz"))
            }
            assertTrue("stereo 44.1kHz WAV must be rejected", failed)
        } finally {
            file.delete()
        }
    }

    private fun wavFile(
        samples: ShortArray,
        channels: Int = 1,
        sampleRate: Int = 16000,
        bitsPerSample: Int = 16,
        extensible: Boolean = false,
        extraChunkBeforeData: Boolean = false,
    ): File {
        val file = File.createTempFile("pcm-wav-", ".wav")
        val fmtSize = if (extensible) 40 else 16
        val extraSize = if (extraChunkBeforeData) 12 else 0
        val dataSize = samples.size * 2
        val riffSize = 4 + 8 + fmtSize + extraSize + 8 + dataSize

        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeBytes("RIFF")
            writeLe32(out, riffSize)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            writeLe32(out, fmtSize)
            writeLe16(out, if (extensible) 0xFFFE else 1)
            writeLe16(out, channels)
            writeLe32(out, sampleRate)
            writeLe32(out, sampleRate * channels * bitsPerSample / 8)
            writeLe16(out, channels * bitsPerSample / 8)
            writeLe16(out, bitsPerSample)
            if (extensible) {
                writeLe16(out, 22)
                writeLe16(out, bitsPerSample)
                writeLe32(out, if (channels == 1) 4 else 3)
                writeLe16(out, 1)
                repeat(14) { out.writeByte(0) }
            }
            if (extraChunkBeforeData) {
                out.writeBytes("LIST")
                writeLe32(out, 4)
                out.writeBytes("INFO")
            }
            out.writeBytes("data")
            writeLe32(out, dataSize)
            samples.forEach { value ->
                writeLe16(out, value.toInt() and 0xFFFF)
            }
        }
        return file
    }

    private fun writeLe16(out: DataOutputStream, value: Int) {
        out.writeByte(value and 0xFF)
        out.writeByte((value ushr 8) and 0xFF)
    }

    private fun writeLe32(out: DataOutputStream, value: Int) {
        out.writeByte(value and 0xFF)
        out.writeByte((value ushr 8) and 0xFF)
        out.writeByte((value ushr 16) and 0xFF)
        out.writeByte((value ushr 24) and 0xFF)
    }
}
