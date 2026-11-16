package com.kingzcheung.xime.ai.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ai.ime.ImeUiAction
import com.kingzcheung.xime.ai.ime.ImeUiState
import com.kingzcheung.xime.ai.ime.LocalKeyboardMetrics
import com.kingzcheung.xime.ai.ui.components.TypeFreeIcons
import com.kingzcheung.xime.ai.ui.theme.LocalTypeFreeKeyboardPalette
import com.kingzcheung.xime.ai.ui.theme.TypeFreeEmerald

private val AskAiIndigo = Color(0xFF6366F1)
private val AskAiSky = Color(0xFF0EA5E9)

/**
 * In-keyboard AI preview card panel (v3.5.0):
 * Displays fast, safe AI answers inside the keyboard viewport without modifying host input field.
 */
@Composable
fun AskAiCardPanel(
    uiState: ImeUiState,
    onAction: (ImeUiAction) -> Unit
) {
    val palette = LocalTypeFreeKeyboardPalette.current
    val s = LocalKeyboardMetrics.current.elementScale
    val query = uiState.askAiQuery
    val answer = uiState.askAiAnswer
    val isLoading = uiState.isAskAiLoading
    val errorMessage = uiState.askAiErrorMessage

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = (8f * s).dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. Header (32.dp): Left `🔍 Q: ${query}` + Right [↩ 返回键盘] and [✕]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(AskAiIndigo.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🔍",
                        fontSize = 10.sp
                    )
                }
                Text(
                    text = "Q: $query",
                    color = palette.keyTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // [↩ 返回键盘]
                Row(
                    modifier = Modifier
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.keyCapBg)
                        .border(1.dp, palette.keyBorder, RoundedCornerShape(12.dp))
                        .clickable { onAction(ImeUiAction.ReturnFromAskAiToQwerty) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.KeyboardGrid,
                        contentDescription = "返回键盘",
                        tint = palette.keyTextPrimary,
                        modifier = Modifier.size(11.dp)
                    )
                    Text(
                        text = "返回键盘",
                        color = palette.keyTextPrimary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // [✕]
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(palette.keyCapBg)
                        .border(1.dp, palette.keyBorder, CircleShape)
                        .clickable { onAction(ImeUiAction.ReturnFromAskAiToQwerty) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = TypeFreeIcons.Close,
                        contentDescription = "关闭",
                        tint = palette.keyTextMuted,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }

        // 2. Middle Card: Scrollable answer container (weight 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.cardElevatedBg)
                .border(1.dp, palette.keyBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            when {
                isLoading -> {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = AskAiIndigo
                        )
                        Text(
                            text = "🔍 AI 正在检索解答…",
                            color = palette.keyTextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                errorMessage != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = errorMessage,
                            color = Color(0xFFEF4444),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.keyCapBg)
                                .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
                                .clickable { onAction(ImeUiAction.RetryAskAiCard) }
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "重试",
                                color = palette.keyTextPrimary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = answer.ifBlank { "（无回答内容）" },
                            color = palette.keyTextPrimary,
                            fontSize = 13.5.sp,
                            lineHeight = 19.5.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }
        }

        // 3. Bottom Action Buttons: 3 action buttons visible once answer is ready
        if (!isLoading && answer.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Button 1: ✓ 知道了（清空输入框）
                Box(
                    modifier = Modifier
                        .weight(1.1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.keyCapBg)
                        .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
                        .clickable { onAction(ImeUiAction.DismissAskAiAndClearEditor) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✓ 知道了(清空)",
                        color = palette.keyTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }

                // Button 2: 📋 复制
                Box(
                    modifier = Modifier
                        .weight(0.7f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.keyCapBg)
                        .border(1.dp, palette.keyBorder, RoundedCornerShape(10.dp))
                        .clickable { onAction(ImeUiAction.CopyAskAiAnswer) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "📋 复制",
                        color = palette.keyTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }

                // Button 3: 填入输入框 ↵
                Box(
                    modifier = Modifier
                        .weight(1.1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(TypeFreeEmerald)
                        .clickable { onAction(ImeUiAction.CommitAskAiAnswerToEditor) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "填入输入框 ↵",
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
