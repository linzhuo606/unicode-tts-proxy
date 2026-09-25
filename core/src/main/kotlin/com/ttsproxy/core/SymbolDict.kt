package com.ttsproxy.core

/**
 * 符号词典：码位序列 -> 中文读法。
 *
 * 运行时是一个纯查表结构，所有复杂度（CLDR 抓取、肤色/性别派生名合成、去重）
 * 都在离线的 tools 生成器里完成。这样服务端只需要做最长匹配，不做任何推导。
 *
 * 文件格式（UTF-8，制表符分隔，`#` 开头为注释）：
 * ```
 * <空格分隔的十六进制码位>\t<类别>\t<简洁读法>\t<详细读法>
 * 1F44D              \tE\t点赞\t点赞表情
 * 1F468 200D 1F469 200D 1F467 200D 1F466\tE\t一家四口\t一家四口表情
 * 2211               \tM\t求和\t求和符号
 * 3382               \tP\t平方米\t平方米
 * ```
 * 详细读法留空表示与简洁读法相同。
 */
class SymbolDict private constructor(
    private val byFirst: Map<Int, List<Entry>>,
) {

    enum class Kind {
        /** emoji——只有这一类在「关闭」档下会被整个丢弃。 */
        EMOJI,

        /** 数学符号。 */
        MATH,

        /** 物理与化学符号。 */
        SCIENCE,

        /** 其他符号：货币、标点、箭头、几何、圈号、制表框线等。 */
        SYMBOL,

        /** 盲文点位。 */
        BRAILLE,

        /**
         * 国际音标。单独一类是因为它的简洁档读法**可以整体切换**：
         * 默认读中文音理简称，也可以切成 ICEB 盲文点位（见 [briefAsBraille]）。
         */
        IPA;

        companion object {
            fun fromTag(tag: String): Kind? = when (tag) {
                "E" -> EMOJI
                "M" -> MATH
                "P" -> SCIENCE
                "S" -> SYMBOL
                "B" -> BRAILLE
                "I" -> IPA
                else -> null
            }

            fun tagOf(kind: Kind): String = when (kind) {
                EMOJI -> "E"
                MATH -> "M"
                SCIENCE -> "P"
                SYMBOL -> "S"
                BRAILLE -> "B"
                IPA -> "I"
            }

            /**
             * 用户自定义条目没写类别时的兜底判定。
             *
             * 只有 EMOJI 这一类在「关闭」档下会被整段丢弃，所以判错的代价就是
             * 关闭档下多读或少读一个字符，不会出别的乱子。
             */
            fun guess(key: IntArray): Kind {
                for (cp in key) if (isEmojiCodePoint(cp)) return EMOJI
                return SYMBOL
            }

            private fun isEmojiCodePoint(cp: Int): Boolean = when (cp) {
                in 0x1F000..0x1FAFF, in 0x1F900..0x1F9FF -> true
                in 0x2600..0x27BF, in 0x2B00..0x2BFF -> true
                in 0x1F1E6..0x1F1FF -> true          // 区域指示符（国旗）
                0x200D, 0xFE0F -> true               // ZWJ 与变体选择符只出现在 emoji 序列里
                else -> false
            }
        }
    }

    class Entry(
        /** 码位序列。长度 >= 1。 */
        @JvmField val key: IntArray,
        @JvmField val kind: Kind,
        @JvmField val brief: String,
        verbose: String,
        /**
         * 上下文条件读法，按声明顺序取第一个命中的。
         * 例如 Ω 的 `AFTER_QUANTITY -> 欧姆`：`10Ω` 读欧姆，孤立的 Ω 读欧米伽。
         */
        @JvmField val conditions: List<Pair<ReadingContext, String>> = emptyList(),
        /**
         * 连续重复时是否合并成**一次**读法，而不是「N 个 X」。
         *
         * 给本来就写成两个的字符配读法时要用：「——」读成「2 个 破折号」是错的。
         * 内置词典已经把破折号、省略号交还下游了，这个选项留给用户词典。
         * 而连续的横线、重复的 emoji 报个数才有用。
         */
        @JvmField val collapseRepeats: Boolean = false,
        /**
         * 屏蔽：匹配上就整段丢弃，什么都不读。
         *
         * 内置词典打包在 assets 里改不了，所以用户要「删掉」一个内置读法时，
         * 实际写入的是一条屏蔽条目——用叠加代替删除，内置词典永远保持原样，
         * 用户随时可以撤销。
         */
        @JvmField val muted: Boolean = false,
        /** 来自用户词典。同长度时排在内置条目前面，因此优先命中。 */
        @JvmField val fromUser: Boolean = false,
    ) {
        @JvmField
        val verbose: String = verbose.ifBlank { brief }

        fun reading(verbosity: Verbosity): String =
            if (verbosity == Verbosity.VERBOSE) verbose else brief

        /**
         * 结合上下文取读法。条件命中时优先用条件读法，
         * 因为它比通用读法更具体（也更容易读错，所以判据必须收紧）。
         */
        fun reading(verbosity: Verbosity, cps: IntArray, start: Int, end: Int): String {
            for ((context, text) in conditions) {
                if (ReadingContext.matches(context, cps, start, end)) return text
            }
            return reading(verbosity)
        }

        /** 码位长度，用于最长匹配。 */
        val length: Int get() = key.size
    }

    val size: Int = byFirst.values.sumOf { it.size }

    /** 全部条目，顺序不保证。导出与统计用。 */
    fun entries(): List<Entry> = byFirst.values.flatten()

    val isEmpty: Boolean get() = size == 0

    /**
     * 在 [cps] 的 [from] 位置做最长匹配。
     *
     * 桶按码位长度降序排列，所以第一个匹配上的就是最长的。这一点是正确性的前提：
     * 👨‍👩‍👧‍👦 必须匹配成「一家四口」而不是先匹配到 👨「男人」。
     */
    fun match(cps: IntArray, from: Int): Entry? {
        val bucket = byFirst[cps[from]] ?: return null
        val remaining = cps.size - from
        for (entry in bucket) {
            val key = entry.key
            if (key.size > remaining) continue
            var i = 1
            var ok = true
            while (i < key.size) {
                if (cps[from + i] != key[i]) {
                    ok = false
                    break
                }
                i++
            }
            if (ok) return entry
        }
        return null
    }

    companion object {
        val EMPTY: SymbolDict = SymbolDict(emptyMap())

        /**
         * 解析词典。遇到坏行就跳过——词典损坏绝不能让引擎失声，
         * 大不了少替换几个符号。
         */
        /**
         * 按优先级叠加若干层条目，靠前的层优先命中。
         *
         * 同一码位序列可能在多层里都有，这里**不做去重**：
         * 排序把用户层排到前面，[match] 取第一个命中的，天然就是覆盖语义。
         */
        fun of(layers: List<List<Entry>>): SymbolDict {
            val buckets = HashMap<Int, MutableList<Entry>>()
            for (layer in layers) {
                for (entry in layer) buckets.getOrPut(entry.key[0]) { ArrayList(4) }.add(entry)
            }
            // 先按长度降序（最长匹配的前提），同长度时用户条目在前（覆盖内置）
            for (bucket in buckets.values) {
                bucket.sortWith(
                    compareByDescending<Entry> { it.key.size }.thenByDescending { it.fromUser }
                )
            }
            return SymbolDict(buckets)
        }

        fun parse(lines: Sequence<String>): SymbolDict = of(listOf(parseEntries(lines)))

        /**
         * 把音标条目的简洁读法换成盲文点位。
         *
         * 默认的简洁读法是中文音理简称（`ŋ` 读「软腭鼻音」）。学过盲文音标的用户
         * 更习惯直接听点位，切过去就调这个。点位不用另存一列——详细读法本来就是
         * 「点位 + 音理」，把前缀的点位摘出来即可，摘不出来的条目原样保留。
         */
        fun briefAsBraille(entries: List<Entry>): List<Entry> = entries.map { entry ->
            if (entry.kind != Kind.IPA) return@map entry
            val dots = leadingDots(entry.verbose) ?: return@map entry
            Entry(
                entry.key, entry.kind, dots, entry.verbose,
                entry.conditions, entry.collapseRepeats, entry.muted, entry.fromUser,
            )
        }

        /** 取出形如 `点 2 3 5 点 1` 的前缀；不是这个形状就返回 null。 */
        private fun leadingDots(verbose: String): String? {
            val tokens = verbose.split(' ')
            var end = 0
            while (end < tokens.size && (tokens[end] == "点" || tokens[end].toIntOrNull() != null)) end++
            if (end == 0 || tokens[0] != "点") return null
            return tokens.subList(0, end).joinToString(" ")
        }

        fun parseEntries(lines: Sequence<String>): List<Entry> {
            val out = ArrayList<Entry>(4096)
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val cols = line.split('\t')
                if (cols.size < 3) continue

                val key = parseCodePoints(cols[0]) ?: continue
                if (key.isEmpty()) continue
                val kind = Kind.fromTag(cols[1].trim()) ?: continue
                val brief = cols[2].trim()
                if (brief.isEmpty()) continue
                val verbose = if (cols.size > 3) cols[3].trim() else ""
                val raw5 = if (cols.size > 4) cols[4] else ""
                val conditions = parseConditions(raw5)
                val collapse = raw5.split(';').any { it.trim().equals("repeat=collapse", true) }

                out.add(Entry(key, kind, brief, verbose, conditions, collapse))
            }
            return out
        }

        fun parse(text: String): SymbolDict = parse(text.lineSequence())

        /** 形如 `after_quantity=欧姆;after_formula=气体`。认不出的条件直接忽略。 */
        internal fun parseConditions(field: String): List<Pair<ReadingContext, String>> {
            val text = field.trim()
            if (text.isEmpty()) return emptyList()
            val out = ArrayList<Pair<ReadingContext, String>>(2)
            for (part in text.split(';')) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val context = ReadingContext.fromKey(part.substring(0, idx)) ?: continue
                val reading = part.substring(idx + 1).trim()
                if (reading.isNotEmpty()) out.add(context to reading)
            }
            return out
        }

        internal fun parseCodePoints(field: String): IntArray? {
            val parts = field.trim().split(' ', '-', '_').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val out = IntArray(parts.size)
            for (i in parts.indices) {
                val cp = parts[i].toIntOrNull(16) ?: return null
                // 代理项不是字符：写进 UTF-8 文件会变成 ?，读回来就接管了所有问号
                if (cp < 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return null
                out[i] = cp
            }
            return out
        }
    }
}
