package com.pyvideotrans.subtitle.data.asr

import android.content.Context
import android.os.Build
import java.io.File
import java.util.Locale

/**
 * Qualcomm SoCs with an HTP architecture supported by the packaged QNN
 * runtime. Detection prefers Build.SOC_MODEL (API 31+) and falls back to the
 * board/product codenames used by Qualcomm builds.
 */
enum class QnnSoc(
    val socModel: String,
    val label: String,
    val shortLabel: String,
    val htpArch: Int,
    private val aliases: Set<String>,
) {
    SM8850(
        socModel = "SM8850",
        label = "Snapdragon 8 Elite Gen 5",
        shortLabel = "8 Elite Gen 5",
        htpArch = 81,
        aliases = setOf("SM8850", "CANOE"),
    ),
    SM8750(
        socModel = "SM8750",
        label = "Snapdragon 8 Elite",
        shortLabel = "8 Elite",
        htpArch = 79,
        aliases = setOf("SM8750", "SUN"),
    ),
    SM8650(
        socModel = "SM8650",
        label = "Snapdragon 8 Gen 3",
        shortLabel = "8 Gen 3",
        htpArch = 75,
        aliases = setOf("SM8650", "KALAMA", "PINEAPPLE"),
    );

    val skelName: String get() = "libQnnHtpV${htpArch}Skel.so"
    val stubName: String get() = "libQnnHtpV${htpArch}Stub.so"
    val priority: Int get() = ordinal

    fun matches(raw: String): Boolean {
        if (raw.isBlank()) return false
        val upper = raw.uppercase(Locale.US)
        val tokens = upper.split(Regex("[^A-Z0-9]+")).filter { it.isNotBlank() }
        if (tokens.any { it in aliases }) return true
        return aliases.any { alias ->
            alias.startsWith("SM") && (upper == alias || upper.startsWith("$alias-"))
        }
    }

    companion object {
        fun detect(): QnnSoc? {
            val candidates = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Build.SOC_MODEL)
                }
                add(Build.BOARD)
                add(Build.DEVICE)
                add(Build.HARDWARE)
                add(Build.PRODUCT)
            }
            return entries.firstOrNull { soc ->
                candidates.any { soc.matches(it) }
            }
        }

        fun fromSocModel(value: String): QnnSoc? =
            entries.firstOrNull { it.socModel.equals(value, ignoreCase = true) }
    }
}

data class QnnStatus(
    val soc: QnnSoc?,
    val detectedSoc: String,
    val nativeLibraryDir: String,
    val missingLibs: List<String>,
) {
    val ready: Boolean get() = soc != null && missingLibs.isEmpty()

    fun reason(): String = when {
        soc == null -> "当前设备不是受支持的骁龙平台（需要 8 Gen 3 / 8 Elite / 8 Elite Gen 5）"
        missingLibs.isNotEmpty() -> "缺少 NPU 运行库：${missingLibs.joinToString("、")}"
        else -> ""
    }
}

object QnnSupport {

    private val REQUIRED_RUNTIME_LIBS = listOf(
        "libsherpa-onnx-jni.so",
        "libonnxruntime.so",
        "libQnnHtp.so",
        "libQnnSystem.so",
    )

    fun requiredLibs(soc: QnnSoc?): List<String> = buildList {
        addAll(REQUIRED_RUNTIME_LIBS)
        if (soc != null) {
            add(soc.stubName)
            add(soc.skelName)
        }
    }

    /**
     * Libraries the application CPU can load. The Skel image is a Hexagon ELF
     * that only the DSP reads, so dlopen'ing it would always report an error.
     */
    fun hostLoadableLibs(soc: QnnSoc?): List<String> = buildList {
        addAll(REQUIRED_RUNTIME_LIBS)
        if (soc != null) {
            add(soc.stubName)
        }
    }

    fun status(context: Context): QnnStatus {
        val soc = QnnSoc.detect()
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val missing = requiredLibs(soc).filterNot { File(nativeDir, it).isFile }
        val detected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "${Build.BOARD}/${Build.DEVICE}" }
        } else {
            "${Build.BOARD}/${Build.DEVICE}"
        }
        return QnnStatus(
            soc = soc,
            detectedSoc = detected,
            nativeLibraryDir = nativeDir.absolutePath,
            missingLibs = missing,
        )
    }
}
