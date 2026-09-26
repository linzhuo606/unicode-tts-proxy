package com.ttsproxy

import android.content.Context
import android.os.SystemClock
import java.util.ArrayDeque
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
     * 框架调 onStop 时，我们这边已经没有正在读的句子。
     *
     * 框架的 stop 是按「当时正在读的那一句」发出的，但送到我们手里没有句子编号；
     * 那一句要是在 stop 送达之前恰好读完了，这次 stop 就会落到下一句头上。
     * 这个计数不是 0，就说明真机上撞见过这个窗口。
     */
    val lateStops = AtomicInteger()

    /** 下游主动打断了我们正在合成的一块，而不是我们叫它停的。 */
    val downstreamStops = AtomicInteger()

    /** 提交被拒或卡死之后丢弃下游连接、重连的次数。 */
    val reconnects = AtomicInteger()

    /** 两句之间换了发声引擎（开机时从顶替引擎换回目标引擎也算一次）。 */
    val engineSwitches = AtomicInteger()

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

    /** 到本引擎自己的那条连接连上了谁。念出来不是本应用的包名，防冻结就没生效。 */
    @Volatile var selfSessionEngine: String? = null

    /** 本进程启动时距开机多少秒。数值很大说明进程在用着用着的时候被杀过、重新拉起来了。 */
    val startedAtUptimeSec: Long = SystemClock.elapsedRealtime() / 1000

    /** 本次开机后这是第几次启动本进程。1 才正常；更大说明进程死过。 */
    @Volatile var launchesThisBoot = 0
        private set

    /** 一句朗读是怎么结束的。标签是念给用户听的，要短、要能听辨。 */
    enum class EndReason(val label: String) {
        DONE("读完"),
        EMPTY("处理后为空"),
        UPSTREAM_STOP("上游打断"),
        UPSTREAM_REFUSED("上游已停、拒收音频"),
        DOWNSTREAM_STOP("下游打断"),
        DOWNSTREAM_ERROR("下游报错"),
        STALLED("下游无输出超时"),
        SUBMIT_REJECTED("提交被拒"),
        FORMAT_DRIFT("采样率漂移"),
        NO_ENGINE("引擎连不上"),
        LOOP("请求环路"),
        EXCEPTION("异常"),
    }

    /** 最近一句的记录。 */
    class Record(
        val seq: Int,
        val chars: Int,
        val chunk: Int,
        val chunks: Int,
        val caller: String,
        val reason: EndReason,
        val detail: String?,
        val engine: String?,
        val standIn: Boolean,
        val elapsedMs: Long,
        val uptimeSec: Long,
    )

    private const val HISTORY_SIZE = 8
    private val history = ArrayDeque<Record>(HISTORY_SIZE)

    /** 两句之间换了引擎，或者别的值得念出来的事件。和句子记录混排，按时间顺序。 */
    private val events = ArrayDeque<String>(HISTORY_SIZE)

    fun record(r: Record) {
        synchronized(history) {
            if (history.size >= HISTORY_SIZE) history.removeFirst()
            history.addLast(r)
        }
    }

    fun event(text: String) {
        synchronized(events) {
            if (events.size >= HISTORY_SIZE) events.removeFirst()
            events.addLast("开机后第 " + SystemClock.elapsedRealtime() / 1000 + " 秒：" + text)
        }
    }

    /**
     * 进程启动时调一次。同一次开机内每启动一次加一，跨开机归一。
     * 「同一次开机」用开机时刻判断：墙钟减去开机以来的毫秒数，同一次开机内基本不变。
     */
    fun noteProcessStart(context: Context) {
        runCatching {
            val prefs = Prefs.of(context)
            val bootStamp = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            val known = prefs.getLong(KEY_BOOT_STAMP, 0L)
            val sameBoot = Math.abs(bootStamp - known) < BOOT_STAMP_TOLERANCE_MS
            val count = if (sameBoot) prefs.getInt(KEY_LAUNCHES, 0) + 1 else 1
            launchesThisBoot = count
            prefs.edit().putLong(KEY_BOOT_STAMP, bootStamp).putInt(KEY_LAUNCHES, count).apply()
        }
    }

    fun summary(): String = buildString {
        append("已转发 ").append(utterances.get()).append(" 句")
        append("；音频丢弃 ").append(audioOverflows.get()).append(" 次")
        append("；采样率漂移 ").append(formatDrifts.get()).append(" 次")
        append("；下游报错 ").append(downstreamErrors.get()).append(" 次")
        append("；等待超时 ").append(watchdogTimeouts.get()).append(" 次")
        append("；格式缺失 ").append(missingFormats.get()).append(" 次")
        append("；迟到的打断 ").append(lateStops.get()).append(" 次")
        append("；下游主动打断 ").append(downstreamStops.get()).append(" 次")
        append("；丢弃连接 ").append(reconnects.get()).append(" 次")
        append("；换引擎 ").append(engineSwitches.get()).append(" 次")
        append("。除「已转发」和「换引擎」外全是 0 才正常。")
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
        append("　防冻结连接：")
        append(selfSessionEngine?.let { "已连上 " + it } ?: "还没连上")
        append("。")
        append("　本进程在开机后第 ").append(startedAtUptimeSec).append(" 秒启动")
        if (launchesThisBoot > 0) {
            append("，是本次开机后第 ").append(launchesThisBoot).append(" 次启动")
        }
        append("。")
        Prefs.restoredFromMirror?.let {
            append("　本次启动从备份补回了配置：").append(it).append("。")
        }
    }

    /** 最近几句各自是怎么结束的，念出来就能定位是哪一环断的。 */
    fun history(): String {
        val records = synchronized(history) { history.toList() }
        val notes = synchronized(events) { events.toList() }
        if (records.isEmpty() && notes.isEmpty()) return "还没有转发过句子。"
        return buildString {
            append("最近的句子，从早到晚：")
            for (r in records) {
                append("\n第 ").append(r.seq).append(" 句，")
                append(r.chars).append(" 字")
                if (r.chunks > 1) append("，第 ").append(r.chunk).append(" 块，共 ").append(r.chunks).append(" 块")
                append("，来自 ").append(r.caller)
                append("，").append(r.reason.label)
                r.detail?.let { append("（").append(it).append("）") }
                append("，用时 ").append(String.format("%.1f", r.elapsedMs / 1000.0)).append(" 秒")
                append("，开机后第 ").append(r.uptimeSec).append(" 秒")
                r.engine?.let {
                    append("，引擎 ").append(it)
                    if (r.standIn) append("（顶替中）")
                }
                append("。")
            }
            if (notes.isNotEmpty()) {
                append("\n其他事件：")
                for (n in notes) append("\n").append(n).append("。")
            }
        }
    }

    fun reset() {
        audioOverflows.set(0)
        formatDrifts.set(0)
        downstreamErrors.set(0)
        watchdogTimeouts.set(0)
        missingFormats.set(0)
        utterances.set(0)
        lateStops.set(0)
        downstreamStops.set(0)
        reconnects.set(0)
        engineSwitches.set(0)
        synchronized(history) { history.clear() }
        synchronized(events) { events.clear() }
    }

    private const val KEY_BOOT_STAMP = "diag_boot_stamp"
    private const val KEY_LAUNCHES = "diag_launches_this_boot"

    /** 墙钟在一次开机内可能被网络校时挪动几秒，留足余量。 */
    private const val BOOT_STAMP_TOLERANCE_MS = 5 * 60_000L
}
