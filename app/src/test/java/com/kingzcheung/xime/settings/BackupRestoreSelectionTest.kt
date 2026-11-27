package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云恢复的两个接线回归：
 *
 * 1. 文件级增量恢复下载后必须按清单哈希校验——之前直接把远端文件写进重建的 zip，
 *    损坏/篡改的数据会进用户词库。整包恢复有校验，文件级不能反而更弱。
 * 2. 用户在备份列表选了具体 id，必须恢复那个整包——之前无条件优先镜像恢复，
 *    选 3 天前的旧包实际恢复成当前镜像，时间点回滚失效。
 */
class BackupRestoreSelectionTest {

    private val incremental by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/settings/IncrementalBackup.kt").readText()
    }
    private val manager by lazy {
        RepoFiles.file("src/main/java/com/kingzcheung/xime/settings/BackupManager.kt").readText()
    }

    /** 截取 BackupManager.restore(context, plugin, id) 方法体。 */
    private fun restoreBody(): String {
        val start = manager.indexOf("suspend fun restore(context: Context, plugin: BackupPlugin, id: String)")
        assertTrue("找不到 BackupManager.restore", start >= 0)
        val rest = manager.substring(start)
        val next = rest.indexOf("\n    suspend fun ", 1)
            .let { if (it < 0) rest.length else it }
        return rest.substring(0, next)
    }

    @Test
    fun `增量恢复下载后按清单哈希校验`() {
        val body = incremental.substring(
            incremental.indexOf("suspend fun restore("),
        ).substringBefore("\n    // ---")
        assertTrue("下载后必须计算实际 sha256", "val actual = " in body && "sha256Of(it)" in body)
        assertTrue("必须与清单哈希比对", "actual.equals(expected.trim(), ignoreCase = true)" in body)
        assertTrue("不匹配必须失败而非继续写入", "文件校验失败（sha256 不匹配）" in body)
        // putNextEntry 必须在校验通过之后
        val check = body.indexOf("val actual = ")
        val write = body.indexOf("zos.putNextEntry(ZipEntry(path))")
        assertTrue("必须先校验再写入 zip", check >= 0 && write > check)
    }

    @Test
    fun `备份恢复优先按用户所选 id 下载整包`() {
        val body = restoreBody()
        val pull = body.indexOf("plugin.pullBackup(id)")
        val incremental = body.indexOf("IncrementalBackup.restore(")
        assertTrue("必须先按 id 拉整包", pull >= 0 && incremental > pull)
    }

    @Test
    fun `整包可用时不走增量镜像恢复`() {
        val body = restoreBody()
        // pullBackup(id) != null 时直接 return，不得落到增量分支
        val pull = body.indexOf("plugin.pullBackup(id)")
        val ifNotNull = body.indexOf("if (download != null)", pull)
        val returnInBlock = body.indexOf("return@withLock try", ifNotNull)
        val incremental = body.indexOf("IncrementalBackup.restore(")
        assertTrue(
            "按 id 拿到整包后必须直接返回，不得再走镜像恢复",
            ifNotNull >= 0 && returnInBlock > ifNotNull && returnInBlock < incremental,
        )
    }

    @Test
    fun `按 id 失败才回退增量且失败不吞掉`() {
        val body = restoreBody()
        assertTrue("回退分支必须保留日志", "回退文件级镜像恢复" in body)
        assertTrue("增量恢复结果必须原样返回", "return@withLock incremental" in body)
    }
}
