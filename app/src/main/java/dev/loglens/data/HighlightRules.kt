package dev.loglens.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义高亮规则：把自己的关键词按自己的颜色标出来。
 *
 * 这一条是从付费版那边抄过来的刚需——排查问题时要同时盯住三五个关键词，
 * 只有一种命中色根本分不清谁是谁。
 */
data class HighlightRule(
    val id: Int,
    val name: String,
    val keyword: String,
    val colorHex: String,
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeLine: Boolean = false,
    val enabled: Boolean = true
) {

    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kw", keyword)
        put("color", colorHex)
        put("regex", regex)
        put("cs", caseSensitive)
        put("line", wholeLine)
        put("on", enabled)
    }.toString()

    companion object {
        fun fromJson(raw: String): HighlightRule? = runCatching {
            val o = JSONObject(raw)
            HighlightRule(
                id = o.optInt("id", 0),
                name = o.optString("name", ""),
                keyword = o.optString("kw", ""),
                colorHex = o.optString("color", "#FFB300"),
                regex = o.optBoolean("regex", false),
                caseSensitive = o.optBoolean("cs", false),
                wholeLine = o.optBoolean("line", false),
                enabled = o.optBoolean("on", true)
            )
        }.getOrNull()
    }
}

/** 一段命中区间 + 该区间的颜色（ARGB） */
data class MarkSpan(val range: IntRange, val color: Int, val wholeLine: Boolean = false)

/**
 * 编译后的规则集。文本匹配在滚动时逐行调用，所有 Regex 只在构建时编译一次。
 * 单行最多输出 128 段，防止一条巨长的堆栈把排版拖死。
 */
class CompiledHighlights(rules: List<HighlightRule>) {

    private class Entry(
        val regex: Regex?,
        val literal: String?,
        val color: Int,
        val wholeLine: Boolean
    )

    private val entries: List<Entry> = rules.asSequence()
        .filter { it.enabled && it.keyword.isNotBlank() }
        .mapNotNull { rule ->
            val color = parseColor(rule.colorHex) ?: return@mapNotNull null
            if (rule.regex) {
                val re = runCatching {
                    if (rule.caseSensitive) Regex(rule.keyword) else Regex(rule.keyword, RegexOption.IGNORE_CASE)
                }.getOrNull() ?: return@mapNotNull null
                Entry(re, null, color, rule.wholeLine)
            } else {
                Entry(null, rule.keyword, color, rule.wholeLine)
            }
        }
        .toList()

    val isEmpty: Boolean get() = entries.isEmpty()

    fun spans(text: String): List<MarkSpan> {
        if (entries.isEmpty() || text.isEmpty()) return emptyList()
        val out = ArrayList<MarkSpan>(4)
        for (e in entries) {
            if (out.size >= 128) break
            val re = e.regex
            if (re != null) {
                runCatching {
                    re.findAll(text).take(32).forEach { m ->
                        if (m.value.isNotEmpty()) out.add(MarkSpan(m.range, e.color, false))
                    }
                }
            } else {
                val kw = e.literal ?: continue
                if (kw.isEmpty()) continue
                var from = 0
                while (out.size < 128) {
                    val idx = text.indexOf(kw, from, ignoreCase = true)
                    if (idx < 0) break
                    out.add(MarkSpan(idx until idx + kw.length, e.color, false))
                    from = idx + kw.length
                }
            }
        }
        if (out.isEmpty()) return out
        // 整行染色由调用方按 wholeLine 决定，这里补记一份标记
        for (e in entries) {
            if (!e.wholeLine) continue
            val hit = if (e.regex != null) {
                runCatching { e.regex.containsMatchIn(text) }.getOrDefault(false)
            } else {
                text.contains(e.literal ?: "", ignoreCase = true)
            }
            if (hit) {
                out.add(MarkSpan(0 until text.length, e.color, true))
                break
            }
        }
        return out
    }

    companion object {
        fun parseColor(raw: String): Int? {
            val hex = raw.removePrefix("#").trim()
            return runCatching {
                when (hex.length) {
                    6 -> (0xFF000000L or hex.toLong(16)).toInt()
                    8 -> hex.toLong(16).toInt()
                    else -> null
                }
            }.getOrNull()
        }

        fun toHex(color: Int): String = String.format("#%08X", color)
    }
}

/** 规则持久化：整份列表塞一个 JSON 数组进 SharedPreferences，省一张表 */
object HighlightStore {

    private const val MAX_RULES = 32

    private var cache: MutableList<HighlightRule>? = null

    val palette = listOf(
        "#FFFF6B6B", "#FFFFB300", "#FF4ADE80", "#FF38BDF8",
        "#FFC084FC", "#FFF472B6", "#FFA3E635", "#FFFB923C"
    )

    fun load(prefs: Prefs): List<HighlightRule> {
        cache?.let { return it.toList() }
        val raw = prefs.highlightRulesJson
        val list = ArrayList<HighlightRule>()
        if (raw.isNotBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    HighlightRule.fromJson(arr.optString(i))?.let { list.add(it) }
                }
            }
        }
        cache = list
        return list.toList()
    }

    fun save(prefs: Prefs, rules: List<HighlightRule>) {
        val trimmed = rules.take(MAX_RULES)
        cache = trimmed.toMutableList()
        val arr = JSONArray()
        for (r in trimmed) arr.put(r.toJson())
        prefs.highlightRulesJson = arr.toString()
    }

    fun nextId(rules: List<HighlightRule>): Int = (rules.maxOfOrNull { it.id } ?: 0) + 1
}
