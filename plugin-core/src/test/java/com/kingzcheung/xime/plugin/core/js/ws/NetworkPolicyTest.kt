package com.kingzcheung.xime.plugin.core.js.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {

    private val trusted = setOf("dashscope.aliyuncs.com")

    @Test
    fun `extractHost parses various urls`() {
        assertEquals("dashscope.aliyuncs.com", NetworkPolicy.extractHost("wss://dashscope.aliyuncs.com/api-ws/v1/inference/"))
        assertEquals("asr.example.com", NetworkPolicy.extractHost("wss://asr.example.com:443/ws"))
        assertEquals("openai.com", NetworkPolicy.extractHost("wss://openai.com?query=1"))
        assertEquals("host", NetworkPolicy.extractHost("ws://host"))
        assertNull(NetworkPolicy.extractHost(""))
        assertNull(NetworkPolicy.extractHost("http://"))
    }

    @Test
    fun `extractHttpHost extracts domain only from http urls`() {
        assertEquals("dav.example.com", NetworkPolicy.extractHttpHost("https://dav.example.com:8080/dav/"))
        assertEquals("api.openai.com", NetworkPolicy.extractHttpHost("https://api.openai.com/v1/chat/completions"))
        assertEquals("192.168.1.50", NetworkPolicy.extractHttpHost("http://192.168.1.50:8080/dav/"))
        assertNull("非 http 协议返回 null", NetworkPolicy.extractHttpHost("wss://dav.example.com/ws"))
        assertNull("空白返回 null", NetworkPolicy.extractHttpHost("  "))
        assertNull("无协议返回 null", NetworkPolicy.extractHttpHost("dav.example.com/path"))
    }

    @Test
    fun `trusted host passes silently`() {
        assertNull(
            NetworkPolicy.check(
                "wss://dashscope.aliyuncs.com/ws",
                trusted, declaredHosts = emptyList(), authorizedHosts = emptySet()
            )
        )
    }

    @Test
    fun `declared and authorized host passes`() {
        assertNull(
            NetworkPolicy.check(
                "wss://asr.example.com/ws",
                trusted,
                declaredHosts = listOf("asr.example.com"),
                authorizedHosts = setOf("asr.example.com")
            )
        )
    }

    @Test
    fun `undeclared host rejected`() {
        val reason = NetworkPolicy.check(
            "wss://evil.example.com/ws",
            trusted, declaredHosts = emptyList(), authorizedHosts = emptySet()
        )
        assertTrue("未声明域名应拒绝", reason != null)
    }

    @Test
    fun `declared but unauthorized host rejected`() {
        val reason = NetworkPolicy.check(
            "wss://asr.example.com/ws",
            trusted,
            declaredHosts = listOf("asr.example.com"),
            authorizedHosts = emptySet()
        )
        assertTrue("已声明但未授权应拒绝", reason != null)
        assertTrue("拒绝原因应含授权提示", reason!!.contains("授权"))
    }

    @Test
    fun `configured custom host authorized passes`() {
        assertNull(
            NetworkPolicy.check(
                "https://my-llm.example.com/v1/chat/completions",
                trusted,
                declaredHosts = emptyList(),
                authorizedHosts = setOf("my-llm.example.com"),
                customHosts = setOf("my-llm.example.com")
            )
        )
    }

    @Test
    fun `manually authorized but undeclared host passes`() {
        // 回归：插件只声明 allowCustomHosts（无 hosts 列表）、自定义服务器域名来自
        // 用户手动授权——此时即使未声明/未配置也必须放行（真机实证曾误拒 api.openai.com）
        assertNull(
            NetworkPolicy.check(
                "https://api.openai.com/v1/chat/completions",
                trusted,
                declaredHosts = emptyList(),
                authorizedHosts = setOf("api.openai.com"),
                customHosts = emptySet()
            )
        )
    }

    @Test
    fun `configured custom host not authorized rejected`() {
        val reason = NetworkPolicy.check(
            "https://my-llm.example.com/v1/chat/completions",
            trusted,
            declaredHosts = emptyList(),
            authorizedHosts = emptySet(),
            customHosts = setOf("my-llm.example.com")
        )
        assertTrue("已配置但未授权应拒绝", reason != null)
        assertTrue("拒绝原因应含授权提示", reason!!.contains("授权"))
    }

    @Test
    fun `neither declared nor configured host rejected`() {
        val reason = NetworkPolicy.check(
            "https://evil.example.com/v1",
            trusted,
            declaredHosts = emptyList(),
            authorizedHosts = emptySet(),
            customHosts = setOf("my-llm.example.com")
        )
        assertTrue("未声明未配置应拒绝", reason != null)
    }

    // ── 安全回归：URL userinfo 绕过（旧实现 hand-rolled 解析的真实漏洞） ──

    @Test
    fun `userinfo in url cannot smuggle a trusted host`() {
        // https://api.openai.com:8443@evil.com/steal
        // 旧实现 substringBefore(':') 会得到 api.openai.com → 命中白名单放行，
        // 但真实 host 是 evil.com（api.openai.com:8443 是 userinfo）。
        val reason = NetworkPolicy.check(
            "https://api.openai.com:8443@evil.com/steal",
            trustedHosts = setOf("api.openai.com"),
            declaredHosts = listOf("api.openai.com"),
            authorizedHosts = setOf("api.openai.com")
        )
        assertTrue("带 userinfo 的 URL 必须拒绝，不能放行到 evil.com", reason != null)
        assertNull(
            "解析器本身必须拒绝 userinfo 形式的 URL",
            NetworkPolicy.extractHost("https://api.openai.com:8443@evil.com/steal")
        )
    }

    @Test
    fun `userinfo with wss is also rejected`() {
        assertNull(
            NetworkPolicy.extractHost("wss://trusted.example.com@evil.example.com/ws")
        )
    }

    @Test
    fun `non http ws scheme rejected`() {
        assertNull("file 协议应拒绝", NetworkPolicy.extractHost("file:///etc/passwd"))
        assertNull("gopher 协议应拒绝", NetworkPolicy.extractHost("gopher://evil.com/"))
    }

    @Test
    fun `host is lowercased and trailing dot normalized`() {
        assertEquals("api.openai.com", NetworkPolicy.extractHost("https://API.OpenAI.com/v1"))
        assertEquals("api.openai.com", NetworkPolicy.extractHost("https://api.openai.com./v1"))
    }

    @Test
    fun `ws and wss schemes supported`() {
        assertEquals("a.example.com", NetworkPolicy.extractHost("ws://a.example.com/path"))
        assertEquals("b.example.com", NetworkPolicy.extractHost("wss://b.example.com:443/path"))
    }
}