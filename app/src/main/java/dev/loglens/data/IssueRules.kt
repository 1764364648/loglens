package dev.loglens.data

import java.util.regex.Pattern

/**
 * 问题体检：把散在几千行里的典型故障模式捞出来做归集统计。
 *
 * 和「只看崩溃」的区别在于：崩溃只是其中一类，ANR、OOM、权限拒绝、网络失败、
 * 掉帧卡顿、StrictMode 违规这些同样会让线上出问题，但不会留下 FATAL 标记。
 */
data class IssueRule(
    val id: String,
    val name: String,
    val pattern: String,
    val severity: Severity,
    val hint: String
) {
    enum class Severity(val label: String) { ERROR("错误"), WARN("警告"), INFO("提示") }
}

data class IssueHit(
    val rule: IssueRule,
    val count: Int,
    val sample: LogEntry,
    val lastSeen: String
)

object IssueRules {

    val builtin: List<IssueRule> = listOf(
        IssueRule(
            "crash", "Java 崩溃",
            "(FATAL EXCEPTION|AndroidRuntime.*FATAL|Uncaught exception)",
            IssueRule.Severity.ERROR,
            "Java 层抛出的未捕获异常，通常紧跟一段堆栈"
        ),
        IssueRule(
            "native_crash", "Native 崩溃",
            "(Fatal signal \\d+|SIGSEGV|SIGABRT|tombstone)",
            IssueRule.Severity.ERROR,
            "native 层崩溃，需要配合 kernel 缓冲与 tombstone 一起看"
        ),
        IssueRule(
            "anr", "ANR / 主线程卡死",
            "(ANR in |Application Not Responding|Input dispatching timed out|Broadcast of Intent.*timeout)",
            IssueRule.Severity.ERROR,
            "主线程被阻塞，重点看 CPU 占用与锁等待"
        ),
        IssueRule(
            "oom", "内存不足 / 被杀",
            "(OutOfMemoryError|lowmemorykiller|Out of memory|LMK.*kill|onTrimMemory.*COMPLETE)",
            IssueRule.Severity.ERROR,
            "内存压力过大，进程可能被系统回收"
        ),
        IssueRule(
            "permission", "权限被拒",
            "(Permission Denial|SecurityException|avc: *denied|does not have android\\.permission)",
            IssueRule.Severity.WARN,
            "权限或 SELinux 拒绝，和适配问题高度相关"
        ),
        IssueRule(
            "network", "网络失败",
            "(UnknownHostException|SocketTimeoutException|Connection refused|SSLHandshakeException|ETIMEDOUT|ENETUNREACH)",
            IssueRule.Severity.WARN,
            "请求发不出去或握手失败，先排查代理与证书"
        ),
        IssueRule(
            "jank", "掉帧 / 卡顿",
            "(Skipped \\d+ frames|Davey!|doFrame.*took \\d+ms)",
            IssueRule.Severity.WARN,
            "绘制超时，通常定位到具体帧与调用栈"
        ),
        IssueRule(
            "strictmode", "StrictMode 违规",
            "(StrictMode policy violation|policy violation.*DiskRead|policy violation.*Network)",
            IssueRule.Severity.WARN,
            "主线程做了磁盘或网络 IO"
        ),
        IssueRule(
            "dead_object", "资源泄漏 / 已释放对象",
            "(trying to use a recycled bitmap|A resource failed to call (close|release)|CursorWindow.*finalized)",
            IssueRule.Severity.WARN,
            "资源没释放，长时间运行会持续上涨"
        ),
        IssueRule(
            "native_dsp", "原生加速器 / DSP 失败",
            "(QnnDsp|QnnHtp|HtpSkel|libcdsprpc|adsprpc|fastrpc|OpenCL|Adreno).{0,80}(fail|error|unable|not found|invalid)",
            IssueRule.Severity.ERROR,
            "NPU/HTP 侧的加载或执行失败常常只打在自己的 tag 上，用户态日志很少给完整原因，需结合厂商侧日志与回调输出"
        ),
        IssueRule(
            "service_died", "服务/进程异常退出",
            "(Process .* has died|service died|Binder.*DEAD_OBJECT|has leaked ServiceConnection)",
            IssueRule.Severity.ERROR,
            "进程或 Binder 断链，常见于后台被清理"
        )
    )

