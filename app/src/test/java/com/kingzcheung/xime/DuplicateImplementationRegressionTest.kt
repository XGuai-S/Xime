package com.kingzcheung.xime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复实现回归测试。
 *
 * 本仓库历史上同一段实现被复制到多个文件（例如按键压深 5 份、滑动状态处理 5 份、
 * 流复制 9 份），改一处漏改其余，正是悬浮卡片错位一类线上问题的根源。
 * 这些重复已在结构优化中收敛；本测试锁定结果，防止再次被复制回来。
 *
 * 只对「已确定收敛」的具体片段做断言，不试图做通用的重复检测——
 * 通用检测对同名不同体（各 ViewModel 的 refresh 等）误报太多。
 */
class DuplicateImplementationRegressionTest {

    private fun sourcesContaining(needle: String): List<String> =
        RepoFiles.mainKotlinSources
            .filter { it.readText().contains(needle) }
            .map { it.name }
            .sorted()

    @Test
    fun `按键压深只有一份实现`() {
        // 曾散落 5 份逐字相同的局部 darkenColor（KeyButton x3、KeyboardLayout x2）
        val decls = RepoFiles.mainKotlinSources.filter { f ->
            f.readText().lineSequence().any { it.trim().startsWith("fun darkenColor(") }
        }
        assertEquals(emptyList<String>(), decls.map { it.name }.sorted())

        // 唯一实现应位于 KeyColorUtils.kt，且被两个键盘文件共用
        val users = sourcesContaining("darkenKeyColor(")
        assertTrue("darkenKeyColor 应在 KeyColorUtils 定义: $users", users.contains("KeyColorUtils.kt"))
        assertTrue("KeyButton 应使用共享实现: $users", users.contains("KeyButton.kt"))
        assertTrue("KeyboardLayout 应使用共享实现: $users", users.contains("KeyboardLayout.kt"))
    }

    @Test
    fun `按键滑动状态处理只有一份实现`() {
        // 曾散落 5 份逐字相同的局部 processSwipeState（KeyboardLayout/Stroke/T9/Number/CommonSymbol）
        val decls = RepoFiles.mainKotlinSources.filter { f ->
            f.readText().lineSequence().any { it.trim().startsWith("fun processSwipeState(") }
        }
        assertEquals(emptyList<String>(), decls.map { it.name }.sorted())

        val users = sourcesContaining("processKeySwipeState(")
        assertTrue("共享实现应位于 KeySwipeState: $users", users.contains("KeySwipeState.kt"))
        assertTrue("KeyboardLayout 应使用共享实现: $users", users.contains("KeyboardLayout.kt"))
        assertTrue("StrokeKeyboardLayout 应使用共享实现: $users", users.contains("StrokeKeyboardLayout.kt"))
        assertTrue("T9KeyboardLayout 应使用共享实现: $users", users.contains("T9KeyboardLayout.kt"))
        assertTrue("NumberKeyboardLayout 应使用共享实现: $users", users.contains("NumberKeyboardLayout.kt"))
        assertTrue(
            "CommonSymbolKeyboardLayout 应使用共享实现: $users",
            users.contains("CommonSymbolKeyboardLayout.kt")
        )
    }

    @Test
    fun `SchemaManager 内不存在重复的流复制样板`() {
        assertTrue(RepoFiles.file("app/src/main/java/com/kingzcheung/xime/settings/SchemaManager.kt").exists())
        val text = RepoFiles.file("app/src/main/java/com/kingzcheung/xime/settings/SchemaManager.kt").readText()
        // 只允许定义体内那一处；出现 2 次以上即为样板被复制回来
        val occurrences = Regex(Regex.escape("outputStream().use { output -> input.copyTo(output) }"))
            .findAll(text).count() +
            Regex(Regex.escape("outputStream().use { output -> inputStream.copyTo(output) }"))
                .findAll(text).count()
        assertTrue("SchemaManager 的流复制样板应只剩定义体 1 处，实际 $occurrences", occurrences <= 1)
    }

    @Test
    fun `流复制辅助函数不得自我递归`() {
        // 收敛过程中曾把 copyStreamTo 的定义体误替换为对自身的调用（编译可通过，但运行即栈溢出）
        val text = RepoFiles.file("app/src/main/java/com/kingzcheung/xime/settings/SchemaManager.kt").readText()
        val body = text.substringAfter("private fun copyStreamTo(", "")
            .substringBefore("\n    }")
        assertTrue("copyStreamTo 定义体不应调用自身", !body.contains("copyStreamTo("))
    }

    @Test
    fun `横向滑动接管样板已收敛为共享函数`() {
        // 三处渲染器曾各抄一段「取出左右滑回调 → 判定 → 置位」
        val users = sourcesContaining("suppressCursorMoveIfHorizontalSwipe(")
        assertTrue("应有共享实现: $users", users.contains("KeyButton.kt"))
        assertTrue("KeyboardLayout 应使用共享实现: $users", users.contains("KeyboardLayout.kt"))

        // 旧样板的特征写法不应再出现
        assertEquals(
            emptyList<String>(),
            sourcesContaining("onSwipeLeftAction != null || onSwipeRightAction != null").sorted()
        )
    }
}
