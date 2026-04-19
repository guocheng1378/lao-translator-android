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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * MiMo 语音合成（TTS）— 用于中文 + 老挝语播报
 *
 * API 端点: https://api.xiaomimimo.com/v1/chat/completions
 * 模型: mimo-v2-audio-tts
 * 输出: WAV/PCM 音频（24kHz, 16bit, mono）
 *
 * 支持 style 参数控制语气风格，如 "开心"、"悲伤"、"语速慢" 等。
 */
class MiMoTtsManager(private val context: Context) {

    companion object {
        private const val TAG = "MiMoTts"
        private const val ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions"
        private const val MODEL = "mimo-v2-audio-tts"
        private const val API_KEY = "sk-cqf4xgp4avkf4lfgrhwvwvmr2xd0gntc0ukyiwx45ljhtb02"
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

    fun isAvailable(): Boolean = true  // key 写死在代码里，始终可用

    /**
     * 语音合成并播放
     *
     * @param text 要合成的文字
     * @param style 语气风格（可选）：开心 / 悲伤 / 语速慢 / 清晰有力 / 东北话 等
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

            // 构建 content（带 style 标签）
            val content = if (style.isNotBlank()) {
                "<style>${style}</style>${trimmedText}"
            } else {
                trimmedText
            }

            // 构建请求体
            val messages = JSONArray().put(
                JSONObject().put("role", "assistant").put("content", content)
            )
            val audio = JSONObject()
                .put("format", "wav")
                .put("voice", "mimo_default")

            val payload = JSONObject()
                .put("model", MODEL)
                .put("audio", audio)
                .put("messages", messages)

            Log.d(TAG, "TTS request: '${trimmedText.take(30)}...' style=$style")

            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("Content-Type", "application/json")
                .addHeader("api-key", API_KEY)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code}: $body")
                withContext(Dispatchers.Main) {
                    callback?.onError("合成失败：HTTP ${response.code}")
                }
                return@withContext
            }

            // 解析响应
            val json = JSONObject(body)

            if (json.has("error")) {
                val error = json.getJSONObject("error")
                val msg = error.optString("message", "未知错误")
                Log.e(TAG, "API error: $msg")
                withContext(Dispatchers.Main) { callback?.onError("合成失败：$msg") }
                return@withContext
            }

            val audioData = json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getJSONObject("audio")
                .getString("data")

            val rawBytes = android.util.Base64.decode(audioData, android.util.Base64.DEFAULT)
            Log.d(TAG, "Got ${rawBytes.size} bytes of audio")

            // 判断是 WAV 还是 raw PCM，如果是 PCM 需要加 WAV header
            val wavBytes = if (rawBytes.size > 4 &&
                rawBytes[0] == 'R'.code.toByte() &&
                rawBytes[1] == 'I'.code.toByte() &&
                rawBytes[2] == 'F'.code.toByte() &&
                rawBytes[3] == 'F'.code.toByte()
            ) {
                rawBytes // 已经是 WAV
            } else {
                pcmToWav(rawBytes, 24000) // raw PCM → WAV
            }

            playAudio(wavBytes, callback)

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
                val tempFile = File(context.cacheDir, "mimo_tts_${System.currentTimeMillis()}.wav")
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

    /** Raw PCM → WAV (24kHz, 16bit, mono) */
    private fun pcmToWav(pcmData: ByteArray, sampleRate: Int = 24000): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = pcmData.size + 36

        return ByteArray(44 + pcmData.size).also { wav ->
            // RIFF header
            "RIFF".forEachIndexed { i, c -> wav[i] = c.code.toByte() }
            writeInt(wav, 4, totalDataLen)
            "WAVE".forEachIndexed { i, c -> wav[8 + i] = c.code.toByte() }

            // fmt chunk
            "fmt ".forEachIndexed { i, c -> wav[12 + i] = c.code.toByte() }
            writeInt(wav, 16, 16)
            writeShort(wav, 20, 1) // PCM
            writeShort(wav, 22, channels.toShort())
            writeInt(wav, 24, sampleRate)
            writeInt(wav, 28, byteRate)
            writeShort(wav, 32, (channels * bitsPerSample / 8).toShort())
            writeShort(wav, 34, bitsPerSample.toShort())

            // data chunk
            "data".forEachIndexed { i, c -> wav[36 + i] = c.code.toByte() }
            writeInt(wav, 40, pcmData.size)

            pcmData.copyInto(wav, 44)
        }
    }

    private fun writeInt(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShort(buf: ByteArray, offset: Int, value: Short) {
        buf[offset] = (value.toInt() and 0xFF).toByte()
        buf[offset + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
    }
}
