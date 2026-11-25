package com.kingzcheung.xime.plugin

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [PluginConfigStoreImpl] 解密缓存的正确性回归。
 *
 * 背景：网络请求路径每次都会遍历全部配置项、逐个做 AES-GCM 解密来解析域名白名单。
 * 加缓存是为了去掉这份重复运算，但缓存最大的风险是**返回过期值**——
 * 用户改了服务器地址却被旧白名单拦住，表现为「配置不生效」。
 * 因此这里把「配置变更后必须看到新值」钉死。
 */
class PluginConfigStoreImplTest {

    /** 计数用 cipher：可验证解密次数，并让密文与明文一一对应。 */
    private class CountingCipher : ValueCipher {
        var decryptCount = 0
        private var seq = 0
        // 格式 enc:<序号>|<明文>：用 | 分隔，因为明文本身含 :（https://…）
        override fun encrypt(plain: String): String = "enc:${seq++}|$plain"
        override fun decrypt(stored: String): String? {
            decryptCount++
            return stored.substringAfter('|', "")
        }
    }

    private class Fixture {
        val backing = HashMap<String, String>()
        val pendingWrites = HashMap<String, String>()
        val pendingRemovals = mutableListOf<String>()
        val prefs: SharedPreferences = mock()
        val app: Application = mock()
        val editor: SharedPreferences.Editor = mock()

        init {
            whenever(app.getSharedPreferences(any(), any())).thenReturn(prefs)
            // getString(key, default) 的第二个参数在生产代码里是 null，
            // 必须用 anyOrNull 匹配，否则桩不生效（返回 null 让被测代码早退）。
            whenever(prefs.getString(any(), anyOrNull())).thenAnswer {
                backing[it.getArgument<String>(0)] ?: it.getArgument<String?>(1)
            }
            whenever(prefs.all).thenAnswer { HashMap(backing) }
            // set/remove 走链式调用：putString(...).apply()，编辑器必须返回自身。
            whenever(editor.putString(any(), any())).thenAnswer {
                pendingWrites[it.getArgument(0)] = it.getArgument(1)
                editor
            }
            whenever(editor.remove(any())).thenAnswer {
                pendingRemovals += it.getArgument<String>(0)
                editor
            }
            whenever(editor.apply()).thenAnswer {
                pendingWrites.forEach { (k, v) -> backing[k] = v }
                pendingRemovals.forEach { backing.remove(it) }
                pendingWrites.clear()
                pendingRemovals.clear()
                null
            }
            whenever(prefs.edit()).thenReturn(editor)
        }

        fun store(cipher: ValueCipher) = PluginConfigStoreImpl(app, "test-plugin", cipher)
    }

    @Test
    fun `同一密文重复读取只解密一次`() {
        val f = Fixture()
        val cipher = CountingCipher()
        f.backing["server"] = "enc:0|https://a.example.com"

        val store = f.store(cipher)
        repeat(5) { store.get("server") }

        assertEquals("重复读取不应反复解密", 1, cipher.decryptCount)
    }

    @Test
    fun `写入后读到的是新值 且不需要解密`() {
        val f = Fixture()
        val cipher = CountingCipher()
        val store = f.store(cipher)

        store.set("server", "https://new.example.com")
        // set 已把明文放进缓存，读回不应再走解密
        val before = cipher.decryptCount
        assertEquals("https://new.example.com", store.get("server"))
        assertEquals("写入后读回不应额外解密", before, cipher.decryptCount)
    }

    @Test
    fun `外部改动密文后必须重新解密并返回新值 不能返回旧缓存`() {
        val f = Fixture()
        val cipher = CountingCipher()
        f.backing["server"] = "enc:0|https://old.example.com"
        val store = f.store(cipher)

        assertEquals("https://old.example.com", store.get("server"))

        // 模拟外部（设置页/插件）直接改写 SharedPreferences：密文变了
        f.backing["server"] = "enc:1|https://changed.example.com"

        assertEquals(
            "密文变化后必须重新解密，不能返回旧值（否则表现为配置不生效）",
            "https://changed.example.com",
            store.get("server"),
        )
        assertEquals("应恰好解密两次（首读 + 变更后）", 2, cipher.decryptCount)
    }

    @Test
    fun `解密失败返回 null 且不写入缓存`() {
        val f = Fixture()
        val failing = object : ValueCipher {
            var calls = 0
            override fun encrypt(plain: String) = plain
            override fun decrypt(stored: String): String? {
                calls++
                return null
            }
        }
        f.backing["broken"] = "enc:0|garbage"
        val store = f.store(failing)

        assertNull(store.get("broken"))
        assertNull(store.get("broken"))
        assertEquals("解密失败不应被缓存，每次都需重试", 2, failing.calls)
    }

    @Test
    fun `缺失键返回 null 且不触碰解密`() {
        val f = Fixture()
        val cipher = CountingCipher()
        val store = f.store(cipher)

        assertNull(store.get("missing"))
        assertEquals("不存在的键不该调用解密", 0, cipher.decryptCount)
    }

    @Test
    fun `keys 反映底层存储的全部键`() {
        val f = Fixture()
        f.backing["a"] = "enc:0|1"
        f.backing["b"] = "enc:1|2"
        val store = f.store(CountingCipher())

        assertEquals(setOf("a", "b"), store.keys())
    }

    @Test
    fun `缓存容量有上限 不会随配置项无限增长`() {
        val f = Fixture()
        val cipher = CountingCipher()
        val store = f.store(cipher)

        // 写入远超上限的不同值，然后读第一个：应因被淘汰而需要重新解密。
        repeat(300) { i ->
            f.backing["k$i"] = "enc:$i|v$i"
            store.get("k$i")
        }
        val before = cipher.decryptCount
        store.get("k0")
        assertTrue(
            "早期条目应已被淘汰，重读需要重新解密（证明缓存有界）",
            cipher.decryptCount > before,
        )
    }
}
