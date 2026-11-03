package com.pyvideotrans.subtitle.data.asr

/**
 * The resegmented cues are only trustworthy when every spoken chunk reported word times: a chunk
 * without them would enter the timeline as one multi-second block, so a single miss sends the
 * whole run back to the segment-level route the whisper engine has always used.
 */
internal fun isFinelySegmented(tokenCount: Int, timestamplessChunks: Int): Boolean =
    tokenCount > 0 && timestamplessChunks == 0
