package com.kingzcheung.xime.service

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词库重载不得因「落盘会自增版本号」而陷入无限重载。
 *
 * ## 缺陷成因（本轮 H04 修复引入）
 *
 * H04 让 `loadWordTablesOnce` 在读盘前先 flush（`wordTableSaver.save()`），
 * 但 `AutoWordStore.saveTemp/saveFixed` 内部**无条件** `onPersisted()` → `bumpDataVersion()`。
 * 于是：
 *
 * 1. flush → dataVersion = N+1
 * 2. 发布时 `loadedAutoWordVersion = base.version`（= N+1，拍在 flush 之后，这一步没问题）
 * 3. 收尾落盘（固定词库收敛 / prune）→ dataVersion = N+2
 * 4. 下次 `maybeReloadWordTables` 看到 `dataVersion() != loadedAutoWordVersion` → 再次 load
 * 5. 回到第 1 步 —— **每个输入会话都全量重读四张词库**
 *
 * 表现是打字卡顿、日志刷屏「Word tables ready」、IO 长时间被占。
 *
 * ## 修法
 *
 * 整个重载流程（含收尾落盘）结束后，把 `loadedAutoWordVersion` 对齐到**当前**版本号，
 * 而不是沿用中途拍的快照版本。
 */
class WordTableReloadVersionTest {
    private val source: String = RepoFiles.mainKotlinSources
        .single { it.name == "XimeInputMethodService.kt" }
        .readText()
        .replace("\r\n", "\n")

    private val loadFn: String = source
        .substringAfter("private suspend fun loadWordTablesOnce()")
        .substringBefore("private fun maybeReloadWordTables()")

    @Test
    fun `reload realigns version after its own trailing saves`() {
        assertTrue(
            "重载结束时必须把版本号对齐到当前值，否则收尾落盘的 bump 会造成无限重载",
            loadFn.contains("loadedAutoWordVersion = AutoWordStore.dataVersion()"),
        )
    }

    @Test
    fun `realignment happens after every trailing persistence call`() {
        val realign = loadFn.indexOf("loadedAutoWordVersion = AutoWordStore.dataVersion()")
        assertTrue("必须存在对齐语句", realign > 0)
        // 收尾落盘的两个来源都必须在对齐之前
        val converge = loadFn.indexOf("saveWordData()")
        val prune = loadFn.indexOf("scheduleWordFreqSave()")
        if (converge > 0) {
            assertTrue("固定词库收敛落盘必须早于版本对齐", converge < realign)
        }
        if (prune > 0) {
            assertTrue("prune 落盘调度必须早于版本对齐", prune < realign)
        }
    }

    @Test
    fun `flush before reading disk is preserved`() {
        // H04 的核心修复不能被这次改动回退掉。
        assertTrue(
            "读盘前必须仍在 IO 域内 flush 内存增量",
            loadFn.contains("wordTableIo.io { wordTableSaver.save() }"),
        )
        val flush = loadFn.indexOf("wordTableIo.io { wordTableSaver.save() }")
        val snapshot = loadFn.indexOf("wordTableReloadGuard.snapshot()")
        assertTrue("flush 必须在拍快照之前", flush < snapshot)
    }

    @Test
    fun `save path bumps version hence the realignment is required`() {
        // 这条测试锁住「为什么需要对齐」这个前提：落盘路径确实会自增版本号。
        val store = RepoFiles.mainKotlinSources
            .single { it.name == "AutoWordStore.kt" }
            .readText()
            .replace("\r\n", "\n")
        assertTrue(
            "AutoWordStore.saveTemp 必须在写盘后 bump 版本（这是对齐逻辑的前提）",
            store.contains("onPersisted()"),
        )
        assertTrue(
            "onPersisted 必须是 bumpDataVersion",
            store.contains("private fun onPersisted() = bumpDataVersion()"),
        )
    }

    @Test
    fun `reload is gated on version difference only`() {
        // 守卫条件本身保持「版本不同才重载」；修法是对齐版本，而不是放宽比较。
        val maybe = source.substringAfter("private fun maybeReloadWordTables()")
            .substringBefore("private val editorKeyGeneration")
        assertTrue(
            "重载判定必须仍是版本不等才触发",
            maybe.contains("if (AutoWordStore.dataVersion() == loadedAutoWordVersion) return"),
        )
        assertFalse(
            "不得改成无条件跳过（那会漏掉真正的外部改动）",
            maybe.contains("if (true) return"),
        )
    }
}
