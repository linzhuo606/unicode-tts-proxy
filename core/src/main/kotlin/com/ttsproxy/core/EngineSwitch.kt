package com.ttsproxy.core

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 发声引擎的切换：用户选了哪个、此刻在用哪个、连不上时谁来顶。
 *
 * 纯逻辑，不碰 Android API——真正怎么连上一个引擎交给 [Connector]，
 * 所以这里的每一条规则都能在本机测。
 *
 * ## 「此刻用的不是用户选的」有两种，处理方式正好相反
 *
 * - **刚选的引擎**：立刻去连，这一句**等它**。手里有旧引擎能顶着时最多等 [Timing.switchWaitMs]，
 *   超时就先让旧引擎读这一句，连好了下一句再换。
 * - **这一轮已经连失败过的引擎**：合成线程**不再等它**，谁能出声就先用谁，重连交给后台。
 *   开机输密码那会儿目标引擎起不来，就是这种情况——出声比出对的声重要。
 *
 * 上一版把两者当成了同一件事：只要手里有能用的引擎、又不是用户选的那个，就先用着，
 * 等后台每五秒一次的检查再换。开机时这没错，可用户在设置里从 A 换到 B 时，旧的 A 也「能用」，
 * 于是要等五秒多才真正换过去，连「将由 B 发声」这句确认都是 A 念的。
 *
 * ## 线程
 *
 * - [acquire] 在合成线程上调，而且**只有它会更换正在用的引擎**。所以换引擎只发生在两句之间，
 *   不会有哪一句读到一半被掐断。
 * - 建连接在一条专用线程上串行做，阻塞多久都不碍合成线程的事。
 * - 所有等待都有上限。合成线程是框架唯一的一条，卡住就是全系统失声。
 */
