package dev.loglens.ui

import android.widget.Toast
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import dev.loglens.data.IssueHit
import dev.loglens.data.IssueRules
import dev.loglens.data.OfflineImport
import dev.loglens.data.OfflineLog
import dev.loglens.service.OverlayLogService
import dev.loglens.util.LogFiles
import dev.loglens.util.SnapshotImage
import dev.loglens.util.ExportFormats
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.loglens.capture.CaptureController
import dev.loglens.data.CompiledFilter
import dev.loglens.data.CompiledHighlights
import dev.loglens.data.CrashAggregate
import dev.loglens.data.CrashStats
import dev.loglens.data.FilterPreset
import dev.loglens.data.HighlightRule
import dev.loglens.data.HighlightStore
import dev.loglens.data.AppGraph
import dev.loglens.data.LogEntry
import dev.loglens.data.LogFilter
import dev.loglens.data.LogLevel
import dev.loglens.data.LogRepository
import dev.loglens.data.Prefs
import dev.loglens.data.PresetStore
import dev.loglens.data.ProcNames
import dev.loglens.logcat.DeviceLogGuard
import dev.loglens.ui.mx.BottomAppBar
import dev.loglens.ui.mx.ButtonDefaults
import dev.loglens.ui.mx.Card
import dev.loglens.ui.mx.CardDefaults
import dev.loglens.ui.mx.ExperimentalMaterial3Api
import dev.loglens.ui.mx.FilterChip
import dev.loglens.ui.mx.FilterChipDefaults
import dev.loglens.ui.mx.FloatingActionButton
import dev.loglens.ui.mx.Icon
import dev.loglens.ui.mx.IconButton
import dev.loglens.ui.mx.OutlinedTextField
import dev.loglens.ui.mx.Scaffold
import dev.loglens.ui.mx.Surface
import dev.loglens.ui.mx.Text
import dev.loglens.ui.mx.TextButton
import dev.loglens.ui.mx.TopAppBar
import dev.loglens.ui.mx.TopAppBarDefaults
import dev.loglens.ui.theme.LocalLogPalette
import dev.loglens.ui.theme.LogPalette
import dev.loglens.util.ExportFormat
import dev.loglens.util.Exporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 书签存储：跨页面保留，且不受列表裁剪影响。
 * 被裁掉的日志如果用户收藏过，仍能在「只看书签」里翻出来。
 */
private object Bookmarks {
    val entries = mutableStateListOf<LogEntry>()
    private val keys = HashSet<String>()

    /**
     * 用内容签名做键，不用裸 id：离线导入的日志 id 从 1 起，和实时缓冲的 id 完全重合，
     * 拿 id 当键会把「离线第 3 条」和「实时第 3 条」当成同一条，收藏互相串。
     */
    private fun keyOf(e: LogEntry): String =
        "${e.time}|${e.pid}|${e.tid}|${e.level.letter}|${e.tag}|${e.message}"

    fun has(entry: LogEntry): Boolean = keys.isNotEmpty() && keys.contains(keyOf(entry))

    /** 返回 true 表示本次是「收藏」，false 表示「取消收藏」 */
    fun toggle(entry: LogEntry): Boolean {
        val k = keyOf(entry)
        return if (keys.remove(k)) {
            entries.removeAll { keyOf(it) == k }
            false
        } else {
            keys.add(k)
            entries.add(0, entry)
            true
        }
    }

    fun count(): Int = entries.size

    fun clear() {
        entries.clear()
        keys.clear()
    }
}


/**
 * LogScreen 的界面状态容器。
 *
 * 这些状态原本是同一个 1086 行函数里的 40 来个 `remember`，读写点横跨顶栏、筛选、
 * 工具条、列表和八个弹窗——想改一处字号都得读完一千行。抽成普通类之后，主函数只剩
 * 「采集 → 过滤 → 渲染」的流水线，各区域各成一个 composable，共享同一个实例。
 *
 * 它不是 ViewModel：没有生命周期、不读磁盘、不跨页面存活，边界就是这次组合，
 * 与原来 `remember` 的语义完全一致（离开日志页照样丢弃）。
 */
