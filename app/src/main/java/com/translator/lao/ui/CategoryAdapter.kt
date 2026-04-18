package com.translator.lao.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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
    }

    override fun getItemCount() = entries.size
}
