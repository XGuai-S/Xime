package com.kingzcheung.xime.service

import com.kingzcheung.xime.keyboard.HANDWRITING_SCHEMA_ID
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.rime.T9InputController
import com.kingzcheung.xime.rime.buildT9DisplayState
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.settings.KeyboardComboEvaluator
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.keyboard.isT9Schema
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 输入会话组合/UI 更新与方案开关。
 *
 * 承载 Rime composition 到候选栏状态的映射（applyComposition/updateUIWithResult）、
 * 方案名称/开关刷新与切换、T9 切离提交等逻辑。共享状态通过 service 引用访问。
 */
internal class ImeSessionController(private val service: XimeInputMethodService) {
    internal fun applyComposition(
        composition: com.kingzcheung.xime.rime.RimeComposition,
        pluginActions: List<CandidateAction> = emptyList(),
        t9PluginInjections: List<T9CandidateInjection> = emptyList(),
    ) {
        val inputText = composition.input
        // 编码是否真的占用输入框（「候选首词显示在输入框」开启时编码让位给候选词）
        val codeInInputBox = SettingsPreferences.isCodeInInputBox(service)
        val isAsciiMode = composition.isAsciiMode

        val pendingEnglish = service.candidateState.value.pendingEnglishText

        val display = computeDisplayState(
            isAsciiMode = isAsciiMode,
            candidatesWithComments = composition.candidates,
            inputText = inputText,
            preeditText = composition.preedit,
        )
        if (display.isT9) {
            FileLogger.d(
                XimeInputMethodService.TAG,
                "T9 display: enginePreedit='${composition.preedit}' rawPreedit='${display.t9RawPreedit}' input='$inputText' partials=${service.t9PartialSegments.size}"
            )
        }
        // T9：插件候选按引擎锚点并入显示态，并生成与候选平行的 actions；
        // 非 T9：直接沿用显示态，actions 用调用方的插件变换结果。
        val t9Merged = if (display.isT9) mergeT9PluginInjections(display, t9PluginInjections) else null
        val shown = t9Merged?.let { display.copy(candidates = it.candidates, comments = it.comments) } ?: display
        val candidateActions = t9Merged?.actions ?: pluginActions

        // 用户开始实际输入时，清除候选栏中残留的 inline suggestions
        if (shown.text.isNotEmpty() || shown.candidates.isNotEmpty()) {
            service.dismissInlineSuggestions()
        }

        writeDisplayState(
            display = shown,
            rawInputText = inputText,
            hasNextPage = composition.hasNextPage,
            hasPrevPage = composition.hasPrevPage,
            // 候选词变换映射（全键盘 pluginActions / T9 平行 actions）
            candidateActions = candidateActions,
            isAsciiMode = isAsciiMode,
            pendingEnglish = pendingEnglish,
        )
        if (isAsciiMode != service.uiState.value.isAsciiMode) {
            FileLogger.i(XimeInputMethodService.TAG, "applyComposition: ascii ${service.uiState.value.isAsciiMode}->$isAsciiMode")
        }
        // 展开态时刷新跨页全量候选并重置页码（编码已变化）
        service.refreshExpandedCandidates()
        // composing 快照 → 插件（input_changed 事件；T9 与候选栏同源显示态）
        service.pluginEvents.dispatchInputChanged(if (shown.isT9) shown.text else inputText)

        refreshPendingEnglishAssociations(pendingEnglish)

        if (!service.uiState.value.toolPanelInputFocused) {
            syncInputBoxComposition(codeInInputBox, shown.text, shown.candidates, shown.isComposing)
        }
    }

