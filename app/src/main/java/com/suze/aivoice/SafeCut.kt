package com.suze.aivoice

/**
 * 安全的字符串截断工具。
 *
 * Kotlin 的 `String.length` / `take(n)` / `substring(n)` 都按 **UTF-16 代码单元**计数，
 * 而 emoji、生僻汉字（如 𠮷）、部分符号由**代理对**（surrogate pair，2 个代码单元）组成。
 *
 * 如果截断点恰好落在代理对中间，就会留下**孤立的高代理**（如 \uD83D），
 * 在 TextView / TTS / JSON 里显示成 `�` 乱码，甚至导致 JSON 序列化失败。
 *
 * 这个工具保证：截断后永远不会留下半个字符。
 */
object SafeCut {

    /**
     * 取前 [max] 个「显示字符」（按 Unicode 码点，而不是 UTF-16 单元），
     * 且不会切断代理对。
     *
     * @param max 上限，按码点计（1 个 emoji / 生僻字算 1）
     */
    fun takeChars(s: String, max: Int): String {
        if (max <= 0) return ""
        if (s.length <= max) return s   // 快路径：单元数都没超，肯定安全
        var count = 0
        var i = 0
        while (i < s.length && count < max) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            count++
        }
        return s.substring(0, i)
    }

    /**
     * 保证字符串尾部不是孤立的高代理。
     * 用于「按 UTF-16 单元截断」之后做一次兜底修复：若最后一个字符是高代理，就去掉它。
     */
    fun trimLoneSurrogate(s: String): String {
        if (s.isEmpty()) return s
        val last = s[s.length - 1]
        if (last.isHighSurrogate()) {
            // 高代理必须跟一个低代理才成对；落在末尾就是坏的，去掉
            return s.substring(0, s.length - 1)
        }
        return s
    }

    /**
     * 按 UTF-16 单元截断到 [maxUnits]，但**自动回避代理对切半**。
     *
     * 与 `s.take(maxUnits)` 语义接近，但绝不产生孤立代理。
     */
    /**
     * 求 a 的后缀与 b 的前缀的最长公共长度（重叠），并保证不会切成代理对中间。
     *
     * 用于流式拼接：当接口返回的片段与已累积内容有重叠时，
     * 只取新增部分，避免重复粘连导致的「乱句」。
     *
     * @param maxLen 扫描上限（一般传 minOf(a.length, b.length)）
     * @return 重叠的代码单元长度，0 表示无重叠
     */
    fun commonOverlap(a: String, b: String, maxLen: Int): Int {
        val limit = minOf(maxLen, a.length, b.length)
        var best = 0
        var len = limit
        while (len > 0) {
            // 快速排除：首字符不同肯定不可能重叠
            if (a[a.length - len] == b[0]) {
                var ok = true
                for (k in 1 until len) {
                    if (a[a.length - len + k] != b[k]) { ok = false; break }
                }
                if (ok) { best = len; break }
            }
            len--
        }
        if (best == 0) return 0
        // 防止把切点落在代理对中间：若 a 的重叠起点是低代理、b 的重叠终点是高代理，退一格
        val startIdx = a.length - best
        if (startIdx > 0 && a[startIdx].isLowSurrogate() && a[startIdx - 1].isHighSurrogate()) {
            best -= 1
        }
        if (best > 0 && b[best - 1].isHighSurrogate()) {
            best -= 1
        }
        return if (best > 0) best else 0
    }

    fun takeUnitsSafe(s: String, maxUnits: Int): String {
        if (maxUnits <= 0) return ""
        if (s.length <= maxUnits) return s
        var end = maxUnits
        // 切点前一个字符若为高代理，说明正好切在代理对中间，退一格
        if (end > 0 && s[end - 1].isHighSurrogate()) {
            end -= 1
        }
        return s.substring(0, end)
    }
}
