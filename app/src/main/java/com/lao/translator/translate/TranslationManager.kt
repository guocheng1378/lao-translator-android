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
 * 双向翻译管理器：老挝语 ↔ 中文
 *
 * 主翻译: ML Kit（离线，模型下载后完全本地运行）
 * 兜底翻译: MyMemory API（在线，ML Kit 不可用时启用）
 */
class TranslationManager(private val context: Context) {

    companion object {
        private const val TAG = "TranslationManager"
        private const val CACHE_MAX_SIZE = 200
    }

    sealed class TranslateDirection {
        data object LaoToChinese : TranslateDirection()
        data object ChineseToLao : TranslateDirection()
    }

    enum class TranslateMode {
        MLKIT,       // ML Kit 离线
        MYMEMORY,    // MyMemory 在线
        UNAVAILABLE
    }

    // ML Kit translators
    private var mlKitLaoToZh: Translator? = null
    private var mlKitZhToLao: Translator? = null
    private var mlKitReady = false

    private var _myMemoryReady = false
    private var _initAttempted = false

    // LRU 缓存
    private val cache = object : LinkedHashMap<String, String>(CACHE_MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > CACHE_MAX_SIZE
        }
    }

    val isReady: Boolean get() = mlKitReady || _myMemoryReady

    /**
     * 初始化：尝试 ML Kit，失败则尝试 MyMemory
     */
    suspend fun init() = withContext(Dispatchers.IO) {
        _initAttempted = true

        // 尝试 ML Kit
        try {
            initMlKit()
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit 初始化失败: ${e.message}")
            mlKitReady = false
        }

        // ML Kit 不可用时尝试 MyMemory
        if (!mlKitReady) {
            try {
                initMyMemory()
            } catch (e: Exception) {
                Log.w(TAG, "MyMemory 初始化失败: ${e.message}")
                _myMemoryReady = false
            }
        }

        Log.d(TAG, "TranslationManager 就绪: mlKit=$mlKitReady, myMemory=$_myMemoryReady")
    }

    /**
     * 初始化 ML Kit 翻译器（离线）
     * 首次使用需联网下载语言模型，之后完全离线
     */
    private suspend fun initMlKit() = withContext(Dispatchers.IO) {
        Log.d(TAG, "初始化 ML Kit 翻译器...")

        val loLang = TranslateLanguage.LAO
        val zhLang = TranslateLanguage.CHINESE

        if (loLang == null || zhLang == null) {
            Log.e(TAG, "ML Kit 不支持老挝语或中文")
            return@withContext
        }

        // 创建翻译器
        val loToZhOptions = TranslatorOptions.Builder()
            .setSourceLanguage(loLang)
            .setTargetLanguage(zhLang)
            .build()
        mlKitLaoToZh = Translation.getClient(loToZhOptions)

        val zhToLoOptions = TranslatorOptions.Builder()
            .setSourceLanguage(zhLang)
            .setTargetLanguage(loLang)
            .build()
        mlKitZhToLao = Translation.getClient(zhToLoOptions)

        // 下载语言模型（首次需要网络，之后离线可用）
        Log.d(TAG, "下载 ML Kit 语言模型（首次约30MB）...")
        try {
            mlKitLaoToZh?.downloadModelIfNeeded()?.await()
            mlKitZhToLao?.downloadModelIfNeeded()?.await()
            mlKitReady = true
            Log.d(TAG, "✅ ML Kit 翻译器就绪（离线可用）")
        } catch (e: Exception) {
            Log.e(TAG, "❌ ML Kit 模型下载失败: ${e.message}")
            mlKitLaoToZh?.close()
            mlKitZhToLao?.close()
            mlKitLaoToZh = null
            mlKitZhToLao = null
            throw e
        }
    }

    /**
     * 初始化 MyMemory API（在线翻译兜底）
     */
    private suspend fun initMyMemory() = withContext(Dispatchers.IO) {
        _myMemoryReady = false

        if (!isNetworkAvailable()) {
            Log.w(TAG, "无网络连接，跳过 MyMemory")
            return@withContext
        }

        try {
            val test = translateMyMemory("hello", "en|zh-CN")
            if (test.isNotBlank()) {
                Log.d(TAG, "✅ MyMemory 连通测试通过")
                _myMemoryReady = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "MyMemory 连通测试失败: ${e.message}")
        }
    }

    fun getCurrentMode(): TranslateMode = when {
        mlKitReady -> TranslateMode.MLKIT
        _myMemoryReady -> TranslateMode.MYMEMORY
        else -> TranslateMode.UNAVAILABLE
    }

    /**
     * 翻译：优先 ML Kit，失败自动降级 MyMemory
     */
    suspend fun translate(text: String, direction: TranslateDirection): String {
        if (text.isBlank()) return ""

        val langPair = when (direction) {
            TranslateDirection.LaoToChinese -> "lo|zh-CN"
            TranslateDirection.ChineseToLao -> "zh-CN|lo"
        }

        // 查缓存
        val cacheKey = "$langPair|$text"
        cache[cacheKey]?.let {
            Log.d(TAG, "缓存命中: '${text.take(20)}' -> '$it'")
            return it
        }

        // 如果都没就绪，尝试重新初始化
        if (!mlKitReady && !_myMemoryReady) {
            Log.d(TAG, "翻译服务未就绪，尝试重新初始化...")
            try { init() } catch (_: Exception) {}
        }

        if (!mlKitReady && !_myMemoryReady) {
            throw IllegalStateException("翻译服务不可用（ML Kit 模型未下载，MyMemory 也不通）")
        }

        // 优先 ML Kit
        if (mlKitReady) {
            try {
                val result = translateMlKit(text, direction)
                if (result.isNotBlank()) {
                    synchronized(cache) { cache[cacheKey] = result }
                    return result
                }
            } catch (e: Exception) {
                Log.e(TAG, "ML Kit 翻译失败，降级到 MyMemory: ${e.message}")
            }
        }

        // 兜底 MyMemory
        if (_myMemoryReady && isNetworkAvailable()) {
            try {
                val result = translateMyMemory(text, langPair)
                if (result.isNotBlank()) {
                    synchronized(cache) { cache[cacheKey] = result }
                    return result
                }
            } catch (e: Exception) {
                Log.e(TAG, "MyMemory 翻译也失败: ${e.message}")
            }
        }

        throw IllegalStateException("所有翻译方式均失败")
    }

    /**
     * ML Kit 离线翻译
     */
    private suspend fun translateMlKit(text: String, direction: TranslateDirection): String {
        return withContext(Dispatchers.IO) {
            val translator = when (direction) {
                TranslateDirection.LaoToChinese -> mlKitLaoToZh
                TranslateDirection.ChineseToLao -> mlKitZhToLao
            } ?: throw IllegalStateException("ML Kit 翻译器未初始化")

            val result = translator.translate(text).await()
            Log.d(TAG, "✅ ML Kit 翻译: '$text' -> '$result'")
            result
        }
    }

    /**
     * MyMemory 在线翻译
     */
    private fun translateMyMemory(text: String, langPair: String): String {
        if (text.trim().length <= 2) return text

        val encoded = URLEncoder.encode(text, "UTF-8")
        val urlStr = "https://api.mymemory.translated.net/get?q=$encoded&langpair=$langPair"

        Log.d(TAG, "MyMemory 请求: langPair=$langPair, text='${text.take(50)}'")

        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
        conn.connectTimeout = 10000
        conn.readTimeout = 15000

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
            val json = JSONObject(body)
            val translated = json.optJSONObject("responseData")?.optString("translatedText", "") ?: ""
            Log.d(TAG, "✅ MyMemory 翻译: '$text' -> '$translated'")
            return translated
        } catch (e: Exception) {
            Log.e(TAG, "MyMemory 请求异常: ${e.message}")
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
        mlKitLaoToZh?.close()
        mlKitZhToLao?.close()
        mlKitLaoToZh = null
        mlKitZhToLao = null
        mlKitReady = false
        _myMemoryReady = false
        synchronized(cache) { cache.clear() }
        Log.d(TAG, "TranslationManager 已释放")
    }
}
