package com.translator.lao.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.translator.lao.data.OfflineDictionaryDb
import com.translator.lao.data.ThaiRomanizer
import com.translator.lao.databinding.ActivityDictionarySearchBinding
import com.translator.lao.databinding.ItemDictionaryEntryBinding
import java.util.Locale

class DictionarySearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDictionarySearchBinding
    private lateinit var db: OfflineDictionaryDb
    private lateinit var adapter: DictAdapter
    private var isLaoToChinese = true
    private var tts: TextToSpeech? = null
    private var showingFavorites = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDictionarySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = OfflineDictionaryDb(this)

        // 先导入词典数据，确保搜索可用
        try { db.importFromMemory() } catch (e: Exception) {
            android.util.Log.e("DictSearch", "import failed", e)
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                android.util.Log.d("DictSearch", "TTS engine ready")
            } else {
                android.util.Log.e("DictSearch", "TTS init failed: $status")
                showToast("语音引擎初始化失败")
            }
        }

        setupUI()
        showEmptyHint()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }
        updateDirectionUI()

        binding.btnSwitch.setOnClickListener {
            isLaoToChinese = !isLaoToChinese
            updateDirectionUI()
            val q = binding.etSearch.text.toString().trim()
            if (q.isNotEmpty()) doSearch(q)
        }

        binding.btnFavorites.setOnClickListener {
            showingFavorites = !showingFavorites
            if (showingFavorites) showFavorites()
            else {
                val q = binding.etSearch.text.toString().trim()
                if (q.isNotEmpty()) doSearch(q) else showEmptyHint()
            }
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                showingFavorites = false
                if (q.isNotEmpty()) doSearch(q) else showEmptyHint()
            }
        })

        binding.btnClearHistory.setOnClickListener {
            db.clearHistory()
            showEmptyHint()
            binding.btnClearHistory.visibility = View.GONE
            showToast("历史已清空")
        }

        // 语音搜索按钮
        binding.btnVoice.setOnClickListener {
            if (isListening) {
                stopVoiceRecognition()
            } else {
                startThaiVoiceRecognition()
            }
        }

        // 长按显示语音搜索提示
        binding.btnVoice.setOnLongClickListener {
            val visible = binding.tvVoiceHint.visibility == View.VISIBLE
            binding.tvVoiceHint.visibility = if (visible) View.GONE else View.VISIBLE
            true
        }

        adapter = DictAdapter(
            onSpeak = { text, isLao ->
                val engine = tts
                if (engine == null) {
                    showToast("语音引擎未初始化")
                    return@DictAdapter
                }
                val locale = if (isLao) Locale("lo") else Locale.CHINESE
                val langResult = engine.setLanguage(locale)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    val langName = if (isLao) "老挝语" else "中文"
                    showToast("${langName}语音数据不可用，尝试安装...")
                    val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    try { startActivity(installIntent) } catch (_: Exception) {
                        showToast("${langName}语音不支持")
                    }
                    return@DictAdapter
                }
                val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
                if (result == TextToSpeech.ERROR) {
                    showToast("朗读失败")
                }
            },
            onCopy = { text ->
                val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("dict", text))
                showToast("已复制")
            },
            onFav = { zh, lao, isFav ->
                if (isFav) { db.removeFavorite(zh, lao); showToast("已取消收藏") }
                else { db.addFavorite(zh, lao); showToast("已收藏") }
                if (showingFavorites) showFavorites()
                else {
                    val q = binding.etSearch.text.toString().trim()
                    if (q.isNotEmpty()) doSearch(q)
                }
            },
            isFav = { zh, lao -> db.isFavorite(zh, lao) },
            isLaoToChinese = { isLaoToChinese }
        )

        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = adapter
    }

    private fun updateDirectionUI() {
        binding.tvDirection.text = if (isLaoToChinese) "老挝语 → 中文" else "中文 → 老挝语"
        binding.etSearch.hint = if (isLaoToChinese) "输入老挝语搜索..." else "输入中文搜索..."
    }

    private fun doSearch(query: String) {
        db.addHistory(query)
        binding.btnClearHistory.visibility = View.GONE
        Thread {
            // 常规搜索
            val results = db.search(query, isLaoToChinese).toMutableList()

            // 如果是老挝→中文模式且结果少，也尝试拼音搜索
            if (isLaoToChinese && results.size < 5) {
                val romResults = db.searchByRomanization(query.lowercase())
                for (r in romResults) {
                    if (r !in results) results.add(r)
                }
            }

            runOnUiThread {
                adapter.submitList(results)
                binding.tvEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                binding.tvEmpty.text = if (results.isEmpty()) {
                    "未找到 \"$query\" 的结果\n\n💡 可以试试：\n• 输入拼音如 sabaidee\n• 点击 🎤 用泰语语音搜索"
                } else {
                    ""
                }
            }
        }.start()
    }

    private fun showEmptyHint() {
        adapter.submitList(emptyList())
        binding.tvEmpty.visibility = View.VISIBLE
        binding.tvEmpty.text = "输入文字搜索词典\n或点击 ⭐ 查看收藏"
    }

    private fun showFavorites() {
        Thread {
            val favs = db.getAllFavorites()
            runOnUiThread {
                adapter.submitList(favs)
                binding.tvEmpty.visibility = if (favs.isEmpty()) View.VISIBLE else View.GONE
                binding.tvEmpty.text = "暂无收藏\n点击词条旁的 ⭐ 添加"
            }
        }.start()
    }

    // ========== 泰语语音搜索 ==========

    private fun startThaiVoiceRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("语音识别不可用")
                .setMessage("当前设备未安装语音识别服务\n\n" +
                    "国内手机通常缺少 Google 语音服务\n\n" +
                    "💡 建议：使用手机键盘自带的 🎤 语音输入")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        // 实际尝试创建识别器（有些设备 isRecognitionAvailable 返回 true 但创建失败）
        try {
            val test = SpeechRecognizer.createSpeechRecognizer(this)
            if (test == null) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("语音识别服务缺失")
                    .setMessage("设备缺少语音识别服务（Google Speech Services）\n\n" +
                        "这在国内手机上非常常见\n\n" +
                        "💡 建议：使用手机键盘自带的 🎤 语音输入")
                    .setPositiveButton("知道了", null)
                    .show()
                return
            }
            test.destroy()
        } catch (e: Exception) {
            android.app.AlertDialog.Builder(this)
                .setTitle("语音识别服务缺失")
                .setMessage("无法启动语音识别：${e.message}\n\n💡 建议：使用手机键盘自带的 🎤 语音输入")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        stopVoiceRecognition()
        isListening = true
        binding.tvVoiceHint.visibility = View.VISIBLE
        binding.tvVoiceHint.text = "🎤 正在听... 请用泰语说话"
        binding.tvVoiceHint.setTextColor(getColor(android.R.color.holo_red_light))

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        } catch (e: Exception) {
            showToast("创建语音识别器失败")
            resetVoiceUI()
            return
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                binding.tvVoiceHint.text = "🎤 正在识别..."
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                binding.tvVoiceHint.text = "⏳ 处理中..."
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音，请重试"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误（语音识别需要联网）"
                    SpeechRecognizer.ERROR_CLIENT -> "语音识别服务异常\n设备可能缺少 Google 语音服务\n💡 建议使用键盘上的🎤语音输入"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "语音识别服务异常\n（已授权但仍失败 = 服务缺失）\n💡 建议使用键盘上的🎤语音输入"
                    else -> "识别失败 (错误码: $error)\n💡 建议使用键盘上的🎤语音输入"
                }
                showToast(msg)
                resetVoiceUI()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    handleThaiSpeechResult(matches[0])
                } else {
                    showToast("未识别到内容")
                }
                resetVoiceUI()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    binding.tvVoiceHint.text = "🎤 ${matches[0]}"
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        // 使用泰语 locale
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "th-TH")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            showToast("启动语音识别失败: ${e.message}")
            resetVoiceUI()
        }
    }

    private fun stopVoiceRecognition() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        isListening = false
    }

    private fun resetVoiceUI() {
        isListening = false
        binding.tvVoiceHint.text = "🎤 用泰语说话搜索老挝语词典（泰语和老挝语相似度约70%）"
        binding.tvVoiceHint.setTextColor(getColor(android.R.color.darker_gray))
    }

    /**
     * 处理泰语语音识别结果
     */
    private fun handleThaiSpeechResult(thaiText: String) {
        // 将泰语文本显示在搜索框
        binding.etSearch.setText(thaiText)

        // 泰语 → 罗马拼音
        val romanized = ThaiRomanizer.romanize(thaiText)
        android.util.Log.d("VoiceSearch", "泰语: $thaiText → 拼音: $romanized")

        if (romanized.length < 2) {
            showToast("识别结果太短，请重试")
            return
        }

        // 显示拼音搜索结果
        binding.tvVoiceHint.text = "🔍 搜索: $thaiText ($romanized)"
        binding.tvVoiceHint.visibility = View.VISIBLE

        Thread {
            // 先尝试拼音模糊匹配
            val romResults = db.searchByRomanization(romanized)

            // 也尝试直接用泰语文本搜索
            val directResults = db.search(thaiText, isLaoToChinese)

            // 合并去重
            val allResults = mutableListOf<Pair<String, String>>()
            allResults.addAll(romResults)
            for (r in directResults) {
                if (r !in allResults) allResults.add(r)
            }

            runOnUiThread {
                if (allResults.isNotEmpty()) {
                    adapter.submitList(allResults)
                    binding.tvEmpty.visibility = View.GONE
                    showToast("找到 ${allResults.size} 条结果（泰语语音匹配）")
                } else {
                    adapter.submitList(emptyList())
                    binding.tvEmpty.visibility = View.VISIBLE
                    binding.tvEmpty.text = "未找到 \"$thaiText\" ($romanized) 的匹配\n\n💡 提示：\n• 泰语和老挝语约70%相似\n• 部分老挝语独有词汇无法匹配\n• 也可直接输入拼音搜索，如 sabaidee"
                }
            }
        }.start()
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVoiceRecognition()
        tts?.shutdown()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DictionarySearchActivity::class.java))
        }
    }
}

