package dev.loglens.data

/**
 * logcat 缓冲区选择。main/system/crash 日常够用；
 * events 需要单独一条流做包名过滤，kernel、radio 属于进阶排查。
 */
enum class LogBuffer(val id: String, val label: String, val note: String) {
    MAIN("main", "main", "应用与框架常规日志"),
    SYSTEM("system", "system", "系统服务、AMS/PMS 等"),
    CRASH("crash", "crash", "Java 崩溃与 ANR 现场"),
    EVENTS("events", "events", "生命周期事件（需按包名过滤）"),
    KERNEL("kernel", "kernel", "内核日志，native 崩溃排查"),
    RADIO("radio", "radio", "射频、通话与网络"),
    ;

    companion object {
        fun of(id: String): LogBuffer? = entries.firstOrNull { it.id == id }

        /** 默认集合：main + system + crash */
        val DEFAULT: Set<LogBuffer> = setOf(MAIN, SYSTEM, CRASH)

        /** 解析 "main,system,crash" 这类逗号串，非法项忽略，空则退回默认 */
        fun parse(csv: String): Set<LogBuffer> {
            val parsed = csv.split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .mapNotNull { of(it) }
                .toSet()
            return parsed.ifEmpty { DEFAULT }
        }

        fun encode(buffers: Set<LogBuffer>): String =
            LogBuffer.entries.filter { it in buffers }.joinToString(",") { it.id }

        /** 除 events 之外的合并流缓冲区串；events 单独起流 */
        fun mainSources(buffers: Set<LogBuffer>): String {
            val rest = LogBuffer.entries.filter { it in buffers && it != EVENTS }
            return (if (rest.isEmpty()) listOf(MAIN) else rest).joinToString(",") { it.id }
        }

        fun hasEvents(buffers: Set<LogBuffer>): Boolean = buffers.contains(EVENTS)
    }
}
