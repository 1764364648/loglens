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

/** 日志列表（含小地图与空态）。ColumnScope 扩展：里面要用 weight(1f) 吃掉剩余高度。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ColumnScope.LogListArea(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelBlockedCard(
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
internal fun EmptyLogs(blocked: Boolean, hasAny: Boolean, onlyBookmarked: Boolean) {
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
internal fun LogActionBar(
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
internal fun BarAction(
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
internal fun LogRow(
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
