package com.ttsproxy.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserDictTest {

    private val builtin = SymbolDict.parseEntries(
        sequenceOf(
            "1F63B\tE\t爱心眼猫\t爱心眼猫：猫脸张嘴笑、两只眼睛是红心",
            "1F44D\tE\t点赞\t点赞：握拳竖起大拇指",
            "2211\tM\t求和\t求和符号",
        )
    )

    private fun pipeline(userText: String): TextPipeline {
        val user = UserDict.parse(userText).entries
        return TextPipeline(SymbolDict.of(listOf(user, builtin)))
    }

    @Test
    fun `用户条目覆盖内置读法`() {
        val p = pipeline("😻\t猫猫头\t猫猫头：一只笑眯眯的猫")
        assertEquals("猫猫头", p.transform("😻", Verbosity.BRIEF))
        assertEquals("猫猫头：一只笑眯眯的猫", p.transform("😻", Verbosity.VERBOSE))
    }

    @Test
    fun `没被覆盖的内置条目照旧`() {
        val p = pipeline("😻\t猫猫头")
        assertEquals("点赞", p.transform("👍", Verbosity.BRIEF))
    }

    @Test
    fun `减号表示屏蔽——任何档位都不出声`() {
        val p = pipeline("👍\t-")
        for (v in Verbosity.values()) {
            assertEquals("好的", p.transform("好的👍", v), "档位 " + v.key)
        }
    }

    @Test
    fun `屏蔽优先于档位——详细档也不读`() {
        val p = pipeline("2211\t-")
        assertEquals("", p.transform("∑", Verbosity.VERBOSE))
    }

    @Test
    fun `第一列认字符、U加号写法和裸码位`() {
        for (field in listOf("😻", "U+1F63B", "1F63B")) {
            val key = UserDict.parseKey(field)
            assertTrue(key != null && key.size == 1 && key[0] == 0x1F63B, "解析失败: " + field)
        }
    }

    @Test
    fun `单个字母按字符解析而不是当成十六进制`() {
        // A 是合法的十六进制，但用户输入 A 的意思一定是字母 A 本身
        assertEquals(listOf('A'.code), UserDict.parseKey("A")?.toList())
        assertEquals(listOf('F'.code), UserDict.parseKey("F")?.toList())
        // 而 U+41 明确写了前缀，就该按码位走
        assertEquals(listOf(0x41), UserDict.parseKey("U+41")?.toList())
    }

    @Test
    fun `多码位序列——粘贴整串和写码位等价`() {
        val pasted = UserDict.parseKey("👨‍👩‍👧")
        val hex = UserDict.parseKey("1F468 200D 1F469 200D 1F467")
        assertEquals(hex?.toList(), pasted?.toList())
    }

    @Test
    fun `用户条目在最长匹配里不会顶掉更长的内置序列`() {
        val longer = SymbolDict.parseEntries(
            sequenceOf("1F468 200D 1F469\tE\t一对夫妻\t", "1F468\tE\t男人\t")
        )
        val user = UserDict.parse("👨\t老哥").entries
        val p = TextPipeline(SymbolDict.of(listOf(user, longer)))
        // 更长的内置序列仍然优先——覆盖只在同一个键上生效
        assertEquals("一对夫妻", p.transform("👨‍👩", Verbosity.BRIEF))
        assertEquals("老哥", p.transform("👨", Verbosity.BRIEF))
    }

    @Test
    fun `坏行只丢自己并报出行号`() {
        val result = UserDict.parse(
            "# 注释\n" +
                "😻\t猫猫头\n" +
                "这一行没有制表符\n" +
                "\t空的第一列\n" +
                "👍\t点个赞\n"
        )
        assertEquals(2, result.entries.size)
        assertEquals(2, result.problems.size)
        assertEquals(3, result.problems[0].lineNumber)
        assertEquals(4, result.problems[1].lineNumber)
    }

    @Test
    fun `重复的键只留第一条并报告`() {
        val result = UserDict.parse("😻\t猫猫头\n😻\t爱心猫\n")
        assertEquals(1, result.entries.size)
        assertEquals("猫猫头", result.entries[0].brief)
        assertEquals(1, result.problems.size)
    }

    @Test
    fun `导出再导入得到同样的条目`() {
        val text = "😻\t猫猫头\t猫猫头：一只笑眯眯的猫\tkind=E\n" +
            "∑\t求和\t\tkind=M\n" +
            "—\t破折号\t\tkind=S;repeat=collapse\n" +
            "Ω\t欧米伽\t\tkind=S;after_quantity=欧姆\n" +
            "👍\t-\t\t\n"
        val first = UserDict.parse(text).entries
        val round = UserDict.parse(UserDict.format(first))
        assertTrue(round.problems.isEmpty(), "回读不该有坏行")
        assertEquals(first.size, round.entries.size)
        val a = first.sortedBy { it.key[0] }
        val b = round.entries.sortedBy { it.key[0] }
        for (i in a.indices) {
            assertEquals(a[i].key.toList(), b[i].key.toList())
            assertEquals(a[i].brief, b[i].brief)
            assertEquals(a[i].verbose, b[i].verbose)
            assertEquals(a[i].kind, b[i].kind)
            assertEquals(a[i].muted, b[i].muted)
            assertEquals(a[i].collapseRepeats, b[i].collapseRepeats)
            assertEquals(a[i].conditions, b[i].conditions)
        }
    }

    @Test
    fun `没写类别时自动判定——emoji 在关闭档要被丢弃`() {
        val p = pipeline("1FAF6\t双手比心")
        assertEquals("双手比心", p.transform("🧶".let { "🫶" }, Verbosity.BRIEF))
        // 自动判成 EMOJI，所以「关闭」档整段丢弃
        assertEquals("", p.transform("🫶", Verbosity.OFF))
    }

    @Test
    fun `非 emoji 的自定义条目在关闭档不做替换但也不丢`() {
        val p = pipeline("§\t分节号")
        assertEquals("分节号", p.transform("§", Verbosity.BRIEF))
        assertEquals("§", p.transform("§", Verbosity.OFF))
    }

    @Test
    fun `空的用户词典不影响任何行为`() {
        val p = pipeline("")
        assertEquals("爱心眼猫", p.transform("😻", Verbosity.BRIEF))
        assertFalse(p.transform("😻", Verbosity.BRIEF).isEmpty())
    }

    // ---------------- 存盘再读回来必须一模一样 ----------------

    private fun entry(key: IntArray, brief: String, verbose: String = brief) = SymbolDict.Entry(
        key = key,
        kind = SymbolDict.Kind.SYMBOL,
        brief = brief,
        verbose = verbose,
        muted = false,
        fromUser = true,
    )

    private fun roundTrip(e: SymbolDict.Entry): SymbolDict.Entry {
        val back = UserDict.parse(UserDict.format(listOf(e)))
        assertTrue(back.problems.isEmpty(), "读回来报错: " + back.problems.map { it.reason })
        assertEquals(1, back.entries.size, "应当正好读回一条")
        return back.entries[0]
    }

    @Test
    fun `空白字符做键，存盘读回来不会列错位`() {
        // 曾经：整行 trim 把键吃掉，后面几列左移——读法「空格」成了要替换的字，
        // 全文的「空格」都被改读成详细读法
        for (cp in intArrayOf(0x00A0, 0x3000, 0x0020, 0x2028)) {
            val back = roundTrip(entry(intArrayOf(cp), "空格", "全角空格"))
            assertTrue(back.key.contentEquals(intArrayOf(cp)), "键变了: U+%04X".format(cp))
            assertEquals("空格", back.brief)
            assertEquals("全角空格", back.verbose)
        }
    }

    @Test
    fun `井号开头的键不会被当成注释丢掉`() {
        val keycap = intArrayOf(0x23, 0xFE0F, 0x20E3)
        val back = roundTrip(entry(keycap, "井号"))
        assertTrue(back.key.contentEquals(keycap))
    }

    @Test
    fun `长得像码位的字符按字符读回来`() {
        // 字符 "12" 本身，不能读回来变成 U+0012
        val key = "12".codePoints().toArray()
        assertTrue(roundTrip(entry(key, "十二")).key.contentEquals(key))
    }

    @Test
    fun `读法里的换行和制表符不会把一条拆成两条`() {
        val back = roundTrip(entry(intArrayOf(0x1F63B), "猫猫头", "猫猫头\n一只笑眯眯的猫\t的"))
        assertEquals("猫猫头 一只笑眯眯的猫 的", back.verbose)
    }

    @Test
    fun `带 BOM 的文件第一行照样认得`() {
        val p = pipeline("﻿😻\t猫猫头")
        assertEquals("猫猫头", p.transform("😻", Verbosity.BRIEF))
    }

    @Test
    fun `代理项码位不收`() {
        // 写进 UTF-8 会变成问号，读回来就接管了所有问号
        val result = UserDict.parse("U+D800\t问号")
        assertTrue(result.entries.isEmpty())
        assertEquals(1, result.problems.size, "要报给用户第几行错了")
    }
}
