package com.kingzcheung.xime.clipboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板内容级敏感识别（P1-3）。
 *
 * 缺陷背景：采集入口原先只看密码管理器打的 `IS_SENSITIVE` extra。用户从普通应用复制
 * 银行卡号、明文密码、API key 时没有任何标记 → 照常入库 → 进备份 zip（明文）→ 推云同步。
 *
 * 修法：复用 AI 截图链路的 LocalPiiRedactor（带 Luhn / GB11643 校验位），命中高风险凭据
 * 的文本标记为 TYPE_SENSITIVE，排除备份与云同步（本机仍可粘贴）。
 *
 * 这里钉住：该拦的拦住、不该拦的别误伤（误伤会让正常复制的内容凭空消失）。
 */
class ClipboardSensitivityTest {

    @Test
    fun `plain text is not sensitive`() {
        assertFalse(ClipboardSensitivity.containsHighRiskCredential("明天下午三点开会，记得带材料"))
        assertFalse(ClipboardSensitivity.containsHighRiskCredential("https://example.com/some/page"))
        assertFalse(ClipboardSensitivity.containsHighRiskCredential("hello world"))
    }

    @Test
    fun `blank text is not sensitive`() {
        assertFalse(ClipboardSensitivity.containsHighRiskCredential(""))
        assertFalse(ClipboardSensitivity.containsHighRiskCredential("   "))
    }

    @Test
    fun `valid bank card number is sensitive`() {
        // 4111 1111 1111 1111 是 Luhn 校验通过的经典测试卡号
        assertTrue(
            "Luhn 通过的银行卡号必须识别为敏感",
            ClipboardSensitivity.containsHighRiskCredential("卡号 4111111111111111 请勿外传"),
        )
    }

    @Test
    fun `contextual password is sensitive`() {
        assertTrue(
            "带密码上下文的明文必须识别为敏感",
            ClipboardSensitivity.containsHighRiskCredential("登录密码：MyS3cretPass"),
        )
        assertTrue(
            ClipboardSensitivity.containsHighRiskCredential("password: hunter2hunter2"),
        )
    }

    @Test
    fun `api key is sensitive`() {
        assertTrue(
            "sk- 开头的 API key 必须识别为敏感",
            ClipboardSensitivity.containsHighRiskCredential("sk-proj-abcdefghijklmnopqrstuvwxyz123456"),
        )
        assertTrue(
            ClipboardSensitivity.containsHighRiskCredential("AKIAIOSFODNN7EXAMPLE"),
        )
    }

    @Test
    fun `very long text does not throw and is scanned by prefix`() {
        val huge = "x".repeat(100_000)
        // 不应抛异常、不应卡住（超长只扫前 4096 字符）
        assertFalse(ClipboardSensitivity.containsHighRiskCredential(huge))
        // 前缀里带凭据仍能识别
        val hugeWithKey = "sk-proj-abcdefghijklmnopqrstuvwxyz123456" + "x".repeat(100_000)
        assertTrue(ClipboardSensitivity.containsHighRiskCredential(hugeWithKey))
    }

    @Test
    fun `bank card followed by expiry or cvv is still detected`() {
        // 最典型的信用卡粘贴形态：卡号 + 有效期 / 卡号 + CVV。
        // 旧正则是贪婪的，会把 "12/26" 的 "12" 吞进卡号 → Luhn 失败 → 整条漏判。
        assertTrue(
            "卡号后跟有效期必须仍能识别",
            ClipboardSensitivity.containsHighRiskCredential("4111111111111111 12/26"),
        )
        assertTrue(
            "卡号后跟 CVV 必须仍能识别",
            ClipboardSensitivity.containsHighRiskCredential("4111 1111 1111 1111 123"),
        )
        assertTrue(
            ClipboardSensitivity.containsHighRiskCredential("卡号:4111111111111111 有效期:12/26 CVV:123"),
        )
    }

    @Test
    fun `json password field is sensitive`() {
        assertTrue(
            "JSON 里的 password 字段必须识别",
            ClipboardSensitivity.containsHighRiskCredential("""{"user":"a","password":"MyS3cret!Pass"}"""),
        )
    }

    @Test
    fun `quicksend manual entry with credentials is rejected`() {
        // addQuickSendItem / updateQuickSendItem 是用户手动新建快捷短语的入口。
        // 快捷发送条目会随完整备份外传（backupEntries 收 isQuickSend 行），
        // 所以手动输入的密码/卡号同样要拦——与 addQuickSend 提升路径同口径。
        assertTrue(
            ClipboardSensitivity.containsHighRiskCredential("登录密码：MyS3cretPass"),
        )
        assertTrue(
            ClipboardSensitivity.containsHighRiskCredential("4111111111111111"),
        )
        // 正常短语不拦
        assertFalse(ClipboardSensitivity.containsHighRiskCredential("我的邮箱签名"))
    }

    @Test
    fun `phone number alone is not treated as high risk`() {
        // 手机号属「可还原」类别：不拦，否则复制通讯录也会被标敏感、进不了备份
        assertFalse(
            "普通手机号不应被判为高风险凭据",
            ClipboardSensitivity.containsHighRiskCredential("联系电话 13800138000"),
        )
    }
}