    /** 在输入框模式向编辑器写入编码文本。 */
    private fun showInputBoxComposition(
        ic: android.view.inputmethod.InputConnection,
        displayText: String,
        caretOffset: Int = -1,
    ) {
        // 第二参数：末尾/未知时传 1（光标紧跟编码末尾，避免传 displayText.length 时被 AOSP
        // 钳制到整段文本末尾）；编码编辑态（caret 在中间）传 offset+1，光标落在编码
        // 第 offset 个字符之后。
        // 标记输入框存在 composing 区域：endComposingInputBox 仅在此标记下执行 setComposingText("") 清空，
        // 否则该调用会在光标处插入空串，光标处有选中文字时等于删除选区。
        service.markInputBoxComposing()
        ic.beginBatchEdit()
        try {
            val cursorArg = if (caretOffset < 0 || caretOffset >= displayText.length) 1 else caretOffset + 1
            ic.setComposingText(displayText, cursorArg)
        } finally {
            ic.endBatchEdit()
        }
    }

    /** 取刚组装进 candidateState 的显示光标偏移（输入框 composing 光标用）。 */
    private fun preeditCaretOffsetForInputBox(displayText: String): Int {
        return service.candidateState.value.preeditCaretPos.takeIf { displayText.isNotEmpty() } ?: -1
    }

    /**
     * 把当前应显示的文本同步进系统输入框 composing 区。
     *
     * 两条来源互斥、优先级明确：
     * - 「候选首词显示在输入框」开启 → 写**第一个候选词**（编码让位，只在候选栏显示）。
     *   开关开启时绝不写编码，保证 composing 区内容确定——清空/撤回/删除会话要靠
     *   [SettingsPreferences.isCodeInInputBox] 判断输入框里到底是编码还是候选词。
     * - 否则「编码显示=显示在输入框」→ 写编码。
     *
     * 两者都不满足时清空 composing 区，避免上一帧的编码/候选词残留在正文里。
     */
    private fun syncInputBoxComposition(
        codeInInputBox: Boolean,
        displayText: String,
        displayCandidates: List<String>,
        isComposing: Boolean,
    ) {
        val candidateFirst = SettingsPreferences.showCandidateFirstInInputBox(service)
        if (!codeInInputBox && !candidateFirst) return
        val candidate = displayCandidates.firstOrNull().orEmpty()
            .takeIf { candidateFirst && isComposing && it.isNotEmpty() }
        val text = candidate ?: displayText.takeIf { codeInInputBox && isComposing && it.isNotEmpty() }
        if (text == null) {
            service.endComposingInputBox()
            return
        }
        // 候选词不是可编辑编码，光标固定在末尾；编码沿用编辑光标
        showInputBoxComposition(
            service.currentInputConnection,
            text,
            if (candidate != null) -1 else preeditCaretOffsetForInputBox(displayText),
        )
    }

    /**
     * 计算编码显示串中的光标偏移（字符，-1 = 末尾/非编辑态）。
     *
     * 编辑光标由宿主维护（service.editingCaretPos，相对 raw input）；librime caret
     * 恒在编码末尾，引擎返回的光标信息不反映编辑位置。此处把 raw 偏移映射到显示串：
     * 逐字符对应、跳过音节分隔符（' 或空格）——全拼的音节分隔回显可精确对应；
     * 显示格式差异大的方案（双拼展开等）仅影响竖线视觉位置，编辑位置仍按 raw input。
     *
     * 失同步自愈：编码为空、光标越界，或编码与编辑态快照不一致（Shift+字母清组合、
     * 选词、外部 clearComposition 等非编辑路径改动过编码）时复位编辑态，
     * 防止残留位置导致竖线错位或编辑拦截在错误位置删除/插入。
     */
    private fun displayCaretOffset(displayText: String, inputText: String): Int {
        val editingCaret = service.editingCaretPos
        if (editingCaret < 0 || inputText.isEmpty() || displayText.isEmpty() ||
            editingCaret >= inputText.length || service.editingCaretInput != inputText
        ) {
            service.editingCaretPos = -1
            service.editingCaretInput = ""
            return -1
        }
        if (displayText == inputText) return editingCaret
        var raw = 0
        var disp = 0
        while (disp < displayText.length && raw < editingCaret) {
            val ch = displayText[disp]
            if (ch == '\'' || ch == ' ') {
                disp++
                continue
            }
            raw++
            disp++
        }
        return if (disp >= displayText.length) -1 else disp
    }

