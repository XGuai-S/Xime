package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import com.kingzcheung.xime.keyboard.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * axcv 长按不再唤起【全选 / 剪切 / 复制 / 粘贴】菜单。
 *
 * 需求：移除 a/x/c/v 四个键长按气泡里的编辑菜单项。
 * 注意这些 [GestureAction] 本身仍然保留——工具栏按钮（[com.kingzcheung.xime.keyboard.ToolbarButton]）
 * 依旧使用它们，本测试只钉住「键位长按不再挂载」。
 */
class AxcvLongPressMenuRemovedTest {

    private val editingActions = setOf(
        GestureAction.SELECT_ALL,
        GestureAction.CUT,
        GestureAction.COPY,
        GestureAction.PASTE,
    )

    private fun qwerty(): Map<String, KeyBinding> {
        val text = RepoFiles.file("src/main/assets/xime.yaml").readText()
        return KeysConfigHelper.parseKeyboardYamlSection(text, "qwerty", emptyMap())
            ?: error("解析 keyboard.qwerty.keys 失败")
    }

    @Test
    fun `axcv 长按气泡不含全选剪切复制粘贴`() {
        val keys = qwerty()
        for (id in listOf("a", "x", "c", "v")) {
            val binding = keys[id] ?: error("缺少键 $id")
            val actions = binding.longPress?.values?.mapNotNull { it.action }.orEmpty()
            val offending = actions.filter { it in editingActions }
            assertEquals("键 $id 的长按仍挂载编辑菜单动作: $offending", emptyList<GestureAction>(), offending)
        }
    }

    @Test
    fun `axcv 长按仍保留字母本身`() {
        val keys = qwerty()
        // 移除菜单项不应连带删掉字母候选（小写 + 大写至少各一项）
        for (id in listOf("a", "x", "c", "v")) {
            val values = keys[id]!!.longPress?.values.orEmpty().map { it.label.ifEmpty { it.value } }
            assertTrue("键 $id 长按缺少小写字母: $values", values.contains(id))
            assertTrue("键 $id 长按缺少大写字母: $values", values.contains(id.uppercase()))
        }
    }

    @Test
    fun `工具栏仍可使用的编辑动作未被从枚举移除`() {
        // 防止误删枚举导致工具栏「全选/复制/粘贴」失效
        for (action in editingActions) {
            assertTrue("GestureAction.${action.name} 应保留", GestureAction.entries.contains(action))
        }
    }
}
