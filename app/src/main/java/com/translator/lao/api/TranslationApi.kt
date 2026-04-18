package com.translator.lao.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 翻译服务 - 支持 Bing / Google / Auto 三种翻译源
 * Auto 模式依次尝试 Google → Bing → LibreTranslate
 */
object TranslationApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    enum class Source { AUTO, GOOGLE, BING }

    /**
     * 翻译文本
     * @param text 要翻译的文本
     * @param from 源语言代码 (lo=老挝语, zh=中文)
     * @param to 目标语言代码
     * @param source 翻译源
     */
    suspend fun translate(
        text: String,
        from: String,
        to: String,
        source: Source = Source.AUTO
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                when (source) {
                    Source.GOOGLE -> translateGoogle(text, from, to)
                    Source.BING -> translateBing(text, from, to)
                    Source.AUTO -> translateAuto(text, from, to)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Auto 模式：依次尝试 Google → Bing → LibreTranslate
     */
    private fun translateAuto(text: String, from: String, to: String): Result<String> {
        // 尝试 Google
        val google = translateGoogleSync(text, from, to)
        if (google.isSuccess) return google

        // 尝试 Bing
        val bing = translateBingSync(text, from, to)
        if (bing.isSuccess) return bing

        // 尝试 LibreTranslate
        val libre = translateLibreSync(text, from, to)
        if (libre.isSuccess) return libre

        return Result.failure(Exception("所有翻译源均失败"))
    }

    // ========== Google 翻译 ==========

    /**
     * Google Translate（免费非官方接口）
     */
    private fun translateGoogle(text: String, from: String, to: String): Result<String> {
        return translateGoogleSync(text, from, to)
    }

    private fun translateGoogleSync(text: String, from: String, to: String): Result<String> {
        return try {
            val sl = if (from == "lo") "lo" else "zh-CN"
            val tl = if (to == "lo") "lo" else "zh-CN"
            val encoded = java.net.URLEncoder.encode(text, "UTF-8")

            val url = "https://translate.googleapis.com/translate_a/single?" +
                    "client=gtx&sl=$sl&tl=$tl&dt=t&q=$encoded"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful && body.isNotEmpty()) {
                // 响应格式: [[["翻译结果","原文",null,null,N],...],null,"zh-CN"]
                val json = JSONArray(body)
                val sentences = json.getJSONArray(0)
                val result = StringBuilder()
                for (i in 0 until sentences.length()) {
                    val sentence = sentences.getJSONArray(i)
                    result.append(sentence.getString(0))
                }
                Result.success(result.toString())
            } else {
                Result.failure(Exception("Google翻译失败: HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Google翻译异常: ${e.message}"))
        }
    }

    // ========== Bing 翻译 ==========

    /**
     * Bing/Microsoft Translator（免费非官方接口）
     */
    private fun translateBing(text: String, from: String, to: String): Result<String> {
        return translateBingSync(text, from, to)
    }

    private fun translateBingSync(text: String, from: String, to: String): Result<String> {
        return try {
            val fromCode = if (from == "lo") "lo" else "zh-Hans"
            val toCode = if (to == "lo") "lo" else "zh-Hans"

            // 使用 Bing Translator 的免费 API
            val url = "https://api.cognitive.microsofttranslator.com/translate" +
                    "?api-version=3.0&from=$fromCode&to=$toCode"

            val jsonBody = JSONArray().put(JSONObject().put("Text", text))

            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .post(jsonBody.toString().toRequestBody(JSON_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful && body.isNotEmpty()) {
                val json = JSONArray(body)
                val translations = json.getJSONObject(0)
                    .getJSONArray("translations")
                val result = translations.getJSONObject(0).getString("text")
                Result.success(result)
            } else {
                Result.failure(Exception("Bing翻译失败: HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Bing翻译异常: ${e.message}"))
        }
    }

    // ========== LibreTranslate 备用 ==========

    private fun translateLibreSync(text: String, from: String, to: String): Result<String> {
        return try {
            val fromCode = if (from == "lo") "lo" else "zh"
            val toCode = if (to == "lo") "lo" else "zh"

            val jsonBody = JSONObject()
                .put("q", text)
                .put("source", fromCode)
                .put("target", toCode)

            val request = Request.Builder()
                .url("https://libretranslate.com/translate")
                .header("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody(JSON_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful && body.isNotEmpty()) {
                val json = JSONObject(body)
                val result = json.getString("translatedText")
                Result.success(result)
            } else {
                Result.failure(Exception("LibreTranslate失败: HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("LibreTranslate异常: ${e.message}"))
        }
    }

    // ========== 快捷方法 ==========

    suspend fun laoToChinese(text: String, source: Source = Source.AUTO): Result<String> {
        return translate(text, "lo", "zh", source)
    }

    suspend fun chineseToLao(text: String, source: Source = Source.AUTO): Result<String> {
        return translate(text, "zh", "lo", source)
    }
}
