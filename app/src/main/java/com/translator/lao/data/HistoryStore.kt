package com.translator.lao.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 翻译历史存储 — 使用 JSON 序列化，安全可靠
 */
class HistoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lao_translator_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_HISTORY = "translation_history_v2"
        private const val KEY_HISTORY_LEGACY = "translation_history"
        private const val MAX_HISTORY = 50
    }

    data class HistoryEntry(
        val source: String,
        val result: String,
        val fromLang: String,
        val toLang: String,
        val timestamp: Long
    )

    /**
     * 保存一条翻译记录
     */
    fun saveHistory(source: String, result: String, fromLang: String, toLang: String) {
        val history = getHistory().toMutableList()
        // 去重：同样的 source+result 不重复添加，移到最前
        history.removeAll { it.source == source && it.result == result }
        history.add(0, HistoryEntry(source, result, fromLang, toLang, System.currentTimeMillis()))
        while (history.size > MAX_HISTORY) {
            history.removeAt(history.size - 1)
        }
        saveAll(history)
    }

    /**
     * 删除一条历史记录
     */
    fun deleteHistory(source: String, result: String) {
        val history = getHistory().filterNot { it.source == source && it.result == result }
        saveAll(history)
    }

    /**
     * 清除所有历史
     */
    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    /**
     * 获取翻译历史
     */
    fun getHistory(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null)
        if (!raw.isNullOrBlank()) {
            return parseJsonArray(raw)
        }
        // 兼容旧格式
        return migrateLegacy()
    }

    private fun saveAll(entries: List<HistoryEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().apply {
                put("s", e.source)
                put("r", e.result)
                put("fl", e.fromLang)
                put("tl", e.toLang)
                put("t", e.timestamp)
            })
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun parseJsonArray(raw: String): List<HistoryEntry> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                try {
                    HistoryEntry(
                        source = obj.getString("s"),
                        result = obj.getString("r"),
                        fromLang = obj.getString("fl"),
                        toLang = obj.getString("tl"),
                        timestamp = obj.getLong("t")
                    )
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 迁移旧的 `|||`/`:::` 格式到 JSON
     */
    private fun migrateLegacy(): List<HistoryEntry> {
        val legacy = prefs.getString(KEY_HISTORY_LEGACY, "") ?: ""
        if (legacy.isBlank()) return emptyList()

        val entries = legacy.split("|||").mapNotNull { item ->
            val parts = item.split(":::")
            if (parts.size >= 5) {
                try {
                    HistoryEntry(
                        source = parts[0],
                        result = parts[1],
                        fromLang = parts[2],
                        toLang = parts[3],
                        timestamp = parts[4].toLong()
                    )
                } catch (_: Exception) { null }
            } else null
        }

        // 迁移后保存为新格式，删除旧 key
        if (entries.isNotEmpty()) {
            saveAll(entries)
        }
        prefs.edit().remove(KEY_HISTORY_LEGACY).apply()
        return entries
    }

    fun saveDirection(from: String, to: String) {
        prefs.edit().putString("last_direction", "$from|$to").apply()
    }

    fun getLastDirection(): Pair<String, String> {
        val dir = prefs.getString("last_direction", "lo|zh") ?: "lo|zh"
        val parts = dir.split("|")
        return Pair(parts[0], parts.getOrElse(1) { "zh" })
    }
}