private class LogScreenUi(
    val prefs: Prefs,
    // 进来时的统计由外面给，UI 状态自己不碰全局仓库
    initialStats: LogRepository.Stats = LogRepository.Stats(0, 0)
) {

    private val maxRows = prefs.maxRows

    val items = mutableStateListOf<LogEntry>()
    val hold = mutableStateListOf<LogEntry>()
    /** 进过列表的 id，LazyColumn 的 key 必须唯一，重复 key 直接崩 */
    val seen = HashSet<Long>()
    val holdIds = HashSet<Long>()
    val pausedRef = java.util.concurrent.atomic.AtomicBoolean(false)
    val picked = mutableStateListOf<Long>()

    var paused by mutableStateOf(false)
    var heldCount by mutableIntStateOf(0)
    var autoScroll by mutableStateOf(prefs.autoScroll)

    // ── 过滤条件 ────────────────────────────────────────────
    var query by mutableStateOf("")
    var regex by mutableStateOf(prefs.searchRegex)
    var minPriority by mutableIntStateOf(prefs.minLevelPriority)
    var onlyCrash by mutableStateOf(false)
    var onlyBookmarked by mutableStateOf(false)
    var tagInclude by mutableStateOf(prefs.filterTagsInclude)
    var tagExclude by mutableStateOf(prefs.filterTagsExclude)
    var pidFilter by mutableStateOf(prefs.filterPids)
    var advancedOpen by mutableStateOf(false)
    var bookmarkVersion by mutableIntStateOf(0)
    var exportOpen by mutableStateOf(false)
    var exportFormat by mutableStateOf(ExportFormat.of(prefs.exportFormat))

    // ── 高亮 / 预设 / 崩溃归集 ──────────────────────────────
    var highlightRules by mutableStateOf(HighlightStore.load(prefs))
    var highlightOpen by mutableStateOf(false)
    var crashOpen by mutableStateOf(false)

    var issueOpen by mutableStateOf(false)
    var filesOpen by mutableStateOf(false)
    var buffersOpen by mutableStateOf(false)
    var tagsOpen by mutableStateOf(false)
    var selectMode by mutableStateOf(false)
    var actionEntry by mutableStateOf<LogEntry?>(null)
    var history by mutableStateOf(prefs.queryHistory.split("\n").filter { it.isNotBlank() })

    var fileItems by mutableStateOf<List<LogFiles.Item>>(emptyList())
    var bufferSet by mutableStateOf(
        prefs.buffers.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    )
    var overlayAlive by mutableStateOf(OverlayLogService.alive)
    var presets by mutableStateOf(PresetStore.load(prefs))
    var activePreset by mutableStateOf<String?>(null)
    var crashRows by mutableStateOf<List<CrashAggregate>>(emptyList())
    var crashCounts by mutableStateOf(Triple(0, 0, 0))
    var findCursor by mutableIntStateOf(-1)
    var expandedFolds by mutableStateOf<Set<String>>(emptySet())
    // 展开折叠后的定位只能在下一帧做（displayRows 要等重组才更新）
    var pendingJumpId by mutableStateOf<Long?>(null)
    // 跳转闪烁定位：命中行的 entry.id，2 秒后自动清除
    var jumpFlash by mutableStateOf<Long?>(null)
    var jumpHint by mutableStateOf<String?>(null)

    // 拉进程名表用的计数与去重键
    var procTick by mutableIntStateOf(0)
    var procRefreshedFor by mutableStateOf<String?>(null)

    var stats by mutableStateOf(initialStats)

    /** 恢复渲染：把暂停期间压着的行一次性补进列表 */
    fun flushHold(scope: CoroutineScope, listState: LazyListState) {
        if (hold.isNotEmpty()) {
            val restore = ArrayList<LogEntry>(hold.size)
            for (e in hold) if (seen.add(e.id)) restore.add(e)
            hold.clear()
            holdIds.clear()
            items.addAll(0, restore)
            trimList(items, seen, maxRows)
        }
        heldCount = 0
        if (autoScroll) scope.launch { runCatching { listState.scrollToItem(0) } }
    }
}

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    kotlinx.coroutines.FlowPreview::class
)
@Composable
fun LogScreen(
    prefs: Prefs,
    targetLabel: String,
    targetPkg: String,
    state: CaptureController.State,
    onBack: () -> Unit,
    // 从首页「导入日志文件」进来时，进页就弹文件选择器（不需要 Shizuku，纯离线看别人发来的 log）
    startWithImport: Boolean = false,
    // 仓库与进程名表从外面注入：默认取 AppGraph 里的全局实例，测试可以换成干净的替身
    logs: LogRepository = AppGraph.logs,
    procs: ProcNames = AppGraph.procs
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 离线日志：选一个 txt 就能当实时缓冲来看
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "导入的日志"
            Thread {
                val parsed = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()
                        ?.use { OfflineImport.parse(it) }
                }.getOrNull() ?: emptyList()
                Handler(Looper.getMainLooper()).post {
                    if (parsed.isEmpty()) {
                        Toast.makeText(context, "没解析出日志行，确认是 logcat 文本", Toast.LENGTH_SHORT).show()
                    } else {
                        OfflineLog.load(parsed, name)
                        val tail = if (parsed.size >= 200_000) "（超过 20 万行，只取前面这一段）" else ""
                        Toast.makeText(context, "已载入 ${parsed.size} 行：$name$tail", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
    }

    // 首页「导入日志文件」进来的那一次：页面一出现就把选择器弹出来，省掉「进日志页再点打开文件」两步
    LaunchedEffect(Unit) {
        if (startWithImport) {
            openDoc.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
        }
    }

    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    val maxRows = prefs.maxRows
    // 仓库上限跟界面口径对齐，避免内存里留着 5000 行而界面只认 2500
    logs.capacity = maxRows

    // 界面状态全部搬进容器；主函数只保留「采集 → 过滤 → 渲染」这条流水线
    val ui = remember { LogScreenUi(prefs, logs.statsSnapshot()) }

    // 浮窗可能在它自己的关闭按钮里被收掉，页面只读进页面时的快照会一直显示「浮窗已开」，
    // 这里订阅存活状态：无论是谁关的，按钮文案都跟着变
    LaunchedEffect(Unit) {
        OverlayLogService.aliveState.collect { ui.overlayAlive = it }
    }

    // 搜索词停留 1.2s 才记进历史，避免边打字边存一堆半截词
    LaunchedEffect(ui.query) {
        if (ui.query.trim().length < 2) return@LaunchedEffect
        kotlinx.coroutines.delay(1200)
        prefs.pushQuery(ui.query)
        ui.history = prefs.queryHistory.split("\n").filter { it.isNotBlank() }
    }

    // 离线模式切换也要触发重组（离线内容不走 items，只能靠版本号推动）
    val offlineVersion by OfflineLog.version.collectAsState()

    val highlighter = remember(ui.highlightRules) { CompiledHighlights(ui.highlightRules) }
    val collapseStacks = prefs.collapseStacks

    // 崩溃归集从全局仓库持续收数，这里只负责定时落盘和刷新界面
    LaunchedEffect(Unit) {
        while (isActive) {
            ui.crashRows = CrashStats.load(prefs)
            ui.crashCounts = CrashStats.counts(prefs)
            CrashStats.flush(prefs)
            delay(4_000)
        }
    }

    val filter = remember(
        ui.query, ui.regex, ui.minPriority, ui.onlyCrash, ui.onlyBookmarked,
        ui.tagInclude, ui.tagExclude, ui.pidFilter
    ) {
        LogFilter(
            query = ui.query,
            regex = ui.regex,
            minPriority = ui.minPriority,
            onlyCrash = ui.onlyCrash,
            onlyBookmarked = ui.onlyBookmarked,
            tagsInclude = LogFilter.splitTags(ui.tagInclude),
            tagsExclude = LogFilter.splitTags(ui.tagExclude),
            pids = parsePids(ui.pidFilter)
        )
    }
    val compiled = remember(filter) { CompiledFilter(filter) }

    // package: / process: 这类查询靠 pid→进程名表；表是空的就现拉一次，
    // 放 IO 线程，不卡列表。拉到了用一个计数触发 viewData 重算。
    LaunchedEffect(filter.query) {
        val q = filter.query
        if (q.isNotBlank() && PROC_QUERY.containsMatchIn(q) && ui.procRefreshedFor != q) {
            ui.procRefreshedFor = q
            if (withContext(Dispatchers.IO) { procs.refresh() }) ui.procTick++
        }
    }

    val listState = rememberLazyListState()

    val blocked by CaptureController.logChannelBlocked.collectAsState()
    val logFile by CaptureController.logFile.collectAsState()
    // 采集会话的暂停态：从通知 / 岛上点暂停、继续时，页面这边要跟着切，不能两套状态各说各话
    val sessionPaused by CaptureController.paused.collectAsState()

    // 进页面时把缓冲一次性铺上，attach 是原子的，不会和增量通道抢数据
    LaunchedEffect(Unit) {
        val seed = logs.attach()
        if (ui.items.isEmpty() && ui.seen.isEmpty()) {
            for (e in seed.asReversed()) {
                if (ui.seen.add(e.id)) ui.items.add(e)
            }
            trimList(ui.items, ui.seen, maxRows)
        }
    }

    // 增量刷新，160ms 一批，日志再密也不打满主线程。
    // 这里不订阅任何 StateFlow，而是定时拉：采集侧因此可以完全「零流写入」。
    LaunchedEffect(Unit) {
        while (true) {
            delay(160)
            val batch = logs.drainPending()
            ui.stats = logs.statsSnapshot()
            if (batch.isEmpty()) continue

            if (ui.pausedRef.get()) {
                // 暂停时照常入账，只是不渲染，恢复后一次性补齐
                val accepted = ArrayList<LogEntry>(batch.size)
                for (e in batch) if (ui.holdIds.add(e.id)) accepted.add(e)
                if (accepted.isNotEmpty()) {
                    ui.hold.addAll(0, accepted.asReversed())
                    trimList(ui.hold, ui.holdIds, maxRows)
                }
                ui.heldCount = ui.hold.size
                continue
            }

            val fresh = ArrayList<LogEntry>(batch.size)
            for (e in batch) if (ui.seen.add(e.id)) fresh.add(e)
            if (fresh.isEmpty()) continue
            ui.items.addAll(0, fresh.asReversed())
            trimList(ui.items, ui.seen, maxRows)
        }
    }

    // 以采集会话为准同步页面：岛上/通知里点了暂停，这里也停更；恢复回来再接着刷
    LaunchedEffect(sessionPaused) {
        if (sessionPaused && !ui.paused) {
            ui.paused = true
            ui.pausedRef.set(true)
            ui.hold.clear()
            ui.holdIds.clear()
            ui.heldCount = 0
        } else if (!sessionPaused && ui.paused) {
            ui.paused = false
            ui.pausedRef.set(false)
            ui.flushHold(scope, listState)
        }
    }

    val snapshot = when {
                            OfflineLog.entries.isNotEmpty() -> OfflineLog.entries
                            ui.onlyBookmarked -> Bookmarks.entries.toList()
                            else -> ui.items.toList()
                        }
    val viewKey = ui.bookmarkVersion
    // 一遍遍历同时得到「可视行」和「各级别条数」：
    // 两者只差一个 minPriority，所以先用不含级别的条件判一次，再各取所需。
    // 原来这里是两次全缓冲过滤 + 一次 distinctBy 装箱，2500 行下每 160ms 都要重跑一轮。
    val viewData = remember(snapshot, compiled, filter.minPriority, viewKey, ui.procTick) {
        val env = compiled.env()
        val rows = ArrayList<LogEntry>(snapshot.size)
        val counts = IntArray(5)
        var prevShown = false
        for (e in snapshot) {
            val marked = Bookmarks.has(e)
            if (!compiled.acceptIgnoringLevel(e, marked, env)) {
                prevShown = false
                continue
            }
            val p = e.level.priority
            if (e.isContinuation) {
                // 续行（异常堆栈体）是 RAW，但它是上一行的内容：上一行过筛就跟着显示。
                // 它不参与级别计数，否则一次崩溃的几十行堆栈会把 ERROR 计数撑大
                if (prevShown) rows.add(e)
                continue
            }
            prevShown = false
            // 非续行的纯 RAW 行不参与计数也不显示，与原逻辑一致
            if (p < LogLevel.VERBOSE.priority) continue
            when {
                p >= LogLevel.ERROR.priority -> counts[4]++
                p >= LogLevel.WARN.priority -> counts[3]++
                p >= LogLevel.INFO.priority -> counts[2]++
                p >= LogLevel.DEBUG.priority -> counts[1]++
                else -> counts[0]++
            }
            if (p >= filter.minPriority) {
                rows.add(e)
                prevShown = true
            }
        }
        rows to counts
    }
    val visible = viewData.first
    val levelCounts = viewData.second

    val displayRows = remember(visible, collapseStacks, ui.expandedFolds) {
        if (collapseStacks) foldContinuations(visible, ui.expandedFolds)
        else visible.map { LogRowItem.One(it) }
    }

    // 折叠区间展开后，新行列表要等这次重组结束才拿得到，定位在这里补做
    LaunchedEffect(displayRows, ui.pendingJumpId) {
        val id = ui.pendingJumpId ?: return@LaunchedEffect
        val idx = displayRows.indexOfFirst { it is LogRowItem.One && it.entry.id == id }
        if (idx >= 0) {
            runCatching { listState.scrollToItem((idx - 2).coerceAtLeast(0)) }
            ui.pendingJumpId = null
        }
    }

    // scan 只读每条日志创建时算好的掩码，是 O(行数) 的位运算（约几十微秒）；
    // 这里刻意不用 remember(visible)：那个 key 会在大列表上做逐元素 equals，反而更贵
    val issueHits = IssueRules.scan(visible)

    // 跳到某条日志：索引必须查 displayRows（折叠后与 visible 下标不一致），并高亮闪烁提示落点
    fun jumpToEntry(target: LogEntry) {
        val idx = displayRows.indexOfFirst { it is LogRowItem.One && it.entry.id == target.id }
        if (idx < 0 && collapseStacks) {
            val vIdx = visible.indexOfFirst { it.id == target.id }
            // 目标落在某个折叠区间内部（不是头行）：原来只认 fold.head，必然提示「不在视图内」。
            // 先展开那个区间，再用 pendingJumpId 在重组后定位。
            val fold = if (vIdx >= 0) {
                displayRows.firstOrNull {
                    it is LogRowItem.Fold && vIdx >= it.start && vIdx < it.end
                } as? LogRowItem.Fold
            } else {
                null
            }
            if (fold != null) {
                ui.expandedFolds = ui.expandedFolds + fold.expandKey
                ui.pendingJumpId = target.id
                ui.jumpFlash = target.id
                ui.jumpHint = "已展开折叠区间 · 目标行高亮 2 秒"
                scope.launch {
                    delay(2200)
                    if (ui.jumpFlash == target.id) ui.jumpFlash = null
                }
                scope.launch {
                    delay(4000)
                    if (ui.jumpHint != null) ui.jumpHint = null
                }
                return
            }
        }
        if (idx < 0) {
            Toast.makeText(context, "该条不在当前视图内", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            runCatching {
                // reverseLayout：index 0 在底部，目标行上移 2 行避免被底部工具栏压住
                listState.scrollToItem((idx - 2).coerceAtLeast(0))
            }
        }
        ui.jumpFlash = target.id
        ui.jumpHint = "已定位 · 目标行高亮 2 秒"
        scope.launch {
            delay(2200)
            if (ui.jumpFlash == target.id) ui.jumpFlash = null
        }
        scope.launch {
            delay(4000)
            if (ui.jumpHint != null) ui.jumpHint = null
        }
    }

    LaunchedEffect(visible.size, ui.autoScroll) {
        if (ui.autoScroll && visible.isNotEmpty() && listState.firstVisibleItemIndex <= 2) {
            runCatching { listState.scrollToItem(0) }
        }
    }

    val capturing = state is CaptureController.State.Capturing

    Scaffold(
        containerColor = colors.background,
        topBar = {
            LogTopBar(
                ui = ui,
                capturing = capturing,
                sessionPaused = sessionPaused,
                targetLabel = targetLabel,
                targetPkg = targetPkg,
                onBack = onBack
            )
        },
        bottomBar = {
            LogBottomBar(
                ui = ui,
                visible = visible,
                prefs = prefs,
                listState = listState,
                targetPkg = targetPkg,
                logs = logs
            )
        },
        floatingActionButton = {
    if (!ui.autoScroll) {
        FloatingActionButton(
            onClick = {
                ui.autoScroll = true
                prefs.autoScroll = true
            },
            containerColor = colors.primary,
            contentColor = colors.onPrimary
        ) {
            Icon(
                Icons.Rounded.VerticalAlignBottom,
                contentDescription = "回到底部"
            )
        }
    }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LogToolsArea(
                ui = ui,
                prefs = prefs,
                listState = listState,
                compiled = compiled,
                visible = visible,
                levelCounts = levelCounts,
                state = state,
                logFile = logFile,
                issueHits = issueHits,
                onOpenDoc = {
                    openDoc.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
                },
                onFindNext = {
                    if (visible.isNotEmpty()) {
                        ui.findCursor = (ui.findCursor + 1) % visible.size
                        // findCursor 是 visible 的下标，不是 LazyColumn 的行号：
                        // 折叠之后两者行数不同，直接把 cursor 当 index 会滚到列表另一头
                        val target = visible[ui.findCursor]
                        val idx = displayRows.indexOfFirst {
                            it is LogRowItem.One && it.entry.id == target.id
                        }
                        if (idx >= 0) {
                            scope.launch { runCatching { listState.scrollToItem(idx) } }
                        } else {
                            jumpToEntry(target)
                        }
                    }
                }
            )
            LogNotices(ui = ui, prefs = prefs, blocked = blocked)
            LogListArea(
                ui = ui,
                visible = visible,
                displayRows = displayRows,
                compiled = compiled,
                highlighter = highlighter,
                listState = listState,
                prefs = prefs,
                blocked = blocked
            )
        }
    }

    LogDialogs(
        ui = ui,
        prefs = prefs,
        visible = visible,
        targetPkg = targetPkg,
        issueHits = issueHits,
        onJump = { jumpToEntry(it) }
    )
}

/** 顶栏：目标包名 + 采集状态胶囊。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogTopBar(
    ui: LogScreenUi,
    capturing: Boolean,
    sessionPaused: Boolean,
    targetLabel: String,
    targetPkg: String,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    TopAppBar(
        title = {
            Column {
                Text(
                    targetLabel.ifBlank { "实时日志" },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    targetPkg,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.onSurface
                )
            }
        },
        actions = {
            StatusChip(
                label = when {
                    ui.paused || sessionPaused -> "已暂停"
                    capturing -> "实时"
                    else -> "已停止"
                },
                color = when {
                    ui.paused || sessionPaused -> palette.warn
                    capturing -> palette.info
                    else -> colors.onSurfaceVariant
                }
            )
            Spacer(Modifier.width(16.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
    )
}

/** 底栏：多选操作条 / 采集操作条。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogBottomBar(
    ui: LogScreenUi,
    visible: List<LogEntry>,
    prefs: Prefs,
    listState: LazyListState,
    targetPkg: String,
    logs: LogRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    if (ui.selectMode) {
        SelectionBar(
            count = ui.picked.size,
            onCopy = {
                val list = visible.filter { it.id in ui.picked }
                if (list.isEmpty()) {
                    Toast.makeText(context, "没选日志", Toast.LENGTH_SHORT).show()
                } else {
                    Exporter.copy(context, Exporter.dump(list))
                    Toast.makeText(context, "已复制 ${list.size} 行", Toast.LENGTH_SHORT).show()
                }
            },
            onShare = {
                val list = visible.filter { it.id in ui.picked }
                if (list.isEmpty()) {
                    Toast.makeText(context, "没选日志", Toast.LENGTH_SHORT).show()
                } else {
                    Exporter.shareText(context, Exporter.dump(list))
                }
            },
            onSelectAll = {
                ui.picked.clear()
                visible.forEach { ui.picked.add(it.id) }
            },
            onExit = {
                ui.selectMode = false
                ui.picked.clear()
            }
        )
    } else {
        LogActionBar(
            paused = ui.paused,
            autoScroll = ui.autoScroll,
            onPauseToggle = {
                val next = !ui.paused
                ui.paused = next
                ui.pausedRef.set(next)
                if (next) {
                    // 「暂停」就是真的停下采集：logcat 断流 → 通知和岛一起变「已暂停采集」，
                    // 不再出现「页面说暂停了、日志还在涨」这种事
                    CaptureController.pause()
                    ui.hold.clear()
                    ui.holdIds.clear()
                    ui.heldCount = 0
                } else {
                    // 「继续」把会话按原参数拉回来，再补齐暂停期间压在手上的行
                    CaptureController.resume()
                    ui.flushHold(scope, listState)
                }
            },
            onClear = {
                logs.clear()
                ui.items.clear()
                ui.hold.clear()
                ui.seen.clear()
                ui.holdIds.clear()
                ui.heldCount = 0
                // 清空后必须回到最新位置，否则新日志进来看不见，像是"清空后就不动了"
                scope.launch { runCatching { listState.scrollToItem(0) } }
                Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
            },
            onExport = { ui.exportOpen = true },
            onShare = {
                scope.launch {
                    val data = withContext(Dispatchers.IO) {
                        visible.asReversed()
                    }
                    if (data.isEmpty()) {
                        Toast.makeText(context, "没有可分享的日志", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val file = withContext(Dispatchers.IO) {
                        ExportFormats.export(context, data, targetPkg, ui.exportFormat)
                    }
                    if (file != null) {
                        runCatching {
                            ExportFormats.shareFile(context, file, ui.exportFormat.mime)
                        }
                    } else {
                        Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onFollowToggle = {
                ui.autoScroll = !ui.autoScroll
                prefs.autoScroll = ui.autoScroll
                if (ui.autoScroll) scope.launch { listState.scrollToItem(0) }
            }
        )
    }
}

/** 筛选区：搜索框、工具条、离线来源条、高级过滤面板。 */
@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    kotlinx.coroutines.FlowPreview::class
)
@Composable
private fun LogToolsArea(
    ui: LogScreenUi,
    prefs: Prefs,
    listState: LazyListState,
    compiled: CompiledFilter,
    visible: List<LogEntry>,
    levelCounts: IntArray,
    state: CaptureController.State,
    logFile: String?,
    issueHits: List<IssueHit>,
    onOpenDoc: () -> Unit,
    /** 「下一个命中」的定位在 LogScreen 里做：它要按 displayRows 折算出真实行号 */
    onFindNext: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current
    val showCounts = prefs.showLevelCounts

    FilterSection(
        query = ui.query,
        onQueryChange = {
            ui.query = it
            prefs.searchRegex = ui.regex
        },
        regex = ui.regex,
        onRegexToggle = {
            ui.regex = !ui.regex
            prefs.searchRegex = ui.regex
        },
        regexInvalid = compiled.regexInvalid,
        advancedOpen = ui.advancedOpen,
        onAdvancedToggle = { ui.advancedOpen = !ui.advancedOpen },
        minPriority = ui.minPriority,
        onPriorityChange = {
            ui.minPriority = it
            prefs.minLevelPriority = it
        },
        onlyCrash = ui.onlyCrash,
        onCrashToggle = { ui.onlyCrash = !ui.onlyCrash },
        onlyBookmarked = ui.onlyBookmarked,
        onBookmarkToggle = { ui.onlyBookmarked = !ui.onlyBookmarked },
        bookmarkCount = Bookmarks.count(),
        levelCounts = levelCounts,
        showCounts = showCounts,
        statsLine = buildString {
            append("总计 ")
            append(fmt(ui.stats.total))
            append(" 条 · 命中 ")
            append(fmt(visible.size.toLong()))
            append(" 条 · 崩溃 ")
            append(ui.stats.crashes)
            if (!logFile.isNullOrBlank()) {
                // 别把「本会话有落盘文件」写成「正在写」：采集停了/暂停了，
                // logFile 仍然非空，标注要跟着采集状态走
                val writing = state is CaptureController.State.Capturing && !ui.paused
                append(if (writing) " · 落盘中" else " · 已落盘")
            }
        }
    )

    ToolsBar(
        presets = ui.presets,
        activePreset = ui.activePreset,
        onApplyPreset = { p ->
            ui.activePreset = p.name
            ui.query = p.query
            ui.regex = p.regex
            prefs.searchRegex = p.regex
            ui.minPriority = p.minPriority
            prefs.minLevelPriority = p.minPriority
            ui.onlyCrash = p.onlyCrash
            ui.onlyBookmarked = p.onlyBookmarked
            ui.tagInclude = p.tagsInclude
            ui.tagExclude = p.tagsExclude
            ui.pidFilter = p.pids
            prefs.filterTagsInclude = p.tagsInclude
            prefs.filterTagsExclude = p.tagsExclude
            prefs.filterPids = p.pids
        },
        onDeletePreset = { p ->
            if (!p.builtin) {
                ui.presets = PresetStore.remove(prefs, p.name)
                if (ui.activePreset == p.name) ui.activePreset = null
            }
        },
        onSaveCurrent = {
            val name = buildString {
                append(if (ui.query.isBlank()) "筛选" else ui.query.take(12))
                if (ui.onlyCrash) append("·崩溃")
                if (ui.onlyBookmarked) append("·书签")
                if (ui.minPriority > 0) append("·")
                    .append(LogLevel.entries.firstOrNull { it.priority == ui.minPriority }?.letter ?: "+")
            }
            val preset = FilterPreset(
                name = name,
                query = ui.query,
                regex = ui.regex,
                minPriority = ui.minPriority,
                onlyCrash = ui.onlyCrash,
                onlyBookmarked = ui.onlyBookmarked,
                tagsInclude = ui.tagInclude,
                tagsExclude = ui.tagExclude,
                pids = ui.pidFilter
            )
            ui.presets = PresetStore.upsert(prefs, preset)
            ui.activePreset = name
            Toast.makeText(context, "已存为「$name」", Toast.LENGTH_SHORT).show()
        },
        highlightCount = ui.highlightRules.count { it.enabled },
        onOpenHighlights = { ui.highlightOpen = true },
        crashSummary = if (ui.crashCounts.first > 0) "崩溃 今日${ui.crashCounts.first}" else "崩溃归集",
        onOpenCrashes = { ui.crashOpen = true },
        findTotal = if (ui.query.isBlank()) 0 else visible.size,
        onFindNext = onFindNext,
        issueSummary = if (issueHits.isEmpty()) "问题体检" else "问题体检 ${issueHits.size}",
        onOpenIssues = { ui.issueOpen = true },
        overlayOn = ui.overlayAlive,
        onOpenOverlay = {
            if (OverlayLogService.alive) {
                OverlayLogService.stop(context)
                ui.overlayAlive = false
                Toast.makeText(context, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
            } else if (!OverlayLogService.canDraw(context)) {
                Toast.makeText(context, "先给「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            } else {
                OverlayLogService.start(context)
                ui.overlayAlive = true
                Toast.makeText(context, "悬浮窗已开，可拖动", Toast.LENGTH_SHORT).show()
            }
        },
        onOpenFile = {
            if (OfflineLog.entries.isNotEmpty()) {
                OfflineLog.clear()
                Toast.makeText(context, "已回到实时日志", Toast.LENGTH_SHORT).show()
            } else {
                onOpenDoc()
            }
        },
        onOpenHistory = {
            ui.filesOpen = true
            ui.fileItems = emptyList()
            scope.launch {
                ui.fileItems = withContext(Dispatchers.IO) { LogFiles.list(context) }
            }
        },
        onSnapshot = {
            if (visible.isEmpty()) {
                Toast.makeText(context, "没有可存的日志", Toast.LENGTH_SHORT).show()
            } else {
                Thread {
                    val f = SnapshotImage.render(
                        context,
                        visible,
                        prefs.lastLabel.ifBlank { "日志快照" }
                    )
                    Handler(Looper.getMainLooper()).post {
                        if (f != null) {
                            Toast.makeText(context, "已存图：${f.name}", Toast.LENGTH_LONG).show()
                            runCatching { ExportFormats.shareFile(context, f, "image/png") }
                        } else {
                            Toast.makeText(context, "存图失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
        },
        onOpenBuffers = { ui.buffersOpen = true },
        bufferSummary = ui.bufferSet.joinToString("/") { it },
        onOpenTags = { ui.tagsOpen = true },
        selectMode = ui.selectMode,
        selectCount = ui.picked.size,
        bookmarkCount = remember(ui.bookmarkVersion) { Bookmarks.count() },
        onClearMarks = {
            Bookmarks.clear()
            ui.bookmarkVersion++
            Toast.makeText(context, "已清除全部收藏", Toast.LENGTH_SHORT).show()
        },
        onToggleSelect = {
            ui.selectMode = !ui.selectMode
            if (!ui.selectMode) ui.picked.clear()
        }
    )

    if (OfflineLog.entries.isNotEmpty()) {
        OfflineSourceBar(
            name = OfflineLog.sourceName,
            count = OfflineLog.entries.size,
            onClose = { OfflineLog.clear() }
        )
    }

    if (ui.advancedOpen) {
        AdvancedFilterPanel(
            tagInclude = ui.tagInclude,
            onTagInclude = {
                ui.tagInclude = it
                prefs.filterTagsInclude = it
            },
            tagExclude = ui.tagExclude,
            onTagExclude = {
                ui.tagExclude = it
                prefs.filterTagsExclude = it
            },
            pids = ui.pidFilter,
            onPids = {
                ui.pidFilter = it
                prefs.filterPids = it
            },
            onReset = {
                ui.query = ""
                ui.regex = false
                prefs.searchRegex = false
                ui.minPriority = LogLevel.VERBOSE.priority
                prefs.minLevelPriority = ui.minPriority
                ui.onlyCrash = false
                ui.onlyBookmarked = false
                ui.tagInclude = ""
                ui.tagExclude = ""
                ui.pidFilter = ""
                prefs.filterTagsInclude = ""
                prefs.filterTagsExclude = ""
                prefs.filterPids = ""
            }
        )
    }
}

/** 列表上方的四条提示：跳转提示、暂停横幅、通道被拦、搜索历史。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogNotices(
    ui: LogScreenUi,
    prefs: Prefs,
    blocked: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    ui.jumpHint?.let { hint ->
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            shape = RoundedCornerShape(10.dp),
            color = palette.warn.copy(alpha = 0.14f),
            contentColor = palette.warn
        ) {
            Text(
                hint,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }

    if (ui.paused) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(10.dp),
            color = palette.warn.copy(alpha = 0.12f),
            contentColor = palette.warn
        ) {
            Text(
                if (ui.heldCount > 0) {
                    "已暂停采集，这期间新进来 ${fmt(ui.heldCount.toLong())} 条，点「继续」补齐"
                } else {
                    "已暂停采集 · 日志流已停，点「继续」恢复抓取"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }

    if (blocked) {
        ChannelBlockedCard(
            onDismiss = { CaptureController.dismissChannelBlocked() },
            onOpenDialer = {
                if (!DeviceLogGuard.openDialer(context)) {
                    Exporter.copy(context, DeviceLogGuard.ENGINEER_CODE)
                    Toast.makeText(
                        context,
                        "无法调起拨号盘，工程码已复制",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onRaiseLevel = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { DeviceLogGuard.raiseLevel() }
                    Toast.makeText(context, r, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    if (ui.history.isNotEmpty()) {
        HistoryChips(
            history = ui.history.take(8),
            onPick = { q ->
                ui.query = q
                ui.regex = false
            },
            onRemove = { q ->
                prefs.dropQuery(q)
                ui.history = prefs.queryHistory.split("\n").filter { it.isNotBlank() }
            }
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** 日志列表（含小地图与空态）。ColumnScope 扩展：里面要用 weight(1f) 吃掉剩余高度。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.LogListArea(
    ui: LogScreenUi,
    visible: List<LogEntry>,
    displayRows: List<LogRowItem>,
    compiled: CompiledFilter,
    highlighter: CompiledHighlights,
    listState: LazyListState,
    prefs: Prefs,
    blocked: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    val highlightCrash = prefs.highlightCrash
    val compact = prefs.compactRows
    val showTime = prefs.showTime
    val showPid = prefs.showPid
    val fontSize = prefs.fontSize
    val showMinimap = prefs.showMinimap

    Spacer(Modifier.height(4.dp))

    Box(Modifier.weight(1f)) {
        if (visible.isEmpty()) {
            EmptyLogs(
                blocked = blocked,
                hasAny = ui.items.isNotEmpty(),
                onlyBookmarked = ui.onlyBookmarked
            )
        } else {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)
                ) {
                    items(items = displayRows, key = { it.key }) { row ->
                        when (row) {
                            is LogRowItem.One -> {
                                val entry = row.entry
                                val bookmarked = Bookmarks.has(entry)
                                LogRow(
                                    entry = entry,
                                    highlightCrash = highlightCrash,
                                    compact = compact,
                                    showTime = showTime,
                                    showPid = showPid,
                                    fontSize = fontSize,
                                    bookmarked = bookmarked,
                                    flashed = entry.id == ui.jumpFlash,
                                    compiled = compiled,
                                    highlighter = highlighter,
                                    queryColor = palette.warn,
                                    selectMode = ui.selectMode,
                                    selected = entry.id in ui.picked,
                                    onToggleSelect = {
                                        if (entry.id in ui.picked) ui.picked.remove(entry.id)
                                        else ui.picked.add(entry.id)
                                    },
                                    onMenu = { ui.actionEntry = entry },
                                    onCopy = {
                                        Exporter.copy(context, Exporter.formatEntry(entry))
                                        Toast.makeText(context, "已复制该行", Toast.LENGTH_SHORT).show()
                                    },
                                    onBookmark = {
                                        val added = Bookmarks.toggle(entry)
                                        // 取消收藏后触发器必须递增，否则「只看书签」视图不会刷新
                                        ui.bookmarkVersion++
                                        Toast.makeText(
                                            context,
                                            if (added) "已收藏该行" else "已取消收藏",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            }

                            is LogRowItem.Fold -> {
                                FoldRow(
                                    count = row.count,
                                    kind = row.kind,
                                    head = row.head,
                                    expanded = row.expandKey in ui.expandedFolds,
                                    onToggle = {
                                        ui.expandedFolds = if (row.expandKey in ui.expandedFolds) {
                                            ui.expandedFolds - row.expandKey
                                        } else {
                                            ui.expandedFolds + row.expandKey
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                if (showMinimap) {
                    MinimapBar(
                        rows = visible,
                        onSeek = { ratio ->
                            // ratio 0 = 小地图顶部 = 最旧的一条；reverseLayout 下 index 0 在底部。
                            // 映射先落到 visible 下标，再换算成 displayRows 的行号：折叠区间会压缩行数，
                            // 直接按 displayRows.size 取比例，折叠越多偏得越远。
                            val lastV = (visible.size - 1).coerceAtLeast(0)
                            val vIdx = ((1f - ratio) * lastV).toInt().coerceIn(0, lastV)
                            val target = visible[vIdx]
                            val idx = displayRows.indexOfFirst {
                                it is LogRowItem.One && it.entry.id == target.id
                            }
                            if (idx >= 0) {
                                scope.launch { runCatching { listState.scrollToItem(idx) } }
                            } else {
                                // 落在折叠区间内部：展开它，行号由 LogScreen 的 pendingJump 补定位
                                val fold = displayRows.firstOrNull {
                                    it is LogRowItem.Fold && vIdx >= it.start && vIdx < it.end
                                } as? LogRowItem.Fold
                                if (fold != null) {
                                    ui.expandedFolds = ui.expandedFolds + fold.expandKey
                                    ui.pendingJumpId = target.id
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(top = 4.dp, bottom = 8.dp, end = 2.dp)
                    )
                }
            }
        }
    }
}

/** 八个弹窗：单行菜单、tag 统计、历史文件、缓冲区、问题体检、导出、高亮规则、崩溃归集。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogDialogs(
    ui: LogScreenUi,
    prefs: Prefs,
    visible: List<LogEntry>,
    targetPkg: String,
    issueHits: List<IssueHit>,
    onJump: (LogEntry) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    ui.actionEntry?.let { entry ->
        EntryActionsDialog(
            title = "${entry.level.letter}/${entry.tag}",
            bookmarked = Bookmarks.has(entry),
            onCopy = {
                Exporter.copy(context, Exporter.formatEntry(entry))
                Toast.makeText(context, "已复制该行", Toast.LENGTH_SHORT).show()
            },
            onShare = { Exporter.shareText(context, Exporter.formatEntry(entry)) },
            onBookmark = {
                Bookmarks.toggle(entry)
                ui.bookmarkVersion++
            },
            onFilterTag = {
                ui.query = "tag:${entry.tag}"
                ui.regex = false
            },
            onFilterPid = {
                ui.query = "pid:${entry.pid}"
                ui.regex = false
            },
            onDismiss = { ui.actionEntry = null }
        )
    }

    if (ui.tagsOpen) {
        val counts = remember(visible, ui.tagsOpen) {
            visible.groupingBy { it.tag }.eachCount().entries
                .sortedByDescending { it.value }
                .take(40)
                .map { it.key to it.value }
        }
        TagStatsDialog(
            tags = counts,
            total = visible.size,
            onPick = { tag ->
                ui.tagsOpen = false
                ui.query = "tag:$tag"
                ui.regex = false
            },
            onDismiss = { ui.tagsOpen = false }
        )
    }

    if (ui.filesOpen) {
        LogFilesDialog(
            items = ui.fileItems,
            onOpen = { item ->
                // 直接回放：以前点历史文件交给系统 ACTION_VIEW，会弹「打开方式」把日志踢出 App。
                // 这里内部解析后进离线视图，过滤与搜索照常可用。
                Thread {
                    val parsed = runCatching {
                        item.file.bufferedReader().use { OfflineImport.parse(it) }
                    }.getOrDefault(emptyList())
                    Handler(Looper.getMainLooper()).post {
                        if (parsed.isEmpty()) {
                            Toast.makeText(context, "读不出内容，确认是 logcat 文本", Toast.LENGTH_SHORT)
                                .show()
                        } else {
                            OfflineLog.load(parsed, item.name)
                            ui.filesOpen = false
                            Toast.makeText(
                                context,
                                "已回放 ${item.name}（${parsed.size} 行）",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }.start()
            },
            onShare = { item -> runCatching { LogFiles.share(context, item) } },
            onDelete = { item ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { LogFiles.remove(item) }
                    if (ok) {
                        ui.fileItems = withContext(Dispatchers.IO) { LogFiles.list(context) }
                        Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { ui.filesOpen = false }
        )
    }

    if (ui.buffersOpen) {
        BufferDialog(
            selected = ui.bufferSet,
            onToggle = { key ->
                val next = if (ui.bufferSet.contains(key)) ui.bufferSet - key else ui.bufferSet + key
                if (next.isEmpty()) {
                    Toast.makeText(context, "至少留一个缓冲区", Toast.LENGTH_SHORT).show()
                } else {
                    ui.bufferSet = next
                    prefs.buffers = next.joinToString(",")
                    Toast.makeText(context, "改完重启抓取生效", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { ui.buffersOpen = false },
            onClearDevice = {
                Thread {
                    val r = dev.loglens.shizuku.ShellRunner.exec("logcat -b all -c")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            context,
                            if (r.code == 0) "设备日志缓冲已清空" else "清空失败：${r.stderr.take(60)}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }.start()
            }
        )
    }

    if (ui.issueOpen) {
        IssueDialog(
            hits = issueHits,
            onJump = { hit ->
                ui.issueOpen = false
                onJump(hit.sample)
            },
            onDismiss = { ui.issueOpen = false }
        )
    }

    if (ui.exportOpen) {
        ExportDialog(
            format = ui.exportFormat,
            onFormat = {
                ui.exportFormat = it
                prefs.exportFormat = it.id
            },
            onDismiss = { ui.exportOpen = false },
            onExport = {
                ui.exportOpen = false
                scope.launch {
                    // 导出跟随当前筛选：以前不管怎么筛都导全量，导出来的根本不是眼前这份
                    val data = withContext(Dispatchers.IO) { visible.asReversed() }
                    if (data.isEmpty()) {
                        Toast.makeText(context, "没有可导出的日志", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val file = withContext(Dispatchers.IO) {
                        ExportFormats.export(context, data, targetPkg, ui.exportFormat)
                    }
                    Toast.makeText(
                        context,
                        if (file != null) "已导出 ${file.name}" else "导出失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onShare = {
                ui.exportOpen = false
                scope.launch {
                    // 导出跟随当前筛选：以前不管怎么筛都导全量，导出来的根本不是眼前这份
                    val data = withContext(Dispatchers.IO) { visible.asReversed() }
                    if (data.isEmpty()) {
                        Toast.makeText(context, "没有可分享的日志", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val file = withContext(Dispatchers.IO) {
                        ExportFormats.export(context, data, targetPkg, ui.exportFormat)
                    }
                    if (file != null) {
                        runCatching {
                            ExportFormats.shareFile(context, file, ui.exportFormat.mime)
                        }
                    } else {
                        Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (ui.highlightOpen) {
        HighlightRulesDialog(
            rules = ui.highlightRules,
            onSave = {
                ui.highlightRules = it
                HighlightStore.save(prefs, it)
            },
            onDismiss = { ui.highlightOpen = false }
        )
    }

    if (ui.crashOpen) {
        CrashStatsDialog(
            rows = ui.crashRows,
            todayCount = ui.crashCounts.first,
            weekCount = ui.crashCounts.second,
            totalCount = ui.crashCounts.third,
            onClear = {
                CrashStats.clear(prefs)
                ui.crashRows = emptyList()
                ui.crashCounts = Triple(0, 0, 0)
            },
            onJump = { agg ->
                // 跳到列表里第一条同签名日志。sample 统计时被截断到 600 字，
                // 所以这里同样按前缀长度比较，别拿完整 message 做全等。
                ui.crashOpen = false
                val sample = agg.sample
                val target = if (sample.isBlank()) {
                    null
                } else {
                    visible.firstOrNull { it.message.take(sample.length) == sample }
                }
                if (target != null) onJump(target)
                else Toast.makeText(context, "该崩溃不在当前视图内", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { ui.crashOpen = false }
        )
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSection(
    query: String,
    onQueryChange: (String) -> Unit,
    regex: Boolean,
    onRegexToggle: () -> Unit,
    regexInvalid: Boolean,
    advancedOpen: Boolean,
    onAdvancedToggle: () -> Unit,
    minPriority: Int,
    onPriorityChange: (Int) -> Unit,
    onlyCrash: Boolean,
    onCrashToggle: () -> Unit,
    onlyBookmarked: Boolean,
    onBookmarkToggle: () -> Unit,
    bookmarkCount: Int,
    levelCounts: IntArray,
    showCounts: Boolean,
    statsLine: String
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            placeholder = {
                Text(
                    if (regex) "正则匹配 message / tag / 时间" else "过滤关键字、tag 或时间",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "清除",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            textStyle = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                LogLevel.VERBOSE,
                LogLevel.DEBUG,
                LogLevel.INFO,
                LogLevel.WARN,
                LogLevel.ERROR
            ).forEach { lv ->
                val active = lv.priority >= minPriority
                val tint = levelColor(lv, palette)
                FilterChip(
                    selected = active,
                    onClick = { onPriorityChange(lv.priority) },
                    label = {
                        val n = when (lv.priority) {
                            LogLevel.ERROR.priority -> levelCounts[4]
                            LogLevel.WARN.priority -> levelCounts[3]
                            LogLevel.INFO.priority -> levelCounts[2]
                            LogLevel.DEBUG.priority -> levelCounts[1]
                            else -> levelCounts[0]
                        }
                        Text(
                            if (showCounts) "${lv.letter} $n" else lv.letter,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = colors.onSurfaceVariant,
                        selectedContainerColor = tint.copy(alpha = 0.18f),
                        selectedLabelColor = tint
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = active,
                        borderColor = colors.outlineVariant,
                        selectedBorderColor = tint.copy(alpha = 0.5f)
                    )
                )
            }

            FilterChip(
                selected = onlyCrash,
                onClick = onCrashToggle,
                label = {
                    Text("只看崩溃", style = MaterialTheme.typography.labelMedium)
                },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = colors.onSurfaceVariant,
                    selectedContainerColor = palette.error.copy(alpha = 0.16f),
                    selectedLabelColor = palette.error
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = onlyCrash,
                    borderColor = colors.outlineVariant,
                    selectedBorderColor = palette.error.copy(alpha = 0.5f)
                )
            )

            FilterChip(
                selected = regex,
                onClick = onRegexToggle,
                label = {
                    Text(
                        if (regexInvalid) "正则写错了" else "正则",
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = colors.onSurfaceVariant,
                    selectedContainerColor = palette.info.copy(alpha = 0.16f),
                    selectedLabelColor = palette.info
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = regex,
                    borderColor = colors.outlineVariant,
                    selectedBorderColor = palette.info.copy(alpha = 0.5f)
                )
            )

            FilterChip(
                selected = onlyBookmarked,
                onClick = onBookmarkToggle,
                label = {
                    Text(
                        if (bookmarkCount > 0) "书签 $bookmarkCount" else "书签",
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = colors.onSurfaceVariant,
                    selectedContainerColor = palette.warn.copy(alpha = 0.16f),
                    selectedLabelColor = palette.warn
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = onlyBookmarked,
                    borderColor = colors.outlineVariant,
                    selectedBorderColor = palette.warn.copy(alpha = 0.5f)
                )
            )

            FilterChip(
                selected = advancedOpen,
                onClick = onAdvancedToggle,
                label = {
                    Text("tag / pid", style = MaterialTheme.typography.labelMedium)
                },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = colors.onSurfaceVariant,
                    selectedContainerColor = colors.secondary.copy(alpha = 0.16f),
                    selectedLabelColor = colors.secondary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = advancedOpen,
                    borderColor = colors.outlineVariant,
                    selectedBorderColor = colors.secondary.copy(alpha = 0.5f)
                )
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            statsLine,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedFilterPanel(
    tagInclude: String,
    onTagInclude: (String) -> Unit,
    tagExclude: String,
    onTagExclude: (String) -> Unit,
    pids: String,
    onPids: (String) -> Unit,
    onReset: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "按 tag / 进程号精确过滤，多个用逗号分隔",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onReset,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)
                ) {
                    Text("重置筛选", style = MaterialTheme.typography.labelMedium)
                }
            }
            OutlinedTextField(
                value = tagInclude,
                onValueChange = onTagInclude,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = {
                    Text("只看这些 tag，如 ActivityManager,NetworkManager", style = MaterialTheme.typography.labelSmall)
                },
                textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = tagExclude,
                onValueChange = onTagExclude,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = {
                    Text("排除这些 tag，如 OpenGLRenderer,Choreographer", style = MaterialTheme.typography.labelSmall)
                },
                textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pids,
                onValueChange = onPids,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = {
                    Text("只看这些进程号，如 12345,12399", style = MaterialTheme.typography.labelSmall)
                },
                textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportDialog(
    format: ExportFormat,
    onFormat: (ExportFormat) -> Unit,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceVariant,
        title = {
            Text("导出日志", style = MaterialTheme.typography.titleSmall)
        },
        text = {
            Column {
                Text(
                    "选一种格式，txt 最通用，csv 能直接进表格，json 方便脚本处理，zip 附带设备信息与崩溃摘要。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Text(
                    "导出范围跟当前视图一致：有筛选就只导筛出来的，正在看离线日志就导离线内容。",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExportFormat.entries.forEach { f ->
                        FilterChip(
                            selected = f == format,
                            onClick = { onFormat(f) },
                            label = {
                                Text(f.label, style = MaterialTheme.typography.labelMedium)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = colors.onSurfaceVariant,
                                selectedContainerColor = colors.primary.copy(alpha = 0.16f),
                                selectedLabelColor = colors.primary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = f == format,
                                borderColor = colors.outlineVariant,
                                selectedBorderColor = colors.primary.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    format.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onExport,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)
            ) {
                Text("导出到文件", style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onShare,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.secondary)
                ) {
                    Text("直接分享", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)
                ) {
                    Text("取消", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelBlockedCard(
    onDismiss: () -> Unit,
    onOpenDialer: () -> Unit,
    onRaiseLevel: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant),
        border = BorderStroke(1.dp, palette.warn.copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "日志通道被系统限制",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.warn,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = colors.onSurfaceVariant
                    )
                ) {
                    Text("收起", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                "荣耀和华为 ROM 默认关掉「AP 日志」，这时候 logcat 只会吐极少量的系统日志。" +
                    "拨号输 ${DeviceLogGuard.ENGINEER_CODE}，进后台设置，找到 LOG 设置或 AP LOG 设置，" +
                    "把 AP 日志打开。开完这个 App 会自己接上，不用重启。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(
                    onClick = onOpenDialer,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)
                ) {
                    Text("打开拨号盘", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = onRaiseLevel,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.secondary)
                ) {
                    Text("提升日志等级", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun EmptyLogs(blocked: Boolean, hasAny: Boolean, onlyBookmarked: Boolean) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(
                Icons.Rounded.Terminal,
                contentDescription = null,
                tint = if (blocked) palette.warn else colors.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = when {
                    blocked -> "系统把日志通道掐了，按上面的步骤打开 AP 日志"
                    onlyBookmarked -> "还没有收藏任何一行，长按日志可以把关键行存成书签"
                    !hasAny -> "还没等到日志，去目标应用里点一下就有"
                    else -> "当前筛选条件下没有匹配的日志"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogActionBar(
    paused: Boolean,
    autoScroll: Boolean,
    onPauseToggle: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onFollowToggle: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    BottomAppBar(
        containerColor = colors.surfaceVariant,
        contentColor = colors.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarAction(
                icon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                label = if (paused) "继续" else "暂停",
                tint = if (paused) palette.info else colors.onSurface,
                onClick = onPauseToggle
            )
            BarAction(
                icon = Icons.Rounded.DeleteSweep,
                label = "清空",
                tint = palette.warn,
                onClick = onClear
            )
            BarAction(
                icon = Icons.Rounded.Save,
                label = "导出",
                tint = colors.secondary,
                onClick = onExport
            )
            BarAction(
                icon = Icons.Rounded.IosShare,
                label = "分享",
                tint = colors.primary,
                onClick = onShare
            )
            BarAction(
                icon = Icons.Rounded.VerticalAlignBottom,
                label = if (autoScroll) "跟随中" else "已脱离",
                tint = if (autoScroll) palette.info else colors.onSurfaceVariant,
                onClick = onFollowToggle
            )
        }
    }
}

@Composable
private fun BarAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(
    entry: LogEntry,
    highlightCrash: Boolean,
    compact: Boolean,
    showTime: Boolean,
    showPid: Boolean,
    fontSize: Int,
    bookmarked: Boolean,
    flashed: Boolean = false,
    compiled: CompiledFilter,
    highlighter: CompiledHighlights,
    queryColor: Color,
    selectMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onMenu: () -> Unit = {},
    onCopy: () -> Unit,
    onBookmark: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current
    val color = levelColor(entry.level, palette)
    val crash = highlightCrash && entry.isCrash
    val background = if (crash) palette.crashBackground else Color.Transparent
    // 跳转落点闪烁高亮，2 秒内淡出（由外层 jumpFlash 控制）
    val flashTint = palette.warn.copy(alpha = if (flashed) 0.26f else 0f)

    val messageText = remember(entry.message, compiled, highlighter) {
        val ranges = compiled.highlight(entry.message)
        val spans = highlighter.spans(entry.message)
        if (spans.isEmpty()) markText(entry.message, ranges, queryColor)
        else markTextMulti(entry.message, ranges, spans, queryColor)
    }
    val tagText = remember(entry.tag, compiled, highlighter) {
        val ranges = compiled.highlight(entry.tag)
        val spans = highlighter.spans(entry.tag)
        if (spans.isEmpty()) markText(entry.tag, ranges, queryColor)
        else markTextMulti(entry.tag, ranges, spans, queryColor)
    }

    if (entry.isContinuation) {
        Text(
            text = messageText,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                lineHeight = (fontSize + 4.5f).sp
            ),
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .background(if (selected) colors.primary.copy(alpha = 0.14f) else flashTint)
                .combinedClickable(
                    onClick = { if (selectMode) onToggleSelect() else onCopy() },
                    onLongClick = { if (selectMode) onToggleSelect() else onMenu() }
                )
                .padding(start = 24.dp, end = 12.dp, top = 1.dp, bottom = 1.dp)
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (selected) colors.primary.copy(alpha = 0.14f) else background)
            .background(flashTint)
            .combinedClickable(
                onClick = { if (selectMode) onToggleSelect() else onCopy() },
                onLongClick = { if (selectMode) onToggleSelect() else onMenu() }
            )
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(color.copy(alpha = if (crash) 1f else 0.55f))
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(
                    start = 10.dp,
                    end = 12.dp,
                    top = if (compact) 1.5.dp else 3.dp,
                    bottom = if (compact) 1.5.dp else 4.dp
                )
        ) {
            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showTime) {
                        Text(
                            entry.time.takeLast(17),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = (fontSize - 1.5f).sp
                            ),
                            color = colors.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        entry.level.letter,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = (fontSize - 1).sp
                        ),
                        color = color
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tagText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = (fontSize - 1).sp
                        ),
                        color = color.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (showPid) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${entry.pid}/${entry.tid}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = (fontSize - 2).sp
                            ),
                            color = colors.onSurfaceVariant
                        )
                    }
                    if (bookmarked) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Rounded.Bookmark,
                            contentDescription = "已收藏",
                            tint = palette.warn,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    if (crash) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = palette.error.copy(alpha = 0.2f),
                            contentColor = palette.error
                        ) {
                            Text(
                                "崩溃",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(1.dp))
            }

            Text(
                text = messageText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize + 4.5f).sp
                ),
                color = if (crash) palette.error else colors.onSurface,
                maxLines = if (compact) 1 else Int.MAX_VALUE,
                overflow = if (compact) TextOverflow.Ellipsis else TextOverflow.Clip
            )
        }
    }
}

/** 关键词命中的片段用底色标出来，正则和字面量都走这里 */
private fun markText(text: String, ranges: List<IntRange>, color: Color): AnnotatedString {
    if (ranges.isEmpty() || text.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (r in ranges) {
            val start = r.first.coerceIn(0, text.length)
            val end = (r.last + 1).coerceIn(start, text.length)
            if (end > start) {
                addStyle(SpanStyle(background = color.copy(alpha = 0.35f)), start, end)
            }
        }
    }
}

private fun levelColor(level: LogLevel, palette: LogPalette): Color = when (level) {
    LogLevel.VERBOSE -> palette.verbose
    LogLevel.DEBUG -> palette.debug
    LogLevel.INFO -> palette.info
    LogLevel.WARN -> palette.warn
    LogLevel.ERROR, LogLevel.FATAL, LogLevel.ASSERT -> palette.error
    LogLevel.RAW -> palette.raw
}

private fun fmt(v: Long): String =
    if (v >= 10_000) String.format(Locale.US, "%.1f万", v / 10_000f) else v.toString()

/** 进程号手输框：逗号/空格/中文逗号都认，非数字直接丢掉 */
private fun parsePids(raw: String): Set<Int> =
    raw.split(',', '，', ' ', ';')
        .mapNotNull { it.trim().toIntOrNull() }
        .toSet()

/**
 * 裁到行数上限，顺手把被裁掉的 id 从去重集合里摘掉。
 * 不用 subList().clear()，SnapshotStateList 的子视图操作行为不稳。
 */
private fun trimList(list: MutableList<LogEntry>, ids: MutableSet<Long>, max: Int) {
    while (list.size > max) {
        ids.remove(list.removeAt(list.size - 1).id)
    }
}

/** 列表行：普通日志行，或一段被折叠的堆栈 */
/** 折叠类型：堆栈续行 / 完全相同的重复行 / 同 tag 连续刷屏 */
private enum class FoldKind { STACK, EXACT, FLOOD }

private sealed class LogRowItem {
    abstract val key: Any

    class One(val entry: LogEntry) : LogRowItem() {
        override val key: Any get() = entry.id
    }

    class Fold(
        val id: Long,
        val count: Int,
        val head: LogEntry,
        val kind: FoldKind = FoldKind.STACK,
        /** 折叠覆盖的 visible 区间 [start, end)：跳转到区间内的非头行时要靠它找回 */
        val start: Int = 0,
        val end: Int = 0
    ) : LogRowItem() {
        override val key: Any get() = expandKey

        /** 展开/收起状态也用这个键：同一行被 EXACT 与 FLOOD 折叠过时不会互相串 */
        val expandKey: String get() = when (kind) {
            FoldKind.STACK -> "fold-$id"
            FoldKind.EXACT -> "rep-$id"
            FoldKind.FLOOD -> "flood-$id"
        }
    }
}

/**
 * 把连续 ≥4 行的堆栈折叠成一行。
 * 崩溃栈动辄几十行，不折叠的话一屏只能看一条日志。
 */
private fun foldContinuations(
    rows: List<LogEntry>,
    expanded: Set<String>
): List<LogRowItem> {
    val out = ArrayList<LogRowItem>(rows.size)
    var i = 0
    while (i < rows.size) {
        val e = rows[i]

        if (e.isContinuation) {
            var j = i
            while (j < rows.size && rows[j].isContinuation) j++
            val run = j - i
            if (run >= 4) {
                // 折叠头始终保留：展开后还点得到「收起」，否则一展开就再也收不回去
                out.add(
                    LogRowItem.Fold(
                        id = e.id, count = run, head = e, kind = FoldKind.STACK, start = i, end = j
                    )
                )
                if ("fold-${e.id}" in expanded) {
                    for (k in i until j) out.add(LogRowItem.One(rows[k]))
                }
            } else {
                for (k in i until j) out.add(LogRowItem.One(rows[k]))
            }
            i = j
            continue
        }

        // ① 完全相同的连续行：至少 3 条才折叠
        var rep = i + 1
        while (rep < rows.size && isSameLine(rows[rep], e)) rep++
        val repRun = rep - i
        if (repRun >= 3) {
            out.add(
                LogRowItem.Fold(
                    id = e.id, count = repRun, head = e, kind = FoldKind.EXACT, start = i, end = rep
                )
            )
            if ("rep-${e.id}" in expanded) {
                for (k in i until rep) out.add(LogRowItem.One(rows[k]))
            }
            i = rep
            continue
        }

        // ② 同 tag 同级别连续刷屏：内容带变化数字的那种（frameRate=NaN、took 123ms），
        //    逐字比对永远命中不了，只能按 tag 归并。阈值抬高到 8 条，避免把正常日志吞掉。
        var flood = i + 1
        while (flood < rows.size && isSameTag(rows[flood], e)) flood++
        val floodRun = flood - i
        if (floodRun >= 8) {
            out.add(
                LogRowItem.Fold(
                    id = e.id, count = floodRun, head = e, kind = FoldKind.FLOOD, start = i, end = flood
                )
            )
            if ("flood-${e.id}" in expanded) {
                for (k in i until flood) out.add(LogRowItem.One(rows[k]))
            }
            i = flood
            continue
        }

        out.add(LogRowItem.One(e))
        i++
    }
    return out
}

/** 判定「同一个 tag 的连刷」：级别与 tag 相同即可，内容可以不同 */
private fun isSameTag(a: LogEntry, b: LogEntry): Boolean =
    a.level == b.level && a.tag == b.tag &&
        // 空 tag 的 RAW 行聚合成「连续刷屏」没有意义，也不该吞掉原始输出
        a.tag.isNotBlank() && a.level != LogLevel.RAW &&
        !a.isCrash && !b.isCrash && !a.isContinuation && !b.isContinuation

/** 判定「同一行」：级别、tag、内容一致，且都不是崩溃行（崩溃必须逐条留着看） */
private fun isSameLine(a: LogEntry, b: LogEntry): Boolean =
    a.level == b.level && a.tag == b.tag && a.message == b.message &&
        !a.isCrash && !b.isCrash && !a.isContinuation && !b.isContinuation

@Composable
private fun FoldRow(
    count: Int,
    kind: FoldKind,
    head: LogEntry,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(start = 24.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.outlineVariant)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                expanded && kind == FoldKind.EXACT -> "收起这 $count 条重复日志"
                expanded && kind == FoldKind.FLOOD -> "收起 ${head.tag} 的 $count 条刷屏日志"
                expanded -> "收起 $count 行堆栈"
                kind == FoldKind.EXACT -> "⋯ 重复 $count 条相同日志，点开看全过程"
                kind == FoldKind.FLOOD -> "⋯ ${head.tag} 连续刷屏 $count 条，点开看"
                else -> "⋯ 已折叠 $count 行堆栈，点开看"
            },
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
    }
}

/** 查询里出现 package:/pkg:/app:/process:/proc: 时，说明要先备好 pid→进程名表 */
private val PROC_QUERY = Regex("""(?:^|\s)(?:package|pkg|app|process|proc)[~=]?:""", RegexOption.IGNORE_CASE)

