package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [CapitalAmountKeys] 的键面/角标规则测试。
 *
 * 对应三条用户可见需求：
 * - 普通模式只有「0」键带大写角标「零」（模式开关的指示）；
 * - 大写模式**全部**数字键带角标，内容为原数字（键面已是壹贰叁…的对照表）；
 * - 小数点等非数字键在任何模式下都不带角标。
 *
 * 角标范围错了不会崩，只会让用户看不出哪个键能切模式（普通模式全键带角标），
 * 或者看不出壹贰叁各自是几（大写模式只有 0 键带角标）。
 */
class CapitalAmountKeysTest {

    // ── 键面 ──

    @Test
    fun `普通模式键面保持原数字`() {
        (0..9).forEach { d ->
            assertEquals(d.toString(), CapitalAmountKeys.face(d.toString(), capitalMode = false))
        }
    }

    @Test
    fun `大写模式键面转大写`() {
        val expected = listOf("零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖")
        expected.forEachIndexed { digit, capital ->
            assertEquals(capital, CapitalAmountKeys.face(digit.toString(), capitalMode = true))
        }
    }

    // ── 角标：只有 0 键 ──

    @Test
    fun `普通模式只有 0 键有角标且为零`() {
        (0..9).forEach { d ->
            val digit = d.toString()
            val badge = CapitalAmountKeys.badge(digit, capitalMode = false)
            if (digit == CapitalAmountKeys.TOGGLE_DIGIT) {
                assertEquals("零", badge)
            } else {
                // 反证：其余键也长角标会让「哪个键能切模式」失去指示作用
                assertNull("$digit 键不该有角标", badge)
            }
        }
    }

    @Test
    fun `大写模式全部数字键都有角标且回显原数字`() {
        // 大写模式键面是壹贰叁…，角标给出「这串大写对应哪个数字」的对照，
        // 全键给出才完整（只有 0 键有角标时，用户看不出壹贰叁各自是几）
        (0..9).forEach { d ->
            val digit = d.toString()
            assertEquals(
                "大写模式下 $digit 键角标应是原数字",
                digit,
                CapitalAmountKeys.badge(digit, capitalMode = true),
            )
        }
    }

    @Test
    fun `进入键就是 0`() {
        assertEquals("0", CapitalAmountKeys.TOGGLE_DIGIT)
    }

    @Test
    fun `0 键的角标在两种模式下互为对照`() {
        // 角标始终显示「键面上没有的那一种写法」：普通模式键面是 0 → 角标零；
        // 大写模式键面是 零 → 角标 0
        val digit = CapitalAmountKeys.TOGGLE_DIGIT
        val faceNormal = CapitalAmountKeys.face(digit, capitalMode = false)
        val badgeNormal = CapitalAmountKeys.badge(digit, capitalMode = false)
        val faceCapital = CapitalAmountKeys.face(digit, capitalMode = true)
        val badgeCapital = CapitalAmountKeys.badge(digit, capitalMode = true)

        assertEquals("普通模式 0 键角标应是它的大写", faceCapital, badgeNormal)
        assertEquals("大写模式 0 键角标应是普通模式的键面", faceNormal, badgeCapital)
    }

    @Test
    fun `角标范围随模式变化`() {
        // 普通模式只有 1 个（0 键），大写模式全部 10 个。
        // 反证：若普通模式也全键带角标，「哪个键能切模式」的指示就消失了；
        // 若大写模式只有 0 键带角标，壹贰叁…就没有对照。
        val normal = (0..9).map { it.toString() }.filter { CapitalAmountKeys.badge(it, false) != null }
        val capital = (0..9).map { it.toString() }.filter { CapitalAmountKeys.badge(it, true) != null }

        assertEquals("普通模式带角标的键应只有 0 键", listOf(CapitalAmountKeys.TOGGLE_DIGIT), normal)
        assertEquals("大写模式应全键带角标", (0..9).map { it.toString() }, capital)
    }

    @Test
    fun `角标内容始终是键面文字的另一种写法`() {
        // 两种模式下角标都不该等于键面，否则角标只是重复键面信息（纯视觉噪音）
        (0..9).forEach { d ->
            val digit = d.toString()
            listOf(false, true).forEach { capital ->
                val badge = CapitalAmountKeys.badge(digit, capital)
                if (badge != null) {
                    assertNotEquals(
                        "大写模式=$capital 时 $digit 键的角标不该与键面相同",
                        CapitalAmountKeys.face(digit, capital),
                        badge,
                    )
                }
            }
        }
    }

    // ── 非数字键不参与 ──

    @Test
    fun `非数字键没有角标`() {
        // 小数点键不该因为「大写模式」而长出角标
        listOf(false, true).forEach { capital ->
            assertNull(CapitalAmountKeys.badge(".", capital))
            assertNull(CapitalAmountKeys.badge("", capital))
            assertNull(CapitalAmountKeys.badge("delete", capital))
            assertNull(CapitalAmountKeys.badge("00", capital))
        }
    }

    @Test
    fun `非数字键键面原样返回`() {
        assertEquals(".", CapitalAmountKeys.face(".", capitalMode = true))
        assertEquals("delete", CapitalAmountKeys.face("delete", capitalMode = true))
    }
}
