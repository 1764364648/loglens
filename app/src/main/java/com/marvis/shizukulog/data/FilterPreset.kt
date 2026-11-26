package com.marvis.shizukulog.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 过滤预设：把一套「查询 + 级别 + tag + pid」存下来，下次一键还原。
 *
 * 内置几条常用的空白预设（崩溃、ANR、网络、权限），省得每次手打。
 */
data class FilterPreset(
    val name: String,
    val query: String = "",
    val regex: Boolean = false,
    val minPriority: Int = 0,
    val onlyCrash: Boolean = false,
    val onlyBookmarked: Boolean = false,
    val tagsInclude: String = "",
    val tagsExclude: String = "",
    val pids: String = "",
    val builtin: Boolean = false
) {

    fun toJson(): String = JSONObject().apply {
        put("n", name)
        put("q", query)
        put("re", regex)
        put("lv", minPriority)
        put("cr", onlyCrash)
        put("bm", onlyBookmarked)
        put("ti", tagsInclude)
        put("te", tagsExclude)
        put("pid", pids)
        put("bi", builtin)
    }.toString()

    companion object {
        fun fromJson(raw: String): FilterPreset? = runCatching {
            val o = JSONObject(raw)
            FilterPreset(
                name = o.optString("n", ""),
                query = o.optString("q", ""),
                regex = o.optBoolean("re", false),
                minPriority = o.optInt("lv", 0),
                onlyCrash = o.optBoolean("cr", false),
                onlyBookmarked = o.optBoolean("bm", false),
                tagsInclude = o.optString("ti", ""),
                tagsExclude = o.optString("te", ""),
                pids = o.optString("pid", ""),
                builtin = o.optBoolean("bi", false)
            )
        }.getOrNull()

        /** 内置预设：故意用通用写法，不绑定某个 App */
        fun builtins(): List<FilterPreset> = listOf(
            FilterPreset(
                name = "只看崩溃",
                query = "is:crash",
                minPriority = LogLevel.ERROR.priority,
                onlyCrash = true,
                builtin = true
            ),
            FilterPreset(
                name = "ANR / 卡死",
                query = "ANR | \"Application Not Responding\" | \"Input dispatching timed out\"",
                builtin = true
            ),
            FilterPreset(
                name = "网络请求",
                query = "okhttp | retrofit | HttpURLConnection | grpc | SocketTimeout",
                builtin = true
            ),
            FilterPreset(
                name = "权限被拒",
                query = "SecurityException | Permission Denial | EACCES | EPERM",
                builtin = true
            ),
            FilterPreset(
                name = "最近 30 秒",
                query = "age:30s",
                builtin = true
            ),
            // 原生 / DSP 侧报错：QNN、HTP、adsprpc、fastrpc 的失败只打在自己的 tag 上，
            // 按 message 搜关键词会漏，这里改用 tag 白名单兜住，级别从 W 起（保留 E/F）。
            FilterPreset(
                name = "原生 / DSP 报错",
                minPriority = LogLevel.WARN.priority,
                tagsInclude = "QnnDsp,QnnHtp,adsprpc,fastrpc,libcdsprpc,libc,Adreno,OpenCL",
                builtin = true
            )
        )
    }
}

object PresetStore {

    private const val MAX_USER = 40

    private var cache: MutableList<FilterPreset>? = null

    fun load(prefs: Prefs): List<FilterPreset> {
        cache?.let { return it.toList() }
        val list = ArrayList<FilterPreset>()
        val raw = prefs.filterPresetsJson
        if (raw.isNotBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    FilterPreset.fromJson(arr.optString(i))?.let { list.add(it.copy(builtin = false)) }
                }
            }
        }
        cache = list
        return list.toList()
    }

    fun save(prefs: Prefs, presets: List<FilterPreset>) {
        val user = presets.filter { !it.builtin }.take(MAX_USER)
        cache = user.toMutableList()
        val arr = JSONArray()
        for (p in user) arr.put(p.toJson())
        prefs.filterPresetsJson = arr.toString()
    }

    fun upsert(prefs: Prefs, preset: FilterPreset): List<FilterPreset> {
        val current = load(prefs).toMutableList()
        val idx = current.indexOfFirst { it.name.equals(preset.name, ignoreCase = true) }
        if (idx >= 0) current[idx] = preset else current.add(preset)
        save(prefs, current)
        return current.toList()
    }

    fun remove(prefs: Prefs, name: String): List<FilterPreset> {
        val current = load(prefs).toMutableList()
        current.removeAll { it.name.equals(name, ignoreCase = true) }
        save(prefs, current)
        return current.toList()
    }
}
