package com.pyvideotrans.subtitle.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ModelManifest(
    val version: Int = 3,
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
) {
    val isVad: Boolean get() = kind == "vad"
    val isVadOnnx: Boolean get() = kind == "vad-onnx"
    val isQnn: Boolean get() = kind == "qnn"
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
