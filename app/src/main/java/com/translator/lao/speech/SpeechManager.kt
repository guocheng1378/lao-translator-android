package com.translator.lao.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 语音管理器 - 统一管理语音识别和语音合成
 * 使用 Android 原生 SpeechRecognizer + TextToSpeech
 */
class SpeechManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsInitialized = false

    // ========== 语音识别 ==========

    interface RecognitionCallback {
        fun onResult(text: String)
        fun onError(error: String)
        fun onBeginOfSpeech()
        fun onEndOfSpeech()
    }

    /**
     * 开始语音识别
     * @param locale 语言区域 (Locale.CHINESE / Locale("lo"))
     * @param callback 回调
     */
    fun startListening(locale: Locale = Locale.CHINESE, callback: RecognitionCallback) {
        stopListening()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callback.onError("当前设备不支持语音识别")
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() { callback.onBeginOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { callback.onEndOfSpeech() }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足，请授予麦克风权限"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                    SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
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
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        speechRecognizer?.startListening(intent)
    }

    /**
     * 停止语音识别
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // ========== 语音合成 ==========

    /**
     * 初始化 TTS
     */
    fun initTts(onReady: ((Boolean) -> Unit)? = null) {
        textToSpeech = TextToSpeech(context) { status ->
            ttsInitialized = status == TextToSpeech.SUCCESS
            onReady?.invoke(ttsInitialized)
        }
    }

    /**
     * 朗读文本
     * @param text 要朗读的文本
     * @param locale 语言区域
     */
    fun speak(text: String, locale: Locale = Locale.CHINESE) {
        if (!ttsInitialized || textToSpeech == null) {
            initTts { success ->
                if (success) doSpeak(text, locale)
            }
            return
        }
        doSpeak(text, locale)
    }

    private fun doSpeak(text: String, locale: Locale) {
        textToSpeech?.language = locale
        textToSpeech?.setSpeechRate(0.9f)
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_utterance")
    }

    /**
     * 停止朗读
     */
    fun stopSpeaking() {
        textToSpeech?.stop()
    }

    /**
     * 释放资源
     */
    fun release() {
        stopListening()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsInitialized = false
    }
}
