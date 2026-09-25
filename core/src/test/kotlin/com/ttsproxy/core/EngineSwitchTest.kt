package com.ttsproxy.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SELF = "com.ttsproxy"

/**
 * 发声引擎怎么切换。每一条要么对应一次真实故障，要么是「绝不能让手机变哑巴」的底线。
 *
 * 连接是假的：每个引擎连多久、连不连得上都由测试说了算，所以能精确复现
 * 「换引擎要等好一会」「开机锁屏时目标起不来」「连到一半又换了」这些时序。
 */
class EngineSwitchTest {

    private class FakeLink(override val pkg: String) : EngineSwitch.Link {
        @Volatile override var healthy = true
        @Volatile var closed = false

        override fun close() {
            closed = true
            healthy = false
        }

        override fun toString() = pkg
    }

    private class FakeConnector : EngineSwitch.Connector<FakeLink> {
        enum class Result { OK, UNAVAILABLE, FAILED }

        class Behavior(val delayMs: Long, val result: Result)

        private val behaviors = ConcurrentHashMap<String, Behavior>()
        private val opens = ConcurrentHashMap<String, AtomicInteger>()
        val opened = CopyOnWriteArrayList<FakeLink>()
        val readies = CopyOnWriteArrayList<Pair<String, Boolean>>()

        @Volatile var standInList: List<String> = emptyList()

        fun set(pkg: String, result: Result = Result.OK, delayMs: Long = 20) {
            behaviors[pkg] = Behavior(delayMs, result)
        }

        fun opens(pkg: String): Int = opens[pkg]?.get() ?: 0

        fun readied(pkg: String): Boolean = readies.any { it.first == pkg }

        override fun open(pkg: String, stillWanted: () -> Boolean): EngineSwitch.Outcome<FakeLink> {
            opens.getOrPut(pkg) { AtomicInteger() }.incrementAndGet()
            val behavior = behaviors[pkg] ?: Behavior(20, Result.OK)
            if (behavior.result == Result.UNAVAILABLE) return EngineSwitch.Outcome.Unavailable
            val deadline = System.nanoTime() + behavior.delayMs * 1_000_000
            while (System.nanoTime() < deadline) {
                if (!stillWanted()) return EngineSwitch.Outcome.Abandoned
                Thread.sleep(2)
            }
            if (behavior.result == Result.FAILED) return EngineSwitch.Outcome.Failed
            return EngineSwitch.Outcome.Opened(FakeLink(pkg).also { opened.add(it) })
        }

        override fun standIns(): List<String> = standInList

        override fun onReady(link: FakeLink, awaited: Boolean) {
            readies.add(link.pkg to awaited)
        }
    }

    private val timing = EngineSwitch.Timing(
        switchWaitMs = 400,
        pollMs = 40,
        pollWindowMs = 60_000,
        backoffMs = 40,
        backoffMaxMs = 160,
    )

