package com.kingzcheung.xime.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 生效模型的选择规则：只从已安装模型里选，未下载任何模型时不指定模型。
 */
class AsrModelManagerSelectionTest {

    private fun model(id: String) = AsrModelManager.AsrModelInfo(
        id = id,
        name = id,
        language = "zh",
        size = "1MB",
        downloadUrl = "https://example.com/$id.tar.bz2",
        files = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt")
    )

    private val zipformer = model("zipformer-zh-int8")
    private val xAsr = model("x-asr-480ms-zh-en-punct-int8")

    @Test
    fun `no installed model means no effective model`() {
        val effective = AsrModelManager.pickEffectiveModel(listOf(zipformer, xAsr), null) { false }
        assertNull(effective)
    }

    @Test
    fun `only installed model becomes effective without explicit selection`() {
        val effective = AsrModelManager.pickEffectiveModel(listOf(xAsr, zipformer), null) { it == xAsr.id }
        assertEquals(xAsr.id, effective?.id)
    }

    @Test
    fun `selected model wins when installed`() {
        val effective = AsrModelManager.pickEffectiveModel(listOf(zipformer, xAsr), xAsr.id) { true }
        assertEquals(xAsr.id, effective?.id)
    }

    @Test
    fun `selected but uninstalled model falls back to installed model`() {
        val effective = AsrModelManager.pickEffectiveModel(listOf(zipformer, xAsr), zipformer.id) { it == xAsr.id }
        assertEquals(xAsr.id, effective?.id)
    }

    @Test
    fun `fallback order follows registry order`() {
        // 两个都装了且未选择过：按注册表顺序取 zipformer，保证主进程与 :asr 进程一致
        val effective = AsrModelManager.pickEffectiveModel(listOf(xAsr, zipformer), null) { true }
        assertEquals(zipformer.id, effective?.id)
    }
}
