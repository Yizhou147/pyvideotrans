package com.pyvideotrans.subtitle.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ModelManifest(
    val version: Int = 1,
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
    val url: String,
    val license: String = "",
    val description: String = "",
) {
    val isVad: Boolean get() = kind == "vad"
}

data class LocalModel(
    val file: java.io.File,
    val displayName: String,
    val spec: ModelSpec?,
    val custom: Boolean,
)
