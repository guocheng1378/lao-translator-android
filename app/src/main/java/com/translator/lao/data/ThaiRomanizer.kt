package com.translator.lao.data

/**
 * 泰语 → 罗马拼音 转换器
 *
 * 用于将 Google SpeechRecognizer 输出的泰语文本转为罗马拼音，
 * 再通过拼音模糊匹配搜索老挝语词典。
 *
 * 基于 RTGS (Royal Thai General System) 简化映射。
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

    // 泰语元音标记 → 罗马拼音
    private val vowelMarks = mapOf(
        '\u0E30' to "a",   // sara a
        '\u0E31' to "a",   // mai han-akat (short a above)
        '\u0E32' to "a",   // sara aa (long a)
        '\u0E33' to "am",  // sara am
        '\u0E34' to "i",   // sara i
        '\u0E35' to "i",   // sara ii
        '\u0E36' to "ue",  // sara ue
        '\u0E37' to "ue",  // sara uue
        '\u0E38' to "u",   // sara u
        '\u0E39' to "u",   // sara uu
        '\u0E40' to "e",   // sara e (leading)
        '\u0E41' to "ae",  // sara ae (leading)
        '\u0E42' to "o",   // sara o (leading)
        '\u0E43' to "ai",  // sara ai maimuan (leading)
        '\u0E44' to "ai",  // sara ai maimalai (leading)
        '\u0E45' to "oe",  // lakhonyy
        '\u0E47' to "",    // maitaikhu (vowel shortener, skip)
        '\u0E48' to "",    // mai ek (tone)
        '\u0E49' to "",    // mai tho (tone)
        '\u0E4A' to "",    // mai tri (tone)
        '\u0E4B' to "",    // mai chattawa (tone)
        '\u0E4C' to "",    // thanthakhat (skip)
        '\u0E4D' to "",    // nikkhahit (skip)
        '\u0E4E' to "",    // yamakkan (skip)
    )

    // 泰语数字
    private val thaiDigits = mapOf(
        '๐' to "0", '๑' to "1", '๒' to "2", '๓' to "3", '๔' to "4",
        '๕' to "5", '๖' to "6", '๗' to "7", '๘' to "8", '๙' to "9",
    )

    // 独立元音（sara e/ae/o 前置 + 辅音的组合）
    private val leadingVowels = setOf('\u0E40', '\u0E41', '\u0E42', '\u0E43', '\u0E44')

    /**
     * 将泰语文本转为罗马拼音
     * 例: "สวัสดี" → "sawatdi"
     * 例: "ขอบคุณ" → "khobkhun"
     */
    fun romanize(thaiText: String): String {
        if (thaiText.isEmpty()) return ""

        val sb = StringBuilder()
        val chars = thaiText.toCharArray()
        var i = 0

        while (i < chars.size) {
            val c = chars[i]

            // 泰语数字
            if (c in thaiDigits) {
                sb.append(thaiDigits[c])
                i++
                continue
            }

            // 前置元音 (sara e/ae/o) - 输出元音再处理后面的辅音
            if (c in leadingVowels) {
                sb.append(vowelMarks[c] ?: "")
                i++
                continue
            }

            // 辅音
            if (c in consonants) {
                sb.append(consonants[c])
                i++
                continue
            }

            // 元音标记和声调标记
            if (c in vowelMarks) {
                sb.append(vowelMarks[c])
                i++
                continue
            }

            // 非泰语字符直接保留
            sb.append(c)
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
     * 检测文本是否包含泰语字符
     */
    fun containsThai(text: String): Boolean {
        return text.any { it.code in 0x0E01..0x0E5B }
    }
}
