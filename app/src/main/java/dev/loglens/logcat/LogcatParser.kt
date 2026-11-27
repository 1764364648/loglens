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
     * 年份推断的状态。
     *
     * logcat 的 threadtime 只给 MM-dd，没有年份，跨天/跨年占日志时按「单调不减」推：
     * 本月之后的月份视为去年，12 月→1 月进位到下一年。
     *
     * 这份状态**必须每路日志流各持一个**：主日志流和 events 流是两个线程同时解析，
     * 放在 object 上时 `@Synchronized` 只能保住原子性，语义仍会交叉污染——events 的
     * 日期和 main 的日期不同步时 `lastMonth` 会来回跳，跨年判断整段错位。
     */
    class Clock internal constructor() {

        private var lastYear = -1
        private var lastMonth = -1

        /**
         * 同一秒里几百行日志的 MM-dd 是同一个，日期补全结果也是同一个。
         * 原来每行都要 split + Calendar.getInstance() + String.format()（后者一次就是几微秒），
         * 高频刷屏时它是解析线程最大的固定开销之一。缓存上一次的输入/输出即可。
         */
        private var dateKey: String? = null
        private var dateOut: String = ""
        private var dateYear = -1
        private var dateMonth = -1

        internal fun reset() {
            synchronized(this) {
                lastYear = -1
                lastMonth = -1
                dateKey = null
            }
        }

        internal fun fullDate(mmdd: String): String = synchronized(this) {
            val cached = dateKey
            if (cached == mmdd && dateYear == lastYear && dateMonth == lastMonth) {
                dateOut
            } else {
                val parts = mmdd.split("-")
                val y3 = parts.getOrNull(0)?.toIntOrNull()
                val m3 = parts.getOrNull(1)?.toIntOrNull()
                // `-v year` 时 logcat 自己带了年份，直接用，不要再按「单调不减」猜一遍
                if (parts.size == 3 && y3 != null && m3 != null) {
                    lastYear = y3
                    lastMonth = m3
                    dateKey = mmdd
                    dateOut = mmdd
                    dateYear = y3
                    dateMonth = m3
                    mmdd
                } else {
                    val m = y3
                    if (m == null) {
                        mmdd
                    } else {
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
                        out
                    }
                }
            }
        }
    }

    /** 不传 clock 的调用方（单测、一次性解析）共用的默认状态 */
    private val defaultClock = Clock()

    fun resetClock() = defaultClock.reset()

    private val THREADTIME = Regex(
        "^((?:\\d{4}-)?\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.*?)\\s*:[ ]?(.*)$"
    )

    /** events buffer 的 brief 行：`10-27 14:03:22.123 I/am_proc_start( 1234): [0,4321,...]` */
    private val BRIEF = Regex(
        "^((?:\\d{4}-)?\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+([VDIWEFA])/(.*?)\\(\\s*(\\d+)\\)\\s*:[ ]?(.*)$"
    )

    // `-v year` 会在日期前多一个 `yyyy-`；不认它，整行就匹配不上，被当成上一行的续行
    private val DATE_PREFIX = Regex("^(?:\\d{4}-)?\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}")

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

    /**
     * 逐行关键字匹配：一条异常会贡献好几条（`Process: `、`FATAL EXCEPTION`、`backtrace:` 都算），
     * 所以它的计数是**崩溃行数**，不是崩溃次数。按签名归集的次数在 `CrashStats`。
     */
    fun isCrash(line: String): Boolean = CRASH_MARKERS.any { line.contains(it) }

    /**
     * @param clock 年份推断状态，由调用方按日志流各持一个；不传则共用默认状态。
     *   放在 `nextId` 前面，是为了让 `parse(line) { ... }` 这种尾随 lambda 写法仍然成立。
     * @param nextId id 分配器，单测里直接给自增值
     * @return 解析结果；无法解析且不像新行时返回 null（调用方按续行处理）
     */
    fun parse(line: String, clock: Clock = defaultClock, nextId: () -> Long): LogEntry? {
        THREADTIME.find(line)?.let { m ->
            val (date, time, pid, tid, lvl, tag, msg) = m.destructured
            val level = LogLevel.fromChar(lvl[0])
            return LogEntry(
                id = nextId(),
                time = "${clock.fullDate(date)} $time",
                pid = pid.toIntOrNull() ?: 0,
                tid = tid.toIntOrNull() ?: 0,
                level = level,
                tag = tag.trim().ifEmpty { "-" },
                message = msg,
                isCrash = isCrash(line)
            )
        }
        BRIEF.find(line)?.let { m ->
            val (date, time, lvl, tag, pid, msg) = m.destructured
            return LogEntry(
                id = nextId(),
                time = "${clock.fullDate(date)} $time",
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
