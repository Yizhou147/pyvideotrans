package com.pyvideotrans.subtitle.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelUrlCandidatesTest {

    private val githubUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

    private val domesticUrl =
        "https://modelscope.cn/models/viggocx/whisper.cpp/resolve/master/ggml-small.bin"

    @Test
    fun githubUrlsGetProxiesBeforeDirect() {
        val urls = resolveModelUrl(githubUrl, "")
        assertEquals(githubUrl, urls.last())
        assertEquals(3, urls.size)
        assertTrue(urls[0].endsWith("/$githubUrl"))
    }

    @Test
    fun customMirrorIsTriedFirst() {
        val urls = resolveModelUrl(githubUrl, "https://mirror.example.com/")
        assertEquals("https://mirror.example.com/$githubUrl", urls.first())
        assertEquals(4, urls.size)
    }

    @Test
    fun nonGithubUrlsAreNotPrefixedWithGithubProxies() {
        assertEquals(listOf(domesticUrl), resolveModelUrl(domesticUrl, ""))
    }

    @Test
    fun manifestQnnAndVadOnnxUseGithub() {
        val text = File("src/main/assets/models.json").readText()
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString(ModelManifest.serializer(), text)
        val githubSpecs = manifest.models.filter { it.isQnn || it.isVadOnnx }
        assertEquals(11, githubSpecs.size)
        githubSpecs.forEach { spec ->
            assertTrue(spec.name, spec.urls.single().startsWith("https://github.com/"))
            assertTrue(spec.name, resolveModelUrl(spec.urls.single(), "").size > 1)
        }
    }
}
