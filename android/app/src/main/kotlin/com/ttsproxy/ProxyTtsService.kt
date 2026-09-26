package com.ttsproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import com.ttsproxy.core.Chunker
import com.ttsproxy.core.DiacriticFold
import com.ttsproxy.core.PcmFramer
import com.ttsproxy.core.TextPipeline
import com.ttsproxy.core.Verbosity
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 我们注册进系统的 TTS 引擎。TalkBack 调它，它做完 Unicode 规范化之后转给下游引擎发声。
 *
 * ## 线程模型（弄错就会让手机变哑巴）
 * - [onSynthesizeText] 跑在框架唯一的合成线程上，**要求阻塞到合成结束**。
 *   AOSP 里**没有任何超时看门狗**：这里一旦卡住，引擎对所有客户端永久静音，
 *   用户只能闭着眼睛去系统设置换引擎。所以每一次等待都必须带超时。
 * - [onStop] 从 binder 线程调用（不是合成线程），是把我们从阻塞里唤醒的唯一入口，
 *   必须非阻塞、幂等、线程安全。
 * - [SynthesisCallback] 的所有方法**只能在合成线程调用**。下游音频从 binder 线程回来，
 *   所以中间必须隔一个队列（[PcmPipe]）。
 */
class ProxyTtsService : TextToSpeechService() {

    /**
     * 这两个必须是 lazy，不能是 lateinit。
     *
     * `TextToSpeechService.onCreate()` 的**最后一步**会调用 `onLoadLanguage(...)`，
     * 而我们的实现顺着 onIsLanguageAvailable -> supportedLocales() 会读到 downstream。
     * 那时 super.onCreate() 还没返回，赋值语句根本没执行到——lateinit 会抛
     * UninitializedPropertyAccessException。服务和界面同进程，于是整个进程被杀，
     * 用户看到的就是「点图标没反应，多点几次提示屡次停止运行」。
     *
     * lazy 在首次访问时才构造，此时 attachBaseContext 已经完成，Context 可用。
     */
    private val lazyDownstream = lazy { DownstreamEngine(this) }
    private val downstream: DownstreamEngine by lazyDownstream

    // 用户改了自定义词典要立刻生效，所以流水线不能是 by lazy 的一次性对象。
    // DictLoader 内部按用户词典版本号缓存，版本没变时这里就是一次原子读。
    @Volatile
    private var cachedPipeline: TextPipeline? = null

    @Volatile
    private var cachedDictVersion = -1

    /**
     * 缓存对应的「音标读盲文点位」开关。只按词典版本缓存的话，
     * 切换这个开关要等进程重启才生效——用户会以为开关坏了。
     */
    @Volatile
    private var cachedIpaBraille = false

    private fun pipeline(): TextPipeline {
        val wanted = UserDictStore.version.get()
        val braille = Prefs.ipaAsBraille(this)
        val hit = cachedPipeline
        if (hit != null && cachedDictVersion == wanted && cachedIpaBraille == braille) return hit
        val built = runCatching { TextPipeline(DictLoader.load(this)) }
            .getOrElse {
                Tlog.e(TAG, "词典载入失败，降级为不替换", it)
                TextPipeline.withoutDict()
            }
        cachedPipeline = built
        cachedDictVersion = wanted
        cachedIpaBraille = braille
        return built
    }

    private val activeSession = AtomicReference<Session?>(null)
    private val passthroughIds = AtomicLong()

    /**
     * 回灌看门狗。真机上抓到过：下游音频早就到齐，可上游的 audioAvailable 一卡就是五秒——
     * 框架自己的音轨活着却不吃数据，用户听到的就是「读着读着没声了」。
     * 那段时间我们卡在框架里出不来，只能由另一条线程来记下卡住的起止时刻，好和系统事件对时间。
     */
    @Volatile private var feedStartedAt = 0L
    @Volatile private var feedSeq = 0

