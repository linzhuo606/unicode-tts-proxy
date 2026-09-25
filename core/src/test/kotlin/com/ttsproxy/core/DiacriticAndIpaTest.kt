package com.ttsproxy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 带附加符号的字母、以及它和国际音标的边界。
 *
 * 这两件事互相牵扯：同一个码位 U+0301 在 `café` 里是锐音符、在音标里是高平调，
 * 所以放在一处测，边界才看得清。
 */
class DiacriticAndIpaTest {

    private fun brief(s: String) = PIPELINE.transform(s, Verbosity.BRIEF)
    private fun verbose(s: String) = PIPELINE.transform(s, Verbosity.VERBOSE)
    private fun off(s: String) = PIPELINE.transform(s, Verbosity.OFF)

    // ---------------- 重音字母 ----------------

    @Test
    fun `简洁档把重音字母折成基本字母`() {
        assertEquals("cafe", brief("caf" + cp(E_ACUTE)))
        assertEquals("ma", brief("m" + cp(A_CARON)))
        assertEquals("Angstrom", brief("Ångström"))
    }

    @Test
    fun `详细档读出基本字母加符号名`() {
        assertEquals("e 锐音符", verbose(cp(E_ACUTE)))
        assertEquals("a 反折符", verbose(cp(A_CARON)))
    }

    @Test
    fun `已经拆开写的重音字母同样认得`() {
        assertEquals("e", brief("e" + cp(ACUTE_ABOVE)))
        assertEquals("e 锐音符", verbose("e" + cp(ACUTE_ABOVE)))
    }

    @Test
    fun `折出来的基本字母要再查一次词典`() {
        // ά 折成 α 之后必须命中希腊字母读法，否则等于把「阿尔法」退化成一个乱码字符
        assertEquals("阿尔法", brief(cp(ALPHA_TONOS)))
    }

    @Test
    fun `关闭档不做词典替换但照样折叠`() {
        // 「关闭」档解决的是「别乱读」，不是「别处理」
        assertEquals("cafe", off("caf" + cp(E_ACUTE)))
    }

    // ---------------- 与音标的边界 ----------------

    @Test
    fun `音标的组合符号不能被当成重音字母折掉`() {
        // ɛ 加波浪符合不出任何预组合字符，说明这是音标的鼻化，不是正字法的重音字母
        assertEquals("ɛ 鼻化", brief(cp(OPEN_E) + cp(TILDE_ABOVE)))
    }

    @Test
    fun `能合成的就按重音字母走，合不成的才留给音标`() {
        // e + 波浪符能合成 ẽ（葡萄牙语、越南语里真实存在）→ 折叠
        assertEquals("e", brief("e" + cp(TILDE_ABOVE)))
        // ɛ + 波浪符合不出来 → 波浪符走词典读「鼻化」
        assertEquals("ɛ 鼻化", brief(cp(OPEN_E) + cp(TILDE_ABOVE)))
    }

    // ---------------- 音标读法 ----------------

    @Test
    fun `音标简洁档读中文音理简称`() {
        assertEquals("软腭鼻音", brief(cp(ENG)))
        assertEquals("清龈后擦音", brief(cp(ESH)))
    }

    @Test
    fun `音标详细档读点位加音理`() {
        assertEquals("点 1 2 4 6 软腭鼻音", verbose(cp(ENG)))
    }

    @Test
    fun `切成盲文点位之后简洁档读点位`() {
        val braille = SymbolDict.of(listOf(SymbolDict.briefAsBraille(TEST_DICT.entries())))
        val pipeline = TextPipeline(braille)
        assertEquals("点 1 2 4 6", pipeline.transform(cp(ENG), Verbosity.BRIEF))
        // 详细档不受开关影响，任何时候都是点位加音理
        assertEquals("点 1 2 4 6 软腭鼻音", pipeline.transform(cp(ENG), Verbosity.VERBOSE))
    }

    @Test
    fun `字母后面跟几千个附加符号也不会卡住合成线程`() {
        // 乱码「炸弹消息」：上方类和下方类交替，NFC 重排是平方复杂度。
        // 不给尝试长度封顶时，电脑上要五六秒，手机上更久，整机失声
        val bomb = StringBuilder("e")
        repeat(2_000) { bomb.appendCodePoint(0x0301).appendCodePoint(0x0316) }
        val start = System.nanoTime()
        PIPELINE.transformSafe(bomb.toString(), Verbosity.VERBOSE)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 1_000, "处理用了 $ms 毫秒")
    }

    @Test
    fun `叠两层附加符号的字母照样整体折叠`() {
        // 封顶不能误伤正常的叠加：越南语 ệ = e + 下点 + 抑扬符
        assertEquals("e", brief("e" + cp(0x0323) + cp(0x0302)))
    }

    @Test
    fun `切换只影响音标，别的类别原样不动`() {
        val braille = SymbolDict.of(listOf(SymbolDict.briefAsBraille(TEST_DICT.entries())))
        val pipeline = TextPipeline(braille)
        assertEquals("求和", pipeline.transform(cp(SUMMATION), Verbosity.BRIEF))
        assertEquals("点赞", pipeline.transform(cp(THUMBS_UP), Verbosity.BRIEF))
    }
}
