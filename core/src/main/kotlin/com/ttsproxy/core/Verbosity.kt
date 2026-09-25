package com.ttsproxy.core

/**
 * 朗读详略档位。用户可在设置里一键切换——理科内容密集时，详细档会造成「符号爆炸」，
 * 反而拖慢盲人用户的理解速度，所以这个开关是必需品而不是锦上添花。
 */
enum class Verbosity {
    /**
     * 关闭：emoji 直接丢弃不读；符号**不做**词典替换，但仍然折叠数学字母、
     * 清理不可见字符。这一档解决的是「别乱读」，不是「别处理」。
     */
    OFF,

    /** 简洁（默认）：读词典里的短读法。x² →「x 平方」，㎡ →「平方米」。 */
    BRIEF,

    /** 详细：读结构化读法。x² →「x 上标 2」，emoji 补「表情」后缀并读出修饰符。 */
    VERBOSE;

    companion object {
        fun fromKey(key: String?): Verbosity = when (key) {
            "off" -> OFF
            "verbose" -> VERBOSE
            else -> BRIEF
        }
    }

    val key: String
        get() = when (this) {
            OFF -> "off"
            BRIEF -> "brief"
            VERBOSE -> "verbose"
        }
}
