package com.pyvideotrans.subtitle.data.asr

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Minimal streaming reader for the 16 kHz mono 16-bit PCM WAV files produced by
 * [com.pyvideotrans.subtitle.data.media.MediaRepository.extractAudio].
 */
class PcmWavReader(file: File) : Closeable {

    private val raf = RandomAccessFile(file, "r")

    val sampleRate: Int
    val channels: Int
    val bitsPerSample: Int
    val dataOffset: Long
    val dataBytes: Long
    val totalSamples: Long

    init {
        try {
            val data = readDataChunk()
            sampleRate = data.format.sampleRate
            channels = data.format.channels
            bitsPerSample = data.format.bitsPerSample
            dataOffset = data.offset
            dataBytes = data.size
            totalSamples = data.size / 2L
            raf.seek(dataOffset)
        } catch (t: Throwable) {
            runCatching { raf.close() }
            throw t
        }
    }

    private fun readDataChunk(): WavData {
        if (raf.length() < 12L) {
            throw IOException("WAV 文件过短")
        }
        val header = ByteArray(12)
        raf.readFully(header)
        val riff = String(header, 0, 4, StandardCharsets.ISO_8859_1)
        val wave = String(header, 8, 4, StandardCharsets.ISO_8859_1)
        if (riff != "RIFF" || wave != "WAVE") {
            throw IOException("不是有效的 WAV 文件")
        }

        var fmt: FmtChunk? = null
        while (raf.filePointer + 8 <= raf.length()) {
            val id = readId()
            val chunkSize = readUInt32()
            val chunkStart = raf.filePointer
            if (id == "fmt ") {
                fmt = readFmt(chunkSize)
            } else if (id == "data") {
                val parsed = fmt ?: throw IOException("WAV 缺少 fmt 块")
                if (parsed.formatTag != FORMAT_PCM || parsed.channels != 1 ||
                    parsed.bitsPerSample != 16 || parsed.sampleRate != 16000
                ) {
                    throw IOException(
                        "WAV 格式不受支持：需要 16kHz 单声道 16-bit PCM，" +
                            "实际 ${parsed.sampleRate}Hz/${parsed.channels}ch/${parsed.bitsPerSample}bit",
                    )
                }
                val size = minOf(chunkSize, raf.length() - chunkStart).coerceAtLeast(0L)
                if (size <= 0L) throw IOException("WAV 音频数据为空")
                return WavData(parsed, chunkStart, size)
            }
            val next = chunkStart + chunkSize + (chunkSize and 1L)
            if (next <= chunkStart || next >= raf.length()) break
            raf.seek(next)
        }
        throw IOException("WAV 缺少音频数据块")
    }

    private data class WavData(
        val format: FmtChunk,
        val offset: Long,
        val size: Long,
    )

    fun readSamples(destination: ShortArray): Int {
        if (destination.isEmpty()) return 0
        val position = raf.filePointer
        if (position < dataOffset) {
            raf.seek(dataOffset)
        }
        val end = dataOffset + dataBytes
        val remaining = (end - raf.filePointer) / 2L
        if (remaining <= 0L) return 0
        val wanted = minOf(destination.size.toLong(), remaining).toInt()
        val buffer = ByteArray(minOf(wanted, 4096) * 2)
        var written = 0

        while (written < wanted) {
            val chunk = minOf(wanted - written, buffer.size / 2)
            val read = raf.read(buffer, 0, chunk * 2)
            if (read <= 0) break
            val samples = read / 2
            for (i in 0 until samples) {
                destination[written + i] =
                    ((buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)).toShort()
            }
            written += samples
        }
        return written
    }

    override fun close() {
        runCatching { raf.close() }
    }

    private fun readId(): String {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return String(bytes, StandardCharsets.ISO_8859_1)
    }

    private fun readUInt32(): Long {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) {
            throw IOException("WAV 文件被截断")
        }
        return b0.toLong() or
            (b1.toLong() shl 8) or
            (b2.toLong() shl 16) or
            (b3.toLong() shl 24)
    }

    private fun readUInt16(): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        if (b0 < 0 || b1 < 0) {
            throw IOException("WAV 文件被截断")
        }
        return b0 or (b1 shl 8)
    }

    private fun readFmt(chunkSize: Long): FmtChunk {
        if (chunkSize < 16L) throw IOException("WAV fmt 块无效")
        val formatTag = readUInt16()
        val channelCount = readUInt16()
        val rate = readUInt32().toInt()
        raf.skipBytes(6)
        val bits = readUInt16()
        var tag = formatTag
        if (formatTag == FORMAT_EXTENSIBLE && chunkSize >= 40L) {
            raf.skipBytes(2) // cbSize
            raf.skipBytes(2) // valid bits per sample
            raf.skipBytes(4) // channel mask
            tag = readUInt16()
        }
        return FmtChunk(
            formatTag = if (formatTag == FORMAT_EXTENSIBLE) tag else formatTag,
            channels = channelCount,
            sampleRate = rate,
            bitsPerSample = bits,
        )
    }

    private data class FmtChunk(
        val formatTag: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
    )

    private companion object {
        const val FORMAT_PCM = 1
        const val FORMAT_EXTENSIBLE = 0xFFFE
    }
}
