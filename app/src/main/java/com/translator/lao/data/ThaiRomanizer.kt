package com.translator.lao.data

/**
 * 泰语 → 罗马拼音 转换器
 *
 * 用于将 Google SpeechRecognizer 输出的泰语文本转为罗马拼音，
 * 再通过拼音模糊匹配搜索老挝语词典。
 *
 * 基于 RTGS (Royal Thai General System) + 常用变体映射。
 */
object ThaiRomanizer {

    // 泰语辅音 → 罗马拼音
    private val consonants = mapOf(
        'ก' to "k", 'ข' to "kh", 'ฃ' to "kh", 'ค' to "kh", 'ฅ' to "kh",
        'ฆ' to "kh", 'ง' to "ng", 'จ' to "j", 'ฉ' to "ch", 'ช' to "ch",
        'ซ' to "s", 'ฌ' to "ch", 'ญ' to "y", 'ฎ' to "d", 'ฏ' to "t",
        'ฐ' to "th", 'ฑ' to "th", 'ฒ' to "th", 'ณ' to "n", 'ด' to "d",
        'ต' to "t", 'ถ' to "th", 'ท' to "th", 'ธ' to "th", 'น' to "n",
        'บ' to "b", 'ป' to "p", 'ผ' to "ph", 'ฝ' to "f", 'พ' to "ph",
        'ฟ' to "f", 'ภ' to "ph", 'ม' to "m", 'ย' to "y", 'ร' to "r",
        'ล' to "l", 'ว' to "w", 'ศ' to "s", 'ษ' to "s", 'ส' to "s",
        'ห' to "h", 'ฬ' to "l", 'อ' to "", 'ฮ' to "h",
    )

    // 泰语元音（含组合元音，需按顺序匹配）
    private val vowels = listOf(
        // 长元音
        "เ\uE34ีย" to "ia", "เ\uE34ียะ" to "ia",
        "\uE34ัว" to "ua", "\uE34ัวะ" to "ua",
        "เ\uE34า" to "ao",
        "แ\uE34ะ" to "ae", "แ\uE34" to "ae",
        "เ\uE34อะ" to "oe", "เ\uE34อ" to "oe",
        "เ\uE34าะ" to "o",
        // 短元音
        "เ\uE34ะ" to "e", "เ\uE34" to "e",
        "แ\uE34ะ" to "ae",
        "เ\uE34อะ" to "oe",
        // 基本元音
        "\uE34า" to "a", "\uE34ำ" to "am",
        "\uE34ุ" to "u", "\uE34ู" to "u",
        "\uE34ิ" to "i", "\uE34ี" to "i",
        "\uE34ึ" to "ue", "\uE34ือ" to "ue",
        "\uE34ะ" to "a", "\uE34ั" to "a",
        "\uE34็" to "o",
        "โ\uE34ะ" to "o", "โ\uE34" to "o",
        "เ\uE34" to "e", "แ\uE34" to "ae",
        "ไ\uE34" to "ai", "ใ\uE34" to "ai",
        "\uE34" to "a", // fallback
    )

    // 泰语声调标记（忽略，不影响拼音匹配）
    private val toneMarks = setOf('\u0E48', '\u0E49', '\u0E4A', '\u0E4B')

    // 泰语数字
    private val thaiDigits = mapOf(
        '๐' to '0', '๑' to '1', '๒' to '2', '๓' to '3', '๔' to '4',
        '๕' to '5', '๖' to '6', '๗' to '7', '๘' to '8', '๙' to '9',
    )

    /**
     * 将泰语文本转为罗马拼音
     * 例: "สวัสดี" → "sawatdi"
     */
    fun romanize(thaiText: String): String {
        if (thaiText.isEmpty()) return ""

        val sb = StringBuilder()
        var i = 0
        val chars = thaiText.toCharArray()

        while (i < chars.size) {
            val c = chars[i]

            // 跳过声调标记
            if (c in toneMarks) { i++; continue }

            // 泰语数字
            if (c in thaiDigits) { sb.append(thaiDigits[c]); i++; continue }

            // 非泰语字符直接保留
            if (c.code < 0x0E01 || c.code > 0x0E5B) {
                sb.append(c); i++; continue
            }

            // 尝试匹配组合元音（最多往前看3个字符）
            var matched = false
            for (len in 4 downTo 2) {
                if (i + len <= chars.size) {
                    val sub = String(chars, i, len)
                    // 检查是否是组合元音模式
                    val key = vowels.find { it.first == sub }
                    if (key != null) {
                        sb.append(key.second)
                        i += len
                        matched = true
                        break
                    }
                }
            }

            if (matched) continue

            // 辅音
            if (c in consonants) {
                sb.append(consonants[c])
                i++
                continue
            }

            // 独立元音标记
            if (c.code in 0x0E30..0x0E3A || c.code in 0x0E40..0x0E44) {
                val romanized = when (c) {
                    '\u0E30' -> "a"
                    '\u0E31' -> "a"
                    '\u0E32' -> "a"
                    '\u0E33' -> "am"
                    '\u0E34' -> "i"
                    '\u0E35' -> "i"
                    '\u0E36' -> "ue"
                    '\u0E37' -> "ue"
                    '\u0E38' -> "u"
                    '\u0E39' -> "u"
                    '\u0E40' -> "e"
                    '\u0E41' -> "ae"
                    '\u0E42' -> "o"
                    '\u0E43' -> "ai"
                    '\u0E44' -> "ai"
                    '\u0E45' -> "oe" // ๅ
                    else -> ""
                }
                sb.append(romanized)
                i++
                continue
            }

            // 其他泰语字符跳过
            i++
        }

        return sb.toString().lowercase()
    }

    /**
     * 从词典条目中提取罗马拼音部分
     * 输入: "ສະບາຍດີ (sabaidee)" → 输出: "sabaidee"
     * 输入: "ປາ" → 输出: null
     */
    fun extractRomanization(dictValue: String): String? {
        val start = dictValue.indexOf('(')
        val end = dictValue.indexOf(')')
        if (start < 0 || end <= start) return null
        return dictValue.substring(start + 1, end).trim().lowercase()
    }

    /**
     * Levenshtein 编辑距离
     */
    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m

        val dp = IntArray(n + 1) { it }
        for (i in 1..m) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..n) {
                val temp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) {
                    prev
                } else {
                    1 + minOf(prev, dp[j], dp[j - 1])
                }
                prev = temp
            }
        }
        return dp[n]
    }

    /**
     * 拼音相似度 (0.0 ~ 1.0)
     */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = maxOf(a.length, b.length)
        val dist = levenshtein(a, b)
        return 1.0 - (dist.toDouble() / maxLen)
    }

    /**
     * 判断泰语罗马拼音是否与老挝语罗马拼音足够接近
     * 阈值 0.6 比较宽松，适合泰-老语音差异
     */
    fun isMatch(thaiRomanized: String, laoRomanized: String): Boolean {
        // 精确匹配
        if (thaiRomanized == laoRomanized) return true

        // 前缀匹配（泰语输入可能是截断的）
        if (laoRomanized.startsWith(thaiRomanized) && thaiRomanized.length >= 3) return true
        if (thaiRomanized.startsWith(laoRomanized) && laoRomanized.length >= 3) return true

        // 模糊匹配
        return similarity(thaiRomanized, laoRomanized) >= 0.6
    }

    /**
     * 检测文本是否包含泰语字符
     */
    fun containsThai(text: String): Boolean {
        return text.any { it.code in 0x0E01..0x0E5B }
    }
}
