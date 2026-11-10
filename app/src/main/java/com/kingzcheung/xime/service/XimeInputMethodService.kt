package com.kingzcheung.xime.service

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.kingzcheung.xime.ui.keyboard.KeyboardWindowMetrics
import com.kingzcheung.xime.ui.keyboard.LocalStretchFactor
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ui.keyboard.FloatingCardGeometry
import com.kingzcheung.xime.ui.keyboard.FloatingExitGlow
import com.kingzcheung.xime.ui.keyboard.KeyboardResizeOverlay
import com.kingzcheung.xime.ui.keyboard.HardwareKeyboardCandidateBar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.asCoroutineDispatcher
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kingzcheung.xime.association.AssociationManager
import com.kingzcheung.xime.ui.keyboard.KeyboardCallbacks
import com.kingzcheung.xime.viewmodel.KeyboardUiState
import com.kingzcheung.xime.viewmodel.KeyboardViewModel
import com.kingzcheung.xime.clipboard.ClipboardManager
import com.kingzcheung.xime.clipboard.sync.ClipboardSyncBridge
import com.kingzcheung.xime.plugin.ActivePluginSelection
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.core.api.ToolPlugin
import com.kingzcheung.xime.plugin.core.api.ToolResult
import com.kingzcheung.xime.plugin.core.js.PluginEvent
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.speech.AsrBackendFactory
import com.kingzcheung.xime.speech.RecognitionState
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.rime.buildT9DisplayState

import com.kingzcheung.xime.settings.LogLevel
import com.kingzcheung.xime.settings.LogLevelResolver

import com.kingzcheung.xime.rime.RimeCandidate
import com.kingzcheung.xime.rime.resolveRimeCandidateIndex

import com.kingzcheung.xime.correction.KeyTapLogger
import com.kingzcheung.xime.settings.SchemaConfigHelper
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.settings.SyncManager
import com.kingzcheung.xime.ui.keyboard.KeyboardView
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.ui.theme.keyboardBackground
import kotlin.math.abs
import kotlin.math.roundToInt
import com.kingzcheung.xime.settings.ConfigDirOpener
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.theme.XimeTheme
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.keyboard.ActionExecutor
import com.kingzcheung.xime.keyboard.KeyboardPage
import com.kingzcheung.xime.keyboard.OverlayRoute
import com.kingzcheung.xime.keyboard.ToolbarButtonItem
import com.kingzcheung.xime.plugin.core.api.PluginResultItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import java.io.File

object QuickSendFormEditTextHolder {
    var editText: android.widget.EditText? = null
}

/** 快捷发送表单的触发编码输入框 holder（与内容输入框独立）。 */
object QuickSendFormCodeEditTextHolder {
    var editText: android.widget.EditText? = null
}

/** 通用工具面板输入框 holder（与快捷发送独立，避免互相覆盖）。 */
object ToolPanelEditTextHolder {
    /** 当前聚焦的输入框（软/硬按键路由目标；主输入框与控件行字段共用，谁聚焦指向谁）。 */
    var editText: android.widget.EditText? = null

    /** 主输入框（预填与生成取词的锚点；控件行字段聚焦时与 editText 不同）。 */
    var main: android.widget.EditText? = null

    /** 用户是否手动编辑过主输入框：true 后宿主不再回填 prefill（防覆盖用户正在输入的内容）。 */
    var userEdited: Boolean = false

    /** 宿主程序化写入输入框期间的抑制标志：写入不标记 userEdited。 */
    var applyingProgrammatically: Boolean = false
}

/**
 * T9 九键 partial commit 段：右选部分提交时累积的（文本, 拼音）对。
 * 文本用于 preedit 拼接与上屏，拼音用于用户词典调频/回滚，两者同源同步维护。
 */
data class T9PartialSegment(val text: String, val pinyin: String)

class XimeInputMethodService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, ActionExecutor {

    companion object {
        internal const val TAG = "XimeInputMethodService"
        private const val DARK_MODE_LIGHT = 0
        private const val DARK_MODE_DARK = 1
        private const val DARK_MODE_SYSTEM = 2
        private const val HARDWARE_CANDIDATE_BAR_HEIGHT = 72
        internal const val SAFE_TEXT_LIMIT = 262144

        /**
         * 撤回快照 / 落点校验读取输入框的窗口（code unit，2026-10-02）。
         * 足够覆盖单次删除会话（长按连删），避免按 [SAFE_TEXT_LIMIT] 整段读取时
         * 每次手势都拷贝几百 KB 字符串。
         */
        internal const val UNDO_TEXT_WINDOW = 4096

        /** 面板 loading 延迟显示阈值：此时间内完成的动作不显示进度条（面板高度也不变，防闪烁）。 */
        private const val TOOL_PANEL_LOADING_SHOW_DELAY_MS = 250L
    }

    /** release 构建不输出调试日志，减少 logcat 写入开销。 */
    private fun debugLog(msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, msg)
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    internal val rimeEngine = RimeEngine.getInstance()
    
    internal lateinit var clipboardManager: ClipboardManager

    internal var clipboardSyncBridge: ClipboardSyncBridge? = null
    
