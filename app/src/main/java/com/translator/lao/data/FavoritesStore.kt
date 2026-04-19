package com.translator.lao.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 收藏管理器 — 使用 JSON 序列化，安全可靠
 */
class FavoritesStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lao_translator_favorites_v2", Context.MODE_PRIVATE)

    private val legacyPrefs: SharedPreferences =
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
        val raw = prefs.getString(KEY_FAVORITES, null)
        if (!raw.isNullOrBlank()) {
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                    try {
                        FavoriteEntry(
                            source = obj.getString("s"),
                            target = obj.getString("r"),
                            fromLang = obj.getString("fl"),
                            toLang = obj.getString("tl"),
                            timestamp = obj.getLong("t")
                        )
                    } catch (_: Exception) { null }
                }
            } catch (_: Exception) { emptyList() }
        }
        return migrateLegacy()
    }

    fun clear() {
        prefs.edit().remove(KEY_FAVORITES).apply()
    }

    private fun save(list: List<FavoriteEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("s", e.source)
                put("r", e.target)
                put("fl", e.fromLang)
                put("tl", e.toLang)
                put("t", e.timestamp)
            })
        }
        prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
    }

    private fun migrateLegacy(): List<FavoriteEntry> {
        val raw = legacyPrefs.getString(KEY_FAVORITES, "") ?: ""
        if (raw.isBlank()) return emptyList()

        val entries = raw.split("|||").mapNotNull { item ->
            val p = item.split(":::")
            if (p.size >= 5) {
                try {
                    FavoriteEntry(p[0], p[1], p[2], p[3], p[4].toLong())
                } catch (_: Exception) { null }
            } else null
        }

        if (entries.isNotEmpty()) {
            save(entries)
        }
        legacyPrefs.edit().remove(KEY_FAVORITES).apply()
        return entries
    }
}
