package com.ttsproxy

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.UserManager

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
        // 日志最先起：开机后那几分钟的故障，用户一打开界面就消失，只能靠进程一起来就记
        runCatching { Tlog.init(this) }
        // 存储层出问题最多是配置没补回来，绝不能让它拦住进程启动——那会让引擎彻底起不来
        runCatching { Prefs.restoreFromMirror(this) }
        runCatching { removeRetiredFiles() }
        // 记下本次开机后这是第几次拉起进程：进程被杀过，诊断里就能念出来
        Diagnostics.noteProcessStart(this)
        runCatching {
            val um = getSystemService(Context.USER_SERVICE) as UserManager
            Tlog.i(
                TAG,
                "本次开机后第 " + Diagnostics.launchesThisBoot + " 次启动，用户已解锁=" + um.isUserUnlocked +
                    (Prefs.restoredFromMirror?.let { "，从备份补回=" + it } ?: ""),
            )
            if (!um.isUserUnlocked) {
                // 解锁那一刻记一笔：开机故障要和「解锁前 / 解锁后」对得上
                registerReceiver(
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            Tlog.i(TAG, "收到 ACTION_USER_UNLOCKED，用户已解锁")
                            runCatching { unregisterReceiver(this) }
                        }
                    },
                    IntentFilter(Intent.ACTION_USER_UNLOCKED),
                )
            }
        }.onFailure { Tlog.w(TAG, "记录启动状态失败", it) }
        runCatching { watchAudioDevices() }.onFailure { Tlog.w(TAG, "监听音频设备失败", it) }
    }

    /**
     * 音频输出设备的接入和拔出。真机上抓到过音轨活着却几秒不吃数据，最常见的原因就是
     * 输出设备在切换（开机后蓝牙耳机重连），系统要把所有音轨重建一遍。记下来好对时间。
     */
    private fun watchAudioDevices() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                    Tlog.i(TAG, "音频设备接入: " + describe(added))
                }

                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
                    Tlog.i(TAG, "音频设备拔出: " + describe(removed))
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

    private fun describe(devices: Array<out AudioDeviceInfo>): String =
        devices.filter { it.isSink }.joinToString("，") { deviceType(it.type) + "(" + it.productName + ")" }

    private fun deviceType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "扬声器"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙A2DP"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        23 -> "助听器"
        26 -> "蓝牙LE耳机"
        27 -> "蓝牙LE音箱"
        30 -> "蓝牙LE广播"
        else -> "类型" + type
    }

    private companion object {
        const val TAG = "App"
    }

    /** 吞吐统计已经移除，从旧版本升上来的机器上还留着它的文件，顺手清掉。 */
    private fun removeRetiredFiles() {
        val dir = Prefs.storageContext(this).filesDir
        for (name in listOf("usage.tsv", "usage.tsv.prev", "usage.tsv.tmp")) {
            java.io.File(dir, name).takeIf { it.exists() }?.delete()
        }
    }
}
