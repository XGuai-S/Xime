package com.kingzcheung.xime.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchivedSchemeBackupTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `backup retains unique archived files and skips equivalent top level copies`() {
        val rime = temp.newFolder("rime")
        val archived = File(rime, "schemes/disabled")
        assertTrue(archived.mkdirs())
        File(archived, "disabled.schema.yaml").writeText("schema")
        File(archived, "shared.dict.yaml").writeText("same content")
        File(archived, "custom.dict.yaml").writeText("archived version")
        File(archived, "table.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(archived, "level.userdb/data").apply {
            parentFile.mkdirs()
            writeText("derived")
        }
        File(rime, "shared.dict.yaml").writeText("same content")
        File(rime, "custom.dict.yaml").writeText("active version")

        val entries = RimeExportManager.archivedSchemeEntries(rime, "local-id").toMap()

        assertTrue("顶层缺失的禁用方案文件必须打包", "schemes/disabled/disabled.schema.yaml" in entries)
        assertFalse("与顶层相同的硬链接/副本不能重复打包", "schemes/disabled/shared.dict.yaml" in entries)
        assertTrue("归档版与当前顶层版本不同，应保留归档副本", "schemes/disabled/custom.dict.yaml" in entries)
        assertFalse("二进制派生产物不备份", entries.keys.any { it.endsWith("table.bin") })
        assertFalse("leveldb 用户词典目录不备份", entries.keys.any { it.contains(".userdb/") })
    }
}
