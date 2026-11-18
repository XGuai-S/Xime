package com.kingzcheung.xime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 守住 `RimeProcessResult` 的 JVM 构造签名。
 *
 * **为什么必须有这条测试**：`app/src/main/jni/librime_jni/rime_jni.cc` 里用
 * `GetMethodID(cls, "<init>", "(ZLjava/lang/String;...II)V")` 拿的是**写死的**签名。
 * 一旦 Kotlin 侧字段增删/换序/换类型而 C++ 没跟着改，`GetMethodID` 返回 null，
 * 之后每次 `NewObject` 都会触发原生 abort（SIGABRT）——logcat 里没有任何 Java 栈，
 * 只在 `dumpsys activity exit-info` 里看到 `reason=SIGNALED status=6`，
 * 用户侧表现就是「一打字就闪退」。
 *
 * 3.11.37 因为给这个类加了一个 `stale: Boolean` 字段真实崩过一次（连续 5 次 SIGABRT）。
 * 这条测试把 C++ 里的签名抄一份下来做对账，以后谁再动字段会直接红。
 */
class RimeProcessResultJniSignatureTest {

    /** 与 rime_jni.cc 中 gRimeProcessResultCtor 的 GetMethodID 字面量保持逐字一致。 */
    private val expectedJniSignature =
        "(ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;" +
            "[Lcom/kingzcheung/xime/rime/RimeCandidate;ZZZ" +
            "Ljava/lang/String;Ljava/lang/String;II)V"

    private fun descriptorOf(type: Class<*>): String = when {
        type == java.lang.Boolean.TYPE -> "Z"
        type == java.lang.Integer.TYPE -> "I"
        type == java.lang.Long.TYPE -> "J"
        type == java.lang.Character.TYPE -> "C"
        type == java.lang.Byte.TYPE -> "B"
        type == java.lang.Short.TYPE -> "S"
        type == java.lang.Float.TYPE -> "F"
        type == java.lang.Double.TYPE -> "D"
        type.isArray -> type.name.replace('.', '/') // [Lcom/x/Y; / [I 已是 JVM 形式
        else -> "L" + type.name.replace('.', '/') + ";"
    }

    @Test
    fun `构造签名必须与 JNI 硬编码签名一致`() {
        val ctor = RimeProcessResult::class.java.constructors
            .firstOrNull { it.parameterCount == 12 }
        assertNotNull(
            "找不到 12 参构造器：字段被增删了？必须同步改 rime_jni.cc 的 GetMethodID 签名",
            ctor,
        )
        val actual = ctor!!.parameterTypes.joinToString("") { descriptorOf(it) }
        assertEquals(
            "RimeProcessResult 构造签名与 rime_jni.cc 不一致 —— 不改 C++ 就会 SIGABRT 闪退",
            expectedJniSignature,
            "($actual)V",
        )
    }

    @Test
    fun `候选类签名同样受 JNI 约束`() {
        val ctor = RimeCandidate::class.java.constructors
            .firstOrNull { it.parameterCount == 2 }
        assertNotNull("RimeCandidate 构造器变了：rime_jni.cc 的 gRimeCandidateCtor 也要同步", ctor)
        val actual = ctor!!.parameterTypes.joinToString("") { descriptorOf(it) }
        assertEquals(
            "RimeCandidate 构造签名与 rime_jni.cc 不一致",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            "($actual)V",
        )
    }
}
