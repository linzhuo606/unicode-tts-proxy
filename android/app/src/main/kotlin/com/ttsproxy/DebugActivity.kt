package com.ttsproxy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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

        Tlog.i("DebugActivity", "检查朗读效果 界面打开")
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

        // 日志的三个按钮。复制成功要主动念出来，否则用户不知道按成没有
        val copyLog = findViewById<Button>(R.id.copy_log)
        copyLog.setOnClickListener {
            val text = Tlog.snapshot()
            if (text.isBlank()) {
                announce(copyLog, getString(R.string.debug_log_empty))
                return@setOnClickListener
            }
            val ok = runCatching {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ttsproxy-log", text))
            }.isSuccess
            val lines = text.count { it == '\n' }
            announce(copyLog, if (ok) getString(R.string.debug_log_copied, lines) else "复制失败")
        }
        findViewById<Button>(R.id.share_log).setOnClickListener {
            // 发文件而不是发文本：剪贴板那条路有大小上限，文件没有，开机那一段不会被截掉
            val file = Tlog.exportFile(this)
            if (file == null || file.length() == 0L) {
                announce(it, getString(R.string.debug_log_empty))
                return@setOnClickListener
            }
            runCatching {
                val uri = FileProvider.getUriForFile(this, packageName + ".files", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Unicode 朗读中转 日志")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, getString(R.string.debug_share_log)))
            }.onFailure { e -> Toast.makeText(this, "分享失败：" + e.message, Toast.LENGTH_LONG).show() }
        }
        findViewById<Button>(R.id.clear_log).setOnClickListener {
            Tlog.clear()
            announce(it, getString(R.string.debug_log_cleared))
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
            Tlog.i("DebugActivity", "点了 处理并朗读，原文 " + raw.length + " 字，处理后 " + processed.length + " 字")
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

    /** 操作结果既显示在 Toast 里，也主动念出来；只靠 Toast 读屏用户经常听不到。 */
    private fun announce(view: View, message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        view.announceForAccessibility(message)
    }

    private companion object {
        const val DIAGNOSTICS_REFRESH_MS = 3000L
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        Tlog.i("DebugActivity", "检查朗读效果 界面关闭")
        speaker?.shutdown()
        speaker = null
        super.onDestroy()
    }
}
