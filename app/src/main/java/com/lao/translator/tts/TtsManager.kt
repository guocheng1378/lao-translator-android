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

class TtsManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsManager"
    }

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var laoSupported = false
    private var zhSupported = false

    var onSpeakDone: (() -> Unit)? = null

    suspend fun init() = withContext(Dispatchers.Main) {
        val success = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val listener = TextToSpeech.OnInitListener { status ->
                    Log.d(TAG, "TTS OnInitListener: status=$status")

                    if (status == TextToSpeech.SUCCESS) {
                        val engine = tts?.defaultEngine ?: "unknown"
                        Log.d(TAG, "TTS engine: $engine")

                        // 等引擎完全绑定（HyperOS 需要）
                        try { Thread.sleep(300) } catch (_: InterruptedException) {}

                        try {
                            zhSupported = tts?.setLanguage(Locale.CHINESE)?.let {
                                it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED
                            } ?: false

                            val laoLocale = Locale("lo", "LA")
                            laoSupported = tts?.setLanguage(laoLocale)?.let {
                                it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED
                            } ?: false

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
                            Log.d(TAG, "TTS OK: zh=$zhSupported, lo=$laoSupported")
                        } catch (e: Exception) {
                            Log.e(TAG, "TTS config error: ${e.message}", e)
                            isReady = false
                        }
                    } else {
                        Log.e(TAG, "TTS init failed: status=$status")
                        isReady = false
                    }
                    if (cont.isActive) cont.resume(Unit)
                }

                try {
                    tts = TextToSpeech(context, listener)
                } catch (e: Exception) {
                    Log.e(TAG, "TTS construct error: ${e.message}")
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }

        if (success == null) {
            Log.e(TAG, "TTS init timeout (10s)")
            isReady = false
        }
        Log.d(TAG, "TTS init done: isReady=$isReady")
    }

    fun speak(text: String, language: String) {
        if (!isReady) {
            Log.w(TAG, "speak skip: isReady=false")
            return
        }
        if (text.isBlank() || tts == null) {
            Log.w(TAG, "speak skip: text blank or tts null")
            return
        }

        val locale = when (language) {
            "lo" -> {
                if (!laoSupported) {
                    Log.w(TAG, "Lao TTS not supported")
                    suggestLaoTtsInstall()
                    return
                }
                Locale("lo", "LA")
            }
            "zh" -> Locale.CHINESE
            else -> Locale.CHINESE
        }

        tts?.language = locale
        tts?.setSpeechRate(if (language == "lo") 0.85f else 1.0f)

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        val id = "tts_${System.currentTimeMillis()}"
        Log.d(TAG, "speak: lang=$language, text='${text.take(30)}', id=$id")
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
        Log.d(TAG, "speak result: $result")
    }

    fun stop() { tts?.stop() }
    fun isSpeaking(): Boolean = tts?.isSpeaking == true
    fun isLaoAvailable(): Boolean = laoSupported
    fun isChineseAvailable(): Boolean = zhSupported

    private fun suggestLaoTtsInstall() {
        try {
            context.startActivity(Intent().apply {
                setClassName("com.google.android.tts", "com.google.android.tts.settings.EngineSettings")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            try {
                context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }

    fun release() {
        isReady = false
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        Log.d(TAG, "TTS released")
    }
}
