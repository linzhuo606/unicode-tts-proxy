package com.ttsproxy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 跳到系统设置里的几个页面。首次设置向导和设置界面都要用，放一起。
 *
 * 每个方法都返回有没有跳成功；跳不成功的兜底页面也在这里选好，调用方只管念结果。
 */
object SystemPages {

    /**
     * 系统的「文字转语音输出」页面，用户在那里把首选引擎改成本应用。
     * 不同厂商的入口不一样，挨个试；都没有就退到无障碍设置。
     */
    fun openTtsSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$TextToSpeechSettingsActivity"),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
        return candidates.any { start(context, it) }
    }

    /**
     * 华为等系统的「应用启动管理」。本应用在那里必须是手动管理并放开三项，
     * 否则开机后会被系统冻住几秒，朗读读到一半没声；每次卸载重装都会被清回自动管理。
     * 不同机型类名不同，挨个试；都没有就退到本应用的应用信息页。
     * 返回 true 表示跳到了启动管理本身，false 表示只跳到了兜底页。
     */
    fun openStartupManager(context: Context): Boolean {
        val candidates = listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.bootstart.BootStartActivity",
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        )
        for ((pkg, cls) in candidates) {
            if (start(context, Intent().setClassName(pkg, cls))) return true
        }
        openAppInfo(context)
        return false
    }

    /** 本应用的应用信息页，至少能在那里关电池优化。 */
    fun openAppInfo(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:" + context.packageName)),
    )

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
}
