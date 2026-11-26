package com.airtouch.app.input.keyboard.t9

import kotlin.math.min

/**
 * 九键拼音引擎：把一串数字变成候选汉字/词。
 *
 * 纯 Kotlin、不依赖 Android（只依赖 [PinyinDictionary] 的数据），因此可以脱离设备单独推理。
 *
 * ## 候选是怎么排出来的
 * 1. **整词优先**：`64426` 直接命中词表的「你好」，一个候选就是完整的一个词；
 * 2. **整串自成一个音节**：`9426` 命中 `#chars` 的 `9426` 行，那行里已经混着 `xian`、`zhao`
 *    等所有落在这个数字上的音节，按字频排好序了，直接摊开就是候选；
 * 3. **切成多个音节**：用动态规划把数字串切成若干合法音节（见 [segment]），前缀各取最高频字，
 *    末音节逐个列字 —— 于是「ni + hao」会给出「你好/你号/你浩…」这样可选的列表；
 * 4. **兜底**：永远补一个「原样数字」候选，用户不会因为切不出音节而卡住。
 *
 * ## 排序用的「频率」
 * 词库里每个音节下的汉字已经按字频降序，所以这里用**下标**当频率代理：`1/(1+下标)`。
 * 不需要额外的频率表，排序方向是对的（越靠前的字越常用，组合出来的候选就越靠前）。
 */
object PinyinEngine {

    /** 一个候选项 */
    data class Candidate(val text: String, val kind: Kind)

    enum class Kind {
        /** 整词 */
        WORD,

        /** 单字（多音节时前缀已按最高频字补全） */
        CHAR,

        /** 原样输出数字串，兜底用 */
        RAW,
    }

    /** 一个音节切分片段 */
    data class Segment(val digits: String, val chars: String)

    /**
     * 一次最多给几个候选。
     *
     * 原来是 8 —— 用户反馈「词库太少没法拼拼音」：同一个数字串下同音字动辄几十个（`94` = yi/xi/zhi…），
     * 只给 8 个根本翻不到想打的那个字。候选条本身是横向可滚动的（`T9CandidateBar` 用了
     * `horizontalScroll`），所以放宽到 24 是纯收益：一屏能看到前几个，往右滑还能继续找。
     */
    const val DEFAULT_LIMIT = 24

    /** 最长接受多少个数字键：够打六个音节，再长说明用户在乱按 */
    const val MAX_DIGITS = 24

    /**
     * 把数字串切成合法音节，返回得分最高的那种切法；切不出来返回 null。
     *
     * 动态规划：`best[i]` = 前缀 `digits[0, i)` 的最优得分，音节长度 1~6。
     * 得分取**乘积**（每项 ≤ 1），因此音节数少的切法天然占优 —— 「一个音节能读通」就不该被拆成
     * 两个音节。长度相同时，各音节首字越常用（下标越小）得分越高。
     */
    fun segment(dictionary: PinyinDictionary, digits: String): List<Segment>? {
        if (digits.isEmpty()) return null

        val n = digits.length
        val best = DoubleArray(n + 1) { 0.0 }
        val from = IntArray(n + 1) { -1 }
        best[0] = 1.0

        for (end in 1..n) {
            for (len in 1..MAX_SYLLABLE_DIGITS) {
                val start = end - len
                if (start < 0) break
                if (best[start] <= 0.0) continue
                val piece = digits.substring(start, end)
                if (!dictionary.isSyllable(piece)) continue
                val score = best[start] * syllableScore(dictionary, piece)
                if (score > best[end]) {
                    best[end] = score
                    from[end] = start
                }
            }
        }

        if (from[n] < 0) return null

        val result = ArrayList<Segment>()
        var cursor = n
        while (cursor > 0) {
            val start = from[cursor]
            val piece = digits.substring(start, cursor)
            result.add(Segment(piece, dictionary.charsFor(piece)))
            cursor = start
        }
        result.reverse()
        return result
    }

