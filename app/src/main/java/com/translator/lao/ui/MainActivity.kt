package com.translator.lao.ui

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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
import com.translator.lao.data.FavoritesStore
import com.translator.lao.data.HistoryStore
import com.translator.lao.databinding.ActivityMainBinding
import com.translator.lao.speech.SpeechManager
import com.translator.lao.update.UpdateManager
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var speechManager: SpeechManager
    private lateinit var favoritesStore: FavoritesStore
    private lateinit var historyStore: HistoryStore

    private var isOfflineMode = true
    private var isLaoToChinese = true
    private var isListening = false
    private var selectedCategoryIndex = -1
    private var currentSource = TranslationApi.Source.MYMEMORY
    private var pendingDownloadId: Long = -1L
    private var lastSourceText = ""
    private var lastResultText = ""

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVoiceInput()
        else showToast(getString(R.string.mic_permission_needed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        speechManager = SpeechManager(this)
        favoritesStore = FavoritesStore(this)
        historyStore = HistoryStore(this)

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
        setupSourceButtons()
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

    private fun setupSourceButtons() {
        updateSourceUI()

        binding.btnSrcMyMemory.setOnClickListener {
            currentSource = TranslationApi.Source.MYMEMORY
            updateSourceUI()
            showToast(getString(R.string.source_changed, "MyMemory"))
        }
        binding.btnSrcGoogle.setOnClickListener {
            currentSource = TranslationApi.Source.GOOGLE
            updateSourceUI()
            showToast(getString(R.string.source_changed, "Google"))
        }
        binding.btnSrcBing.setOnClickListener {
            currentSource = TranslationApi.Source.BING
            updateSourceUI()
            showToast(getString(R.string.source_changed, "Bing"))
        }
        binding.btnSrcLibre.setOnClickListener {
            currentSource = TranslationApi.Source.LIBRE
            updateSourceUI()
            showToast(getString(R.string.source_changed, "LibreTranslate"))
        }
    }

    private fun updateSourceUI() {
        fun styleBtn(btn: com.google.android.material.button.MaterialButton,
                     active: Boolean, activeColor: Int) {
            if (active) {
                btn.backgroundTintList = ContextCompat.getColorStateList(this, activeColor)
                btn.setTextColor(ContextCompat.getColor(this, R.color.text_on_primary))
                btn.strokeWidth = 0
            } else {
                btn.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.transparent)
                btn.setTextColor(ContextCompat.getColor(this, activeColor))
                btn.strokeWidth = 2
                btn.strokeColor = ContextCompat.getColorStateList(this, activeColor)
            }
        }
        styleBtn(binding.btnSrcMyMemory, currentSource == TranslationApi.Source.MYMEMORY, R.color.mymemory_orange)
        styleBtn(binding.btnSrcGoogle, currentSource == TranslationApi.Source.GOOGLE, R.color.google_blue)
        styleBtn(binding.btnSrcBing, currentSource == TranslationApi.Source.BING, R.color.bing_green)
        styleBtn(binding.btnSrcLibre, currentSource == TranslationApi.Source.LIBRE, R.color.libre_purple)
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
        binding.btnCheckUpdate.setOnClickListener {
            checkForUpdate()
        }

        // 翻译历史
        binding.btnHistory.setOnClickListener {
            HistoryActivity.start(this)
        }

        // 我的收藏
        binding.btnFavorites.setOnClickListener {
            FavoritesActivity.start(this)
        }

        // 拍照翻译
        binding.btnOcr.setOnClickListener {
            OcrTranslateActivity.start(this)
        }

        // 词典搜索
        binding.btnDictionary.setOnClickListener {
            DictionarySearchActivity.start(this)
        }

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
            binding.tvEmptyStateHint.visibility = View.VISIBLE
            lastSourceText = ""
            lastResultText = ""
        }

        binding.btnCopy.setOnClickListener {
            val text = binding.tvResult.text.toString()
            if (text.isNotEmpty()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
                showToast(getString(R.string.copied))
            }
        }

        // 收藏翻译结果
        binding.btnFavoriteResult.setOnClickListener {
            if (lastSourceText.isNotEmpty() && lastResultText.isNotEmpty()) {
                val fromLang = if (isLaoToChinese) "lo" else "zh"
                val toLang = if (isLaoToChinese) "zh" else "lo"
                if (favoritesStore.isFavorite(lastSourceText, lastResultText)) {
                    favoritesStore.removeFavorite(lastSourceText, lastResultText)
                    binding.btnFavoriteResult.setImageResource(R.drawable.ic_star)
                    binding.btnFavoriteResult.imageTintList = ContextCompat.getColorStateList(this, R.color.text_hint)
                    showToast(getString(R.string.unfavorited))
                } else {
                    favoritesStore.addFavorite(lastSourceText, lastResultText, fromLang, toLang)
                    binding.btnFavoriteResult.setImageResource(R.drawable.ic_star_filled)
                    binding.btnFavoriteResult.imageTintList = null
                    showToast(getString(R.string.favorited))
                }
            }
        }

        binding.btnSpeakResult.setOnClickListener {
            val text = binding.tvResult.text.toString()
            if (text.isNotEmpty() && text != getString(R.string.translating)) {
                if (speechManager.isSpeaking()) {
                    speechManager.stopSpeaking()
                    return@setOnClickListener
                }
                if (!speechManager.isTtsAvailable()) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("语音播报不可用")
                        .setMessage("当前设备没有可用的语音合成引擎\n\n${speechManager.getTtsStatus()}\n\n建议：复制文字后使用手机自带的朗读功能")
                        .setPositiveButton("知道了", null)
                        .setNeutralButton("复制文字") { _, _ ->
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
                            showToast(getString(R.string.copied))
                        }
                        .show()
                    return@setOnClickListener
                }
                showToast(getString(R.string.tts_speaking))
                lifecycleScope.launch {
                    val ttsLocale = if (isLaoToChinese) Locale.CHINESE else Locale("lo")
                    speechManager.speak(text, locale = ttsLocale, callback = object : SpeechManager.TtsCallback {
                        override fun onComplete() {
                            Log.d("MainActivity", "TTS playback complete")
                        }
                        override fun onError(error: String) {
                            showToast(getString(R.string.tts_failed_prefix) + error)
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
            binding.layoutSourceSelector.visibility = View.GONE
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
            binding.layoutSourceSelector.visibility = View.VISIBLE
        }
    }

    private fun updateDirectionUI() {
        if (isLaoToChinese) {
            binding.tvSourceLang.text = getString(R.string.source_lang_lao)
            binding.tvTargetLang.text = getString(R.string.target_lang_zh)
            binding.etSource.hint = getString(R.string.input_hint)
        } else {
            binding.tvSourceLang.text = getString(R.string.source_lang_zh)
            binding.tvTargetLang.text = getString(R.string.target_lang_lao)
            binding.etSource.hint = "输入中文或点击🎤说话..."
        }
    }

    private fun updateNetworkStatus() {
        val online = isNetworkAvailable()
        if (online) {
            binding.networkDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.online_green)
            binding.tvNetworkStatus.text = getString(R.string.network_online)
            binding.tvNetworkStatus.setTextColor(ContextCompat.getColor(this, R.color.online_green))
        } else {
            binding.networkDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.offline_gray)
            binding.tvNetworkStatus.text = getString(R.string.network_offline)
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
        binding.tvEmptyStateHint.visibility = View.GONE
        binding.tvResult.text = getString(R.string.translating_label)
        binding.tvResult.setTextColor(ContextCompat.getColor(this, R.color.text_body))
        binding.progressBar.visibility = View.VISIBLE
        lastSourceText = text

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
            val translated = results.joinToString("\n")
            lastResultText = translated
            binding.tvResult.text = translated
            binding.tvResult.alpha = 0f
            binding.tvResult.animate().alpha(1f).setDuration(300).start()

            // 保存历史
            val fromLang = if (isLaoToChinese) "lo" else "zh"
            val toLang = if (isLaoToChinese) "zh" else "lo"
            historyStore.saveHistory(text, translated, fromLang, toLang)
            updateFavoriteButton()
        } else {
            lastResultText = ""
            binding.tvResult.text = getString(R.string.no_result_offline)
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
                lastResultText = translated
                binding.tvResult.text = translated
                binding.tvResult.alpha = 0f
                binding.tvResult.animate().alpha(1f).setDuration(300).start()

                // 保存历史
                val fromLang = if (isLaoToChinese) "lo" else "zh"
                val toLang = if (isLaoToChinese) "zh" else "lo"
                historyStore.saveHistory(text, translated, fromLang, toLang)
                updateFavoriteButton()
            }.onFailure { error ->
                lastResultText = ""
                binding.tvResult.text = getString(R.string.no_result_online) + ": ${error.message}"
                binding.tvResult.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_red))
            }
        }
    }

    // ==================== 在线更新 ====================

    private fun checkForUpdate() {
        if (!isNetworkAvailable()) {
            showToast(getString(R.string.network_required))
            return
        }

        showToast(getString(R.string.checking_update))
        lifecycleScope.launch {
            UpdateManager.checkForUpdate(this@MainActivity).onSuccess { info ->
                if (info != null) {
                    showUpdateDialog(info)
                } else {
                    showToast(getString(R.string.already_latest))
                }
            }.onFailure { error ->
                showToast(error.message ?: "检查更新失败")
            }
        }
    }

    private fun showUpdateDialog(info: UpdateManager.UpdateInfo) {
        val size = UpdateManager.formatFileSize(info.fileSize)
        val msg = buildString {
            appendLine("发现新版本: ${info.versionName}")
            appendLine("大小: $size")
            appendLine()
            appendLine("更新内容:")
            appendLine(info.changelog)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_found_title))
            .setMessage(msg)
            .setPositiveButton("立即更新") { _, _ ->
                startDownload(info)
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun startDownload(info: UpdateManager.UpdateInfo) {
        showToast(getString(R.string.download_started))
        pendingDownloadId = UpdateManager.downloadApk(this, info.apkUrl, info.versionName)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == pendingDownloadId) {
                    unregisterReceiver(this)
                    showToast(getString(R.string.download_complete))
                    UpdateManager.installApk(this@MainActivity, pendingDownloadId)
                    pendingDownloadId = -1L
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    // ==================== 工具 ====================

    private fun updateFavoriteButton() {
        if (lastSourceText.isNotEmpty() && lastResultText.isNotEmpty()) {
            val isFav = favoritesStore.isFavorite(lastSourceText, lastResultText)
            if (isFav) {
                binding.btnFavoriteResult.setImageResource(R.drawable.ic_star_filled)
                binding.btnFavoriteResult.imageTintList = null
            } else {
                binding.btnFavoriteResult.setImageResource(R.drawable.ic_star)
                binding.btnFavoriteResult.imageTintList = ContextCompat.getColorStateList(this, R.color.text_hint)
            }
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.release()
    }
}

