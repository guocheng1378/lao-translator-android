package com.translator.lao.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 简单的本地存储 - 保存翻译历史和用户偏好
 */
class HistoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lao_translator_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_HISTORY = "translation_history"
        private const val KEY_LAST_DIRECTION = "last_direction"
        private const val MAX_HISTORY = 50
    }

    /**
     * 保存一条翻译记录
     */
    fun saveHistory(source: String, result: String, fromLang: String, toLang: String) {
        val history = getHistory().toMutableList()
        val entry = HistoryEntry(source, result, fromLang, toLang, System.currentTimeMillis())
        history.add(0, entry)

        // 只保留最近 MAX_HISTORY 条
        while (history.size > MAX_HISTORY) {
            history.removeAt(history.size - 1)
        }

        val json = history.joinToString("|||") { entry ->
            "${entry.source}:::${entry.result}:::${entry.fromLang}:::${entry.toLang}:::${entry.timestamp}"
        }
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }

    /**
     * 获取翻译历史
     */
    fun getHistory(): List<HistoryEntry> {
        val json = prefs.getString(KEY_HISTORY, "") ?: ""
        if (json.isBlank()) return emptyList()

        return json.split("|||").mapNotNull { item ->
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
                } catch (e: Exception) { null }
            } else null
        }
    }

    /**
     * 清除历史
     */
    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    /**
     * 保存上次选择的翻译方向
     */
    fun saveDirection(from: String, to: String) {
        prefs.edit().putString(KEY_LAST_DIRECTION, "$from|$to").apply()
    }

    /**
     * 获取上次选择的翻译方向
     */
    fun getLastDirection(): Pair<String, String> {
        val dir = prefs.getString(KEY_LAST_DIRECTION, "lo|zh") ?: "lo|zh"
        val parts = dir.split("|")
        return Pair(parts[0], parts.getOrElse(1) { "zh" })
    }

    data class HistoryEntry(
        val source: String,
        val result: String,
        val fromLang: String,
        val toLang: String,
        val timestamp: Long
    )
}
