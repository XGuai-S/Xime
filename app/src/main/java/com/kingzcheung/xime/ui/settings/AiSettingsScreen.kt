package com.kingzcheung.xime.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kingzcheung.xime.ai.XimeAiApp
import com.kingzcheung.xime.ai.bubble.XimeBubbleController
import com.kingzcheung.xime.ai.data.AppSettings
import com.kingzcheung.xime.ai.data.ModelCapabilitySlot
import com.kingzcheung.xime.ai.data.SttProtocol
import com.kingzcheung.xime.ai.data.UnifiedProviderCatalog
import kotlinx.coroutines.launch

/**
 * AI 服务商配置页（移植自 TypeFree 的 ApiSettingsScreen，按 Xime 需要精简）。
 *
 * 支持三类服务商：
 * 1. 官方直连（DeepSeek / 百炼 / OpenAI / 硅基流动 …）
 * 2. **中转站**：任何 OpenAI 兼容网关，填它的 baseUrl + 它签发的 Key 即可；
 *    中转站通常把 `/v1/models` 也实现了，所以「拉取模型」能直接列出可用模型。
 * 3. 局域网自建（Ollama / one-api 等），Key 可留空。
 *
 * 三个操作按钮：
 * - 拉取模型：请求 `{baseUrl}/models`，把返回的 id 列成下拉列表供选择；
 * - 测试连接：拿当前模型发一次极短请求，显示真实首字延迟（ms）；
 * - 保存配置：写回 AppRepository。
 */
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    remember {
        XimeAiApp.init(context.applicationContext as android.app.Application)
    }
    val repo = remember { runCatching { XimeAiApp.repository }.getOrNull() }
    val settings by (repo?.settings?.collectAsState() ?: remember {
        mutableStateOf<AppSettings?>(null)
    })
    val scope = rememberCoroutineScope()

    var sttBaseUrl by remember(settings) { mutableStateOf(settings?.sttConfig?.baseUrl.orEmpty()) }
    var sttApiKey by remember(settings) { mutableStateOf(settings?.sttConfig?.apiKey.orEmpty()) }
    var sttModel by remember(settings) { mutableStateOf(settings?.sttConfig?.model.orEmpty()) }
    var llmBaseUrl by remember(settings) { mutableStateOf(settings?.llmConfig?.baseUrl.orEmpty()) }
    var llmApiKey by remember(settings) { mutableStateOf(settings?.llmConfig?.apiKey.orEmpty()) }
    var llmModel by remember(settings) { mutableStateOf(settings?.llmConfig?.model.orEmpty()) }

    // 当前选中的内置基础服务商（与 sttConfig.presetId 同步）
    var providerId by remember(settings) { mutableStateOf(settings?.sttConfig?.presetId.orEmpty()) }
    var sttProtocol by remember(settings) {
        mutableStateOf(settings?.sttConfig?.protocol ?: SttProtocol.OPENAI_HTTP)
    }
    var selectedSupportsLlm by remember(settings) {
        mutableStateOf(
            UnifiedProviderCatalog.providers.firstOrNull { it.id == settings?.sttConfig?.presetId }?.supportsLlm
                ?: true
        )
    }

    SettingsScreenScaffold(title = "AI 服务商", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "支持官方直连、中转站与局域网自建。中转站填它提供的接口地址与 Key 即可，" +
                    "「拉取模型」会列出该站可用的全部模型。所有 Key 仅保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- 基础服务商（内置目录，一键带入接口地址与默认模型）----
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                val catalog = remember { UnifiedProviderCatalog.providers.filter { it.supportsStt } }
                var providerMenuOpen by remember { mutableStateOf(false) }
                val currentProvider = catalog.firstOrNull { it.id == providerId }
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("服务商", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "选择内置基础服务商可一键填入接口地址与默认模型，含「本地/开源 SenseVoice」自建项；也可在下方手动修改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box {
                        OutlinedButton(
                            onClick = { providerMenuOpen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = currentProvider?.name ?: "自定义 / 手动填写",
                                modifier = Modifier.weight(1f)
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = "选择服务商")
                        }
                        DropdownMenu(
                            expanded = providerMenuOpen,
                            onDismissRequest = { providerMenuOpen = false },
                            modifier = Modifier.heightIn(max = 420.dp)
                        ) {
                            catalog.forEach { p ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                p.summary,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        providerMenuOpen = false
                                        providerId = p.id
                                        sttProtocol = p.defaultSttProtocol
                                        selectedSupportsLlm = p.supportsLlm
                                        if (p.defaultBaseUrl.isNotBlank()) sttBaseUrl = p.defaultBaseUrl
                                        if (p.defaultSttModel.isNotBlank()) sttModel = p.defaultSttModel
                                        if (p.supportsLlm) {
                                            if (p.defaultBaseUrl.isNotBlank()) llmBaseUrl = p.defaultBaseUrl
                                            if (p.defaultLlmModel.isNotBlank()) llmModel = p.defaultLlmModel
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            ProviderCard(
                title = "语音识别（STT）",
                slot = ModelCapabilitySlot.STT,
                hint = "OpenAI 兼容的 STT 接口。例：whisper-1 / FunAudioLLM/SenseVoiceSmall",
                baseUrl = sttBaseUrl, onBaseUrl = { sttBaseUrl = it },
                apiKey = sttApiKey, onApiKey = { sttApiKey = it },
                model = sttModel, onModel = { sttModel = it },
            )

            ProviderCard(
                title = "文本润色（LLM）",
                slot = ModelCapabilitySlot.LLM,
                hint = "用于去口癖、加标点、改写、翻译与军师推理。例：deepseek-chat / qwen-plus",
                baseUrl = llmBaseUrl, onBaseUrl = { llmBaseUrl = it },
                apiKey = llmApiKey, onApiKey = { llmApiKey = it },
                model = llmModel, onModel = { llmModel = it },
            )

            // ---- 文本润色开关：关闭后口述净写走纯 STT 直出 ----
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("文本润色（LLM）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "关闭后「口述净写」只做语音转文本直接上屏，不调用 LLM 润色；" +
                                "语音编辑 / 帮我写 / 翻译等仍按各自意图处理。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = settings?.llmConfig?.enabled == true,
                        onCheckedChange = { on ->
                            repo?.updateSettings { it.copy(llmConfig = it.llmConfig.copy(enabled = on)) }
                        }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ---- 权限与系统能力 ----
            // ---- 工作台语音优先本地识别 ----
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("工作台语音用本地识别（离线）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "AI 工作台录音改用本地 Zipformer int8 离线识别（需在「语音转文本」下载本地模型）；润色仍走 LLM，未配可用 LLM 时直接上屏不润色。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = settings?.workbenchLocalSttEnabled == true,
                        onCheckedChange = { on ->
                            repo?.updateSettings { it.copy(workbenchLocalSttEnabled = on) }
                        }
                    )
                }
            }

            // ---- AI 句子补全开关（默认关闭）----
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("AI 句子补全（实验性）", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "打字停顿后按文风预测下一句，作为联想候选显示，点选即上屏。默认关闭。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = settings?.aiCompletionEnabled == true,
                        onCheckedChange = { on ->
                            repo?.updateSettings { it.copy(aiCompletionEnabled = on) }
                        }
                    )
                }
            }

            PermissionCard()

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    repo?.updateSettings { cur ->
                        cur.copy(
                            sttConfig = cur.sttConfig.copy(
                                baseUrl = sttBaseUrl.trim(),
                                apiKey = sttApiKey.trim(),
                                model = sttModel.trim(),
                                protocol = sttProtocol,
                                presetId = providerId.ifBlank { cur.sttConfig.presetId },
                            ),
                            llmConfig = cur.llmConfig.copy(
                                baseUrl = llmBaseUrl.trim(),
                                apiKey = llmApiKey.trim(),
                                model = llmModel.trim(),
                                presetId = if (providerId.isNotBlank() && selectedSupportsLlm) {
                                    providerId
                                } else {
                                    cur.llmConfig.presetId
                                },
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存配置")
            }
        }
    }
}

@Composable
private fun ProviderCard(
    title: String,
    slot: ModelCapabilitySlot,
    hint: String,
    baseUrl: String,
    onBaseUrl: (String) -> Unit,
    apiKey: String,
    onApiKey: (String) -> Unit,
    model: String,
    onModel: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 拉取到的模型列表要持久化：离开页面 / 重启后仍在，直到用户重新「拉取模型」才覆盖。
    // 按 slot 分开缓存（各卡片的接口地址可能不同）。
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("ai_models_cache", Context.MODE_PRIVATE)
    }
    val cacheKey = remember(slot) { "models_${slot.name}" }
    var models by remember(slot) { mutableStateOf(loadCachedModels(prefs, cacheKey)) }
    var menuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusIsError by remember { mutableStateOf(false) }

    fun setStatus(msg: String, isError: Boolean) {
        status = msg
        statusIsError = isError
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrl,
                label = { Text("接口地址") },
                placeholder = { Text("https://api.example.com/v1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKey,
                label = { Text("API Key") },
                placeholder = { Text("中转站的 Key 也填这里；局域网可留空") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            // ---- 模型：可手输，也可从下拉列表选 ----
            Box {
                OutlinedTextField(
                    value = model,
                    onValueChange = onModel,
                    label = { Text("模型") },
                    singleLine = true,
                    trailingIcon = {
                        if (models.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "选择模型",
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable { menuOpen = true }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.heightIn(max = 320.dp)
                ) {
                    models.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m, style = MaterialTheme.typography.bodySmall) },
                            onClick = { onModel(m); menuOpen = false }
                        )
                    }
                }
            }

            // ---- 操作行 ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        setStatus("正在拉取模型列表…", false)
                        scope.launch {
                            val engine = runCatching { XimeAiApp.aiEngine }.getOrNull()
                            if (engine == null) {
                                busy = false
                                setStatus("AI 引擎未就绪", true)
                                return@launch
                            }
                            val r = engine.fetchAvailableModels(slot, baseUrl, apiKey)
                            busy = false
                            r.onSuccess { list ->
                                models = list.map { it.id }
                                // 落盘缓存，App 重启 / 离开页面后仍保留，直到下次重新拉取
                                saveCachedModels(prefs, cacheKey, models)
                                setStatus(
                                    if (list.isEmpty()) "服务端返回空列表"
                                    else "拉取到 ${list.size} 个模型，点右侧箭头选择"
                                , list.isEmpty())
                            }.onFailure { e ->
                                setStatus(e.message ?: "拉取失败", true)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("拉取模型", style = MaterialTheme.typography.labelSmall)
                }

                OutlinedButton(
                    enabled = !busy && model.isNotBlank(),
                    onClick = {
                        busy = true
                        setStatus("测试中…", false)
                        scope.launch {
                            val engine = runCatching { XimeAiApp.aiEngine }.getOrNull()
                            if (engine == null) {
                                busy = false
                                setStatus("AI 引擎未就绪", true)
                                return@launch
                            }
                            val probe = engine.probeModelLatency(slot, baseUrl, apiKey, model)
                            busy = false
                            if (probe.isSuccess) {
                                setStatus("连接成功 · 首字 ${probe.latencyMs ?: 0}ms", false)
                            } else {
                                setStatus("失败：${probe.errorSummary?.ifBlank { "未知错误" } ?: "未知错误"}", true)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("测试连接", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                }
            }
            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 权限与系统能力卡片。
 *
 * 三项系统权限各自独立：麦克风（语音）、悬浮窗（胶囊）、无障碍（读屏帮回）。
 * 全部按需引导——点哪项跳哪项设置，不一次性弹三个对话框骚扰用户。
 */
@Composable
private fun PermissionCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 权限状态不是 Compose 可观察量：跳系统设置授权后必须主动重读。
    // 之前只用 remember(refresh) 缓存且仅在麦克风按钮上 ++，所以从系统设置
    // 返回时界面一直显示「去开启」——看起来像权限没生效。
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // 从悬浮窗 / 无障碍等系统设置页返回会让 Activity 重新 ON_RESUME
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 直接读、不 remember：这三项都是廉价的系统查询（Settings/AppOps 读取），
    // 设置页重组频率极低，实时读才能保证「用户在系统设置里授权后返回即正确」。
    // 之前用 remember(refresh) 缓存，refresh 不变时会一直显示旧值（
    // 实测悬浮窗已 allow 但仍显示"去开启"）。
    val micOk = refresh.let { com.kingzcheung.xime.ai.ui.AiPermissions.hasMicrophone(context) }
    val overlayOk = refresh.let { com.kingzcheung.xime.ai.ui.AiPermissions.canDrawOverlays(context) }
    val a11yOk = refresh.let { com.kingzcheung.xime.ai.ui.AiPermissions.isAccessibilityEnabled(context) }
    // 已授权项被点击时的页面内提示（Toast 可能被系统通知权限拦截，故内联展示更稳妥）
    var permHint by remember { mutableStateOf<String?>(null) }
    // 开关状态跟配置走，避免「开关关着但胶囊在跑」或反之
    val repo = remember { runCatching { XimeAiApp.repository }.getOrNull() }
    val persistedBubble by (repo?.settings?.collectAsState() ?: remember {
        mutableStateOf<AppSettings?>(null)
    })
    var bubbleOn by remember(persistedBubble?.bubbleEnabled) {
        mutableStateOf(persistedBubble?.bubbleEnabled == true)
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refresh++
        // 用户勾了「不再询问」后再 requestPermissions，系统不弹框、直接回调 false。
        // 此时浏览器已经无能为力：跳到系统权限页让用户手动打开，避免"点了没反应"。
        if (!granted) {
            val activity = context.findActivity()
            val canAsk = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                activity, Manifest.permission.RECORD_AUDIO
            )
            if (!canAsk) {
                XimeBubbleController.openMicrophonePermissionSettings(context)
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("系统权限", style = MaterialTheme.typography.titleSmall)
            Text(
                "AI 功能按需申请，不开启对应权限时只影响相应能力。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PermissionRow("麦克风（语音识别）", micOk) {
                if (micOk) {
                    // 已有权限：不跳转，只提示，避免用户以为没生效又去点
                    permHint = "已拥有麦克风权限，无需重复开启"
                    return@PermissionRow
                }
                val activity = context.findActivity()
                val prefs = context.getSharedPreferences("ai_perm", Context.MODE_PRIVATE)
                val everRequested = prefs.getBoolean("mic_ever_requested", false)
                val canAsk = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, Manifest.permission.RECORD_AUDIO
                )
                if (everRequested && !canAsk) {
                    // 已被永久拒绝（勾了“不再询问”）：系统不会再弹框，直接跳权限页
                    XimeBubbleController.openMicrophonePermissionSettings(context)
                } else {
                    prefs.edit().putBoolean("mic_ever_requested", true).apply()
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            PermissionRow("悬浮窗（语音胶囊）", overlayOk) {
                if (overlayOk) {
                    permHint = "已拥有悬浮窗权限，无需重复开启"
                } else {
                    XimeBubbleController.openOverlayPermissionSettings(context)
                }
            }
            PermissionRow("无障碍（读屏帮回）", a11yOk) {
                if (a11yOk) {
                    permHint = "已开启无障碍服务，无需重复开启"
                } else {
                    XimeBubbleController.openAccessibilitySettings(context)
                }
            }
            permHint?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 悬浮胶囊开关（三项权限未齐时禁用）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("悬浮语音胶囊", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (overlayOk && micOk) "在其他应用上方常驻，点一下即可语音"
                        else "需先开启悬浮窗与麦克风权限",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = bubbleOn,
                    enabled = overlayOk && micOk,
                    onCheckedChange = { on ->
                        if (on) {
                            // 启动前校验：权限不全时调 startForegroundService 会让服务
                            // 进不了前台，5 秒后被系统判超时并杀进程。
                            if (!com.kingzcheung.xime.ai.ui.AiPermissions.canDrawOverlays(context) ||
                                !com.kingzcheung.xime.ai.ui.AiPermissions.hasMicrophone(context)
                            ) {
                                android.widget.Toast.makeText(
                                    context, "请先开启悬浮窗与麦克风权限",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                refresh++
                                return@Switch
                            }
                            bubbleOn = true
                            // 服务 onStartCommand 会读 bubbleEnabled 判是否继续；必须先写盘再启动
                            repo?.updateSettings { it.copy(bubbleEnabled = true) }
                            com.kingzcheung.xime.ai.bubble.XimeBubbleService.start(context)
                        } else {
                            bubbleOn = false
                            repo?.updateSettings { it.copy(bubbleEnabled = false) }
                            com.kingzcheung.xime.ai.bubble.XimeBubbleService.stop(context)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // 整行可点：即使状态检测暂时失准（如权限已给但缓存未刷新），
        // 用户仍能点进设置页确认，不会出现"按钮点了没反应"。
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (granted) "已开启" else "去开启",
            style = MaterialTheme.typography.bodySmall,
            color = if (granted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.secondary
        )
    }
}

/** 从 Compose 的 LocalContext 向上一层找宿主 Activity（ContextWrapper 逐层解包）。 */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** 读取某 slot 已缓存（上次拉取）的模型列表；无缓存或损坏时返回空表。 */
private fun loadCachedModels(prefs: android.content.SharedPreferences, key: String): List<String> {
    val raw = prefs.getString(key, null) ?: return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(raw)
        List(arr.length()) { arr.getString(it) }
    }.getOrDefault(emptyList())
}

/** 把某 slot 的模型列表写入缓存（覆盖旧值）。 */
private fun saveCachedModels(
    prefs: android.content.SharedPreferences,
    key: String,
    models: List<String>,
) {
    val arr = org.json.JSONArray()
    models.forEach { arr.put(it) }
    prefs.edit().putString(key, arr.toString()).apply()
}
