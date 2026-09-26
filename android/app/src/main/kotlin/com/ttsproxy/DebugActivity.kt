package com.ttsproxy

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ttsproxy.core.TextPipeline
import com.ttsproxy.core.Verbosity

/**
 * 检查朗读效果：输入任意文本，看到处理后的结果，并通过完整链路试听。
 *
 * 这个界面的价值在于让用户能**准确报告**哪个字符读错了——盲人用户没法截图，
 * 但可以把处理后的文本复制出来发给我们。
 */
class DebugActivity : AppCompatActivity() {

    private lateinit var pipeline: TextPipeline
    private var speaker: SelfSpeaker? = null
    private var diagnostics: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        pipeline = TextPipeline(DictLoader.load(this))
        speaker = SelfSpeaker(this)

        val input = findViewById<EditText>(R.id.input)
        val result = findViewById<TextView>(R.id.result)
        val button = findViewById<Button>(R.id.process)
        diagnostics = findViewById(R.id.diagnostics)
        findViewById<Button>(R.id.reset_diagnostics).setOnClickListener {
            Diagnostics.reset()
            refreshDiagnostics()
        }

        input.setText(getString(R.string.sample_text))

        button.setOnClickListener {
            val raw = input.text?.toString().orEmpty()
            if (raw.isBlank()) {
                result.text = getString(R.string.debug_empty)
                result.announceForAccessibility(result.text)
                return@setOnClickListener
            }
            val verbosity = if (Prefs.detailSingleEmoji(this) && pipeline.isSingleEntry(raw)) {
                Verbosity.VERBOSE
            } else {
                Prefs.verbosity(this)
            }
            val processed = pipeline.transformSafe(raw, verbosity)
            result.text = processed
            speaker?.speak(processed)
            // 朗读完再刷新，这样试听后立刻能听到有没有出问题
            result.postDelayed({ refreshDiagnostics() }, DIAGNOSTICS_REFRESH_MS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDiagnostics()
    }

    /**
     * 把运行计数显示出来。盲人用户没法截图，也未必方便连电脑抓日志，
     * 把这一行念出来就是一份可执行的故障报告。
     */
    private fun refreshDiagnostics() {
        // 第一段是计数，第二段是最近几句各自怎么结束的。「朗读到一半没了」这类问题，
        // 光看计数分不清是谁停的，第二段能直接说出是上游、下游还是我们自己。
        diagnostics?.text = Diagnostics.summary() + "\n\n" + Diagnostics.history()
    }

    private companion object {
        const val DIAGNOSTICS_REFRESH_MS = 3000L
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        speaker?.shutdown()
        speaker = null
        super.onDestroy()
    }
}
