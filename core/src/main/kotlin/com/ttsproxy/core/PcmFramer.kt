package com.ttsproxy.core

/**
 * PCM 帧对齐。
 *
 * 下游引擎每次回调给多少字节由它自己决定，不保证是帧的整数倍。而 16 位采样每帧 2 字节，
 * 一旦在帧中间切开、或者漏掉一个字节，后面**所有**采样都会从错位的字节对重建——
 * 听感是持续的刺耳乱读，而下一次错位又可能把对齐掰回来，
 * 表现就是「读着读着开始乱，有时又自己好了」。
 *
 * 所以：只回灌整帧，不足一帧的尾巴留到下一段拼上，绝不丢弃也绝不单独灌出去。
 */
class PcmFramer(frameBytes: Int) {

    /** 一帧的字节数：采样宽度 × 声道数。至少 1。 */
    val frameBytes: Int = frameBytes.coerceAtLeast(1)

    private var leftover: ByteArray = EMPTY

    /** 还压在手里、不足一帧的字节数。正常情况下是 0 或 1。 */
    val pending: Int get() = leftover.size

    /**
     * 收下一段音频，返回可以立即回灌的、**长度必为帧整数倍**的数据。
     * 上一次剩下的尾巴会拼在前面。
     */
    fun accept(bytes: ByteArray): ByteArray {
        val data = if (leftover.isEmpty()) bytes else leftover + bytes
        val usable = (data.size / frameBytes) * frameBytes
        leftover = if (usable < data.size) data.copyOfRange(usable, data.size) else EMPTY
        return if (usable == data.size) data else data.copyOfRange(0, usable)
    }

    /**
     * 把上游允许的单次回灌上限对齐到帧。
     * 直接用 `maxBufferSize` 切分同样会切在帧中间。
     */
    fun alignBufferSize(maxBuffer: Int): Int =
        ((maxBuffer / frameBytes) * frameBytes).coerceAtLeast(frameBytes)

    fun reset() {
        leftover = EMPTY
    }

    companion object {
        private val EMPTY = ByteArray(0)

        /** 16 位是 2 字节一个采样，8 位是 1，浮点是 4；再乘声道数。 */
        fun frameBytesFor(bytesPerSample: Int, channels: Int): Int =
            (bytesPerSample.coerceAtLeast(1) * channels.coerceAtLeast(1))
    }
}
