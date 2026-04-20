package com.lao.translator.stt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class WhisperManager(private val context: Context) {

    companion object {
        private const val TAG = "WhisperManager"
        var nativeLoaded = false
            private set

        init {
            try {
                System.loadLibrary("whisper_jni")
                nativeLoaded = true
                Log.d(TAG, "whisper_jni loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load whisper_jni: ${e.message}")
                nativeLoaded = false
            }
        }
        const val SAMPLE_RATE = 16000
    }

    data class TranscribeResult(
        val text: String,
        val language: String,
        val isLao: Boolean = language == "lo",
        val isChinese: Boolean = language == "zh"
    )

    private var isInitialized = false
    private var warmedUp = false

    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeTranscribe(audioData: FloatArray, nSamples: Int, language: String): String
    private external fun nativeRelease()

    suspend fun init(modelName: String = "ggml-small.bin"): Boolean = withContext(Dispatchers.IO) {
        if (!nativeLoaded) {
            throw IllegalStateException("Native 库加载失败，设备可能不支持此架构")
        }
        val modelFile = File(context.filesDir, "models/$modelName")
        if (!modelFile.exists()) {
            throw IllegalStateException("模型文件不存在: ${modelFile.absolutePath}")
        }
        isInitialized = nativeInit(modelFile.absolutePath)
        isInitialized
    }

    suspend fun warmup() {
        if (!isInitialized || warmedUp) return
        withContext(Dispatchers.Default) {
            val silence = FloatArray(SAMPLE_RATE) { 0.001f }
            nativeTranscribe(silence, silence.size, "")
            warmedUp = true
        }
    }

    suspend fun transcribeAuto(samples: FloatArray): TranscribeResult =
        withContext(Dispatchers.Default) {
            if (!isInitialized) return@withContext TranscribeResult("", "")

            val raw = nativeTranscribe(samples, samples.size, "")

            val lines = raw.split("\n", limit = 2)
            val detectedLang = if (lines.isNotEmpty() && lines[0].startsWith("LANG:")) {
                lines[0].removePrefix("LANG:").trim()
            } else {
                ""
            }
            val text = if (lines.size > 1) lines[1].trim() else raw.trim()

            val langCode = when {
                detectedLang.startsWith("lao") -> "lo"
                detectedLang.startsWith("chinese") || detectedLang == "zh" -> "zh"
                detectedLang.startsWith("english") -> "en"
                else -> detectedLang
            }

            TranscribeResult(text, langCode)
        }

    suspend fun transcribe(samples: FloatArray, language: String): String =
        withContext(Dispatchers.Default) {
            if (!isInitialized) return@withContext ""
            val raw = nativeTranscribe(samples, samples.size, language)
            val lines = raw.split("\n", limit = 2)
            if (lines.size > 1) lines[1].trim() else raw.trim()
        }

    fun release() {
        if (isInitialized) {
            nativeRelease()
            isInitialized = false
            warmedUp = false
        }
    }
}