    /**
     * 每条规则的低成本预筛关键词：日志里出现其中任意一个，才值得上正则。
     *
     * 必须是「正则能命中的行一定含某个关键词」的超集（否则会漏报），
     * 顺序与 [builtin] 严格对应，位掩码的第 i 位就是 builtin[i]。
     */
    private val prefilter: List<List<String>> = listOf(
        listOf("fatal exception", "androidruntime", "uncaught exception"),
        listOf("fatal signal", "sigsegv", "sigabrt", "tombstone"),
        listOf("anr in", "application not responding", "input dispatching timed out", "broadcast of intent"),
        listOf("outofmemoryerror", "lowmemorykiller", "out of memory", "lmk", "ontrimmemory"),
        listOf("permission denial", "securityexception", "avc:", "does not have android.permission"),
        listOf("unknownhostexception", "sockettimeoutexception", "connection refused", "sslhandshakeexception", "etimedout", "enetunreach"),
        listOf("skipped ", "davey!", "doframe"),
        listOf("strictmode policy violation", "policy violation"),
        listOf("recycled bitmap", "failed to call", "cursorwindow"),
        listOf("qnndsp", "qnnhtp", "htpskel", "libcdsprpc", "adsprpc", "fastrpc", "opencl", "adreno"),
        listOf("has died", "service died", "dead_object", "has leaked serviceconnection")
    )

    /**
     * 只编译一次。原来 [IssueRule] 里每次调用都 Pattern.compile，
     * 2500 行 × 每 160ms 一轮，光编译就把主线程和 GC 吃满了。
     */
    private val patterns: List<Pattern> = builtin.map { Pattern.compile(it.pattern, Pattern.CASE_INSENSITIVE) }

    init {
        // 预筛表必须与规则表一一对应，错位会让掩码指到别的规则上
        require(prefilter.size == builtin.size) { "IssueRules 预筛关键词表与规则表数量不一致" }
    }

    /**
     * 算一条日志命中了哪些规则，返回位掩码（0 表示没命中）。
     *
     * 先做无分配的关键词预筛（大小写不敏感 indexOf，不产生临时字符串），
     * 只有可疑的行才拼文本上正则——绝大多数日志会在这里直接落到 0。
     */
    fun maskOf(tag: String, message: String): Int {
        var mask = 0
        for (i in patterns.indices) {
            if (!candidate(tag, message, prefilter[i])) continue
            val text = if (tag.isEmpty()) message else "$tag $message"
            if (patterns[i].matcher(text).find()) mask = mask or (1 shl i)
        }
        return mask
    }

    private fun candidate(tag: String, message: String, keys: List<String>): Boolean {
        for (k in keys) {
            if (message.contains(k, ignoreCase = true) || tag.contains(k, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * 按规则归集；只统计命中的条数并留一条样本用于跳转。
     *
     * 判定已随「每条日志创建」算好（见 LogEntry.issueMask），这里只读掩码，
     * 所以整缓冲刷新一遍是 O(行数) 的位运算，不再是 O(行数 × 规则数) 的正则匹配。
     */
    fun scan(entries: List<LogEntry>): List<IssueHit> {
        if (entries.isEmpty()) return emptyList()
        val counts = IntArray(builtin.size)
        val samples = arrayOfNulls<LogEntry>(builtin.size)
        var any = false
        for (entry in entries) {
            var bits = entry.issueMask
            if (bits == 0) continue
            var i = 0
            while (bits != 0 && i < builtin.size) {
                if (bits and 1 == 1) {
                    counts[i]++
                    samples[i] = entry
                    any = true
                }
                bits = bits shr 1
                i++
            }
        }
        if (!any) return emptyList()
        val out = ArrayList<IssueHit>(builtin.size)
        for (i in builtin.indices) {
            val sample = samples[i] ?: continue
            out.add(IssueHit(builtin[i], counts[i], sample, sample.time))
        }
        return out.sortedWith(compareBy({ it.rule.severity.ordinal }, { -it.count }))
    }

    fun summary(hits: List<IssueHit>): String {
        val errorCount = hits.filter { it.rule.severity == IssueRule.Severity.ERROR }.sumOf { it.count }
        val warnCount = hits.filter { it.rule.severity == IssueRule.Severity.WARN }.sumOf { it.count }
        return when {
            errorCount > 0 -> "问题 $errorCount"
            warnCount > 0 -> "隐患 $warnCount"
            else -> "未发现异常"
        }
    }
}
