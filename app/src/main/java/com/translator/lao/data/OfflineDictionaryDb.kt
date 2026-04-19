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
        private const val DB_VERSION = 1

        private const val TABLE_DICT = "dictionary"
        private const val COL_ID = "_id"
        private const val COL_ZH = "zh"
        private const val COL_LO = "lao"

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
        db.execSQL("CREATE TABLE $TABLE_DICT ($COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, $COL_ZH TEXT NOT NULL, $COL_LO TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_zh ON $TABLE_DICT($COL_ZH)")
        db.execSQL("CREATE INDEX idx_lo ON $TABLE_DICT($COL_LO)")

        db.execSQL("CREATE TABLE $TABLE_FAVORITES ($COL_FAV_ZH TEXT NOT NULL, $COL_FAV_LO TEXT NOT NULL, $COL_FAV_TS INTEGER NOT NULL, UNIQUE($COL_FAV_ZH, $COL_FAV_LO))")

        db.execSQL("CREATE TABLE $TABLE_HISTORY ($COL_HIS_ID INTEGER PRIMARY KEY AUTOINCREMENT, $COL_HIS_QUERY TEXT NOT NULL, $COL_HIS_TS INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DICT")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_FAVORITES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
        onCreate(db)
    }

    fun importFromMemory() {
        val db = writableDatabase
        val count = db.rawQuery("SELECT COUNT(*) FROM $TABLE_DICT", null).use {
            it.moveToFirst(); it.getInt(0)
        }
        if (count > 0) return

        db.beginTransaction()
        try {
            for ((zh, lao) in Dictionary.zhToLao) {
                db.insert(TABLE_DICT, null, ContentValues().apply {
                    put(COL_ZH, zh)
                    put(COL_LO, lao)
                })
            }
            db.setTransactionSuccessful()
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
