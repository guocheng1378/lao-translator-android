package com.translator.lao.speech

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 语音管理器
 *
 * - 语音识别：系统 SpeechRecognizer（国内手机可能不可用）
 * - 语音合成：优先使用 Edge TTS（高质量），不可用时自动 fallback 到系统 TTS
 */
class SpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechManager"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    val edgeTts = MiMoTtsManager(context)

    // 系统 TTS fallback
    private var systemTts: TextToSpeech? = null
    private var systemTtsReady = false

    // 防止重复播放
    @Volatile
    private var isSpeakingNow = false

    // 缓存语音识别可用性检查结果
    private var recognitionChecked = false
    private var recognitionCached = false

    // ========== 语音识别 ==========

    interface RecognitionCallback {
        fun onResult(text: String)
        fun onError(error: String)
        fun onBeginOfSpeech()
        fun onEndOfSpeech()
    }

    fun isRecognitionAvailable(): Boolean {
        if (recognitionChecked) return recognitionCached
        recognitionChecked = true

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.d(TAG, "SpeechRecognizer: system reports unavailable")
            recognitionCached = false
            return false
        }
        var recognizer: SpeechRecognizer? = null
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            Log.w(TAG, "SpeechRecognizer creation failed: ${e.message}")
        }
        recognitionCached = recognizer != null
        recognizer?.destroy()
        if (!recognitionCached) {
            Log.w(TAG, "SpeechRecognizer: system says available but createSpeechRecognizer() returned null")
        }
        return recognitionCached
    }

    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun getRecognitionUnavailableReason(): String {
        if (!hasRecordAudioPermission()) {
            return "未授予麦克风权限\n\n请在手机设置中开启麦克风权限后重试\n\n💡 也可以使用手机键盘自带的 🎤 语音输入"
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return "当前设备不支持语音识别\n\n可能原因：\n1. 未安装 Google 语音服务（国内手机常见）\n2. 系统语音服务被禁用\n\n💡 建议：使用手机键盘自带的 🎤 语音输入按钮"
        }
        var canCreate = false
        try {
            val r = SpeechRecognizer.createSpeechRecognizer(context)
            canCreate = (r != null)
            r?.destroy()
        } catch (_: Exception) {}
        if (!canCreate) {
            return "语音识别服务不可用\n\n原因：设备未安装 Google 语音服务（Speech Services）\n这在国内手机上非常常见\n\n💡 解决方法：\n1. 打开输入框，使用键盘上的 🎤 语音按钮（推荐）\n2. 安装 Google App（应用商店搜索）\n3. 安装后需科学上网才能使用"
        }
        return "语音识别可用"
    }

    fun startListening(locale: Locale = Locale.CHINESE, callback: RecognitionCallback) {
        stopListening()

        if (!hasRecordAudioPermission()) {
            callback.onError("未授予麦克风权限\n\n💡 建议：使用手机键盘自带的 🎤 语音输入")
            return
        }
        if (!isRecognitionAvailable()) {
            callback.onError(getRecognitionUnavailableReason())
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
            callback.onError("创建语音识别器失败：设备缺少语音识别服务\n\n💡 建议：使用手机键盘自带的 🎤 语音输入")
            return
        }

        if (speechRecognizer == null) {
            callback.onError("无法创建语音识别器：设备缺少语音识别服务\n\n💡 建议：使用手机键盘自带的 🎤 语音输入")
            return
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { callback.onBeginOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { callback.onEndOfSpeech() }

            override fun onError(error: Int) {
                Log.e(TAG, "SpeechRecognizer error: $error")
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "录音错误（麦克风被其他应用占用）"
                    SpeechRecognizer.ERROR_CLIENT -> "语音识别服务异常\n\n可能原因：未安装 Google 语音服务（国内手机常见）\n\n💡 建议：使用手机键盘自带的 🎤 语音输入"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "语音识别服务异常\n\n即使已授予权限，设备缺少语音服务也会报此错误\n\n💡 建议：使用手机键盘自带的 🎤 语音输入"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误（语音识别需要联网）"
                    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音，请大声一点再说一次"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌，请稍后重试"
                    SpeechRecognizer.ERROR_SERVER -> "服务器错误（语音服务不可用）\n\n💡 建议：使用手机键盘自带的 🎤 语音输入"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时，请重新说话"
                    else -> "识别失败 (错误码: $error)\n\n💡 建议：使用手机键盘自带的 🎤 语音输入"
                }
                callback.onError(msg)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    callback.onResult(matches[0])
                } else {
                    callback.onError("未识别到内容")
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            callback.onError("启动语音识别失败：${e.message}")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    // ========== 语音合成 ==========

    interface TtsCallback {
        fun onComplete()
        fun onError(error: String)
    }

    fun isTtsAvailable(): Boolean = true

    fun isEdgeTtsAvailable(): Boolean = edgeTts.isAvailable()

    fun isSpeaking(): Boolean = isSpeakingNow

    /**
     * 初始化 TTS
     * 始终初始化系统 TTS 作为 fallback
     */
    fun initTts(onReady: ((Boolean) -> Unit)? = null) {
        initSystemTts { sysReady ->
            onReady?.invoke(sysReady || edgeTts.isAvailable())
        }
    }

    private fun initSystemTts(onReady: ((Boolean) -> Unit)? = null) {
        try {
            systemTts = TextToSpeech(context) { status ->
                systemTtsReady = (status == TextToSpeech.SUCCESS)
                Log.d(TAG, "System TTS init: status=$status, ready=$systemTtsReady")
                onReady?.invoke(systemTtsReady)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init system TTS", e)
            systemTtsReady = false
            onReady?.invoke(false)
        }
    }

    fun getTtsStatus(): String {
        val edge = if (edgeTts.isAvailable()) "Edge TTS ✅" else "Edge TTS ❌ (Tailscale 不可达)"
        val sys = if (systemTtsReady) "系统 TTS ✅" else "系统 TTS ❌"
        return "$edge\n$sys"
    }

    suspend fun speak(
        text: String,
        locale: Locale = Locale.CHINESE,
        callback: TtsCallback? = null
    ) = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            withContext(Dispatchers.Main) { callback?.onError("文字为空") }
            return@withContext
        }

        // 防抖：正在播放时先停止
        if (isSpeakingNow) {
            stopSpeaking()
        }
        isSpeakingNow = true

        val isLao = locale.language == "lo"
        edgeTts.setLanguage(isLao)

        val wrappedCallback = object : TtsCallback {
            override fun onComplete() {
                isSpeakingNow = false
                callback?.onComplete()
            }
            override fun onError(error: String) {
                isSpeakingNow = false
                callback?.onError(error)
            }
        }

        if (edgeTts.isAvailable()) {
            Log.d(TAG, "Using Edge TTS for speech")
            edgeTts.speak(text, callback = object : MiMoTtsManager.TtsCallback {
                override fun onComplete() { wrappedCallback.onComplete() }
                override fun onError(error: String) {
                    Log.w(TAG, "Edge TTS failed, trying system TTS: $error")
                    speakWithSystemTts(text, locale, wrappedCallback)
                }
            })
        } else {
            Log.d(TAG, "Edge TTS unavailable, using system TTS")
            speakWithSystemTts(text, locale, wrappedCallback)
        }
    }

    private fun speakWithSystemTts(
        text: String,
        locale: Locale,
        callback: TtsCallback?
    ) {
        if (!systemTtsReady || systemTts == null) {
            isSpeakingNow = false
            callback?.onError("语音播报不可用\n\n系统 TTS 未就绪，请检查设备 TTS 设置")
            return
        }

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                systemTts?.language = locale
                systemTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        isSpeakingNow = false
                        callback?.onComplete()
                    }
                    override fun onError(utteranceId: String?) {
                        isSpeakingNow = false
                        callback?.onError("系统 TTS 播报出错")
                    }
                })
                systemTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
            } catch (e: Exception) {
                Log.e(TAG, "System TTS error", e)
                isSpeakingNow = false
                callback?.onError("系统 TTS 出错：${e.message}")
            }
        }
    }

    fun stopSpeaking() {
        isSpeakingNow = false
        edgeTts.stop()
        try { systemTts?.stop() } catch (_: Exception) {}
    }

    fun release() {
        stopListening()
        edgeTts.release()
        try { systemTts?.shutdown() } catch (_: Exception) {}
        systemTts = null
        systemTtsReady = false
    }
}
