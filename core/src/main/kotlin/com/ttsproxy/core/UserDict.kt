package com.ttsproxy.core

/**
 * 用户自定义词典的文件格式。
 *
 * ## 为什么不直接复用内置词典的格式
 *
 * 内置词典第一列是十六进制码位（`1F63B`），那是给生成器用的。盲人用户在手机上
 * 没法方便地查一个字符的码位，但**能直接粘贴字符**。所以这里第一列两种都认：
 * 字符本身、或者 `U+1F63B` / `1F63B` 这样的码位。导出时写字符，并在行尾附码位注释，
 * 这样文件在电脑上用记事本打开也读得懂、改得动。
 *
 * ## 格式规范（UTF-8，制表符分隔）
 *
 * ```
 * # 注释行以井号开头，空行忽略
 * 字符或码位 <TAB> 简洁读法 <TAB> 详细读法 <TAB> 选项
 * ```
 *
 * - **第 1 列**：要改读法的字符。可以是字符本身（`😻`）、`U+1F63B`、`1F63B`。
 *   多码位序列（ZWJ、肤色、国旗）用空格分隔：`1F468 200D 1F469`，
 *   或者直接粘贴整个序列 `👨‍👩‍👧`。
 * - **第 2 列**：简洁读法。写成 `-` 表示**屏蔽**，匹配到就什么都不读。
 * - **第 3 列**：详细读法。留空表示与简洁读法相同。
 * - **第 4 列**：选项，分号分隔，可省略：
 *   - `kind=E|M|P|S|B` 指定类别，不写则自动判定（只影响「关闭」档要不要丢弃）
 *   - `repeat=collapse` 连续重复时只读一次（破折号、省略号需要）
 *   - `after_quantity=欧姆` 之类的上下文条件读法
 *
 * 每一行都是独立的：某一行写坏了只丢那一行，不影响其余条目，更不会让引擎失声。
 */
object UserDict {

    /** 简洁读法写成这个就是屏蔽，什么都不读。 */
    const val MUTE = "-"

    const val FILE_NAME = "userdict.tsv"

    /** 一行的解析结果。坏行也要留下来，导入时要能告诉用户第几行错了。 */
    class Problem(@JvmField val lineNumber: Int, @JvmField val line: String, @JvmField val reason: String)

    class Result(
        @JvmField val entries: List<SymbolDict.Entry>,
        @JvmField val problems: List<Problem>,
    )

    fun parse(text: String): Result = parse(text.lineSequence())

    fun parse(lines: Sequence<String>): Result {
        val entries = ArrayList<SymbolDict.Entry>()
        val problems = ArrayList<Problem>()
        val seen = HashSet<String>()
        var lineNumber = 0
        for (raw in lines) {
            lineNumber++
            // 不能对整行 trim：第一列可能就是全角空格、不换行空格这类字符，
            // 整行一裁，后面几列就整体左移一格，读法会被当成要替换的字符。
            // 记事本存的「UTF-8 带 BOM」也要去掉，否则第一行的键前面多一个看不见的字符，永远匹配不上。
            var line = raw.removeSuffix("\r")
            if (lineNumber == 1) line = line.removePrefix("﻿")
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val cols = line.split('\t')
            if (cols.size < 2) {
                problems.add(Problem(lineNumber, line, "至少要有字符和读法两列，用制表符分开"))
                continue
            }
            val key = parseKey(cols[0])
            if (key == null || key.isEmpty()) {
                problems.add(Problem(lineNumber, line, "第一列不是有效的字符或码位"))
                continue
            }
            val dedupe = key.joinToString(" ")
            if (!seen.add(dedupe)) {
                problems.add(Problem(lineNumber, line, "这个字符前面已经写过了，这一行被忽略"))
                continue
            }
            val brief = cols[1].trim()
            if (brief.isEmpty()) {
                problems.add(Problem(lineNumber, line, "读法是空的；想屏蔽请写一个减号"))
                continue
            }
            val muted = brief == MUTE
            val verbose = if (cols.size > 2) cols[2].trim() else ""
            val options = if (cols.size > 3) cols[3] else ""
            val kind = kindOf(options) ?: SymbolDict.Kind.guess(key)
            entries.add(
                SymbolDict.Entry(
                    key = key,
                    kind = kind,
                    // 屏蔽条目的读法用不上，但 Entry 要求非空，塞占位符
                    brief = if (muted) MUTE else brief,
                    verbose = if (muted) MUTE else verbose,
                    conditions = if (muted) emptyList() else SymbolDict.parseConditions(options),
                    collapseRepeats = options.split(';').any { it.trim().equals("repeat=collapse", true) },
                    muted = muted,
                    fromUser = true,
                )
            )
        }
        return Result(entries, problems)
    }

