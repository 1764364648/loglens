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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogDialogs(
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
internal fun FilterSection(
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
internal fun AdvancedFilterPanel(
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
