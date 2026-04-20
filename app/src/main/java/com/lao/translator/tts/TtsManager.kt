package com.lao.translator.tts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 双语 TTS 管理器
 * 中文：系统 TTS 即可
 * 老挝语：尝试系统 TTS，不支持则引导安装
 */
class TtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var laoSupported = false
    private var zhSupported = false

    // 播放完成回调
    var onSpeakDone: (() -> Unit)? = null

    /**
     * 初始化 TTS 引擎
     */
    suspend fun init() = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts?.defaultEngine ?: ""
                    android.util.Log.d("TtsManager", "TTS engine: $engine")

                    // 检测中文支持
                    zhSupported = tts?.setLanguage(Locale.CHINESE)?.let {
                        it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED
                    } ?: false

                    // 检测老挝语支持
                    val laoLocale = Locale("lo", "LA")
                    laoSupported = tts?.setLanguage(laoLocale)?.let {
                        it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED
                    } ?: false

                    // 回退到中文作为默认
                    tts?.setLanguage(Locale.CHINESE)

                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            onSpeakDone?.invoke()
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {}
                    })

                    isReady = true
                    android.util.Log.d("TtsManager", "中文=$zhSupported, 老挝语=$laoSupported")
                }
                cont.resume(Unit)
            }
        }
    }

    /**
     * 朗读文本
     * @param text 要朗读的文本
     * @param language "zh" 或 "lo"
     */
    fun speak(text: String, language: String) {
        if (!isReady || text.isBlank()) return

        val locale = when (language) {
            "lo" -> {
                if (!laoSupported) {
                    // 老挝语不支持，提示用户安装
                    suggestLaoTtsInstall()
                    return
                }
                Locale("lo", "LA")
            }
            "zh" -> Locale.CHINESE
            else -> Locale.CHINESE
        }

        tts?.language = locale

        // 设置语速（老挝语稍微慢一点，更清晰）
        if (language == "lo") {
            tts?.setSpeechRate(0.85f)
        } else {
            tts?.setSpeechRate(1.0f)
        }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, "tts_${System.currentTimeMillis()}")
    }

    /**
     * 停止朗读
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * 是否正在朗读
     */
    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    /**
     * 老挝语 TTS 是否可用
     */
    fun isLaoAvailable(): Boolean = laoSupported

    /**
     * 中文 TTS 是否可用
     */
    fun isChineseAvailable(): Boolean = zhSupported

    /**
     * 老挝语不支持时，引导用户安装 TTS 引擎
     */
    private fun suggestLaoTtsInstall() {
        // 尝试打开 Google TTS 设置（Google TTS 有老挝语语言包可下载）
        try {
            val intent = Intent().apply {
                setClassName(
                    "com.google.android.tts",
                    "com.google.android.tts.settings.EngineSettings"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // 打不开就打开系统 TTS 设置
            try {
                val intent = Intent("com.android.settings.TTS_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {
                // 最后的 fallback
            }
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
