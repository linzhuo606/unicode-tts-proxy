package com.ttsproxy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MathFoldTest {

    @Test
    fun `数学粗体字母折叠成 ASCII`() {
        assertEquals('A'.code, MathFold.fold(0x1D400))   // 𝐀
        assertEquals('Z'.code, MathFold.fold(0x1D419))
        assertEquals('a'.code, MathFold.fold(0x1D41A))
        assertEquals('z'.code, MathFold.fold(0x1D433))
    }

    @Test
    fun `每个字母风格区段的首尾都对得上`() {
        val blocks = listOf(
            0x1D400, 0x1D41A, 0x1D434, 0x1D44E, 0x1D468, 0x1D482,
            0x1D49C, 0x1D4B6, 0x1D4D0, 0x1D4EA, 0x1D504, 0x1D51E,
            0x1D538, 0x1D552, 0x1D56C, 0x1D586, 0x1D5A0, 0x1D5BA,
            0x1D5D4, 0x1D5EE, 0x1D608, 0x1D622, 0x1D63C, 0x1D656,
            0x1D670, 0x1D68A,
        )
        for ((index, start) in blocks.withIndex()) {
            val base = if (index % 2 == 0) 'A'.code else 'a'.code
            assertEquals(base, MathFold.fold(start), "区段 ${start.toString(16)} 起点")
            assertEquals(base + 25, MathFold.fold(start + 25), "区段 ${start.toString(16)} 终点")
        }
    }

    @Test
    fun `黑板粗体 X 折叠成 X`() {
        assertEquals('X'.code, MathFold.fold(0x1D54F))  // 𝕏
    }

    @Test
    fun `五套数学数字都折叠成 ASCII 数字`() {
        for (start in listOf(0x1D7CE, 0x1D7D8, 0x1D7E2, 0x1D7EC, 0x1D7F6)) {
            assertEquals('0'.code, MathFold.fold(start))
            assertEquals('9'.code, MathFold.fold(start + 9))
        }
        assertEquals('1'.code, MathFold.fold(0x1D7CF))  // 𝟏
    }

    @Test
    fun `无点 i 和 j`() {
        assertEquals('i'.code, MathFold.fold(0x1D6A4))
        assertEquals('j'.code, MathFold.fold(0x1D6A5))
    }

    @Test
    fun `数学希腊字母折叠成普通希腊字母`() {
        // 粗体区段 U+1D6A8 起：0..24 大写，25 是 ∇，26..50 小写，51 是 ∂，52..57 变体形
        assertEquals(0x0391, MathFold.fold(0x1D6A8))          // 𝚨 -> Α
        assertEquals(0x03A9, MathFold.fold(0x1D6A8 + 24))     // -> Ω
        assertEquals(0x03F4, MathFold.fold(0x1D6A8 + 17))     // -> ϴ（插在 Ρ 和 Σ 之间）
        assertEquals(0x2207, MathFold.fold(0x1D6A8 + 25))     // -> ∇
        assertEquals(0x03B1, MathFold.fold(0x1D6A8 + 26))     // -> α
        assertEquals(0x03C9, MathFold.fold(0x1D6A8 + 50))     // -> ω
        assertEquals(0x2202, MathFold.fold(0x1D6A8 + 51))     // -> ∂
        assertEquals(0x03F5, MathFold.fold(0x1D6A8 + 52))     // -> ϵ
    }

    @Test
    fun `五个希腊风格区段间隔 58 个码位`() {
        for (start in listOf(0x1D6A8, 0x1D6E2, 0x1D71C, 0x1D756, 0x1D790)) {
            assertEquals(0x0391, MathFold.fold(start), "区段 ${start.toString(16)}")
            assertEquals(0x2202, MathFold.fold(start + 51), "区段 ${start.toString(16)} 的 ∂")
        }
    }

    @Test
    fun `全角字母数字与运算符折叠`() {
        assertEquals('A'.code, MathFold.fold(0xFF21))
        assertEquals('z'.code, MathFold.fold(0xFF5A))
        assertEquals('0'.code, MathFold.fold(0xFF10))
        assertEquals('9'.code, MathFold.fold(0xFF19))
        assertEquals('+'.code, MathFold.fold(0xFF0B))
        assertEquals('='.code, MathFold.fold(0xFF1D))
    }

    @Test
    fun `全角标点不折叠——中文引擎需要它来断句`() {
        assertFalse(MathFold.canFold(0xFF0C))  // ，
        assertFalse(MathFold.canFold(0xFF1F))  // ？
        assertFalse(MathFold.canFold(0xFF01))  // ！
    }

    @Test
    fun `Letterlike Symbols 不参与折叠——那会把语义读没`() {
        // 这正是不能整体套 NFKC 的原因：NFKC 会把 ℝ 折成 R、ℑ 折成 I
        assertFalse(MathFold.canFold(0x211D))  // ℝ 实数集
        assertFalse(MathFold.canFold(0x2111))  // ℑ 虚部
        assertFalse(MathFold.canFold(0x210E))  // ℎ 普朗克常数
        assertFalse(MathFold.canFold(0x2115))  // ℕ
        assertFalse(MathFold.canFold(0x2124))  // ℤ
    }

    @Test
    fun `普通字符不折叠`() {
        assertFalse(MathFold.canFold('A'.code))
        assertFalse(MathFold.canFold(0x4E2D))   // 中
        assertFalse(MathFold.canFold(THUMBS_UP))
        assertTrue(MathFold.fold('A'.code) < 0)
    }

    @Test
    fun `排版空格折成普通空格`() {
        // 个别引擎不认这些空白，数字会被粘成一个大数；也不能丢弃，丢了词就连一起了
        for (cp in intArrayOf(0x2000, 0x2003, 0x2009, 0x200A, 0x202F, 0x205F, 0x00A0, 0x3000)) {
            assertEquals(' '.code, MathFold.fold(cp), "U+%04X 应当折成普通空格".format(cp))
        }
    }

    @Test
    fun `行段分隔符也折成空格`() {
        assertEquals(' '.code, MathFold.fold(0x2028))
        assertEquals(' '.code, MathFold.fold(0x2029))
    }

    @Test
    fun `零宽字符不在折叠之列——它们该被丢弃`() {
        // 零宽空格折成普通空格会凭空插出一个词边界，那是另一码事，归 Invisibles 管
        assertTrue(MathFold.fold(0x200B) < 0)
        assertTrue(Invisibles.shouldDrop(0x200B))
    }
}
