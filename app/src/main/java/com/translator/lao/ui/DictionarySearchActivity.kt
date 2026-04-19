package com.translator.lao.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDictionarySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = OfflineDictionaryDb(this)

        // 先导入词典数据，确保搜索可用
        try { db.importFromMemory() } catch (e: Exception) {
            android.util.Log.e("DictSearch", "import failed", e)
        }

        tts = TextToSpeech(this) { /* ready */ }

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

        adapter = DictAdapter(
            onSpeak = { text, isLao ->
                val locale = if (isLao) Locale("lo") else Locale.CHINESE
                tts?.language = locale
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts")
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
            val results = db.search(query, isLaoToChinese)
            runOnUiThread {
                adapter.submitList(results.map { Pair(it.first, it.second) })
                binding.tvEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                binding.tvEmpty.text = "未找到 \"$query\" 的结果"
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

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
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
