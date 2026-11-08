package com.kingzcheung.xime.rime

import com.kingzcheung.xime.util.FileLogger
import java.io.File
import java.util.concurrent.locks.ReentrantLock

data class RimeCandidate(
    val text: String,
    val comment: String
)

/**
 * 批量查询当前 composition 状态。
 *
 * 通过 JNI 一次性返回 input/preedit/commit/candidates/paging/ascii_mode，
 * 避免 updateUI 中多次独立 JNI 调用带来的固定开销。
 */
data class RimeComposition(
    val input: String,
    val preedit: String,
    val committedText: String,
    val candidates: Array<RimeCandidate>,
    val hasNextPage: Boolean,
    val hasPrevPage: Boolean,
    val isAsciiMode: Boolean,
    /** native 快照：组合内光标（raw input 字符偏移）。当前显示层由宿主编辑光标驱动，此字段仅随快照返回。 */
    val caretPos: Int = 0,
    /** native 快照：preedit 中的光标（UTF-8 字节偏移）。 */
    val preeditCursorPos: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RimeComposition) return false
        return input == other.input &&
                preedit == other.preedit &&
                committedText == other.committedText &&
                candidates.contentEquals(other.candidates) &&
                hasNextPage == other.hasNextPage &&
                hasPrevPage == other.hasPrevPage &&
                isAsciiMode == other.isAsciiMode &&
                caretPos == other.caretPos &&
                preeditCursorPos == other.preeditCursorPos
    }

    override fun hashCode(): Int {
        var result = input.hashCode()
        result = 31 * result + preedit.hashCode()
        result = 31 * result + committedText.hashCode()
        result = 31 * result + candidates.contentHashCode()
        result = 31 * result + hasNextPage.hashCode()
        result = 31 * result + hasPrevPage.hashCode()
        result = 31 * result + isAsciiMode.hashCode()
        result = 31 * result + caretPos
        result = 31 * result + preeditCursorPos
        return result
    }
}

data class RimeProcessResult(
    val processed: Boolean,
    val committedText: String,
    val inputText: String,
    val preeditText: String,
    val candidates: Array<RimeCandidate>,
    val isAsciiMode: Boolean,
    val hasNextPage: Boolean,
    val hasPrevPage: Boolean,
    /**
     * T9 左侧面板状态（格式 STATE;PINYIN;DIGIT_LEN;SEL_DIGITS;PANEL_DIGITS;LEFT_LOCKED）。
     * 由 JNI 在 T9 会话活跃时填充，非 T9 场景为空字符串。
     */
    val t9PanelState: String = "",
    /**
     * T9 首音节候选（"pinyin|digitLength" 逗号分隔，如 "ji|2,li|2,j|1"）。
     * 由 JNI 一次计算，避免 Kotlin 侧重复取数。
     */
    val t9SyllableOptions: String = "",
    /** native 快照：组合内光标（raw input 字符偏移）。当前显示层由宿主编辑光标驱动，此字段仅随快照返回。 */
    val caretPos: Int = 0,
    /** native 快照：preedit 中的光标（UTF-8 字节偏移）。 */
    val preeditCursorPos: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RimeProcessResult) return false
        return processed == other.processed &&
                committedText == other.committedText &&
                inputText == other.inputText &&
                preeditText == other.preeditText &&
                candidates.contentEquals(other.candidates) &&
                isAsciiMode == other.isAsciiMode &&
                hasNextPage == other.hasNextPage &&
                hasPrevPage == other.hasPrevPage &&
                t9PanelState == other.t9PanelState &&
                t9SyllableOptions == other.t9SyllableOptions &&
                caretPos == other.caretPos &&
                preeditCursorPos == other.preeditCursorPos
    }

    override fun hashCode(): Int {
        var result = processed.hashCode()
        result = 31 * result + committedText.hashCode()
        result = 31 * result + inputText.hashCode()
        result = 31 * result + preeditText.hashCode()
        result = 31 * result + candidates.contentHashCode()
        result = 31 * result + isAsciiMode.hashCode()
        result = 31 * result + hasNextPage.hashCode()
        result = 31 * result + hasPrevPage.hashCode()
        result = 31 * result + t9PanelState.hashCode()
        result = 31 * result + t9SyllableOptions.hashCode()
        result = 31 * result + caretPos
        result = 31 * result + preeditCursorPos
        return result
    }
}

/** 将一次按键得到的完整结果转为 RimeComposition，供 T9 控制器复用，避免重复 JNI。 */
fun RimeProcessResult.toComposition(): RimeComposition {
    return RimeComposition(
        input = inputText,
        preedit = preeditText,
        committedText = committedText,
        candidates = candidates,
        hasNextPage = hasNextPage,
        hasPrevPage = hasPrevPage,
        isAsciiMode = isAsciiMode,
        caretPos = caretPos,
        preeditCursorPos = preeditCursorPos,
    )
}

class RimeEngine {

    companion object {
        private const val TAG = "RimeEngine"
        /**
         * 按键重试等锁上限。
         *
         * 按键在 key-process 单线程上串行执行，等锁太久会把后续按键全部堵死；
         * 太短则部署/维护瞬间仍会丢键。100ms 是「让用户几乎感知不到丢键，
         * 又不至于让快打排队」的折中。
         */
        private const val KEY_LOCK_RETRY_WAIT_MS = 100L
        private var instance: RimeEngine? = null
        private var deploymentCallback: ((Boolean, String) -> Unit)? = null

        /** 全局 Rime 引擎锁 — 所有 native 调用必须通过此锁同步 */
        val rimeLock = ReentrantLock()

        init {
            System.loadLibrary("rime_jni")
        }

        fun getInstance(): RimeEngine {
            return instance ?: synchronized(this) {
                instance ?: RimeEngine().also { instance = it }
            }
        }

        fun isInitialized(): Boolean = instance?.isInitialized ?: false

        /**
         * 检查指定的 Rime 模块是否已注册（用于验证插件集成）
         */
        fun isModuleRegistered(moduleName: String): Boolean {
            val engine = instance ?: return false
            if (!engine.isInitialized) return false
            return engine.nativeIsModuleRegistered(moduleName)
        }

        fun setDeploymentCallback(callback: (isDeploying: Boolean, message: String) -> Unit) {
            deploymentCallback = callback
        }
    }

