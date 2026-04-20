package com.lao.translator.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lao.translator.databinding.ActivityMainBinding
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
    private var isProcessing = false
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
        translator = TranslationManager()
        tts = TtsManager(this)
        recorder = AudioRecorder()

        setupUI()
        initModels()
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
    }

    private fun initModels() {
        binding.tvStatus.text = "⚡ 正在加载语音模型..."
        Log.d(TAG, "initModels 开始, nativeLoaded=${WhisperManager.nativeLoaded}, " +
                "可用内存=${Runtime.getRuntime().freeMemory() / 1024 / 1024}MB, " +
                "最大内存=${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB")

        // Whisper 必须先加载完才能用
        lifecycleScope.launch {
            try {
                withTimeout(120_000) { // 2 分钟超时
                    withContext(Dispatchers.IO) {
                        ensureModelExists("ggml-small.bin")
                        Log.d(TAG, "模型文件就绪, 开始 nativeInit")
                        val t0 = System.currentTimeMillis()
                        whisper.init("ggml-small.bin")
                        Log.d(TAG, "nativeInit 完成, 耗时=${System.currentTimeMillis() - t0}ms")
                    }
                    // 跳过 warmup — 它会导致小米设备上的 whisper 崩溃
                    // warmup 只是预热 JIT，不影响实际使用
                    whisperReady = true
                    modelsReady = true
                    binding.tvStatus.text = "🎙️ 语音就绪，翻译模型加载中..."
                    Log.d(TAG, "Whisper 加载完成")
                }
            } catch (e: TimeoutCancellationException) {
                binding.tvStatus.text = "❌ 语音模型加载超时（2分钟），请重启应用"
                Log.e(TAG, "Whisper 加载超时", e)
                modelsReady = false
                return@launch
            } catch (e: Exception) {
                binding.tvStatus.text = "❌ 语音模型加载失败: ${e.message}"
                Log.e(TAG, "Whisper 加载失败", e)
                modelsReady = false
                return@launch
            }
        }

        // 翻译和 TTS 后台加载，不阻塞主流程
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { translator.init() }
                Log.d(TAG, "翻译模型加载完成")
                // 翻译就绪后更新状态（仅当 Whisper 也已就绪）
                if (whisperReady) {
                    binding.tvStatus.text = "✅ 全部就绪，点击麦克风开始"
                }
            } catch (e: Exception) {
                Log.e(TAG, "翻译模型加载失败", e)
                if (whisperReady) {
                    binding.tvStatus.text = "🎙️ 语音就绪（翻译不可用: ${e.message}），点击麦克风开始"
                }
            }
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { tts.init() }
                Log.d(TAG, "TTS 加载完成")
            } catch (e: Exception) {
                Log.e(TAG, "TTS 加载失败", e)
            }
        }
    }

    private suspend fun ensureModelExists(modelName: String) = withContext(Dispatchers.IO) {
        val modelDir = File(filesDir, "models")
        val modelFile = File(modelDir, modelName)
        if (modelFile.exists()) return@withContext

        modelDir.mkdirs()

        // 1. Try assets
        try {
            assets.open("models/$modelName").use { input ->
                FileOutputStream(modelFile).use { output -> input.copyTo(output) }
            }
            return@withContext
        } catch (_: Exception) {}

        // 2. Try external storage
        val ext = File(getExternalFilesDir(null), "models/$modelName")
        if (ext.exists()) {
            ext.copyTo(modelFile)
            return@withContext
        }

        // 3. Download from HuggingFace
        withContext(Dispatchers.Main) {
            binding.tvStatus.text = "📥 正在下载模型 (460MB)，请等待..."
        }

        try {
            val url = URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin")
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
        Log.d(TAG, "startRealtimeTranslation 调用")
        sourceBuffer.clear()
        targetBuffer.clear()
        lastSourceLang = ""
        chunkCount = 0
        skipCount = 0
        isProcessing = false

        binding.tvSourceText.text = ""
        binding.tvTargetText.text = ""
        binding.tvSourceLabel.text = "原文"
        binding.tvTargetLabel.text = "译文"
        binding.tvDetectedLang.text = "🎙️ 监听中..."
        setRecordingUI(true)

        recorder.startRecording(chunkDurationMs = 2000, overlapMs = 500) { audioChunk ->
            if (isProcessing) return@startRecording

            chunkCount++

            lifecycleScope.launch {
                isProcessing = true
                try {
                    processChunk(audioChunk)
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    private suspend fun processChunk(audioChunk: FloatArray) {
        binding.tvStatus.text = "🔄 识别中... (有效片段 #$chunkCount)"
        Log.d(TAG, "processChunk #$chunkCount, audioChunk.size=${audioChunk.size}")

        val result = withContext(Dispatchers.Default) {
            whisper.transcribeAuto(audioChunk)
        }

        Log.d(TAG, "transcribeAuto 返回: text='${result.text}', lang='${result.language}', isLao=${result.isLao}")

        if (result.text.isBlank()) {
            Log.w(TAG, "transcribeAuto 返回空文本! chunk #$chunkCount")
            skipCount++
            binding.tvStatus.text = "🎙️ 监听中... (有效 #$chunkCount, 跳过 #$skipCount)"
            return
        }

        lastSourceLang = result.language
        val sourceLangName = langName(result.language)
        val targetLangCode = targetLang(result.language)
        val targetLangName = langName(targetLangCode)

        binding.tvDetectedLang.text = "🌐 $sourceLangName → $targetLangName"
        binding.tvSourceLabel.text = "原文 ($sourceLangName)"
        binding.tvTargetLabel.text = "译文 ($targetLangName)"

        sourceBuffer.append(result.text)
        binding.tvSourceText.text = sourceBuffer.toString()

        val dir = if (result.isLao)
            TranslationManager.TranslateDirection.LaoToChinese
        else
            TranslationManager.TranslateDirection.ChineseToLao

        val translated = try {
            withContext(Dispatchers.IO) {
                translator.translate(result.text.trim(), dir)
            }
        } catch (e: Exception) {
            Log.e(TAG, "翻译失败", e)
            null
        }

        if (!translated.isNullOrBlank()) {
            targetBuffer.append(translated)
            binding.tvTargetText.text = targetBuffer.toString()

            if (autoSpeak) {
                withContext(Dispatchers.Main) {
                    tts.speak(translated, targetLangCode)
                }
            }
        }

        binding.tvStatus.text = "✅ 已翻译 (有效 #$chunkCount)"
    }

    private fun stopRecording() {
        recorder.stopRecording()
        setRecordingUI(false)
        binding.tvStatus.text = "⏹ 已停止，点击麦克风继续"
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
    }
}