    /** 候选栏显示态：由引擎输出（composition / process result）映射而来，两个入口共用。 */
    private data class DisplayState(
        val isT9: Boolean,
        val text: String,
        val candidates: List<String>,
        val comments: List<String>,
        val isComposing: Boolean,
        /** T9 路径下参与显示决策的 rawPreedit（仅供 [applyComposition] 的诊断日志使用）。 */
        val t9RawPreedit: String? = null,
    )

    /** T9 插件候选并入后的结果：候选、注释，以及与候选平行的 actions。 */
    private class MergedT9Candidates(
        val candidates: List<String>,
        val comments: List<String>,
        val actions: List<CandidateAction>,
    )

    /**
     * 引擎输出 → 候选栏显示态（[applyComposition] 与 [updateUIWithResult] 共用）。
     *
     * 收敛前这段逻辑（ASCII 汉字过滤、T9 候选过滤、T9/非 T9 显示分支）在两个入口里
     * 各有一份几乎逐字相同的实现，改一处漏一处就会让「组合输入」与「按键处理结果」
     * 两条路径的候选栏表现不一致。
     */
    private fun computeDisplayState(
        isAsciiMode: Boolean,
        candidatesWithComments: Array<com.kingzcheung.xime.rime.RimeCandidate>,
        inputText: String,
        preeditText: String,
    ): DisplayState {
        val (filteredTexts, filteredComments) = filterCandidatesForDisplay(isAsciiMode, candidatesWithComments)

        val isT9 = isT9Schema(service.uiState.value.currentSchemaId)
        // T9 候选词过滤：根据左侧选择历史过滤不匹配的候选词
        val (t9FilteredTexts, t9FilteredComments) = if (isT9) {
            service.keyboardCallbacks?.onFilterT9Candidates?.invoke(filteredTexts, filteredComments)
                ?: (filteredTexts to filteredComments)
        } else {
            filteredTexts to filteredComments
        }

        if (isT9) {
            // 编码显示规则（永不回退原始数字 input）见 resolveT9RawPreedit
            val rawPreedit = resolveT9RawPreedit(preeditText, inputText, service.candidateState.value.preeditText)
            // preedit 转换由 C++ t9_filter 完成，Kotlin 侧直接使用引擎输出的 preedit
            val display = buildT9DisplayState(
                service.t9PartialSegments.map { it.text }, rawPreedit, inputText, t9FilteredTexts, t9FilteredComments
            )
            return DisplayState(
                isT9 = true,
                text = display.displayText,
                candidates = display.displayCandidates,
                comments = display.displayComments,
                isComposing = display.isComposing,
                t9RawPreedit = rawPreedit,
            )
        }
        // 非 T9 方案：preeditText 用引擎回显（带音节分隔符，如全拼 ni'hao）供候选栏展示；
        // inputText 保留原始键入串（无分隔）——空格/切模式等提交路径会把它直接上屏，
        // 不能混入回显字符。历史上曾整体用 input 规避自装双拼插件的展开编码显示，
        // 代价是全拼丢失分隔符；现仅显示层恢复 librime 标准回显。
        return DisplayState(
            isT9 = false,
            text = resolveDisplayText(preeditText, inputText),
            candidates = filteredTexts,
            comments = filteredComments,
            isComposing = inputText.isNotEmpty(),
        )
    }

