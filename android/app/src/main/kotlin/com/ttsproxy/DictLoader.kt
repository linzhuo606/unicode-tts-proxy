package com.ttsproxy

import android.content.Context
import android.util.Log
import com.ttsproxy.core.SymbolDict

/**
 * 加载符号词典：内置表（assets）叠加用户自定义表。
 *
 * 内置词典放在 APK 的 assets 里，锁屏状态下也读得到（不受凭据加密存储影响），
 * 这对 directBootAware 的服务很重要；用户词典放在设备保护存储里，同理。
 *
 * 加载失败绝不能让引擎失声——降级成空词典，只是不做替换而已。
 */
object DictLoader {

    private const val TAG = "DictLoader"
    private const val ASSET_NAME = "symbols.tsv"

    /** 内置表解析一次就缓存住，用户改词典时只需要重叠加，不必重读 600KB 的 assets。 */
    @Volatile
    private var builtin: List<SymbolDict.Entry>? = null

    @Volatile
    private var cached: SymbolDict? = null

    /** 缓存对应的用户词典版本。和 [UserDictStore.version] 不一致就重建。 */
    @Volatile
    private var cachedUserVersion = -1

    /** 缓存对应的音标读法档位。用户改了这个开关也要重建。 */
    @Volatile
    private var cachedBraille = false

    fun load(context: Context): SymbolDict {
        val wanted = UserDictStore.version.get()
        val braille = Prefs.ipaAsBraille(context)
        val hit = cached
        if (hit != null && cachedUserVersion == wanted && cachedBraille == braille) return hit
        return synchronized(this) {
            val again = cached
            if (again != null && cachedUserVersion == wanted && cachedBraille == braille) {
                return@synchronized again
            }
            build(context, wanted, braille).also {
                cached = it
                cachedUserVersion = wanted
                cachedBraille = braille
            }
        }
    }

    private fun build(context: Context, userVersion: Int, braille: Boolean): SymbolDict {
        val loaded = builtin ?: loadBuiltin(context).also { builtin = it }
        // 音标读盲文点位是整体切换，在这里换掉简洁读法，流水线本身不必知道有这回事
        val base = if (braille) SymbolDict.briefAsBraille(loaded) else loaded
        val user = UserDictStore.load(context)
        if (user.isNotEmpty()) Log.i(TAG, "用户词典 " + user.size + " 条（版本 " + userVersion + "）")
        // 用户层排在前面 —— 同一个键上覆盖内置读法
        return SymbolDict.of(listOf(user, base))
    }

    private fun loadBuiltin(context: Context): List<SymbolDict.Entry> = try {
        context.assets.open(ASSET_NAME).use { input ->
            input.bufferedReader(Charsets.UTF_8).useLines { lines ->
                SymbolDict.parseEntries(lines).also {
                    Log.i(TAG, "内置词典载入 " + it.size + " 条")
                }
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "内置词典载入失败，降级为不替换（引擎仍可正常发声）", t)
        emptyList()
    }

    /** 用户词典或音标读法档位改动后调用，下次 [load] 会重建。 */
    fun invalidate() {
        cachedUserVersion = -1
    }
}
