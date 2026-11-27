package dev.loglens.data

/**
 * Android Studio 风格的 key-value 查询语法。
 *
 * 支持：
 *   tag:  message:  line:  package:  process:  pid:  tid:
 *   tag~: （正则）  tag=: （整字段相等）  level:  is:  age:
 *   -前缀取反   &  |  括号   "带空格的短语"
 *
 * 之所以自己写解析器而不是套一层正则：这类查询会被逐行调用几万次，
 * 解析一次成树，之后每行只走布尔运算，代价可控。
 */
object QuerySyntax {

    class ParseException(message: String) : Exception(message)

    /** 求值环境。进程名表可能为空（未授权/ps 失败），此时 package:/process: 退化成匹配不到。 */
    class Env(
        val procNames: Map<Int, String> = emptyMap(),
        val nowMs: Long = System.currentTimeMillis(),
        /**
         * 是否区分大小写。默认 false（和 Android Studio 的查询一致）；
         * 界面上的「区分大小写」开关会把它传进来，字面量匹配才真的区分大小写。
         */
        val caseSensitive: Boolean = false
    ) {
        /** 换成另一套大小写策略，进程表/时钟不变 */
        fun withCase(sensitive: Boolean): Env =
            if (sensitive == caseSensitive) this else Env(procNames, nowMs, sensitive)
    }

    fun interface Node {
        fun eval(entry: LogEntry, env: Env): Boolean
    }

    /** 解析结果：语法树 + 需要高亮的字面量 */
    class Program(
        val root: Node,
        val highlightTerms: List<String>,
        val usesAge: Boolean = false
    ) {
        fun eval(entry: LogEntry, env: Env): Boolean = root.eval(entry, env)
    }

    fun parse(raw: String): Program? {
        val tokens = tokenize(raw) ?: return null
        if (tokens.isEmpty()) return null
        val parser = Parser(tokens)
        val root = runCatching { parser.parseExpr() }.getOrNull() ?: return null
        if (!parser.atEnd()) return null
        return Program(root, parser.highlights.distinct().filter { it.isNotBlank() }, parser.sawAge)
    }

    // ── 词法 ──────────────────────────────────────────────
    private sealed interface Tok {
        data class Word(val text: String) : Tok
        data object And : Tok
        data object Or : Tok
        data object LParen : Tok
        data object RParen : Tok
        data object Not : Tok
    }

