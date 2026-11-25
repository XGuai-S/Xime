package com.kingzcheung.xime.service

import com.kingzcheung.xime.RimeNativeSupport
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.AutoWordConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Bug 2 回归：满码唯一自动上屏必须与候选栏排序后的首位一致。
 *
 * 缺陷：`auto_select`（小鹤等顶功方案）由 librime 直接 commit 它的**引擎首选**，
 * 完全绕过 Kotlin 层的词频/手动重排。用户看到的首选是 A，顶屏却是 B——
 * 反馈「自动顶屏没有顶首码，候选排序首码，顶屏却不是首码候选」。
 *
 * 修法：撞码保护不再只看「造词库有没有该码」，还看「排序会不会改变首位」；
 * 命中时改走 setInput，不触发引擎 auto_select。
 */
class AutoSelectTopCandidateTest {
    /**
     * mock<RimeEngine>() 需要实例化该类，其 init 会 loadLibrary("rime_jni")。
     * 仓库只提供 ARM64 Linux/Android 的 .so，本机加载不了时跳过该类的全部用例
     * （Linux CI 与真机 androidTest 上正常执行，覆盖不降低）。
     */
    @org.junit.Before
    fun requireRimeNative() = RimeNativeSupport.assumeAvailable()
    private class Fixture(
        candidates: List<String> = listOf("甲", "乙", "丙"),
        orderTable: Map<String, Map<String, Int>> = emptyMap(),
        freqTable: Map<String, Map<String, Int>> = emptyMap(),
    ) {
        val engine: RimeEngine = mock()
        val service: XimeInputMethodService = mock()
        val router = ImeKeyRouter(service)

        init {
            whenever(service.rimeEngine).thenReturn(engine)
            whenever(service.candidateOrderTable).thenReturn(orderTable)
            whenever(service.wordFreqTable).thenReturn(freqTable)
            whenever(engine.getCandidates()).thenReturn(candidates.toTypedArray())
        }

        fun wouldChangeTop(code: String, cfg: AutoWordConfig): Boolean {
            val m = ImeKeyRouter::class.java
                .getDeclaredMethod("sortWouldChangeTopCandidate", String::class.java, AutoWordConfig::class.java)
            m.isAccessible = true
            return m.invoke(router, code, cfg) as Boolean
        }
    }

    private fun cfg(
        sortByFreq: Boolean = true,
        sortByManual: Boolean = true,
    ) = AutoWordConfig(sortByFreq = sortByFreq, sortByManual = sortByManual)

    @Test
    fun `frequency order that lifts a later candidate above the engine top is detected`() {
        // 引擎顺序 甲/乙/丙，词频里乙最高 → 排序后首位是乙，与引擎首选甲不同。
        // 这种编码一旦满码唯一，引擎会把「甲」顶出去，而用户看到的首选是「乙」。
        val f = Fixture(
            freqTable = mapOf("hckj" to mapOf("乙" to 9)),
        )
        assertTrue(
            "排序把非引擎首选顶到第一位时必须拦截自动上屏",
            f.wouldChangeTop("hckj", cfg()),
        )
    }

    @Test
    fun `frequency order agreeing with the engine top is not blocked`() {
        // 词频首位就是引擎首位（甲）→ 排序不改变结果，无需拦截（保持原生手感）。
        val f = Fixture(
            freqTable = mapOf("hckj" to mapOf("甲" to 9)),
        )
        assertFalse(
            "排序结果与引擎首选一致时不得干预",
            f.wouldChangeTop("hckj", cfg()),
        )
    }

    @Test
    fun `manual order takes precedence over frequency`() {
        // 手动拖动把丙放第一；词频里甲最高。手动优先 → 首位变丙 → 必须拦截。
        val f = Fixture(
            orderTable = mapOf("hckj" to mapOf("丙" to 3, "甲" to 2, "乙" to 1)),
            freqTable = mapOf("hckj" to mapOf("甲" to 9)),
        )
        assertTrue(
            "手动排序改变首位时必须拦截",
            f.wouldChangeTop("hckj", cfg()),
        )
    }

    @Test
    fun `code without any order record is never blocked`() {
        val f = Fixture()
        assertFalse(
            "没有排序记录时不得干预（避免影响普通方案的正常自动上屏）",
            f.wouldChangeTop("hckj", cfg()),
        )
    }

    @Test
    fun `sorting disabled means no interception`() {
        val f = Fixture(
            freqTable = mapOf("hckj" to mapOf("乙" to 9)),
        )
        assertFalse(
            "词频/手动排序都关闭时，候选顺序就是引擎顺序，不该拦截",
            f.wouldChangeTop("hckj", cfg(sortByFreq = false, sortByManual = false)),
        )
    }

    @Test
    fun `single candidate cannot change the top`() {
        val f = Fixture(
            candidates = listOf("甲"),
            freqTable = mapOf("hckj" to mapOf("甲" to 9)),
        )
        assertFalse("只有一个候选时排序无从改变首位", f.wouldChangeTop("hckj", cfg()))
    }

    @Test
    fun `empty code is never blocked`() {
        val f = Fixture(freqTable = mapOf("" to mapOf("乙" to 9)))
        assertFalse(f.wouldChangeTop("", cfg()))
    }

    @Test
    fun `host wires the new guard into the avoid-auto-select branch`() {
        // 接线契约：撞码保护必须把「排序会改首位」纳入判定。
        val source = com.kingzcheung.xime.RepoFiles.mainKotlinSources
            .single { it.name == "ImeKeyRouter.kt" }
            .readText()
            .replace("\r\n", "\n")
        assertTrue(
            "撞码保护必须同时考虑排序首位变化",
            source.contains("sortWouldChangeTopCandidate(nextCode, autoWordConfig)"),
        )
        assertTrue(
            "排序首位变化与造词撞码任一命中都要改走 setInput",
            source.contains("if (hasAutoWord || sortWouldChangeTop)"),
        )
    }
}