class EngineSwitch<L : EngineSwitch.Link>(
    private val connector: Connector<L>,
    private val selfPackage: String,
    private val timing: Timing = Timing(),
    /** 每一步决定都念一句给它。core 不碰 Android 的日志，由外面接到落盘日志上。 */
    private val log: (String) -> Unit = {},
) {

    /** 到某个引擎的一条连接。 */
    interface Link {
        val pkg: String

        /** 此刻能不能用。下游进程被杀、框架又没能重连上时会变成 false。 */
        val healthy: Boolean

        /** 断开。不会在持锁时调用，但可能在合成线程上调，所以不能慢。 */
        fun close()
    }

    sealed class Outcome<out L> {
        class Opened<out L>(val link: L) : Outcome<L>()

        /** 此刻根本绑不上：开机未解锁时非 directBootAware 的引擎、被停用、被卸载。查一次很便宜，可以勤着查。 */
        object Unavailable : Outcome<Nothing>()

        /** 绑得上却没连成：超时、初始化报错、被框架偷换成别的引擎。每试一次都有代价，要退避。 */
        object Failed : Outcome<Nothing>()

        /** 连到一半，用户改了主意。 */
        object Abandoned : Outcome<Nothing>()
    }

    interface Connector<L : Link> {
        /** 连一个引擎。**阻塞**，自己负责超时；[stillWanted] 变成 false 时要尽快放弃。 */
        fun open(pkg: String, stillWanted: () -> Boolean): Outcome<L>

        /** 目标连不上时拿来顶替的引擎，按优先级排好。 */
        fun standIns(): List<String>

        /**
         * 连好之后、换上之前预热：让它真正合成一遍，把模型和线程都拉起来。**阻塞**，自己负责超时。
         * 返回 false 表示这个引擎连合成一句都做不到，按连接失败处理。只在没有句子等它时被调用。
         */
        fun warmUp(link: L): Boolean = true

        /**
         * 新连接好了。[awaited] 为 true 表示有一句正等着它读。
         */
        fun onReady(link: L, awaited: Boolean) {}
    }

    class Timing(
        /** 刚选的引擎还没连好、手里有旧引擎能顶着时，这一句最多等多久。 */
        val switchWaitMs: Long = 3_000,
        /** 目标此刻绑不上时，多久回头查一次。只是问一下包管理器，很便宜。 */
        val pollMs: Long = 2_000,
        /** 连续绑不上超过这么久就放慢到 [backoffMaxMs]：多半是被卸载了，而不是开机还没解锁。 */
        val pollWindowMs: Long = 10 * 60_000L,
        /** 连接失败后第一次重试的间隔，之后每次翻倍。 */
        val backoffMs: Long = 2_000,
        val backoffMaxMs: Long = 30_000,
    )

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tts-proxy-connect").apply { isDaemon = true }
    }

    // ---- 以下状态都由 lock 保护；带 @Volatile 的另外允许不加锁地读 ----

    /** 用户要的引擎。 */
    @Volatile private var wanted: String? = null

    /** 合成线程正在用的连接。 */
    @Volatile private var active: L? = null

    /** 已经连好、还没换上去的连接：目标引擎，或者目标连不上时的顶替。 */
    private var ready: L? = null

    private var closed = false

    /** 当前目标这一轮连续失败的次数。换目标、或者连上了就清零。 */
    private var failures = 0
    private var heavyFailures = 0
    private var unavailableSince = 0L
    private var nextAttemptAt = 0L

    /** 目标连不上之后，顶替候选也一个都没连上。这时合成线程再等也等不来什么。 */
    private var standInExhausted = false

    /** 正在 [acquire] 里等着的句子数。 */
    private var waiters = 0
    private var kickQueued = false
    private var retry: ScheduledFuture<*>? = null

    /** 正在用的连接。停止朗读、读诊断信息用。 */
    val current: L? get() = active

    /** 此刻出声的不是用户选的那个。 */
    val onStandIn: Boolean
        get() {
            val link = active ?: return false
            val target = wanted ?: return false
            return link.pkg != target
        }

    /** 用户选了 [pkg]：立刻在后台开始连，不等下一句。 */
    fun select(pkg: String) {
        if (pkg == selfPackage) return
        lock.withLock {
            if (closed) return
            retarget(pkg)
            kick()
        }
    }

    /**
     * 合成线程调用：拿一条此刻能出声的连接，最多等 [timeoutMs]。
     *
     * 返回的可能是用户选的引擎，也可能是顶替的（见 [onStandIn]）；一个都没有时返回 null。
     */
    fun acquire(target: String, timeoutMs: Long, cancelled: () -> Boolean = { false }): L? {
        if (target == selfPackage) return null
        val retired = ArrayList<L>(1)
        try {
            return lock.withLock { acquireLocked(target, timeoutMs, cancelled, retired) }
        } finally {
            for (link in retired) closeQuietly(link)
        }
    }

    /** 让正在 [acquire] 里等的那一句醒过来看一眼 cancelled。打断朗读时调，不阻塞。 */
    fun wake() {
        // 持锁的地方都很短、也不调外部代码，这里等锁不会卡住调用方
        lock.withLock { changed.signalAll() }
    }

    /**
     * 合成线程发现 [link] 实际上已经坏了：提交直接被拒（下游被更新、被强停之后绑定作废，
     * 框架不会自己重连），或者下游卡死不出声。
     *
     * 只看 [Link.healthy] 是不够的——它只在连接建立时判一次，运行中坏掉没人会把它改回 false，
     * 那样 [acquire] 会一直把这条死连接交出去，从此每一句都没声音。
     * 这里把它摘下来关掉，按常规流程重连或找顶替。
     */
    fun reportBroken(link: L) {
        val drop = lock.withLock {
            if (closed) return
            when {
                active === link -> active = null
                ready === link -> ready = null
                else -> return
            }
            changed.signalAll()
            kick()
            link
        }
        log("丢弃失效连接 " + link.pkg)
        closeQuietly(drop)
    }

    private fun acquireLocked(target: String, timeoutMs: Long, cancelled: () -> Boolean, retired: MutableList<L>): L? {
        if (closed) return null
        retarget(target)
        val start = System.nanoTime()
        var kicked = false
        waiters++
        try {
            while (!closed) {
                val act = active
                val rdy = ready
                if (act != null && act.pkg == target && act.healthy) return act
                if (rdy != null && rdy.pkg == target && rdy.healthy) return promote(rdy, retired)

                if (!kicked) {
                    kick()
                    kicked = true
                }
                // 目标还没好。手里能出声的：正在用的那个，或者已经连好的顶替
                val stopGap = act?.takeIf { it.healthy } ?: rdy?.takeIf { it.healthy }
                val waited = elapsedMs(start)
                if (stopGap != null &&
                    (failures > 0 || waited >= timing.switchWaitMs || waited >= timeoutMs)
                ) {
                    return if (stopGap === rdy) promote(stopGap, retired) else stopGap
                }
                // 目标连不上、顶替也全都连不上：别让这一句干等到超时，后面还排着别的句子
                if (stopGap == null && failures > 0 && standInExhausted) return null
                if (waited >= timeoutMs) return null
                // 这一句已经被打断了，别让它继续占着合成线程，后面的句子在排队
                if (cancelled()) return null
                val limit = if (stopGap != null) minOf(timing.switchWaitMs, timeoutMs) else timeoutMs
                try {
                    changed.await(limit - waited, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    // 别吞掉中断标记；手里有能出声的就先用它读这一句
                    Thread.currentThread().interrupt()
                    return stopGap?.let { if (it === rdy) promote(it, retired) else it }
                }
            }
            return null
        } finally {
            waiters--
        }
    }

    /** 换上 [link]。只在合成线程上、两句之间发生。 */
    private fun promote(link: L, retired: MutableList<L>): L {
        val old = active
        active = link
        if (ready === link) ready = null
        if (old != null && old !== link) retired.add(old)
        changed.signalAll()
        log("换上 " + link.pkg + (old?.let { "，退下 " + it.pkg } ?: "") + (if (link.pkg != wanted) "（顶替）" else ""))
        return link
    }

    private fun retarget(pkg: String) {
        if (wanted == pkg) return
        log("目标改为 " + pkg + (wanted?.let { "（原 " + it + "）" } ?: ""))
        wanted = pkg
        failures = 0
        heavyFailures = 0
        unavailableSince = 0L
        nextAttemptAt = 0L
        standInExhausted = false
        retry?.cancel(false)
        retry = null
        changed.signalAll()
    }

    /** 让连接线程看一眼现状该做什么。重复调用只会排一次。 */
    private fun kick() {
        if (closed || kickQueued) return
        kickQueued = true
        try {
            worker.execute {
                lock.withLock { kickQueued = false }
                runSafely { reconcile() }
            }
        } catch (e: RejectedExecutionException) {
            kickQueued = false
        }
    }

    private fun scheduleRetry(delayMs: Long) {
        retry?.cancel(false)
        retry = try {
            worker.schedule({ runSafely { reconcile() } }, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            null
        }
    }

    // ---------------- 以下只在连接线程上跑 ----------------

    private sealed class Step {
        object Idle : Step()
        object StandIn : Step()
        class Connect(val pkg: String) : Step()
    }

    private fun reconcile() {
        while (true) {
            val step = lock.withLock { nextStep() }
            when (step) {
                Step.Idle -> return
                Step.StandIn -> {
                    bringUpStandIn()
                    return
                }
                is Step.Connect -> {
                    val pkg = step.pkg
                    log("开始连目标 " + pkg)
                    val outcome = openQuietly(pkg) { isStillWanted(pkg) }
                    log("连目标 " + pkg + " 结果：" + outcome.javaClass.simpleName)
                    when (outcome) {
                        is Outcome.Opened -> {
                            if (warmIfIdle(outcome.link)) {
                                publish(outcome.link, asTarget = true)
                                return
                            }
                            // 连上了却连一句都合成不出来：和「起不来」一个待遇，退避重试，期间旧引擎照读
                            closeQuietly(outcome.link)
                            fail(pkg, cheap = false)
                        }
                        // 用户中途改了主意：回头按新的目标再来一遍。
                        // 目标其实没变的话按失败算，免得在这里空转
                        Outcome.Abandoned -> if (isStillWanted(pkg)) fail(pkg, cheap = false)
                        Outcome.Unavailable -> fail(pkg, cheap = true)
                        Outcome.Failed -> fail(pkg, cheap = false)
                    }
                }
            }
        }
    }

    private fun nextStep(): Step {
        if (closed) return Step.Idle
        val target = wanted ?: return Step.Idle
        if (isUsable(active, target) || isUsable(ready, target)) return Step.Idle
        if (nowMs() >= nextAttemptAt) return Step.Connect(target)
        // 目标在退避期里。手里什么都没有的话，先找个能出声的顶上
        return if (needsStandIn()) Step.StandIn else Step.Idle
    }

    private fun isUsable(link: L?, target: String): Boolean =
        link != null && link.pkg == target && link.healthy

    private fun needsStandIn(): Boolean =
        !closed && active?.healthy != true && ready?.healthy != true

    private fun isStillWanted(pkg: String): Boolean = lock.withLock { !closed && wanted == pkg }

    private fun fail(pkg: String, cheap: Boolean) {
        lock.withLock {
            if (closed || wanted != pkg) return
            failures++
            val now = nowMs()
            val delay = if (cheap) {
                if (unavailableSince == 0L) unavailableSince = now
                if (now - unavailableSince < timing.pollWindowMs) timing.pollMs else timing.backoffMaxMs
            } else {
                heavyFailures++
                minOf(timing.backoffMs shl minOf(heavyFailures - 1, 16), timing.backoffMaxMs)
            }
            nextAttemptAt = now + delay
            log("连 " + pkg + " 失败（" + (if (cheap) "绑不上" else "起不来") + "，第 " + failures + " 次），" + delay + "ms 后再试")
            scheduleRetry(delay)
            // 正等着这个引擎的那一句不必再等：手里有能出声的就先用
            changed.signalAll()
        }
    }

    /**
     * 目标连不上、手里又什么都没有：挑一个此刻能用的引擎顶上。
     *
     * 一个候选都连不上就算了，**不去让框架自己挑**：框架的回退链（默认引擎、排名最高的引擎）
     * 用的是同一套解析规则，别的引擎此刻都绑不上时，它唯一能绑上的就是我们自己——
     * 那是自己等自己的死锁，不是出声。
     */
    private fun bringUpStandIn() {
        val target = lock.withLock { if (needsStandIn()) wanted else null } ?: return
        val stillNeeded = { lock.withLock { wanted == target && needsStandIn() } }
        val candidates = try {
            connector.standIns()
        } catch (t: Throwable) {
            emptyList()
        }
        log("目标 " + target + " 连不上，找顶替，候选：" + candidates.joinToString(" "))
        for (pkg in candidates) {
            if (pkg == selfPackage || pkg == target) continue
            if (!stillNeeded()) return
            val outcome = openQuietly(pkg, stillNeeded)
            log("连顶替 " + pkg + " 结果：" + outcome.javaClass.simpleName)
            if (outcome !is Outcome.Opened) continue
            if (!warmIfIdle(outcome.link)) {
                closeQuietly(outcome.link)
                continue
            }
            if (publish(outcome.link, asTarget = false)) return
        }
        lock.withLock {
            if (wanted == target && needsStandIn()) {
                log("顶替候选全部连不上")
                standInExhausted = true
                changed.signalAll()
            }
        }
    }

    /**
     * 连好了、换上之前，没人等的话先预热。
     *
     * 真机上撞到过：开机后顶替引擎读得好好的，目标引擎一连上就换，可它是冷的——
     * 第一句提交之后两秒多才开始合成，再三秒多没出一个字，用户以为坏了划走了。
     * 预热在连接线程上做，期间旧引擎照读；预热不过就当连接失败，按退避再来。
     * 有句子正等着它时不预热：那是用户刚在设置里选了它，等的就是它来读这一句。
     */
    private fun warmIfIdle(link: L): Boolean {
        val waiting = lock.withLock { closed || waiters > 0 }
        if (waiting) return true
        log("预热 " + link.pkg)
        val ok = try {
            connector.warmUp(link)
        } catch (t: Throwable) {
            false
        }
        log("预热 " + link.pkg + (if (ok) " 完成" else " 失败"))
        return ok
    }

    /** 把连好的连接放进 [ready]，等合成线程下一句换上。不再需要了就直接关掉。 */
    private fun publish(link: L, asTarget: Boolean): Boolean {
        val discard: L?
        var awaited = false
        lock.withLock {
            val keep = !closed && if (asTarget) wanted == link.pkg else needsStandIn()
            if (!keep) {
                discard = link
            } else {
                discard = ready
                ready = link
                standInExhausted = false
                if (asTarget) {
                    failures = 0
                    heavyFailures = 0
                    unavailableSince = 0L
                    nextAttemptAt = 0L
                    retry?.cancel(false)
                    retry = null
                }
                awaited = waiters > 0
                changed.signalAll()
            }
        }
        discard?.let { closeQuietly(it) }
        if (discard === link) {
            log("连好的 " + link.pkg + " 已经不需要了，关掉")
            return false
        }
        log("连好 " + link.pkg + (if (asTarget) "（目标）" else "（顶替）") + "，等下一句换上" + (if (awaited) "，有句子在等" else ""))
        runSafely { connector.onReady(link, awaited) }
        return true
    }

    private fun openQuietly(pkg: String, stillWanted: () -> Boolean): Outcome<L> = try {
        connector.open(pkg, stillWanted)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        Outcome.Abandoned
    } catch (t: Throwable) {
        Outcome.Failed
    }

    /** 停掉一切。之后 [acquire] 一律返回 null。 */
    fun close() {
        val links = lock.withLock {
            if (closed) return
            closed = true
            retry?.cancel(false)
            retry = null
            changed.signalAll()
            val all = listOfNotNull(active, ready)
            active = null
            ready = null
            all
        }
        // 正在连的那一个会被打断，连好了也会在 publish 里被关掉
        worker.shutdownNow()
        for (link in links) closeQuietly(link)
    }

    private fun closeQuietly(link: L) {
        runSafely { link.close() }
    }

    /**
     * 这里的代码跑在我们自己开的线程上。Android 上任何线程漏出一个异常都会杀掉整个进程——
     * 连带 TalkBack 正在用的这个引擎一起，所以一律兜住。
     */
    private inline fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            // 吞掉：出错的最多是这一次连接，下一次 kick 或重试会重新评估
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
}
