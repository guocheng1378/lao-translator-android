package com.lao.translator.translate

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 双向翻译管理器：老挝语 ↔ 中文
 * 使用 Google ML Kit，支持离线
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
     * 首次需要联网下载，之后可离线使用
     */
    suspend fun init() = withContext(Dispatchers.IO) {
        // 老挝语 → 中文
        val lao2zhOptions = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.LAO)
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build()
        laoToChinese = Translation.getClient(lao2zhOptions)

        // 中文 → 老挝语
        val zh2laoOptions = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.CHINESE)
            .setTargetLanguage(TranslateLanguage.LAO)
            .build()
        chineseToLao = Translation.getClient(zh2laoOptions)

        // 下载语言模型（首次需要，之后离线可用）
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
