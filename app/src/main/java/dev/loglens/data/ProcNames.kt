package dev.loglens.data

import dev.loglens.shizuku.ShellRunner

/**
 * pid → 进程名快照。
 *
 * logcat 只给 pid/tid，不给进程名。要让 `package:` `process:` 这类查询有东西可比，
 * 就得先有一张 pid 表。这张表由 Shizuku shell 拉 `ps` 生成，几秒内过期也无所谓——
 * 过滤是给人看日志用的，不是拿来做进程审计。
 *
 * 普通 class，实例由 [AppGraph] 注入；`ps` 的取数方式从构造参数进（默认就是 Shizuku shell），
 * 测试里换成固定输出即可验证解析与过期逻辑，不必起设备。
 */
class ProcNames(
    private val exec: (String, Long) -> ShellRunner.Result = { cmd, timeout ->
        ShellRunner.exec(cmd, timeout)
    }
) {

    @Volatile
    private var table: Map<Int, String> = emptyMap()

    @Volatile
    var updatedAt: Long = 0L
        private set

    fun snapshot(): Map<Int, String> = table

    fun nameOf(pid: Int): String? = table[pid]

    fun isStale(ttlMs: Long = 15_000L): Boolean =
        table.isEmpty() || System.currentTimeMillis() - updatedAt > ttlMs

    /** 拉一次 ps 表。失败保留旧表，不清空，避免过滤条件突然全空。 */
    fun refresh(): Boolean {
        val text = runCatching { exec("ps -A -o PID,NAME", 8_000L) }.getOrNull()
            ?.takeIf { it.code == 0 }
            ?.stdout
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { exec("ps -A", 8_000L) }.getOrNull()
                ?.takeIf { it.code == 0 }
                ?.stdout
            ?: return false

        val parsed = parse(text)
        if (parsed.isEmpty()) return false
        table = parsed
        updatedAt = System.currentTimeMillis()
        return true
    }

    /** 兼容 `ps -A -o PID,NAME` 与老式 `ps -A`（USER PID PPID ... NAME）两种列序 */
    private fun parse(text: String): Map<Int, String> {
        val out = HashMap<Int, String>(256)
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("USER") || line.startsWith("PID")) continue
            val cols = line.split(Regex("\\s+"))
            if (cols.size < 2) continue
            val pid = cols[1].toIntOrNull() ?: cols[0].toIntOrNull() ?: continue
            val name = cols.last()
            if (name.isBlank()) continue
            out[pid] = name
        }
        return out
    }

    fun clear() {
        table = emptyMap()
        updatedAt = 0L
    }
}
