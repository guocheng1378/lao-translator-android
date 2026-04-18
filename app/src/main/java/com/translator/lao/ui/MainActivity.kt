package com.translator.lao.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.translator.lao.R
import com.translator.lao.api.TranslationApi
import com.translator.lao.data.HistoryStore
import com.translator.lao.databinding.ActivityMainBinding
import com.translator.lao.speech.SpeechManager
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var speechManager: SpeechManager
    private lateinit var historyStore: HistoryStore

    private var isLaoToChinese = true // true=老挝→中, false=中→老挝
    private var isListening = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceInput()
        } else {
            showToast("需要麦克风权限才能使用语音输入")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        speechManager = SpeechManager(this)
        historyStore = HistoryStore(this)

        // 恢复上次的翻译方向
        val (from, to) = historyStore.getLastDirection()
        isLaoToChinese = from == "lo"

        initUI()
        initTts()
    }

    private fun initUI() {
        updateDirectionUI()

        // 翻译按钮
        binding.btnTranslate.setOnClickListener {
            val text = binding.etSource.text.toString().trim()
            if (text.isNotEmpty()) {
                performTranslation(text)
            }
        }

        // 语言切换按钮
        binding.btnSwitch.setOnClickListener {
            it.animate()
                .rotationBy(180f)
                .setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

            isLaoToChinese = !isLaoToChinese
            updateDirectionUI()

            // 交换输入输出文本
            val sourceText = binding.etSource.text.toString()
            val resultText = binding.tvResult.text.toString()
            if (resultText.isNotEmpty() && resultText != getString(R.string.translating)) {
                binding.etSource.setText(resultText)
                binding.tvResult.text = sourceText
            }

            historyStore.saveDirection(
                if (isLaoToChinese) "lo" else "zh",
                if (isLaoToChinese) "zh" else "lo"
            )
        }

        // 语音输入按钮
        binding.btnVoice.setOnClickListener {
            if (isListening) {
                speechManager.stopListening()
                isListening = false
                updateVoiceButton()
            } else {
                checkPermissionAndListen()
            }
        }

        // 朗读结果
        binding.btnSpeakResult.setOnClickListener {
            val text = binding.tvResult.text.toString()
            if (text.isNotEmpty() && text != getString(R.string.translating)) {
                val locale = if (isLaoToChinese) Locale.CHINESE else Locale("lo")
                speechManager.speak(text, locale)
                animateButton(it)
            }
        }

        // 清空按钮
        binding.btnClear.setOnClickListener {
            binding.etSource.text?.clear()
            binding.tvResult.text = ""
            binding.tvResult.visibility = View.GONE
            animateButton(it)
        }

        // 复制结果
        binding.btnCopy.setOnClickListener {
            val text = binding.tvResult.text.toString()
            if (text.isNotEmpty()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("translation", text)
                )
                showToast("已复制到剪贴板")
                animateButton(it)
            }
        }
    }

    private fun initTts() {
        speechManager.initTts { success ->
            if (!success) {
                showToast("语音合成功能初始化失败")
            }
        }
    }

    private fun updateDirectionUI() {
        if (isLaoToChinese) {
            binding.tvSourceLang.text = "🇱🇦 老挝语"
            binding.tvTargetLang.text = "🇨🇳 中文"
            binding.etSource.hint = "输入老挝语或点击麦克风说话..."
        } else {
            binding.tvSourceLang.text = "🇨🇳 中文"
            binding.tvTargetLang.text = "🇱🇦 老挝语"
            binding.etSource.hint = "输入中文或点击麦克风说话..."
        }
    }

    private fun checkPermissionAndListen() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED -> {
                startVoiceInput()
            }
            else -> {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startVoiceInput() {
        isListening = true
        updateVoiceButton()

        val locale = if (isLaoToChinese) Locale("lo") else Locale.CHINESE

        speechManager.startListening(locale, object : SpeechManager.RecognitionCallback {
            override fun onResult(text: String) {
                runOnUiThread {
                    isListening = false
                    updateVoiceButton()
                    binding.etSource.setText(text)
                    performTranslation(text)
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    isListening = false
                    updateVoiceButton()
                    showToast(error)
                }
            }

            override fun onBeginOfSpeech() {
                runOnUiThread {
                    binding.tvListeningHint.visibility = View.VISIBLE
                    binding.tvListeningHint.text = "正在聆听..."
                }
            }

            override fun onEndOfSpeech() {
                runOnUiThread {
                    binding.tvListeningHint.text = "识别中..."
                }
            }
        })
    }

    private fun updateVoiceButton() {
        if (isListening) {
            binding.btnVoice.setImageResource(R.drawable.ic_mic_off)
            binding.btnVoice.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.accent_red)
                )
            binding.voiceRipple.visibility = View.VISIBLE
            binding.voiceRipple.startAnimation(
                AnimationUtils.loadAnimation(this, R.anim.pulse)
            )
        } else {
            binding.btnVoice.setImageResource(R.drawable.ic_mic)
            binding.btnVoice.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.primary)
                )
            binding.voiceRipple.visibility = View.GONE
            binding.voiceRipple.clearAnimation()
            binding.tvListeningHint.visibility = View.GONE
        }
    }

    private fun performTranslation(text: String) {
        binding.tvResult.visibility = View.VISIBLE
        binding.tvResult.text = getString(R.string.translating)
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = if (isLaoToChinese) {
                TranslationApi.laoToChinese(text)
            } else {
                TranslationApi.chineseToLao(text)
            }

            binding.progressBar.visibility = View.GONE

            result.onSuccess { translated ->
                binding.tvResult.text = translated
                binding.tvResult.alpha = 0f
                binding.tvResult.animate().alpha(1f).setDuration(300).start()

                // 保存历史
                historyStore.saveHistory(
                    text, translated,
                    if (isLaoToChinese) "lo" else "zh",
                    if (isLaoToChinese) "zh" else "lo"
                )
            }.onFailure { error ->
                binding.tvResult.text = "翻译失败: ${error.message}"
                binding.tvResult.setTextColor(
                    ContextCompat.getColor(this@MainActivity, R.color.accent_red)
                )
            }
        }
    }

    private fun animateButton(view: View) {
        view.animate()
            .scaleX(0.9f).scaleY(0.9f)
            .setDuration(100)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }.start()
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.release()
    }
}
