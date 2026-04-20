package com.lao.translator.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

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
    private var isProcessing = false  // 防止重入

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
        binding.tvStatus.text = "⚡ 正在加载模型..."

        lifecycleScope.launch {
            try {
                // 并行初始化
                val whisperJob = async(Dispatchers.IO) {
                    ensureModelExists("ggml-small.bin")
                    whisper.init("ggml-small.bin")
                }
                val translateJob = async(Dispatchers.IO) {
                    translator.init()
                }
                val ttsJob = async(Dispatchers.IO) {
                    tts.init()
                }

                // 等待所有完成
                whisperJob.await()
                translateJob.await()
                ttsJob.await()

                // 预热 Whisper（消除首次冷启动延迟）
                whisper.warmup()

                // 状态展示
                val ttsInfo = mutableListOf<String>()
                if (tts.isChineseAvailable()) ttsInfo.add("中文✅") else ttsInfo.add("中文❌")
                if (tts.isLaoAvailable()) ttsInfo.add("老挝语✅") else ttsInfo.add("老挝语❌")

                if (!tts.isLaoAvailable()) {
                    binding.tvStatus.text = "⚠️ 老挝语播报需安装 Google TTS 语言包"
                } else {
                    binding.tvStatus.text = "✅ 就绪 [${ttsInfo.joinToString(" ")}]，点击麦克风开始"
                }

            } catch (e: Exception) {
                binding.tvStatus.text = "❌ 初始化失败: ${e.message}"
            }
        }
    }

    private suspend fun ensureModelExists(modelName: String) = withContext(Dispatchers.IO) {
        val modelDir = File(filesDir, "models")
        val modelFile = File(modelDir, modelName)
        if (modelFile.exists()) return@withContext

        modelDir.mkdirs()
        try {
            assets.open("models/$modelName").use { input ->
                FileOutputStream(modelFile).use { output -> input.copyTo(output) }
            }
        } catch (_: Exception) {
            val ext = File(getExternalFilesDir(null), "models/$modelName")
            if (ext.exists()) ext.copyTo(modelFile)
            else throw IllegalStateException("请将模型放到: ${ext.absolutePath}")
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
     * 智能实时转译（优化版）
     * 优化点：
     * 1. VAD 跳过静音 — 不浪费识别时间
     * 2. 重叠采样 — 不丢词
     * 3. 并行处理 — 录音和识别并行
     * 4. 防重入 — 上一段没处理完不接新段
     */
    private fun startRealtimeTranslation() {
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

        // 2秒切片 + 0.5秒重叠 = 更快响应且不丢词
        recorder.startRecording(chunkDurationMs = 2000, overlapMs = 500) { audioChunk ->
            // 防重入：如果上一段还在处理，跳过
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

    /**
     * 处理单个音频片段（识别 + 翻译 + 播报）
     */
    private suspend fun processChunk(audioChunk: FloatArray) {
        binding.tvStatus.text = "🔄 识别中... (有效片段 #$chunkCount)"

        // 1. Whisper 识别（在后台线程）
        val result = withContext(Dispatchers.Default) {
            whisper.transcribeAuto(audioChunk)
        }

        if (result.text.isBlank()) {
            binding.tvStatus.text = "🎙️ 监听中... (有效 #$chunkCount, 跳过静音 #$skipCount)"
            return
        }

        lastSourceLang = result.language
        val sourceLangName = langName(result.language)
        val targetLangCode = targetLang(result.language)
        val targetLangName = langName(targetLangCode)

        // 更新 UI
        binding.tvDetectedLang.text = "🌐 $sourceLangName → $targetLangName"
        binding.tvSourceLabel.text = "原文 ($sourceLangName)"
        binding.tvTargetLabel.text = "译文 ($targetLangName)"

        sourceBuffer.append(result.text)
        binding.tvSourceText.text = sourceBuffer.toString()

        // 2. 翻译（在后台线程，和下一段录音并行）
        val dir = if (result.isLao)
            TranslationManager.TranslateDirection.LaoToChinese
        else
            TranslationManager.TranslateDirection.ChineseToLao

        val translated = withContext(Dispatchers.IO) {
            translator.translate(result.text.trim(), dir)
        }

        if (translated.isNotBlank()) {
            targetBuffer.append(translated)
            binding.tvTargetText.text = targetBuffer.toString()

            // 3. 播报
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
