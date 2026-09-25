package com.ttsproxy.core

/**
 * 上下文条件：同一个符号在不同语境下读法不同。
 *
 * 这不是完整的上下文消歧引擎，只是几条**高精度**的规则。取舍原则是
 * **宁可漏判也不要错判**：读成通用读法只是啰嗦，读错却会误导学生。
 */
enum class ReadingContext {

    /**
     * 前面紧邻一个数量：数字，或 SI 词头字母（k M G T m n p µ）。
     *
     * 用于 Ω：`10Ω`、`4.7kΩ` 读「欧姆」，孤立的 Ω 读「欧米伽」。
     */
    AFTER_QUANTITY,

    /**
     * 前面紧邻一个**含下标数字的化学式**。
     *
     * 用于 ↑↓：`CO₂↑` 读「气体」、`BaSO₄↓` 读「沉淀」，而 `价格↑`、`GDP↑`
     * 仍读「上箭头」。判据刻意收紧到「必须出现下标数字」——因此 `AgCl↓`
     * 这种没有下标的会漏判，读成「下箭头」。漏判只是啰嗦，错判会误导。
     */
    AFTER_FORMULA,

    /**
     * 前面紧邻一个拉丁字母。用于 ′ ″：`f′` 读「导数」，`45′` 读「分」。
     */
    AFTER_LATIN,

    /**
     * 后面紧邻一个拉丁字母。用于 µ：`μm` 读「微」，孤立的 μ 读「缪」。
     */
    BEFORE_LATIN;

    companion object {
        fun fromKey(key: String): ReadingContext? = when (key.trim().lowercase()) {
            "after_quantity" -> AFTER_QUANTITY
            "after_formula" -> AFTER_FORMULA
            "after_latin" -> AFTER_LATIN
            "before_latin" -> BEFORE_LATIN
            else -> null
        }

        /** [fromKey] 的逆运算，导出用户词典时要把条件写回文件。 */
        fun keyOf(context: ReadingContext): String = when (context) {
            AFTER_QUANTITY -> "after_quantity"
            AFTER_FORMULA -> "after_formula"
            AFTER_LATIN -> "after_latin"
            BEFORE_LATIN -> "before_latin"
        }

        /** SI 词头，跟在它们后面的 Ω 同样是欧姆。 */
        private val SI_PREFIXES = "kKMGTmnpµμ".toSet()

        private const val MAX_LOOKBACK = 12

        fun matches(context: ReadingContext, cps: IntArray, start: Int, end: Int): Boolean =
            when (context) {
                AFTER_QUANTITY -> previous(cps, start)?.let { isDigit(it) || (it < 0x10000 && it.toChar() in SI_PREFIXES) } == true
                AFTER_LATIN -> previous(cps, start)?.let { isLatin(it) } == true
                BEFORE_LATIN -> next(cps, end)?.let { isLatin(it) } == true
                AFTER_FORMULA -> looksLikeFormula(cps, start)
            }

        private fun previous(cps: IntArray, start: Int): Int? {
            var i = start - 1
            while (i >= 0 && Character.isWhitespace(cps[i])) i--
            return if (i >= 0) cps[i] else null
        }

        private fun next(cps: IntArray, end: Int): Int? {
            var i = end
            while (i < cps.size && Character.isWhitespace(cps[i])) i++
            return if (i < cps.size) cps[i] else null
        }

        private fun isDigit(cp: Int): Boolean =
            cp in '0'.code..'9'.code || cp in 0xFF10..0xFF19

        private fun isLatin(cp: Int): Boolean =
            cp in 'A'.code..'Z'.code || cp in 'a'.code..'z'.code

        private fun isSubscriptDigit(cp: Int): Boolean = cp in 0x2080..0x2089

        /**
         * 往回扫一段化学式模样的token（字母、数字、下标、括号），
         * 要求其中**至少出现一个下标数字**才认定为化学式。
         */
        private fun looksLikeFormula(cps: IntArray, start: Int): Boolean {
            var i = start - 1
            var scanned = 0
            var sawSubscript = false
            while (i >= 0 && scanned < MAX_LOOKBACK) {
                val cp = cps[i]
                when {
                    isSubscriptDigit(cp) -> sawSubscript = true
                    isLatin(cp) || isDigit(cp) || cp == '('.code || cp == ')'.code -> Unit
                    else -> return sawSubscript
                }
                i--
                scanned++
            }
            return sawSubscript
        }
    }
}
