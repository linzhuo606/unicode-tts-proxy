package com.ttsproxy

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ttsproxy.core.SymbolDict
import com.ttsproxy.core.UserDict
import com.ttsproxy.core.Verbosity

/**
 * 自定义词典界面。
 *
 * 交互刻意做成「查 → 改 → 存」三步，每一步的结果都用 [announce] 播报出来：
 * 盲人用户看不到界面上多出来的一行字，只有主动播报才知道操作成没成。
 *
 * 所有控件都是标准控件，不自绘——TalkBack 对标准控件的支持最好。
 */
class DictActivity : AppCompatActivity() {

    private lateinit var keyInput: EditText
    private lateinit var briefInput: EditText
    private lateinit var verboseInput: EditText
    private lateinit var status: TextView
    private lateinit var listView: TextView

    /** 当前查询的码位序列。查过之后保存、屏蔽、删除都针对它。 */
    private var currentKey: IntArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dict)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        keyInput = findViewById(R.id.dict_key)
        briefInput = findViewById(R.id.dict_brief)
        verboseInput = findViewById(R.id.dict_verbose)
        status = findViewById(R.id.dict_status)
        listView = findViewById(R.id.dict_list)

        findViewById<Button>(R.id.dict_lookup).setOnClickListener { lookup() }
        findViewById<Button>(R.id.dict_save).setOnClickListener { save() }
        findViewById<Button>(R.id.dict_mute).setOnClickListener { mute() }
        findViewById<Button>(R.id.dict_delete).setOnClickListener { delete() }
        findViewById<Button>(R.id.dict_export).setOnClickListener { startExport() }
        findViewById<Button>(R.id.dict_import).setOnClickListener { startImport() }

        refreshList()
    }

    // ---- 查 ----------------------------------------------------------------

    private fun lookup() {
        val raw = keyInput.text?.toString().orEmpty()
        val key = UserDict.parseKey(raw)
        if (key == null || key.isEmpty()) {
            currentKey = null
            report(getString(R.string.dict_bad_key))
            return
        }
        currentKey = key

        val mine = UserDictStore.find(this, key)
        val builtin = builtinEntry(key)

        briefInput.setText(if (mine != null && !mine.muted) mine.brief else builtin?.brief.orEmpty())
        verboseInput.setText(
            when {
                mine != null && !mine.muted -> if (mine.verbose == mine.brief) "" else mine.verbose
                builtin != null && builtin.verbose != builtin.brief -> builtin.verbose
                else -> ""
            }
        )

        val where = UserDict.describeKey(key)
        val text = buildString {
            append(where).append('\n')
            when {
                mine != null && mine.muted -> append(getString(R.string.dict_state_muted))
                mine != null -> append(getString(R.string.dict_state_custom, mine.brief, mine.verbose))
                else -> append(getString(R.string.dict_state_none_custom))
            }
            append('\n')
            if (builtin != null) {
                append(getString(R.string.dict_state_builtin, builtin.brief, builtin.verbose))
            } else {
                append(getString(R.string.dict_state_no_builtin))
            }
        }
        report(text)
    }

    /**
     * 只查内置表。不能直接查合并后的词典——那样用户的自定义条目会盖住内置读法，
     * 界面上就永远看不到「原本读什么」，也就没法判断自己该不该改回去。
     */
    private fun builtinEntry(key: IntArray): SymbolDict.Entry? {
        val merged = DictLoader.load(this)
        val hit = merged.match(key, 0) ?: return null
        if (hit.key.size != key.size) return null
        if (!hit.fromUser) return hit
        // 命中的是用户条目，再从内置层里找同键的那一条
        return merged.entries().firstOrNull { !it.fromUser && it.key.contentEquals(key) }
    }

    // ---- 改 ----------------------------------------------------------------

    private fun save() {
        val key = currentKey ?: run { report(getString(R.string.dict_lookup_first)); return }
        val brief = UserDict.oneLine(briefInput.text?.toString().orEmpty()).trim()
        if (brief.isEmpty()) {
            report(getString(R.string.dict_need_brief))
            return
        }
        if (brief == UserDict.MUTE) {
            mute()
            return
        }
        val verbose = UserDict.oneLine(verboseInput.text?.toString().orEmpty()).trim()
        val entry = SymbolDict.Entry(
            key = key,
            kind = builtinEntry(key)?.kind ?: SymbolDict.Kind.guess(key),
            brief = brief,
            verbose = verbose,
            muted = false,
            fromUser = true,
        )
        if (UserDictStore.upsert(this, entry)) {
            DictLoader.invalidate()
            refreshList()
            // 存完立刻念一遍改后的效果，让用户当场确认改对了没有
            report(getString(R.string.dict_saved, preview(key)))
        } else {
            report(getString(R.string.dict_save_failed))
        }
    }

    private fun mute() {
        val key = currentKey ?: run { report(getString(R.string.dict_lookup_first)); return }
        val entry = SymbolDict.Entry(
            key = key,
            kind = builtinEntry(key)?.kind ?: SymbolDict.Kind.guess(key),
            brief = UserDict.MUTE,
            verbose = UserDict.MUTE,
            muted = true,
            fromUser = true,
        )
        if (UserDictStore.upsert(this, entry)) {
            DictLoader.invalidate()
            refreshList()
            report(getString(R.string.dict_muted, UserDict.describeKey(key)))
        } else {
            report(getString(R.string.dict_save_failed))
        }
    }

    private fun delete() {
        val key = currentKey ?: run { report(getString(R.string.dict_lookup_first)); return }
        if (UserDictStore.remove(this, key)) {
            DictLoader.invalidate()
            refreshList()
            report(getString(R.string.dict_deleted, preview(key)))
        } else {
            report(getString(R.string.dict_nothing_to_delete))
        }
    }

    /** 改完之后这个字符现在会被读成什么——直接跑一遍真实流水线，不做推断。 */
    private fun preview(key: IntArray): String {
        val text = String(key, 0, key.size)
        val out = TextPipelineHolder.get(this).transformSafe(text, Verbosity.BRIEF)
        return out.ifBlank { getString(R.string.dict_preview_silent) }
    }

    // ---- 导入导出 -----------------------------------------------------------

    private fun startExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, UserDict.FILE_NAME)
        }
        runCatching { startActivityForResult(intent, REQ_EXPORT) }
            .onFailure { report(getString(R.string.dict_no_file_picker)) }
    }

    private fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // 有些文件管理器不把 .tsv 报成 text/plain，放宽到全部类型免得用户选不中
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQ_IMPORT) }
            .onFailure { report(getString(R.string.dict_no_file_picker)) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT -> doExport(uri)
            REQ_IMPORT -> doImport(uri)
        }
    }

    private fun doExport(uri: Uri) {
        val result = runCatching {
            contentResolver.openOutputStream(uri)?.use {
                it.write(UserDictStore.exportText(this).toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("打不开目标文件")
        }
        if (result.isSuccess) {
            report(getString(R.string.dict_exported, UserDictStore.load(this).size))
        } else {
            report(getString(R.string.dict_export_failed))
        }
    }

    private fun doImport(uri: Uri) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (text == null) {
            report(getString(R.string.dict_import_failed))
            return
        }
        val (count, problems) = UserDictStore.importFrom(this, text)
        DictLoader.invalidate()
        refreshList()
        val message = StringBuilder(getString(R.string.dict_imported, count))
        if (problems.isNotEmpty()) {
            message.append('\n').append(getString(R.string.dict_import_problems, problems.size))
            // 只报前三条，念太多用户记不住；完整清单在导出的文件里能看到
            for (p in problems.take(3)) {
                message.append('\n').append(getString(R.string.dict_import_problem_line, p.lineNumber, p.reason))
            }
        }
        report(message.toString())
    }

    // ---- 列表与播报 ---------------------------------------------------------

    private fun refreshList() {
        val result = UserDictStore.loadWithProblems(this)
        listView.text = if (result.entries.isEmpty()) {
            getString(R.string.dict_list_empty)
        } else {
            buildString {
                append(getString(R.string.dict_list_count, result.entries.size)).append('\n')
                for (e in result.entries.sortedBy { it.key.firstOrNull() ?: 0 }) {
                    append(String(e.key, 0, e.key.size))
                    append(' ')
                    append(if (e.muted) getString(R.string.dict_list_muted) else e.brief)
                    append('\n')
                }
            }
        }
    }

    private fun report(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
        announce(text)
    }

    /**
     * 主动播报。TalkBack 不会自动念一个已经在屏幕上的 TextView 的内容变化，
     * 而这个界面的每一步反馈都只体现在这段文字里。
     */
    private fun announce(text: String) {
        status.announceForAccessibility(text)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private companion object {
        const val REQ_EXPORT = 1
        const val REQ_IMPORT = 2
    }
}

/** 界面侧要用合并后的流水线做预览，和服务共用同一份缓存。 */
object TextPipelineHolder {
    fun get(context: android.content.Context) =
        com.ttsproxy.core.TextPipeline(DictLoader.load(context))
}
