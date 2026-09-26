package com.ttsproxy

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 首次设置向导。装好后第一次打开本应用时出现，四步走完就能用。
 *
 * 为什么要有它：三件事缺一件引擎就等于没装——选发声引擎、把系统的文字转语音输出改成本应用、
 * 在华为等系统里放开后台。第三件最容易漏，而且每次卸载重装都会被系统清掉，用户很难自己想到。
 *
 * 全部用标准控件、一屏顺着读下来。每一步一个按钮，按完当场念结果，用户不用回头找。
 * 「完成」之后不再出现；设置里可以重新打开。
 */
class OnboardingActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speaker: SelfSpeaker? = null
    private var engines: List<DownstreamEngine.EngineChoice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        speaker = SelfSpeaker(this)

        val engineButton = findViewById<Button>(R.id.pick_engine)
        val engineState = findViewById<TextView>(R.id.engine_state)
        engineButton.isEnabled = false
        refreshEngineState(engineState)

        // 引擎列表要加载别的应用的资源，放后台；加载完再放开按钮，否则空列表会崩
        Thread {
            val found = runCatching { DownstreamEngine.availableEngines(applicationContext) }.getOrElse { emptyList() }
            mainHandler.post {
                if (isFinishing) return@post
                engines = found
                engineButton.isEnabled = found.isNotEmpty()
                if (found.isEmpty()) engineState.text = getString(R.string.pref_downstream_empty)
            }
        }.apply { isDaemon = true }.start()

        engineButton.setOnClickListener { pickEngine(engineState) }

        findViewById<Button>(R.id.open_tts_settings).setOnClickListener {
            val ok = SystemPages.openTtsSettings(this)
            announce(it, getString(if (ok) R.string.onboard_opened_tts else R.string.onboard_open_failed))
        }

        findViewById<Button>(R.id.open_startup).setOnClickListener {
            val direct = SystemPages.openStartupManager(this)
            announce(it, getString(if (direct) R.string.onboard_opened_startup else R.string.toast_startup_manager_missing))
        }

        findViewById<Button>(R.id.test_speak).setOnClickListener {
            if (Prefs.downstreamEngine(this) == null) {
                announce(it, getString(R.string.toast_no_engine))
            } else {
                speaker?.speak(getString(R.string.sample_text))
            }
        }

        findViewById<Button>(R.id.finish).setOnClickListener {
            Prefs.setOnboarded(this, true)
            finish()
        }
    }

    private fun pickEngine(state: TextView) {
        val labels = engines.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.onboard_pick_engine_title)
            .setItems(labels) { _, which ->
                val chosen = engines[which]
                Prefs.setDownstreamEngine(this, chosen.name)
                refreshEngineState(state)
                // 服务监听着这项设置，会立刻去连；确认语等新引擎连好由它来念
                speaker?.speak(getString(R.string.toast_engine_ready, chosen.label))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshEngineState(state: TextView) {
        val selected = Prefs.downstreamEngine(this)
        state.text = if (selected == null) {
            getString(R.string.onboard_engine_none)
        } else {
            val label = engines.firstOrNull { it.name == selected }?.label ?: selected
            getString(R.string.onboard_engine_current, label)
        }
    }

    /** 结果要主动念出来，否则用户不知道按成没有。 */
    private fun announce(view: View, message: String) {
        view.announceForAccessibility(message)
    }

    override fun onDestroy() {
        speaker?.shutdown()
        speaker = null
        super.onDestroy()
    }
}
