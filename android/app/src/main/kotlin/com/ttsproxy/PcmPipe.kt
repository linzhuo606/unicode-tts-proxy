package com.ttsproxy

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 下游音频的中转管道。
 *
 * **线程隔离**是这个类存在的第一个理由：`onAudioAvailable` 在 binder 线程回调，
 * 而 `SynthesisCallback` 的所有方法只能在合成线程调用。所以 binder 线程只负责入队，
 * 合成线程出队后再回灌。
 *
 * **绝不丢弃音频**是第二个理由，而且比第一个更要命：丢掉一段 PCM 不只是少一点声音，
 * 16 位采样每帧 2 字节，丢掉奇数长度的一段会让**后续所有采样错位一个字节**，
 * 听感是持续的刺耳乱读，而再丢一段奇数长度的又可能把对齐掰回来——
 * 表现就是「读着读着开始乱，有时又自己好了」。
 *
 * **绝不阻塞 binder 线程**是第三个理由，它其实是第二个理由的一部分。
 * 早先的做法是缓冲满了就让生产端（binder 线程）等着，看起来是背压，其实下游根本不会
 * 因此放慢：音频回调是 oneway 调用，下游只管往外发。我们这边一个 binder 线程卡住，
 * 同一个回调对象后面所有的回调都在内核里排队，占着本进程那点有限的异步缓冲；
 * 那块缓冲一满，下游再发的回调**直接失败**，音频在下游那头就被扔掉了——我们连计数都看不到。
 * 等待不但没保住音频，还把丢失藏了起来。
 *
 * 所以现在生产端永远不等，来多少收多少。内存由上游保证有界：[com.ttsproxy.core.Chunker]
 * 保证每块不超过 250 字，而我们一次只让下游合成一块。
 */
class PcmPipe(
    /**
     * 是否收集音频。兼容模式（直通）下由下游自己发声，我们不接管音频——
     * 但框架仍然会把 PCM 推给我们，这时必须立刻丢弃。
     */
    private val collectAudio: Boolean = true,
) {

    sealed class Event {
        /** 下游报出的音频格式。来自 onBeginSynthesis，不需要解析 WAV 头。 */
        class Format(val sampleRate: Int, val encoding: Int, val channels: Int) : Event() {
            override fun equals(other: Any?): Boolean = other is Format &&
                other.sampleRate == sampleRate && other.encoding == encoding &&
                other.channels == channels

            override fun hashCode(): Int = (sampleRate * 31 + encoding) * 31 + channels
            override fun toString(): String = "${sampleRate}Hz/enc$encoding/${channels}ch"
        }

        class Chunk(val bytes: ByteArray) : Event()
        object Done : Event()
        class Failed(val code: Int) : Event()
        object Interrupted : Event()
    }

    /** 无界队列：put() 永远不会阻塞，这正是 binder 线程需要的。 */
    private val queue = LinkedBlockingQueue<Event>()

    /** 此刻压在队列里、还没被合成线程取走的音频字节数。 */
    private val buffered = AtomicLong()

    @Volatile
    private var closed = false

    /** 当前积压的字节数，给诊断和回归检查用。 */
    val bufferedBytes: Long get() = buffered.get()

    // ---- 生产端：binder 线程调用，必须立刻返回 ----

    fun offerFormat(sampleRate: Int, encoding: Int, channels: Int) {
        if (!closed) queue.put(Event.Format(sampleRate, encoding, channels))
    }

    fun offerChunk(bytes: ByteArray) {
        if (!collectAudio || closed || bytes.isEmpty()) return
        val size = bytes.size.toLong()
        if (buffered.addAndGet(size) > MAX_BUFFERED_BYTES) {
            // 走到这里说明某一块的音频大得离谱（分块失效了），已经不是音质问题而是链路问题。
            // 宁可丢这一段并记下来，也不能卡住 binder 线程——卡住会让下游那头悄悄丢得更多。
            buffered.addAndGet(-size)
            Diagnostics.audioOverflows.incrementAndGet()
            Tlog.e(TAG, "音频积压超过上限，丢弃一段（会破坏字节对齐）")
            return
        }
        queue.put(Event.Chunk(bytes))
    }

    fun offerDone() {
        if (!closed) queue.put(Event.Done)
    }

    fun offerFailed(code: Int) {
        if (!closed) queue.put(Event.Failed(code))
    }

    // ---- 消费端：合成线程调用 ----

    fun poll(timeoutMs: Long): Event? {
        val event = try {
            queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
        if (event is Event.Chunk) buffered.addAndGet(-event.bytes.size.toLong())
        return event
    }

    /** 打断：清空积压并塞一个 Interrupted，立刻唤醒卡在 poll() 上的合成线程。 */
    fun interrupt() {
        closed = true
        queue.clear()
        buffered.set(0)
        queue.offer(Event.Interrupted)
    }

    companion object {
        private const val TAG = "PcmPipe"

        /**
         * 积压上限 24MB。这只是防失控的保险，正常永远碰不到。
         *
         * 账要算对：每块最多 250 字，正常语速约 55 秒——早先这里写的是「约 15 秒、
         * 不到 700KB」，差了三四倍。按那个估算定的 4MB 上限，在高采样率引擎上本来就离
         * 撑满不远，一旦分块失效就必然撑满。48kHz 16 位单声道每秒 96KB，55 秒约 5.3MB，
         * 语速调到半速也才十一二兆。
         */
        const val MAX_BUFFERED_BYTES = 24L * 1024 * 1024
    }
}
