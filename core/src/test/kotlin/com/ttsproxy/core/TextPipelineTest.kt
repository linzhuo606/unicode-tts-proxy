package com.ttsproxy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextPipelineTest {

    private fun brief(s: String) = PIPELINE.transform(s, Verbosity.BRIEF)
    private fun verbose(s: String) = PIPELINE.transform(s, Verbosity.VERBOSE)
    private fun off(s: String) = PIPELINE.transform(s, Verbosity.OFF)

    // ---------------- emoji ----------------

    @Test
    fun `基础 emoji 替换成中文并与原文分开`() {
        assertEquals("点赞 好的", brief(cp(THUMBS_UP) + "好的"))
        assertEquals("好的 点赞", brief("好的" + cp(THUMBS_UP)))
        assertEquals("a 点赞 b", brief("a" + cp(THUMBS_UP) + "b"))
    }

    @Test
    fun `已有空格时不再重复补空格`() {
        assertEquals("点赞 好的", brief(cp(THUMBS_UP) + " 好的"))
        assertFalse(brief(cp(THUMBS_UP) + " 好的").contains("  "))
    }

    @Test
    fun `ZWJ 家庭序列整体识别，不能拆成男人`() {
        val family = cp(MAN, ZWJ, WOMAN, ZWJ, GIRL, ZWJ, BOY)
        assertEquals("一家四口", brief(family))
        assertFalse(brief(family).contains("男人"))
    }

    @Test
    fun `肤色修饰序列优先于裸 emoji`() {
        assertEquals("浅肤色男人表情", verbose(cp(MAN, SKIN_LIGHT)))
    }

    @Test
    fun `国旗 keycap 与标签序列`() {
        assertEquals("中国", brief(cp(RI_C, RI_N)))
        assertEquals("数字一", brief(cp(0x31, VS16, KEYCAP)))
        assertEquals(
            "苏格兰旗",
            brief(cp(FLAG_BASE, 0xE0067, 0xE0062, 0xE0073, 0xE0063, 0xE0074, 0xE007F)),
        )
    }

    @Test
    fun `三个以上才合并计数，两个照读两遍`() {
        // 两个连着是「强调」，读两遍比报数字更贴近原意；三个以上才报数字避免刷屏
        assertEquals("点赞", brief(cp(THUMBS_UP)))
        assertEquals("点赞 点赞", brief(cp(THUMBS_UP, THUMBS_UP)))
        assertEquals("3 个 点赞", brief(cp(THUMBS_UP, THUMBS_UP, THUMBS_UP)))
        assertEquals("5 个 点赞", brief(cp(THUMBS_UP).repeat(5)))
    }

    @Test
    fun `整段只有一个词条时可以识别出来`() {
        // 逐字符浏览、或者别人发来一条纯 emoji 消息，都是这种情况
        assertTrue(PIPELINE.isSingleEntry(cp(THUMBS_UP)))
        assertTrue(PIPELINE.isSingleEntry("  " + cp(THUMBS_UP) + " "))
        assertTrue(PIPELINE.isSingleEntry(cp(MAN, ZWJ, WOMAN, ZWJ, GIRL, ZWJ, BOY)))
        assertTrue(PIPELINE.isSingleEntry(cp(SUMMATION)))

        assertFalse(PIPELINE.isSingleEntry(cp(THUMBS_UP) + "好"))
        assertFalse(PIPELINE.isSingleEntry(cp(THUMBS_UP, THUMBS_UP)))
        assertFalse(PIPELINE.isSingleEntry("好的"))
        assertFalse(PIPELINE.isSingleEntry(""))
        assertFalse(PIPELINE.isSingleEntry(null))
    }

    @Test
    fun `游离的 ZWJ 被丢弃而不是读出来`() {
        val result = brief(cp(THUMBS_UP, ZWJ, THUMBS_UP))
        assertEquals("点赞 点赞", result)
    }

    @Test
    fun `新版 Unicode emoji 能正常读出`() {
        assertEquals("融化的脸", brief(cp(MELTING_FACE)))
    }

    // ---------------- 数学 ----------------

    @Test
    fun `数学字母数字折叠成 ASCII`() {
        assertEquals("A1", brief(cp(0x1D400, 0x1D7CF)))          // 𝐀𝟏
        assertEquals("x", brief(cp(0x1D465)))                     // 𝑥 斜体 x
        assertEquals("AB", brief(cp(0x1D538, 0x1D539)))          // 𝔸𝔹 黑板粗体
        // ℂ 在 Letterlike Symbols 里，不折叠；测试词典里也没有它，所以原样透传
        assertEquals(cp(0x2102), brief(cp(0x2102)))
    }

    @Test
    fun `黑板粗体的数学含义不被折叠掉`() {
        // ℝ 必须读「实数集」，而不是被 NFKC 折成字母 R
        assertEquals("实数集", brief(cp(REALS)))
        assertEquals("x 实数集", brief("x" + cp(REALS)))
    }

    @Test
    fun `运算符按档位读出不同详略`() {
        assertEquals("求和", brief(cp(SUMMATION)))
        assertEquals("求和符号", verbose(cp(SUMMATION)))
        assertEquals("积分", brief(cp(INTEGRAL)))
    }

    @Test
    fun `上标下标`() {
        assertEquals("x 平方", brief("x" + cp(SUP_2)))
        assertEquals("x 上标 2", verbose("x" + cp(SUP_2)))
    }

    // ---------------- 物理化学 ----------------

    @Test
    fun `单位方块展开成全称`() {
        assertEquals("25 摄氏度", brief("25" + cp(DEGREE_C)))
        assertEquals("100 平方米", brief("100" + cp(SQ_M2)))
        assertEquals("3 立方米", brief("3" + cp(SQ_M3)))
        assertEquals("5 千克", brief("5" + cp(SQ_KG)))
        assertEquals("欧姆", brief(cp(OHM_SIGN)))
        assertEquals("埃", brief(cp(ANGSTROM)))
    }

    @Test
    fun `化学式的下标与电荷`() {
        assertEquals("H 下标 2 O", brief("H" + cp(SUB_2) + "O"))
        assertEquals("SO 下标 4 平方 负", brief("SO" + cp(SUB_4) + cp(SUP_2) + cp(SUP_MINUS)))
    }

    @Test
    fun `可逆反应箭头`() {
        assertEquals("A 可逆 B", brief("A" + cp(EQUILIBRIUM) + "B"))
        assertEquals("A 可逆反应 B", verbose("A" + cp(EQUILIBRIUM) + "B"))
    }

    // ---------------- 不可见字符 ----------------

    @Test
    fun `不可见字符被清理`() {
        assertEquals("ab", brief("a" + cp(0x200B) + "b"))
        assertEquals("ab", brief("a" + cp(0xFEFF) + "b"))
        assertEquals("ab", brief("a" + cp(0x00AD) + "b"))
        assertEquals("ab", brief("a" + cp(0x202E) + "b"))
    }

    @Test
    fun `孤立代理项与私用区字符被清理`() {
        assertEquals("ab", brief("a\uD800b"))
        assertEquals("ab", brief("a" + cp(0xE000) + "b"))
    }

    @Test
    fun `换行与制表符保留`() {
        assertEquals("a\nb", brief("a\nb"))
        assertEquals("a\tb", brief("a\tb"))
    }

    // ---------------- 详略三档 ----------------

    @Test
    fun `关闭档丢弃 emoji`() {
        assertEquals("好的", off(cp(THUMBS_UP) + "好的"))
        assertEquals("", off(cp(THUMBS_UP, THUMBS_UP)))
        assertEquals("", off(cp(MAN, ZWJ, WOMAN, ZWJ, GIRL, ZWJ, BOY)))
    }

    @Test
    fun `关闭档不替换符号但仍然折叠与清理`() {
        // 符号原样透传（这一档解决的是「别乱读」，不是「别处理」）
        assertEquals(cp(SUMMATION), off(cp(SUMMATION)))
        assertEquals(cp(DEGREE_C), off(cp(DEGREE_C)))
        // 但折叠与清理照做
        assertEquals("A1", off(cp(0x1D400, 0x1D7CF)))
        assertEquals("ab", off("a" + cp(0x200B) + "b"))
    }

    // ---------------- 健壮性 ----------------

    @Test
    fun `空输入与空字典不崩`() {
        assertEquals("", PIPELINE.transformSafe(null, Verbosity.BRIEF))
        assertEquals("", PIPELINE.transformSafe("", Verbosity.BRIEF))
        val bare = TextPipeline.withoutDict()
        assertEquals("A1", bare.transform(cp(0x1D400, 0x1D7CF), Verbosity.BRIEF))
        assertEquals(cp(THUMBS_UP), bare.transform(cp(THUMBS_UP), Verbosity.BRIEF))
    }

    @Test
    fun `普通中文原样透传`() {
        val plain = "打开设置，选择文字转语音输出。"
        assertEquals(plain, brief(plain))
    }

    @Test
    fun `魔鬼测试文本——三类符号混排`() {
        val devil = buildString {
            append("已完成")
            append(cp(THUMBS_UP))
            append(cp(MAN, ZWJ, WOMAN, ZWJ, GIRL, ZWJ, BOY))
            append("题目：")
            append(cp(SUMMATION))
            append(cp(0x1D400))          // 𝐀
            append(cp(REALS))
            append("，ΔH=")
            append("-")
            append("285.8 kJ，温度 25")
            append(cp(DEGREE_C))
            append("，H")
            append(cp(SUB_2))
            append("O")
            append(cp(0x200B))           // 零宽空格
        }
        val result = brief(devil)

        // 逐类确认
        assertTrue(result.contains("点赞"), result)
        assertTrue(result.contains("一家四口"), result)
        assertTrue(result.contains("求和"), result)
        assertTrue(result.contains("A"), result)
        assertTrue(result.contains("实数集"), result)
        assertTrue(result.contains("摄氏度"), result)
        assertTrue(result.contains("下标 2"), result)
        // 不可见字符不能残留
        assertFalse(result.contains(cp(0x200B)), "零宽空格残留")
        assertFalse(result.contains(cp(ZWJ)), "ZWJ 残留")
        // 数学粗体 A 必须已折叠，原字符不能残留
        assertFalse(result.contains(cp(0x1D400)), "数学粗体 A 未折叠")
    }

    @Test
    fun `相同符号连续出现时合并计数`() {
        // 目录里的「第一章 ·········· 5」这种，读成「10 个 点」远好过读十遍
        assertEquals("100 个 求和", brief(cp(SUMMATION).repeat(100)))
    }

    @Test
    fun `替换是膨胀变换——这正是必须在替换后再分块的原因`() {
        // 用不同符号交替，避开连续重复合并，才能看出真实的膨胀比
        val unit = cp(SUMMATION) + cp(INTEGRAL) + cp(DEGREE_C)
        val input = unit.repeat(200)
        val output = brief(input)
        assertTrue(
            output.length > input.length * 2,
            "长度 ${input.length} -> ${output.length}，膨胀比应大于 2",
        )
    }
}
