package com.ttsproxy.core

/**
 * 文本处理流水线：把 TalkBack 传来的原文，改写成下游 TTS 引擎能正确朗读的中文。
 *
 * 单趟从左到右扫描，每个位置依次尝试：
 *  1. 词典最长匹配（emoji 序列 / 多字符符号 / 单字符符号）
 *  2. 无损折叠（数学字母数字、全角）
 *  3. 不可见字符清理
 *  4. 原样透传
 *
 * 这个类是纯函数、无状态、线程安全的——它会被合成线程直接调用。
 */
class TextPipeline(private val dict: SymbolDict) {

    /**
     * 带兜底的转换。**服务层应当只调这个。**
     *
     * 盲人用户的手机静音等于设备失联，任何异常都必须退化成「读原文」，
     * 绝不能把异常抛给系统的合成线程。
     */
    fun transformSafe(input: String?, verbosity: Verbosity): String {
        if (input.isNullOrEmpty()) return ""
        return try {
            transform(input, verbosity)
        } catch (t: Throwable) {
            input
        }
    }

    fun transform(input: String, verbosity: Verbosity): String {
        if (input.isEmpty()) return input

        val cps = input.codePoints().toArray()
        val out = StringBuilder(input.length + 16)
        // 替换文本与原文粘连会让 TTS 读成一个词（「点赞好的」），所以两侧补空格。
        // 但不插标点——逗号会带来过长的停顿，连续 emoji 时听感很糟。
        var needSpaceBeforeRaw = false

        var i = 0
        while (i < cps.size) {
            // 内置词典不许替换两类字符：下游本来就处理得好的标点，以及空白与格式控制符。见 shielded()。
            val entry = dict.match(cps, i)?.takeUnless { shielded(it) }

            if (entry != null &&
                (verbosity != Verbosity.OFF || entry.kind == SymbolDict.Kind.EMOJI || entry.muted)
            ) {
                val repeats = countRepeats(cps, i, entry.key)
                i += entry.key.size * repeats

                // 屏蔽条目：用户明确要求这个字符不出声，任何档位都丢弃。
                // 放在档位判断之前——用户的意愿优先于档位。
                if (entry.muted) continue

                if (verbosity == Verbosity.OFF) {
                    // 「关闭」档：emoji 整段丢弃，连同其中的 ZWJ / 变体选择符。
                    continue
                }

                val reading = entry.reading(verbosity, cps, i - entry.key.size * repeats, i)
                val text = if (repeats >= REPEAT_COUNT_THRESHOLD && !entry.collapseRepeats) {
                    "$repeats 个 $reading"
                } else if (repeats > 1 && !entry.collapseRepeats) {
                    // 两个连着的照读两遍，保留「强调」的语气；三个以上才报数字避免刷屏
                    List(repeats) { reading }.joinToString(" ")
                } else {
                    reading
                }
                appendReading(out, text)
                needSpaceBeforeRaw = true
                continue
            }

            val cp = cps[i]

            // 带附加符号的字母：预组合的（é）直接拆，已经拆开写的（e + 组合符）
            // 要先确认这一串真能合成一个字符，合不成的多半是音标，附加符号留给词典。
            val fold = foldDiacritic(cps, i, verbosity)
            if (fold != null) {
                if (fold.reading != null) {
                    appendReading(out, fold.reading)
                    needSpaceBeforeRaw = true
                } else {
                    needSpaceBeforeRaw = appendRaw(out, fold.base, needSpaceBeforeRaw)
                }
                i += fold.consumed
                continue
            }

            i++

            val folded = MathFold.fold(cp)
            if (folded >= 0) {
                needSpaceBeforeRaw = appendRaw(out, folded, needSpaceBeforeRaw)
                continue
            }
            if (Invisibles.shouldDrop(cp)) continue
            needSpaceBeforeRaw = appendRaw(out, cp, needSpaceBeforeRaw)
        }

        return out.toString()
    }

    /**
     * 一次重音字母折叠的结果。
     *
     * [reading] 非空表示按「读法」输出（词典命中，或详细档要念符号名）；
     * 为空表示按原文字符输出 [base]——`café` 折出来的 `e` 要紧贴前面的 `caf`，
     * 当成读法插空格会变成 `caf e`，那是两个词。
     */
    private class Fold(
        @JvmField val consumed: Int,
        @JvmField val base: Int,
        @JvmField val reading: String?,
    )

    /**
     * 尝试把 [i] 位置折成「基本字母 + 附加符号」。不适用时返回 null。
     *
     * 两种写法都要认：
     * - 预组合的单个码位（`é` U+00E9），Unicode 规范化表直接给出拆分；
     * - 基字母后面跟着组合符号（`e` + U+0301）。这一种必须先确认它们**能合成**
     *   一个真实存在的字符，否则就是音标的临时组合（`ɛ̃`），附加符号得留给词典
     *   读成「鼻化」，折掉就把音位信息弄丢了。
     */
    private fun foldDiacritic(cps: IntArray, i: Int, verbosity: Verbosity): Fold? {
        val cp = cps[i]

        DiacriticFold.split(cp)?.let { split ->
            return fold(1, split.base, split.marks, split.marks.size, verbosity)
        }

        if (!Character.isLetter(cp)) return null
        var available = 0
        while (i + 1 + available < cps.size && DiacriticFold.isCombiningMark(cps[i + 1 + available])) {
            available++
        }
        if (available == 0) return null

        // 从最长的一串往回退，取能合成的最长前缀：`e` + 分音符 + 锐音符 这类叠加要一起吃掉。
        // 预组合字符最多带三个附加符号（ᾏ），再长的前缀不可能合成。必须封顶：
        // 乱码消息里一个字母后面能跟几千个附加符号，每试一次都要整串做 NFC，
        // 不封顶的话合成线程会卡住好几秒，整机失声。
        var take = minOf(available, DiacriticFold.MAX_MARKS)
        while (take > 0) {
            val marks = IntArray(take) { cps[i + 1 + it] }
            if (DiacriticFold.composes(cp, marks, take)) {
                return fold(1 + take, cp, marks, take, verbosity)
            }
            take--
        }
        return null
    }

