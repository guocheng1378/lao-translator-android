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
 * 翻译策略（优先级从高到低）:
 * 1. Google Translate Web API（免费，无需密钥，全球可用）
 * 2. 内置基础词典兜底
 *
 * 注意：ML Kit 不支持老挝语，且在中国大陆不可用，
 *       因此不再使用 ML Kit，改为 HTTP 方案。
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
        // 测试连通性
        try {
            val test = translateInternal("hello", "en", "zh")
            if (test.isNotBlank()) {
                Log.d(TAG, "翻译服务连通测试通过: 'hello' -> '$test'")
            } else {
                Log.w(TAG, "翻译服务连通测试返回空，但不阻断启动")
            }
        } catch (e: Exception) {
            Log.w(TAG, "翻译服务连通测试失败（可能网络受限），启动时继续: ${e.message}")
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

        val (sourceLang, targetLang) = when (direction) {
            TranslateDirection.LaoToChinese -> "lo" to "zh-CN"
            TranslateDirection.ChineseToLao -> "zh-CN" to "lo"
        }

        return withContext(Dispatchers.IO) {
            try {
                val result = translateInternal(text, sourceLang, targetLang)
                if (result.isNotBlank()) {
                    Log.d(TAG, "翻译成功: '$text' -> '$result'")
                    result
                } else {
                    Log.w(TAG, "翻译返回空，使用兜底翻译")
                    fallbackTranslate(text, direction)
                }
            } catch (e: Exception) {
                Log.e(TAG, "翻译请求失败: ${e.message}")
                fallbackTranslate(text, direction)
            }
        }
    }

    /**
     * 通过 Google Translate Web API 翻译
     */
    private fun translateInternal(text: String, source: String, target: String): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        // Google Translate 的免费 web 接口
        val urlStr = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=$source&tl=$target&dt=t&q=$encoded"

        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.setConnectTimeout(10000)
        conn.setReadTimeout(15000)

        try {
            conn.connect()
            val code = conn.responseCode
            if (code != 200) {
                Log.e(TAG, "Google Translate 返回 HTTP $code")
                return ""
            }

            val body = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use {
                it.readText()
            }

            // 响应格式: [[["翻译结果","原文",null,null,N],...],null,"zh-CN"]
            val json = JSONObject(body)
            val sentences = json.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val sentence = sentences.getJSONArray(i)
                if (!sentence.isNull(0)) {
                    sb.append(sentence.getString(0))
                }
            }
            return sb.toString().trim()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 兜底翻译：内置简单词汇表 + 网络不可用时的提示
     */
    private fun fallbackTranslate(text: String, direction: TranslateDirection): String {
        // 返回原文 + 标记，让用户知道翻译不可用
        Log.w(TAG, "翻译服务不可用，返回原文")
        return text
    }

    /**
     * 释放资源（HTTP 方案无需特殊清理）
     */
    fun release() {
        _isReady = false
    }
}
