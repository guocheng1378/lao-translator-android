package com.translator.lao.ui

import android.app.AlertDialog
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
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.translator.lao.R
import com.translator.lao.data.FavoritesStore
import com.translator.lao.data.HistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var historyStore: HistoryStore
    private lateinit var favoritesStore: FavoritesStore
    private var allEntries = listOf<HistoryStore.HistoryEntry>()

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, HistoryActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        historyStore = HistoryStore(this)
        favoritesStore = FavoritesStore(this)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<View>(R.id.btnClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空历史")
                .setMessage("确定要清空所有翻译记录吗？")
                .setPositiveButton("清空") { _, _ ->
                    historyStore.clearHistory()
                    refreshList()
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        val etSearch = findViewById<android.widget.EditText>(R.id.etSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterList(s?.toString() ?: "")
            }
        })

        val rv = findViewById<RecyclerView>(R.id.rvList)
        rv.layoutManager = LinearLayoutManager(this)

        refreshList()
    }

    private fun refreshList() {
        allEntries = historyStore.getHistory()
        val tvEmpty = findViewById<TextView>(R.id.tvEmpty)
        val rv = findViewById<RecyclerView>(R.id.rvList)

        if (allEntries.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rv.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rv.visibility = View.VISIBLE
            rv.adapter = HistoryAdapter(allEntries.toMutableList())
        }
    }

    private fun filterList(query: String) {
        val filtered = if (query.isBlank()) {
            allEntries
        } else {
            allEntries.filter {
                it.source.contains(query, true) || it.result.contains(query, true)
            }
        }
        val rv = findViewById<RecyclerView>(R.id.rvList)
        rv.adapter = HistoryAdapter(filtered.toMutableList())
    }

    private fun dirLabel(from: String, to: String): String {
        val fromName = if (from == "lo") "老挝语" else "中文"
        val toName = if (to == "lo") "老挝语" else "中文"
        return "$fromName → $toName"
    }

    private fun formatTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3600_000 -> "${diff / 60_000} 分钟前"
            diff < 86400_000 -> "${diff / 3600_000} 小时前"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
        }
    }

    inner class HistoryAdapter(
        private val entries: MutableList<HistoryStore.HistoryEntry>
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
            val tvDirection: TextView = view.findViewById(R.id.tvDirection)
            val tvSource: TextView = view.findViewById(R.id.tvSource)
            val tvTarget: TextView = view.findViewById(R.id.tvTarget)
            val tvTime: TextView = view.findViewById(R.id.tvTime)
            val btnFavorite: ImageButton = view.findViewById(R.id.btnFavorite)
            val btnCopy: ImageButton = view.findViewById(R.id.btnCopy)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history_entry, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = entries[position]
            holder.tvDirection.text = dirLabel(entry.fromLang, entry.toLang)
            holder.tvSource.text = entry.source
            holder.tvTarget.text = entry.result
            holder.tvTime.text = formatTime(entry.timestamp)

            // 收藏状态
            val isFav = favoritesStore.isFavorite(entry.source, entry.result)
            holder.btnFavorite.setImageResource(
                if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star
            )

            holder.btnFavorite.setOnClickListener {
                if (isFav) {
                    favoritesStore.removeFavorite(entry.source, entry.result)
                    holder.btnFavorite.setImageResource(R.drawable.ic_star)
                    Toast.makeText(this@HistoryActivity, "已取消收藏", Toast.LENGTH_SHORT).show()
                } else {
                    favoritesStore.addFavorite(entry.source, entry.result, entry.fromLang, entry.toLang)
                    holder.btnFavorite.setImageResource(R.drawable.ic_star_filled)
                    Toast.makeText(this@HistoryActivity, "已收藏", Toast.LENGTH_SHORT).show()
                }
            }

            holder.btnCopy.setOnClickListener {
                val text = "${entry.source}\n${entry.result}"
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("translation", text))
                Toast.makeText(this@HistoryActivity, "已复制", Toast.LENGTH_SHORT).show()
            }

            holder.btnDelete.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos in entries.indices) {
                    entries.removeAt(pos)
                    notifyItemRemoved(pos)
                    notifyItemRangeChanged(pos, entries.size)
                    // 重新保存
                    val prefs = getSharedPreferences("lao_translator_prefs", MODE_PRIVATE)
                    val json = entries.joinToString("|||") { e ->
                        "${e.source}:::${e.result}:::${e.fromLang}:::${e.toLang}:::${e.timestamp}"
                    }
                    prefs.edit().putString("translation_history", json).apply()
                }
            }
        }

        override fun getItemCount() = entries.size
    }
}