    /**
     * T9 插件候选按引擎锚点插入（transformForT9 已把插件输出顺序折叠为
     * anchorEngineIndex；编码命中→锚 0→第二候选位，内容命中→跟随对应候选；
     * 无锚点追加末尾）。引擎段 actions 用平行 index 引用（T9 点击走引擎分支，
     * 不实际消费 engineIndex，仅用于插件候选分流）。
     */
    private fun mergeT9PluginInjections(
        display: DisplayState,
        injections: List<T9CandidateInjection>,
    ): MergedT9Candidates {
        val baseCands = display.candidates
        val baseComments = display.comments
        val byAnchor = injections.groupBy { it.anchorEngineIndex }
        val mergedCands = ArrayList<String>(baseCands.size + injections.size)
        val mergedComments = ArrayList<String>(mergedCands.size)
        val mergedActions = ArrayList<CandidateAction>(mergedCands.size)
        for ((idx, cand) in baseCands.withIndex()) {
            byAnchor[idx]?.forEach { inj ->
                mergedCands.add(inj.snapshot.text)
                mergedComments.add(inj.snapshot.comment)
                mergedActions.add(CandidateAction.plugin(inj.snapshot.text))
            }
            mergedCands.add(cand)
            mergedComments.add(baseComments.getOrElse(idx) { "" })
            mergedActions.add(CandidateAction.engine(idx))
        }
        byAnchor[null]?.forEach { inj ->
            mergedCands.add(inj.snapshot.text)
            mergedComments.add(inj.snapshot.comment)
            mergedActions.add(CandidateAction.plugin(inj.snapshot.text))
        }
        return MergedT9Candidates(mergedCands, mergedComments, mergedActions)
    }

    /**
     * 把显示态写入 candidateState（两个入口共用）。
     *
     * 两处差异只由参数表达：候选栏页信息来自各自的输入对象；候选动作由调用方决定
     * （T9 用与候选平行的 actions，非 T9 用插件变换 actions）。
     */
    private fun writeDisplayState(
        display: DisplayState,
        rawInputText: String,
        hasNextPage: Boolean,
        hasPrevPage: Boolean,
        candidateActions: List<CandidateAction>,
        isAsciiMode: Boolean,
        pendingEnglish: String,
    ) {
        // 组合键：输入码完整命中时，候选栏直接给出求值后的结果（日期/节气/命令），
        // 覆盖引擎候选。T9 不接入（其输入是数字码，与组合键编码空间不同）。
        // 防御：组合键涉及用户可改的配置与日期格式化，任何异常都只能降级成「不干预」，
        // 绝不能让输入法进程崩掉（真机曾因正则兼容性在此处崩成 ExceptionInInitializerError）。
        val combo = if (display.isT9) null else runCatching {
            KeysConfigHelper.matchCombo(rawInputText)
        }.getOrNull()
        if (combo != null) {
            val evaluated = runCatching {
                combo.items.map { item ->
                    if (item.isCommand) {
                        item.label.orEmpty().ifEmpty { item.command.orEmpty() } to
                            CandidateAction.command(item.command.orEmpty())
                    } else {
                        val text = KeyboardComboEvaluator.evaluate(item.template.orEmpty())
                        text to CandidateAction.combo(text)
                    }
                }.filter { (label, _) -> label.isNotEmpty() }
            }.getOrDefault(emptyList())
            if (evaluated.isEmpty()) return
            service.candidateState.value = service.candidateState.value.copy(
                inputText = rawInputText,
                preeditText = rawInputText,
                candidates = evaluated.map { it.first },
                candidateComments = evaluated.map { "组合" },
                candidateActions = evaluated.map { it.second },
                isComposing = true,
                associationCandidates = emptyList(),
                isShowingRecentClipboard = false,
                hasNextPage = false,
                hasPrevPage = false,
                preeditCaretPos = -1,
            )
            return
        }
        // 用户手动排序（纯引擎路径）：按本地词频表重排候选栏显示顺序。
        // 重排产生的 engine actions 让点选仍落在正确的引擎候选上
        // （selectCandidateAsync 走 pendingAction.engineIndex）。
        val reordered = if (shouldApplyCandidateOrder(display.isT9, isAsciiMode, candidateActions)) {
            CandidateOrderStore.reorder(
                code = rawInputText,
                candidates = display.candidates,
                comments = display.comments,
                table = service.candidateOrderTable,
            )
        } else {
            null
        }
        service.candidateState.value = service.candidateState.value.copy(
            // T9 保持合成显示态同源；非 T9 仅显示层用 preedit 回显，inputText 保留原始键入串
            inputText = if (display.isT9) display.text else rawInputText,
            preeditText = display.text,
            candidates = reordered?.candidates ?: display.candidates,
            candidateComments = reordered?.comments ?: display.comments,
            isComposing = display.isComposing,
            associationCandidates = if ((isAsciiMode || !service.isChineseMode) && pendingEnglish.isEmpty()) emptyList() else service.candidateState.value.associationCandidates,
            isShowingRecentClipboard = false,
            hasNextPage = hasNextPage,
            hasPrevPage = hasPrevPage,
            candidateActions = reordered?.actions ?: candidateActions,
            preeditCaretPos = displayCaretOffset(display.text, rawInputText)
        )
    }

