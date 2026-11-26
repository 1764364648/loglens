package dev.loglens.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 离线日志导入：把别人发来的 logcat 文本、或自己之前导出的 txt 拖回来分析。
 * 支持 threadtime / time / brief 三种常见排版，认不出的行按 RAW 保留，绝不丢内容。
 */
object OfflineImport {

    // 日期段年份可选：新版导出带 yyyy-，系统原生日志只有 MM-dd
    private val threadTime = Regex(
        "^\\s*((?:\\d{4}-)?\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+([^:]*?)\\s*:\\s?(.*)$"
    )
    private val timeOnly = Regex(
        "^\\s*(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+([^:]*?)\\s*:\\s?(.*)$"
    )
    /** logcat -v time：`11-27 14:03:22.123 I/Tag( 1234): message` */
    private val logcatTime = Regex(
        "^\\s*((?:\\d{4}-)?\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+([VDIWEFA])/([^\\s(]+)\\s*\\(\\s*(\\d+)\\):\\s?(.*)$"
    )
    private val brief = Regex("^\\s*([VDIWEFA])/([^\\s(]+)\\s*\\(\\s*(\\d+)\\):\\s?(.*)$")

    /** 补齐年份：没写年份的按当前年推，出现比现在更晚的月份视为去年 */
    private fun normalize(date: String): String {
        val t = date.trim()
        if (t.length >= 10 && t[4] == '-') return t
        val m = t.take(2).toIntOrNull() ?: return t
        val now = java.util.Calendar.getInstance()
        val y = if (m > now.get(java.util.Calendar.MONTH) + 1) now.get(java.util.Calendar.YEAR) - 1 else now.get(java.util.Calendar.YEAR)
        return "%04d-%s".format(y, t)
    }

    fun parse(text: String, maxLines: Int = 200_000): List<LogEntry> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<LogEntry>(1024)
        var last: LogEntry? = null
        var id = 0L
        for (raw in text.lineSequence()) {
            if (out.size >= maxLines) break
            if (raw.isEmpty()) continue
            val t = threadTime.find(raw)
            val lc = if (t == null) logcatTime.find(raw) else null
            val o = if (t == null && lc == null) timeOnly.find(raw) else null
            val b = if (t == null && lc == null && o == null) brief.find(raw) else null
            when {
                t != null -> {
                    id += 1
                    val e = LogEntry(
                        id = id,
                        time = normalize(t.groupValues[1]),
                        pid = t.groupValues[2].toIntOrNull() ?: 0,
                        tid = t.groupValues[3].toIntOrNull() ?: 0,
                        level = levelOf(t.groupValues[4]),
                        tag = t.groupValues[5].trim(),
                        message = t.groupValues[6],
                        isCrash = false,
                        isContinuation = false,
                        isRaw = false
                    )
                    out.add(e)
                    last = e
                }
                lc != null -> {
                    id += 1
                    val e = LogEntry(
                        id = id,
                        time = normalize(lc.groupValues[1]),
                        pid = lc.groupValues[4].toIntOrNull() ?: 0,
                        tid = 0,
                        level = levelOf(lc.groupValues[2]),
                        tag = lc.groupValues[3].trim(),
                        message = lc.groupValues[5],
                        isCrash = false,
                        isContinuation = false,
                        isRaw = false
                    )
                    out.add(e)
                    last = e
                }
                o != null -> {
                    id += 1
                    val e = LogEntry(
                        id = id,
                        time = o.groupValues[1].trim(),
                        pid = o.groupValues[2].toIntOrNull() ?: 0,
                        tid = o.groupValues[3].toIntOrNull() ?: 0,
                        level = levelOf(o.groupValues[4]),
                        tag = o.groupValues[5].trim(),
                        message = o.groupValues[6],
                        isCrash = false,
                        isContinuation = false,
                        isRaw = false
                    )
                    out.add(e)
                    last = e
                }
                b != null -> {
                    id += 1
                    val e = LogEntry(
                        id = id,
                        time = "",
                        pid = b.groupValues[3].toIntOrNull() ?: 0,
                        tid = 0,
                        level = levelOf(b.groupValues[1]),
                        tag = b.groupValues[2].trim(),
                        message = b.groupValues[4],
                        isCrash = false,
                        isContinuation = false,
                        isRaw = false
                    )
                    out.add(e)
                    last = e
                }
                else -> {
                    val prev = last
                    if (prev != null && (raw.startsWith(" ") || raw.startsWith("\t") || raw.startsWith("at "))) {
                        // 堆栈续行：挂到上一条后面，保持可读性
                        id += 1
                        val cont = LogEntry(
                            id = id,
                            time = prev.time,
                            pid = prev.pid,
                            tid = prev.tid,
                            level = prev.level,
                            tag = prev.tag,
                            message = raw,
                            isCrash = prev.isCrash,
                            isContinuation = true,
                            isRaw = false
                        )
                        out.add(cont)
                        last = cont
                    } else {
                        id += 1
                        out.add(
                            LogEntry(
                                id = id,
                                time = "",
                                pid = 0,
                                tid = 0,
                                level = LogLevel.RAW,
                                tag = "",
                                message = raw,
                                isCrash = false,
                                isContinuation = false,
                                isRaw = true
                            )
                        )
                    }
                }
            }
        }
        // 多份日志合并导入时，各文件时间线会交错（11-20、11-25 混排），统一按完整时间戳重排
        out.sortBy { it.time }
        return out
    }

    private fun levelOf(letter: String): LogLevel = when (letter.trim()) {
        "V" -> LogLevel.VERBOSE
        "D" -> LogLevel.DEBUG
        "I" -> LogLevel.INFO
        "W" -> LogLevel.WARN
        "E" -> LogLevel.ERROR
        "F" -> LogLevel.FATAL
        "A" -> LogLevel.ASSERT
        else -> LogLevel.RAW
    }
}

/**
 * 当前正在看的离线日志。非空时日志页优先展示它，而不是实时缓冲。
 * 用 StateFlow 计版本号，UI 侧 collect 后就能自动刷新。
 */
object OfflineLog {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    @Volatile
    var entries: List<LogEntry> = emptyList()
        private set

    @Volatile
    var sourceName: String = ""
        private set

    fun load(list: List<LogEntry>, name: String) {
        entries = list
        sourceName = name
        _version.value = _version.value + 1
    }

    fun clear() {
        entries = emptyList()
        sourceName = ""
        _version.value = _version.value + 1
    }
}
