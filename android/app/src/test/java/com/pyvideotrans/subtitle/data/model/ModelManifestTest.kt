package com.pyvideotrans.subtitle.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelManifestTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun manifest(): ModelManifest {
        val file = File("src/main/assets/models.json")
        assertTrue("models.json missing at ${file.absolutePath}", file.isFile)
        return json.decodeFromString(ModelManifest.serializer(), file.readText())
    }

    @Test
    fun manifestHasAllModelKinds() {
        val manifest = manifest()
        assertEquals(4, manifest.version)
        assertTrue(manifest.models.isNotEmpty())
        assertTrue(manifest.models.any { it.kind == "asr" })
        assertEquals(1, manifest.models.count { it.isVad })
        assertEquals(1, manifest.models.count { it.isVadOnnx })
        assertTrue(manifest.models.any { it.fileName == "ggml-large-v3-turbo-q5_0.bin" })
        assertTrue(manifest.models.any { it.fileName == "ggml-small.bin" })
        assertEquals(10, manifest.models.count { it.isQnn })
        // Only the flagship SoC has a SenseVoice context binary published.
        assertEquals(1, manifest.models.count { it.isSenseVoice })
        // SenseVoice emits no punctuation at all, so exactly one mark model exists.
        assertEquals(1, manifest.models.count { it.isPunctuation })
    }

    @Test
    fun qnnModelsCoverEachSupportedSoc() {
        val manifest = manifest()
        QNN_SOCS.forEach { soc ->
            val models = manifest.models.filter { it.isQnn && it.soc == soc }
            assertTrue("$soc needs NPU models", models.isNotEmpty())
            assertEquals("$soc needs tiny/small/medium", 3, models.count { !it.isSenseVoice })
            models.forEach { spec ->
                assertTrue("${spec.id} must be a .tar.bz2 archive", spec.fileName.endsWith(".tar.bz2"))
                assertTrue(
                    "${spec.id} must come from the QNN model release",
                    spec.urls.all { it.contains("asr-models-qnn-binary") },
                )
            }
        }
    }

    @Test
    fun everyModelIsDownloadableAndVerifiable() {
        val manifest = manifest()
        val ids = mutableSetOf<String>()
        val names = mutableSetOf<String>()
        manifest.models.forEach { spec ->
            assertTrue("duplicate id ${spec.id}", ids.add(spec.id))
            assertTrue("duplicate fileName ${spec.fileName}", names.add(spec.fileName))
            assertTrue(spec.id.isNotBlank())
            assertTrue(spec.name.isNotBlank())
            assertTrue("${spec.id} has no size", spec.sizeBytes > 0)
            assertTrue(
                "${spec.id} sha256 must be 64 hex chars",
                Regex("[0-9a-f]{64}").matches(spec.sha256),
            )
            assertTrue("${spec.id} has no download urls", spec.urls.isNotEmpty())
            spec.urls.forEach { url ->
                assertTrue("${spec.id} must use https: $url", url.startsWith("https://"))
            }
            assertTrue("${spec.id} needs a license", spec.license.isNotBlank())
            assertTrue(
                "unexpected kind ${spec.kind}",
                spec.kind in setOf("asr", "vad", "vad-onnx", "qnn", "punct"),
            )
            when (spec.kind) {
                "qnn" -> {
                    assertTrue("${spec.id} needs a soc", spec.soc in QNN_SOCS)
                }
                "asr", "vad" -> {
                    assertTrue("${spec.id} must be a .bin file", spec.fileName.endsWith(".bin"))
                    assertTrue(
                        "${spec.id} needs a ModelScope mirror for mainland networks",
                        spec.urls.any { it.contains("modelscope.cn") },
                    )
                    assertEquals("${spec.id} is used as downloaded", 0L, spec.innerSizeBytes)
                }
                "vad-onnx" -> {
                    assertTrue("${spec.id} must be an .onnx file", spec.fileName.endsWith(".onnx"))
                }
                "punct" -> {
                    // The app unpacks one file out of the archive and runs that, so both
                    // digests have to be in the manifest: the archive for the download,
                    // the inner file for the import-from-PC path.
                    assertTrue("${spec.id} must be a .tar.bz2 archive", spec.fileName.endsWith(".tar.bz2"))
                    assertTrue("${spec.id} needs the unpacked size", spec.innerSizeBytes > spec.sizeBytes)
                    assertTrue(
                        "${spec.id} inner sha256 must be 64 hex chars",
                        Regex("[0-9a-f]{64}").matches(spec.innerSha256),
                    )
                }
            }
        }
    }

    private companion object {
        val QNN_SOCS = setOf("SM8850", "SM8750", "SM8650")
    }
}
