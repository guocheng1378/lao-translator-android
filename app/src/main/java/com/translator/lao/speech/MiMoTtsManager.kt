package com.translator.lao.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.translator.lao.api.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

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
        // 飞牛 NAS TTS 服务（Tailscale 内网地址）
        private const val ENDPOINT = "https://guo.tail7aa8e0.ts.net/v1/audio/speech"
        private const val API_KEY = ""  // 留空 = 不需要认证

        // 中文语音: zh-CN-XiaoxiaoNeural（女声，自然）
        private const val VOICE_ZH = "zh-CN-XiaoxiaoNeural"
        // 老挝语语音: lo-LA-KeomanyNeural
        private const val VOICE_LO = "lo-LA-KeomanyNeural"

        // 从 ENDPOINT 提取主机名，用于日志
        private val HOST: String = try {
            java.net.URI(ENDPOINT).host ?: "guo.tail7aa8e0.ts.net"
        } catch (_: Exception) {
            "guo.tail7aa8e0.ts.net"
        }
    }

    interface TtsCallback {
        fun onComplete()
        fun onError(error: String)
    }

    private val client get() = HttpClient.tts
    private var mediaPlayer: MediaPlayer? = null
    private var isLao = false

    /**
     * 始终返回 true，不做过滤。
     * 实际可用性由 speak() 的 HTTP 请求和异常处理来判断。
     */
    fun isAvailable(): Boolean = true

    fun getUnreachableReason(): String {
        return "语音合成服务不可达\n\n" +
                "当前使用 Tailscale 内网地址 ($HOST)\n\n" +
                "解决方法：\n" +
                "1. 安装 Tailscale App 并登录同一网络\n" +
                "2. 确认 Tailscale 已连接且 MagicDNS 已启用\n" +
                "3. 或联系开发者配置公网 TTS 服务\n\n" +
                "已自动切换为系统语音引擎播报"
    }

    fun setLanguage(lao: Boolean) {
        isLao = lao
    }

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

            val response = try {
                client.newCall(reqBuilder.build()).execute()
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "Network error: cannot resolve $HOST", e)
                withContext(Dispatchers.Main) {
                    callback?.onError("网络错误：无法连接到 TTS 服务 ($HOST)，请检查 Tailscale 连接")
                }
                return@withContext
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Timeout connecting to $HOST", e)
                withContext(Dispatchers.Main) {
                    callback?.onError("连接超时：TTS 服务响应过慢，请检查网络")
                }
                return@withContext
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "Connection refused to $HOST", e)
                withContext(Dispatchers.Main) {
                    callback?.onError("连接被拒绝：TTS 服务未启动或不可达")
                }
                return@withContext
            } catch (e: javax.net.ssl.SSLException) {
                Log.e(TAG, "SSL error for $HOST", e)
                withContext(Dispatchers.Main) {
                    callback?.onError("SSL 错误：Tailscale 证书问题，请检查 Tailscale 连接状态")
                }
                return@withContext
            } catch (e: Exception) {
                Log.e(TAG, "HTTP request failed", e)
                withContext(Dispatchers.Main) {
                    callback?.onError("网络错误：${e.javaClass.simpleName} - ${e.message}")
                }
                return@withContext
            }

            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.e(TAG, "HTTP ${response.code}: $errBody")
                withContext(Dispatchers.Main) {
                    callback?.onError("合成失败：HTTP ${response.code} ${errBody.take(100)}")
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
                callback?.onError("语音合成出错：${e.javaClass.simpleName} - ${e.message}")
            }
        }
    }

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
