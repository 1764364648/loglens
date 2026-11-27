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

/** 筛选区：搜索框、工具条、离线来源条、高级过滤面板。 */
@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3Api::class,
    kotlinx.coroutines.FlowPreview::class
)
@Composable
internal fun LogToolsArea(
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

/** 八个弹窗：单行菜单、tag 统计、历史文件、缓冲区、问题体检、导出、高亮规则、崩溃归集。 */
@OptIn(ExperimentalMaterial3Api::class)

@Composable
internal fun ExportDialog(
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
