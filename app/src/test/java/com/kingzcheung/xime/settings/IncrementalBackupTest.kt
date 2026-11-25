package com.kingzcheung.xime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件级增量备份的纯逻辑（不上网络、不碰 Android）。
 *
 * 这里锚定三件事：
 * 1. **只传变化的文件**——整包时代每次重传几十 MB，这是本次改动的核心收益；
 * 2. **清单可往返**——清单决定"哪些文件已是最新"，解析错一个字符就会全量重传或漏传；
 * 3. **路径不穿越**——zip 条目名来自备份包，必须清洗后再落 staging。
 */
class IncrementalBackupTest {

    @Test
    fun `plan uploads only changed files`() {
        val local = mapOf(
            "rime/user.yaml" to "aaa",
            "rime/default.custom.yaml" to "bbb",
            "_xime_backup/settings.json" to "ccc",
        )
        val remote = mapOf(
            "rime/user.yaml" to "aaa",          // 未变
            "rime/default.custom.yaml" to "OLD", // 变了
            "_xime_backup/settings.json" to "ccc", // 未变
        )

        val plan = IncrementalBackup.planUploads(local, remote)

        assertEquals(
            "只有哈希不同的那一个文件需要上传",
            listOf("rime/default.custom.yaml" to "bbb"),
            plan.toUpload,
        )
        assertEquals(2, plan.unchanged)
        assertTrue(plan.remoteOnly.isEmpty())
    }

    @Test
    fun `remote without hash is treated as changed`() {
        // 插件没返回 sha256 时远端哈希是空串——必须保守重传，
        // 否则「远端有文件但哈希未知」会被误判成最新，更新永久丢失
        val local = mapOf("a.txt" to "h1")
        val remote = mapOf("a.txt" to "")

        val plan = IncrementalBackup.planUploads(local, remote)

        assertEquals(1, plan.toUpload.size)
        assertEquals(0, plan.unchanged)
    }

    @Test
    fun `first backup uploads everything`() {
        val local = mapOf("a" to "1", "b" to "2")
        val plan = IncrementalBackup.planUploads(local, emptyMap())
        assertEquals(2, plan.toUpload.size)
        assertEquals(0, plan.unchanged)
    }

    @Test
    fun `unchanged backup uploads nothing`() {
        val local = mapOf("a" to "1", "b" to "2")
        val plan = IncrementalBackup.planUploads(local, local)
        assertTrue("内容全没变时不得上传任何文件", plan.toUpload.isEmpty())
        assertEquals(2, plan.unchanged)
    }

    @Test
    fun `remote only files are reported but never uploaded`() {
        val local = mapOf("a" to "1")
        val remote = mapOf("a" to "1", "old/gone.txt" to "x")
        val plan = IncrementalBackup.planUploads(local, remote)
        assertTrue(plan.toUpload.isEmpty())
        assertEquals(listOf("old/gone.txt"), plan.remoteOnly)
    }

    @Test
    fun `manifest round trips`() {
        val files = mapOf(
            "rime/user.yaml" to "0f1e2d",
            "_xime_backup/settings.json" to "aabbcc",
            "含空格 的路径.txt" to "ddeeff",
        )

        val text = IncrementalBackup.renderManifest(files)
        val parsed = IncrementalBackup.parseManifest(text)

        assertEquals("清单必须能往返", files, parsed)
    }

    @Test
    fun `manifest parse rejects unknown version`() {
        val text = "# xime incremental backup manifest v99\nabc\tfile.txt\n"
        assertNull("版本不认识时必须返回 null，让调用方回退整包", IncrementalBackup.parseManifest(text))
    }

    @Test
    fun `manifest parse rejects garbage`() {
        assertNull(IncrementalBackup.parseManifest(""))
        assertNull(IncrementalBackup.parseManifest("not a manifest"))
        assertNull(IncrementalBackup.parseManifest("{}"))
    }

    @Test
    fun `manifest skips malformed lines`() {
        val text = "# xime incremental backup manifest v1\n" +
            "good\trime/user.yaml\n" +
            "no-tab-here\n" +
            "\tmissing-hash.txt\n" +
            "hash\tonly.txt\n"
        val parsed = IncrementalBackup.parseManifest(text)
        assertEquals(
            "格式不符的行要跳过，不能污染清单",
            mapOf("rime/user.yaml" to "good", "only.txt" to "hash"),
            parsed,
        )
    }

    @Test
    fun `staging path never escapes`() {
        assertEquals("a/b.txt", IncrementalBackup.safeStagingPath("a/b.txt"))
        assertEquals("etc/passwd", IncrementalBackup.safeStagingPath("../../etc/passwd"))
        assertEquals("a/c", IncrementalBackup.safeStagingPath("a/./c"))
        assertEquals("_", IncrementalBackup.safeStagingPath(""))
        assertEquals("_", IncrementalBackup.safeStagingPath("/"))
        // 反斜杠按分隔符处理（Windows 打的包）
        assertEquals("a/b", IncrementalBackup.safeStagingPath("a\\b"))
    }

    @Test
    fun `streaming sha256 matches byte array hash`() {
        // 备份走流式哈希（避免把整条 zip 条目读进内存），必须与一次性哈希结果一致，
        // 否则「同一个文件」在备份清单里和实际内容对不上，每次都会重传
        val data = ByteArray(200_000) { (it % 251).toByte() }
        assertEquals(
            IncrementalBackup.sha256Hex(data),
            IncrementalBackup.sha256Of(data.inputStream()),
        )
    }

    @Test
    fun `sha256 matches known vector`() {
        // "abc" 的 SHA-256 是行业标准测试向量
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            IncrementalBackup.sha256Hex("abc".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            IncrementalBackup.sha256Hex(ByteArray(0)),
        )
    }
}
