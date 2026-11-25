package com.kingzcheung.xime.ai.network.asr

import android.content.Context
import com.kingzcheung.xime.service.AsrInferenceClient
import com.kingzcheung.xime.speech.AsrModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 本地流式 ASR 会话：把 :asr 进程里的 Zipformer int8 引擎包成 [StreamingAsrSession]。
 *
 * 用途：让 AI 工作台的「实时写入（Live Write）」在本地模型下也能边说话边出字，
 * 不依赖任何网络。partial 结果由 :asr 服务周期性回调（AsrInferenceClient 的
 * onPartialResult），final 结果在 [finishAndAwaitFinal] 时返回。
 *
 * 注意：与云端流式互斥使用；本地不可用（未下载模型/未绑定服务）时
 * [isAvailable] 返回 false，调用方应回退为"录音后整段识别"。
 */
class LocalZipformerStreamingSession(
    private val context: Context
) : StreamingAsrSession {

    private val client = AsrInferenceClient(context)
    private val _partialText = MutableStateFlow("")
    private val _finalText = MutableStateFlow("")

    override val partialText: StateFlow<String> = _partialText.asStateFlow()

    @Volatile private var started = false
    @Volatile private var finished = false

    private val callback = object : AsrInferenceClient.AsrCallback {
        override fun onPartialResult(text: String) {
            if (!finished && text.isNotBlank()) _partialText.value = text
        }

        override fun onFinalResult(text: String) {
            if (text.isNotBlank()) _finalText.value = text
        }

        override fun onError(message: String) {
            // 静默：失败由 finishAndAwaitFinal 的返回体现
        }
    }

    /** 本地模型与服务是否就绪。 */
    suspend fun prepare(): Boolean {
        return try {
            val mm = AsrModelManager(context)
            if (!mm.isModelReady()) return false
            val dir = mm.getSelectedModelDir()?.absolutePath ?: return false
            if (!client.ensureBound()) return false
            // cancel 可能发生在绑定等待期间（最长 5s）：那时它的 unbind 是空操作，
            // 这里不自行收尾，:asr 绑定就永远没人解。
            if (finished) {
                runCatching { client.unbind() }
                return false
            }
            if (!client.startAsr(dir, callback)) {
                runCatching { client.unbind() }
                return false
            }
            if (finished) {
                runCatching { client.cancelAsr() }
                runCatching { client.unbind() }
                return false
            }
            started = true
            true
        } catch (_: Throwable) {
            runCatching { client.unbind() }
            false
        }
    }

    /**
     * 异步准备：立即返回，在 IO 线程绑定服务并创建引擎。
     * sendPcmFrame 在 ready 之前会被丢弃，因此不会崩；调用方应先
     * warmup 再开始录音，以尽量减少开头的丢帧。
     */
    @Volatile private var prepareJob: kotlinx.coroutines.Job? = null

    fun prepareInBackground() {
        if (started || finished || prepareJob?.isActive == true) return
        prepareJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            prepare()
        }
    }

    override fun sendPcmFrame(pcm16Le: ByteArray) {
        if (!started || finished || pcm16Le.isEmpty()) return
        runCatching { client.pushAsrAudio(pcm16Le) }
    }

    override suspend fun finishAndAwaitFinal(timeoutMs: Long): Result<String> {
        if (!started) {
            // 准备仍在途：置 finished 让 prepare 自行收尾，并解掉可能已建立的绑定
            finished = true
            prepareJob?.cancel()
            runCatching { client.unbind() }
            return Result.failure(IOException("本地识别未启动"))
        }
        return try {
            val text = client.stopAsr()
            finished = true
            val clean = if (text.isNotBlank()) text else _partialText.value
            if (clean.isBlank()) Result.failure(IOException("未识别到语音内容"))
            else Result.success(clean)
        } catch (e: Throwable) {
            Result.failure(if (e is IOException) e else IOException(e.message ?: "本地识别失败", e))
        } finally {
            runCatching { client.unbind() }
        }
    }

    override fun cancel() {
        finished = true
        prepareJob?.cancel()
        runCatching { client.cancelAsr() }
        runCatching { client.unbind() }
    }
}
