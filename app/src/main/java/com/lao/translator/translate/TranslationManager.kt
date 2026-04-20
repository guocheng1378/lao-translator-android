package com.lao.translator.translate

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 双向翻译管理器：老挝语 ↔ 中文
 * 使用 MyMemory API（免费，无需密钥，每日 5000 字符）
 * 由于 ML Kit 不支持老挝语且国内无法下载模型，改用在线 API
 */
class TranslationManager {

    companion object {
        private const val TAG = "TranslationManager"
        // MyMemory 免费 API，配合 email 可提升配额到 50000 字符/天
        private const val API_URL = "https://api.mymemory.translated.net/get"
        // 如需更高配额，替换为你的邮箱
        private const val EMAIL = ""
    }

    private var _isReady = false

    val isReady: Boolean get() = _isReady

    sealed class TranslateDirection {
        data object LaoToChinese : TranslateDirection()
        data object ChineseToLao : TranslateDirection()
    }

    /**
     * 初始化（测试 API 连通性）
     */
    suspend fun init() = withContext(Dispatchers.IO) {
        _isReady = false
        try {
            // 测试 API 是否可达
            val test = translateInternal("ສະບາຍດີ", "lo", "zh")
            Log.d(TAG, "API 测试通过: '$test'")
            _isReady = true
        } catch (e: Exception) {
            Log.e(TAG, "翻译 API 不可用: ${e.message}")
            throw IllegalStateException("翻译服务不可用，请检查网络: ${e.message}")
        }
    }

    /**
     * 翻译文本
     */
    suspend fun translate(text: String, direction: TranslateDirection): String {
        if (text.isBlank()) return ""
        if (!_isReady) throw IllegalStateException("翻译服务未就绪")

        val (from, to) = when (direction) {
            TranslateDirection.LaoToChinese -> "lo" to "zh"
            TranslateDirection.ChineseToLao -> "zh" to "lo"
        }

        return translateInternal(text, from, to)
    }

    private suspend fun translateInternal(text: String, from: String, to: String): String =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(text, "UTF-8")
            var urlStr = "$API_URL?q=$encoded&langpair=$from|$to"
            if (EMAIL.isNotEmpty()) urlStr += "&de=$EMAIL"

            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "LaoTranslator/1.0")

            try {
                conn.inputStream.bufferedReader().use { reader ->
                    val json = JSONObject(reader.readText())
                    val responseStatus = json.optInt("responseStatus", 0)

                    if (responseStatus == 200) {
                        val translated = json.getJSONObject("responseData").getString("translatedText")
                        Log.d(TAG, "翻译: '$text' → '$translated' ($from→$to)")
                        translated
                    } else {
                        val msg = json.optString("responseDetails", "未知错误")
                        Log.e(TAG, "翻译 API 错误 ($responseStatus): $msg")
                        throw IllegalStateException("翻译失败: $msg")
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

    fun release() {
        _isReady = false
    }
}
