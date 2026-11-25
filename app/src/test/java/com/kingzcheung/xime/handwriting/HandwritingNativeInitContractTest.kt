package com.kingzcheung.xime.handwriting

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手写引擎初始化必须采纳 native 的返回值。
 *
 * 缺陷：`initialize` 的首次快路径调用 `nativeInitialize(modelPath)` 后无条件
 * `nativeReady = true; return true`。模型损坏/与本机 onnxruntime 不兼容时 native
 * 返回 false，宿主却当成加载成功——手写面板一直在跑但预测恒为空，用户只看到
 * "写不出字"，日志里也没有失败信号。
 *
 * 该 object 的全部关键方法都是 external native，JVM 单测无法替换实现，
 * 因此这里对生产源码做契约检查，钉住「必须采纳返回值」这一不变量。
 */
class HandwritingNativeInitContractTest {
    private val source: String = RepoFiles.mainKotlinSources
        .single { it.name == "HandwritingNativeEngine.kt" }
        .readText()
        .replace("\r\n", "\n")

    private val initializeBody: String = source
        .substringAfter("fun initialize(context: Context, modelPath: String): Boolean {")
        .substringBefore("fun predict(")

    @Test
    fun `first path must adopt the native return value instead of assuming success`() {
        assertFalse(
            "首次快路径不得在调用 native 前就把 nativeReady 置 true",
            initializeBody.contains("nativeInitialize(modelPath)\n            nativeReady = true"),
        )
        assertTrue(
            "首次快路径必须采纳 nativeInitialize 的返回值",
            initializeBody.contains("nativeReady = nativeInitialize(modelPath)"),
        )
    }

    @Test
    fun `unsatisfied link error must clear readiness`() {
        assertTrue(
            "UnsatisfiedLinkError 分支必须把 nativeReady 复位，避免残留假成功状态",
            initializeBody.contains("} catch (e: UnsatisfiedLinkError) {\n            nativeReady = false\n        }"),
        )
    }

    @Test
    fun `both load paths return the native result`() {
        val assignments = Regex("nativeReady = nativeInitialize\\(modelPath\\)").findAll(initializeBody).count()
        assertTrue("两条加载路径都必须采纳 native 返回值，实际 $assignments 处", assignments == 2)
        assertFalse("不得存在忽略返回值后直接置 true 的残余写法", initializeBody.contains("nativeInitialize(modelPath)\n            nativeReady = true"))
    }

    @Test
    fun `predict stays inert when native reports not ready`() {
        val predictBody = source.substringAfter("fun predict(").substringBefore("fun release(")
        val guardIndex = predictBody.indexOf("if (!nativeReady) return emptyArray()")
        val nativeIndex = predictBody.indexOf("nativePredict(")
        assertTrue("未就绪时 predict 必须直接返回空", guardIndex >= 0)
        assertTrue("就绪判定必须先于 native 调用", guardIndex < nativeIndex)
    }
}
