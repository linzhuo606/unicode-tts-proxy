package com.ttsproxy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 长文朗读挤成一团的回归测试。
 *
 * 词典曾经把 `。` 换成「句号」两个字、`、` 换成「顿号」：下游引擎看不到断句，句与句之间
 * 不再停顿；分块器也靠句号断句，一段长文变成一整块，音频撑爆缓冲、开始丢数据。
 * 症状和更早一次缓冲丢数据的故障一模一样，用户一听就认出来了。
 *
 * 核心单测平时用的是自带的小词典，测不到「真实词典本身把句号收了」这种事，
 * 所以这里专门拿一份「坏词典」来钉住两道防线：断句标点原样放行、每块不超过软上限。
 */
class LongTextRegressionTest {

    /** 模拟出问题的那份词典：把断句标点收成了读法。 */
    private val badDict = SymbolDict.parse(
        listOf(
            "3002\tS\t句号",
            "3001\tS\t顿号",
            "FF0C\tS\t逗号",
            "300A\tS\t左书名号",
            "300B\tS\t右书名号",
            "201C\tS\t左双引号",
            "0022\tS\t引",
            "2014\tS\t破折号",
            "2026\tS\t省略号",
            "00B7\tS\t间隔号",
            "1F44D\tE\t点赞\t点赞表情",
        ).asSequence()
    )

    private val novel = listOf(
        "清晨的雾气还没有散去，街道两旁的店铺已经陆续开门了。",
        "卖早点的老张一边摆桌椅，一边和邻居打招呼，说今天又是个好天气。",
        "他的妻子在灶台前忙着，锅里的油条翻滚着，香气飘出去很远。",
        "住在巷子尽头的李奶奶拄着拐杖慢慢走过来，她每天都是第一个到的客人。",
        "老张赶紧迎上去，扶她在靠窗的位置坐下，又转身端来一碗热豆浆。",
        "太阳渐渐升高，雾气散了，街上的人也多了起来。",
        "上学的孩子背着书包跑过，骑车上班的年轻人按着车铃，卖菜的小贩吆喝着新到的青菜、萝卜和土豆。",
        "巷子口的那棵老槐树下，几个老人已经摆好了棋盘，一边下棋一边争论着昨天的新闻。",
        "有人说要下雨了，有人说不会，谁也说服不了谁，最后都笑了起来。",
        "这就是这条老街普通的一个早晨，平淡，却让人觉得踏实。",
        "后来城市改造，这条街拆了，老张的早点铺也搬到了很远的新区。",
        "可每当有人提起这条街，大家总会想起那碗豆浆、那两根油条，还有李奶奶慢慢走来的背影。",
    ).joinToString("")

    @Test
    fun `内置词典收了标点也不许替换`() {
        val pipeline = TextPipeline(badDict)
        val text = "你好。世界、再见，《书名》“引着的话”艾萨克·牛顿沉默了……——"
        assertEquals(text, pipeline.transform(text, Verbosity.BRIEF))
        assertEquals(text, pipeline.transform(text, Verbosity.VERBOSE))
        // 同一份词典里的其他条目照常工作——拦的只是断句标点
        assertEquals("点赞", pipeline.transform(cp(THUMBS_UP), Verbosity.BRIEF))
    }

    @Test
    fun `整段只有一个句号也不当成词条`() {
        // 服务层用 isSingleEntry 决定要不要切到详细档，这里也得守同一条规矩
        assertFalse(TextPipeline(badDict).isSingleEntry("。"))
    }

    @Test
    fun `用户自己要改断句标点的读法，照改`() {
        // 用户词典是用户的明确选择，不受这条保护限制
        val mine = SymbolDict.Entry(intArrayOf(0x3002), SymbolDict.Kind.SYMBOL, "句号", "", fromUser = true)
        val dict = SymbolDict.of(listOf(listOf(mine), badDict.entries()))
        assertEquals("你好 句号", TextPipeline(dict).transform("你好。", Verbosity.BRIEF))
    }

    @Test
    fun `长文经过坏词典之后句号一个不少，每块不超过软上限`() {
        val out = TextPipeline(badDict).transform(novel, Verbosity.BRIEF)
        assertEquals(novel.count { it == '。' }, out.count { it == '。' }, "句号一个都不能少")
        val chunks = Chunker.split(out)
        assertEquals(out, chunks.joinToString(""), "分块不能丢字也不能改字")
        assertTrue(chunks.size >= 2, "这么长的文本应当被分块")
        for (c in chunks) {
            assertTrue(c.length <= Chunker.SOFT_LIMIT, "块长 ${c.length} 超过软上限")
        }
    }

    @Test
    fun `没有句号的长段在逗号处切开，不会整段塞成一块`() {
        // 逗号一路到底的长段落，按句切分帮不上忙——旧逻辑会把它原样当成一整块
        val text = "这一句只用逗号一路连接下去，".repeat(40)
        val chunks = Chunker.split(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.size >= 2)
        for (c in chunks) {
            assertTrue(c.length <= Chunker.SOFT_LIMIT, "块长 ${c.length} 超过软上限")
            assertTrue(c.endsWith("，"), "应当切在逗号后面，让停顿落在块尾：$c")
        }
    }

    @Test
    fun `连逗号都没有的长串按软上限硬切，英文切在空格处`() {
        val text = "word ".repeat(120)
        val chunks = Chunker.split(text)
        assertEquals(text, chunks.joinToString(""))
        for ((i, c) in chunks.withIndex()) {
            assertTrue(c.length <= Chunker.SOFT_LIMIT, "块长 ${c.length} 超过软上限")
            if (i < chunks.size - 1) assertTrue(c.endsWith(" "), "应当切在空格后，别把单词劈开：[$c]")
        }
    }

    @Test
    fun `受保护的标点覆盖中文正文里最常见的那几个`() {
        for (c in "。，、；：？！《》【】〈〉〔〕〖〗～（）·…—–‐‥―‧") {
            assertTrue(NativePunctuation.isProtected(c.code), "「$c」应当受保护")
        }
        // 引号也一样：用户定的规矩是我们别插嘴，整个交给下游
        for (c in "“”‘’「」『』" + Character.toString(0x22) + Character.toString(0x27)) {
            assertTrue(NativePunctuation.isProtected(c.code), "「$c」是引号，应当原样交给下游")
        }
    }
}
