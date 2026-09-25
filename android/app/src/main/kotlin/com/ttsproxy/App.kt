package com.ttsproxy

import android.app.Application

/**
 * 只做一件事：**进程一起来就先把配置补齐**。
 *
 * 需要一个统一入口，是因为进程可能从任何地方被拉起来——TalkBack 绑定 TTS 服务、
 * 用户点开设置、系统查询引擎信息。哪个先来都不一定，而它们都会读配置。
 * 放在 Application.onCreate 里能保证「补配置」发生在任何一次读取之前，
 * 且整个进程只做一次。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 存储层出问题最多是配置没补回来，绝不能让它拦住进程启动——那会让引擎彻底起不来
        runCatching { Prefs.restoreFromMirror(this) }
        runCatching { removeRetiredFiles() }
    }

    /** 吞吐统计已经移除，从旧版本升上来的机器上还留着它的文件，顺手清掉。 */
    private fun removeRetiredFiles() {
        val dir = Prefs.storageContext(this).filesDir
        for (name in listOf("usage.tsv", "usage.tsv.prev", "usage.tsv.tmp")) {
            java.io.File(dir, name).takeIf { it.exists() }?.delete()
        }
    }
}
