package dev.loglens.data

import java.util.ArrayDeque

/**
 * 全局日志仓库：环形缓冲 + 增量待消费队列。
 *
 * UI 侧不要每次全量刷新，而是周期性 [drain] 拿新增条目后插入列表头部，
 * 这样 LazyColumn 只做增量 diff，高频日志也不会 ANR。
 *
 * 这里是普通 class 而不是 object：实例由 [AppGraph] 注入，测试里可以 new 一个干净的仓库，
 * 不必在用例之间手工清静态状态。
 */
class LogRepository(private val defaultCapacity: Int = 5000) {

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>()
    private val pending = ArrayList<LogEntry>()

    private var seq = 0L

    private var totalValue = 0L
    private var crashValue = 0

    @Volatile
    var capacity: Int = defaultCapacity

    data class Stats(val total: Long, val crashes: Int)

    fun nextId(): Long = synchronized(lock) { ++seq }

    /**
     * 收一条日志。
     *
     * 这里刻意不写任何 StateFlow：每行都 `_version.value = ...` + `_stats.value = ...`
     * 等于每条日志两次并发原语写、两次收集器唤醒，而 UI 侧本来就是 160ms 批量取，
     * 中间值全被合并掉——纯浪费。计数只累加，UI 在自己的 tick 里读 [statsSnapshot]。
     */
    fun add(entry: LogEntry) {
        synchronized(lock) {
            buffer.addLast(entry)
            totalValue++
            if (entry.isCrash) {
                crashValue++
                CrashStats.record(entry)
            }
            while (buffer.size > capacity) buffer.removeFirst()
            pending.add(entry)
            if (pending.size > capacity) {
                // 极端刷屏场景，丢弃待消费队列头部，避免内存膨胀
                val drop = pending.size - capacity
                pending.subList(0, drop).clear()
            }
        }
    }

    fun drainPending(): List<LogEntry> = synchronized(lock) {
        if (pending.isEmpty()) return emptyList()
        val out = ArrayList(pending)
        pending.clear()
        out
    }

    /**
     * UI 初次挂载专用：在一次加锁内同时取走全量快照并清空待消费队列。
     *
     * 否则「seed = snapshot()」与「drainPending()」会返回同一批条目，
     * LazyColumn 的 key 重复会直接抛 IllegalArgumentException 把进程带走。
     *
     * @return 旧→新顺序（buffer 的写入顺序）
     */
    fun attach(): List<LogEntry> = synchronized(lock) {
        val out = ArrayList(buffer)
        pending.clear()
        out
    }

    fun snapshot(): List<LogEntry> = synchronized(lock) {
        ArrayList(buffer).also { it.reverse() }
    }

    /**
     * 最近 [limit] 条，按旧→新顺序返回。
     *
     * 注意 [snapshot] 返回的是新→旧；需要「最新 N 行」的调用方不要再用 takeLast，
     * 否则拿到的是缓冲区里最旧的一段（悬浮窗、崩溃快照曾因此显示错误内容）。
     */
    fun recent(limit: Int): List<LogEntry> = synchronized(lock) {
        if (limit <= 0 || buffer.isEmpty()) return emptyList()
        val n = minOf(limit, buffer.size)
        val out = ArrayList<LogEntry>(n)
        val it = buffer.descendingIterator()
        while (out.size < n && it.hasNext()) out.add(it.next())
        out.reverse()
        out
    }

    fun statsSnapshot(): Stats = synchronized(lock) { Stats(totalValue, crashValue) }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            pending.clear()
            totalValue = 0
            crashValue = 0
        }
    }
}
