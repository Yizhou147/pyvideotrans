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
    fun manifestHasAsrAndVadModels() {
        val manifest = manifest()
        assertEquals(2, manifest.version)
        assertTrue(manifest.models.isNotEmpty())
        assertTrue(manifest.models.any { it.kind == "asr" })
        assertEquals(1, manifest.models.count { it.isVad })
        assertTrue(manifest.models.any { it.fileName == "ggml-large-v3-turbo-q5_0.bin" })
        assertTrue(manifest.models.any { it.fileName == "ggml-small.bin" })
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
            assertTrue("${spec.id} must be a .bin file", spec.fileName.endsWith(".bin"))
            assertTrue("${spec.id} has no size", spec.sizeBytes > 0)
            assertTrue(
                "${spec.id} sha256 must be 64 hex chars",
                Regex("[0-9a-f]{64}").matches(spec.sha256),
            )
            assertTrue("${spec.id} has no download urls", spec.urls.isNotEmpty())
            spec.urls.forEach { url ->
                assertTrue("${spec.id} must use https: $url", url.startsWith("https://"))
            }
            assertTrue(
                "${spec.id} needs a ModelScope mirror for mainland networks",
                spec.urls.any { it.contains("modelscope.cn") },
            )
            assertTrue("${spec.id} needs a license", spec.license.isNotBlank())
            assertTrue(spec.kind == "asr" || spec.kind == "vad")
        }
    }
}
