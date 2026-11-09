package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按键效果 → 按键气泡」开关的接线契约测试。
 *
 * 这个开关的效果只在真机键盘上可见，单测无法直接渲染 Compose 气泡，
 * 因此这里锁住三件容易在后续重构中静默丢失的事：
 * 1. 设置页有开关且写回同一个偏好键；
 * 2. 偏好默认开，保持历史行为不变；
 * 3. 绘制入口只拦截「纯按压」气泡，滑动/长按提示气泡不受影响。
 */
class PressBubbleSettingContractTest {

    private fun source(name: String): String = RepoFiles.mainKotlinSources
        .single { it.name == name }
        .readText()
        .replace("\r\n", "\n")

    @Test
    fun `按键效果页提供气泡开关并接入 ViewModel`() {
        val screen = source("KeyEffectSettingsScreen.kt")
        assertTrue("设置页缺少按键气泡分组", screen.contains("SettingsSection(title = \"按键气泡\""))
        assertTrue("设置页缺少开关文案", screen.contains("text = \"显示按键气泡\""))
        assertTrue("开关未绑定 UI 状态", screen.contains("checked = uiState.pressBubbleEnabled"))
        assertTrue(
            "开关未写回 ViewModel",
            screen.contains("onCheckedChange = { viewModel.setPressBubbleEnabled(it) }"),
        )

        val viewModel = source("KeyEffectSettingsViewModel.kt")
        assertTrue("UI 状态缺少气泡字段", viewModel.contains("val pressBubbleEnabled: Boolean = true"))
        assertTrue(
            "初始化未读取气泡偏好",
            viewModel.contains("pressBubbleEnabled = SettingsPreferences.isPressBubbleEnabled(context)"),
        )
        assertTrue(
            "写入未持久化气泡偏好",
            viewModel.contains("SettingsPreferences.setPressBubbleEnabled(context, enabled)"),
        )
    }

    @Test
    fun `气泡偏好默认开启并落到独立键`() {
        val prefs = source("SettingsPreferences.kt")
        assertTrue(
            "气泡偏好键应为 press_bubble_enabled",
            prefs.contains("const val KEY_PRESS_BUBBLE_ENABLED = \"press_bubble_enabled\""),
        )
        assertTrue(
            "气泡偏好默认必须为开，保持历史行为",
            prefs.contains("getBoolean(KEY_PRESS_BUBBLE_ENABLED, true)"),
        )
        assertTrue(
            "气泡偏好缺少写入方法",
            prefs.contains("fun setPressBubbleEnabled(context: Context, enabled: Boolean)"),
        )
    }

    @Test
    fun `绘制入口只拦截纯按压气泡`() {
        val bubble = source("SwipeBubble.kt")
        assertTrue(
            "绘制入口未读取气泡偏好",
            bubble.contains("SettingsPreferences.isPressBubbleEnabled(context)"),
        )
        assertTrue(
            "绘制入口未监听气泡偏好变化，切换开关后需要重启才生效",
            bubble.contains("key == SettingsPreferences.KEY_PRESS_BUBBLE_ENABLED"),
        )
        assertTrue(
            "关闭开关时必须先判定是否为纯按压态",
            bubble.contains(
                "val isPurePress = swipeState.isPressed && !swipeState.isSwiping && !swipeState.isLongPress"
            ),
        )
        assertTrue(
            "纯按压态才应被开关拦截",
            bubble.contains("if (isPurePress && !pressBubbleEnabled) return null"),
        )
        // 反证：不能把整个气泡绘制层直接关掉，否则滑动/长按选项气泡也会消失。
        assertFalse(
            "开关不能无条件拦截所有气泡绘制",
            bubble.contains("if (!SettingsPreferences.isPressBubbleEnabled(context)) return null"),
        )
    }
}
