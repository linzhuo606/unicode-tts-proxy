package com.ttsproxy.core

/**
 * 无损折叠：把「长得像普通字母数字、但码位在别处」的字符还原成 ASCII / 普通希腊字母。
 *
 * 为什么不直接用 NFKC：NFKC 会把 ℝ 折成 R、ℑ 折成 I，反而把「实数集」「虚部」的语义
 * 抹掉。我们只折叠白名单内确定无损的部分，Letterlike Symbols 里那些有数学含义的字符
 * 留给词典去读。
 */
object MathFold {

    private const val NO_FOLD = -1

    // ---- Mathematical Alphanumeric Symbols (U+1D400–U+1D7FF) 里的拉丁字母与数字 ----
    // 每条 = (区段起点, 目标起点)，区段长 26（字母）或 10（数字）。
    // 区段内的保留码位（如 U+1D455，因为 ℎ U+210E 占了它的位置）是未分配字符，
    // 正常文本里不会出现；万一出现，按位置折叠得到的也是正确字母，无害。
    private val LETTER_BLOCKS = intArrayOf(
        0x1D400, 'A'.code, 0x1D41A, 'a'.code, // 粗体
        0x1D434, 'A'.code, 0x1D44E, 'a'.code, // 斜体
        0x1D468, 'A'.code, 0x1D482, 'a'.code, // 粗斜体
        0x1D49C, 'A'.code, 0x1D4B6, 'a'.code, // 花体
        0x1D4D0, 'A'.code, 0x1D4EA, 'a'.code, // 粗花体
        0x1D504, 'A'.code, 0x1D51E, 'a'.code, // 哥特体
        0x1D538, 'A'.code, 0x1D552, 'a'.code, // 黑板粗体
        0x1D56C, 'A'.code, 0x1D586, 'a'.code, // 粗哥特体
        0x1D5A0, 'A'.code, 0x1D5BA, 'a'.code, // 无衬线
        0x1D5D4, 'A'.code, 0x1D5EE, 'a'.code, // 无衬线粗体
        0x1D608, 'A'.code, 0x1D622, 'a'.code, // 无衬线斜体
        0x1D63C, 'A'.code, 0x1D656, 'a'.code, // 无衬线粗斜体
        0x1D670, 'A'.code, 0x1D68A, 'a'.code, // 等宽
    )

    private val DIGIT_BLOCKS = intArrayOf(
        0x1D7CE, 0x1D7D8, 0x1D7E2, 0x1D7EC, 0x1D7F6,
    )

    // ---- 数学希腊字母 ----
    // 5 个风格区段，每段固定 58 个码位：25 个大写 + ∇ + 25 个小写 + ∂ + 6 个变体形。
    private val GREEK_BLOCK_STARTS = intArrayOf(0x1D6A8, 0x1D6E2, 0x1D71C, 0x1D756, 0x1D790)
    private const val GREEK_BLOCK_LEN = 58

    /** 大写序列：Α..Ρ(17 个) + ϴ + Σ..Ω(7 个)。ϴ 插在中间，不能靠等差算。 */
    private val GREEK_CAPITALS = IntArray(25).also { out ->
        for (i in 0..16) out[i] = 0x0391 + i          // Α..Ρ
        out[17] = 0x03F4                              // ϴ 大写 theta 符号
        for (i in 18..24) out[i] = 0x03A3 + (i - 18)  // Σ..Ω
    }

    /** 6 个变体形：ϵ ϑ ϰ ϕ ϱ ϖ */
    private val GREEK_VARIANTS = intArrayOf(0x03F5, 0x03D1, 0x03F0, 0x03D5, 0x03F1, 0x03D6)

    // ---- 全角形式（U+FF00–U+FFEF）----
    // 只折叠字母、数字和数学运算符。全角标点（，。？！）保持原样：
    // 中文引擎本来就处理得好，折成半角反而丢掉断句韵律。
    private val FULLWIDTH_OPERATORS = mapOf(
        0xFF0B to '+'.code, 0xFF0D to '-'.code, 0xFF1D to '='.code,
        0xFF1C to '<'.code, 0xFF1E to '>'.code, 0xFF0F to '/'.code,
        0xFF0A to '*'.code, 0xFF3C to 0x5C, 0xFF3E to '^'.code,
        0xFF05 to '%'.code,
    )

    /**
     * 排版用的各种宽度空格，以及行/段分隔符。
     *
     * 它们不是不可见字符（不能丢弃——丢了词就粘在一起了）。不是每个引擎都认得这些空白，
     * 统一折成普通空格，断词行为就和普通文本一致了。
     *
     * 它们本身一个字都不该念：内置词典不许给它们配读法，见 [Invisibles.isWhitespaceOrFormat]。
     */
    private fun foldSpace(cp: Int): Int = when (cp) {
        in 0x2000..0x200A -> ' '.code      // en/em quad、各种定宽空格
        0x2028, 0x2029 -> ' '.code         // 行分隔符、段分隔符
        0x202F, 0x205F -> ' '.code         // 窄不断行空格、中等数学空格
        0x00A0, 0x1680, 0x3000 -> ' '.code // 不断行空格、欧甘空格、全角空格
        else -> NO_FOLD
    }

    /**
     * 尝试折叠一个码位。
     * @return 折叠后的码位，若不该折叠则返回 [NO_FOLD]（负数）。
     */
    fun fold(cp: Int): Int {
        if (cp in 0x1D400..0x1D7FF) return foldMathAlphanumeric(cp)
        if (cp in 0xFF01..0xFF5E) return foldFullwidth(cp)
        val space = foldSpace(cp)
        if (space != NO_FOLD) return space
        return NO_FOLD
    }

    fun canFold(cp: Int): Boolean = fold(cp) != NO_FOLD

    private fun foldMathAlphanumeric(cp: Int): Int {
        var i = 0
        while (i < LETTER_BLOCKS.size) {
            val start = LETTER_BLOCKS[i]
            if (cp in start..(start + 25)) return LETTER_BLOCKS[i + 1] + (cp - start)
            i += 2
        }
        for (start in DIGIT_BLOCKS) {
            if (cp in start..(start + 9)) return '0'.code + (cp - start)
        }
        // 无点 i / 无点 j
        if (cp == 0x1D6A4) return 'i'.code
        if (cp == 0x1D6A5) return 'j'.code
        for (start in GREEK_BLOCK_STARTS) {
            if (cp in start until (start + GREEK_BLOCK_LEN)) {
                return foldGreek(cp - start)
            }
        }
        return NO_FOLD
    }

    private fun foldGreek(index: Int): Int = when (index) {
        in 0..24 -> GREEK_CAPITALS[index]
        25 -> 0x2207                       // ∇
        in 26..50 -> 0x03B1 + (index - 26) // α..ω（含词尾 ς）
        51 -> 0x2202                       // ∂
        in 52..57 -> GREEK_VARIANTS[index - 52]
        else -> NO_FOLD
    }

    private fun foldFullwidth(cp: Int): Int = when (cp) {
        in 0xFF10..0xFF19 -> '0'.code + (cp - 0xFF10)
        in 0xFF21..0xFF3A -> 'A'.code + (cp - 0xFF21)
        in 0xFF41..0xFF5A -> 'a'.code + (cp - 0xFF41)
        else -> FULLWIDTH_OPERATORS[cp] ?: NO_FOLD
    }
}
