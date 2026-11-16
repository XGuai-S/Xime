package com.kingzcheung.xime.ai.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kingzcheung.xime.R
import com.kingzcheung.xime.ai.XimeAiApp
import com.kingzcheung.xime.ai.audio.WavAudioRecorder
import com.kingzcheung.xime.ai.data.HistoryRecord
import com.kingzcheung.xime.ai.data.PipelineMode
import com.kingzcheung.xime.ai.data.VoiceIntentMode
import com.kingzcheung.xime.ai.ime.ImeLogic
import com.kingzcheung.xime.ai.network.AiPipelineEngine
import com.kingzcheung.xime.ai.network.PipelineStage
import com.kingzcheung.xime.ai.network.asr.StreamingAsrSession
import com.kingzcheung.xime.ai.ui.components.TypeFreeIcons
import com.kingzcheung.xime.ai.ui.theme.TypeFreeEmerald
import com.kingzcheung.xime.ai.ui.theme.TypeFreeRose
import com.kingzcheung.xime.ai.ui.theme.TypeFreeTheme
import com.kingzcheung.xime.ai.ui.theme.resolveDarkTheme
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

sealed interface BubblePhase {
    data object Idle : BubblePhase
    data object Recording : BubblePhase
    data class Processing(val label: String) : BubblePhase
    data class Feedback(val message: String, val isError: Boolean = false) : BubblePhase
}

