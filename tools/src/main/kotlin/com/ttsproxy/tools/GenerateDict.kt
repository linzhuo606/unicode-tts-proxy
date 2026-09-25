package com.ttsproxy.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URL

/**
 * 生成 APK assets 里的符号词典。
 *
 * 分工：
 * - **分词**靠 emoji-test.txt（Unicode 官方列出的全部合法 emoji 序列）。
 *   运行时据此建 Trie 做最长匹配，不依赖设备上 android.icu 的 Unicode 版本
 *   ——那个版本随 Android 版本浮动，老机器切不对新 emoji。
 * - **命名**靠 CLDR 中文标注；CLDR 覆盖不到的（比如比 CLDR 更新的 emoji）
 *   在这里离线推导好，运行时就是纯查表，不做任何推理。
 */

// Unicode 的 /Public/emoji/ 下只有到 16.0 的版本目录，更新的版本只在 latest/ 提供，
// 所以这里取 latest 并把实际版本号写进产物头部。生成物 symbols.tsv 是入库的，
// 构建本身可复现；重新生成是一次显式的维护动作。
private const val EMOJI_TEST_URL = "https://unicode.org/Public/emoji/latest/emoji-test.txt"
private const val CLDR_TAG = "46.0.0"
private const val ANNOTATIONS_URL =
    "https://raw.githubusercontent.com/unicode-org/cldr-json/" + CLDR_TAG +
        "/cldr-json/cldr-annotations-full/annotations/zh/annotations.json"
private const val DERIVED_URL =
    "https://raw.githubusercontent.com/unicode-org/cldr-json/" + CLDR_TAG +
        "/cldr-json/cldr-annotations-derived-full/annotationsDerived/zh/annotations.json"

/** 未知 emoji 的兜底读法。听到「表情符号」至少知道那里有个东西，比静音或乱读强。 */
private val TAB_CHAR = Char(9)

private const val UNKNOWN_EMOJI = "表情符号"

/** 只有这两个分组适合加「表情」后缀；给旗帜或物件加就成了病句。 */
private val SUFFIXABLE_GROUPS = setOf("Smileys & Emotion", "People & Body")

private val SKIN_TONES = mapOf(
    0x1F3FB to "较浅肤色",
    0x1F3FC to "中等浅肤色",
    0x1F3FD to "中等肤色",
    0x1F3FE to "中等深肤色",
    0x1F3FF to "较深肤色",
)

data class EmojiEntry(val codePoints: List<Int>, val group: String) {
    /** 带 U+20E3 的是 keycap 序列（1️⃣ #️⃣ 之类）。 */
    val isKeycap: Boolean get() = codePoints.contains(0x20E3)
}

