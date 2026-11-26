package dev.loglens.logcat

import dev.loglens.data.LogEntry
import dev.loglens.data.LogLevel

/**
 * 解析 `logcat -v threadtime` 的输出。
 *
 * 正常行形如：
 * `10-27 14:03:22.123  1234  1250 I ActivityManager: Start proc 4321:com.tencent.mm/u0a123`
 */
object LogcatParser {

    /**
     * logcat 的 threadtime 只给 MM-dd，没有年份。
     * 跨天/跨年占日志时（11-20、11-25 甚至去年 12 月混在一起）时间轴会彻底乱掉，
     * 所以这里按「单调不减」推年份：本月之后的月份视为去年，12 月→1 月则进位到下一年。
     */
    private var lastYear = -1
    private var lastMonth = -1

    /**
     * 同一秒里几百行日志的 MM-dd 是同一个，日期补全结果也是同一个。
     * 原来这里每行都要 split + Calendar.getInstance() + String.format()（后者一次就是几微秒），
     * 高频刷屏时它是解析线程最大的固定开销之一。缓存上一次的输入/输出即可。
     */
    private var dateKey: String? = null
    private var dateOut: String = ""
    private var dateYear = -1
    private var dateMonth = -1

    @Synchronized
    fun resetClock() {
        lastYear = -1
        lastMonth = -1
        dateKey = null
    }

    @Synchronized
    private fun fullDate(mmdd: String): String {
        val cached = dateKey
        if (cached == mmdd && dateYear == lastYear && dateMonth == lastMonth) return dateOut
        val parts = mmdd.split("-")
        val m = parts.getOrNull(0)?.toIntOrNull() ?: return mmdd
        val d = parts.getOrNull(1) ?: "01"
        val now = java.util.Calendar.getInstance()
        val nowYear = now.get(java.util.Calendar.YEAR)
        val nowMonth = now.get(java.util.Calendar.MONTH) + 1
        val year = when {
            lastYear < 0 -> if (m > nowMonth) nowYear - 1 else nowYear
            m < lastMonth - 6 -> lastYear + 1
            m > lastMonth + 6 -> lastYear - 1
            else -> lastYear
        }
        lastYear = year
        lastMonth = m
        // 拼字符串代替 String.format：format 要解析格式串 + 走 Formatter，单次开销大得多
        val out = year.toString() + "-" + m.toString().padStart(2, '0') + "-" +
            d.padStart(2, '0')
        dateKey = mmdd
        dateOut = out
        dateYear = lastYear
        dateMonth = lastMonth
        return out
    }

    private val THREADTIME = Regex(
        "^(\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.*?)\\s*:[ ]?(.*)$"
    )

    /** events buffer 的 brief 行：`10-27 14:03:22.123 I/am_proc_start( 1234): [0,4321,...]` */
    private val BRIEF = Regex(
        "^(\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+([VDIWEFA])/(.*?)\\(\\s*(\\d+)\\)\\s*:[ ]?(.*)$"
    )

    private val DATE_PREFIX = Regex("^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}")

    private val CRASH_MARKERS = listOf(
        "FATAL EXCEPTION",
        "ANR in ",
        "Force finishing activity",
        "Force stopping ",
        "*** FATAL EXCEPTION",
        "signal 11 (SIGSEGV)",
        "signal 6 (SIGABRT)",
        "signal 7 (SIGBUS)",
        "Fatal signal",
        "Process: ",
        "backtrace:",
        "Native crash"
    )

    fun startsWithTimestamp(line: String): Boolean = DATE_PREFIX.containsMatchIn(line)

    fun isCrash(line: String): Boolean = CRASH_MARKERS.any { line.contains(it) }

    /**
     * @return 解析结果；无法解析且不像新行时返回 null（调用方按续行处理）
     */
    fun parse(line: String, nextId: () -> Long): LogEntry? {
        THREADTIME.find(line)?.let { m ->
            val (date, clock, pid, tid, lvl, tag, msg) = m.destructured
            val level = LogLevel.fromChar(lvl[0])
            return LogEntry(
                id = nextId(),
                time = "${fullDate(date)} $clock",
                pid = pid.toIntOrNull() ?: 0,
                tid = tid.toIntOrNull() ?: 0,
                level = level,
                tag = tag.trim().ifEmpty { "-" },
                message = msg,
                isCrash = isCrash(line)
            )
        }
        BRIEF.find(line)?.let { m ->
            val (date, clock, lvl, tag, pid, msg) = m.destructured
            return LogEntry(
                id = nextId(),
                time = "${fullDate(date)} $clock",
                pid = pid.toIntOrNull() ?: 0,
                tid = 0,
                level = LogLevel.fromChar(lvl[0]),
                tag = tag.trim().ifEmpty { "events" },
                message = msg,
                isCrash = isCrash(line)
            )
        }
        if (startsWithTimestamp(line)) {
            // 有时间戳但格式不认识：当作原始行，避免误并入上一条
            return LogEntry(
                id = nextId(),
                time = "",
                pid = 0,
                tid = 0,
                level = LogLevel.RAW,
                tag = "raw",
                message = line,
                isCrash = isCrash(line),
                isRaw = true
            )
        }
        return null
    }
}
