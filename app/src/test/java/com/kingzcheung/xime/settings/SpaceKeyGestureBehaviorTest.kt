package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空格键「只长按进语音、左右滑动只移光标」契约测试。
 *
 * 用户反馈的两个缺陷：
 * 1. 空格键左右滑动会**误开语音**；
 * 2. 长按进语音**不够灵敏**。
 *
 * 根因：长按计时器（旧实现硬编码 `delay(400)`）与滑动判定并行，而滑动方向判定里一旦
 * `longPressTriggered` 就不再判方向 —— 于是「按住稍久再左右滑」会先触发长按进入语音，
 * 滑动被整个丢弃。现在把「位移超过 touch slop 即取消长按资格」做成显式信号
 * （`awaitSwipeDirection` 的 `onMoveBeyondSlop`），并让长按时间可配（默认 300ms）。
 */
class SpaceKeyGestureBehaviorTest {

    private fun sourceOf(fileName: String): String =
        RepoFiles.mainKotlinSources.first { it.name == fileName }
            .readText().replace("\r\n", "\n")

    private val layoutSource = sourceOf("KeyboardLayout.kt")
    private val assetsYaml: String =
        RepoFiles.file("app/src/main/assets/xime.yaml").readText().replace("\r\n", "\n")

    // ── 配置模型 ──

    @Test
    fun `默认长按时间为 300 毫秒`() {
        assertEquals(300, KeyboardGesturesConfig.DEFAULT_LONG_PRESS_MS)
        assertEquals(300, KeyboardGesturesConfig().longPressMs)
        // 比系统 400ms 更灵敏，这是「长按灵敏度调高」的落点
        assertTrue(KeyboardGesturesConfig.DEFAULT_LONG_PRESS_MS < 400)
    }

    @Test
    fun `长按时间被夹取在合理区间`() {
        val helper = KeysConfigHelper
        assertEquals(KeyboardGesturesConfig.MIN_LONG_PRESS_MS, helper.normalizeLongPressMs(0))
        assertEquals(KeyboardGesturesConfig.MIN_LONG_PRESS_MS, helper.normalizeLongPressMs(-100))
        assertEquals(KeyboardGesturesConfig.MAX_LONG_PRESS_MS, helper.normalizeLongPressMs(99999))
        assertEquals(500, helper.normalizeLongPressMs(500))
    }

    @Test
    fun `从 YAML 解析长按时间`() {
        val parsed = KeysConfigHelper.parseKeyboardGesturesYamlText(
            """
            keyboard:
              gestures:
                long_press_ms: 250
            """.trimIndent()
        )
        assertNotNull(parsed)
        assertEquals(250, parsed!!.longPressMs)
    }

    @Test
    fun `未配置 gestures 段时返回 null 以便回退默认值`() {
        assertNull(KeysConfigHelper.parseKeyboardGesturesYamlText("keyboard:\n  candidates:\n    show_clear_button: true"))
    }

    @Test
    fun `字段级合并 custom 优先于内置`() {
        val merged = KeysConfigHelper.mergeGesturesConfigs(
            custom = KeyboardGesturesPartial(longPressMs = 200),
            builtIn = KeyboardGesturesPartial(longPressMs = 450),
        )
        assertEquals(200, merged.longPressMs)
        // 两边都没配 → 代码默认值
        assertEquals(
            KeyboardGesturesConfig.DEFAULT_LONG_PRESS_MS,
            KeysConfigHelper.mergeGesturesConfigs(null, null).longPressMs,
        )
    }

    @Test
    fun `内置 xime_yaml 提供了 gestures 段`() {
        assertTrue("assets 里应写出 gestures 段供用户调整", assetsYaml.contains("gestures:"))
        assertTrue(assetsYaml.contains("long_press_ms: 300"))
        // 配置了就要能被解析出来
        val parsed = KeysConfigHelper.parseKeyboardGesturesYamlText(assetsYaml)
        assertNotNull("内置配置必须可解析", parsed)
        assertEquals(300, parsed!!.longPressMs)
    }

