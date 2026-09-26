package com.ttsproxy

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 落盘的运行日志。
 *
 * 为什么要有它：有一类故障只在开机后那几分钟出现，用户一打开本应用它就消失了，
 * 而 logcat 又要连电脑。所以进程一起来（TalkBack 开机就会把服务拉起来）就开始往
 * 设备保护存储里写，用户之后进「检查朗读效果」一键复制出来发给我们即可。
 *
 * 写两路：
 *  1. 我们自己的日志：每一行同时打到 logcat 和文件，文件是主路。
 *  2. 顺带起一个只看本进程的 `logcat` 子进程，把框架在本进程里打的日志
 *     （TextToSpeech 客户端的「Disconnected from TTS engine」、服务端的
 *     「done() was called before start()」、AudioTrack 报错之类）也收进同一个文件。
 *     这一路是尽力而为：系统不让起就算了，第 1 路照常。
 *
 * 不记任何朗读文本，只记长度和事件。文件放在设备保护存储里，锁屏时也写得进。
 * 超过上限就把当前文件改名成 .1 重新开始，最多留两份。
 */
object Tlog {

    private const val FILE_NAME = "trace.log"
    private const val MAX_BYTES = 2L * 1024 * 1024

    /** 复制到剪贴板时最多带多少字节：binder 单次事务约 1MB，留足余量。分享走文件，不受这个限制。 */
    const val SNAPSHOT_BYTES = 400 * 1024

    /** 完整日志文件，给分享用。两份文件按时间顺序拼成一份临时文件。 */
    fun exportFile(context: Context): File? {
        val d = dir ?: return null
        synchronized(lock) { runCatching { writer?.flush() } }
        val out = File(context.cacheDir, "ttsproxy-log.txt")
        return runCatching {
            out.outputStream().use { o ->
                for (name in listOf("$FILE_NAME.1", FILE_NAME)) {
                    val f = File(d, name)
                    if (f.exists()) f.inputStream().use { it.copyTo(o) }
                }
            }
            out
        }.getOrNull()
    }

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var dir: File? = null
    private var writer: Writer? = null
    private var written = 0L
    private val lock = Any()

    /** 我们自己打的 tag。logcat 那一路遇到这些 tag 就跳过，免得每行重复两遍。 */
    private val ownTags = HashSet<String>()

    /** 进程一起来就调，越早越好。 */
    fun init(context: Context) {
        val d = Prefs.storageContext(context.applicationContext).filesDir
        synchronized(lock) {
            if (dir != null) return
            dir = d
            runCatching { open() }
        }
        i(
            "Tlog",
            "==== 进程启动 pid=" + Process.myPid() + " 开机后 " + SystemClock.elapsedRealtime() / 1000 +
                " 秒 版本=" + versionOf(context) + " ====",
        )
        startLogcat()
    }

