package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CapitalAmountInputState] 的逐键交互测试。
 *
 * 用一个内存字符串当「输入框」跑完整交互序列，验证每一步之后输入框里应该是什么。
 * 这是本功能唯一可行的端到端验证方式：真机上「长按 5 → 输数字 → 看金额」不可自动化回归，
 * 而该模式的正确性恰恰体现在**一整串交互的结果**上，不在单个按键上。
 *
 * 模拟的输入框遵循 `replaceBeforeCursor` 的语义：仅当光标前文本恰好等于 expected 时
 * 才替换，否则失败（用户移动过光标/退格过）。
 */
class CapitalAmountInputStateTest {

    /** 模拟输入框：维护一段文本与一个光标位置。 */
    private class FakeEditor {
        val text = StringBuilder()
        var cursor = 0

        /** 等价于 replaceBeforeCursor(expected, replacement)：校验后再替换。 */
        fun write(expected: String, replacement: String): Boolean {
            val start = cursor - expected.length
            if (start < 0) return false
            if (text.substring(start, cursor) != expected) return false
            text.replace(start, cursor, replacement)
            cursor = start + replacement.length
            return true
        }

        /** 宿主自身处理按键（未进入大写模式时走这条路）。 */
        fun passthrough(key: String) {
            when (key) {
                "delete" -> if (cursor > 0) {
                    text.deleteCharAt(cursor - 1)
                    cursor--
                }
                "space" -> append(" ")
                "clear_all" -> {
                    text.setLength(0)
                    cursor = 0
                }
                else -> append(key)
            }
        }

        private fun append(s: String) {
            text.insert(cursor, s)
            cursor += s.length
        }

        override fun toString(): String = text.toString()
    }

    private var active = false
    private val editor = FakeEditor()
    private val state = CapitalAmountInputState(isActive = { active }, setActive = { active = it })
    private val passedThrough = mutableListOf<String>()

    /** 按下一键（走状态机，与真机同一路径）。 */
    private fun press(key: String) {
        state.press(
            key = key,
            write = { expected, replacement -> editor.write(expected, replacement) },
            passthrough = {
                passedThrough.add(it)
                editor.passthrough(it)
            },
        )
    }

    private fun type(keys: String) = keys.forEach { press(it.toString()) }

    // ── 进入/退出 ──

    @Test
    fun `未进入模式时按键原样交回宿主`() {
        type("123")
        assertEquals("123", editor.toString())
        assertTrue("未进入模式不应有任何替换动作", passedThrough.containsAll(listOf("1", "2", "3")))
    }

    @Test
    fun `长按 5 进入模式后键面数字转大写`() {
        state.toggle()
        assertTrue(active)
        assertEquals("伍", CapitalAmountKeys.face("5", active))
    }

    @Test
    fun `再次长按退出模式`() {
        state.toggle()
        state.toggle()
        assertFalse(active)
        // 退出后已上屏的金额保留，后续按键回到普通路径
        press("7")
        assertEquals("7", editor.toString())
    }

    @Test
    fun `exit 结束输入后不反向进入模式`() {
        // 候选栏点选结果意味着「这笔金额输完了」。
        // 反证：若这里复用 toggle，用户点完会被反向按回模式里，
        // 下一个数字又接在旧算式上继续算（且屏幕上看不出还在算）
        state.toggle()
        assertTrue(active)
        type("123")
        state.exit()
        assertFalse("exit 后必须退出模式", active)
        assertEquals("", state.raw)
        assertEquals("", state.committed)
        // 已上屏金额保留，后续按键回到普通路径
        press("7")
        assertEquals("壹佰贰拾叁元整7", editor.toString())
    }

    @Test
    fun `exit 作废累积基线不让下一个数字接旧算式`() {
        state.toggle()
        type("123+45")
        assertEquals("壹佰陆拾捌元整", editor.toString())
        state.exit()
        // 重新进入后必须是一笔新金额，而不是接着 123+45 算
        state.toggle()
        type("9")
        assertEquals("壹佰陆拾捌元整玖元整", editor.toString())
    }

    @Test
    fun `未进入模式时 exit 无副作用`() {
        type("12")
        state.exit()
        assertFalse(active)
        assertEquals("12", editor.toString())
    }

    // ── 核心：逐键自动补全位值 ──

    @Test
    fun `依次按 1 2 3 每按一键整串重写`() {
        state.toggle()
        press("1")
        assertEquals("壹元整", editor.toString())
        press("2")
        assertEquals("壹拾贰元整", editor.toString())
        press("3")
        assertEquals("壹佰贰拾叁元整", editor.toString())
    }

    @Test
    fun `输入含零金额自动补零`() {
        state.toggle()
        type("1005")
        assertEquals("壹仟零伍元整", editor.toString())
    }

    @Test
    fun `输入跨节金额自动补节权与零`() {
        state.toggle()
        type("100000001")
        assertEquals("壹亿零壹元整", editor.toString())
    }

    @Test
    fun `输入小数得到角分`() {
        state.toggle()
        type("123.45")
        assertEquals("壹佰贰拾叁元肆角伍分", editor.toString())
    }

