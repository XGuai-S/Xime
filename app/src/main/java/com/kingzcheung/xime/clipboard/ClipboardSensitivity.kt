package com.kingzcheung.xime.clipboard

import com.kingzcheung.xime.ai.privacy.LocalPiiRedactor

/**
 * 剪贴板内容级敏感识别（P1-3）。
 *
 * ## 为什么需要
 *
 * 采集入口原先只看 `ClipDescription.extras` 里的 `IS_SENSITIVE` 标记——那是**密码管理器
 * 主动打的标签**。用户从普通应用（记事本、微信、网页）复制银行卡号、明文密码、API key、
 * 身份证号时，没有任何标记，于是照常入库 → 进备份 zip（明文）→ 推云同步。
 *
 * ## 做法
 *
 * 复用 AI 截图链路已有的 [LocalPiiRedactor]：它带 Luhn 校验（银行卡）、GB 11643 校验位
 * （身份证）、Gitleaks API key 正则、密码上下文正则，误报率远低于裸正则。
 *
 * 命中「高风险凭据」（`category.restorable == false`：密码、验证码、证件号、银行卡、密钥）
 * 的文本标记为敏感条目：**仍然入库**（用户复制完要能粘回去），但
 * 排除在备份与云同步之外——这是用户真正在意的外泄面。
 *
 * ## 边界
 *
 * - 只判高风险类别；手机号/邮箱/地址这类可还原类别不拦（否则正常复制通讯录也会被标敏感）
 * - 识别是启发式，漏判仍可能发生；这里只降低面，不承诺 100%
 * - 纯本地计算，无网络、无落盘
 */
internal object ClipboardSensitivity {

    /** 超过此长度不做内容识别：几 MB 文本逐正则跑会卡住采集线程。 */
    private const val MAX_SCAN_CHARS = 4096

    /**
     * 文本是否含高风险凭据（密码/验证码/证件号/银行卡/密钥）。
     *
     * @return true = 应标记为敏感条目
     */
    fun containsHighRiskCredential(text: String): Boolean {
        if (text.isBlank()) return false
        // 超长文本截取头部判断：真正的凭据不会出现在几 KB 之后
        val sample = if (text.length > MAX_SCAN_CHARS) text.substring(0, MAX_SCAN_CHARS) else text
        return try {
            LocalPiiRedactor.containsHighRiskCredentials(sample)
        } catch (_: Throwable) {
            // 识别本身出错时按「不敏感」处理：宁可漏标，也不能让采集链路因识别异常中断
            false
        }
    }
}
