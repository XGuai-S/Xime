package com.kingzcheung.xime.clipboard

import com.kingzcheung.xime.clipboard.ClipboardProbePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 补采入库判定（用户 2026-11 反馈「复制的内容在候选栏反复出现」的回归锁）。
 *
 * 现象与日志：同一条 5 字符文本在 20 分钟内「入库」7 次，每次都紧跟 onWindowShown，
 * 数据库里该行 timestamp 一直被刷新、consumed 反复回到 0。
 */
class ClipboardProbePolicyTest {

    @Test
    fun `never seen content is always recorded even if unchanged`() {
        // 复制发生时本进程还没起来 → 监听器没回调、标志为 false，但内容确实是新的
        assertEquals(
            Action.RECORD,
            ClipboardProbePolicy.decide(seenInPanel = false, consumed = false, changedSinceRecord = false)
        )
    }

    @Test
    fun `seen and unchanged is skipped`() {
        // 核心回归：键盘每次弹出补采同一份旧剪贴板，绝不能重复入库（否则反复出现）
        assertEquals(
            Action.SKIP,
            ClipboardProbePolicy.decide(seenInPanel = true, consumed = false, changedSinceRecord = false)
        )
        assertEquals(
            Action.SKIP,
            ClipboardProbePolicy.decide(seenInPanel = true, consumed = true, changedSinceRecord = false)
        )
    }

    @Test
    fun `seen changed but not consumed is skipped`() {
        // 已在候选栏展示，不必再造一次变更事件（避免无谓刷新 timestamp）
        assertEquals(
            Action.SKIP,
            ClipboardProbePolicy.decide(seenInPanel = true, consumed = false, changedSinceRecord = true)
        )
    }

    @Test
    fun `seen changed and consumed is recorded to revive`() {
        // 用户又复制了同一段已被消费的文本 → 必须能重新进候选栏
        assertEquals(
            Action.RECORD,
            ClipboardProbePolicy.decide(seenInPanel = true, consumed = true, changedSinceRecord = true)
        )
    }
}