    private fun fold(consumed: Int, base: Int, marks: IntArray, count: Int, verbosity: Verbosity): Fold {
        // 「关闭」档不做词典替换，但折叠照做——这一档解决的是「别乱读」，不是「别处理」
        val entry = if (verbosity == Verbosity.OFF) null else dict.match(intArrayOf(base), 0)
        val baseReading = entry?.reading(verbosity)

        if (verbosity != Verbosity.VERBOSE) {
            // 简洁档：附加符号丢掉，只留基本字母
            return Fold(consumed, base, baseReading)
        }

        val names = StringBuilder()
        for (k in 0 until count) {
            val name = DiacriticFold.markName(marks[k]) ?: continue
            names.append(' ').append(name)
        }
        if (names.isEmpty()) return Fold(consumed, base, baseReading)

        val head = baseReading ?: String(Character.toChars(base))
        return Fold(consumed, base, head + names)
    }

    /**
     * 这条内置条目要不要忽略。两类字符**只能**交给别的规则，词典不许插手：
     * 下游本来就处理得好的标点（原样交给它，见 [NativePunctuation]），
     * 以及空白与格式控制符（归空格折叠与不可见字符清理，一个字都不该念，见 [Invisibles]）。
     * 用户词典不受限制。
     */
    private fun shielded(entry: SymbolDict.Entry): Boolean {
        if (NativePunctuation.shields(entry)) return true
        return !entry.fromUser && entry.key.size == 1 && Invisibles.isWhitespaceOrFormat(entry.key[0])
    }

    /** 数出从 [from] 开始，[key] 连续重复了多少次。用于把 👍👍👍 合并成「3 个 点赞」。 */
    private fun countRepeats(cps: IntArray, from: Int, key: IntArray): Int {
        var count = 1
        var pos = from + key.size
        while (pos + key.size <= cps.size) {
            var k = 0
            while (k < key.size && cps[pos + k] == key[k]) k++
            if (k < key.size) break
            count++
            pos += key.size
        }
        return count
    }

    private fun appendReading(out: StringBuilder, text: String) {
        if (out.isEmpty()) {
            out.append(text)
            return
        }
        val last = out[out.length - 1]
        // 标点本身就起分隔作用，再补空格只会让文本变脏
        val separated = last.isWhitespace() ||
            isOpeningPunctuation(last.code) || isClosingPunctuation(last.code)
        if (!separated) out.append(' ')
        out.append(text)
    }

    /** @return 下一个原文字符前是否仍需补空格 */
    private fun appendRaw(out: StringBuilder, cp: Int, needSpace: Boolean): Boolean {
        val skipSpace = Character.isWhitespace(cp) || isClosingPunctuation(cp)
        if (needSpace && !skipSpace && out.isNotEmpty()) out.append(' ')
        out.appendCodePoint(cp)
        return false
    }

    // 分隔空格不该插在标点前后，否则「摄氏度 ，」这种间隙会出现在调试界面里，
    // 读起来也可能被个别引擎当成停顿。
    private fun isClosingPunctuation(cp: Int): Boolean = when (cp) {
        0x3001, 0x3002, 0xFF0C, 0xFF01, 0xFF1F, 0xFF1B, 0xFF1A,   // 、。，！？；：
        0xFF09, 0x3011, 0x300B, 0x300D, 0x300F, 0xFF3D, 0xFF5D,   // ）】》」』］｝
        0x2C, 0x2E, 0x21, 0x3F, 0x3B, 0x3A, 0x29, 0x5D, 0x7D, 0x3E, 0x25 -> true
        else -> false
    }

    private fun isOpeningPunctuation(cp: Int): Boolean = when (cp) {
        0xFF08, 0x3010, 0x300A, 0x300C, 0x300E, 0xFF3B, 0xFF5B,   // （【《「『［｛
        0x28, 0x5B, 0x7B, 0x3C -> true
        else -> false
    }

    /**
     * 整段文本是否恰好等于**一个**词典条目。
     *
     * 用 TalkBack 逐字符浏览去查某个 emoji 时，引擎收到的就是单独一个 emoji；
     * 别人发的一条纯 emoji 消息也是。这两种情况下用户都是想搞懂它，
     * 而且不存在刷屏问题——可以直接给详细读法。
     */
    fun isSingleEntry(text: String?): Boolean {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        val cps = trimmed.codePoints().toArray()
        val entry = dict.match(cps, 0)?.takeUnless { shielded(it) } ?: return false
        return entry.key.size == cps.size
    }

    companion object {
        /**
         * 连续重复到几个才合并成「N 个 X」。
         * 两个连着照读两遍保留强调语气，三个以上才报数字。
         */
        const val REPEAT_COUNT_THRESHOLD = 3

        /** 词典缺失时的降级实例：只做折叠与清理，不做替换。仍然比不处理强。 */
        fun withoutDict(): TextPipeline = TextPipeline(SymbolDict.EMPTY)
    }
}
