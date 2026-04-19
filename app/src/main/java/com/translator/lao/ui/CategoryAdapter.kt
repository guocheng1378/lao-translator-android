package com.translator.lao.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.translator.lao.R
import com.translator.lao.data.FavoritesStore
import com.translator.lao.databinding.ItemCategoryEntryBinding

/**
 * 分类词条列表适配器
 */
class CategoryAdapter(
    private val entries: List<Pair<String, String>>
) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

    var onItemClick: ((Pair<String, String>) -> Unit)? = null

    class ViewHolder(val binding: ItemCategoryEntryBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCategoryEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (source, target) = entries[position]
        holder.binding.tvSource.text = source
        holder.binding.tvTarget.text = target

        holder.itemView.setOnClickListener {
            onItemClick?.invoke(entries[position])
        }

        // 收藏按钮
        val context = holder.itemView.context
        val favStore = FavoritesStore(context)
        val isFav = favStore.isFavorite(source, target)
        holder.binding.btnFavorite.setImageResource(
            if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star
        )

        holder.binding.btnFavorite.setOnClickListener {
            if (favStore.isFavorite(source, target)) {
                favStore.removeFavorite(source, target)
                holder.binding.btnFavorite.setImageResource(R.drawable.ic_star)
                Toast.makeText(context, "已取消收藏", Toast.LENGTH_SHORT).show()
            } else {
                // 分类词库没有语言方向，按默认 lao→zh 或 zh→lao 处理
                favStore.addFavorite(source, target, "lo", "zh")
                holder.binding.btnFavorite.setImageResource(R.drawable.ic_star_filled)
                Toast.makeText(context, "已收藏", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun getItemCount() = entries.size
}
