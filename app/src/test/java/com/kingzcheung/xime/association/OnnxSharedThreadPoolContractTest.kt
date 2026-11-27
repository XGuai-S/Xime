package com.kingzcheung.xime.association

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/** ONNX 会话必须真正使用共享线程池，并显式关闭空闲自旋，避免移动端待机耗电。 */
class OnnxSharedThreadPoolContractTest {
    private fun native(rel: String) = RepoFiles.file("src/main/jni/$rel").readText()

    private val envHeader by lazy { native("onnx_env.h") }
    private val envImpl by lazy { native("onnx_env.cc") }
    private val association by lazy { native("onnx_jni.cc") }
    private val handwriting by lazy { native("handwriting_jni.cc") }
    private val asr by lazy { native("asr/zipformer2_model.cc") }

    @Test
    fun `shared env exposes one session options helper`() {
        assertTrue(envHeader.contains("OnnxApplySharedThreadPoolOptions"))
        assertTrue(envImpl.contains("bool OnnxApplySharedThreadPoolOptions("))
    }

    @Test
    fun `helper disables per session threads and idle spinning`() {
        assertTrue(envImpl.contains("api->DisablePerSessionThreads(options)"))
        assertTrue(envImpl.contains("kOrtSessionOptionsConfigAllowIntraOpSpinning"))
        assertTrue(envImpl.contains("kOrtSessionOptionsConfigAllowInterOpSpinning"))
    }

    @Test
    fun `all session owners apply the shared pool helper`() {
        assertTrue(association.contains("OnnxApplySharedThreadPoolOptions("))
        assertTrue(handwriting.contains("OnnxApplySharedThreadPoolOptions("))
        assertTrue(asr.contains("OnnxApplySharedThreadPoolOptions("))
    }
}
