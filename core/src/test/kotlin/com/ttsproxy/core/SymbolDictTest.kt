package com.ttsproxy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SymbolDictTest {

    @Test
    fun `解析出正确条数并跳过坏行`() {
        // 固定装置里有一条空码位行和一条非法十六进制行，都应被跳过
        // 固定装置里有一条空码位行和一条非法十六进制行，都应被跳过
        assertEquals(TEST_DICT_ENTRIES - 2, TEST_DICT.size)
    }

    @Test
    fun `最长匹配——一家四口不能被拆成男人`() {
        val family = intArrayOf(MAN, ZWJ, WOMAN, ZWJ, GIRL, ZWJ, BOY)
        val hit = TEST_DICT.match(family, 0)
        assertEquals("一家四口", hit?.brief)
        assertEquals(7, hit?.length)
    }

    @Test
    fun `序列不完整时回退到较短的匹配`() {
        val lone = intArrayOf(MAN, 'a'.code)
        assertEquals("男人", TEST_DICT.match(lone, 0)?.brief)
    }

    @Test
    fun `尾部截断的序列不会越界`() {
        val truncated = intArrayOf(MAN, ZWJ, WOMAN)
        assertEquals("男人", TEST_DICT.match(truncated, 0)?.brief)
    }

    @Test
    fun `详细读法留空时回退到简洁读法`() {
        val hit = TEST_DICT.match(intArrayOf(DEGREE_C), 0)!!
        assertEquals("摄氏度", hit.brief)
        assertEquals("摄氏度", hit.verbose)
        assertEquals("摄氏度", hit.reading(Verbosity.VERBOSE))
    }

    @Test
    fun `简洁与详细读法可以不同`() {
        val hit = TEST_DICT.match(intArrayOf(SUP_2), 0)!!
        assertEquals("平方", hit.reading(Verbosity.BRIEF))
        assertEquals("上标 2", hit.reading(Verbosity.VERBOSE))
    }

    @Test
    fun `没匹配上返回 null`() {
        assertNull(TEST_DICT.match(intArrayOf('a'.code), 0))
    }

    @Test
    fun `空词典可用且不崩`() {
        assertTrue(SymbolDict.EMPTY.isEmpty)
        assertNull(SymbolDict.EMPTY.match(intArrayOf(THUMBS_UP), 0))
        assertSame(SymbolDict.EMPTY, SymbolDict.EMPTY)
    }

    @Test
    fun `码位分隔符支持空格和连字符`() {
        val d = SymbolDict.parse(sequenceOf("1F1E8-1F1F3\tE\t中国"))
        assertEquals("中国", d.match(intArrayOf(RI_C, RI_N), 0)?.brief)
    }
}
