package com.translator.lao.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.translator.lao.data.OfflineDictionaryDb
import com.translator.lao.data.ThaiRomanizer
import com.translator.lao.databinding.ActivityDictionarySearchBinding
import com.translator.lao.databinding.ItemDictionaryEntryBinding
import com.translator.lao.speech.SpeechManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class DictionarySearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDictionarySearchBinding
    private lateinit var db: OfflineDictionaryDb
    private lateinit var adapter: DictAdapter
    private lateinit var speechManager: SpeechManager
    private var isLaoToChinese = true
    private var showingFavorites = false
    private var isVoiceListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDictionarySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = OfflineDictionaryDb(this)
        speechManager = SpeechManager(this)

        // 导入词典数据
        try { db.importFromMemory() } catch (e: Exception) {
            android.util.Log.e("DictSearch", "import failed", e)
        }

        // 初始化 TTS
        speechManager.initTts { success ->
            if (!success) {
                android.util.Log.w("DictSearch", "TTS init failed: ${speechManager.getTtsStatus()}")
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

        // 语音搜索按钮（复用 SpeechManager）
        binding.btnVoice.setOnClickListener {
            if (isVoiceListening) {
                speechManager.stopListening()
                resetVoiceUI()
            } else {
                startVoiceRecognition()
            }
        }

        binding.btnVoice.setOnLongClickListener {
            val visible = binding.tvVoiceHint.visibility == View.VISIBLE
            binding.tvVoiceHint.visibility = if (visible) View.GONE else View.VISIBLE
            true
        }

        adapter = DictAdapter(
            onSpeak = { text, isLao ->
                if (speechManager.isSpeaking()) {
                    speechManager.stopSpeaking()
                    return@DictAdapter
                }
                lifecycleScope.launch {
                    val locale = if (isLao) Locale("lo") else Locale.CHINESE
                    speechManager.speak(text, locale = locale, callback = object : SpeechManager.TtsCallback {
                        override fun onComplete() {}
                        override fun onError(error: String) {
                            showToast("朗读失败：$error")
                        }
                    })
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
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                val list = db.search(query, isLaoToChinese).toMutableList()
                // 老挝→中文且结果少时，补充拼音搜索
                if (isLaoToChinese && list.size < 5) {
                    val romResults = db.searchByRomanization(query.lowercase())
                    for (r in romResults) {
                        if (r !in list) list.add(r)
                    }
                }
                list
            }
            adapter.submitList(results)
            binding.tvEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
            binding.tvEmpty.text = if (results.isEmpty()) {
                "未找到 \"$query\" 的结果\n\n💡 可以试试：\n• 输入拼音如 sabaidee\n• 点击 🎤 用泰语语音搜索"
            } else ""
        }
    }

    private fun showEmptyHint() {
        adapter.submitList(emptyList())
        binding.tvEmpty.visibility = View.VISIBLE
        binding.tvEmpty.text = "输入文字搜索词典\n或点击 ⭐ 查看收藏"
    }

    private fun showFavorites() {
        lifecycleScope.launch {
            val favs = withContext(Dispatchers.IO) { db.getAllFavorites() }
            adapter.submitList(favs)
            binding.tvEmpty.visibility = if (favs.isEmpty()) View.VISIBLE else View.GONE
            binding.tvEmpty.text = if (favs.isEmpty()) "暂无收藏\n点击词条旁的 ⭐ 添加" else ""
        }
    }

    // ========== 泰语语音搜索（复用 SpeechManager） ==========

    private fun startVoiceRecognition() {
        if (!speechManager.isRecognitionAvailable()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("语音识别不可用")
                .setMessage(speechManager.getRecognitionUnavailableReason())
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        isVoiceListening = true
        binding.tvVoiceHint.visibility = View.VISIBLE
        binding.tvVoiceHint.text = "🎤 正在听... 请用泰语说话"
        binding.tvVoiceHint.setTextColor(getColor(android.R.color.holo_red_light))

        // 使用泰语 locale 进行语音识别
        speechManager.startListening(Locale("th", "TH"), object : SpeechManager.RecognitionCallback {
            override fun onBeginOfSpeech() {
                binding.tvVoiceHint.text = "🎤 正在识别..."
            }
            override fun onEndOfSpeech() {
                binding.tvVoiceHint.text = "⏳ 处理中..."
            }
            override fun onResult(text: String) {
                resetVoiceUI()
                handleThaiSpeechResult(text)
            }
            override fun onError(error: String) {
                resetVoiceUI()
                showToast(error)
            }
        })
    }

    private fun resetVoiceUI() {
        isVoiceListening = false
        binding.tvVoiceHint.text = "🎤 用泰语说话搜索老挝语词典（泰语和老挝语相似度约70%）"
        binding.tvVoiceHint.setTextColor(getColor(android.R.color.darker_gray))
    }

    private fun handleThaiSpeechResult(thaiText: String) {
        binding.etSearch.setText(thaiText)

        val romanized = ThaiRomanizer.romanize(thaiText)
        android.util.Log.d("VoiceSearch", "泰语: $thaiText → 拼音: $romanized")

        if (romanized.length < 2) {
            showToast("识别结果太短，请重试")
            return
        }

        binding.tvVoiceHint.text = "🔍 搜索: $thaiText ($romanized)"
        binding.tvVoiceHint.visibility = View.VISIBLE

        lifecycleScope.launch {
            val allResults = withContext(Dispatchers.IO) {
                val romResults = db.searchByRomanization(romanized)
                val directResults = db.search(thaiText, isLaoToChinese)
                val combined = mutableListOf<Pair<String, String>>()
                combined.addAll(romResults)
                for (r in directResults) {
                    if (r !in combined) combined.add(r)
                }
                combined
            }

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
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.release()
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
