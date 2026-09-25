package com.ttsproxy

import java.util.concurrent.atomic.AtomicInteger

/**
 * 运行时诊断计数。
 *
 * 盲人用户没法截图，也未必能连电脑抓 logcat。把这些计数显示在「检查朗读效果」
 * 界面里，出问题时能直接念给我们听——这是可执行的故障报告。
 */
object Diagnostics {

    /** 音频缓冲长时间排不空，被迫丢弃。**只要不是 0 就说明音质出过问题。** */
    val audioOverflows = AtomicInteger()

    /** 同一次朗读里下游报出了不同的采样率，该块被丢弃。 */
    val formatDrifts = AtomicInteger()

    /** 下游引擎报错。 */
    val downstreamErrors = AtomicInteger()

    /** 看门狗超时：下游长时间没有任何输出。 */
    val watchdogTimeouts = AtomicInteger()

    /** 下游没有上报音频格式，用了兜底参数。 */
    val missingFormats = AtomicInteger()

    /** 累计转发的句子数，用来判断上面那些计数的相对严重程度。 */
    val utterances = AtomicInteger()

    /**
     * 用户**选定**的下游引擎，和**实际**连上的那个。
     *
     * 这两个不一致就是「我选了 A 却听到系统默认」的现场证据：框架在连不上目标引擎时
     * 会悄悄换一个再报成功。盲人用户没法看日志，把这两个念出来就能当场判断。
     */
    @Volatile var wantedEngine: String? = null
    @Volatile var actualEngine: String? = null

    /** 目标引擎此刻连不上，正在用别的顶着。锁屏那会儿就是这个状态。 */
    @Volatile var onFallback = false

    fun summary(): String = buildString {
        append("已转发 ").append(utterances.get()).append(" 句")
        append("；音频丢弃 ").append(audioOverflows.get()).append(" 次")
        append("；采样率漂移 ").append(formatDrifts.get()).append(" 次")
        append("；下游报错 ").append(downstreamErrors.get()).append(" 次")
        append("；等待超时 ").append(watchdogTimeouts.get()).append(" 次")
        append("；格式缺失 ").append(missingFormats.get()).append(" 次")
        append("。除「已转发」外全是 0 才正常。")
        // 这一句是给「重启后配置丢了」那类问题用的：用户念出来我就知道兜底有没有生效
        val wanted = wantedEngine
        val actual = actualEngine
        if (wanted != null) {
            append("　下游引擎：想连 ").append(wanted)
            append("，实际连上 ").append(actual ?: "读不到")
            if (onFallback) {
                append("　目标引擎此刻连不上，正在用顶替引擎发声，可用之后会自动换回去")
            }
            append("。")
        }
        Prefs.restoredFromMirror?.let {
            append("　本次启动从备份补回了配置：").append(it).append("。")
        }
    }

    fun reset() {
        audioOverflows.set(0)
        formatDrifts.set(0)
        downstreamErrors.set(0)
        watchdogTimeouts.set(0)
        missingFormats.set(0)
        utterances.set(0)
    }
}