    /**
     * 一个音节的得分：用「这个音节上挂着多少个汉字」当音节常用度的代理。
     *
     * 词库里没有全局字频表（只有「数字 → 汉字，按字频降序」），所以无法精确判断哪个音节更常用。
     * 但同音字多的音节（`yi`、`shi`）本来就常用，同音字极少的音节（`nüe`、`fiao`）本来就罕见，
     * 这个代理的方向是对的。归一化到 (0, 1]，于是「音节切得少」在乘积里天然占优。
     */
    private fun syllableScore(dictionary: PinyinDictionary, digits: String): Double {
        val count = dictionary.charsFor(digits).length
        if (count == 0) return 0.0
        return (1.0 + minOf(count, PRODUCTIVE_CHARS).toDouble()) / (1.0 + PRODUCTIVE_CHARS)
    }

    /**
     * 生成候选列表。
     *
     * @param limit 最多返回几个（[DEFAULT_LIMIT]）；「原样数字」兜底项也占一个名额
     */
    fun candidates(
        dictionary: PinyinDictionary,
        digits: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<Candidate> {
        if (digits.isEmpty() || limit <= 0) return emptyList()
        val out = ArrayList<Candidate>(limit + 1)

        // 1. 整词
        for (word in dictionary.wordsFor(digits)) {
            out.add(Candidate(word, Kind.WORD))
            if (out.size >= limit) return out
        }

        // 2. 整串就是一个音节
        val whole = dictionary.charsFor(digits)
        if (whole.isNotEmpty()) {
            for (ch in whole) {
                out.add(Candidate(ch.toString(), Kind.CHAR))
                if (out.size >= limit) break
            }
        } else {
            // 3. 切成多个音节：前 n-2 个音节各取最高频字，**最后两个音节做组合**
            //    （各取前 COMBO_CHARS 个字，按下标和排序）。原来只让最后一个音节变化，
            //    于是 `64'426`（ni'hao）只能给出「你好/你号/你浩…」—— 第一个字永远是「你」，
            //    想打「尼豪」「泥嚎」这类就翻不到；现在最后两个字一起组合，候选量立刻够用。
            val segments = segment(dictionary, digits)
            if (segments != null && segments.size >= 2) {
                val headCount = segments.size - 2
                val prefix = buildString {
                    for (i in 0 until headCount) {
                        val top = segments[i].chars.firstOrNull() ?: return@buildString
                        append(top)
                    }
                }
                if (prefix.length == headCount) {
                    val headChars = segments[segments.size - 2].chars.take(COMBO_CHARS)
                    val lastChars = segments[segments.size - 1].chars.take(COMBO_CHARS)
                    // 下标和 = 「两个字各自的常用度」的代理，小的排前面；sortBy 稳定，同级保持 i 序
                    val combos = ArrayList<Pair<Int, String>>(headChars.length * lastChars.length)
                    for (i in headChars.indices) {
                        for (j in lastChars.indices) {
                            combos.add((i + j) to buildString { append(headChars[i]); append(lastChars[j]) })
                        }
                    }
                    combos.sortBy { it.first }
                    for ((_, combo) in combos) {
                        out.add(Candidate(prefix + combo, Kind.CHAR))
                        if (out.size >= limit) break
                    }
                }
            }
        }

        // 4. 兜底：原样数字（用户总能把自己按的东西发出去）
        if (out.size < limit) out.add(Candidate(digits, Kind.RAW))

        return out.distinctBy { it.text }
    }

    /**
     * 组合串的显示文案：按最优切分插入分隔符，例如 `64426` → `64'426`。
     *
     * 只显示数字而不是拼音字母：词库里只有数字键，硬凑字母反而会显示一个用户没打过的音节。
     * 「打的是什么」这件事由数字如实表达。
     */
    fun compositionLabel(dictionary: PinyinDictionary, digits: String): String {
        if (digits.isEmpty()) return ""
        val segments = segment(dictionary, digits) ?: return digits
        return segments.joinToString("'") { it.digits }
    }

    /** 单个音节的数字最长 6 位（`zhuang` = 948264） */
    private const val MAX_SYLLABLE_DIGITS = 6

    /** 多音节时，最后两个音节各取前多少个字参与组合（上限 6×6=36 个候选，够填满候选条） */
    private const val COMBO_CHARS = 6

    /** 音节得分的归一化上限：同音字多到这个数就算「很常用」 */
    private const val PRODUCTIVE_CHARS = 40
}
