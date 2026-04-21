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
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"

        // ✅ 改动：超时从 25s 增大到 120s（现在是整段语音，低端机可能需要更久）
        private const val WHISPER_TIMEOUT_MS = 120_000L

        // 安全超时：单段语音处理的硬上限
        private const val MAX_PROCESSING_SECONDS = 150
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var whisper: WhisperManager
    private lateinit var translator: TranslationManager
    private lateinit var tts: TtsManager
    private lateinit var recorder: AudioRecorder

    private val sourceBuffer = StringBuilder()
    private val targetBuffer = StringBuilder()
    private var lastSourceLang = ""
    private var segmentCount = 0
    private var autoSpeak = true

    // ✅ 改动：用 AtomicBoolean 防止竞态
    private val isProcessing = AtomicBoolean(false)
    @Volatile private var processingStartTime = 0L
    private var modelsReady = false
    private var whisperReady = false

    private lateinit var logFile: File
    private val logTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun fileLog(msg: String) {
        try {
            val ts = logTimeFmt.format(Date())
            FileWriter(logFile, true).use { it.append("[$ts] $msg\n") }
        } catch (_: Exception) {}
    }

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

        logFile = File(filesDir, "app_debug.log")
        fileLog("=== App 启动 ===")

        whisper = WhisperManager(this)
        translator = TranslationManager(this)
        tts = TtsManager(this)
        recorder = AudioRecorder(this)

        requestNotificationPermission()
        setupUI()
        initModels()
    }

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

        binding.tvStatus.setOnLongClickListener {
            showLog()
            true
        }

        binding.btnTextTranslate.setOnClickListener {
            val input = binding.etTextInput.text.toString().trim()
            if (input.isBlank()) return@setOnClickListener

            binding.tvSourceText.text = input
            binding.tvStatus.text = "🔄 正在翻译..."
            fileLog("文字翻译: '$input'")

            lifecycleScope.launch {
                try {
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
                        fileLog("文字翻译成功: '$translated'")
                    } else {
                        binding.tvStatus.text = "⚠️ 翻译返回空"
                        fileLog("文字翻译返回空")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "文字翻译失败", e)
                    binding.tvStatus.text = "❌ 翻译失败: ${e.message}"
                    fileLog("文字翻译异常: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    private fun showLog() {
        lifecycleScope.launch {
            try {
                val log = withContext(Dispatchers.IO) {
                    if (logFile.exists()) {
                        val lines = logFile.readLines()
                        lines.takeLast(30).joinToString("\n")
                    } else {
                        "暂无日志"
                    }
                }
                withContext(Dispatchers.Main) {
                    binding.tvSourceText.text = "📋 最近日志:\n$log"
                    binding.tvStatus.text = "📋 日志已显示（长按状态可查看）"
                }
            } catch (e: Exception) {
                Log.e(TAG, "读取日志失败", e)
            }
        }
    }

    private fun initModels() {
        val availMem = Runtime.getRuntime().freeMemory() / 1024 / 1024
        val maxMem = Runtime.getRuntime().maxMemory() / 1024 / 1024
        binding.tvStatus.text = "⚡ 正在加载语音模型..."
        Log.d(TAG, "initModels nativeLoaded=${WhisperManager.nativeLoaded} mem=${availMem}/${maxMem}MB")
        fileLog("initModels: nativeLoaded=${WhisperManager.nativeLoaded} mem=${availMem}/${maxMem}MB")

        if (!WhisperManager.nativeLoaded) {
            binding.tvStatus.text = "❌ 语音库加载失败，设备不支持 arm64-v8a"
            Log.e(TAG, "whisper_jni 未加载！")
            fileLog("❌ whisper_jni 未加载！")
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
                        fileLog("模型文件就绪, 开始 nativeInit")

                        val t0 = System.currentTimeMillis()
                        whisper.init("ggml-tiny.bin")
                        val elapsed = System.currentTimeMillis() - t0
                        Log.d(TAG, "nativeInit 完成, 耗时=${elapsed}ms")
                        fileLog("nativeInit 完成, 耗时=${elapsed}ms, isInitialized=${whisper.isInitialized()}")

                        if (!whisper.isInitialized()) {
                            throw IllegalStateException("nativeInit 返回 false（${elapsed}ms）")
                        }
                    }
                    try { whisper.warmup() } catch (_: Exception) {}
                    whisperReady = true
                    modelsReady = true
                    binding.tvStatus.text = "🎙️ 语音就绪，点击麦克风开始"
                    Log.d(TAG, "✅ Whisper 加载完成")
                    fileLog("✅ Whisper 加载完成")
                }
            } catch (e: TimeoutCancellationException) {
                binding.tvStatus.text = "❌ 语音模型加载超时(60s)，请重启重试"
                Log.e(TAG, "Whisper 加载超时", e)
                fileLog("❌ Whisper 加载超时: ${e.message}")
                modelsReady = false
            } catch (e: Exception) {
                binding.tvStatus.text = "❌ 语音模型加载失败: ${e.message?.take(50)}"
                Log.e(TAG, "Whisper 加载失败", e)
                fileLog("❌ Whisper 加载失败: ${e.javaClass.simpleName}: ${e.message}")
                modelsReady = false
            }
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { translator.init() }
                Log.d(TAG, "✅ 翻译服务就绪 (MyMemory)")
                fileLog("✅ 翻译服务就绪 (MyMemory)")
                if (whisperReady) {
                    binding.tvStatus.text = "✅ 全部就绪，点击麦克风开始"
                }
            } catch (e: Exception) {
                Log.e(TAG, "⚠️ MyMemory 翻译服务不可用: ${e.message}")
                fileLog("⚠️ MyMemory 翻译服务不可用: ${e.javaClass.simpleName}: ${e.message}")
                if (whisperReady) {
                    binding.tvStatus.text = "🎙️ 语音就绪（翻译API暂不可用，将自动重试），点击麦克风开始"
                }
            }
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { tts.init() }
                Log.d(TAG, "✅ TTS 就绪: 中文=${tts.isChineseAvailable()}, 老挝语=${tts.isLaoAvailable()}")
                fileLog("✅ TTS 就绪: 中文=${tts.isChineseAvailable()}, 老挝语=${tts.isLaoAvailable()}")
            } catch (e: Exception) {
                Log.e(TAG, "TTS 加载失败", e)
                fileLog("⚠️ TTS 加载失败: ${e.message}")
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

    /**
     * ✅ 改版：不再传固定切片参数，录音器自己用 VAD 检测语音段
     */
    private fun startRealtimeTranslation() {
        if (!modelsReady) {
            Toast.makeText(this, "语音模型尚未加载完成，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d(TAG, "🎙️ startRealtimeTranslation")
        fileLog("🎙️ 开始录音翻译 (VAD 模式)")
        sourceBuffer.clear()
        targetBuffer.clear()
        lastSourceLang = ""
        segmentCount = 0
        isProcessing.set(false)
        processingStartTime = 0L

        binding.tvSourceText.text = ""
        binding.tvTargetText.text = ""
        binding.tvSourceLabel.text = "原文"
        binding.tvTargetLabel.text = "译文"
        binding.tvDetectedLang.text = "🎙️ 监听中..."
        setRecordingUI(true)

        TranslationService.start(this)

        // ✅ 改动：回调接收的是一整段语音（2-10秒），不是 2.5s 固定切片
        recorder.startRecording { utterance ->
            val now = System.currentTimeMillis()

            // 安全检查：如果处理卡死超过阈值，强制重置
            if (isProcessing.get()) {
                if (processingStartTime > 0 && (now - processingStartTime) > MAX_PROCESSING_SECONDS * 1000) {
                    Log.w(TAG, "⚠️ isProcessing 卡死超过 ${MAX_PROCESSING_SECONDS}s，强制重置！")
                    fileLog("⚠️ isProcessing 卡死，强制重置")
                    isProcessing.set(false)
                } else {
                    // 正在处理中，丢弃这段语音（避免积压）
                    Log.d(TAG, "⏳ 上一段还在处理中，跳过当前语音段 (${utterance.size} samples)")
                    return@startRecording
                }
            }

            segmentCount++
            val durationMs = utterance.size * 1000L / WhisperManager.SAMPLE_RATE
            Log.d(TAG, "📥 收到语音段 #$segmentCount (${utterance.size} samples, ${durationMs}ms)")

            lifecycleScope.launch {
                isProcessing.set(true)
                processingStartTime = System.currentTimeMillis()
                try {
                    processUtterance(utterance)
                } catch (e: Exception) {
                    Log.e(TAG, "processUtterance 异常: ${e.message}", e)
                    fileLog("❌ processUtterance 顶层异常: ${e.javaClass.simpleName}: ${e.message}")
                    runOnUiThread {
                        binding.tvStatus.text = "🎙️ 监听中... (处理异常: ${e.message?.take(30)})"
                    }
                } finally {
                    isProcessing.set(false)
                    processingStartTime = 0L
                }
            }
        }
    }

    /**
     * ✅ 改版：处理一整段语音（VAD 切出的完整句子）
     */
    private suspend fun processUtterance(utterance: FloatArray) {
        val durationMs = utterance.size * 1000L / WhisperManager.SAMPLE_RATE

        // 能量检查
        var energy = 0f
        for (s in utterance) energy += s * s
        energy /= utterance.size

        Log.d(TAG, "🔄 processUtterance #$segmentCount, ${utterance.size} samples (${durationMs}ms), energy=$energy")
        fileLog("🔄 processUtterance #$segmentCount, ${utterance.size} samples (${durationMs}ms), energy=$energy")

        // 太短或能量太低就跳过
        if (durationMs < 500) {
            Log.d(TAG, "⏭ 语音太短 (${durationMs}ms)，跳过")
            fileLog("⏭ 语音太短 ${durationMs}ms")
            return
        }
        if (energy < 0.000001f) {
            Log.w(TAG, "⚠️ 音频能量极低 ($energy)")
            fileLog("⚠️ 能量极低 $energy")
            return
        }

        withContext(Dispatchers.Main) {
            binding.tvStatus.text = "🔄 识别中... #$segmentCount (${durationMs / 1000.0}s)"
        }

        // ====== Whisper 转写 ======
        val result = try {
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(WHISPER_TIMEOUT_MS) {
                    whisper.transcribeAuto(utterance)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "❌ Whisper 转写超时 (${WHISPER_TIMEOUT_MS}ms)")
            fileLog("❌ Whisper 转写超时 ${WHISPER_TIMEOUT_MS}ms (${durationMs}ms 音频)")
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "🎙️ 监听中... (转写超时，已跳过)"
            }
            return
        } catch (e: Exception) {
            Log.e(TAG, "❌ Whisper 转写异常: ${e.message}", e)
            fileLog("❌ Whisper 转写异常: ${e.javaClass.simpleName}: ${e.message}")
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "🎙️ 监听中... (转写异常: ${e.message?.take(30)})"
            }
            return
        }

        if (result == null) {
            Log.e(TAG, "❌ Whisper 转写返回 null (超时)")
            fileLog("❌ Whisper 转写返回 null (超时 ${WHISPER_TIMEOUT_MS}ms)")
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "🎙️ 监听中... (转写超时)"
            }
            return
        }

        Log.d(TAG, "transcribeAuto: text='${result.text}', lang='${result.language}'")
        fileLog("✅ 转写结果: text='${result.text}', lang='${result.language}', isLao=${result.isLao}")

        if (result.text.isBlank()) {
            Log.d(TAG, "转写返回空文本")
            fileLog("⏭ 转写空文本 #$segmentCount")
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "🎙️ 监听中... (段 #$segmentCount, 空文本)"
            }
            return
        }

        lastSourceLang = result.language
        val sourceLangName = langName(result.language)
        val targetLangCode = targetLang(result.language)
        val targetLangName = langName(targetLangCode)

        // 原文立刻显示
        withContext(Dispatchers.Main) {
            binding.tvDetectedLang.text = "🌐 $sourceLangName → $targetLangName"
            binding.tvSourceLabel.text = "原文 ($sourceLangName)"
            binding.tvTargetLabel.text = "译文 ($targetLangName)"
            sourceBuffer.append(result.text)
            binding.tvSourceText.text = sourceBuffer.toString()
            binding.tvStatus.text = "✅ 已识别 #$segmentCount，翻译中..."
        }

        // 翻译异步执行
        val dir = if (result.isLao)
            TranslationManager.TranslateDirection.LaoToChinese
        else
            TranslationManager.TranslateDirection.ChineseToLao

        fileLog("🔄 开始翻译: dir=$dir, text='${result.text.take(50)}'")

        lifecycleScope.launch {
            try {
                val translated = withContext(Dispatchers.IO) {
                    translator.translate(result.text.trim(), dir)
                }
                if (!translated.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        targetBuffer.append(translated)
                        binding.tvTargetText.text = targetBuffer.toString()
                        binding.tvStatus.text = "✅ 已翻译 #$segmentCount [MyMemory]"
                        if (autoSpeak) {
                            tts.speak(translated, targetLangCode)
                        }
                    }
                    fileLog("✅ 翻译成功: '$translated'")
                } else {
                    withContext(Dispatchers.Main) {
                        binding.tvStatus.text = "⚠️ 翻译返回空，原文已保留"
                    }
                    fileLog("⚠️ 翻译返回空")
                }
            } catch (e: Exception) {
                Log.e(TAG, "翻译失败: ${e.message}", e)
                fileLog("❌ 翻译失败: ${e.javaClass.simpleName}: ${e.message}")
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "⚠️ 识别成功，翻译不可用: ${e.message?.take(30)}"
                }
            }
        }
    }

    private fun stopRecording() {
        recorder.stopRecording()
        isProcessing.set(false)
        processingStartTime = 0L
        setRecordingUI(false)
        binding.tvStatus.text = "⏹ 已停止，点击麦克风继续"
        Log.d(TAG, "⏹ 录音已停止")
        fileLog("⏹ 录音停止")

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
        TranslationService.stop(this)
    }
}