    /**
     * 回填英文联想候选（两个入口共用）。
     *
     * 在途联想过期校验：pendingEnglish 已变/已清（如点击候选栏「清空」）时丢弃迟到回填，
     * 防止「清了又冒出来」与旧联想覆盖新联想的竞态。
     */
    private fun refreshPendingEnglishAssociations(pendingEnglish: String) {
        if (pendingEnglish.isEmpty() || service.isSecretEditor() || !service.supportsEnglishCandidateReplace()) return
        service.serviceScope.launch {
            val candidates = service.predictionManager.getEnglishAssociations(pendingEnglish, PredictionManager.MAX_ASSOCIATION_COUNT)
            withContext(Dispatchers.Main) {
                val current = service.candidateState.value
                if (current.pendingEnglishText == pendingEnglish) {
                    service.candidateState.value = current.copy(associationCandidates = candidates)
                }
            }
        }
    }

    internal fun updateUIWithResult(
        result: com.kingzcheung.xime.rime.RimeProcessResult,
        pluginActions: List<CandidateAction> = emptyList(),
    ) {
        val isAsciiMode = result.isAsciiMode

        val pendingEnglish = service.candidateState.value.pendingEnglishText

        val display = computeDisplayState(
            isAsciiMode = isAsciiMode,
            candidatesWithComments = result.candidates,
            inputText = result.inputText,
            preeditText = result.preeditText,
        )

        // 用户开始实际输入时，清除候选栏中残留的 inline suggestions
        if (display.text.isNotEmpty() || display.candidates.isNotEmpty()) {
            service.dismissInlineSuggestions()
        }

        writeDisplayState(
            display = display,
            rawInputText = result.inputText,
            hasNextPage = result.hasNextPage,
            hasPrevPage = result.hasPrevPage,
            // 候选词变换映射（T9 不接入变换，防御清空；ascii 时调用方不产生 actions）
            candidateActions = if (display.isT9) emptyList() else pluginActions,
            isAsciiMode = isAsciiMode,
            pendingEnglish = pendingEnglish,
        )
        service.uiState.value = service.uiState.value.copy(isAsciiMode = isAsciiMode)
        // 展开态时刷新跨页全量候选并重置页码（编码已变化）
        service.refreshExpandedCandidates()
        // 候选展开页：编码删空（候选与联想均空）时自动收起，不留空页
        service.maybeCollapseCandidatePage()
        // composing 快照 → 插件（input_changed 事件；空编码表示本轮输入结束）
        service.pluginEvents.dispatchInputChanged(if (display.isT9) display.text else result.inputText)

        refreshPendingEnglishAssociations(pendingEnglish)

        syncInputBoxComposition(
            SettingsPreferences.isCodeInInputBox(service), display.text, display.candidates, display.isComposing
        )
    }

