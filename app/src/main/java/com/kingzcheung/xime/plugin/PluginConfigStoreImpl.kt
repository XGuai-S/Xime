package com.kingzcheung.xime.plugin

import android.app.Application
import android.content.Context
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore

/**
 * 插件配置存储：值为 Android Keystore AES-GCM 密文，读时解密。
 *
 * ## 为什么带内存缓存
 *
 * [SecureValueCipher.decrypt] 每次都要做一次 AES-GCM 运算。网络请求路径上
 * （`HttpHostApiImpl` / `WsHostApiImpl` / `SseUrlAuthorizer`）每次请求都会调用
 * `ExtensionManager.getConfiguredNetworkHosts`，而它要**遍历全部配置项逐个解密**
 * 才能解析出域名白名单——请求越频繁、配置项越多，这里就越像在给每个请求加税。
 *
 * 缓存以「密文原文」为键：密文变了缓存自然失效，不需要额外的失效通知，
 * 也不会出现「改了配置却仍被旧白名单拦截」的陈旧数据问题。
 *
 * ## 一致性
 *
 * - 写入/删除走本类的 [set] / [remove]，同步更新缓存；
 * - 外部（如设置页直接改 SharedPreferences）改动时，密文原文不同 → 缓存不命中，
 *   自动重新解密。因此缓存**不会**返回过期值。
 *
 * 缓存只存解密后的**配置值**，不存密钥；进程退出即消失。
 */
class PluginConfigStoreImpl(
    private val app: Application,
    private val pluginId: String,
    private val cipher: ValueCipher = SecureValueCipher
) : PluginConfigStore {

    private val prefs: android.content.SharedPreferences =
        app.getSharedPreferences("plugin_cfg_$pluginId", Context.MODE_PRIVATE)

    override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        // 以密文原文为缓存键：外部改动会让密文变化，缓存自然失效。
        cachedDecrypted[stored]?.let { return it }
        val plain = cipher.decrypt(stored) ?: return null
        cachedDecrypted[stored] = plain
        return plain
    }

    override fun set(key: String, value: String) {
        val stored = cipher.encrypt(value)
        prefs.edit().putString(key, stored).apply()
        cachedDecrypted[stored] = value
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun keys(): Set<String> = prefs.all.keys

    private companion object {
        /**
         * 密文 → 明文。按进程共享（不同 pluginId 的密文互不相同，无需按插件分桶）。
         *
         * 容量上限防止长期运行累积：插件配置项数量有限（几十项量级），
         * 超限时整体清空即可——重新解密的代价远小于内存无限增长。
         */
        private const val MAX_CACHED_VALUES = 256
        private val cachedDecrypted =
            java.util.Collections.synchronizedMap(
                object : LinkedHashMap<String, String>(16, 0.75f, true) {
                    override fun removeEldestEntry(
                        eldest: MutableMap.MutableEntry<String, String>?
                    ): Boolean = size > MAX_CACHED_VALUES
                }
            )
    }
}