    @Test
    fun `角位为零而分位非零时元后补零`() {
        state.toggle()
        type("123.05")
        assertEquals("壹佰贰拾叁元零伍分", editor.toString())
    }

    @Test
    fun `先按小数点再按数字得到角分而不是元`() {
        // 仅按下小数点时不上屏，但累积必须记住它
        state.toggle()
        press(".")
        assertEquals("", editor.toString())
        press("5")
        assertEquals("伍角整", editor.toString())
    }

    // ── 退格 ──

    @Test
    fun `退格逐位回退并同步重写`() {
        state.toggle()
        type("123")
        assertEquals("壹佰贰拾叁元整", editor.toString())
        press("delete")
        assertEquals("壹拾贰元整", editor.toString())
        press("delete")
        assertEquals("壹元整", editor.toString())
    }

    @Test
    fun `退格清空累积后留在模式内并把退格交回宿主`() {
        // 先有宿主内容，再进入模式输一位、退两格
        press("9")
        assertEquals("9", editor.toString())
        state.toggle()
        press("1")
        assertEquals("9壹元整", editor.toString())

        press("delete")
        assertEquals("9", editor.toString())
        press("delete")
        // 第二次退格：累积已空 → 交回宿主删掉 "9"，但留在模式内（只能按 0 切回）
        assertEquals("", editor.toString())
        assertTrue("累积用尽后仍应留在模式内", active)
    }

    @Test
    fun `退格到仅剩小数点时不上屏但仍保留累积`() {
        state.toggle()
        type("1.5")
        assertEquals("壹元伍角整", editor.toString())
        press("delete")
        // 回退到 "1."：金额回到壹元整
        assertEquals("壹元整", editor.toString())
        press("delete")
        // 回退到 "1"
        assertEquals("壹元整", editor.toString())
        assertTrue("仍在模式内", active)
    }

    // ── 加减乘除 ──

    @Test
    fun `加法按结果上屏大写金额`() {
        state.toggle()
        type("123")
        assertEquals("壹佰贰拾叁元整", editor.toString())
        press("+")
        // 运算符本身不改变数值：屏幕暂时不动
        assertEquals("壹佰贰拾叁元整", editor.toString())
        press("4")
        // 123+4 = 127
        assertEquals("壹佰贰拾柒元整", editor.toString())
        press("5")
        // 123+45 = 168
        assertEquals("壹佰陆拾捌元整", editor.toString())
        assertTrue("运算符不应退出模式", active)
    }

    @Test
    fun `链式运算按优先级求值`() {
        state.toggle()
        type("4*5+2")
        // 4*5+2 = 22（先乘后加）
        assertEquals("贰拾贰元整", editor.toString())
    }

    @Test
    fun `小数算式按结果上屏`() {
        state.toggle()
        type("0.1+0.2")
        // 浮点误差需被消掉，否则会得到 0.30000000000000004
        assertEquals("叁角整", editor.toString())
    }

    @Test
    fun `减法得到负数时输出「负…」大写而不是当成正数`() {
        // 反证：旧实现把负数当「无法表示」直接退出模式并上屏 -50；
         // 需求是输出大写负数结果，所以这里必须得到「负…」且留在模式内。
        state.toggle()
        type("100-15")
        assertEquals("捌拾伍元整", editor.toString())
        press("0")
        // 100-150 = -50：必须在金额前带「负」，不能静默当成 +50（错钱）
        assertEquals("负伍拾元整", editor.toString())
        assertTrue("负数仍可继续输入，不应退出模式", active)
    }

    @Test
    fun `运算符不能作为算式开头`() {
        state.toggle()
        press("+")
        assertEquals("", editor.toString())
        assertTrue("无效输入不应退出模式", active)
        // 之后仍可正常输入金额
        press("5")
        assertEquals("伍元整", editor.toString())
    }

    @Test
    fun `连续运算符被拒绝`() {
        state.toggle()
        type("123+")
        assertEquals("壹佰贰拾叁元整", editor.toString())
        press("*")
        // 想换运算符应先退格；这里不得把算式变成 123+*
        assertEquals("壹佰贰拾叁元整", editor.toString())
        press("4")
        assertEquals("壹佰贰拾柒元整", editor.toString())
    }

    @Test
    fun `第二个操作数可以重新使用小数点`() {
        state.toggle()
        type("1.5+2.5")
        assertEquals("肆元整", editor.toString())
    }

    @Test
    fun `退格回退算式并重新求值`() {
        state.toggle()
        type("123+45")
        assertEquals("壹佰陆拾捌元整", editor.toString())
        press("delete")
        // 123+4 = 127
        assertEquals("壹佰贰拾柒元整", editor.toString())
        press("delete")
        press("delete")
        // 退掉运算符，回到 123
        assertEquals("壹佰贰拾叁元整", editor.toString())
        assertTrue("仍在模式内", active)
    }

    @Test
    fun `退格删掉运算符后金额不变但仍留在模式内`() {
        state.toggle()
        type("123+")
        assertEquals("壹佰贰拾叁元整", editor.toString())
        press("delete")
        assertEquals("壹佰贰拾叁元整", editor.toString())
        assertTrue("仍在模式内", active)
        press("4")
        assertEquals("壹仟贰佰叁拾肆元整", editor.toString())
    }

