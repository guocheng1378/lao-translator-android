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
                Log.d(TAG, "✅ whisper_jni loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ Failed to load whisper_jni: ${e.message}")
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

    suspend fun init(modelName: String = "ggml-base.bin"): Boolean = withContext(Dispatchers.IO) {
        if (!nativeLoaded) {
            Log.e(TAG, "nativeLoaded=false, 无法加载 whisper_jni")
            throw IllegalStateException("Native 库加载失败，设备可能不支持此架构 (arm64-v8a)")
        }
        val modelFile = File(context.filesDir, "models/$modelName")
        Log.d(TAG, "模型路径: ${modelFile.absolutePath}, 存在: ${modelFile.exists()}, 大小: ${modelFile.length()} bytes")

        if (!modelFile.exists()) {
            val modelDir = File(context.filesDir, "models")
            modelDir.mkdirs()
            try {
                Log.d(TAG, "尝试从 assets 复制模型...")
                context.assets.open("models/$modelName").use { input ->
                    java.io.FileOutputStream(modelFile).use { output ->
                        val buf = ByteArray(65536)
                        var total = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            total += read
                        }
                        Log.d(TAG, "从 assets 复制完成，大小: $total bytes")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "从 assets 复制失败: ${e.message}")
                throw IllegalStateException("模型文件不存在且从 assets 复制失败: ${e.message}")
            }
        }

        val fileSize = modelFile.length()
        if (fileSize < 50_000_000) {
            Log.e(TAG, "模型文件过小 ($fileSize bytes)，可能下载不完整")
            throw IllegalStateException("模型文件不完整 (${fileSize / 1024 / 1024}MB)，请删除后重启应用重新下载")
        }

        val rt = Runtime.getRuntime()
        Log.d(TAG, "内存状态: 可用=${rt.freeMemory() / 1024 / 1024}MB, 最大=${rt.maxMemory() / 1024 / 1024}MB")
        Log.d(TAG, "开始 nativeInit, 路径: ${modelFile.absolutePath}")

        val t0 = System.currentTimeMillis()
        isInitialized = nativeInit(modelFile.absolutePath)
        val elapsed = System.currentTimeMillis() - t0

        Log.d(TAG, "nativeInit 返回: $isInitialized, 耗时=${elapsed}ms, 文件大小=${fileSize} bytes")
        if (!isInitialized) {
            Log.e(TAG, "nativeInit 失败！模型可能损坏或格式不正确")
            throw IllegalStateException("Whisper 模型初始化失败（耗时${elapsed}ms），模型文件可能损坏")
        }
        isInitialized
    }

    suspend fun warmup() {
        if (!isInitialized || warmedUp) return
        withContext(Dispatchers.Default) {
            Log.d(TAG, "开始 warmup...")
            val silence = FloatArray(SAMPLE_RATE) { 0.001f }
            try {
                nativeTranscribe(silence, silence.size, "")
                warmedUp = true
                Log.d(TAG, "warmup 完成")
            } catch (e: Exception) {
                Log.w(TAG, "warmup 失败（不影响正常使用）: ${e.message}")
            }
        }
    }

    suspend fun transcribeAuto(samples: FloatArray): TranscribeResult =
        withContext(Dispatchers.Default) {
            if (!isInitialized) {
                Log.w(TAG, "transcribeAuto 调用时 isInitialized=false")
                return@withContext TranscribeResult("", "")
            }

            val energy = calculateEnergy(samples)
            Log.d(TAG, "开始转写, samples.size=${samples.size}, 能量=$energy")
            val t0 = System.currentTimeMillis()

            val raw = try {
                nativeTranscribe(samples, samples.size, "")
            } catch (e: Exception) {
                Log.e(TAG, "nativeTranscribe 异常: ${e.message}", e)
                return@withContext TranscribeResult("", "")
            }

            val elapsed = System.currentTimeMillis() - t0
            Log.d(TAG, "nativeTranscribe 返回: '$raw' (长度=${raw.length}, 耗时=${elapsed}ms)")

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

            Log.d(TAG, "转写结果: text='$text', lang='$langCode' (原始='$detectedLang')")
            TranscribeResult(text, langCode)
        }

    suspend fun transcribe(samples: FloatArray, language: String): String =
        withContext(Dispatchers.Default) {
            if (!isInitialized) return@withContext ""
            val raw = nativeTranscribe(samples, samples.size, language)
            val lines = raw.split("\n", limit = 2)
            if (lines.size > 1) lines[1].trim() else raw.trim()
        }

    private fun calculateEnergy(samples: FloatArray): Float {
        var energy = 0f
        for (s in samples) energy += s * s
        return energy / samples.size
    }

    fun isInitialized(): Boolean = isInitialized

    fun release() {
        if (isInitialized) {
            nativeRelease()
            isInitialized = false
            warmedUp = false
            Log.d(TAG, "Whisper 已释放")
        }
    }
}
