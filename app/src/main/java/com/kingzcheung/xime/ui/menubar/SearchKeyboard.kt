package com.kingzcheung.xime.ui.menubar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 剪贴板专用极简自绘键盘。
 *
 * 剪贴板界面运行在输入法自身窗口内，系统不会为它弹出软键盘，
 * 因此点击搜索框后没有可用键盘。这里接管下方列表区域显示自绘键盘：
 * 打字实时回填搜索词，按「搜索」收起键盘并展示过滤结果。
 */
@Composable
fun SearchKeyboard(
    onInput: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    keyBgColor: Color,
    textColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    var shift by remember { mutableStateOf(false) }
    var numeric by remember { mutableStateOf(false) }

    val rows: List<List<String>> = if (numeric) {
        listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("-", "/", ":", ";", "(", ")", "¥", "&", "@", "\""),
            listOf(".", ",", "?", "!", "'", "[", "]", "{", "}")
        )
    } else {
        listOf(
            "qwertyuiop".map { it.toString() },
            "asdfghjkl".map { it.toString() },
            "zxcvbnm".map { it.toString() }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                row.forEach { ch ->
                    val label = if (!numeric && shift) ch.uppercase() else ch
                    KeyCap(label, keyBgColor, textColor, Modifier.weight(1f)) {
                        onInput(label)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyCap(if (numeric) "ABC" else "123", keyBgColor, textColor, Modifier.width(54.dp)) {
                numeric = !numeric
            }
            if (!numeric) {
                KeyCap("⇧", if (shift) accentColor.copy(alpha = 0.35f) else keyBgColor, textColor, Modifier.width(46.dp)) {
                    shift = !shift
                }
            }
            KeyCap("空格", keyBgColor, textColor, Modifier.weight(1f)) { onInput(" ") }
            KeyCap("⌫", keyBgColor, textColor, Modifier.width(50.dp)) { onBackspace() }
            KeyCap("清空", keyBgColor, textColor, Modifier.width(50.dp)) { onClear() }
            KeyCap("搜索", accentColor, Color.White, Modifier.width(62.dp)) { onSearch() }
        }
    }
}

@Composable
private fun KeyCap(
    label: String,
    bg: Color,
    fg: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = fg,
            fontSize = if (label.length > 2) 12.sp else 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}
