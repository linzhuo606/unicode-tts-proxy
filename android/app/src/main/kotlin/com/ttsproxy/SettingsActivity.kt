package com.ttsproxy

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

/**
 * 设置界面。用标准的 PreferenceFragmentCompat 而不是自绘控件——TalkBack 对它的
 * 支持最好，盲人用户必须能纯靠听完成首次配置。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val mainHandler = Handler(Looper.getMainLooper())
        private var speaker: SelfSpeaker? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // 和服务用同一份设备保护存储，否则锁屏状态下服务读不到配置
            preferenceManager.setStorageDeviceProtected()
            preferenceManager.sharedPreferencesName = Prefs.NAME
            setPreferencesFromResource(R.xml.preferences, rootKey)

            speaker = SelfSpeaker(requireContext())

            // PreferenceFragmentCompat 直接写 SharedPreferences，绕开了 Prefs 的写入口，
            // 所以在这里挂监听，用户改完立刻同步一份掉电安全的镜像
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(mirrorListener)

            populateEngines()

            findPreference<Preference>("test_speak")?.setOnPreferenceClickListener {
                if (Prefs.downstreamEngine(requireContext()) == null) {
                    toast(getString(R.string.toast_no_engine))
                } else {
                    speaker?.speak(getString(R.string.sample_text))
                }
                true
            }

            findPreference<Preference>("open_dict")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DictActivity::class.java))
                true
            }

            findPreference<Preference>("open_debug")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DebugActivity::class.java))
                true
            }

            findPreference<Preference>("open_startup_manager")?.setOnPreferenceClickListener {
                openStartupManager()
                true
            }
        }

        /**
         * 填充下游引擎列表。读各个引擎的名字要加载别的应用的资源，所以放到后台线程。
         *
         * 选中之后不用在这里做什么：服务那边监听着这项设置，一变就开始连新引擎，
         * 下面那句确认语会等新引擎连好、由它来念。
         *
         * 列表为空基本只有一个原因：manifest 里漏了 <queries> 声明。
         */
        private fun populateEngines() {
            val pref = findPreference<ListPreference>(Prefs.KEY_DOWNSTREAM) ?: return
            pref.summary = getString(R.string.pref_downstream_summary)
            // 列表没加载好之前点开会直接崩（ListPreference 要求 entries 非空），先关上
            pref.isEnabled = false

            // requireContext() 必须在主线程取好：后台线程里 fragment 一旦分离就会抛，
            // 而后台线程的异常会杀掉整个进程
            val context = requireContext().applicationContext
            Thread {
                val engines = runCatching { DownstreamEngine.availableEngines(context) }
                    .getOrElse { emptyList() }
                // Kotlin 的数组是不变的：Array<String> 赋不进 Array<CharSequence>，
                // 必须显式建成 CharSequence 数组
                val labels: Array<CharSequence> = Array(engines.size) { engines[it].label }
                val values: Array<CharSequence> = Array(engines.size) { engines[it].name }
                mainHandler.post {
                    if (!isAdded) return@post
                    if (engines.isEmpty()) {
                        pref.isEnabled = false
                        pref.summary = getString(R.string.pref_downstream_empty)
                        return@post
                    }
                    pref.entries = labels
                    pref.entryValues = values
                    pref.isEnabled = true
                    updateEngineSummary(pref)
                    pref.setOnPreferenceChangeListener { _, newValue ->
                        val pkg = newValue as? String
                        mainHandler.post announce@{
                            if (!isAdded) return@announce
                            updateEngineSummary(pref, pkg)
                            // 配置完成时主动朗读确认，让用户当场知道链路通了
                            val label = engines.firstOrNull { it.name == pkg }?.label ?: pkg
                            speaker?.speak(getString(R.string.toast_engine_ready, label))
                        }
                        true
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        /**
         * 跳到系统的「应用启动管理」。华为的入口是手机管家里的一个 Activity，不同机型类名不同，
         * 挨个试；都没有就退到本应用的应用信息页，那里至少能关电池优化。
         *
         * 真机上查实过：开机后系统会把本进程冻住几秒，朗读读到一半没声；卸载重装会把这里的
         * 手动管理设置清空，退回自动管理，问题就复发。
         */
        private fun openStartupManager() {
            val context = requireContext()
            val candidates = listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.bootstart.BootStartActivity",
            )
            for ((pkg, cls) in candidates) {
                val intent = Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(intent); true }.getOrDefault(false)) return
            }
            toast(getString(R.string.toast_startup_manager_missing))
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        private fun updateEngineSummary(pref: ListPreference, override: String? = null) {
            val selected = override ?: Prefs.downstreamEngine(requireContext())
            val index = pref.entryValues?.indexOfFirst { it.toString() == selected } ?: -1
            pref.summary = if (index >= 0) {
                pref.entries?.getOrNull(index)?.toString()
            } else {
                getString(R.string.pref_downstream_none)
            }
        }

        private fun toast(message: String) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }

        /** 保存成强引用：SharedPreferences 只持有监听器的弱引用，临时对象会被回收掉。 */
        private val mirrorListener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                context?.let { runCatching { Prefs.mirror(it) } }
            }

        override fun onDestroy() {
            preferenceManager.sharedPreferences
                ?.unregisterOnSharedPreferenceChangeListener(mirrorListener)
            speaker?.shutdown()
            speaker = null
            super.onDestroy()
        }
    }
}
