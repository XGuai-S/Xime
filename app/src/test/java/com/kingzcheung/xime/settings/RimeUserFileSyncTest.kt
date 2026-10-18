package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RimeUserFileSync] 的同步语义测试。
 *
 * 重点覆盖两件容易出错的事：
 * 1. **同步范围**：大词库要同步（用户明确要求能改），但二进制与运行状态必须排除，
 *    排除判定要优先于包含判定（否则 `foo.userdb.txt` 会被当成可同步的文本）；
 * 2. **冲突判定**：三向基线比对必须能区分「应用更新了」与「用户改了」，
 *    把两者搞反会导致用户的修改被静默覆盖。
 */
class RimeUserFileSyncTest {

    // ── 同步范围 ──

    @Test
    fun `用户可编辑的文本配置参与同步`() {
        listOf(
            "default.custom.yaml",
            "pinyin_simp.custom.yaml",
            "symbols.yaml",
            "pinyin_simp.dict.yaml",
            "pinyin_simp_ext.dict.yaml",
            "wubi86_user.dict.yaml",
            "custom_phrase.txt",
            "lua/date_translator.lua",
            "lua/convert_ar_num_to_zh.lua",
        ).forEach {
            assertTrue("$it 应参与同步", RimeUserFileSync.isSyncable(it))
        }
    }

    @Test
    fun `编译产物与运行状态不参与同步`() {
        listOf(
            "build/pinyin_simp.schema.yaml",
            "build/default.yaml",
            "build/lua/date_translator.lua",
            "sync/abc/def.yaml",
            "logs/rime.log",
            "installation.yaml",
            "user.yaml",
            "pinyin_simp.userdb",
            "pinyin_simp.userdb.txt",
            "english_trie.bin",
            "foo.db",
        ).forEach {
            assertFalse("$it 不应参与同步", RimeUserFileSync.isSyncable(it))
        }
    }

    @Test
    fun `完整镜像补入运行状态与编译产物`() {
        // 「用其他应用打开」看到的目录内容要与「打开配置目录」（私有工作目录）一致：
        // sync() 只管可编辑文本，其余由完整镜像单向补齐
        listOf(
            "build/XGuai_flypy.table.bin",
            "build/XGuai_flypy.prism.bin",
            "build/XGuai_flypy.schema.yaml",
            "XGuai_flypy.userdb",
            "pinyin_simp.userdb",
            "installation.yaml",
            "user.yaml",
        ).forEach {
            assertTrue("$it 应补入镜像", RimeUserFileSync.shouldMirrorRuntime(it))
        }
    }

    @Test
    fun `完整镜像不重复处理文本也不搬运行日志`() {
        listOf(
            // 文本归 sync() 双向同步，补镜像不得重复处理
            "default.custom.yaml",
            "custom_phrase.txt",
            "lua/date_translator.lua",
            // 运行日志与版本库不进镜像
            "logs/rime.log",
            "sync/abc/def.yaml",
            ".git/config",
            "",
        ).forEach {
            assertFalse("$it 不应由完整镜像处理", RimeUserFileSync.shouldMirrorRuntime(it))
        }
    }

    @Test
    fun `排除判定优先于包含判定`() {
        // 这条是本类最容易写反的地方：`foo.userdb.txt` 以 .txt 结尾，
        // 若先判「包含」就会把 userdb 导出当成可同步文本，用户一改就被 Rime 重新生成覆盖
        assertFalse(
            "userdb 导出以 .txt 结尾，仍必须排除",
            RimeUserFileSync.isSyncable("pinyin_simp.userdb.txt"),
        )
        assertTrue(
            "普通 txt 仍应参与同步（对照组）",
            RimeUserFileSync.isSyncable("custom_phrase.txt"),
        )
    }

    @Test
    fun `根目录的键盘配置不重复出现在镜像里`() {
        // xime.yaml 已释放到 Xime/ 根目录，镜像里再来一份会让人分不清该改哪个
        assertFalse(
            "rime 目录里的 xime.yaml 不应镜像",
            RimeUserFileSync.isSyncable("xime.yaml"),
        )
    }

    @Test
    fun `冲突备份文件不会被再次同步`() {
        // 否则每次冲突都会生成新备份，无限增长
        assertFalse(
            "app-backup 文件不应参与同步",
            RimeUserFileSync.isSyncable("pinyin_simp.custom.yaml.app-backup"),
        )
    }