    private fun versionOf(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        write("I", tag, msg, null)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (t == null) Log.w(tag, msg) else Log.w(tag, msg, t)
        write("W", tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t == null) Log.e(tag, msg) else Log.e(tag, msg, t)
        write("E", tag, msg, t)
    }

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        synchronized(ownTags) { ownTags.add(tag) }
        val line = buildString {
            append(stamp.format(Date()))
            append(" up=").append(SystemClock.elapsedRealtime() / 1000)
            append(' ').append(level).append('/').append(tag)
            append(" [").append(Thread.currentThread().name).append("] ")
            append(msg)
            if (t != null) append(' ').append(Log.getStackTraceString(t))
            append('\n')
        }
        appendRaw(line)
    }

    private fun appendRaw(line: String) {
        synchronized(lock) {
            val w = writer ?: return
            runCatching {
                w.write(line)
                w.flush()
                written += line.length
                if (written > MAX_BYTES) rotate()
            }
        }
    }

    // ---- 以下都在持有 lock 时调用 ----

    private fun open() {
        val d = dir ?: return
        val f = File(d, FILE_NAME)
        written = f.length()
        writer = OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8)
    }

    private fun rotate() {
        runCatching { writer?.close() }
        writer = null
        val d = dir ?: return
        val f = File(d, FILE_NAME)
        val old = File(d, "$FILE_NAME.1")
        if (old.exists()) old.delete()
        f.renameTo(old)
        runCatching { open() }
    }

    /** 最近的日志文本，给剪贴板和分享用。太长就只取最后一段。 */
    fun snapshot(maxBytes: Int = SNAPSHOT_BYTES): String {
        val d = dir ?: return ""
        synchronized(lock) { runCatching { writer?.flush() } }
        val cur = runCatching { File(d, FILE_NAME).readText(Charsets.UTF_8) }.getOrDefault("")
        val text = if (cur.length >= maxBytes) {
            cur
        } else {
            val old = runCatching { File(d, "$FILE_NAME.1").readText(Charsets.UTF_8) }.getOrDefault("")
            old + cur
        }
        if (text.length <= maxBytes) return text
        // 从行首截断，别让第一行是半截
        val cut = text.length - maxBytes
        val nl = text.indexOf('\n', cut)
        return "（前面已截去）\n" + text.substring(if (nl < 0) cut else nl + 1)
    }

    fun clear() {
        val d = dir ?: return
        synchronized(lock) {
            runCatching { writer?.close() }
            writer = null
            File(d, FILE_NAME).delete()
            File(d, "$FILE_NAME.1").delete()
            runCatching { open() }
        }
        i("Tlog", "==== 日志已清空 ====")
    }

    fun sizeBytes(): Long {
        val d = dir ?: return 0
        return File(d, FILE_NAME).length() + File(d, "$FILE_NAME.1").length()
    }

    /**
     * 只看本进程的 logcat。应用没有 READ_LOGS 权限，但自己进程的日志系统是让读的。
     * 起不来就记一行原因，不影响主路。
     */
    private fun startLogcat() {
        Thread({
            val proc = try {
                ProcessBuilder("logcat", "-v", "threadtime", "--pid=" + Process.myPid())
                    .redirectErrorStream(true)
                    .start()
            } catch (t: Throwable) {
                w("Tlog", "起不了 logcat 子进程，只记我们自己的日志", t)
                return@Thread
            }
            i("Tlog", "logcat 子进程已启动")
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).useLines { lines ->
                    for (line in lines) {
                        if (line.isEmpty() || line.startsWith("--------- beginning")) continue
                        if (!worthKeeping(line)) continue
                        appendRaw("  logcat| $line\n")
                    }
                }
            }.onFailure { w("Tlog", "logcat 子进程读取中断", it) }
            runCatching { proc.destroy() }
        }, "tts-proxy-logcat").apply { isDaemon = true }.start()
    }

    /**
     * 只留有用的 logcat 行。第一版什么都收，结果每句话都带十几行 AudioTrack/PlayerBase
     * 的系统噪音，四百 KB 的上限被噪音撑满，开机那一段反而被截掉了。
     *
     * threadtime 格式：`日期 时间 pid tid 级别 tag: 消息`。
     */
    private fun worthKeeping(line: String): Boolean {
        // 跳过前四个字段（日期 时间 pid tid），然后是级别，再是 "TAG: msg"
        var idx = 0
        var fields = 0
        while (fields < 4 && idx < line.length) {
            while (idx < line.length && line[idx] != ' ') idx++
            while (idx < line.length && line[idx] == ' ') idx++
            fields++
        }
        if (idx >= line.length) return false
        val level = line[idx]
        idx++
        while (idx < line.length && line[idx] == ' ') idx++
        val colon = line.indexOf(':', idx)
        if (colon <= idx) return false
        val tag = line.substring(idx, colon).trim()
        if (synchronized(ownTags) { tag in ownTags }) return false
        // AudioTrack 每句刷十几行，但「start()」和「frames delivered」两行能证明声音真放出去了多少
        if (tag == "AudioTrack") return line.contains("frames delivered") || line.contains(" start(")
        if (tag in NOISY_TAGS) return false
        if (tag in WANTED_TAGS) return true
        // 其余只留错误和致命：崩溃、ANR、进程被杀的痕迹都在这两级里
        return level == 'E' || level == 'F'
    }

    /** 框架里和 TTS 直接相关的 tag，不管级别都留。 */
    private val WANTED_TAGS = setOf(
        "TextToSpeech", "TextToSpeechService", "TtsEngines", "TextToSpeechManager",
        "AudioPlaybackHandler", "BlockingAudioTrack", "SynthesisPlaybackQueueItem",
        "PlaybackSynthesisRequest", "FileSynthesisRequest", "AudioPlaybackQueueItem",
        "AndroidRuntime", "System.err", "LifecycleTransaction", "ActivityThread",
    )

    /** 每句话都刷十几行、又从来不说明问题的系统 tag。 */
    private val NOISY_TAGS = setOf(
        "AudioTrack", "android.media.AudioTrack", "AudioTrack-JNI", "PlayerBase", "AudioSystem",
        "OpenGLRenderer", "HwAdaptiveFrameManager", "HwViewRootImpl", "ViewRootImpl", "DecorView",
        "InputMethodManager", "InsetsSourceConsumer", "libEGL",
    )
}
