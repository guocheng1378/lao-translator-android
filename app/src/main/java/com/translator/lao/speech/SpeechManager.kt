package com.translator.lao.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    private val edgeTts = MiMoTtsManager(context)

    // 系统 TTS fallback
    private var systemTts: TextToSpeech? = null
    private var systemTtsReady = false

    // ========== 语音识别（保持原有逻辑） ==========

    interface RecognitionCallback {
        fun onResult(text: String)
        fun onError(error: String)
        fun onBeginOfSpeech()
        fun onEndOfSpeech()
    }

    fun isRecognitionAvailable(): Boolean {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        Log.d(TAG, "SpeechRecognizer available: $available")
        return available
    }

    fun getRecognitionUnavailableReason(): String {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return "当前设备不支持语音识别\n\n可能原因：\n1. 未安装 Google 语音服务（国内手机常见）\n2. 未授予麦克风权限\n\n建议：使用手机键盘自带的🎤语音输入"
        }
        return "语音识别可用"
    }

    fun startListening(locale: Locale = Locale.CHINESE, callback: RecognitionCallback) {
        stopListening()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callback.onError(getRecognitionUnavailableReason())
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
            callback.onError("创建语音识别器失败：${e.message}\n\n建议：使用手机键盘自带的🎤语音输入")
            return
        }

        if (speechRecognizer == null) {
            callback.onError("无法创建语音识别器\n\n建议：使用手机键盘自带的🎤语音输入")
            return
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { callback.onBeginOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { callback.onEndOfSpeech() }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "录音错误（麦克风被占用）"
                    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "未授予麦克风权限，请在设置中开启"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误（语音识别需要联网）"
                    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音，请大声一点再说一次"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌，请稍后重试"
                    SpeechRecognizer.ERROR_SERVER -> "服务器错误（语音服务不可用）"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时，请重新说话"
                    else -> "识别失败 (错误码: $error)"
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

    // ========== 语音合成（Edge TTS + 系统 TTS fallback） ==========

    interface TtsCallback {
        fun onComplete()
        fun onError(error: String)
    }

    /**
     * TTS 是否可用（Edge TTS 或系统 TTS 至少一个可用）
     */
    fun isTtsAvailable(): Boolean {
        return edgeTts.isAvailable() || systemTtsReady
    }

    /**
     * Edge TTS 是否可用（用于 UI 展示更详细的状态）
     */
    fun isEdgeTtsAvailable(): Boolean = edgeTts.isAvailable()

    /**
     * 异步初始化系统 TTS 作为 fallback
     */
    fun initTts(onReady: ((Boolean) -> Unit)? = null) {
        // 先检测 Edge TTS
        val edgeAvailable = edgeTts.isAvailable()
        Log.d(TAG, "Edge TTS available: $edgeAvailable")

        if (edgeAvailable) {
            onReady?.invoke(true)
            // 后台初始化系统 TTS 作为备用
            initSystemTts()
            return
        }

        // Edge TTS 不可用，初始化系统 TTS
        initSystemTts(onReady)
    }

    private fun initSystemTts(onReady: ((Boolean) -> Unit)? = null) {
        try {
            systemTts = TextToSpeech(context) { status ->
                systemTtsReady = (status == TextToSpeech.SUCCESS)
                Log.d(TAG, "System TTS init: status=$status, ready=$systemTtsReady")
                onReady?.invoke(systemTtsReady || edgeTts.isAvailable())
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

    /**
     * 语音播报（suspend，需要在 lifecycleScope.launch 中调用）
     *
     * 优先使用 Edge TTS（高质量神经语音），失败时自动 fallback 到系统 TTS
     *
     * @param text 要播报的文字
     * @param locale 语言（自动判断中文/老挝语）
     */
    suspend fun speak(
        text: String,
        locale: Locale = Locale.CHINESE,
        callback: TtsCallback? = null
    ) = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            withContext(Dispatchers.Main) { callback?.onError("文字为空") }
            return@withContext
        }

        // 根据 locale 判断语言方向
        val isLao = locale.language == "lo"
        edgeTts.setLanguage(isLao)

        if (edgeTts.isAvailable()) {
            // 优先使用 Edge TTS
            Log.d(TAG, "Using Edge TTS for speech")
            edgeTts.speak(text, callback = object : MiMoTtsManager.TtsCallback {
                override fun onComplete() {
                    callback?.onComplete()
                }

                override fun onError(error: String) {
                    Log.w(TAG, "Edge TTS failed, trying system TTS: $error")
                    // Edge TTS 失败，fallback 到系统 TTS
                    speakWithSystemTts(text, locale, callback)
                }
            })
        } else {
            // Edge TTS 不可用，直接用系统 TTS
            Log.d(TAG, "Edge TTS unavailable, using system TTS")
            speakWithSystemTts(text, locale, callback)
        }
    }

    /**
     * 使用系统 TTS 播报
     */
    private fun speakWithSystemTts(
        text: String,
        locale: Locale,
        callback: TtsCallback?
    ) {
        if (!systemTtsReady || systemTts == null) {
            // 系统 TTS 也不可用
            val reason = if (!edgeTts.isAvailable()) {
                edgeTts.getUnreachableReason()
            } else {
                "语音播报不可用"
            }
            callback?.onError(reason)
            return
        }

        // 切到主线程调用系统 TTS
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                systemTts?.language = locale
                systemTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        callback?.onComplete()
                    }
                    override fun onError(utteranceId: String?) {
                        callback?.onError("系统 TTS 播报出错")
                    }
                })
                systemTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
            } catch (e: Exception) {
                Log.e(TAG, "System TTS error", e)
                callback?.onError("系统 TTS 出错：${e.message}")
            }
        }
    }

    /** 停止播报 */
    fun stopSpeaking() {
        edgeTts.stop()
        try {
            systemTts?.stop()
        } catch (_: Exception) {}
    }

    fun release() {
        stopListening()
        edgeTts.release()
        try {
            systemTts?.shutdown()
        } catch (_: Exception) {}
        systemTts = null
        systemTtsReady = false
    }
}
