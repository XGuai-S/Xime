package com.kingzcheung.xime.service

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.kingzcheung.xime.MainActivity
import com.kingzcheung.xime.ui.keyboard.FloatingCardGeometry
import com.kingzcheung.xime.ui.keyboard.isHandwritingSchema
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.settings.SchemaConfigHelper
import com.kingzcheung.xime.settings.RimeUserFileSync
import com.kingzcheung.xime.settings.SchemaManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.util.FileLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 方案管理与输入模式切换。
 *
 * 承载方案切换（switchSchema/applyPageSizeSetting）、部署（reloadConfig/deploy/deploySchema/downloadSchema）、
 * 工具栏编辑动作、键盘高度与浮动模式调整。
 * 中英切换已收敛至 [AsciiModeController]。
 * 共享状态通过 service 引用访问。
 */
internal class ImeSchemaController(private val service: XimeInputMethodService) {
    internal fun reloadConfig() {

        service.mainHandler.post {
            service.requestHideSelf(0)
            android.widget.Toast.makeText(service, "方案部署中...", android.widget.Toast.LENGTH_SHORT).show()
        }
        
        // 部署投递到 key-processing 队列：与按键/切换同队列串行执行，
        // 部署期间输入/切换操作排队等待，完成后自动恢复，
        // 不再因 rimeLock 被部署占用而失败或静默丢弃。
        service.keyRouter.postRimeJob {
            try {
                KeysConfigHelper.loadConfig(service)
                // 重新加载配色方案（用户可能在 xime.custom.yaml 中修改了 color_schemes）
                KeyboardThemes.reload(service)

                // 部署前把可见目录 `/storage/emulated/0/rime/xime/rime/` 里用户改过的方案与词库
                // 写回 Rime 工作目录。没有这一步，用户改完点「部署方案」读到的仍是旧文件。
                // 注意：本类有三个部署入口（reloadConfig / deploy / deploySchema），都要同步。
                RimeUserFileSync.sync(service)
                
                // 用户可能刚在可见目录手改过词库表（词频/造词/排序/黑名单）。这些表
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
                val deployed = RimeConfigHelper.ensureDeployment(service, force = userDataChanged)
                if (!deployed) {
                    FileLogger.e(
                        XimeInputMethodService.TAG,
                        "reloadConfig: ensureDeployment failed, keeping previous build artifacts"
                    )
                }
                
                // 部署完成后重新加载配置（Rime 可能在部署过程中改写文件）
                KeysConfigHelper.loadConfig(service)
                KeyboardThemes.reload(service)
                
                val availableSchemas = service.rimeEngine.getAvailableSchemas()
                
                val savedSchema = SettingsPreferences.getCurrentSchema(service)
                if (savedSchema in availableSchemas) {
                    applyPageSizeSetting(savedSchema)
                    service.rimeEngine.switchSchema(savedSchema)
                } else {
                    FileLogger.w(XimeInputMethodService.TAG, "Schema $savedSchema not found in available schemas")
                }
                
                // 直接在 key-processing 线程同步读取 name，避免嵌套协程的时序问题
                val currentSchemaId = service.rimeEngine.getCurrentSchema()
                val schemaName = SchemaManager.getSchemaDisplayName(
                    service, currentSchemaId
                ) ?: currentSchemaId

                withContext(Dispatchers.Main) {
                    service.uiState.value = service.uiState.value.copy(
                        schemaName = schemaName,
                        currentSchemaId = currentSchemaId,
                    )
                    service.updateUI()
                    android.widget.Toast.makeText(service, "方案部署完成", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                FileLogger.e(XimeInputMethodService.TAG, "Failed to reload config", e)
            }
        }
    }
    
    /**
     * 遗留入口：当前全仓没有调用点（设置页「部署」走 SchemaSettingsViewModel.deploySchema，
     * 键盘菜单「重载」走 reloadConfig）。保留但未接线，改动前请先接上或删掉。
     */
    private fun deploySchema() {
        try {
            // 同 reloadConfig：部署前先把可见目录里的用户修改写回工作目录
            RimeUserFileSync.sync(service)
            // 统一走 ensureDeployment：增量优先、失败才清空、成功才标记 done/hash。
            // （历史同 reloadConfig：直接 deploy() 失败也标记「已部署完成」，下次启动
            //  误判已部署跳过编译 → 私有 build 无 .bin → engine=0 打不出字。）
            val deployed = RimeConfigHelper.ensureDeployment(service)
            if (!deployed) {
                FileLogger.e(
                    XimeInputMethodService.TAG,
                    "deploySchema: ensureDeployment failed, keeping previous build artifacts"
                )
                return
            }
            val savedSchema = SettingsPreferences.getCurrentSchema(service)
            applyPageSizeSetting(savedSchema)
            service.rimeEngine.switchSchema(savedSchema)
            val currentSchemaId = service.rimeEngine.getCurrentSchema()
            service.uiState.value = service.uiState.value.copy(
                schemaName = SchemaManager.getSchemaDisplayName(service, currentSchemaId) ?: currentSchemaId,
                currentSchemaId = currentSchemaId,
            )
            service.updateUI()
        } catch (e: Exception) {
            FileLogger.e(XimeInputMethodService.TAG, "Failed to deploy schema", e)
        }
    }
    
    internal fun openSettings() {
        try {
            val intent = Intent(service, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
        } catch (e: Exception) {
            FileLogger.e(XimeInputMethodService.TAG, "Failed to open settings", e)
        }
    }
    
    private var editSelAnchor = -1

    internal fun handleToolbarEditingAction(action: String) {
        val ic = service.currentInputConnection ?: return
        when (action) {
            "select_all" -> ic.performContextMenuAction(android.R.id.selectAll)
            "copy" -> ic.performContextMenuAction(android.R.id.copy)
            "cut" -> ic.performContextMenuAction(android.R.id.cut)
            "paste" -> ic.performContextMenuAction(android.R.id.paste)
            "tab" -> {
                // 发真实的 TAB 键：多行编辑器/终端/IDE 才会当缩进处理；
                // 普通单行输入框本来就忽略 Tab，不会插入字符
                val t = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0))
                ic.sendKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0))
            }
            "home" -> ic.setSelection(0, 0)
            "end" -> {
                val before = ic.getTextBeforeCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
                val after = ic.getTextAfterCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
                ic.setSelection(before.length + after.length, before.length + after.length)
            }
            // 方向键不再给宿主发 DPAD 键事件：宿主常把 DPAD 当焦点切换，
            // 结果输入框失焦、编辑键盘被收起退回主键盘（用户 2026-11 反馈）。
            // 改为直接操作光标（组合态移编码光标、空闲态移选区，见 moveCursor）。
            "arrow_up" -> moveCursorToLine(ic, -1)
            "arrow_down" -> moveCursorToLine(ic, 1)
            "arrow_left" -> service.moveCursor(-1)
            "arrow_right" -> service.moveCursor(1)

            "select_begin" -> {
                editSelAnchor = (ic.getTextBeforeCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: "").length
            }
            "select_end" -> {
                editSelAnchor = -1
            }
            "select_arrow_left" -> extendSelection(ic, -1)
            "select_arrow_right" -> extendSelection(ic, 1)
            "select_arrow_up" -> {
                val before = ic.getTextBeforeCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
                val pos = before.length
                if (pos > 0) {
                    val prevNewline = before.lastIndexOf('\n', pos - 2)
                    val lineStart = if (prevNewline >= 0) prevNewline + 1 else 0
                    ic.beginBatchEdit()
                    ic.setSelection(editSelAnchor, lineStart)
                    ic.endBatchEdit()
                }
            }
            "select_arrow_down" -> {
                val before = ic.getTextBeforeCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
                val after = ic.getTextAfterCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
                val pos = before.length
                val total = before.length + after.length
                if (pos < total) {
                    val nextNewline = after.indexOf('\n')
                    val lineEnd = if (nextNewline >= 0) pos + nextNewline else total
                    ic.beginBatchEdit()
                    ic.setSelection(editSelAnchor, lineEnd)
                    ic.endBatchEdit()
                }
            }
        }
    }

    /**
     * 上下方向键：把光标移到上一行/下一行的行首（不发送按键事件给宿主）。
     *
     * 发 DPAD 给宿主时，很多应用会把它解释成控件焦点切换，导致输入框失焦、
     * 键盘（含编辑键盘）被系统收起——这正是「按箭头就返回主键盘」的原因。
     */
    private fun moveCursorToLine(ic: InputConnection, direction: Int) {
        val before = ic.getTextBeforeCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
        val after = ic.getTextAfterCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
        val pos = before.length
        val total = pos + after.length
        val target = if (direction < 0) {
            if (pos <= 0) {
                0
            } else {
                val prevNewline = before.lastIndexOf('\n', pos - 2)
                if (prevNewline >= 0) prevNewline + 1 else 0
            }
        } else {
            val nextNewline = after.indexOf('\n')
            if (nextNewline >= 0) pos + nextNewline + 1 else total
        }
        ic.setSelection(target, target)
    }

    private fun extendSelection(ic: InputConnection, direction: Int) {
        val before = ic.getTextBeforeCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
        val after = ic.getTextAfterCursor(XimeInputMethodService.SAFE_TEXT_LIMIT, 0) ?: ""
        val pos = before.length
        val total = before.length + after.length
        val next = (pos + direction).coerceIn(0, total)
        if (next != pos) {
            ic.beginBatchEdit()
            ic.setSelection(editSelAnchor, next)
            ic.endBatchEdit()
        }
    }

    internal fun applyPageSizeSetting(schemaId: String) {
        val userPageSize = SettingsPreferences.getPageSize(service)
        if (userPageSize > 0) {
            service.rimeEngine.setPageSize(schemaId, userPageSize)
        }
    }

    internal fun switchSchema(schemaId: String) {
        if (isHandwritingSchema(schemaId)) {
            // 检查手写模型文件是否已下载
            if (!com.kingzcheung.xime.handwriting.HandwritingEngine.hasModel(service)) {
                FileLogger.w(XimeInputMethodService.TAG, "Handwriting model not found, redirecting to download")
                android.widget.Toast.makeText(
                    service, "请先下载手写模型", android.widget.Toast.LENGTH_LONG
                ).show()
                val intent = android.content.Intent(
                    service, com.kingzcheung.xime.MainActivity::class.java
                ).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("open_fragment", "model_management")
                }
                service.startActivity(intent)
                return
            }
            SettingsPreferences.setCurrentSchema(service, schemaId)
            service.keyboardViewModel.switchMain(com.kingzcheung.xime.keyboard.MainType.HANDWRITING)
            // 手写模型按"用键盘时加载"管理：切到手写方案即展示手写键盘，
            // 由 HandwritingKeyboardLayout 创建时（LaunchedEffect）负责加载，
            // 此处不重复加载（避免与布局初始化并发双 bind/load）
            service.sessionController.updateSchemaName()
            return
        }
        // 切离手写方案时释放手写引擎
        com.kingzcheung.xime.handwriting.HandwritingEngine.release()
        service.keyboardViewModel.switchMain(com.kingzcheung.xime.keyboard.MainType.FULL)
        try {
            SettingsPreferences.setCurrentSchema(service, schemaId)
            // 用户自定义候选词数：先写 custom.yaml 再切方案，Rime 会自动加载
            applyPageSizeSetting(schemaId)
            // 部署/编译进行中 switchSchema 返回 false（不阻塞等待），
            // 此时不应继续触发其他 native 调用进入编译中的引擎
            if (!service.rimeEngine.switchSchema(schemaId)) {
                if (service.rimeEngine.isMaintaining()) {
                    FileLogger.w(XimeInputMethodService.TAG, "switchSchema skipped: deployment in progress")
                    Toast.makeText(service, "词库部署中，请稍后再切换方案", Toast.LENGTH_SHORT).show()
                    return
                }
                // 引擎切换失败（常见：方案未部署/不在 schema_list，老版本升级残留）。
                // 必须回滚偏好到引擎实际方案：偏好留着目标值会让 UI 状态与引擎
                // 永久脱节（键盘已显示九键但数字键无人处理，候选栏始终 IDLE）。
                // getCurrentSchema 无会话时返回空串——此时不动偏好，保留原值由
                // IME 重启的恢复逻辑兜底，避免把空串写进偏好与 user.yaml
                val actual = service.rimeEngine.getCurrentSchema()
                if (actual.isNotEmpty()) {
                    SettingsPreferences.setCurrentSchema(service, actual)
                }
                FileLogger.w(XimeInputMethodService.TAG, "switchSchema failed: target=$schemaId actual=$actual")
                Toast.makeText(service, "方案未部署，请在方案管理中部署后再试", Toast.LENGTH_SHORT).show()
                return
            }
            if (!service.rimeEngine.isAsciiMode()) {
                service.rimeEngine.setOption("ascii_punct", false)
            }
            service.sessionController.updateSchemaName()
            service.updateUI()
            // 确保键盘布局与方案匹配（如 T9 九键不应被 switchMain 重置为全键盘）
            service.keyboardViewModel.dispatch(
                com.kingzcheung.xime.ui.keyboard.KeyboardDispatchAction.AsciiModeChanged(
                    service.rimeEngine.isAsciiMode(), schemaId
                )
            )
            Toast.makeText(service, "已切换输入方案", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            FileLogger.e(XimeInputMethodService.TAG, "Failed to switch schema", e)
        }
    }
    
    private fun downloadSchema(schemaId: String) {
        service.serviceScope.launch(Dispatchers.IO) {
            service.notifyDeploymentStatus(true, "正在下载 $schemaId...")
            
            val success = SchemaConfigHelper.downloadSchema(service, schemaId)
            
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(service, "$schemaId 下载成功，请点击部署", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(service, "$schemaId 下载失败", Toast.LENGTH_SHORT).show()
                }
                service.notifyDeploymentStatus(false, "")
            }
        }
    }
    
    private fun deploy() {
        // 部署投递到 key-processing 队列，与输入/切换串行执行，避免持锁饿死输入
        service.keyRouter.postRimeJob {
            // 部署前刷新手势配置和配色方案缓存
            KeysConfigHelper.loadConfig(service)
            KeyboardThemes.reload(service)
            
            service.notifyDeploymentStatus(true, "正在部署...")

            // 部署前把用户在可见目录 `/storage/emulated/0/rime/xime/rime/` 里的修改写回 Rime
            // 工作目录。没有这一步，用户改完点部署，部署读到的仍是旧文件——
            // 表现为「改了没生效」，比不能改更让人困惑。
            RimeUserFileSync.sync(service)

            val success = service.rimeEngine.deploy()
            if (success) {
                // 与其余部署入口一致：成功才落 hash + done。原先漏了这一步，导致
                // ensureDeployment 下次必然 hash 失配、再白跑一轮部署。
                RimeConfigHelper.storeDeploymentHash(service)
                com.kingzcheung.xime.settings.SettingsPreferences.setDeploymentDone(service, true)
            }
            
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(service, "部署成功", Toast.LENGTH_SHORT).show()
                    service.updateUI()
                } else {
                    Toast.makeText(service, "部署失败", Toast.LENGTH_SHORT).show()
                }
                service.notifyDeploymentStatus(false, "")
            }
        }
    }
    
    internal fun setKeyboardHeight(heightDp: Int) {
        // 写入口径按方向 × 悬浮四套独立，与渲染侧读键一致（横屏悬浮独立高度）
        val isLandscape = SettingsPreferences.isLandscapeKeyboardHeightKey(service)
        val isFloating = SettingsPreferences.isFloatingMode(service, isLandscape)
        SettingsPreferences.setKeyboardHeightDp(service, heightDp, isLandscape, isFloating)
        service.uiState.value = service.uiState.value.copy(keyboardHeightDp = heightDp)
        // 不弹 Toast：调节界面本身就是实时预览，确定时键盘已经变成目标高度，
        // 再提示"键盘高度已调整"是多余噪音
    }

    internal fun toggleFloatingMode(enabled: Boolean, floatingMinY: Int = 0) {
        // 任何路径切悬浮都清掉拖动提示态（正常由拖动松手消费，这里兜底防光效残留）
        service.floatingExitHintState.value = false
        val isLandscape = service.resources.configuration.screenWidthDp > service.resources.configuration.screenHeightDp
        SettingsPreferences.setFloatingMode(service, enabled, isLandscape)
        val loadedX = SettingsPreferences.getFloatingOffsetX(service, isLandscape)
        val halfMargin = FloatingCardGeometry.halfMarginDpForConfig(
            service.resources.configuration.screenWidthDp,
            service.resources.configuration.screenHeightDp,
        )
        val clampedX = loadedX.coerceIn(-halfMargin, halfMargin)
        // 悬浮切换后立即按「方向 × 悬浮」口径重载尺寸三件套：此前 uiState 里
        // 还留着另一套口径的值（如从非悬浮切悬浮时保留非悬浮的 padding/margin），
        // 切换瞬间渲染侧会用旧口径，表现为「悬浮键盘带着非悬浮的留白/边距」。
        val refreshHeight = SettingsPreferences.getKeyboardHeightDp(service, isLandscape, enabled)
        val refreshBottom = SettingsPreferences.getKeyboardBottomPaddingDp(service, isLandscape, enabled)
        val refreshMarginStart = SettingsPreferences.getKeyboardMarginStartDp(service, isLandscape, enabled)
        val refreshMarginEnd = SettingsPreferences.getKeyboardMarginEndDp(service, isLandscape, enabled)
        service.uiState.value = service.uiState.value.copy(
            isFloatingMode = enabled,
            keyboardHeightDp = refreshHeight,
            keyboardBottomPaddingDp = refreshBottom,
        )
        service.geometryState.value = service.geometryState.value.copy(
            keyboardMarginStartDp = refreshMarginStart,
            keyboardMarginEndDp = refreshMarginEnd,
            resizePreviewMarginStartDp = refreshMarginStart,
            resizePreviewMarginEndDp = refreshMarginEnd,
            floatingOffsetX = clampedX,
            // 开启悬浮归位到"拖动可达的最低点"：拖动钳制下界是 floatingMinY（导航栏高）
            floatingOffsetY = if (enabled) floatingMinY else 0,
        )
        if (enabled) {
            service.closeToolPanel()
            // 清掉上一轮的实测矩形：新卡片矩形未建立前，触摸区/钳制走 FloatingCardGeometry 兜底
            service.floatingCardBounds = null
            service.currentFloatingCardHeightDp = 0
        }
        service.applyWindowBackground()
    }

}
