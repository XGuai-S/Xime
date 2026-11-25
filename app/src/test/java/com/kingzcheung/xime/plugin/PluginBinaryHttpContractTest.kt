package com.kingzcheung.xime.plugin

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件对外 API 的语义契约。
 *
 * 三条都是「看起来成功、实际语义错误」的类型：
 * - `bin.uint32be` 复用 int32be 的 Int 通路，超过 Int.MAX 的值被饱和；
 * - `http.request` 对 PROPFIND/MKCOL 固定传 null body，插件 XML 被静默吞掉；
 * - `PluginErrorLog` 的列表在被多线程 add/trim 时裸 toList 会抛并发异常。
 * 插件测试需要 xipm 产物，这里用源码契约钉住，避免回归。
 */
class PluginBinaryHttpContractTest {
    private fun read(name: String): String {
        val candidates = listOf(
            "plugin-core/src/main/java/com/kingzcheung/xime/plugin/core/$name",
            "app/src/main/java/com/kingzcheung/xime/plugin/$name",
        )
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (rel in candidates) {
                val file = java.io.File(dir, rel)
                if (file.isFile) return file.readText().replace("\r\n", "\n")
            }
            dir = dir.parentFile
        }
        error("找不到源码 $name")
    }

    @Test
    fun `uint32be does not reuse signed int encoder`() {
        val source = read("js/JsScriptRuntime.kt")
        assertFalse(
            "uint32be 不得复用 int32be：Double.toInt() 会饱和，0xB2D05E00 会编成 7F FF FF FF",
            Regex("""uint32be"\)\s*\{\s*args\s*->\s*int32be\(""").containsMatchIn(source),
        )
        assertTrue(
            "uint32be 必须走无符号编码通路",
            Regex("""uint32be"\)\s*\{\s*args\s*->\s*uint32be\(""").containsMatchIn(source),
        )
        assertTrue("必须存在真正的无符号编码实现", source.contains("private fun uint32be(value: Double)"))
    }

    @Test
    fun `webdav methods forward the caller body`() {
        val source = read("http/HttpHostApiImpl.kt")
        assertFalse(
            "PROPFIND/MKCOL 传 null 会静默丢弃插件请求体",
            source.contains("\"MKCOL\" -> requestBuilder.method(\"MKCOL\", null)") ||
                source.contains("\"PROPFIND\" -> requestBuilder.method(\"PROPFIND\", null)"),
        )
        assertTrue(source.contains("\"MKCOL\" -> requestBuilder.method(\"MKCOL\", body)"))
        assertTrue(source.contains("\"PROPFIND\" -> requestBuilder.method(\"PROPFIND\", body)"))
    }

    @Test
    fun `error log list reads are synchronized`() {
        val source = read("security/PluginErrorLog.kt")
        assertFalse(
            "无锁 toList 在并发 add/trim 下会抛 ConcurrentModificationException",
            source.contains("errorLogs[pluginId]?.toList() ?: emptyList()"),
        )
        assertTrue(source.contains("synchronized(list) { (list as List<PluginError>).toList() }"))
    }
}
