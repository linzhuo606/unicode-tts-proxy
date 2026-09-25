package com.ttsproxy

import android.content.Context
import android.util.Log
import com.ttsproxy.core.SymbolDict
import com.ttsproxy.core.UserDict
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 用户自定义词典的存取。
 *
 * 文件落在**设备保护存储**里，和配置同一个地方：服务声明了 directBootAware，
 * 开机解锁前就会被绑定，那时凭据加密存储还读不到——用户的自定义读法不能在锁屏时失效。
 *
 * 所有写操作都会推高 [version]，服务据此在下一句合成时重建流水线，
 * 不需要重启引擎。这一点很要紧：改完读法要立刻能听到效果，
 * 否则用户没法判断自己改对了没有。
 */
object UserDictStore {

    private const val TAG = "UserDictStore"

    /** 每次写入自增。服务拿它和自己缓存的版本比对，变了就重建词典。 */
    val version = AtomicInteger()

    fun file(context: Context): File =
        File(Prefs.storageContext(context).filesDir, UserDict.FILE_NAME)

    /**
     * 读出用户条目。任何异常都返回空列表——用户词典坏掉最多是自定义读法失效，
     * 绝不能连累内置词典和发声。
     */
    fun load(context: Context): List<SymbolDict.Entry> = runCatching {
        val text = DurableStore.read(context, UserDict.FILE_NAME) ?: return emptyList()
        UserDict.parse(text).entries
    }.getOrElse {
        Log.e(TAG, "用户词典读取失败，本次忽略自定义读法", it)
        emptyList()
    }

    /** 连同坏行一起读出来，供词典界面向用户报告哪一行有问题。 */
    fun loadWithProblems(context: Context): UserDict.Result = runCatching {
        val text = DurableStore.read(context, UserDict.FILE_NAME)
        if (text == null) UserDict.Result(emptyList(), emptyList()) else UserDict.parse(text)
    }.getOrElse { UserDict.Result(emptyList(), emptyList()) }

    fun save(context: Context, entries: List<SymbolDict.Entry>): Boolean = runCatching {
        // 走 DurableStore：先 fsync 再原子改名，并留一份 .prev。
        // 词典和配置一样，重启丢了用户就得从头再配一遍。
        val ok = DurableStore.write(context, UserDict.FILE_NAME, UserDict.format(entries))
        if (ok) version.incrementAndGet()
        ok
    }.getOrElse {
        Log.e(TAG, "用户词典写入失败", it)
        false
    }

    /** 新增或覆盖一条。键相同就替换，不产生重复条目。 */
    fun upsert(context: Context, entry: SymbolDict.Entry): Boolean {
        val keep = load(context).filterNot { it.key.contentEquals(entry.key) }
        return save(context, keep + entry)
    }

    /** 删掉一条自定义读法，该字符回到内置读法。 */
    fun remove(context: Context, key: IntArray): Boolean {
        val all = load(context)
        val keep = all.filterNot { it.key.contentEquals(key) }
        if (keep.size == all.size) return false
        return save(context, keep)
    }

    fun find(context: Context, key: IntArray): SymbolDict.Entry? =
        load(context).firstOrNull { it.key.contentEquals(key) }

    /** 导入：合并到现有条目上，同键以导入的为准。返回新增/覆盖了多少条。 */
    fun importFrom(context: Context, text: String): Pair<Int, List<UserDict.Problem>> {
        val parsed = UserDict.parse(text)
        if (parsed.entries.isEmpty()) return 0 to parsed.problems
        val incoming = parsed.entries.associateBy { it.key.joinToString(" ") }
        val kept = load(context).filterNot { incoming.containsKey(it.key.joinToString(" ")) }
        return if (save(context, kept + parsed.entries)) {
            parsed.entries.size to parsed.problems
        } else {
            0 to parsed.problems
        }
    }

    fun exportText(context: Context): String = UserDict.format(load(context))
}
