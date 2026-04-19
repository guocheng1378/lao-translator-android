package com.translator.lao.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 收藏管理器 - 保存用户收藏的翻译词条
 */
class FavoritesStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lao_translator_favorites", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FAVORITES = "favorites"
    }

    data class FavoriteEntry(
        val source: String,
        val target: String,
        val fromLang: String,
        val toLang: String,
        val timestamp: Long
    )

    fun addFavorite(source: String, target: String, fromLang: String, toLang: String) {
        val list = getAll().toMutableList()
        // 去重：同样的 source+target 不重复添加
        if (list.any { it.source == source && it.target == target }) return
        list.add(0, FavoriteEntry(source, target, fromLang, toLang, System.currentTimeMillis()))
        save(list)
    }

    fun removeFavorite(source: String, target: String) {
        val list = getAll().filterNot { it.source == source && it.target == target }
        save(list)
    }

    fun isFavorite(source: String, target: String): Boolean {
        return getAll().any { it.source == source && it.target == target }
    }

    fun getAll(): List<FavoriteEntry> {
        val raw = prefs.getString(KEY_FAVORITES, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("|||").mapNotNull { item ->
            val p = item.split(":::")
            if (p.size >= 5) {
                try {
                    FavoriteEntry(p[0], p[1], p[2], p[3], p[4].toLong())
                } catch (_: Exception) { null }
            } else null
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_FAVORITES).apply()
    }

    private fun save(list: List<FavoriteEntry>) {
        val raw = list.joinToString("|||") {
            "${it.source}:::${it.target}:::${it.fromLang}:::${it.toLang}:::${it.timestamp}"
        }
        prefs.edit().putString(KEY_FAVORITES, raw).apply()
    }
}
