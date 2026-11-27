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
internal object Bookmarks {
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
internal class LogScreenUi(
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
