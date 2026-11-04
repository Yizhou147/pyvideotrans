package com.pyvideotrans.subtitle.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ModelManifest(
    val version: Int = 4,
    val models: List<ModelSpec> = emptyList(),
)

@Serializable
data class ModelSpec(
    val id: String,
    val kind: String,
    val name: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val urls: List<String>,
    val license: String = "",
    val description: String = "",
    /** SoC identifier of a QNN bundle, e.g. SM8850. Empty for GGML models. */
    val soc: String = "",
    /**
     * Which recogniser a QNN bundle holds: "whisper" (the default, encoder.bin +
     * decoder.bin) or "sense-voice" (one model.bin, and word-level timestamps).
     */
    val family: String = "whisper",
    /**
     * The file the archive is unpacked to, for kinds whose download is an archive
     * but whose runtime needs one file (the punctuation model). Zero when the
     * downloaded file is used as it is.
     */
    val innerSizeBytes: Long = 0,
    /** SHA-256 of [innerSizeBytes] bytes of the unpacked file, so an import can be verified. */
    val innerSha256: String = "",
) {
    val isVad: Boolean get() = kind == "vad"
    val isVadOnnx: Boolean get() = kind == "vad-onnx"
    val isQnn: Boolean get() = kind == "qnn"
    val isPunctuation: Boolean get() = kind == "punct"
    val isSenseVoice: Boolean get() = isQnn && family == "sense-voice"
}

data class LocalModel(
    val file: java.io.File,
    val displayName: String,
    val spec: ModelSpec?,
    val custom: Boolean,
)

data class LocalQnnModel(
    val spec: ModelSpec,
    val dir: java.io.File,
)

data class QnnModelFiles(
    val dir: java.io.File,
    val encoder: java.io.File,
    val decoder: java.io.File,
    val tokens: java.io.File,
)

data class QnnSenseVoiceFiles(
    val dir: java.io.File,
    val model: java.io.File,
    val tokens: java.io.File,
)
