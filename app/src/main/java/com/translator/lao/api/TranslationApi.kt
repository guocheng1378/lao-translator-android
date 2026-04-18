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
 * 翻译服务 - 支持 5 种翻译源：Google / Bing / MyMemory / LibreTranslate / Auto
 */
object TranslationApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    enum class Source(val label: String) {
        AUTO("Auto"),
        GOOGLE("Google"),
        BING("Bing"),
        MYMEMORY("MyMemory"),
        LIBRE("LibreTranslate")
    }

    suspend fun translate(
        text: String, from: String, to: String, source: Source = Source.AUTO
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            when (source) {
                Source.GOOGLE -> translateGoogle(text, from, to)
                Source.BING -> translateBing(text, from, to)
                Source.MYMEMORY -> translateMyMemory(text, from, to)
                Source.LIBRE -> translateLibre(text, from, to)
                Source.AUTO -> translateAuto(text, from, to)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Auto: Google → Bing → MyMemory → Libre */
    private fun translateAuto(text: String, from: String, to: String): Result<String> {
        translateGoogle(text, from, to).onSuccess { return Result.success(it) }
        translateBing(text, from, to).onSuccess { return Result.success(it) }
        translateMyMemory(text, from, to).onSuccess { return Result.success(it) }
        translateLibre(text, from, to).onSuccess { return Result.success(it) }
        return Result.failure(Exception("所有翻译源均失败"))
    }

    // ========== Google ==========
    private fun translateGoogle(text: String, from: String, to: String): Result<String> = try {
        val sl = if (from == "lo") "lo" else "zh-CN"
        val tl = if (to == "lo") "lo" else "zh-CN"
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=${java.net.URLEncoder.encode(text, "UTF-8")}"
        val resp = client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").get().build()).execute()
        val body = resp.body?.string() ?: ""
        if (resp.isSuccessful && body.isNotEmpty()) {
            val arr = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) sb.append(arr.getJSONArray(i).getString(0))
            Result.success(sb.toString())
        } else Result.failure(Exception("Google: HTTP ${resp.code}"))
    } catch (e: Exception) { Result.failure(Exception("Google: ${e.message}")) }

    // ========== Bing ==========
    private fun translateBing(text: String, from: String, to: String): Result<String> = try {
        val fromCode = if (from == "lo") "lo" else "zh-Hans"
        val toCode = if (to == "lo") "lo" else "zh-Hans"
        val body = JSONArray().put(JSONObject().put("Text", text)).toString()
        val resp = client.newCall(Request.Builder()
            .url("https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&from=$fromCode&to=$toCode")
            .header("Content-Type", "application/json").post(body.toRequestBody(JSON_TYPE)).build()).execute()
        val rb = resp.body?.string() ?: ""
        if (resp.isSuccessful && rb.isNotEmpty()) {
            Result.success(JSONArray(rb).getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text"))
        } else Result.failure(Exception("Bing: HTTP ${resp.code}"))
    } catch (e: Exception) { Result.failure(Exception("Bing: ${e.message}")) }

    // ========== MyMemory ==========
    private fun translateMyMemory(text: String, from: String, to: String): Result<String> = try {
        val url = "https://api.mymemory.translated.net/get?q=${java.net.URLEncoder.encode(text, "UTF-8")}&langpair=$from|$to"
        val resp = client.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").get().build()).execute()
        val rb = resp.body?.string() ?: ""
        if (resp.isSuccessful && rb.isNotEmpty()) {
            val json = JSONObject(rb)
            if (json.getInt("responseStatus") == 200)
                Result.success(json.getJSONObject("responseData").getString("translatedText"))
            else Result.failure(Exception("MyMemory: ${json.optString("responseDetails")}"))
        } else Result.failure(Exception("MyMemory: HTTP ${resp.code}"))
    } catch (e: Exception) { Result.failure(Exception("MyMemory: ${e.message}")) }

    // ========== LibreTranslate ==========
    private fun translateLibre(text: String, from: String, to: String): Result<String> = try {
        val body = JSONObject().put("q", text).put("source", if (from == "lo") "lo" else "zh").put("target", if (to == "lo") "lo" else "zh").toString()
        val resp = client.newCall(Request.Builder().url("https://libretranslate.com/translate")
            .header("Content-Type", "application/json").post(body.toRequestBody(JSON_TYPE)).build()).execute()
        val rb = resp.body?.string() ?: ""
        if (resp.isSuccessful && rb.isNotEmpty()) Result.success(JSONObject(rb).getString("translatedText"))
        else Result.failure(Exception("Libre: HTTP ${resp.code}"))
    } catch (e: Exception) { Result.failure(Exception("Libre: ${e.message}")) }

    // ========== 快捷方法 ==========
    suspend fun laoToChinese(text: String, source: Source = Source.AUTO) = translate(text, "lo", "zh", source)
    suspend fun chineseToLao(text: String, source: Source = Source.AUTO) = translate(text, "zh", "lo", source)
}
