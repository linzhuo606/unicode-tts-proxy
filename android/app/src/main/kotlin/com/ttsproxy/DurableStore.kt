package com.ttsproxy

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 掉电安全的小文件读写。
 *
 * ## 为什么不用 SharedPreferences
 *
 * SharedPreferences 的 `apply()` 每次都会**重写整个文件**，而且是异步的。
 * 重启手机时进程被直接杀掉，正好撞上一次重写，Android 会回退到 `.bak`；
 * 万一 `.bak` 也在半途，整个文件就空了——文件里的东西**一起**丢，
 * 用户看到的就是「重启之后引擎选择和统计一起没了」。
 *
 * 这里三件事保证不丢：
 *  1. 先写临时文件，`fsync` 把内容真正落盘，再原子改名。掉电要么是旧内容要么是新内容，
 *     没有写了一半的中间态。（Java 拿不到目录的文件描述符，没法 fsync 目录项；
 *     好在内容已经落盘，改名在 ext4/f2fs 的默认挂载参数下是有序的。）
 *  2. 改名前把旧文件留成 `.prev`。万一新内容自己是坏的（磁盘满、写了半截），
 *     下次启动还能从旧副本里把配置捞回来。
 *  3. 读不出来就当空，绝不抛异常——存储出问题最多丢配置，不能连累发声。
 *
 * 文件都放在**设备保护存储**里，和配置同一个地方：服务声明了 directBootAware，
 * 开机解锁前就会被绑定，那时凭据加密存储还读不到。
 */
object DurableStore {

    private const val TAG = "DurableStore"

    fun file(context: Context, name: String): File =
        File(Prefs.storageContext(context).filesDir, name)

    fun read(context: Context, name: String): String? = runCatching {
        val f = file(context, name)
        if (f.exists() && f.length() > 0) return@runCatching f.readText(Charsets.UTF_8)
        // 主文件不在或是空的，试试上一次写入留下的旧副本
        val prev = File(f.parentFile, "$name.prev")
        if (prev.exists() && prev.length() > 0) prev.readText(Charsets.UTF_8) else null
    }.getOrElse {
        Log.w(TAG, "读取 $name 失败，当作没有", it)
        null
    }

    /**
     * 原子写入。**成功返回 true**。
     *
     * 保留一份 `.prev`：即使新内容本身是坏的（比如磁盘满写了半截），
     * 下次启动还能从旧副本里把配置捞回来。
     */
    // 合成线程和界面线程可能同时写同一份镜像，共用一个 .tmp，必须串行
    @Synchronized
    fun write(context: Context, name: String, text: String): Boolean = runCatching {
        val f = file(context, name)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            // 这一行是关键：不 sync 的话内容还在页缓存里，掉电就没了
            out.fd.sync()
        }
        if (f.exists()) {
            val prev = File(f.parentFile, "$name.prev")
            if (prev.exists()) prev.delete()
            f.renameTo(prev)
        }
        tmp.renameTo(f)
    }.getOrElse {
        Log.e(TAG, "写入 $name 失败", it)
        false
    }
}
