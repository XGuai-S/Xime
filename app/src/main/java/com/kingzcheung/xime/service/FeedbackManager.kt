package com.kingzcheung.xime.service

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import com.kingzcheung.xime.R
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

enum class HapticMode(val value: String) {
    FollowingSystem("following_system"),
    Enabled("enabled"),
    Disabled("disabled");

    companion object {
        fun fromValue(value: String): HapticMode =
            entries.find { it.value == value } ?: FollowingSystem
    }
}

class FeedbackManager(private val context: Context) {

    private companion object {
        /** 「跟随系统」模式下未自定义时长时的默认短震时长（毫秒）。 */
        const val DEFAULT_SYSTEM_HAPTIC_MS = 20L
    }

    /** 诊断计数器：每 20 次按键打一条，用于排查「设置了却没震感」。 */
    private var hapticLogCounter = 0

    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<String, Int>()

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private var hasAmplitudeControl = false

    private val feedbackScope = CoroutineScope(Dispatchers.Default)

    private var soundEnabled = true
    private var soundVolume = 50
    private var hapticMode = HapticMode.FollowingSystem
    private var hapticOnKeyUp = false
    private var pressDuration = 0L
    private var longPressDuration = 0L
    private var pressAmplitude = 0
    private var longPressAmplitude = 0

    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    // 注意：这里**不要**缓存 Settings.System 的触感反馈开关。
    // 曾试过「初始化读一次 + ContentObserver 刷新」，真机反馈震动直接消失
    // （各 ROM 该键的语义/时序不一致），已回滚为每次现读。
    // 性能上只保留「抬起键先返回」，省掉一半查询。

    fun initialize() {
        hasAmplitudeControl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()
        initSoundPool()
        loadSettings()
        registerPrefsListener()
    }

    fun release() {
        prefsListener?.let {
            SettingsPreferences.getPrefsPublic(context).unregisterOnSharedPreferenceChangeListener(it)
        }
        soundPool?.release()
        soundPool = null
        feedbackScope.cancel()
    }

    private fun initSoundPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()

