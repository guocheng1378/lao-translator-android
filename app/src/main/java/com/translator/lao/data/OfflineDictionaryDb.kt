package com.translator.lao.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class OfflineDictionaryDb(context: Context) : SQLiteOpenHelper(
    context, DB_NAME, null, DB_VERSION
) {

    companion object {
        private const val DB_NAME = "lao_dictionary.db"
        private const val DB_VERSION = 2

        private const val TABLE_DICT = "dictionary"
        private const val COL_ID = "_id"
        private const val COL_ZH = "zh"
        private const val COL_LO = "lao"
        private const val COL_ROM = "romanization"

        private const val TABLE_FAVORITES = "favorites"
        private const val COL_FAV_ZH = "zh"
        private const val COL_FAV_LO = "lao"
        private const val COL_FAV_TS = "ts"

        private const val TABLE_HISTORY = "search_history"
        private const val COL_HIS_ID = "_id"
        private const val COL_HIS_QUERY = "query"
        private const val COL_HIS_TS = "ts"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE $TABLE_DICT ($COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, $COL_ZH TEXT NOT NULL, $COL_LO TEXT NOT NULL, $COL_ROM TEXT)")
        db.execSQL("CREATE INDEX idx_zh ON $TABLE_DICT($COL_ZH)")
        db.execSQL("CREATE INDEX idx_lo ON $TABLE_DICT($COL_LO)")
        db.execSQL("CREATE INDEX idx_rom ON $TABLE_DICT($COL_ROM)")

        db.execSQL("CREATE TABLE $TABLE_FAVORITES ($COL_FAV_ZH TEXT NOT NULL, $COL_FAV_LO TEXT NOT NULL, $COL_FAV_TS INTEGER NOT NULL, UNIQUE($COL_FAV_ZH, $COL_FAV_LO))")

        db.execSQL("CREATE TABLE $TABLE_HISTORY ($COL_HIS_ID INTEGER PRIMARY KEY AUTOINCREMENT, $COL_HIS_QUERY TEXT NOT NULL, $COL_HIS_TS INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            // v1 → v2: 添加罗马拼音列（安全添加，列已存在时忽略）
            try {
                db.execSQL("ALTER TABLE $TABLE_DICT ADD COLUMN $COL_ROM TEXT")
            } catch (_: Exception) {
                // 列已存在，忽略
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_rom ON $TABLE_DICT($COL_ROM)")
            // 清空数据，下次 importFromMemory 会重新导入带拼音的数据
            db.execSQL("DELETE FROM $TABLE_DICT")
        }
    }

    fun importFromMemory() {
        val db = writableDatabase
        val count = db.rawQuery("SELECT COUNT(*) FROM $TABLE_DICT", null).use {
            it.moveToFirst(); it.getInt(0)
        }
        // 检查已有数据的完整性：如果条目存在但拼音全部为空，说明是从 v1 升级的残留数据
        if (count > 0) {
            val romCount = db.rawQuery("SELECT COUNT(*) FROM $TABLE_DICT WHERE $COL_ROM IS NOT NULL AND $COL_ROM != ''", null).use {
                it.moveToFirst(); it.getInt(0)
            }
            if (romCount == 0 && count > 0) {
                android.util.Log.w("DictDb", "Found $count entries with no romanization, re-importing...")
                db.execSQL("DELETE FROM $TABLE_DICT")
                // 继续执行导入
            } else {
                android.util.Log.d("DictDb", "Dictionary has $count entries ($romCount with romanization), OK")
                return
            }
        }

        var imported = 0
        db.beginTransaction()
        try {
            for ((zh, lao) in Dictionary.zhToLao) {
                // 提取罗马拼音部分
                val rom = try { ThaiRomanizer.extractRomanization(lao) } catch (_: Exception) { null }
                db.insert(TABLE_DICT, null, ContentValues().apply {
                    put(COL_ZH, zh)
                    put(COL_LO, lao)
                    put(COL_ROM, rom)
                })
                imported++
            }
            db.setTransactionSuccessful()
            android.util.Log.d("DictDb", "Imported $imported dictionary entries")
        } catch (e: Exception) {
            android.util.Log.e("DictDb", "Import failed after $imported entries", e)
        } finally {
            db.endTransaction()
        }
    }

    fun search(query: String, isLaoToChinese: Boolean, limit: Int = 50): List<Pair<String, String>> {
        val db = readableDatabase
        val results = mutableListOf<Pair<String, String>>()
        val q = query.trim()
        if (q.isEmpty()) return results

        val col = if (isLaoToChinese) COL_LO else COL_ZH
        val ret = if (isLaoToChinese) COL_ZH else COL_LO

        // 精确
        db.rawQuery("SELECT $col, $ret FROM $TABLE_DICT WHERE $col = ? LIMIT 1", arrayOf(q)).use {
            if (it.moveToFirst()) return listOf(it.getString(0) to it.getString(1))
        }

        // 前缀
        db.rawQuery("SELECT $col, $ret FROM $TABLE_DICT WHERE $col LIKE ? ORDER BY LENGTH($col) LIMIT ?", arrayOf("$q%", limit.toString())).use {
            while (it.moveToFirst()) {
                results.add(it.getString(0) to it.getString(1))
                if (!it.moveToNext()) break
            }
        }

        // 包含
        if (results.size < 5) {
            db.rawQuery("SELECT $col, $ret FROM $TABLE_DICT WHERE $col LIKE ? ORDER BY LENGTH($col) LIMIT ?", arrayOf("%$q%", (limit - results.size).toString())).use {
                while (it.moveToFirst()) {
                    val p = it.getString(0) to it.getString(1)
                    if (p !in results) results.add(p)
                    if (!it.moveToNext()) break
                }
            }
        }

        return results
    }

    /**
     * 通过罗马拼音模糊搜索（用于泰语语音识别结果匹配老挝语词典）
     *
     * @param romanizedQuery 泰语罗马化后的拼音，如 "sawatdi"
     * @param limit 最大返回数
     * @return 匹配的 (中文, 老挝语) 列表，按相似度排序
     */
    fun searchByRomanization(romanizedQuery: String, limit: Int = 30): List<Pair<String, String>> {
        val db = readableDatabase
        val q = romanizedQuery.trim().lowercase()
        if (q.isEmpty() || q.length < 2) return emptyList()

        // 先尝试精确/前缀匹配
        val exactResults = mutableListOf<Pair<String, String>>()
        db.rawQuery(
            "SELECT $COL_ZH, $COL_LO, $COL_ROM FROM $TABLE_DICT WHERE $COL_ROM IS NOT NULL AND $COL_ROM != '' AND ($COL_ROM = ? OR $COL_ROM LIKE ?) LIMIT ?",
            arrayOf(q, "$q%", limit.toString())
        ).use {
            while (it.moveToFirst()) {
                exactResults.add(it.getString(0) to it.getString(1))
                if (!it.moveToNext()) break
            }
        }
        if (exactResults.isNotEmpty()) return exactResults

        // 模糊匹配：遍历所有有拼音的条目，计算相似度
        val scored = mutableListOf<Triple<String, String, Double>>()
        db.rawQuery(
            "SELECT $COL_ZH, $COL_LO, $COL_ROM FROM $TABLE_DICT WHERE $COL_ROM IS NOT NULL AND $COL_ROM != ''",
            null
        ).use {
            while (it.moveToFirst()) {
                val rom = it.getString(2)
                // 处理多拼音条目（如 "khop jai" 可能包含空格分隔的词）
                val romParts = rom.split(" ")
                val bestScore = romParts.maxOfOrNull { part ->
                    ThaiRomanizer.similarity(q, part)
                } ?: 0.0

                // 也检查完整拼音
                val fullScore = ThaiRomanizer.similarity(q, rom)
                val score = maxOf(bestScore, fullScore)

                if (score >= 0.55) {
                    scored.add(Triple(it.getString(0), it.getString(1), score))
                }
                if (!it.moveToNext()) break
            }
        }

        return scored
            .sortedByDescending { it.third }
            .take(limit)
            .map { it.first to it.second }
    }

    fun addFavorite(zh: String, lao: String) {
        writableDatabase.insertWithOnConflict(
            TABLE_FAVORITES, null,
            ContentValues().apply {
                put(COL_FAV_ZH, zh); put(COL_FAV_LO, lao)
                put(COL_FAV_TS, System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun removeFavorite(zh: String, lao: String) {
        writableDatabase.delete(TABLE_FAVORITES, "$COL_FAV_ZH=? AND $COL_FAV_LO=?", arrayOf(zh, lao))
    }

    fun isFavorite(zh: String, lao: String): Boolean {
        readableDatabase.rawQuery("SELECT 1 FROM $TABLE_FAVORITES WHERE $COL_FAV_ZH=? AND $COL_FAV_LO=? LIMIT 1", arrayOf(zh, lao)).use {
            return it.moveToFirst()
        }
    }

    fun getAllFavorites(): List<Pair<String, String>> {
        val r = mutableListOf<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT $COL_FAV_ZH, $COL_FAV_LO FROM $TABLE_FAVORITES ORDER BY $COL_FAV_TS DESC", null).use {
            while (it.moveToFirst()) {
                r.add(it.getString(0) to it.getString(1))
                if (!it.moveToNext()) break
            }
        }
        return r
    }

    fun addHistory(query: String) {
        val db = writableDatabase
        db.delete(TABLE_HISTORY, "$COL_HIS_QUERY=?", arrayOf(query))
        db.insert(TABLE_HISTORY, null, ContentValues().apply {
            put(COL_HIS_QUERY, query); put(COL_HIS_TS, System.currentTimeMillis())
        })
        db.execSQL("DELETE FROM $TABLE_HISTORY WHERE $COL_HIS_ID NOT IN (SELECT $COL_HIS_ID FROM $TABLE_HISTORY ORDER BY $COL_HIS_TS DESC LIMIT 50)")
    }

    fun getRecent(limit: Int = 20): List<String> {
        val r = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT $COL_HIS_QUERY FROM $TABLE_HISTORY ORDER BY $COL_HIS_TS DESC LIMIT ?", arrayOf(limit.toString())).use {
            while (it.moveToFirst()) {
                r.add(it.getString(0))
                if (!it.moveToNext()) break
            }
        }
        return r
    }

    fun clearHistory() {
        writableDatabase.delete(TABLE_HISTORY, null, null)
    }
}
