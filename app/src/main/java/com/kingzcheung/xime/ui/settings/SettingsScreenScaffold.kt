package com.kingzcheung.xime.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 设置页通用外壳：统一的 Scaffold + 带返回箭头的 TopAppBar。
 *
 * 原先这段外壳在 27 个设置页里重复了 36 份，只有标题与返回回调不同
 * （各份之间还在配色、尾逗号、Icon 换行上悄悄漂移过）。收敛到此处后，
 * 标题栏配色与返回语义只需改一个地方。
 *
 * body 保持为自由槽：各页分别是 LazyColumn / Column / Box，不做统一。
 *
 * @param containerColor Scaffold 底色。默认取 [MaterialTheme.colorScheme.surface]
 *   （多数页面如此）；少数页面沿用 Material3 Scaffold 的默认值
 *   [MaterialTheme.colorScheme.background]，迁移时按原值显式传入以保持行为不变。
 * @param actions 标题栏右侧动作区，默认空（与 [TopAppBar] 自身默认一致）。
 * @param floatingActionButton 右下角悬浮按钮，默认空（与 [Scaffold] 自身默认一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenScaffold(
    title: String,
    onBack: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = containerColor,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = floatingActionButton,
    ) { paddingValues ->
        content(paddingValues)
    }
}
