package com.kingzcheung.xime.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页共享组件的去重契约。
 *
 * [LocalPackageCard] 与 [TagChip] 原先各有多份**逐字节相同**的私有实现
 * （卡片 2 份各 104 行、标签胶囊 3 份），已收敛为单一实现。
 * 这些断言防止以后再次复制粘贴出第二份。
 */
class SettingsComponentDedupTest {

    private fun mainSources(): List<File> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java")
            if (candidate.isDirectory) {
                return candidate.walkTopDown().filter { it.extension == "kt" }.toList()
            }
            dir = dir.parentFile
        }
        error("未找到 app/src/main/java（user.dir=${System.getProperty("user.dir")}）")
    }

    private fun filesContaining(needle: String): List<String> =
        mainSources().filter { it.readText().contains(needle) }.map { it.name }

    @Test
    fun `本地包卡片只有一份实现`() {
        assertEquals(
            "LocalPackageCard 应只有一处定义",
            listOf("LocalPackageCard.kt"),
            filesContaining("fun LocalPackageCard("),
        )
    }

    @Test
    fun `标签胶囊只有一份实现`() {
        assertEquals(
            "TagChip 应只有一处定义",
            listOf("TagChip.kt"),
            filesContaining("fun TagChip("),
        )
    }

    @Test
    fun `两个设置页都复用共享卡片`() {
        val callers = filesContaining("LocalPackageCard(")
        assertTrue(
            "SchemaLocalScreen 应调用 LocalPackageCard，实际调用方=$callers",
            callers.contains("SchemaLocalScreen.kt"),
        )
        assertTrue(
            "SchemaSettingsScreen 应调用 LocalPackageCard，实际调用方=$callers",
            callers.contains("SchemaSettingsScreen.kt"),
        )
    }

    @Test
    fun `标签胶囊被卡片与模型页复用`() {
        val callers = filesContaining("TagChip(")
        assertTrue(
            "LocalPackageCard 应调用 TagChip，实际调用方=$callers",
            callers.contains("LocalPackageCard.kt"),
        )
        assertTrue(
            "ModelLocalScreen 应调用 TagChip，实际调用方=$callers",
            callers.contains("ModelLocalScreen.kt"),
        )
    }
}
