package com.translator.lao.data

/**
 * 词典查询引擎
 * 支持：整句匹配、逐词匹配、部分匹配、分类浏览
 */
object DictionaryStore {

    private val zhToLao = Dictionary.zhToLao
    private val laoToChinese = Dictionary.laoToChinese

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
        val results = mutableListOf<String>()

        // 1. 精确整句匹配
        val exact = dictionary[input]
        if (exact != null) {
            results.add(exact)
            return results
        }

        // 2. 包含匹配（输入是词典key的子串或key包含输入）
        val containsMatches = dictionary.filter { (key, value) ->
            key.contains(input) || input.contains(key)
        }.entries.sortedByDescending { it.key.length }

        if (containsMatches.isNotEmpty()) {
            // 如果有包含匹配，优先返回最长key的匹配
            val bestMatch = containsMatches.first()
            results.add("【${bestMatch.key}】${bestMatch.value}")
            
            // 也返回其他相关匹配（最多5条）
            containsMatches.drop(1).take(5).forEach { entry ->
                results.add("【${entry.key}】${entry.value}")
            }
            return results
        }

        // 3. 逐词拆分匹配
        val words = splitInput(input, isLaoToChinese)
        if (words.size > 1) {
            val translated = words.joinToString(" ") { word ->
                dictionary[word] ?: word
            }
            // 只有当至少有一个词被翻译时才返回
            if (translated != input) {
                results.add(translated)
            }
        }

        // 4. 模糊搜索：遍历词典key，查找相似项
        if (results.isEmpty()) {
            val fuzzyMatches = dictionary.filter { (key, _) ->
                key.any { it in input } || input.any { it in key }
            }.entries.take(10)

            fuzzyMatches.forEach { entry ->
                results.add("【${entry.key}】${entry.value}")
            }
        }

        return results
    }

    /**
     * 按分类获取词条
     * @param category 分类
     * @param isLaoToChinese true=显示老挝→中, false=显示中→老挝
     * @return 该分类下的所有词条列表
     */
    fun getEntriesByCategory(
        category: Dictionary.Category,
        isLaoToChinese: Boolean
    ): List<Pair<String, String>> {
        val dictionary = if (isLaoToChinese) laoToChinese else zhToLao
        val results = mutableListOf<Pair<String, String>>()

        // 使用完整词典，根据分类关键字筛选相关词条
        val allEntries = if (isLaoToChinese) {
            // 老挝→中：返回 老挝语 中文 对
            zhToLao.map { (zh, lao) -> lao.split(" ").first() to zh }
        } else {
            // 中→老挝：返回 中文 老挝语 对
            zhToLao.toList()
        }

        // 按分类关键词筛选
        for ((key, value) in allEntries) {
            if (category.keywords.any { kw -> key.contains(kw) || value.contains(kw) }) {
                if (isLaoToChinese) {
                    results.add(key to value)
                } else {
                    results.add(key to value)
                }
            }
        }

        // 去重并排序
        return results.distinctBy { it.first }.sortedBy { it.first }
    }

    /**
     * 获取分类下的所有词条（完整版，从词典中提取）
     */
    fun getCategoryEntries(
        category: Dictionary.Category,
        isLaoToChinese: Boolean
    ): List<Pair<String, String>> {
        return if (isLaoToChinese) {
            // 从 laoToChinese 中找相关条目
            val results = mutableListOf<Pair<String, String>>()
            // 也从 zhToLao 中取反向
            for ((zh, lao) in zhToLao) {
                val laoWord = lao.split(" ").first()
                if (category.keywords.any { kw -> zh.contains(kw) || laoWord.contains(kw) }) {
                    results.add(laoWord to zh)
                }
            }
            results.distinctBy { it.first }.sortedBy { it.first }
        } else {
            val results = mutableListOf<Pair<String, String>>()
            for ((zh, lao) in zhToLao) {
                if (category.keywords.any { kw -> zh.contains(kw) }) {
                    results.add(zh to lao)
                }
            }
            results.distinctBy { it.first }.sortedBy { it.first }
        }
    }

    /**
     * 智能分词（简单实现）
     */
    private fun splitInput(input: String, isLaoToChinese: Boolean): List<String> {
        return if (isLaoToChinese) {
            // 老挝语按空格分词
            input.split("\\s+".toRegex()).filter { it.isNotBlank() }
        } else {
            // 中文按标点和空格分词
            input.split("[\\s，。！？、；：]+".toRegex()).filter { it.isNotBlank() }
        }
    }

    /**
     * 获取所有分类
     */
    fun getAllCategories(): List<Dictionary.Category> = Dictionary.categories
}
