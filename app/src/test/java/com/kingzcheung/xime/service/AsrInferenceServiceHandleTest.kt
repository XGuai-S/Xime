package com.kingzcheung.xime.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * :asr 进程模型句柄重建判定：切换模型后必须按新目录重建句柄，
 * 否则会复用旧句柄继续跑旧模型（文件名相同的模型曾完全无感）。
 */
class AsrInferenceServiceHandleTest {

    @Test
    fun `尚未加载时需创建句柄`() {
        assertTrue(AsrInferenceService.shouldReloadHandle(0L, null, "/models/x-asr"))
    }

    @Test
    fun `同一模型目录复用句柄`() {
        assertFalse(
            AsrInferenceService.shouldReloadHandle(42L, "/models/x-asr", "/models/x-asr")
        )
    }

    @Test
    fun `切换模型目录需重建句柄`() {
        assertTrue(
            AsrInferenceService.shouldReloadHandle(42L, "/models/zipformer-zh-int8", "/models/x-asr")
        )
    }
}
