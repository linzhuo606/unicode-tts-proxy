package com.ttsproxy.core

import java.text.Normalizer

/**
 * 带附加符号的字母（é ñ ü ç、拼音的 mā má mǎ mà、希腊语的 ά）的处理。
 *
 * ## 为什么要处理
 *
 * 这些字符原本原样透传给下游引擎，多数引擎读成基本字母或者干脆跳过。
 * 跳过意味着 `Ångström` 听起来只剩 `ngstrm`，用户不知道少了什么。
 *
 * ## 怎么处理
 *
 * - **简洁档**：折成基本字母。`é` 读 `e`，`ǎ` 读 `a`，`ά` 读「阿尔法」
 *   （折出来的基本字母还要再查一次词典，希腊字母才不会退化成乱码）。
 * - **详细档**：基本字母 + 附加符号名。`é` 读「e 锐音符」，`mǎ` 里的 `ǎ` 读「a 反折符」。
 *
 * ## 与国际音标的边界
 *
 * 音标里的附加符号（鼻化 ̃ 、清化 ̥ 、成音节 ̩ ）**不能**被折掉，那是音位信息。
 * 划界的办法是看**能不能合成**：
 *
 * - `e` + `́ ` 能合成 `é`（U+00E9），说明这是正字法里的重音字母 → 折叠。
 * - `ɛ` + `̃ ` 合不出任何字符，Unicode 里根本没有「带波浪符的 ɛ」 → 不折，
 *   波浪符走词典读成「鼻化」。
 *
 * 这条判据不是启发式，是 Unicode 规范化表本身的性质：正字法里真实存在的
 * 重音字母才有预组合码位，音标的临时组合没有。
 *
 * 代价是 `ẽ`（e 加波浪符）这类两边都成立的字符会被判成正字法字母。
 * 这是有意的取舍：日常文本里的葡萄牙语、越南语比音标多得多，
 * 而且详细档仍然读得出符号名，用户词典还能逐条改。
 */
object DiacriticFold {

    /** 折叠结果：基本字母 + 附加符号码位。 */
    class Folded(@JvmField val base: Int, @JvmField val marks: IntArray)

    /**
     * 预组合字符 -> 基本字母加附加符号。不是这类字符时返回 null。
     *
     * 只认「首字符是字母、其余全是组合附加符号」的规范分解。这条限制挡掉了
     * 合字（ﬁ）、上下标（²）、兼容分解（㎡）——那些各有各的处理办法，
     * 折成基本字母会丢信息。
     */
    fun split(cp: Int): Folded? = table[cp]

    /** 附加符号的中文名。认不出的返回 null——详细档宁可不读，也不能瞎报一个名字。 */
    fun markName(cp: Int): String? = MARK_NAMES[cp]

    /**
     * [base] 加上 [marks] 能不能合成一个预组合字符。
     *
     * 用于「基字母 + 组合符号」这种已经拆开写的文本：能合成的按重音字母折叠，
     * 合不成的把附加符号留给词典（多半是音标）。
     */
    /** 预组合字符的规范分解里最多有三个附加符号（ᾏ = Α + 三个），留一个余量。 */
    const val MAX_MARKS = 4

    fun composes(base: Int, marks: IntArray, count: Int): Boolean {
        if (count == 0) return false
        val sb = StringBuilder(1 + count)
        sb.appendCodePoint(base)
        for (i in 0 until count) sb.appendCodePoint(marks[i])
        val composed = Normalizer.normalize(sb, Normalizer.Form.NFC)
        return composed.codePointCount(0, composed.length) == 1
    }

    /** 是否是组合附加符号（加在前一个字符上的记号）。 */
    fun isCombiningMark(cp: Int): Boolean = when (cp) {
        in 0x0300..0x036F -> true          // 组合附加符号
        in 0x1AB0..0x1AFF -> true          // 组合附加符号扩展
        in 0x1DC0..0x1DFF -> true          // 组合附加符号补充
        in 0x20D0..0x20F0 -> true          // 符号用组合附加符号
        else -> false
    }

    /**
     * 覆盖范围：拉丁文（含扩展与附加）、希腊文、西里尔文里带附加符号的字母。
     *
     * 不做全码位扫描——那要遍历十几万个码位。这几段之外的带附加符号字母
     * （天城文、希伯来文……）本来也不在本项目的目标语种里。
     */
    private val RANGES = intArrayOf(
        0x00C0, 0x024F,   // 拉丁补充 + 拉丁扩展 A/B
        0x0370, 0x03FF,   // 希腊文
        0x0400, 0x04FF,   // 西里尔文
        0x1E00, 0x1EFF,   // 拉丁扩展附加（越南语在这一段）
        0x1F00, 0x1FFF,   // 希腊文扩展（古希腊语的调号）
    )

    private val table: Map<Int, Folded> by lazy(LazyThreadSafetyMode.PUBLICATION) { buildTable() }

    private fun buildTable(): Map<Int, Folded> {
        val out = HashMap<Int, Folded>(1024)
        var r = 0
        while (r < RANGES.size) {
            for (cp in RANGES[r]..RANGES[r + 1]) {
                val folded = decompose(cp) ?: continue
                out[cp] = folded
            }
            r += 2
        }
        return out
    }

    /**
     * 单个码位的规范分解。
     *
     * 用 NFD 而不是 NFKD：NFKD 是兼容分解，会把 ℝ 折成 R、㎡ 折成 m2，
     * 那正是我们要保住的语义。
     */
    private fun decompose(cp: Int): Folded? {
        if (!Character.isLetter(cp)) return null
        val src = String(Character.toChars(cp))
        val nfd = Normalizer.normalize(src, Normalizer.Form.NFD)
        if (nfd == src) return null
        val cps = nfd.codePoints().toArray()
        if (cps.size < 2) return null                       // 单例分解（Å→Å 那种），不是重音字母
        if (!Character.isLetter(cps[0])) return null
        for (i in 1 until cps.size) {
            if (!isCombiningMark(cps[i])) return null
        }
        return Folded(cps[0], cps.copyOfRange(1, cps.size))
    }

    /**
     * 附加符号的中文名。只收正字法里真会用到的那些。
     *
     * 名字用的是排版界的叫法（锐音符、抑音符），不是音标的叫法（高平调、低平调）：
     * 走到这里说明字符已经被判成正字法字母了，读音标术语反而误导。
     * 音标那一路走词典，读的是音理简称。
     */
    private val MARK_NAMES: Map<Int, String> = hashMapOf(
        0x0300 to "抑音符",
        0x0301 to "锐音符",
        0x0302 to "扬抑符",
        0x0303 to "波浪符",
        0x0304 to "长音符",
        0x0305 to "上划线",
        0x0306 to "短音符",
        0x0307 to "上点",
        0x0308 to "分音符",
        0x0309 to "上钩",
        0x030A to "上圆圈",
        0x030B to "双锐音符",
        0x030C to "反折符",
        0x030F to "双抑音符",
        0x0311 to "倒短音符",
        0x031B to "角号",
        0x0323 to "下点",
        0x0324 to "下双点",
        0x0325 to "下圆圈",
        0x0326 to "下逗号",
        0x0327 to "下加符",
        0x0328 to "反尾符",
        0x032E to "下短音符",
        0x0331 to "下划线",
        0x0335 to "短横贯线",
        0x0336 to "长横贯线",
        0x0337 to "短斜贯线",
        0x0338 to "长斜贯线",
        0x0342 to "希腊长音调",
        0x0343 to "希腊柔气符",
        0x0344 to "希腊分音尖音",
        0x0345 to "希腊下加符",
    )
}
