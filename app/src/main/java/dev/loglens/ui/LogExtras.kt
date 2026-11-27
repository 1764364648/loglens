package dev.loglens.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.loglens.util.LogFiles
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.loglens.data.CrashAggregate
import dev.loglens.data.FilterPreset
import dev.loglens.data.IssueHit
import dev.loglens.data.IssueRule
import dev.loglens.data.CompiledHighlights
import dev.loglens.data.HighlightRule
import dev.loglens.data.HighlightStore
import dev.loglens.data.LogEntry
import dev.loglens.data.LogLevel
import dev.loglens.data.MarkSpan
import dev.loglens.ui.mx.ButtonDefaults
import dev.loglens.ui.mx.Card
import dev.loglens.ui.mx.CardDefaults
import dev.loglens.ui.mx.FilterChip
import dev.loglens.ui.mx.FilterChipDefaults
import dev.loglens.ui.mx.Icon
import dev.loglens.ui.mx.IconButton
import dev.loglens.ui.mx.OutlinedTextField
import dev.loglens.ui.mx.Switch
import dev.loglens.ui.mx.Text
import dev.loglens.ui.mx.TextButton
import dev.loglens.ui.theme.LocalLogPalette
import dev.loglens.ui.theme.LogPalette

/**
 * 右侧严重级别迷你图：一列细色带，颜色就是级别，纵向位置对应列表位置。
 *
 * 屏幕里几千行日志，翻到中段就分不清「上面还有多少条红字」。
 * 这条带子一眼能看出红色集中在哪一段，点一下直接跳过去。
 */
@Composable
fun MinimapBar(
    rows: List<LogEntry>,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    if (rows.isEmpty()) return
    val palette = LocalLogPalette.current
    var heightPx by remember { mutableStateOf(1) }

    val buckets = remember(rows, heightPx) {
        val n = (heightPx / 3).coerceIn(8, 240)
        val worst = IntArray(n)
        // rows[0] 是最新的一条，画到最下面；所以倒着填
        for (i in rows.indices) {
            val slot = n - 1 - (i.toLong() * n / rows.size).toInt()
            val p = rows[i].level.priority
            if (slot in worst.indices && p > worst[slot]) worst[slot] = p
        }
        worst
    }

    Canvas(
        modifier = modifier
            .width(7.dp)
            .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .pointerInput(rows.size, heightPx) {
                detectTapGestures { offset ->
                    // 只把「点在哪一段」的 0..1 比例抛给调用方。
                    // 这里不能用 rows.size 换算下标：列表开着堆栈折叠，实际渲染行数
                    // 和 rows 的数量对不上，而且 reverseLayout 下 index 0 在底部，
                    // 直接用比例算一定跳错行。
                    val ratio = (offset.y / heightPx.toFloat()).coerceIn(0f, 1f)
                    onSeek(ratio)
                }
            }
    ) {
        val n = buckets.size
        if (n == 0) return@Canvas
        val h = size.height / n
        for (i in 0 until n) {
            val p = buckets[i]
            if (p < 0) continue
            val c = minimapColor(p, palette)
            drawRect(
                color = c,
                topLeft = Offset(0f, i * h),
                size = Size(size.width, (h + 0.6f).coerceAtLeast(1f))
            )
        }
    }
}

private fun minimapColor(priority: Int, palette: LogPalette): Color = when {
    priority >= LogLevel.ERROR.priority -> palette.error
    priority >= LogLevel.WARN.priority -> palette.warn
    priority >= LogLevel.INFO.priority -> palette.info
    priority >= LogLevel.DEBUG.priority -> palette.debug
    else -> palette.verbose
}

