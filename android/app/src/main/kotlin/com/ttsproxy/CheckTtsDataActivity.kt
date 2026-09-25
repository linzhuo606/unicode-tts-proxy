package com.ttsproxy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * 响应 `android.speech.tts.engine.CHECK_TTS_DATA`。
 *
 * 注意 action 的真名是 CHECK_TTS_DATA —— `CHECK_VOICE_DATA_PASS` 只是**结果码**的
 * 常量名，两者极容易混淆。
 *
 * 系统是拿到 PASS 之后才写 Settings.Secure.TTS_DEFAULT_SYNTH：这个 Activity 不返回
 * 或者返回 FAIL，用户就**切不成**我们的引擎。所以它必须瞬时返回、无界面、绝不弹窗。
 */
class CheckTtsDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 语言码是 ISO-3 的 lang-COUNTRY-variant，不是 BCP-47。
        // extra 的键名就是这两个字面量（对应 TextToSpeech.Engine 里的
        // EXTRA_AVAILABLE_VOICES / EXTRA_UNAVAILABLE_VOICES）。
        val data = Intent().apply {
            putStringArrayListExtra("availableVoices", arrayListOf("zho-CHN", "eng-USA"))
            putStringArrayListExtra("unavailableVoices", arrayListOf())
        }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, data)
        finish()
    }
}
