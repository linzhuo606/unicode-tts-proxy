package com.ttsproxy.core

/**
 * 不可见 / 格式控制字符的识别。
 *
 * 这些字符正是下游引擎最容易卡住或乱读的东西：零宽连接符会被读成「问号」，
 * 孤立代理项会让某些引擎直接静音。走到这一步说明词典没匹配上，
 * 它们已经不承载任何可朗读的信息，直接丢弃。
 */
object Invisibles {

    /**
     * 是否应当丢弃。
     *
     * 关于「未分配码位」的处理有个坑：`Character.isDefined()` 用的是 JDK 自带的
     * Unicode 版本（JDK 17 是 Unicode 13）。如果无条件套用，Unicode 14 之后新增的
     * emoji（比如 🫠 U+1FAE0）会被判成未分配而**被我们丢掉**——那就把本来能读的
     * 字符弄没了。所以未分配判定只用于基本多文种平面（BMP）；辅助平面上只认
     * 显式列出的区段，宁可放过也不错杀。
     */
    fun shouldDrop(cp: Int): Boolean {
        if (isExplicitlyInvisible(cp)) return true
        if (isSurrogate(cp)) return true
        if (isPrivateUse(cp)) return true
        if (isNonCharacter(cp)) return true
        if (cp <= 0xFFFF && !Character.isDefined(cp)) return true
        return false
    }

    /**
     * 空白、控制与格式字符：它们**不是要念出来的符号**。
     *
     * 空白交给空格折叠（折成普通空格），控制与格式字符交给 [shouldDrop] 清理。
     * 内置词典不许给它们配读法——匹配词典在折叠和清理之前，一旦配了读法就会被念出来。
     * 不换行空格 U+00A0 在网页文字里极常见（HTML 的 `&nbsp;`），它要是有读法，
     * 长文读起来就会隔几个词冒一句「不换行空格」。
     *
     * 按 Unicode 类别判定，不列清单：清单会漏，类别不会。
     */
    fun isWhitespaceOrFormat(cp: Int): Boolean = when (Character.getType(cp)) {
        Character.SPACE_SEPARATOR.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt() -> true
        else -> false
    }

    private fun isExplicitlyInvisible(cp: Int): Boolean = when (cp) {
        // C0 控制符，但保留制表符/换行/回车——它们是合法的断句信号
        in 0x00..0x08, in 0x0B..0x0C, in 0x0E..0x1F, 0x7F -> true
        in 0x80..0x9F -> true              // C1 控制符
        0x00AD -> true                     // 软连字符
        0x034F -> true                     // 组合字素连接符
        0x061C -> true                     // 阿拉伯字母标记
        0x115F, 0x1160 -> true             // 谚文填充符
        0x17B4, 0x17B5 -> true             // 高棉固有元音
        in 0x180B..0x180E -> true          // 蒙古文自由变体选择符
        in 0x200B..0x200F -> true          // 零宽空格/非连接符/连接符、左右标记
        in 0x202A..0x202E -> true          // 双向控制符
        in 0x2060..0x2064 -> true          // 单词连接符、不可见运算符
        in 0x2066..0x206F -> true          // 双向隔离符、弃用格式符
        0x3164 -> true                     // 谚文填充符
        in 0xFE00..0xFE0F -> true          // 游离的变体选择符
        0xFEFF -> true                     // 字节序标记
        0xFFA0 -> true                     // 半角谚文填充符
        in 0xFFF9..0xFFFB -> true          // 行间注释标记
        0xFFFD -> true                     // 替换字符：本身不承载信息
        in 0x1D173..0x1D17A -> true        // 乐谱控制符
        in 0x1F3FB..0x1F3FF -> true        // 游离的肤色修饰符
        in 0xE0000..0xE0FFF -> true        // 标签字符与变体选择符补充区
        else -> false
    }

    private fun isSurrogate(cp: Int): Boolean = cp in 0xD800..0xDFFF

    private fun isPrivateUse(cp: Int): Boolean =
        cp in 0xE000..0xF8FF || cp in 0xF0000..0xFFFFD || cp in 0x100000..0x10FFFD

    private fun isNonCharacter(cp: Int): Boolean =
        cp in 0xFDD0..0xFDEF || (cp and 0xFFFE) == 0xFFFE
}
