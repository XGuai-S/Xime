package com.kingzcheung.xime.ai.data

import android.content.Context
import android.content.pm.PackageManager
import android.text.InputType
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.roundToInt

class AppRepository(
    private val appContext: Context?,
    internal val publicStore: KeyValueStore,
    internal val secretVault: SecretVault
) {

    constructor(appContext: Context) : this(
        appContext = appContext,
        publicStore = SharedPreferencesKeyValueStore(
            appContext.getSharedPreferences(PUBLIC_PREFS_NAME, Context.MODE_PRIVATE)
        ),
        secretVault = SecretVault.fromContext(appContext)
    )

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * 清理 AI 模块历史版本的剪贴板缓存。
     *
     * 早期版本在 AI 模块里另存了一份剪贴板明文（`clipboard_v1`），**与主剪贴板的
     * 敏感识别完全无关**——密码/卡号会以明文形式躺在 SharedPreferences 里。
     * 3.4.46 起这套 API 已无调用者，属于死代码；这里把历史数据一并抹掉。
     *
     * 用 `remove` 而不是写空串：留一个空 JSON 数组同样占着键，且将来误读会得到空列表。
     */
    init {
        runCatching { publicStore.putString(KEY_CLIPBOARD, null) }
    }

    private val _settings = MutableStateFlow(loadSettingsInternal())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _history = MutableStateFlow(
        pruneHistoryByRetention(loadHistoryInternal(), _settings.value.historyRetention)
    )
    val history: StateFlow<List<HistoryRecord>> = _history.asStateFlow()



    private val _isImeWindowVisible = MutableStateFlow(false)
    val isImeWindowVisible: StateFlow<Boolean> = _isImeWindowVisible.asStateFlow()

    fun setImeWindowVisible(visible: Boolean) {
        _isImeWindowVisible.value = visible
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun loadSettingsInternal(): AppSettings {
        val raw = publicStore.getString(KEY_SETTINGS, null)
        if (raw == null) {
            publicStore.putBoolean(KEY_MIGRATED_V340_COMPACT_4ROW_AND_LIFT, true)
            return secretVault.hydrateSettingsWithSecrets(AppSettings())
        }
        return try {
            val parsed = gson.fromJson(raw, AppSettings::class.java) ?: AppSettings()
            val safeTargets = (parsed.translationTargets ?: emptyList())
                .filter { it.isNotBlank() }
                .ifEmpty { DefaultPresets.defaultTranslationTargets() }
            val safeActiveTarget = if (parsed.activeTranslateTarget.isNullOrBlank()) {
                safeTargets.first()
            } else {
                parsed.activeTranslateTarget
            }
            val safeDict = (parsed.dictionary ?: DefaultPresets.defaultDictionary()).map { item ->
                item.copy(
                    id = item.id.ifBlank { UUID.randomUUID().toString() },
                    wrongVariants = item.wrongVariants ?: "",
                    note = item.note ?: "",
                    source = item.source ?: DictionarySource.MANUAL,
                    createdAt = if (item.createdAt <= 0L) System.currentTimeMillis() else item.createdAt
                )
            }
            val resolvedFlypyEnabled = if (raw.contains("\"flypyEnabled\"")) {
                parsed.flypyEnabled
            } else {
                true
            }
            val resolvedCommaPeriodQuickSelect = if (raw.contains("\"flypyCommaPeriodQuickSelect\"")) {
                parsed.flypyCommaPeriodQuickSelect
            } else {
                true
            }
            val resolvedRememberLastKeyboard = if (raw.contains("\"rememberLastKeyboardMode\"")) {
                parsed.rememberLastKeyboardMode
            } else {
                true
            }
            val resolvedLastKeyboardTab = (parsed.lastKeyboardTab ?: "").trim().ifEmpty { "VOICE" }
            val resolvedLastQwertyIsChinese = if (raw.contains("\"lastQwertyIsChinese\"")) {
                parsed.lastQwertyIsChinese
            } else {
                resolvedFlypyEnabled
            }
            val migratedV340CompactLift = publicStore.getBoolean(KEY_MIGRATED_V340_COMPACT_4ROW_AND_LIFT, false)
            val rawQwertyShowNumberRow = if (raw.contains("\"qwertyShowNumberRow\"")) {
                parsed.qwertyShowNumberRow
            } else {
                false
            }
            val resolvedQwertyShowNumberRow = if (!migratedV340CompactLift) {
                false
            } else {
                rawQwertyShowNumberRow
            }
            val resolvedQwertyKeyPopupPreview = if (raw.contains("\"qwertyKeyPopupPreview\"")) {
                parsed.qwertyKeyPopupPreview
            } else {
                true
            }
            val resolvedClipboardEnabled = if (raw.contains("\"clipboardEnabled\"")) {
                parsed.clipboardEnabled
            } else {
                true
            }
            val resolvedClipboardQuickSuggestion = if (raw.contains("\"clipboardQuickPasteSuggestion\"")) {
                parsed.clipboardQuickPasteSuggestion
            } else {
                true
            }
            val resolvedPauseMedia = if (raw.contains("\"pauseMediaWhileRecording\"")) {
                parsed.pauseMediaWhileRecording
            } else {
                true
            }
            val resolvedWhisperMode = if (raw.contains("\"whisperModeEnabled\"")) {
                parsed.whisperModeEnabled
            } else {
                false
            }
            val resolvedEqualAskAi = if (raw.contains("\"equalEqualAskAiEnabled\"")) {
                parsed.equalEqualAskAiEnabled
            } else {
                true
            }
            val resolvedKeyboardHeightLevel = if (raw.contains("\"keyboardHeightLevel\"")) {
                parsed.keyboardHeightLevel.coerceIn(-2, 2)
            } else {
                0
            }
            val rawKeyboardHeightMode: KeyboardHeightMode =
                if (raw.contains("\"keyboardHeightMode\"")) {
                    @Suppress("USELESS_ELVIS")
                    parsed.keyboardHeightMode ?: KeyboardHeightMode.LIFT
                } else if (!migratedV340CompactLift) {
                    KeyboardHeightMode.STRETCH
                } else {
                    KeyboardHeightMode.LIFT
                }
            val rawKeyboardLiftLevel = if (raw.contains("\"keyboardLiftLevel\"")) {
                parsed.keyboardLiftLevel.coerceIn(0, 4)
            } else {
                0
            }
            val resolvedKeyboardHeightMode: KeyboardHeightMode = if (!migratedV340CompactLift) {
                KeyboardHeightMode.LIFT
            } else {
                rawKeyboardHeightMode
            }
            val resolvedKeyboardLiftLevel = if (!migratedV340CompactLift) {
                migrateStretchLevelToLiftLevel(
                    oldMode = rawKeyboardHeightMode,
                    oldHeightLevel = resolvedKeyboardHeightLevel,
                    oldLiftLevel = rawKeyboardLiftLevel
                )
            } else {
                rawKeyboardLiftLevel
            }
            val migratedV30LiveWrite = publicStore.getBoolean(KEY_MIGRATED_V30_LIVE_WRITE_DEFAULT, false)
            val resolvedLiveWriteToEditor = if (!migratedV30LiveWrite) {
                publicStore.putBoolean(KEY_MIGRATED_V30_LIVE_WRITE_DEFAULT, true)
                true
            } else if (raw.contains("\"liveWriteToEditor\"")) {
                parsed.liveWriteToEditor
            } else {
                true
            }
            // 新用户默认 Rime 全拼 (真实 librime 1.17.0)；老用户升级（存在旧 JSON 但无 inputScheme 字段）保持 FLYPY_TABLE
            val resolvedInputScheme = if (raw.contains("\"inputScheme\"")) {
                parsed.inputScheme ?: InputScheme.RIME_PINYIN
            } else {
                InputScheme.FLYPY_TABLE
            }
            val resolvedFixedCandidateOrder = if (raw.contains("\"fixedCandidateOrder\"")) {
                parsed.fixedCandidateOrder
            } else {
                null
            }
            val resolvedPredictionEnabled = if (raw.contains("\"predictionEnabled\"")) {
                parsed.predictionEnabled
            } else {
                true
            }
            val resolvedAiCompletionEnabled = if (raw.contains("\"aiCompletionEnabled\"")) {
                parsed.aiCompletionEnabled
            } else {
                false
            }
            val resolvedChatContextReplyEnabled = if (raw.contains("\"chatContextReplyEnabled\"")) {
                parsed.chatContextReplyEnabled
            } else {
                false
            }
            val resolvedChatContextDisclosureAccepted = if (raw.contains("\"chatContextDisclosureAccepted\"")) {
                parsed.chatContextDisclosureAccepted
            } else {
                false
            }
            val resolvedChatContextDisclosureDismissed = if (raw.contains("\"chatContextDisclosureDismissed\"")) {
                parsed.chatContextDisclosureDismissed
            } else {
                false
            }
            val resolvedJevCopilotEnabled = if (raw.contains("\"jevCopilotEnabled\"")) {
                parsed.jevCopilotEnabled
            } else {
                true
            }
            val resolvedJevUseDedicatedModel = if (raw.contains("\"jevUseDedicatedModel\"")) {
                parsed.jevUseDedicatedModel
            } else {
                false
            }
            val resolvedChatContextSendScreenshot = if (raw.contains("\"chatContextSendScreenshot\"")) {
                parsed.chatContextSendScreenshot
            } else {
                true
            }
            val resolvedManualIncognitoMode = if (raw.contains("\"manualIncognitoMode\"")) {
                parsed.manualIncognitoMode
            } else {
                false
            }
            val safeReplyAttitudes = (parsed.replyAttitudePresets ?: emptyList())
                .mapNotNull { item ->
                    val lbl = (item.label ?: "").trim()
                    val inst = (item.instruction ?: "").trim()
                    if (lbl.isEmpty() || inst.isEmpty()) {
                        null
                    } else {
                        ReplyAttitudePreset(
                            id = (item.id ?: "").trim().ifEmpty { UUID.randomUUID().toString() },
                            label = lbl,
                            instruction = inst
                        )
                    }
                }
                .ifEmpty { DefaultPresets.defaultReplyAttitudes() }
            val safeSnippets = (parsed.snippets ?: DefaultPresets.defaultSnippets()).map { s ->
                s.copy(
                    id = s.id.ifBlank { UUID.randomUUID().toString() },
                    title = s.title ?: "",
                    voiceTriggers = s.voiceTriggers ?: "",
                    flypyCode = SnippetMatcher.normalizeFlypyCode(s.flypyCode ?: ""),
                    content = s.content ?: ""
                )
            }
            val rawVault = parsed.providerVault ?: emptyMap()
            val safeVault = rawVault.mapValues { (k, v) ->
                ProviderCredential(
                    providerId = (v.providerId ?: "").ifBlank { k },
                    apiKey = v.apiKey ?: "",
                    sttApiKey = v.sttApiKey ?: "",
                    appId = v.appId ?: "",
                    customBaseUrl = v.customBaseUrl ?: ""
                )
            }
            val rawStt = parsed.sttConfig ?: SttConfig()
            val rawSttBaseUrl = rawStt.baseUrl ?: "https://api.siliconflow.cn/v1"
            val rawSttPresetId = rawStt.presetId ?: "siliconflow"
            val isDeepSeekStt = rawSttPresetId == "deepseek_asr" ||
                rawSttPresetId == "deepseek" ||
                com.kingzcheung.xime.ai.network.EndpointResolver.isDeepSeekAsrEndpoint(rawSttBaseUrl)
            val safeStt = rawStt.copy(
                presetId = if (isDeepSeekStt && rawSttPresetId.isBlank()) "deepseek_asr" else rawSttPresetId,
                protocol = rawStt.protocol ?: SttProtocol.OPENAI_HTTP,
                baseUrl = if (isDeepSeekStt) {
                    com.kingzcheung.xime.ai.network.EndpointResolver.resolveSttEndpoint(
                        rawSttBaseUrl.ifBlank { com.kingzcheung.xime.ai.network.EndpointResolver.DEFAULT_DEEPSEEK_ASR_URL }
                    )
                } else {
                    rawSttBaseUrl
                },
                apiKey = rawStt.apiKey ?: "",
                appId = rawStt.appId ?: "",
                resourceId = (rawStt.resourceId ?: "").ifBlank { "volc.seedasr.sauc.duration" },
                model = (rawStt.model ?: "").ifBlank {
                    if (isDeepSeekStt) "deepseek-asr" else "FunAudioLLM/SenseVoiceSmall"
                },
                language = rawStt.language ?: ""
            )
            val rawLlm = parsed.llmConfig ?: LlmConfig()
            val safeLlm = rawLlm.copy(
                presetId = rawLlm.presetId ?: "deepseek",
                baseUrl = rawLlm.baseUrl ?: "https://api.deepseek.com/v1",
                apiKey = rawLlm.apiKey ?: "",
                model = rawLlm.model ?: "deepseek-chat",
                thinkingMode = rawLlm.thinkingMode ?: ThinkingMode.DISABLED
            )
            val rawMm = parsed.multimodalConfig ?: MultimodalConfig()
            val safeMm = rawMm.copy(
                presetId = rawMm.presetId ?: "gemini",
                protocol = rawMm.protocol ?: MultimodalProtocol.GEMINI_NATIVE,
                baseUrl = rawMm.baseUrl ?: "https://generativelanguage.googleapis.com/v1beta",
                apiKey = rawMm.apiKey ?: "",
                model = rawMm.model ?: "gemini-3.5-flash-lite",
                thinkingMode = rawMm.thinkingMode ?: ThinkingMode.DISABLED
            )
            val defaultJevLlm = AppSettings().jevLlmConfig
            val rawJevLlm = parsed.jevLlmConfig ?: defaultJevLlm
            val safeJevLlm = rawJevLlm.copy(
                presetId = (rawJevLlm.presetId ?: "").ifBlank { defaultJevLlm.presetId },
                baseUrl = (rawJevLlm.baseUrl ?: "").ifBlank { defaultJevLlm.baseUrl },
                apiKey = rawJevLlm.apiKey ?: "",
                model = (rawJevLlm.model ?: "").ifBlank { defaultJevLlm.model },
                temperature = if (raw.contains("\"jevLlmConfig\"")) {
                    rawJevLlm.temperature.coerceIn(0.0f, 1.0f)
                } else {
                    defaultJevLlm.temperature
                },
                thinkingMode = rawJevLlm.thinkingMode ?: defaultJevLlm.thinkingMode
            )
            val preliminary = parsed.copy(
                sttConfig = safeStt,
                llmConfig = safeLlm,
                multimodalConfig = safeMm,
                jevUseDedicatedModel = resolvedJevUseDedicatedModel,
                jevLlmConfig = safeJevLlm,
                themePreference = parsed.themePreference ?: ThemePreference.LIGHT,
                flypyEnabled = resolvedFlypyEnabled,
                flypyCommaPeriodQuickSelect = resolvedCommaPeriodQuickSelect,
                rememberLastKeyboardMode = resolvedRememberLastKeyboard,
                lastKeyboardTab = resolvedLastKeyboardTab,
                lastQwertyIsChinese = resolvedLastQwertyIsChinese,
                qwertyShowNumberRow = resolvedQwertyShowNumberRow,
                qwertyKeyPopupPreview = resolvedQwertyKeyPopupPreview,
                clipboardEnabled = resolvedClipboardEnabled,
                clipboardQuickPasteSuggestion = resolvedClipboardQuickSuggestion,
                pauseMediaWhileRecording = resolvedPauseMedia,
                whisperModeEnabled = resolvedWhisperMode,
                equalEqualAskAiEnabled = resolvedEqualAskAi,
                keyboardHeightLevel = resolvedKeyboardHeightLevel,
                keyboardHeightMode = resolvedKeyboardHeightMode,
                keyboardLiftLevel = resolvedKeyboardLiftLevel,
                liveWriteToEditor = resolvedLiveWriteToEditor,
                inputScheme = resolvedInputScheme,
                fixedCandidateOrder = resolvedFixedCandidateOrder,
                predictionEnabled = resolvedPredictionEnabled,
                aiCompletionEnabled = resolvedAiCompletionEnabled,
                chatContextReplyEnabled = resolvedChatContextReplyEnabled,
                chatContextDisclosureAccepted = resolvedChatContextDisclosureAccepted,
                chatContextDisclosureDismissed = resolvedChatContextDisclosureDismissed,
                jevCopilotEnabled = resolvedJevCopilotEnabled,
                chatContextSendScreenshot = resolvedChatContextSendScreenshot,
                manualIncognitoMode = resolvedManualIncognitoMode,
                replyAttitudePresets = safeReplyAttitudes,
                providerVault = safeVault,
                snippets = safeSnippets,
                historyRetention = parsed.historyRetention ?: HistoryRetention.FOREVER,
                translationTargets = safeTargets,
                activeTranslateTarget = safeActiveTarget,
                bubbleYRatio = if (parsed.bubbleYRatio in 0.08f..0.90f) parsed.bubbleYRatio else 0.48f,
                styles = (parsed.styles ?: emptyList()).ifEmpty { DefaultPresets.defaultStyles() },
                appRules = parsed.appRules ?: DefaultPresets.defaultAppRules(),
                dictionary = safeDict
            )
            val hydrated = secretVault.hydrateSettingsWithSecrets(preliminary)
            if (SecretVault.containsPlaintextSecrets(preliminary) || !migratedV340CompactLift) {
                secretVault.saveSecretsFromSettings(hydrated)
                val sanitized = secretVault.stripSecretsForPublicPersistence(hydrated)
                publicStore.putString(KEY_SETTINGS, gson.toJson(sanitized))
                if (!migratedV340CompactLift) {
                    publicStore.putBoolean(KEY_MIGRATED_V340_COMPACT_4ROW_AND_LIFT, true)
                }
            }
            hydrated
        } catch (_: Exception) {
            secretVault.hydrateSettingsWithSecrets(AppSettings())
        }
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun loadHistoryInternal(): List<HistoryRecord> {
        val raw = publicStore.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<HistoryRecord>>() {}.type
            val list = gson.fromJson<List<HistoryRecord>>(raw, type) ?: emptyList()
            list.map { item ->
                val resolvedIntent = item.intentMode ?: if (item.isSpeakToEdit) {
                    VoiceIntentMode.SPEAK_TO_EDIT
                } else {
                    VoiceIntentMode.DICTATE
                }
                item.copy(
                    intentMode = resolvedIntent,
                    translateTargetLanguage = item.translateTargetLanguage ?: "",
                    audioFilePath = item.audioFilePath ?: ""
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }





    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val prevRetention = _settings.value.historyRetention
        val updated = transform(_settings.value)
        _settings.value = updated
        secretVault.saveSecretsFromSettings(updated)
        val sanitized = secretVault.stripSecretsForPublicPersistence(updated)
        publicStore.putString(KEY_SETTINGS, gson.toJson(sanitized))

        if (updated.historyRetention != prevRetention) {
            val pruned = pruneHistoryByRetention(_history.value, updated.historyRetention)
            if (pruned.size != _history.value.size) {
                _history.value = pruned
                publicStore.putString(KEY_HISTORY, gson.toJson(pruned))
            }
        }
    }

    fun addHistory(record: HistoryRecord) {
        val retention = _settings.value.historyRetention
        if (retention == HistoryRetention.NEVER) {
            return
        }
        val prunedExisting = pruneHistoryByRetention(_history.value, retention)
        val updated = (listOf(record) + prunedExisting).take(MAX_HISTORY_ITEMS)
        _history.value = updated
        publicStore.putString(KEY_HISTORY, gson.toJson(updated))
    }

    fun deleteHistoryItem(id: String) {
        val updated = _history.value.filterNot { it.id == id }
        _history.value = updated
        publicStore.putString(KEY_HISTORY, gson.toJson(updated))
    }

    fun clearHistory() {
        _history.value = emptyList()
        publicStore.remove(KEY_HISTORY)
    }

    fun deleteDictionaryEntries(ids: Set<String>) {
        if (ids.isEmpty()) return
        updateSettings { curr ->
            curr.copy(dictionary = curr.dictionary.filterNot { it.id in ids })
        }
    }

    /**
     * Auto-learns a corrected word/phrase into the user's Personal Dictionary (`AUTO_LEARNED`)
     * when the user manually edits freshly dictated text on the built-in keyboard.
     */
    fun learnCorrectionFromDiff(beforeText: String, afterText: String): DictionaryEntry? {
        if (!_settings.value.autoLearnDictionary) return null
        val pair = extractAutoLearnedReplacement(beforeText, afterText) ?: return null
        val (wrongPart, correctedTerm) = pair

        var learnedEntry: DictionaryEntry? = null
        updateSettings { curr ->
            val existingIdx = curr.dictionary.indexOfFirst {
                it.term.equals(correctedTerm, ignoreCase = true)
            }
            val updatedDict = if (existingIdx >= 0) {
                val existing = curr.dictionary[existingIdx]
                val existingVariants = existing.wrongVariants
                    .split(',', '，')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val mergedVariants = if (
                    wrongPart.isNotBlank() &&
                    !wrongPart.equals(correctedTerm, ignoreCase = true) &&
                    existingVariants.none { it.equals(wrongPart, ignoreCase = true) }
                ) {
                    (existingVariants + wrongPart).joinToString(", ")
                } else {
                    existing.wrongVariants
                }
                val merged = existing.copy(wrongVariants = mergedVariants, enabled = true)
                learnedEntry = merged
                curr.dictionary.toMutableList().apply { set(existingIdx, merged) }
            } else {
                val created = DictionaryEntry(
                    term = correctedTerm,
                    wrongVariants = if (!wrongPart.equals(correctedTerm, ignoreCase = true)) wrongPart else "",
                    note = "打字改错自动学习",
                    enabled = true,
                    source = DictionarySource.AUTO_LEARNED
                )
                learnedEntry = created
                listOf(created) + curr.dictionary
            }
            curr.copy(dictionary = updatedDict)
        }
        return learnedEntry
    }

    /**
     * Resolve human-readable app label from package name.
     */
    fun resolveAppName(packageName: String?): String {
        if (packageName.isNullOrBlank()) return "未知应用"
        _settings.value.appRules.firstOrNull { it.packageName == packageName }?.let {
            if (it.appName.isNotBlank()) return it.appName
        }
        val ctx = appContext ?: return packageName.substringAfterLast('.')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        return try {
            val pm = ctx.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.')
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        } catch (_: Exception) {
            packageName
        }
    }

    /**
     * Resolve which ToneStyle should be active right now based on user manual override
     * or App-Aware automatic context detection.
     */
    fun resolveActiveStyle(packageName: String?, inputType: Int = 0): Pair<ToneStyle, Boolean> {
        val current = _settings.value
        val styles = current.styles.ifEmpty { DefaultPresets.defaultStyles() }
        val defaultStyle = styles.firstOrNull { it.id == "smart_default" } ?: styles.first()

        if (current.selectedManualStyleId != "auto") {
            val manual = styles.firstOrNull { it.id == current.selectedManualStyleId } ?: defaultStyle
            return manual to false
        }

        if (!current.appAwareToneEnabled || packageName.isNullOrBlank()) {
            return defaultStyle to true
        }

        val matchedRule = current.appRules.firstOrNull {
            it.enabled && it.packageName.equals(packageName, ignoreCase = true)
        }
        if (matchedRule != null) {
            val style = styles.firstOrNull { it.id == matchedRule.styleId }
            if (style != null) return style to true
        }

        val pkgLower = packageName.lowercase()
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val heuristicStyleId = when {
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT ||
                pkgLower.contains("mail") || pkgLower.contains("docs") || pkgLower.contains("note") -> "formal_doc"
            pkgLower.contains("chat") || pkgLower.contains("messenger") || pkgLower.contains("weibo") -> "casual_chat"
            pkgLower.contains("work") || pkgLower.contains("lark") || pkgLower.contains("ding") || pkgLower.contains("teams") -> "work_collab"
            pkgLower.contains("code") || pkgLower.contains("term") || pkgLower.contains("git") -> "code_prompt"
            else -> "smart_default"
        }
        val heuristicStyle = styles.firstOrNull { it.id == heuristicStyleId } ?: defaultStyle
        return heuristicStyle to true
    }

    companion object {
        const val PUBLIC_PREFS_NAME = "typefree_ime_prefs"
        internal const val KEY_SETTINGS = "settings_v1"
        internal const val KEY_HISTORY = "history_v1"
        internal const val KEY_CLIPBOARD = "clipboard_v1"
        private const val KEY_MIGRATED_V30_LIVE_WRITE_DEFAULT = "migrated_v30_live_write_default"
        internal const val KEY_MIGRATED_V340_COMPACT_4ROW_AND_LIFT = "mig_v340_compact_4row_and_lift"
        private const val MAX_HISTORY_ITEMS = 200
        private val PUNCT_OR_SPACE_ONLY = Regex("^[\\s\\p{Punct}，。？！、：；“”‘’（）【】《》…—\\-]+$")

        fun migrateStretchLevelToLiftLevel(
            oldMode: KeyboardHeightMode,
            oldHeightLevel: Int,
            oldLiftLevel: Int
        ): Int {
            return if (oldMode == KeyboardHeightMode.STRETCH) {
                when {
                    oldHeightLevel <= 0 -> 0
                    oldHeightLevel == 1 -> 2
                    else -> 3
                }
            } else {
                oldLiftLevel.coerceIn(0, 4)
            }
        }

        fun migrateSettingsToV340CompactLift(
            oldSettings: AppSettings,
            alreadyMigrated: Boolean
        ): AppSettings {
            if (alreadyMigrated) return oldSettings
            val migratedLift = migrateStretchLevelToLiftLevel(
                oldMode = oldSettings.keyboardHeightMode,
                oldHeightLevel = oldSettings.keyboardHeightLevel,
                oldLiftLevel = oldSettings.keyboardLiftLevel
            )
            return oldSettings.copy(
                qwertyShowNumberRow = false,
                keyboardHeightMode = KeyboardHeightMode.LIFT,
                keyboardLiftLevel = migratedLift
            )
        }

        fun pruneHistoryByRetention(
            records: List<HistoryRecord>,
            retention: HistoryRetention,
            nowMs: Long = System.currentTimeMillis()
        ): List<HistoryRecord> {
            return when (retention) {
                HistoryRetention.NEVER -> emptyList()
                HistoryRetention.FOREVER -> records
                else -> {
                    val maxAge = retention.maxAgeMs ?: return records
                    val cutoff = nowMs - maxAge
                    records.filter { it.timestamp >= cutoff }
                }
            }
        }

        /**
         * Pure diff extractor for TypeFree Auto-Dictionary Learning:
         * Given `beforeText` (just dictated) and `afterText` (edited on keyboard),
         * extracts `(replacedWrongSpan, correctedTerm)` if a concise term/name (`2..24` chars)
         * was corrected rather than an entire sentence rewrite or pure deletion.
         */
        fun extractAutoLearnedReplacement(
            beforeText: String,
            afterText: String
        ): Pair<String, String>? {
            val before = beforeText.trim()
            val after = afterText.trim()
            if (before.isEmpty() || after.isEmpty() || before == after) return null

            var prefixLen = 0
            val minLen = minOf(before.length, after.length)
            while (prefixLen < minLen && before[prefixLen] == after[prefixLen]) {
                prefixLen++
            }

            var beforeEnd = before.length
            var afterEnd = after.length
            while (
                beforeEnd > prefixLen &&
                afterEnd > prefixLen &&
                before[beforeEnd - 1] == after[afterEnd - 1]
            ) {
                beforeEnd--
                afterEnd--
            }

            // Expand to ASCII word boundaries so changing "deep seek" -> "DeepSeek" captures full token
            while (
                prefixLen > 0 &&
                (before[prefixLen - 1].isLetterOrDigit() && before[prefixLen - 1].code < 128)
            ) {
                prefixLen--
            }
            while (
                beforeEnd < before.length &&
                afterEnd < after.length &&
                ((before[beforeEnd].isLetterOrDigit() && before[beforeEnd].code < 128) ||
                    (after[afterEnd].isLetterOrDigit() && after[afterEnd].code < 128))
            ) {
                beforeEnd = (beforeEnd + 1).coerceAtMost(before.length)
                afterEnd = (afterEnd + 1).coerceAtMost(after.length)
            }

            val wrongSpan = before.substring(prefixLen, beforeEnd).trim()
            val rightSpan = after.substring(prefixLen, afterEnd).trim()

            if (wrongSpan.isEmpty() || rightSpan.isEmpty() || wrongSpan == rightSpan) return null
            if (rightSpan.length !in 2..24 || wrongSpan.length > 28) return null
            if (rightSpan.contains('\n') || wrongSpan.contains('\n')) return null
            if (PUNCT_OR_SPACE_ONLY.matches(rightSpan) || PUNCT_OR_SPACE_ONLY.matches(wrongSpan)) return null

            return wrongSpan to rightSpan
        }

        fun computeUsageStats(
            settings: AppSettings,
            history: List<HistoryRecord>
        ): UsageStatsSummary {
            val totalDictations = history.size
            val totalChars = history.sumOf { it.polishedText.length }
            val totalDurationSec = history.sumOf { it.durationMs }.coerceAtLeast(0L) / 1000.0
            val measuredWpm = if (totalDurationSec >= 3.0 && totalChars > 0) {
                ((totalChars / 1.8) / (totalDurationSec / 60.0)).roundToInt().coerceIn(135, 280)
            } else {
                220
            }
            // Manual mobile typing ~42 WPM vs voice dictation
            val savedMin = ((totalChars / 42.0) * 0.72).roundToInt().coerceAtLeast(if (totalDictations > 0) 1 else 0)

            val dictScore = (settings.dictionary.count { it.enabled } * 8).coerceAtMost(35)
            val ruleScore = (if (settings.appAwareToneEnabled) 25 else 8)
            val apiScore = when (settings.pipelineMode) {
                PipelineMode.DUAL_STAGE -> if (settings.sttConfig.apiKey.isNotBlank()) 25 else 10
                PipelineMode.MULTIMODAL_DIRECT -> if (settings.multimodalConfig.apiKey.isNotBlank()) 25 else 10
            }
            val historyScore = (totalDictations * 3).coerceAtMost(15)
            val overallPercent = (dictScore + ruleScore + apiScore + historyScore).coerceIn(18, 100)

            val catProgress = linkedMapOf(
                "即时社交 (微信 / Telegram)" to ((0.55f + history.count { it.styleName.contains("聊天") } * 0.08f).coerceAtMost(1f)),
                "职场协作 (飞书 / 钉钉 / Slack)" to ((0.50f + history.count { it.styleName.contains("职场") } * 0.08f).coerceAtMost(1f)),
                "邮件与文档 (Gmail / Notion)" to ((0.48f + history.count { it.styleName.contains("正式") } * 0.08f).coerceAtMost(1f)),
                "AI 助手与编程 (ChatGPT / Claude)" to ((0.62f + settings.dictionary.size * 0.05f).coerceAtMost(1f))
            )

            return UsageStatsSummary(
                totalDictations = totalDictations,
                totalCharacters = totalChars,
                averageWpm = measuredWpm,
                savedMinutes = savedMin,
                overallPersonalizationPercent = overallPercent,
                categoryProgress = catProgress
            )
        }
    }
}
