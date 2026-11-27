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

/** 顶栏：目标包名 + 采集状态胶囊。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogTopBar(
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
internal fun LogBottomBar(
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

/** 列表上方的四条提示：跳转提示、暂停横幅、通道被拦、搜索历史。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogNotices(
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
