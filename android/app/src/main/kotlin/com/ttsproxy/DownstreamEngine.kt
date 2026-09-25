package com.ttsproxy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.ttsproxy.core.EngineSwitch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 下游 TTS 引擎的封装：真正发声的那一个。
 *
 * 音频用「流式截获」的方式拿回来：调 [TextToSpeech.synthesizeToFile]，然后靠
 * onBeginSynthesis / onAudioAvailable 在合成过程中实时收 PCM。写 WAV 文件的是
 * **框架自己的** FileSynthesisCallback 而不是引擎代码，所以任何继承 TextToSpeechService
 * 的引擎都自动支持这条路，不需要引擎作者配合。
 *
 * 用哪个引擎、什么时候换、连不上时谁来顶，由 [EngineSwitch] 决定（纯逻辑，本机有测试）；
 * 这里只管怎么真正连上一个引擎。
 */
class DownstreamEngine(base: Context) {

    // directBootAware：锁屏状态下也要能读配置、写临时文件
    private val context: Context = Prefs.storageContext(base.applicationContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tts-proxy-downstream").apply { isDaemon = true }
    }

    private val pipes = ConcurrentHashMap<String, PcmPipe>()
    private val idGen = AtomicLong()

    /**
     * 按 utteranceId 把回调路由到对应的管道。
     *
     * 每条连接只挂**这一个**监听器：setOnUtteranceProgressListener 是每个 TextToSpeech 实例
     * 一个，如果每次提交都换一个监听器，两个分块同时在途时后者会把前者顶掉。
     * 换引擎时新旧两条连接会短暂并存，id 全局唯一，所以共用一个也不会串。
     */
    private val router = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onBeginSynthesis(
            utteranceId: String?,
            sampleRateInHz: Int,
            audioFormat: Int,
            channelCount: Int,
        ) {
            pipes[utteranceId]?.offerFormat(sampleRateInHz, audioFormat, channelCount)
        }