/**
 * 工具条：预设、高亮规则、崩溃统计、下一条命中。
 * 都放在过滤区下面一行，滚动时跟着列表一起走，不占内容高度。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsBar(
    presets: List<FilterPreset>,
    activePreset: String?,
    onApplyPreset: (FilterPreset) -> Unit,
    onDeletePreset: (FilterPreset) -> Unit,
    onSaveCurrent: () -> Unit,
    highlightCount: Int,
    onOpenHighlights: () -> Unit,
    crashSummary: String,
    onOpenCrashes: () -> Unit,
    findTotal: Int,
    onFindNext: () -> Unit,
    issueSummary: String = "",
    onOpenIssues: () -> Unit = {},
    onOpenOverlay: () -> Unit = {},
    overlayOn: Boolean = false,
    onOpenFile: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onSnapshot: () -> Unit = {},
    onOpenBuffers: () -> Unit = {},
    bufferSummary: String = "",
    onOpenTags: () -> Unit = {},
    selectMode: Boolean = false,
    selectCount: Int = 0,
    onToggleSelect: () -> Unit = {},
    bookmarkCount: Int = 0,
    onClearMarks: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    // 这排入口原来靠横向滚动，滑不到就等于功能不存在（历史文件/存图/多选都可能被挤出屏幕）。
    // 改成自动换行：所有入口一次性可见，不依赖手势。
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        presets.forEach { preset ->
            val active = preset.name == activePreset
            FilterChip(
                selected = active,
                onClick = { onApplyPreset(preset) },
                label = { Text(preset.name, style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = colors.onSurfaceVariant,
                    selectedContainerColor = colors.primary.copy(alpha = 0.16f),
                    selectedLabelColor = colors.primary
                )
            )
        }
        FilterChip(
            selected = false,
            onClick = onSaveCurrent,
            leadingIcon = {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(14.dp))
            },
            label = { Text("存为预设", style = MaterialTheme.typography.labelMedium) },
            colors = FilterChipDefaults.filterChipColors(labelColor = colors.onSurfaceVariant)
        )

        Box(
            Modifier
                .width(1.dp)
                .height(18.dp)
                .background(colors.outlineVariant)
        )

        FilterChip(
            selected = highlightCount > 0,
            onClick = onOpenHighlights,
            leadingIcon = {
                Icon(
                    Icons.Rounded.Highlight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
            },
            label = {
                Text(
                    if (highlightCount > 0) "高亮 $highlightCount" else "高亮规则",
                    style = MaterialTheme.typography.labelMedium
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                labelColor = colors.onSurfaceVariant,
                selectedContainerColor = palette.warn.copy(alpha = 0.16f),
                selectedLabelColor = palette.warn
            )
        )

        FilterChip(
            selected = false,
            onClick = onOpenCrashes,
            leadingIcon = {
                Icon(
                    Icons.Rounded.BugReport,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
            },
            label = { Text(crashSummary, style = MaterialTheme.typography.labelMedium) },
            colors = FilterChipDefaults.filterChipColors(
                labelColor = colors.onSurfaceVariant,
                selectedContainerColor = palette.error.copy(alpha = 0.16f),
                selectedLabelColor = palette.error
            )
        )

        if (findTotal > 0) {
            FilterChip(
                selected = false,
                onClick = onFindNext,
                leadingIcon = {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                label = {
                    Text("下一个 · 共 $findTotal", style = MaterialTheme.typography.labelMedium)
                },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = colors.onSurfaceVariant,
                    selectedContainerColor = palette.info.copy(alpha = 0.16f),
                    selectedLabelColor = palette.info
                )
            )
        }

        FilterChip(
            selected = false,
            onClick = onOpenIssues,
            label = {
                Text(issueSummary.ifBlank { "问题体检" }, style = MaterialTheme.typography.labelMedium)
            },
            colors = FilterChipDefaults.filterChipColors(labelColor = colors.onSurfaceVariant)
        )

        FilterChip(
            selected = overlayOn,
            onClick = onOpenOverlay,
            label = {
                Text(if (overlayOn) "悬浮窗已开" else "悬浮窗", style = MaterialTheme.typography.labelMedium)
            },
            colors = FilterChipDefaults.filterChipColors(
                labelColor = colors.onSurfaceVariant,
                selectedContainerColor = colors.primary.copy(alpha = 0.16f),
                selectedLabelColor = colors.primary
            )
        )

        FilterChip(
            selected = false,
            onClick = onOpenFile,
            label = { Text("打开日志文件", style = MaterialTheme.typography.labelMedium) },
            colors = FilterChipDefaults.filterChipColors(labelColor = colors.onSurfaceVariant)
        )

        FilterChip(
            selected = false,
            onClick = onOpenHistory,
            label = { Text("历史文件", style = MaterialTheme.typography.labelMedium) },
            colors = FilterChipDefaults.filterChipColors(labelColor = colors.onSurfaceVariant)
        )

        FilterChip(
            selected = false,
            onClick = onSnapshot,
            label = { Text("存图", style = MaterialTheme.typography.labelMedium) },
            colors = FilterChipDefaults.filterChipColors(labelColor = colors.onSurfaceVariant)
        )

        FilterChip(
            selected = selectMode,
            onClick = onToggleSelect,
            label = {
                Text(
                    if (selectMode) "已选 $selectCount" else "多选",
                    style = MaterialTheme.typography.labelMedium
                )
            },
            colors = FilterChipDefaults.filterChipColors(labelColor = colors.onSurfaceVariant)
        )

        if (bookmarkCount > 0) {
            FilterChip(
                selected = false,
                onClick = onClearMarks,
                label = {
                    Text(
                        "清除收藏 $bookmarkCount",
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                colors = FilterChipDefaults.filterChipColors(labelColor = LocalLogPalette.current.warn)
            )
        }

        FilterChip(
            selected = false,
            onClick = onOpenTags,
            label = { Text("热点标签", style = MaterialTheme.typography.labelMedium) },
            colors = FilterChipDefaults.filterChipColors(labelColor = colors.onSurfaceVariant)
        )

        FilterChip(
            selected = false,
            onClick = onOpenBuffers,
            label = {
                Text(
                    bufferSummary.ifBlank { "缓冲区" },
                    style = MaterialTheme.typography.labelMedium
                )
            },
            colors = FilterChipDefaults.filterChipColors(labelColor = colors.onSurfaceVariant)
        )
    }
}

/** 多色高亮：规则命中用规则自己的颜色，查询命中统一压一层底色盖在上面。 */
fun markTextMulti(
    text: String,
    queryRanges: List<IntRange>,
    spans: List<MarkSpan>,
    queryColor: Color
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString(text)
    if (queryRanges.isEmpty() && spans.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (s in spans) {
            val start = s.range.first.coerceIn(0, text.length)
            val end = (s.range.last + 1).coerceIn(start, text.length)
            if (end > start) {
                addStyle(
                    SpanStyle(
                        background = Color(s.color).copy(alpha = 0.55f),
                        color = Color.Black.copy(alpha = 0.92f),
                        fontWeight = if (s.wholeLine) FontWeight.Medium else FontWeight.Normal
                    ),
                    start,
                    end
                )
            }
        }
        for (r in queryRanges) {
            val start = r.first.coerceIn(0, text.length)
            val end = (r.last + 1).coerceIn(start, text.length)
            if (end > start) {
                addStyle(SpanStyle(background = queryColor.copy(alpha = 0.32f)), start, end)
            }
        }
    }
}