fun main(args: Array<String>) {
    if (args.size < 3) {
        System.err.println("用法: GenerateDict <数据目录> <人工词表目录> <输出文件>")
        return
    }
    val dataDir = File(args[0]).apply { mkdirs() }
    val curatedDir = File(args[1])
    val output = File(args[2])

    val emojiTest = ensureFile(File(dataDir, "emoji-test.txt"), EMOJI_TEST_URL)
    val annotations = ensureFile(File(dataDir, "annotations-zh.json"), ANNOTATIONS_URL)
    val derived = ensureFile(File(dataDir, "annotations-derived-zh.json"), DERIVED_URL)

    // 人工重写的 emoji 名。除了覆盖同码位的条目，派生肤色变体时也要用它——
    // 否则改了 👍 的名字，👍🏻 还会沿用 CLDR 的旧名。
    val curatedEmoji = readCuratedEmojiNames(curatedDir)
    if (curatedEmoji.isNotEmpty()) println("人工重写的 emoji 名 " + curatedEmoji.size + " 条")

    val names = HashMap<String, String>()
    // 先读基础表，再用派生表覆盖：派生表里带肤色/性别的组合名更具体
    names.putAll(readAnnotations(annotations, "annotations"))
    names.putAll(readAnnotations(derived, "annotationsDerived"))
    println("CLDR 中文名 " + names.size + " 条")

    val emojiVersion = readEmojiVersion(emojiTest)
    val sequences = readEmojiTest(emojiTest)
    println("emoji 序列 " + sequences.size + " 条（emoji-test " + emojiVersion + "）")

    // 先读人工词表。它们优先于 emoji 表：↔ ™ ▪ 这些码位两边都有，
    // 人工校对过的数学读法更合适，也避免同一个键出现两条互相打架的记录。
    val rows = ArrayList<String>()
    val curatedKeys = HashSet<String>()
    // 目录里所有 symbols-*.tsv 都会被收进来，类别由文件名决定。
    // 类别目前只影响「关闭」档（那一档只丢 emoji），其余一视同仁。
    val curatedFiles = (curatedDir.listFiles() ?: emptyArray())
        .filter { it.name.startsWith("symbols-") && it.name.endsWith(".tsv") }
        .sortedBy { it.name }
    if (curatedFiles.isEmpty()) println("警告：" + curatedDir.path + " 下没有找到人工词表")
    // 同一个码位不许在两张人工词表里各写一条：两条会一起进桶，
    // 最长匹配同长度时靠插入顺序决胜负，等于读法由文件名的字母序偷偷决定。
    // 这种重复真的发生过（2776–2793 同时在 enclosed 和 misc 里），所以在这里挡死。
    val keyOwner = HashMap<String, String>()
    val clashes = ArrayList<String>()
    for (file in curatedFiles) {
        val kind = kindForFile(file.name)
        val curated = readCurated(file, kind)
        println(file.name + ": " + curated.size + " 条  [" + kind + "]")
        for (rowText in curated) {
            val key = rowText.substringBefore('\t')
            curatedKeys.add(key)
            val owner = keyOwner.put(key, file.name)
            if (owner != null && owner != file.name) {
                clashes += key + " 同时出现在 " + owner + " 和 " + file.name
            }
        }
        rows += curated
    }
    require(clashes.isEmpty()) {
        "人工词表之间有重复码位 " + clashes.size + " 处，必须先合并：" +
            clashes.take(20).joinToString("；")
    }

    // 下游本来就处理得好的标点不许进内置词典：断句标点是它的韵律指令，换成字会让句子挤成一团，
    // 分块也会随之失效（长文朗读乱读的回归就是这么来的）；引号同理，用户要的是我们别插嘴。
    // 清单和 core 的 NativePunctuation 是同一份——tools 不依赖 core，只好抄一份，改一处要改两处。
    val nativeHits = keyOwner.keys.filter { key ->
        val cps = key.split(' ')
        cps.size == 1 && isNativePunctuation(cps[0].toInt(16))
    }
    require(nativeHits.isEmpty()) {
        "内置词典收了应当原样交给下游的标点 " + nativeHits.joinToString(" ") +
            "：这些必须原样交给下游引擎，见 core 的 NativePunctuation"
    }

    // 空白、控制与格式字符不许有读法：它们归空格折叠与不可见字符清理管，一个字都不该念。
    // 按 Unicode 类别判定，不列清单——上一次就是清单外的不换行空格漏了进来。
    val silentHits = keyOwner.keys.filter { key ->
        val cps = key.split(' ')
        cps.size == 1 && isWhitespaceOrFormat(cps[0].toInt(16))
    }
    require(silentHits.isEmpty()) {
        "内置词典给空白或格式控制符配了读法 " + silentHits.joinToString(" ") +
            "：这些字符不该念出来，见 core 的 Invisibles.isWhitespaceOrFormat"
    }

    // 欧洲语言里的普通字母（æ ø ß ð þ）不许有读法：收进来 København 会读成「K 小写圆加斜杠 benhavn」。
    // 音标表是唯一的例外，它的取舍在那张表的注释里另有交代。按类别判定，不列清单。
    val letterHits = keyOwner.filter { (key, owner) ->
        val cps = key.split(' ')
        owner != "symbols-ipa.tsv" && cps.size == 1 && isCasedLatinLetter(cps[0].toInt(16))
    }.keys
    require(letterHits.isEmpty()) {
        "内置词典给拉丁字母配了读法 " + letterHits.joinToString(" ") +
            "：这些在西文里就是普通字母，交给下游引擎原样念"
    }

    var unnamed = 0
    var overridden = 0
    for (entry in sequences) {
        // 单个 ASCII 字符绝不能当成 emoji 替换掉——那会把数字和井号读没
        if (entry.codePoints.size == 1 && entry.codePoints[0] < 0x80) continue
        if (curatedKeys.contains(keyOf(entry.codePoints))) {
            overridden++
            continue
        }
        val resolved = resolveName(entry, names, curatedEmoji)
        if (resolved == null) unnamed++
        val brief = resolved?.first ?: fallbackName(entry)
        val verbose = resolved?.second ?: fallbackName(entry)
        rows += row(entry.codePoints, "E", brief, verbose)
    }
    if (overridden > 0) println("有 " + overridden + " 条 emoji 被人工词表覆盖")
    if (unnamed > 0) println("警告：" + unnamed + " 条 emoji 没有中文名，已用兜底读法")

    val unique = rows.distinct().sorted()
    output.parentFile?.mkdirs()
    output.bufferedWriter(Charsets.UTF_8).use { out ->
        out.write("# 由 tools/GenerateDict 生成，请勿手工编辑\n")
        out.write("# emoji: Unicode emoji-test " + emojiVersion + " + CLDR " + CLDR_TAG + " 中文标注 (Unicode License)\n")
        out.write("# 格式: 码位序列<TAB>类别(E 表情/M 数学/P 理化/S 其他/B 盲文/I 音标)<TAB>简洁读法<TAB>详细读法\n")
        unique.forEach { out.write(it); out.write("\n") }
    }
    println("已写出 " + output.path + "（" + unique.size + " 条，" + (output.length() / 1024) + " KB）")
}

