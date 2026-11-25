package com.kingzcheung.xime.clipboard

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 敏感剪贴板条目去重（SENSITIVE 去重失效回归）。
 *
 * 缺陷：高风险凭据（密码/证件号/银行卡/密钥）入库时被标成 [ClipboardEntry.TYPE_SENSITIVE]，
 * 但 `upsertAndTrim` 用的 `findByText` 查询硬编码 `type = 'text'`，查不到敏感条目，
 * 于是同一段密码每复制一次就新插一行——去重与复活全部失效，敏感明文在库里越堆越多。
 *
 * 修法：新增不限定 type 的 `findAnyTextEntry`，让 upsertAndTrim 走它；
 * `findByText` 保留原语义供备份合并使用（那里只该合并 text 类型）。
 *
 * JVM 单测无 Robolectric，跑不了 Room 内存库，故用源码契约钉住接线与 SQL 语义。
 */
class SensitiveClipboardDedupeTest {

    private val daoSource: String by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/clipboard/db/ClipboardDao.kt").readText()
    }

    @Test
    fun `findAnyTextEntry 查询不限定 type`() {
        val m = Regex(
            """@Query\("([^"]*?)"\)\s*suspend fun findAnyTextEntry""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(daoSource) ?: error("找不到 findAnyTextEntry 的 @Query")
        val sql = m.groupValues[1]
        assertTrue("去重查询必须能命中 TYPE_SENSITIVE，实际 SQL：$sql", "type" !in sql)
        assertTrue("去重查询须排除快捷发送，实际 SQL：$sql", "isQuickSend = 0" in sql)
    }

    @Test
    fun `upsertAndTrim 使用不限类型的查询`() {
        val start = daoSource.indexOf("suspend fun upsertAndTrim(")
        assertTrue("找不到 upsertAndTrim", start >= 0)
        // 截取函数体（到下一个 suspend fun 或文件尾），避免正则被嵌套大括号截断
        val rest = daoSource.substring(start)
        val nextFun = rest.indexOf("\n    suspend fun ", 1).let { if (it < 0) rest.length else it }
        val body = rest.substring(0, nextFun)
        assertTrue("upsertAndTrim 必须调用 findAnyTextEntry", "findAnyTextEntry(text)" in body)
        assertFalse("upsertAndTrim 不得再用限定类型的 findByText", "findByText(text)" in body)
    }

    @Test
    fun `findByText 保留类型限定供备份合并专用`() {
        val m = Regex(
            """@Query\("([^"]*?)"\)\s*suspend fun findByText""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(daoSource) ?: error("找不到 findByText 的 @Query")
        assertTrue(
            "备份合并只合并 text 类型，findByText 必须保留 type = 'text'",
            "type = 'text'" in m.groupValues[1],
        )
    }

    @Test
    fun `敏感类型常量存在且区别于普通文本`() {
        val entry = RepoFiles.file("src/main/java/com/kingzcheung/xime/clipboard/db/ClipboardEntry.kt").readText()
        assertTrue("缺少 TYPE_SENSITIVE 常量", "TYPE_SENSITIVE" in entry)
        assertTrue("缺少 TYPE_TEXT 常量", "TYPE_TEXT" in entry)
    }
}
