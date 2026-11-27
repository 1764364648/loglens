package dev.loglens.data

/**
 * 前端过滤条件。采集层不做过滤（避免误丢日志），全部在这里过一遍。
 *
 * 命中规则：级别 ≥ minPriority，且 tag 命中 include（为空不限），且 tag 不命中 exclude，
 * 且 pid 在 pids 里（为空不限），且崩溃/书签开关满足，且 query 表达式命中。
 */
data class LogFilter(
    val query: String = "",
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val minPriority: Int = 0,
    val onlyCrash: Boolean = false,
    val onlyBookmarked: Boolean = false,
    val tagsInclude: List<String> = emptyList(),
    val tagsExclude: List<String> = emptyList(),
    val pids: Set<Int> = emptySet(),
) {
    companion object {
        /** tag 输入框支持中英文逗号、空格、竖线混写 */
        fun splitTags(raw: String): List<String> =
            raw.split(',', '，', '|', ' ', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
    }
}

fun LogFilter.compile(): CompiledFilter = CompiledFilter(this)

/**
 * 编译后的过滤器：正则/语法树只构建一次，逐行判断时零分配（高亮除外）。
 *
 * query 走 Android Studio 那套 key-value 语法（tag: message: pid: is: age: …）；
 * 只有当 user 显式开了「正则」开关、且表达式里没有可识别的 key 时，才把整串当一个正则。
 */
class CompiledFilter(filter: LogFilter) {

    val minPriority = filter.minPriority
    private val onlyCrash = filter.onlyCrash
    private val onlyBookmarked = filter.onlyBookmarked
    private val pids = filter.pids
    private val include = filter.tagsInclude.map { it.lowercase() }.filter { it.isNotBlank() }
    private val exclude = filter.tagsExclude.map { it.lowercase() }.filter { it.isNotBlank() }

    private val program: QuerySyntax.Program?
    private val pattern: Regex?
    private val keyword: String?
    private val caseSensitive = filter.caseSensitive

    val regexInvalid: Boolean

    /** 需要高亮的字面量（来自查询语句里的裸词、tag:、message:） */
    val highlightTerms: List<String>

    init {
        val q = filter.query.trim()
        // 「正则」开关打开、且整串更像一条正则（含正则元字符）时，直接整串编译，
        // 不再走 key-value 语法树。否则 `.*Exception.*` 会被当成一个裸词去做字面量包含，
        // 用户看到的就是「勾了正则却永远搜不到」。
        val rawRegex = filter.regex && q.isNotEmpty() && looksLikeRegex(q)
        val parsed = if (q.isEmpty() || rawRegex) null else QuerySyntax.parse(q)

        if (q.isEmpty()) {
            program = null; pattern = null; keyword = null
            regexInvalid = false; highlightTerms = emptyList()
        } else if (rawRegex) {
            val compiled = runCatching {
                if (filter.caseSensitive) Regex(q) else Regex(q, RegexOption.IGNORE_CASE)
            }
            program = null
            pattern = compiled.getOrNull()
            // 编译失败不要静默返回空页，把原串当关键字，同时让界面提示「正则写错了」
            keyword = if (compiled.isSuccess) null else q
            regexInvalid = compiled.isFailure
            highlightTerms = if (compiled.isSuccess) emptyList() else listOf(q)
        } else if (parsed != null) {
            program = parsed; pattern = null; keyword = null
            regexInvalid = false; highlightTerms = parsed.highlightTerms
        } else if (filter.regex) {
            // 语法解析失败（括号没闭合、key 缺值等），但用户开了正则：仍按正则再试一次
            val compiled = runCatching {
                if (filter.caseSensitive) Regex(q) else Regex(q, RegexOption.IGNORE_CASE)
            }
            program = null
            pattern = compiled.getOrNull()
            keyword = if (compiled.isSuccess) null else q
            regexInvalid = compiled.isFailure
            highlightTerms = if (compiled.isSuccess) emptyList() else listOf(q)
        } else {
            // 语法解析失败（比如括号没闭合），退化成本文子串匹配，比整页空白友好
            program = null; pattern = null; keyword = q
            regexInvalid = false; highlightTerms = listOf(q)
        }
    }

    fun accept(entry: LogEntry, bookmarked: Boolean): Boolean =
        accept(entry, bookmarked, env())

    /** 一次取好查询环境，批量判断时复用，避免每条日志都读时钟/进程表 */
    fun env(): QuerySyntax.Env = QueryEnvHolder.env(program).withCase(caseSensitive)

    fun accept(entry: LogEntry, bookmarked: Boolean, env: QuerySyntax.Env): Boolean {
        if (entry.level.priority < minPriority) return false
        return acceptIgnoringLevel(entry, bookmarked, env.withCase(caseSensitive))
    }

    /**
     * 不判级别、只判其余条件的版本。
     *
     * 「各级别条数」用的就是这半边：它和可视行的条件只差一个 minPriority，
     * 于是列表和计数可以在同一遍遍历里出结果，不用把整个缓冲扫两遍。
     */
    fun acceptIgnoringLevel(entry: LogEntry, bookmarked: Boolean, env: QuerySyntax.Env): Boolean {
        if (onlyCrash && !entry.isCrash) return false
        if (onlyBookmarked && !bookmarked) return false
        if (pids.isNotEmpty() && entry.pid !in pids) return false

        if (include.isNotEmpty() || exclude.isNotEmpty()) {
            val tag = if (caseSensitive) entry.tag else entry.tag.lowercase()
            if (include.isNotEmpty() && include.none { tag.contains(it) }) return false
            if (exclude.isNotEmpty() && exclude.any { tag.contains(it) }) return false
        }

        val prog = program
        if (prog != null) return prog.eval(entry, env)

        val re = pattern
        if (re != null) return re.containsMatchIn(entry.message) || re.containsMatchIn(entry.tag)

        val k = keyword
        if (k != null && k.isNotEmpty()) {
            return entry.message.contains(k, ignoreCase = !caseSensitive) ||
                entry.tag.contains(k, ignoreCase = !caseSensitive)
        }
        return true
    }

    /** 命中区间，用于正文高亮；最多 64 段，避免长日志拖慢滚动 */
    fun highlight(text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>(4)
        val re = pattern
        if (re != null) {
            runCatching {
                re.findAll(text).take(64).forEach { m -> if (m.value.isNotEmpty()) ranges.add(m.range) }
            }
            return ranges
        }
        for (term in highlightTerms) {
            if (ranges.size >= 64) break
            if (term.isEmpty()) continue
            var from = 0
            while (ranges.size < 64) {
                val idx = text.indexOf(term, from, ignoreCase = !caseSensitive)
                if (idx < 0) break
                ranges.add(idx until idx + term.length)
                from = idx + term.length
            }
        }
        return ranges
    }
}

/**
 * 判断「这串东西该不该当整条正则」：含正则元字符、且不是一条 key-value 语法查询。
 *
 * 只有两个条件都满足才走正则，避免把 `(tag:a|tag:b)` 这种语法写法误当正则，
 * 也避免 `Exception` 这种纯裸词被硬塞进 Regex 里编一遍。
 */
private val REGEX_META = Regex("""[\\^$.|?*+()\[\]{}]""")
private val SYNTAX_KEY = Regex(
    """(?:^|\s)(?:tag|message|msg|line|package|pkg|app|process|proc|pid|tid|level|lvl|is|age|name)[~=]?:(?=\S)""",
    RegexOption.IGNORE_CASE
)

private fun looksLikeRegex(query: String): Boolean =
    REGEX_META.containsMatchIn(query) && !SYNTAX_KEY.containsMatchIn(query)

/**
 * age: 这类条件依赖「现在几点」，不能把 Env 在编译期定死；
 * 但也不能每行都读一遍进程表和时钟。按秒缓存，滚动时开销可以忽略。
 */
internal object QueryEnvHolder {

    private var cachedAt = 0L
    private var cached: QuerySyntax.Env = QuerySyntax.Env()

    fun env(program: QuerySyntax.Program?): QuerySyntax.Env {
        if (program == null) return EMPTY
        if (!program.usesAge) {
            // 不用时间条件时退化成只看进程表，5 秒刷一次足够
            val now = System.currentTimeMillis()
            if (now - cachedAt > 5_000L) refresh(now)
            return cached
        }
        val now = System.currentTimeMillis()
        if (now - cachedAt > 1_000L) refresh(now)
        return cached
    }

    private fun refresh(now: Long) {
        cached = QuerySyntax.Env(ProcNames.snapshot(), now)
        cachedAt = now
    }

    val EMPTY = QuerySyntax.Env()
}

