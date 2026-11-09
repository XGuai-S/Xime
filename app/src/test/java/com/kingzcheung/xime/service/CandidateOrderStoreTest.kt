package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选词手动排序词频表的纯逻辑测试。
 *
 * 覆盖解析/序列化往返、全序转权重、按词频重排、全序并入表四条路径；
 * 重排后点选的「显示 index → 引擎 index」映射由生成的
 * [CandidateAction.engine] 承担，这里一并断言。
 */
class CandidateOrderStoreTest {

    // ── parse / serialize ──

    @Test
    fun `解析基本条目与表头跳过`() {
        val text = "# 候选排序词频：词\t编码\t权重\n" +
            "你\tni\t100\n" +
            "尼\tni\t90\n" +
            "\n" +
            "好\thao\t100\n"
        val table = CandidateOrderStore.parse(text)
        assertEquals(2, table.size)
        assertEquals(mapOf("你" to 100, "尼" to 90), table["ni"])
        assertEquals(mapOf("好" to 100), table["hao"])
    }

    @Test
    fun `坏行只丢该行不丢整表`() {
        val text = "你\tni\t100\n坏行\tni\t不是数字\nni\n空码\t\t50\n"
        val table = CandidateOrderStore.parse(text)
        assertEquals("坏权重行应被跳过", mapOf("你" to 100), table["ni"])
        assertEquals("坏行/空行不占位，只有一条有效编码", 1, table.size)
    }

    @Test
    fun `同编码同词后者覆盖前者`() {
        val table = CandidateOrderStore.parse("你\tni\t100\n你\tni\t50\n")
        assertEquals(mapOf("你" to 50), table["ni"])
    }

    @Test
    fun `序列化后解析往返一致`() {
        val original = LinkedHashMap<String, Map<String, Int>>().apply {
            put("ni", LinkedHashMap<String, Int>().apply { put("你", 100); put("尼", 90) })
            put("hao", LinkedHashMap<String, Int>().apply { put("好", 100) })
        }
        val roundTrip = CandidateOrderStore.parse(CandidateOrderStore.serialize(original))
        assertEquals(original, roundTrip)
    }

    @Test
    fun `序列化编码内按权重降序且词在前`() {
        val text = CandidateOrderStore.serialize(
            mapOf("ni" to mapOf("尼" to 90, "你" to 100))
        )
        // ni 下有两个词，各自成行；最重的「你」应排在该编码的第一行，且行首是词
        val niLine = text.lineSequence().first { it.contains("\tni\t") }
        assertTrue("较重的词应在前且词为首列", niLine == "你\tni\t100")
    }

    // ── fullOrderWeights ──

    @Test
    fun `全序转权重前大后小`() {
        val weights = CandidateOrderStore.fullOrderWeights(listOf("你", "尼", "拟"))
        assertEquals(mapOf("你" to 30, "尼" to 20, "拟" to 10), weights)
    }

    @Test
    fun `全序转权重跳过空词`() {
        // 空词不写权重；其后词的权重沿用其在列表中的原始位置（不因空洞重排）
        val weights = CandidateOrderStore.fullOrderWeights(listOf("你", "", "拟"))
        assertEquals(mapOf("你" to 30, "拟" to 10), weights)
    }

    // ── reorder ──

    @Test
    fun `无排序记录的编码不重排`() {
        assertNull(
            CandidateOrderStore.reorder(
                code = "ni",
                candidates = listOf("你", "尼", "拟"),
                comments = listOf("123", "456", "789"),
                table = mapOf("hao" to mapOf("好" to 100)),
            )
        )
    }

    @Test
    fun `顺序本就一致时不返回重排结果`() {
        val table = mapOf("ni" to mapOf("你" to 100, "尼" to 90, "拟" to 80))
        assertNull(
            CandidateOrderStore.reorder(
                code = "ni",
                candidates = listOf("你", "尼", "拟"),
                comments = listOf("a", "b", "c"),
                table = table,
            )
        )
    }

