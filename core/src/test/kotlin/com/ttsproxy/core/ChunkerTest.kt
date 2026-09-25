package com.ttsproxy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkerTest {

    @Test
    fun `短文本整段直通不分块`() {
        // TalkBack 自己已经做过句子级分块，绝大多数播报只有几十个字符。
        // 分块的每次请求都有队列往返开销，短文本上分块反而更慢。
        val short = "打开设置，选择文字转语音输出。"
        assertEquals(listOf(short), Chunker.split(short))
    }

    @Test
    fun `空文本返回空列表`() {
        assertEquals(emptyList(), Chunker.split(""))
    }

    @Test
    fun `长文本在句子边界处切开`() {
        val sentence = "这是一个用来测试分块的句子。"
        val text = sentence.repeat(60)
        val chunks = Chunker.split(text)

        assertTrue(chunks.size > 1, "应当被分块")
        assertEquals(text, chunks.joinToString(""), "分块不能丢字也不能改字")
        for (c in chunks) {
            assertTrue(c.endsWith("。") || c === chunks.last(), "应当切在句号后: $c")
        }
    }

    @Test
    fun `任何一块都不超过硬上限`() {
        val text = "符号".repeat(6000)   // 没有标点的长串，句子切分帮不上忙
        val chunks = Chunker.split(text)
        assertEquals(text, chunks.joinToString(""))
        for (c in chunks) {
            assertTrue(c.length <= Chunker.HARD_LIMIT, "块长 ${c.length} 超过硬上限")
        }
    }

    @Test
    fun `强切不会切在代理对中间`() {
        // 全部是补充平面字符，每个占两个 UTF-16 码元；在奇数位置强切会产出孤立代理项
        val emoji = cp(THUMBS_UP)
        val text = emoji.repeat(3000)
        val chunks = Chunker.split(text, hardLimit = 101, softLimit = 101)
        assertEquals(text, chunks.joinToString(""))
        for (c in chunks) {
            assertTrue(c.length <= 101)
            assertTrue(
                c.none { Character.isHighSurrogate(it) && c.indexOf(it) == c.length - 1 },
                "块尾出现孤立高代理项",
            )
            // 逐个码位重新解析应当和原字符数一致
            assertEquals(0, c.length % 2, "块长应为偶数，说明没切开代理对")
        }
    }

    @Test
    fun `膨胀后的文本仍能切进硬上限——这是静默失败的防线`() {
        // 规范化是膨胀变换：3999 字符的输入可能膨胀到 6000，
        // 超过下游的 getMaxSpeechInputLength() 会导致 synthesizeToFile 静默失败
        // 输入 1800 字符——远低于 4000，TalkBack 会毫不犹豫地发过来。
        // 但替换之后膨胀到 4000 以上，下游就会静默失败。
        val input = (cp(SUMMATION) + cp(INTEGRAL) + cp(DEGREE_C)).repeat(600)
        assertTrue(input.length < Chunker.HARD_LIMIT, "前提：输入本身是合法长度")
        val expanded = PIPELINE.transform(input, Verbosity.BRIEF)
        assertTrue(expanded.length > Chunker.HARD_LIMIT, "前提：替换后确实超了上限")

        val chunks = Chunker.split(expanded)
        assertEquals(expanded, chunks.joinToString(""))
        for (c in chunks) assertTrue(c.length <= Chunker.HARD_LIMIT)
    }
}
