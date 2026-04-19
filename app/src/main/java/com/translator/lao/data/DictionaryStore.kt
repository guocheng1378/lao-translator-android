package com.translator.lao.data

/**
 * 词典查询引擎
 * 支持：精确匹配、前缀匹配、逐词匹配、分类浏览
 */
object DictionaryStore {

    private val zhToLao = Dictionary.zhToLao
    private val laoToChinese = Dictionary.laoToChinese

    // 按 key 长度降序排列的缓存，加速包含匹配
    private val zhToLaoSorted by lazy {
        zhToLao.entries.sortedByDescending { it.key.length }
    }
    private val laoToChineseSorted by lazy {
        laoToChinese.entries.sortedByDescending { it.key.length }
    }

    /**
     * 翻译入口
     * @param text 输入文本
     * @param isLaoToChinese true=老挝→中, false=中→老挝
     * @return 翻译结果列表（可能有多条匹配）
     */
    fun translate(text: String, isLaoToChinese: Boolean): List<String> {
        val input = text.trim()
        if (input.isEmpty()) return emptyList()

        val dictionary = if (isLaoToChinese) laoToChinese else zhToLao
        val sorted = if (isLaoToChinese) laoToChineseSorted else zhToLaoSorted
        val results = mutableListOf<String>()

        // 1. 精确整句匹配
        val exact = dictionary[input]
        if (exact != null) {
            results.add(exact)
            return results
        }

        // 2. 包含匹配：key 包含 input 或 input 包含 key
        //    取最长 key 的匹配（已排序），最多返回 6 条
        var found = 0
        for (entry in sorted) {
            if (entry.key.contains(input) || input.contains(entry.key)) {
                results.add("【${entry.key}】${entry.value}")
                found++
                if (found >= 6) break
            }
        }
        if (results.isNotEmpty()) return results

        // 3. 逐词拆分匹配
        val words = splitInput(input, isLaoToChinese)
        if (words.size > 1) {
            var anyTranslated = false
            val translated = words.joinToString(" ") { word ->
                val t = dictionary[word]
                if (t != null) anyTranslated = true
                t ?: word
            }
            if (anyTranslated) {
                results.add(translated)
            }
        }

        // 4. 前缀匹配（只在无结果时触发，限制扫描范围）
        if (results.isEmpty()) {
            val prefixMatches = sorted.filter { it.key.startsWith(input) || input.startsWith(it.key) }
                .take(5)
            prefixMatches.forEach { entry ->
                results.add("【${entry.key}】${entry.value}")
            }
        }

        return results
    }

    /**
     * 获取分类下的所有词条
     * @param category 分类
     * @param isLaoToChinese true=显示老挝→中, false=显示中→老挝
     */
    fun getCategoryEntries(
        category: Dictionary.Category,
        isLaoToChinese: Boolean
    ): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()

        for ((zh, lao) in zhToLao) {
            val laoWord = lao.split(" ").first()
            val matches = category.keywords.any { kw ->
                if (isLaoToChinese) zh.contains(kw) || laoWord.contains(kw)
                else zh.contains(kw)
            }
            if (matches) {
                if (isLaoToChinese) {
                    results.add(laoWord to zh)
                } else {
                    results.add(zh to lao)
                }
            }
        }

        return results.distinctBy { it.first }.sortedBy { it.first }
    }

    /**
     * 智能分词
     */
    private fun splitInput(input: String, isLaoToChinese: Boolean): List<String> {
        return if (isLaoToChinese) {
            input.split("\\s+".toRegex()).filter { it.isNotBlank() }
        } else {
            input.split("[\\s，。！？、；：]+".toRegex()).filter { it.isNotBlank() }
        }
    }

    fun getAllCategories(): List<Dictionary.Category> = Dictionary.categories
}
