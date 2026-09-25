package com.ttsproxy.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 空白与格式控制符不许有读法。
 *
 * 不换行空格 U+00A0 在网页文字里极常见（HTML 的 `&nbsp;`）。它是空白不是符号，
 * 念出来就是隔几个词冒一句「不换行空格」。软连字符、零宽空格同理，它们本该被悄悄去掉。
 * 词典匹配排在空格折叠和不可见字符清理之前，所以一旦词典给它们配了读法，后面的规则就拦不住——
 * 这里拿一份「坏词典」钉住这道闸。
 */
class SilentCharactersTest {

    /** 模拟批量补词典时混进来的那类条目。 */
    private val badDict = SymbolDict.parse(
        listOf(
            "00A0\tS\t不换行空格",
            "00AD\tS\t软连字符",
            "3000\tS\t全角空格",
            "2009\tS\t窄空格",
            "200B\tS\t零宽空格",
        ).asSequence()
    )

    @Test
    fun `不换行空格不念，只当普通空格`() {
        val out = TextPipeline(badDict).transform("你好" + cp(0x00A0) + "世界", Verbosity.BRIEF)
        assertEquals("你好 世界", out)
    }

    @Test
    fun `软连字符和零宽空格不念，直接去掉`() {
        val pipeline = TextPipeline(badDict)
        assertEquals("international", pipeline.transform("inter" + cp(0x00AD) + "national", Verbosity.BRIEF))
        assertEquals("你好", pipeline.transform("你" + cp(0x200B) + "好", Verbosity.BRIEF))
    }

    @Test
    fun `各种排版空格在详细档也不念`() {
        val pipeline = TextPipeline(badDict)
        for (c in intArrayOf(0x3000, 0x2009)) {
            assertEquals("a b", pipeline.transform("a" + cp(c) + "b", Verbosity.VERBOSE))
        }
    }

    @Test
    fun `用户自己要给空白配读法，照办`() {
        // 用户词典是用户的明确选择，不受这条保护限制
        val mine = SymbolDict.Entry(intArrayOf(0x00A0), SymbolDict.Kind.SYMBOL, "空格", "", fromUser = true)
        val dict = SymbolDict.of(listOf(listOf(mine), badDict.entries()))
        assertEquals("a 空格 b", TextPipeline(dict).transform("a" + cp(0x00A0) + "b", Verbosity.BRIEF))
    }

    @Test
    fun `类别判定覆盖空白、控制与格式字符，不误伤普通字符`() {
        for (c in intArrayOf(0x0020, 0x00A0, 0x3000, 0x2009, 0x2028, 0x2029, 0x0009, 0x00AD, 0x200B, 0xFEFF)) {
            assertEquals(true, Invisibles.isWhitespaceOrFormat(c), "U+%04X 应当算空白或格式字符".format(c))
        }
        for (c in intArrayOf('a'.code, 0x4E2D, 0x3002, 0x2211, THUMBS_UP)) {
            assertEquals(false, Invisibles.isWhitespaceOrFormat(c), "U+%04X 不该被当成空白".format(c))
        }
    }
}
