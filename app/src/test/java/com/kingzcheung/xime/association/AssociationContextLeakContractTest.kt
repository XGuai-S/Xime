package com.kingzcheung.xime.association

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联想单例只能长期持有应用上下文。
 *
 * 缺陷：PredictionManager 传入的是输入法服务实例；ModelRuntime.register 对重复 id 直接跳过，
 * loader 闭包被永久持有。闭包、NgramFusionEngine、InferenceClient 捕获原始 Context 会把
 * 已销毁的服务钉在内存里，之后重载还会拿它去 bindService。
 *
 * 两个类都是进程级 object，依赖 Android 运行时与跨进程服务，这里对生产源码做契约检查。
 */
class AssociationContextLeakContractTest {
    private fun source(name: String): String = RepoFiles.mainKotlinSources
        .single { it.name == name }
        .readText()
        .replace("\r\n", "\n")

    private val managerInit: String = source("AssociationManager.kt")
        .substringAfter("suspend fun initialize(ctx: Context): Boolean")
        .substringBefore("suspend fun predict(")

    private val engineInit: String = source("OnnxAssociationEngine.kt")
        .substringAfter("suspend fun initialize(context: Context): Boolean {")
        .substringBefore("suspend fun predict(")

    @Test
    fun `manager captures only application context`() {
        assertTrue(managerInit.contains("val appContext = ctx.applicationContext"))
        assertTrue(managerInit.contains("NgramFusionEngine(appContext)"))
        assertTrue(managerInit.contains("loader = { OnnxAssociationEngine.initialize(appContext) }"))
        assertFalse("NgramFusionEngine 不得持有原始 Context", managerInit.contains("NgramFusionEngine(ctx)"))
        assertFalse("loader 不得捕获原始 Context", managerInit.contains("initialize(ctx)"))
    }

    @Test
    fun `engine captures only application context`() {
        assertTrue(engineInit.contains("val appContext = context.applicationContext"))
        assertTrue(engineInit.contains("loader = { initialize(appContext) }"))
        assertTrue(engineInit.contains("InferenceClient(appContext)"))
        assertFalse("loader 不得捕获原始 Context", engineInit.contains("loader = { initialize(context) }"))
        assertFalse("InferenceClient 不得持有原始 Context", engineInit.contains("InferenceClient(context)"))
    }

    @Test
    fun `application context is derived before register`() {
        val derive = engineInit.indexOf("val appContext = context.applicationContext")
        val register = engineInit.indexOf("ModelRuntime.register(")
        assertTrue(derive in 0 until register)
    }
}