class XimeBubbleService :
    Service(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val repository get() = XimeAiApp.repository
    private val aiEngine get() = XimeAiApp.aiEngine

    private lateinit var windowManager: WindowManager
    private lateinit var audioRecorder: WavAudioRecorder
    private var overlayView: ComposeView? = null
    private lateinit var windowParams: WindowManager.LayoutParams

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pipelineJob: Job? = null
    private var activeStreamingSession: StreamingAsrSession? = null
    private var feedbackResetJob: Job? = null

    private var isForeground = false

    private val _phase = MutableStateFlow<BubblePhase>(BubblePhase.Idle)
    val phase = _phase.asStateFlow()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 胶囊服务可能被独立拉起（无障碍/开机），先确保 AI 模块就绪再访问 repository/aiEngine
        XimeAiApp.ensureInit(applicationContext)
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioRecorder = WavAudioRecorder(this)
        ensureNotificationChannel()

        // startForegroundService() 启动后必须在 5 秒内调用 startForeground()，
        // 否则系统会抛 ForegroundServiceDidNotStartInTimeException 并结束进程
        // （2026-11-15 23:18:09 的崩溃就是这个：onCreate 早退没进前台）。
        // 所以这里第一件事就是进前台，任何终止分支都改由 stopSelf() 收尾。
        if (!enterForeground()) {
            stopSelf()
            return
        }

        if (!XimeBubbleController.hasOverlayPermission(this)) {
            stopSelf()
            return
        }

        attachOverlayWindow()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        serviceScope.launch {
            runCatching {
                val snap = repository.settings.value
                aiEngine.prewarmLiveStreamingIfNeeded(snap)
                aiEngine.prewarmEndpoints(snap)
            }
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        XimeBubbleController.setBubbleServiceRunning(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 配置未开启或没有悬浮窗权限：立刻退出（此时已是前台服务，stopSelf 会一并清通知）
        if (!XimeBubbleController.hasOverlayPermission(this) || !repository.settings.value.bubbleEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        XimeBubbleController.setBubbleServiceRunning(false)
        activeStreamingSession?.cancel()
        activeStreamingSession = null
        pipelineJob?.cancel()
        feedbackResetJob?.cancel()
        if (::audioRecorder.isInitialized) {
            serviceScope.launch { audioRecorder.cancelRecording() }
        }
        detachOverlayWindow()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun attachOverlayWindow() {
        if (overlayView != null) return
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val settings = repository.settings.value
        val edgeMarginPx = (10 * metrics.density).roundToInt()
        val bubbleWidthPx = (62 * metrics.density).roundToInt()

        val initialX = if (settings.bubbleDockLeft) {
            edgeMarginPx
        } else {
            (screenWidth - bubbleWidthPx - edgeMarginPx).coerceAtLeast(0)
        }
        val initialY = (screenHeight * settings.bubbleYRatio)
            .roundToInt()
            .coerceIn((screenHeight * 0.08f).roundToInt(), (screenHeight * 0.85f).roundToInt())

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@XimeBubbleService)
            setViewTreeViewModelStoreOwner(this@XimeBubbleService)
            setViewTreeSavedStateRegistryOwner(this@XimeBubbleService)
            setContent {
                val currSettings by repository.settings.collectAsState()
                val imeVisible by repository.isImeWindowVisible.collectAsState()
                val focusedSnap by XimeBubbleController.focusedInput.collectAsState()
                val a11yConnected by XimeBubbleController.isAccessibilityConnected.collectAsState()
                val forcePreview by XimeBubbleController.forcePreviewVisible.collectAsState()
                val currPhase by phase.collectAsState()
                val amplitudes by audioRecorder.amplitudes.collectAsState()
                val elapsedMs by audioRecorder.elapsedMs.collectAsState()

                // 目标可见性：只在「无障碍焦点/密码判定」抖动时不要立即反应，
                // 否则每次 hasEditableFocus 抖一下就让整窗 GONE/VISIBLE 来回切 → 视觉闪烁。
                // 用 debounce 做缓冲：状态持续稳定 ~180ms 才真正切换窗口可见性。
                var shouldShow by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    snapshotFlow {
                        !imeVisible &&
                            !focusedSnap.isSensitivePassword &&
                            // 常驻：不再依赖"当前有可编辑焦点"——否则在桌面/视频/阅读等
                            // 没有输入框的应用里胶囊会消失、用不了。只要开启+无障碍已连、
                            // 非密码框就显示（点它录音，插入走无障碍节点/剪贴板兜底）。
                            (currPhase != BubblePhase.Idle ||
                                forcePreview ||
                                !a11yConnected ||
                                true)
                    }
                        .debounce(180L)
                        .collect { show ->
                            shouldShow = show
                            // 用 alpha 控制而不是 GONE/VISIBLE：避免反复触发重新布局/重新挂载造成闪烁
                            this@apply.alpha = if (show) 1f else 0f
                            this@apply.isClickable = show
                        }
                }

                if (shouldShow) {
                    TypeFreeTheme(themePreference = currSettings.themePreference) {
                        val isDark = resolveDarkTheme(currSettings.themePreference)
                        BubbleOverlayWidget(
                            isDark = isDark,
                            phase = currPhase,
                            hasSelectedText = focusedSnap.selectedText.isNotBlank(),
                            amplitudes = amplitudes,
                            elapsedMs = elapsedMs,
                            onTapPrimary = ::handleBubbleTap,
                            onCancel = ::cancelBubbleAction,
                            onDragDelta = ::moveWindowBy,
                            onDragEnd = ::snapWindowToEdge
                        )
                    }
                }
            }
        }

        try {
            windowManager.addView(view, windowParams)
            overlayView = view
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun detachOverlayWindow() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun moveWindowBy(dx: Float, dy: Float) {
        val view = overlayView ?: return
        val metrics = resources.displayMetrics
        windowParams.x = (windowParams.x + dx.roundToInt())
            .coerceIn(0, (metrics.widthPixels - view.width.coerceAtLeast(100)).coerceAtLeast(0))
        windowParams.y = (windowParams.y + dy.roundToInt())
            .coerceIn(
                (metrics.heightPixels * 0.05f).roundToInt(),
                (metrics.heightPixels * 0.88f).roundToInt()
            )
        try {
            windowManager.updateViewLayout(view, windowParams)
        } catch (_: Exception) {
        }
    }

    private fun snapWindowToEdge() {
        val view = overlayView ?: return
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val edgeMarginPx = (10 * metrics.density).roundToInt()
        val viewW = view.width.takeIf { it > 0 } ?: (58 * metrics.density).roundToInt()

        val centerX = windowParams.x + viewW / 2
        val dockLeft = centerX < screenWidth / 2
        windowParams.x = if (dockLeft) {
            edgeMarginPx
        } else {
            (screenWidth - viewW - edgeMarginPx).coerceAtLeast(0)
        }
        try {
            windowManager.updateViewLayout(view, windowParams)
        } catch (_: Exception) {
        }
        val yRatio = (windowParams.y.toFloat() / screenHeight.coerceAtLeast(1)).coerceIn(0.08f, 0.88f)
        repository.updateSettings {
            it.copy(bubbleDockLeft = dockLeft, bubbleYRatio = yRatio)
        }
    }

    private fun handleBubbleTap() {
        when (_phase.value) {
            is BubblePhase.Idle, is BubblePhase.Feedback -> startBubbleRecording()
            is BubblePhase.Recording -> finishBubbleRecordingAndInject()
            is BubblePhase.Processing -> Unit
        }
    }

    private fun startBubbleRecording() {
        feedbackResetJob?.cancel()
        if (!audioRecorder.hasRecordPermission()) {
            vibrate(strong = true)
            showTemporaryFeedback("请先授予麦克风权限", isError = true)
            return
        }

        // Refresh accessibility focus snapshot right before recording
        XimeAccessibilityService.activeInstance?.refreshFocusedInputState()
        promoteToMicrophoneForeground()

        activeStreamingSession?.cancel()
        activeStreamingSession = null
        val settingsSnap = repository.settings.value
        // 与工作台一致：开启"本地识别"时用本地 Zipformer 流式，否则云端流式
        val streamSession: com.kingzcheung.xime.ai.network.asr.StreamingAsrSession? =
            if (settingsSnap.workbenchLocalSttEnabled) {
                val mm = com.kingzcheung.xime.speech.AsrModelManager(this)
                if (mm.isModelReady()) {
                    FileLogger.d("XimeBubbleService", "capsule: using LOCAL streaming")
                    com.kingzcheung.xime.ai.network.asr.LocalZipformerStreamingSession(this)
                        .also { runCatching { it.prepareInBackground() } }
                } else {
                    FileLogger.d("XimeBubbleService", "capsule: local model not ready, fallback cloud")
                    aiEngine.openLiveStreamingSession(settingsSnap)
                }
            } else {
                aiEngine.openLiveStreamingSession(settingsSnap)
            }

        val res = audioRecorder.startRecording(
            scope = serviceScope,
            onStreamChunk = if (streamSession != null) {
                { pcmChunk -> streamSession.sendPcmFrame(pcmChunk) }
            } else {
                null
            },
            pauseMediaPlayback = settingsSnap.pauseMediaWhileRecording,
            whisperMode = settingsSnap.whisperModeEnabled
        )
        res.fold(
            onSuccess = {
                activeStreamingSession = streamSession
                serviceScope.launch {
                    aiEngine.prewarmEndpoints(settingsSnap)
                }
                FileLogger.d("XimeBubbleService", "capsule: recording started (stream=${streamSession != null})")
                vibrate(strong = true)
                _phase.value = BubblePhase.Recording
            },
            onFailure = { err ->
                FileLogger.e("XimeBubbleService", "capsule record start failed: ${err.message}")
                streamSession?.cancel()
                demoteFromForeground()
                vibrate(strong = true)
                showTemporaryFeedback(err.message ?: "无法开始录音", isError = true)
            }
        )
    }

    private fun finishBubbleRecordingAndInject() {
        if (_phase.value !is BubblePhase.Recording) return
        val liveSession = activeStreamingSession
        activeStreamingSession = null
        vibrate(strong = true)
        pipelineJob?.cancel()
        pipelineJob = serviceScope.launch {
            val stopRes = audioRecorder.stopRecording()
            demoteFromForeground()
            stopRes.fold(
                onSuccess = { recorded ->
                    val settings = repository.settings.value
                    var preTranscribed: String? = null
                    var tailWaitMs = 0L

                    if (liveSession != null) {
                        _phase.value = BubblePhase.Processing("收尾中…")
                        val tailStart = System.currentTimeMillis()
                        val streamRes = liveSession.finishAndAwaitFinal()
                        tailWaitMs = (System.currentTimeMillis() - tailStart).coerceAtLeast(0L)

                        val streamErr = streamRes.exceptionOrNull()
                        if (streamErr is IllegalStateException) {
                            vibrate(strong = true)
                            showTemporaryFeedback((streamErr.message ?: "API 未配置").take(18), isError = true)
                            return@launch
                        }
                        val candidate = streamRes.getOrNull()?.trim().orEmpty()
                        if (candidate.isNotEmpty() && !AiPipelineEngine.isHallucinatedSilence(candidate)) {
                            preTranscribed = candidate
                        }
                    }

                    if (preTranscribed == null && !recorded.hasValidSpeech) {
                        liveSession?.cancel()
                        vibrate(strong = true)
                        showTemporaryFeedback("未检测到说话声", isError = true)
                        return@launch
                    }

                    val liveSnap = XimeAccessibilityService.activeInstance?.refreshFocusedInputState()
                        ?: XimeBubbleController.focusedInput.value
                    val pkg = liveSnap.packageName
                    val appName = repository.resolveAppName(pkg)
                    val (activeStyle, _) = repository.resolveActiveStyle(pkg, liveSnap.inputType)
                    val selectedText = liveSnap.selectedText
                    val intentMode = if (selectedText.isNotBlank()) {
                        VoiceIntentMode.SPEAK_TO_EDIT
                    } else {
                        VoiceIntentMode.DICTATE
                    }

                    _phase.value = BubblePhase.Processing(
                        when {
                            settings.pipelineMode == PipelineMode.MULTIMODAL_DIRECT -> "直出中…"
                            preTranscribed != null -> if (selectedText.isNotBlank()) "改写中…" else "润色中…"
                            else -> "识别中…"
                        }
                    )

                    FileLogger.d("XimeBubbleService", "capsule: stop ok dur=${recorded.durationMs} speech=${recorded.hasValidSpeech} pre=${preTranscribed?.length ?: 0}")
                    val result = aiEngine.processVoiceAudio(
                        wavFile = recorded.wavFile,
                        settings = settings,
                        activeStyle = activeStyle,
                        targetAppName = appName,
                        selectedText = selectedText,
                        surroundingBeforeCursor = liveSnap.surroundingBeforeCursor,
                        intentMode = intentMode,
                        preTranscribedText = preTranscribed,
                        sttTailWaitMs = tailWaitMs,
                        onStageChanged = { stage ->
                            _phase.value = BubblePhase.Processing(
                                when (stage) {
                                    PipelineStage.TRANSCRIBING_STT -> "识别中…"
                                    PipelineStage.POLISHING_LLM -> if (selectedText.isNotBlank()) "改写中…" else "润色中…"
                                    PipelineStage.MULTIMODAL_PROCESSING -> "生成中…"
                                }
                            )
                        }
                    )

                    result.fold(
                        onSuccess = { exec ->
                            val finalOut = exec.finalOutput
                            val a11y = XimeAccessibilityService.activeInstance
                            FileLogger.d("XimeBubbleService", "capsule: pipeline ok outLen=${finalOut.length} a11y=${a11y != null}")
                            val inserted = a11y?.insertPolishedText(finalOut) ?: false
                            FileLogger.d("XimeBubbleService", "capsule: insert result=$inserted")
                            if (!inserted) {
                                copyToClipboard(finalOut)
                            }
                            val isIncognito = settings.manualIncognitoMode ||
                                liveSnap.isSensitivePassword ||
                                ImeLogic.isIncognitoImeOptions(
                                    imeOptions = 0,
                                    inputType = liveSnap.inputType,
                                    manualIncognitoMode = settings.manualIncognitoMode
                                )
                            if (isIncognito) {
                                runCatching { recorded.wavFile.delete() }
                            } else {
                                val archivedWav = WavAudioRecorder.archiveWavForHistory(
                                    baseDir = filesDir,
                                    wavFile = recorded.wavFile,
                                    retention = settings.historyRetention
                                )
                                repository.addHistory(
                                    HistoryRecord(
                                        targetAppName = if (appName == "未知应用") "悬浮胶囊" else appName,
                                        targetPackage = pkg,
                                        styleName = activeStyle.name,
                                        isSpeakToEdit = selectedText.isNotBlank(),
                                        intentMode = intentMode,
                                        originalSelectedText = selectedText,
                                        rawTranscript = exec.rawTranscript,
                                        polishedText = finalOut,
                                        durationMs = recorded.durationMs,
                                        latencyMs = exec.latencyMs,
                                        modeLabel = "胶囊 · ${exec.modeSummary}",
                                        audioFilePath = archivedWav.absolutePath
                                    )
                                )
                            }
                            vibrate(strong = false)
                            runCatching { aiEngine.prewarmLiveStreamingIfNeeded(settings) }
                            showTemporaryFeedback(
                                if (inserted) "✓ 已上屏" else "✓ 已复制剪贴板",
                                isError = false
                            )
                        },
                        onFailure = { err ->
                            if (err is CancellationException) return@launch
                            FileLogger.e("XimeBubbleService", "capsule: pipeline failed: ${err.message}", err)
                            vibrate(strong = true)
                            showTemporaryFeedback(
                                (err.message ?: "处理失败").take(18),
                                isError = true
                            )
                        }
                    )
                },
                onFailure = { err ->
                    liveSession?.cancel()
                    showTemporaryFeedback((err.message ?: "录音太短").take(16), isError = true)
                }
            )
        }
    }

    private fun cancelBubbleAction() {
        vibrate(strong = false)
        activeStreamingSession?.cancel()
        activeStreamingSession = null
        pipelineJob?.cancel()
        feedbackResetJob?.cancel()
        serviceScope.launch { audioRecorder.cancelRecording() }
        demoteFromForeground()
        _phase.value = BubblePhase.Idle
    }

    private fun showTemporaryFeedback(message: String, isError: Boolean) {
        feedbackResetJob?.cancel()
        _phase.value = BubblePhase.Feedback(message, isError)
        feedbackResetJob = serviceScope.launch {
            delay(1800L)
            if (_phase.value is BubblePhase.Feedback) {
                _phase.value = BubblePhase.Idle
            }
        }
    }

    private fun buildStatusNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("TypeFree 悬浮语音胶囊")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setOngoing(true)
            .build()

    /**
     * 进入前台服务并保持常驻。
     *
     * 胶囊需要长期存活，不能靠 stopForeground 退出前台（Android 12+ 后台服务容易被回收），
     * 所以「录音中」与「待命」只是换通知文案，服务的性质不变。
     *
     * @return true 表示已成功成为前台服务
     */
    private fun enterForeground(): Boolean {
        val notification = buildStatusNotification("悬浮语音胶囊待命")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            return true
        } catch (t: Throwable) {
            // 麦克风类型不被允许（例如权限刚被撤销）时退化为普通前台服务，
            // 关键是不能让 startForeground 没被调用，否则系统直接杀进程。
            val fallback = runCatching { startForeground(NOTIFICATION_ID, notification) }
            if (fallback.isFailure) {
                FileLogger.e("XimeBubbleService", "enterForeground failed", t)
            }
            return fallback.isSuccess
        }
    }

    private fun promoteToMicrophoneForeground() {
        if (!isForeground) isForeground = enterForeground()
        runCatching {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.notify(NOTIFICATION_ID, buildStatusNotification("正在聆听语音输入…"))
        }
    }

    private fun demoteFromForeground() {
        // 只把通知恢复成待命状态，服务保持前台（胶囊常驻依赖这一点）
        runCatching {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.notify(NOTIFICATION_ID, buildStatusNotification("悬浮语音胶囊待命"))
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TypeFree 语音录音服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保障悬浮胶囊在跨应用时稳定录音"
                setShowBadge(false)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("TypeFree", text))
        } catch (_: Exception) {
        }
    }

    private fun vibrate(strong: Boolean) {
        if (!repository.settings.value.hapticFeedback) return
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(
                VibrationEffect.createOneShot(
                    if (strong) 22L else 10L,
                    if (strong) 170 else 90
                )
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "xime_ai_bubble_mic"
        private const val NOTIFICATION_ID = 2100

        /** 启动悬浮胶囊（需已授予 SYSTEM_ALERT_WINDOW 与麦克风权限）。 */
        fun start(context: android.content.Context) {
            runCatching {
                val i = android.content.Intent(context, XimeBubbleService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }.onFailure {
                FileLogger.e("XimeBubbleService", "start failed", it)
            }
        }

        /** 停止悬浮胶囊。 */
        fun stop(context: android.content.Context) {
            runCatching { context.stopService(android.content.Intent(context, XimeBubbleService::class.java)) }
        }
    }
}

@Composable
private fun BubbleOverlayWidget(
    isDark: Boolean,
    phase: BubblePhase,
    hasSelectedText: Boolean,
    amplitudes: List<Float>,
    elapsedMs: Long,
    onTapPrimary: () -> Unit,
    onCancel: () -> Unit,
    onDragDelta: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    // Authentic `bubble_hero_light.png` / `bubble_hero_dark.png` colors:
    // Light mode -> Pure charcoal (#141417) circle + white waveform + translucent light outer halo
    // Dark mode  -> Crisp off-white (#F4F4F6) circle + dark waveform + translucent dark outer halo
    val primaryFill = if (isDark) Color(0xFFF4F4F6) else Color(0xFF141417)
    val contentFg = if (isDark) Color(0xFF141417) else Color(0xFFFFFFFF)
    val haloRing = if (isDark) Color(0x38FFFFFF) else Color(0x22000000)

    Box(
        modifier = Modifier
            .padding(4.dp)
            .pointerInput(Unit) {
                // 自定义拖动：不用 detectDragGestures（其 awaitTouchSlopOrCancellation 会被
                // 内部 clickable 消费的 down 取消，导致拖不动）。这里忽略消费状态，只在
                // 越过 touchSlop 后接管为拖动；不越 slop 则不处理，交给内部 clickable 当点击。
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragging = false
                    var slopPassed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.position - change.previousPosition
                        if (!slopPassed &&
                            (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        ) {
                            slopPassed = true
                        }
                        if (slopPassed) {
                            dragging = true
                            change.consume()
                            onDragDelta(delta.x, delta.y)
                        }
                    }
                    if (dragging) onDragEnd()
                }
            }
    ) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(120)) },
            label = "BubblePhaseAnim"
        ) { targetPhase ->
            when (targetPhase) {
                is BubblePhase.Idle -> {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(haloRing)
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .shadow(6.dp, CircleShape)
                                .clip(CircleShape)
                                .background(primaryFill)
                                .clickable { onTapPrimary() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.VoiceWaveform,
                                contentDescription = "TypeFree 悬浮语音",
                                tint = contentFg,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        if (hasSelectedText) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(2.dp)
                                    .size(11.dp)
                                    .clip(CircleShape)
                                    .background(TypeFreeEmerald)
                                    .border(1.5.dp, Color.White, CircleShape)
                            )
                        }
                    }
                }

                is BubblePhase.Recording -> {
                    Row(
                        modifier = Modifier
                            .height(48.dp)
                            .shadow(8.dp, RoundedCornerShape(24.dp))
                            .clip(RoundedCornerShape(24.dp))
                            .background(primaryFill)
                            .clickable { onTapPrimary() }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Mini live symmetrical 5-bar waveform
                        val sample = amplitudes.takeLast(5).let {
                            if (it.size < 5) List(5 - it.size) { 0.25f } + it else it
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.5.dp)
                        ) {
                            sample.forEachIndexed { idx, amp ->
                                val baseFactor = if (idx == 2) 1.0f else if (idx == 1 || idx == 3) 0.75f else 0.5f
                                val barH = (6f + (amp.coerceIn(0.08f, 1f) * 16f * baseFactor)).dp
                                Box(
                                    modifier = Modifier
                                        .width(2.5.dp)
                                        .height(barH)
                                        .clip(CircleShape)
                                        .background(contentFg)
                                )
                            }
                        }

                        Text(
                            text = ImeLogic.formatElapsedTimer(elapsedMs),
                            color = contentFg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(contentFg.copy(alpha = 0.16f))
                                .clickable { onCancel() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = TypeFreeIcons.Close,
                                contentDescription = "取消",
                                tint = contentFg,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }

                is BubblePhase.Processing -> {
                    Row(
                        modifier = Modifier
                            .height(44.dp)
                            .shadow(6.dp, RoundedCornerShape(22.dp))
                            .clip(RoundedCornerShape(22.dp))
                            .background(primaryFill)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = contentFg
                        )
                        Text(
                            text = targetPhase.label,
                            color = contentFg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                is BubblePhase.Feedback -> {
                    Row(
                        modifier = Modifier
                            .height(42.dp)
                            .shadow(6.dp, RoundedCornerShape(21.dp))
                            .clip(RoundedCornerShape(21.dp))
                            .background(if (targetPhase.isError) TypeFreeRose else primaryFill)
                            .clickable { onTapPrimary() }
                            .padding(horizontal = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = targetPhase.message,
                            color = if (targetPhase.isError) Color.White else contentFg,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                }
            }
        }
    }
}
