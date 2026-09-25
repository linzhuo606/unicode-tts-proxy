package com.ttsproxy.core

/** 用码位构造字符串，避免源文件编码问题，也让测试意图一目了然。 */
fun cp(vararg cps: Int): String {
    val sb = StringBuilder()
    for (c in cps) sb.appendCodePoint(c)
    return sb.toString()
}

// ---- 常用码位常量 ----
const val THUMBS_UP = 0x1F44D          // 👍
const val MAN = 0x1F468                // 👨
const val WOMAN = 0x1F469              // 👩
const val GIRL = 0x1F467               // 👧
const val BOY = 0x1F466                // 👦
const val ZWJ = 0x200D
const val VS16 = 0xFE0F
const val KEYCAP = 0x20E3
const val SKIN_LIGHT = 0x1F3FB
const val RI_C = 0x1F1E8               // 区域指示符 C
const val RI_N = 0x1F1F3               // 区域指示符 N
const val FLAG_BASE = 0x1F3F4          // 🏴
const val MELTING_FACE = 0x1FAE0       // 🫠 Unicode 14 新增——JDK 17 认不出它

const val SUMMATION = 0x2211           // ∑
const val INTEGRAL = 0x222B            // ∫
const val REALS = 0x211D               // ℝ
const val SUP_2 = 0x00B2               // ²
const val SUB_2 = 0x2082               // ₂
const val SUB_4 = 0x2084               // ₄
const val SUP_MINUS = 0x207B           // ⁻

const val DEGREE_C = 0x2103            // ℃
const val SQ_M2 = 0x33A1               // ㎡  （注意：不是 U+3382，那是 ㎂）
const val SQ_M3 = 0x33A5               // ㎥
const val SQ_KG = 0x338F               // ㎏
const val OHM_SIGN = 0x2126            // Ω 欧姆记号
const val ANGSTROM = 0x212B            // Å
const val EQUILIBRIUM = 0x21CC         // ⇌
const val DELTA_CAP = 0x0394           // Δ
const val OMEGA_GREEK = 0x03A9         // Ω 希腊大写欧米伽（人们打电阻单位用的就是它）
const val MU_GREEK = 0x03BC            // μ
const val ARROW_UP = 0x2191            // ↑
const val ARROW_DOWN = 0x2193          // ↓
const val PRIME = 0x2032               // ′

/**
 * 测试用的小词典。真实词典由 tools 模块从 CLDR 生成，但核心逻辑的正确性
 * 不应该依赖生成产物——所以测试自带数据。
 */
val TEST_DICT_SOURCE: List<String> = listOf(
        "# 测试词典",
        "1F44D\tE\t点赞\t点赞表情",
        "1F468 200D 1F469 200D 1F467 200D 1F466\tE\t一家四口\t一家四口表情",
        "1F468\tE\t男人\t男人表情",
        "1F468 1F3FB\tE\t男人\t浅肤色男人表情",
        "1F1E8 1F1F3\tE\t中国\t中国国旗",
        "0031 FE0F 20E3\tE\t数字一\t按键数字一",
        "1F3F4 E0067 E0062 E0073 E0063 E0074 E007F\tE\t苏格兰旗\t苏格兰旗帜",
        "1FAE0\tE\t融化的脸\t融化的脸表情",
        "2211\tM\t求和\t求和符号",
        "222B\tM\t积分\t积分符号",
        "211D\tM\t实数集\t实数集合",
        "00B2\tM\t平方\t上标 2",
        "2082\tM\t下标 2\t下标 2",
        "2084\tM\t下标 4\t下标 4",
        "207B\tM\t负\t上标负号",
        "2103\tP\t摄氏度",
        "33A1\tP\t平方米",
        "33A5\tP\t立方米",
        "338F\tP\t千克",
        "2126\tP\t欧姆",
        "212B\tP\t埃",
        "21CC\tP\t可逆\t可逆反应",
        "0394\tP\t德尔塔\t大写德尔塔",
        // 带上下文条件的词条：第 5 列是条件读法
        "03A9\tP\t欧米伽\t大写欧米伽\tafter_quantity=欧姆",
        "03BC\tP\t缪\t缪\tbefore_latin=微",
        "03BC 6D\tP\t微米",
        "2191\tM\t上箭头\t上箭头\tafter_formula=气体",
        "2193\tM\t下箭头\t下箭头\tafter_formula=沉淀",
        "2032\tM\t分\t分\tafter_latin=导数",
        // 国际音标：简洁读音理简称，详细读点位加音理
        "014B\tI\t软腭鼻音\t点 1 2 4 6 软腭鼻音",
        "0283\tI\t清龈后擦音\t点 1 5 6 清龈后擦音",
        "0303\tI\t鼻化\t点 4 点 1 2 4 5 6 鼻化",
        // 折出基本字母后还要再查一次词典，希腊字母才不会退化成乱码
        "03B1\tP\t阿尔法\t小写阿尔法",
        "\t坏行会被跳过",
        "ZZZZ\tM\t非法码位",
    )

val TEST_DICT: SymbolDict = SymbolDict.parse(TEST_DICT_SOURCE.asSequence())

/** 词典源里的数据行数（含两条故意写坏的），供解析测试核对。 */
val TEST_DICT_ENTRIES: Int = TEST_DICT_SOURCE.count { !it.startsWith("#") }

const val ENG = 0x014B                 // ŋ 浊软腭鼻音
const val ESH = 0x0283                 // ʃ 清龈后擦音
const val TILDE_ABOVE = 0x0303         // 组合波浪符：音标里表示鼻化
const val OPEN_E = 0x025B              // ɛ 词典里没有，用来试透传
const val ACUTE_ABOVE = 0x0301         // 组合锐音符
const val E_ACUTE = 0x00E9             // é
const val A_CARON = 0x01CE             // ǎ 拼音的上声
const val ALPHA_TONOS = 0x03AC         // ά 带重音的希腊小写阿尔法

val PIPELINE = TextPipeline(TEST_DICT)