    internal lateinit var keyboardContainer: VoiceKeyboardContainer
    
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal val keyProcessingDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "key-process").also { it.isDaemon = true }
    }.asCoroutineDispatcher()
    
    internal val keyJobs = Channel<Job>(Channel.UNLIMITED)
    /**
     * 在途按键计数（发送 +1 / job 完成 -1）。
     *
     * Channel<Job> 没有 size API，无法直接得知积压深度；用这个计数器近似
     * 「key-process 尚未处理完的按键数」。>1 表示队列有积压，是快打卡顿的证据。
     */
    internal val keyJobsInFlight = java.util.concurrent.atomic.AtomicInteger(0)
    /**
     * keyJobs 积压埋点计数（非线程安全，仅用于「每 N 键记一次」的日志降频）。
     * handleKeyPress 在主线程串行调用，无需同步。
     */
    internal var queueDepthLogCounter = 0
    internal val uiEventChannel = Channel<suspend () -> Unit>(Channel.CONFLATED)

    /**
     * 长按退格合并锁/状态。
     *
     * 长按退格以约 80ms 的固定频率重复派发，而 rime 退格（JNI + 输入重组）耗时可能
     * 超过 80ms。若每次重复都排队，keyJobs 会堆积，候选栏 UI 更新变成"迟到的跳帧"
     * 突发式刷新（一闪一闪）。这里把高频重复的退格合并为单个 job：处理完一次后
     * 立即消费累积的 [pendingDeleteCount]，删除速率被 rime 吞吐自然限制，
     * UI 更新平滑，抬手后也不会洪水式多删。
     */
    internal val deleteCoalesceLock = Any()
    internal var deleteJobActive = false
    internal var pendingDeleteCount = 0
    /**
     * 删除调度世代：输入框切换时自增。
     * 排队中的退格 job 持有旧世代，执行前比对即整体作废，不再作用到新输入框。
     */
    @Volatile
    internal var deleteGeneration = 0

    init {
        // 按键任务按入队顺序在 key-process 单线程上执行（天然 FIFO），主线程只负责
        // 按序 start，不做 join 等待——打字时主线程不再被 rime 处理 + 候选刷新阻塞，
        // 候选/预编辑区刷新与按键反馈解耦，连续快速输入不卡顿不跟手。
        // 单线程 executor 保证 job 串行执行，顺序与入队一致；UI 更新经 uiEventChannel
        // （CONFLATED）回主线程。
        serviceScope.launch {
            keyJobs.consumeEach { job ->
                try {
                    job.start()
                } catch (e: Throwable) {
                    // 单个按键任务启动失败不能让消费者协程退出，否则后续按键/候选点选会永久堆积失效
                    FileLogger.e(TAG, "key job failed to start, keep consuming", e)
                }
            }
        }
        serviceScope.launch(Dispatchers.Main) {
            uiEventChannel.consumeEach { work -> work() }
        }
    }
    
    internal val mainHandler = Handler(Looper.getMainLooper())
    
    internal val uiState = mutableStateOf(InputUIState())
    /**
     * 悬浮卡片几何镜像（高频变化字段，与 [uiState] 分离）。
     *
     * 拖动调节/悬浮卡片时这些值每帧都在变。若写进 [uiState]，`uiState.value`
     * 每帧换新实例 → 服务层 `kbState = remember(state, ...)` 每次重建 → 整个
     * KeyboardView（全部按键、候选栏）重组 → 拖动明显卡顿。
     *
     * 这里用独立 `mutableStateOf` 承接高频几何变化：KeyboardView 只把它的
     * 引用传给悬浮卡片容器，自己不解引用，几何变化不会让它重组；只有真正读
     * `geometryState.value` 的悬浮卡片容器（外框）重绘，键盘按键区保持不动。
     *
     * 注意：`resizePreviewHeightDp`（影响按键区高度，重组必要）与
     * `showKeyboardResize`（低频开关）继续留在 [uiState]，不走这里。
     */
    internal val geometryState = mutableStateOf(
        com.kingzcheung.xime.service.KeyboardGeometry()
    )
    /** 悬浮键盘拖到底部"松手切换非悬浮"的提示态：拖动回调边沿写入（进入时震动一次），
     *  松手在 onFloatingKeyboardDragEnd 消费；底部光效（FloatingExitGlow）按它显隐 */
    internal val floatingExitHintState = mutableStateOf(false)
    internal val candidateState = mutableStateOf(CandidateState())

    /**
     * 线程安全地读改写 [candidateState]。
     *
     * Compose 的 [mutableStateOf] **不是线程安全的**：`state.value = state.value.copy(...)`
     * 在工作线程上执行时，与主线程的写入/快照应用存在竞态——可能丢掉一次更新
     * （表现为候选栏内容卡在上一轮、联想词回填丢失），严重时触发快照冲突异常。
     *
     * 按键处理跑在 [keyProcessingDispatcher] 单线程上，而 UI 观察在主线程：
     * 绝大多数写入点已显式 `withContext(Dispatchers.Main)`，但热路径上再切一次
     * 协程会多一次调度开销。这里统一收口：
     *   - 已在主线程 → 直接改，零开销；
     *   - 不在主线程 → post 到主线程再改（保持写入顺序）。
     *
     * 注意：非主线程调用时本次修改**异步生效**，调用方不要立刻依赖读回结果。
     */
    internal inline fun updateCandidateState(crossinline transform: (CandidateState) -> CandidateState) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            candidateState.value = transform(candidateState.value)
        } else {
            mainHandler.post { candidateState.value = transform(candidateState.value) }
        }
    }

    /** 线程安全地读改写 [uiState]；语义与注意事项同 [updateCandidateState]。 */
    internal inline fun updateUiState(crossinline transform: (InputUIState) -> InputUIState) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            uiState.value = transform(uiState.value)
        } else {
            mainHandler.post { uiState.value = transform(uiState.value) }
        }
    }
    /**
     * 用户手动排序的候选词频表（编码 → 词 → 权重）。
     *
     * 注意：**不能在字段初始化时用 [CandidateOrderStore.load] 加载**——InputMethodService
     * 的构造发生在 attachBaseContext 之前，那时 `getFilesDir()` 返回 null，
     * 直接加载会在服务创建瞬间抛 NPE 闪退（真机已验证）。因此这里只留空表，
     * 在 [onCreate] 里用已 attach 的 context 加载。
     */
    internal var candidateOrderTable: Map<String, Map<String, Int>> = emptyMap()
    /**
     * 上屏词频表（编码 → 词 → 上屏次数）。
     *
     * 与 [candidateOrderTable] 同一加载约束：InputMethodService 构造先于
     * attachBaseContext，字段初始化时 `getFilesDir()` 为 null，只能在 [onCreate]
     * 里用已 attach 的 context 加载。上屏时更新、防抖落盘（见 [recordWordFrequency]）。
     */
    @Volatile
    internal var wordFreqTable: Map<String, Map<String, Int>> = emptyMap()
    /**
     * 自动造词临时词库（编码 → 词 → 条目）。
     *
     * 与 [candidateOrderTable] 同一加载约束（onCreate 里用已 attach 的 context 加载）。
     * 上屏时记录、晋升时更新并防抖落盘，见 [recordWordFrequency]。
     */
    @Volatile
    internal var autoWordTemp: Map<String, Map<String, AutoWordStore.Entry>> = emptyMap()
    /** 自动造词固定词库（编码 → 词 → 权重），晋升时更新落盘；作为 custom_phrase 注入 rime。 */
    @Volatile
    internal var autoWordFixed: Map<String, Map<String, Int>> = emptyMap()
    /**
     * 黑名单词库：长按候选删除的词（词 → 删除时的编码），显示候选时按编码整条剔除
     * （见 [WordBlacklistStore]）。编码为空 = 按词全局屏蔽（兼容旧数据）。
     *
     * 加载约束同上（onCreate 里用已 attach 的 context 加载）。
     */
    internal var wordBlacklist: Map<String, Map<String, Int>> = emptyMap()

    /**
     * 四张词库是否已从磁盘装载完毕。
     *
     * 词库加载已移到 IO 线程（见 onCreate），在它完成前内存表是**空表占位**。
     * 此时绝不能让 [saveWordData] 落盘——空表写回会直接抹掉用户的造词/词频/排序数据。
     * 因此所有「写盘」入口必须先过这个标记：未装载完就跳过，等装载完成后的
     * 正常防抖/退出保存再把真实内容写出去。
     */
    @Volatile
    private var wordTablesLoaded = false

    /**
     * 词库表互斥锁：保护 [candidateOrderTable] / [wordFreqTable] / [autoWordTemp] /
     * [autoWordFixed] / [wordBlacklist] 的**读-改-写**。
     *
     * 为什么必须加锁：这些表是「内存唯一真源」，但写入发生在两个线程上——
     * - 主线程：上屏统计（[recordWordFrequency]）、手改合并（[reconcileUserDataFiles]）；
     * - IO 线程：防抖落盘（[saveWordData]，见 wordFreqSaveRunnable）。
     *
     * @Volatile 只保证可见性，**不保证读-改-写原子**：
     * `table = store.save(context, table)` 是「读字段 → 写盘/合并 → 回写字段」三步，
     * 若期间主线程刚 `table = store.withCommit(table, ...)` 记了一次上屏，IO 线程
     * 回写的旧快照就会把这笔计数覆盖掉——表现为「刚打的词频偶尔丢一次」。
     * 加锁后两个方向的写入严格互斥，不再有丢失更新。
     *
     * 用 [java.util.concurrent.locks.ReentrantLock]（可重入）：[recordWordFrequency]
     * 持锁期间会调 [recordComboWord] / [recordAutoWord]，闭包内若再取锁不会自锁。
     * 无竞争时开销约 20ns，对按键热路径可忽略。
     */
    private val wordTablesLock = java.util.concurrent.locks.ReentrantLock()

    /**
     * 全量 [AutoWordStore.promote] 的最小间隔（毫秒）。
     *
     * 上屏只改变本次词的 lastSeen/count，其它词的淘汰窗口基于各自 lastSeen、本次上屏
     * 不影响它们。因此每次上屏都全表 promote 是纯浪费（O(条目数)），改为节流：
     * - 日常每次上屏用 [AutoWordStore.promoteWord]（只查本次词，O(该词编码数)）；
     * - 每过 [FULL_PROMOTE_INTERVAL_MS] 做一次全量 promote，顺带把超窗口的陈旧词淘汰掉。
     *
     * 淘汰窗口（7 天）远大于该间隔，60 秒粒度足以及时清掉「久未上屏」的词。
     */
    private val fullPromoteIntervalMs = 60_000L

    /** 上次全量 [AutoWordStore.promote] 的时间戳；0 = 尚未做过（首次上屏即全量）。 */
    private var lastFullPromoteTime = 0L
    private val clipboardItemsState = mutableStateOf<List<com.kingzcheung.xime.clipboard.ClipboardItem>>(emptyList())
    private val voiceAmplitudeState = mutableFloatStateOf(0f)
    private val voiceSpectrumState = mutableStateOf(FloatArray(16))
    private val quickSendItemsState = mutableStateOf<List<com.kingzcheung.xime.clipboard.ClipboardItem>>(emptyList())
    internal val recentClipboardItemsState = mutableStateOf<List<com.kingzcheung.xime.clipboard.ClipboardItem>>(emptyList())


    private val bottomInsetPxState = mutableStateOf(0)
    /** 左右 inset（px）：手机上键盘内容避让挖孔/横屏导航栏，平板不做避让（候选栏按钮靠边）。 */
    private val horizontalInsetPxState = mutableStateOf(0 to 0)
    private var hasHardwareKeyboard = false
    /** 物理 Shift 按下到抬起之间是否有其他按键（组合输入大写等），抬起时据此决定是否切换中英文。 */
    private var shiftComboDetected = false
    /** 当前输入框是否受限（密码/终端/NO_SUGGESTIONS，见 EditorInfoClassifier）。
     *  主线程写（onStartInput）、key-processing 线程读（英文联想短路），volatile 保证可见性。 */
    @Volatile
    private var editorRestricted: Boolean = false
    /** 秘密输入框（密码/TYPE_NULL）：英文联想与回删替换的统一禁用线 */
    private var editorSecret: Boolean = false

    internal var isTrackingVoiceButtons = false
    internal var voiceRecordingStarted = false
    private var pendingVoiceAction: (() -> Unit)? = null
    internal var composeViewRef: View? = null
    /**
     * 撤销槽：最近一次「上滑清空 / 删除」掉的内容，下滑撤回（undo_clear）时回插。
     * 单槽语义 —— 后一次清空/删除会覆盖前一次（= 撤回最近一次操作）。
     */
    internal var lastClearedText: String = ""
    /**
     * 撤销落点锚：删除会话入账（[finishDeleteSession]）时光标前的文本（窗口读取，
     * 见 [UNDO_TEXT_WINDOW]）。下滑撤回前校验光标前文本未变，避免用户随后打过字 /
     * 移过光标时把旧内容插到错误位置。
     *
     * null = 无锚（清空入账，或读不到输入框文本）→ 不做校验，保持改动前的撤回行为。
     */
    internal var lastUndoAnchorText: String? = null
    /**
     * 删除会话状态（下滑撤回删除，2026-10-02）。
     *
     * 长按连删以 30ms 频率重复派发（KeyButton 长按重复），逐次记账会在热路径上
     * 引入 InputConnection 往返，故只在会话首尾各读一次光标前文本：
     *   · [beginDeleteSession]（删除键按下 / 首次派发退格）：快照光标前文本 +
     *     候选栏模式下的编码串；
     *   · [finishDeleteSession]（下滑撤回请求 / 按了别的键）：两次读取之差即本次
     *     删掉的内容，写入 [lastClearedText]，复用既有 undo_clear 回插通道。
     *
     * 结算**不挂在按键抬起**上：抬手时退格 job 可能仍在 keyJobs 队列里（还没落到
     * 输入框），过早读取会少记内容并让落点锚失配。撤回请求与其它按键都排在退格 job
     * 之后（同一 keyJobs FIFO），读到的是删干净之后的状态。
     */
    private var deleteSessionActive = false
    private var deleteSessionBeforeText = ""
    private var deleteSessionBeforeCode = ""
    /**
     * 删除键左滑撤销槽：被整段回删的上屏内容，以及删除后光标前的文本（落点锚）。
     *
     * 右滑恢复前用锚校验光标没被别人动过，避免把旧内容插到错误位置；
     * 换输入框时随 [resetDeleteStateForNewInputSession] 一起作废。
     */
    internal var swipeDeletedChunk: String? = null
    internal var swipeDeletedAnchor: String? = null

    /** 累积的 partial commit 段列表（多段选词场景下逐段追加，文本+拼音同源，供调频/回滚） */
    internal val t9PartialSegments = mutableListOf<T9PartialSegment>()
    /**
     * 组合态编码编辑光标（raw input 字符偏移，-1 = 位于末尾/非编辑态）。
     * 仅全键盘（非 T9）使用：滑动移动此位置，退格/字母键在编辑态由宿主直接改
     * 编码串后 setInput 整串重建——librime caret 恒在编码末尾，候选始终针对
     * 整个编码转换，编辑位置只影响插入/删除点。
     * 主线程（滑动）写、key-processing 线程（编辑拦截）读写，人手操作天然串行。
     */
    @Volatile
    internal var editingCaretPos: Int = -1
    /** 编辑态建立时的编码快照：displayCaretOffset 比对检测非编辑路径的编码变化
     *  （Shift+字母清组合、选词、外部清空等），不一致即复位编辑态（失同步自愈）。 */
    @Volatile
    internal var editingCaretInput: String = ""
    /** 键盘回调引用，用于在 RIME selectCandidate 前同步通知 T9 控制器 */
    internal var keyboardCallbacks: KeyboardCallbacks? = null
    internal var isChineseMode = true
    /** 悬浮卡片实测矩形（窗口坐标，px）：FloatingKeyboardContainer 布局后回传，
     *  触摸区（onComputeInsets）的唯一真源；null = 尚未实测（走 FloatingCardGeometry 兜底） */
    internal var floatingCardBounds: FloatingCardGeometry.CardBounds? = null
    /** 悬浮卡片实测高（dp）；0 = 尚未实测（拖动钳制此时用 FloatingCardGeometry 兜底） */
    internal var currentFloatingCardHeightDp: Int = 0
    internal var previousSchemaId: String = ""
    
    internal val calculatorEngine = com.kingzcheung.xime.calculator.CalculatorEngine()

    private val _viewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    internal val keyboardViewModel: KeyboardViewModel by lazy {
        ViewModelProvider(
            _viewModelStore,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(applicationContext as android.app.Application)
        ).get(KeyboardViewModel::class.java)
    }

    /** 候选展开页自动收起：候选与联想均空（编码删空）时收起在位展开页，不留空页。
     *  在状态生产端调用（而非依赖 UI 重组观察）——候选变化时键盘区作用域被设计为跳过重组。 */
    internal fun maybeCollapseCandidatePage() {
        if (!keyboardViewModel.candidatePageExpanded.value) return
        val cs = candidateState.value
        if (cs.candidates.isEmpty() && cs.associationCandidates.isEmpty()) {
            keyboardViewModel.setCandidatePageExpanded(false)
        }
    }

    /** 刷新展开页的跨页全量候选；非展开态清空以省内存。编码变化与展开动作时调用 */
    internal fun refreshExpandedCandidates() {
        if (!keyboardViewModel.candidatePageExpanded.value) {
            if (candidateState.value.expandedCandidates.isNotEmpty()) {
                candidateState.value = candidateState.value.copy(
                    expandedCandidates = emptyList(),
                    expandedActions = emptyList()
                )
            }
            return
        }
        val perfT0 = android.os.SystemClock.elapsedRealtime()
        // 纠错候选由 librime 插件（correction_translator）直接产出并参与引擎排序，
        // 展开页只需拉取引擎的跨页全量候选，不再在 Kotlin 侧合成候选。
        val all = rimeEngine.getAllCandidates().toList()
        candidateState.value = candidateState.value.copy(
            expandedCandidates = all,
            expandedActions = emptyList()
        )
        android.util.Log.d(
            "CandidatePerf",
            "refreshExpandedCandidates: count=${all.size} cost=${android.os.SystemClock.elapsedRealtime() - perfT0}ms"
        )
    }

    /** 硬件键盘在展开态按 DPAD_DOWN/UP：展开页滚动一屏（经事件流驱动 UI） */
    private fun expandedPageScroll(direction: Int) {
        keyboardViewModel.requestExpandedPageScroll(direction)
    }
    
    internal val predictionManager = PredictionManager(
        context = this,
        serviceScope = serviceScope,
        onPredictionResult = { candidates ->
            candidateState.value = candidateState.value.copy(
                associationCandidates = candidates
            )
        },
    )
    
    internal val voiceRecognitionHandler = VoiceRecognitionHandler(
        context = this,
        onStateChanged = { newState -> uiState.value = newState },
        getState = { uiState.value },
        getInputConnection = { currentInputConnection },
        onVoiceComplete = {
            val action = pendingVoiceAction
            pendingVoiceAction = null
            action?.invoke()

            restoreAfterVoiceFinish()
        },
        onAmplitudeChanged = { amplitude ->
            voiceAmplitudeState.floatValue = amplitude
        },
        onSpectrumChanged = { spectrum ->
            voiceSpectrumState.value = spectrum
        },
        onComposingWritten = { markInputBoxComposing() }
    )

    /**
     * 结束语音会话的统一出口：停止预启动并进入收尾——等待引擎最终结果后提交，
     * 超时回退提交部分结果（见 VoiceRecognitionHandler.finishRecognition）。
     * 键盘状态在收尾完成时经 onVoiceComplete → [restoreAfterVoiceFinish] 恢复，
     * 期间语音面板保持"正在识别..."显示，避免用户感知到结果被截断。
     * 幂等：收尾已在进行中时重复调用自动跳过。
     */
    internal fun endVoiceSession() {
        voiceRecognitionHandler.cancelPreStart()
        voiceRecognitionHandler.finishRecognition()
    }

    /**
     * 发送类动作前的语音会话收尾：立即冲刷已识别文本并丢弃迟到结果
     * （见 [VoiceRecognitionHandler.sealPendingForSend]），随后结束会话。
     *
     * 与 [endVoiceSession] 的区别是"不等引擎最终结果"——宿主动作（聊天应用的回车即"发送"）
     * 马上就要执行，等来的最终结果只会落在动作之后，造成输入框内容重复或错位。
     */
    internal fun sealVoiceSessionForSend() {
        voiceRecognitionHandler.sealPendingForSend()
        endVoiceSession()
    }

    /** 语音会话真正完成（最终结果已提交/超时兜底/出错）后恢复键盘状态。幂等。 */
    internal fun restoreAfterVoiceFinish() {
        // 兜底：会话结束的任何路径都确保录音已请求停止（幂等，正常松手路径
        // recordingThread 已置空时直接返回）。UI 状态一旦清零，松手停止链就失效，
        // 这里漏一次 stop 麦克风/引擎连接就会一直后台占用。
        voiceRecognitionHandler.stopRecognition()
        keyboardViewModel.exitVoice()
        isTrackingVoiceButtons = false
        voiceRecordingStarted = false
        voiceAmplitudeState.floatValue = 0f
        uiState.value = uiState.value.copy(
            isVoiceMode = false,
            voiceSticky = false,
            voiceButtonState = VoiceButtonState(),
            voiceRecognitionState = RecognitionState.IDLE,
            voiceRecognizedText = "",
            voiceAmplitude = 0f
        )
    }
    
    private var sharedPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var clipboardCollectorJob: kotlinx.coroutines.Job? = null
    
    internal val feedbackManager = FeedbackManager(this)

    internal val keyRouter = ImeKeyRouter(this)

    internal val sessionController = ImeSessionController(this)

    internal val schemaController = ImeSchemaController(this)

    /** ascii（中/西文）模式唯一控制入口：切换/会话决策/归位均收敛于此 */
    internal val asciiModeController = AsciiModeController(this)

    internal val textCommit = ImeTextCommit(this)
    
    private val inlineSuggestionManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        InlineSuggestionManager(this)
    } else null
    
    private fun loadDarkModePreference() {
        val isLandscape = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        val isFloatingMode = SettingsPreferences.isFloatingMode(this, isLandscape)
        val loadedX = SettingsPreferences.getFloatingOffsetX(this, isLandscape)
        val loadedY = SettingsPreferences.getFloatingOffsetY(this, isLandscape)
        val screenH = resources.configuration.screenHeightDp
        val halfMargin = FloatingCardGeometry.halfMarginDpForConfig(
            resources.configuration.screenWidthDp,
            screenH,
        )
        val kbH = SettingsPreferences.getKeyboardHeightDp(this, isLandscape, isFloatingMode)
        val cappedKbH = kbH.coerceAtMost((screenH * 8) / 10)
        val cardH = FloatingCardGeometry.fallbackCardHeightDp(
            cappedKbH,
            SettingsPreferences.getKeyboardBottomPaddingDp(this, isLandscape, isFloatingMode),
        )
        val navBarDp = tryGetNavBarHeightDp(this, window.window)
        val minY = if (isFloatingMode) navBarDp else 0
        val effectiveH = if (isFloatingMode) screenH - tryGetStatusBarHeightDp(this, window.window) else screenH
        val maxY = maxOf(minY, effectiveH - cardH - 20)
        val clampedX = loadedX.coerceIn(-halfMargin, halfMargin)
        val clampedY = loadedY.coerceIn(minY, maxY)
        if (clampedX != loadedX || clampedY != loadedY) {
            SettingsPreferences.setFloatingOffsetX(this, clampedX, isLandscape)
            SettingsPreferences.setFloatingOffsetY(this, clampedY, isLandscape)
        }
        uiState.value = uiState.value.copy(
            darkMode = SettingsPreferences.getDarkMode(this),
            themeId = SettingsPreferences.getKeyboardTheme(this),
            isSttEnabled = SettingsPreferences.isSttEnabled(this@XimeInputMethodService),
            keyboardHeightDp = SettingsPreferences.getKeyboardHeightDp(this, isLandscape, isFloatingMode),
            keyboardBottomPaddingDp = SettingsPreferences.getKeyboardBottomPaddingDp(this, isLandscape, isFloatingMode),
            // 几何三件套写独立 geometryState（高频变化，避免重建 kbState）
            toolbarButtons = SettingsPreferences.getToolbarButtons(this),
            isFloatingMode = isFloatingMode,
        )
        geometryState.value = geometryState.value.copy(
            keyboardMarginStartDp = SettingsPreferences.getKeyboardMarginStartDp(this, isLandscape, isFloatingMode),
            keyboardMarginEndDp = SettingsPreferences.getKeyboardMarginEndDp(this, isLandscape, isFloatingMode),
            resizePreviewMarginStartDp = SettingsPreferences.getKeyboardMarginStartDp(this, isLandscape, isFloatingMode),
            resizePreviewMarginEndDp = SettingsPreferences.getKeyboardMarginEndDp(this, isLandscape, isFloatingMode),
            floatingOffsetX = clampedX,
            floatingOffsetY = clampedY,
        )
    }
    
    private fun registerSharedPrefsListener() {
        val prefs = SettingsPreferences.getPrefsPublic(this)
        sharedPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when {
                key == "dark_mode" || key == "keyboard_theme" || key == "show_bottom_buttons" ||
                    // 工具栏按钮原来只在 loadDarkModePreference 里读一次、没有任何刷新入口，
                    // 增删按钮后必须重建键盘才生效（用户 2026-11 排查）
                    key == "toolbar_buttons" -> {
                    loadDarkModePreference()
                    applyWindowBackground()
                }
                // 键盘尺寸/底距/左右边距按「方向 × 悬浮」拆成多套键
                // （keyboard_height_dp_landscape / _floating / _floating_landscape 等）。
                // 原来只精确匹配基础键名，改横屏/悬浮那几套不会刷新，表现为「调了没反应」。
                key != null && (
                    key.startsWith("keyboard_height_dp") ||
                        key.startsWith("keyboard_bottom_padding_dp") ||
                        key.startsWith("keyboard_margin_start_dp") ||
                        key.startsWith("keyboard_margin_end_dp")
                    ) -> {
                    loadDarkModePreference()
                    applyWindowBackground()
                }
                key == "floating_mode" || key == "floating_mode_landscape" -> {
                    loadDarkModePreference()
                    applyWindowBackground()
                }
                // 显示类设置：候选栏每次重组都会现读 prefs，但没人触发重组。
                // 自增修订号 → 键盘整体重组一次，设置立即见效（不必等下一次按键）。
                key == "show_candidate_comments" || key == "candidate_text_size" ||
                    key == "page_size" || key == "input_text_location" ||
                    key == "candidate_first_in_input_box" -> {
                    uiState.value = uiState.value.copy(
                        settingsRevision = uiState.value.settingsRevision + 1,
                    )
                }
                key == "stt_enabled" -> {
                    val sttEnabled = SettingsPreferences.isSttEnabled(this@XimeInputMethodService)
                    uiState.value = uiState.value.copy(isSttEnabled = sttEnabled)
                    // 关闭开关时结束进行中的语音会话
                    if (!sttEnabled && (uiState.value.isVoiceMode || voiceRecordingStarted)) {
                        endVoiceSession()
                    }
                }
                key == SettingsPreferences.KEY_STT_KEEP_ENGINE_ALIVE -> {
                    // 开启时立即预热模型常驻待命（走 AsrSupport.warmup 注册常驻后端）；
                    // 关闭时不主动销毁，:asr 服务端按同步到的设置恢复空闲回收，
                    // 且下一会话开始时 OfflineAsrBackend 会重新同步设置
                    if (SettingsPreferences.isSttKeepEngineAlive(this@XimeInputMethodService)) {
                        Thread { AsrBackendFactory.warmup(this@XimeInputMethodService) }.start()
                    }
                }
                key == SettingsPreferences.KEY_SMART_PREDICTION_ENABLED -> onPredictionSettingChanged()
                key == SettingsPreferences.KEY_CLIPBOARD_SYNC_ENABLED -> updateClipboardSync()
                key == SettingsPreferences.KEY_CLIPBOARD_SYNC_PLUGIN_ID -> {
                    stopClipboardSync()
                    updateClipboardSync()
                }
                key == SettingsPreferences.KEY_HARDWARE_KEYBOARD_DETECTION_ENABLED -> {
                    hasHardwareKeyboard = SettingsPreferences.isHardwareKeyboardDetectionEnabled(this@XimeInputMethodService) &&
                        resources.configuration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS
                    applyCompactMode()
                    applyWindowBackground()
                    updateCursorUpdateMonitoring()
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
    }

    /** 设置驱动：智能联想开启时加载联想模型，关闭时卸载。 */
    private fun onPredictionSettingChanged() {
        val enabled = SettingsPreferences.isSmartPredictionEnabled(this)
        if (enabled) {
            serviceScope.launch(Dispatchers.IO) {
                com.kingzcheung.xime.association.AssociationManager.initialize(this@XimeInputMethodService)
            }
        } else {
            com.kingzcheung.xime.association.AssociationManager.release()
        }
    }
    
    private fun saveDarkModePreference(mode: Int) {
        SettingsPreferences.setDarkMode(this, mode)
        uiState.value = uiState.value.copy(darkMode = mode)
    }
    
    fun toggleDarkMode() {
        val currentMode = uiState.value.darkMode
        val newMode = when (currentMode) {
            DARK_MODE_LIGHT -> DARK_MODE_DARK
            DARK_MODE_DARK -> DARK_MODE_LIGHT
            else -> { // DARK_MODE_SYSTEM: 切换到当前系统主题的反面
                if (isDarkTheme()) DARK_MODE_LIGHT else DARK_MODE_DARK
            }
        }
        saveDarkModePreference(newMode)
    }
    
    fun isDarkTheme(): Boolean {
        return when (uiState.value.darkMode) {
            DARK_MODE_DARK -> true
            DARK_MODE_SYSTEM -> {
                val nightModeFlags = resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            else -> false
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 允许 IME 窗口绘制到摄像头挖孔/刘海区域（横屏时背景覆盖全屏）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.window?.attributes?.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        savedStateRegistryController.performRestore(null)
        window.window?.decorView?.setViewTreeLifecycleOwner(this)
        window.window?.decorView?.setViewTreeSavedStateRegistryOwner(this)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        
        
        FileLogger.init(this)
        KeyTapLogger.init(this)
        KeyTapLogger.enabled = SettingsPreferences.isKeyTapLogEnabled(this)
        KeyTapLogger.schemaProvider = { uiState.value.currentSchemaId }
        FileLogger.i(TAG, "XimeInputMethodService created")
        // 候选排序/词频/造词/黑名单四张词库：**整体挪到 IO 线程加载**。
        //
        // 这里原来在 onCreate（主线程，即输入法服务创建期间）同步 readText + 逐行
        // split + 建嵌套 Map 四份文件。真机数据量下（历史遗留曾达 5 万条 / 2.4MB
        // 临时词库）这一步是「第一次弹键盘明显迟滞」的主因——服务创建占住主线程，
        // 窗口画不出来。词库只是候选排序的增强项：**空表时全链路照常工作**
        // （CandidateOrderStore/reorder 与 hitsFor 命中空表即原样返回），
        // 因此可以先让键盘秒开，再后台补齐。IO 线程读完后在主线程原子替换字段。
        //
        // 注意保持「读 + 收敛 + 写回 + prune」的顺序完整执行：这些操作之间共享
        // 内存态与磁盘基线，拆开并发会互相覆盖。
        serviceScope.launch(Dispatchers.IO) {
            try {
                val orderTable = CandidateOrderStore.load(this@XimeInputMethodService)
                // 3.3.15「yaml 单源」：candidate_order.dict.yaml 由 load/save 直接读写，
                // 不再需要额外 ensureRimeDict（那份中间层 txt 已不存在）。
                val freqTable = WordFreqStore.load(this@XimeInputMethodService)
                val tempTable = AutoWordStore.loadTemp(this@XimeInputMethodService)
                // 一词一码：读入即收敛并落盘。历史版本把组合生词的全部编码一起晋升，
                // 文件里同一个词挂着 2-4 个编码（「阿红」挂 4 条）。只在读取时收敛内存
                // 不会自愈磁盘，这里把收敛结果写回，旧数据一次性修干净。
                val rawFixed = AutoWordStore.loadFixedRaw(this@XimeInputMethodService)
                val cleanFixed = AutoWordStore.dedupeFixed(rawFixed)
                var fixedTable = cleanFixed
                if (cleanFixed !== rawFixed) {
                    // 确实发生了收敛：立即写盘，下次启动读到干净表
                    fixedTable = AutoWordStore.saveFixed(this@XimeInputMethodService, cleanFixed)
                    FileLogger.i(TAG, "AutoWordStore: 固定词库一词多音收敛，" +
                        "${rawFixed.values.sumOf { it.size }} 条 → ${cleanFixed.values.sumOf { it.size }} 条")
                }
                val blacklistTable = WordBlacklistStore.load(this@XimeInputMethodService)
                // 启动即清理临时词库：超过观察窗口（auto_word.promote_days，默认 3 天）
                // 没再上屏的词直接删除，不必等到下次上屏——用户可能长时间不开输入法。
                val prunedTemp = AutoWordStore.prune(
                    tempTable,
                    System.currentTimeMillis(),
                    com.kingzcheung.xime.settings.KeysConfigHelper.getAutoWordConfig().promoteDays,
                )
                val needSave = prunedTemp !== tempTable
                // 一次性在主线程发布，避免候选链路读到「一半新一半旧」的中间态
                withContext(Dispatchers.Main) {
                    wordTablesLoaded = true
                    candidateOrderTable = orderTable
                    wordFreqTable = freqTable
                    autoWordTemp = prunedTemp
                    autoWordFixed = fixedTable
                    wordBlacklist = blacklistTable
                    if (needSave) scheduleWordFreqSave()
                    FileLogger.d(
                        TAG,
                        "Word tables ready: temp=${autoWordTemp.size} entries, " +
                            "fixed=${autoWordFixed.size} entries, " +
                            "tempTotal=${autoWordTemp.values.sumOf { it.size }}, " +
                            "fixedTotal=${autoWordFixed.values.sumOf { it.size }}",
                    )
                }
            } catch (t: Throwable) {
                // 词库加载失败不应拖垮输入法：记日志后保持空表继续工作
                FileLogger.e(TAG, "词库加载失败，候选排序/造词降级为空表", t)
            }
        }
        FileLogger.i(
            TAG,
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
                "screen=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}@${resources.displayMetrics.density}"
        )
        
        feedbackManager.initialize()
        
        loadDarkModePreference()
        registerSharedPrefsListener()
        
        initRimeEngine()
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                initClipboardManager()
                initAssociationEngine()
                initSpeechRecognition()

                withContext(Dispatchers.Main) {
                    FileLogger.i(TAG, "Service initialization completed")
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Initialization failed: ${e.message}")
            }
        }
    }
    
    private fun initSpeechRecognition() {
        voiceRecognitionHandler.initialize()
    }
    
    private fun initAssociationEngine() {
        predictionManager.initialize()
    }
    
    
    private fun getPredictionFromPlugin(contextText: String) {
        predictionManager.getPrediction(contextText)
    }
    
    private fun initRimeEngine() {
        Log.d(TAG, "initRimeEngine: Starting initialization...")
        
        // 必须在任何异步操作之前同步加载键盘按键配置，
        // 否则 KeyboardLayout 组合时 swipeUp/swipeDown 配置可能尚未就绪，
        // 导致按键上的符号不显示、上滑/下滑手势不触发。
        //
        // 【实测结论：不要再来"优化"这一段】
        // 本段 runBlocking 的实际阻塞时长实测仅 3~94ms（真机三次采样）：
        // Application.onCreate 里的异步预热已让 loadConfig 命中 mtime 缓存，
        // 在冷启动 1038ms 中只占 3~9%，不是瓶颈。
        // 曾改成「后台线程解析 + 主线程限时等待 200ms」，实测冷启动无改善
        // （1103/1121ms vs 1038ms，噪声内），故回滚为原实现（更简单）。
        runBlocking(Dispatchers.IO) {
            val t0 = System.nanoTime()
            KeysConfigHelper.loadConfig(this@XimeInputMethodService)
            // onCreate 在主线程，这段同步等待直接等于键盘弹出前的阻塞时长。
            // 保持 WARN 级别：默认日志等级（error）下也能看到，便于长期观察是否劣化。
            FileLogger.w(
                TAG,
                "initRimeEngine: loadConfig 耗时 ${(System.nanoTime() - t0) / 1_000_000}ms",
            )
        }

        // 引擎异步就绪前先以持久化方案填充 UI 状态：键盘首帧事件链（InputSessionStarted/
        // AsciiModeChanged）即携带正确 schemaId 推导布局，避免弹出时先渲染全键盘、
        // 引擎就绪后才切九键/笔画的闪烁。引擎实际方案随后由 updateSchemaName 权威覆盖
        uiState.value = uiState.value.copy(
            currentSchemaId = SettingsPreferences.getCurrentSchema(this)
        )
        
        initDeploymentCallback()
        
        val initJob = serviceScope.launch(Dispatchers.IO) {
            try {
                notifyDeploymentStatus(true, "正在初始化...")
                
                val (userDataDir, sharedDataDir) = RimeConfigHelper.initializeRimeDataAsync(this@XimeInputMethodService)
                
                notifyDeploymentStatus(true, "正在加载输入法引擎...")
                // 引擎是进程级单例：切换输入法会让系统销毁/重建本服务，但引擎仍在。
                // 已初始化时绝不能重复 initialize（librime 不支持同进程二次 startup），
                // 直接复用，后面 ensureSession 会重建会话。
                if (RimeEngine.isInitialized()) {
                    FileLogger.i(TAG, "initRimeEngine: engine already initialized, reusing")
                } else {
                    rimeEngine.initialize(userDataDir, sharedDataDir)
                }

                // 检查词库是否已部署（deploymentDone 标记 + 部署 hash 一致）
                val deploymentDone = SettingsPreferences.isDeploymentDone(this@XimeInputMethodService)
                val needsDeployment = !deploymentDone || !RimeConfigHelper.isDeploymentComplete(this@XimeInputMethodService)
                // 部署前兜底：确保 rime 可编译词典 word_freq.dict.yaml 已生成。
                // XGuai_flypy 等方案的 import_tables 引用 `word_freq`，若文件缺失
                // librime 编译方案词库直接失败、build 无 .bin → 打不出字（engine=0）。
                // 词频表已落盘时按内存表生成；未加载过则从可见目录读一次。
                if (needsDeployment && wordFreqTable.isEmpty()) {
                    // 3.3.15「yaml 单源」：word_freq.dict.yaml 已是真源，
                    // 部署前只需确保内存表已装载（文件由 save 直接维护）。
                    wordFreqTable = WordFreqStore.load(this@XimeInputMethodService)
                }
                if (needsDeployment) {
                    // 统一部署入口（进程内互斥，hash 一致时内部跳过）。
                    // 与 XimeApplication 预初始化共享，避免两者并发触发两次全量编译。
                    notifyDeploymentStatus(true, "正在编译词库...")
                    if (RimeConfigHelper.ensureDeployment(this@XimeInputMethodService)) {
                        rimeEngine.updateLastBuildTime()
                    } else {
                        FileLogger.e(TAG, "initRimeEngine: ensureDeployment failed, deployment may not have completed")
                    }
                } else {
                    Log.d(TAG, "initRimeEngine: Already deployed, creating session directly")
                }

                // 创建 session（已部署时跳过 maintenance 直接创建）
                val sessionReady = rimeEngine.ensureSession(180_000L)
                if (sessionReady) {
                    Log.d(TAG, "initRimeEngine: Session ready")
                    // 确保部署成功后才标记完成，避免首次部署超时后误标记
                    if (needsDeployment) {
                        SettingsPreferences.setDeploymentDone(this@XimeInputMethodService, true)
                        RimeConfigHelper.storeDeploymentHash(this@XimeInputMethodService)
                    }
                } else {
                    FileLogger.w(TAG, "initRimeEngine: Session not ready after 60s, continuing in background")
                }
                notifyDeploymentStatus(false, "")

                // 必须在主线程执行：函数内部直接读写 viewModel / uiState（抽取时漏了这层，
                // 原来紧跟着的那段内联 withContext(Main) 代码才是真正干活的地方，两者重复执行两遍）。
                withContext(Dispatchers.Main) { applyInitialSchemaAfterInit() }
                // 引擎可用后加载用户词库词集合，供组合生词查重（异步，失败静默）
                refreshSchemeDictWords(force = true)
                // 迁移：清理旧版注入到快捷短语的自动造词（造词已改候选合并通道）
                cleanupAutoWordFromCustomPhrase()
            } catch (e: Exception) {
                FileLogger.e(TAG, "initRimeEngine: Failed to initialize Rime engine", e)
                notifyDeploymentStatus(false, "初始化失败")
            }
        }
        
        startInitWatchdog()
    }
    

    /** initRimeEngine 的 setDeploymentCallback 段：注册引擎部署状态回调。 */
    private fun initDeploymentCallback() {
        RimeEngine.setDeploymentCallback { isDeploying, message ->
            serviceScope.launch(Dispatchers.Main) {
                uiState.value = uiState.value.copy(
                    isDeploying = isDeploying,
                    deploymentMessage = message
                )
            }
        }
    }

    /** initRimeEngine 的 withContext(Main) 段：按「手写方案 / savedSchema 可用 / 已编译 / 退回首可用」四档套用初始方案，随后刷新方案名、恢复选项并刷新 UI。 */
    private suspend fun applyInitialSchemaAfterInit() {
        val savedSchema = SettingsPreferences.getCurrentSchema(this@XimeInputMethodService)
        val availableSchemas = rimeEngine.getAvailableSchemas()
        val currentSchema = rimeEngine.getCurrentSchema()
        Log.d(TAG, "initRimeEngine: currentSchema=$currentSchema, savedSchema=$savedSchema, availableSchemas=${availableSchemas.joinToString()}")
                    
        when {
            isHandwritingSchema(savedSchema) -> {
                // 手写方案：不要调 rimeEngine.switchSchema（Rime 没有手写引擎），
                // 也不要覆盖 savedSchema（由 onStartInput 恢复 UI）
                Log.d(TAG, "initRimeEngine: savedSchema is handwriting, keeping current Rime schema")
                // UI 布局恢复：冷启动时第一次 onStartInput 先于引擎初始化完成，
                // RimeEngine.isInitialized()=false 会跳过 handwriting UI 恢复，
                // 此处必须补切，否则第一次弹出键盘停留在默认全键盘
                val hwDir = com.kingzcheung.xime.model.ModelStorage.getModelDir(
                    this@XimeInputMethodService, "ochwpro"
                )
                com.kingzcheung.xime.model.ModelStorage.migrateLegacyForModel(
                    this@XimeInputMethodService, "ochwpro"
                )
                val modelOk = java.io.File(hwDir, "ochwpro.onnx").exists() &&
                    java.io.File(hwDir, "char_index.json").exists()
                if (modelOk) {
                    val page = keyboardViewModel.page.value
                    val alreadyHandwriting = page is com.kingzcheung.xime.keyboard.KeyboardPage.Main &&
                        page.type == com.kingzcheung.xime.keyboard.MainType.HANDWRITING
                    if (!alreadyHandwriting) {
                        keyboardViewModel.switchMain(com.kingzcheung.xime.keyboard.MainType.HANDWRITING)
                    }
                    // 手写模型按"用键盘时加载"管理：不在此预载，
                    // HandwritingKeyboardLayout 创建时（LaunchedEffect）负责加载
                } else {
                    FileLogger.w(TAG, "initRimeEngine: handwriting model missing, keep full keyboard")
                    android.widget.Toast.makeText(
                        this@XimeInputMethodService,
                        "请先下载手写模型",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            savedSchema in availableSchemas -> {
                // 即使 savedSchema == currentSchema 也要调用 switchSchema，
                // 因为 nativeCreateSession 后 schema 的 processor/translator 等
                // 可能未完全初始化，switchSchema 会触发完整的初始化流程
                Log.d(TAG, "initRimeEngine: Switching to saved schema: $savedSchema")
                schemaController.applyPageSizeSetting(savedSchema)
                rimeEngine.switchSchema(savedSchema)
            }
            SchemaManager.isSchemaCompiled(this@XimeInputMethodService, savedSchema) -> {
                Log.d(TAG, "initRimeEngine: Schema compiled but not in get_schema_list, switching anyway")
                schemaController.applyPageSizeSetting(savedSchema)
                rimeEngine.switchSchema(savedSchema)
            }
            availableSchemas.isNotEmpty() -> {
                // savedSchema 不可用且未编译，退而求其次用第一个可用方案
                val fallbackSchema = availableSchemas.first()
                Log.d(TAG, "initRimeEngine: savedSchema '$savedSchema' not available, falling back to '$fallbackSchema'")
                schemaController.applyPageSizeSetting(fallbackSchema)
                rimeEngine.switchSchema(fallbackSchema)
                SettingsPreferences.setCurrentSchema(this@XimeInputMethodService, fallbackSchema)
            }
        }
                    
        sessionController.updateSchemaName()
        // onStartInput 在部署进行中会跳过 schema 切换与选项恢复，
        // 部署完成后这里补齐 UI 状态，保证键盘可用
        sessionController.restorePersistedSchemaOptions()
        updateUI()
        Log.d(TAG, "initRimeEngine: Rime engine initialized successfully")
    }

    /** initRimeEngine 的 watchdog 段：190s 后强制清除加载态，避免 native 卡死时用户永久停在加载页。 */
    private fun startInitWatchdog() {
    // Watchdog: force-clear loading state after 190s
    // withTimeout cannot cancel native JNI calls; if rimeEngine.initialize() hangs
    // in librime, the IO coroutine would block forever. This watchdog ensures the
    // user is never permanently stuck on the loading screen.
    // 首次编译最多等 120s + ensureSession 60s + 10s 缓冲
    serviceScope.launch(Dispatchers.Main) {
        delay(190_000L)
        if (uiState.value.isDeploying) {
            FileLogger.w(TAG, "initRimeEngine: Watchdog triggered - native init appears stuck, forcing loading state cleared")
            uiState.value = uiState.value.copy(
                isDeploying = false,
                deploymentMessage = "初始化超时，请重启输入法"
            )
        }
    }

    }
    // onStartInput 的日志级别段：把持久化的日志级别同步到引擎与文件日志。
    private fun syncVerboseLoggingToEngine() {
        // 跨进程同步日志级别（级别在主进程设置页或 xime.yaml 中确定）。
        // 同步到 native：控制 rime JNI 的按键/候选 logcat 日志（tag XimeRime），
        // postRimeJob 在 key-processing 线程执行，避免主线程等 rimeLock。
        // 用 LogLevelResolver：它统一处理「界面覆盖 → xime.yaml → 默认」的优先级，
        // 保证这里与设置页回显永远是同一个判据。
        val level = LogLevelResolver.resolve(this)
        FileLogger.setLogLevel(level)
        val verboseForEngine = level.allows(LogLevel.INFO)
        keyRouter.postRimeJob { rimeEngine.setVerboseLogging(verboseForEngine) }
    }

    // onStartInput 的方案恢复主块：引擎已初始化且不在部署时，按「手写方案 / savedSchema
    // 可用 / 已编译 / 退回首可用」四档确定方案并切换，随后刷新方案名、恢复方案选项并刷新 UI。
    // 部署进行中则跳过切换以避免主线程等 rimeLock（60MB 词库编译可达 30s+，会 ANR）。
    private fun restoreSchemaOnStartInput(restarting: Boolean) {
        // 部署/全量编译进行中：不执行 schema 切换（switchSchema 会等待 rimeLock，
        // 60MB 词库编译可达 30s+，主线程等待会导致 ANR）。部署完成后
        // initRimeEngine 的流程会自动切换到正确方案，这里只做 UI 状态恢复。
        if (!rimeEngine.isMaintaining()) {
            val savedSchema = SettingsPreferences.getCurrentSchema(this)
            val currentSchema = rimeEngine.getCurrentSchema()
            val availableSchemas = rimeEngine.getAvailableSchemas()
            debugLog("onStartInput: saved=$savedSchema, current=$currentSchema, available=${availableSchemas.joinToString()}")
                
            val actualSchema: String
            when {
                isHandwritingSchema(savedSchema) -> {
                    debugLog("onStartInput: saved schema is handwriting, checking model files")
                    val hwDir = com.kingzcheung.xime.model.ModelStorage.getModelDir(this, "ochwpro")
                    com.kingzcheung.xime.model.ModelStorage.migrateLegacyForModel(this, "ochwpro")
                    val modelFile = java.io.File(hwDir, "ochwpro.onnx")
                    val charIndexFile = java.io.File(hwDir, "char_index.json")
                    if (!modelFile.exists() || !charIndexFile.exists()) {
                        FileLogger.w(TAG, "Handwriting model not found, falling back to first available schema")
                        android.widget.Toast.makeText(
                            this, "请先下载手写模型", android.widget.Toast.LENGTH_LONG
                        ).show()
                        val fallbackSchema = if (availableSchemas.isNotEmpty()) {
                            availableSchemas.first()
                        } else {
                            savedSchema
                        }
                        schemaController.applyPageSizeSetting(fallbackSchema)
                        rimeEngine.switchSchema(fallbackSchema)
                        SettingsPreferences.setCurrentSchema(this, fallbackSchema)
                        actualSchema = fallbackSchema
                    } else {
                        debugLog("onStartInput: saved schema is handwriting, keeping handwriting mode")
                        keyboardViewModel.switchMain(com.kingzcheung.xime.keyboard.MainType.HANDWRITING)
                        // 手写模型按"用键盘时加载"管理：不在此加载/重载，
                        // 布局创建（LaunchedEffect）与落笔时的 predict 自愈兜底
                        actualSchema = savedSchema
                    }
                }
                savedSchema in availableSchemas -> {
                    if (savedSchema != currentSchema) {
                        debugLog("onStartInput: Switching to saved schema: $savedSchema")
                        schemaController.applyPageSizeSetting(savedSchema)
                        rimeEngine.switchSchema(savedSchema)
                    } else {
                        // schema 已匹配：不再重复 switchSchema。
                        // 旧实现为了「确保 processor 完全初始化」每次都重切，但 switchSchema 会
                        // 阻塞取 rimeLock，并在锁内写 custom.yaml —— 每个输入框的首帧都在等这把锁。
                        // 引擎已加载该方案时 processors 本来就绪，重切是纯浪费。
                        debugLog("onStartInput: Schema already matches, skip re-switch")
                    }
                    actualSchema = savedSchema
                }
                SchemaManager.isSchemaCompiled(this@XimeInputMethodService, savedSchema) -> {
                    debugLog("onStartInput: Schema compiled but not in get_schema_list, switching anyway")
                    schemaController.applyPageSizeSetting(savedSchema)
                    rimeEngine.switchSchema(savedSchema)
                    actualSchema = savedSchema
                }
                availableSchemas.isNotEmpty() -> {
                    val fallbackSchema = availableSchemas.first()
                    debugLog("onStartInput: savedSchema '$savedSchema' not available, falling back to '$fallbackSchema'")
                    schemaController.applyPageSizeSetting(fallbackSchema)
                    rimeEngine.switchSchema(fallbackSchema)
                    SettingsPreferences.setCurrentSchema(this, fallbackSchema)
                    actualSchema = fallbackSchema
                }
                else -> actualSchema = savedSchema
            }
            sessionController.updateSchemaName()
            // 方案可能已变化：刷新组合生词查重用的用户词库词集合（异步，失败静默）
            refreshSchemeDictWords()
                
            // 从 user.yaml 恢复方案选项（中/西、简/繁等，含 ascii_mode）
            sessionController.restorePersistedSchemaOptions()
            updateUI()
        } else {
            debugLog("onStartInput: deployment in progress, skipping schema switch")
        }
    }

    // onStartInput 的键盘布局重置段：ascii 确定性决策 + 纯数字输入框自动进数字面板。
    private fun resetKeyboardOnStartInput(attribute: EditorInfo?, restarting: Boolean) {
    // 重置键盘布局到初始状态，避免切换应用后仍残留之前的布局（如英文、数字、符号）。
    // 必须携带当前 schemaId，否则 T9/笔画等专用布局会被错误重置为默认全键盘。
    // restarting=true 表示同一输入会话内的状态刷新（应用 restartInput），此时不应
    // 重置布局，否则数字/符号面板会在输入中被切回全键盘。
    if (RimeEngine.isInitialized() && !restarting) {
        // ascii 确定性决策：默认中文（英文态不跨收起存活），密码框临时英文。
        // 不读引擎当前值——上次会话收起时的落点不再决定本次初始状态
        val startAscii = asciiModeController.applyStartDecision(attribute)
        FileLogger.i(TAG, "onStartInput: reset keyboard, startAscii=$startAscii")
        uiState.value = uiState.value.copy(isAsciiMode = startAscii)
        // currentSchemaId 为空（如引擎重建后 updateSchemaName 尚未完成）时，
        // 用持久化方案兜底，避免布局退化为 26 键全键盘
        val schemaId = uiState.value.currentSchemaId
            .ifBlank { SettingsPreferences.getCurrentSchema(this) }
        // 纯数字输入框（号码/验证码等）自动进入数字面板，可由设置关闭
        val forceNumberPanel = EditorInfoClassifier.isNumberEditor(attribute) &&
            SettingsPreferences.isAutoNumberKeyboardEnabled(this)
        // 用 FileLogger（release 也写）：数字/电话框的 inputType 是排查
        // 「手机号框进不了中文」「内容闪烁」的关键，debugLog 只在 debug 版可见。
        FileLogger.i(
            TAG,
            "onStartInput: inputType=0x${Integer.toHexString(attribute?.inputType ?: 0)}" +
                " cls=0x${Integer.toHexString((attribute?.inputType ?: 0) and android.text.InputType.TYPE_MASK_CLASS)}" +
                " restricted=$editorRestricted forceNumberPanel=$forceNumberPanel" +
                " autoNumKeyboard=${SettingsPreferences.isAutoNumberKeyboardEnabled(this)}",
        )
        keyboardViewModel.resetKeyboard(startAscii, schemaId, forceNumberPanel)
    } else {
        val rimeAscii = if (RimeEngine.isInitialized()) rimeEngine.isAsciiMode() else "n/a"
        FileLogger.i(TAG, "onStartInput: skip keyboard reset, restarting=$restarting, rimeAscii=$rimeAscii, ui=${uiState.value.isAsciiMode}")
    }
    }

    // onStartInput 的剪贴板段：填充最近剪贴板候选并启动其变更监听。
    /**
     * 剪贴板是否**允许**占据候选栏。
     *
     * 只判两件事：用户正在组字（有编码/组合态）时绝不让路；候选栏不是空闲态（已是
     * 剪贴板展示）时由 [showClipboardCandidates] 自己判断是否刷新。
     *
     * 为什么需要这条闸门：旧实现只要剪贴板一变就无条件覆盖候选栏并把 isComposing 置
     * false，用户正打字时突然被替换成剪贴板条目，表现为「复制的文字/图片反复出现在
     * 候选栏」（每复制一次、每进一个输入框都来一次）。
     */
    private fun canShowClipboardInCandidateBar(): Boolean {
        val cs = candidateState.value
        // 正在组字（有编码或组合态）／有联想候选 → 剪贴板不得抢占
        if (cs.isComposing) return false
        if (cs.inputText.isNotEmpty()) return false
        return true
    }

    /** 把 [items] 写进候选栏剪贴板展示态（空则退回空闲态）。调用方须已过 [canShowClipboardInCandidateBar]。 */
    private fun showClipboardCandidates(items: List<com.kingzcheung.xime.clipboard.ClipboardItem>) {
        recentClipboardItemsState.value = items
        // 记录当前展示的条目 id，供按键输入后统一标记消费
        synchronized(showingClipboardIds) {
            showingClipboardIds.clear()
            items.forEach { showingClipboardIds.add(it.id) }
        }
        if (items.isNotEmpty()) {
            candidateState.value = candidateState.value.copy(
                candidates = items.map { it.text },
                candidateComments = emptyList(),
                inputText = "",
                isComposing = false,
                associationCandidates = emptyList(),
                isShowingRecentClipboard = true,
            )
        } else if (candidateState.value.isShowingRecentClipboard) {
            candidateState.value = candidateState.value.copy(
                candidates = emptyList(),
                candidateComments = emptyList(),
                isShowingRecentClipboard = false,
                candidateActions = emptyList(),
            )
        }
    }

    /**
     * 建立剪贴板候选监听。
     *
     * 展示规则（用户明确）：
     * 1. 复制后的条目在**每次进入输入框**都展示（含图片），直到被消费；
     * 2. 用户**按键盘产生输入**后才消失——由 [markShowingClipboardConsumed] 统一标记；
     * 3. 只切应用、没敲键盘 → 下次进输入框仍展示。
     */
    private fun startClipboardCandidatesCollection() {
    ensureClipboardManagerInitialized()
    try {
        val items = clipboardManager.getRecentItems(30)
        recentClipboardItemsState.value = items
        if (canShowClipboardInCandidateBar()) showClipboardCandidates(items)
    } catch (e: Exception) {
        FileLogger.e(TAG, "Failed to get recent clipboard items", e)
    }

    clipboardCollectorJob?.cancel()
    clipboardCollectorJob = serviceScope.launch {
        clipboardManager.clipboardItems.collect { _ ->
            val items = clipboardManager.getRecentItems(30)
            recentClipboardItemsState.value = items
            // 新复制内容 / 状态刷新：候选栏空闲时展示（正在打字则不抢占）
            if (!canShowClipboardInCandidateBar()) return@collect
            showClipboardCandidates(items)
        }
    }
    }

    /**
     * 把验证码从候选栏撤下（键盘任意按键点击后调用，用户 2026-11 规则）。
     *
     * 只摘掉 [otpCodes] 里的文本，别的联想候选不动；主线程外的调用切主线程执行。
     * 展示侧同时由 OtpCollector 挡住再注入（见 isOtpDisplayable），两处配合保证
     * 「按过键就不再显示」。
     */
    internal fun dismissOtpCandidates(otpCodes: List<String>) {
        if (otpCodes.isEmpty()) return
        val apply = {
            val cs = candidateState.value
            val kept = cs.associationCandidates.filterNot { it in otpCodes }
            if (kept.size != cs.associationCandidates.size) {
                candidateState.value = cs.copy(associationCandidates = kept)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else mainHandler.post(apply)
    }

    /**
     * 候选栏当前展示的剪贴板首条（未展示/无条目返回 null）。
     *
     * 供空格键上屏用：调用方必须在 [markShowingClipboardConsumed] **之前**取快照，
     * 否则条目已被标记消费、候选栏被清空，空格就退化成插入空格字符。
     */
    internal fun firstVisibleClipboardItem(): com.kingzcheung.xime.clipboard.ClipboardItem? {
        val items = recentClipboardItemsState.value
        return if (shouldSpaceCommitClipboard(candidateState.value.isShowingRecentClipboard, items.size)) {
            items.first()
        } else {
            null
        }
    }

    /**
     * 用户按键盘产生了实际输入：把当前正展示的剪贴板条目全部标记消费。
     *
     * 调用点在按键主路径（见 ImeKeyRouter）——只在**真正输入字符**时触发，
     * 切换应用/展开面板不会调到这里，因此不会误清。
     */
    internal fun markShowingClipboardConsumed() {
        val ids = synchronized(showingClipboardIds) { showingClipboardIds.toList() }
        if (ids.isEmpty()) return
        showingClipboardIds.clear()
        ids.forEach { clipboardManager.markConsumedById(it) }
    }

    internal fun notifyDeploymentStatus(isDeploying: Boolean, message: String) {
        serviceScope.launch(Dispatchers.Main) {
            uiState.value = uiState.value.copy(
                isDeploying = isDeploying,
                deploymentMessage = message
            )
        }
    }
    
    private fun initClipboardManager() {
        Log.d(TAG, "initClipboardManager: Starting initialization...")
        try {
            clipboardManager = ClipboardManager.getInstance(this)
            clipboardItemsState.value = clipboardManager.clipboardItems.value
            quickSendItemsState.value = clipboardManager.quickSendItems.value

            serviceScope.launch {
                clipboardManager.clipboardItems.collect { items ->
                    clipboardItemsState.value = items
                }
            }
            serviceScope.launch {
                clipboardManager.quickSendItems.collect { items ->
                    quickSendItemsState.value = items
                    // 快捷发送列表变更 → 插件事件（仅投递给 manifest 声明
                    // capabilities.events 含 quick_send_changed 的插件）
                    PluginManager.dispatchEvent(
                        com.kingzcheung.xime.plugin.core.js.PluginEvent(
                            com.kingzcheung.xime.plugin.core.js.PluginEvent.TYPE_QUICK_SEND_CHANGED,
                            mapOf(com.kingzcheung.xime.plugin.core.js.PluginEvent.FIELD_COUNT to items.size)
                        )
                    )
                }
            }
            startClipboardSyncIfEnabled()
            serviceScope.launch {
                PluginManager.pluginInstancesFlow.collect {
                    updateClipboardSync()
                    refreshToolbarPluginButtons()
                    closeToolPanelIfPluginGone()
                }
            }
            Log.d(TAG, "initClipboardManager: Clipboard manager initialized successfully")
        } catch (e: Exception) {
            FileLogger.e(TAG, "initClipboardManager: Failed to initialize clipboard manager", e)
        }
    }

    private fun startClipboardSyncIfEnabled() {
        if (clipboardSyncBridge != null) return
        try {
            if (!SettingsPreferences.isClipboardSyncEnabled(this)) {
                Log.d(TAG, "Clipboard sync disabled in settings")
                return
            }
            val enabled = ExtensionManager.getEnabledClipboardSyncPlugins(this)
            if (enabled.isEmpty()) return
            val preferredId = SettingsPreferences.getClipboardSyncPluginId(this)
            // 与插件管理页共用同一判定规则（ActivePluginSelection），避免"引擎在跑、页面显示未使用"
            val resolvedId = ActivePluginSelection.resolve(preferredId, enabled.map { it.first })
            val selected = enabled.firstOrNull { it.first == resolvedId } ?: enabled.first()
            // 偏好为空或指向未启用插件时回填实际选中项：让状态收敛，而不是长期并存两种"真相"
            if (resolvedId != preferredId) {
                SettingsPreferences.setClipboardSyncPluginId(this, selected.first)
                Log.d(TAG, "Clipboard sync plugin id resolved: '$preferredId' -> '${selected.first}'")
            }
            // 能力声明校验：未声明同步协议的插件不启动（manifest.capabilities.clipboard_sync.protocols）
            val clipboardSyncCapabilities = ExtensionManager.getAllInstalledPlugins()
                .firstOrNull { it.id == selected.first }
                ?.capabilities?.clipboardSync
            if (clipboardSyncCapabilities?.protocols.isNullOrEmpty()) {
                FileLogger.w(TAG, "Clipboard sync plugin ${selected.first} 未声明同步协议，拒绝启动")
                return
            }
            val plugin = selected.second
            // 拉取间隔由所选插件的配置提供（插件 settings.schema 声明 pull_interval_seconds），
            // 每次拉取节流时动态读取，插件设置修改后即时生效
            val syncConfigStore = PluginManager.configStoreFactory
                .create(applicationContext as android.app.Application, selected.first)
            clipboardSyncBridge = ClipboardSyncBridge(
                clipboardManager,
                plugin,
                pluginId = selected.first,
                // 未声明 attachments 的插件自动降级为仅文本同步（图片不推送、远端图片不落盘）
                supportsAttachments = clipboardSyncCapabilities.attachments,
                pullIntervalSeconds = {
                    syncConfigStore.get(ClipboardSyncBridge.CONFIG_KEY_PULL_INTERVAL_SECONDS)
                }
            )
            clipboardSyncBridge?.start()
            uiState.value = uiState.value.copy(clipboardSyncEnabled = true)
            Log.d(TAG, "Clipboard sync started: ${selected.first}")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to start clipboard sync", e)
        }
    }

    private fun stopClipboardSync() {
        clipboardSyncBridge?.release()
        clipboardSyncBridge = null
        uiState.value = uiState.value.copy(clipboardSyncEnabled = false)
    }

    /** 剪贴板同步设置或插件状态变化时调用，按条件动态启停。 */
    private fun updateClipboardSync() {
        if (!::clipboardManager.isInitialized) return
        if (clipboardSyncBridge == null) {
            startClipboardSyncIfEnabled()
            return
        }
        if (
            !SettingsPreferences.isClipboardSyncEnabled(this) ||
            ExtensionManager.getEnabledClipboardSyncPlugins(this).isEmpty()
        ) {
            stopClipboardSync()
            return
        }
        // 当前 bridge 使用的插件与偏好选中的插件不一致时，重启切换到偏好插件
        val enabled = ExtensionManager.getEnabledClipboardSyncPlugins(this)
        val preferredId = SettingsPreferences.getClipboardSyncPluginId(this)
        // 与 startClipboardSyncIfEnabled 同一解析规则（含"首个已启用项"回退）
        val resolvedId = ActivePluginSelection.resolve(preferredId, enabled.map { it.first })
        val shouldUse = enabled.firstOrNull { it.first == resolvedId } ?: return
        if (resolvedId != preferredId) SettingsPreferences.setClipboardSyncPluginId(this, resolvedId)
        // 能力声明也会随插件热更新变化（典型：插件从"仅文本"升级到声明 attachments）：
        // 能力变了必须重建 bridge，否则会一直沿用旧能力（表现成"图片永远不同步"）
        val attachments = ExtensionManager.getAllInstalledPlugins()
            .firstOrNull { it.id == shouldUse.first }
            ?.capabilities?.clipboardSync?.attachments == true
        if (shouldUse.first != clipboardSyncBridge?.pluginId ||
            attachments != clipboardSyncBridge?.supportsAttachments
        ) {
            stopClipboardSync()
            startClipboardSyncIfEnabled()
        }
    }

    /**
     * 刷新已启用插件声明的工具栏按钮到 uiState（插件启用/加载/卸载变化时调用）。
     * 两层显示控制的第一层：只有启用插件的按钮进入候选池；toolbar_buttons 偏好决定最终显示。
     */
    private fun refreshToolbarPluginButtons() {
        serviceScope.launch(Dispatchers.IO) {
            val buttons = ExtensionManager.getAllInstalledPlugins()
                .filter { SettingsPreferences.isPluginEnabled(this@XimeInputMethodService, it.id) }
                .flatMap { info ->
                    info.toolbarButtons.map { btn ->
                        ToolbarButtonItem.Plugin(
                            id = btn.id,
                            label = btn.label.ifBlank { btn.id },
                            icon = ExtensionManager.extractToolbarButtonIcon(
                                this@XimeInputMethodService, info.id, info, btn.icon
                            ) ?: ExtensionManager.extractPluginManifestIcon(this@XimeInputMethodService, info),
                            pluginId = info.id,
                            action = btn.action,
                        )
                    }
                }
            withContext(Dispatchers.Main) {
                uiState.value = uiState.value.copy(toolbarPluginButtons = buttons)
            }
        }
    }

    /** 面板打开时记录的输入框选区起止（上屏前恢复以替换原选区）。 */
    private var toolPanelSelection: Pair<Int, Int>? = null

    /** 面板生成轮询任务（流式生成期间持续刷新候选）。 */
    private var toolPanelPollJob: Job? = null

    /** 面板 loading 延迟显示任务：快速动作（互换语言等）不闪进度条、面板高度不跳动。 */
    private var toolPanelLoadingJob: Job? = null

    /**
     * 延迟显示面板 loading 指示（标题栏小圈）：阈值内完成的快速动作（本地互换、快速插件）
     * 全程不显示，避免指示器闪烁；慢动作（网络类 onAction / 生成）超时后照常反馈。
     * 面板重开（epoch 递增）后过期自动作废。
     */
    private fun scheduleToolPanelLoading(epoch: Long) {
        toolPanelLoadingJob?.cancel()
        toolPanelLoadingJob = serviceScope.launch {
            delay(TOOL_PANEL_LOADING_SHOW_DELAY_MS)
            if (uiState.value.toolPanelRequestEpoch == epoch && uiState.value.toolPanelVisible) {
                uiState.value = uiState.value.copy(toolPanelLoading = true)
            }
        }
    }

    /**
     * 插件工具栏按钮 action=open_panel 的宿主入口：打开该插件的通用面板。
     * 记录选区、按优先级收集上下文预填、向插件取初始面板状态并渲染。
     */
    internal fun openToolPanel(pluginId: String) {
        if (uiState.value.isFloatingMode ||
            resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        ) {
            Toast.makeText(this, "工具面板不支持在悬浮/横屏模式下使用", Toast.LENGTH_SHORT).show()
            return
        }
        if (uiState.value.showQuickSendForm) {
            uiState.value = uiState.value.copy(
                showQuickSendForm = false,
                quickSendFormFocused = false,
                quickSendCodeFocused = false,
                quickSendEditingItemId = null,
                quickSendEditingItemText = "",
                quickSendEditingItemCode = "",
            )
            QuickSendFormEditTextHolder.editText = null
            QuickSendFormCodeEditTextHolder.editText = null
        }
        // 打开面板前清理宿主输入框残留输入态（未上屏的拼音/英文），
        // 避免旧组合混入面板输入、或面板关闭后覆盖宿主输入框中段文字。
        val pending = candidateState.value
        if (pending.isComposing || pending.inputText.isNotEmpty() || pending.pendingEnglishText.isNotEmpty()) {
            rimeEngine.clearComposition()
            endComposingInputBox()
            candidateState.value = candidateState.value.copy(
                candidates = emptyList(),
                candidateComments = emptyList(),
                associationCandidates = emptyList(),
                pendingEnglishText = "",
                inputText = "",
                candidateActions = emptyList(),
                preeditText = "",
                isComposing = false,
                isShowingRecentClipboard = false,
                hasNextPage = false,
                hasPrevPage = false
            )
        }
        toolPanelSelection = readCurrentSelection()
        // 每次打开都是新会话：清空用户编辑标记，允许宿主回填一次 prefill
        ToolPanelEditTextHolder.userEdited = false
        val contextText = collectToolPanelContext()
        val pluginName = ExtensionManager.getAllInstalledPlugins()
            .firstOrNull { it.id == pluginId }?.name ?: pluginId
        val display = ExtensionManager.getAllInstalledPlugins()
            .firstOrNull { it.id == pluginId }?.capabilities?.tool?.display
        val epoch = uiState.value.toolPanelRequestEpoch + 1
        uiState.value = uiState.value.copy(
            toolPanelVisible = true,
            toolPanelInputFocused = display != ToolResult.PASSIVE,
            toolPanelPluginId = pluginId,
            toolPanelTitle = pluginName,
            toolPanelPrefillText = contextText,
            toolPanelItems = emptyList(),
            toolPanelDisplay = display?.name,
            toolPanelUiNodes = null,
            toolPanelLoading = false,
            toolPanelRequestEpoch = epoch,
            enterKeyText = if (display == ToolResult.PASSIVE) "发送" else "生成",
        )
        scheduleToolPanelLoading(epoch)
        serviceScope.launch(Dispatchers.IO) {
            // runCatching：插件异常（超时/中毒/网络失败）时不卡 loading，面板恢复可交互
            val state = runCatching {
                (ExtensionManager.getPluginById(pluginId) as? ToolPlugin)
                    ?.getPanelState(contextText)
            }.getOrNull()
            // inputText 契约：插件未返回 = 沿用上下文（适配器已解析）；
            // 返回空串 = 插件明确要求空输入框（如翻译插件拒绝剪贴板预填）
            val prefill = state?.inputText ?: contextText
            withContext(Dispatchers.Main) {
                if (uiState.value.toolPanelRequestEpoch != epoch || !uiState.value.toolPanelVisible) {
                    return@withContext
                }
                toolPanelLoadingJob?.cancel()
                uiState.value = uiState.value.copy(
                    toolPanelPrefillText = prefill,
                    toolPanelItems = state?.items ?: emptyList(),
                    toolPanelUiNodes = state?.ui,
                    toolPanelLoading = false,
                )
            }
        }
        if (display == ToolResult.PASSIVE) {
            // 纯展示面板与表情/符号同级：Overlay 全屏覆盖键盘，不撑高候选栏上方区域
            keyboardViewModel.showOverlay(OverlayRoute.ToolPanel)
        }
    }

    /**
     * 面板 action（InfoPanel 按钮 / direct 控件行按钮，如互换语言）：通知插件后单次重拉
     * getPanelState 刷新 ui 节点（action 改变数据后面板立即反映）。
     * 先置 loading 再执行：同步生成（插件 onPanelAction 阻塞返回）期间面板显示加载态。
     * 上下文与打开面板/生成时同口径（最近一次 prefill），避免 ai-reply 这类
     * "inputText 变化即重置缓存"的插件在按钮动作后被空上下文清状态。
     */
    internal fun dispatchToolPanelAction(actionId: String) {
        val pluginId = uiState.value.toolPanelPluginId
        val epoch = uiState.value.toolPanelRequestEpoch
        val contextText = uiState.value.toolPanelPrefillText
        // 延迟显示 loading：本地快动作（如互换语言）不闪指示器
        scheduleToolPanelLoading(epoch)
        serviceScope.launch(Dispatchers.IO) {
            val plugin = ExtensionManager.getPluginById(pluginId) as? ToolPlugin ?: return@launch
            // runCatching：插件异常（超时/中毒）时不卡 loading，面板恢复可交互
            val state = runCatching {
                plugin.onPanelInput("", contextText)
                plugin.onPanelAction(actionId)
                plugin.getPanelState(contextText)
            }.getOrNull()
            toolPanelLoadingJob?.cancel()
            withContext(Dispatchers.Main) {
                if (uiState.value.toolPanelRequestEpoch == epoch && uiState.value.toolPanelVisible) {
                    uiState.value = uiState.value.copy(
                        toolPanelUiNodes = state?.ui,
                        toolPanelItems = state?.items ?: uiState.value.toolPanelItems,
                        toolPanelLoading = state?.loading ?: false,
                    )
                }
            }
        }
    }

    /**
     * 控件行字段变更（文本输入/下拉选择）：实时通知插件（key = ui 节点 key）。
     * 插件自行保存状态，宿主不代存；UI 同步返回，插件侧由 JsScriptRuntime 全量捕获异常。
     */
    internal fun onToolPanelFieldInput(key: String, value: String) {
        val pluginId = uiState.value.toolPanelPluginId
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                (ExtensionManager.getPluginById(pluginId) as? ToolPlugin)?.onPanelInput(key, value)
            }
        }
    }

    internal fun closeToolPanel() {        toolPanelSelection = null
        stopToolPanelPoll()
        // 面板相关 Overlay 页面（ToolPanel 纯展示页）开着时联动关闭
        val page = keyboardViewModel.page.value
        if (page is com.kingzcheung.xime.keyboard.KeyboardPage.Overlay &&
            page.route == OverlayRoute.ToolPanel
        ) {
            keyboardViewModel.closeOverlay()
        }
        uiState.value = uiState.value.copy(
            toolPanelVisible = false,
            toolPanelInputFocused = false,
            toolPanelPluginId = "",
            toolPanelTitle = "",
            toolPanelPrefillText = "",
            toolPanelItems = emptyList(),
            toolPanelDisplay = null,
            toolPanelUiNodes = null,
            toolPanelLoading = false,
            enterKeyText = "发送",
        )
        ToolPanelEditTextHolder.editText = null
        ToolPanelEditTextHolder.main = null
        ToolPanelEditTextHolder.userEdited = false
        toolPanelLoadingJob?.cancel()
    }

    /**
     * 面板候选条目上屏：有选区时先恢复选区再提交（替换原选区），无选区时光标处追加。
     * 直接经 InputConnection 提交，不走 commitText 重定向（避免注入回面板输入框）。
     */
    internal fun commitToolPanelItem(text: String) {
        val pluginId = uiState.value.toolPanelPluginId
        val itemId = uiState.value.toolPanelItems.firstOrNull { it.text == text }?.id ?: text
        serviceScope.launch(Dispatchers.IO) {
            (ExtensionManager.getPluginById(pluginId) as? ToolPlugin)?.onPanelItemClick(itemId)
        }
        val ic = currentInputConnection ?: return
        toolPanelSelection?.let { (start, end) ->
            runCatching { ic.setSelection(start, end) }
        }
        ic.commitText(text, 1)
        if (isChineseMode) {
            predictionManager.appendCommittedText(text)
            predictionManager.recordInput(text)
        }
        // 工具面板上屏同样是真实一笔，记入单次上屏供换行键上滑重复
        rememberCommitChunk(text)
        closeToolPanel()
    }

    /**
     * 面板触发生成：通知插件输入变化并触发 generate，异步轮询取回最新面板状态。
     * 同步生成（智能回复）阻塞返回后一次取回；流式生成（帮写）期间插件 loading=true，
     * 宿主持续轮询刷新，直到 loading 结束或面板关闭。
     * 代际号防旧结果回填：新请求/重开会递增 epoch，旧结果回来时检测到已过期则丢弃。
     */
    internal fun triggerToolPanelGenerate() {
        val pluginId = uiState.value.toolPanelPluginId
        // passive 纯展示面板：无生成语义，enter 不触发（数据由事件驱动 + InfoPanel 点击 action 刷新）
        if (uiState.value.toolPanelDisplay == ToolResult.PASSIVE.name) {
            return
        }
        // 使用前授权检测：插件有未授权网络域名时先引导授权，不发起请求
        if (!com.kingzcheung.xime.plugin.PluginNetworkAuthHelper.ensureAuthorized(this, pluginId)) {
            return
        }
        // 生成取词固定读主输入框（控件行字段聚焦时按键路由指向字段本身，不影响生成上下文）
        val inputText = ToolPanelEditTextHolder.main?.text?.toString() ?: ""
        // 捕获当前代际号（openToolPanel 时递增）。轮询期间持续对比：
        // 面板被重新打开（epoch 递增）即视为过期，丢弃本轮结果。
        val epoch = uiState.value.toolPanelRequestEpoch
        val epochStartTime = System.currentTimeMillis()
        toolPanelPollJob?.cancel()
        toolPanelPollJob = serviceScope.launch(Dispatchers.IO) {
            val plugin = ExtensionManager.getPluginById(pluginId) as? ToolPlugin
            plugin?.onPanelInput("", inputText)
            plugin?.onPanelAction("generate")
            while (uiState.value.toolPanelVisible) {
                val state = plugin?.getPanelState(inputText) ?: break
                val items = state.items
                val loading = state.loading
                withContext(Dispatchers.Main) {
                    if (uiState.value.toolPanelRequestEpoch == epoch) {
                        // 轮询接管 loading（插件报告），取消尚未触发的延迟显示
                        toolPanelLoadingJob?.cancel()
                        uiState.value = uiState.value.copy(
                            toolPanelItems = items,
                            toolPanelLoading = loading,
                        )
                    }
                }
                if (!loading) break
                delay(200)
            }
            // 结果交互：生成结束后按结果显示方式决策——
            //   display=DIRECT → 直接上屏替换选区并关闭面板（AI 翻译/帮写等单结果场景，无需点击）
            //   display=PASSIVE → 面板本身全屏展示 items（InfoPanel 点选上屏），无需分派
            //   display=null（未声明）→ 直接上屏（旧行为为按数量兜底开页面，页面已并入 passive 面板）
            //   空结果 → 保持面板（用户可重新生成）
            withContext(Dispatchers.Main) {
                if (uiState.value.toolPanelVisible) {
                    val items = uiState.value.toolPanelItems
                    when {
                        items.isEmpty() -> {
                            // 空结果：静默失败。原因已记录到 PluginErrorLog
                            // （插件中心错误弹窗 / 设置→日志查看器可查看），不打断用户。
                            com.kingzcheung.xime.plugin.core.security.PluginErrorLog
                                .getLastError(pluginId)
                                ?.let { lastError ->
                                    FileLogger.w(
                                        TAG,
                                        "tool panel generate empty result, plugin error: ${
                                            com.kingzcheung.xime.plugin.core.security.PluginErrorLog
                                                .userMessage(lastError)
                                        } ${lastError.message}"
                                    )
                                }
                        }
                        else -> commitToolPanelItem(items[0].text)
                    }
                }
            }
        }
    }

    private fun stopToolPanelPoll() {
        toolPanelPollJob?.cancel()
        toolPanelPollJob = null
    }

    /**
     * 面板所属插件被禁用/卸载时自动关闭面板（工具栏按钮候选池同步移除，
     * toolbar_buttons 偏好中残留 id 匹配不到自然不显示，无需清理偏好）。
     */
    private fun closeToolPanelIfPluginGone() {
        val state = uiState.value
        if (!state.toolPanelVisible) return
        val pluginId = state.toolPanelPluginId
        if (pluginId.isBlank()) return
        val stillEnabled = ExtensionManager.getAllInstalledPlugins().any {
            it.id == pluginId && SettingsPreferences.isPluginEnabled(this, it.id)
        }
        if (!stillEnabled) {
            closeToolPanel()
        }
    }

    /** 读取当前输入框选区（起止不等时返回，供上屏替换原选区）。 */
    private fun readCurrentSelection(): Pair<Int, Int>? {
        val ic = currentInputConnection ?: return null
        return runCatching {
            val req = android.view.inputmethod.ExtractedTextRequest()
            val extracted = ic.getExtractedText(req, 0)
            if (extracted != null && extracted.selectionStart >= 0 && extracted.selectionEnd > extracted.selectionStart) {
                Pair(extracted.selectionStart, extracted.selectionEnd)
            } else null
        }.getOrNull()
    }

    /** 收集面板上下文：仅选中文本；未选中时返回空串（不预填输入框全文/剪贴板，待用户自行输入）。 */
    /**
     * 工具面板上下文收集（选区 > 输入框选区 > 剪贴板）：
     * 对方消息通常来自聊天 App 复制而非输入框选区，剪贴板兜底是 AI 回复等
     * 插件拿到上下文的关键路径（插件契约见 plugins/ai-reply/main.ts）。
     */
    private fun collectToolPanelContext(): String {
        val ic = currentInputConnection
        if (ic != null) {
            runCatching {
                val sel = ic.getSelectedText(0)?.toString()
                if (!sel.isNullOrBlank()) return sel
            }
            runCatching {
                val req = android.view.inputmethod.ExtractedTextRequest()
                val extracted = ic.getExtractedText(req, 0)
                if (extracted != null && extracted.selectionStart >= 0 && extracted.selectionEnd > extracted.selectionStart) {
                    val t = extracted.text?.toString()
                    if (t != null) {
                        val s = extracted.selectionStart.coerceIn(0, t.length)
                        val e = extracted.selectionEnd.coerceIn(s, t.length)
                        if (e > s) return t.substring(s, e)
                    }
                }
            }
        }
        // 剪贴板兜底：无选区/无输入连接时取系统剪贴板
        runCatching {
            if (::clipboardManager.isInitialized) {
                clipboardManager.getCurrentClipboardText()?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return ""
    }

    private fun ensureClipboardManagerInitialized() {
        if (!::clipboardManager.isInitialized) {
            Log.d(TAG, "ensureClipboardManagerInitialized: Initializing clipboard manager synchronously")
            try {
                clipboardManager = ClipboardManager.getInstance(this)
                clipboardItemsState.value = clipboardManager.clipboardItems.value
                quickSendItemsState.value = clipboardManager.quickSendItems.value
                Log.d(TAG, "ensureClipboardManagerInitialized: Clipboard manager initialized")
            } catch (e: Exception) {
                FileLogger.e(TAG, "ensureClipboardManagerInitialized: Failed to initialize clipboard manager", e)
            }
        }
    }

    override fun onCreateInputView(): View {
        // 注意：**不要**在这里复用旧的 keyboardContainer。
        // 实测（3.2.81）复用已 detach 的 Compose 容器会让输入法窗口显示失败——键盘直接弹不出来。
        // 视图重建的「闪一下」只能从减少重建诱因（窗口/会话层）去压，不能靠复用一个死视图。
        FileLogger.i(TAG, "onCreateInputView: build keyboard view")
        keyboardContainer = VoiceKeyboardContainer(
            context = this,
            uiStateProvider = { uiState.value },
            onUiStateChanged = { newState -> uiState.value = newState },
            onPerformVibration = { view -> feedbackManager.hapticFeedback(view) },
            onPerformUndo = { pendingVoiceAction = { textCommit.performUndo() } },
            onPerformSearch = { pendingVoiceAction = { textCommit.performSearch() } },
            onStopRecognition = {
                endVoiceSession()
            },
            isRecording = { voiceRecordingStarted },
            setRecording = { voiceRecordingStarted = it },
            onVoiceDismiss = {
                val action = pendingVoiceAction
                pendingVoiceAction = null
                // 滑到左/右按钮抬手＝撤销/发送：动作执行前先封口。
                // 动作=发送时收尾等待中的最终结果会落在发送之后（内容重复/错位）；
                // 动作=撤销时也需先冲刷，撤销按"已上屏文本"计数才准。
                if (action != null) voiceRecognitionHandler.sealPendingForSend()
                action?.invoke()
                endVoiceSession()
            },
            onTouchCancel = {
                uiState.value = uiState.value.copy(
                    swipeCancelEpoch = uiState.value.swipeCancelEpoch + 1
                )
            }
        )
        // 编码气泡绘制在候选栏上方（栏外，drawBehind 负坐标）：insets 重构后容器
        // 物理高度 = Compose 内容总高（gravity BOTTOM），候选栏紧贴容器顶边，
        // 气泡落在容器顶边之外——关闭容器裁剪，气泡才能画进 inputArea 的空白区。
        // 仅影响越界绘制裁剪，不触碰容器高度/insets 计算路径。
        keyboardContainer.clipChildren = false

        bottomInsetPxState.value = getActiveBottomInsetPx(window.window)
        horizontalInsetPxState.value = getActiveHorizontalInsetsPx(window.window)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            keyboardContainer.setOnApplyWindowInsetsListener { v, insets ->
                val px = extractBottomInset(insets)
                if (px != bottomInsetPxState.value) {
                    bottomInsetPxState.value = px
                }
                val h = extractHorizontalInsets(insets)
                if (h != horizontalInsetPxState.value) {
                    horizontalInsetPxState.value = h
                }
                v.onApplyWindowInsets(insets)
            }
        }
        
        val composeView = ComposeView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            composeViewRef = this
            // 键盘调节的半透明遮罩要画到 composeView 顶边之外（键盘上方的应用区域）：
            // ComposeView 默认 clipChildren=true 会把越界内容裁掉，这里必须关闭
            clipChildren = false
            setContent { KeyboardContent() }
         }
        
        keyboardContainer.addView(composeView)

        applyWindowBackground()

        return keyboardContainer
    }

    /**
     * onCreateInputView 的 Compose 内容树：原 setContent 块逐字搬出，
     * 仅整体缩进减 8 空格。作为类内 @Composable 成员可直读服务状态，
     * 无需把 30+ 个派生值逐个传参。
     */
    @Composable
    private fun KeyboardContent() {
        val cand = candidateState.value
        val state = uiState.value
        val page by keyboardViewModel.page.collectAsState(com.kingzcheung.xime.keyboard.KeyboardPage.Main(com.kingzcheung.xime.keyboard.MainType.FULL))
        val isHandwritingMode = (page as? com.kingzcheung.xime.keyboard.KeyboardPage.Main)?.type == com.kingzcheung.xime.keyboard.MainType.HANDWRITING
        val isDarkTheme = isDarkTheme()
        val screenHeightDp = resources.configuration.screenHeightDp
        val physicalScreenDp = (resources.displayMetrics.heightPixels / resources.displayMetrics.density).roundToInt()
        val statusBarHeightDp = tryGetStatusBarHeightDp(this@XimeInputMethodService, window.window)
        val navBarHeightDp = tryGetNavBarHeightDp(this@XimeInputMethodService, window.window)
        val visibleNavBarHeightDp = tryGetVisibleNavBarHeightDp(this@XimeInputMethodService, window.window)
        // 用物理屏幕高度减去状态栏，保证不同 Android 版本一致
        val effectiveScreenH = if (state.isFloatingMode) physicalScreenDp - statusBarHeightDp else screenHeightDp
        val windowVisibleHeightDp = effectiveScreenH
        val navBarAlreadyExcluded = (physicalScreenDp - screenHeightDp) >= (navBarHeightDp + statusBarHeightDp - KeyboardWindowMetrics.NAV_BAR_EXCLUDED_TOLERANCE_DP)
        val floatingMinY = if (navBarAlreadyExcluded) 0 else visibleNavBarHeightDp

        val screenWidthDp = resources.configuration.screenWidthDp
        val screenIsLandscape = screenWidthDp > screenHeightDp
        val portraitScreenHeightDp = if (screenIsLandscape) screenWidthDp else screenHeightDp
        val isLandscape = !state.isFloatingMode && screenIsLandscape
        // 高度一律按屏幕方向取键（悬浮 / 非悬浮同口径）。
        // 悬浮模式不再回退到竖屏键：横屏悬浮的卡片是独立形态，需要自己的高度，
        // 复用竖屏高度会让横屏卡片高得超出屏幕（且与调节、重置的键不一致）。
        val orientationHeight = SettingsPreferences.getKeyboardHeightDp(
            this@XimeInputMethodService, screenIsLandscape, state.isFloatingMode
        )
        val displayHeight = orientationHeight.coerceAtMost((if (state.isFloatingMode) portraitScreenHeightDp else screenHeightDp) * KeyboardWindowMetrics.DISPLAY_HEIGHT_MAX_PERCENT / 100)
        // 调节进行中：预览高度是唯一真源（横竖屏 / 悬浮统一）。
        // 此前横屏硬编码屏高 70%、竖屏走 displayHeight，与 resizePreviewHeightDp 脱节：
        // 悬浮模式卡片实际高 = 高度 × CARD_SCALE + 拖条，若调节时强行用未缩放预览值，
        // 点「确定」后卡片会突降 15%+18dp。统一走 floatingCardContentHeight 公式后，
        // 非悬浮（floatScale=1 / dragBar=0）结果恒等于预览值，行为完全不变。
        val keyboardHeight = if (state.showKeyboardResize) {
            state.resizePreviewHeightDp
        } else if (isHandwritingMode) {
            screenHeightDp / 2
        } else {
            displayHeight
        }
        val floatScale = if (state.isFloatingMode) FloatingCardGeometry.CARD_SCALE else 1f
        val effectiveKeyboardHeight = (keyboardHeight * floatScale).toInt()
        val floatingDragBarHeight = if (state.isFloatingMode) FloatingCardGeometry.DRAG_BAR_HEIGHT_DP else 0
        val floatingCardContentHeight = effectiveKeyboardHeight + floatingDragBarHeight
                
        val density = LocalDensity.current
        // 统一使用 View 层多类型检测的 insets，避免 Compose
        // navigationBars 恒为手势条高度导致与系统栏（三键导航）差异被抹平。
        val activeBottomPx = bottomInsetPxState.value
        val rawDp = if (activeBottomPx > 0) {
            with(density) { activeBottomPx.toDp().value.toInt() }
        } else 0
        // 底部留白整体缩减量（dp）：让键盘比系统导航栏实际高度再低一点，
        // 键盘背景已 edge-to-edge 延伸到系统栏后，留白可小于系统栏高度。
        // 横屏手势区 inset 更大（约 32dp vs 竖屏 16dp），固定减 8 会留下过厚的
        // 底条（24dp，为竖屏 3 倍）；横屏多减一档到 16dp，仍足以盖住手势条。
        val bottomInsetShrinkDp = if (isLandscape) KeyboardWindowMetrics.BOTTOM_INSET_SHRINK_LANDSCAPE_DP else KeyboardWindowMetrics.BOTTOM_INSET_SHRINK_PORTRAIT_DP
        // 标准（三键）导航栏 inset 明显大于手势条，额外多减一点，
        // 让标准模式高度更接近抬高模式，但保留可辨识的差异。
        val extraShrinkDp = if (rawDp >= KeyboardWindowMetrics.STANDARD_NAV_BAR_INSET_THRESHOLD_DP) KeyboardWindowMetrics.STANDARD_NAV_BAR_EXTRA_SHRINK_DP else 0
        val bottomSpaceDp = if (rawDp > 0) (rawDp - bottomInsetShrinkDp - extraShrinkDp).coerceAtLeast(0) else 0
        // 兜底仅用于彻底检测不到任何底部 inset 的场景（全屏沉浸），
        // 不再把已有差异（标准 44dp / 手势 16dp）强行垫平。
        val minBottomDp = KeyboardWindowMetrics.MIN_BOTTOM_SPACE_DP
        val activeBottomDp = if (bottomSpaceDp == 0) minBottomDp else bottomSpaceDp
        val navBarDp = activeBottomDp.dp
        val hasNavBar = navBarDp > 0.dp
        // 左右边衬区：手机上键盘内容（候选栏 logo/收起按钮与按键区）避让挖孔/横屏导航栏；
        // 平板不避让（候选栏 logo/收起按钮靠边，见 CandidateBar）；浮动键盘可拖动，同样不避让。
        val isTabletDevice = com.kingzcheung.xime.ui.isTablet()
        val horizontalInsetDp = with(density) {
            horizontalInsetPxState.value.first.toDp() to horizontalInsetPxState.value.second.toDp()
        }

        // 快捷发送 / 工具面板为"键盘上方的撑高面板"：显示时键盘总高增加面板高度（面板在键盘上方，
        // 不遮键盘按键），同时容器物理高度同步变大（updateHeight）→ IME insets 由系统确定性重算，
        // 关闭后容器还原，彻底避免 insets 残留与白色区域。
        // Overlay 页面（menubar/剪贴板/emoji 等）全屏覆盖键盘内容区：激活期间撑高面板
        // 不参与计算，否则 Overlay 页面会带上表单/工具面板的额外高度（容器整体被撑高）。
        val isOverlayPage = page is com.kingzcheung.xime.keyboard.KeyboardPage.Overlay
        val quickSendFormExtra = if (state.showQuickSendForm && !isOverlayPage) KeyboardWindowMetrics.QUICK_SEND_FORM_EXTRA_HEIGHT_DP else 0
        // 需与 ToolPanel 渲染高度一致（toolPanelHeightDp：内容自适应 + 上限），
        // 否则容器比面板多/少一截，键盘被拉高。
        // PASSIVE 纯展示面板走 Overlay 全屏覆盖（键盘窗口内容区），不撑高。
        val toolPanelExtra = if (state.toolPanelVisible &&
            state.toolPanelDisplay != "PASSIVE" &&
            !isOverlayPage
        ) {
            com.kingzcheung.xime.ui.keyboard.toolPanelHeightDp(
                hasControls = !state.toolPanelUiNodes.isNullOrEmpty(),
            )
        } else 0
        val overlayPanelExtra = quickSendFormExtra + toolPanelExtra

        XimeTheme(darkTheme = isDarkTheme, themeId = state.themeId) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Sync FrameLayout height with Compose content height
                // 调节中 floatingCardContentHeight 已由 keyboardHeight=预览值 推导，
                // 与非调节同一公式 → 确定前后无跳变
                val contentHeight = floatingCardContentHeight + overlayPanelExtra
                val totalDp = if (state.isCompact || state.isFloatingMode) effectiveScreenH
                    else contentHeight + state.keyboardBottomPaddingDp + activeBottomDp
                SideEffect {
                    // 容器物理高度 = Compose 内容总高（含底部留白），全模式统一。
                    // 容器高度变化 → View 层 relayout → traversal → onComputeInsets
                    // 自动以新高度重算并上报（ViewRootImpl 每次 traversal 都 dispatch
                    // OnComputeInternalInsetsListener，值变化即 setInsets），
                    // 无需 +1dp hack 强制造型变化。
                    // 悬浮模式不在此写卡片高：卡片矩形以 onCardPositioned 实测为唯一
                    // 真源，公式兜底收敛在 FloatingCardGeometry——此前实测值与公式值
                    // 双写同一字段，静止时公式值（偏高约 80–100dp）会覆盖实测值，
                    // 导致触摸区吞掉卡片上方点击、再次拖动时钳制跳位。
                    keyboardContainer.updateHeight(totalDp)
                }
                val kbColors = KeysConfigHelper.getKeyboardColors()
                val longToColor: (Long) -> androidx.compose.ui.graphics.Color = { if (it == 0L)  { androidx.compose.ui.graphics.Color(0xE61E1E1E) } else if (it > 0xFFFFFF) { androidx.compose.ui.graphics.Color(it) } else { androidx.compose.ui.graphics.Color(0xFF000000 or it) } }
                val isDark = isDarkTheme
                val cardBg = if (isDark) longToColor(com.kingzcheung.xime.settings.KeyboardColorsConfig.FALLBACK_BG_DARK) else longToColor(com.kingzcheung.xime.settings.KeyboardColorsConfig.FALLBACK_BG_LIGHT)
                val candidateTextCol = com.kingzcheung.xime.ui.theme.KeyboardThemes.getCandidateTextColorOverride(state.themeId, isDark)
                    ?: if (isDark) longToColor(kbColors.candidateTextColorDark) else longToColor(kbColors.candidateTextColor)
                val accentCol = com.kingzcheung.xime.ui.theme.KeyboardThemes.getAccentColor(state.themeId, isDark)
                val selectedTextCol = com.kingzcheung.xime.ui.theme.KeyboardThemes.getCandidateSelectedTextColor(state.themeId, isDark)
                val keyboardBgColor = cardBg
                val rootTheme = com.kingzcheung.xime.ui.theme.KeyboardThemes.getThemeById(state.themeId)
                if (state.isCompact && (cand.candidates.isNotEmpty() || cand.isShowingRecentClipboard || cand.inputText.isNotEmpty())) {
                    HardwareKeyboardCandidateBar(
                        inputText = cand.inputText,
                        preeditText = cand.preeditText,
                        // 紧凑/浮空候选栏只渲染文本：图片位替换为「图片」标签，
                        // 长度与顺序不变（点选索引仍与 recentClipboardItemsState 对齐）
                        candidates = cand.candidates.mapIndexed { i, text ->
                            if (cand.isShowingRecentClipboard &&
                                recentClipboardItemsState.value.getOrNull(i)?.isImage == true
                            ) {
                                "图片"
                            } else {
                                text
                            }
                        },
                        hasNextPage = cand.hasNextPage,
                        hasPrevPage = cand.hasPrevPage,
                        cursorX = state.cursorX,
                        cursorY = state.cursorY,
                        cursorVisible = state.cursorVisible,
                        highlightIndex = highlightIndex.intValue,
                        cardBackgroundColor = cardBg,
                        candidateTextColor = candidateTextCol,
                        activeColor = accentCol,
                        selectedTextColor = selectedTextCol,
                    )
                } else if (state.isCompact) {
                    Box(modifier = Modifier.fillMaxSize())
                } else {
                // 非浮动：背景与键盘内容同区域，贴底覆盖键盘内容高度 + 底部导航栏留白，
                // 键盘内容通过 offset 上移 activeBottomDp 留出导航栏空间（对齐参考实现 bottomPaddingSpace）。
                // 浮动模式：卡片由 KeyboardView 内部 FloatingKeyboardContainer 自绘背景与定位，此处不做背景/偏移。
                // 键盘宽度调节：左右边距各自独立内收（可整体偏移），背景随内容同宽
                // （调节进行中用预览值，拖动实时跟随；非调节用已保存值）
                val bgGeo = if (state.isFloatingMode) null else geometryState.value
                val keyboardMarginStartDp = bgGeo?.effectiveMarginStart(state.showKeyboardResize) ?: 0
                val keyboardMarginEndDp = bgGeo?.effectiveMarginEnd(state.showKeyboardResize) ?: 0
                if (!state.isFloatingMode) {
                            // 非悬浮：负边距无意义（键盘不能超出屏幕），钳到 0 防崩溃。
                            val bgMarginStart = keyboardMarginStartDp.coerceAtLeast(0)
                            val bgMarginEnd = keyboardMarginEndDp.coerceAtLeast(0)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((floatingCardContentHeight + state.keyboardBottomPaddingDp + overlayPanelExtra + activeBottomDp).dp)
                            .align(androidx.compose.ui.Alignment.BottomCenter)
                    ) {
                        // 背景必须画在「padding 内缩后的内层」，两侧才会透出宿主应用。
                        // 旧写法 fillMaxWidth().padding().keyboardBackground() 的 padding
                        // 在 align 之前被 align 的测量抵消（align 重测 fillMaxWidth 全宽），
                        // 背景仍铺满全宽 —— 表现为「左右缩小后两侧仍是键盘背景色」。
                        // 嵌套后：内层 Box 尺寸 = 全宽 - 左右边距，背景随之内缩。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((floatingCardContentHeight + state.keyboardBottomPaddingDp + overlayPanelExtra + activeBottomDp).dp)
                            .align(androidx.compose.ui.Alignment.BottomCenter)
                    ) {
                        // 背景必须与**内容层完全同口径**内缩，否则两侧边界对不上：
                        // 内容层是 horizontalInset + margin（手机避让挖孔/横屏导航栏），
                        // 背景若只减 margin，横屏左右边衬区不对称时会出现「一侧空隙大、
                        // 一侧空隙小」（实测左边明显更宽）。平板与内容层一致只用 margin。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    start = if (isTabletDevice) bgMarginStart.dp
                                    else (horizontalInsetDp.first + bgMarginStart.dp),
                                    end = if (isTabletDevice) bgMarginEnd.dp
                                    else (horizontalInsetDp.second + bgMarginEnd.dp),
                                )
                                .keyboardBackground(rootTheme.keyboardBackground, isDark, keyboardBgColor)
                        )
                    }
                    }
                }
                Box(
                    modifier = Modifier

                        .fillMaxWidth()
                        .height((floatingCardContentHeight + state.keyboardBottomPaddingDp + overlayPanelExtra).dp)
                        .align(androidx.compose.ui.Alignment.BottomCenter)
                        .then(if (state.isFloatingMode) Modifier else Modifier.offset(y = (-activeBottomDp).dp))
                        .then(
                            // 手机：键盘内容整体避入左右边衬区（挖孔/横屏导航栏）；平板不避让。
                            // 宽度调节的左右边距两种设备都生效（边距为 0 时与原行为一致）
                            if (state.isFloatingMode) Modifier
                            else if (isTabletDevice) Modifier.padding(
                                start = keyboardMarginStartDp.coerceAtLeast(0).dp,
                                end = keyboardMarginEndDp.coerceAtLeast(0).dp,
                            )
                            else Modifier.padding(
                                start = horizontalInsetDp.first + keyboardMarginStartDp.coerceAtLeast(0).dp,
                                end = horizontalInsetDp.second + keyboardMarginEndDp.coerceAtLeast(0).dp,
                            )
                        )
                ) {
                CompositionLocalProvider(LocalStretchFactor provides state.stretchFactor) {
                    // 注意：kbState 只承载键盘按键/布局状态，不承载候选数据。
                    // 候选数据单独通过 candidateState 传给 KeyboardView。
                    // remember 的 key 均为候选无关依赖：候选变化时 kbState 实例保持不变，
                    // KeyboardView（按键区）跳过重组，只有读取 candidateState 的候选栏重组，
                    // 避免长按退格时高频候选更新触发整个键盘重组导致候选栏闪烁。
                    val kbState = remember(
                        state,
                        isDarkTheme,
                        effectiveKeyboardHeight,
                        floatingMinY,
                    isHandwritingMode,
                    clipboardItemsState.value,
                    quickSendItemsState.value,
                    recentClipboardItemsState.value,
                    calculatorEngine.isActive(),
                    ) {
                        KeyboardUiState(
                        // 单一数据源：其余 43 个同名同义字段改为委托读取，不再逐字段复制
                        // （两份状态人工同步漏一处，就会出现「键盘显示英文、实际打中文」的错位）
                        source = state,
                        isDarkTheme = isDarkTheme,
                        keyboardHeightDp = effectiveKeyboardHeight,
                        clipboardItems = clipboardItemsState.value,
                        quickSendItems = quickSendItemsState.value,
                        recentClipboardItems = recentClipboardItemsState.value,
                        isCalculatorMode = calculatorEngine.isActive(),
                        isHandwritingMode = isHandwritingMode,
                        voiceBottomActive = state.voiceButtonState.bottomActive,
                        voiceLeftActive = state.voiceButtonState.leftActive,
                        voiceRightActive = state.voiceButtonState.rightActive,
                        )
                    }
                    // 覆盖页（菜单/剪贴板/表情等）激活时清除内联建议：
                    // InlineContentView 由独立 surface 支撑，其子 surface 合成在
                    // 窗口自身内容之上，Compose 覆盖层即使不透明也遮不住，
                    // 建议会浮在剪贴板/菜单面板上方（表现为候选栏位置内容重叠）。
                    // 与开始输入时 dismissInlineSuggestions 同语义，均含 surface
                    // 释放（InlineSuggestionViews.releaseAll）；覆盖页关闭后由
                    // 宿主 app 重新下发建议。
                    val isOverlayActive = keyboardViewModel.page
                        .collectAsState().value is KeyboardPage.Overlay
                    LaunchedEffect(isOverlayActive) {
                        if (isOverlayActive) dismissInlineSuggestions()
                    }
                    val callbacks = rememberImeKeyboardCallbacks(this@XimeInputMethodService, floatingMinY)
                    keyboardCallbacks = callbacks
                    val hapticView = LocalView.current
                    KeyboardView(
                        viewModel = keyboardViewModel,
                        state = kbState,
                        geometryState = geometryState,
                        candidateState = candidateState,
                        voiceAmplitudeState = this@XimeInputMethodService.voiceAmplitudeState,
                        voiceSpectrumState = this@XimeInputMethodService.voiceSpectrumState,
                        callbacks = callbacks,
                        inlineSuggestions = inlineSuggestionManager?.suggestions.orEmpty(),
                        // 非按键交互（符号/表情面板、菜单栏、候选栏按钮）的振动，
                        // 语义与按键按下反馈完全一致（模式/时长/振幅走同一配置）
                        onHapticFeedback = { feedbackManager.hapticFeedback(hapticView) },
                        onCardPositioned = { left: Int, top: Int, right: Int, bottom: Int ->
                            val cardHeightPx = bottom - top
                            if (cardHeightPx > 0) {
                                // 实测矩形（窗口坐标）是触摸区与拖动钳制的唯一真源
                                floatingCardBounds = FloatingCardGeometry.CardBounds(left, top, right, bottom)
                                currentFloatingCardHeightDp = (cardHeightPx / density.density).roundToInt()
                            }
                        },
                    )
                   }
                   if (state.showKeyboardResize) {
                      // 键盘调节层：渲染在键盘内容 Box 内部，matchParentSize 与键盘
                      // 同一矩形——遮罩、四边手柄、三按钮与键盘像素级对齐，不存在
                      // 坐标复制误差。拖动中实时预览，「确定」才落盘；「取消」全部
                      // 还原；「重置」回默认（预览态，仍需确定）。边界收敛在
                      // KeyboardResizeBounds，覆盖层只上报合法绝对值。
                      // 悬浮卡片宽度只有窗口的 scaleFactor（横屏时约 38%），若调节层铺满
                      // 整个窗口，SpaceEvenly 排布的重置/确定按钮会落在卡片之外——那些位置
                      // 不在 IME 触摸区内，点击直接穿透给宿主应用（表现为按钮按不了）。
                      // 这里按卡片实际宽度居中约束，保证三个按钮全部落在卡片内部。
                      // 仅「横屏 + 悬浮」时调节层才贴合卡片；竖屏与其他情况保持最初默认
                      // （占满整个内容 Box、无偏移），两者互不干扰。
                      //
                      // 横屏悬浮卡片宽度 = 窗口宽 × widthFraction（按短边折算，约 38%），
                      // 位置 = 底部对齐 + offsetX / offsetY，与 FloatingKeyboardContainer 完全一致。
                      // 调节层必须严格复刻这个矩形：否则 SpaceEvenly 排布的重置/确定按钮会
                      // 落到卡片之外（卡片外不在 IME 触摸区内，点击直接穿透给宿主应用），
                      // 且卡片上移后遮罩会错位（表现为调节层贴底、没跟随键盘）。
                      // 任何悬浮（竖屏 / 横屏）都要贴合卡片矩形，否则调节层铺满窗口、
                      // 手柄与卡片错位（竖屏悬浮此前就是这个问题）。
                      val isFloatingResize = state.isFloatingMode
                      // 仅横屏悬浮需要「长边口径」的高度基准（卡片为竖屏形态缩放）。
                      val isLandscapeFloatingResize = state.isFloatingMode && screenIsLandscape
                      val resizeCardWidthFraction = if (isFloatingResize)
                          FloatingCardGeometry.widthFraction(
                              FloatingCardGeometry.portraitScreenWidthDp(screenWidthDp, screenHeightDp),
                              screenWidthDp,
                          ) else 1f
                      // 调节层必须与 FloatingKeyboardContainer 的卡片矩形完全一致：
                      // 卡片宽 = 标准宽 - marginStart - marginEnd（负边距会变宽），
                      // 卡片水平位移 = (marginStart - marginEnd)/2（边跟手）。
                      // 此前只按标准宽 + offsetX 铺遮罩，用户拖边距后遮罩不跟随卡片，
                      // 表现为「遮罩不跟随键盘」（手柄与卡片错位、按钮跑出卡片）。
                      val resizeStandardWidthDp = (screenWidthDp * resizeCardWidthFraction)
                      GeometryObserver(geometryState) { resizeGeo ->
                      val resizeMarginStart = if (isFloatingResize) resizeGeo.resizePreviewMarginStartDp else 0
                      val resizeMarginEnd = if (isFloatingResize) resizeGeo.resizePreviewMarginEndDp else 0
                      val resizeActualWidthDp = (resizeStandardWidthDp - resizeMarginStart - resizeMarginEnd)
                          .coerceAtLeast(120f)
                      val resizeMarginShiftDp = (resizeMarginStart - resizeMarginEnd) / 2f
                      // 遮罩高度贴合卡片总高（内容 + 底部留白），不是整个窗口高。
                      // 竖屏悬浮卡片较矮，若用 fillMaxHeight 遮罩会铺到窗口底，
                      // 下手柄落在窗口底而非卡片底（表现为手柄与卡片错位、拖不准）。
                      val resizeCardTotalHeightDp =
                          (floatingCardContentHeight + state.keyboardBottomPaddingDp).dp
                      Box(
                          modifier = Modifier
                              .fillMaxSize(),
                          contentAlignment = if (isFloatingResize)
                              androidx.compose.ui.Alignment.BottomCenter
                          else androidx.compose.ui.Alignment.Center,
                      ) {
                      // 调节层遮罩跟随卡片实际矩形（宽 = 标准宽 - 左右边距，可随负边距变宽；
                      // 水平位移 = 边距差/2）。遮罩与卡片始终同矩形，手柄/按钮与卡片像素对齐。
                      // （此前遮罩固定按标准宽铺，用户拖边距后遮罩不跟随卡片，表现为遮罩错位。）
                      // 调节开始时的边距预置为当前值，用户从现状继续拖，不会跳变。
                      Box(
                          modifier = Modifier
                              .then(
                                  if (isFloatingResize)
                                      Modifier.width(resizeActualWidthDp.dp)
                                  else Modifier.fillMaxWidth()
                              )
                              .height(resizeCardTotalHeightDp)
                              .offset(
                                  x = if (isFloatingResize)
                                      (resizeGeo.floatingOffsetX + resizeMarginShiftDp).dp else 0.dp,
                                  y = if (isFloatingResize) (-resizeGeo.floatingOffsetY).dp else 0.dp,
                              )
                              .align(androidx.compose.ui.Alignment.BottomCenter)
                      ) {
                      KeyboardResizeOverlay(
                          // 传**逻辑（未缩放）**高度：边界、拖动回调、重置/确定的落盘值
                          // 全是这个口径，与 resizePreviewHeightDp 同源同单位。
                          // 悬浮时的显示高度由 heightScaleFactor 换算，不再预算成
                          // effectiveKeyboardHeight 传进来——那样 heightDp（已缩放）与
                          // heightBounds（未缩放）单位错配，底部手柄 coerceIn 区间反转
                          // 崩溃（日志两次 "Cannot coerce value to an empty range"）。
                          heightDp = keyboardHeight,
                          heightScaleFactor = floatScale,
                          // 横屏悬浮的高度基准 = 竖屏长边（卡片高度基准），否则拖动边界
                          // 按横屏短边收敛，卡片可用范围被压小、拖动几乎无反应。
                          heightBaseDp = if (isLandscapeFloatingResize)
                              maxOf(screenWidthDp, screenHeightDp) else 0,
                          // 宽度基准用卡片实际渲染宽度（任何悬浮都要传）：
                          // 传 0 会退回「按整屏宽算边距上限+下限锁 0」，只允许向内收窄、
                          // 不允许负边距外扩，竖屏悬浮卡片边缘永远拖不到屏幕边界。
                          widthBaseDp = if (isFloatingResize)
                              (screenWidthDp * resizeCardWidthFraction).roundToInt() else 0,
                          bottomPaddingDp = state.keyboardBottomPaddingDp,
                          marginStartDp = resizeGeo.resizePreviewMarginStartDp,
                          marginEndDp = resizeGeo.resizePreviewMarginEndDp,
                          onHeightChange = { newHeight ->
                              uiState.value = uiState.value.copy(resizePreviewHeightDp = newHeight)
                          },
                          onBottomPaddingChange = { newPadding ->
                              // 下手柄「高度 ⇄ 留白」耦合已由 Overlay 保证总高恒定，
                              // 此处只落盘留白；不补偿 offsetY——补偿会让整个键盘动。
                              uiState.value = uiState.value.copy(keyboardBottomPaddingDp = newPadding)
                          },
                          onMarginStartChange = { newMargin ->
                              geometryState.value = geometryState.value.copy(resizePreviewMarginStartDp = newMargin)
                          },
                          onMarginEndChange = { newMargin ->
                              geometryState.value = geometryState.value.copy(resizePreviewMarginEndDp = newMargin)
                          },
                          onReset = {
                              // 重置到当前方向的默认高度（横屏悬浮独立口径）
                              // 重置只回落到当前「方向 × 悬浮」口径的默认值，
                              // 不影响其他三套独立高度
                              val resetIsLandscape = SettingsPreferences.isLandscapeKeyboardHeightKey(
                                  this@XimeInputMethodService
                              )
                              val resetIsFloating = SettingsPreferences.isFloatingMode(
                                  this@XimeInputMethodService, resetIsLandscape
                              )
                              uiState.value = uiState.value.copy(
                                  resizePreviewHeightDp = SettingsPreferences.getKeyboardHeightDefaultsFor(
                                      this@XimeInputMethodService, resetIsLandscape, resetIsFloating
                                  ),
                                  keyboardBottomPaddingDp = 0,
                                  resizePreviewMarginStartDp = 0,
                                  resizePreviewMarginEndDp = 0,
                              )
                              // 重置同样要清掉几何镜像里的边距预览（读取侧已切到 geometryState）
                              geometryState.value = geometryState.value.copy(
                                  resizePreviewMarginStartDp = 0,
                                  resizePreviewMarginEndDp = 0,
                              )
                          },
                          onConfirm = {
                              val snapshot = uiState.value
                              schemaController.setKeyboardHeight(snapshot.resizePreviewHeightDp)
                              // 三件套一律写「方向 × 悬浮」口径的键，与读取侧完全同源，
                              // 避免横屏悬浮的调节结果污染竖屏非悬浮的保存值。
                              val confirmIsLandscape = SettingsPreferences.isLandscapeKeyboardHeightKey(
                                  this@XimeInputMethodService
                              )
                              val confirmIsFloating = SettingsPreferences.isFloatingMode(
                                  this@XimeInputMethodService, confirmIsLandscape
                              )
                              SettingsPreferences.setKeyboardBottomPaddingDp(this@XimeInputMethodService, snapshot.keyboardBottomPaddingDp, confirmIsLandscape, confirmIsFloating)
                              val confirmGeo = geometryState.value
                              SettingsPreferences.setKeyboardMarginStartDp(this@XimeInputMethodService, confirmGeo.resizePreviewMarginStartDp, confirmIsLandscape, confirmIsFloating)
                              SettingsPreferences.setKeyboardMarginEndDp(this@XimeInputMethodService, confirmGeo.resizePreviewMarginEndDp, confirmIsLandscape, confirmIsFloating)
                               // 悬浮键盘当前位置在拖拽结束时已落盘（handleFloatingKeyboardDragEnd），
                               // 调节过程不再改动 offsetY（下手柄纯高度调节、底边不跟手），
                               // 此处写回原值无副作用；保留横屏悬浮分支保持兼容。
                              if (confirmIsLandscape && confirmIsFloating) {
                                  SettingsPreferences.setFloatingOffsetY(
                                      this@XimeInputMethodService, confirmGeo.floatingOffsetY, confirmIsLandscape,
                                  )
                              }
                              uiState.value = snapshot.copy(
                                  showKeyboardResize = false,
                                  keyboardHeightDp = snapshot.resizePreviewHeightDp,
                              )
                              // 确认后把预览几何固化为已保存几何
                              geometryState.value = confirmGeo.copy(
                                  keyboardMarginStartDp = confirmGeo.resizePreviewMarginStartDp,
                                  keyboardMarginEndDp = confirmGeo.resizePreviewMarginEndDp,
                              )
                          },
                          onCancel = {
                              val cancelIsLandscape = SettingsPreferences.isLandscapeKeyboardHeightKey(
                                  this@XimeInputMethodService
                              )
                              val restoreHeight = SettingsPreferences.getKeyboardHeightDp(
                                  this@XimeInputMethodService,
                                  cancelIsLandscape,
                                  SettingsPreferences.isFloatingMode(this@XimeInputMethodService, cancelIsLandscape),
                              )
                              val cancelIsFloating = SettingsPreferences.isFloatingMode(this@XimeInputMethodService, cancelIsLandscape)
                              val restoreStart = SettingsPreferences.getKeyboardMarginStartDp(this@XimeInputMethodService, cancelIsLandscape, cancelIsFloating)
                              val restoreEnd = SettingsPreferences.getKeyboardMarginEndDp(this@XimeInputMethodService, cancelIsLandscape, cancelIsFloating)
                              uiState.value = uiState.value.copy(
                                  showKeyboardResize = false,
                                  keyboardHeightDp = restoreHeight,
                                  resizePreviewHeightDp = restoreHeight,
                                  keyboardBottomPaddingDp = SettingsPreferences.getKeyboardBottomPaddingDp(this@XimeInputMethodService, cancelIsLandscape, cancelIsFloating),
                              )
                              // 取消：几何镜像恢复为已保存值
                              geometryState.value = geometryState.value.copy(
                                  keyboardMarginStartDp = restoreStart,
                                  resizePreviewMarginStartDp = restoreStart,
                                  keyboardMarginEndDp = restoreEnd,
                                  resizePreviewMarginEndDp = restoreEnd,
                              )
                          },
                          modifier = Modifier.matchParentSize()
                      )
                      }
                      }
                      }
                  }
                   }
                    if (!state.isFloatingMode && navBarDp > 0.dp) {
                        Spacer(modifier = Modifier.fillMaxWidth().height(navBarDp))
                    }
                    // 悬浮拖到底部的停靠提示光：必须是根 Box 最后一个子级
                    // （画在键盘内容之上）——第一版画在卡片后面，手势导航设备上
                    // minY=0、卡片底边贴住窗口底边，光晕几乎全被卡片挡住而不可见。
                    // 颜色取主题强调色；x 含 offsetX 与卡片对齐。
                    if (state.isFloatingMode && floatingExitHintState.value) {
                        FloatingExitGlow(
                            offsetXdp = geometryState.value.floatingOffsetX,
                            cardWidthDp = FloatingCardGeometry.cardWidthDpForConfig(screenWidthDp, screenHeightDp),
                            bottomGapDp = floatingMinY,
                            glowColor = accentCol,
                            modifier = Modifier
                                .align(androidx.compose.ui.Alignment.BottomCenter)
                                .offset(x = geometryState.value.floatingOffsetX.dp),
                        )
                    }
               }
              }
         }
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        win.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        try {
            window.window?.decorView
                ?.findViewById<FrameLayout>(android.R.id.inputArea)
                ?.let { area ->
                    area.updateLayoutParams<android.view.ViewGroup.LayoutParams> {
                        height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    // 栏外编码气泡越出容器顶边后进入 inputArea 空白区，
                    // inputArea 默认 clipChildren=true 会把越界部分裁掉。
                    area.clipChildren = false
                }
            // 容器在 inputArea 内底部对齐（gravity BOTTOM）：
            // 容器物理高度 = Compose 内容总高，小于全屏窗口时若默认 top-left
            // 对齐会让键盘跑到屏顶。高度由 SideEffect 的 updateHeight 动态设置。
            view.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                gravity = android.view.Gravity.BOTTOM
            }
            val state = uiState.value
            if (state.isFloatingMode || state.isCompact) {
                view.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                    height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                }
            } else {
                // 首显前预设容器高度：容器初始 MATCH_PARENT（贴底 → 顶部 y=0）会让窗口
                // 第一次 traversal 的 onComputeInsets 报告"键盘占满全屏"，而 SideEffect 的
                // updateHeight 下一帧才生效；部分应用按首次 inset 布局后不再响应修正，
                // 输入框被顶到屏顶、与键盘间留大片空白（重进时容器已带正确高度故不复现）。
                // 按偏好高度预设首帧真实几何，之后仍由 SideEffect 统一维护。
                val isLandscape =
                    resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
                // 高度口径：方向 × 悬浮四套独立（横屏悬浮卡片与全宽键盘互不干扰）
                val displayHeight = SettingsPreferences.getKeyboardHeightDp(
                    this, isLandscape, SettingsPreferences.isFloatingMode(this, isLandscape)
                )
                    .coerceAtMost((resources.configuration.screenHeightDp * KeyboardWindowMetrics.DISPLAY_HEIGHT_MAX_PERCENT) / 100)
                val density = resources.displayMetrics.density
                val rawDp = if (bottomInsetPxState.value > 0)
                    (bottomInsetPxState.value / density).toInt() else 0
                val extraShrink = if (rawDp >= KeyboardWindowMetrics.STANDARD_NAV_BAR_INSET_THRESHOLD_DP) KeyboardWindowMetrics.STANDARD_NAV_BAR_EXTRA_SHRINK_DP else 0
                val bottomSpace = if (rawDp > 0) (rawDp - KeyboardWindowMetrics.BOTTOM_INSET_SHRINK_PORTRAIT_DP - extraShrink).coerceAtLeast(0) else 0
                val activeBottomDp = if (bottomSpace == 0) KeyboardWindowMetrics.MIN_BOTTOM_SPACE_DP else bottomSpace
                view.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                    height = ((displayHeight + state.keyboardBottomPaddingDp + activeBottomDp) * density).toInt()
                }
            }
        } catch (e: Exception) {
            // 首帧几何预设失败会让窗口按错高度布局（输入框被顶到屏顶），必须可追溯
            FileLogger.w(TAG, "applyInitialGeometry failed", e)
        }
    }
    
    // ── ActionExecutor 实现 ──

    override fun performEditorMenuAction(actionId: Int) {
        when (actionId) {
            android.R.id.undo -> {
                // performContextMenuAction 对 undo 支持不一致，改用 Ctrl+Z 键盘快捷键
                val now = SystemClock.uptimeMillis()
                currentInputConnection?.sendKeyEvent(
                    KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON)
                )
                currentInputConnection?.sendKeyEvent(
                    KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON)
                )
            }
            else -> currentInputConnection?.performContextMenuAction(actionId)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val e = event ?: return super.onKeyDown(keyCode, event)
        // 物理 Shift 单击切中英文的组合检测：Shift 按下重置标记，期间任何其他键按下即视为组合
        // （如 Shift+字母大写），抬起时不再触发切换。
        if (isShiftKeyCode(keyCode)) {
            if (e.repeatCount == 0) shiftComboDetected = false
        } else {
            shiftComboDetected = true
        }
        // Ctrl 编辑快捷键（复制/剪切/粘贴/全选/撤销）：必须在 rime 路由之前拦截，
        // 否则字母会被当作拼音吞掉（如 Ctrl+C 变成向引擎输入 c，复制失效）。
        // 执行链路与软键盘工具栏按钮一致（performEditorMenuAction）。
        if (e.isCtrlPressed) {
            val actionId = when (keyCode) {
                KeyEvent.KEYCODE_C -> android.R.id.copy
                KeyEvent.KEYCODE_X -> android.R.id.cut
                KeyEvent.KEYCODE_V -> android.R.id.paste
                KeyEvent.KEYCODE_A -> android.R.id.selectAll
                KeyEvent.KEYCODE_Z -> android.R.id.undo
                else -> 0
            }
            if (actionId != 0) {
                performEditorMenuAction(actionId)
                return true
            }
            // 其余 Ctrl 组合（Ctrl+方向键词移动、Ctrl+Shift 系列等）交还系统与目标应用处理
            return super.onKeyDown(keyCode, event)
        }
        if (hasHardwareKeyboard && candidateState.value.candidates.isNotEmpty()) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (keyboardViewModel.candidatePageExpanded.value) {
                        expandedPageScroll(1); highlightIndex.intValue = 0; return true
                    }
                    if (candidateState.value.hasNextPage) { keyRouter.pageDown(); highlightIndex.intValue = 0; return true }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (keyboardViewModel.candidatePageExpanded.value) {
                        expandedPageScroll(-1); highlightIndex.intValue = 0; return true
                    }
                    if (candidateState.value.hasPrevPage) { keyRouter.pageUp(); highlightIndex.intValue = 0; return true }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val maxIdx = candidateState.value.candidates.size - 1
                    highlightIndex.intValue = (highlightIndex.intValue + 1).coerceAtMost(maxIdx)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    highlightIndex.intValue = (highlightIndex.intValue - 1).coerceAtLeast(0)
                    return true
                }
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER -> {
                    if (candidateState.value.candidates.isNotEmpty()) {
                        keyRouter.selectCandidate(highlightIndex.intValue)
                        highlightIndex.intValue = 0
                        return true
                    }
                }
                KeyEvent.KEYCODE_1 -> { keyRouter.selectCandidate(0); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_2 -> { keyRouter.selectCandidate(1); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_3 -> { keyRouter.selectCandidate(2); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_4 -> { keyRouter.selectCandidate(3); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_5 -> { keyRouter.selectCandidate(4); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_6 -> { keyRouter.selectCandidate(5); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_7 -> { keyRouter.selectCandidate(6); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_8 -> { keyRouter.selectCandidate(7); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_9 -> { keyRouter.selectCandidate(8); highlightIndex.intValue = 0; return true }
                KeyEvent.KEYCODE_0 -> { keyRouter.selectCandidate(9); highlightIndex.intValue = 0; return true }
            }
        }
        // Caps Lock 的 meta state 由系统自动维护，与 Shift 同效（物理键盘大小写切换）
        val isShifted = e.isShiftPressed || e.isCapsLockOn
        val key = keyCodeToKey(keyCode, isShifted)
        if (key != null) {
            keyRouter.handleKeyPress(key, isShifted)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // 物理 Shift 单击（按下到抬起间无其他按键）切换中英文，与 PC 中文输入法习惯一致；
        // 复用软键盘 earth 键的 ime_switch 链路（USER_TOGGLE，会话级不持久化）。
        // 组合使用（Shift+字母等）时放行 super，不触发切换。
        if (isShiftKeyCode(keyCode) && !shiftComboDetected) {
            dispatchKey("ime_switch")
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun sendKeyEvent(keyCode: Int) {
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    override fun executeCommand(name: String) {
        when (name) {
            "clear_composition" -> {
                keyRouter.postRimeJob {
                    rimeEngine.clearComposition()
                    withContext(Dispatchers.Main) {
                        mainHandler.post { updateUI() }
                    }
                }
            }
            "show_ime_picker" -> {
                // 防御：个别 ROM 在键盘窗口内调用会抛，未捕获即闪退。
                runCatching {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                    @Suppress("DEPRECATION")
                    imm?.showInputMethodPicker()
                }
            }
            // 组合键「重新部署」：与设置页「部署方案」同一实现（同步可见目录 → 重编 → 重载）。
            "reload" -> schemaController.reloadConfig()
            // 打开目录：`open_dir` 默认公开 rime 根，也可写 `open_dir:<绝对路径>` 指定。
            else -> if (ConfigDirOpener.isOpenDirCommand(name)) {
                ConfigDirOpener.openDirectory(this, ConfigDirOpener.parseOpenDirPath(name))
            } else {
                FileLogger.w(TAG, "Unknown command: $name")
            }
        }
    }

    override fun repeatLastInput() {
        // 有候选还没上屏时，先把第一个候选打上去，并立即**再重复上屏同一个词一次**
        // （用户语义：一次上滑 = 这个候选上屏两次）。
        //
        // 第二次不能在本函数里同步提交：selectCandidate 是排进 keyJobs 的异步任务，
        // 此刻 lastCommitChunk 还没更新。这里再排一个 job，靠 keyJobs 的 FIFO
        // （消费端逐个 join）保证它排在第一次提交之后执行——那时 lastCommitChunk
        // 已经是刚上屏的候选词。若第一次没完成整串上屏（组合态仍在，例如只上屏了
        // 部分字），就不重复，避免凭空多打一次。
        val candidateNow = candidateState.value
        if (RepeatCommitPolicy.shouldCommitFirstCandidate(
                isComposing = candidateNow.isComposing,
                candidateCount = candidateNow.candidates.size,
            )
        ) {
            keyRouter.selectCandidate(0)
            keyRouter.postRimeJob {
                withContext(Dispatchers.Main) {
                    if (!candidateState.value.isComposing) {
                        val repeated = predictionManager.lastCommitChunk
                        if (repeated.isNotEmpty()) {
                            currentInputConnection?.commitText(repeated, 1)
                        }
                    }
                }
            }
            return
        }
        // 只重提最近一次【单次】上屏的内容，不能把整个上下文缓冲区又打一遍
        val lastText = predictionManager.lastCommitChunk
        if (lastText.isNotEmpty()) {
            currentInputConnection?.commitText(lastText, 1)
        }
    }

    override fun dispatchKey(key: String) {
        // 与物理键盘 onKeyDown 同一入口：handleKeyPress 内部自行调度到 key-processing 线程
        keyRouter.handleKeyPress(key, false)
    }

    /**
     * 光标左右移动（空格键左右滑等手势）。
     *
     * 复用键盘整屏拖拽同一条逻辑：组合态移动编码编辑光标、空闲态移动编辑器选区。
     */
    override fun moveCursor(delta: Int) {
        handleCursorMove(this, delta)
    }

    // ── 原有方法 ──

    
    /** 上一次输入会话的编辑框指纹（包名|fieldId|inputType），识别"同一输入框重启"。 */
    @Volatile
    private var lastEditorSignature: String? = null

    /** 上一次 onStartInput 的时刻，配合指纹判断快速重启（QQ 撤回/发送就这样刷）。 */
    @Volatile
    private var lastStartInputAtMs: Long = 0L

    /** 同一编辑框快速重启的判定窗口。 */
    private val SAME_EDITOR_RESTART_WINDOW_MS = 3000L

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // QQ 撤回/发送消息时会把输入框重建一遍：系统给出的是 restarting=false 的"新会话"，
        // 但实际还是同一个编辑框（同样的包名/fieldId/inputType，且间隔很短）。
        // 这种重启不该换 sessionId、不该清候选栏——否则键盘会闪一下再弹出。
        val nowMs = System.currentTimeMillis()
        val editorSignature = attribute?.let { "${it.packageName}|${it.fieldId}|${it.inputType}" }
        val sameEditorQuickRestart = editorSignature != null &&
            editorSignature == lastEditorSignature &&
            nowMs - lastStartInputAtMs in 0 until SAME_EDITOR_RESTART_WINDOW_MS
        FileLogger.i(
            TAG,
            "onStartInput: sig=$editorSignature restarting=$restarting quickRestart=$sameEditorQuickRestart",
        )
        lastEditorSignature = editorSignature
        lastStartInputAtMs = nowMs
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        loadDarkModePreference()

        // 敏感输入框（密码等）判定 + composing 去重标志重置（详见 PluginEventDispatcher）
        pluginEvents.onStartInput(attribute)

        // 受限输入框判定（密码/终端/NO_SUGGESTIONS）：供英文联想等补全功能短路
        editorRestricted = EditorInfoClassifier.isRestrictedEditor(attribute)
        // 秘密输入框判定（密码/终端，不含 NO_SUGGESTIONS）：英文联想/回删替换的禁用线
        editorSecret = EditorInfoClassifier.isSecretEditor(attribute)

        // 输入 target 变化：旧编辑框的 composing 区域不再可达，复位标记。
        // 防御 stale 标记导致 endComposingInputBox 对新编辑框执行 setComposingText("")
        // （无 composing 时会在光标处插入空串，选中文字时等于删除选区）。
        if (!restarting) {
            inputBoxComposingActive = false
            // 换输入框：删除会话/排队退格/撤回槽全部作废（见 resetDeleteStateForNewInputSession），
            // 否则旧框内容会被撤回进新框、排队的退格会在新框上重放多删字。
            resetDeleteStateForNewInputSession()
            otpAutoFilledInCurrentSession = false
        }
        if (sameEditorQuickRestart) {
            // 同一编辑框的快速重启：什么都不重建，直接返回（等价于一次无操作刷新）
            return
        }

        predictionManager.clearCommittedText()
        // 新输入会话清空 partial commit 累积：外部 UI（如设置页输入框"清除"按钮仅清 Compose
        // state）会触发 restartInput → 此处重建 T9，若残留累积会被 buildT9DisplayState 拼进
        // preedit 回灌输入框（2026-08-07 日志实证：清除后 testText 从 '' 回灌为 '几乎'）。
        t9PartialSegments.clear()
        debugLog("onStartInput: cleared lastCommittedText")

        syncVerboseLoggingToEngine()
        
        restoreSchemaOnStartInput(restarting)

        uiState.value = uiState.value.copy(
            // restarting = 宿主对**同一个**输入框调 restartInput（QQ 发送/撤回消息、
            // 部分 App 刷新输入框都会走这里）。此时若照常换 inputSessionId，
            // KeyboardView 的 LaunchedEffect 会走一遍「新输入会话」重置
            // （t9Controller.reset + InputSessionStarted 分发），表现为键盘闪一下再弹出。
            inputSessionId = if (restarting || sameEditorQuickRestart) {
                uiState.value.inputSessionId
            } else {
                System.nanoTime()
            },
            isInputSessionRestarting = restarting,
            isSttEnabled = SettingsPreferences.isSttEnabled(this@XimeInputMethodService),
        )

        resetKeyboardOnStartInput(attribute, restarting)
        // 键盘已在显示时切到另一个（验证码）输入框：onStartInputView 可能因 restarting 跳过，
        // 这里补一次（内部有「框里已含该验证码则不填」防重）。
        if (!restarting) maybeAutoFillOtp(attribute)

        if (!restarting && !sameEditorQuickRestart) {
            // 只有真正的输入会话切换才重建这些状态。
            // restartInput（QQ 发送/撤回消息后宿主刷新输入框）如果也重建，候选栏会被
            // 清空再被剪贴板候选写入，视觉上就是「闪一下」，且白付一次存储读取 + 监听重启。
            candidateState.value = CandidateState()
            // 剪贴板候选：只建立监听，**不在每次进入输入框时自动展示历史剪贴板**。
            // 用户明确要求：复制内容一旦在当前键盘状态展示过（或被消费），换应用不该再弹出来。
            // 真正展示时机只剩「用户刚复制新内容」这一种，见 [startClipboardCandidatesCollection]。
            startClipboardCandidatesCollection()
        }

        attribute?.let { updateEnterKeyText(it) }
    }
    
    private val highlightIndex = mutableIntStateOf(0)

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        FileLogger.i(TAG, "onStartInputView: restarting=$restarting")
        info?.let { updateEnterKeyText(it) }
        // 发送后残留清理：宿主已把输入框清空（聊天「发送」把嵌入的词发出去并清空输入框），
        // 但上一轮的引擎组合态/候选栏还挂着（QQ 等应用发送时不回调光标归零，
        // onWindowHidden 的清理与键盘再弹出的时序对不上）时，在键盘弹出这一刻补一次清理。
        clearResidualCandidatesIfHostEmpty()
        // 验证码自动填充（方案 B 兜底）：MIUI 等国内 ROM 会过滤 SMS_RECEIVED 广播，
        // 这里在键盘弹出时主动查一次最近短信。IO 线程执行，不阻塞键盘首帧。
        hasHardwareKeyboard = SettingsPreferences.isHardwareKeyboardDetectionEnabled(this) &&
            resources.configuration.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS
        applyCompactMode()
        applyWindowBackground()
        updateCursorUpdateMonitoring()
        // 验证码自动填充：点进「验证码输入框」（数字类）时，把刚收到的验证码直接填入
        maybeAutoFillOtp(info)
    }

    /**
     * 宿主输入框为空但 Xime 仍残留组合态/候选时的兜底清理。
     *
     * 场景：聊天应用「发送」把输入框里嵌入的候选词发出去并清空输入框——部分应用
     * （如 QQ）此过程不回调 onUpdateSelection 光标归零，键盘收起清理与再弹出
     * 的时序也可能错开，上一轮的引擎编码与候选栏就这样残留到下一次弹出。
     * 这里在键盘弹出时检测「宿主输入框前后都取不到字符 + Xime 有残留」补一次全清。
     *
     * 保守条件（不误伤）：宿主文本非空（正常会话）或查询失败（null）时不动；
     * 组合键输入态（orq/oiz 触发码累积中/组合结果占据候选栏）豁免。
     */
    private fun clearResidualCandidatesIfHostEmpty() {
        val st = candidateState.value
        val hasResidue = st.candidates.isNotEmpty() || st.associationCandidates.isNotEmpty() ||
            st.candidateComments.isNotEmpty() || st.inputText.isNotEmpty() ||
            st.preeditText.isNotEmpty() || st.isComposing ||
            rimeEngine.getInput().isNotEmpty() || t9PartialSegments.isNotEmpty()
        if (!hasResidue) return
        if (shouldExemptComboState(rimeEngine.getInput(), st.candidates, st.candidateComments)) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        val after = ic.getTextAfterCursor(1, 0)
        // 宿主不支持查询（null）或文本非空时保守不清
        if (before == null || after == null) return
        if (before.isNotEmpty() || after.isNotEmpty()) return
        FileLogger.d(TAG, "onStartInputView: 宿主输入框为空但存在残留组合态/候选，兜底全清")
        rimeEngine.clearComposition()
        t9PartialSegments.clear()
        inputBoxComposingActive = false
        candidateState.value = st.copy(
            inputText = "", preeditText = "",
            candidates = emptyList(), candidateComments = emptyList(),
            associationCandidates = emptyList(), pendingEnglishText = "",
            isComposing = false, hasNextPage = false, hasPrevPage = false,
            candidateActions = emptyList(),
        )
    }

    /**
     * 验证码自动填充。
     *
     * 只在用户点进**数字类输入框**（验证码框的典型 inputType）时触发，且只填
     * 5 分钟内收到的验证码（更早的短信验证码早已失效）；填完立即从待用列表移除，
     * 重复进入同一框不会二次填入。
     *
     * 局限：这是**启发式**判断——只看 inputType 类别，无法确知该框语义，
     * 因此「金额/数量」等纯数字框也可能被填。用 5 分钟时效 + 填后即移除来收缩误伤面。
     */
    /**
     * 本次输入会话里是否已经自动填过验证码。
     *
     * `onStartInput` 与 `onStartInputView` 会各调一次 [maybeAutoFillOtp]（同一次聚焦），
     * 原来只靠「输入框里已含该码」防重，而 `getExtractedText` 在 Compose/Flutter 等宿主
     * 常返回 null，防重直接失效 → 字段里出现 `123456123456`。
     * 现在用会话级标志只填一次；换输入框时复位，保留「换框后仍可自动填充」的原行为。
     */
    private var otpAutoFilledInCurrentSession = false

    private fun maybeAutoFillOtp(info: EditorInfo?) {
        if (info == null) return
        if (otpAutoFilledInCurrentSession) return
        if (!runCatching { SettingsPreferences.isOtpAutofillEnabled(this) }.getOrDefault(false)) return
        // 「自动填充」独立开关：关掉后仍收集（候选栏/剪贴板照常），只是不往输入框填
        if (!runCatching { SettingsPreferences.isOtpAutoFillEnabled(this) }.getOrDefault(true)) return
        val cls = info.inputType and android.text.InputType.TYPE_MASK_CLASS
        if (cls != android.text.InputType.TYPE_CLASS_NUMBER) return

        // 密码框：数字密码变体不填
        val variation = info.inputType and android.text.InputType.TYPE_MASK_VARIATION
        if (variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD) return

        // 金额/数量/坐标类：带「可为小数」或「可为负数」标志的数字框不是验证码框，不填。
        // 验证码永远是纯正整数，这是区分金额框最可靠的特征。
        val numberFlags = info.inputType and (
            android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            )
        if (numberFlags != 0) {
            FileLogger.d(TAG, "auto-fill skipped: decimal/signed number field (amount-like)")
            return
        }
        val otp = runCatching {
            com.kingzcheung.xime.service.otp.OtpCollector.takeFresh()
        }.getOrNull() ?: return

        // 防重复：输入框里已经含这句验证码就不再填。
        // （同一框反复进入、或 onStartInput 与 onStartInputView 双重触发时，
        //   否则会变成 123456123456。）
        val current = runCatching {
            currentInputConnection
                ?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                ?.text?.toString().orEmpty()
        }.getOrDefault("")
        if (current.contains(otp)) {
            FileLogger.d(TAG, "auto-fill skipped: field already contains otp")
            return
        }

        FileLogger.i(TAG, "auto-fill otp into number field, len=${otp.length}")
        runCatching { commitText(otp) }.onFailure {
            FileLogger.w(TAG, "auto-fill otp commitText failed", it)
            return
        }
        otpAutoFilledInCurrentSession = true
        // **不 consume**：验证码保留到过期（OtpCollector.FRESH_WINDOW_MS）或被新码替换，
        // 用户填错/删掉后 5 分钟内仍能再次自动填入。
        runCatching {
            android.widget.Toast.makeText(this, "已填入验证码", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 上次请求的 CURSOR_UPDATE 模式：未变化时不重复发 IPC。 */
    private var lastCursorUpdateMode: Int = Int.MIN_VALUE

    /** 根据当前硬件键盘状态管理光标位置更新监听。 */
    private fun updateCursorUpdateMonitoring() {
        val mode = if (hasHardwareKeyboard) {
            InputConnection.CURSOR_UPDATE_MONITOR or InputConnection.CURSOR_UPDATE_IMMEDIATE
        } else {
            0
        }
        // requestCursorUpdates 是跨进程 Binder 调用；每次键盘弹出都重发同一模式纯属浪费。
        if (mode == lastCursorUpdateMode) return
        lastCursorUpdateMode = mode
        currentInputConnection?.requestCursorUpdates(mode)
    }

    private var anchorCoords = floatArrayOf(0f, 0f, 0f, 0f)

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        if (!hasHardwareKeyboard) return
        try {
            val bounds = info.getCharacterBounds(0)
            if (bounds != null) {
                anchorCoords[0] = bounds.left
                anchorCoords[1] = bounds.bottom
                anchorCoords[2] = bounds.left
                anchorCoords[3] = bounds.top
            } else {
                anchorCoords[0] = info.insertionMarkerHorizontal
                anchorCoords[1] = info.insertionMarkerBottom
                anchorCoords[2] = info.insertionMarkerHorizontal
                anchorCoords[3] = info.insertionMarkerTop
            }
            if (anchorCoords.any(Float::isNaN)) return
            info.matrix.mapPoints(anchorCoords)
            val screenY = anchorCoords[1].toInt().coerceIn(0, resources.displayMetrics.heightPixels)
            val screenX = anchorCoords[0].toInt().coerceIn(0, resources.displayMetrics.widthPixels)
            uiState.value = uiState.value.copy(
                cursorX = screenX,
                cursorY = screenY,
                cursorVisible = true,
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "onUpdateCursorAnchorInfo failed", e)
        }
    }

    /** onUpdateSelection 重入闸门（见 SelectionUpdateGate）。 */
    private val selectionUpdateGate = SelectionUpdateGate()

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // 自身写入 InputConnection 会同步触发本回调，处理中直接忽略，避免递归
        if (!selectionUpdateGate.tryEnter()) return
        try {
            // 输入框内容被清空（聊天「发送」把文字发出去、全选删除等）时光标回到开头：
            // 此时组合态往往已结束（词已上屏、hasPendingComposition()=false），原有逻辑
            // 在下方直接 return，但候选栏可能还挂着上屏后的联想/残留候选——
            // 跟随输入框一起清空，避免「消息已发出去，候选栏还挂着词」。
            // 仅在无待上屏组合态时清（有组合态时交由下方原有光标丢弃逻辑处理）。
            if (oldSelEnd > 0 && newSelStart == 0 && newSelEnd == 0) {
                val st = candidateState.value
                // 组合键输入态（orq/oiz 等触发码累积中、或候选栏已被组合结果占据）
                // 豁免清空：否则触发码永远凑不齐、组合候选被误清
                // （与下方原有 shouldExemptComboState 豁免口径一致）。
                if (shouldExemptComboState(rimeEngine.getInput(), st.candidates, st.candidateComments)) {
                    return
                }
                // 精确判定「发送/清空」而非「用户把光标点到开头」：光标前后都取不到字符
                // 才说明输入框确实被清空（点开头时光标后仍有文本）。宿主不支持查询
                // （返回 null）时保守不清，避免误伤正常输入。
                val ic = currentInputConnection
                val beforeEmpty = ic?.getTextBeforeCursor(1, 0)?.isEmpty() ?: false
                val afterEmpty = ic?.getTextAfterCursor(1, 0)?.isEmpty() ?: false
                if (!(beforeEmpty && afterEmpty)) {
                    return
                }
                if (st.candidates.isNotEmpty() || st.associationCandidates.isNotEmpty() ||
                    st.candidateComments.isNotEmpty() || st.inputText.isNotEmpty() ||
                    st.preeditText.isNotEmpty() || st.isComposing
                ) {
                    FileLogger.d(TAG, "onUpdateSelection: 输入框清空（发送/全删），清空候选栏残留+组合态")
                    // 引擎组合态一并丢弃：否则残留编码会让候选栏在下次刷新时重新填回
                    rimeEngine.clearComposition()
                    t9PartialSegments.clear()
                    inputBoxComposingActive = false
                    candidateState.value = st.copy(
                        inputText = "", preeditText = "",
                        candidates = emptyList(), candidateComments = emptyList(),
                        associationCandidates = emptyList(), pendingEnglishText = "",
                        isComposing = false, hasNextPage = false, hasPrevPage = false,
                        candidateActions = emptyList(),
                    )
                }
                return
            }
            // 仅在输入框承载编码/候选词（input-box 模式）时才处理光标移动丢弃组合态：
            // 此时 setComposingText 写入编辑器的 composing 区，宿主回调的 candidatesStart/End
            // 覆盖该区域，可据此区分「输入引起的光标回调」与「用户移动光标」。
            // 候选栏模式下编码只在输入法候选栏显示，编辑器光标变化与组合态无关，宿主也常
            // 报告 candidates=-1（豁免条件永不成立），任何光标回调都会被误判为「用户移动」
            // 而清空引擎 input —— 连续输入触发码（如组合键 orq）会在累积完成前被打断。
            if (!SettingsPreferences.isInputBoxComposing(this)) return
            if (!shouldBreakCompositionOnSelectionChange(
                    oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
                )
            ) {
                return
            }
            // 组合键输入态（编码累积中且无候选、或候选栏已被组合键结果占据）豁免丢弃：
            // combos 触发码（如 orq）在输完前引擎往往无候选，宿主写入编码的光标回调
            // 不应打断累积；命中后候选栏被日期/命令结果占据，同样不应被宿主回调清掉。
            val state = candidateState.value
            if (shouldExemptComboState(rimeEngine.getInput(), state.candidates, state.candidateComments)) return
            if (!hasPendingComposition()) return
            FileLogger.i(
                TAG,
                "onUpdateSelection: 丢弃待上屏组合态 ($oldSelStart,$oldSelEnd)->($newSelStart,$newSelEnd)"
            )
            breakPendingCompositionForCaretMove(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        } finally {
            selectionUpdateGate.exit()
        }
    }

    /** 是否存在未上屏的组合态（输入框 composing / t9 半提交 / 引擎输入）。 */
    private fun hasPendingComposition(): Boolean {
        val state = candidateState.value
        return inputBoxComposingActive ||
            t9PartialSegments.isNotEmpty() ||
            state.isComposing ||
            state.preeditText.isNotEmpty() ||
            rimeEngine.getInput().isNotEmpty()
    }

    /**
     * 光标移动后丢弃未上屏组合态：引擎输入 / t9 半提交 / 输入框编码全部作废，
     * 下一键从新光标处重新开始，避免旧编码与新输入合并上屏到错误位置。
     */
    private fun breakPendingCompositionForCaretMove(
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        keyRouter.postRimeJob {
            rimeEngine.clearComposition()
            withContext(Dispatchers.Main) {
                mainHandler.post { updateUI() }
            }
        }
        t9PartialSegments.clear()
        val ic = currentInputConnection
        val wasInputBoxComposing = inputBoxComposingActive
        inputBoxComposingActive = false
        val restoreCaret = restoreCaretAfterInputBoxComposingRemoval(
            newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        if (wasInputBoxComposing && ic != null && restoreCaret != null) {
            // 编码在输入框里以 composing 区域存在：setComposingText("") 原地删除（丢弃），
            // 随后把被带到编码起点的光标恢复到用户点击位置
            ic.beginBatchEdit()
            try {
                ic.setComposingText("", 0)
                ic.finishComposingText()
                ic.setSelection(restoreCaret, restoreCaret)
            } finally {
                ic.endBatchEdit()
            }
        } else {
            // 无组合区可删/拖动选区：只 finish 不定案清空（setComposingText("") 会误删光标处选区）
            ic?.finishComposingText()
        }
        uiState.value = uiState.value.copy(
            t9ResetSignal = uiState.value.t9ResetSignal + 1,
            t9RightCandidateSelectedCount = 0,
            t9SelectedCandidatePinyin = ""
        )
        candidateState.value = candidateState.value.copy(
            candidates = emptyList(),
            candidateComments = emptyList(),
            inputText = "",
            preeditText = "",
            isComposing = false,
            associationCandidates = emptyList(),
            hasNextPage = false,
            hasPrevPage = false,
            candidateActions = emptyList(),
            preeditCaretPos = -1
        )
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    override fun onEvaluateInputViewShown(): Boolean {
        return true
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        return true
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        hasHardwareKeyboard = SettingsPreferences.isHardwareKeyboardDetectionEnabled(this) &&
            newConfig.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS
        super.onConfigurationChanged(newConfig)
        // 方向切换必须退出键盘调节态：调节中的预览值属于切换前的方向
        // （横屏悬浮是独立高度口径），若不退出，竖屏会延续横屏悬浮的调节层
        // 与预览值——表现为「调横屏悬浮的，竖屏的键盘调节器被唤醒」。
        // 退出时不动任何落盘值，两个方向的数据保持各自独立。
        if (uiState.value.showKeyboardResize) {
            val isLandscape = newConfig.screenWidthDp > newConfig.screenHeightDp
            val restoreHeight = SettingsPreferences.getKeyboardHeightDp(
                this, isLandscape, SettingsPreferences.isFloatingMode(this, isLandscape)
            )
            val restoreFloating = SettingsPreferences.isFloatingMode(this, isLandscape)
            val restoreStart = SettingsPreferences.getKeyboardMarginStartDp(this, isLandscape, restoreFloating)
            val restoreEnd = SettingsPreferences.getKeyboardMarginEndDp(this, isLandscape, restoreFloating)
            uiState.value = uiState.value.copy(
                showKeyboardResize = false,
                keyboardHeightDp = restoreHeight,
                resizePreviewHeightDp = restoreHeight,
                keyboardBottomPaddingDp = SettingsPreferences.getKeyboardBottomPaddingDp(this, isLandscape, restoreFloating),
            )
            geometryState.value = geometryState.value.copy(
                keyboardMarginStartDp = restoreStart,
                resizePreviewMarginStartDp = restoreStart,
                keyboardMarginEndDp = restoreEnd,
                resizePreviewMarginEndDp = restoreEnd,
            )
        }
        if (newConfig.screenWidthDp > newConfig.screenHeightDp) {
            closeToolPanel()
        }
        applyCompactMode()
        loadDarkModePreference()
        applyWindowBackground()
        updateCursorUpdateMonitoring()
    }

    /** applyWindowBackground 上次应用的状态签名：主题/深色/紧凑/浮动均未变时跳过。 */
    private var lastWindowBgSignature: String? = null

    internal fun applyWindowBackground() {
        val state = uiState.value
        val isDark = isDarkTheme()
        // 主题、深浅、紧凑、浮动都没变时直接跳过：原先每次键盘弹出都重建 ColorDrawable、
        // 重设导航栏颜色、setDecorFitsSystemWindows 并 requestApplyInsets（强制全树
        // 重新分发 insets → relayout），是键盘弹出延迟感的来源之一。
        val signature = "${state.themeId}|$isDark|${state.isCompact}|${state.isFloatingMode}"
        if (signature == lastWindowBgSignature) return
        lastWindowBgSignature = signature
        try {
            val theme = com.kingzcheung.xime.ui.theme.KeyboardThemes.getThemeById(state.themeId)
            // 图片背景无法映射到 window 层，用主题主色作为导航栏/窗口兜底色；
            // solid / gradient 用解析出的键盘背景兜底色。
            val bgColor = if (theme.keyboardBackground?.type == "image") {
                com.kingzcheung.xime.ui.theme.KeyboardThemes.getPrimaryColor(state.themeId, isDark)
            } else {
                com.kingzcheung.xime.ui.theme.KeyboardThemes.getKeyboardBackgroundColor(state.themeId, isDark)
            }
            val argb = (bgColor.alpha * 255).toInt() shl 24 or
                (bgColor.red * 255).toInt() shl 16 or
                (bgColor.green * 255).toInt() shl 8 or
                (bgColor.blue * 255).toInt()
            window.window?.let { win ->
                if (state.isCompact) {
                    // 硬件键盘候选栏模式：窗口透明
                    win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    win.setDimAmount(0f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        win.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
                    }
                } else if (state.isFloatingMode) {
                    win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    win.setDimAmount(0f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        win.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
                    }
                } else {
                    // 非浮动模式：参考成熟输入法 FULL 方案的背景/高度布局。
                    // 1) edge-to-edge：窗口绘制到系统导航栏后面，键盘背景（渐变/图片）可延伸到底部；
                    // 2) 窗口背景透明：键盘内容由 Compose 绘制，键盘上方露出应用内容而不是白色/主题色块；
                    // 3) 导航栏透明 + 关闭强制对比度：底部导航栏区域由键盘背景覆盖，不会露出系统白色。
                    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(win, false)
                    win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                    win.setDimAmount(0f)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        win.isNavigationBarContrastEnforced = false
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        win.setNavigationBarColor(android.graphics.Color.TRANSPARENT)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    win.decorView.let { decor ->
                        val controller = androidx.core.view.WindowInsetsControllerCompat(win, decor)
                        controller.isAppearanceLightNavigationBars = !isDark
                    }
                }
                // setDecorFitsSystemWindows(false) 后必须重新分发 insets，
                // 否则 onApplyWindowInsets 不会触发、底部导航栏高度检测不到。
                win.decorView.requestApplyInsets()
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "applyWindowBackground failed", e)
        }
    }

    private fun applyCompactMode() {
        val current = uiState.value
        val detectionEnabled = SettingsPreferences.isHardwareKeyboardDetectionEnabled(this)
        val isCompact = detectionEnabled && hasHardwareKeyboard
        FileLogger.i(
            TAG,
            "applyCompactMode: keyboardCfg=${keyboardConfigName(resources.configuration.keyboard)}, " +
                "hasHardwareKeyboard=$hasHardwareKeyboard, isCompact=$isCompact (was ${current.isCompact})"
        )
        if (current.isCompact != isCompact) {
            uiState.value = current.copy(isCompact = isCompact)
            if (isCompact) {
                window.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
        }
    }

    private fun keyboardConfigName(value: Int): String = when (value) {
        android.content.res.Configuration.KEYBOARD_UNDEFINED -> "UNDEFINED"
        android.content.res.Configuration.KEYBOARD_NOKEYS -> "NOKEYS"
        android.content.res.Configuration.KEYBOARD_QWERTY -> "QWERTY"
        android.content.res.Configuration.KEYBOARD_12KEY -> "12KEY"
        else -> "UNKNOWN($value)"
    }

    private fun updateEnterKeyText(editorInfo: EditorInfo) {
        val imeOptions = editorInfo.imeOptions
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val enterText = when {
            noEnterAction -> "换行"
            action == EditorInfo.IME_ACTION_GO -> "前往"
            action == EditorInfo.IME_ACTION_SEARCH -> "搜索"
            action == EditorInfo.IME_ACTION_SEND -> "发送"
            action == EditorInfo.IME_ACTION_NEXT -> "下一项"
            action == EditorInfo.IME_ACTION_DONE -> "完成"
            else -> "换行"
        }
        uiState.value = uiState.value.copy(enterKeyText = enterText)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        inlineSuggestionManager?.clear()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        // 会话结束：引擎 ascii 归位默认中文，英文态不跨会话残留
        asciiModeController.restoreDefaultChoice()
        clearInputState()
        recentClipboardItemsState.value = emptyList()
    }
    
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // 手写模型轻量，按"用键盘时加载、键盘收起即卸载"管理：输入会话结束
        // （收起键盘/焦点离开）即释放，:inference 侧同步卸载模型；未初始化时
        // release() 幂等空操作。下次落笔由 predict 自愈或布局重建重载。
        com.kingzcheung.xime.handwriting.HandwritingEngine.release()
    }

    /**
     * [onWindowHidden] 的延迟清理句柄。
     *
     * 宿主调 `restartInput()`（QQ 发送/撤回消息后刷新输入框）时，键盘窗口常会**短促**
     * 隐藏再显示。若隐藏时就 `clearInputState()`，重显时要整套重建（面板/页面/候选全没了），
     * 视觉上就是「闪一下再弹出」。这里延迟清理，窗口重新显示就取消。
     */
    private var windowHiddenCleanup: Runnable? = null

    /**
     * 短促隐藏的判定窗口：超过这个时长才认为是用户真的收起了键盘。
     *
     * 实测（QQ 撤回消息）：`onStartInputView` → `onWindowHidden` → 约 1.7s → 再
     * `onStartInputView` + `onWindowShown`。600ms 挡不住，会把状态清掉再重建，
     * 视觉上就是闪一下。取 3s 覆盖这种抖动；真正收起键盘 3s 后仍会清理。
     */
    private val WINDOW_HIDDEN_CLEANUP_DELAY_MS = 3000L

    override fun onWindowHidden() {
        super.onWindowHidden()
        FileLogger.i(TAG, "onWindowHidden: cleanup scheduled in ${WINDOW_HIDDEN_CLEANUP_DELAY_MS}ms")
        // 收起键盘立即清空候选栏：不等 3 秒延迟清理，否则下次弹出的一瞬间
        // 仍会看到上一次的候选/编码残留。只清显示层与 native 组合态；
        // 面板、语音等重状态仍交给下面的延迟清理，宿主 restartInput 造成的
        // 短促隐藏重显时不会整屏重建（保留 3.2.80 修过的「闪一下」）。
        candidateState.value = candidateState.value.copy(
            candidates = emptyList(),
            candidateComments = emptyList(),
            associationCandidates = emptyList(),
            pendingEnglishText = "",
            hasNextPage = false,
            hasPrevPage = false,
            candidateActions = emptyList(),
            inputText = "",
            isComposing = false,
        )
        rimeEngine.clearComposition()
        windowHiddenCleanup?.let { mainHandler.removeCallbacks(it) }
        val cleanup = Runnable {
            windowHiddenCleanup = null
            FileLogger.i(TAG, "windowHidden cleanup: clearInputState")
            clearInputState()
            recentClipboardItemsState.value = emptyList()
            // 键盘隐藏时重置快捷发送表单临时态：表单开启状态下直接隐藏键盘（未走 onClose）
            // 会把 showQuickSendForm 残留到下次弹窗，候选栏上方渲染出残留表单背景造成遮挡；
            // 回车键文案也需一并还原（打开表单时被改为"确定"）。
            if (uiState.value.showQuickSendForm || uiState.value.quickSendFormFocused) {
                uiState.value = uiState.value.copy(
                    showQuickSendForm = false,
                    quickSendFormFocused = false,
                    quickSendCodeFocused = false,
                    quickSendEditingItemId = null,
                    quickSendEditingItemText = "",
                    quickSendEditingItemCode = "",
                    enterKeyText = "发送",
                )
                QuickSendFormEditTextHolder.editText = null
                QuickSendFormCodeEditTextHolder.editText = null
            }
        }
        windowHiddenCleanup = cleanup
        mainHandler.postDelayed(cleanup, WINDOW_HIDDEN_CLEANUP_DELAY_MS)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        val cancelled = windowHiddenCleanup != null
        windowHiddenCleanup?.let { mainHandler.removeCallbacks(it) }
        windowHiddenCleanup = null
        FileLogger.i(TAG, "onWindowShown: cancelledPendingCleanup=$cancelled")
        // 键盘刚显示的**这一帧**不要做取色对比与剪贴板同步 IPC：
        // 两者都在主线程，会把首帧推后，表现为弹出时「闪一下」。
        // 推到 decorView 的下一帧执行，效果不变（取色/同步都是异步生效的）。
        window?.window?.decorView?.post {
            KeyboardThemes.refreshDynamicSchemes(this)
            clipboardSyncBridge?.pullOnce()
        }
    }
    
    private fun clearInputState() {
        closeToolPanel()
        // 输入会话结束：关闭残留的面板页面（表情/符号等 overlay），
        // 避免下次键盘弹出时在候选栏上方渲染上次的面板背景
        var page = keyboardViewModel.page.value
        while (page is com.kingzcheung.xime.keyboard.KeyboardPage.Overlay) {
            keyboardViewModel.closeOverlay()
            page = keyboardViewModel.page.value
        }
        calculatorEngine.clear()
        rimeEngine.clearComposition()
        t9PartialSegments.clear()
        // 输入法隐藏/结束输入：静默停止语音会话，丢弃未识别文本，避免迟到结果写入新输入框
        if (uiState.value.isVoiceMode || voiceRecordingStarted) {
            voiceRecognitionHandler.abandonSession()
            voiceRecognitionHandler.stopRecognition()
            voiceRecognitionHandler.cancelPreStart()
            isTrackingVoiceButtons = false
            voiceRecordingStarted = false
            voiceAmplitudeState.floatValue = 0f
            uiState.value = uiState.value.copy(
                isVoiceMode = false,
                voiceSticky = false,
                voiceButtonState = VoiceButtonState(),
                voiceRecognitionState = RecognitionState.IDLE,
                voiceRecognizedText = "",
                voiceAmplitude = 0f
            )
            keyboardViewModel.exitVoice()
        }
        uiState.value = uiState.value.copy(
            t9ResetSignal = uiState.value.t9ResetSignal + 1,
            t9RightCandidateSelectedCount = 0,
            t9SelectedCandidatePinyin = ""
        )
        candidateState.value = candidateState.value.copy(
            candidates = emptyList(),
            candidateComments = emptyList(),
            inputText = "",
            isComposing = false,
            isShowingRecentClipboard = false,
            associationCandidates = emptyList(),
            pendingEnglishText = "",
            hasNextPage = false,
            hasPrevPage = false,
            englishReplaceSupported = true,
            candidateActions = emptyList()
        )
        endComposingInputBox()
    }

    /**
     * 输入框是否存在 IME 写入的 composing 区域（拼音编码回显 / 语音识别临时文本）。
     * Android 无法查询宿主编辑器的 composing 状态，由写入点主动标记。
     */
    private var inputBoxComposingActive = false
    /** 上次写入输入框 composing 区的文本与光标参数：内容未变时跳过重复 setComposingText IPC。 */
    internal var lastInputBoxComposingText: String? = null
    internal var lastInputBoxComposingCaret: Int = Int.MIN_VALUE

    /** 标记刚向输入框写入了 composing 文本（showInputBoxComposition / 语音 partial）。 */
    internal fun markInputBoxComposing() {
        inputBoxComposingActive = true
    }

    /**
     * 「退格后显示编码」时间窗终点。
     *
     * 优化「首候选显示在输入框」这个功能：该功能让输入框显示**首候选汉字**，
     * 好处是边打边预览将上屏的词，但**退格时**汉字整块换掉，用户看不到编码在
     * 逐字减少，体感像"一下清空"。
     *
     * 这里在退格后的短暂窗口内改为显示**编码**，让逐字回退可见；
     * 窗口结束自动恢复显示候选词（原功能不受影响）。
     */
    @Volatile
    internal var forceCodeUntilMs = 0L

    internal val FORCE_CODE_WINDOW_MS = 1500L

    internal fun isForceCodeWindowActive(): Boolean =
        System.currentTimeMillis() < forceCodeUntilMs

    internal fun beginForceCodeWindow() {
        forceCodeUntilMs = System.currentTimeMillis() + FORCE_CODE_WINDOW_MS
    }

    /**
     * 提前结束「退格后显示编码」窗口。
     *
     * 用户 2026-11 要求：点一下删除键后输入框短暂显示编码（逐字回退可见）是对的，
     * 但只要接着按下任意编码键，就应当立刻恢复「首候选词显示在输入框」——不能等
     * 满 [FORCE_CODE_WINDOW_MS] 才切回去，否则继续打字时输入框里一直是裸露的编码。
     */
    internal fun endForceCodeWindow() {
        forceCodeUntilMs = 0L
    }

    /** 只读：输入框 composing 区当前是否有我们写入的内容（删除路径判断用）。 */
    internal val inputBoxComposingActiveForDelete: Boolean
        get() = inputBoxComposingActive

    /**
     * 清理输入框中的 composing 区域（未上屏的拼音编码 / 语音临时文本）。
     *
     * 无论输入位置设置（输入框/候选栏）都执行。
     * 注意：composing 区域不存在时 [InputConnection.setComposingText] 并非空操作——
     * 它会在光标处"插入"空串，光标处有选中文字时等于删除整个选区
     * （收起键盘/焦点切换误删选中文字的根因）。因此仅在标记过 composing 时才清空，
     * 否则只调用 finishComposingText（无 composing 时是无害空操作，仅兜底清理残留 span）。
     */
    internal fun endComposingInputBox(forceClear: Boolean = false) {
        currentInputConnection?.let {
            // forceClear：调用方确知刚写过 composing（如命令候选的标签），但标记可能不准
            // （写入被容错吞掉 / 焦点变化抢先重置），此时强制清空，避免标签被
            // finishComposingText 提交成普通文本残留在输入框。
            if (forceClear || inputBoxComposingActive) {
                it.setComposingText("", 0)
            }
            it.finishComposingText()
        }
        inputBoxComposingActive = false
        lastInputBoxComposingText = null
        lastInputBoxComposingCaret = Int.MIN_VALUE
    }

    /**
     * 当前输入框是否受限（密码/终端/NO_SUGGESTIONS，见 EditorInfoClassifier）：
     * 英文联想等补全类功能应短路。
     */
    internal fun isEditorRestricted(): Boolean = editorRestricted

    internal fun isSecretEditor(): Boolean = editorSecret

    /**
     * 当前宿主是否支持英文候选的"回删替换"机制。
     *
     * 英文直接上屏模式下，选中候选词需要 deleteSurroundingText 回删已上屏编码再提交候选词；
     * 终端等受限宿主对该能力（含文本探测接口）通常不支持，探针返回 null。
     * 此类宿主直接不提供英文联想候选。
     */
    internal fun supportsEnglishCandidateReplace(): Boolean {
        val session = uiState.value.inputSessionId
        if (session == englishReplaceProbeSession) return englishReplaceProbeResult
        // 无活动输入连接时不缓存：此时必然 false，会话恢复后应重新探测
        val ic = currentInputConnection ?: return false
        val supported = runCatching { ic.getTextBeforeCursor(1, 0) != null }
            .getOrDefault(false)
        englishReplaceProbeSession = session
        englishReplaceProbeResult = supported
        return supported
    }

    /**
     * [supportsEnglishCandidateReplace] 的会话级缓存（-1 = 尚未探测）。
     *
     * getTextBeforeCursor 是跨进程 IPC（0.1~1ms 级），而本方法被按键热路径调用 4 处
     * （ImeKeyRouter.refreshAfterKey 每按键、1196 行、ImeSessionController 444 行等），
     * 英文输入时几乎每按键一次。宿主能力在同一输入会话内不会变化，故按
     * [InputUIState.inputSessionId] 缓存；切换输入框后 sessionId 更新即自动失效重探。
     */
    private var englishReplaceProbeSession: Long = -1L
    private var englishReplaceProbeResult: Boolean = false

    override fun onDestroy() {
        super.onDestroy()
        sharedPrefsListener?.let {
            SettingsPreferences.getPrefsPublic(this).unregisterOnSharedPreferenceChangeListener(it)
        }
        RimeEngine.setDeploymentCallback { _, _ -> }
        stopClipboardSync()
        if (::clipboardManager.isInitialized) {
            clipboardManager.release()
        }
        _viewModelStore.clear()
        feedbackManager.release()
        // 只结束会话，**不** finalize 引擎：切换输入法会让系统销毁/重建本服务，
        // 而 RimeEngine 是进程级单例。若在此 finalize，切回本输入法时重建服务会再次
        // startup → rime->initialize()，librime 同进程内“finalize 后再 initialize”
        // 并不可靠，真机表现就是「从别的输入法切回本输入法闪退」。
        // 引擎保留在进程里，切回时 ensureSession 直接复用（见 RimeEngine.endSession）。
        rimeEngine.endSession()
        AssociationManager.release()
        voiceRecognitionHandler.release()
        com.kingzcheung.xime.handwriting.HandwritingEngine.release()
        ExtensionManager.release()
        com.kingzcheung.xime.association.NativeOnnxEngine.releaseSharedEnv()
        serviceScope.cancel()
        keyProcessingDispatcher.close()
        // 词频/词库防抖未到期的直接落盘，避免服务销毁丢掉最近几次上屏统计。
        // 只撤自己的那一条：mainHandler 还挂着别的 UI/防抖任务，全清会连带取消它们
        mainHandler.removeCallbacks(wordFreqSaveRunnable)
        mainHandler.removeCallbacks(autoWordDeployRunnable)
        wordFreqSavePending = false
        saveWordData()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
    
    internal fun hideKeyboard() {
        clearInputState()
        requestHideSelf(0)
    }
    
    /**
     * 候选排序面板换位后保存词频（写全序）：把该编码下的候选 [ordered] 整体并入
     * 本地词频表并写盘，随后按新表立即重排当前候选（下次同编码输入也延续该顺序）。
     *
     * 需在主线程调用（面板换位回调路径）。
     */
    internal fun saveCandidateOrder(code: String, ordered: List<String>) {
        if (ordered.isEmpty()) return
        // 词库未装载完时内存表是空表占位，落盘会覆盖掉磁盘上的真实排序表
        if (!wordTablesLoaded) return
        // 先吃进用户在配置目录里的手改，再叠加本次拖动结果。
        // 全程持锁：与 IO 线程的 saveWordData 互斥，避免旧快照回写覆盖（见 wordTablesLock）。
        wordTablesLock.lock()
        try {
            candidateOrderTable = CandidateOrderStore.reconcile(this, candidateOrderTable)
            candidateOrderTable = CandidateOrderStore.withFullOrder(candidateOrderTable, code, ordered)
            CandidateOrderStore.save(this, candidateOrderTable)
        } finally {
            wordTablesLock.unlock()
        }
        updateUI()
    }

    internal fun updateUI() {
        val composition = rimeEngine.getComposition()
        // 候选词变换（hotPath 插件能力）：仅 key-processing 线程同步等插件（至多 15ms），
        // 主线程调用点（联想上屏/光标移动/剪贴板点选后的刷新）一律跳过——主线程永不等待插件；
        // 这些调用点组合态已清空（input 为空），正常不触发，Looper 判定仅为防御
        val transformed = if (composition.input.isNotEmpty() &&
            android.os.Looper.myLooper() != android.os.Looper.getMainLooper()
        ) {
            candidateTransform.transform(
                inputText = composition.input,
                preedit = composition.preedit,
                engineCandidates = composition.candidates.toList(),
                asciiMode = composition.isAsciiMode,
            )
        } else {
            null
        }
        if (transformed != null) {
            sessionController.applyComposition(
                composition.copy(candidates = transformed.candidates.toTypedArray()),
                transformed.actions
            )
        } else {
            sessionController.applyComposition(composition)
        }
    }

    /**
     * 用户开始输入时清除候选栏中的 inline suggestions，让位于正常输入候选。
     */
    internal fun dismissInlineSuggestions() {
        inlineSuggestionManager?.clear()
    }


    

    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (inlineSuggestionManager == null) return null
        updateInlineSuggestionTheme()
        val result = inlineSuggestionManager.onCreateInlineSuggestionsRequest(uiExtras)
        return result
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return inlineSuggestionManager?.onInlineSuggestionsResponse(response) ?: false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun updateInlineSuggestionTheme() {
        val state = uiState.value
        val isDark = when (state.darkMode) {
            1 -> true
            2 -> (resources.configuration.uiMode.and(
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
            )) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> false
        }
        val t = com.kingzcheung.xime.ui.theme.KeyboardThemes
        inlineSuggestionManager?.apply {
            val c = t.getCandidateTextColor(state.themeId, isDark)
            candidateTextColorArgb = (c.alpha * 255).toInt() shl 24 or
                (c.red * 255).toInt() shl 16 or
                (c.green * 255).toInt() shl 8 or
                (c.blue * 255).toInt()
            val label = c.copy(alpha = 0.6f)
            labelTextColorArgb = (label.alpha * 255).toInt() shl 24 or
                (label.red * 255).toInt() shl 16 or
                (label.green * 255).toInt() shl 8 or
                (label.blue * 255).toInt()
            isDarkTheme = isDark
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        val state = uiState.value
        if (state.isCompact) {
            try {
                val decor = window.window?.decorView
                if (decor != null) {
                    val navBarBg = decor.findViewById<View>(android.R.id.navigationBarBackground)
                    val navBarH = navBarBg?.height ?: 0
                    val h = (decor.height - navBarH).coerceAtLeast(0)
                    outInsets.contentTopInsets = h
                    outInsets.visibleTopInsets = h
                    outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
                    return
                }
            } catch (_: Exception) { }
            super.onComputeInsets(outInsets)
        } else if (state.isFloatingMode) {
            outInsets.apply {
                contentTopInsets = resources.displayMetrics.heightPixels
                visibleTopInsets = resources.displayMetrics.heightPixels
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                // 卡片矩形以 onCardPositioned 实测（窗口坐标）为唯一真源；
                // 首帧实测前用 FloatingCardGeometry 兜底推算。此前在这里按
                // "0.85×窗口宽 + 公式卡高"重推，横屏下宽度直接错（0.85×长边
                // ≠ 实际 0.85×短边）、高度随公式值漂移，触摸区与卡片错位。
                val bounds = floatingCardBounds?.takeIf { it.heightPx > 0 } ?: run {
                    val density = resources.displayMetrics.density
                    val decor = window.window?.decorView
                    val windowWidthPx = decor?.width?.takeIf { it > 0 }
                        ?: resources.displayMetrics.widthPixels
                    val statusBarHeightDp = tryGetStatusBarHeightDp(this@XimeInputMethodService, window.window)
                    val windowHeightPx = (resources.displayMetrics.heightPixels -
                        (statusBarHeightDp * density).toInt()).coerceAtLeast(1)
                    val config = resources.configuration
                    FloatingCardGeometry.fallbackBounds(
                        windowWidthPx = windowWidthPx,
                        windowHeightPx = windowHeightPx,
                        offsetXdp = geometryState.value.floatingOffsetX,
                        offsetYdp = geometryState.value.floatingOffsetY,
                        cardWidthDp = FloatingCardGeometry.cardWidthDpForConfig(config.screenWidthDp, config.screenHeightDp),
                        cardHeightDp = FloatingCardGeometry.fallbackCardHeightDp(
                            SettingsPreferences.getKeyboardHeightDp(

                                this@XimeInputMethodService,

                                config.screenWidthDp > config.screenHeightDp,
                                state.isFloatingMode,

                            ).coerceAtMost((config.screenHeightDp * 8) / 10),
                            state.keyboardBottomPaddingDp,
                        ),
                        density = density,
                    )
                }
                touchableRegion.set(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
        } else {
            // 非浮动模式：窗口全屏，容器物理高度 = Compose 内容总高（含底部留白），
            // 容器在窗口内底部对齐（gravity BOTTOM）。
            // contentTopInsets 直接用容器顶部在窗口中的 y 同步计算：
            // 容器高度变化（面板撑高/收起）→ View relayout → traversal → 本方法
            // 自动以新几何重算并上报，无需 hack；窗口全屏时 super 会误判键盘占满
            // 全屏导致布局下沉，故必须显式报告容器顶部。
            if (::keyboardContainer.isInitialized && keyboardContainer.height > 0) {
                val loc = IntArray(2)
                keyboardContainer.getLocationInWindow(loc)
                val topPx = loc[1].coerceAtLeast(0)
                outInsets.contentTopInsets = topPx
                outInsets.visibleTopInsets = topPx
                outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            } else {
                super.onComputeInsets(outInsets)
            }
        }
    }

    /** 插件下行事件投递器（input_changed / text_committed，敏感输入豁免）。 */
    internal val pluginEvents = PluginEventDispatcher(this)

    /** 候选词变换协调器（插件 candidate_transform 能力，hotPath：key-processing 线程同步调用）。 */
    internal val candidateTransform = CandidateTransformCoordinator(this)

    override fun commitText(text: String) {
        // 上屏即作废「清空/删除撤回」槽：
        // 否则「上滑清空 → 打字 → 单击删除 → 下滑撤回」会把很久以前清空的整段内容插回来
        // （单击删除不再刷新撤回槽，旧槽会一直存活）。
        lastClearedText = ""
        lastUndoAnchorText = null
        commitTextAndPredict(text, isPaste = false)
    }

    /**
     * 粘贴性质上屏（键盘剪贴板点选/编辑面板提交）：与 [commitText] 相同的上屏与
     * 联想行为，但 text_committed 事件带 is_paste 标记——事件语义是"文本上屏"
     * （照常投递给所有订阅插件），是否把粘贴计入打字量由插件自行决定
     * （typing-stats 过滤，用户反馈"一天一万多字"的主要来源即长文本粘贴）。
     */
    internal fun commitPastedText(text: String) {
        commitTextAndPredict(text, isPaste = true)
    }

    private fun commitTextAndPredict(text: String, isPaste: Boolean) {
        commitTextSilently(text, isPaste)
        if (isChineseMode) {
            mainHandler.post {
                if (!uiState.value.isAsciiMode) {
                    getPredictionFromPlugin(predictionManager.lastCommittedText)
                }
            }
        }
    }

    /**
     * 快捷发送表单内退格：按焦点路由到文本框/触发编码框，删除光标前字符或选区。
     * 与原行为对齐：表单显示即处理（未聚焦时删文本框），焦点在编码框时删编码框。
     * 需在主线程调用；返回 false 表示表单未显示（调用方继续常规退格流程）。
     */
    internal fun deleteInQuickSendForm(): Boolean {
        if (!uiState.value.showQuickSendForm) return false
        val et = if (uiState.value.quickSendFormFocused && uiState.value.quickSendCodeFocused)
            QuickSendFormCodeEditTextHolder.editText
        else QuickSendFormEditTextHolder.editText
        et?.let { box ->
            val start = box.selectionStart.coerceAtLeast(0)
            val end = box.selectionEnd.coerceAtLeast(start)
            if (end > start) {
                box.text?.delete(start, end)
                try { box.setSelection(start) } catch (e: Exception) { FileLogger.d(TAG, "box.setSelection(start) ignored: ${e.message}") }
            } else if (start > 0) {
                box.text?.delete(start - 1, start)
                try { box.setSelection(start - 1) } catch (e: Exception) { FileLogger.d(TAG, "box.setSelection(start-1) ignored: ${e.message}") }
            }
        }
        return true
    }

    /**
     * 静默上屏：与 [commitText] 相同的落盘路径（内部编辑器重定向、InputConnection、
     * text_committed 事件、联想上下文/输入统计），但不触发联想推理。
     * 手写叠写自动上屏/替换使用——笔画替换频率高，逐次推理既浪费又会闪烁候选栏。
     * [isPaste] 标记粘贴性质上屏，透传到 text_committed payload（见 commitPastedText）。
     * 需在主线程调用。
     */
    internal fun commitTextSilently(text: String, isPaste: Boolean = false) {
        // 会话日志：上屏文本（用于把按键序列与其最终结果对齐）
        KeyTapLogger.recordCommit(text, isPaste)
        if (uiState.value.quickSendFormFocused) {
            // 焦点在触发编码输入框时路由到编码框，否则路由到快捷发送文本框
            val codeFocused = uiState.value.quickSendCodeFocused
            mainHandler.post {
                val et = if (codeFocused) QuickSendFormCodeEditTextHolder.editText
                else QuickSendFormEditTextHolder.editText
                et?.let { box ->
                    val start = box.selectionStart.coerceAtLeast(0)
                    val textLen = text.length
                    box.text?.replace(start, box.selectionEnd.coerceAtLeast(start), text)
                    try { box.setSelection(start + textLen) } catch (e: Exception) { FileLogger.d(TAG, "box.setSelection(start + textLen) ignored: ${e.message}") }
                }
            }
            return
        }
        if (uiState.value.toolPanelInputFocused) {
            mainHandler.post {
                ToolPanelEditTextHolder.editText?.let { et ->
                    val start = et.selectionStart.coerceAtLeast(0)
                    val textLen = text.length
                    et.text?.replace(start, et.selectionEnd.coerceAtLeast(start), text)
                    try { et.setSelection(start + textLen) } catch (e: Exception) { FileLogger.d(TAG, "et.setSelection(start + textLen) ignored: ${e.message}") }
                }
            }
            return
        }
        currentInputConnection?.commitText(text, 1)

        // 上屏词频统计：真实上屏后按当前编码计入统计表（自动造词与候选排序的数据源）
        recordWordFrequency(text, isPaste)

        // text_committed 事件：真实上屏才累计/投递（内部编辑器分支已在上方 return；
        // 敏感输入框（密码）不计不投；粘贴性质上屏带 is_paste 标记，见 commitPastedText；
        // 详见 PluginEventDispatcher）
        pluginEvents.onTextCommitted(text, isPaste)

        if (isChineseMode) {
            predictionManager.appendCommittedText(text)
            predictionManager.recordInput(text)
        }

        // 单次上屏记录（中英两模式都维护）：供换行键上滑「重复上一次输入」
        rememberCommitChunk(text)
    }


    /**
     * 记下**单次**上屏文本，供换行键上滑的「重复上一次输入」使用。
     *
     * 不能复用 [PredictionManager.lastCommittedText]：那是最近 25 字的滚动上下文，
     * 直接重提会把前文一起再打一遍。这里只记最近一笔，中英两模式都维护。
     */
    private fun rememberCommitChunk(text: String) {
        predictionManager.appendCommittedChunk(text)
    }

    // ── 上屏词频统计（自动造词与候选排序的数据源） ──

    /** 词频防抖落盘延迟（ms）：合并高频上屏的多次写盘，避免每键一次 IO。 */
    private val wordFreqSaveDebounceMs: Long = 1500

    private var wordFreqSavePending = false

    /**
     * 晋升自动部署（2026-11-10 用户规则 A）：晋升进固定词库的词只有编译进
     * table.bin 才会**按码表顺序**出现在引擎候选里（而非被 mergeAutoWordCandidates
     * 追加到候选尾部）。晋升发生 → 置标志 → 落盘后触发一次静默增量部署+重载。
     */
    @Volatile
    private var autoWordDeployPending = false

    /** 上次晋升自动部署的时间戳（节流用：60s 内只部署一次，重载会重建 session）。 */
    @Volatile
    private var lastAutoWordDeployAt = 0L

    /** 晋升部署的重试计数（最多 ~60s，等用户停手/无组合态的窗口再部署）。 */
    private var autoWordDeployRetryCount = 0

    /**
     * 晋升自动部署的重试任务：每 3 秒检查一次「无组合态 + 节流窗口已过」，
     * 命中就把 auto_word 编译进 table 并重载 session。
     *
     * 为什么不只在 saveWordData（防抖落盘）里判断：上屏后用户往往立刻继续打字，
     * 落盘时几乎总是有组合态，部署被无限推迟（实测隔了近 30 分钟才触发一次）。
     * 主动轮询空闲窗口才能真正「晋升后很快生效」。
     */
    private val autoWordDeployRunnable: Runnable = Runnable {
        if (!autoWordDeployPending) {
            autoWordDeployRetryCount = 0
            return@Runnable
        }
        val now = System.currentTimeMillis()
        val composing = candidateState.value.isComposing ||
            candidateState.value.preeditText.isNotEmpty()
        if (composing || now - lastAutoWordDeployAt < 60_000L) {
            // 有组合态 / 节流窗口内：稍后重试（最多 20 次 ≈ 60s；超了就留给下次晋升/落盘）
            if (autoWordDeployRetryCount < 20) {
                autoWordDeployRetryCount++
                // lambda 里的 this 是外层 Service，重排自身要用字段引用
                mainHandler.postDelayed(autoWordDeployRunnable, 3_000L)
            } else {
                autoWordDeployRetryCount = 0
            }
            return@Runnable
        }
        autoWordDeployRetryCount = 0
        autoWordDeployPending = false
        lastAutoWordDeployAt = now
        serviceScope.launch(Dispatchers.IO) {
            try {
                FileLogger.i(TAG, "auto_word: 晋升触发静默增量部署（词进 table，按码表顺序）")
                val ok = RimeConfigHelper.ensureDeployment(this@XimeInputMethodService)
                if (ok) {
                    keyRouter.postRimeJob {
                        val schema = rimeEngine.getCurrentSchema()
                        if (schema.isNotEmpty()) rimeEngine.switchSchema(schema)
                        withContext(Dispatchers.Main) { updateUI() }
                    }
                } else {
                    FileLogger.w(TAG, "auto_word: 晋升自动部署失败，下次晋升/重载再试")
                }
            } catch (t: Throwable) {
                FileLogger.e(TAG, "auto_word: 晋升自动部署异常", t)
            }
        }
    }

    /**
     * 组合生词的**累积窗口**：最近若干次上屏的汉字段（按上屏顺序）。
     *
     * 为什么不能只存「上一次上屏」（旧实现 `lastCommittedWord`）：
     * 逐字打「小」「霞」「霞」时，旧逻辑每次组合后只把左半边置为最后一段：
     *   · 「霞」上屏 → 拼出「小霞」，随后 lastCommittedWord = "霞"（「小」被丢弃）
     *   · 第二个「霞」→ 只能拼出「霞霞」，「小霞霞」永远拼不出来
     * 也就是说组合链是「滑动窗口 1」，只能覆盖相邻两段。
     *
     * 改为保留最近 [comboWindowMaxChars] 个字的上屏历史后，每次上屏都与历史的
     * **每个后缀**拼接，于是「小」+「霞」+「霞」能同时得到「霞霞」（后缀"霞"）
     * 与「小霞霞」（后缀"小霞"），符合「能组的都组出来」的要求。
     *
     * 只在本次上屏全为汉字时推进；粘贴/英文/标点/反查等会清空窗口，避免跨界拼接。
     */
    private val comboHistory = ArrayList<String>()

    /**
     * 当前候选栏正在展示的剪贴板条目 id（用于「按键盘输入后统一标记消费」）。
     *
     * 语义（用户明确）：
     * - 复制后条目**每次进入输入框都会展示**（只要没被消费）；
     * - 用户**点了键盘产生输入**后才消失，并对这些 id 调 markConsumedById → 之后任何应用
     *   都不再显示；
     * - 只切应用、没敲键盘 → 下次进输入框仍展示（这是期望行为）。
     */
    internal val showingClipboardIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())


    /** 组合窗口保留的最大字数（按码点）：与 combo_max_chars 同量级，多留一点供长词后缀。 */
    private val comboWindowMaxChars: Int = 8

    /**
     * 最近一次**从候选栏选中**的词（点选/数字选/造词候选/空格上屏首候选）。
     *
     * 晋升计数要求「整个词在候选栏出现并被完整上屏」：上屏后候选快照往往已被清空，
     * 此时用本标记兜底判断本次提交是否就是那个候选；没有标记的提交（如输入框里
     * 预览残留、光标移动丢弃组合态带入的内容）不计选中次数。
     */
    private var lastSelectedCandidateWord: String? = null
    /** 记录本次从候选栏选中的词，见 [lastSelectedCandidateWord]。 */
    internal fun markCandidateSelection(word: String) {
        if (word.isNotEmpty()) {
            lastSelectedCandidateWord = word
            // 同步快照当前编码：上屏后 candidateState 常已被清空，靠这里保住编码，
            // 否则 word_freq.txt 会因 code 为空而漏记（见 recordWordFrequency 的兜底）。
            val code = candidateState.value.inputText.ifEmpty { candidateState.value.preeditText }
            if (code.isNotEmpty()) lastSelectedCandidateCode = code
        }
    }

    /**
     * 最近一次选中候选时的**输入编码**快照。
     *
     * 为什么需要它：候选点选/空格上屏后 `candidateState` 往往已被清空（inputText/preedit
     * 都为空），此时 [recordWordFrequency] 再读当前编码只能拿到空串，`WordFreqStore.withCommit`
     * 遇空编码直接返回原表——用户明确选中的词就这么漏出了词频统计。
     * 上屏前在 [markCandidateSelection] 里存一份编码，上屏时优先用它兜底。
     */
    private var lastSelectedCandidateCode: String? = null

    /**
     * 已启用方案词库的**词集合**（方案词典 `.dict.yaml` 词条 + 该方案用户词典
     * `<词典名>.userdb` 自造词），普通上屏词收录查重用：
     * 词在启用方案词库里已能打出来，无需再收录生成一份。
     * 引擎读 userdb 持 rimeLock 独占，故缓存只能异步刷新（见 [refreshSchemeDictWords]），
     * 上屏路径只读内存，绝不触发引擎调用。
     */
    @Volatile
    private var schemeDictWords: Set<String> = emptySet()

    /**
     * 方案**主词典**词条集合（3.3.22）：只含 Flypy/XGuai_user 这类真词库，
     * 不含 word_freq/auto_word/candidate_order 派生库。
     *
     * 用途：组合生词的「词典已有不造」判据。原来用 [schemeDictWords]（含派生库）
     * 有两个问题：
     * 1. 派生库本身是用户可管理的（删词/清库），拿它当依据，用户删掉的词
     *    永远学不回来（内存快照陈旧时尤甚，实测「制式」案例）；
     * 2. word_freq 上屏即写、条目巨多，任何真词库没有的词都先被词频表"收录"过
     *    一次，等于组合生词被词频表完全挡死。
     */
    private var schemeRootWords: Set<String> = emptySet()

    /** 当前方案词典「词 → 编码串」（多码空格分隔，仅 2~4 字词），组合生词编码级去重用。 */
    @Volatile
    private var schemeRootWordCodes: Map<String, String> = emptyMap()

    /** App 自己生成的派生词库名：它们由词频/造词/排序表生成，不算"真词库"。 */
    private val DERIVED_DICT_NAMES = setOf(
        "word_freq", "auto_word", "candidate_order",
    )
    /**
     * 声明了 `nofreq` 标记的词典词条（表头注释 `# nofreq: true` 或 `#@/nofreq`）。
     *
     * 这些词来自用户声明「不进词频」的词典（如个人词库 XGuai_user.dict.yaml），
     * 上屏时**跳过词频记录**（word_freq.txt 不收录），
     * 但仍是方案词典的正常候选（能打出来、能手动排序）。
     *
     * 与 [schemeDictWords] 同批加载（同一份码表），上屏路径只读内存。
     */
    @Volatile
    private var noFreqWords: Set<String> = emptySet()
    /**
     * 声明了 `no_auto_word` 标记 / 配置了 `auto_word.no_auto_word_dicts` 的词典词条
     * （表头注释 `# no_auto_word: true`，或在 xime.yaml 的 `auto_word.no_auto_word_dicts`
     * 里列出词典名，如 `XGuai_user`）。
     *
     * 这些词上屏时**跳过自动造词链**（不进临时/固定词库、不参与组合生词），
     * 但仍是方案词典的正常候选（能打出来、能手动排序、能进词频）。
     *
     * 与 [schemeDictWords] 同批加载（同一份码表），上屏路径只读内存。
     */
    @Volatile
    private var noAutoWordWords: Set<String> = emptySet()

    /**
     * 已启用方案的「全部词条」集合（[schemeDictWords]）与「单字 → 满四码」索引（[schemeCharCodes]）。
     * 两者同批加载（同一份码表），上屏路径只读内存。
     */
    @Volatile
    private var schemeCharCodes: Map<String, List<String>> = emptyMap()

    /**
     * 词库解析结果缓存：词集、排除集、单字满四码索引。
     * 与 [dictSnapshot] 配套——文件未变时直接复用，避免重解析数十万行码表。
     */
    private data class SchemeDictCache(
        val words: Set<String>,
        val noFreq: Set<String>,
        val noAutoWord: Set<String>,
        val charCodes: Map<String, List<String>>,
        /** 当前方案主词典及其 import_tables 的词条；不含其它启用方案和派生词库。 */
        val rootWords: Set<String> = emptySet(),
        /** 当前方案 translator.dictionary；切换方案时必须重建 rootWords。 */
        val rootSchema: String = "",
        /**
         * 当前方案词典的「词 → 词典编码串」（多码空格分隔，仅 2~4 字词）。
         *
         * 用于组合生词的**编码级**去重：词典里有该词、但词典编码不含造词规则
         * 生成的编码时（如「置顶」词典码 vdd，造词规则码是「置前2码+顶前2码」），
         * 仍应允许造词码——否则用户按造词规则打码永远搜不到该词（2026-11-10 反馈）。
         */
        val rootWordCodes: Map<String, String> = emptyMap(),
    )

    /** 解析成功的词库文件快照：(绝对路径, 最后修改时间, 字节数)。 */
    private var dictSnapshot: List<Triple<String, Long, Long>>? = null
    /** 最近一次解析结果（文件未变时直接复用）。 */
    private var schemeDictCache: SchemeDictCache? = null
    /** 上次触发刷新的时间戳（节流：输入框切换频繁，不必每次都读）。 */
    private var lastSchemeDictRefreshAt = 0L
    /** 词库最短刷新间隔（ms）：文件未变会直接跳过解析，这里只兜住同次运行内反复触发。 */
    private val schemeDictRefreshIntervalMs = 300_000L
    /** 刷新协程是否在跑：并发触发（引擎就绪 force + onStartInput）时避免重复解析。 */
    @Volatile
    private var schemeDictRefreshRunning = false

    /**
     * 异步刷新 [schemeDictWords] / [schemeCharCodes]。
     * 收集当前启用方案引用的各词典（`.dict.yaml`，随 import_tables 递归展开）全部词条
     * + 各词典用户词典（`.userdb`）自造词。
     *
     * 性能要点：
     * - 流式收集：边读边处理，不堆几十万 DictEntry 对象。
     * - 文件快照缓存：词典 (mtime, size) 未变时直接复用上次结果，跳过整轮重解析。
     * - 并发守卫：force 与 onStartInput 同时触发时只解析一次。
     *
     * 引擎未就绪或解析失败保持原缓存（收录退化为不查重）。
     *
     * @param force 方案切换/引擎就绪时强制刷新，忽略 [schemeDictRefreshIntervalMs] 节流
     */
    private fun refreshSchemeDictWords(force: Boolean = false) {
        if (!com.kingzcheung.xime.rime.RimeEngine.isInitialized()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSchemeDictRefreshAt < schemeDictRefreshIntervalMs) return
        if (schemeDictRefreshRunning) return
        lastSchemeDictRefreshAt = now
        schemeDictRefreshRunning = true
        serviceScope.launch(Dispatchers.IO) {
            try {
                runCatching { doRefreshSchemeDictWords() }
            } finally {
                schemeDictRefreshRunning = false
            }
        }
    }

    /** [refreshSchemeDictWords] 解析主体（IO 线程）。 */
    private fun doRefreshSchemeDictWords() {
        val ctx = this@XimeInputMethodService
        val rimeDir = com.kingzcheung.xime.settings.SchemaManager.getRimeDir(ctx)
        // 根词典：各启用方案的主词典（translator.dictionary）。
        val rootDictNames = com.kingzcheung.xime.settings.SchemaManager
            .getEnabledSchemas(ctx)
            .mapNotNull { com.kingzcheung.xime.settings.SchemaManager.getReferencedDictName(ctx, it) }
            .distinct()
        if (rootDictNames.isEmpty()) return
        val activeSchemaId = com.kingzcheung.xime.settings.SettingsPreferences.getCurrentSchema(ctx)
        val activeRootDict = com.kingzcheung.xime.settings.SchemaManager.getReferencedDictName(ctx, activeSchemaId)
            ?.takeIf { it in rootDictNames }.orEmpty()

        // 快照命中：所有词典文件都没动过，且活动主方案未变时，直接复用上次结果。
        // rootWords 只属于活动方案；不能拿“所有启用方案的并集”做组合过滤，
        // 否则拼音/五笔词典有的词会误挡小鹤方案学词（例如「制式」在拼音词库里）。
        val cached = dictSnapshot
        if (cached != null && cached.isNotEmpty() &&
            schemeDictCache?.rootSchema == activeRootDict &&
            cached.all { (path, mtime, size) ->
                val f = java.io.File(path)
                f.isFile && f.lastModified() == mtime && f.length() == size
            }
        ) {
            schemeDictCache?.let { applySchemeDictCache(it) }
            return
        }

        val cfg = com.kingzcheung.xime.settings.KeysConfigHelper.getAutoWordConfig()
        val cfgNoFreqDicts = cfg.noFreqDicts.toHashSet()
        val cfgNoAutoWordDicts = cfg.noAutoWordDicts.toHashSet()
        // 各词典排除标记：xime.yaml 配置优先，词典表头标记其次。
        val noFreqByDict = HashMap<String, Boolean>()
        val noAutoWordByDict = HashMap<String, Boolean>()

        val words = LinkedHashSet<String>()
        val rootWords = LinkedHashSet<String>()
        val noFreq = LinkedHashSet<String>()
        val noAutoWord = LinkedHashSet<String>()
        val snapshot = ArrayList<Triple<String, Long, Long>>()
        val loadedNames = ArrayList<String>()

        com.kingzcheung.xime.settings.DictionaryHelper.collectEntriesStreaming(
            rootDicts = rootDictNames,
            readDict = { name ->
                val f = java.io.File(rimeDir, "$name.dict.yaml")
                if (!f.isFile) return@collectEntriesStreaming null
                snapshot += Triple(f.absolutePath, f.lastModified(), f.length())
                loadedNames += name
                f.readText(Charsets.UTF_8)
            },
            onDictStart = { name, headerNoFreq, headerNoAutoWord ->
                if (name in cfgNoFreqDicts || headerNoFreq) noFreqByDict[name] = true
                if (name in cfgNoAutoWordDicts || headerNoAutoWord) noAutoWordByDict[name] = true
            },
            onEntry = { name, word, code ->
                if (word.isEmpty()) return@collectEntriesStreaming
                // 排除 app 派生库（word_freq / auto_word / candidate_order）：这些是本功能
                // 自身生成/统计的库。若把它们的词条计入 words（=schemeDictWords），组合生词
                // 一进库就会命中下方的「词典已有」判据（recordWordFrequencyLocked 的
                // text !in schemeDictWords），count 永远停在 0、无法晋升固定词库
                // （实测 bug：组合词被选满设定次数仍不进阶，根因即此）。
                // 这里需要的语义是「用户在启用方案主词典里本来就能打出的词」，派生库不算。
                // 与下方 rootWords 的排除口径一致（3.3.22 已对 rootWords 修，此处补齐）。
                if (name !in DERIVED_DICT_NAMES) words.add(word)
                // 排除集按词典级别判定：该词典声明了 nofreq / no_auto_word 才排除其词条。
                if (noFreqByDict[name] == true) noFreq.add(word)
                if (noAutoWordByDict[name] == true) noAutoWord.add(word)
                // 单字码不再从主词典混杂收集：改由专名单字表（auto_word.char_dict，
                // 缺省 Flypy_char）提供。主码表里单字的简码与全码混在一起，拿它当造词
                // 基底会编出用户实际打不出来的码；纯单字表（每字一行满四码）才可靠。
            },
        )

        // 各词典用户词典（userdb）自造词。
        for (name in loadedNames) {
            runCatching {
                for (entry in com.kingzcheung.xime.settings.UserDictIoManager.readEntries(name)) {
                    if (entry.word.isNotEmpty()) words.add(entry.word)
                }
            }
        }

        // 单独收集当前活动方案的真词条闭包，用作组合生词去重判据。
        // 仅查当前方案；排除 app 派生词典 word_freq / auto_word / candidate_order。
        val rootWordCodes = HashMap<String, String>()
        if (activeRootDict.isNotEmpty()) {
            com.kingzcheung.xime.settings.DictionaryHelper.collectEntriesStreaming(
                rootDicts = listOf(activeRootDict),
                readDict = { name ->
                    val f = java.io.File(rimeDir, "$name.dict.yaml")
                    if (!f.isFile) null else f.readText(Charsets.UTF_8)
                },
                onEntry = { name, word, code ->
                    if (word.isNotEmpty() && name !in DERIVED_DICT_NAMES) {
                        rootWords.add(word)
                        // 词 → 词典编码（多码空格追加）：仅组合生词可能产出的 2~4 字词
                        val len = word.codePointCount(0, word.length)
                        if (len in 2..4 && code.isNotEmpty()) {
                            rootWordCodes[word] = rootWordCodes[word]?.let { "$it $code" } ?: code
                        }
                    }
                },
            )
        }

        val charCodes = SchemeCodeIndex.buildCharCodes(loadCharDictEntries(cfg.charDict, snapshot))
        val result = SchemeDictCache(
            words, noFreq, noAutoWord, charCodes, rootWords, activeRootDict, rootWordCodes,
        )
        dictSnapshot = snapshot.toList()
        schemeDictCache = result
        applySchemeDictCache(result)
        FileLogger.d(
            TAG,
            "scheme dict refreshed: ${words.size} words, ${charCodes.size} chars from ${loadedNames.joinToString()}"
        )
    }

    /**
     * 读自动造词的单字码基底：[name] 为词典名（不含 `.dict.yaml`），返回 (字, 码) 序列。
     *
     * 只读这一张表，不递归 import_tables：造词基底必须干净（每字一行满四码），
     * 递归会把主码表的简码混进来。文件不存在或读失败返回空序列，此时造词自然停摆
     * （编不出码 = 不造该词），不会退回乱码基底。
     *
     * 兼容可见目录与私有目录：先用 UserConfigDir 取可见侧，取不到再试 rime 私有目录。
     */
    private fun loadCharDictEntries(
        name: String,
        snapshot: MutableList<Triple<String, Long, Long>>? = null,
    ): Sequence<Pair<String, String>> {
        if (name.isBlank()) return emptySequence()
        val ctx = this@XimeInputMethodService
        val fileName = "$name.dict.yaml"
        // 优先可见目录（UserConfigDir.readText），其次 rime 私有目录；命中哪侧就把
        // 该侧文件记入快照，否则改了单字表也不会触发下一轮重建（缓存会一直用旧码）。
        val visibleText = com.kingzcheung.xime.settings.UserConfigDir.readText(ctx, fileName)
        if (visibleText != null) {
            // 可见侧快照：stamp 只给 (秒, 字节数)，路径用「可见目录 + 文件名」拼出，
            // 与主词典快照的绝对路径同形，缓存命中判定才能一致。
            com.kingzcheung.xime.settings.UserConfigDir.stamp(ctx, fileName)?.let { (m, sz) ->
                val abs = java.io.File(
                    com.kingzcheung.xime.settings.UserConfigDir.absoluteDir, fileName
                ).absolutePath
                snapshot?.add(Triple(abs, m * 1000L, sz))
            }
            return parseCharEntries(visibleText, name)
        }
        val dir = com.kingzcheung.xime.settings.SchemaManager.getRimeDir(ctx)
        val file = java.io.File(dir, fileName)
        if (!file.isFile) return emptySequence()
        snapshot?.add(Triple(file.absolutePath, file.lastModified(), file.length()))
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: return emptySequence()
        return parseCharEntries(text, name)
    }

    /**
     * 解析单字表文本为 (字, 码) 序列，并打日志。
     *
     * 用 [DictionaryHelper.parseCodeTable] 而不是 parseDictEntries：后者要求文件里有
     * Rime 头部的 `...` 分隔符才把后续行当词条，而纯单字表（如 Flypy_char.dict.yaml）
     * 往往直接就是 `字<TAB>码` 两列、没有 `...`，用 parseDictEntries 会解析出 0 条。
     */
    private fun parseCharEntries(text: String, name: String): Sequence<Pair<String, String>> {
        val entries = ArrayList<Pair<String, String>>()
        for (e in com.kingzcheung.xime.settings.DictionaryHelper.parseCodeTable(text)) {
            if (e.word.isNotEmpty() && e.code.isNotEmpty()) entries += e.word to e.code
        }
        FileLogger.d(TAG, "char dict '$name' loaded: ${entries.size} entries")
        return entries.asSequence()
    }

    /** 把解析结果刷到字段（均为 volatile / 原子赋值，主线程读安全）。 */
    private fun applySchemeDictCache(c: SchemeDictCache) {
        schemeDictWords = c.words
        schemeRootWords = c.rootWords
        schemeRootWordCodes = c.rootWordCodes
        noFreqWords = c.noFreq
        noAutoWordWords = c.noAutoWord
        schemeCharCodes = c.charCodes
    }
    /**
     * 文本是否全为**主方案单字码表能打出**的简体字。
     *
     * 简繁切换上屏的繁体词不进词库：主方案是简体音形，繁体词（如「樣」「說」「麼」）
     * 不在 Flypy_char 单字码表里（编不出码），记进词库只会产出打不出来的组合
     * （实测收录过「怎麼樣」「怎麼說」）。
     *
     * 判据：用内存里的主方案单字码索引（schemeCharCodes，源 Flypy_char 8842 简体字）
     * 而非 GB2312 编码测试——Android 的 GB2312 实现把繁体也算可编码，判定失效；
     * 且 GB2312 会误滤 GBK 简体生僻字（囧/腌臜 等用户实际打得出的字）。
     *
     * 非 CJK 字符（ASCII/拉丁/常见标点）不参与判定；码表未加载时空判据通过。
     */
    private fun isSimplifiedText(text: String): Boolean {
        if (text.isEmpty()) return true
        if (schemeCharCodes.isEmpty()) return true
        for (ch in text) {
            val cp = ch.code
            if (cp < 0x2E80) continue  // ASCII / 拉丁 / CJK 符号区不判
            if (!schemeCharCodes.containsKey(ch.toString())) return false
        }
        return true
    }

    /**
     * 记录一次上屏词频：以上屏瞬间的编码（inputText/preeditText）为键，
     * 上屏文本为词，计数 +1。
     *
     * 过滤：粘贴、密码框、T9（已有 t9Memorize 调频体系）、英文模式不计——
     * 只统计中文纯引擎输入的词，与候选排序应用范围一致。
     *
     * 同时联动自动造词：词进临时词库，窗口内攒够次数即晋升固定词库
     * （见 [AutoWordStore.record] / [AutoWordStore.promote]）。
     */
    private fun recordWordFrequency(text: String, isPaste: Boolean) {
        // 与 saveWordData（IO 线程落盘）/ reconcileUserDataFiles 互斥：
        // 「读内存表 → 累加计数/造词晋升 → 写回」必须原子，否则 IO 线程回写的旧快照
        // 会把这次上屏统计覆盖掉（丢失更新，见 [wordTablesLock]）。
        // ReentrantLock 可重入：内部调 recordComboWord / recordAutoWord 再取锁不会自锁。
        wordTablesLock.lock()
        try {
            recordWordFrequencyLocked(text, isPaste)
        } finally {
            wordTablesLock.unlock()
        }
    }

    /** [recordWordFrequency] 的实际实现；调用方必须已持有 [wordTablesLock]。 */
    private fun recordWordFrequencyLocked(text: String, isPaste: Boolean) {
        if (isPaste || editorSecret) return
        if (isT9Schema(uiState.value.currentSchemaId)) return
        if (uiState.value.isAsciiMode) return
        // 大写金额 / 计算器：候选栏展示的是程序生成的结果，和输入编码没有任何关系，
        // 记进词频就是脏数据（用户实测：输入 ma 的候选里出现「肆元整」「伍元整」）。
        val cs = candidateState.value
        if (cs.capitalAmountAmount.isNotEmpty() || cs.capitalAmountExpression.isNotEmpty()) return
        if (calculatorEngine.isActive()) return
        // 声明了 nofreq 的词典词条（表头 `# nofreq: true` / `#@/nofreq`，如个人词库
        // XGuai_user.dict.yaml）：不参与上屏词频统计（word_freq.txt/yaml 不收录），
        // 但仍走自动造词链（能正常学习/组合）。只在词频层面跳过。
        if (text in noFreqWords) return
        // 繁体上屏（简繁切换后）不进词库：主方案是简体音形，繁体词的编码映射
        // 无意义，记进词库只会产出打不出来的组合（实测收录过「怎麼樣」「覺得」）。
        // 组合链一并清空，避免繁体词混入后续拼接。
        if (!isSimplifiedText(text)) {
            comboHistory.clear()
            return
        }
        // 自动造词开关（xime.yaml auto_word.enabled）：关闭时不做任何收录/统计
        val autoWordConfig = com.kingzcheung.xime.settings.KeysConfigHelper.getAutoWordConfig()
        if (!autoWordConfig.enabled) return
        // 兜底编码只属于「刚被选中的那个候选」。
        // 金额整串重写（commitTextSilently）、手写叠写、语音上屏这些**非候选**上屏如果
        // 也借用它，就会把上一条候选的编码写到这些文本头上——实测污染过：
        // ma → 肆元整 / 伍元整 / 曲、l → 国。这里要求「本次上屏的文本就是刚选中的候选词」
        // 才允许用快照编码。
        val code = candidateState.value.inputText
            .ifEmpty { candidateState.value.preeditText }
            .ifEmpty {
                if (lastSelectedCandidateWord == text) lastSelectedCandidateCode.orEmpty() else ""
            }
        // 反查模式（` 引导符）上屏的字**不进词频、不造词**：
        // 反查是「按字找码」，上屏的字与 ` 后面的码没有任何输入法编码关系，
        // 记进词频会写入 `` `aaek `` / `` hc` `` 这类脏编码（历史已污染过 9 类条目），
        // 造词也会拼出不存在的词。这里从入口一刀切断：
        // 既拦截 `  引导的输入码，也拦截反查候选的快捷码（[lastSelectedCandidateCode] 兜底值）。
        if (code.contains('`')) {
            // 反查上屏同样要打断组合链：它不是正常输入的字词序列。
            lastSelectedCandidateWord = null
            lastSelectedCandidateCode = null
            comboHistory.clear()
            return
        }
        // 本次上屏的词在启用方案里能打出的**全部**编码（按三个总规则组合）。
        // 造词基底只用该字的**满四码**条目（简码不参与），多音字会得到多个组合码。
        // 单字返回空（单字本身不用造词），但**不影响组合链**：见下方的 advanceComboHistory。
        val schemeCodes = SchemeCodeIndex.composeCodes(text, schemeCharCodes)
        // 用户明确从候选栏选中的词必须进词频表：编码缺失时按方案规则取一个
        // （短码优先，即组合里最实用的那个）。单字无组合码，用其满四码单字码兜底；
        // 只有规则也编不出（无满四码）时才放弃。
        val effectiveCode = code.ifEmpty {
            when {
                lastSelectedCandidateWord == text && schemeCodes.isNotEmpty() ->
                    schemeCodes.firstOrNull().orEmpty()
                // 单字（composeCodes 返回空）：用该字的满四码单字码兜底，保证单字也进词频
                lastSelectedCandidateWord == text ->
                    schemeCharCodes[text]?.firstOrNull().orEmpty()
                else -> ""
            }
        }
        if (effectiveCode.isEmpty()) {
            lastSelectedCandidateWord = null
            lastSelectedCandidateCode = null
            // 词频可以不记（编不出码），但**组合生词与组合链必须照常推进**：
            // 单字上屏（composeCodes 返回空）是最常见的组合场景（「一」+「键」→「一键」），
            // 若在这里直接 return，组合逻辑永远走不到，表现为「字词上屏不组词」。
            recordComboWord(text, autoWordConfig)
            // 把本次上屏推进组合窗口，下一次（及更后面的上屏）才能与它拼接。
            advanceComboHistory(text)
            return
        }
        // 词频就落在**本次上屏对应的编码**上：输入框/候选快照里的实际编码
        // （[markCandidateSelection] 已把候选对应的编码快照进 lastSelectedCandidateCode，
        // 上屏后 candidateState 清空也能兜底）。不替换成词自身方案码——用户在什么编码下
        // 选中的词，词频就记在什么编码下，下次按同样编码打，候选排序能吃到这次统计。
        val freqCode = code.ifEmpty { effectiveCode }
        val next = WordFreqStore.withCommit(wordFreqTable, freqCode, text)
        if (next !== wordFreqTable) {
            wordFreqTable = next
        }
        // 自动造词：临时词库记录，随后晋升/淘汰。
        // 词已在启用方案的词库里（方案词典 .dict.yaml 或用户词典 .userdb）时不收录。
        // 晋升统计「用户选中次数」（候选点选/空格上屏）：
        // - 词已在临时词库 → 这次上屏算一次选中，计数 +1；
        // - 词已在固定词库 → 已学成，不再计数；
        // - 首次上屏的新词 → 收录（首次上屏算一次选中，初始 1 次）。
        //
        // 计数门槛：只有「该词整个出现在候选栏并被完整上屏」才计一次。
        // 候选快照已被上屏清空时（真实上屏的常见终态），用本次选中的词
        // （[lastSelectedCandidateWord]）兜底；没有候选来源的提交——只是嵌在输入框里
        // 预览、或只上屏了词的一部分——都不算选中，不参与晋升计数。
        val selectedWord = lastSelectedCandidateWord
        lastSelectedCandidateWord = null
        // 编码快照与词快照同生命周期：留着会在下一次「非候选上屏」时被误用。
        lastSelectedCandidateCode = null
        val shownCandidates = candidateState.value.candidates
        val committedWholeWordFromCandidates =
            text in shownCandidates || (shownCandidates.isEmpty() && text == selectedWord)
        // 计数判据与组合生词去重**同口径（编码级）**（用户 2026-11-10）：
        // - 词典没有该词 → 学；
        // - 词典有该词、且词典编码已含本次上屏编码（用户打的就是词典码）→ 不学（能打出来，无需造）；
        // - 词典有该词、但本次编码不是词典编码（按造词规则打造词码，如「置顶」的词典码
        //   是 vdd、本次是造词码）→ **学**，否则造词进了 temp 却永远计不上数、晋升不了
        //   （与 recordComboWord 编码级去重配套的闭环）；
        // - 词典有该词但无编码映射（单字/>4字词，映射只建 2~4 字）→ 保守不学（同原行为）。
        val inRootDict = text in schemeRootWords
        val dictCodeStr = schemeRootWordCodes[text]
        val dictAlreadyHasThisCode = when {
            !inRootDict -> false
            dictCodeStr == null -> true
            else -> freqCode.isNotEmpty() && " $dictCodeStr ".contains(" $freqCode ")
        }
        if (committedWholeWordFromCandidates && !dictAlreadyHasThisCode) {
            // 该词在方案里能打出的全部编码（满四码基底 + 三个总规则）；
            // 任一字没有满四码时就编不出来，此时回退实际输入码（保证仍能学）。
            val codes = schemeCodes.ifEmpty { listOf(effectiveCode) }
            // 已收录过的（任意一个编码下命中）→ 这次上屏算一次选中，只在**已存在**的
            // 编码上计数，避免每次上屏把多余编码也刷一遍；已晋升固定词库则不再计数。
            val recordedCodes = codes.filter { autoWordTemp[it]?.containsKey(text) == true }
            val actualCode = freqCode.ifEmpty { codes.first() }
            when {
                // 已收录过：在已存在的编码上计数，并把实际按键编码作为晋升时的首选
                recordedCodes.isNotEmpty() ->
                    recordAutoWord(text, recordedCodes, autoWordConfig, preferCode = actualCode)
                codes.any { autoWordFixed[it]?.containsKey(text) == true } -> Unit
                // 新词只记**用户实际敲的那个编码**（freqCode）：此前把该词全部理论编码
                // 一起入库，3 字词 2-3 个码、多音字更多，导致词库条数按编码数倍数膨胀。
                // 只记实际编码既贴合用户习惯，也不会造出没人会打的冗余条目。
                else -> recordAutoWord(
                    text,
                    listOf(actualCode),
                    autoWordConfig,
                    preferCode = actualCode,
                )
            }
        }
        // 组合生词（与编码能否生成无关，两条上屏路径共用 [recordComboWord]）。
        recordComboWord(text, autoWordConfig)
        // 本次上屏推进组合窗口：汉字词入窗（之后可与后续上屏拼出更长的词），
        // 非汉字（粘贴、英文、标点）清空窗口，避免跨界拼接。
        advanceComboHistory(text)
        scheduleWordFreqSave()
    }

    /**
     * 组合生词：把上一次上屏的词与本次上屏的词拼接成新词进临时词库
     * （「现在」+「目录」→「现在目录」，长度由 combo_min/max_chars 控制）。
     *
     * 编码按方案单字规则从满四码拼出；任一字没有满四码就不造（编不出来等于打不出来）。
     * 组合产生的词初始次数为 0：它只是候选，被用户**选中上屏**后才开始计数
     * （见 [AutoWordStore.record]），累计到 promote_count 次晋升固定词库。
     *
     * 无条件执行（只要 comboEnabled 且前后两词都是汉字）：单字上屏是最常见的组合
     * 场景（「一」+「键」→「一键」），不能因为「单字编不出组合码」就跳过拼接。
     */
    private fun recordComboWord(text: String, cfg: com.kingzcheung.xime.settings.AutoWordConfig) {
        if (!cfg.comboEnabled) return
        if (!AutoWordStore.isComboWord(text)) return
        // 与累积窗口的**每个后缀**拼接，而不是只与「上一次上屏」拼接。
        // 逐字打「小」「霞」「霞」时窗口为 [小, 霞]，本次 text = 霞：
        //   · 后缀 "霞"   → 「霞霞」（2 字）
        //   · 后缀 "小霞" → 「小霞霞」（3 字）
        // 旧实现只保留最后一段，只能拼出前者，「小霞霞」永远学不到。
        val comboWords = AutoWordStore.comboWithHistory(
            history = comboHistory,
            word = text,
            minChars = cfg.comboMinChars,
            maxChars = cfg.comboMaxChars,
            // 用户规则（2026-11-09）：词库已有的词不参与组合生词。
            // 词典能直接打出的词不该再拼进造词库——否则打「你想怎么处理这件事」
            // 会产出「想怎么」「处理这」「件事」这类跨词边界碎片（实测 6 词 14 编码全废）。
            isDictWord = { w -> w in schemeRootWords },
            dictWordFilterEnabled = cfg.comboDictFilter,
        )
        for (comboText in comboWords) {
            // 该词按方案单字规则能打出的**全部**编码，一条不落收录：
            // 2 字词 = 各字前 2 码（多音字会得到多个组合）；
            // 3 字词 = 两种形态（各字首码 / 前两字首码 + 第三字前 2 码）。
            // 不能只取 firstOrNull()：composeCodes 按「短码优先」排序，3 字词的
            // 4 码形态永远排在后面被丢掉，用户按 4 码打时搜不到该词，
            // 表现为「3 字两种编码只有一种生效」。
            val comboCodes = SchemeCodeIndex.composeCodes(comboText, schemeCharCodes)
            if (comboCodes.isEmpty()) continue
            // 词典去重改为**编码级**（用户 2026-11-10）：只在「词典已有该词、
            // 且词典编码里已含某个造词规则编码」时跳过（避免同一词同一码重复候选）；
            // 词典有该词但编码不同（如「置顶」词典码 vdd，造词规则码是置前2码+顶前2码）
            // → 仍造词，让用户按造词规则打码也能搜到（否则两头堵：词典码打不出造词码、
            // 造词又因「词典已有」被跳过）。编码串空格分隔，两端加空格做边界匹配防前缀误判。
            val dictCodesStr = schemeRootWordCodes[comboText]
            if (dictCodesStr != null && comboCodes.any { " $dictCodesStr ".contains(" $it ") }) {
                continue
            }
            recordAutoWord(comboText, comboCodes, cfg, initialCount = 0)
        }
    }

    /**
     * 推进组合窗口：把本次上屏记入历史，并裁掉超出 [comboWindowMaxChars] 的旧段。
     *
     * 非汉字上屏（粘贴、英文、数字、标点）会**清空**窗口——跨过这些内容拼接没有意义，
     * 还会把噪音粘进词库。反查上屏同理（见调用点）。
     */
    private fun advanceComboHistory(text: String) {
        if (!AutoWordStore.isComboWord(text)) {
            comboHistory.clear()
            return
        }
        comboHistory.add(text)
        // 按码点裁剪：从头部丢段，直到总字数不超过窗口上限
        var total = comboHistory.sumOf { it.codePointCount(0, it.length) }
        while (comboHistory.size > 1 && total > comboWindowMaxChars) {
            val removed = comboHistory.removeAt(0)
            total -= removed.codePointCount(0, removed.length)
        }
    }

    /**
     * 收录一个自动造词：按 [codes] 逐个进临时词库并晋升。
     *
     * **一词一个编码**：调用方只传该词最实用的那一条（[codes] 正常只有 1 个元素）。
     * 早先会把多音字组合出的全部编码一起入库，固定词库因此出现「一词多音」
     * （同一个词挂 3-4 个编码），已按一词一码收口；这里保留遍历只为兼容历史调用。
     * 编码为空（组合词编不出来）时不收录。
     *
     * [initialCount] 新条目的初始次数：普通收录 1（首次上屏算一次候选选中），
     * 组合生词 0（自动拼接的词还没被用户选中过）。晋升判定为 count >= promote_count。
     * 造词不注入快捷短语（custom_phrase.txt）：候选由 [mergeAutoWordCandidates] 直接合并。
     */
    private fun recordAutoWord(
        word: String,
        codes: List<String>,
        config: com.kingzcheung.xime.settings.AutoWordConfig,
        initialCount: Int = 1,
        /** 本次用户实际按下的编码：晋升固定词库时只收这一个（一词一码）。 */
        preferCode: String = "",
    ) {
        // 声明了 no_auto_word / 配置了 auto_word.no_auto_word_dicts 的词典词条
        // （如个人词库 XGuai_user.dict.yaml）：不参与自动造词链——
        // 不进临时/固定词库、不参与组合生词。但仍是方案词典正常候选。
        if (word in noAutoWordWords) return
        // 批量收录：多个编码只复制一次外层表（见 AutoWordStore.recordMany 注释）。
        // 旧实现对每个编码各调一次 record，组合词 16 码 = 16 次整表浅复制（~1MB 短命对象）。
        val next = AutoWordStore.recordMany(autoWordTemp, codes, word, nowForRecord(), initialCount)
        val changed = next !== autoWordTemp
        if (changed) autoWordTemp = next
        // 晋升（节流，避免每次上屏全表遍历，见 [fullPromoteIntervalMs] 注释）：
        // - 距上次全量超时 → 全量 promote（晋升 + 顺带淘汰超窗口的陈旧词）；
        // - 未超时 → 只增量晋升本次词（promoteWord，O(该词编码数)）。
        if (changed) {
            val now = System.currentTimeMillis()
            val promotion = if (now - lastFullPromoteTime >= fullPromoteIntervalMs) {
                lastFullPromoteTime = now
                AutoWordStore.promote(
                    autoWordTemp, autoWordFixed, now,
                    windowDays = config.promoteDays,
                    minCount = config.promoteCount,
                    // 一词一码：固定词库只收用户实际按的那个编码，避免多音字一词多音
                    preferCode = preferCode,
                )
            } else {
                AutoWordStore.promoteWord(
                    autoWordTemp, autoWordFixed, now, word,
                    minCount = config.promoteCount,
                    preferCode = preferCode,
                )
            }
            autoWordTemp = promotion.temp
            autoWordFixed = promotion.fixed
            if (promotion.promoted.isNotEmpty()) {
                // 有词晋升：标记待部署并启动重试链（等组合态空闲窗口执行静默增量部署，
                // 让晋升词尽快进 table.bin、按码表顺序出现在候选里）
                autoWordDeployPending = true
                mainHandler.removeCallbacks(autoWordDeployRunnable)
                autoWordDeployRetryCount = 0
                mainHandler.postDelayed(autoWordDeployRunnable, 3_000L)
            }
        }
    }

    /** 统一的当前毫秒时间戳（record 内多次调用保持一致性，避免跨毫秒边界）。 */
    private fun nowForRecord(): Long = System.currentTimeMillis()

    /**
     * 把自动造词（临时+固定词库）中编码匹配当前输入的词合并进候选尾部。
     *
     * 造词不经过 rime、也不写进快捷短语（custom_phrase.txt）：显示候选时由输入法
     * 直接把两库的词按当前编码合入（完整编码优先；编码没打完时按前缀命中，
     * 见 [AutoWordStore.candidatesFor]），点选走插件候选（直接上屏）路径。这样临时词库的
     * 词也能被选中上屏，晋升统计的是「用户选中次数」（见 [recordWordFrequency]）。
     *
     * 仅非 T9、非 ascii 的中文组合态合并；无匹配词时返回 null（不干预候选）。
     *
     * @return 合并后的候选/注释/动作三元组（动作尾部补 plugin 直接上屏）
     */
    internal fun mergeAutoWordCandidates(
        code: String,
        engineCandidates: List<String>,
        engineComments: List<String>,
        engineActions: List<CandidateAction>,
        isT9: Boolean,
        isAscii: Boolean,
    ): CandidateOrderStore.Reordered? {
        if (isT9 || isAscii || code.isEmpty()) return null
        val autoWordConfig = com.kingzcheung.xime.settings.KeysConfigHelper.getAutoWordConfig()
        if (!autoWordConfig.enabled) return null
        val extra = AutoWordStore.hitsFor(
            temp = autoWordTemp,
            fixed = autoWordFixed,
            code = code,
            known = engineCandidates.toHashSet(),
            prefixMatch = autoWordConfig.prefixMatch,
        )
        if (extra.isEmpty()) return null
        // 引擎候选无显式 actions 时（非 T9 无插件变换：显示 index 即引擎 index），
        // 补齐占位 engine(i)，保证 actions 与合并后候选完全对齐——否则造词候选的
        // plugin 动作会错位到引擎候选位置，点选造词静默失败（selectRimeOk=false）。
        val baseActions = if (engineActions.size >= engineCandidates.size) {
            engineActions
        } else {
            engineCandidates.indices.map { CandidateAction.engine(it) }
        }
        // 临时词（未晋升的自动造词）在候选栏以「◌」前缀区分，见
        // [CandidateState.autoWordTempIndices]。这里只记录**下标**，不改候选文本：
        // 候选文本还要被上屏、黑名单、输入框首词等路径消费，「◌」绝不能混进文本。
        // 固定词库（已晋升）不加标记，与引擎候选一致。
        val tempIndices = HashSet<Int>()
        extra.forEachIndexed { i, hit ->
            if (hit.fromTemp) tempIndices += engineCandidates.size + i
        }
        return CandidateOrderStore.Reordered(
            candidates = engineCandidates + extra.map { it.word },
            comments = engineComments + extra.map { "" },
            actions = baseActions + extra.map { CandidateAction.plugin(it.word) },
            autoWordTempIndices = tempIndices,
        )
    }

    /**
     * 判断 [word] 是否为当前编码下的自动造词（临时或固定词库）。
     *
     * 候选合并同时写入平行的 actions，正常情况下点选走 plugin 动作；此判断作为
     * 兜底：actions 万一未同步（防御），按“词在造词表”也能直接上屏。
     */
    internal fun isAutoWordCandidate(word: String, code: String): Boolean =
        code.isNotEmpty() && (
            autoWordTemp[code]?.containsKey(word) == true ||
                autoWordFixed[code]?.containsKey(word) == true
            )

    /**
     * 把一个词（连同删除时的编码）加入黑名单并落盘：之后该编码下它不再出现在候选栏
     * （显示层屏蔽，见 [WordBlacklistStore.filter]）。
     *
     * 长按删除走的是 librime 的 tombstone，只对用户词典生效——方案词典与自造词库的词
     * 删了还会再冒出来，因此删除必须同时进黑名单。
     *
     * [code] 传删除时的当前输入编码；为空时按词全局屏蔽（所有编码下都不再出现）。
     */
    internal fun addWordBlacklist(word: String, code: String = "") {
        if (word.isEmpty()) return
        // 词库未装载完时内存表是空表占位，落盘会覆盖掉磁盘上的真实黑名单
        if (!wordTablesLoaded) return
        // 先吃进用户在配置目录里的手改（黑名单文件也可能刚被改过），再加本次删除的词。
        // 全程持锁：与 IO 线程的 saveWordData 互斥，避免旧快照回写覆盖（见 wordTablesLock）。
        wordTablesLock.lock()
        try {
            wordBlacklist = WordBlacklistStore.reconcile(this, wordBlacklist)
            val next = WordBlacklistStore.withBlocked(wordBlacklist, word, code)
            if (next === wordBlacklist) return
            wordBlacklist = next
            WordBlacklistStore.save(this, next)
        } finally {
            wordTablesLock.unlock()
        }
        FileLogger.i(TAG, "WordBlacklist: added '$word' code='$code'")
    }

    /**
     * 迁移清理（一次性、幂等）：移除旧版本注入到快捷短语（custom_phrase.txt）的自动造词。
     *
     * 自动造词已改为候选合并通道，不再占用快捷短语。custom_phrase 里与固定词库
     * （auto_word.txt）重叠的词就是旧版注入的造词，清掉后快捷短语只留用户手写内容
     * （手写词不在 auto_word.txt 里，不会误删）。
     *
     * 引擎就绪后调用（需 IO + 增量部署）；失败静默，下次启动再试。
     */
    private fun cleanupAutoWordFromCustomPhrase() {
        val schemaId = uiState.value.currentSchemaId
        if (schemaId.isBlank()) return
        val autoWords = HashSet<String>()
        for ((_, words) in autoWordFixed) autoWords.addAll(words.keys)
        if (autoWords.isEmpty()) return
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val existing = com.kingzcheung.xime.settings.PersonalDictManager
                    .loadCustomPhrases(this@XimeInputMethodService, schemaId)
                val kept = existing.filter { it.word !in autoWords }
                if (kept.size == existing.size) return@launch
                com.kingzcheung.xime.settings.PersonalDictManager.saveCustomPhrases(
                    this@XimeInputMethodService, schemaId, kept
                )
                com.kingzcheung.xime.settings.PersonalDictManager.ensureSchemaPack(this@XimeInputMethodService, schemaId)
                rimeEngine.deployIncremental()
            }
        }
    }

    /**
     * 防抖落盘：延迟期间多次上屏只写一次，服务销毁前强制写。
     * 词频表、临时词库、固定词库三者同批写入。
     */
    private fun scheduleWordFreqSave() {
        if (wordFreqSavePending) return
        wordFreqSavePending = true
        mainHandler.postDelayed(wordFreqSaveRunnable, wordFreqSaveDebounceMs)
    }

    /** 防抖到期的写盘任务；持有引用才能只撤它自己（见 onDestroy）。 */
    private val wordFreqSaveRunnable = Runnable {
        wordFreqSavePending = false
        // 写盘（用户手改合并 + 词频/词库三件套落盘 + rime dict 重写）挪到 IO 线程，
        // 防抖到期的瞬间不阻塞主线程（打字停顿/上屏时不再出现一次顿挫）。
        serviceScope.launch(Dispatchers.IO) { saveWordData() }
    }

    /**
     * 立即写盘词频与词库三件套（防抖到期或服务销毁时调用）。
     *
     * save 内部会先合并用户在配置目录 `/storage/emulated/0/rime/xime/` 里的手改
     * （见 [UserDataMerge]），返回值必须采纳——不采纳的话下次保存又会冲掉。
     */
    private fun saveWordData() {
        // 词库还没从磁盘装载完（onCreate 中的 IO 任务未完成，见 [wordTablesLoaded]）：
        // 此刻内存里是空表占位，落盘会把空的词频/造词/排序**覆盖掉真实数据**。
        // 直接跳过——装载完成后会有正常的上屏防抖与退出保存把内容写出去。
        if (!wordTablesLoaded) {
            FileLogger.w(TAG, "saveWordData skipped: 词库尚未装载完成，避免空表覆盖磁盘")
            return
        }
        // 与主线程的 recordWordFrequency / reconcileUserDataFiles 互斥：
        // 「读内存表 → 合并手改 → 写盘 → 回写合并结果」必须原子，否则主线程刚记的
        // 上屏计数会被本方法读到的旧快照回写覆盖（丢失更新，见 wordTablesLock 注释）。
        wordTablesLock.lock()
        try {
            wordFreqTable = WordFreqStore.save(this, wordFreqTable)
            autoWordTemp = AutoWordStore.saveTemp(this, autoWordTemp)
            autoWordFixed = AutoWordStore.saveFixed(this, autoWordFixed)
            // 同步生成 rime 派生词典 auto_word.dict.yaml：方案 import_tables 引用它，
            // 缺这一步时引擎永远编译旧快照（删掉的词照旧出现）。
            AutoWordStore.ensureFixedRimeDict(this, autoWordFixed)
        } finally {
            wordTablesLock.unlock()
        }
        // 自 v3.2.57 起：词频**不再触发任何部署/编译**。
        //
        // 词频对候选顺序的影响已改为 Kotlin 层实时重排（ImeSessionController 里按
        // wordFreqTable 降序，见 CandidateOrderStore.reorder），上屏即生效、零编译。
        // 因此 word_freq.dict.yaml 变新不再需要 librime 重编 table.bin——那一步是
        // 「增量部署后打不了字要等一会」的根源（部署独占 rimeLock 3 秒以上）。
        //
        // 这里仍保留 word_freq.dict.yaml 的生成（供用户查看/备份、以及手动重载时的
        // 兜底排序），只是不再因为它的变化去触发部署。

        // 晋升自动部署（2026-11-10 用户规则 A）：固定词库刚落盘（auto_word.dict.yaml
        // 是 import_tables 引用的），触发一次**静默增量部署 + 重载**让晋升词进
        // table.bin——此后它由引擎按码表顺序提供（mergeAutoWordCandidates 的 seen
        // 会跳过引擎已有词，不再追加尾部）。节流 60s：部署+重载会短暂重建 session。
        // 晋升落盘后顺手推一次重试链（真正执行由 autoWordDeployRunnable 等空闲窗口），
        // 保证「只有落盘成功后才部署」也能被覆盖到。
        if (autoWordDeployPending) {
            mainHandler.removeCallbacks(autoWordDeployRunnable)
            autoWordDeployRetryCount = 0
            mainHandler.postDelayed(autoWordDeployRunnable, 1_000L)
        }
    }

    /**
     * 合并用户在配置目录下的手改（词频/造词/排序/黑名单五件套），只合并不写盘。
     *
     * 由「重载」入口调用：reloadConfig 只重载 rime 与键盘配置，词库表本来不重读，
     * 用户手改要靠这一步才进得了内存（合并不了的话，下次保存还会被冲掉）。
     * 合并后内存表即包含文件里的全部有效条目，下次保存自然落盘；基线记为当前文件
     * 内容，之后保存不会把用户的删除写回冲掉。
     *
     * 必须主线程调用：与 [saveWordData] / [recordWordFrequency] 同线程操作内存表。
     */
    internal fun reconcileUserDataFiles(): Boolean {
        // 返回「是否检测到外部手改」：各 Store.reconcile 在文件没被改过时原样返回
        // 传入的实例，因此引用比较即可判断，无需再算一遍 hash。
        var changed = false
        wordTablesLock.lock()
        try {
            val newOrder = CandidateOrderStore.reconcile(this, candidateOrderTable)
            if (newOrder !== candidateOrderTable) {
                candidateOrderTable = newOrder
                changed = true
            }
            val newFreq = WordFreqStore.reconcile(this, wordFreqTable)
            if (newFreq !== wordFreqTable) {
                wordFreqTable = newFreq
                changed = true
            }
            val newTemp = AutoWordStore.reconcileTemp(this, autoWordTemp)
            if (newTemp !== autoWordTemp) {
                autoWordTemp = newTemp
                changed = true
            }
            val newFixed = AutoWordStore.reconcileFixed(this, autoWordFixed)
            if (newFixed !== autoWordFixed) {
                autoWordFixed = newFixed
                changed = true
            }
            val newBlacklist = WordBlacklistStore.reconcile(this, wordBlacklist)
            if (newBlacklist !== wordBlacklist) {
                wordBlacklist = newBlacklist
                changed = true
            }
        } finally {
            wordTablesLock.unlock()
        }
        if (changed) {
            FileLogger.i(TAG, "reconcileUserDataFiles: detected external word-table edits")
        }
        // 3.3.27（B1 修复）：不再在 reconcile 里调 ensureRimeDict。
        // save() 写盘时已经同步生成 dict.yaml，reconcile 这里再调一次会让每次
        // 重载都判定 changed=true，触发不必要的强制重编（每次 30s+）。
        // 外部手改检测由上面各 Store.reconcile 的 baselineText 比对完成。
        return changed
    }

    /**
     * 「重载」检测到词库手改后，把手改结果落盘并重新生成派生文件
     * （`word_freq.dict.yaml` 等由 [WordFreqStore.save] 内部生成）。
     *
     * 为什么必须先做这一步：reconcile 只合并内存，磁盘上的 dict 还是旧的；紧接着的
     * 增量部署按**文件时间戳**决定重编谁，读不到新内容就等于没编。必须主线程调用
     * （与 [recordWordFrequency] 同线程，锁内串行）。
     */
    internal fun flushWordDataForReload() {
        saveWordData()
    }

    /**
     * 手写活动区固化后触发一轮联想推理（基于已上屏文本）。
     * 空格/标点上屏走全量 commitText 自带推理，无需调用此方法。
     */
    internal fun finalizeHandwritingPrediction() {
        if (!isChineseMode) return
        mainHandler.post {
            if (!uiState.value.isAsciiMode) {
                getPredictionFromPlugin(predictionManager.lastCommittedText)
            }
        }
    }

    // ── 删除会话撤回（下滑撤回删除，2026-10-02）────────────────────────
    // 设计见 [deleteSessionActive] 字段注释：删除是 30ms 级热路径，只在会话首尾
    // 各读一次输入框，靠两次快照之差还原"这次删掉了什么"，写入 lastClearedText，
    // 由既有 undo_clear 分支回插，不改动撤回通道本身。

    /** 编码串是否显示在输入框内（候选栏模式需单独记编码，输入框模式已含在文本差里）。 */
    private fun isInputTextInInputBox(): Boolean = SettingsPreferences.isCodeInInputBox(this)

    /** 读取光标前文本（受限窗口，见 [UNDO_TEXT_WINDOW]）。需主线程；读不到返回 null。 */
    private fun readTextBeforeCursorWindowed(): String? = runCatching {
        currentInputConnection?.getTextBeforeCursor(UNDO_TEXT_WINDOW, 0)?.toString()
    }.getOrNull()

    /**
     * 删除会话开始：快照光标前文本与（候选栏模式下的）编码串。
     * 会话进行中重复调用为空操作——长按连删只在按下时快照一次。
     */
    internal fun beginDeleteSession() {
        // 会话快照读写 InputConnection 与 Compose 状态，必须在主线程（dispatchKey
        // 等入口可能来自后台线程，这里兜底切主线程）。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { beginDeleteSession() }
            return
        }
        if (deleteSessionActive) return
        // 候选首词模式下 composing 区里是候选词：它不属于正文，且会随编码逐帧变化，
        // 混进前缀差会让「撤回删除」把候选词当成正文插回去。先清掉 composing 区再快照。
        if (SettingsPreferences.showCandidateFirstInInputBox(this)) {
            endComposingInputBox()
        }
        val before = readTextBeforeCursorWindowed() ?: return
        deleteSessionActive = true
        deleteSessionBeforeText = before
        deleteSessionBeforeCode = if (isInputTextInInputBox()) "" else candidateState.value.inputText
    }

    /**
     * 换输入框时作废全部删除簿记（用户 2026-11 修复）。
     *
     * 为什么**不结算**：结算走 [finishDeleteSession]，它用「新框光标前文本」与「旧框快照」
     * 做前缀差；新框为空时 `DeleteUndo.removedPrefix(旧文本, "")` 会返回旧框**整段**内容，
     * 等于把上一个框的文字记成本次删除——一次下滑撤回就会把它插进新框。
     * 所以这里只作废（清会话、清撤回槽、丢弃排队退格、复位连按时间戳），不写撤回槽。
     */
    internal fun resetDeleteStateForNewInputSession() {
        deleteGeneration++
        deleteSessionActive = false
        deleteSessionBeforeText = ""
        deleteSessionBeforeCode = ""
        // 撤回槽跨输入框没有意义：留着会被 anchorMatches(null, …) 放行，插到错误的位置
        lastClearedText = ""
        lastUndoAnchorText = null
        // 左滑回删的撤销槽同理：换框后不允许再把旧内容恢复进新框
        swipeDeletedChunk = null
        swipeDeletedAnchor = null
        synchronized(deleteCoalesceLock) {
            pendingDeleteCount = 0
            deleteJobActive = false
        }
        keyRouter.forgetDeleteContinuity()
    }

    /**
     * 删除会话结算：把本次删掉的内容登记为可撤回（写入 [lastClearedText]）。
     *
     * 被删内容 = 输入框前缀差 +（候选栏模式下）编码串前缀差：默认候选栏模式编码不在
     * 输入框里，长按先吃编码再吃已上屏文本，两段都要记；INPUT_TEXT_INPUT_BOX 模式编码
     * 就在输入框内，已包含在前缀差里，再拼会重复插入。
     */
    internal fun finishDeleteSession() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { finishDeleteSession() }
            return
        }
        if (!deleteSessionActive) return
        deleteSessionActive = false
        val after = readTextBeforeCursorWindowed() ?: return
        val afterCode = if (isInputTextInInputBox()) "" else candidateState.value.inputText
        val removed = DeleteUndo.removedPrefix(deleteSessionBeforeText, after) +
            DeleteUndo.removedPrefix(deleteSessionBeforeCode, afterCode)
        if (removed.isEmpty()) return
        lastClearedText = removed
        lastUndoAnchorText = after
    }

    /**
     * 撤回落点校验：光标前文本与入账时一致才允许回插（详见 [DeleteUndo.anchorMatches]）。
     * 无锚（null）时放行。需主线程。
     */
    internal fun isUndoAnchorValid(): Boolean =
        DeleteUndo.anchorMatches(lastUndoAnchorText, readTextBeforeCursorWindowed())

    /**
     * 删除光标前 count 个字符。
     * 焦点在输入法内部编辑器（快捷发送/工具面板）时作用于对应 EditText，
     * 否则作用于宿主 InputConnection。需在主线程调用。
     */
    internal fun deleteBeforeCursor(count: Int) {
        val quickSendFocused = uiState.value.quickSendFormFocused
        if (quickSendFocused || uiState.value.toolPanelInputFocused) {
            val et = when {
                quickSendFocused && uiState.value.quickSendCodeFocused ->
                    QuickSendFormCodeEditTextHolder.editText
                quickSendFocused -> QuickSendFormEditTextHolder.editText
                else -> ToolPanelEditTextHolder.editText
            }
            et?.let { box ->
                val end = box.selectionStart.coerceAtLeast(0)
                val start = (end - count).coerceAtLeast(0)
                box.text?.replace(start, end, "")
            }
            return
        }
        currentInputConnection?.deleteSurroundingText(count, 0)
    }

    /**
     * 光标前文本与 expected 相同时替换为 replacement，返回是否替换成功。
     * 焦点在输入法内部编辑器时作用于对应 EditText，否则宿主 InputConnection。
     * 不匹配时不做任何操作（调用方决定降级策略）。需在主线程调用。
     */
    internal fun replaceBeforeCursor(expected: String, replacement: String): Boolean {
        val quickSendFocused = uiState.value.quickSendFormFocused
        if (quickSendFocused || uiState.value.toolPanelInputFocused) {
            val et = when {
                quickSendFocused && uiState.value.quickSendCodeFocused ->
                    QuickSendFormCodeEditTextHolder.editText
                quickSendFocused -> QuickSendFormEditTextHolder.editText
                else -> ToolPanelEditTextHolder.editText
            } ?: return false
            val selStart = et.selectionStart.coerceAtLeast(0)
            val start = (selStart - expected.length).coerceAtLeast(0)
            val before = et.text?.substring(start, selStart)
            if (before != expected) return false
            et.text?.replace(start, selStart, replacement)
            try { et.setSelection(start + replacement.length) } catch (e: Exception) { FileLogger.d(TAG, "et.setSelection(after-replace) ignored: ${e.message}") }
            return true
        }
        val ic = currentInputConnection ?: return false
        val before = runCatching {
            ic.getTextBeforeCursor(expected.length, 0)?.toString()
        }.getOrNull()
        if (before != expected) return false
        var replaced = false
        ic.beginBatchEdit()
        try {
            replaced = ic.deleteSurroundingText(expected.length, 0)
            ic.commitText(replacement, 1)
        } finally {
            ic.endBatchEdit()
        }
        return replaced
    }

    
    
}
