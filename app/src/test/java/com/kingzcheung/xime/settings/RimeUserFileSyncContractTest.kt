package com.kingzcheung.xime.settings

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RimeUserFileSync] 的接线契约测试。
 *
 * 同步逻辑本身在 [RimeUserFileSyncTest] 里测；这里只锁「有没有接对地方」——
 * 逻辑正确但没被调用，等于功能不存在，而且是最难从日志里发现的一类问题。
 */
class RimeUserFileSyncContractTest {

    private val sync = RepoFiles.file(
        "app/src/main/java/com/kingzcheung/xime/settings/RimeUserFileSync.kt",
    ).readText()

    /** 去掉注释行，只对可执行代码做断言（避免注释里的字样造成误判）。 */
    private fun executable(source: String): String =
        source.lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    @Test
    fun `应用启动与授权后必须执行同步`() {
        val userConfigDir = RepoFiles.file(
            "app/src/main/java/com/kingzcheung/xime/settings/UserConfigDir.kt",
        ).readText()
        assertTrue(
            "releaseUserConfig 应调用 RimeUserFileSync.sync",
            userConfigDir.contains("RimeUserFileSync.sync(context)"),
        )
        // releaseUserConfig 是「启动」与「授权后」两条路径的共同入口
        // （XimeApplication 启动 + MainActivity.onResume 授权返回），接在这里一处即可覆盖两者
        val releaseBody = userConfigDir.substringAfter("fun releaseUserConfig")
            .substringBefore("\n    }")
        assertTrue(
            "同步必须在 ensureReleased 之后（目录就绪了才有意义）",
            releaseBody.indexOf("ensureReleased") < releaseBody.indexOf("RimeUserFileSync.sync"),
        )
    }

