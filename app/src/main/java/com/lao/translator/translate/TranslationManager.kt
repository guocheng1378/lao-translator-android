package com.lao.translator.translate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 双向翻译管理器：老挝语 ↔ 中文（双路径）
 *
 * 路径 1: ML Kit 泰语 — 离线可用，zh↔th bundled model
 * 路径 2: MyMemory API — 在线，真正的 zh↔lo
 *
 * 有网 → 用 MyMemory (lo)；无网 → 用 ML Kit (th) 兜底
 */
class TranslationManager(private val context: Context) {

    companion object {
        private const val TAG = "TranslationManager"
    }

    sealed class TranslateDirection {
        data object LaoToChinese : TranslateDirection()
        data object ChineseToLao : TranslateDirection()
    }

    enum class TranslateMode {
        MLKIT_THAI,      // 离线，ML Kit 泰语
        MYMEMORY_LAO,    // 在线，MyMemory 老挝语
        UNAVAILABLE       // 不可用
    }

    private var _isReady = false
    private var mlKitReady = false
    private var zhThTranslator: Translator? = null

    val isReady: Boolean get() = _isReady

    /**
     * 初始化：加载 ML Kit zh↔th bundled model
     */
    suspend fun init() = withContext(Dispatchers.IO) {
        _isReady = false
        mlKitReady = false

        // 初始化 ML Kit 泰语翻译
        try {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.THAI)
                .build()
            zhThTranslator = Translation.getClient(options)
            zhThTranslator?.downloadModelIfNeeded()?.await()
            mlKitReady = true
            Log.d(TAG, "ML Kit zh↔th 模型就绪")
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit zh↔th 加载失败: ${e.message}")
            zhThTranslator = null
        }

        // 测试 MyMemory 连通性
        if (isNetworkAvailable()) {
            try {
                val test = translateMyMemory("hello", "en|zh-CN")
                if (test.isNotBlank()) {
                    Log.d(TAG, "MyMemory 连通测试通过: 'hello' -> '$test'")
                }
            } catch (e: Exception) {
                Log.w(TAG, "MyMemory 连通测试失败: ${e.message}")
            }
        }

        _isReady = mlKitReady || isNetworkAvailable()
        Log.d(TAG, "TranslationManager 就绪: mlKit=$mlKitReady, network=${isNetworkAvailable()}")
    }

    /**
     * 获取当前翻译模式
     */
    fun getCurrentMode(): TranslateMode = when {
        isNetworkAvailable() -> TranslateMode.MYMEMORY_LAO
        mlKitReady -> TranslateMode.MLKIT_THAI
        else -> TranslateMode.UNAVAILABLE
    }

    /**
     * 翻译文本 — 自动选择最佳路径
     */
    suspend fun translate(text: String, direction: TranslateDirection): String {
        if (text.isBlank()) return ""

        if (!_isReady) {
            throw IllegalStateException("翻译服务未就绪")
        }

        val online = isNetworkAvailable()
        val mode = when {
            online -> TranslateMode.MYMEMORY_LAO
            mlKitReady -> TranslateMode.MLKIT_THAI
            else -> TranslateMode.UNAVAILABLE
        }

        Log.d(TAG, "翻译模式: $mode, 方向: $direction")

        return when (mode) {
            TranslateMode.MYMEMORY_LAO -> translateViaMyMemory(text, direction)
            TranslateMode.MLKIT_THAI -> translateViaMlKit(text, direction)
            TranslateMode.UNAVAILABLE -> throw IllegalStateException("无网络且离线翻译不可用")
        }
    }

    /**
     * MyMemory API: 真正的老挝语翻译 (需网络)
     */
    private suspend fun translateViaMyMemory(text: String, direction: TranslateDirection): String {
        val langPair = when (direction) {
            TranslateDirection.LaoToChinese -> "lo|zh-CN"
            TranslateDirection.ChineseToLao -> "zh-CN|lo"
        }
        return withContext(Dispatchers.IO) {
            try {
                val result = translateMyMemory(text, langPair)
                if (result.isNotBlank()) {
                    Log.d(TAG, "MyMemory 翻译成功: '$text' -> '$result'")
                    result
                } else {
                    Log.w(TAG, "MyMemory 返回空，尝试 ML Kit 兜底")
                    translateViaMlKit(text, direction)
                }
            } catch (e: Exception) {
                Log.e(TAG, "MyMemory 失败: ${e.message}，尝试 ML Kit 兜底")
                translateViaMlKit(text, direction)
            }
        }
    }

    /**
     * ML Kit 泰语翻译 (离线)
     */
    private suspend fun translateViaMlKit(text: String, direction: TranslateDirection): String {
        if (!mlKitReady || zhThTranslator == null) {
            throw IllegalStateException("ML Kit 离线翻译不可用")
        }

        return withContext(Dispatchers.IO) {
            try {
                val (src, tgt) = when (direction) {
                    TranslateDirection.LaoToChinese -> Pair("th", "zh")
                    TranslateDirection.ChineseToLao -> Pair("zh", "th")
                }

                // zh↔th 只需一个 translator（初始化时设为 zh→th）
                // 反向翻译需要另一个 translator 实例
                val result = if (src == "zh") {
                    zhThTranslator!!.translate(text).await()
                } else {
                    // th→zh: 需要创建反向 translator
                    val thZhOptions = TranslatorOptions.Builder()
                        .setSourceLanguage(TranslateLanguage.THAI)
                        .setTargetLanguage(TranslateLanguage.CHINESE)
                        .build()
                    val thZhTranslator = Translation.getClient(thZhOptions)
                    thZhTranslator.downloadModelIfNeeded().await()
                    val r = thZhTranslator.translate(text).await()
                    thZhTranslator.close()
                    r
                }

                Log.d(TAG, "ML Kit 翻译: '$text' -> '$result' ($src→$tgt)")
                result
            } catch (e: Exception) {
                Log.e(TAG, "ML Kit 翻译失败: ${e.message}")
                throw e
            }
        }
    }

    /**
     * MyMemory HTTP 请求
     */
    private fun translateMyMemory(text: String, langPair: String): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val urlStr = "https://api.mymemory.translated.net/get?q=$encoded&langpair=$langPair"

        Log.d(TAG, "MyMemory 请求: langPair=$langPair, text='$text'")

        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = 15000
        conn.readTimeout = 20000

        try {
            conn.connect()
            val code = conn.responseCode
            if (code != 200) {
                Log.e(TAG, "MyMemory API HTTP $code")
                return ""
            }
            val body = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use {
                it.readText()
            }
            Log.d(TAG, "MyMemory 响应: ${body.take(500)}")
            val json = JSONObject(body)
            val responseData = json.optJSONObject("responseData")
            val translated = responseData?.optString("translatedText", "") ?: ""
            val responseStatus = json.optInt("responseStatus", -1)
            Log.d(TAG, "MyMemory 翻译结果: '$translated', status=$responseStatus")
            return translated
        } catch (e: Exception) {
            Log.e(TAG, "MyMemory 请求异常: ${e.message}", e)
            return ""
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 检测网络是否可用
     */
    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 释放资源
     */
    fun release() {
        zhThTranslator?.close()
        zhThTranslator = null
        mlKitReady = false
        _isReady = false
    }
}
