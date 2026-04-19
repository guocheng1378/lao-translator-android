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
import java.net.InetAddress
import java.net.UnknownHostException
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
        // 飞牛 NAS TTS 服务（Tailscale 内网地址）
        private const val ENDPOINT = "https://guo.tail7aa8e0.ts.net/v1/audio/speech"
        private const val API_KEY = ""  // 留空 = 不需要认证

        // 中文语音: zh-CN-XiaoxiaoNeural（女声，自然）
        private const val VOICE_ZH = "zh-CN-XiaoxiaoNeural"
        // 老挝语语音: lo-LA-KeomanyNeural
        private const val VOICE_LO = "lo-LA-KeomanyNeural"

        // 从 ENDPOINT 提取主机名，用于连通性检测
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null
    private var isLao = false  // 由 speak 调用时设置

    // 连通性检测缓存（避免频繁 DNS 查询）
    @Volatile
    private var lastCheckTime = 0L
    @Volatile
    private var lastCheckResult = false
    private val CHECK_CACHE_MS = 30_000L // 30秒缓存

    /**
     * 检测 TTS 服务是否可达
     * 通过 DNS 解析判断设备是否在 Tailscale 网络中
     */
    fun isReachable(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCheckTime < CHECK_CACHE_MS) {
            return lastCheckResult
        }

        val result = try {
            InetAddress.getByName(HOST)
            true
        } catch (e: UnknownHostException) {
            Log.w(TAG, "DNS resolution failed for $HOST: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Reachability check failed: ${e.message}")
            false
        }

        lastCheckTime = now
        lastCheckResult = result
        return result
    }

    /**
     * 获取不可达原因的用户友好描述
     */
    fun getUnreachableReason(): String {
        return "语音合成服务不可达\n\n" +
                "当前使用 Tailscale 内网地址 ($HOST)\n\n" +
                "解决方法：\n" +
                "1. 安装 Tailscale App 并登录同一网络\n" +
                "2. 确认 Tailscale 已连接且 MagicDNS 已启用\n" +
                "3. 或联系开发者配置公网 TTS 服务\n\n" +
                "已自动切换为系统语音引擎播报"
    }

    fun isAvailable(): Boolean = isReachable()

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

            // 先检测连通性
            if (!isReachable()) {
                withContext(Dispatchers.Main) {
                    callback?.onError("服务不可达：$HOST 无法解析，请检查 Tailscale 连接")
                }
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
