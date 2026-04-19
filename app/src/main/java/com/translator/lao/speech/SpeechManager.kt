package com.translator.lao.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 语音管理器
 *
 * - 语音识别：系统 SpeechRecognizer（国内手机可能不可用）
 * - 语音合成：MiMo TTS API（无限制，支持中老双语）
 */
class SpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechManager"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val miMoTts = MiMoTtsManager(context)

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

    // ========== 语音合成（MiMo TTS） ==========

    interface TtsCallback {
        fun onComplete()
        fun onError(error: String)
    }

    /** TTS 始终可用 */
    fun isTtsAvailable(): Boolean = miMoTts.isAvailable()

    /** 兼容旧接口 */
    fun initTts(onReady: ((Boolean) -> Unit)? = null) {
        onReady?.invoke(true)
    }

    fun getTtsStatus(): String = "TTS 已就绪"

    /**
     * 语音播报（suspend，需要在 lifecycleScope.launch 中调用）
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
        miMoTts.setLanguage(isLao)

        miMoTts.speak(text, callback = object : MiMoTtsManager.TtsCallback {
            override fun onComplete() = callback?.onComplete() ?: Unit
            override fun onError(error: String) = callback?.onError(error) ?: Unit
        })
    }

    /** 停止播报 */
    fun stopSpeaking() {
        miMoTts.stop()
    }

    fun release() {
        stopListening()
        miMoTts.release()
    }
}
