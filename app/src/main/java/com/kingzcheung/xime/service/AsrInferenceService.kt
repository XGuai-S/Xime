package com.kingzcheung.xime.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.kingzcheung.xime.speech.AsrModelManager
import com.kingzcheung.xime.speech.AsrNative
import com.kingzcheung.xime.util.FileLogger
import java.io.File

/**
 * 离线语音识别服务，运行在 :asr 独立进程。
 *
 * 模型加载与推理全部在此进程完成，输入法主进程仅负责音频采集与结果回调，
 * 不占用输入法进程内存。
 *
 * 模型空闲释放：语音会话结束后一段时间无新会话，自动释放模型句柄
 * （[AsrNative.nativeRelease]），只保留 :asr 进程与 ONNX 运行库，避免
 * 154MB 模型长期常驻推高功耗。下次 [startAsr] 时再按需加载（加载已提速）。
 */
class AsrInferenceService : Service() {

    companion object {
        private const val TAG = "AsrInferenceService"

        /** 语音会话结束后，多久无新会话即释放模型。 */
        private const val IDLE_RELEASE_DELAY_MS = 60_000L

        /**
         * 是否需要重建模型句柄：尚未加载，或已加载句柄对应的模型目录与请求不一致
         * （用户切换了模型）。忽略目录变化会继续用旧模型识别。
         */
        internal fun shouldReloadHandle(handle: Long, loadedDir: String?, requestedDir: String): Boolean =
            handle == 0L || loadedDir != requestedDir
    }

    private var asrHandle: Long = 0L
    private var asrCallback: IInferenceAsrCallback? = null
    private val asrLock = Any()

    /** 当前句柄对应的模型目录。切换模型后目录变化，据此重建句柄。 */
    private var loadedModelDir: String? = null
    // "保持引擎常驻"：开启时跳过空闲自动释放，模型常驻内存换响应速度
    @Volatile
    private var keepModelAlive = false

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleReleaseRunnable = Runnable {
        synchronized(asrLock) {
            if (asrHandle != 0L) {
                AsrNative.nativeRelease(asrHandle)
                asrHandle = 0L
                asrCallback = null
                loadedModelDir = null
                FileLogger.i(TAG, "ASR model released after idle timeout")
            }
        }
    }

    private fun scheduleIdleRelease() {
        if (keepModelAlive) return
        idleHandler.removeCallbacks(idleReleaseRunnable)
        idleHandler.postDelayed(idleReleaseRunnable, IDLE_RELEASE_DELAY_MS)
    }

    private fun cancelIdleRelease() {
        idleHandler.removeCallbacks(idleReleaseRunnable)
    }

    private val binder = object : IInferenceAsrService.Stub() {

        override fun startAsr(modelDir: String, callback: IInferenceAsrCallback): Boolean {
            cancelIdleRelease()
            // 已加载句柄对应的是旧模型目录（用户切换了模型）时必须先卸载重建，
            // 否则会复用旧模型继续识别（文件名相同的模型曾完全无感）
            val needReload = synchronized(asrLock) {
                shouldReloadHandle(asrHandle, loadedModelDir, modelDir)
            }
            if (needReload) {
                synchronized(asrLock) {
                    if (asrHandle != 0L) {
                        AsrNative.nativeRelease(asrHandle)
                        asrHandle = 0L
                        FileLogger.i(TAG, "ASR model dir changed, released old handle")
                    }
                    loadedModelDir = null
                }
                // 模型加载耗时较长，放在 asrLock 外执行，避免阻塞其它 ASR 控制操作
                val handle = createAsrHandle(modelDir)
                if (handle == 0L) {
                    Log.e(TAG, "Failed to create ASR recognizer from $modelDir")
                    return false
                }
                synchronized(asrLock) {
                    if (asrHandle == 0L) {
                        asrHandle = handle
                        loadedModelDir = modelDir
                        FileLogger.i(TAG, "ASR recognizer created, handle=$handle, modelDir=$modelDir")
                    } else {
                        AsrNative.nativeRelease(handle)
                    }
                }
            }
            return try {
                synchronized(asrLock) {
                    asrCallback = callback
                    AsrNative.nativeReset(asrHandle)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "startAsr failed", e)
                false
            }
        }

        override fun pushAsrAudio(audioData: ByteArray) {
            val partial: String
            val cb: IInferenceAsrCallback?
            synchronized(asrLock) {
                if (asrHandle == 0L) return
                AsrNative.nativeAcceptPcm(asrHandle, audioData)
                partial = AsrNative.nativeGetPartial(asrHandle)
                cb = asrCallback
            }
            if (cb != null && partial.isNotEmpty()) {
                try {
                    cb.onPartialResult(partial)
                } catch (_: Exception) {
                }
            }
        }

        override fun stopAsr(): String {
            val text = try {
                synchronized(asrLock) {
                    if (asrHandle == 0L) ""
                    else AsrNative.nativeFinalize(asrHandle)
                }
            } catch (e: Exception) {
                Log.e(TAG, "stopAsr failed", e)
                ""
            }
            scheduleIdleRelease()
            synchronized(asrLock) { asrCallback = null }
            return text
        }

        override fun cancelAsr() {
            synchronized(asrLock) {
                if (asrHandle != 0L) AsrNative.nativeReset(asrHandle)
                asrCallback = null
            }
            scheduleIdleRelease()
        }

        override fun releaseAsr() {
            cancelIdleRelease()
            synchronized(asrLock) {
                if (asrHandle != 0L) {
                    AsrNative.nativeRelease(asrHandle)
                    asrHandle = 0L
                }
                asrCallback = null
                loadedModelDir = null
            }
        }

        override fun setKeepModelAlive(keepAlive: Boolean) {
            keepModelAlive = keepAlive
            if (keepAlive) {
                // 取消可能已排期的空闲释放
                cancelIdleRelease()
            } else if (synchronized(asrLock) { asrHandle } != 0L) {
                // 从常驻切回默认：模型仍在内存，恢复空闲回收
                scheduleIdleRelease()
            }
            FileLogger.i(TAG, "setKeepModelAlive: $keepAlive")
        }
    }

    /**
     * 按模型目录名（即模型 id）解析文件清单并创建识别器（在 :asr 进程加载模型）。
     * 不用 [AsrModelManager.getSelectedModelInfo]：跨进程读偏好可能滞后于刚切换的模型。
     */
    private fun createAsrHandle(modelDir: String): Long {
        return try {
            val dir = File(modelDir)
            val info = AsrModelManager(this).getModelInfo(dir.name)
            val encoder = File(dir, info.encoderFile)
            val decoder = File(dir, info.decoderFile)
            val joiner = File(dir, info.joinerFile)
            val tokens = File(dir, "tokens.txt")
            if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
                Log.e(TAG, "ASR model files incomplete in $modelDir")
                return 0L
            }
            AsrNative.nativeCreate(
                encoder.absolutePath,
                decoder.absolutePath,
                joiner.absolutePath,
                tokens.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "createAsrHandle failed", e)
            0L
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        cancelIdleRelease()
        synchronized(asrLock) {
            if (asrHandle != 0L) {
                AsrNative.nativeRelease(asrHandle)
                asrHandle = 0L
            }
            asrCallback = null
            loadedModelDir = null
        }
        super.onDestroy()
    }
}