    internal fun updateSchemaName() {
        val context = service
        service.serviceScope.launch(Dispatchers.IO) {
            val page = service.keyboardViewModel.page.value
            val isHandwritingMode = (page as? com.kingzcheung.xime.keyboard.KeyboardPage.Main)?.type == com.kingzcheung.xime.keyboard.MainType.HANDWRITING
            val engineSchemaId = service.rimeEngine.getCurrentSchema()
            // session 未就绪时 getCurrentSchema() 返回空串：用持久化方案兜底，
            // 避免空值覆盖已正确的 currentSchemaId/schemaName 导致键盘退化为全键盘
            val currentSchemaId = when {
                // 引擎已切到非手写方案时以引擎为准：键盘若仍停留手写页（部署期间
                // fallback 的残留），钉死 handwriting 会让 UI 与引擎永久脱节，
                // 表现为选完方案后键盘卡在手写页
                engineSchemaId.isNotEmpty() && !isHandwritingSchema(engineSchemaId) -> engineSchemaId
                // 手写页在态时报告当前持久化的手写方案 id（内置为 handwriting，
                // 第三方手写方案报告其自身 id，方案名/图标显示才正确）
                isHandwritingMode -> SettingsPreferences.getCurrentSchema(context)
                    .takeIf { isHandwritingSchema(it) } ?: HANDWRITING_SCHEMA_ID
                engineSchemaId.isNotEmpty() -> engineSchemaId
                else -> SettingsPreferences.getCurrentSchema(context)
            }
            val name = SchemaManager.getSchemaDisplayName(context, currentSchemaId)

            val enabledIds = SchemaManager.getEnabledSchemas(context)
            val allSchemas = SchemaManager.discoverSchemas(context)
            val schemas = allSchemas
                .filter { meta -> meta.schemaId in enabledIds && SchemaManager.isSchemaCompiled(context, meta.schemaId) }
                .map { meta ->
                    com.kingzcheung.xime.settings.SchemaInfo(
                        schemaId = meta.schemaId,
                        name = meta.name,
                        version = meta.version,
                        author = meta.author,
                        description = meta.description,
                        isDownloaded = true
                    )
                }

            withContext(Dispatchers.Main) {
                service.uiState.value = service.uiState.value.copy(
                    schemaName = name ?: currentSchemaId,
                    currentSchemaId = currentSchemaId,
                    schemas = schemas
                )
                refreshSchemaSwitches()
            }
        }
    }

    /** 读取当前引擎方案 `switches` 的实际取值，组装成菜单栏可展示的状态。 */
    private fun loadSchemaSwitches(schemaId: String): List<com.kingzcheung.xime.viewmodel.SchemaSwitchUiState> {
        if (schemaId.isEmpty()) return emptyList()
        val defs = SchemaManager.getSchemaSwitches(service, schemaId)
        return defs.map { def ->
            val index = when {
                def.name.isNotEmpty() && def.states.isNotEmpty() ->
                    if (service.rimeEngine.getOption(def.name)) minOf(1, def.states.size - 1) else 0
                def.options.isNotEmpty() -> {
                    val active = def.options.indexOfFirst { service.rimeEngine.getOption(it) }
                    if (active < 0) 0 else active
                }
                else -> 0
            }
            com.kingzcheung.xime.viewmodel.SchemaSwitchUiState(
                name = def.name,
                options = def.options,
                states = def.states,
                abbrev = def.abbrev,
                currentIndex = index
            )
        }
    }

    /** 在引擎线程上刷新菜单栏方案开关状态。 */
    private fun refreshSchemaSwitches() {
        service.serviceScope.launch(service.keyProcessingDispatcher) {
            val schemaId = service.rimeEngine.getCurrentSchema()
            val switches = loadSchemaSwitches(schemaId)
            withContext(Dispatchers.Main) {
                service.uiState.value = service.uiState.value.copy(schemaSwitches = switches)
            }
        }
    }