    @Test
    fun `隐藏文件与空路径不参与同步`() {
        assertFalse(RimeUserFileSync.isSyncable(".hidden.yaml"))
        assertFalse(RimeUserFileSync.isSyncable(""))
        assertFalse(RimeUserFileSync.isSyncable("   "))
        assertFalse(RimeUserFileSync.isSyncable("build/.gitkeep"))
    }

    @Test
    fun `损坏文件名不参与同步`() {
        // 中文名被错误编码转换后字节无法解码，文件名含 U+FFFD 替换符；
        // 同步会把损坏名带进可见目录，正确内容在正常路径下同步即可
        assertFalse(
            "乱码说明文档不应镜像",
            RimeUserFileSync.isSyncable("rime\uFFFD\uFFFD\uFFFD小\uFFFD\uFFFD说\uFFFD\uFFFD.txt"),
        )
        assertFalse(
            "乱码目录里的文件不应镜像",
            RimeUserFileSync.isSyncable("\uFFFD\uFFFD\uFFFD\uFFFD/rime.lua"),
        )
        // 对照组：正常 lua 脚本仍应同步（lua 统一放 rime/lua/ 下）
        assertTrue(
            "正常 lua 脚本仍应参与同步",
            RimeUserFileSync.isSyncable("lua/date_translator.lua"),
        )
    }

    @Test
    fun `反斜杠路径按分隔符正确归一化`() {
        // Windows 上 relativeTo().path 用反斜杠，若不归一化则排除目录判断会失效
        assertFalse(
            "build\\x.schema.yaml 也应识别为 build 目录",
            RimeUserFileSync.isSyncable("build\\pinyin_simp.schema.yaml"),
        )
        assertTrue(
            "反斜杠路径的普通配置仍应同步",
            RimeUserFileSync.isSyncable("sub\\foo.custom.yaml"),
        )
    }

    // ── 三向基线比对 ──

    @Test
    fun `两侧都不存在时不做任何动作`() {
        assertEquals(
            RimeUserFileSync.SyncAction.NONE,
            RimeUserFileSync.decideAction(null, null, null),
        )
    }

    @Test
    fun `首次释放把私有版本推到可见目录`() {
        assertEquals(
            "可见侧没有文件时应释放",
            RimeUserFileSync.SyncAction.PUSH_TO_VISIBLE,
            RimeUserFileSync.decideAction(privHash = "p1", visHash = null, baseHash = null),
        )
    }

    @Test
    fun `用户在可见目录新增的文件要写回私有目录`() {
        assertEquals(
            RimeUserFileSync.SyncAction.PUSH_TO_PRIVATE,
            RimeUserFileSync.decideAction(privHash = null, visHash = "v1", baseHash = null),
        )
    }

    @Test
    fun `应用删除私有文件时同步删除未改动的可见副本`() {
        // 关键回归：卸载方案后 cleanRimeDir 清掉工作目录里的文件，
        // 若这里判定为 PUSH_TO_PRIVATE，同步会把刚清理的文件从镜像复活回去，
        // 表现为「卸载了方案但文件还在」
        assertEquals(
            "可见副本自上次同步未变，说明是应用删的，应一并删除",
            RimeUserFileSync.SyncAction.DELETE_VISIBLE,
            RimeUserFileSync.decideAction(privHash = null, visHash = "base", baseHash = "base"),
        )
    }

    @Test
    fun `应用删除私有文件但用户改过可见副本时保留用户的成果`() {
        // 与上一条的区别只在可见副本是否等于基线：用户改过就不该被删掉
        assertEquals(
            "用户改过可见副本时应保留并写回私有",
            RimeUserFileSync.SyncAction.PUSH_TO_PRIVATE,
            RimeUserFileSync.decideAction(privHash = null, visHash = "user", baseHash = "base"),
        )
    }

    @Test
    fun `用户删除可见文件时从私有恢复而不删除私有`() {
        // 与「应用删除」方向相反：输入法常驻，误删 symbols.yaml 会让标点输入失效，
        // 因此可见侧缺失一律恢复，绝不反向删除私有文件
        assertEquals(
            RimeUserFileSync.SyncAction.PUSH_TO_VISIBLE,
            RimeUserFileSync.decideAction(privHash = "p1", visHash = null, baseHash = "p1"),
        )
    }

    @Test
    fun `两个方向的删除语义必须相反`() {
        // 反证：若把两个方向写成同一种动作，要么「卸载不干净」，要么「误删即失能」
        val appDeleted = RimeUserFileSync.decideAction(null, "base", "base")
        val userDeleted = RimeUserFileSync.decideAction("base", null, "base")
        assertTrue(
            "应用删除应传播到可见目录",
            appDeleted == RimeUserFileSync.SyncAction.DELETE_VISIBLE,
        )
        assertTrue(
            "用户删除应从私有恢复",
            userDeleted == RimeUserFileSync.SyncAction.PUSH_TO_VISIBLE,
        )
        assertTrue("两者必须不同", appDeleted != userDeleted)
    }

