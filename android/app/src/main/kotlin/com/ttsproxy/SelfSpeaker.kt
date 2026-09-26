package com.ttsproxy

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.concurrent.atomic.AtomicLong

/**
 * 通过**我们自己的引擎**朗读一段文本，用于试听和调试。
 *
 * 这条路是 应用 → 本引擎 → 下游引擎，走的正是 TalkBack 走的那条链路，
 * 所以试听能真实反映用户实际听到的效果。注意这不是自引用——自引用指的是
 * 把本引擎又设成本引擎的下游。
 */
class SelfSpeaker(context: Context) {

    private val appContext = context.applicationContext
    private val ids = AtomicLong()

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val pending = ArrayList<String>()

    init {
        runCatching { connect() }
    }

    private fun connect() {
        tts = TextToSpeech(appContext, { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                synchronized(pending) {
                    pending.forEach { speakNow(it) }
                    pending.clear()
                }
            }
        }, appContext.packageName)
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (ready) {
            speakNow(text)
        } else {
            synchronized(pending) { pending.add(text) }
        }
    }

    private fun speakNow(text: String) {
        val engine = tts ?: return
        Tlog.i("SelfSpeaker", "本应用试听 " + text.length + " 字（QUEUE_FLUSH）")
        runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "selftest-" + ids.incrementAndGet())
        }
    }

    fun shutdown() {
        Tlog.i("SelfSpeaker", "本应用试听客户端 stop + shutdown")
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