    private fun tokenize(raw: String): List<Tok>? {
        val out = ArrayList<Tok>(8)
        val sb = StringBuilder()
        var i = 0
        var quoted = false

        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(Tok.Word(sb.toString()))
                sb.setLength(0)
            }
        }

        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '"' -> {
                    quoted = !quoted
                    i++
                }
                quoted -> {
                    sb.append(c)
                    i++
                }
                c.isWhitespace() -> {
                    flush()
                    i++
                }
                c == '&' -> {
                    flush(); out.add(Tok.And); i++
                }
                c == '|' -> {
                    flush(); out.add(Tok.Or); i++
                }
                c == '(' -> {
                    flush(); out.add(Tok.LParen); i++
                }
                c == ')' -> {
                    flush(); out.add(Tok.RParen); i++
                }
                // 词首的 - 视作取反；词中的 - 属于内容（如 tag:xxx-yyy）
                c == '-' && sb.isEmpty() -> {
                    out.add(Tok.Not); i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        if (quoted) return null
        flush()
        return out
    }

    // ── 语法 ──────────────────────────────────────────────
    private class Parser(private val tokens: List<Tok>) {
        private var pos = 0
        val highlights = ArrayList<String>(4)
        var sawAge = false
            private set

        fun atEnd(): Boolean = pos >= tokens.size

        fun parseExpr(): Node = parseOr()

        private fun parseOr(): Node {
            var left = parseAnd()
            while (true) {
                val t = tokens.getOrNull(pos)
                if (t is Tok.Or) {
                    pos++
                    val right = parseAnd()
                    val l = left
                    left = Node { e, env -> l.eval(e, env) || right.eval(e, env) }
                } else {
                    return left
                }
            }
        }

        private fun parseAnd(): Node {
            var left = parseUnary()
            while (true) {
                val t = tokens.getOrNull(pos)
                if (t is Tok.And) {
                    pos++
                    val right = parseUnary()
                    val l = left
                    left = Node { e, env -> l.eval(e, env) && right.eval(e, env) }
                } else if (t is Tok.Word || t is Tok.LParen || t is Tok.Not) {
                    // 相邻词默认按 AND 处理，和 Android Studio 一致
                    val right = parseUnary()
                    val l = left
                    left = Node { e, env -> l.eval(e, env) && right.eval(e, env) }
                } else {
                    return left
                }
            }
        }

        private fun parseUnary(): Node {
            val t = tokens.getOrNull(pos) ?: throw ParseException("表达式不完整")
            return when (t) {
                is Tok.Not -> {
                    pos++
                    val inner = parseUnary()
                    Node { e, env -> !inner.eval(e, env) }
                }
                is Tok.LParen -> {
                    pos++
                    val inner = parseOr()
                    if (tokens.getOrNull(pos) !is Tok.RParen) throw ParseException("括号未闭合")
                    pos++
                    inner
                }
                is Tok.Word -> {
                    pos++
                    atom(t.text)
                }
                else -> throw ParseException("意外的符号")
            }
        }

        private fun atom(word: String): Node {
            val idx = word.indexOf(':')
            if (idx <= 0) {
                // 裸词：全文（消息 / tag / 时间）子串匹配
                highlights.add(word)
                return Node { e, env ->
                    val ic = !env.caseSensitive
                    e.message.contains(word, ic) ||
                        e.tag.contains(word, ic) ||
                        e.time.contains(word, ic)
                }
            }
            var key = word.substring(0, idx).lowercase()
            val value = word.substring(idx + 1)
            if (value.isEmpty()) throw ParseException("缺少值")

            var mode = Mode.CONTAINS
            if (key.endsWith("~")) {
                mode = Mode.REGEX
                key = key.dropLast(1)
            } else if (key.endsWith("=")) {
                mode = Mode.EQUALS
                key = key.dropLast(1)
            }

            when (key) {
                "tag" -> {
                    if (mode == Mode.CONTAINS) highlights.add(value)
                    val re = if (mode == Mode.REGEX) compileOrThrow(value) else null
                    return Node { e, env -> matchField(e.tag, value, mode, re, env.caseSensitive) }
                }
                "message", "msg" -> {
                    if (mode == Mode.CONTAINS) highlights.add(value)
                    val re = if (mode == Mode.REGEX) compileOrThrow(value) else null
                    return Node { e, env -> matchField(e.message, value, mode, re, env.caseSensitive) }
                }
                "line" -> {
                    val re = compileOrThrow(value)
                    return Node { e, _ -> re.containsMatchIn(e.message) }
                }
                "package", "pkg", "app" -> {
                    // 正则必须在解析期编译：放进 eval 会逐行编译，且非法正则会以未捕获异常
                    // 从过滤循环里抛到 Compose，直接崩界面
                    val re = if (mode == Mode.REGEX) compileOrThrow(value) else null
                    return Node { e, env ->
                        val name = env.procNames[e.pid] ?: return@Node false
                        matchField(name, value, mode, re, env.caseSensitive)
                    }
                }
                "process", "proc" -> {
                    val re = if (mode == Mode.REGEX) compileOrThrow(value) else null
                    return Node { e, env ->
                        val name = env.procNames[e.pid] ?: return@Node false
                        matchField(name, value, mode, re, env.caseSensitive)
                    }
                }
                "pid" -> {
                    val set = splitInts(value) ?: throw ParseException("pid 需要数字")
                    return Node { e, _ -> e.pid in set }
                }
                "tid" -> {
                    val set = splitInts(value) ?: throw ParseException("tid 需要数字")
                    return Node { e, _ -> e.tid in set }
                }
                "level", "lvl" -> {
                    val p = levelPriority(value) ?: throw ParseException("未知级别")
                    // RAW 的 priority 是 -1，用 >= 会恒真；raw 必须按等值匹配
                    if (value.equals("raw", true)) {
                        return Node { e, _ -> e.level == LogLevel.RAW }
                    }
                    return Node { e, _ -> e.level.priority >= p }
                }
                "is" -> {
                    val v = value.lowercase()
                    val lvl = levelPriority(v)
                    return Node { e, _ ->
                        when (v) {
                            "crash", "fatal" -> e.isCrash || e.level == LogLevel.FATAL
                            "stacktrace", "stack" -> e.isContinuation ||
                                e.message.startsWith("at ") ||
                                e.message.startsWith("Caused by")
                            "firebase" -> e.tag.startsWith("Firebase")
                            "raw" -> e.level == LogLevel.RAW
                            else -> if (lvl != null) e.level.priority >= lvl else false
                        }
                    }
                }
                "age" -> {
                    val ms = parseAge(value) ?: throw ParseException("age 需要形如 30s/5m/2h/1d")
                    sawAge = true
                    return Node { e, env ->
                        val t = LogTime.millisOf(e.time) ?: return@Node false
                        env.nowMs - t <= ms
                    }
                }
                "name" -> return Node { _, _ -> true } // 预设名由 UI 层处理，这里不拦
                else -> {
                    // 不认识的 key 当普通文本查，避免用户写习惯性前缀就整页空白
                    highlights.add(word)
                    return Node { e, env ->
                        e.message.contains(word, !env.caseSensitive) ||
                            e.tag.contains(word, !env.caseSensitive)
                    }
                }
            }
        }

        private fun compileOrThrow(src: String): Regex =
            runCatching { Regex(src, RegexOption.IGNORE_CASE) }
                .getOrElse { throw ParseException("正则写法有误：$src") }
    }

    private enum class Mode { CONTAINS, EQUALS, REGEX }

    private fun matchField(field: String, value: String, mode: Mode, re: Regex?, caseSensitive: Boolean): Boolean =
        when (mode) {
            Mode.CONTAINS -> field.contains(value, ignoreCase = !caseSensitive)
            Mode.EQUALS -> field.equals(value, ignoreCase = !caseSensitive)
            Mode.REGEX -> re?.containsMatchIn(field) == true
        }

    private fun splitInts(raw: String): Set<Int>? {
        val parts = raw.split(',', '，', '|', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val out = HashSet<Int>(parts.size)
        for (p in parts) out.add(p.toIntOrNull() ?: return null)
        return out
    }

    fun levelPriority(raw: String): Int? = when (raw.lowercase()) {
        "v", "verbose" -> LogLevel.VERBOSE.priority
        "d", "debug" -> LogLevel.DEBUG.priority
        "i", "info" -> LogLevel.INFO.priority
        "w", "warn", "warning" -> LogLevel.WARN.priority
        "e", "error" -> LogLevel.ERROR.priority
        "f", "fatal", "a", "assert" -> LogLevel.FATAL.priority
        "raw" -> LogLevel.RAW.priority
        else -> null
    }

    private fun parseAge(raw: String): Long? {
        val v = raw.trim().lowercase()
        if (v.isEmpty()) return null
        val unit = v.last()
        val num = v.dropLast(1).toDoubleOrNull() ?: return null
        val factor = when (unit) {
            's' -> 1_000L
            'm' -> 60_000L
            'h' -> 3_600_000L
            'd' -> 86_400_000L
            else -> return null
        }
        return (num * factor).toLong()
    }
}

