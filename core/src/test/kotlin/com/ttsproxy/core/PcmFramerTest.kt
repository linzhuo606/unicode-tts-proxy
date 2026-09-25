package com.ttsproxy.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 这组测试守的是一个真实故障：下游引擎回调给的缓冲长度不是帧的整数倍时，
 * 从帧中间切开会让后续所有采样错位一个字节，听感是持续的刺耳乱读。
 */
class PcmFramerTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `整帧数据原样通过`() {
        val framer = PcmFramer(2)
        assertContentEquals(bytes(1, 2, 3, 4), framer.accept(bytes(1, 2, 3, 4)))
        assertEquals(0, framer.pending)
    }

    @Test
    fun `不足一帧的尾巴留到下一段拼上`() {
        val framer = PcmFramer(2)
        // 奇数长度：最后一个字节必须扣下来
        assertContentEquals(bytes(1, 2), framer.accept(bytes(1, 2, 3)))
        assertEquals(1, framer.pending)
        // 下一段拼上，凑齐的帧才放行
        assertContentEquals(bytes(3, 4, 5, 6), framer.accept(bytes(4, 5, 6)))
        assertEquals(0, framer.pending)
    }

    @Test
    fun `连续奇数长度的输入不会丢字节也不会错位`() {
        val framer = PcmFramer(2)
        val input = ArrayList<Byte>()
        val output = ArrayList<Byte>()
        var next = 0
        // 全是奇数长度，最容易暴露错位
        for (size in listOf(1, 3, 5, 7, 9, 11, 13)) {
            val chunk = ByteArray(size) { (next++ % 251).toByte() }
            input.addAll(chunk.toList())
            val aligned = framer.accept(chunk)
            assertEquals(0, aligned.size % 2, "回灌的长度必须是帧的整数倍")
            output.addAll(aligned.toList())
        }
        // 输出加上还压着的尾巴，应当和输入逐字节相同——顺序没乱、一个字节没丢
        assertEquals(input.size, output.size + framer.pending)
        assertContentEquals(
            input.take(output.size).toByteArray(),
            output.toByteArray(),
            "字节流必须原样保持，错一个字节就是持续噪声",
        )
    }

    @Test
    fun `四字节帧同样对齐`() {
        val framer = PcmFramer(4)   // 16 位立体声
        assertContentEquals(bytes(1, 2, 3, 4), framer.accept(bytes(1, 2, 3, 4, 5, 6)))
        assertEquals(2, framer.pending)
        assertContentEquals(bytes(5, 6, 7, 8), framer.accept(bytes(7, 8)))
    }

    @Test
    fun `切分上限对齐到帧`() {
        assertEquals(8192, PcmFramer(2).alignBufferSize(8192))
        assertEquals(8192, PcmFramer(4).alignBufferSize(8192))
        // 上游给了个奇数上限，必须向下取整到帧
        assertEquals(8190, PcmFramer(2).alignBufferSize(8191))
        assertEquals(8188, PcmFramer(4).alignBufferSize(8191))
        // 上限比一帧还小时至少给一帧，否则切不动会死循环
        assertEquals(4, PcmFramer(4).alignBufferSize(1))
    }

    @Test
    fun `帧宽非法时退化为 1 而不是崩溃或死循环`() {
        val framer = PcmFramer(0)
        assertEquals(1, framer.frameBytes)
        assertContentEquals(bytes(1, 2, 3), framer.accept(bytes(1, 2, 3)))
        assertTrue(framer.alignBufferSize(0) >= 1)
    }

    @Test
    fun `帧宽计算`() {
        assertEquals(2, PcmFramer.frameBytesFor(2, 1))   // 16 位单声道
        assertEquals(4, PcmFramer.frameBytesFor(2, 2))   // 16 位立体声
        assertEquals(1, PcmFramer.frameBytesFor(1, 1))   // 8 位单声道
        assertEquals(8, PcmFramer.frameBytesFor(4, 2))   // 浮点立体声
    }
}
