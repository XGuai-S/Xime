package com.kingzcheung.xime.keyboard

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.twotone.Assignment
import androidx.compose.material.icons.automirrored.twotone.LastPage
import androidx.compose.material.icons.twotone.ContentCopy
import androidx.compose.material.icons.twotone.ContentPaste
import androidx.compose.material.icons.twotone.EmojiEmotions
import androidx.compose.material.icons.twotone.FirstPage
import androidx.compose.material.icons.twotone.KeyboardAlt
import androidx.compose.material.icons.twotone.LastPage
import androidx.compose.material.icons.twotone.Paid
import androidx.compose.material.icons.twotone.Quickreply
import androidx.compose.material.icons.twotone.SelectAll
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.twotone.Edit
import androidx.compose.material.icons.twotone.AutoAwesome
import androidx.compose.material.icons.twotone.Mic
import androidx.compose.ui.graphics.vector.ImageVector

enum class ToolbarButton(
    val id: String,
    val label: String,
    val icon: ImageVector
) {
    EMOJI("emoji", "表情", Icons.TwoTone.EmojiEmotions),
    CLIPBOARD("clipboard", "剪贴板", Icons.AutoMirrored.TwoTone.Assignment),
    SCHEMA("schema", "方案选择", Icons.TwoTone.KeyboardAlt),
    QUICK_PHRASE("quick_phrase", "快捷发送", Icons.TwoTone.Quickreply),
    SYMBOL("symbol", "符号", Icons.TwoTone.Paid),
    FLOAT("float", "悬浮", Icons.Default.PictureInPicture),
    SELECT_ALL("select_all", "全选", Icons.TwoTone.SelectAll),
    COPY("copy", "复制", Icons.TwoTone.ContentCopy),
    PASTE("paste", "粘贴", Icons.TwoTone.ContentPaste),
    HOME("home", "段首", Icons.TwoTone.FirstPage),
    END("end", "段尾", Icons.AutoMirrored.TwoTone.LastPage),
    HANDWRITING_LOOKUP("handwriting_lookup", "手写找字", Icons.Outlined.Gesture),
    EDIT("edit", "编辑", Icons.TwoTone.Edit),
    VOICE("voice", "语音", Icons.TwoTone.Mic),
    /**
     * AI 工作台入口（移植自 TypeFree 的五大 AI 意图）。
     * 点开后面板顶部是五个意图标签：口述净写 / 帮我写 / 语音编辑 / 实时快译 / Jev 军师，
     * 并含「问 AI」卡片。需要先在设置里配置至少一个服务商 Key。
     */
    AI("ai", "AI 工作台", Icons.TwoTone.AutoAwesome);

    companion object {
        val DEFAULT_VISIBLE = emptySet<ToolbarButton>()

        fun fromId(id: String): ToolbarButton? =
            entries.find { it.id == id }
    }
}

/**
 * 内置工具栏按钮当前是否可用。
 *
 * 不可用的按钮既要从候选栏隐藏（KeyboardView），也要从定制列表里去掉
 * （ToolbarCustomizeView）——两处必须同一口径，否则会出现「定制里能勾选、
 * 键盘上却不显示」的幽灵按钮。
 *
 * [hasHandwritingModel] 按需取值：只有「手写找字」需要探测本地模型。
 */
fun isBuiltinToolbarButtonAvailable(
    button: ToolbarButton,
    isSttEnabled: Boolean,
    hasHandwritingModel: () -> Boolean,
): Boolean = when (button) {
    ToolbarButton.HANDWRITING_LOOKUP -> hasHandwritingModel()
    ToolbarButton.VOICE -> isSttEnabled
    else -> true
}

data class ToolbarAction(
    val item: ToolbarButtonItem,
    val onClick: () -> Unit
)