/** 类别只由文件名决定，新增词表时不用改代码。 */
private fun kindForFile(name: String): String = when {
    // symbols-emoji*.tsv 全算 emoji：只有这一类在「关闭」档下会被丢弃
    name.startsWith("symbols-emoji") -> "E"
    else -> kindForOtherFile(name)
}

private fun kindForOtherFile(name: String): String = when (name) {
    "symbols-math.tsv" -> "M"
    "symbols-science.tsv" -> "P"
    "symbols-braille.tsv" -> "B"
    // 音标单独一类：它的简洁读法可以整体切成盲文点位，别的类别没有这个开关
    "symbols-ipa.tsv" -> "I"
    else -> "S"
}

private fun keyOf(cps: List<Int>): String = cps.joinToString(" ") { it.toString(16).uppercase() }

/**
 * CLDR 没收录时的兜底。区域指示符对必然是国旗，读「旗帜」比读「表情符号」有用得多
 * ——emoji-test 的版本总是比 CLDR 新，新国家或地区的旗子会先落到这里。
 */
private fun fallbackName(entry: EmojiEntry): String {
    val isFlagPair = entry.codePoints.size == 2 &&
        entry.codePoints.all { it in 0x1F1E6..0x1F1FF }
    return if (isFlagPair) "旗帜" else UNKNOWN_EMOJI
}

private fun row(
    cps: List<Int>,
    kind: String,
    brief: String,
    verbose: String,
    conditions: String = "",
): String {
    val key = keyOf(cps)
    val v = if (verbose == brief) "" else verbose
    return key + "\t" + kind + "\t" + brief +
        "\t" + v + "\t" + conditions
}