    @Test
    fun `两侧内容一致时不做动作`() {
        assertEquals(
            "内容相同必须判定为无需动作，否则每次启动都白写一遍",
            RimeUserFileSync.SyncAction.NONE,
            RimeUserFileSync.decideAction(privHash = "same", visHash = "same", baseHash = "same"),
        )
    }

    @Test
    fun `可见侧被用户修改时覆盖私有侧`() {
        // 私有仍是基线（应用没动过），可见变了 → 是用户改的
        assertEquals(
            RimeUserFileSync.SyncAction.PUSH_TO_PRIVATE,
            RimeUserFileSync.decideAction(privHash = "base", visHash = "user", baseHash = "base"),
        )
    }

    @Test
    fun `私有侧被应用更新时覆盖可见侧`() {
        // 可见仍是基线（用户没动过），私有变了 → 是应用更新的
        assertEquals(
            RimeUserFileSync.SyncAction.PUSH_TO_VISIBLE,
            RimeUserFileSync.decideAction(privHash = "app", visHash = "base", baseHash = "base"),
        )
    }

    @Test
    fun `两侧都被修改时判定为冲突并保留可见版本`() {
        assertEquals(
            RimeUserFileSync.SyncAction.CONFLICT_KEEP_VISIBLE,
            RimeUserFileSync.decideAction(privHash = "app", visHash = "user", baseHash = "base"),
        )
    }

    @Test
    fun `无基线且两侧不同时以可见为准`() {
        // 用户可能在首次同步前就放好了自己的配置，此时应尊重可见侧
        assertEquals(
            RimeUserFileSync.SyncAction.PUSH_TO_PRIVATE,
            RimeUserFileSync.decideAction(privHash = "p1", visHash = "v1", baseHash = null),
        )
    }

    @Test
    fun `冲突与用户修改必须被区分而不是都当成用户改`() {
        // 反证：把「应用更新」误判成「用户改」会让用户看到应用的新默认值被旧副本顶回去；
        // 反过来把「用户改」误判成「应用更新」则会静默丢掉用户的编辑。
        // 两者行为必须不同。
        val appUpdated = RimeUserFileSync.decideAction("app", "base", "base")
        val userEdited = RimeUserFileSync.decideAction("base", "user", "base")
        assertTrue(
            "应用更新应写向可见目录",
            appUpdated == RimeUserFileSync.SyncAction.PUSH_TO_VISIBLE,
        )
        assertTrue(
            "用户修改应写向私有目录",
            userEdited == RimeUserFileSync.SyncAction.PUSH_TO_PRIVATE,
        )
        assertTrue("两者必须不同", appUpdated != userEdited)
    }

    // ── 基线清单 ──

    @Test
    fun `基线清单可往返解析`() {
        val baseline = linkedMapOf(
            "default.custom.yaml" to "aaa",
            "pinyin_simp.dict.yaml" to "bbb",
        )
        val text = RimeUserFileSync.renderBaseline(baseline)
        assertEquals(baseline, RimeUserFileSync.parseBaseline(text))
    }

    @Test
    fun `基线清单渲染结果按路径排序且稳定`() {
        val first = RimeUserFileSync.renderBaseline(
            linkedMapOf("b.yaml" to "2", "a.yaml" to "1"),
        )
        val second = RimeUserFileSync.renderBaseline(
            linkedMapOf("a.yaml" to "1", "b.yaml" to "2"),
        )
        assertEquals("顺序不同的同一份数据应渲染成相同文本", first, second)
        assertTrue("a 应排在 b 前", first.indexOf("a.yaml") < first.indexOf("b.yaml"))
    }

    @Test
    fun `基线清单忽略空行与格式不符的行`() {
        val parsed = RimeUserFileSync.parseBaseline(
            """
            
            abc123	foo.custom.yaml
            这行没有制表符
            	空路径
            def456	bar.dict.yaml
            """.trimIndent(),
        )
        assertEquals(
            mapOf("foo.custom.yaml" to "abc123", "bar.dict.yaml" to "def456"),
            parsed,
        )
    }

    @Test
    fun `空基线解析为空映射`() {
        assertTrue(RimeUserFileSync.parseBaseline("").isEmpty())
        assertTrue(RimeUserFileSync.parseBaseline("   \n  ").isEmpty())
    }
}