    @Test
    fun `按词频重排并生成引擎索引映射`() {
        // 引擎顺序 你/尼/拟，但用户排过 拟/你/尼 →
        // 显示变成 拟/你/尼，actions 指回各自原来的引擎下标 2/0/1
        val table = mapOf(
            "ni" to mapOf("你" to 100, "尼" to 90, "拟" to 200)
        )
        val result = CandidateOrderStore.reorder(
            code = "ni",
            candidates = listOf("你", "尼", "拟"),
            comments = listOf("1", "2", "3"),
            table = table,
        )!!
        assertEquals(listOf("拟", "你", "尼"), result.candidates)
        assertEquals(listOf("3", "1", "2"), result.comments)
        assertEquals(listOf(2, 0, 1), result.actions.map { it.engineIndex })
        assertTrue("纯引擎重排动作不带上屏文本", result.actions.all { it.commitText.isEmpty() })
    }

    @Test
    fun `没记录的新词排最后且保持引擎相对顺序`() {
        // 用户只排过 你/尼；「好」是新词，保持引擎相对位置跟在排过的词后
        val table = mapOf("ni" to mapOf("你" to 100, "尼" to 200))
        val result = CandidateOrderStore.reorder(
            code = "ni",
            candidates = listOf("你", "尼", "好"),
            comments = listOf("1", "2", "3"),
            table = table,
        )!!
        assertEquals(listOf("尼", "你", "好"), result.candidates)
        assertEquals(listOf(1, 0, 2), result.actions.map { it.engineIndex })
    }

    // ── withFullOrder ──

    @Test
    fun `全序并入表替换该编码其他编码不动`() {
        val table = LinkedHashMap<String, Map<String, Int>>().apply {
            put("hao", mapOf("好" to 100))
            put("ni", mapOf("你" to 100, "尼" to 90))
        }
        val next = CandidateOrderStore.withFullOrder(
            table = table,
            code = "ni",
            ordered = listOf("拟", "你"),
        )
        assertEquals("其他编码不受影响", mapOf("好" to 100), next["hao"])
        assertEquals(mapOf("拟" to 20, "你" to 10), next["ni"])
    }

    @Test
    fun `空表上级码超限时不新增编码`() {
        val table = LinkedHashMap<String, Map<String, Int>>().apply {
            repeat(4096) { put("c$it", mapOf("词" to 10)) }
        }
        val next = CandidateOrderStore.withFullOrder(
            table = table,
            code = "new",
            ordered = listOf("新"),
        )
        assertNull("超限后新编码不应写入", next["new"])
    }

    // ── newerOf（回退遮蔽修复） ──

    @Test
    fun `仅可见目录存在时取可见`() {
        assertEquals(
            "visible",
            CandidateOrderStore.newerOf(null, "visible", null, null),
        )
    }

    @Test
    fun `仅私有目录存在时取私有`() {
        assertEquals(
            "private",
            CandidateOrderStore.newerOf(null, null, 2000L, "private"),
        )
    }

    @Test
    fun `两处都存在时私有较新取私有`() {
        // 用户关掉「所有文件访问」后继续拖拽：可见目录残留旧表 v1，
        // 私有目录是更新的 v2 → 必须读 v2，而不是被可见旧表遮蔽。
        // visibleStamp.first 为秒（UserConfigDir.stamp 语义）；privateStamp 为毫秒（File.lastModified 语义）。
        assertEquals(
            "private-v2",
            CandidateOrderStore.newerOf(
                visibleStamp = 1000L to 10L, // 可见目录：1000 秒（旧）
                visibleText = "visible-v1",
                privateStamp = 2000_000L, // 私有目录：2000 秒（新）
                privateText = "private-v2",
            ),
        )
    }

    @Test
    fun `两处都存在时可见不旧于私有取可见`() {
        // 正常路径：已迁移到可见目录且刚写过（时间戳相同或更新）→ 可见侧胜出。
        assertEquals(
            "visible",
            CandidateOrderStore.newerOf(
                visibleStamp = 2000L to 10L,
                visibleText = "visible",
                privateStamp = 1000_000L,
                privateText = "private",
            ),
        )
        assertEquals(
            "visible",
            CandidateOrderStore.newerOf(
                visibleStamp = 2000L to 10L,
                visibleText = "visible",
                privateStamp = 2000_000L,
                privateText = "private-same-time",
            ),
        )
    }

    @Test
    fun `一处为空时取另一处`() {
        assertEquals(
            "visible",
            CandidateOrderStore.newerOf(null, "visible", 2000L, null),
        )
        assertEquals(
            "private",
            CandidateOrderStore.newerOf(1000L to 10L, null, 2000L, "private"),
        )
    }
}