private fun ensureFile(file: File, url: String): File {
    if (file.exists() && file.length() > 0) return file
    println("下载 " + url)
    URL(url).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
    return file
}

/** CLDR 的 tts 字段是只有一个元素的数组。 */
private fun readAnnotations(file: File, rootKey: String): Map<String, String> {
    val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
    val map = root[rootKey]?.jsonObject?.get("annotations")?.jsonObject ?: return emptyMap()
    val out = HashMap<String, String>(map.size)
    for ((emoji, value) in map) {
        val tts = value.jsonObject["tts"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
        if (!tts.isNullOrBlank()) out[emoji] = tts
    }
    return out
}

/**
 * 解析 emoji-test.txt。
 *
 * 只取 fully-qualified / minimally-qualified / unqualified 三种状态：真实文本里
 * 这三种形式都会出现（尤其是少了 FE0F 的 unqualified 形式）。component 是单独的
 * 肤色/发色修饰符，不作为独立条目——游离的修饰符由运行时当作不可见字符丢弃。
 */
/** 从 emoji-test.txt 的注释头里读出版本号，写进产物以便追溯。 */
private fun readEmojiVersion(file: File): String {
    file.useLines { lines ->
        for (line in lines) {
            if (!line.startsWith("#")) break
            val marker = "# Version:"
            if (line.startsWith(marker)) return line.removePrefix(marker).trim()
        }
    }
    return "unknown"
}

private fun readEmojiTest(file: File): List<EmojiEntry> {
    val out = ArrayList<EmojiEntry>()
    var group = ""
    file.forEachLine { raw ->
        val line = raw.trim()
        if (line.startsWith("# group:")) {
            group = line.removePrefix("# group:").trim()
        } else if (line.isNotEmpty() && !line.startsWith("#")) {
            val semi = line.indexOf(';')
            if (semi > 0) {
                val hash = line.indexOf('#', semi)
                val end = if (hash > 0) hash else line.length
                val status = line.substring(semi + 1, end).trim()
                if (status != "component") {
                    val cps = line.substring(0, semi).trim().split(' ')
                        .filter { it.isNotEmpty() }
                        .mapNotNull { it.toIntOrNull(16) }
                    if (cps.isNotEmpty()) out.add(EmojiEntry(cps, group))
                }
            }
        }
    }
    return out
}

/**
 * 给一个 emoji 序列找中文读法。逐级降级：
 * 原样 -> 补/去 FE0F -> 剥掉肤色修饰符后查基础名，再把肤色补进详细读法。
 *
 * @return 简洁读法 to 详细读法；实在找不到返回 null
 */
private fun resolveName(
    entry: EmojiEntry,
    names: Map<String, String>,
    curated: Map<String, Pair<String, String>>,
): Pair<String, String>? {
    // 查找顺序很要紧：**人工表的两级都要排在 CLDR 前面**。
    // CLDR 的派生表里本来就有 👍🏻 这类带肤色的条目，若先查 CLDR 精确匹配，
    // 改了 👍 的名字之后 👍🏻 仍会沿用 CLDR 旧名，一套词典里出现两种叫法。
    val tones = entry.codePoints.filter { SKIN_TONES.containsKey(it) }
    val stripped = entry.codePoints.filterNot { SKIN_TONES.containsKey(it) }
    val toneName = tones.mapNotNull { SKIN_TONES[it] }.distinct().joinToString(" ")

    // 1. 人工表精确匹配
    for (candidate in variants(entry.codePoints)) {
        curated[candidate]?.let { return it }
    }
    // 2. 人工表的基础条目 + 肤色
    if (tones.isNotEmpty()) {
        for (candidate in variants(stripped)) {
            curated[candidate]?.let {
                // 肤色缀在「常表示…」后面会和前一句连读，补一个句号分开
                val sep = if (it.second.endsWith("。") || it.second.endsWith("；")) "" else "。"
                return it.first to (it.second + sep + toneName)
            }
        }
    }
    // 3. CLDR 精确匹配
    for (candidate in variants(entry.codePoints)) {
        val hit = names[candidate]
        if (hit != null) return format(hit, entry.group, "", entry.isKeycap)
    }
    // 4. CLDR 的基础条目 + 肤色
    if (tones.isNotEmpty()) {
        for (candidate in variants(stripped)) {
            val hit = names[candidate]
            if (hit != null) return format(hit, entry.group, toneName, entry.isKeycap)
        }
    }
    return null
}

/**
 * 读人工重写的 emoji 名，键是码位序列拼成的字符串，和 [variants] 对得上。
 *
 * 要读**所有** symbols-emoji*.tsv，不能只读一个文件：漏掉哪张表，
 * 那张表里条目的肤色变体就会落回 CLDR 旧名，同一个 emoji 出现两种叫法。
 */
private fun readCuratedEmojiNames(dir: File): Map<String, Pair<String, String>> {
    val files = (dir.listFiles() ?: emptyArray())
        .filter { it.name.startsWith("symbols-emoji") && it.name.endsWith(".tsv") }
        .sortedBy { it.name }
    val out = HashMap<String, Pair<String, String>>()
    for (file in files) {
        for (raw in file.readLines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cols = line.split(TAB_CHAR)
            if (cols.size < 2) continue
            val cps = cols[0].trim().split(' ').filter { it.isNotEmpty() }
                .mapNotNull { it.toIntOrNull(16) }
            val brief = cols[1].trim()
            if (cps.isEmpty() || brief.isEmpty()) continue
            val verbose = cols.getOrNull(2)?.trim().orEmpty().ifEmpty { brief }
            val pair = brief to verbose
            // 带不带变体选择符的两种写法都登记：✌️ 和 ✌ 在真实文本里都会出现，
            // 只登记带 FE0F 的那种，裸形式就会漏回 CLDR 旧名
            out[cps.toStr()] = pair
            out.putIfAbsent(cps.filterNot { it == 0xFE0F }.toStr(), pair)
        }
    }
    return out
}

/** 同一个序列在文本里可能带也可能不带 FE0F，两种形式都要能查到。 */
private fun variants(cps: List<Int>): List<String> {
    val withVs = cps.toStr()
    val withoutVs = cps.filterNot { it == 0xFE0F }.toStr()
    return listOf(withVs, withoutVs).distinct()
}

private fun List<Int>.toStr(): String {
    val sb = StringBuilder()
    forEach { sb.appendCodePoint(it) }
    return sb.toString()
}

/**
 * CLDR 的派生名形如「挥手: 较浅肤色」。简洁档只读冒号前的主体，
 * 详细档把修饰语也读出来。
 */
private fun format(
    tts: String,
    group: String,
    extraTone: String,
    isKeycap: Boolean,
): Pair<String, String> {
    val parts = tts.split(":", "：").map { it.trim() }.filter { it.isNotEmpty() }
    // 国旗的 CLDR 名形如「旗: 中国」，有区分度的是冒号**后**半段。
    // 照搬「取前半段」的规则会把每一面国旗都读成「旗」。
    val brief = if (group == "Flags" && parts.size >= 2) {
        val place = parts.drop(1).joinToString("")
        if (parts[0] == "旗") place + "旗" else place
    } else if (isKeycap && parts.size >= 2) {
        // keycap 的名字同样是「按键: 1」这种结构，只读前半段就变成 12 个「按键」了
        parts.joinToString(" ")
    } else if (parts.isEmpty()) {
        tts
    } else {
        parts[0]
    }
    val detailParts = ArrayList<String>(parts)
    if (extraTone.isNotEmpty()) detailParts.add(extraTone)
    var verbose = detailParts.joinToString(" ")
    if (SUFFIXABLE_GROUPS.contains(group) && !verbose.endsWith("表情")) verbose += "表情"
    return brief to verbose
}

/** 读人工校对过的理科词表，顺手校验：坏行要在生成期就暴露，不能带进 APK。 */
private fun readCurated(file: File, kind: String): List<String> {
    val out = ArrayList<String>()
    var lineNo = 0
    file.forEachLine { raw ->
        lineNo++
        val line = raw.trim()
        if (line.isNotEmpty() && !line.startsWith("#")) {
            val cols = line.split('\t')
            require(cols.size >= 2) { file.name + ":" + lineNo + " 列数不足 -> " + line }
            val cps = cols[0].trim().split(' ').filter { it.isNotEmpty() }.map {
                it.toIntOrNull(16)
                    ?: throw IllegalArgumentException(file.name + ":" + lineNo + " 非法码位 " + it)
            }
            require(cps.isNotEmpty()) { file.name + ":" + lineNo + " 空码位" }
            val brief = cols[1].trim()
            require(brief.isNotEmpty()) { file.name + ":" + lineNo + " 简洁读法为空" }
            val verbose = cols.getOrNull(2)?.trim().orEmpty()
            // 以 ? 开头的列是上下文条件（?after_quantity=欧姆），以 # 开头的是注释。
            // 用前缀而不是固定列号，加注释时不必顾虑列序。
            val conditions = cols.drop(1)
                .map { it.trim() }
                .filter { it.startsWith("?") }
                .joinToString(";") { it.removePrefix("?") }
            out.add(row(cps, kind, brief,
                if (verbose.isEmpty()) brief else verbose, conditions))
        }
    }
    return out
}

/**
 * 拉丁文里有大小写之分的字母：西文正文里的普通字母。
 * 类字母符号和数字形式两块除外——`K`（开尔文）、`Å`（埃）是单位，本来就该读。
 */
private fun isCasedLatinLetter(cp: Int): Boolean =
    Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN &&
        Character.UnicodeBlock.of(cp) != Character.UnicodeBlock.LETTERLIKE_SYMBOLS &&
        Character.UnicodeBlock.of(cp) != Character.UnicodeBlock.NUMBER_FORMS &&
        when (Character.getType(cp).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER -> true
            else -> false
        }

/** 和 core 的 NativePunctuation.isProtected 是同一份清单。 */
private fun isNativePunctuation(cp: Int): Boolean = when (cp) {
    0x21, 0x2C, 0x2E, 0x3A, 0x3B, 0x3F -> true
    0x28, 0x29, 0x5B, 0x5D, 0x7B, 0x7D -> true
    0x22, 0x27 -> true
    0x00AB, 0x00BB, 0x2039, 0x203A -> true
    in 0x2018..0x201F -> true
    0x2E42 -> true
    0x00B7, 0x2027 -> true
    0x2010, 0x2013, 0x2014, 0x2015 -> true
    0x2025, 0x2026 -> true
    0xFF01, 0xFF0C, 0xFF0E, 0xFF1A, 0xFF1B, 0xFF1F -> true
    0xFF02, 0xFF07 -> true
    0xFF08, 0xFF09, 0xFF3B, 0xFF3D, 0xFF5B, 0xFF5D -> true
    0xFF5E, 0xFF5F, 0xFF60 -> true
    0xFF61, 0xFF62, 0xFF63, 0xFF64 -> true
    0x3001, 0x3002 -> true
    in 0x3008..0x3011 -> true
    in 0x3014..0x301F -> true
    else -> false
}

/** 和 core 的 Invisibles.isWhitespaceOrFormat 同一条规则：空白、控制、格式字符。 */
private fun isWhitespaceOrFormat(cp: Int): Boolean = when (Character.getType(cp)) {
    Character.SPACE_SEPARATOR.toInt(),
    Character.LINE_SEPARATOR.toInt(),
    Character.PARAGRAPH_SEPARATOR.toInt(),
    Character.CONTROL.toInt(),
    Character.FORMAT.toInt() -> true
    else -> false
}
