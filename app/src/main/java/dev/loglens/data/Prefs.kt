package dev.loglens.data

import android.content.Context
import android.content.SharedPreferences
import dev.loglens.data.LogLevel

/**
 * 轻量配置存储。只存最小必要项，避免引入 DataStore 依赖。
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("shizuku_logger", Context.MODE_PRIVATE)

    var lastPackage: String
        get() = sp.getString(KEY_PKG, "") ?: ""
        set(value) = sp.edit().putString(KEY_PKG, value).apply()

    var lastLabel: String
        get() = sp.getString(KEY_LABEL, "") ?: ""
        set(value) = sp.edit().putString(KEY_LABEL, value).apply()

    /** 边抓边落盘 */
    var saveToFile: Boolean
        get() = sp.getBoolean(KEY_SAVE, true)
        set(value) = sp.edit().putBoolean(KEY_SAVE, value).apply()

    /** 崩溃行高亮 */
    var highlightCrash: Boolean
        get() = sp.getBoolean(KEY_HIGHLIGHT, true)
        set(value) = sp.edit().putBoolean(KEY_HIGHLIGHT, value).apply()

    /** 自动滚动到底部 */
    var autoScroll: Boolean
        get() = sp.getBoolean(KEY_AUTOSCROLL, true)
        set(value) = sp.edit().putBoolean(KEY_AUTOSCROLL, value).apply()

    /** 最低显示级别（含更高优先级） */
    var minLevelPriority: Int
        get() = sp.getInt(KEY_MIN_LEVEL, LogLevel.VERBOSE.priority)
        set(value) = sp.edit().putInt(KEY_MIN_LEVEL, value).apply()

    /** 是否把 events buffer 一并读入 */
    var includeEvents: Boolean
        get() = sp.getBoolean(KEY_EVENTS, false)
        set(value) = sp.edit().putBoolean(KEY_EVENTS, value).apply()

    /**
     * 全量模式：不按 uid/pid 过滤，main+system+crash+events 一起抓。
     * 用于判断「问题在系统层还是应用层」，量大，默认关。
     */
    var globalMode: Boolean
        get() = sp.getBoolean(KEY_GLOBAL, false)
        set(value) = sp.edit().putBoolean(KEY_GLOBAL, value).apply()

    /** 紧凑模式：单行显示，不折行，同屏塞更多条 */
    var compactRows: Boolean
        get() = sp.getBoolean(KEY_COMPACT, false)
        set(value) = sp.edit().putBoolean(KEY_COMPACT, value).apply()

    /** 日志正文字号（sp），11~16 */
    var fontSize: Int
        get() = sp.getInt(KEY_FONT_SIZE, 11)
        set(value) = sp.edit().putInt(KEY_FONT_SIZE, value.coerceIn(9, 20)).apply()

    /** 是否显示时间列 */
    var showTime: Boolean
        get() = sp.getBoolean(KEY_SHOW_TIME, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_TIME, value).apply()

    /** 是否显示 pid / tid 列 */
    var showPid: Boolean
        get() = sp.getBoolean(KEY_SHOW_PID, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_PID, value).apply()

    /** 观察日志时屏幕常亮 */
    var keepScreenOn: Boolean
        get() = sp.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = sp.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    /** 内存中保留的最大行数 */
    var maxRows: Int
        get() = sp.getInt(KEY_MAX_ROWS, 2500)
        set(value) = sp.edit().putInt(KEY_MAX_ROWS, value.coerceIn(500, 20_000)).apply()

    /**
     * 采集哪些 logcat 缓冲区，逗号分隔（main,system,crash,events,kernel,radio）。
     * events 仍单独起一条流，其余合并成一条。
     */
    var buffers: String
        get() = sp.getString(KEY_BUFFERS, "main,system,crash") ?: "main,system,crash"
        set(value) = sp.edit().putString(KEY_BUFFERS, value).apply()

    /** 崩溃 / ANR 额外弹通知（默认开：崩溃了没人告诉你，等于没抓） */
    var crashNotify: Boolean
        get() = sp.getBoolean(KEY_CRASH_NOTIFY, true)
        set(value) = sp.edit().putBoolean(KEY_CRASH_NOTIFY, value).apply()

    /** 单个日志文件的大小上限（MB），到点自动轮转，避免写满存储 */
    var logFileMaxMb: Int
        get() = sp.getInt(KEY_LOG_MAX_MB, 32)
        set(value) = sp.edit().putInt(KEY_LOG_MAX_MB, value.coerceIn(4, 512)).apply()

    /** 轮转后保留几份（含当前文件） */
    var logFileKeep: Int
        get() = sp.getInt(KEY_LOG_KEEP, 3)
        set(value) = sp.edit().putInt(KEY_LOG_KEEP, value.coerceIn(1, 20)).apply()

    /** 崩溃发生时自动保存现场快照（含崩溃前若干行） */
    var crashSnapshot: Boolean
        get() = sp.getBoolean(KEY_CRASH_SNAPSHOT, true)
        set(value) = sp.edit().putBoolean(KEY_CRASH_SNAPSHOT, value).apply()

    /** 崩溃前预录：不落盘，只在内存里滚动保留最近日志（默认关，常驻会多耗一点电） */
    var preRecord: Boolean
        get() = sp.getBoolean(KEY_PRE_RECORD, false)
        set(value) = sp.edit().putBoolean(KEY_PRE_RECORD, value).apply()

    /** 过滤框默认按正则解释 */
    var searchRegex: Boolean
        get() = sp.getBoolean(KEY_SEARCH_REGEX, false)
        set(value) = sp.edit().putBoolean(KEY_SEARCH_REGEX, value).apply()

    /** 上次用过的导出格式：txt / csv / json / zip */
    var exportFormat: String
        get() = sp.getString(KEY_EXPORT_FORMAT, "txt") ?: "txt"
        set(value) = sp.edit().putString(KEY_EXPORT_FORMAT, value).apply()

    /** 高级过滤：tag 包含列表（逗号分隔，任一命中即保留） */
    var filterTagsInclude: String
        get() = sp.getString(KEY_TAG_INCLUDE, "") ?: ""
        set(value) = sp.edit().putString(KEY_TAG_INCLUDE, value).apply()

    /** 高级过滤：tag 排除列表（逗号分隔，命中即丢弃） */
    var filterTagsExclude: String
        get() = sp.getString(KEY_TAG_EXCLUDE, "") ?: ""
        set(value) = sp.edit().putString(KEY_TAG_EXCLUDE, value).apply()

    /** 高级过滤：只看这些 pid（逗号分隔，空为不限制） */
    var filterPids: String
        get() = sp.getString(KEY_FILTER_PIDS, "") ?: ""
        set(value) = sp.edit().putString(KEY_FILTER_PIDS, value).apply()

    /** 取证：server 日志路径 */
    var forensicLogPath: String
        get() = sp.getString(KEY_FORENSIC_PATH, "") ?: ""
        set(value) = sp.edit().putString(KEY_FORENSIC_PATH, value).apply()

    /** 取证：logcat tag 过滤列表（逗号分隔） */
    var forensicTags: String
        get() = sp.getString(KEY_FORENSIC_TAGS, "") ?: ""
        set(value) = sp.edit().putString(KEY_FORENSIC_TAGS, value).apply()

    /** 取证：是否把整份 server 日志 cp 到 /sdcard/Download */
    var forensicCopyDownload: Boolean
        get() = sp.getBoolean(KEY_FORENSIC_CP, true)
        set(value) = sp.edit().putBoolean(KEY_FORENSIC_CP, value).apply()

    /** 上一次自身崩溃的堆栈（首页展示，便于无 adb 环境下取日志） */
    var lastCrash: String
        get() = sp.getString(KEY_LAST_CRASH, "") ?: ""
        // 崩溃路径写盘必须同步，apply() 异步落盘来不及，进程一挂就丢了
        set(value) {
            sp.edit().putString(KEY_LAST_CRASH, value).commit()
        }

    /** 自定义高亮规则（JSON 数组） */
    var highlightRulesJson: String
        get() = sp.getString(KEY_HIGHLIGHT_RULES, "") ?: ""
        set(value) = sp.edit().putString(KEY_HIGHLIGHT_RULES, value).apply()

    /** 过滤预设（JSON 数组） */
    var filterPresetsJson: String
        get() = sp.getString(KEY_PRESETS, "") ?: ""
        set(value) = sp.edit().putString(KEY_PRESETS, value).apply()

    /** 崩溃归集统计（JSON 数组） */
    var crashStatsJson: String
        get() = sp.getString(KEY_CRASH_STATS, "") ?: ""
        set(value) = sp.edit().putString(KEY_CRASH_STATS, value).apply()

    /** 堆栈自动折叠：连续续行超过 3 行折起来 */
    var collapseStacks: Boolean
        get() = sp.getBoolean(KEY_COLLAPSE, true)
        set(value) = sp.edit().putBoolean(KEY_COLLAPSE, value).apply()

    /** 右侧严重级别迷你图 */
    var showMinimap: Boolean
        get() = sp.getBoolean(KEY_MINIMAP, true)
        set(value) = sp.edit().putBoolean(KEY_MINIMAP, value).apply()

    /** 过滤区顶部显示各级别命中数量 */
    var showLevelCounts: Boolean
        get() = sp.getBoolean(KEY_COUNTS, true)
        set(value) = sp.edit().putBoolean(KEY_COUNTS, value).apply()

    /** 最近用过的搜索词（换行分隔，最多 12 条，最新的在前；单条超长的一律丢弃） */
    var queryHistory: String
        get() {
            val raw = sp.getString(KEY_QUERY_HISTORY, "") ?: ""
            if (raw.isEmpty()) return ""
            // 历史里混进整行日志会把这份 prefs 撑到几十 KB，读取时顺手清掉
            val clean = raw.split("\n").filter { it.isNotBlank() && it.length <= 80 }.take(12)
            val fixed = clean.joinToString("\n")
            if (fixed != raw) sp.edit().putString(KEY_QUERY_HISTORY, fixed).apply()
            return fixed
        }
        set(value) = sp.edit().putString(KEY_QUERY_HISTORY, value).apply()

    init {
        // 启动时触发一次历史清洗：老版本可能把整行日志当成搜索词存进来
        runCatching { queryHistory }
    }

    /** 把一条搜索词压进历史：去重、置顶、限长 */
    fun pushQuery(text: String) {
        val t = text.trim()
        if (t.length < 2) return
        // 单条限长：整行日志不该被当成搜索词记进历史
        if (t.length > 80) return
        val list = queryHistory.split("\n").filter { it.isNotBlank() && it != t }
        queryHistory = (listOf(t) + list).take(12).joinToString("\n")
    }

    fun dropQuery(text: String) {
        queryHistory = queryHistory.split("\n")
            .filter { it.isNotBlank() && it != text }
            .take(12)
            .joinToString("\n")
    }

    private companion object {
        const val KEY_QUERY_HISTORY = "query_history"
        const val KEY_PKG = "last_pkg"
        const val KEY_HIGHLIGHT_RULES = "highlight_rules"
        const val KEY_PRESETS = "filter_presets"
        const val KEY_CRASH_STATS = "crash_stats"
        const val KEY_COLLAPSE = "collapse_stacks"
        const val KEY_MINIMAP = "show_minimap"
        const val KEY_COUNTS = "show_level_counts"
        const val KEY_LABEL = "last_label"
        const val KEY_SAVE = "save_to_file"
        const val KEY_HIGHLIGHT = "highlight_crash"
        const val KEY_AUTOSCROLL = "auto_scroll"
        const val KEY_MIN_LEVEL = "min_level"
        const val KEY_EVENTS = "include_events"
        const val KEY_GLOBAL = "global_mode"
        const val KEY_COMPACT = "compact_rows"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_SHOW_TIME = "show_time"
        const val KEY_SHOW_PID = "show_pid"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_MAX_ROWS = "max_rows"
        const val KEY_BUFFERS = "buffers"
        const val KEY_CRASH_NOTIFY = "crash_notify"
        const val KEY_LOG_MAX_MB = "log_max_mb"
        const val KEY_LOG_KEEP = "log_keep"
        const val KEY_CRASH_SNAPSHOT = "crash_snapshot"
        const val KEY_PRE_RECORD = "pre_record"
        const val KEY_SEARCH_REGEX = "search_regex"
        const val KEY_EXPORT_FORMAT = "export_format"
        const val KEY_TAG_INCLUDE = "filter_tag_include"
        const val KEY_TAG_EXCLUDE = "filter_tag_exclude"
        const val KEY_FILTER_PIDS = "filter_pids"
        const val KEY_FORENSIC_PATH = "forensic_log_path"
        const val KEY_FORENSIC_TAGS = "forensic_tags"
        const val KEY_FORENSIC_CP = "forensic_copy_download"
        const val KEY_LAST_CRASH = "last_crash"
    }
}