@Composable
fun HighlightRulesDialog(
    rules: List<HighlightRule>,
    onSave: (List<HighlightRule>) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val draft = remember { mutableStateListOf<HighlightRule>().also { it.addAll(rules) } }
    var keyword by remember { mutableStateOf("") }
    var useRegex by remember { mutableStateOf(false) }
    var wholeLine by remember { mutableStateOf(false) }
    var colorIndex by remember { mutableStateOf(0) }

    fun commit(list: List<HighlightRule>) {
        draft.clear()
        draft.addAll(list)
        onSave(list)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceVariant,
        title = { Text("高亮规则", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                Text(
                    "给自己的关键词各配一种颜色。写正则时用 tag 名当锚点最省事，比如 ^OkHttp.*。",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                if (draft.isEmpty()) {
                    Text(
                        "还没有规则。加一条试试：关键词 okhttp，颜色随便挑。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        draft.forEachIndexed { index, rule ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Color(CompiledHighlights.parseColor(rule.colorHex) ?: 0xFFFFB300.toInt())
                                        )
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        rule.keyword,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = colors.onSurface
                                    )
                                    Text(
                                        buildString {
                                            if (rule.regex) append("正则 ")
                                            if (rule.wholeLine) append("整行 ")
                                            if (!rule.enabled) append("已停用")
                                        }.ifBlank { "字面量匹配" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { on ->
                                        commit(draft.toMutableList().also { l ->
                                            l[index] = rule.copy(enabled = on)
                                        })
                                    }
                                )
                                IconButton(
                                    onClick = {
                                        commit(draft.toMutableList().also { it.removeAt(index) })
                                    }
                                ) {
                                    Icon(
                                        Icons.Rounded.DeleteOutline,
                                        contentDescription = "删除",
                                        tint = colors.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = {
                        Text("关键词或正则", style = MaterialTheme.typography.labelSmall)
                    },
                    textStyle = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    )
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HighlightStore.palette.forEachIndexed { i, hex ->
                        val c = Color(CompiledHighlights.parseColor(hex) ?: 0xFFFFB300.toInt())
                        Box(
                            Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(c)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(if (i == colorIndex) colors.onSurface.copy(alpha = 0.25f) else Color.Transparent)
                        )
                        FilterChip(
                            selected = i == colorIndex,
                            onClick = { colorIndex = i },
                            label = {
                                Text("色${i + 1}", style = MaterialTheme.typography.labelSmall)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = colors.onSurfaceVariant,
                                selectedContainerColor = c.copy(alpha = 0.2f),
                                selectedLabelColor = c
                            )
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "按正则解释",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                    Switch(checked = useRegex, onCheckedChange = { useRegex = it })
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "整行染色",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                    Switch(checked = wholeLine, onCheckedChange = { wholeLine = it })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            val kw = keyword.trim()
                            if (kw.isEmpty()) return@TextButton
                            val next = HighlightRule(
                                id = HighlightStore.nextId(draft),
                                name = kw,
                                keyword = kw,
                                colorHex = HighlightStore.palette[colorIndex % HighlightStore.palette.size],
                                regex = useRegex,
                                wholeLine = wholeLine,
                                enabled = true
                            )
                            commit(draft.toList() + next)
                            keyword = ""
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)
                    ) {
                        Text("添加", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)
            ) {
                Text("好了", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}

/** 崩溃统计：按异常签名归集，标出今天/近 7 天次数和「新出现的」 */
@Composable
fun CrashStatsDialog(
    rows: List<CrashAggregate>,
    todayCount: Int,
    weekCount: Int,
    totalCount: Int,
    onClear: () -> Unit,
    onJump: (CrashAggregate) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceVariant,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("崩溃归集", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onClear,
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.warn)
                ) {
                    Text("清空统计", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        text = {
            Column {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        StatCell("今天", todayCount, palette.error)
                        StatCell("近 7 天", weekCount, palette.warn)
                        StatCell("累计", totalCount, colors.onSurface)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (rows.isEmpty()) {
                    Text(
                        "还没有崩溃记录。目标应用崩一次，这里就会按异常类型累计。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        rows.take(50).forEach { agg ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 整行可点定位，不再只能点点右侧放大镜
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onJump(agg) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            agg.type.take(38),
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = colors.onSurface
                                        )
                                        if (isFreshCrash(agg)) {
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "新",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = palette.error
                                            )
                                        }
                                    }
                                    Text(
                                        "首次 ${timeLabel(agg.firstSeen)} · 最近 ${timeLabel(agg.lastSeen)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "×${agg.count}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = palette.error
                                )
                                IconButton(onClick = { onJump(agg) }) {
                                    Icon(
                                        Icons.Rounded.Search,
                                        contentDescription = "定位",
                                        tint = colors.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)
            ) {
                Text("关闭", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}

@Composable
private fun StatCell(label: String, value: Int, tint: Color) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            color = tint
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

private fun isFreshCrash(agg: CrashAggregate): Boolean =
    System.currentTimeMillis() - agg.firstSeen <= 10 * 60_000L

private fun timeLabel(ms: Long): String {
    if (ms <= 0L) return "-"
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = ms
    return String.format(
        "%02d:%02d",
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE)
    )
}


/** 问题体检面板：把 ANR / OOM / 权限 / 网络 / 卡顿这些非崩溃类故障也归集出来 */
@Composable
fun IssueDialog(
    hits: List<IssueHit>,
    onJump: (IssueHit) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceVariant,
        title = { Text("问题体检", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                Text(
                    if (hits.isEmpty()) "当前缓冲里没命中典型故障模式。" else "按故障类型归集，点一条直接跳到现场。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                hits.forEach { hit ->
                    val tint = when (hit.rule.severity) {
                        IssueRule.Severity.ERROR -> palette.error
                        IssueRule.Severity.WARN -> palette.warn
                        else -> palette.info
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(tint.copy(alpha = 0.10f))
                            // 整行可点：以前只有右侧角标能点，用户点标题无反应
                            .clickable { onJump(hit) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(tint)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                hit.rule.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurface
                            )
                            Text(
                                hit.rule.hint,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = true,
                            onClick = { onJump(hit) },
                            label = {
                                Text("${hit.count} 条", style = MaterialTheme.typography.labelMedium)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = tint,
                                selectedContainerColor = tint.copy(alpha = 0.18f),
                                selectedLabelColor = tint
                            )
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}


/** 顶部提示条：当前看的是导入的离线文件而不是实时流 */
@Composable
fun OfflineSourceBar(
    name: String,
    count: Int,
    onClose: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.warn.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(palette.warn)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("离线日志", style = MaterialTheme.typography.labelMedium, color = palette.warn)
            Text(
                "$name · ${count} 行",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onClose) {
            Text("回到实时", style = MaterialTheme.typography.labelMedium)
        }
    }
}


/** 历史文件面板：录制落盘与导出过的都在这里，能看能发能删 */
@Composable
fun LogFilesDialog(
    items: List<LogFiles.Item>,
    onOpen: (LogFiles.Item) -> Unit,
    onShare: (LogFiles.Item) -> Unit,
    onDelete: (LogFiles.Item) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceVariant,
        title = { Text("历史日志文件", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                Text(
                    if (items.isEmpty()) "还没有落盘文件，开着录制跑一会儿就会出现在这里。"
                    else "共 ${items.size} 个文件，点名字打开，删除即移出磁盘。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(items) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onOpen(item) }
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (item.fromCapture) palette.info else palette.info)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${item.sizeText} · ${item.timeText}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onShare(item) }) {
                                Text("分享", style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(onClick = { onDelete(item) }) {
                                Text("删除", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}

/** 缓冲区选择：main/system/crash/events/radio/kernel，改完重启抓取生效 */
@Composable
fun BufferDialog(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onClearDevice: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    // `logcat -b all -c` 清的是整机所有 App 的缓冲、不可恢复：必须两步确认，不能一个按钮直接执行
    var confirmClear by remember { mutableStateOf(false) }
    val all = listOf(
        "main" to "应用日志",
        "system" to "系统日志",
        "crash" to "崩溃缓冲",
        "events" to "事件流",
        "radio" to "基带/网络",
        "kernel" to "内核 dmesg"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceVariant,
        title = { Text("日志缓冲区", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                Text(
                    "勾选要抓的缓冲区，改完会重启一次抓取。内核日志需要设备放开权限，抓不到属正常。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                if (!confirmClear) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text(
                            "清空设备日志缓冲（logcat -b all -c）",
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalLogPalette.current.warn
                        )
                    }
                } else {
                    Text(
                        "这会清掉整机所有 App 的日志缓冲（包括没在抓的），清完无法恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalLogPalette.current.error
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { confirmClear = false }) {
                            Text("取消", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(
                            onClick = {
                                confirmClear = false
                                onClearDevice()
                            }
                        ) {
                            Text(
                                "确认清空",
                                style = MaterialTheme.typography.labelMedium,
                                color = LocalLogPalette.current.error
                            )
                        }
                    }
                }
                TextButton(
                    onClick = { all.map { it.first }.filter { !selected.contains(it) }.forEach(onToggle) }
                ) {
                    Text("全选（含内核 / 事件流）", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(4.dp))
                all.forEach { (key, label) ->
                    val on = selected.contains(key)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onToggle(key) }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                            Text(key, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        Switch(checked = on, onCheckedChange = { onToggle(key) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}


/** 热点标签：谁在刷屏一眼看清，点一下直接锁定那个 tag */
@Composable
fun TagStatsDialog(
    tags: List<Pair<String, Int>>,
    total: Int,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceVariant,
        title = { Text("热点标签", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                Text(
                    if (tags.isEmpty()) "当前视图没有可统计的条目。"
                    else "共 $total 条，按出现次数排序。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(tags) { row ->
                        val name = row.first
                        val count = row.second
                        val ratio = if (total <= 0) 0f else count.toFloat() / total
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(name) }
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    name.ifBlank { "(无标签)" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(colors.onSurfaceVariant.copy(alpha = 0.15f))
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(ratio.coerceIn(0.02f, 1f))
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(palette.debug)
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                count.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.info
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}


/** 单行动作表：复制、分享该行、收藏、按 tag / pid 锁定，短按复制长按出这个 */
@Composable
fun EntryActionsDialog(
    title: String,
    bookmarked: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onBookmark: () -> Unit,
    onFilterTag: () -> Unit,
    onFilterPid: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current
    val actions = listOf(
        "复制这一行" to (palette.debug to onCopy),
        "分享这一行" to (palette.info to onShare),
        (if (bookmarked) "取消收藏" else "收藏这一行") to (palette.warn to onBookmark),
        "只看这个 TAG" to (colors.primary to onFilterTag),
        "只看这个 PID" to (colors.primary to onFilterPid)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceVariant,
        title = { Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2) },
        text = {
            Column {
                actions.forEach { (label, pair) ->
                    val tint = pair.first
                    val act = pair.second
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                act()
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(tint)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}

/** 多选时替换底部条：复制 / 分享 / 全选 / 退出 */
@Composable
fun SelectionBar(
    count: Int,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSelectAll: () -> Unit,
    onExit: () -> Unit
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
            SelectAction(Icons.Rounded.ContentCopy, "复制 $count", palette.debug, onCopy)
            SelectAction(Icons.Rounded.IosShare, "分享 $count", palette.info, onShare)
            SelectAction(Icons.Rounded.SelectAll, "全选", colors.secondary, onSelectAll)
            SelectAction(Icons.Rounded.Close, "退出", palette.warn, onExit)
        }
    }
}

@Composable
private fun SelectAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

/** 搜索历史：点一下重新用，长按删掉这条 */
@Composable
fun HistoryChips(
    history: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    if (history.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        history.forEach { q ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.onSurfaceVariant.copy(alpha = 0.08f))
                    .pointerInput(q) {
                        detectTapGestures(
                            onTap = { onPick(q) },
                            onLongPress = { onRemove(q) }
                        )
                    }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    q,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
