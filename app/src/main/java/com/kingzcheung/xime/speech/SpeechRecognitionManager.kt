package com.kingzcheung.xime.speech

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log

import androidx.annotation.RequiresPermission
import com.kingzcheung.xime.plugin.ActivePluginSelection
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.util.MicCaptureGate

class SpeechRecognitionManager(
    private val context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val backendFactory: (() -> AsrBackend?)? = null,
) {

    companion object {
        private const val TAG = "SpeechRecognitionManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_SECONDS = 0.1f
        private const val SPEECH_THRESHOLD = 25

        /** 停止时等待录音线程退出的超时：覆盖 backend.stop() 同步等待最终结果的耗时。 */
        private const val JOIN_TIMEOUT_MS = 5000L

        /** 麦克风闸门持有者名（见 [MicCaptureGate]），同时用于给用户提示「被谁占用」。 */
        internal const val MIC_OWNER = "键盘语音"

        /**
         * 停止后延迟释放后端的窗口：在线插件 stop() 只发送结束信号，最终结果由
         * 服务端处理完尾点后经 WebSocket 异步回调（1~2s），需比
         * VoiceRecognitionHandler.FINISH_TIMEOUT_MS 更长，保证回调通道存活。
         */
        private const val BACKEND_RELEASE_DELAY_MS = 3500L
    }

    private var backend: AsrBackend? = null
    private var recordingThread: RecordingThread? = null

    // 会话序号：用于区分连续语音会话，防止旧会话的回收线程误释放新会话的后端
    private var sessionId = 0

    /**
     * 当前活动会话号（0 = 没有活动会话）。
     *
     * 后端回调本身不带来源信息，只能按「回调到达时哪个会话是活动的」判定：
     * 只接受来自当前活动会话的结果/错误，旧会话的迟到包一律丢弃。
     */
    @Volatile
    private var activeSession = 0

    /**
     * 当前会话是否已开始向后端送入音频。
     *
     * 用来关掉「取消 A → 立刻开始 B → A 的最终包到达」那个窗口：
     * 后端回调不带来源会话号，B 尚未送音频时的最终包只可能来自 A。
     */
    @Volatile
    private var audioFlowing = false
    // 待执行的延迟释放所对应的会话序号（-1 表示无待释放）
    @Volatile
    private var pendingReleaseSession = -1
    private val pendingBackendRelease = Runnable {
        val session = pendingReleaseSession
        pendingReleaseSession = -1
        // 主线程只取引用；release() 含跨进程 IPC/断连，放后台线程执行
        val b = synchronized(preloadLock) {
            if (session != -1 && sessionId == session) {
                val tmp = backend
                backend = null
                tmp
            } else null
        }
        if (b != null) {
            Thread { b.release() }.start()
        }
    }
    // 后台加载 ASR 模型的进行中标记与取消标记
    @Volatile
    private var loadingInProgress = false
    @Volatile
    private var loadingCancelled = false

    private var resultCallback: ((String) -> Unit)? = null
    private var partialResultCallback: ((String) -> Unit)? = null
    private var stateCallback: ((RecognitionState) -> Unit)? = null
    @Volatile
    private var currentState: RecognitionState = RecognitionState.IDLE
    private var errorCallback: ((String, Boolean) -> Unit)? = null
    private var amplitudeCallback: ((Float) -> Unit)? = null
    private var spectrumCallback: ((FloatArray) -> Unit)? = null

    // 预启动的 AudioRecord：手指按下 150ms 后启动，语音激活时直接交给录音线程
    private var preStartedRecord: AudioRecord? = null
    private val preStartTimeoutRunnable = Runnable { cancelPreStart() }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecognition() {
        // 加载期间的重复请求由下方 loadingInProgress 合并，不在 UI 线程等待模型。
        
        if (recordingThread != null) {
            FileLogger.w(TAG, "Recognition already running, ignoring start request")
            return
        }

        FileLogger.i(TAG, "Starting speech recognition")
        setState(RecognitionState.PROCESSING)

        val loadEpoch = synchronized(preloadLock) {
            if (released) return
            if (loadingInProgress) {
                loadingCancelled = false
                return
            }
            if (backend != null) null else {
                loadingInProgress = true
                loadingCancelled = false
                lifecycleEpoch
            }
        }
        if (loadEpoch != null) {
            Thread {
                val ok = preload()
                // Ownership ends in the callback, so stop/cancel can still revoke queued starts.
                val posted = mainHandler.post {
                    synchronized(preloadLock) {
                        if (released || lifecycleEpoch != loadEpoch) return@post
                        loadingInProgress = false
                        if (loadingCancelled) {
                            setState(RecognitionState.IDLE)
                        } else if (!ok || backend == null) {
                            errorCallback?.invoke("无法初始化语音引擎，请检查本地模型或在线语音插件配置", true)
                            setState(RecognitionState.ERROR)
                        } else if (recordingThread == null) {
                            startRecording()
                        }
                    }
                }
                if (!posted) synchronized(preloadLock) {
                    if (lifecycleEpoch == loadEpoch) loadingInProgress = false
                }
            }.start()
            return
        }

        startRecording()
    }

    private fun startRecording() {
        val currentBackend = synchronized(preloadLock) { backend } ?: return
        val session = synchronized(preloadLock) { ++sessionId }
        // 新会话开始：解除「取消后抑制结果」（见 cancelRecognition / handleResult）
        suppressResults = false
        // 登记活动会话；audioFlowing 待本会话真正送出音频后才放开
        activeSession = session
        audioFlowing = false

        // 新会话复用后端：取消上一次会话遗留的延迟释放
        mainHandler.removeCallbacks(pendingBackendRelease)
        pendingReleaseSession = -1

        // 预启动的 AudioRecord 已运行 ~250ms，直接交给录音线程
        var preStarted: AudioRecord? = null
        synchronized(this) {
            preStarted = preStartedRecord
            preStartedRecord = null
        }
        mainHandler.removeCallbacks(preStartTimeoutRunnable)

        val thread = RecordingThread(currentBackend, preStarted, session)
        recordingThread = thread
        thread.start()
    }

    /**
     * 延迟释放后端：给在线插件的异步最终结果留出送达窗口。窗口内新会话开始
     * （startRecording）会取消释放并复用后端；释放前校验会话序号，避免误释放
     * 新会话正在使用的后端。
     * "引擎常驻"开启时不安排释放——后端跨会话保留，闲置后再用免重建
     * （重新加载模型/重建 Lua 后端与连接正是"闲置后首次使用慢"的根源）；
     * 关闭设置后自然回退为延迟释放，无需主动清理。
     */
    private fun scheduleBackendRelease(session: Int) {
        if (SettingsPreferences.isSttKeepEngineAlive(context)) return
        pendingReleaseSession = session
        mainHandler.removeCallbacks(pendingBackendRelease)
        mainHandler.postDelayed(pendingBackendRelease, BACKEND_RELEASE_DELAY_MS)
    }

    fun stopRecognition() {
        Log.d(TAG, "Stopping recognition")
        val thread = recordingThread
        if (thread == null) {
            // 模型仍在后台加载中：标记取消，加载完成后不再启动录音
            if (loadingInProgress) {
                loadingCancelled = true
                mainHandler.post {
                    setState(RecognitionState.IDLE)
                }
            }
            return
        }
        recordingThread = null
        thread.stopRequested = true
        // 会话结束：不再接受任何结果（stop 的最终包由 stopRecognition 之后的
        // 「延迟释放窗口」内存留的后端送达——此窗口内 activeSession 保持，见 scheduleBackendRelease 注释）
        val session = synchronized(preloadLock) { sessionId }
        // 不能 interrupt：录音线程可能正阻塞在 backend.stop() 同步等待最终结果
        // （本地 stopAsr 的 runBlocking），中断会吞掉最终文本；forceStopAudio 释放
        // AudioRecord 使 read() 返回错误、循环自然退出后执行 stop()
        thread.forceStopAudio()
        Thread {
            try {
                // join 超时兜底：录音线程若卡在 Lua 调用中（最坏 CALL_TIMEOUT_MS=180s），
                // 不能让后端释放无限期阻塞（否则 WebSocket 与麦克风一直占着）
                thread.join(JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                // 卡死兜底：标记中断，尽快从可中断调用中退出
                thread.interrupt()
            }
            scheduleBackendRelease(session)
            mainHandler.post {
                setState(RecognitionState.IDLE)
            }
        }.start()
    }

    fun cancelRecognition() {
        Log.d(TAG, "Canceling recognition")
        // 取消 = 用户不要这段结果：此后到达的最终包一律丢弃（见 handleResult）。
        suppressResults = true
        activeSession = 0
        audioFlowing = false
        val thread = recordingThread
        if (thread == null) {
            // 模型仍在后台加载中：标记取消，加载完成后不再启动录音
            if (loadingInProgress) {
                loadingCancelled = true
                mainHandler.post {
                    setState(RecognitionState.IDLE)
                }
            }
            return
        }
        recordingThread = null
        thread.stopRequested = true
        // 会话结束：不再接受任何结果（stop 的最终包由 stopRecognition 之后的
        // 「延迟释放窗口」内存留的后端送达——此窗口内 activeSession 保持，见 scheduleBackendRelease 注释）
        val session = synchronized(preloadLock) { sessionId }
        // 同 stopRecognition：不打断 backend.stop()，避免破坏引擎收尾状态
        thread.forceStopAudio()
        Thread {
            try {
                thread.join(JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                thread.interrupt()
            }
            scheduleBackendRelease(session)
            mainHandler.post {
                setState(RecognitionState.IDLE)
            }
        }.start()
    }

    fun getState(): RecognitionState = currentState

    private fun setState(state: RecognitionState) {
        currentState = state
        stateCallback?.invoke(state)
    }

    fun setCallbacks(
        onResult: (String) -> Unit,
        onPartialResult: ((String) -> Unit)? = null,
        onStateChange: (RecognitionState) -> Unit,
        onError: (message: String, userVisible: Boolean) -> Unit,
        onAmplitude: ((Float) -> Unit)? = null,
        onSpectrum: ((FloatArray) -> Unit)? = null
    ) {
        resultCallback = onResult
        partialResultCallback = onPartialResult
        stateCallback = onStateChange
        errorCallback = onError
        amplitudeCallback = onAmplitude
        spectrumCallback = onSpectrum
    }

    fun startPreStart() {
        cancelPreStart()
        // 先抢麦克风闸门再建设备：抢不到就干脆不建 AudioRecord。
        // 否则会和悬浮胶囊同时持有 AudioRecord，两路都 startRecording 成功但只有一路能读到数据，
        // 最终两边的用户都看到「一直在转圈、识别不出字」。
        if (!MicCaptureGate.tryAcquire(MIC_OWNER)) {
            FileLogger.w(TAG, "预启动跳过：麦克风被 ${MicCaptureGate.currentHolder()} 占用")
            return
        }
        val record = createAudioRecord()
        if (record == null) {
            MicCaptureGate.release(MIC_OWNER)
            return
        }
        record.startRecording()
        synchronized(this) {
            preStartedRecord = record
        }
        mainHandler.removeCallbacks(preStartTimeoutRunnable)
        mainHandler.postDelayed(preStartTimeoutRunnable, 2000)
    }

    fun cancelPreStart() {
        mainHandler.removeCallbacks(preStartTimeoutRunnable)
        synchronized(this) {
            val record = preStartedRecord
            preStartedRecord = null
            if (record != null) {
                try { record.stop() } catch (_: Exception) { }
                record.release()
            }
        }
        // 录音线程用同一个持有者名：录音进行中调用这里不能放开闸门，否则胶囊会插进来抢设备。
        if (recordingThread == null) {
            MicCaptureGate.release(MIC_OWNER)
        }
    }

    fun release() {
        Log.d(TAG, "Releasing speech recognition")
        synchronized(preloadLock) {
            released = true
            lifecycleEpoch++
            loadingCancelled = true
            loadingInProgress = false
        }
        cancelPreStart()
        mainHandler.removeCallbacks(pendingBackendRelease)
        pendingReleaseSession = -1
        cancelRecognition()
        val b = synchronized(preloadLock) {
            val tmp = backend
            backend = null
            tmp
        }
        if (b != null) {
            // release() 含跨进程 IPC，放到后台线程执行，避免阻塞主线程（onDestroy 等场景）
            Thread {
                b.release()
            }.start()
        }
    }

    private val preloadLock = Object()

    private val initializationLock = Any()
    private var lifecycleEpoch = 0L
    private var released = false

    fun preload(): Boolean = synchronized(initializationLock) {
        val epoch = synchronized(preloadLock) {
            if (released) return false
            if (backend != null) return true
            lifecycleEpoch
        }
        var candidate: AsrBackend? = null
        var published = false
        try {
            val newBackend = createBackend() ?: return false
            candidate = newBackend
            newBackend.setCallbacks(
                onResult = { text -> handleResult(text) },
                onPartialResult = { text -> handlePartialResult(text) },
                onStateChange = { state -> setState(state) },
                onError = { error -> handleError(error) }
            )
            if (!newBackend.initialize()) return false
            synchronized(preloadLock) {
                if (released || lifecycleEpoch != epoch) return false
                backend = newBackend
                published = true
            }
            true
        } catch (t: Throwable) {
            FileLogger.w(TAG, "STT 预加载失败: ${t.javaClass.simpleName}: ${t.message}")
            false
        } finally {
            if (!published) runCatching { candidate?.release() }
        }
    }

    private fun createBackend(): AsrBackend? {
        backendFactory?.let { return it() }
        // 用户开启"本地识别"时才使用离线后端，否则走在线插件（严格互斥，不自动切换）
        val useLocal = SettingsPreferences.isSttUseLocal(context)
        return if (useLocal) {
            AsrBackendFactory.create(context) ?: createOnlineAsrBackend()
        } else {
            createOnlineAsrBackend()
        }
    }

    private fun createOnlineAsrBackend(): AsrBackend? {
        val enabledPlugins = ExtensionManager.getEnabledAsrPlugins(context)
        if (enabledPlugins.isEmpty()) return null

        val selectedId = SettingsPreferences.getSttOnlinePluginId(context)
        // 与插件管理页共用同一判定规则（ActivePluginSelection）：偏好不可用时回退首个已启用插件，
        // 并回填偏好，避免"识别在跑、插件页显示未使用"
        val resolvedId = ActivePluginSelection.resolve(selectedId, enabledPlugins.map { it.first })
        if (resolvedId != selectedId) SettingsPreferences.setSttOnlinePluginId(context, resolvedId)
        val selected = enabledPlugins.firstOrNull { it.first == resolvedId }
            ?: enabledPlugins.firstOrNull()
        val (_, plugin) = selected ?: return null

        val backend = plugin.createBackend(context.applicationContext)
        val pluginName = ExtensionManager.getAllInstalledPlugins()
            .firstOrNull { it.id == selected.first }?.name ?: selected.first
        return PluginAsrBackendAdapter(pluginName, backend)
    }

    private fun createAudioRecord(bufferSecs: Float = 2.0f): AudioRecord? {
        val bufferSize = (SAMPLE_RATE * bufferSecs).toInt()
        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                null
            } else {
                record
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            null
        }
    }

    private inner class RecordingThread(
        private val currentBackend: AsrBackend,
        private val preStarted: AudioRecord? = null,
        private val session: Int = 0,
    ) : Thread("AsrRecording") {

        private val spectrumAnalyzer = SpectrumAnalyzer()

        /**
         * 停止请求标志：不能只依赖线程中断标志停止循环。
         *
         * processAudioChunk 会经 JsScriptRuntime.runGuarded 的 FutureTask.get 执行，
         * FutureTask.awaitDone 内部用 Thread.interrupted() 检查中断状态并**清除中断标志**，
         * 随后 JsScriptRuntime.call 吞掉 InterruptedException 正常返回 NIL——
         * 于是中断标志被消费后 while (!interrupted()) 永远为真，录音线程无法停止，
         * stopRecognition 的 join() 永不返回，后端（WebSocket）与麦克风一直后台占用。
         */
        @Volatile
        var stopRequested = false

        @Volatile
        private var audioRecord: AudioRecord? = null

        override fun run() {
            // 抢麦克风闸门（预启动路径已持有，这里是刷新租约）。
            // 抢不到就直接失败并告诉用户被谁占用——比录出一段静音再让用户困惑好得多。
            if (!MicCaptureGate.tryAcquire(MIC_OWNER)) {
                val holder = MicCaptureGate.currentHolder()
                preStarted?.let { stale ->
                    try { stale.stop() } catch (_: Exception) { }
                    try { stale.release() } catch (_: Exception) { }
                }
                mainHandler.post {
                    errorCallback?.invoke(
                        "麦克风正被「${holder ?: "其他功能"}」使用，请先结束那一边",
                        true
                    )
                    setState(RecognitionState.ERROR)
                }
                return
            }

            val audioRecord = preStarted ?: (createAudioRecord() ?: run {
                MicCaptureGate.release(MIC_OWNER)
                mainHandler.post {
                    errorCallback?.invoke("无法启动录音", false)
                    setState(RecognitionState.ERROR)
                }
                return
            })
            this.audioRecord = audioRecord

            if (!currentBackend.start()) {
                audioRecord.stop()
                audioRecord.release()
                MicCaptureGate.release(MIC_OWNER)
                mainHandler.post {
                    errorCallback?.invoke("启动引擎失败", false)
                    setState(RecognitionState.ERROR)
                }
                return
            }

            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.startRecording()
            }
            mainHandler.post {
                setState(RecognitionState.LISTENING)
            }

            val buffer = ShortArray((SAMPLE_RATE * BUFFER_SIZE_SECONDS).toInt())
            val byteBuffer = ByteArray(buffer.size * 2)
            var speechDetected = false
            // 语音前缓冲：保存检测到语音前的若干块，检测到后一起送入 ASR，
            // 避免"你/觉"等弱开头的语音块因音量低于阈值被当作静音丢弃
            val preSpeechBuffer = ArrayDeque<ByteArray>()
            val maxPreSpeechChunks = 4  // 0.4s 语音前缓冲

            try {
                while (!interrupted() && !stopRequested) {
                    val nread = audioRecord.read(buffer, 0, buffer.size)
                    if (nread == 0) {
                        // read() 立即返回 0（静音/设备切换瞬间）时旧实现直接进入下一轮，
                        // 变成紧循环：单核 100% 且不响应停止请求。与 WavAudioRecorder 对齐加让出。
                        try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    }
                    if (nread > 0) {
                        var peak = 0
                        for (i in 0 until nread) {
                            val s = buffer[i].toInt()
                            byteBuffer[i * 2] = (s and 0xFF).toByte()
                            byteBuffer[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                            val abs = if (s < 0) -s else s
                            if (abs > peak) peak = abs
                        }
                        // 归一化振幅（0~1）与频段频谱，驱动频谱可视化
                        val normalized = (peak / 32768f).coerceIn(0f, 1f)
                        val spectrum = spectrumAnalyzer.analyze(buffer, nread)
                        mainHandler.post {
                            amplitudeCallback?.invoke(normalized)
                            spectrumCallback?.invoke(spectrum)
                        }
                        val chunk = byteBuffer.copyOf(nread * 2)
                        if (!speechDetected) {
                            preSpeechBuffer.addLast(chunk)
                            // 缓冲满仍未检测到语音：放弃 VAD，直接开始识别，
                            // 保证整段弱音内容也能送入 ASR（开头静音已被缓冲丢弃）
                            if (preSpeechBuffer.size >= maxPreSpeechChunks) {
                                speechDetected = true
                                markAudioFlowing()
                                while (preSpeechBuffer.isNotEmpty()) {
                                    currentBackend.processAudioChunk(preSpeechBuffer.removeFirst())
                                }
                            } else if (isSpeech(chunk)) {
                                speechDetected = true
                                markAudioFlowing()
                                // 把语音前缓冲的块按顺序送入 ASR，保证开头不丢失
                                while (preSpeechBuffer.isNotEmpty()) {
                                    currentBackend.processAudioChunk(preSpeechBuffer.removeFirst())
                                }
                            }
                        } else {
                            markAudioFlowing()
                            currentBackend.processAudioChunk(chunk)
                        }
                    } else if (nread < 0) {
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "录音循环异常退出: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                // forceStopAudio 可能已从外部 stop/release，这里需容错（重复释放不抛异常中断后续流程）
                try { audioRecord.stop() } catch (_: Exception) { }
                try { audioRecord.release() } catch (_: Exception) { }
                // 设备真正放开后才释放闸门：先放闸门会让别的录音路径抢到还在占用的麦克风
                MicCaptureGate.release(MIC_OWNER)
            }

            // 只有本会话仍持有后端时才收尾：旧线程晚退出时若仍调 stop()，
            // 会把新会话正在用的引擎停掉（用户表现为「说着说着突然断了」）。
            if (ownsBackend()) {
                currentBackend.stop()
            } else {
                Log.d(TAG, "skip backend.stop(): session $session superseded")
            }
            finishRecording()
            Log.d(TAG, "Recognition thread ended")
        }

        /**
         * 录音循环结束后的统一收尾（M03）。
         *
         * 录音线程因 read() 返回负数或处理中抛异常自然退出时，旧实现不清
         * recordingThread，之后 startRecognition() 首行判定「已在录音」直接忽略，
         * 用户必须显式 stop/cancel 才能再录。这里按会话身份清槽位（只清自己的，
         * 避免误清新会话），并对非主动 stop/cancel 的异常退出报一次错误。
         */
        private fun finishRecording() {
            synchronized(preloadLock) {
                if (recordingThread === this) recordingThread = null
            }
            // 主动 stop/cancel 或被新会话取代：不打扰用户
            if (stopRequested || !ownsBackend()) return
            mainHandler.post {
                if (stopRequested || !ownsBackend()) return@post
                errorCallback?.invoke("语音识别已中断，请重新开始说话", true)
                setState(RecognitionState.ERROR)
            }
        }

        /** 本会话是否仍拥有当前后端（活动会话未被新会话取代）。 */
        private fun ownsBackend(): Boolean =
            session != 0 && synchronized(preloadLock) { session == sessionId }

        /** 本会话开始向后端送音频：只有仍持有后端时才放开结果门。 */
        private fun markAudioFlowing() {
            if (ownsBackend()) audioFlowing = true
        }

        /** 从外部强制停止录音：录音线程阻塞在 read() 等不可中断调用时，释放 AudioRecord 使其立即返回错误并退出。 */
        fun forceStopAudio() {
            audioRecord?.let { record ->
                try { record.stop() } catch (_: Exception) { }
                try { record.release() } catch (_: Exception) { }
            }
            // 设备已由本方法强行释放，闸门同步放开：否则录音线程若卡在 backend.stop() 里，
            // 麦克风闸门会被一直占着，胶囊那边直接报「被键盘语音占用」。
            MicCaptureGate.release(MIC_OWNER)
        }

        private fun isSpeech(chunk: ByteArray): Boolean {
            var peak = 0
            for (i in 0 until chunk.size / 2) {
                val low = chunk[i * 2].toInt() and 0xFF
                val high = chunk[i * 2 + 1].toInt()
                val sample = ((high shl 8) or low).toShort().toInt()
                val abs = kotlin.math.abs(sample)
                if (abs > peak) peak = abs
            }
            return peak > SPEECH_THRESHOLD
        }
    }

    /** 取消后抑制结果回调：cancel 之后后端仍可能吐出最终包（旧会话的尾巴）。 */
    @Volatile
    private var suppressResults = false

    /**
     * 后端最终结果入口。
     *
     * 后端回调不带来源会话号，只能按「回调到达时哪个会话是活动会话」判定：
     * 取消后（suppressResults）或旧会话的迟到包一律丢弃，避免 A 的尾包被当作 B 的结果上屏。
     */
    private fun handleResult(text: String) {
        if (suppressResults) {
            Log.d(TAG, "取消后到达的识别结果已丢弃 len=${text.length}")
            return
        }
        // 没有活动录音会话（已 stop/cancel）时到达的包一律丢弃
        if (activeSession == 0) {
            Log.d(TAG, "无活动会话的识别结果已丢弃 len=${text.length}")
            return
        }
        // 新会话尚未送入任何音频时的「最终包」只能是上一会话的尾巴（后端回调不带来源会话号，
        // 这是唯一可判定的窗口），丢弃以免被当成新会话结果上屏
        if (!audioFlowing) {
            Log.d(TAG, "新会话未开始送音频，丢弃迟到最终包 len=${text.length}")
            return
        }
        mainHandler.post {
            if (suppressResults || activeSession == 0 || !audioFlowing) return@post
            resultCallback?.invoke(text)
        }
    }

    private fun handlePartialResult(text: String) {
        mainHandler.post {
            if (text.isNotEmpty()) {
                partialResultCallback?.invoke(text)
            }
        }
    }

    private fun handleError(error: String) {
        Log.e(TAG, "Recognition error: $error")
        if (suppressResults || activeSession == 0) {
            Log.d(TAG, "无活动会话的错误已丢弃")
            return
        }
        mainHandler.post {
            if (suppressResults || activeSession == 0) return@post
            errorCallback?.invoke(error, true)
        }
    }
}
