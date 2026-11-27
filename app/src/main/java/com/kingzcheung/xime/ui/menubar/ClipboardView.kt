package com.kingzcheung.xime.ui.menubar

import com.kingzcheung.xime.ui.keyboard.rememberKeyShadowModifier
import com.kingzcheung.xime.ui.keyboard.rememberKeyBevelModifier
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.twotone.Image
import androidx.compose.material.icons.twotone.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kingzcheung.xime.clipboard.ClipboardDragSender
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.service.CandidateState
import com.kingzcheung.xime.clipboard.clipboardLongPressDrag
import com.kingzcheung.xime.ui.CLIPBOARD_CARD_MAX_PX
import com.kingzcheung.xime.ui.rememberClipboardImageRequest
import com.kingzcheung.xime.ui.keyboard.WIDE_CONTAINER_WIDTH
import com.kingzcheung.xime.ui.keyboard.tolerantClick
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import java.io.File

@Composable
fun ClipboardView(
    clipboardItems: List<ClipboardItem>,
    quickSendItems: List<ClipboardItem>,
    selectedTab: Int,
    backgroundColor: Color,
    keyTextColor: Color,
    keyBgColor: Color,
    viewModel: KeyboardViewModel,
    onSelectItem: (String, Long?) -> Unit,
    onSplitWords: (String, Long) -> Unit,
    onBack: (() -> Unit)? = null,
    onClipboardTabChange: ((Int) -> Unit)? = null,
    /**
     * 候选态：搜索框的「嵌入首选」直接从这里读首候选。
     *
     * 为什么传 State 而不是由服务层写入 uiState 中转：Compose 侧只有真正订阅了
     * 变化源才会重组。经 uiState 走一圈时，KeyboardView 的 kbState 由 remember 缓存
     * （按键时用同一实例跳过重组），新字段到不了 ClipboardView——实测「嵌入首选不生效」。
     */
    embeddedCandidateState: androidx.compose.runtime.State<CandidateState>? = null,
    /** 搜索框聚焦时把键盘按键路由到搜索框（而不是宿主应用）。 */
    onSearchFocusChange: ((Boolean) -> Unit)? = null,
    /** 搜索态下在列表下方插入键盘（由调用方渲染真实 Rime 键盘）。 */
    keyboardSlot: (@Composable () -> Unit)? = null,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier,
    onQuickSendAddClick: (() -> Unit)? = null,
    onQuickSendEditItem: ((Long, String, String) -> Unit)? = null,
    onPullRemote: (() -> Unit)? = null,
    pullRemoteAvailable: Boolean = false,
    /** 图片条目点选（直插/回写由服务层处理）。 */
    onSelectImage: ((ClipboardItem) -> Unit)? = null,
    /** 图片条目 → 本地绝对文件（不存在返回 null，用于缩略图与拖拽）。 */
    imageFileOf: ((ClipboardItem) -> File?)? = null,
    /** 拖拽发送已成功启动（收起输入法，露出目标应用的接收区域）。 */
    onDragSendStarted: (() -> Unit)? = null,
) {
    // 卡片/格子背景：与菜单项背景一致（keyBgColor，浅色纯白、深色跟随 keyboard.colors）
    val itemBgColor = keyBgColor
    val textColor = keyTextColor
    val subTextColor = keyTextColor.copy(alpha = 0.65f)
    val accentColor = MaterialTheme.colorScheme.primary
    // 图标按钮容器色：surface 与 primary 的混合色调（带种子色但不过于强烈）
    val iconButtonContainer = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.primary,
        0.35f
    )
    var menuAnchor by remember { mutableStateOf<MenuAnchor?>(null) }
    var isMultiSelect by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showClearConfirm by remember { mutableStateOf(false) }
    // 剪贴板搜索：纯 UI 层内存过滤（≤1000 条），不动 DAO——
    // 多选/删除/拖拽/图片条目机制全部原样复用。图片条目 text 为空，搜索时自然不显示。
    var searchQuery by remember { mutableStateOf("") }
    // 搜索模式：点搜索框后下方列表区换成自绘键盘（输入法自身窗口内系统不弹软键盘）
    var searchKeyboardActive by remember { mutableStateOf(false) }
    // 持有搜索框 EditText 引用：点整框时主动给它焦点并通知按键路由
    var searchEditText by remember { mutableStateOf<android.widget.EditText?>(null) }

    // 「候选首词显示在输入框」在搜索态的等价展示：把将要上屏的词跟在关键词后面预览。
    // 纯展示，不写入 searchQuery、不影响过滤。
    //
    // CompositionLocal / SharedPreferences 都必须无条件读取：把 LocalContext.current 放进
    // if 分支会形成条件式 CompositionLocal 读取，重组时槽位数量变化会让 Compose 抛错
    // 或错位复用。开关值本身用 remember 缓存，避免每次重组都走一遍 SharedPreferences。
    val context = androidx.compose.ui.platform.LocalContext.current
    val candidateFirstEnabled = remember(context) {
        com.kingzcheung.xime.settings.SettingsPreferences.showCandidateFirstInInputBox(context)
    }
    val embeddedCandidate: String = if (searchKeyboardActive && candidateFirstEnabled &&
        embeddedCandidateState != null
    ) {
        val cand = embeddedCandidateState.value
        // 两个前提一起判：
        //   isComposing —— 必须处于组合态，否则上一轮已上屏的候选会一直挂在搜索框上；
        //   inputText 非空 —— 自动上屏（四码唯一）后引擎会短暂保留候选列表但编码已清，
        //     只看 isComposing 会让「刚上屏的那个词」在下一码开始前继续显示，
        //     看起来像嵌入首选卡住了。编码清零即视为本轮结束，立刻撤下预览。
        if (cand.isComposing && cand.inputText.isNotEmpty()) {
            cand.candidates.firstOrNull().orEmpty()
        } else {
            ""
        }
    } else {
        ""
    }

    /** 进入搜索态：只翻标志位，注册交给下面的 LaunchedEffect。
     *  旧实现靠点击回调里读 EditText 引用，离屏框尚未创建时点击等于没生效，
     *  按键会直接落到宿主应用上（表现为“光标不在搜索框”）。 */
    fun activateSearch() {
        searchKeyboardActive = true
        searchEditText?.let { et ->
            com.kingzcheung.xime.service.ToolPanelEditTextHolder.editText = et
            onSearchFocusChange?.invoke(true)
        }
    }

    /** 退出搜索态：必须复位按键路由标志，否则上屏会继续写进已废弃的搜索框。 */
    fun exitSearch() {
        searchKeyboardActive = false
        onSearchFocusChange?.invoke(false)
    }

    /** 改搜索词的唯一出口：Compose 展示态与离屏 EditText 双向同步。
     *  只改 state 会导致下一键在离屏框旧内容上追加，被清掉的字又回来。 */
    fun setQuery(value: String) {
        searchQuery = value
        val et = searchEditText ?: return
        if (et.text?.toString() != value) {
            et.setText(value)
            try {
                et.setSelection(value.length)
            } catch (e: Exception) {
                // 离屏框不参与可见选中，设置失败不影响过滤
            }
        }
    }

    // 搜索态生效后绑定：此时离屏 EditText 一定已由 AndroidView 创建
    LaunchedEffect(searchEditText, searchKeyboardActive) {
        if (!searchKeyboardActive) return@LaunchedEffect
        searchEditText?.let { et ->
            com.kingzcheung.xime.service.ToolPanelEditTextHolder.editText = et
            com.kingzcheung.xime.util.FileLogger.d(
                "ClipboardView",
                "activateSearch: et.attached=${et.isAttachedToWindow} callback=${onSearchFocusChange != null}"
            )
            onSearchFocusChange?.invoke(true)
        }
    }

    // 面板关闭（返回/选中条目/切键盘/换页）必然销毁本组件，这里兜底复位路由标志：
    // 否则 clipboardSearchFocused 一直为 true，之后所有上屏都被吞进一个废弃的 EditText。
    DisposableEffect(Unit) {
        onDispose {
            onSearchFocusChange?.invoke(false)
            if (com.kingzcheung.xime.service.ToolPanelEditTextHolder.editText === searchEditText) {
                com.kingzcheung.xime.service.ToolPanelEditTextHolder.editText = null
            }
        }
    }

    // 搜索框只在「剪贴板 tab + 非多选」显示，但搜索键盘与按键路由标志是独立的两份状态。
    // 任一条件变化都要退出搜索态：否则按键会写进一个看不见的离屏框，宿主一个字都收不到。
    LaunchedEffect(selectedTab, isMultiSelect) {
        if ((selectedTab != 0 || isMultiSelect) && searchKeyboardActive) {
            exitSearch()
        }
    }

    fun exitMultiSelect() {
        isMultiSelect = false
        selectedIds = emptySet()
    }

    // 布局按父容器真实宽度自适应（悬浮卡片/键盘收窄/分屏的容器宽 ≠ 屏幕宽），
    // 不读屏幕方向：宽容器（横屏全屏）用大边距，其余按竖屏形态
    BoxWithConstraints(modifier = modifier) {
        val isWide = maxWidth >= WIDE_CONTAINER_WIDTH

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // 离屏按键接收器：尺寸 1x1、透明、不参与布局可见区域。
        // 键盘按键经 ToolPanelEditTextHolder 写入这里，再同步到 searchQuery。
        // 这样搜索框本身只需纯 Compose 展示，整行点击不会被 EditText 吞掉。
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.size(1.dp),
            factory = { ctx ->
                android.widget.EditText(ctx).apply {
                    alpha = 0f
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    isFocusable = false
                    isFocusableInTouchMode = false
                    isClickable = false
                    setSingleLine(true)
                    searchEditText = this
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: android.text.Editable?) {
                            val t = s?.toString().orEmpty()
                            if (t != searchQuery) searchQuery = t
                        }
                    })
                }
            }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (isWide) 50.dp else 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(iconButtonContainer)
                    .clickable { onBack?.invoke() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "关闭面板",
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(iconButtonContainer)
                    .padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (selectedTab == 0) accentColor else Color.Transparent)
                            .clickable { onClipboardTabChange?.invoke(0) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "剪贴板",
                            color = if (selectedTab == 0) Color.White else textColor,
                            fontSize = 11.sp,
                            fontWeight = if (selectedTab == 0) FontWeight.Medium else FontWeight.Normal
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (selectedTab == 1) accentColor else Color.Transparent)
                            .clickable { onClipboardTabChange?.invoke(1) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "快捷发送",
                            color = if (selectedTab == 1) Color.White else textColor,
                            fontSize = 11.sp,
                            fontWeight = if (selectedTab == 1) FontWeight.Medium else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (selectedTab == 0) {
                if (isMultiSelect) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(iconButtonContainer)
                            .clickable { exitMultiSelect() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "退出多选",
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    // 剪贴板同步已启用（配置+启用插件）时，提供主动拉取按钮
                    if (pullRemoteAvailable && onPullRemote != null) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(iconButtonContainer)
                                .clickable(onClick = onPullRemote),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.TwoTone.Sync,
                                contentDescription = "拉取远端剪贴板",
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (clipboardItems.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(iconButtonContainer)
                                .clickable { showClearConfirm = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "清空剪贴板",
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            if (selectedTab == 1 && onQuickSendAddClick != null) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(iconButtonContainer)
                        .clickable(onClick = onQuickSendAddClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加快捷发送",
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // 剪贴板 tab 的搜索框：与标题行同款视觉（iconButtonContainer 底），
        // 多选模式下隐藏（避免与批量操作栏争空间）。
        if (selectedTab == 0 && !isMultiSelect) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isWide) 50.dp else 8.dp)
                    .padding(bottom = 6.dp)
                    // 整行可点：点搜索框任意位置（图标/文字/空白）都唤起键盘
                    .clickable { activateSearch() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(iconButtonContainer)
                        .then(
                            // 搜索态给一圈描边：还没输入内容时也能看出焦点已经在搜索框
                            if (searchKeyboardActive) {
                                Modifier.border(
                                    1.dp,
                                    accentColor.copy(alpha = 0.75f),
                                    RoundedCornerShape(15.dp)
                                )
                            } else {
                                Modifier
                            }
                        )
                        .clickable { activateSearch() }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "搜索剪贴板",
                            tint = subTextColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        // 纯 Compose 展示（整行可点由外层 Row 处理）。
                        // 键盘按键通过离屏 EditText 接收后同步到这里。
                        // 搜索词与嵌入首选是**两段并列内容**，不是互斥关系：
                        //   · 中文输入时拼音进 Rime 组合，searchQuery 不变；
                        //   · 四码唯一自动上屏后，上屏的词写进离屏 EditText，searchQuery 变成非空，
                        //     再按下一码时组合重新开始——此时必须同时显示「已上屏的词 + 新的首候选」。
                        // 早前把两者做成互斥分支，导致「自动上屏后下一码的嵌入首选凭空消失」。
                        // 占位文案只在两者都为空时出现（搜索态有候选预览时不该再摆一句提示）。
                        val hasEmbedded = searchKeyboardActive && embeddedCandidate.isNotEmpty()
                        val showQuery = searchQuery.isNotEmpty()
                        val showPlaceholder = !showQuery && !hasEmbedded
                        if (searchKeyboardActive && showPlaceholder) {
                            // 插入符前置：离屏 EditText 不参与布局，系统插入符画不到这里，
                            // 这是「焦点已进搜索框」的唯一视觉提示。
                            SearchCaret(color = textColor)
                            Spacer(modifier = Modifier.width(3.dp))
                        }
                        when {
                            showQuery -> Text(
                                text = searchQuery,
                                color = textColor,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            showPlaceholder -> Text(
                                text = "搜索剪贴板内容",
                                color = subTextColor,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        if (hasEmbedded) {
                            // 候选首词嵌入（与宿主输入框同名功能一致）：接在搜索词后面以主题色
                            // 预览将要上屏的词，纯展示、不写入 searchQuery，也不影响过滤结果。
                            if (showQuery) Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = embeddedCandidate,
                                color = accentColor.copy(alpha = 0.85f),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        if (searchKeyboardActive && searchQuery.isNotEmpty()) {
                            SearchCaret(color = textColor)
                        }
                        if (searchQuery.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier.size(28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "清除搜索",
                                    tint = subTextColor,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { setQuery("") }
                                )
                            }
                        }
                    }
                }

                // 搜索按钮：未进入搜索态时点它等于点搜索框（唤起键盘）；
                // 已在搜索态时点它收起键盘看结果——过滤是实时的，不需要额外「提交」。
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(
                            if (searchQuery.isNotEmpty()) MaterialTheme.colorScheme.primary
                            else iconButtonContainer
                        )
                        .clickable {
                            if (searchKeyboardActive) exitSearch() else activateSearch()
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchKeyboardActive) "完成" else "搜索",
                        color = if (searchQuery.isNotEmpty()) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            textColor
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }

        // 搜索态下过滤：空格分隔的多个关键词取「全部命中」(AND)，大小写不敏感。
        // 图片条目 text 为空，自然被排除（搜索只针对文本）。
        val searchTokens = remember(searchQuery) {
            searchQuery.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        }
        val displayClipboardItems = remember(clipboardItems, searchTokens, selectedTab) {
            if (searchTokens.isEmpty() || selectedTab != 0) {
                clipboardItems
            } else {
                // 一遍扫描完成「过滤 + 计分」：旧比较器在每两两比较时都重新 minOf 全扫
                // （O(n log n · tokens · |text|)），这里先把分数算好再排序（O(n·tokens + n log n)）。
                // 小写副本只用来判命中与计分；卡片高亮仍用原串 regionMatches，下标不受
                // 少数字符小写化变长（如 İ→i̇）影响。
                val lowerTokens = searchTokens.map { it.lowercase() }
                clipboardItems.mapNotNull { item ->
                    val lower = item.text.lowercase()
                    var firstHit = -1
                    for (token in lowerTokens) {
                        val idx = lower.indexOf(token)
                        if (idx < 0) return@mapNotNull null
                        if (firstHit < 0 || idx < firstHit) firstHit = idx
                    }
                    item to firstHit
                }.sortedWith(compareBy({ it.second }, { it.first.text.length }))
                    .map { it.first }
            }
        }

        // 关键词变化后回到列表顶部：否则停在旧滚动位置时过滤结果更短，
        // 会出现「明明有结果却是空白页」的错觉。
        val gridState = rememberLazyGridState()
        LaunchedEffect(searchTokens) {
            if (searchTokens.isNotEmpty()) gridState.scrollToItem(0)
        }

        val pagerState = rememberPagerState(
            initialPage = selectedTab,
            pageCount = { 2 }
        )

        LaunchedEffect(selectedTab) {
            pagerState.animateScrollToPage(selectedTab)
        }

        LaunchedEffect(pagerState.currentPage) {
            if (pagerState.currentPage != selectedTab) {
                // 切到其它 tab 时退出多选：多选态只属于剪贴板 tab，切走后
                // 底栏/选中集均无意义（选中集只由剪贴板条目填充）
                exitMultiSelect()
                onClipboardTabChange?.invoke(pagerState.currentPage)
            }
        }

        run {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { page ->
            if (page == 0) {
                ClipboardTabContent(
                    items = displayClipboardItems,
                    queryTokens = searchTokens,
                    emptyHint = if (searchTokens.isNotEmpty()) "没有匹配的剪贴板内容" else "剪贴板为空",
                    gridState = gridState,
                    itemBgColor = itemBgColor,
                    textColor = textColor,
                    subTextColor = subTextColor,
                    accentColor = accentColor,
                    onSelect = onSelectItem,
                    onRemove = { id -> viewModel.removeClipboardItem(id) },
                    onAddToQuickSend = { id -> viewModel.addToQuickSend(id) },
                    onSplitWords = onSplitWords,
                    onLongPressItem = { item, isLeftColumn ->
                        menuAnchor = MenuAnchor(item, isLeftColumn, tab = 0)
                    },
                    isMultiSelect = isMultiSelect,
                    selectedIds = selectedIds,
                    onToggleSelect = { id ->
                        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                    },
                    onExitMultiSelect = { exitMultiSelect() },
                    onSelectImage = onSelectImage,
                    imageFileOf = imageFileOf,
                    onDragSendStarted = onDragSendStarted,
                )
            } else {
                QuickSendTabContent(
                    items = quickSendItems,
                    itemBgColor = itemBgColor,
                    textColor = textColor,
                    subTextColor = subTextColor,
                    accentColor = accentColor,
                    viewModel = viewModel,
                    onSelect = { onSelectItem(it, null) },
                    onQuickSendAddClick = onQuickSendAddClick,
                    onQuickSendEditItem = onQuickSendEditItem,
                    onDragSendStarted = onDragSendStarted,
                    onLongPressItem = { item, isLeftColumn ->
                        menuAnchor = MenuAnchor(item, isLeftColumn, tab = 1)
                    }
                )
            }
        }
        }

        // 搜索态：下方换成自绘键盘（键盘即为本输入法，系统不会为自身弹软键盘，
        // 因此这里用自绘键盘接管输入）。
        if (searchKeyboardActive && keyboardSlot != null) {
            // 方案 B：下方渲染真实 Rime 键盘（双拼/词库/候选全在）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .padding(top = 4.dp)
            ) {
                keyboardSlot()
            }
        } else if (searchKeyboardActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                SearchKeyboard(
                    onInput = { ch -> setQuery(searchQuery + ch) },
                    onBackspace = {
                        if (searchQuery.isNotEmpty()) setQuery(searchQuery.dropLast(1))
                    },
                    onClear = { setQuery("") },
                    onSearch = { exitSearch() },
                    keyBgColor = keyBgColor,
                    textColor = keyTextColor,
                    accentColor = keyTextColor
                )
            }
        }

        if (isMultiSelect && selectedTab == 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "已选 ${selectedIds.size} 项",
                    color = textColor,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                        .clickable(enabled = selectedIds.isNotEmpty()) {
                            viewModel.removeClipboardItems(selectedIds.toList())
                            exitMultiSelect()
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "删除",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(if (isWide) 15.dp else bottomPaddingDp.dp))

        menuAnchor?.let { anchor ->
            val menuItems = if (anchor.tab == 0) {
                if (anchor.item.isOtp) {
                    listOf(
                        LongPressMenuEntry(
                            icon = Icons.Default.Delete,
                            label = "删除",
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { viewModel.removeClipboardItem(anchor.item.id) },
                        )
                    )
                } else if (anchor.item.isImage) {
                    // 图片条目：拆词/快捷发送无意义（文本操作），只剩多选 + 删除
                    listOf(
                        LongPressMenuEntry(
                            icon = Icons.Default.PushPin,
                            label = if (anchor.item.isPinned) "取消置顶" else "置顶",
                            onClick = {
                                viewModel.togglePinClipboardItem(anchor.item.id)
                            }
                        ),
                        LongPressMenuEntry(
                            icon = Icons.Default.DoneAll,
                            label = "多选",
                            onClick = {
                                isMultiSelect = true
                                selectedIds = emptySet()
                            }
                        ),
                        LongPressMenuEntry(
                            icon = Icons.Default.Delete,
                            label = "删除",
                            tint = MaterialTheme.colorScheme.error,
                            onClick = {
                                viewModel.removeClipboardItem(anchor.item.id)
                            }
                        )
                    )
                } else {
                    listOf(
                        LongPressMenuEntry(
                            icon = Icons.Default.PushPin,
                            label = if (anchor.item.isPinned) "取消置顶" else "置顶",
                            onClick = {
                                viewModel.togglePinClipboardItem(anchor.item.id)
                            }
                        ),
                        LongPressMenuEntry(
                            icon = Icons.Default.ContentCut,
                            label = "拆词",
                            onClick = {
                                onSplitWords(anchor.item.text, anchor.item.id)
                            }
                        ),
                        LongPressMenuEntry(
                            icon = Icons.Outlined.StarBorder,
                            label = "快捷",
                            onClick = {
                                viewModel.addToQuickSend(anchor.item.id)
                            }
                        ),
                        LongPressMenuEntry(
                            icon = Icons.Default.DoneAll,
                            label = "多选",
                            onClick = {
                                isMultiSelect = true
                                selectedIds = emptySet()
                            }
                        ),
                        LongPressMenuEntry(
                            icon = Icons.Default.Delete,
                            label = "删除",
                            tint = MaterialTheme.colorScheme.error,
                            onClick = {
                                viewModel.removeClipboardItem(anchor.item.id)
                            }
                        )
                    )
                }
            } else {
                listOfNotNull(
                    LongPressMenuEntry(
                        icon = Icons.Filled.PushPin,
                        label = "置顶",
                        onClick = {
                            viewModel.togglePinQuickSend(anchor.item.id)
                        }
                    ),
                    onQuickSendEditItem?.let { edit ->
                        LongPressMenuEntry(
                            icon = Icons.Default.Create,
                            label = "编辑",
                            onClick = {
                                edit(anchor.item.id, anchor.item.text, anchor.item.code)
                            }
                        )
                    },
                    LongPressMenuEntry(
                        icon = Icons.Default.Delete,
                        label = "删除",
                        tint = MaterialTheme.colorScheme.error,
                        onClick = {
                            viewModel.removeQuickSendItem(anchor.item.id)
                        }
                    )
                )
            }
            LongPressMenuOverlay(
                text = if (anchor.item.isImage) clipboardImageLabel(anchor.item) else anchor.item.text,
                isLeftColumn = anchor.isLeftColumn,
                backgroundColor = backgroundColor,
                contentBgColor = itemBgColor,
                textColor = textColor,
                onDismiss = { menuAnchor = null },
                menuItems = menuItems
            )
        }

        if (showClearConfirm) {
            ClearClipboardConfirmOverlay(
                itemCount = clipboardItems.size,
                backgroundColor = backgroundColor,
                cardBgColor = itemBgColor,
                textColor = textColor,
                subTextColor = subTextColor,
                onCancel = { showClearConfirm = false },
                onConfirm = {
                    showClearConfirm = false
                    viewModel.clearClipboard()
                }
            )
        }
    }
    }
}

@Composable
private fun ClearClipboardConfirmOverlay(
    itemCount: Int,
    backgroundColor: Color,
    cardBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cardBgColor
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "清空剪贴板",
                    color = textColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "将删除全部 $itemCount 条剪贴板记录",
                    color = subTextColor,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable(onClick = onCancel)
                            .padding(horizontal = 18.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "取消",
                            color = textColor,
                            fontSize = 13.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.error)
                            .clickable(onClick = onConfirm)
                            .padding(horizontal = 18.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "清空",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

internal data class MenuAnchor(
    val item: ClipboardItem,
    val isLeftColumn: Boolean,
    val tab: Int
)

data class LongPressMenuEntry(
    val icon: ImageVector,
    val label: String,
    val tint: Color? = null,
    val onClick: () -> Unit
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipboardTabContent(
    items: List<ClipboardItem>,
    /** 当前搜索关键词（已拆分）：卡片内高亮命中的片段。 */
    queryTokens: List<String> = emptyList(),
    /** 列表为空时的提示语：搜索无结果与真正为空要说不同的话。 */
    emptyHint: String = "剪贴板为空",
    gridState: LazyGridState = rememberLazyGridState(),
    itemBgColor: Color,
    textColor: Color,
    subTextColor: Color,
    accentColor: Color,
    onSelect: (String, Long?) -> Unit,
    onRemove: (Long) -> Unit,
    onAddToQuickSend: (Long) -> Unit,
    onSplitWords: (String, Long) -> Unit,
    onLongPressItem: (ClipboardItem, Boolean) -> Unit,
    isMultiSelect: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onToggleSelect: (Long) -> Unit = {},
    onExitMultiSelect: () -> Unit = {},
    onSelectImage: ((ClipboardItem) -> Unit)? = null,
    imageFileOf: ((ClipboardItem) -> File?)? = null,
    onDragSendStarted: (() -> Unit)? = null,
) {
    val view = LocalView.current
    if (items.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyHint,
                color = subTextColor,
                fontSize = 13.sp
            )
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                // 图片文件解析含磁盘 IO：条目重组很频繁，按条目 id 缓存一次即可。
                val itemFile = remember(item.id) { imageFileOf?.invoke(item) }
                val highlighted = isMultiSelect && item.id in selectedIds
                val onClick: () -> Unit = {
                    if (isMultiSelect) {
                        onToggleSelect(item.id)
                    } else if (item.isImage) {
                        onSelectImage?.invoke(item)
                    } else {
                        onSelect(item.text, item.id)
                    }
                }
                val onLongClick: () -> Unit = {
                    if (isMultiSelect) onExitMultiSelect()
                    else onLongPressItem(item, index % 2 == 0)
                }
                // 拖拽发送：长按后拖出 slop 启动；多选态不启用（选择模式冲突）。
                // 启动失败（文件缺失/URI 获取失败/系统拒绝）由手势回退长按菜单。
                val onStartDrag: (() -> Boolean)? = if (isMultiSelect) null else {
                    {
                        // 拖拽时文件可能刚落盘，重新解析一次拿最新状态
                        val ok = ClipboardDragSender.startDragAndDrop(view, item, imageFileOf?.invoke(item))
                        if (ok) onDragSendStarted?.invoke()
                        ok
                    }
                }
                if (item.isImage) {
                    ClipboardImageCard(
                        item = item,
                        isPinned = item.isPinned,
                        highlighted = highlighted,
                        bgColor = itemBgColor,
                        textColor = textColor,
                        subTextColor = subTextColor,
                        accentColor = accentColor,
                        file = itemFile,
                        modifier = Modifier.height(62.dp),
                        onClick = onClick,
                        onLongClick = onLongClick,
                        isMultiSelect = isMultiSelect,
                        dragKey = item.id,
                        onStartDrag = onStartDrag,
                    )
                } else {
                    GridItemCard(
                        text = item.text,
                        queryTokens = queryTokens,
                        isPinned = item.isPinned,
                        highlighted = highlighted,
                        bgColor = itemBgColor,
                        textColor = textColor,
                        accentColor = accentColor,
                        modifier = Modifier.height(62.dp),
                        onClick = onClick,
                        onLongClick = onLongClick,
                        isMultiSelect = isMultiSelect,
                        dragKey = item.id,
                        onStartDrag = onStartDrag,
                    )
                }
            }
        }
    }
}

/**
 * 置顶条目的行首标记字形。
 *
 * 选 U+2691（⚑ BLACK FLAG）而不是 `📌`：后者在多数 ROM 上按彩色 emoji 渲染，
 * 会撑高行距并破坏 14sp 正文的单色协调；⚑ 是单色等宽字形，跟正文混排不跳。
 */
private const val PIN_GLYPH = "⚑"

/** 空回调：非多选态占位，使 tolerantClick 保持长按等待但不执行动作。 */
private val noOpCallback: () -> Unit = {}

/** 图片条目的菜单/长按浮层文案（图片没有可展示的文本）。 */
internal fun clipboardImageLabel(item: ClipboardItem): String {
    val size = if (item.width > 0 && item.height > 0) " · ${item.width}×${item.height}" else ""
    return "图片$size"
}

/**
 * 剪贴板图片卡片：缩略图（Coil 按目标尺寸降采样，不整图解码）。
 * 文件缺失（配额淘汰/被清理）时显示占位文案，点选不再上屏。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClipboardImageCard(
    item: ClipboardItem,
    /** 置顶条目：右上角挂一枚图钉角标。 */
    isPinned: Boolean = false,
    highlighted: Boolean,
    bgColor: Color,
    textColor: Color,
    subTextColor: Color,
    accentColor: Color,
    file: File?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isMultiSelect: Boolean = false,
    dragKey: Any? = null,
    onStartDrag: (() -> Boolean)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    val bg = if (highlighted) accentColor.copy(alpha = 0.18f) else bgColor
    // 多选态长按退出多选；非多选态长按菜单由 clipboardLongPressDrag 处理，
    // 这里保留非空但空实现的回调：让 tolerantClick 进入长按等待，长按触发后抬起
    // 不再 dispatch onClick，避免误上屏（传 null 会导致长按抬起仍触发点击）
    val noOpLongClick: (() -> Unit)? = if (isMultiSelect) onLongClick else noOpCallback
    Box(
        modifier = modifier
                .shadow(6.dp, RoundedCornerShape(12.dp), clip = true)
                .then(rememberKeyShadowModifier(bg, true, 8.dp, false, 100))
            .border(1.5.dp, if (highlighted) accentColor else Color.Transparent, shape)
            .clip(shape)
            .background(bg)
                .then(rememberKeyBevelModifier(bg, true, 8.dp, false, 100))
            // 长按/拖拽由 clipboardLongPressDrag 处理（长按未拖 → onLongClick 弹菜单）。
            // 用 tolerantClick 而非 combinedClickable：卡片位于 LazyVerticalGrid + HorizontalPager 内，
            // 轻点的小位移会被父级手势判定吃掉（部分 ROM/高采样屏幕表现为“点了没反应”）。
            .tolerantClick(onClick = onClick, onLongClick = noOpLongClick)
            // 拖拽手势必须放在点击手势之后（更内层）：Main pass 内层先收，
            // 长按触发后外层点击手势会消费后续事件，外层收到的位移恒为 Zero
            .clipboardLongPressDrag(
                key = dragKey,
                enabled = onStartDrag != null,
                onLongPress = onLongClick,
                onStartDrag = onStartDrag ?: { false },
            )
    ) {
        if (file != null) {
            AsyncImage(
                // 显式上限：卡片约 165×62dp，按 512px 方框降采样解码（不整图进内存）
                model = rememberClipboardImageRequest(file, CLIPBOARD_CARD_MAX_PX),
                contentDescription = clipboardImageLabel(item),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.TwoTone.Image,
                    contentDescription = null,
                    tint = subTextColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "图片已清理",
                    color = subTextColor,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // 图片卡片的置顶标识只能叠在缩略图上（没有文字行首可用），
        // 放在左上角并带底色：右上角是缩略图视觉重心，且与图片主体重叠更明显。
        if (isPinned) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(accentColor.copy(alpha = 0.9f))
                    .padding(1.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = "已置顶",
                    tint = Color.White,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
    }
}

@Composable
fun GridItemCard(
    text: String,
    /** 搜索关键词：命中片段用强调色 + 半粗显示，一眼看出为什么匹配。 */
    queryTokens: List<String> = emptyList(),
    /** 置顶条目：右上角挂一枚图钉角标（列表已按置顶优先排序，这里补可视化标识）。 */
    isPinned: Boolean = false,
    highlighted: Boolean,
    bgColor: Color,
    textColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isMultiSelect: Boolean = false,
    dragKey: Any? = null,
    onStartDrag: (() -> Boolean)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    val bg = if (highlighted) accentColor.copy(alpha = 0.18f) else bgColor
    // 多选态长按退出多选；非多选态长按菜单由 clipboardLongPressDrag 处理，
    // 这里保留非空但空实现的回调：让 tolerantClick 进入长按等待，长按触发后抬起
    // 不再 dispatch onClick，避免误上屏（传 null 会导致长按抬起仍触发点击）
    val noOpLongClick: (() -> Unit)? = if (isMultiSelect) onLongClick else noOpCallback
    Column(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(12.dp), clip = true)
            .then(rememberKeyShadowModifier(bg, true, 8.dp, false, 100))
            .border(1.5.dp, if (highlighted) accentColor else Color.Transparent, shape)
            .clip(shape)
            .background(bg)
            .then(rememberKeyBevelModifier(bg, true, 8.dp, false, 100))
            // 长按/拖拽由 clipboardLongPressDrag 处理（长按未拖 → onLongClick 弹菜单）。
            // 用 tolerantClick 而非 combinedClickable：卡片位于 LazyVerticalGrid + HorizontalPager 内，
            // 轻点的小位移会被父级手势判定吃掉（部分 ROM/高采样屏幕表现为“点了没反应”）。
            .tolerantClick(onClick = onClick, onLongClick = noOpLongClick)
            // 拖拽手势必须放在点击手势之后（更内层）：Main pass 内层先收，
            // 长按触发后外层点击手势会消费后续事件，外层收到的位移恒为 Zero
            .clipboardLongPressDrag(
                key = dragKey,
                enabled = onStartDrag != null,
                onLongPress = onLongClick,
                onStartDrag = onStartDrag ?: { false },
            )
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.Center
    ) {
        // 始终走 AnnotatedString 重载：String / AnnotatedString 混在 if-else 里
        // 会被推断成 Any，Text 没有匹配的重载（编译报 None of the candidates）。
        // 缓存命中的标注串：列表滚动与重组很频繁，每次重新扫描并分配没有意义。
        val annotated = remember(text, queryTokens, accentColor) {
            highlightMatches(text, queryTokens, accentColor)
        }
        // 置顶标识：图钉直接作为文本的第一个字符（AnnotatedString 前缀），
        // 不用 Row 并排也不用浮层。并排会把可用宽度让出一截（长文本被挤成三行），
        // 浮层又会盖住换行后的字——作为字形前缀参与排版，两者都不会发生。
        val withPin = remember(annotated, isPinned, accentColor) {
            if (!isPinned) {
                annotated
            } else {
                buildAnnotatedString {
                    pushStyle(
                        SpanStyle(color = accentColor)
                    )
                    append(PIN_GLYPH)
                    pop()
                    append(' ')
                    append(annotated)
                }
            }
        }
        Text(
            text = withPin,
            color = textColor,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 搜索框的自绘插入符：独立组件，只在搜索态被组合——
 * 面板打开但没进搜索态时完全不创建动画时钟，键盘窗口照常进入 idle 帧。
 * alpha 在 graphicsLayer 里读 State：闪烁只重绘该层，不触发整棵子树重组。
 */
@Composable
private fun SearchCaret(color: Color) {
    val alpha = rememberInfiniteTransition(label = "searchCaret")
        .animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 520, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "searchCaretAlpha"
        )
    Box(
        modifier = Modifier
            .width(1.5.dp)
            .height(15.dp)
            .background(color)
            .graphicsLayer { this.alpha = alpha.value }
    )
}

/** 单张卡片最多标注的高亮段数（两行文本远用不到，纯防御性上界）。 */
private const val MAX_HIGHLIGHT_RANGES = 64

/** 空格分隔关键词：既支持多词，也容忍全角空格（NFKC 归一前先按空白切）。 */
private val WHITESPACE_REGEX = Regex("[\\s\\u00A0\\u3000]+")

/**
 * 大小写不敏感的 [from] 起点查找，返回字符下标；未命中返回 -1。
 *
 * 不用 lowercase() 再 indexOf：某些字符小写化后长度会变（如 İ），
 * 下标就对不上高亮区间了。regionMatches 逐个位置比较，下标始终对原始串。
 */
private fun indexOfIgnoreCase(text: String, token: String, from: Int): Int {
    // token 为空时必须短路：调用方用 idx + token.length 推进起点，长度为 0 会原地打转。
    if (token.isEmpty()) return -1
    val last = text.length - token.length
    if (last < 0) return -1
    // 起点越界直接返回 -1。这里不能用 coerceIn(0, last) 钳进范围：
    // 那会把「已搜索到末尾」的 from 拉回 last 再命中一次，形成无限追加
    // （2026-11-16 真机 OOM：Arrays.copyOf 36MB，栈顶就是本函数）。
    var i = if (from < 0) 0 else from
    while (i <= last) {
        if (text.regionMatches(i, token, 0, token.length, ignoreCase = true)) return i
        i++
    }
    return -1
}

/**
 * 把 [text] 中命中 [tokens] 的片段标成 [hitColor] + 半粗。
 *
 * 多个关键词命中同一段时先合并区间，否则会叠加多层 SpanStyle（加粗叠两次、
 * 颜色互相覆盖），渲染结果是脏的。
 */
private fun highlightMatches(text: String, tokens: List<String>, hitColor: Color): AnnotatedString {
    if (tokens.isEmpty()) return AnnotatedString(text)
    val ranges = mutableListOf<IntRange>()
    var budget = MAX_HIGHLIGHT_RANGES
    outer@ for (token in tokens) {
        var from = 0
        while (true) {
            val idx = indexOfIgnoreCase(text, token, from)
            if (idx < 0) break
            ranges += idx until (idx + token.length)
            from = idx + token.length
            // 硬上界：卡片只渲染两行，几十段高亮之后的都看不见；
            // 更要紧的是防止任何查找逻辑退化时把主线程内存吃穿。
            if (--budget <= 0) break@outer
        }
    }
    if (ranges.isEmpty()) return AnnotatedString(text)
    val merged = mutableListOf<IntRange>()
    ranges.sortedBy { it.first }.forEach { r ->
        val last = merged.lastOrNull()
        if (last != null && r.first <= last.last + 1) {
            merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
        } else {
            merged += r
        }
    }
    return buildAnnotatedString {
        append(text)
        merged.forEach { r ->
            addStyle(SpanStyle(color = hitColor, fontWeight = FontWeight.SemiBold), r.first, r.last + 1)
        }
    }
}

@Composable
fun LongPressMenuOverlay(
    text: String,
    isLeftColumn: Boolean,
    backgroundColor: Color,
    contentBgColor: Color,
    textColor: Color,
    onDismiss: () -> Unit,
    menuItems: List<LongPressMenuEntry>,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(backgroundColor)
                .clickable(onClick = onDismiss)
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier.weight(if (isLeftColumn) 2f else 1f),
                contentAlignment = Alignment.Center
            ) {
                if (isLeftColumn) {
                    ContentCard(
                        text = text,
                        bgColor = contentBgColor,
                        textColor = textColor
                    )
                } else {
                    MenuCard(menuItems = menuItems, cardBgColor = contentBgColor, onDismiss = onDismiss)
                }
            }

            Box(
                modifier = Modifier.weight(if (isLeftColumn) 1f else 2f),
                contentAlignment = Alignment.Center
            ) {
                if (isLeftColumn) {
                    MenuCard(menuItems = menuItems, cardBgColor = contentBgColor, onDismiss = onDismiss)
                } else {
                    ContentCard(
                        text = text,
                        bgColor = contentBgColor,
                        textColor = textColor
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentCard(
    text: String,
    bgColor: Color,
    textColor: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = bgColor
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun MenuCard(
    menuItems: List<LongPressMenuEntry>,
    cardBgColor: Color,
    onDismiss: () -> Unit,
) {
    // 菜单项随功能增加会变多（置顶/拆词/快捷/多选/删除…），面板高度又受键盘窗口限制。
    // 这里给卡片一个"不超过可用高度"的约束 + 竖向滚动，保证每一项都点得到，
    // 而不是被 Row 的居中布局裁在可视区外（旧实现直接溢出，底下几项看不见）。
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxCardHeight = maxHeight
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxCardHeight),
            // 长按弹出菜单立体感：Material3 阴影 + crisp 高光层。
            tonalElevation = 2.dp,
            shadowElevation = 8.dp,
            shape = RoundedCornerShape(12.dp),
            color = cardBgColor
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 6.dp)
            ) {
                menuItems.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        )
                    }
                    LongPressMenuItem(
                        icon = entry.icon,
                        label = entry.label,
                        tint = entry.tint ?: MaterialTheme.colorScheme.onSurface,
                        onClick = {
                            onDismiss()
                            entry.onClick()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun LongPressMenuItem(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 14.sp
        )
    }
}