        override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
            if (audio == null || audio.isEmpty()) return
            pipes[utteranceId]?.offerChunk(audio)
        }

        override fun onDone(utteranceId: String?) {
            pipes[utteranceId]?.offerDone()
        }

        // 框架要求实现这个已废弃的重载；实际走的是下面带错误码的那个
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String?) {
            pipes[utteranceId]?.offerFailed(-1)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            pipes[utteranceId]?.offerFailed(errorCode)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            pipes[utteranceId]?.interrupt()
        }
    }

    private val switch = EngineSwitch(Opener(), context.packageName)

    init {
        // 上次进程被杀时没来得及删的临时文件
        cleanupSinkFiles()
    }

    /** 用户选了 [pkg]：立刻在后台开始连，不等下一句。 */
    fun select(pkg: String) {
        runCatching { switch.select(pkg) }.onFailure { Log.e(TAG, "切换发声引擎失败", it) }
    }

    /**
     * 确保此刻有引擎能出声。从合成线程调用，最多等 [timeoutMs]——
     * 无限等待会把引擎唯一的合成线程钉死，那等于让用户的手机永久失声。
     *
     * 返回 true 不代表用的就是 [pkg]：它此刻连不上时会先用别的顶着（见 [usingFallback]），
     * 等它可用了再换回去。
     */
    fun ensureReady(pkg: String, timeoutMs: Long, cancelled: () -> Boolean = { false }): Boolean = try {
        if (pkg == context.packageName) {
            // 自引用是硬死锁，不是报错那么简单
            Log.e(TAG, "拒绝把自己当作下游引擎")
            false
        } else {
            switch.acquire(pkg, timeoutMs, cancelled) != null
        }
    } catch (t: Throwable) {
        Log.e(TAG, "下游引擎准备失败", t)
        false
    }

    /**
     * 正在用的连接实际上已经坏了（提交被拒、下游卡死）。扔掉它，下一次 [ensureReady]
     * 会重连或者找顶替。只从合成线程调——只有合成线程会更换正在用的连接，
     * 所以这里摘掉的一定是刚才出问题的那一条。
     */
    fun markBroken() {
        val link = switch.current ?: return
        Log.w(TAG, "下游连接失效，重连: " + link.pkg)
        runCatching { switch.reportBroken(link) }.onFailure { Log.e(TAG, "丢弃失效连接失败", it) }
    }

    /** 此刻正在用的引擎不是用户选的那个。诊断界面要显示这个状态。 */
    val usingFallback: Boolean get() = switch.onStandIn

    /**
     * 当前实际连上的引擎包名，给诊断界面用——「你以为在用 A，其实在用谁」。
     * 读不到时返回 null，界面上显示「读不到」而不是瞎猜。
     */
    val connectedEngine: String? get() = currentEngineOf(switch.current?.tts)

    /** 镜像给上游的声音列表快照。onGetVoices() 是阻塞式 binder 调用，只能读这个快照。 */
    val voiceSnapshot: List<Voice> get() = switch.current?.voices ?: emptyList()

    val enginePackage: String? get() = switch.current?.pkg

    /** 到一个下游引擎的一条连接。 */
    private inner class Connection(override val pkg: String) : EngineSwitch.Link {

        @Volatile var tts: TextToSpeech? = null
            private set

        @Volatile override var healthy = false

        /** 这个引擎的声音列表。连上之后在后台取一次。 */
        @Volatile var voices: List<Voice> = emptyList()

        /** 上一次设给它的声音。同一个声音不必每句都设一遍。 */
        @Volatile var appliedVoice: String? = null

        private val initDone = CountDownLatch(1)
        @Volatile private var initStatus = TextToSpeech.ERROR
        private var closed = false

        val initOk: Boolean get() = initStatus == TextToSpeech.SUCCESS

        /** 在主线程上建。 */
        fun build() {
            synchronized(this) {
                if (closed) return
                tts = try {
                    TextToSpeech(context, { status -> onInit(status) }, pkg).also {
                        // 必须在任何 synthesizeToFile 之前挂好监听器，否则会丢掉前几个音频块
                        runCatching { it.setOnUtteranceProgressListener(router) }
                    }
                } catch (t: Throwable) {
                    // 跑在主线程上，抛出去就是崩溃
                    Log.e(TAG, "构造下游引擎失败: " + pkg, t)
                    initDone.countDown()
                    null
                }
            }
        }

        private fun onInit(status: Int) {
            if (initDone.count > 0) {
                initStatus = status
                initDone.countDown()
                return
            }
            // 下游进程被杀之后框架会自己重连，每重连一次这里就再被调一次
            healthy = status == TextToSpeech.SUCCESS && tts != null
            if (healthy) inBackground { refreshVoices(this) }
        }

        fun awaitInit(timeoutMs: Long, stillWanted: () -> Boolean): Wait =
            awaitLatch(initDone, timeoutMs, stillWanted)

        override fun close() {
            val engine = synchronized(this) {
                closed = true
                healthy = false
                tts.also { tts = null }
            } ?: return
            // shutdown 要走好几次 IPC。换引擎时这里在合成线程上被调，别让新引擎的第一句等它
            inBackground {
                runCatching { engine.stop() }
                runCatching { engine.shutdown() }
            }
        }
    }

    /** 真正去连一个引擎。跑在 [EngineSwitch] 的连接线程上，阻塞多久都不影响合成线程。 */
    private inner class Opener : EngineSwitch.Connector<Connection> {

        override fun open(pkg: String, stillWanted: () -> Boolean): EngineSwitch.Outcome<Connection> {
            // 开机未解锁时，非 directBootAware 的引擎在包管理器里就查不到。
            // 这一步只是一次本地查询，所以锁屏期间可以勤着查
            if (!isBindable(pkg)) return EngineSwitch.Outcome.Unavailable
            val probe = BindProbe(pkg)
            if (!probe.bind()) return EngineSwitch.Outcome.Unavailable
            var pending: Connection? = null
            try {
                when (awaitLatch(probe.connected, PROBE_TIMEOUT_MS, stillWanted)) {
                    Wait.DONE -> Unit
                    Wait.ABANDONED -> return EngineSwitch.Outcome.Abandoned
                    Wait.TIMEOUT -> {
                        Log.w(TAG, "绑定 " + pkg + " 超时")
                        return EngineSwitch.Outcome.Failed
                    }
                }
                val conn = Connection(pkg)
                pending = conn
                mainHandler.post { conn.build() }
                when (conn.awaitInit(INIT_TIMEOUT_MS, stillWanted)) {
                    Wait.DONE -> Unit
                    Wait.ABANDONED -> return EngineSwitch.Outcome.Abandoned
                    Wait.TIMEOUT -> {
                        Log.w(TAG, "下游引擎初始化超时: " + pkg)
                        return EngineSwitch.Outcome.Failed
                    }
                }
                if (!conn.initOk) {
                    Log.e(TAG, "下游引擎初始化失败: " + pkg)
                    return EngineSwitch.Outcome.Failed
                }
                // 第二道保险：框架连不上点名的引擎时会悄悄换一个再报成功，见 README。
                // 读不到就不做判断——绝不能因为「读不到」把一条本来能用的链路判死
                val actual = currentEngineOf(conn.tts)
                if (actual != null && actual != pkg) {
                    Log.e(TAG, "框架把请求的引擎 " + pkg + " 悄悄换成了 " + actual)
                    return EngineSwitch.Outcome.Failed
                }
                conn.healthy = true
                pending = null
                return EngineSwitch.Outcome.Opened(conn)
            } finally {
                pending?.close()
                // 新连接自己已经绑上了，这里放手不会让下游服务被销毁
                probe.release()
            }
        }

        override fun standIns(): List<String> = availableEngines(context).map { it.name }

        override fun onReady(link: Connection, awaited: Boolean) {
            inBackground {
                refreshVoices(link)
                if (!awaited) warmUp(link)
            }
        }
    }

    /**
     * 按 TTS_SERVICE 定向 bind 一次，确认目标引擎此刻真的绑得上。
     *
     * 构造 TextToSpeech 之前必须先确认这一点：公开构造函数是 useFallback = true，
     * 连不上点名的引擎时不报错，而是悄悄连上「默认引擎」再报成功。
     * 包管理器查得到也不保证绑得上——有的系统会拦截应用之间的关联启动。
     *
     * 这个绑定要**一直留到新连接建好**才放：先放的话，没有别的客户端时下游服务会被销毁，
     * 紧接着 TextToSpeech 再绑一次又得重新创建，重量级引擎等于冷启动两遍。
     */
    private inner class BindProbe(private val pkg: String) : ServiceConnection {
        val connected = CountDownLatch(1)
        private var bound = false

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = connected.countDown()
        override fun onServiceDisconnected(name: ComponentName?) = Unit

        // 引擎返回 null binder 也算「在」，别把它误判成不可用
        override fun onNullBinding(name: ComponentName?) = connected.countDown()

        fun bind(): Boolean {
            val ok = try {
                context.bindService(Intent(TTS_SERVICE_ACTION).setPackage(pkg), this, Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) {
                Log.w(TAG, "bind 探测抛异常: " + pkg, t)
                false
            }
            // bindService 返回 false 时也必须解绑，否则会泄漏一个绑定记录
            if (ok) bound = true else runCatching { context.unbindService(this) }
            return ok
        }

        fun release() {
            if (!bound) return
            bound = false
            runCatching { context.unbindService(this) }
        }
    }

    private enum class Wait { DONE, TIMEOUT, ABANDONED }

    /** 分片等，用户改了主意时能尽快放手，而不是把这次连接等完。 */
    private fun awaitLatch(latch: CountDownLatch, timeoutMs: Long, stillWanted: () -> Boolean): Wait {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            while (true) {
                if (!stillWanted()) return Wait.ABANDONED
                val left = deadline - System.nanoTime()
                if (left <= 0) return Wait.TIMEOUT
                if (latch.await(minOf(left, WAIT_SLICE_NANOS), TimeUnit.NANOSECONDS)) return Wait.DONE
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Wait.ABANDONED
        }
    }

    /**
     * 目标引擎此刻在包管理器里查不查得到。开机未解锁时，非 directBootAware 的引擎就查不到，
     * bindService 必然失败。
     */
    private fun isBindable(pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager
            .queryIntentServices(Intent(TTS_SERVICE_ACTION).setPackage(pkg), 0)
            .isNotEmpty()
    } catch (t: Throwable) {
        // 查询本身出错时别拦着，交给后面真正的 bind 去判断
        true
    }

    private fun inBackground(task: () -> Unit) {
        try {
            background.execute { task() }
        } catch (e: RejectedExecutionException) {
            // 服务正在销毁
            runCatching(task)
        }
    }

    private fun refreshVoices(link: Connection) {
        val engine = link.tts ?: return
        link.voices = runCatching { engine.voices?.toList().orEmpty() }
            .onFailure { Log.w(TAG, "读取下游声音列表失败", it) }
            .getOrDefault(emptyList())
    }

    /** 抄 TalkBack 的做法：连上之后先空跑一次，把下游的模型和线程预热掉。 */
    private fun warmUp(link: Connection) {
        val engine = link.tts ?: return
        runCatching {
            val sink = File(context.cacheDir, SINK_PREFIX + "-prime.wav")
            engine.synthesizeToFile("1 2 3", Bundle(), sink, SINK_PREFIX + "-prime")
        }.onFailure { Log.w(TAG, "预热失败（不影响使用）", it) }
    }

    /** 语速/音调透传。不设的话，用户在 TalkBack 里调的语速会完全失效。 */
    fun applyProsody(rate: Float, pitch: Float, voiceName: String?) {
        val link = switch.current ?: return
        val engine = link.tts ?: return
        runCatching {
            engine.setSpeechRate(rate)
            engine.setPitch(pitch)
            // 声音只在变了的时候才设。查声音列表是一次 IPC，谷歌这类引擎要带回几百个声音，
            // 每句都查一遍，每句就都多一截延迟
            if (voiceName != null && voiceName != link.appliedVoice) {
                link.appliedVoice = voiceName
                val voice = link.voices.firstOrNull { it.name == voiceName }
                    ?: engine.voices?.firstOrNull { it.name == voiceName }
                if (voice != null) engine.voice = voice
            }
        }.onFailure { Log.w(TAG, "设置语速/音调失败", it) }
    }

    /**
     * 提交一段文本给下游合成，音频通过 [pipe] 流回来。
     * @return utteranceId；提交失败返回 null
     */
    fun submit(text: String, extras: Bundle, pipe: PcmPipe): String? {
        val engine = switch.current?.tts ?: return null
        // utteranceId 不能为 null，否则框架不会分发任何回调
        val id = SINK_PREFIX + "-" + idGen.incrementAndGet()
        pipes[id] = pipe
        return try {
            val sink = File(context.cacheDir, id + ".wav")
            // 绝不要传管道 ParcelFileDescriptor：框架写完要 seek 回文件头填 WAV 头，
            // 管道不可 seek，抛出的 IOException 那段既不报错也不回调，会直接挂死。
            val rc = engine.synthesizeToFile(text, extras, sink, id)
            if (rc != TextToSpeech.SUCCESS) {
                Log.e(TAG, "synthesizeToFile 提交失败 rc=" + rc)
                finish(id)
                null
            } else {
                id
            }
        } catch (t: Throwable) {
            Log.e(TAG, "synthesizeToFile 抛异常", t)
            finish(id)
            null
        }
    }

    /** 兼容模式：直接让下游发声，我们不接管音频。 */
    fun speakDirect(text: String, params: Bundle, utteranceId: String, pipe: PcmPipe): Boolean {
        val engine = switch.current?.tts ?: return false
        pipes[utteranceId] = pipe
        val ok = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        if (!ok) pipes.remove(utteranceId)
        return ok
    }

    fun finish(utteranceId: String) {
        pipes.remove(utteranceId)
        runCatching { File(context.cacheDir, utteranceId + ".wav").delete() }
    }

    /** 打断。必须非阻塞——它从 binder 线程被调用。 */
    fun stopNow() {
        pipes.values.forEach { runCatching { it.interrupt() } }
        pipes.clear()
        // 正在等下游连上的那一句也要叫醒，它会看到自己已被打断而放手
        runCatching { switch.wake() }
        runCatching { switch.current?.tts?.stop() }
    }

    fun shutdown() {
        stopNow()
        switch.close()
        // 不用 shutdownNow：刚排进去的断开连接还得跑完，否则会泄漏绑定
        background.shutdown()
    }

    private fun cleanupSinkFiles() {
        runCatching {
            context.cacheDir.listFiles { f -> f.name.startsWith(SINK_PREFIX) }?.forEach { it.delete() }
        }
    }

    /** 设置界面引擎列表里的一项。 */
    class EngineChoice(val name: String, val label: String)

    companion object {
        private const val TAG = "DownstreamEngine"
        private const val SINK_PREFIX = "ttsproxy-sink"

        /** 引擎注册用的 action，枚举和探测绑定都按它定向。 */
        private const val TTS_SERVICE_ACTION = "android.intent.action.TTS_SERVICE"

        /**
         * 等下游服务起来的上限。重量级引擎冷启动要一两秒；这一步在连接线程上，
         * 不占合成线程，所以可以宽裕些。
         */
        private const val PROBE_TIMEOUT_MS = 4_000L

        /** 服务起来之后，等 TextToSpeech 握手完成的上限。 */
        private const val INIT_TIMEOUT_MS = 6_000L

        private val WAIT_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(100)

        /**
         * 实际连上的引擎包名。`getCurrentEngine()` 在公开 SDK 里是隐藏的，只能反射；
         * 拿不到就返回 null。
         */
        private fun currentEngineOf(tts: TextToSpeech?): String? {
            if (tts == null) return null
            return runCatching {
                TextToSpeech::class.java.getMethod("getCurrentEngine").invoke(tts) as? String
            }.getOrNull()
        }

        /**
         * 列出可作为下游的引擎，**排除自己**，顺序和系统设置里一致：系统引擎在前，再按优先级。
         *
         * 直接问包管理器，用的是和系统枚举引擎同一个查询。原来是建一个 TextToSpeech 探针
         * 再调 getEngines()——那会顺手绑上系统默认引擎，它没在跑的话就平白被拉起来一次。
         *
         * 把自己排除掉是硬性的：自己当下游会造成硬死锁——同进程 bind 拿到的是同一个
         * Service 实例、同一个单线程 SynthHandler，转发的请求排在自己后面永远不会执行。
         *
         * 返回空列表基本只有一个原因：manifest 里漏了 <queries>。
         */
        fun availableEngines(context: Context): List<EngineChoice> {
            val pm = context.packageManager
            val found = try {
                @Suppress("DEPRECATION")
                pm.queryIntentServices(Intent(TTS_SERVICE_ACTION), PackageManager.MATCH_DEFAULT_ONLY)
            } catch (t: Throwable) {
                Log.e(TAG, "枚举引擎失败（检查 manifest 里的 queries 声明）", t)
                return emptyList()
            }

            class Found(val choice: EngineChoice, val system: Boolean, val priority: Int)

            return found.mapNotNull { info ->
                val service = info.serviceInfo ?: return@mapNotNull null
                val pkg = service.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                val label = runCatching { service.loadLabel(pm).toString() }.getOrNull()
                val system = ((service.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0
                Found(EngineChoice(pkg, if (label.isNullOrBlank()) pkg else label), system, info.priority)
            }
                .sortedWith(compareByDescending<Found> { it.system }.thenByDescending { it.priority })
                .distinctBy { it.choice.name }
                .map { it.choice }
        }
    }
}
