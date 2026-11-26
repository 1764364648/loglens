package dev.loglens.data

/**
 * 日志级别。RAW 用于无法解析的原始行（如 events buffer 的 brief 格式）。
 */
enum class LogLevel(val letter: String, val priority: Int) {
    VERBOSE("V", 0),
    DEBUG("D", 1),
    INFO("I", 2),
    WARN("W", 3),
    ERROR("E", 4),
    FATAL("F", 5),
    ASSERT("A", 5),
    RAW("·", -1);

    companion object {
        fun fromChar(c: Char): LogLevel = when (c) {
            'V' -> VERBOSE
            'D' -> DEBUG
            'I' -> INFO
            'W' -> WARN
            'E' -> ERROR
            'F' -> FATAL
            'A' -> ASSERT
            else -> RAW
        }
    }
}

data class LogEntry(
    val id: Long,
    val time: String,
    val pid: Int,
    val tid: Int,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val isCrash: Boolean = false,
    /** 多行日志的续行 */
    val isContinuation: Boolean = false,
    /** 未解析的原始行 */
    val isRaw: Boolean = false
) {
    /**
     * 命中的内置问题规则位掩码（第 i 位对应 IssueRules.builtin[i]），0 表示没命中。
     *
     * 每条日志只算一次、算完就缓存：UI 每 160ms 刷一遍列表时只读这个 int，
     * 不再对整缓冲重跑 11 条正则（那是主线程 60% + GC 40% 的来源）。
     * 只在 UI 线程读，用 NONE 模式省掉同步开销。
     */
    val issueMask: Int by lazy(LazyThreadSafetyMode.NONE) { IssueRules.maskOf(tag, message) }

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        return message.contains(query, ignoreCase = true) ||
            tag.contains(query, ignoreCase = true) ||
            time.contains(query, ignoreCase = true)
    }
}
