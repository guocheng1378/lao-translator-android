package com.lao.translator.tts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 双语 TTS 管理器
 * 中文：系统 TTS 即可
 * 老挝语：尝试系统 TTS，不支持则引导安装
 */
class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var laoSupported = false
    private var zhSupported = false
    private var initError: String? = null

    // 播放完成回调
    var onSpeakDone: (() -> Unit)? = null

    /**
     * 初始化 TTS 引擎
     * ✅ FIX: 移除 Thread.sleep(200)，改用正确的回调等待机制
     */
    suspend fun init() = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts?.defaultEngine ?: "unknown"
                    Log.d(TAG, "TTS engine: $engine")

                    // ✅ FIX: 不用 sleep，直接在回调里操作
                    // TTS engine 的 OnInitListener 回调保证引擎已绑定

                    try {
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
                            override fun onStart(utteranceId: String?) {
                                Log.d(TAG, "TTS onStart: $utteranceId")
                            }
                            override fun onDone(utteranceId: String?) {
                                Log.d(TAG, "TTS onDone: $utteranceId")
                                onSpeakDone?.invoke()
                            }
                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                Log.w(TAG, "TTS onError: $utteranceId")
                            }
                        })

                        isReady = true
                        initError = null
                        Log.d(TAG, "✅ TTS 初始化成功: 中文=$zhSupported, 老挝语=$laoSupported")
                    } catch (e: Exception) {
                        Log.e(TAG, "TTS 配置异常: ${e.message}", e)
                        initError = e.message
                        isReady = false
                    }
                } else {
                    Log.e(TAG, "❌ TTS 初始化失败: status=$status")
                    initError = "TTS 引擎初始化失败 (status=$status)"
                    isReady = false
                }
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    /**
     * 朗读文本
     * @param text 要朗读的文本
     * @param language "zh" 或 "lo"
     */
    fun speak(text: String, language: String) {
        if (!isReady || text.isBlank() || tts == null) {
            Log.w(TAG, "speak 跳过: isReady=$isReady, text='${text.take(20)}', tts=$tts, error=$initError")
            return
        }

        val locale = when (language) {
            "lo" -> {
                if (!laoSupported) {
                    Log.w(TAG, "老挝语 TTS 不支持，引导用户安装")
                    suggestLaoTtsInstall()
                    return
                }
                Locale("lo", "LA")
            }
            "zh" -> Locale.CHINESE
            else -> Locale.CHINESE
        }

        tts?.language = locale

        // 设置语速
        if (language == "lo") {
            tts?.setSpeechRate(0.85f)
        } else {
            tts?.setSpeechRate(1.0f)
        }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        val utteranceId = "tts_${System.currentTimeMillis()}"
        Log.d(TAG, "🔊 speak: lang=$language, text='${text.take(30)}', id=$utteranceId")
        tts?.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
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
            try {
                val intent = Intent("com.android.settings.TTS_SETTINGS")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {
                Log.w(TAG, "无法打开 TTS 设置")
            }
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        isReady = false
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        Log.d(TAG, "TTS 已释放")
    }
}
