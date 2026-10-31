package com.pyvideotrans.subtitle.util

import java.io.File
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhVariantsTest {

    private val miniTable by lazy {
        ZhVariants.parse(
            StringReader(
                """
                後	后
                乾	干
                乾隆	乾隆
                頭髮	头发
                發	发
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `phrase entries win over single characters`() {
        assertEquals("乾隆皇帝头发在后面", ZhVariants.convert(miniTable, "乾隆皇帝頭髮在後面"))
    }

    @Test
    fun `already simplified text is untouched`() {
        assertEquals("大家没做过过去睡", ZhVariants.convert(miniTable, "大家没做过过去睡"))
    }

    @Test
    fun `non chinese text is untouched`() {
        assertEquals("Hello, world! 123", ZhVariants.convert(miniTable, "Hello, world! 123"))
    }

    @Test
    fun `shipped dictionary converts real whisper output`() {
        val table = ZhVariants.parse(StringReader(shippedDictionary()))
        assertTrue("词典过小：${table.size}", table.size > 3000)
        assertEquals(
            "各位同学提到这个体积重一点,完全是可以跟需要转换,我需要加快速度,可以去换成谁",
            ZhVariants.convert(
                table,
                "各位同學提到這個體積重一點,完全是可以跟需要轉換,我需要加快速度,可以去換成誰",
            ),
        )
        assertEquals("当前", ZhVariants.convert(table, "當前"))
        assertEquals("国际", ZhVariants.convert(table, "國際"))
        assertEquals("那个", ZhVariants.convert(table, "那個"))
        assertEquals("乾隆皇帝", ZhVariants.convert(table, "乾隆皇帝"))
    }

    /** Unit tests run with the module directory as working directory. */
    private fun shippedDictionary(): String {
        val candidates = listOf(
            File("src/main/assets/opencc/t2s.tsv"),
            File("app/src/main/assets/opencc/t2s.tsv"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 t2s.tsv 词典：${candidates.map { it.absolutePath }}")
        return file.readText()
    }
}
