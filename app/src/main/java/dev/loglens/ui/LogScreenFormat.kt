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

/** 关键词命中的片段用底色标出来，正则和字面量都走这里 */
internal fun markText(text: String, ranges: List<IntRange>, color: Color): AnnotatedString {
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

internal fun levelColor(level: LogLevel, palette: LogPalette): Color = when (level) {
    LogLevel.VERBOSE -> palette.verbose
    LogLevel.DEBUG -> palette.debug
    LogLevel.INFO -> palette.info
    LogLevel.WARN -> palette.warn
    LogLevel.ERROR, LogLevel.FATAL, LogLevel.ASSERT -> palette.error
    LogLevel.RAW -> palette.raw
}

internal fun fmt(v: Long): String =
    if (v >= 10_000) String.format(Locale.US, "%.1f万", v / 10_000f) else v.toString()

/** 进程号手输框：逗号/空格/中文逗号都认，非数字直接丢掉 */
internal fun parsePids(raw: String): Set<Int> =
    raw.split(',', '，', ' ', ';')
        .mapNotNull { it.trim().toIntOrNull() }
        .toSet()

/**
 * 裁到行数上限，顺手把被裁掉的 id 从去重集合里摘掉。
 * 不用 subList().clear()，SnapshotStateList 的子视图操作行为不稳。
 */
internal fun trimList(list: MutableList<LogEntry>, ids: MutableSet<Long>, max: Int) {
    while (list.size > max) {
        ids.remove(list.removeAt(list.size - 1).id)
    }
}

/** 列表行：普通日志行，或一段被折叠的堆栈 */
/** 折叠类型：堆栈续行 / 完全相同的重复行 / 同 tag 连续刷屏 */
internal enum class FoldKind { STACK, EXACT, FLOOD }

internal sealed class LogRowItem {
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
internal fun foldContinuations(
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
internal fun isSameTag(a: LogEntry, b: LogEntry): Boolean =
    a.level == b.level && a.tag == b.tag &&
        // 空 tag 的 RAW 行聚合成「连续刷屏」没有意义，也不该吞掉原始输出
        a.tag.isNotBlank() && a.level != LogLevel.RAW &&
        !a.isCrash && !b.isCrash && !a.isContinuation && !b.isContinuation

/** 判定「同一行」：级别、tag、内容一致，且都不是崩溃行（崩溃必须逐条留着看） */
internal fun isSameLine(a: LogEntry, b: LogEntry): Boolean =
    a.level == b.level && a.tag == b.tag && a.message == b.message &&
        !a.isCrash && !b.isCrash && !a.isContinuation && !b.isContinuation

@Composable
internal fun FoldRow(
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
internal val PROC_QUERY = Regex("""(?:^|\s)(?:package|pkg|app|process|proc)[~=]?:""", RegexOption.IGNORE_CASE)
