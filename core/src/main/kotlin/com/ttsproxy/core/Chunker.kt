package com.ttsproxy.core

import java.text.BreakIterator
import java.util.Locale

/**
 * 分块。有两个互相独立的动机，别混淆：
 *
 * **硬上限**：下游引擎的 `TextToSpeech.getMaxSpeechInputLength()` 是 4000，超了之后
 * 框架的 `isValid()` 返回 false，`synthesizeToFile` **静默失败**——不报错、不回调，
 * 只是没声音。而我们的替换是**膨胀**变换（`∫` → 「积分」，`🎉` → 「派对拉炮」），
 * 3999 字符的输入完全可能膨胀到 6000。所以长度检查必须放在替换**之后**。
 *
 * **软上限**：为了降低首字延迟做流水线，也为了**守住音频缓冲**。TalkBack 自己已经做过
 * 句子级分块，绝大多数播报只有几十个字符，所以短文本一律整段直通——分块的每次请求
 * 都有队列往返开销，短文本上分块反而更慢。
 *
 * 软上限对**每一块**都必须成立，不能只是「尽量」：一块交给下游之后，下游会一口气把整块
 * 合成完（synthesizeToFile 这条路没有播放背压），音频全部压在我们这边等着播，块越长压得越多。
 * 所以句号断不开的长句要在逗号、顿号、分号处再切，还断不开才按长度硬切。
 *
 * 这条是拿故障换来的：词典曾经把 `。` 换成了「句号」两个字，这里的按句切分随之失效，
 * 一段长文成了一整块，音频撑爆缓冲，长文朗读乱成一团。
 */
object Chunker {

    /** 下游 `TextToSpeech.getMaxSpeechInputLength()` 的值。 */
    const val HARD_LIMIT = 4000

    /** 超过这个长度才分块；分出来的每一块也都不超过它。 */
    const val SOFT_LIMIT = 250

    fun split(
        text: String,
        hardLimit: Int = HARD_LIMIT,
        softLimit: Int = SOFT_LIMIT,
        locale: Locale = Locale.CHINESE,
    ): List<String> {
        if (text.isEmpty()) return emptyList()
        if (text.length <= softLimit) return listOf(text)

        val target = minOf(softLimit, hardLimit)
        val chunks = ArrayList<String>()
        val current = StringBuilder(target + 64)

        for (sentence in sentences(text, locale)) {
            // 句子本身就超长时先拆成分句，再和别的句子一起装箱
            val pieces = if (sentence.length > target) clauses(sentence, target) else listOf(sentence)
            for (piece in pieces) {
                if (current.isNotEmpty() && current.length + piece.length > target) {
                    flush(current, chunks)
                }
                current.append(piece)
            }
        }
        flush(current, chunks)
        return chunks
    }

    private fun flush(buf: StringBuilder, out: MutableList<String>) {
        if (buf.isNotEmpty()) {
            out.add(buf.toString())
            buf.setLength(0)
        }
    }

    private fun sentences(text: String, locale: Locale): List<String> {
        val it = BreakIterator.getSentenceInstance(locale)
        it.setText(text)
        val out = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            out.add(text.substring(start, end))
            start = end
            end = it.next()
        }
        if (out.isEmpty()) out.add(text)
        return out
    }

    /**
     * 把超长的一句拆成不超过 [limit] 的片段。
     *
     * 优先切在分句标点（逗号、顿号、分号、冒号）**之后**：标点留在前一段的末尾，
     * 下游看到的是一个以逗号收尾的片段，停顿落在该停的地方。
     * 某一段连分句标点都没有、仍然超长，才退回按长度硬切。
     */
    private fun clauses(sentence: String, limit: Int): List<String> {
        val parts = ArrayList<String>()
        var start = 0
        for (i in sentence.indices) {
            if (isClauseBreak(sentence[i])) {
                parts.add(sentence.substring(start, i + 1))
                start = i + 1
            }
        }
        if (start < sentence.length) parts.add(sentence.substring(start))
        return parts.flatMap { if (it.length > limit) hardSplit(it, limit) else listOf(it) }
    }

    private fun isClauseBreak(c: Char): Boolean = when (c) {
        '，', '、', '；', '：', ',', ';', ':' -> true
        else -> false
    }

    /**
     * 最后的兜底：按长度硬切。绝不切在代理对中间（那会产出孤立代理项）；
     * 窗口后半截里有空格就切在空格后面，免得把一个英文单词劈成两半。
     */
    private fun hardSplit(text: String, limit: Int): List<String> {
        val out = ArrayList<String>((text.length / limit) + 1)
        var start = 0
        while (start < text.length) {
            var end = minOf(start + limit, text.length)
            if (end < text.length) {
                val space = text.lastIndexOf(' ', end - 1)
                if (space >= start + limit / 2) end = space + 1
            }
            if (end < text.length && Character.isLowSurrogate(text[end])) end--
            if (end <= start) end = minOf(start + limit, text.length)
            out.add(text.substring(start, end))
            start = end
        }
        return out
    }
}
