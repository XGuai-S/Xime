package com.kingzcheung.xime.clipboard

/**
 * 补采（键盘弹出时主动再读一次系统剪贴板）命中内容后的入库决策。
 *
 * 背景：Android 10+ 限制「非焦点应用读剪贴板」，系统监听回调虽会触发但
 * `primaryClip` 常返回 null。键盘弹出时输入法已获得焦点，是唯一可靠的读取时机，
 * 因此 [com.kingzcheung.xime.clipboard.ClipboardManager.captureCurrentClipboardNow]
 * 每次窗口可见都会补采一次。
 *
 * 问题：补采读到的绝大多数是**早就入过库的旧内容**。若补采也走「复活」语义
 * （刷新 timestamp + 复位 consumed），那么每弹一次键盘就把同一条内容重新激活：
 * timestamp 永远落在 30 秒候选窗口内 → 候选栏反复出现同一条；且 consumed 反复被
 * 清零，用户按键后也无法真正「消费」掉它。真机日志表现是同一条文本在 20 分钟内
 * 「入库」7 次。
 *
 * 这个对象把判定抽成纯函数（不依赖 Android / 数据库），便于单测锁定行为。
 */
internal object ClipboardProbePolicy {

    /** 补采命中后的处理动作。 */
    enum class Action {
        /** 静默丢弃：旧剪贴板 / 已在候选栏展示，无需任何写入。 */
        SKIP,

        /** 按新内容入库（含「再次复制同一段已消费文本」的复活语义）。 */
        RECORD,
    }

    /**
     * @param seenInPanel 面板库里是否已存在**同文本**条目
     * @param consumed    该条目是否已被上屏消费（仅 [seenInPanel] 为 true 时有效）
     * @param changedSinceRecord 本进程内系统监听器是否报告过剪贴板变更
     *   （后台读不到内容时，回调本身仍能证明剪贴板变了）
     * @param stillVisible 该条目是否仍在候选栏的展示窗口内（未过期）
     */
    fun decide(
        seenInPanel: Boolean,
        consumed: Boolean,
        changedSinceRecord: Boolean,
        stillVisible: Boolean = true,
    ): Action {
        // 面板里从没见过这段内容 → 一定是新内容。涵盖「复制时本进程还没起来」
        // 的场景：那时监听器不会回调、changedSinceRecord 为 false，但内容确实是新的。
        if (!seenInPanel) return Action.RECORD

        // 见过、已验证过、但**已经滑出候选栏展示窗口**（复制后隔了一段时间才弹键盘）：
        // 必须刷新时间戳让它重新进窗口，否则用户会说「复制了内容有时候不显示」。
        // 这条以前没有，是「未消费就跳过」的隐含假设错误 ——
        // 「未消费」只说明用户还没用它，「没过期」才是「它现在就在候选栏里」。
        if (!consumed && !stillVisible) return Action.RECORD

        // 见过但剪贴板没变过 → 这是同一份旧剪贴板，重复入库只会刷新时间戳
        if (!changedSinceRecord) return Action.SKIP
        // 变过 + 仍在展示（未消费）→ 已在候选栏，无需再造一次变更事件
        if (!consumed) return Action.SKIP
        // 变过 + 已被消费 → 用户又复制了同一段，按重新复制复活
        return Action.RECORD
    }
}