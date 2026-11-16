package com.kingzcheung.xime.ai.data

/**
 * AppSettings 的便捷扩展（移植自 TypeFree 的分散判断，收敛到一处便于复用）。
 */
object AppSettingsExt {

    /**
     * 是否已配置至少一个可用的服务商。
     *
     * 判据：STT 或 LLM 两个槽位中任一存在非空 baseUrl + apiKey。
     * 不校验 Key 是否真的有效（那需要联网，属于「测速」功能的事），
     * 只判断"用户是否填过"，用于决定是引导去设置页还是直接开面板。
     */
    fun AppSettings.hasAnyProviderConfigured(): Boolean {
        val stt = sttConfig
        val llm = llmConfig
        val sttReady = stt.baseUrl.isNotBlank() && stt.apiKey.isNotBlank()
        val llmReady = llm.baseUrl.isNotBlank() && llm.apiKey.isNotBlank()
        val multimodalReady = multimodalConfig.baseUrl.isNotBlank() &&
            multimodalConfig.apiKey.isNotBlank()
        return sttReady || llmReady || multimodalReady
    }
}