    private var isInitialized = false
    private val initLock = Any()
    @Volatile
    private var userDataDir: String = ""

    /**
     * 最近一次快照里的引擎原始候选文本缓存。
     *
     * 用途：选词/删词时需要把「显示层索引」映射回「引擎原始索引」
     * （[resolveRimeCandidateIndex] 本质是一次 indexOf 查找）。原先调用方每次都
     * 重新 `getCandidates()` 走一遍 JNI + 全量数组转换，只为查一个下标；
     * 而这份候选在每个按键后的快照里已经拿到过了，直接复用即可。
     *
     * 只由持锁路径写入（快照/候选查询成功后），读侧无锁且允许短暂过期：
     * 一旦有新快照写入就会覆盖。缓存为空时调用方回退真实 JNI 查询。
     */
    @Volatile
    private var cachedCandidates: List<String> = emptyList()

    /**
     * 最近一次快照里的组合编码（input）缓存。
     *
     * 用途：撞码保护（avoid_auto_select）每个字母键都要读一次「当前编码」来拼 nextCode，
     * 原先固定调 `getInput()` 走 JNI；而每次按键处理完的快照里已经有最新编码了，
     * 直接复用可省掉每键一次 JNI（中文快打时是纯热路径）。
     * 缓存为空表示未知，调用方回退真实查询。
     */
    @Volatile
    private var cachedInputText: String = ""

    /** 写入候选缓存（仅在已持 rimeLock 的成功路径里调用）。 */
    private fun updateCandidatesCache(candidates: Array<RimeCandidate>) {
        cachedCandidates = candidates.map { it.text }
    }

    /** 写入编码缓存（与候选缓存同源，仅在已持 rimeLock 的成功路径里调用）。 */
    private fun updateInputTextCache(input: String) {
        cachedInputText = input
    }

    /**
     * 作废两份快照缓存。
     *
     * 在「直接改写引擎组合状态」的操作后调用（setInput / clearComposition /
     * selectCandidate / commit / deleteCandidate / switchSchema 等）：这些操作绕过了
     * 快照写入路径，若不失效会让调用方读到与引擎不一致的旧编码/旧候选
     * （典型后果：撞码保护拿旧编码拼 nextCode，选词索引映射按旧候选查错位）。
     * 作废后调用方会自动回退真实 JNI 查询，行为与优化前一致。
     */
    private fun invalidateSnapshotCaches() {
        cachedCandidates = emptyList()
        cachedInputText = ""
    }

    /**
     * 读取最近一次快照的组合编码（不触碰 native、不取锁）。
     *
     * 供 avoid_auto_select 拼 nextCode 等「只需知道当前编码」的场景复用，省一次 JNI。
     * 编码可能比真实引擎状态旧一个按键（新快照写入即覆盖），调用方对时序敏感时应用
     * [getInput] 真实查询。
     */
    fun getCachedInputText(): String = cachedInputText

    /**
     * 读取最近一次快照的引擎原始候选（不触碰 native、不取锁，开销为一次字段读）。
     *
     * 返回空列表表示尚未缓存（如键盘刚启动还没处理过按键），调用方应回退 JNI 查询。
     */
    fun getCachedCandidates(): List<String> = cachedCandidates

    /**
     * 管理路径（初始化/部署/会话创建/方案切换等）：阻塞等待锁。
     * 这类调用在后台线程执行，等待部署/编译完成是预期行为。
     */
    private inline fun <T> locked(block: () -> T): T {
        rimeLock.lock()
        try {
            return block()
        } finally {
            rimeLock.unlock()
        }
    }

    /**
     * 输入/查询路径：非阻塞获取锁。拿不到锁（如全量部署持锁中）立即返回
     * [defaultValue] 而不等待，避免主线程 UI（updateUI → getComposition 等）
     * 被 20+ 秒的部署阻塞导致 ANR。拿不到锁时不进入 native，并发安全。
     */
    private inline fun <T> tryLocked(defaultValue: T, block: () -> T): T {
        if (!rimeLock.tryLock()) return defaultValue
        try {
            return block()
        } finally {
            rimeLock.unlock()
        }
    }

    private fun notifyDeploymentStatus(isDeploying: Boolean, message: String) {
        deploymentCallback?.invoke(isDeploying, message)
    }

    fun initialize(userDataDir: String, sharedDataDir: String) {
        if (!isInitialized) {
            synchronized(initLock) {
                if (!isInitialized) {
                    try {
                        this.userDataDir = userDataDir
                        nativeInstallSignalHandler(userDataDir)
                        notifyDeploymentStatus(true, "正在加载输入法引擎...")
                        nativeInitialize(userDataDir, sharedDataDir)
                        isInitialized = true

                        // 部署后主动触发：为已部署目录中所有 T9 方案打九键特有补丁
                        // （t9 四要素 + 个人词库 packs 兜底），保证第三方九键方案
                        // 首次使用前 custom.yaml 健康、用户词典生效。
                        ensureT9SchemaPatchesForDeployedSchemas(userDataDir)

                        // startup 只初始化引擎，不创建 session
                        // session 在第一次使用时延迟创建（ensureSession）
                        // 部署在后台异步运行，不阻塞

                        notifyDeploymentStatus(false, "")
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Error during Rime initialization", e)
                        notifyDeploymentStatus(false, "初始化失败")
                    }
                }
            }
        }
    }

    fun ensureSession(timeoutMs: Long = 60000L): Boolean {
        if (!isInitialized) return false

        // Quick check without lock — native calls are thread-safe reads
        if (nativeHasSession() && getAvailableSchemas().isNotEmpty()) return true

        // 等待编译完成（不持有 rimeLock，避免阻塞主线程 UI 操作）
        // 轮询粒度 100ms：原来是 1000ms，部署刚结束时会白等最多 1 秒才创建 session。
        var waited = 0L
        while (nativeIsMaintaining() && waited < timeoutMs) {
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return false
            }
            waited += 100
        }

