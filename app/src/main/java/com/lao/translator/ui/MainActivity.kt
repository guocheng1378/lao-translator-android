package com.lao.translator.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lao.translator.databinding.ActivityMainBinding
import com.lao.translator.service.TranslationService
import com.lao.translator.stt.AudioRecorder
import com.lao.translator.stt.WhisperManager
import com.lao.translator.translate.TranslationManager
import com.lao.translator.tts.TtsManager
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        // Whisper 转写超时（毫秒），原生代码无法被协程中断，需要自己兜底
        private const val WHISPER_TIMEOUT_MS = 25_000L
        // isProcessing 最大允许时间（秒），超过强制重置
        private const val MAX_PROCESSING_SECONDS = 30
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var whisper: WhisperManager
    private lateinit var translator: TranslationManager
    private lateinit var tts: TtsManager
    private lateinit var recorder: AudioRecorder

    private val sourceBuffer = StringBuilder()
    private val targetBuffer = StringBuilder()
    private var lastSourceLang = ""
    private var chunkCount = 0
    private var skipCount = 0
    private var autoSpeak = true
    @Volatile private var isProcessing = false
    @Volatile private var processingStartTime = 0L
    private var modelsReady = false
    private var whisperReady = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRealtimeTranslation()
        else Toast.makeText(this, "需要录音权限", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        whisper = WhisperManager(this)
        translator = TranslationManager(this)
        tts = TtsManager(this)
        recorder = AudioRecorder(this)

        // ✅ FIX: 请求通知权限（Android 13+）
        requestNotificationPermission()

        setupUI()
        initModels()
    }

    // ✅ FIX: Android 13+ 需要 POST_NOTIFICATIONS 权限
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }
    }

    private fun setupUI() {
        binding.switchTts.setOnCheckedChangeListener { _, isChecked ->
            autoSpeak = isChecked
            if (!isChecked) tts.stop()
        }

        binding.btnSpeakSource.setOnClickListener {
            val text = binding.tvSourceText.text.toString()
            if (text.isNotBlank() && lastSourceLang.isNotEmpty()) {
                tts.speak(text, lastSourceLang)
            }
        }

        binding.btnSpeakTarget.setOnClickListener {
            val text = binding.tvTargetText.text.toString()
            if (text.isNotBlank()) {
                val targetLang = if (lastSourceLang == "lo") "zh" else "lo"
                tts.speak(text, targetLang)
            }
        }

        binding.btnRecord.setOnClickListener {
            if (recorder.isRecording()) stopRecording()
            else checkPermissionAndStart()
        }

        // ✅ FIX: 文字翻译 — 自动检测语言方向，不再硬编码中文→老挝语
        binding.btnTextTranslate.setOnClickListener {
            val input = binding.etTextInput.text.toString().trim()
            if (input.isBlank()) return@setOnClickListener

            binding.tvSourceText.text = input
            binding.tvStatus.text = "🔄 正在翻译..."

            lifecycleScope.launch {
                try {
                    // ✅ FIX: 自动检测语言方向（老挝语包含 ກ-ໜ Unicode 范围）
                    val hasLaoChars = input.any { it in '຀'..'໿' }
                    val dir = if (hasLaoChars) {
                        TranslationManager.TranslateDirection.LaoToChinese
                    } else {
                        TranslationManager.TranslateDirection.ChineseToLao
                    }
                    val sourceName = if (hasLaoChars) "老挝语" else "中文"
                    val targetName = if (hasLaoChars) "中文" else "老挝语"

                    val translated = withContext(Dispatchers.IO) {
                        translator.translate(input, dir)
                    }
                    if (!translated.isNullOrBlank()) {
                        binding.tvTargetText.text = translated
                        binding.tvStatus.text = "✅ 翻译成功 [MyMemory]"
                        binding.tvDetectedLang.text = "🌐 $sourceName → $targetName"
                    } else {
                        binding.tvStatus.text = "⚠️ 翻译返回空"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "文字翻译失败", e)
                    binding.tvStatus.text = "❌ 翻译失败: ${e.message}"
                }
            }
        }
    }

    private fun initModels() {
        val availMem = Runtime.getRuntime().freeMemory() / 1024 / 1024
        val maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024
        binding.tvStatus.text = "⚡ 正在加载语音模型..."
        Log.d(TAG, "initModels nativeLoaded=${WhisperManager.nativeLoaded} mem=${availMem}/${maxMem}MB")

        if (!WhisperManager.nativeLoaded) {
            binding.tvStatus.text = "❌ 语音库加载失败，设备不支持 arm64-v8a"
            Log.e(TAG, "whisper_jni 未加载！")
            return
        }

        lifecycleScope.launch {
            try {
                withTimeout(60_000) {
                    withContext(Dispatchers.IO) {
                        ensureModelExists("ggml-tiny.bin")

                        withContext(Dispatchers.Main) {
                            binding.tvStatus.text = "⚡ 模型已下载，正在加载到内存..."
                        }
                        Log.d(TAG, "模型文件就绪, 开始 nativeInit")

                        val t0 = System.currentTimeMillis()
                        whisper.init("ggml-tiny.bin")
                        val elapsed = System.currentTimeMillis() - t0
                        Log.d(TAG, "nativeInit 完成, 耗时=${elapsed}ms")

                        if (!whisper.isInitialized()) {
                            throw IllegalStateException("nativeInit 返回 false（${elapsed}ms）")
                        }
                    }
                    try { whisper.warmup() } catch (_: Exception) {}
                    whisperReady = true
                    modelsReady = true
                    binding.tvStatus.text = "🎙️ 语音就绪，点击麦克风开始"
                    Log.d(TAG, "✅ Whisper 加载完成")
                }
            } catch (e: TimeoutCancellationException) {
                binding.tvStatus.text = "❌ 语音模型加载超时(60s)，请重启重试"
                Log.e(TAG, "Whisper 加载超时", e)
                modelsReady = false
            } catch (e: Exception) {
                binding.tvStatus.text = "❌ 语音模型加载失败: ${e.message?.take(50)}"
                Log.e(TAG, "Whisper 加载失败", e)
                modelsReady = false
            }
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { translator.init() }
                Log.d(TAG, "✅ 翻译服务就绪")
                if (whisperReady) {
                    binding.tvStatus.text = "✅ 全部就绪，点击麦克风开始"
                }
            } catch (e: Exception) {
                Log.e(TAG, "翻译服务加载失败", e)
                if (whisperReady) {
                    binding.tvStatus.text = "🎙️ 语音就绪（翻译不可用: ${e.message}），点击麦克风开始"
                }
            }
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { tts.init() }
                Log.d(TAG, "✅ TTS 就绪: 中文=${tts.isChineseAvailable()}, 老挝语=${tts.isLaoAvailable()}")
            } catch (e: Exception) {
                Log.e(TAG, "TTS 加载失败", e)
            }
        }
    }

    private suspend fun ensureModelExists(modelName: String) = withContext(Dispatchers.IO) {
        val modelDir = File(filesDir, "models")
        val modelFile = File(modelDir, modelName)
        if (modelFile.exists()) {
            Log.d(TAG, "模型已存在: ${modelFile.length()} bytes")
            return@withContext
        }

        modelDir.mkdirs()

        try {
            assets.open("models/$modelName").use { input ->
                FileOutputStream(modelFile).use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "从 assets 复制模型成功: ${modelFile.length()} bytes")
            return@withContext
        } catch (_: Exception) {}

        val ext = File(getExternalFilesDir(null), "models/$modelName")
        if (ext.exists()) {
            ext.copyTo(modelFile)
            Log.d(TAG, "从外部存储复制模型成功")
            return@withContext
        }

        withContext(Dispatchers.Main) {
            binding.tvStatus.text = "📥 正在下载模型 (75MB)，请等待..."
        }

        try {
            val url = URL("https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.connect()

            val totalBytes = conn.contentLength.toLong()
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(modelFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var lastProgress = -1
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val progress = (downloadedBytes * 100 / totalBytes).toInt()
                            if (progress != lastProgress && progress % 10 == 0) {
                                lastProgress = progress
                                withContext(Dispatchers.Main) {
                                    binding.tvStatus.text = "📥 下载模型中... ${progress}% (${downloadedBytes / 1024 / 1024}MB)"
                                }
                            }
                        }
                    }
                }
            }
            conn.disconnect()
            Log.d(TAG, "模型下载完成: ${modelFile.length()} bytes")
        } catch (e: Exception) {
            modelFile.delete()
            throw IllegalStateException("模型下载失败: ${e.message}\n请手动放到: ${ext.absolutePath}")
        }
    }

    private fun checkPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED -> startRealtimeTranslation()
            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRealtimeTranslation() {
        if (!modelsReady) {
            Toast.makeText(this, "语音模型尚未加载完成，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d(TAG, "🎙️ startRealtimeTranslation")
        sourceBuffer.clear()
        targetBuffer.clear()
        lastSourceLang = ""
        chunkCount = 0
        skipCount = 0
        isProcessing = false
        processingStartTime = 0L

        binding.tvSourceText.text = ""
        binding.tvTargetText.text = ""
        binding.tvSourceLabel.text = "原文"
        binding.tvTargetLabel.text = "译文"
        binding.tvDetectedLang.text = "🎙️ 监听中..."
        setRecordingUI(true)

        // ✅ FIX: 启动前台服务，防止进程被 HyperOS 杀掉
        TranslationService.start(this)

        recorder.startRecording(chunkDurationMs = 2000, overlapMs = 500) { audioChunk ->
            val now = System.currentTimeMillis()
            if (isProcessing) {
                if (processingStartTime > 0 && (now - processingStartTime) > MAX_PROCESSING_SECONDS * 1000) {
                    Log.w(TAG, "⚠️ isProcessing 卡死超过 ${MAX_PROCESSING_SECONDS}s，强制重置！")
                    isProcessing = false
                } else {
                    return@startRecording
                }
            }

            chunkCount++
            Log.d(TAG, "📥 收到 chunk #$chunkCount (size=${audioChunk.size})")

            lifecycleScope.launch {
                isProcessing = true
                processingStartTime = System.currentTimeMillis()
                try {
                    processChunk(audioChunk)
                } catch (e: Exception) {
                    Log.e(TAG, "processChunk 异常: ${e.message}", e)
                    runOnUiThread {
                        binding.tvStatus.text = "🎙️ 监听中... (处理异常: ${e.message?.take(30)})"
                    }
                } finally {
                    isProcessing = false
                    processingStartTime = 0L
                }
            }
        }
    }

    private suspend fun processChunk(audioChunk: FloatArray) {
        Log.d(TAG, "🔄 processChunk #$chunkCount, audioChunk.size=${audioChunk.size}")
        withContext(Dispatchers.Main) {
            binding.tvStatus.text = "🔄 识别中... (有效片段 #$chunkCount)"
        }

        val result = try {
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(WHISPER_TIMEOUT_MS) {
                    whisper.transcribeAuto(audioChunk)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "❌ Whisper 转写超时 (${WHISPER_TIMEOUT_MS}ms)，跳过此 chunk")
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "🎙️ 监听中... (转写超时，已跳过)"
            }
            return
        } catch (e: Exception) {
            Log.e(TAG, "❌ Whisper 转写异常: ${e.message}", e)
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "🎙️ 监听中... (转写异常: ${e.message?.take(30)})"
            }
            return
        }

        if (result == null) {
            Log.e(TAG, "❌ Whisper 转写返回 null（超时）")
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "🎙️ 监听中... (转写超时)"
            }
            return
        }

        Log.d(TAG, "transcribeAuto: text='${result.text}', lang='${result.language}', isLao=${result.isLao}")

        if (result.text.isBlank()) {
            skipCount++
            Log.w(TAG, "转写返回空文本 (有效 #$chunkCount, 跳过 #$skipCount)")
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "🎙️ 监听中... (有效 #$chunkCount, 跳过 #$skipCount)"
            }
            return
        }

        lastSourceLang = result.language
        val sourceLangName = langName(result.language)
        val targetLangCode = targetLang(result.language)
        val targetLangName = langName(targetLangCode)

        withContext(Dispatchers.Main) {
            binding.tvDetectedLang.text = "🌐 $sourceLangName → $targetLangName"
            binding.tvSourceLabel.text = "原文 ($sourceLangName)"
            binding.tvTargetLabel.text = "译文 ($targetLangName)"
            sourceBuffer.append(result.text)
            binding.tvSourceText.text = sourceBuffer.toString()
        }

        val dir = if (result.isLao)
            TranslationManager.TranslateDirection.LaoToChinese
        else
            TranslationManager.TranslateDirection.ChineseToLao

        val translated = try {
            withContext(Dispatchers.IO) {
                translator.translate(result.text.trim(), dir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "翻译失败: ${e.message}", e)
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "⚠️ 识别到: \"${result.text.take(20)}\" 但翻译失败"
            }
            return
        }

        if (!translated.isNullOrBlank()) {
            withContext(Dispatchers.Main) {
                targetBuffer.append(translated)
                binding.tvTargetText.text = targetBuffer.toString()
                binding.tvStatus.text = "✅ 已翻译 #$chunkCount [MyMemory]"

                if (autoSpeak) {
                    tts.speak(translated, targetLangCode)
                }
            }
        } else {
            Log.e(TAG, "翻译返回空! text='${result.text}'")
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "⚠️ 识别到: \"${result.text.take(20)}\" 但翻译返回空"
            }
        }
    }

    private fun stopRecording() {
        recorder.stopRecording()
        isProcessing = false
        processingStartTime = 0L
        setRecordingUI(false)
        binding.tvStatus.text = "⏹ 已停止，点击麦克风继续"
        Log.d(TAG, "⏹ 录音已停止")

        // ✅ FIX: 停止前台服务
        TranslationService.stop(this)
    }

    private fun setRecordingUI(recording: Boolean) {
        if (recording) {
            binding.btnRecord.setImageResource(android.R.drawable.ic_media_pause)
            binding.btnRecord.backgroundTintList =
                ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
        } else {
            binding.btnRecord.setImageResource(android.R.drawable.ic_btn_speak_now)
            binding.btnRecord.backgroundTintList =
                ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
        }
    }

    private fun langName(code: String) = when (code) {
        "lo" -> "老挝语"
        "zh" -> "中文"
        "en" -> "英语"
        else -> code
    }

    private fun targetLang(sourceLang: String) = when (sourceLang) {
        "lo" -> "zh"
        else -> "lo"
    }

    override fun onDestroy() {
        super.onDestroy()
        recorder.stopRecording()
        whisper.release()
        translator.release()
        tts.release()
        // ✅ FIX: 确保 Activity 销毁时也停止前台服务
        TranslationService.stop(this)
    }
}
