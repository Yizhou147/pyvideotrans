package com.pyvideotrans.subtitle.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun bytesUsesBinaryUnits() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1024L * 1024L))
        assertEquals("1.00 GB", formatBytes(1024L * 1024L * 1024L))
        assertEquals("547.45 MB", formatBytes(574_041_195))
    }

    @Test
    fun durationOmitsHoursWhenZero() {
        assertEquals("00:00", formatDurationMs(0))
        assertEquals("00:01", formatDurationMs(1_000))
        assertEquals("01:01", formatDurationMs(61_000))
        assertEquals("1:01:01", formatDurationMs(3_661_000))
        assertEquals("00:00", formatDurationMs(-500))
    }

    @Test
    fun timestampAlwaysHasHoursAndMillis() {
        assertEquals("00:00:00.000", formatTimestampMs(0))
        assertEquals("00:00:14.000", formatTimestampMs(14_000))
        assertEquals("01:01:01.001", formatTimestampMs(3_661_001))
        assertEquals("00:00:05.999", formatTimestampMs(5_999))
    }

    @Test
    fun percentIsPlain() {
        assertEquals("100%", formatPercent(100))
    }
}