    // ── 误开语音的修复 ──

    @Test
    fun `滑动判定在位移超过 touch slop 时回调取消长按`() {
        val body = layoutSource.substringAfter("private suspend fun AwaitPointerEventScope.awaitSwipeDirection(")
            .substringBefore("\n@Composable")
        assertTrue("必须接收 onMoveBeyondSlop 回调", body.contains("onMoveBeyondSlop: () -> Unit"))
        assertTrue("阈值取系统 touchSlop", body.contains("viewConfiguration.touchSlop"))
        assertTrue("首次超 slop 只回调一次", body.contains("slopReported"))
        assertTrue(body.contains("onMoveBeyondSlop()"))
        // slop 判定必须先于方向阈值，否则方向判定会先被 longPressTriggered 短路
        assertTrue(body.indexOf("onMoveBeyondSlop()") < body.indexOf("direction = if (dragX < 0)"))
    }

    @Test
    fun `空格键把位移信号接到长按取消上`() {
        val body = layoutSource.substringAfter("private fun SpaceKey(")
            .substringBefore("\n/** QWERTY 空格键 */")
        assertTrue("需要 moveCancelledLongPress 标志", body.contains("moveCancelledLongPress"))
        assertTrue(
            "位移超 slop 必须置位取消长按",
            body.contains("onMoveBeyondSlop = { moveCancelledLongPress = true }"),
        )
        assertTrue(
            "长按计时到期后要检查取消标志再触发",
            body.contains("if (moveCancelledLongPress) return@launch"),
        )
        // 取消检查必须早于 longPressTriggered 置位与语音唤起
        val guard = body.indexOf("if (moveCancelledLongPress) return@launch")
        val flag = body.indexOf("longPressTriggered = true")
        val voice = body.indexOf("currentOnVoiceModeChange?.invoke(true)")
        assertTrue(guard in 1 until flag)
        assertTrue(flag < voice)
    }

    @Test
    fun `空格键长按时间来自配置而非硬编码`() {
        val body = layoutSource.substringAfter("private fun SpaceKey(")
            .substringBefore("\n/** QWERTY 空格键 */")
        assertTrue("长按时间应作为参数传入", body.contains("longPressMs: Long"))
        assertTrue(body.contains("delay(longPressMs)"))
        assertFalse("不得再硬编码 400ms 长按", body.contains("delay(400)"))
        // 计时器必须在长按时间变化时重建，否则改配置不生效
        assertTrue(body.contains("pointerInput(isSttEnabled, voiceSticky, longPressMs)"))
    }

    @Test
    fun `空格键从配置读取长按时间`() {
        val body = layoutSource.substringAfter("private fun SpaceCell(")
            .substringBefore("\n@Composable\nprivate fun EnterCell")
        assertTrue(body.contains("KeysConfigHelper.getKeyboardGestures().longPressMs"))
        assertTrue(body.contains("longPressMs = "))
    }

    @Test
    fun `滑动手势分支不会触发语音`() {
        val body = layoutSource.substringAfter("private fun SpaceKey(")
            .substringBefore("\n/** QWERTY 空格键 */")
        // 语音只出现在长按任务里；滑动分支只回调 onSwipe
        val swipeBranch = body.substringAfter("if (dir != null && currentOnSwipe != null) {")
            .substringBefore("} else if (!longPressTriggered)")
        assertFalse(swipeBranch.contains("onVoiceModeChange"))
        assertFalse(swipeBranch.contains("currentOnLongPress"))
    }

    @Test
    fun `空格键左右滑配置仍是移动光标`() {
        val helperSource = sourceOf("KeysConfigHelper.kt")
        assertTrue(
            "手势配置解析应与候选配置同源读取 assets 与 custom",
            helperSource.contains("parseKeyboardGesturesFromAssets"),
        )
        assertTrue(assetsYaml.contains("action: cursor_left"))
        assertTrue(assetsYaml.contains("action: cursor_right"))
    }
}
