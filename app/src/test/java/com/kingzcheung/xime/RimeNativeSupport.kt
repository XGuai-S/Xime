package com.kingzcheung.xime

import org.junit.Assume

/**
 * 本机 JVM 能否加载 Rime 的 JNI 库。
 *
 * `RimeEngine` 的 `init { System.loadLibrary("rime_jni") }` 在实例化时执行，
 * 而仓库里 `librime_jni.so` 只有 **ARM64 Linux/Android** 版本：
 * Windows x86_64 等宿主无法加载，Mockito `mock<RimeEngine>()` 需要实例化该类，
 * 于是 `ExceptionInInitializerError: UnsatisfiedLinkError: no rime_jni`，
 * 相关用例（候选/编辑会话隔离）整体报 `NoClassDefFoundError`。
 *
 * 这些用例在 Linux CI（用桩库）与真机 androidTest 上能正常执行，覆盖不该降低。
 * 因此按环境分流：能加载 → 正常跑；不能 → [Assume] 跳过。
 *
 * 只影响测试，不触碰生产代码。
 */
internal object RimeNativeSupport {

    /** 本机能否加载 rime_jni（结果缓存，避免反复试错）。 */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("rime_jni")
            true
        } catch (_: Throwable) {
            // UnsatisfiedLinkError / SecurityException / 架构不匹配都归为不可用
            false
        }
    }

    /** 环境缺 native 库时跳过当前用例。 */
    fun assumeAvailable() {
        Assume.assumeTrue(
            "当前环境无法加载 rime_jni（仓库只提供 ARM64 Linux/Android 的 .so）；" +
                "该用例在 Linux CI 与真机上会正常执行",
            available,
        )
    }
}