    @Test
    fun `每一处部署调用之前都必须有同步`() {
        // 这是「改了没生效」的直接原因：若同步发生在 deploy() 之后（或漏了某个入口），
        // 用户在可见目录里的修改要等下一次部署才生效，甚至永远不生效。
        //
        // 用「逐行扫描 + 记录当前作用域是否已同步」而不是比较全局下标：
        // 一个文件里可能有多个部署入口（ImeSchemaController 就有三个），
        // 全局下标只能验证第一个，漏掉的入口测不出来——本测试最初正是因此漏报。
        val files = listOf(
            "app/src/main/java/com/kingzcheung/xime/service/ImeSchemaController.kt",
            "app/src/main/java/com/kingzcheung/xime/viewmodel/SchemaSettingsViewModel.kt",
        )
        var deploySites = 0
        files.forEach { path ->
            val lines = RepoFiles.file(path).readText().lines()
            var syncedInScope = false
            lines.forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
                // 每个新的函数起点重置标记，避免上一处的同步「照亮」下一处
                if (line.startsWith("fun ") || line.startsWith("private fun ") ||
                    line.startsWith("internal fun ")
                ) {
                    syncedInScope = false
                }
                if (line.contains("RimeUserFileSync.sync(")) syncedInScope = true
                if (line.contains(".deploy()")) {
                    deploySites++
                    assertTrue(
                        "$path:${index + 1} 的「$line」之前没有 RimeUserFileSync.sync",
                        syncedInScope,
                    )
                }
            }
        }
        assertTrue("应至少检查到 4 处部署调用，实际 $deploySites", deploySites >= 4)
    }

    @Test
    fun `镜像目录位于可见配置目录之下`() {
        val code = executable(sync)
        assertTrue(
            "镜像目录应建在 UserConfigDir.absoluteDir 之下",
            code.contains("File(UserConfigDir.absoluteDir, MIRROR_DIR_NAME)"),
        )
        assertEquals("镜像子目录名应为 rime", "rime", RimeUserFileSync.MIRROR_DIR_NAME)
    }

    @Test
    fun `同步源必须是 Rime 工作目录而不是另建路径`() {
        // 若这里改成自拼路径，就会与 SchemaManager.getRimeDir 漂移，
        // 表现为「同步了一个 Rime 并不读取的目录」，改完毫无效果
        val code = executable(sync)
        assertTrue(
            "应从 SchemaManager.getRimeDir 取源目录",
            code.contains("SchemaManager.getRimeDir(context)"),
        )
        assertFalse(
            "不得自行拼接 filesDir/rime",
            code.contains("File(context.filesDir, \"rime\")"),
        )
    }

    @Test
    fun `未授权时不得产生任何写入`() {
        val code = executable(sync)
        val syncBody = code.substringAfter("fun sync(context: Context)")
        // 权限判定必须早于任何遍历/写入动作
        val permissionAt = syncBody.indexOf("hasWritePermission")
        val collectAt = syncBody.indexOf("collectSyncable")
        val writeAt = syncBody.indexOf("writeBaseline")
        assertTrue("应有权限判定", permissionAt >= 0)
        assertTrue("权限判定必须早于遍历", permissionAt < collectAt)
        assertTrue("权限判定必须早于写基线", permissionAt < writeAt)
        assertTrue(
            "未授权应提前返回并标记 permissionRequired",
            syncBody.contains("permissionRequired = true"),
        )
    }

    @Test
    fun `说明文件必须讲清镜像目录与删除规则`() {
        // 用户看不到任何提示就会以为「目录里怎么只有两个文件」——这正是本功能的起因
        val userConfigDir = RepoFiles.file(
            "app/src/main/java/com/kingzcheung/xime/settings/UserConfigDir.kt",
        ).readText()
        val readme = userConfigDir.substringAfter("private val README_TEXT")
        assertTrue(
            "说明应提到镜像目录（引用常量，避免与实现漂移）",
            readme.contains("RimeUserFileSync.MIRROR_DIR_NAME"),
        )
        assertTrue("说明应讲清删除不会同步", readme.contains("删除"))
        assertTrue("说明应讲清部署后才生效", readme.contains("部署"))
    }

    @Test
    fun `引擎初始化完成后必须再同步一次`() {
        // releaseUserConfig 与引擎初始化是两个独立协程，谁先完成不确定。
        // 全新安装时若只靠前者，镜像目录会建成空的——用户授权后打开
        // `/storage/emulated/0/Xime/` 看不到 rime/，正是本功能要消除的困惑。
        val app = RepoFiles.file(
            "app/src/main/java/com/kingzcheung/xime/XimeApplication.kt",
        ).readText()
        assertTrue("应用启动应同步一次", app.contains("RimeUserFileSync.sync("))
        assertTrue(
            "同步必须在 ensureDeployment 之后（那时工作目录才就绪）",
            app.indexOf("ensureDeployment") < app.indexOf("RimeUserFileSync.sync("),
        )
    }

    @Test
    fun `绝不删除私有文件以保护常驻输入法`() {
        // 可见侧文件缺失时判定为 PUSH_TO_VISIBLE（恢复），而不是删除私有侧。
        // 输入法是常驻应用，误删 symbols.yaml 会让标点输入失效，恢复远比删除安全。
        val code = executable(sync)
        assertTrue(
            "可见侧缺失应判定为恢复而非删除",
            code.contains("visHash == null -> SyncAction.PUSH_TO_VISIBLE"),
        )
        assertFalse(
            "不得出现删除私有文件的动作",
            code.contains("priv.delete()") || code.contains("deletePrivate"),
        )
    }

    @Test
    fun `应用侧的删除必须传播到镜像目录`() {
        // 回归保护：卸载方案后 cleanRimeDir 会清掉工作目录，若同步只会 PUSH_TO_VISIBLE，
        // 刚清理掉的文件会被镜像复活，用户看到「卸载了但文件还在」
        val code = executable(sync)
        assertTrue(
            "应定义删除镜像的动作",
            code.contains("DELETE_VISIBLE"),
        )
        assertTrue(
            "私有缺失且可见未改动时应删除可见副本",
            code.contains("visHash == baseHash -> SyncAction.DELETE_VISIBLE"),
        )
        assertTrue(
            "删除动作应真的删掉可见文件",
            code.contains("vis.delete()"),
        )
    }
}
