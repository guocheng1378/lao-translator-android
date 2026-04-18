package com.translator.lao.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 语音管理器
 * - 支持小米/华为等国产手机（不依赖 Google 语音服务）
 * - SpeechRecognizer 不可用时提示用户用键盘语音
 * - TTS 不可用时提示用户复制文字
 */
class SpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechManager"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsInitialized = false
    private var ttsInitStatus = -99

    // ========== 语音识别 ==========

    interface RecognitionCallback {
        fun onResult(text: String)
        fun onError(error: String)
        fun onBeginOfSpeech()
        fun onEndOfSpeech()
    }

    /** 检查语音识别是否可用 */
    fun isRecognitionAvailable(): Boolean {
        val available = SpeechRecognizer.isRecognitionAvailable(context)
        Log.d(TAG, "SpeechRecognizer available: $available")
        return available
    }

    /** 获取不可用的原因 */
    fun getRecognitionUnavailableReason(): String {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return "当前设备不支持语音识别\n\n可能原因：\n1. 未安装 Google 语音服务（国内手机常见）\n2. 未授予麦克风权限\n\n建议：使用手机键盘自带的🎤语音输入"
        }
        return "语音识别可用"
    }

    fun startListening(locale: Locale = Locale.CHINESE, callback: RecognitionCallback) {
        stopListening()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            val reason = getRecognitionUnavailableReason()
            Log.w(TAG, "Recognition not available: $reason")
            callback.onError(reason)
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
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
            }
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech")
                callback.onBeginOfSpeech()
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
                callback.onEndOfSpeech()
            }

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
                Log.e(TAG, "Recognition error: $error - $msg")
                callback.onError(msg)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "onResults: $matches")
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
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        try {
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "startListening called with locale: ${locale.toLanguageTag()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            callback.onError("启动语音识别失败：${e.message}")
        }
    }

    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping listener", e)
        }
        speechRecognizer = null
    }

    // ========== 语音合成 ==========

    /** 检查 TTS 是否可用 */
    fun isTtsAvailable(): Boolean = ttsInitialized

    /** 获取 TTS 状态描述 */
    fun getTtsStatus(): String = when (ttsInitStatus) {
        TextToSpeech.SUCCESS -> "TTS 已就绪"
        TextToSpeech.ERROR -> "TTS 初始化失败"
        TextToSpeech.ERROR_INVALID_REQUEST -> "无效请求"
        TextToSpeech.ERROR_NETWORK -> "网络错误"
        TextToSpeech.ERROR_NETWORK_TIMEOUT -> "网络超时"
        TextToSpeech.ERROR_NOT_INSTALLED_YET -> "TTS 引擎未安装"
        TextToSpeech.ERROR_OUTPUT -> "输出错误"
        TextToSpeech.ERROR_SERVICE -> "TTS 服务错误"
        TextToSpeech.ERROR_SYNTHESIS -> "合成错误"
        -99 -> "TTS 未初始化"
        else -> "TTS 状态未知 ($ttsInitStatus)"
    }

    fun initTts(onReady: ((Boolean) -> Unit)? = null) {
        Log.d(TAG, "Initializing TTS...")
        textToSpeech = TextToSpeech(context) { status ->
            ttsInitStatus = status
            ttsInitialized = status == TextToSpeech.SUCCESS
            Log.d(TAG, "TTS init result: $status (${getTtsStatus()})")

            if (ttsInitialized) {
                // 设置默认语言
                val langResult = textToSpeech?.setLanguage(Locale.CHINESE)
                Log.d(TAG, "TTS set Chinese: $langResult")

                // 获取可用语音列表
                val voices = textToSpeech?.voices
                Log.d(TAG, "Available voices: ${voices?.size}")
                voices?.forEach { v ->
                    Log.d(TAG, "  Voice: ${v.name} (${v.locale})")
                }
            }

            onReady?.invoke(ttsInitialized)
        }
    }

    fun speak(text: String, locale: Locale = Locale.CHINESE) {
        Log.d(TAG, "speak: '$text' locale=$locale ttsInit=$ttsInitialized")

        if (!ttsInitialized) {
            Log.w(TAG, "TTS not initialized, trying to init...")
            initTts { success ->
                if (success) doSpeak(text, locale)
                else Log.e(TAG, "TTS init failed, cannot speak")
            }
            return
        }
        doSpeak(text, locale)
    }

    private fun doSpeak(text: String, locale: Locale) {
        try {
            textToSpeech?.stop()

            val langResult = textToSpeech?.setLanguage(locale)
            Log.d(TAG, "setLanguage($locale) result: $langResult")

            // 如果指定语言不可用，尝试用中文
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Language $locale not supported, falling back to Chinese")
                textToSpeech?.setLanguage(Locale.CHINESE)
            }

            textToSpeech?.setSpeechRate(0.9f)
            val speakResult = textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
            Log.d(TAG, "speak result: $speakResult")
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking", e)
        }
    }

    fun stopSpeaking() {
        textToSpeech?.stop()
    }

    fun release() {
        stopListening()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsInitialized = false
    }
}
