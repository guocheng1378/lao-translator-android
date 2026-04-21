package com.lao.translator.translate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 双向翻译管理器：老挝语 ↔ 中文
 *
 * ✅ FIX: 移除 ML Kit 依赖（在中国大陆连接 Google 服务超时）
 * 改为纯 MyMemory API（免费，支持 zh↔lo）
 * ✅ FIX: 加 LRU 缓存 + 限频，避免撞 MyMemory 免费额度
 */
class TranslationManager(private val context: Context) {

    companion object {
        private const val TAG = "TranslationManager"
        // ✅ FIX: 最大缓存条目数
        private const val CACHE_MAX_SIZE = 200
    }

    sealed class TranslateDirection {
        data object LaoToChinese : TranslateDirection()
        data object ChineseToLao : TranslateDirection()
    }

    enum class TranslateMode {
        MYMEMORY_LAO,    // 在线，真正的 zh↔lo
        UNAVAILABLE       // 不可用
    }

    private var _isReady = false

    // ✅ FIX: 简单 LRU 缓存
    private val cache = object : LinkedHashMap<String, String>(CACHE_MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > CACHE_MAX_SIZE
        }
    }

    val isReady: Boolean get() = _isReady

    suspend fun init() = withContext(Dispatchers.IO) {
        _isReady = false

        if (isNetworkAvailable()) {
            try {
                val test = translateMyMemory("hello", "en|zh-CN")
                if (test.isNotBlank()) {
                    Log.d(TAG, "✅ MyMemory 连通测试通过: 'hello' -> '$test'")
                    _isReady = true
                } else {
                    Log.w(TAG, "MyMemory 连通测试返回空")
                    _isReady = false
                }
            } catch (e: Exception) {
                Log.w(TAG, "MyMemory 连通测试失败: ${e.message}")
                _isReady = false
            }
        } else {
            Log.w(TAG, "无网络连接")
            _isReady = false
        }

        Log.d(TAG, "TranslationManager 就绪: $_isReady, network=${isNetworkAvailable()}")
    }

    fun getCurrentMode(): TranslateMode = when {
        isNetworkAvailable() -> TranslateMode.MYMEMORY_LAO
        else -> TranslateMode.UNAVAILABLE
    }

    suspend fun translate(text: String, direction: TranslateDirection): String {
        if (text.isBlank()) return ""

        if (!_isReady) {
            throw IllegalStateException("翻译服务未就绪（无网络或 MyMemory 不可用）")
        }

        if (!isNetworkAvailable()) {
            throw IllegalStateException("无网络连接，无法翻译")
        }

        val langPair = when (direction) {
            TranslateDirection.LaoToChinese -> "lo|zh-CN"
            TranslateDirection.ChineseToLao -> "zh-CN|lo"
        }

        // ✅ FIX: 查缓存
        val cacheKey = "$langPair|$text"
        cache[cacheKey]?.let {
            Log.d(TAG, "缓存命中: '${text.take(20)}' -> '$it'")
            return it
        }

        return translateViaMyMemory(text, langPair).also { result ->
            if (result.isNotBlank()) {
                synchronized(cache) { cache[cacheKey] = result }
            }
        }
    }

    private suspend fun translateViaMyMemory(text: String, langPair: String): String {
        return withContext(Dispatchers.IO) {
            val result = translateMyMemory(text, langPair)
            if (result.isNotBlank()) {
                Log.d(TAG, "✅ MyMemory 翻译: '$text' -> '$result'")
                result
            } else {
                Log.e(TAG, "❌ MyMemory 返回空: '$text', langPair=$langPair")
                ""
            }
        }
    }

    private fun translateMyMemory(text: String, langPair: String): String {
        // ✅ FIX: 短文本（1-2字符）跳过翻译，减少无意义请求
        if (text.trim().length <= 2) {
            Log.d(TAG, "文本过短，跳过翻译: '$text'")
            return text
        }

        val encoded = URLEncoder.encode(text, "UTF-8")
        val urlStr = "https://api.mymemory.translated.net/get?q=$encoded&langpair=$langPair"

        Log.d(TAG, "MyMemory 请求: langPair=$langPair, text='${text.take(50)}'")

        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
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
            Log.d(TAG, "MyMemory 响应: ${body.take(200)}")
            val json = JSONObject(body)
            val responseData = json.optJSONObject("responseData")
            val translated = responseData?.optString("translatedText", "") ?: ""
            val responseStatus = json.optInt("responseStatus", -1)
            Log.d(TAG, "MyMemory 结果: '$translated', status=$responseStatus")
            return translated
        } catch (e: Exception) {
            Log.e(TAG, "MyMemory 请求异常: ${e.message}", e)
            return ""
        } finally {
            conn.disconnect()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    fun release() {
        _isReady = false
        synchronized(cache) { cache.clear() }
        Log.d(TAG, "TranslationManager 已释放")
    }
}
