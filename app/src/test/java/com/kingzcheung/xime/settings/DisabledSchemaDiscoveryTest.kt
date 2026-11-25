package com.kingzcheung.xime.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 未启用方案必须能在设置页里看到并重新启用。
 *
 * 缺陷现场：`ensureSchemeLayout` 的 `archiveDisabledSchemeFiles` 会把未启用方案的
 * 文件从 rime 顶层移进 `schemes/<方案名>/`。旧 `discoverSchemas` 只扫顶层，
 * 于是这些方案在设置页里完全消失——用户看到「没有其他可用方案」，
 * 想启用也找不到入口（等于把功能删了）。
 */
class DisabledSchemaDiscoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun schemaFile(dir: File, id: String): File {
        val f = File(dir, "$id.schema.yaml")
        f.parentFile?.mkdirs()
        f.writeText("schema:\n  schema_id: $id\n  name: $id\n")
        return f
    }

    @Test
    fun `archived disabled schema is still discovered`() {
        val rime = tmp.newFolder("rime")
        schemaFile(rime, "active")
        val archived = File(File(rime, "schemes"), "disabled")
        schemaFile(archived, "disabled")

        val ids = SchemaManager.discoverSchemasIn(rime).map { it.schemaId }

        assertEquals(
            "顶层与 schemes/ 里的方案都必须被发现，否则未启用方案无法重新启用",
            setOf("active", "disabled"),
            ids.toSet(),
        )
    }

    @Test
    fun `top level schema wins when the same id is archived as well`() {
        val rime = tmp.newFolder("rime-dupe")
        val top = schemaFile(rime, "same")
        top.writeText("schema:\n  schema_id: same\n  name: 顶层名字\n")
        val archived = File(File(rime, "schemes"), "same")
        schemaFile(archived, "same").writeText("schema:\n  schema_id: same\n  name: 归档名字\n")

        val found = SchemaManager.discoverSchemasIn(rime)

        assertEquals("同一 schema_id 只能出现一次", 1, found.size)
        assertEquals("顶层那份优先（引擎实际加载的是它）", "顶层名字", found.single().name)
    }

    @Test
    fun `missing schemes directory still yields top level schemas`() {
        val rime = tmp.newFolder("rime-plain")
        schemaFile(rime, "only")

        assertEquals(listOf("only"), SchemaManager.discoverSchemasIn(rime).map { it.schemaId })
        assertTrue(File(rime, "schemes").exists().not())
    }
}