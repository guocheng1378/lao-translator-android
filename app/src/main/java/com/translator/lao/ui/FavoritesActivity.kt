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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FavoritesActivity : AppCompatActivity() {

    private lateinit var favoritesStore: FavoritesStore
    private var allEntries = listOf<FavoritesStore.FavoriteEntry>()

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, FavoritesActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history) // 复用同一个布局

        favoritesStore = FavoritesStore(this)

        // 改标题
        findViewById<TextView>(R.id.tvTitle).text = "⭐ 我的收藏"

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<View>(R.id.btnClear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空收藏")
                .setMessage("确定要清空所有收藏吗？")
                .setPositiveButton("清空") { _, _ ->
                    favoritesStore.clear()
                    refreshList()
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        findViewById<android.widget.EditText>(R.id.etSearch).hint = "搜索收藏..."
        findViewById<android.widget.EditText>(R.id.etSearch)
            .addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filterList(s?.toString() ?: "")
                }
            })

        findViewById<RecyclerView>(R.id.rvList).layoutManager = LinearLayoutManager(this)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        allEntries = favoritesStore.getAll()
        val tvEmpty = findViewById<TextView>(R.id.tvEmpty)
        val rv = findViewById<RecyclerView>(R.id.rvList)

        if (allEntries.isEmpty()) {
            tvEmpty.text = "暂无收藏\n翻译结果旁点击 ⭐ 可添加收藏"
            tvEmpty.visibility = View.VISIBLE
            rv.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rv.visibility = View.VISIBLE
            rv.adapter = FavAdapter(allEntries.toMutableList())
        }
    }

    private fun filterList(query: String) {
        val filtered = if (query.isBlank()) allEntries
        else allEntries.filter {
            it.source.contains(query, true) || it.target.contains(query, true)
        }
        findViewById<RecyclerView>(R.id.rvList).adapter = FavAdapter(filtered.toMutableList())
    }

    private fun dirLabel(from: String, to: String): String {
        val fromName = if (from == "lo") "老挝语" else "中文"
        val toName = if (to == "lo") "老挝语" else "中文"
        return "$fromName → $toName"
    }

    private fun formatTime(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3600_000 -> "${diff / 60_000} 分钟前"
            diff < 86400_000 -> "${diff / 3600_000} 小时前"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ts))
        }
    }

    inner class FavAdapter(
        private val entries: MutableList<FavoritesStore.FavoriteEntry>
    ) : RecyclerView.Adapter<FavAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
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
            holder.tvTarget.text = entry.target
            holder.tvTime.text = formatTime(entry.timestamp)

            // 收藏按钮始终为已收藏状态
            holder.btnFavorite.setImageResource(R.drawable.ic_star_filled)
            holder.btnFavorite.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos in entries.indices) {
                    favoritesStore.removeFavorite(entry.source, entry.target)
                    entries.removeAt(pos)
                    notifyItemRemoved(pos)
                    notifyItemRangeChanged(pos, entries.size)
                    if (entries.isEmpty()) refreshList()
                    Toast.makeText(this@FavoritesActivity, "已取消收藏", Toast.LENGTH_SHORT).show()
                }
            }

            holder.btnCopy.setOnClickListener {
                val text = "${entry.source}\n${entry.target}"
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("translation", text))
                Toast.makeText(this@FavoritesActivity, "已复制", Toast.LENGTH_SHORT).show()
            }

            holder.btnDelete.setOnClickListener {
                val pos = holder.adapterPosition
                if (pos in entries.indices) {
                    favoritesStore.removeFavorite(entry.source, entry.target)
                    entries.removeAt(pos)
                    notifyItemRemoved(pos)
                    notifyItemRangeChanged(pos, entries.size)
                    if (entries.isEmpty()) refreshList()
                }
            }
        }

        override fun getItemCount() = entries.size
    }
}
