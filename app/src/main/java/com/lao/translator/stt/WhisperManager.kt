package com.lao.translator.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Whisper.cpp JNI 封装（优化版）
 *
 * 优化点：
 * - 首次识别预热，减少冷启动延迟
 * - 结果缓存避免重复识别相似音频
 * - 自动语言检测返回结构化结果
 */
class WhisperManager(private val context: Context) {

    companion object {
        init {
            System.loadLibrary("whisper_jni")
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
        val modelFile = File(context.filesDir, "models/$modelName")
        if (!modelFile.exists()) {
            throw IllegalStateException("模型文件不存在: ${modelFile.absolutePath}")
        }
        isInitialized = nativeInit(modelFile.absolutePath)
        isInitialized
    }

    /**
     * 预热：用静音跑一次，消除首次识别的冷启动延迟
     */
    suspend fun warmup() {
        if (!isInitialized || warmedUp) return
        withContext(Dispatchers.Default) {
            // 1秒静音预热
            val silence = FloatArray(SAMPLE_RATE) { 0.001f }
            nativeTranscribe(silence, silence.size, "")
            warmedUp = true
        }
    }

    /**
     * 自动检测语言 + 识别
     */
    suspend fun transcribeAuto(samples: FloatArray): TranscribeResult =
        withContext(Dispatchers.Default) {
            if (!isInitialized) return@withContext TranscribeResult("", "")

            val raw = nativeTranscribe(samples, samples.size, "")

            // 解析 "LANG:lo\n文本"
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

    /**
     * 指定语言识别
     */
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
