package com.kingzcheung.xime.service

/**
 * 语音识别会话归属门：每次 startAsr 发一个新的会话号，只有持有当前会话号的调用方
 * 才能推音频、结束或取消。
 *
 * 主进程里有多个 [AsrInferenceClient] 实例（语音面板、流式会话、离线后端），
 * 没有归属校验时，一个实例迟到的 stop/cancel/push 会作用到另一个实例刚开始的会话。
 * 新会话开始即顶替旧会话，旧会话号随即失效。
 *
 * 非线程安全：由调用方在 AsrInferenceService 的 asrLock 内使用。
 */
internal class AsrSessionGate {
    private var current = 0L
    private var last = 0L

    /** 开始新会话，返回其会话号（恒为正数）。 */
    fun begin(): Long {
        last += 1
        current = last
        return current
    }

    /** [sessionId] 是否是当前活动会话。0 表示没有会话，永远不匹配。 */
    fun owns(sessionId: Long): Boolean = sessionId != 0L && sessionId == current

    /** 结束会话；调用方不是当前会话时返回 false 且不改变状态。 */
    fun end(sessionId: Long): Boolean {
        if (!owns(sessionId)) return false
        current = 0L
        return true
    }

    /** 无条件作废当前会话（模型释放、服务销毁时用）。会话号不回退。 */
    fun reset() {
        current = 0L
    }
}