    /** 菜单栏方案开关点击：切换引擎选项并刷新状态。 */
    internal fun toggleSchemaSwitch(sw: com.kingzcheung.xime.viewmodel.SchemaSwitchUiState) {
        service.serviceScope.launch(service.keyProcessingDispatcher) {
            if (sw.name == "ascii_mode") {
                // 菜单中西切换 = 用户显式操作（USER_TOGGLE，会话级，不持久化）
                service.asciiModeController.switchAscii(AsciiModeController.Reason.USER_TOGGLE)
            } else if (sw.name.isNotEmpty()) {
                val newValue = !service.rimeEngine.getOption(sw.name)
                service.rimeEngine.setOption(sw.name, newValue)
                persistSchemaOption(sw.name, newValue)
                service.updateUI()
            } else if (sw.options.isNotEmpty()) {
                val nextIndex = (sw.currentIndex + 1) % sw.options.size
                sw.options.forEachIndexed { i, opt ->
                    service.rimeEngine.setOption(opt, i == nextIndex)
                    persistSchemaOption(opt, i == nextIndex)
                }
                service.updateUI()
            }
            refreshSchemaSwitches()
        }
    }

    /** 将方案选项状态写入 librime user.yaml（var/option/<name>）。 */
    internal fun persistSchemaOption(name: String, value: Boolean) {
        if (RimeEngine.isInitialized()) {
            service.rimeEngine.setUserConfigBool("var/option/$name", value)
        }
    }

    /** 从 librime user.yaml 恢复方案选项（简/繁等），在切换方案后调用。
     *  ascii_mode 不在此恢复：由 AsciiModeController.applyStartDecision 按编辑框
     *  类型与用户显式选择决策，避免通用恢复盖过会话级决策。 */
    internal fun restorePersistedSchemaOptions() {
        if (!RimeEngine.isInitialized()) return
        val schemaId = service.rimeEngine.getCurrentSchema()
        if (schemaId.isEmpty()) return
        val defs = SchemaManager.getSchemaSwitches(service, schemaId)
        for (def in defs) {
            if (def.name.isNotEmpty()) {
                if (def.name == "ascii_mode") continue
                service.rimeEngine.setOption(def.name, service.rimeEngine.getUserConfigBool("var/option/${def.name}"))
            } else if (def.options.isNotEmpty()) {
                val activeIndex = def.options.indexOfFirst { service.rimeEngine.getUserConfigBool("var/option/$it") }
                if (activeIndex >= 0) {
                    def.options.forEachIndexed { i, opt -> service.rimeEngine.setOption(opt, i == activeIndex) }
                }
            }
        }
    }

    /**
     * T9 键盘切换离开时：提交右侧候选词列表首位候选词并清理 T9 和 Rime 状态。
     * 运行在 keyProcessingDispatcher 线程。
     */
    internal suspend fun commitFirstCandidateAndClearT9() {
        val isT9 = isT9Schema(service.uiState.value.currentSchemaId)
        if (!isT9) return

        val candState = service.candidateState.value
        val candidates = candState.candidates

        if (candidates.isNotEmpty()) {
            if (service.rimeEngine.selectCandidate(0)) {
                val committedText = service.rimeEngine.commit()
                if (committedText.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        service.commitText(committedText)
                    }
                }
            }
        }

        service.rimeEngine.clearComposition()

        withContext(Dispatchers.Main) {
            service.keyboardCallbacks?.onT9ReplaceFullPinyin?.invoke(T9InputController.CLEAR_ALL)
            service.uiState.value = service.uiState.value.copy(
                t9ResetSignal = service.uiState.value.t9ResetSignal + 1,
                t9RightCandidateSelectedCount = 0,
                t9SelectedCandidatePinyin = ""
            )
            service.t9PartialSegments.clear()
            service.candidateState.value = service.candidateState.value.copy(
                inputText = "",
                preeditText = "",
                candidates = emptyList(),
                candidateComments = emptyList(),
                isComposing = false,
                associationCandidates = emptyList(),
                hasNextPage = false,
                hasPrevPage = false,
                candidateActions = emptyList()
            )
        }
    }

}
