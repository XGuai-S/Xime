package com.kingzcheung.xime.service

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.association.AssociationManager
import com.kingzcheung.xime.association.AssociationService
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PredictionManager(
    private val context: Context,
    private val serviceScope: CoroutineScope,
    private val onPredictionResult: (List<String>) -> Unit,
) {
    companion object {
        private const val TAG = "PredictionManager"
        private const val MAX_CONTEXT_LENGTH = 25
        const val MAX_ASSOCIATION_COUNT = 20
    }
    
    private var _lastCommittedText = ""
    val lastCommittedText: String get() = _lastCommittedText

    /**
     * 最近一次**单次**上屏的文本（区别于 [lastCommittedText] 的滚动上下文缓冲区）。
     *
     * 「重复上一次上屏」只能重复这一笔，不能把前文一起再打一遍：旧实现直接重提
    ，后者是最近 25 字的累积上下文，换行键上滑会把整段历史重复上屏。
     */
    private var _lastCommitChunk = ""
    val lastCommitChunk: String get() = _lastCommitChunk

    /**
     * 预测请求代际号：退格/清空等改变上下文的操作调用 [invalidatePendingPredictions]，
     * 使所有在途的异步预测结果失效。否则长按退格删除时，旧的联想结果会迟到并反复
     * 回填 associationCandidates，候选栏在"显示联想词 ↔ 空"之间闪动（一闪一闪）。
     */
    private var requestEpoch = 0L

    fun invalidatePendingPredictions() {
        requestEpoch++
    }

    /**
     * 单次联想抑制标志：联想候选上屏（点击/空格）前置位，使 commitText 触发的下一轮
     * 自动推理被跳过并清空联想候选（候选栏干净），等待用户下一次真实输入。
     * 连续联想模式不置位——commitText 的自动推理即为"一直上屏一直推理"。
     */
    @Volatile
    private var suppressNextPrediction = false

    fun suppressNextPredictionOnce() {
        suppressNextPrediction = true
    }
    
    fun appendCommittedText(text: String) {
        _lastCommittedText = (_lastCommittedText + text).takeLast(MAX_CONTEXT_LENGTH)
    }

    /** 记录一次单次上屏（供「重复上一次输入」使用）。 */
    fun appendCommittedChunk(text: String) {
        if (text.isNotEmpty()) _lastCommitChunk = text
    }
    
    fun clearCommittedText() {
        _lastCommittedText = ""
        // 换会话（切换编辑框/新输入）后旧编辑框的上屏内容不再可重复，一并作废
        _lastCommitChunk = ""
    }
    
    fun deleteLastChar() {
        if (_lastCommittedText.isNotEmpty()) {
            _lastCommittedText = _lastCommittedText.dropLast(1)
        }
        // 用户删掉了刚才上屏的最后一个字：待重复的这笔内容也要同步缩短，
        // 否则换行键上滑会把用户已经删掉的字又打出来。
        if (_lastCommitChunk.isNotEmpty()) {
            _lastCommitChunk = _lastCommitChunk.dropLast(1)
        }
    }
    
    fun initialize() {
        FileLogger.i(TAG, "Initializing association system")
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                val trieInit = AssociationService.initialize(context)
                if (trieInit) {
                    FileLogger.i(TAG, "Trie association service initialized")
                } else {
                    FileLogger.w(TAG, "Trie association service initialization failed")
                }
                
                if (!ExtensionManager.isInitialized()) {
                    FileLogger.d(TAG, "ExtensionManager not initialized, initializing...")
                    ExtensionManager.initialize(context)
                }
                
                if (SettingsPreferences.isSmartPredictionEnabled(context)) {
                    try {
                        val initialized = AssociationManager.initialize(context)
                        if (initialized) {
                            FileLogger.i(TAG, "Smart prediction initialized")
                        } else {
                            FileLogger.w(TAG, "Smart prediction initialization failed")
                        }
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Failed to initialize smart prediction: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to initialize association system: ${e.message}")
            }
        }
    }
    
    fun getPrediction(contextText: String) {
        // 单次联想：消费抑制标志——联想上屏引发的本轮推理不执行，回调空结果清空候选栏
        if (suppressNextPrediction) {
            suppressNextPrediction = false
            onPredictionResult(emptyList())
            return
        }

        if (contextText.isEmpty()) {
            onPredictionResult(emptyList())
            return
        }
        
        if (!SettingsPreferences.isSmartPredictionEnabled(context)) {
            onPredictionResult(emptyList())
            return
        }
        
        val epoch = requestEpoch
        serviceScope.launch {
            try {
                if (!AssociationManager.isInitialized()) {
                    val initSuccess = withContext(Dispatchers.IO) {
                        AssociationManager.initialize(context)
                    }
                    if (!initSuccess) {
                        Log.e(TAG, "Failed to initialize AssociationManager")
                        withContext(Dispatchers.Main) {
                            if (epoch == requestEpoch) {
                                onPredictionResult(emptyList())
                            }
                        }
                        return@launch
                    }
                }
                
                val candidates = AssociationManager.predict(contextText, MAX_ASSOCIATION_COUNT)

                withContext(Dispatchers.Main) {
                    // 代际过期说明上下文已被退格/清空修改，丢弃过期结果避免候选栏闪动
                    if (epoch == requestEpoch) {
                        onPredictionResult(candidates.map { it.text })
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Prediction failed", e)
                withContext(Dispatchers.Main) {
                    if (epoch == requestEpoch) {
                        onPredictionResult(emptyList())
                    }
                }
            }
        }
    }
    
    fun recordInput(text: String) {
        if (SettingsPreferences.isSmartPredictionEnabled(context) && AssociationManager.isInitialized()) {
            AssociationManager.recordInput(text)
        }
    }
    
    fun recordInputPair(lastChar: String, candidate: String) {
        if (SettingsPreferences.isSmartPredictionEnabled(context) && AssociationManager.isInitialized()) {
            AssociationManager.recordInput(lastChar + candidate)
        }
    }
    
    suspend fun getEnglishAssociations(text: String, limit: Int = MAX_ASSOCIATION_COUNT): List<String> {
        return try {
            AssociationService.getAssociations(context, text, true, limit)
        } catch (e: Exception) {
            Log.e(TAG, "English association failed", e)
            emptyList()
        }
    }
    
    suspend fun getChineseAssociations(text: String, limit: Int = MAX_ASSOCIATION_COUNT): List<String> {
        return try {
            if (!AssociationManager.isInitialized()) {
                AssociationManager.initialize(context)
            }
            
            val candidates = AssociationManager.predict(text, limit)
            candidates.map { it.text }
        } catch (e: Exception) {
            Log.e(TAG, "Chinese association failed", e)
            emptyList()
        }
    }
}