    // ── 其它按键退出模式 ──

    @Test
    fun `空格不退出模式而确定退出并终结本次金额`() {
        // 空格：只有上屏+作废累积，不退出模式；确定仍退出并终结本次金额
        listOf("space", "enter").forEach { key ->
            active = false
            editor.text.setLength(0)
            editor.cursor = 0
            passedThrough.clear()
            state.toggle()
            press("1")
            assertEquals("壹元整", editor.toString())
            press(key)
            if (key == "space") {
                assertTrue("空格不应退出模式（模式只能由 0 键切换）", active)
            } else {
                assertFalse("$key 应退出模式", active)
            }
            assertTrue("$key 应交回宿主", passedThrough.contains(key))
        }
    }

    @Test
    fun `空格不退出模式且下一个数字作为新金额追加`() {
        state.toggle()
        type("123")
        assertEquals("壹佰贰拾叁元整", editor.toString())
        press("space")
        assertEquals("壹佰贰拾叁元整 ", editor.toString())

        // 空格不退出模式：下一个数字直接作为新金额追加在后面
        assertTrue("空格后仍在模式内", active)
        press("5")
        // 旧金额保留，新金额追加在后面（不是误删旧文本）
        assertEquals("壹佰贰拾叁元整 伍元整", editor.toString())
    }

    @Test
    fun `只有 0 键切换模式而空格与退格都不退出`() {
        state.toggle()
        assertTrue(active)
        press("1")
        press("space")
        assertTrue("空格后仍在模式内", active)
        press("delete")
        assertTrue("退格后仍在模式内", active)
        state.toggle()
        assertFalse("0 键才能切回普通模式", active)
    }

    @Test
    fun `退出模式后重新进入从新金额开始`() {
        state.toggle()
        type("123")
        state.toggle()
        assertFalse(active)
        state.toggle()
        press("5")
        // 旧金额保留，新金额追加在后面（不是误删旧文本）
        assertEquals("壹佰贰拾叁元整伍元整", editor.toString())
    }

    // ── 光标被移动时的安全性 ──

    @Test
    fun `光标被移动后不误删旧文本而是追加新金额`() {
        state.toggle()
        type("123")
        assertEquals("壹佰贰拾叁元整", editor.toString())

        // 用户在输入框里把光标移到开头（模拟点击定位）
        editor.cursor = 0
        press("5")

        // 关键：不得删除任何已有内容；本次按键作为一笔**新金额**的开头追加在光标处
        assertEquals("伍元整壹佰贰拾叁元整", editor.toString())
    }

    @Test
    fun `光标移动后本次按键成为新金额的开头`() {
        state.toggle()
        type("123")
        editor.cursor = 0
        press("5")
        assertEquals("伍元整壹佰贰拾叁元整", editor.toString())
        // 基线已重开为一笔新金额，继续按键应重写「伍元整」而不是再去碰旧金额
        press("6")
        assertEquals("伍拾陆元整壹佰贰拾叁元整", editor.toString())
    }

    @Test
    fun `基线失效时退格交回宿主但留在模式内`() {
        // 基线失效后无法安全重写金额，若把退格吞掉用户就会「按了没反应」
        state.toggle()
        type("123")
        assertEquals("壹佰贰拾叁元整", editor.toString())
        editor.cursor = 0
        press("delete")
        assertEquals("壹佰贰拾叁元整", editor.toString())
        // 光标在开头，宿主退格也删不到东西；关键是仍留在模式内
        assertTrue("基线失效后退格仍应留在模式内", active)
    }

    // ── 输入被拒时不产生"按了没反应"的错觉 ──

    @Test
    fun `重复小数点被拒绝且不改动输入框`() {
        state.toggle()
        type("1.5")
        assertEquals("壹元伍角整", editor.toString())
        press(".")
        assertEquals("壹元伍角整", editor.toString())
    }

    @Test
    fun `第三位小数被拒绝且不改动输入框`() {
        state.toggle()
        type("1.23")
        assertEquals("壹元贰角叁分", editor.toString())
        press("4")
        assertEquals("壹元贰角叁分", editor.toString())
    }

    // ── 反证：状态机必须真的与输入框交互 ──

    @Test
    fun `写入失败时不推进累积`() {
        // 不变量：只有真正写进输入框才推进累积，否则后续按键会去替换一段从未上屏的文本。
        // 用一个永远失败的输入框验证：raw 始终为空，每次按键都以空基线重试追加。
        val s = CapitalAmountInputState(isActive = { true }, setActive = {})
        val writes = mutableListOf<Pair<String, String>>()
        fun pressFail(key: String) {
            s.press(
                key = key,
                write = { e, r ->
                    writes.add(e to r)
                    false
                },
                passthrough = {},
            )
        }
        pressFail("1")
        pressFail("2")
        assertEquals("写入失败时累积不得前进", "", s.raw)
        assertEquals("", s.committed)
        assertEquals(listOf("" to "壹元整", "" to "贰元整"), writes)
    }
}
