package com.translator.lao.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 语音合成（TTS）— 中文 + 老挝语播报
 *
 * 使用 openai-edge-tts 服务（兼容 OpenAI TTS API，基于 Microsoft Edge 免费 TTS）
 * 部署方式: https://github.com/travisvn/openai-edge-tts
 *
 * API 端点: POST /v1/audio/speech
 * 输入: JSON {"input": "文字", "voice": "语音名", "response_format": "mp3"}
 * 输出: 直接返回 MP3 音频流
 */
class MiMoTtsManager(private val context: Context) {

    companion object {
        private const val TAG = "EdgeTts"
        // 飞牛 NAS TTS 服务（内网地址，外网需端口转发）
        private const val ENDPOINT = "http://192.168.2.63:5050/v1/audio/speech"
        private const val API_KEY = ""  // 留空 = 不需要认证

        // 中文语音: zh-CN-XiaoxiaoNeural（女声，自然）
        private const val VOICE_ZH = "zh-CN-XiaoxiaoNeural"
        // 老挝语语音: lo-LA-KeomanyNeural
        private const val VOICE_LO = "lo-LA-KeomanyNeural"
    }

    interface TtsCallback {
        fun onComplete()
        fun onError(error: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var isLao = false  // 由 speak 调用时设置

    fun isAvailable(): Boolean = true

    /**
     * 设置语言方向
     * @param lao true = 老挝语, false = 中文
     */
    fun setLanguage(lao: Boolean) {
        isLao = lao
    }

    /**
     * 语音合成并播放
     *
     * @param text 要合成的文字
     * @param style 语气风格（edge-tts 不支持，忽略）
     */
    suspend fun speak(
        text: String,
        style: String = "",
        callback: TtsCallback? = null
    ) = withContext(Dispatchers.IO) {
        try {
            stop()

            if (text.isBlank()) {
                withContext(Dispatchers.Main) { callback?.onError("文字为空") }
                return@withContext
            }

            val trimmedText = if (text.length > 500) text.take(500) else text
            val voice = if (isLao) VOICE_LO else VOICE_ZH

            // 构建请求体
            val payload = JSONObject()
                .put("input", trimmedText)
                .put("voice", voice)
                .put("response_format", "mp3")

            Log.d(TAG, "TTS request: '${trimmedText.take(30)}...' voice=$voice")

            val reqBuilder = Request.Builder()
                .url(ENDPOINT)
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))

            if (API_KEY.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $API_KEY")
            }

            val response = client.newCall(reqBuilder.build()).execute()

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "HTTP ${response.code}: $errBody")
                withContext(Dispatchers.Main) {
                    callback?.onError("合成失败：HTTP ${response.code}")
                }
                return@withContext
            }

            val audioBytes = response.body?.bytes()
            if (audioBytes == null || audioBytes.isEmpty()) {
                withContext(Dispatchers.Main) { callback?.onError("返回音频为空") }
                return@withContext
            }

            Log.d(TAG, "Got ${audioBytes.size} bytes of MP3 audio")
            playAudio(audioBytes, callback)

        } catch (e: Exception) {
            Log.e(TAG, "TTS error", e)
            withContext(Dispatchers.Main) {
                callback?.onError("语音合成出错：${e.message}")
            }
        }
    }

    /** 停止播放 */
    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun release() {
        stop()
    }

    // ========== 内部方法 ==========

    private suspend fun playAudio(audioData: ByteArray, callback: TtsCallback?) {
        withContext(Dispatchers.IO) {
            try {
                val tempFile = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
                tempFile.writeBytes(audioData)

                withContext(Dispatchers.Main) {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        setDataSource(tempFile.absolutePath)
                        prepareAsync()
                        setOnPreparedListener { start() }
                        setOnCompletionListener {
                            release()
                            mediaPlayer = null
                            tempFile.delete()
                            callback?.onComplete()
                        }
                        setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "MediaPlayer error: $what / $extra")
                            release()
                            mediaPlayer = null
                            tempFile.delete()
                            callback?.onError("播放出错")
                            true
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback?.onError("播放出错：${e.message}")
                }
            }
        }
    }
}