        locked {
            waited = 0L
            while (waited < timeoutMs) {
                if (!nativeHasSession()) {
                    nativeCreateSession()
                }
                if (getAvailableSchemas().isNotEmpty()) {
                    return true
                }
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return false
                }
                waited += 100
            }
            FileLogger.w(TAG, "ensureSession: schemas not available after ${timeoutMs}ms, deployment may still be running")
            return false
        }
    }

    fun isMaintaining(): Boolean {
        return nativeIsMaintaining()
    }

    /** 引擎是否繁忙（维护中或 rimeLock 被占用）：非阻塞探测，供 UI 立即反馈使用。 */
    fun isEngineBusy(): Boolean {
        return nativeIsMaintaining() || rimeLock.isLocked
    }

    fun getCurrentSchema(): String {
        if (!nativeHasSession()) return ""
        return tryLocked("") {
            nativeGetCurrentSchema() ?: ""
        }
    }

    /**
     * 按键处理：**阻塞等待锁**（与 [tryLocked] 的查询路径不同）。
     *
     * 调用方约定：processKey 只在按键串行队列（ImeKeyRouter.keyProcessingDispatcher /
     * T9InputController.t9Scope）上执行，均为后台线程——即使部署/同步正持锁，
     * 在此**排队等待**是安全的（不会 ANR），且保证按键**永不静默丢失**。
     *
     * 旧实现用 [tryLocked]：拿不到锁直接返回 false（丢弃按键）。锁被部署等占用时
     * 用户敲的键就被无声吞掉，表现为「打了字没反应/丢字」。
     */
    fun processKey(keycode: Int, mask: Int): Boolean {
        if (!isInitialized) return false
        return locked {
            if (!nativeHasSession() && !nativeCreateSession()) return@locked false
            nativeProcessKey(keycode, mask)
        }
    }
    /**
     * 按键处理并取结果：**阻塞等待锁**，理由同 [processKey]。
     *
     * 返回 [emptyProcessResult] 仅当引擎未初始化（调用方应回退查询）；锁竞争时
     * 不再返回空结果丢弃按键，而是等待队列处理完成。
     */
    fun processKeyAndGetResult(keycode: Int, mask: Int): RimeProcessResult {
        if (!isInitialized) return emptyProcessResult()
        val result = locked {
            if (!nativeHasSession() && !nativeCreateSession()) return@locked emptyProcessResult()
            val r = nativeProcessKeyAndGetResult(keycode, mask)
            // 同步刷新两处缓存：下一键的 avoid_auto_select / 选词索引映射可直接复用，
            // 省掉之后各自的 getInput() / getCandidates() 独立 JNI。
            updateCandidatesCache(r.candidates)
            updateInputTextCache(r.inputText)
            r
        }
        return result
    }

    /** 空结果（未处理）的统一样本，避免各处重复构造。 */
    private fun emptyProcessResult() =
        RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)

    fun getProcessResult(processed: Boolean): RimeProcessResult {
        if (!isInitialized) return RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)
        // 必须持 rimeLock：nativeGetProcessResult 内部 RimeGetContext → Menu::Prepare
        // 会惰性生成候选页（遍历 translations），与 t9FlushRimeInput（set_input → Reset，
        // 释放旧 translations）并发会导致悬空 → ScriptTranslation::Peek 空指针崩溃。
        // 部署/编译持锁期间拿不到锁直接返回空结果（不进 native），避免阻塞调用线程。
        return tryLocked(RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)) {
            val r = nativeGetProcessResult(processed)
            updateCandidatesCache(r.candidates)
            updateInputTextCache(r.inputText)
            r
        }
    }

    /**
     * 一次锁内取完整按键后快照：input / candidates / ascii / hasNext / hasPrev。
     *
     * [refreshAfterKey] 原先 5 次独立 tryLock（每次可能因锁竞争静默返回默认值），
     * 造成候选栏状态不一致（如 getCandidates 成功但 getInput 返回空）。合并为单次
     * tryLock 内串行 native 调用，锁获取从 5 次降为 1 次，且快照内部自洽。
     */
    fun getProcessSnapshot(): RimeProcessResult {
        if (!isInitialized) return RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)
        return tryLocked(RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)) {
            if (!nativeHasSession() && !nativeCreateSession())
                return@tryLocked RimeProcessResult(false, "", "", "", emptyArray(), false, false, false)
            val input = nativeGetInput() ?: ""
            val candidates = (nativeGetCandidatesWithComments() ?: emptyArray()).map { pair ->
                RimeCandidate(
                    text = pair.getOrElse(0) { "" },
                    comment = pair.getOrElse(1) { "" }
                )
            }.toTypedArray()
            updateCandidatesCache(candidates)
            updateInputTextCache(input)
            RimeProcessResult(
                processed = true,
                committedText = "",
                inputText = input,
                preeditText = "",
                candidates = candidates,
                isAsciiMode = nativeIsAsciiMode(),
                hasNextPage = nativeHasNextPage(),
                hasPrevPage = nativeHasPrevPage(),
            )
        }
    }
    fun getCandidates(): Array<String> {
        return tryLocked(emptyArray()) {
            if (!nativeHasSession()) return@tryLocked emptyArray()
            nativeGetCandidates() ?: emptyArray()
        }
    }

    /**
     * 旁路会话只读查询：给定编码返回候选（含注释）。
     * 使用独立 Rime 会话，不改变主会话组合/候选/分页状态；拿不到锁或引擎未就绪返回空。
     * 供智能纠错"码→词"出词用（不污染实时输入）。
     */
    fun bypassLookup(input: String, maxCount: Int = 8): Array<RimeCandidate> {
        if (!isInitialized || input.isEmpty()) return emptyArray()
        return tryLocked(emptyArray()) {
            if (!nativeHasSession()) return@tryLocked emptyArray()
            nativeBypassLookup(input, maxCount)?.map { pair ->
                RimeCandidate(
                    text = pair.getOrElse(0) { "" },
                    comment = pair.getOrElse(1) { "" }
                )
            }?.filter { it.text.isNotEmpty() }?.toTypedArray() ?: emptyArray()
        }
    }

    fun getCandidatesWithComments(): Array<RimeCandidate> {
        return tryLocked(emptyArray()) {
            if (!nativeHasSession()) return@tryLocked emptyArray()
            val rawCandidates = nativeGetCandidatesWithComments() ?: emptyArray()
            val mapped = rawCandidates.map { pair ->
                RimeCandidate(
                    text = pair.getOrElse(0) { "" },
                    comment = pair.getOrElse(1) { "" }
                )
            }.toTypedArray()
            updateCandidatesCache(mapped)
            mapped
        }
    }

    /**
     * 跨页获取整个候选列表（与引擎分页无关），供候选展开页本地分页使用。
     * @param maxCount 收集上限，防御超大列表
     */
    fun getAllCandidates(maxCount: Int = 500): Array<RimeCandidate> {
        return tryLocked(emptyArray()) {
            if (!nativeHasSession()) return@tryLocked emptyArray()
            val rawCandidates = nativeGetAllCandidates(maxCount) ?: emptyArray()
            rawCandidates.map { pair ->
                RimeCandidate(
                    text = pair.getOrElse(0) { "" },
                    comment = pair.getOrElse(1) { "" }
                )
            }.toTypedArray()
        }
    }

    fun getInput(): String {
        return tryLocked("") {
            nativeGetInput() ?: ""
        }
    }

    /**
     * 批量查询当前 composition 全部信息。
     *
     * 一次 JNI 调用返回 input、preedit、committedText、candidates、分页和 ascii_mode，
     * 是 T9 路径 updateUI 的首选查询接口，可替代多次独立 JNI 调用。
     */
    fun getComposition(): RimeComposition {
        return tryLocked(RimeComposition("", "", "", emptyArray(), false, false, false)) {
            nativeGetComposition()
        }
    }

    fun selectCandidate(index: Int): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            val ok = nativeSelectCandidate(index)
            if (ok) invalidateSnapshotCaches()
            ok
        }
    }

    /**
     * 按候选列表全局索引选词（跨页，与 getAllCandidates 遍历顺序一致）。
     * 候选展开页本地分页点选走此接口：本地页内索引 + 页偏移 = 全局索引。
     */
    fun selectCandidateByGlobalIndex(index: Int): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativeSelectCandidateByGlobalIndex(index)
        }
    }

    /**
     * 删除当前页候选（长按候选栏删除自造词）。
     * 标准 C API delete_candidate_on_current_page：librime 对用户词典词条
     * 执行 tombstone 标记（UpdateEntry -1），键盘无关（T9/全键盘通用）。
     * @param index 当前页内的候选索引
     */
    fun deleteCandidateOnCurrentPage(index: Int): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativeDeleteCandidateOnCurrentPage(index)
        }
    }

    /**
     * 按候选列表全局索引删除（跨页，与 getAllCandidates 遍历顺序一致），
     * 供候选展开页本地分页长按删除自造词。
     */
    fun deleteCandidateByGlobalIndex(index: Int): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativeDeleteCandidateByGlobalIndex(index)
        }
    }

    fun pageDown(): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativePageDown()
        }
    }

    fun pageUp(): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativePageUp()
        }
    }

    fun hasNextPage(): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativeHasNextPage()
        }
    }

    fun hasPrevPage(): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession()) return@tryLocked false
            nativeHasPrevPage()
        }
    }

    fun commit(): String {
        return tryLocked("") {
            val text = nativeCommit() ?: ""
            if (text.isNotEmpty()) invalidateSnapshotCaches()
            text
        }
    }

    fun clearComposition() {
        if (!nativeHasSession()) return
        tryLocked(Unit) {
            nativeClearComposition()
            invalidateSnapshotCaches()
        }
    }

    /**
     * 设置 RIME 引擎的输入字符串。
     *
     * 一次 JNI 调用完成整个输入设置，替代逐字符 processKey。
     * 调用后引擎会重新执行完整的处理管线（Speller → Segmentor → Translator）。
     * 支持分隔符 '，如 setInput("ji'he") 会告知 RIME 音节边界。
     *
     * @param input 拼音或数字字符串，如 "zhongguo" 或 "54482"
     * @return 是否设置成功
     */
    fun setInput(input: String): Boolean {
        if (!isInitialized) return false
        return tryLocked(false) {
            if (!nativeHasSession() && !nativeCreateSession()) return@tryLocked false
            val ok = nativeSetInput(input)
            if (ok) invalidateSnapshotCaches()
            ok
        }
    }

    /**
     * 设置输入串并**在同一把锁内**取回处理结果（阻塞等待锁）。
     *
     * ## 为什么需要它（正确性）
     *
     * [setInput] 与 [getProcessResult] 各自都用 [tryLocked]（非阻塞）。两段式调用
     * `setInput(x); getProcessResult(true)` 之间会释放锁，第二次取结果若撞上
     * 部署/维护持锁，会**静默返回空结果（inputText = ""）**。退格/编辑态逻辑正是
     * 用 `result.inputText.isEmpty()` 判断「编码是否已被删空」，于是锁竞争的一次
     * 空结果就被误判成「编码删光了」，进而调用 [clearComposition] 把**整个编码清空**——
     * 表现为「编码态下点一下删除键，整串编码消失」。
     *
     * 本方法持锁内完成 setInput → 取结果 → 刷缓存，全程原子，返回的是真实结果，
     * 不会出现「设置成功但结果为空」的误判。仅在引擎未初始化时返回空结果。
     *
     * @param input 拼音或数字字符串
     * @return 设置后的真实处理结果；未初始化时返回空结果
     */
    fun setInputAndGetResult(input: String): RimeProcessResult {
        if (!isInitialized) return emptyProcessResult()
        return locked {
            if (!nativeHasSession() && !nativeCreateSession()) return@locked emptyProcessResult()
            val ok = nativeSetInput(input)
            if (!ok) return@locked emptyProcessResult()
            invalidateSnapshotCaches()
            val r = nativeGetProcessResult(true)
            updateCandidatesCache(r.candidates)
            updateInputTextCache(r.inputText)
            r
        }
    }

    fun toggleAsciiMode(): Boolean {
        // 用户显式切换操作：阻塞等待锁（部署/维护持锁时排队，完成后自动切换），
        // 不静默失败；调用方保证不在主线程执行（ImeKeyRouter 的 key-process 线程）。
        return locked {
            if (!nativeHasSession() && !nativeCreateSession()) return@locked false
            nativeToggleAsciiMode()
        }
    }

    fun isAsciiMode(): Boolean {
        return tryLocked(false) {
            if (!nativeHasSession() && !nativeCreateSession()) return@tryLocked false
            nativeIsAsciiMode()
        }
    }

    fun setOption(option: String, value: Boolean) {
        if (!nativeHasSession()) return
        // 与 processKey/setInput 等共用 rimeLock：native 侧会话状态不是线程安全的，
        // 原来这两个完全不过锁，与按键处理并发时可能读到半更新的选项。
        locked { nativeSetOption(option, value) }
    }

    fun getOption(option: String): Boolean {
        if (!nativeHasSession()) return false
        return locked { nativeGetOption(option) }
    }

    fun setPageSize(schemaId: String, pageSize: Int) {
        if (!isInitialized) return
        // 必须走 rimeLock：nativeSetPageSize 内部在「会话正用该方案」时会
        // 重建 Schema（applyPageSizeOverride -> session->ApplySchema），
        // 这跟 switchSchema 里的 ApplySchema 是同一类破坏性操作。
        // 原实现没加锁，冷启动时方案重建可能同时在两个线程推进，
        // 两边各自 ApplySchema 会并发重写引擎组件的配置树，
        // 实测 SIGSEGV（shared_ptr<ConfigData>::operator-> 空指针）。
                locked {
            nativeSetPageSize(schemaId, pageSize)
        }
    }

    fun switchSchema(schemaId: String): Boolean {
        // 部署/全量编译进行中（30s+）不阻塞等待：直接返回 false，避免主线程
        // onStartInput/selectSchema 等路径 ANR。部署完成后的 initRimeEngine
        // 流程会重新切换方案。非部署场景保持阻塞锁语义，保证切换可靠。
        if (isMaintaining()) {
            FileLogger.w(TAG, "switchSchema($schemaId) skipped: deployment in progress")
            return false
        }
        locked {
            if (!nativeHasSession()) {
                FileLogger.w(TAG, "switchSchema($schemaId) failed: no rime session")
                return false
            }
            // 在切换方案前，确保 T9 方案的 schema 补丁已注入
            // 这会在 user_data_dir 中创建 {schemaId}.custom.yaml 文件，
            // RIME 引擎加载方案时会自动应用 custom.yaml 中的 patch 补丁
            nativeEnsureT9SchemaPatches(schemaId)
            val switched = nativeSwitchSchema(schemaId)
            if (!switched) {
                // 常见于方案未部署（不在 schema_list，如老版本升级残留）：
                // 留证便于反馈日志定位（用户症状：键盘已切换但按键无候选）
                FileLogger.w(TAG, "switchSchema($schemaId) failed: schema not available, current=${getCurrentSchema()}")
            }
            return switched
        }
    }

    fun startMaintenance(full: Boolean): Boolean {
        if (!isInitialized) return false
        locked {
            return nativeStartMaintenance(full)
        }
    }

    fun deploy(): Boolean {
        if (!isInitialized) return false
        locked {
            // 部署前确保 T9 补丁（含个人词库 packs 确定性名）已写入，
            // 部署时 librime 才会编译对应的 user_<schemaId>.table.bin。
            // 幂等：补丁已就位时 native 侧直接 skip，开销极小。
            ensureT9SchemaPatchesForDeployedSchemas(userDataDir)
            return nativeDeploy()
        }
    }

    /**
     * 增量维护部署：librime 根据文件时间戳只编译变更的 schema/dict，
     * 避免配置小幅变化（如 custom.yaml 补丁）触发 60MB 词库全量重编译。
     * 与 [deploy] 一样持 rimeLock 并等待维护完成，保证维护期间其他
     * native 调用不会并发进入引擎。
     */
    fun deployIncremental(): Boolean {
        if (!isInitialized) return false
        // 关键：librime 的 StartMaintenance 是**异步**启动（std::async 维护线程，
        // 立即返回），编译在独立线程跑。因此绝不能像旧实现那样在 locked{} 里
        // 忙等 nativeIsMaintaining()——那会让 rimeLock 在整个编译期间被独占
        //（1~3 秒甚至更久），而输入查询走 tryLocked 非阻塞，拿不到锁就返回
        // 空候选，表现为「部署后打不了字、要等一会」。
        // 正确做法：锁内只做「启动」，锁外轮询等待；收尾再短暂拿锁。
        val started = locked {
            ensureT9SchemaPatchesForDeployedSchemas(userDataDir)
            nativeStartMaintenance(false)
        }
        if (!started) {
            FileLogger.w(TAG, "deployIncremental: startMaintenance returned false (no tasks or already working)")
            // 没有待编译任务时视为成功（无需重建）；已有维护在跑也让调用方继续等待
            return !nativeIsMaintaining()
        }
        // 锁外等待：期间 rimeLock 空闲，输入查询正常工作，不再「打不了字」。
        var waited = 0L
        while (nativeIsMaintaining() && waited < 180_000L) {
            Thread.sleep(100)
            waited += 100
        }
        if (nativeIsMaintaining()) {
            FileLogger.w(TAG, "deployIncremental: maintenance timed out")
            return false
        }
        // 维护完成后更新 last_build_time，避免下次启动增量检测误判需重编译。
        // 短暂持锁即可（只是一次字段写），不阻塞输入。
        locked { nativeUpdateLastBuildTime() }
        return true
    }

    /**
     * 用户词典同步（librime 原生 sync）：合并 sync 目录下其他设备的快照进 userdb，
     * 并导出本机快照（TSV 文本，时间戳合并）。持 rimeLock 独占至维护结束，
     * 期间输入查询走 tryLocked 立即降级，不会阻塞 UI。
     * 调用前需保证 installation.yaml 存在且 id 稳定（见 SyncManager.ensureInstallationYaml）。
     */
    fun syncUserData(): Boolean {
        if (!isInitialized) return false
        locked {
            return nativeSyncUserData()
        }
    }

    /**
     * 读取用户词库词条，返回文本码表文本（每行 `词<TAB>码<TAB>频率`）。
     * 与 [syncUserData] 同构：native 侧先销毁会话再遍历、遍历后重建会话
     * （librime 要求这些操作前用户词典必须已关闭），故持 rimeLock 独占。
     * 库不存在或打不开时返回空串。
     */
    fun readUserDictText(dictName: String): String {
        if (!isInitialized || dictName.isEmpty()) return ""
        locked {
            return nativeReadUserDictText(dictName) ?: ""
        }
    }

    /**
     * 导出用户词库为文本码表文件（`词<TAB>码<TAB>频率` + `#@` 元数据注释头）。
     * 走 librime `UserDictManager::Export`，与电脑端 rime **同一实现**，
     * 文件可互通；已标记删除的条目不会写出。
     * @param textFilePath 必须是真实文件路径（librime 直接开 ofstream）。
     * @return 导出的条目数；失败返回 -1。
     */
    fun exportUserDict(dictName: String, textFilePath: String): Int {
        if (!isInitialized || dictName.isEmpty() || textFilePath.isEmpty()) return -1
        locked {
            return nativeExportUserDict(dictName, textFilePath)
        }
    }

    /**
     * 从文本码表文件导入进用户词库（**合并**语义：同词条取较大频率，负频率视为删除标记，
     * 不会清空原有条目）。走 librime `UserDictManager::Import`。
     * @param textFilePath 必须是真实文件路径（librime 的 TsvReader 直接开 ifstream）。
     * @return 成功解析并写入的条目数；失败返回 -1。
     */
    fun importUserDict(dictName: String, textFilePath: String): Int {
        if (!isInitialized || dictName.isEmpty() || textFilePath.isEmpty()) return -1
        locked {
            return nativeImportUserDict(dictName, textFilePath)
        }
    }

    /**
     * 把一个用户词库快照（`<词典名>.userdb.txt`，含 `#@/db_name` 元数据）按时间戳
     * 合并进目标词库（`UserDictManager::Restore`）。
     *
     * 与 [syncUserData] 的分工：sync 只处理本机**已有** `<词典名>.userdb` 的词典，
     * 换机/清数据恢复时本机一个用户词库都没有 ⇒ 快照永远合并不进去；这里目标库名
     * 取自快照元数据，目标库不存在会被创建，正好覆盖这个场景。
     */
    fun restoreUserDictSnapshot(snapshotPath: String): Boolean {
        if (!isInitialized || snapshotPath.isEmpty()) return false
        locked {
            return nativeRestoreUserDict(snapshotPath)
        }
    }

    fun lookupText(text: String): String {
        if (!isInitialized || text.isEmpty()) return ""
        return tryLocked("") {
            if (!nativeHasSession()) return@tryLocked ""
            nativeLookupText(text)
        }
    }

    fun getAvailableSchemas(): Array<String> {
        return nativeGetAvailableSchemas() ?: emptyArray()
    }

    /** 读取方案配置字符串项（librime 解析，含 custom.yaml patch 合并后的最终值）。 */
    fun getSchemaString(schemaId: String, key: String): String? {
        if (!isInitialized) return null
        return nativeGetSchemaString(schemaId, key)
    }

    /** 读取方案配置列表项（librime 解析，含 custom.yaml patch 合并后的最终值）。 */
    fun getSchemaList(schemaId: String, key: String): List<String> {
        if (!isInitialized) return emptyList()
        return nativeGetSchemaList(schemaId, key)?.toList() ?: emptyList()
    }

    /** 读取方案自带 translator.packs 中声明的个人词库名。 */
    fun getSchemaPacks(schemaId: String): List<String> =
        getSchemaList(schemaId, "translator/packs")

    /** 读取方案 translator.dictionary 主词典名。 */
    fun getSchemaDictionary(schemaId: String): String? =
        getSchemaString(schemaId, "translator/dictionary")

    /** 读取方案 engine/translators 翻译器列表。 */
    fun getSchemaTranslators(schemaId: String): List<String> =
        getSchemaList(schemaId, "engine/translators")

    /** 方案是否有 speller.algebra（固定音节表/自动造词规则）。 */
    fun hasSpellerAlgebra(schemaId: String): Boolean =
        getSchemaList(schemaId, "speller/algebra").isNotEmpty()

    /** 读取方案 custom_phrase.user_dict（自定义短语词典名）。 */
    fun getCustomPhraseDictName(schemaId: String): String? =
        getSchemaString(schemaId, "custom_phrase/user_dict")

    // ── user.yaml 用户状态 ──

    /** 读取 user.yaml 用户状态字符串（如 var/previously_selected_schema）。 */
    fun getUserConfigString(key: String): String? {
        if (!isInitialized) return null
        return nativeGetUserConfigString(key)
    }

    /** 读取 user.yaml 用户状态布尔值（如 var/option/ascii_mode）。 */
    fun getUserConfigBool(key: String): Boolean {
        if (!isInitialized) return false
        return nativeGetUserConfigBool(key)
    }

    /** 写 user.yaml 用户状态字符串（auto_save，自动落盘）。 */
    fun setUserConfigString(key: String, value: String) {
        if (!isInitialized) return
        nativeSetUserConfigString(key, value)
    }

    /** 写 user.yaml 用户状态布尔值（auto_save，自动落盘）。 */
    fun setUserConfigBool(key: String, value: Boolean) {
        if (!isInitialized) return
        nativeSetUserConfigBool(key, value)
    }

    /** 运行时切换 JNI verbose 日志（仅 Debug 构建生效，Release 为空操作）。 */
    fun setVerboseLogging(enabled: Boolean) {
        locked {
            nativeSetVerboseLogging(enabled)
        }
    }

    fun destroy() {
        if (isInitialized) {
            locked {
                nativeDestroy()
                isInitialized = false
            }
        }
    }

    /**
     * 结束当前输入会话但**保留引擎**（输入法服务切走时调用）。
     *
     * 不能改用 [destroy]：它会调 native `finalize()` 释放 librime 全局资源，
     * 而切回本输入法时服务重建会再次 `startup → rime->initialize()`。
     * librime 在同一进程内「finalize 后再 initialize」并不保证可重入，
     * 真机表现就是「从别的输入法切回本输入法闪退」。引擎保留后，
     * 切回时 [ensureSession] 会直接复用已初始化的引擎重建会话。
     */
    fun endSession() {
        if (!isInitialized) return
        locked {
            nativeEndSession()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // T9 Processor 公共 API
    // ═══════════════════════════════════════════════════════════

    /**
     * 右选候选：根据候选拼音注释、候选文本与文本长度执行右侧选词（消费计算）。
     * 委托给 T9RightCommitHandler 三层消费算法，判断 full/partial commit。
     * 调频不在此进行——由 Kotlin 在 full commit 上屏后经 [t9Memorize] 单独调用。
     * @param pinyin 候选词的拼音注释（如 "li hua"）
     * @param text 候选词文本（如 "丽华"）；用于 (comment, text) 双条件精确定位，
     *   避免同注释歧义（如 几股/击鼓 均 "ji gu"）时捕获他词的调频码
     * @param textLength 候选词字数（如 "丽华" 为 2）
     */
    fun t9SelectCandidate(pinyin: String, text: String?, textLength: Int): Boolean {
        if (!isInitialized) return false
        return tryLocked(false) {
            if (!nativeHasSession() && !nativeCreateSession()) return@tryLocked false
            nativeT9SelectCandidate(pinyin, text, textLength)
        }
    }

    /** 用户词典写入/回滚公共实现：memorize=true → commits=+1；false → commits=-1。 */
    private fun t9DictOp(text: String, pinyin: String, memorize: Boolean): Boolean {
        if (!isInitialized || text.isEmpty() || pinyin.isEmpty()) return false
        return tryLocked(false) {
            if (!nativeHasSession() && !nativeCreateSession()) return@tryLocked false
            if (memorize) nativeT9Memorize(text, pinyin) else nativeT9Forget(text, pinyin)
        }
    }

    /**
     * 用户词典调频写入：在 full commit（含 partial 拼接）上屏后调用，
     * text 为上屏完整文本，pinyin 为空格分隔的拼音音节串（如 "ji hu a"）。
     * C++ 构造 RIME 原生 DictEntry 写入（key 由 RIME 生成）。
     */
    fun t9Memorize(text: String, pinyin: String): Boolean = t9DictOp(text, pinyin, true)

    /** 用户词典调频回滚（undo 联动）：撤销 right commit 段时调用，commits=-1。 */
    fun t9Forget(text: String, pinyin: String): Boolean = t9DictOp(text, pinyin, false)

    // Native 方法声明
    private external fun nativeInitialize(userDataDir: String, sharedDataDir: String)
    private external fun nativeInstallSignalHandler(userDataDir: String)
    private external fun nativeSetVerboseLogging(enabled: Boolean)
    private external fun nativeCreateSession(): Boolean
    private external fun nativeHasSession(): Boolean
    private external fun nativeIsMaintaining(): Boolean
    private external fun nativeGetCurrentSchema(): String?
    private external fun nativeProcessKey(keycode: Int, mask: Int): Boolean
    private external fun nativeProcessKeyAndGetResult(keycode: Int, mask: Int): RimeProcessResult
    private external fun nativeGetProcessResult(processed: Boolean): RimeProcessResult
    private external fun nativeGetCandidates(): Array<String>?
    private external fun nativeGetCandidatesWithComments(): Array<Array<String>>?
    private external fun nativeGetAllCandidates(maxCount: Int): Array<Array<String>>?
    private external fun nativeBypassLookup(input: String, maxCount: Int): Array<Array<String>>?
    private external fun nativeGetInput(): String?
    private external fun nativeGetComposition(): RimeComposition
    private external fun nativeSelectCandidate(index: Int): Boolean
    private external fun nativeSelectCandidateByGlobalIndex(index: Int): Boolean
    private external fun nativeDeleteCandidateOnCurrentPage(index: Int): Boolean
    private external fun nativeDeleteCandidateByGlobalIndex(index: Int): Boolean
    private external fun nativePageDown(): Boolean
    private external fun nativePageUp(): Boolean
    private external fun nativeHasNextPage(): Boolean
    private external fun nativeHasPrevPage(): Boolean
    private external fun nativeCommit(): String?
    private external fun nativeClearComposition()
    private external fun nativeSetInput(input: String): Boolean
    private external fun nativeToggleAsciiMode(): Boolean
    private external fun nativeIsAsciiMode(): Boolean
    private external fun nativeSetOption(option: String, value: Boolean)
    private external fun nativeGetOption(option: String): Boolean
    private external fun nativeSwitchSchema(schemaId: String): Boolean
    private external fun nativeEnsureT9SchemaPatches(schemaId: String): Boolean

    /**
     * 部署后主动触发：遍历已部署 schema 目录，对每个方案调用 nativeEnsureT9SchemaPatches。
     * native 内部 Phase 1 会判定并跳过非 T9 方案（幂等无害）；
     * 未启用方案仅写 custom.yaml 无副作用（后续切换到它时即生效）。
     *
     * 注意：此处【不】触发部署。历史上曾根据 native 返回值（词库未编译）在此调用
     * nativeDeploy，导致 initialize 的 initLock 内同步执行大部署，与引擎初始化/
     * 首次部署时序冲突引发 SIGSEGV（新装引导页场景）。词库编译统一由
     * 引导页/设置页的部署流程（build 缺失或用户显式部署）负责。
     */
    private fun ensureT9SchemaPatchesForDeployedSchemas(userDataDir: String) {
        val schemas = File(userDataDir).listFiles { f ->
            f.isFile && f.name.endsWith(".schema.yaml")
        }?.map { it.name.removeSuffix(".schema.yaml") } ?: return
        for (schemaId in schemas) {
            try {
                nativeEnsureT9SchemaPatches(schemaId)
            } catch (_: Throwable) {
                // 单个方案补丁失败不阻断引擎初始化
            }
            // 邻键误触纠错：把 correction_translator 注入配置声明的方案
            // （xime.yaml correction.schemas，含 xime.custom.yaml 扩展；幂等，未声明跳过）
            try {
                if (com.kingzcheung.xime.correction.CorrectionPriors.isCorrectionSchema(schemaId)) {
                    com.kingzcheung.xime.correction.CorrectionNative.ensureSchemaPatch(schemaId, userDataDir)
                }
            } catch (_: Throwable) {
            }
        }
    }
    private external fun nativeStartMaintenance(full: Boolean): Boolean
    private external fun nativeSyncUserData(): Boolean
    private external fun nativeReadUserDictText(dictName: String): String?
    private external fun nativeExportUserDict(dictName: String, textFilePath: String): Int
    private external fun nativeImportUserDict(dictName: String, textFilePath: String): Int
    private external fun nativeRestoreUserDict(snapshotPath: String): Boolean
    private external fun nativeDeploy(): Boolean
    private external fun nativeDeploySchema(schemaId: String): Boolean
    private external fun nativeLookupText(text: String): String
    private external fun nativeGetAvailableSchemas(): Array<String>?
    private external fun nativeGetSchemaList(schemaId: String, key: String): Array<String>?
    private external fun nativeGetSchemaString(schemaId: String, key: String): String?
    private external fun nativeGetUserConfigString(key: String): String?
    private external fun nativeGetUserConfigBool(key: String): Boolean
    private external fun nativeSetUserConfigString(key: String, value: String): Boolean
    private external fun nativeSetUserConfigBool(key: String, value: Boolean): Boolean
    private external fun nativeIsModuleRegistered(moduleName: String): Boolean
    private external fun nativeUpdateLastBuildTime()
    private external fun nativeSetPageSize(schemaId: String, pageSize: Int)
    private external fun nativeDestroy()
    private external fun nativeEndSession()
    private external fun nativeT9SelectCandidate(pinyin: String, text: String?, textLength: Int): Boolean
    private external fun nativeT9Memorize(text: String, pinyin: String): Boolean
    private external fun nativeT9Forget(text: String, pinyin: String): Boolean
    private external fun nativeT9SelectPinyinDirect(pinyin: String, digitLength: Int): Boolean
    private external fun nativeT9GetLeftPanelState(): String?
    private external fun nativeT9ClearComposition(mode: Int)
    private external fun nativeT9FlushRimeInput()
    private external fun nativeT9GetAndConsumeUndoneRightCommitCount(): Int
    private external fun nativeT9GetRemainingDigits(): String?
    private external fun nativeT9GetFirstSyllableOptions(digits: String, maxResults: Int): String?

    /**
     * 直接选择拼音：传入拼音和对应数字长度，t9_processor 替换 buffer。
     */
    fun t9SelectPinyinDirect(pinyin: String, digitLength: Int): Boolean {
        if (!isInitialized) return false
        return tryLocked(false) {
            if (!nativeHasSession() && !nativeCreateSession()) return@tryLocked false
            nativeT9SelectPinyinDirect(pinyin, digitLength)
        }
    }

    /**
     * 获取 partial commit 后 t9_processor 中剩余的数字串。
     */
    fun t9GetRemainingDigits(): String {
        if (!isInitialized) return ""
        return tryLocked("") {
            nativeT9GetRemainingDigits() ?: ""
        }
    }

    /**
     * 获取左侧面板状态字符串（格式：STATE;PINYIN;DIGIT_LEN;SEL_DIGITS;PANEL_DIGITS;LEFT_LOCKED）。
     * 用于 Kotlin 侧同步 C++ 的选中拼音和面板数字。
     */
    fun t9GetLeftPanelState(): String {
        if (!isInitialized) return "IDLE;;;;;0"
        return tryLocked("IDLE;;;;;0") {
            nativeT9GetLeftPanelState() ?: "IDLE;;;;;0"
        }
    }

    /**
     * 获取并消费 P1 撤销 RightCommit 的计数。
     * 每次 P1 撤销自增 1，每次查询后自减，避免重复消费。
     */
    fun t9GetAndConsumeUndoneRightCommitCount(): Int {
        if (!isInitialized) return 0
        return tryLocked(0) {
            nativeT9GetAndConsumeUndoneRightCommitCount()
        }
    }

    /**
     * 获取首音节候选列表（P3 方案 A：替代 Kotlin T9PinyinMap.firstSyllableOptions）。
     * 返回格式 "pinyin|digitLength" 逗号分隔，如 "ji|2,li|2,j|1,k|1,l|1"。
     */
    fun t9GetFirstSyllableOptions(digits: String, maxResults: Int = 20): List<Pair<String, Int>> {
        if (!isInitialized || digits.isEmpty()) return emptyList()
        return tryLocked(emptyList()) {
            val raw = nativeT9GetFirstSyllableOptions(digits, maxResults) ?: return@tryLocked emptyList()
            if (raw.isEmpty()) return@tryLocked emptyList()
            raw.split(",").mapNotNull { entry ->
                val parts = entry.split("|")
                if (parts.size == 2) {
                    Pair(parts[0], parts[1].toIntOrNull() ?: 1)
                } else null
            }
        }
    }

    /**
     * 清空 T9Processor 全部状态（buffer + undo + state machine）+ RIME composition。
     * @param mode 0=仅清 composition（保留 local state），1=全清（clearAll 场景）
     */
    fun t9ClearComposition(mode: Int) {
        if (!isInitialized) return
        tryLocked(Unit) {
            nativeT9ClearComposition(mode)
        }
    }

    /**
     * 执行 T9Processor 累积的待发送引擎动作（set_input → compose / clear 等）。
     *
     * 异步 flush 模型：T9 处理器在 processKey 内只标记待发送内容（SendToRime），
     * 真正触发引擎 compose 的调用延迟到此处执行。必须由调用方保证在
     * processKey 之后的**后台线程**调用，避免引擎 compose（2-23ms）阻塞 UI 线程。
     */
    fun t9FlushRimeInput() {
        if (!isInitialized) return
        tryLocked(Unit) {
            nativeT9FlushRimeInput()
        }
    }

    fun deploySchema(schemaId: String): Boolean {
        if (!isInitialized) return false
        return nativeDeploySchema(schemaId)
    }

    fun updateLastBuildTime() {
        if (!isInitialized) return
        nativeUpdateLastBuildTime()
    }
}
