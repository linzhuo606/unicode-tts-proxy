package com.ttsproxy

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * 响应 `android.speech.tts.engine.GET_SAMPLE_TEXT`，供系统设置里的「试听」使用。
 *
 * 不实现的话系统会退回内置文案，不算致命；但我们的示例文本正好可以把三类符号都带上，
 * 用户在系统设置里点一下试听就能立刻听出中转是否生效。
 */
class GetSampleTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = Intent().apply {
            // 键名对应 TextToSpeech.Engine.EXTRA_SAMPLE_TEXT
            putExtra("sampleText", getString(R.string.sample_text))
        }
        setResult(RESULT_OK, data)
        finish()
    }
}