    /**
     * 序列化成可导出的文件。
     *
     * 第一列写**字符本身**而不是码位，因为导出的首要用途是让用户在别的设备上
     * 继续编辑；行尾的码位注释是给「这个字符我看不见、也打不出来」的场合兜底。
     */
    fun format(entries: List<SymbolDict.Entry>): String = buildString {
        append("# ttsproxy 用户词典 v1\n")
        append("# 每行：字符<TAB>简洁读法<TAB>详细读法<TAB>选项\n")
        append("# 简洁读法写一个减号表示屏蔽（这个字符什么都不读）。\n")
        append("# 详细读法留空表示与简洁读法相同。选项可省略：\n")
        append("#   kind=E 表情 / M 数学 / P 理化 / S 其他符号 / B 盲文\n")
        append("#   repeat=collapse 连续重复只读一次\n")
        append("#   after_quantity=欧姆 这类上下文条件读法\n")
        append("# 第一列也可以写码位，例如 U+1F63B 或 1F63B；多码位用空格分开。\n")
        for (entry in entries.sortedBy { it.key.firstOrNull() ?: 0 }) {
            append(keyColumn(entry.key)).append('\t')
            append(if (entry.muted) MUTE else oneLine(entry.brief)).append('\t')
            append(if (entry.muted || entry.verbose == entry.brief) "" else oneLine(entry.verbose)).append('\t')
            append(oneLine(optionsOf(entry)))
            append("\t# ").append(entry.key.joinToString(" ") { "U+%04X".format(it) })
            append('\n')
        }
    }

    /**
     * 第一列尽量写字符本身，但读回来会变样的一律改写成码位：
     * - 以 `#` 开头（#️⃣ 键帽）会被当成注释行整行跳过；
     * - 含空白、控制字符、代理项的，读回来时会被裁掉或写坏；
     * - 字符本身看起来像码位的（`12`），读回来会被当成 U+0012。
     * 判据直接用 [parseKey] 往返一遍，不另列清单。
     */
    private fun keyColumn(key: IntArray): String {
        val chars = String(key, 0, key.size)
        val safe = !chars.startsWith("#") &&
            key.none { Character.isWhitespace(it) || Character.isSpaceChar(it) || Character.isISOControl(it) || it in 0xD800..0xDFFF } &&
            parseKey(chars)?.contentEquals(key) == true
        return if (safe) chars else key.joinToString(" ") { "U+%04X".format(it) }
    }

    /** 读法里的制表符、换行会把一条拆成好几条，写文件前一律换成空格。 */
    fun oneLine(text: String): String =
        text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').replace(' ', ' ').replace(' ', ' ')

    /**
     * 把用户输入的一段文本解析成码位序列。
     *
     * 输入框里可能是：粘贴来的字符、`U+1F63B`、`1F63B`、`1F468 200D 1F469`。
     * 判据是「整段看起来都像十六进制码位」才按码位解析，否则按字符本身。
     * 这条判据有个已知的取舍：单个字母 `A`、`B`、`E`、`F` 本身就是合法的十六进制，
     * 会被当成码位 U+000A 之类。所以这里额外要求码位写法至少 2 位，
     * 这样 `A` 仍然按字符走，而 `1F63B` 按码位走。
     */
    fun parseKey(field: String): IntArray? {
        val text = field.trim()
        // 整列只有空白：键就是这个空白字符本身（旧版导出的全角空格、不换行空格条目）
        if (text.isEmpty()) return field.codePoints().toArray().takeIf { it.isNotEmpty() && '\t'.code !in it }
        val parts = text.split(' ', '　').filter { it.isNotEmpty() }
        val looksHex = parts.isNotEmpty() && parts.all { part ->
            val body = part.removePrefix("U+").removePrefix("u+")
            body.length >= 2 && body.length <= 6 && body.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } &&
                (part.startsWith("U+", true) || body.any { it.isDigit() } || body.length > 4)
        }
        if (looksHex) {
            val hex = parts.joinToString(" ") { it.removePrefix("U+").removePrefix("u+") }
            SymbolDict.parseCodePoints(hex)?.let { return it }
            // 明写了 U+ 却不是合法码位（比如代理项）：报错，别退回去当成「U+D800」这几个字
            if (parts.any { it.startsWith("U+", ignoreCase = true) }) return null
        }
        return text.codePoints().toArray().takeIf { it.isNotEmpty() }
    }

    /** 把码位序列显示成「字符 + 码位」，界面上要念给用户听。 */
    fun describeKey(key: IntArray): String =
        String(key, 0, key.size) + "（" + key.joinToString(" ") { "U+%04X".format(it) } + "）"

    private fun kindOf(options: String): SymbolDict.Kind? {
        for (part in options.split(';')) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            if (part.substring(0, idx).trim().equals("kind", true)) {
                return SymbolDict.Kind.fromTag(part.substring(idx + 1).trim().uppercase())
            }
        }
        return null
    }

    private fun optionsOf(entry: SymbolDict.Entry): String {
        val parts = ArrayList<String>(3)
        parts.add("kind=" + SymbolDict.Kind.tagOf(entry.kind))
        if (entry.collapseRepeats) parts.add("repeat=collapse")
        for ((context, reading) in entry.conditions) {
            parts.add(ReadingContext.keyOf(context) + "=" + reading)
        }
        return parts.joinToString(";")
    }
}