class DictAdapter(
    private val onSpeak: (text: String, isLao: Boolean) -> Unit,
    private val onCopy: (text: String) -> Unit,
    private val onFav: (zh: String, lao: String, isCurrentlyFav: Boolean) -> Unit,
    private val isFav: (zh: String, lao: String) -> Boolean,
    private val isLaoToChinese: () -> Boolean
) : ListAdapter<Pair<String, String>, DictAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Pair<String, String>>() {
            override fun areItemsTheSame(a: Pair<String, String>, b: Pair<String, String>) = a == b
            override fun areContentsTheSame(a: Pair<String, String>, b: Pair<String, String>) = a == b
        }
    }

    class VH(val binding: ItemDictionaryEntryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemDictionaryEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (source, target) = getItem(position)
        val isLao = isLaoToChinese()
        holder.binding.tvSource.text = source
        holder.binding.tvTarget.text = target

        val zh = if (isLao) target else source
        val lao = if (isLao) source else target
        val fav = isFav(zh, lao)
        holder.binding.btnFavorite.setImageResource(
            if (fav) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        )

        holder.binding.btnSpeakSource.setOnClickListener { onSpeak(source, isLao) }
        holder.binding.btnSpeakTarget.setOnClickListener { onSpeak(target, !isLao) }
        holder.binding.btnCopy.setOnClickListener { onCopy("$source → $target") }
        holder.binding.btnFavorite.setOnClickListener { onFav(zh, lao, fav) }
    }
}
