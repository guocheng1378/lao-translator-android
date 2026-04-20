package com.lao.translator.translate

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 双向翻译管理器：老挝语 ↔ 中文
 *
 * 使用 MyMemory Translation API（免费，无需密钥，中国大陆可访问）
 * 支持老挝语(lo) ↔ 中文(zh-CN)
 */
class TranslationManager {

    companion object {
        private const val TAG = "TranslationManager"
    }

    private var _isReady = false

    /** 翻译器是否已就绪 */
    val isReady: Boolean get() = _isReady

    sealed class TranslateDirection {
        data object LaoToChinese : TranslateDirection()
        data object ChineseToLao : TranslateDirection()
    }

    /**
     * 初始化（HTTP 方案无需预下载模型）
     */
    suspend fun init() = withContext(Dispatchers.IO) {
        _isReady = false
        try {
            val test = translateInternal("hello", "en|zh-CN")
            if (test.isNotBlank()) {
                Log.d(TAG, "翻译服务连通测试通过: 'hello' -> '$test'")
            } else {
                Log.w(TAG, "翻译服务连通测试返回空，但不阻断启动")
            }
        } catch (e: Exception) {
            Log.w(TAG, "翻译服务连通测试失败，启动时继续: ${e.message}")
        }
        _isReady = true
        Log.d(TAG, "TranslationManager 就绪")
    }

    /**
     * 翻译文本
     */
    suspend fun translate(text: String, direction: TranslateDirection): String {
        if (text.isBlank()) return ""

        if (!_isReady) {
            throw IllegalStateException("翻译服务未就绪，请检查网络后重试")
        }

        val langPair = when (direction) {
            TranslateDirection.LaoToChinese -> "lo|zh-CN"
            TranslateDirection.ChineseToLao -> "zh-CN|lo"
        }

        return withContext(Dispatchers.IO) {
            try {
                val result = translateInternal(text, langPair)
                if (result.isNotBlank()) {
                    Log.d(TAG, "翻译成功: '$text' -> '$result'")
                    result
                } else {
                    Log.w(TAG, "翻译返回空")
                    text
                }
            } catch (e: Exception) {
                Log.e(TAG, "翻译请求失败: ${e.message}")
                text
            }
        }
    }

    /**
     * 通过 MyMemory API 翻译
     * 免费限额: 5000 词/天，无需注册
     */
    private fun translateInternal(text: String, langPair: String): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val urlStr = "https://api.mymemory.translated.net/get?q=$encoded&langpair=$langPair"

        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.setConnectTimeout(15000)
        conn.setReadTimeout(20000)

        try {
            conn.connect()
            val code = conn.responseCode
            if (code != 200) {
                Log.e(TAG, "MyMemory API 返回 HTTP $code")
                return ""
            }

            val body = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use {
                it.readText()
            }

            // 响应格式: {"responseData":{"translatedText":"翻译结果","match":0.85}, ...}
            val json = JSONObject(body)
            val responseData = json.optJSONObject("responseData")
            return responseData?.optString("translatedText", "") ?: ""
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 释放资源（HTTP 方案无需特殊清理）
     */
    fun release() {
        _isReady = false
    }
}
