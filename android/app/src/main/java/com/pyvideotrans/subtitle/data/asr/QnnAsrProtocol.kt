package com.pyvideotrans.subtitle.data.asr

import kotlinx.serialization.Serializable

data class QnnRequest(
    val modelName: String,
    val encoderPath: String,
    val decoderPath: String,
    val tokensPath: String,
    val vadPath: String,
    val wavPath: String,
    val resultPath: String,
    val language: String,
    val threads: Int,
)

@Serializable
internal data class QnnResultPayload(
    val result: TranscriptionResult,
    val elapsedMs: Long,
    val modelLoadMs: Long,
)

class QnnProcessDiedException(message: String) : IllegalStateException(message)

internal object QnnAsrProtocol {
    const val MSG_START = 1
    const val MSG_CANCEL = 2
    const val MSG_PROGRESS = 3
    const val MSG_LOG = 4
    const val MSG_RESULT = 5
    const val MSG_ERROR = 6
    const val MSG_CANCELLED = 7

    const val KEY_MODEL_NAME = "model_name"
    const val KEY_ENCODER = "encoder"
    const val KEY_DECODER = "decoder"
    const val KEY_TOKENS = "tokens"
    const val KEY_VAD = "vad"
    const val KEY_WAV = "wav"
    const val KEY_RESULT_PATH = "result_path"
    const val KEY_LANGUAGE = "language"
    const val KEY_THREADS = "threads"
    const val KEY_LOG = "log"
    const val KEY_ERROR = "error"
}