    private fun <T> withSwitch(connector: FakeConnector, body: (EngineSwitch<FakeLink>) -> T): T {
        val switch = EngineSwitch(connector, SELF, timing)
        try {
            return body(switch)
        } finally {
            switch.close()
        }
    }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val value = block()
        return value to (System.nanoTime() - start) / 1_000_000
    }

    private fun eventually(timeoutMs: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test
    fun `第一次就连用户选的引擎`() {
        val c = FakeConnector()
        withSwitch(c) { s ->
            assertEquals("A", s.acquire("A", 2_000)?.pkg)
            assertFalse(s.onStandIn)
            assertEquals(1, c.opens("A"))
        }
    }

    @Test
    fun `已经连上就不重复连`() {
        val c = FakeConnector()
        withSwitch(c) { s ->
            repeat(5) { assertEquals("A", s.acquire("A", 2_000)?.pkg) }
            assertEquals(1, c.opens("A"))
        }
    }

    @Test
    fun `从 A 换到 B，这一句就由 B 读`() {
        // 用户报的正是这个：换完要等好一会才生效，这期间一直是 A 在读，连确认语都是 A 念的
        val c = FakeConnector()
        c.set("B", delayMs = 100)
        withSwitch(c) { s ->
            val a = s.acquire("A", 2_000)!!
            val (link, ms) = timed { s.acquire("B", 8_000) }
            assertEquals("B", link?.pkg, "换过去之后的第一句就该由 B 读")
            assertTrue(ms < timing.switchWaitMs, "只该等 B 连好的那一下，实际等了 $ms 毫秒")
            assertFalse(s.onStandIn)
            assertTrue(a.closed, "换过去之后旧引擎要断开")
            assertTrue(c.readies.contains("B" to true), "有句子在等 B 时不该再预热，免得排在这一句前面")
        }
    }

    @Test
    fun `在设置里一选就开始连，不等下一句`() {
        val c = FakeConnector()
        c.set("B", delayMs = 50)
        withSwitch(c) { s ->
            s.acquire("A", 2_000)
            s.select("B")
            assertTrue(eventually { c.readied("B") }, "选完之后应当马上在后台连 B")
            val (link, ms) = timed { s.acquire("B", 8_000) }
            assertEquals("B", link?.pkg)
            assertTrue(ms < 50, "B 已经连好了，下一句不该再等，实际等了 $ms 毫秒")
            assertTrue(c.readies.contains("B" to false), "没有句子在等时可以预热")
        }
    }

    @Test
    fun `新引擎连得慢，这一句先让旧引擎读，连好之后下一句换过去`() {
        val c = FakeConnector()
        c.set("B", delayMs = 800)
        withSwitch(c) { s ->
            s.acquire("A", 2_000)
            val (first, ms) = timed { s.acquire("B", 8_000) }
            assertEquals("A", first?.pkg, "B 还没好时不能没声音，先让 A 顶着")
            assertTrue(ms >= timing.switchWaitMs && ms < timing.switchWaitMs + 300, "最多等 ${timing.switchWaitMs} 毫秒，实际 $ms")
            assertTrue(s.onStandIn)
            assertTrue(eventually { c.readied("B") })
            assertEquals("B", s.acquire("B", 8_000)?.pkg)
            assertFalse(s.onStandIn)
        }
    }

    @Test
    fun `新引擎坏了，马上退回旧引擎，之后每句都不再等它`() {
        val c = FakeConnector()
        c.set("B", FakeConnector.Result.FAILED, delayMs = 30)
        c.standInList = listOf("S")
        withSwitch(c) { s ->
            s.acquire("A", 2_000)
            val (first, ms) = timed { s.acquire("B", 8_000) }
            assertEquals("A", first?.pkg)
            assertTrue(ms < timing.switchWaitMs, "B 一失败就该改用 A，不必等满，实际 $ms 毫秒")
            val (second, ms2) = timed { s.acquire("B", 8_000) }
            assertEquals("A", second?.pkg)
            assertTrue(ms2 < 50, "已经知道 B 连不上，后面的句子一刻都不该等，实际 $ms2 毫秒")
            assertTrue(s.onStandIn)
            assertEquals(0, c.opens("S"), "手里有 A 能出声，不必再去拉别的顶替")
            // 后台一直按退避重试，B 好了就换过去
            c.set("B")
            assertTrue(eventually(3_000) { c.readied("B") }, "B 恢复之后应当自动连上")
            assertEquals("B", s.acquire("B", 8_000)?.pkg)
        }
    }

    @Test
    fun `选了此刻绑不上的引擎，继续用原来的`() {
        val c = FakeConnector()
        c.set("B", FakeConnector.Result.UNAVAILABLE)
        withSwitch(c) { s ->
            s.acquire("A", 2_000)
            val (link, ms) = timed { s.acquire("B", 8_000) }
            assertEquals("A", link?.pkg)
            assertTrue(ms < 100, "绑不上是立刻就知道的事，不该等，实际 $ms 毫秒")
            assertTrue(s.onStandIn)
        }
    }

    @Test
    fun `坏掉的引擎按退避重试，不会被一直猛连`() {
        val c = FakeConnector()
        c.set("B", FakeConnector.Result.FAILED, delayMs = 0)
        withSwitch(c) { s ->
            s.acquire("A", 2_000)
            // 模拟 TalkBack 连着读很多句：每一句都会让连接线程看一眼现状
            val end = System.nanoTime() + 600 * 1_000_000L
            while (System.nanoTime() < end) {
                s.acquire("B", 8_000)
                Thread.sleep(3)
            }
            // 退避是 40、80、160、160……，600 毫秒里七次上下；不退避的话每一句都会重连，上百次
            val tries = c.opens("B")
            assertTrue(tries in 2..10, "600 毫秒里连了 $tries 次")
        }
    }

    @Test
    fun `开机锁屏：目标引擎绑不上，先用顶替引擎出声`() {
        val c = FakeConnector()
        c.set("A", FakeConnector.Result.UNAVAILABLE)
        c.standInList = listOf("S")
        withSwitch(c) { s ->
            s.select("A")   // 服务一起来就预连
            assertEquals("S", s.acquire("A", 2_000)?.pkg, "锁屏输密码时不能没有语音")
            assertTrue(s.onStandIn)
        }
    }

    @Test
    fun `解锁之后自动换回用户选的引擎，顶替的断开`() {
        val c = FakeConnector()
        c.set("A", FakeConnector.Result.UNAVAILABLE)
        c.standInList = listOf("S")
        withSwitch(c) { s ->
            val stopGap = s.acquire("A", 2_000)!!
            assertEquals("S", stopGap.pkg)
            c.set("A")   // 用户解锁了
            assertTrue(eventually { c.readied("A") }, "解锁之后应当在一个轮询周期内连上 A")
            assertEquals("A", s.acquire("A", 2_000)?.pkg)
            assertFalse(s.onStandIn)
            assertTrue(stopGap.closed)
        }
    }

    @Test
    fun `目标只是慢一点，不去拉顶替`() {
        val c = FakeConnector()
        c.set("A", delayMs = 150)
        c.standInList = listOf("S")
        withSwitch(c) { s ->
            assertEquals("A", s.acquire("A", 2_000)?.pkg)
            assertEquals(0, c.opens("S"))
        }
    }

    @Test
    fun `一个能出声的都没有：马上返回，不让后面排着的句子干等`() {
        val c = FakeConnector()
        c.set("A", FakeConnector.Result.UNAVAILABLE)
        withSwitch(c) { s ->
            s.select("A")
            val (link, ms) = timed { s.acquire("A", 5_000) }
            assertNull(link)
            assertTrue(ms < 1_000, "目标和顶替都连不上时再等也没用，实际等了 $ms 毫秒")
        }
    }

    @Test
    fun `等待总有上限`() {
        val c = FakeConnector()
        c.set("A", delayMs = 5_000)
        withSwitch(c) { s ->
            val (link, ms) = timed { s.acquire("A", 300) }
            assertNull(link)
            assertTrue(ms in 300..800, "应当按时放弃，实际等了 $ms 毫秒")
        }
    }

    @Test
    fun `绝不把自己当成下游`() {
        val c = FakeConnector()
        c.set("A", FakeConnector.Result.UNAVAILABLE)
        c.standInList = listOf(SELF, "S")
        withSwitch(c) { s ->
            assertNull(s.acquire(SELF, 500), "自己当下游是死锁")
            s.select(SELF)
            assertEquals("S", s.acquire("A", 2_000)?.pkg, "顶替候选里就算混进了自己也要跳过")
            assertEquals(0, c.opens(SELF))
        }
    }

    @Test
    fun `连到一半又换了，以最后一次为准`() {
        val c = FakeConnector()
        c.set("B", delayMs = 2_000)
        c.set("C", delayMs = 30)
        withSwitch(c) { s ->
            s.acquire("A", 2_000)
            s.select("B")
            assertTrue(eventually { c.opens("B") == 1 })
            s.select("C")
            val (link, ms) = timed { s.acquire("C", 8_000) }
            assertEquals("C", link?.pkg)
            assertTrue(ms < timing.switchWaitMs, "不该等 B 连完再连 C，实际等了 $ms 毫秒")
            assertTrue(c.opened.none { it.pkg == "B" }, "B 已经没人要了，应当半路放弃")
        }
    }

    @Test
    fun `正在用的引擎坏了会自己重连`() {
        val c = FakeConnector()
        withSwitch(c) { s ->
            val first = s.acquire("A", 2_000)!!
            first.healthy = false
            val second = s.acquire("A", 2_000)
            assertEquals("A", second?.pkg)
            assertTrue(second !== first, "应当换成一条新连接")
            assertTrue(first.closed, "坏掉的旧连接要断开")
            assertEquals(2, c.opens("A"))
        }
    }

    @Test
    fun `关掉之后连接全部断开，也不再返回任何引擎`() {
        val c = FakeConnector()
        val s = EngineSwitch(c, SELF, timing)
        val link = s.acquire("A", 2_000)!!
        s.close()
        assertTrue(link.closed)
        val (after, ms) = timed { s.acquire("A", 2_000) }
        assertNull(after)
        assertTrue(ms < 100, "关掉之后不该再等，实际等了 $ms 毫秒")
    }

    @Test
    fun `连接运行中坏掉，报告之后会重连而不是一直交出死连接`() {
        // 下游被应用商店更新之后绑定作废，框架不会自己重连，healthy 却还是 true。
        // 不摘掉它，从那一刻起每一句都交给这条死连接，直到进程重启
        val c = FakeConnector()
        withSwitch(c) { s ->
            val dead = s.acquire("A", 2_000)!!
            s.reportBroken(dead)
            assertTrue(dead.closed, "坏掉的连接要关掉")
            val fresh = s.acquire("A", 2_000)
            assertEquals("A", fresh?.pkg)
            assertTrue(fresh !== dead, "必须是新建的连接")
            assertEquals(2, c.opens("A"))
        }
    }

    @Test
    fun `坏掉之后目标连不上，照样有顶替引擎出声`() {
        val c = FakeConnector()
        c.standInList = listOf("B")
        withSwitch(c) { s ->
            val dead = s.acquire("A", 2_000)!!
            c.set("A", FakeConnector.Result.UNAVAILABLE)
            s.reportBroken(dead)
            assertEquals("B", s.acquire("A", 2_000)?.pkg, "出声比出对的声重要")
            assertTrue(s.onStandIn)
        }
    }

    @Test
    fun `报告一条已经换下去的连接不影响正在用的`() {
        val c = FakeConnector()
        withSwitch(c) { s ->
            val a = s.acquire("A", 2_000)!!
            val b = s.acquire("B", 2_000)!!
            s.reportBroken(a)
            assertEquals(b, s.current)
            assertFalse(b.closed)
        }
    }

    @Test
    fun `等下游连上的那一句被打断，马上放手`() {
        val c = FakeConnector()
        c.set("A", delayMs = 5_000)
        withSwitch(c) { s ->
            val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
            Thread {
                Thread.sleep(100)
                stopped.set(true)
                s.wake()
            }.start()
            val (link, ms) = timed { s.acquire("A", 8_000) { stopped.get() } }
            assertNull(link)
            assertTrue(ms < 1_000, "被打断之后还等了 $ms 毫秒，后面的句子都跟着排队")
        }
    }
}
