package com.ttsproxy

import android.content.Context
import android.content.SharedPreferences
import com.ttsproxy.core.Verbosity

/**
 * 配置读写。
 *
 * 刻意使用**设备保护存储**（device-protected storage）而不是默认的 SharedPreferences：
 * 服务声明了 directBootAware，开机解锁前就会被绑定，那时凭据加密存储还读不到。
 * 用设备保护存储，锁屏状态下无障碍也能正常读到下游引擎的配置。
 *
 * ## 为什么还要额外抄一份到文件里
 *
 * SharedPreferences 的 `apply()` 每次重写整个 XML，而且是异步的。重启手机时进程被强杀，
 * 正好撞上一次重写就可能把整个文件丢掉——用户的表现就是「重启后引擎选择没了」。
 * 所以关键配置同时用 [DurableStore] 原子落盘抄一份，进程启动时若发现 SharedPreferences
 * 里缺了而镜像里有，就补回去。**镜像只补不覆盖**：用户在设置里改过的值永远优先。
 */
object Prefs {

    const val NAME = "ttsproxy"

    const val KEY_DOWNSTREAM = "downstream_engine"
    const val KEY_VERBOSITY = "verbosity"
    const val KEY_COMPAT_MODE = "compat_mode"
    const val KEY_DETAIL_SINGLE = "detail_single_emoji"
    const val KEY_LAST_GOOD = "last_good_engine"
    const val KEY_IPA_BRAILLE = "ipa_braille"
    const val KEY_SELF_SESSION = "self_session"

    fun storageContext(context: Context): Context =
        context.createDeviceProtectedStorageContext()

    fun of(context: Context): SharedPreferences =
        storageContext(context).getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 用户选定的下游引擎包名；未配置时返回 null。 */
    fun downstreamEngine(context: Context): String? =
        of(context).getString(KEY_DOWNSTREAM, null)?.takeIf { it.isNotBlank() }

    fun setDownstreamEngine(context: Context, pkg: String?) {
        // 用户主动改配置是低频操作，用 commit() 同步落盘，别让它排在异步队列里等着被强杀
        of(context).edit().putString(KEY_DOWNSTREAM, pkg).commit()
        mirror(context)
    }

    fun verbosity(context: Context): Verbosity =
        Verbosity.fromKey(of(context).getString(KEY_VERBOSITY, Verbosity.BRIEF.key))

    /**
     * 音标简洁档是否改读 ICEB 盲文点位。
     *
     * 默认关：读中文音理简称（`ŋ` 读「软腭鼻音」）。学过盲文音标、更习惯直接
     * 听点位的用户可以打开，`ŋ` 就读成「点 1 2 4 6」。两种读法都对应同一份
     * ICEB 表，只是简洁档念哪一面的区别；详细档任何时候都是「点位 + 音理」。
     */
    fun ipaAsBraille(context: Context): Boolean =
        of(context).getBoolean(KEY_IPA_BRAILLE, false)

    /**
     * 整段文本只有一个 emoji 时，是否直接用详细读法。
     *
     * 用 TalkBack 逐字符浏览去查某个 emoji、或者别人发来一条纯 emoji 消息时，
     * 用户都是想搞懂它，而且不存在刷屏问题。默认开。
     */
    fun detailSingleEmoji(context: Context): Boolean =
        of(context).getBoolean(KEY_DETAIL_SINGLE, true)

    /**
     * 服务活着就保留一条到本引擎自己的 TextToSpeech 连接，默认开。
     *
     * 这是防止系统冻结进程的办法（华为真机查实，见 ProxyTtsService.openSelfSession 的注释）。
     * 9 月 11 日之前的版本靠一个无意留下的探针会话躲过了冻结，改成查包管理器之后就中招了。
     */
    fun selfSession(context: Context): Boolean =
        of(context).getBoolean(KEY_SELF_SESSION, true)

    /** 兼容模式：走「直通」链路而不是「流式截获」，给个别 onAudioAvailable 有 bug 的引擎兜底。 */
    fun compatMode(context: Context): Boolean =
        of(context).getBoolean(KEY_COMPAT_MODE, false)

    fun lastGoodEngine(context: Context): String? =
        of(context).getString(KEY_LAST_GOOD, null)?.takeIf { it.isNotBlank() }

    /**
     * 记住上次能用的引擎。**只在值真的变了的时候写。**
     *
     * 这里原来是每合成一句就写一次。SharedPreferences 每次 `apply()` 重写整个文件，
     * TalkBack 一秒好几句，配置文件就一直处在被反复整体重写的状态——
     * 重启时进程被强杀撞上其中一次，整个文件（连同统计）就没了。
     */
    fun rememberGoodEngine(context: Context, pkg: String) {
        if (lastGoodEngine(context) == pkg) return
        of(context).edit().putString(KEY_LAST_GOOD, pkg).apply()
        mirror(context)
    }

    /** 检测到自引用等坏配置时清掉，避免下次启动继续踩同一个坑。 */
    fun clearDownstream(context: Context) {
        of(context).edit().remove(KEY_DOWNSTREAM).commit()
        mirror(context)
    }

    // ---- 掉电安全的镜像 ----------------------------------------------------

    private const val MIRROR_FILE = "config.tsv"

    /** 上一次从镜像里补回过配置。显示在诊断信息里，方便用户口头反馈。 */
    @Volatile
    var restoredFromMirror: String? = null
        private set

    /** 把关键配置抄一份到原子落盘的文件里。 */
    fun mirror(context: Context) {
        val p = of(context)
        val text = buildString {
            append("# 关键配置的掉电安全副本，由程序维护，删掉不影响使用\n")
            for (key in MIRRORED) {
                val value = p.getString(key, null) ?: continue
                if (value.isNotBlank()) append(key).append('\t').append(value).append('\n')
            }
            append(KEY_DETAIL_SINGLE).append('\t').append(detailSingleEmoji(context)).append('\n')
            append(KEY_COMPAT_MODE).append('\t').append(compatMode(context)).append('\n')
        }
        DurableStore.write(context, MIRROR_FILE, text)
    }

    /**
     * 进程启动时调一次：SharedPreferences 里缺的键，从镜像里补回来。
     *
     * **只补不覆盖。** 镜像是兜底，不是权威——用户刚在设置里改的值必须赢。
     */
    fun restoreFromMirror(context: Context) {
        val text = DurableStore.read(context, MIRROR_FILE) ?: return
        val p = of(context)
        val editor = p.edit()
        val restored = ArrayList<String>(2)
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val cols = t.split('\t')
            if (cols.size < 2) continue
            val (key, value) = cols[0] to cols[1]
            if (p.contains(key)) continue
            when (key) {
                KEY_DETAIL_SINGLE, KEY_COMPAT_MODE -> editor.putBoolean(key, value.toBoolean())
                in MIRRORED -> editor.putString(key, value)
                else -> continue
            }
            restored.add(key)
        }
        if (restored.isEmpty()) return
        editor.commit()
        restoredFromMirror = restored.joinToString("、")
    }

    private val MIRRORED = listOf(KEY_DOWNSTREAM, KEY_VERBOSITY, KEY_LAST_GOOD)
}