/**
 * logcat 的时间只有「月-日 时:分:秒.毫秒」，没有年份，也没有时区。
 * 这里按本机当前年份补全，够用：没人会拿它做跨年日志的时间轴。
 */
object LogTime {

    // 年份可选：本 App 给 logcat 行补过年份（跨年日志会带 yyyy-），离线导入也可能带；
    // 不解析年份会把去年 12 月的日志当成今年 12 月（age: 会把未来时间判成「刚刚」）
    private val full = Regex("""(?:(\d{4})-)?(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{1,3})""")
    private val short = Regex("""(\d{2}):(\d{2}):(\d{2})\.(\d{1,3})""")

    fun millisOf(time: String): Long? {
        if (time.isBlank()) return null
        full.find(time)?.let { m ->
            val year = m.groupValues[1].toIntOrNull() ?: java.util.Calendar.getInstance()
                .get(java.util.Calendar.YEAR)
            val month = m.groupValues[2].toIntOrNull() ?: return null
            val day = m.groupValues[3].toIntOrNull() ?: return null
            val cal = java.util.Calendar.getInstance().apply { clear() }
            cal.set(
                year,
                month - 1,
                day,
                m.groupValues[4].toInt(),
                m.groupValues[5].toInt(),
                m.groupValues[6].toInt()
            )
            cal.set(java.util.Calendar.MILLISECOND, m.groupValues[7].padEnd(3, '0').toInt())
            return cal.timeInMillis
        }
        short.find(time)?.let { m ->
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[1].toInt())
            cal.set(java.util.Calendar.MINUTE, m.groupValues[2].toInt())
            cal.set(java.util.Calendar.SECOND, m.groupValues[3].toInt())
            cal.set(java.util.Calendar.MILLISECOND, m.groupValues[4].padEnd(3, '0').toInt())
            return cal.timeInMillis
        }
        return null
    }
}

