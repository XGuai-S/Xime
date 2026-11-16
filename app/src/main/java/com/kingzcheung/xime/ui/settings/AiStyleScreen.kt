package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ai.XimeAiApp
import com.kingzcheung.xime.ai.data.AppSettings
import com.kingzcheung.xime.ai.data.DefaultPresets
import com.kingzcheung.xime.ai.data.ToneStyle

/**
 * 文风页：选择「自动匹配」或某个固定文风。
 * 文风实际生效逻辑在 AppRepository.resolveActiveStyle（应用感知 + 规则 + 手动）。
 * 这里只负责把 selectedManualStyleId / appAwareToneEnabled 写回设置。
 */
@Composable
fun AiStyleScreen(onBack: () -> Unit) {
    val repo = remember { runCatching { XimeAiApp.repository }.getOrNull() }
    val settings by (repo?.settings?.collectAsState() ?: remember {
        androidx.compose.runtime.mutableStateOf<AppSettings?>(null)
    })

    SettingsScreenScaffold(title = "文风", onBack = onBack) { padding ->
        val st = settings
        if (repo == null || st == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("AI 模块未初始化", color = MaterialTheme.colorScheme.outline)
            }
            return@SettingsScreenScaffold
        }

        val styles = st.styles.ifEmpty { DefaultPresets.defaultStyles() }
        var ruleSearchQuery by remember { mutableStateOf("") }
        var showStyleEditor by remember { mutableStateOf(false) }
        var editingStyleId by remember { mutableStateOf<String?>(null) }
        var styleName by remember { mutableStateOf("") }
        var styleEmoji by remember { mutableStateOf("") }
        var styleSummary by remember { mutableStateOf("") }
        var stylePrompt by remember { mutableStateOf("") }
        var showAttitudeEditor by remember { mutableStateOf(false) }
        var editingAttitudeId by remember { mutableStateOf<String?>(null) }
        var attitudeLabel by remember { mutableStateOf("") }
        var attitudeInstruction by remember { mutableStateOf("") }
        var showRestoreStyles by remember { mutableStateOf(false) }
        var showRestoreRules by remember { mutableStateOf(false) }
        var showAddRule by remember { mutableStateOf(false) }
        var rulePkg by remember { mutableStateOf("") }
        var ruleName by remember { mutableStateOf("") }
        var ruleStyleId by remember { mutableStateOf("smart_default") }
        var showAdd by remember { mutableStateOf(false) }
        var newName by remember { mutableStateOf("") }
        var newSummary by remember { mutableStateOf("") }
        var newPrompt by remember { mutableStateOf("") }
        val autoSelected = st.selectedManualStyleId == "auto"

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "语音上屏前会按所选文风润色。「自动」会根据目标 App（聊天/邮件/文档/代码）智能切换，也可固定一种。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // ---- 应用感知总开关（对齐 TypeFree：置于页面顶部）----
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("应用感知总开关", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                            androidx.compose.material3.Switch(
                                checked = st.appAwareToneEnabled,
                                onCheckedChange = { on ->
                                    repo.updateSettings { it.copy(appAwareToneEnabled = on) }
                                }
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                Text("语气风格库（${styles.size}）", style = MaterialTheme.typography.titleSmall)
            }

            item {
                StyleRow(
                    emoji = "🤖",
                    name = "自动匹配（按 App）",
                    summary = "根据当前应用与输入场景自动选择文风",
                    selected = autoSelected,
                    onClick = { repo.updateSettings { it.copy(selectedManualStyleId = "auto") } }
                )
            }

            items(styles, key = { it.id }) { style ->
                StyleRow(
                    emoji = style.emoji,
                    name = style.name,
                    summary = style.summary,
                    selected = !autoSelected && st.selectedManualStyleId == style.id,
                    onClick = { repo.updateSettings { it.copy(selectedManualStyleId = style.id) } },
                    onEdit = {
                        editingStyleId = style.id
                        styleName = style.name
                        styleEmoji = style.emoji
                        styleSummary = style.summary
                        stylePrompt = style.promptInstruction
                        showStyleEditor = true
                    }
                )
            }

            // ---- AI 帮回快捷态度芯片（对齐 TypeFree）----
            item(key = "reply_attitude_chips") {
                Spacer(Modifier.height(12.dp))
                Text(
                    "💬 AI 帮回快捷态度芯片（${st.replyAttitudePresets.size}）",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "键盘「帮我写 / AI 帮回」面板顶部的快捷态度，点选后可继续说话补充或直接生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    st.replyAttitudePresets.forEach { preset ->
                        androidx.compose.material3.AssistChip(
                            onClick = {
                                editingAttitudeId = preset.id
                                attitudeLabel = preset.label
                                attitudeInstruction = preset.instruction
                                showAttitudeEditor = true
                            },
                            label = { Text(preset.label) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = {
                        editingAttitudeId = null
                        attitudeLabel = ""
                        attitudeInstruction = ""
                        showAttitudeEditor = true
                    }) { Text("新增态度芯片") }
                    TextButton(onClick = {
                        repo.updateSettings {
                            it.copy(
                                replyAttitudePresets =
                                    com.kingzcheung.xime.ai.data.DefaultPresets.defaultReplyAttitudes()
                            )
                        }
                    }) { Text("恢复默认") }
                }
            }

            // ---- 按 App 的文风绑定规则（对齐 TypeFree StylesAppAwareScreen 核心能力）----
            item {
                Spacer(Modifier.height(12.dp))
                Text("应用自动匹配规则（${st.appRules.size}）", style = MaterialTheme.typography.titleSmall)
                Text(
                    "为不同应用指定文风，例如微信用闲聊、邮件用正式。总开关关闭时全部按「自动」处理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.OutlinedButton(
                    onClick = { showAddRule = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("添加应用规则") }
                TextButton(
                    onClick = { showRestoreRules = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("恢复默认应用规则") }
                OutlinedTextField(
                    value = ruleSearchQuery,
                    onValueChange = { ruleSearchQuery = it },
                    label = { Text("搜索应用名称或包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }

            items(
                st.appRules.filter { r ->
                    ruleSearchQuery.isBlank() ||
                        r.appName.contains(ruleSearchQuery, true) ||
                        r.packageName.contains(ruleSearchQuery, true)
                },
                key = { "rule_" + it.id }
            ) { rule ->
                val boundStyle = styles.firstOrNull { it.id == rule.styleId }
                    ?: styles.firstOrNull { it.id == "smart_default" } ?: styles.first()
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (rule.enabled) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    rule.appName.ifBlank { rule.packageName },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    rule.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            androidx.compose.material3.Switch(
                                checked = rule.enabled,
                                onCheckedChange = { on ->
                                    repo.updateSettings { cur ->
                                        cur.copy(appRules = cur.appRules.map {
                                            if (it.id == rule.id) it.copy(enabled = on) else it
                                        })
                                    }
                                }
                            )
                        }
                        // 文风选择（点击轮换，避免再嵌一层下拉）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "文风：${boundStyle.emoji} ${boundStyle.name}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            TextButton(onClick = {
                                val ids = styles.map { it.id }
                                val idx = ids.indexOf(rule.styleId).let { if (it < 0) 0 else it }
                                val next = ids[(idx + 1) % ids.size]
                                repo.updateSettings { cur ->
                                    cur.copy(appRules = cur.appRules.map {
                                        if (it.id == rule.id) it.copy(styleId = next) else it
                                    })
                                }
                            }) { Text("换文风") }
                            TextButton(onClick = {
                                repo.updateSettings { cur ->
                                    cur.copy(appRules = cur.appRules.filterNot { it.id == rule.id })
                                }
                            }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        editingStyleId = null
                        styleName = ""; styleEmoji = "✍️"; styleSummary = ""; stylePrompt = ""
                        showStyleEditor = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("新增自定义文风") }
                TextButton(
                    onClick = { showRestoreStyles = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("恢复默认内置风格") }

                if (showAdd) {
                    AlertDialog(
                        onDismissRequest = { showAdd = false },
                        title = { Text("新增文风") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    label = { Text("名称（必填）") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = newSummary,
                                    onValueChange = { newSummary = it },
                                    label = { Text("说明（可选）") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = newPrompt,
                                    onValueChange = { newPrompt = it },
                                    label = { Text("风格指令（给模型的提示词）") },
                                    minLines = 3,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = newName.isNotBlank(),
                                onClick = {
                                    val style = com.kingzcheung.xime.ai.data.ToneStyle(
                                        name = newName.trim(),
                                        emoji = "✍️",
                                        summary = newSummary.trim(),
                                        promptInstruction = newPrompt.trim(),
                                        isBuiltIn = false
                                    )
                                    repo.updateSettings { it.copy(styles = it.styles + style) }
                                    showAdd = false
                                    newName = ""; newSummary = ""; newPrompt = ""
                                }
                            ) { Text("保存") }
                        },
                        dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } }
                    )
                }
            }

            // 自定义文风可删除
            if (styles.any { !it.isBuiltIn }) {
                item {
                    Text(
                        "删除自定义文风",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(styles.filter { !it.isBuiltIn }, key = { "del_" + it.id }) { style ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${style.emoji} ${style.name}", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            repo.updateSettings { cur ->
                                cur.copy(
                                    styles = cur.styles.filterNot { it.id == style.id },
                                    selectedManualStyleId = if (cur.selectedManualStyleId == style.id) "auto"
                                        else cur.selectedManualStyleId
                                )
                            }
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
        // 恢复默认内置风格
    if (showRestoreStyles) {
        AlertDialog(
            onDismissRequest = { showRestoreStyles = false },
            title = { Text("恢复默认内置风格") },
            text = { Text("将内置文风的名称、说明与提示词恢复为出厂默认；你自建的自定义文风会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        val defaults = com.kingzcheung.xime.ai.data.DefaultPresets.defaultStyles()
                        val defaultIds = defaults.map { it.id }.toSet()
                        repo?.updateSettings { cur ->
                            val customStyles = cur.styles.filter { !it.isBuiltIn && it.id !in defaultIds }
                            cur.copy(styles = defaults + customStyles)
                        }
                    }
                    showRestoreStyles = false
                }) { Text("立即恢复") }
            },
            dismissButton = { TextButton(onClick = { showRestoreStyles = false }) { Text("取消") } }
        )
    }

    // 恢复默认应用规则
    if (showRestoreRules) {
        AlertDialog(
            onDismissRequest = { showRestoreRules = false },
            title = { Text("恢复默认应用规则") },
            text = { Text("补全并恢复内置宿主应用绑定规则；你自己添加的其他包名规则会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        val defaults = com.kingzcheung.xime.ai.data.DefaultPresets.defaultAppRules()
                        val defaultPkgs = defaults.map { it.packageName.lowercase() }.toSet()
                        repo?.updateSettings { cur ->
                            val customRules = cur.appRules.filter {
                                it.packageName.lowercase() !in defaultPkgs
                            }
                            cur.copy(appRules = customRules + defaults)
                        }
                    }
                    showRestoreRules = false
                }) { Text("确认恢复") }
            },
            dismissButton = { TextButton(onClick = { showRestoreRules = false }) { Text("取消") } }
        )
    }

    // 文风编辑器（内置与自定义均可改名称/说明/提示词）
    if (showStyleEditor) {
        AlertDialog(
            onDismissRequest = { showStyleEditor = false },
            title = { Text(if (editingStyleId == null) "新增文风" else "编辑文风") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = styleEmoji,
                        onValueChange = { styleEmoji = it },
                        label = { Text("图标（一个 emoji）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = styleName,
                        onValueChange = { styleName = it },
                        label = { Text("名称（必填）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = styleSummary,
                        onValueChange = { styleSummary = it },
                        label = { Text("说明") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = stylePrompt,
                        onValueChange = { stylePrompt = it },
                        label = { Text("风格指令（给模型的提示词）") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = styleName.isNotBlank(),
                    onClick = {
                        runCatching {
                            val id = editingStyleId
                            repo?.updateSettings { cur ->
                                if (id == null) {
                                    cur.copy(styles = cur.styles + com.kingzcheung.xime.ai.data.ToneStyle(
                                        name = styleName.trim(),
                                        emoji = styleEmoji.trim().ifBlank { "✍️" },
                                        summary = styleSummary.trim(),
                                        promptInstruction = stylePrompt.trim(),
                                        isBuiltIn = false
                                    ))
                                } else {
                                    cur.copy(styles = cur.styles.map {
                                        if (it.id == id) it.copy(
                                            name = styleName.trim(),
                                            emoji = styleEmoji.trim().ifBlank { it.emoji },
                                            summary = styleSummary.trim(),
                                            promptInstruction = stylePrompt.trim()
                                        ) else it
                                    })
                                }
                            }
                        }
                        showStyleEditor = false
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (editingStyleId != null) {
                        TextButton(onClick = {
                            val id = editingStyleId
                            repo?.updateSettings { cur ->
                                cur.copy(
                                    styles = cur.styles.filterNot { it.id == id },
                                    selectedManualStyleId = if (cur.selectedManualStyleId == id) "auto"
                                        else cur.selectedManualStyleId
                                )
                            }
                            showStyleEditor = false
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { showStyleEditor = false }) { Text("取消") }
                }
            }
        )
    }

    // 态度芯片编辑器
    if (showAttitudeEditor) {
        AlertDialog(
            onDismissRequest = { showAttitudeEditor = false },
            title = { Text(if (editingAttitudeId == null) "新增态度芯片" else "编辑态度芯片") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = attitudeLabel,
                        onValueChange = { attitudeLabel = it },
                        label = { Text("标签（如 礼貌拒绝）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = attitudeInstruction,
                        onValueChange = { attitudeInstruction = it },
                        label = { Text("态度指令（给模型的提示词）") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = attitudeLabel.isNotBlank(),
                    onClick = {
                        runCatching {
                            val id = editingAttitudeId
                            repo?.updateSettings { cur ->
                                if (id == null) {
                                    cur.copy(
                                        replyAttitudePresets = cur.replyAttitudePresets +
                                            com.kingzcheung.xime.ai.data.ReplyAttitudePreset(
                                                label = attitudeLabel.trim(),
                                                instruction = attitudeInstruction.trim()
                                            )
                                    )
                                } else {
                                    cur.copy(
                                        replyAttitudePresets = cur.replyAttitudePresets.map {
                                            if (it.id == id) it.copy(
                                                label = attitudeLabel.trim(),
                                                instruction = attitudeInstruction.trim()
                                            ) else it
                                        }
                                    )
                                }
                            }
                        }
                        showAttitudeEditor = false
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (editingAttitudeId != null) {
                        TextButton(onClick = {
                            val id = editingAttitudeId
                            repo?.updateSettings { cur ->
                                cur.copy(
                                    replyAttitudePresets =
                                        cur.replyAttitudePresets.filterNot { it.id == id }
                                )
                            }
                            showAttitudeEditor = false
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { showAttitudeEditor = false }) { Text("取消") }
                }
            }
        )
    }

    // 新增应用规则对话框
        if (showAddRule) {
            // 需在 LazyColumn 外可见；用 repo 读最新
            val curStyles = settings?.styles.orEmpty()
            AlertDialog(
                onDismissRequest = { showAddRule = false },
                title = { Text("添加应用规则") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = rulePkg,
                            onValueChange = { rulePkg = it },
                            label = { Text("包名（必填，如 com.tencent.mm）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = ruleName,
                            onValueChange = { ruleName = it },
                            label = { Text("应用名（如 微信）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "文风：${curStyles.firstOrNull { it.id == ruleStyleId }?.let { it.emoji + it.name } ?: "通用"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = {
                            val ids = curStyles.map { it.id }
                            if (ids.isNotEmpty()) {
                                val idx = ids.indexOf(ruleStyleId).let { if (it < 0) 0 else it }
                                ruleStyleId = ids[(idx + 1) % ids.size]
                            }
                        }) { Text("换文风") }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = rulePkg.isNotBlank(),
                        onClick = {
                            val rule = com.kingzcheung.xime.ai.data.AppToneRule(
                                packageName = rulePkg.trim(),
                                appName = ruleName.trim().ifBlank { rulePkg.trim() },
                                styleId = ruleStyleId,
                                enabled = true
                            )
                            repo.updateSettings { it.copy(appRules = it.appRules + rule) }
                            showAddRule = false
                            rulePkg = ""; ruleName = ""
                        }
                    ) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { showAddRule = false }) { Text("取消") } }
            )
        }
    }


}

@Composable
private fun StyleRow(
    emoji: String,
    name: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.padding(horizontal = 4.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onEdit != null) {
                TextButton(onClick = onEdit) { Text("编辑") }
            }
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}
