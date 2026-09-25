package com.ttsproxy.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** 排版细节：分隔空格不该插在标点前后。 */
class SpacingTest {

    private fun brief(s: String) = PIPELINE.transform(s, Verbosity.BRIEF)

    @Test
    fun `中文标点前不补空格`() {
        assertEquals("温度 25 摄氏度，面积", brief("温度 25" + cp(DEGREE_C) + "，面积"))
        assertEquals("求和。", brief(cp(SUMMATION) + "。"))
        assertEquals("求和、积分", brief(cp(SUMMATION) + "、" + cp(INTEGRAL)))
    }

    @Test
    fun `ASCII 标点前不补空格`() {
        assertEquals("求和, 后面", brief(cp(SUMMATION) + ", 后面"))
        assertEquals("求和)", brief(cp(SUMMATION) + ")"))
    }

    @Test
    fun `开括号后不补空格`() {
        assertEquals("（求和）", brief("（" + cp(SUMMATION) + "）"))
        assertEquals("(点赞)", brief("(" + cp(THUMBS_UP) + ")"))
    }

    @Test
    fun `普通字符仍然补空格`() {
        assertEquals("求和 x", brief(cp(SUMMATION) + "x"))
        assertEquals("x 求和", brief("x" + cp(SUMMATION)))
    }
}
