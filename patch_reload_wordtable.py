#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""3.2.100：「重载」也要让手改词库生效。

词库表（词频/造词/排序/黑名单）不进部署 hash —— word_freq 每分钟都在变，计入 hash
会让每次上屏都触发部署。代价是：用户手改这些表后点「重载」，hash 判定一致 → 编译被
静默跳过 → 改了不生效（词频只影响 Kotlin 层排序，词条增删必须重编 table）。

这里在「重载」路径上加一条：reconcile 检测到外部手改 → 先落盘重新生成派生 dict →
ensureDeployment(force=true) 跳过 hash 快捷路径，走增量重编。
"""
import io

SVC = '/root/xime/app/src/main/java/com/kingzcheung/xime/service/XimeInputMethodService.kt'
RCH = '/root/xime/app/src/main/java/com/kingzcheung/xime/rime/RimeConfigHelper.kt'
SCI = '/root/xime/app/src/main/java/com/kingzcheung/xime/service/ImeSchemaController.kt'


def patch(path, pairs):
    s = io.open(path, encoding='utf-8').read()
    for old, new in pairs:
        c = s.count(old)
        assert c == 1, '%s: count=%d for %r' % (path, c, old[:70])
        s = s.replace(old, new)
    io.open(path, 'w', encoding='utf-8').write(s)
    print('patched', path)


# ── 1. RimeConfigHelper：ensureDeployment 支持 force ──
patch(RCH, [(
    '    fun ensureDeployment(context: Context): Boolean {',
    '''    /**
     * @param force 调用方检测到「用户手改了词库表」时传 true：跳过 hash 快捷路径。
     *   词频/造词/排序/黑名单这些表**故意不进**部署 hash（word_freq 每分钟都在变，
     *   计入 hash 会让每次上屏都触发部署），代价是只改它们时 hash 一致、编译被静默
     *   跳过 —— 表现为「重载了但词库没变」。force 只跳过 hash 判断，不删 build：
     *   build 完整时仍走增量维护，只重编变更的 dict。
     */
    fun ensureDeployment(context: Context, force: Boolean = false): Boolean {''',
), (
    r'''            val artifactsComplete = hasCompleteBuildArtifacts(context)
            if (currentHash.isNotEmpty() &&
                currentHash == SettingsPreferences.getDeploymentHash(context) &&
                artifactsComplete
            ) {
                SettingsPreferences.setDeploymentDone(context, true)
                return true
            }''',
    r'''            val artifactsComplete = hasCompleteBuildArtifacts(context)
            if (force) {
                Log.i(TAG, "ensureDeployment: forced (user changed word tables), skip hash shortcut")
                FileLogger.i(TAG, "ensureDeployment: forced by word-table change, skip hash shortcut")
            }
            if (!force &&
                currentHash.isNotEmpty() &&
                currentHash == SettingsPreferences.getDeploymentHash(context) &&
                artifactsComplete
            ) {
                SettingsPreferences.setDeploymentDone(context, true)
                return true
            }''',
)])

# ── 2. 服务：reconcile 返回「是否有外部手改」+ 落盘辅助 ──
patch(SVC, [(
    r'''    internal fun reconcileUserDataFiles() {
        // 与 saveWordData / recordWordFrequency 互斥，见 wordTablesLock 注释。
        wordTablesLock.lock()
        try {
            candidateOrderTable = CandidateOrderStore.reconcile(this, candidateOrderTable)
            wordFreqTable = WordFreqStore.reconcile(this, wordFreqTable)
            autoWordTemp = AutoWordStore.reconcileTemp(this, autoWordTemp)
            autoWordFixed = AutoWordStore.reconcileFixed(this, autoWordFixed)
            wordBlacklist = WordBlacklistStore.reconcile(this, wordBlacklist)
        } finally {
            wordTablesLock.unlock()
        }
    }''',
    r'''    internal fun reconcileUserDataFiles(): Boolean {
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
    }''',
)])

# ── 3. 重载路径：合并外部手改 → 落盘 → 强制重编 ──
patch(SCI, [(
    r'''        // 用户可能刚在 `/storage/emulated/0/rime/xime/` 下手改过词库文件（词频/造词/
        // 排序/黑名单）：先把外部修改合并进内存，「重载」才真正加载手改内容——这些表
        // 本来不随重载重读，不合并的话手改不仅不生效，下次保存还会被冲掉
        // （见 UserDataMerge）。投主线程：与 saveWordData/recordWordFrequency 同线程
        // 操作内存表，避免竞态；只读不写，与部署互不依赖。
        service.mainHandler.post { service.reconcileUserDataFiles() }

''',
    '',
), (
    r'''                // 统一走 ensureDeployment（增量优先 + 失败才清空重建 + 成功才标记）。
                // 🔴 历史 bug：直接 deleteRecursively() 清空 build + deploy() 失败也标记
                // 「已部署完成」→ 私有 build 的 .bin 被毁且永不重建 → 下次启动
                // isDeploymentComplete 误判已部署而跳过编译 → engine=0 打不出字。
                // ensureDeployment 内部：hash 一致则跳过；build 存在则增量维护；
                // 增量失败才清空全量；且仅部署成功才 storeDeploymentHash + done=true。
                val deployed = RimeConfigHelper.ensureDeployment(service)''',
    r'''                // 用户可能刚在可见目录手改过词库表（词频/造词/排序/黑名单）。这些表
                // **不进部署 hash**（word_freq 每分钟都在变，计入 hash 会让每次上屏都触发
                // 部署），所以只改它们时 hash 一致 → 编译被跳过 → 表现为「重载了但词库
                // 没变」。这里先合并手改（切主线程，与上屏写入同线程），有改动就落盘
                // 重新生成派生 dict，并强制走一遍维护/编译。
                val userDataChanged = withContext(Dispatchers.Main) { service.reconcileUserDataFiles() }
                if (userDataChanged) {
                    withContext(Dispatchers.Main) {
                        service.flushWordDataForReload()
                        android.widget.Toast.makeText(
                            service,
                            "词库有改动，正在重新编译...",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }

                // 统一走 ensureDeployment（增量优先 + 失败才清空重建 + 成功才标记）。
                // 🔴 历史 bug：直接 deleteRecursively() 清空 build + deploy() 失败也标记
                // 「已部署完成」→ 私有 build 的 .bin 被毁且永不重建 → 下次启动
                // isDeploymentComplete 误判已部署而跳过编译 → engine=0 打不出字。
                // ensureDeployment 内部：hash 一致则跳过；build 存在则增量维护；
                // 增量失败才清空全量；且仅部署成功才 storeDeploymentHash + done=true。
                val deployed = RimeConfigHelper.ensureDeployment(service, force = userDataChanged)''',
)])
print('done')
