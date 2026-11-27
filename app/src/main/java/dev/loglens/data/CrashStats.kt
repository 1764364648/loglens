package dev.loglens.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 崩溃归集：同一种异常按签名合并计数，记首次/最近出现时间。
 *
 * 对照组里的 LogDroid 就靠这个：不关心某一条崩溃，而是「这个异常今天第几次了、是不是新出现的」。
 * 签名优先取异常类名，取不到用首行前 100 字符。
 */
data class CrashAggregate(
    val signature: String,
    val type: String,
    val count: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val sample: String,
    val fatal: Boolean
) {
    fun toJson(): String = JSONObject().apply {
        put("sig", signature)
        put("ty", type)
        put("c", count)
        put("f", firstSeen)
        put("l", lastSeen)
        put("s", sample)
        put("fa", fatal)
    }.toString()

    companion object {
        fun fromJson(raw: String): CrashAggregate? = runCatching {
            val o = JSONObject(raw)
            CrashAggregate(
                signature = o.optString("sig", ""),
                type = o.optString("ty", ""),
                count = o.optInt("c", 0),
                firstSeen = o.optLong("f", 0L),
                lastSeen = o.optLong("l", 0L),
                sample = o.optString("s", ""),
                fatal = o.optBoolean("fa", false)
            )
        }.getOrNull()
    }
}

object CrashStats {

    private const val MAX_ROWS = 200
    private val EXCEPTION = Regex("""([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\.(?:[A-Za-z_$][\w$]*Exception|[A-Za-z_$][\w$]*Error))""")

    /** 同一签名短时间内会重复触发，攒够一批再落盘，避免每行崩溃都写一次 SP */
    private val pending = ConcurrentHashMap<String, CrashAggregate>()

    fun signatureOf(entry: LogEntry): String {
        val msg = entry.message.trim()
        if (msg.isEmpty()) return "${entry.tag}|empty"
        EXCEPTION.find(msg)?.let { return it.value }
        val head = msg.lineSequence().firstOrNull()?.trim() ?: msg
        return head.take(100)
    }

    fun record(entry: LogEntry) {
        val sig = signatureOf(entry)
        val type = if (sig.contains('.')) sig.substringAfterLast('.') else sig
        val now = System.currentTimeMillis()
        pending.compute(sig) { _, old ->
            if (old == null) {
                CrashAggregate(
                    signature = sig,
                    type = type,
                    count = 1,
                    firstSeen = now,
                    lastSeen = now,
                    sample = entry.message.take(600),
                    fatal = entry.level == LogLevel.FATAL || entry.level == LogLevel.ASSERT
                )
            } else {
                old.copy(
                    count = old.count + 1,
                    lastSeen = now,
                    fatal = old.fatal || entry.level == LogLevel.FATAL || entry.level == LogLevel.ASSERT
                )
            }
        }
    }

    fun flush(prefs: Prefs) {
        if (pending.isEmpty()) return
        val current = load(prefs).associateBy { it.signature }.toMutableMap()
        for ((sig, agg) in pending) {
            val old = current[sig]
            current[sig] = if (old == null) {
                agg
            } else {
                old.copy(
                    count = old.count + agg.count,
                    lastSeen = maxOf(old.lastSeen, agg.lastSeen),
                    sample = if (old.sample.isBlank()) agg.sample else old.sample,
                    fatal = old.fatal || agg.fatal
                )
            }
        }
        pending.clear()
        val arr = JSONArray()
        current.values.sortedByDescending { it.lastSeen }.take(MAX_ROWS).forEach { arr.put(it.toJson()) }
        prefs.crashStatsJson = arr.toString()
    }

    fun load(prefs: Prefs): List<CrashAggregate> {
        val raw = prefs.crashStatsJson
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<CrashAggregate>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                CrashAggregate.fromJson(arr.optString(i))?.let { out.add(it) }
            }
        }
        return out.sortedByDescending { it.lastSeen }
    }

    fun counts(prefs: Prefs): Triple<Int, Int, Int> {
        val all = load(prefs).toMutableList()
        all.addAll(pending.values)
        val now = System.currentTimeMillis()
        // 本地零点：不能直接用「UTC 零点 ± 时区偏移」，在 UTC+8 的凌晨 / UTC-5 的晚间会差一天
        val dayStart = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        var today = 0
        var week = 0
        var total = 0
        for (a in all) {
            total += a.count
            if (a.lastSeen >= dayStart) today += a.count
            if (a.lastSeen >= now - 7 * 86_400_000L) week += a.count
        }
        return Triple(today, week, total)
    }

    /** 最近 10 分钟内第一次出现，视为「新」异常 */
    fun isNew(agg: CrashAggregate, windowMs: Long = 10 * 60_000L): Boolean =
        System.currentTimeMillis() - agg.firstSeen <= windowMs

    fun clear(prefs: Prefs) {
        pending.clear()
        prefs.crashStatsJson = ""
    }
}
