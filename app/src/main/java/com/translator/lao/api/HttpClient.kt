package com.translator.lao.api

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 共享 OkHttp 客户端
 * 避免多个组件各自创建 OkHttpClient，减少资源浪费
 */
object HttpClient {

    /** 标准客户端：翻译、版本检查等 */
    val standard: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** TTS 客户端：下载音频流，readTimeout 更长 */
    val tts: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
}
