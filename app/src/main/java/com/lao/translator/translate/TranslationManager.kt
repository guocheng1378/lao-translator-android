package com.lao.translator.translate

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * 双向翻译管理器：老挝语 ↔ 中文
 * 使用 Google ML Kit，支持离线
 *
 * 注意：ML Kit 不支持老挝语(Lao)，
 * 当前使用泰语(Thai)作为近似替代
 */
class TranslationManager {

    private var laoToChinese: Translator? = null
    private var chineseToLao: Translator? = null

    sealed class TranslateDirection {
        data object LaoToChinese : TranslateDirection()
        data object ChineseToLao : TranslateDirection()
    }

    /**
     * 初始化翻译器并下载离线模型
     */
    suspend fun init() = withContext(Dispatchers.IO) {
        // ML Kit 不支持老挝语，使用泰语作为替代
        val lao2zhOptions = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.THAI)
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build()
        laoToChinese = Translation.getClient(lao2zhOptions)

        val zh2laoOptions = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE)
            .setTargetLanguage(TranslateLanguage.THAI)
            .build()
        chineseToLao = Translation.getClient(zh2laoOptions)

        laoToChinese?.downloadModelIfNeeded()?.await()
        chineseToLao?.downloadModelIfNeeded()?.await()
    }

    /**
     * 翻译文本
     */
    suspend fun translate(text: String, direction: TranslateDirection): String {
        if (text.isBlank()) return ""

        val translator = when (direction) {
            TranslateDirection.LaoToChinese -> laoToChinese
            TranslateDirection.ChineseToLao -> chineseToLao
        } ?: throw IllegalStateException("翻译器未初始化，请先调用 init()")

        return withContext(Dispatchers.IO) {
            translator.translate(text).await()
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        laoToChinese?.close()
        chineseToLao?.close()
        laoToChinese = null
        chineseToLao = null
    }
}
