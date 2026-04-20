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
    private var _isReady = false

    /** 翻译器是否已就绪（模型下载成功） */
    val isReady: Boolean get() = _isReady

    sealed class TranslateDirection {
        data object LaoToChinese : TranslateDirection()
        data object ChineseToLao : TranslateDirection()
    }

    /**
     * 初始化翻译器并下载离线模型
     * @throws IllegalStateException 模型下载失败时抛出
     */
    suspend fun init() = withContext(Dispatchers.IO) {
        _isReady = false

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

        try {
            laoToChinese?.downloadModelIfNeeded()?.await()
            chineseToLao?.downloadModelIfNeeded()?.await()
        } catch (e: Exception) {
            // 下载失败时清理资源，防止 native 层使用残缺模型
            release()
            throw IllegalStateException("翻译模型下载失败: ${e.message}", e)
        }

        _isReady = true
    }

    /**
     * 翻译文本
     */
    suspend fun translate(text: String, direction: TranslateDirection): String {
        if (text.isBlank()) return ""

        if (!_isReady) {
            throw IllegalStateException("翻译模型未就绪，请检查网络后重试")
        }

        val translator = when (direction) {
            TranslateDirection.LaoToChinese -> laoToChinese
            TranslateDirection.ChineseToLao -> chineseToLao
        } ?: throw IllegalStateException("翻译器未初始化")

        return withContext(Dispatchers.IO) {
            translator.translate(text).await()
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        _isReady = false
        try { laoToChinese?.close() } catch (_: Exception) {}
        try { chineseToLao?.close() } catch (_: Exception) {}
        laoToChinese = null
        chineseToLao = null
    }
}
