package com.ttsproxy.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 上下文条件读法。取舍原则是**宁可漏判也不要错判**：
 * 读成通用读法只是啰嗦，读错会误导学生。
 */
class ReadingContextTest {

    private fun brief(s: String) = PIPELINE.transform(s, Verbosity.BRIEF)

    // ---------------- Ω：欧姆 vs 欧米伽 ----------------

    @Test
    fun `数字后面的欧姆符号读欧姆`() {
        assertEquals("10 欧姆", brief("10" + cp(OMEGA_GREEK)))
        assertEquals("4.7 欧姆", brief("4.7" + cp(OMEGA_GREEK)))
        assertEquals("220 欧姆", brief("220 " + cp(OMEGA_GREEK)))
    }

    @Test
    fun `SI 词头后面的欧姆符号也读欧姆`() {
        // 4.7kΩ、10MΩ 在电路图里比裸数字还常见
        assertEquals("4.7k 欧姆", brief("4.7k" + cp(OMEGA_GREEK)))
        assertEquals("10M 欧姆", brief("10M" + cp(OMEGA_GREEK)))
    }

    @Test
    fun `孤立的欧米伽仍读欧米伽`() {
        assertEquals("欧米伽", brief(cp(OMEGA_GREEK)))
        assertEquals("角频率 欧米伽", brief("角频率" + cp(OMEGA_GREEK)))
    }

    // ---------------- ↑↓：气体沉淀 vs 箭头 ----------------

    @Test
    fun `化学式后的上下箭头读气体和沉淀`() {
        assertEquals("CO 下标 2 气体", brief("CO" + cp(SUB_2) + cp(ARROW_UP)))
        assertEquals("BaSO 下标 4 沉淀", brief("BaSO" + cp(SUB_4) + cp(ARROW_DOWN)))
    }

    @Test
    fun `不是化学式时仍读箭头——这是刻意收紧的判据`() {
        // 判据要求「前面出现过下标数字」，所以下面这些不会被误读成气体
        assertEquals("GDP 上箭头", brief("GDP" + cp(ARROW_UP)))
        assertEquals("价格 上箭头", brief("价格" + cp(ARROW_UP)))
        assertEquals("3 下箭头", brief("3" + cp(ARROW_DOWN)))
        // 代价是没有下标的化学式会漏判，读成箭头而不是沉淀
        assertEquals("AgCl 下箭头", brief("AgCl" + cp(ARROW_DOWN)))
    }

    // ---------------- ′：导数 vs 角分 ----------------

    @Test
    fun `字母后的撇号读导数，数字后读角分`() {
        assertEquals("f 导数", brief("f" + cp(PRIME)))
        assertEquals("45 分", brief("45" + cp(PRIME)))
    }

    // ---------------- μ：微 vs 缪 ----------------

    @Test
    fun `单位前的 mu 读微，其余读缪`() {
        // μm 有专门的两码位词条，整体读「微米」，比「微 m」清楚
        assertEquals("650 微米", brief("650" + cp(MU_GREEK) + "m"))
        // 没有专门词条的单位字母，靠 before_latin 条件退回读「微」
        assertEquals("5 微 H", brief("5" + cp(MU_GREEK) + "H"))
        assertEquals("缪", brief(cp(MU_GREEK)))
        assertEquals("缪 = 5", brief(cp(MU_GREEK) + " = 5"))
    }

    @Test
    fun `成对书写的中文标点合并成一次读法`() {
        // 内置词典已经把破折号交还下游；这个选项留给用户词典。
        // 用户想让破折号读出来就照办，而中文破折号本来就写成「——」，读「2 个 破折号」是错的
        val mine = SymbolDict.Entry(
            intArrayOf(0x2014), SymbolDict.Kind.SYMBOL, "破折号", "",
            collapseRepeats = true, fromUser = true,
        )
        val pipeline = TextPipeline(SymbolDict.of(listOf(listOf(mine))))
        assertEquals("破折号", pipeline.transform(cp(0x2014, 0x2014), Verbosity.BRIEF))
        assertEquals("这里 破折号 那里", pipeline.transform("这里" + cp(0x2014, 0x2014) + "那里", Verbosity.BRIEF))
        // 没有标记 collapse 的仍然报个数
        assertEquals("3 个 求和", brief(cp(SUMMATION, SUMMATION, SUMMATION)))
    }

    @Test
    fun `没有条件的词条不受影响`() {
        assertEquals("25 摄氏度", brief("25" + cp(DEGREE_C)))
        assertEquals("求和", brief(cp(SUMMATION)))
    }
}
