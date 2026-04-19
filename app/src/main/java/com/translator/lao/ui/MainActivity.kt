package com.translator.lao.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.translator.lao.R
import com.translator.lao.api.TranslationApi
import com.translator.lao.data.Dictionary
import com.translator.lao.data.DictionaryStore
import com.translator.lao.databinding.ActivityMainBinding
import com.translator.lao.speech.SpeechManager
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var speechManager: SpeechManager

    private var isOfflineMode = true
    private var isLaoToChinese = true
    private var isListening = false
    private var selectedCategoryIndex = -1
    private val currentSource = TranslationApi.Source.MYMEMORY

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceInput()
        else showToast("需要麦克风权限才能语音输入")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        speechManager = SpeechManager(this)

        initUI()
        updateNetworkStatus()

        // 异步初始化 TTS，失败时提示
        speechManager.initTts { success ->
            runOnUiThread {
                if (!success) {
                    Log.w("MainActivity", "TTS init failed: ${speechManager.getTtsStatus()}")
                }
            }
        }

        // 检查语音识别可用性
        if (!speechManager.isRecognitionAvailable()) {
            Log.w("MainActivity", "Speech recognition not available")
        }
    }

    // ==================== UI 初始化 ====================

    private fun initUI() {
        updateDirectionUI()
        updateModeUI()
        setupModeButtons()
        setupLanguageSwitch()
        setupActionButtons()
        setupCategoryChips()
        setupRecyclerView()
    }

    private fun setupModeButtons() {
        binding.btnOfflineMode.setOnClickListener {
            if (!isOfflineMode) {
                isOfflineMode = true
                updateModeUI()
                selectedCategoryIndex = -1
                setupCategoryChips()
                binding.rvCategoryEntries.visibility = View.GONE
            }
        }
        binding.btnOnlineMode.setOnClickListener {
            if (isOfflineMode) {
                isOfflineMode = false
                updateModeUI()
            }
        }
    }

    private fun setupLanguageSwitch() {
        binding.btnSwitch.setOnClickListener {
            it.animate()
                .rotationBy(180f)
                .setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()

            isLaoToChinese = !isLaoToChinese
            updateDirectionUI()

            val sourceText = binding.etSource.text.toString()
            val resultText = binding.tvResult.text.toString()
            if (resultText.isNotEmpty() && resultText != getString(R.string.translating)) {
                binding.etSource.setText(resultText)
                binding.tvResult.text = sourceText
            }

            selectedCategoryIndex = -1
            setupCategoryChips()
            binding.rvCategoryEntries.visibility = View.GONE
        }
    }

    private fun setupActionButtons() {
        binding.btnTranslate.setOnClickListener {
            val text = binding.etSource.text.toString().trim()
            if (text.isNotEmpty()) performTranslation(text)
        }

        binding.btnVoice.setOnClickListener {
            if (!speechManager.isRecognitionAvailable()) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("语音识别不可用")
                    .setMessage(speechManager.getRecognitionUnavailableReason())
                    .setPositiveButton("知道了", null)
                    .show()
                return@setOnClickListener
            }
            if (isListening) {
                speechManager.stopListening()
                isListening = false
                updateVoiceButton()
            } else {
                checkPermissionAndListen()
            }
        }

        binding.btnClear.setOnClickListener {
            binding.etSource.text?.clear()
            binding.tvResult.text = ""
            binding.cardResult.visibility = View.GONE
        }

        binding.btnCopy.setOnClickListener {
            val text = binding.tvResult.text.toString()
            if (text.isNotEmpty()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
                showToast("已复制")
            }
        }

        binding.btnSpeakResult.setOnClickListener {
            val text = binding.tvResult.text.toString()
            if (text.isNotEmpty() && text != getString(R.string.translating)) {
                if (!speechManager.isTtsAvailable()) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("语音播报不可用")
                        .setMessage("当前设备没有可用的语音合成引擎\n\n${speechManager.getTtsStatus()}\n\n建议：复制文字后使用手机自带的朗读功能")
                        .setPositiveButton("知道了", null)
                        .setNeutralButton("复制文字") { _, _ ->
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
                            showToast("已复制")
                        }
                        .show()
                    return@setOnClickListener
                }
                showToast("正在朗读...")
                lifecycleScope.launch {
                    val ttsLocale = if (isLaoToChinese) Locale.CHINESE else Locale("lo")
                    speechManager.speak(text, locale = ttsLocale, callback = object : SpeechManager.TtsCallback {
                        override fun onComplete() {
                            Log.d("MainActivity", "TTS playback complete")
                        }
                        override fun onError(error: String) {
                            showToast("播报失败：$error")
                        }
                    })
                }
            }
        }
    }

    // ==================== 模式 UI ====================

    private fun updateModeUI() {
        if (isOfflineMode) {
            binding.btnOfflineMode.apply {
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.offline_active)
                setTextColor(ContextCompat.getColor(context, R.color.text_on_primary))
            }
            binding.btnOnlineMode.apply {
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.offline_inactive)
                setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            }
            binding.cardCategories.visibility = View.VISIBLE
        } else {
            binding.btnOfflineMode.apply {
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.offline_inactive)
                setTextColor(ContextCompat.getColor(context, R.color.text_hint))
            }
            binding.btnOnlineMode.apply {
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.online_active)
                setTextColor(ContextCompat.getColor(context, R.color.text_on_primary))
            }
            binding.cardCategories.visibility = View.GONE
        }
    }

    private fun updateDirectionUI() {
        if (isLaoToChinese) {
            binding.tvSourceLang.text = "🇱🇦 老挝语"
            binding.tvTargetLang.text = "🇨🇳 中文"
            binding.etSource.hint = "输入老挝语或点击🎤说话..."
        } else {
            binding.tvSourceLang.text = "🇨🇳 中文"
            binding.tvTargetLang.text = "🇱🇦 老挝语"
            binding.etSource.hint = "输入中文或点击🎤说话..."
        }
    }

    private fun updateNetworkStatus() {
        val online = isNetworkAvailable()
        if (online) {
            binding.networkDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.online_green)
            binding.tvNetworkStatus.text = "在线"
            binding.tvNetworkStatus.setTextColor(ContextCompat.getColor(this, R.color.online_green))
        } else {
            binding.networkDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.offline_gray)
            binding.tvNetworkStatus.text = "离线"
            binding.tvNetworkStatus.setTextColor(ContextCompat.getColor(this, R.color.offline_gray))
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ==================== 分类词库 ====================

    private fun setupCategoryChips() {
        val chipGroup = binding.chipGroupCategories
        chipGroup.removeAllViews()

        val categories = DictionaryStore.getAllCategories()
        categories.forEachIndexed { index, category ->
            val chip = Chip(this).apply {
                text = category.nameZh
                isCheckable = true
                isChecked = (index == selectedCategoryIndex)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedCategoryIndex = index
                        for (i in 0 until chipGroup.childCount) {
                            (chipGroup.getChildAt(i) as? Chip)?.let { c ->
                                if (c != this) c.isChecked = false
                            }
                        }
                        showCategoryEntries(category)
                    } else if (selectedCategoryIndex == index) {
                        selectedCategoryIndex = -1
                        binding.rvCategoryEntries.visibility = View.GONE
                    }
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun setupRecyclerView() {
        binding.rvCategoryEntries.layoutManager = LinearLayoutManager(this)
    }

    private fun showCategoryEntries(category: Dictionary.Category) {
        val entries = DictionaryStore.getCategoryEntries(category, isLaoToChinese)
        if (entries.isEmpty()) {
            binding.rvCategoryEntries.visibility = View.GONE
            return
        }

        binding.rvCategoryEntries.visibility = View.VISIBLE
        val adapter = CategoryAdapter(entries)
        adapter.onItemClick = { (source, _) ->
            binding.etSource.setText(source)
            performTranslation(source)
        }
        binding.rvCategoryEntries.adapter = adapter
    }

    // ==================== 语音 ====================

    private fun checkPermissionAndListen() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED -> startVoiceInput()
            else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
                ContextCompat.getColorStateList(this, R.color.accent_red)
        } else {
            binding.btnVoice.setImageResource(R.drawable.ic_mic)
            binding.btnVoice.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.primary)
            binding.tvListeningHint.visibility = View.GONE
        }
    }

    // ==================== 翻译 ====================

    private fun performTranslation(text: String) {
        binding.cardResult.visibility = View.VISIBLE
        binding.tvResult.text = getString(R.string.translating)
        binding.tvResult.setTextColor(ContextCompat.getColor(this, R.color.text_body))
        binding.progressBar.visibility = View.VISIBLE

        if (isOfflineMode) {
            performOfflineTranslation(text)
        } else {
            performOnlineTranslation(text)
        }
    }

    private fun performOfflineTranslation(text: String) {
        val results = DictionaryStore.translate(text, isLaoToChinese)
        binding.progressBar.visibility = View.GONE

        if (results.isNotEmpty()) {
            binding.tvResult.text = results.joinToString("\n")
            binding.tvResult.alpha = 0f
            binding.tvResult.animate().alpha(1f).setDuration(300).start()
        } else {
            binding.tvResult.text = "未找到翻译，请尝试在线模式"
            binding.tvResult.setTextColor(ContextCompat.getColor(this, R.color.text_hint))
        }
    }

    private fun performOnlineTranslation(text: String) {
        lifecycleScope.launch {
            val result = if (isLaoToChinese) {
                TranslationApi.laoToChinese(text, currentSource)
            } else {
                TranslationApi.chineseToLao(text, currentSource)
            }

            binding.progressBar.visibility = View.GONE

            result.onSuccess { translated ->
                binding.tvResult.text = translated
                binding.tvResult.alpha = 0f
                binding.tvResult.animate().alpha(1f).setDuration(300).start()
            }.onFailure { error ->
                binding.tvResult.text = "翻译失败: ${error.message}"
                binding.tvResult.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_red))
            }
        }
    }

    // ==================== 工具 ====================

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.release()
    }
}