    private fun startFeedWatchdog() {
        Thread({
            // 正在盯着的那一次调用的开始时刻，以及上一次报告的时刻
            var watching = 0L
            var lastReport = 0L
            while (true) {
                try {
                    Thread.sleep(FEED_WATCH_SLICE_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                val since = feedStartedAt
                if (since == 0L) {
                    watching = 0L
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                val stuck = now - since
                if (stuck < FEED_STALL_MS) continue
                if (since != watching) {
                    // 新的一次卡住：第一次报告
                    watching = since
                    lastReport = now
                    Diagnostics.event("第 " + feedSeq + " 句回灌卡住，上游音轨不吃数据")
                    Tlog.w(TAG, "回灌卡住 " + stuck + "ms（第 " + feedSeq + " 句），上游音轨不吃数据")
                } else if (now - lastReport >= FEED_REPORT_EVERY_MS) {
                    lastReport = now
                    Tlog.w(TAG, "回灌仍卡着 " + stuck + "ms（第 " + feedSeq + " 句）")
                }
            }
        }, "tts-proxy-feed-watch").apply { isDaemon = true }.start()
    }

    /** 上游选中的声音，由 onLoadVoice 记录，在 onSynthesizeText 里才真正生效。 */
    @Volatile private var pendingVoice: String? = null

    private var cachedVoiceSource: List<Voice> = emptyList()
    private var cachedMirroredVoices: List<Voice> = emptyList()

    /**
     * 用户没配置、上次可用的也没了时的最后兜底。在后台线程算好存这里，
     * 因为枚举引擎要建一个 TextToSpeech 探针，不能放在合成线程上做。
     */
    @Volatile private var fallbackEngine: String? = null

    private class Session(val seq: Int, val chars: Int, val caller: String) {
        @Volatile var pipe: PcmPipe? = null
        @Volatile var stopped = false

        // 以下只在合成线程上写，给诊断记录用
        val startedAt = SystemClock.elapsedRealtime()
        var chunk = 0
        var chunks = 0
        var engine: String? = null
        var standIn = false
        private var reason: Diagnostics.EndReason? = null
        private var detail: String? = null

        fun interrupt() {
            stopped = true
            pipe?.interrupt()
        }

        /** 记下这一句是怎么结束的。只记第一个原因——后面的多半是它的连锁反应。 */
        fun end(reason: Diagnostics.EndReason, detail: String? = null) {
            if (this.reason == null) {
                this.reason = reason
                this.detail = detail
            }
        }

        fun toRecord(): Diagnostics.Record = Diagnostics.Record(
            seq, chars, chunk, chunks, caller,
            reason ?: Diagnostics.EndReason.DONE, detail,
            engine, standIn,
            SystemClock.elapsedRealtime() - startedAt,
            SystemClock.elapsedRealtime() / 1000,
        )
    }

    /**
     * 用户在设置里换了发声引擎：立刻开始连新的，不等下一句。
     *
     * 设置界面和服务在同一个进程，读写的是同一个 SharedPreferences 实例，所以这里能直接收到。
     * 必须存成字段：SharedPreferences 只弱引用监听器，临时对象会被回收，回调就再也不来了。
     */
    private val engineChoiceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_DOWNSTREAM) {
            runCatching { Prefs.downstreamEngine(this)?.let { downstream.select(it) } }
                .onFailure { Tlog.e(TAG, "切换发声引擎失败", it) }
        }
        if (key == Prefs.KEY_KEEP_ALIVE) {
            if (Prefs.keepAliveWanted(this)) ensureForeground("设置里改了") else leaveForeground()
        }
    }

    /**
     * 是否已经是前台服务。
     *
     * 真机（华为）日志：开机后第 118 秒，正在读的一句读到 2.2 秒时整个进程被系统冻结，
     * 看门狗线程和合成线程在 5.3 秒后同一毫秒一起醒来，音轨报「因 underrun 被禁用，重启」。
     * 用户听到的就是「读着读着没声」。打开一次本应用系统就不再冻它，所以之前怎么都复现不稳。
     * 前台服务是所有厂商都认的「别冻我」信号；这里的通知是低优先级、无声的。
     */
    @Volatile private var foreground = false

    private fun ensureForeground(why: String) {
        if (foreground) return
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                // 最低等级：状态栏不显示图标，折叠进「静默通知」区
                NotificationChannel(KEEP_ALIVE_CHANNEL, getString(R.string.keep_alive_channel), NotificationManager.IMPORTANCE_MIN).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, SettingsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification: Notification = NotificationCompat.Builder(this, KEEP_ALIVE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(getString(R.string.keep_alive_title))
                .setContentText(getString(R.string.keep_alive_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.keep_alive_text)))
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
            ServiceCompat.startForeground(
                this, KEEP_ALIVE_NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            foreground = true
            Tlog.i(TAG, "已升为前台服务（" + why + "）")
        } catch (t: Throwable) {
            // Android 12 起后台不能随便起前台服务；被拒就等下一次机会（下一句、或用户打开设置界面）
            Tlog.w(TAG, "升为前台服务失败（" + why + "）: " + t)
        }
    }

    private fun leaveForeground() {
        if (!foreground) return
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foreground = false
        Tlog.i(TAG, "已退出前台服务")
    }

    /**
     * 设置界面在前台时会用 startForegroundService 拉一次，这里必须尽快 startForeground，
     * 否则系统会以「起了前台服务却不亮通知」为由杀掉进程。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Prefs.keepAliveWanted(this)) ensureForeground("界面拉起") else leaveForeground()
        return START_NOT_STICKY
    }

    /**
     * 「开机后十分钟内」这一档：保护期一到就退出前台服务，通知自动消失。
     * 真机上四次冻结都发生在开机后 109 到 118 秒之间，十分钟余量很足。
     */
    private fun scheduleBootWindowEnd() {
        val left = Prefs.KEEP_ALIVE_BOOT_WINDOW_MS - SystemClock.elapsedRealtime()
        if (left <= 0) return
        Thread({
            try {
                Thread.sleep(left + 1_000)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (!Prefs.keepAliveWanted(this)) {
                Tlog.i(TAG, "开机保护期结束")
                leaveForeground()
            }
        }, "tts-proxy-boot-window").apply { isDaemon = true }.start()
    }

    override fun onCreate() {
        super.onCreate()
        Tlog.i(TAG, "服务 onCreate，配置的下游=" + runCatching { Prefs.downstreamEngine(this) }.getOrNull())
        runCatching { startFeedWatchdog() }.onFailure { Tlog.w(TAG, "回灌看门狗启动失败", it) }
        if (Prefs.keepAliveWanted(this)) ensureForeground("服务启动")
        runCatching { scheduleBootWindowEnd() }.onFailure { Tlog.w(TAG, "开机保护期计时失败", it) }
        runCatching { Prefs.of(this).registerOnSharedPreferenceChangeListener(engineChoiceListener) }
            .onFailure { Tlog.e(TAG, "监听发声引擎设置失败", it) }
        Thread {
            // 后台线程里漏出去的异常会直接杀掉整个进程（连带界面一起闪退），
            // 所以整段都要兜住
            runCatching {
                // 重音字母的折叠表是懒加载的，第一次遇到 é 时才建，要几十到几百毫秒。
                // 那一下会落在合成线程上，听感就是「读到某个词突然卡一下」。
                // 在这里先热一遍，代价是后台线程上的一次性开销。
                DiacriticFold.split(0x00E9)
                // 先算好兜底引擎，免得合成线程上临时去枚举
                fallbackEngine = DownstreamEngine.availableEngines(this).firstOrNull()?.name
            }.onFailure { Tlog.e(TAG, "预热失败", it) }
        }.apply { isDaemon = true }.start()
        // 提前把下游连上，省掉第一句的冷启动。开机时我们可能在解锁之前就被 TalkBack 绑定了，
        // 而绝大多数 TTS 引擎不是 directBootAware，那时根本连不上——EngineSwitch 会先找个
        // 能出声的顶着，并在后台一直盯着，用户解锁后几秒内换回他选的引擎。
        runCatching { Prefs.downstreamEngine(this)?.let { downstream.select(it) } }
            .onFailure { Tlog.e(TAG, "预连下游引擎失败", it) }
    }

    override fun onDestroy() {
        Tlog.w(TAG, "服务 onDestroy")
        leaveForeground()
        runCatching { Prefs.of(this).unregisterOnSharedPreferenceChangeListener(engineChoiceListener) }
        // 用 isInitialized 判断，避免为了关闭反而把 lazy 触发出来
        if (lazyDownstream.isInitialized()) runCatching { downstream.shutdown() }
        super.onDestroy()
    }

    // ---------------- 语言 ----------------
    // 参数是 ISO-3 码（zho / eng），不是 BCP-47。这几个方法可能被多线程调用，
    // 而且 onGetVoices 的默认实现会对几百个 Locale 逐个调 onIsLanguageAvailable，
    // 所以必须是纯查表、无阻塞。

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int = try {
        isLanguageAvailable(lang, country, variant)
    } catch (t: Throwable) {
        // 这些是系统回调，异常漏出去就是进程崩溃
        Tlog.e(TAG, "onIsLanguageAvailable 异常", t)
        TextToSpeech.LANG_NOT_SUPPORTED
    }

    private fun isLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (lang == null) return TextToSpeech.LANG_NOT_SUPPORTED
        val locales = supportedLocales()
        val langMatch = locales.filter { iso3Language(it).equals(lang, ignoreCase = true) }
        if (langMatch.isEmpty()) return TextToSpeech.LANG_NOT_SUPPORTED
        if (country.isNullOrEmpty()) return TextToSpeech.LANG_AVAILABLE

        val countryMatch = langMatch.filter { iso3Country(it).equals(country, ignoreCase = true) }
        if (countryMatch.isEmpty()) return TextToSpeech.LANG_AVAILABLE
        if (variant.isNullOrEmpty()) return TextToSpeech.LANG_COUNTRY_AVAILABLE
        return if (countryMatch.any { it.variant.equals(variant, ignoreCase = true) }) {
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        } else {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        }
    }

    /** 契约要求它和 onIsLanguageAvailable 的返回值一致。 */
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    /** 文档说明它只在 API <= 17 被调用，但方法是 abstract 的，必须实现。 */
    override fun onGetLanguage(): Array<String> = try {
        currentLanguage()
    } catch (t: Throwable) {
        Tlog.e(TAG, "onGetLanguage 异常", t)
        arrayOf("zho", "CHN", "")
    }

    private fun currentLanguage(): Array<String> {
        val locale = supportedLocales().firstOrNull() ?: Locale.SIMPLIFIED_CHINESE
        return arrayOf(
            iso3Language(locale) ?: "zho",
            iso3Country(locale) ?: "CHN",
            locale.variant.orEmpty(),
        )
    }

    // getISO3Language()/getISO3Country() 对某些区域会抛 MissingResourceException
    private fun iso3Language(locale: Locale): String? =
        runCatching { locale.isO3Language }.getOrNull()

    private fun iso3Country(locale: Locale): String? =
        runCatching { locale.isO3Country }.getOrNull()

    private fun supportedLocales(): List<Locale> {
        val fromDownstream = downstream.voiceSnapshot.mapNotNull { it.locale }.distinct()
        return if (fromDownstream.isEmpty()) FALLBACK_LOCALES else fromDownstream
    }

    // ---------------- 声音镜像 ----------------

    /**
     * 注意这是**阻塞式 binder 调用**，卡住会直接卡住 TalkBack 的界面线程。
     * 所以只读快照，绝不在这里等下游初始化。
     */
    @Synchronized
    override fun onGetVoices(): MutableList<Voice> = try {
        mirroredVoices()
    } catch (t: Throwable) {
        Tlog.e(TAG, "onGetVoices 异常", t)
        ArrayList()
    }

    private fun mirroredVoices(): MutableList<Voice> {
        val source = downstream.voiceSnapshot
        if (source === cachedVoiceSource) return ArrayList(cachedMirroredVoices)

        val pkg = downstream.enginePackage ?: Prefs.downstreamEngine(this)
        val mirrored = source.mapNotNull { v ->
            val locale = v.locale ?: return@mapNotNull null
            runCatching {
                Voice(
                    encodeVoiceName(pkg, v.name),
                    locale,
                    v.quality,
                    v.latency,
                    // 必须是 false。TalkBack 的 getLanguages() 会把
                    // isNetworkConnectionRequired() 为 true、或 features 里含
                    // "notInstalled" 的声音整个过滤掉，那样语言菜单里就什么都看不到。
                    false,
                    v.features.orEmpty() - TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED,
                )
            }.getOrNull()
        }
        cachedVoiceSource = source
        cachedMirroredVoices = mirrored
        return ArrayList(mirrored)
    }

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceName != null && decodeVoiceName(voiceName) != null) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }

    /** 跑在合成线程上，只记录不做慢操作；真正的 setVoice 留到 onSynthesizeText。 */
    override fun onLoadVoice(voiceName: String?): Int = runCatching {
        val decoded = voiceName?.let { decodeVoiceName(it) }
        pendingVoice = decoded
        if (decoded != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }.getOrDefault(TextToSpeech.ERROR)

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?,
    ): String? = try {
        defaultVoiceNameFor(lang)
    } catch (t: Throwable) {
        Tlog.e(TAG, "onGetDefaultVoiceNameFor 异常", t)
        null
    }

    private fun defaultVoiceNameFor(lang: String?): String? {
        val pkg = downstream.enginePackage ?: Prefs.downstreamEngine(this)
        val match = downstream.voiceSnapshot.firstOrNull { v ->
            val locale = v.locale ?: return@firstOrNull false
            iso3Language(locale).equals(lang, ignoreCase = true)
        } ?: return null
        return encodeVoiceName(pkg, match.name)
    }

    private fun encodeVoiceName(pkg: String?, voice: String): String = "${pkg.orEmpty()}#$voice"

    /**
     * 用 `#` 分隔而不是下划线：下游的显示名里带下划线很常见，用下划线拼接会解析错。
     */
    private fun decodeVoiceName(encoded: String): String? {
        val idx = encoded.indexOf('#')
        if (idx < 0 || idx == encoded.length - 1) return null
        return encoded.substring(idx + 1)
    }

    // ---------------- 合成 ----------------

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (callback == null) return
        try {
            synthesize(request, callback)
        } catch (t: Throwable) {
            // 绝不能把异常抛回合成线程：那会让引擎对所有客户端失声
            Tlog.e(TAG, "合成异常", t)
            runCatching { callback.error(TextToSpeech.ERROR_SYNTHESIS) }
        } finally {
            // 契约：无论成败都必须调 done()
            runCatching { callback.done() }
        }
    }

    private fun synthesize(request: SynthesisRequest?, callback: SynthesisCallback) {
        val raw = runCatching { request?.charSequenceText?.toString() }.getOrNull().orEmpty()

        val caller = callerLabel(request)
        // TalkBack 的打断方式是 speak("", QUEUE_DESTROY)，所以空串会非常频繁地进来。
        // 必须短路，不然会把一堆空请求转发给下游。
        if (raw.isBlank()) {
            // QUEUE_DESTROY 会把所有客户端正在读的一句全停掉，所以空请求本身就是线索，记一行
            Tlog.i(TAG, "空请求（多半是打断） 来源=" + caller)
            emitSilence(callback)
            return
        }

        val trace = runCatching { request?.params?.getString(KEY_PROXY_TRACE) }.getOrNull()
        val target = resolveDownstream()

        // 会话从这里就要登记上：等下游连上最多要好几秒，这期间用户划走，
        // onStop 得能找到它、把它叫醒，否则后面每一句都要跟着干等。
        val session = Session(Diagnostics.utterances.incrementAndGet(), raw.length, caller)
        activeSession.set(session)
        // 服务启动时升前台可能被系统拒绝，每一句开始都再试一次；已经是前台时这里立刻返回
        if (!foreground && Prefs.keepAliveWanted(this)) ensureForeground("第 " + session.seq + " 句")
        Tlog.i(
            TAG,
            "第 " + session.seq + " 句开始 字数=" + raw.length + " 来源=" + caller +
                (if (Prefs.logText(this)) " 文本=" + raw.take(LOG_TEXT_CHARS).replace('\n', ' ') else ""),
        )
        try {
            // 第 2、3 层防护：运行时硬校验 + 环路探针。
            // 永远不信配置里的值——它可能来自备份恢复、旧版本导入，或者 adb 直接改的。
            if (target == null || target == packageName) {
                Tlog.e(TAG, "下游引擎无效: target=" + target)
                if (target != null) Prefs.clearDownstream(this)
                session.end(Diagnostics.EndReason.NO_ENGINE, "目标无效")
                callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
                return
            }
            // 环路只让这一句失败，**不清配置**：trace 是调用方自己填的参数，
            // 任何应用都能伪造它，不能凭它改用户的设置。
            if (trace?.split(';')?.contains(packageName) == true) {
                Tlog.e(TAG, "请求形成环路: trace=" + trace)
                session.end(Diagnostics.EndReason.LOOP)
                callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
                return
            }
            try {
                synthesizeWith(session, request, callback, raw, target, trace)
            } catch (t: Throwable) {
                session.end(Diagnostics.EndReason.EXCEPTION, t.javaClass.simpleName)
                throw t
            }
        } finally {
            activeSession.compareAndSet(session, null)
            // 每一句怎么结束的都记下来，念出来就是故障报告；日志里也留一行，连电脑时能对上号
            val record = session.toRecord()
            Diagnostics.record(record)
            Tlog.i(
                TAG,
                "第 " + record.seq + " 句结束: " + record.reason.label +
                    (record.detail?.let { "(" + it + ")" } ?: "") +
                    " 字数=" + record.chars + " 块=" + record.chunk + "/" + record.chunks +
                    " 用时=" + record.elapsedMs + "ms 来源=" + record.caller + " 引擎=" + record.engine +
                    (if (record.standIn) "(顶替)" else "") + " stopped=" + session.stopped,
            )
        }
    }

    private fun synthesizeWith(
        session: Session,
        request: SynthesisRequest?,
        callback: SynthesisCallback,
        raw: String,
        target: String,
        trace: String?,
    ) {
        Diagnostics.wantedEngine = target
        val before = downstream.enginePackage
        if (!downstream.ensureReady(target, INIT_TIMEOUT_MS) { session.stopped }) {
            if (session.stopped) {
                session.end(Diagnostics.EndReason.UPSTREAM_STOP, "等引擎时被打断")
                return
            }
            Diagnostics.actualEngine = downstream.connectedEngine
            Tlog.e(TAG, "下游引擎不可用: " + target + "，实际连上 " + Diagnostics.actualEngine)
            session.end(Diagnostics.EndReason.NO_ENGINE)
            callback.error(TextToSpeech.ERROR_SERVICE)
            return
        }
        Diagnostics.actualEngine = downstream.connectedEngine
        Diagnostics.onFallback = downstream.usingFallback
        session.engine = downstream.enginePackage
        session.standIn = downstream.usingFallback
        if (before != null && before != session.engine) {
            // 只有合成线程会换引擎，所以这里看到的变化就是换引擎的全部
            Diagnostics.engineSwitches.incrementAndGet()
            Diagnostics.event("第 " + session.seq + " 句前把引擎从 " + before + " 换成 " + session.engine)
            Tlog.i(TAG, "换引擎: " + before + " -> " + session.engine)
        }
        // 正在用顶替引擎时别把目标记成「上次可用」——它此刻恰恰不可用
        if (!downstream.usingFallback) Prefs.rememberGoodEngine(this, target)

        // 整段只有一个 emoji 时，用户要么是在逐字符查它，要么收到的就是一条纯 emoji 消息，
        // 两种情况都是想搞懂它，而且不会刷屏——直接给详细读法。可在设置里关掉。
        val verbosity = if (Prefs.detailSingleEmoji(this) && pipeline().isSingleEntry(raw)) {
            Verbosity.VERBOSE
        } else {
            Prefs.verbosity(this)
        }
        // 规范化。任何异常都会退回读原文，绝不静音。
        val processed = pipeline().transformSafe(raw, verbosity)
        if (processed.isBlank()) {
            // 例如整条消息只有 emoji，而档位是「关闭」
            session.end(Diagnostics.EndReason.EMPTY)
            emitSilence(callback)
            return
        }

        // 长度检查必须放在规范化**之后**：替换是膨胀变换，输入合法不代表输出合法，
        // 超过下游的 getMaxSpeechInputLength() 会让 synthesizeToFile 静默失败。
        val chunks = Chunker.split(processed)
        session.chunks = chunks.size

        // 语速/音调透传。不转发的话，用户在 TalkBack 里调的语速会完全失效。
        val rate = (request?.speechRate ?: 100).coerceIn(10, 600) / 100f
        val pitch = (request?.pitch ?: 100).coerceIn(10, 600) / 100f
        val voice = request?.voiceName?.let { decodeVoiceName(it) } ?: pendingVoice
        downstream.applyProsody(rate, pitch, voice)

        val traceValue = packageName + ";" + trace.orEmpty()
        if (Prefs.compatMode(this)) {
            speakThrough(session, request, callback, chunks, traceValue)
        } else {
            streamThrough(session, callback, chunks, traceValue, target)
        }
    }

    /**
     * 链路 C（默认）：synthesizeToFile + onAudioAvailable 流式截获，边收边回灌。
     *
     * 这里**不转发** volume / pan / streamType —— 上游框架的 BlockingAudioTrack
     * 已经用请求自带的参数建好了 AudioTrack，再施加一遍会把音量平方。
     *
     * 分块是**串行**处理的，没有做「回灌第 N 块时预提交第 N+1 块」的流水线。
     * 这是有意的取舍：分块只在超过 250 字符时才触发，而 TalkBack 的绝大多数播报只有
     * 几十个字符；流水线会在这个函数里引入若干提前返回时的清理路径，而这里一旦卡住
     * 就是整个引擎永久失声。先要正确，延迟优化留到真机测出确实需要时再说。
     */
    private fun streamThrough(
        session: Session,
        callback: SynthesisCallback,
        chunks: List<String>,
        traceValue: String,
        target: String,
    ) {
        val state = StreamState()
        for (chunk in chunks) {
            session.chunk++
            if (session.stopped) {
                session.end(Diagnostics.EndReason.UPSTREAM_STOP, "提交前")
                return
            }
            val pipe = PcmPipe()
            session.pipe = pipe
            val extras = Bundle().apply { putString(KEY_PROXY_TRACE, traceValue) }
            var id = downstream.submit(chunk, extras, pipe)
            if (id == null && !session.stopped) {
                // 提交直接被拒，基本就是连接已经作废：下游被应用商店更新、被强行停止之后，
                // 框架不会自己重连。扔掉这条连接，重连（或找顶替）后这一块再试一次——
                // 不试的话，从这一刻起每一句都没声音，直到我们的进程重启。
                downstream.markBroken()
                if (downstream.ensureReady(target, INIT_TIMEOUT_MS) { session.stopped }) {
                    session.engine = downstream.enginePackage
                    session.standIn = downstream.usingFallback
                    id = downstream.submit(chunk, extras, pipe)
                }
            }
            if (id == null) {
                if (session.stopped) {
                    session.end(Diagnostics.EndReason.UPSTREAM_STOP, "提交时")
                } else {
                    session.end(Diagnostics.EndReason.SUBMIT_REJECTED)
                    callback.error(TextToSpeech.ERROR_SERVICE)
                }
                return
            }
            // 打断正好落在提交之前的话，onStop 里的 stop 已经执行完，拦不住刚交上去的这一块。
            // 只在这个窄窗口里补停一次，而且就在提交之后当场停。
            //
            // 不要在所有 STOPPED 路径上都补停：那样补的这一次 stop 紧贴着下一句的提交，
            // 异步处理 stop 的引擎会把它落到下一句头上，下一句被掐、下游报「被打断」、
            // 又触发补停……朗读读着读着就断了。上一版就是全路径补停，真机上出现了朗读中途停止，
            // 这是最可疑的原因（按改动逐条排查得出，没有日志证实）。
            if (session.stopped) {
                session.end(Diagnostics.EndReason.UPSTREAM_STOP, "提交后当场补停")
                downstream.stopNow()
                downstream.finish(id)
                return
            }
            try {
                when (drain(session, pipe, callback, state)) {
                    DrainResult.DONE -> Unit
                    DrainResult.STOPPED -> return
                    DrainResult.STALLED -> {
                        // 下游卡死：它的队列头上堵着这一块，后面每一句都得先等满看门狗。
                        // 丢掉这条连接，下一句重连或换顶替。
                        session.end(Diagnostics.EndReason.STALLED)
                        downstream.markBroken()
                        callback.error(TextToSpeech.ERROR_SYNTHESIS)
                        return
                    }
                    DrainResult.FAILED -> {
                        callback.error(TextToSpeech.ERROR_SYNTHESIS)
                        return
                    }
                }
            } finally {
                downstream.finish(id)
            }
        }
        // 一个音频块都没收到（例如下游对这段文本没输出）：仍然要 start()，
        // 否则上游收不到 onStart，TalkBack 的说话状态机会错乱。
        if (!state.started) emitSilence(callback)
    }

    private class StreamState {
        var started = false
        var format: PcmPipe.Event.Format? = null

        /**
         * 帧对齐器。回灌必须按帧走——从帧中间切开会让后续所有采样错位一个字节，
         * 听感是持续的刺耳乱读。详见 [PcmFramer]。
         */
        var framer: PcmFramer = PcmFramer(2)
    }

    /** 16 位单声道是 2 字节一帧；8 位是 1，浮点是 4，再乘声道数。 */
    private fun framerFor(encoding: Int, channels: Int): PcmFramer {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        return PcmFramer(PcmFramer.frameBytesFor(bytesPerSample, channels))
    }

    private enum class DrainResult { DONE, STOPPED, STALLED, FAILED }

    private fun drain(
        session: Session,
        pipe: PcmPipe,
        callback: SynthesisCallback,
        state: StreamState,
    ): DrainResult {
        while (true) {
            if (session.stopped) {
                session.end(Diagnostics.EndReason.UPSTREAM_STOP, "回灌中")
                return DrainResult.STOPPED
            }

            // 看门狗：下游连续这么久没有任何输出就判定卡死。
            // 这是「停滞」超时而不是「总时长」超时——回灌时 audioAvailable 会因为
            // 播放背压而阻塞，那期间不轮询，所以长文本不会被误杀。
            val event = pipe.poll(STALL_TIMEOUT_MS)
            if (event == null) {
                Tlog.e(TAG, "下游 " + STALL_TIMEOUT_MS + "ms 无任何输出，判定卡死")
                Diagnostics.watchdogTimeouts.incrementAndGet()
                return DrainResult.STALLED
            }

            when (event) {
                is PcmPipe.Event.Format -> {
                    if (!state.started) {
                        // start() 只能调一次，格式在这一刻就锁死了
                        if (callback.start(event.sampleRate, event.encoding, event.channels)
                            != TextToSpeech.SUCCESS
                        ) {
                            session.end(Diagnostics.EndReason.UPSTREAM_REFUSED, "start 被拒")
                            return DrainResult.STOPPED
                        }
                        state.started = true
                        state.format = event
                        state.framer = framerFor(event.encoding, event.channels)
                    } else if (event != state.format) {
                        // 同一引擎同一声音下几乎不可能发生。真发生了只能二选一：
                        // 用错误的采样率喂进去（听感是变调，完全听不懂），或者丢掉这一块。
                        // 选丢弃，并且大声打日志——这类杂音极难查。
                        Tlog.e(TAG, "采样率漂移 " + event + " vs " + state.format + "，丢弃该块")
                        Diagnostics.formatDrifts.incrementAndGet()
                        session.end(Diagnostics.EndReason.FORMAT_DRIFT, event.toString() + " 对 " + state.format)
                        return DrainResult.DONE
                    }
                }

                is PcmPipe.Event.Chunk -> {
                    if (!state.started) {
                        Tlog.w(TAG, "下游未上报音频格式，按 16kHz/16 位/单声道兜底")
                        Diagnostics.missingFormats.incrementAndGet()
                        if (callback.start(DEFAULT_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                            != TextToSpeech.SUCCESS
                        ) {
                            session.end(Diagnostics.EndReason.UPSTREAM_REFUSED, "start 被拒")
                            return DrainResult.STOPPED
                        }
                        state.started = true
                        // 格式也要记下：不记的话后面每一块的 Format 事件都会被当成「采样率漂移」丢掉
                        state.format = PcmPipe.Event.Format(DEFAULT_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
                        state.framer = PcmFramer(2)
                    }
                    if (!feed(callback, event.bytes, state)) {
                        // 上游的 audioAvailable 返回错误：几乎只有一种情况，就是框架已经把这一句停了。
                        // 我们的 onStop 可能还没跑到，所以这里 session.stopped 多半还是 false。
                        session.end(
                            Diagnostics.EndReason.UPSTREAM_REFUSED,
                            if (session.stopped) "回灌被拒，已收到打断" else "回灌被拒，打断还没到",
                        )
                        return DrainResult.STOPPED
                    }
                }

                PcmPipe.Event.Done -> return DrainResult.DONE
                is PcmPipe.Event.Failed -> {
                    Tlog.e(TAG, "下游合成失败 code=" + event.code)
                    Diagnostics.downstreamErrors.incrementAndGet()
                    session.end(Diagnostics.EndReason.DOWNSTREAM_ERROR, "错误码 " + event.code)
                    return DrainResult.FAILED
                }
                PcmPipe.Event.Interrupted -> {
                    // 管道被打断有两个来源：我们自己的 onStop（那时 stopped 已经是 true），
                    // 或者下游的 onStop 回调——那是下游自己停的，不是我们叫的。
                    if (session.stopped) {
                        session.end(Diagnostics.EndReason.UPSTREAM_STOP, "管道被打断")
                    } else {
                        session.end(Diagnostics.EndReason.DOWNSTREAM_STOP)
                    }
                    return DrainResult.STOPPED
                }
            }
        }
    }

    /**
     * 把一段 PCM 回灌给上游，**严格按帧对齐**。
     *
     * 帧对齐是这里最要紧的事：16 位采样每帧 2 字节，一旦在帧中间切开或漏掉一个字节，
     * 后面所有采样都会从错位的字节对重建，听感是持续的刺耳乱读。
     * 所以切分长度对齐到帧，不足一帧的尾巴留到下一次拼上。
     */
    private fun feed(callback: SynthesisCallback, bytes: ByteArray, state: StreamState): Boolean {
        feedSeq = activeSession.get()?.seq ?: feedSeq
        // 别硬编码 8192：超过 maxBufferSize 会抛 IllegalArgumentException，不是返回错误码。
        // 再对齐到帧的整数倍，否则同样会切在帧中间。
        val maxBuffer = state.framer.alignBufferSize(callback.maxBufferSize)
        val data = state.framer.accept(bytes)

        var offset = 0
        while (offset < data.size) {
            val length = minOf(maxBuffer, data.size - offset)
            // 上游未播完的音频超过 500ms 时这里会阻塞；被 stop 时立刻返回错误。
            // 必须检查返回值，否则打断之后还会继续灌。
            val began = SystemClock.elapsedRealtime()
            feedStartedAt = began
            val rc = callback.audioAvailable(data, offset, length)
            feedStartedAt = 0L
            val took = SystemClock.elapsedRealtime() - began
            if (took >= FEED_STALL_MS) {
                Tlog.w(TAG, "回灌恢复，这一次 audioAvailable 卡了 " + took + "ms，返回 " + rc)
            }
            if (rc != TextToSpeech.SUCCESS) return false
            offset += length
        }
        return true
    }

    /**
     * 链路 B（兼容模式）：直接让下游发声，我们不接管音频。
     * 给个别 onAudioAvailable 有 bug 的第三方引擎兜底。
     *
     * 这条路必须**手工转发**音频参数：不转发 KEY_PARAM_STREAM 的话，下游会用
     * STREAM_MUSIC，用户调「无障碍音量」就没反应了。
     */
    private fun speakThrough(
        session: Session,
        request: SynthesisRequest?,
        callback: SynthesisCallback,
        chunks: List<String>,
        traceValue: String,
    ) {
        // 仍然要 start()：否则上游收不到 onStart，TalkBack 的说话状态机会错乱
        if (callback.start(DEFAULT_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
            != TextToSpeech.SUCCESS
        ) {
            session.end(Diagnostics.EndReason.UPSTREAM_REFUSED, "start 被拒")
            return
        }

        val incoming = request?.params
        for (chunk in chunks) {
            session.chunk++
            if (session.stopped) {
                session.end(Diagnostics.EndReason.UPSTREAM_STOP, "提交前")
                return
            }
            // 不收集音频：直通模式下框架仍会把 PCM 推给我们，不丢弃的话队列很快填满，
            // 每次入队都要在下游的 binder 线程上阻塞
            val pipe = PcmPipe(collectAudio = false)
            session.pipe = pipe
            val id = "ttsproxy-passthrough-" + passthroughIds.incrementAndGet()

            val params = Bundle().apply {
                putString(KEY_PROXY_TRACE, traceValue)
                putInt(
                    TextToSpeech.Engine.KEY_PARAM_STREAM,
                    incoming?.getInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                        ?: AudioManager.STREAM_MUSIC,
                )
                putFloat(
                    TextToSpeech.Engine.KEY_PARAM_VOLUME,
                    incoming?.getFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) ?: 1.0f,
                )
                putFloat(
                    TextToSpeech.Engine.KEY_PARAM_PAN,
                    incoming?.getFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0.0f) ?: 0.0f,
                )
            }

            if (!downstream.speakDirect(chunk, params, id, pipe)) {
                session.end(Diagnostics.EndReason.SUBMIT_REJECTED)
                downstream.markBroken()
                callback.error(TextToSpeech.ERROR_SERVICE)
                return
            }
            // 直通模式是下游直接出声：打断要是正好落在提交之前，不当场停它就会整块念完。
            // 只在这个窄窗口里补停，理由见 streamThrough。
            if (session.stopped) {
                session.end(Diagnostics.EndReason.UPSTREAM_STOP, "提交后当场补停")
                downstream.stopNow()
                downstream.finish(id)
                return
            }

            try {
                // 直通模式下，播放期间不会有任何事件，只有播完才来 onDone。
                // 所以超时不能用「停滞」时长，得按文本长度估一个上限。
                val budget = PASSTHROUGH_BASE_TIMEOUT_MS + chunk.length * PASSTHROUGH_MS_PER_CHAR
                var waited = 0L
                while (true) {
                    if (session.stopped) {
                        session.end(Diagnostics.EndReason.UPSTREAM_STOP, "等播完时")
                        return
                    }
                    val event = pipe.poll(POLL_SLICE_MS)
                    if (event == null) {
                        waited += POLL_SLICE_MS
                        if (waited >= budget) {
                            Tlog.e(TAG, "直通模式等待下游超时 " + budget + "ms")
                            Diagnostics.watchdogTimeouts.incrementAndGet()
                            session.end(Diagnostics.EndReason.STALLED)
                            downstream.stopNow()
                            return
                        }
                        continue
                    }
                    when (event) {
                        PcmPipe.Event.Done -> break
                        is PcmPipe.Event.Failed -> {
                            Tlog.e(TAG, "直通模式下游报错 code=" + event.code)
                            Diagnostics.downstreamErrors.incrementAndGet()
                            session.end(Diagnostics.EndReason.DOWNSTREAM_ERROR, "错误码 " + event.code)
                            return
                        }
                        PcmPipe.Event.Interrupted -> {
                            session.end(
                                if (session.stopped) Diagnostics.EndReason.UPSTREAM_STOP else Diagnostics.EndReason.DOWNSTREAM_STOP,
                            )
                            return
                        }
                        else -> Unit
                    }
                }
            } finally {
                downstream.finish(id)
            }
        }
    }

    /**
     * 打断。**从 binder 线程调用，不是合成线程**，必须非阻塞、幂等。
     *
     * 这是把我们从 onSynthesizeText 的阻塞里唤醒的唯一入口。
     */
    override fun onStop() {
        runCatching {
            val session = activeSession.getAndSet(null)
            if (session != null) {
                session.interrupt()
                Tlog.i(TAG, "onStop: 打断第 " + session.seq + " 句，第 " + session.chunk + "/" + session.chunks + " 块")
            } else {
                // 框架的 stop 是按「当时正在读的那一句」发的，可它送到这里时那一句可能刚好读完了。
                // 这时下面的 stopNow 落到的是下游队列里的下一块——记下来，真机上出现过就有据可查。
                Diagnostics.lateStops.incrementAndGet()
                Diagnostics.event("onStop 到达时没有正在读的句子（迟到的打断）")
                Tlog.w(TAG, "onStop: 没有正在读的句子，这次 stop 迟到了")
            }
        }
        if (lazyDownstream.isInitialized()) runCatching { downstream.stopNow() }
    }

    /**
     * 这一句是谁发来的。本应用自己（试听、调试界面）和 TalkBack 是两个客户端，
     * 一个客户端的 QUEUE_DESTROY 会把另一个正在读的句子也停掉，查「读着读着没了」必须分清。
     */
    private fun callerLabel(request: SynthesisRequest?): String {
        val uid = runCatching { request?.callerUid }.getOrNull() ?: return "未知"
        if (uid == Process.myUid()) return "本应用"
        val pkgs = runCatching { packageManager.getPackagesForUid(uid) }.getOrNull()
        return (pkgs?.firstOrNull() ?: "uid") + "(" + uid + ")"
    }

    /** 起一次空合成：让上游拿到 onStart / onDone，状态机不至于错乱。 */
    private fun emitSilence(callback: SynthesisCallback) {
        runCatching {
            callback.start(DEFAULT_SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1)
        }
    }

    /**
     * 决定用哪个下游引擎：用户配置 -> 上次可用 -> 系统里的其他引擎。
     * 目的是宁可换一个引擎发声，也不要让用户的手机变哑巴。
     */
    private fun resolveDownstream(): String? {
        Prefs.downstreamEngine(this)?.takeIf { it != packageName }?.let { return it }
        Prefs.lastGoodEngine(this)?.takeIf { it != packageName }?.let { return it }
        return fallbackEngine
    }

    companion object {
        private const val TAG = "ProxyTtsService"

        /**
         * 环路探针用的自定义参数键。框架只对已知的几个 key 做类型校验，
         * 未知的 String key 会原样穿过整条链路——即使中间隔着另一个中转器。
         */
        const val KEY_PROXY_TRACE = "com.ttsproxy.TRACE"

        private const val DEFAULT_SAMPLE_RATE = 16000
        private const val KEEP_ALIVE_CHANNEL = "keep_alive"

        private const val KEEP_ALIVE_NOTIFICATION_ID = 1

        /** 开了「日志里记录朗读文本」时每句最多记多少字。 */
        private const val LOG_TEXT_CHARS = 60

        /**
         * 手里一个能出声的引擎都没有时，这一句最多等下游连上多久。
         * 有旧引擎能顶着时等得更短，见 EngineSwitch.Timing.switchWaitMs。
         */
        private const val INIT_TIMEOUT_MS = 8_000L
        private const val STALL_TIMEOUT_MS = 15_000L
        private const val POLL_SLICE_MS = 1_000L

        /** 单次 audioAvailable 超过这么久就算卡住。正常最多阻塞约 500ms（上游缓冲的长度）。 */
        private const val FEED_STALL_MS = 1_500L
        private const val FEED_WATCH_SLICE_MS = 250L
        private const val FEED_REPORT_EVERY_MS = 2_000L
        private const val PASSTHROUGH_BASE_TIMEOUT_MS = 30_000L
        private const val PASSTHROUGH_MS_PER_CHAR = 400L

        private val FALLBACK_LOCALES = listOf(Locale.SIMPLIFIED_CHINESE, Locale.US)
    }
}
