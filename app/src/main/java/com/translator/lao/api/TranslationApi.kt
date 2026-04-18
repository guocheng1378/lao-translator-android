package com.translator.lao.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 翻译服务 - 使用 MyMemory 免费翻译 API（支持老挝语↔中文）
 * 无需 API Key，每日有免费额度
 */
object TranslationApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 翻译文本
     * @param text 要翻译的文本
     * @param from 源语言代码 (lo=老挝语, zh=中文)
     * @param to 目标语言代码
     * @return 翻译结果
     */
    suspend fun translate(text: String, from: String, to: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
                val url = "https://api.mymemory.translated.net/get?q=$encodedText&langpair=$from|$to"

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "LaoTranslator/1.0")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(body)
                    val responseData = json.getJSONObject("responseData")
                    val translatedText = responseData.getString("translatedText")
                    Result.success(translatedText)
                } else {
                    Result.failure(Exception("翻译失败: HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 老挝语 → 中文
     */
    suspend fun laoToChinese(text: String): Result<String> {
        return translate(text, "lo", "zh")
    }

    /**
     * 中文 → 老挝语
     */
    suspend fun chineseToLao(text: String): Result<String> {
        return translate(text, "zh", "lo")
    }
}