        soundPool?.let { pool ->
            soundIds["standard"] = pool.load(context, R.raw.kb_key_standard, 1)
            soundIds["delete"] = pool.load(context, R.raw.kb_key_delete, 1)
            soundIds["space"] = pool.load(context, R.raw.kb_key_space, 1)
            soundIds["enter"] = pool.load(context, R.raw.kb_key_enter, 1)
        }
    }

    private fun loadSettings() {
        soundEnabled = SettingsPreferences.isSoundEnabled(context)
        soundVolume = SettingsPreferences.getSoundVolume(context)
        hapticMode = HapticMode.fromValue(SettingsPreferences.getHapticMode(context))
        hapticOnKeyUp = SettingsPreferences.isHapticOnKeyUp(context)
        pressDuration = SettingsPreferences.getVibrationPressDuration(context).toLong()
        longPressDuration = SettingsPreferences.getVibrationLongPressDuration(context).toLong()
        pressAmplitude = SettingsPreferences.getVibrationPressAmplitude(context)
        longPressAmplitude = SettingsPreferences.getVibrationLongPressAmplitude(context)
    }

    /**
     * 按模式选择振动属性。
     *
     * 真机日志实证：系统「触感反馈」关闭时，Xime 以默认属性（usage=TOUCH）发起的振动
     * 会被 VibratorManagerService 判为 `IGNORED_FOR_SETTINGS` 直接丢弃——所以「启用」
     * 模式看起来也和「跟随系统」一样不震。
     *
     * 「启用」= 不受系统开关影响，因此改用 USAGE_MEDIA（媒体振动，不受触感设置约束）；
     * 「跟随系统」保持默认 TOUCH 属性，由系统按触感开关决定震不震。
     */
    private fun vibrateCompat(effect: VibrationEffect) {
        if (hapticMode == HapticMode.Enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                vibrator.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA),
                )
            }.onSuccess { return }
        }
        vibrator.vibrate(effect)
    }

    private fun registerPrefsListener() {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "sound_enabled" -> soundEnabled = SettingsPreferences.isSoundEnabled(context)
                "sound_volume" -> soundVolume = SettingsPreferences.getSoundVolume(context)
                "haptic_mode" -> hapticMode = HapticMode.fromValue(SettingsPreferences.getHapticMode(context))
                "haptic_on_keyup" -> hapticOnKeyUp = SettingsPreferences.isHapticOnKeyUp(context)
                "vibration_press_duration" -> pressDuration = SettingsPreferences.getVibrationPressDuration(context).toLong()
                "vibration_long_press_duration" -> longPressDuration = SettingsPreferences.getVibrationLongPressDuration(context).toLong()
                "vibration_press_amplitude" -> pressAmplitude = SettingsPreferences.getVibrationPressAmplitude(context)
                "vibration_long_press_amplitude" -> longPressAmplitude = SettingsPreferences.getVibrationLongPressAmplitude(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun playKeySound(keyType: String = "standard") {
        if (!soundEnabled) return

        val pool = soundPool ?: return
        val soundId = soundIds[keyType] ?: soundIds["standard"] ?: return
        val volume = soundVolume / 100f

        pool.play(soundId, volume, volume, 1, 0, 1.0f)
    }

    fun hapticFeedback(view: View, longPress: Boolean = false, keyUp: Boolean = false) {
        if (hapticLogCounter++ % 20 == 0) {
            FileLogger.i(
                "FeedbackManager",
                "haptic: mode=$hapticMode keyUp=$keyUp hapticOnKeyUp=$hapticOnKeyUp" +
                    " dur=$pressDuration amp=$pressAmplitude ampControl=$hasAmplitudeControl",
            )
        }
        // 抬起键不震动时先返回：原来这一步在查系统设置之后，等于白付一次跨进程查询
        if (keyUp && !hapticOnKeyUp) return
        when (hapticMode) {
            HapticMode.Enabled -> {}
            HapticMode.Disabled -> return
            HapticMode.FollowingSystem -> {
                val systemEnabled = try {
                    Settings.System.getInt(
                        context.contentResolver,
                        Settings.System.HAPTIC_FEEDBACK_ENABLED,
                        1
                    ) == 1
                } catch (_: Exception) {
                    true
                }
                if (!systemEnabled) return
            }
        }

        val duration = if (longPress) longPressDuration else pressDuration
        val amplitude = if (longPress) longPressAmplitude else pressAmplitude
        if (duration != 0L) {
            if (hasAmplitudeControl && amplitude != 0) {
                vibrateCompat(VibrationEffect.createOneShot(duration, amplitude))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrateCompat(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } else {
            // 用户没自定义震动时长：一律用 vibrator 的短震。
            //
            // 原来这里走 View.performHapticFeedback（启用模式还带 IGNORE_GLOBAL_SETTING），
            // 但部分 ROM（MIUI 等）对输入法窗口不执行这条路径，结果就是：
            // 「启用」模式也必须系统触感开着才有震感，和「跟随系统」没有区别。
            // 三种模式的语义在这里已经分清：
            //   关闭     → 上面直接 return
            //   跟随系统 → 系统开关关着时上面已 return
            //   启用     → 不受系统开关影响，直接震
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrateCompat(VibrationEffect.createOneShot(DEFAULT_SYSTEM_HAPTIC_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(DEFAULT_SYSTEM_HAPTIC_MS)
            }
        }
    }

    fun performKeyPressEffect(keyType: String = "standard", view: View) {
        playKeySound(keyType)
        hapticFeedback(view)
    }

    fun performKeyPressDownEffect(key: String, view: View) {
        val keyType = when (key) {
            "delete", "clear_composition" -> "delete"
            "enter", "newline" -> "enter"
            "space" -> "space"
            else -> "standard"
        }
        playKeySound(keyType)
        hapticFeedback(view)
    }

    fun performVibration() {
        if (hapticMode == HapticMode.Disabled) return
        if (!vibrator.hasVibrator()) return
        val duration = if (pressDuration != 0L) pressDuration else 20L
        val amplitude = if (hasAmplitudeControl && pressAmplitude != 0) pressAmplitude else VibrationEffect.DEFAULT_AMPLITUDE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }
}
