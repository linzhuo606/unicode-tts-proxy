package com.ttsproxy.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InvisiblesTest {

    @Test
    fun `零宽与双向控制符要丢弃`() {
        val drop = listOf(
            0x200B, // 零宽空格
            0x200C, // 零宽非连接符
            0x200D, // 游离的零宽连接符
            0x200E, 0x200F,
            0x202A, 0x202E,
            0x2060, 0x2064,
            0x2066, 0x2069,
            0xFEFF, // BOM
            0x00AD, // 软连字符
            0x034F,
        )
        for (cp in drop) assertTrue(Invisibles.shouldDrop(cp), "U+${cp.toString(16).uppercase()}")
    }

    @Test
    fun `游离的变体选择符与肤色修饰符要丢弃`() {
        assertTrue(Invisibles.shouldDrop(0xFE0E))
        assertTrue(Invisibles.shouldDrop(0xFE0F))
        assertTrue(Invisibles.shouldDrop(0x1F3FB))
        assertTrue(Invisibles.shouldDrop(0x1F3FF))
        assertTrue(Invisibles.shouldDrop(0xE0067))  // 标签字符
    }

    @Test
    fun `孤立代理项 私用区 非字符要丢弃`() {
        assertTrue(Invisibles.shouldDrop(0xD800))
        assertTrue(Invisibles.shouldDrop(0xDFFF))
        assertTrue(Invisibles.shouldDrop(0xE000))
        assertTrue(Invisibles.shouldDrop(0xF8FF))
        assertTrue(Invisibles.shouldDrop(0xFFFE))
        assertTrue(Invisibles.shouldDrop(0xFDD0))
        assertTrue(Invisibles.shouldDrop(0x10FFFF))
    }

    @Test
    fun `控制符丢弃但保留换行与制表符`() {
        assertTrue(Invisibles.shouldDrop(0x00))
        assertTrue(Invisibles.shouldDrop(0x07))
        assertTrue(Invisibles.shouldDrop(0x7F))
        assertTrue(Invisibles.shouldDrop(0x9F))
        assertFalse(Invisibles.shouldDrop('\t'.code))
        assertFalse(Invisibles.shouldDrop('\n'.code))
        assertFalse(Invisibles.shouldDrop('\r'.code))
    }

    @Test
    fun `新版 Unicode 的 emoji 不能因为 JDK 认不出就被丢掉`() {
        // 🫠 U+1FAE0 是 Unicode 14 新增的，JDK 17 内置的是 Unicode 13，
        // Character.isDefined() 会返回 false。如果无条件套用这个判定，
        // 我们就会把本来能读的 emoji 弄没——这正是要把未分配判定限制在 BMP 的原因。
        assertFalse(Character.isDefined(MELTING_FACE), "前提：JDK 确实认不出这个码位")
        assertFalse(Invisibles.shouldDrop(MELTING_FACE), "但我们不能丢掉它")
    }

    @Test
    fun `正常字符不丢弃`() {
        assertFalse(Invisibles.shouldDrop('A'.code))
        assertFalse(Invisibles.shouldDrop(0x4E2D))
        assertFalse(Invisibles.shouldDrop(THUMBS_UP))
        assertFalse(Invisibles.shouldDrop(SUMMATION))
        assertFalse(Invisibles.shouldDrop(' '.code))
    }
}
