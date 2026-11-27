package dev.loglens.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.loglens.data.AppRepository
import dev.loglens.data.NetSnapshot
import dev.loglens.shizuku.ShellRunner
import dev.loglens.ui.mx.Card
import dev.loglens.ui.mx.CardDefaults
import dev.loglens.ui.mx.CircularProgressIndicator
import dev.loglens.ui.mx.ExperimentalMaterial3Api
import dev.loglens.ui.mx.FilterChip
import dev.loglens.ui.mx.Icon
import dev.loglens.ui.mx.IconButton
import dev.loglens.ui.mx.Scaffold
import dev.loglens.ui.mx.Text
import dev.loglens.ui.mx.TopAppBar
import dev.loglens.ui.mx.TopAppBarDefaults
import dev.loglens.ui.theme.LocalLogPalette
import dev.loglens.ui.theme.LogPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条连接在时间轴上的记录：首次出现、最近一次出现、被采样到几次、这一刻是否还活着 */
private data class Track(
    val conn: NetSnapshot.Conn,
    val firstSeen: Long,
    val lastSeen: Long,
    val samples: Int,
    val live: Boolean
)

/** 采样间隔：短连接一般 1 秒内就结束，间隔再大就会漏 */
private const val SAMPLE_GAP_MS = 1_400L

/** 已消失的连接保留 90 秒，用来回答「刚才那个请求连过谁」 */
private const val KEEP_DEAD_MS = 90_000L

/** SYN_SENT 持续这么久基本就是握手失败：DNS 拿到了但连不过去，或者被拦、网络刚切走 */
private const val STUCK_HANDSHAKE_MS = 3_000L

private fun trackKey(c: NetSnapshot.Conn): String = buildString {
    append(c.uid).append('|').append(c.proto).append('|')
    append(c.inode).append('|').append(c.local).append('|').append(c.remote)
}

private fun mergeTracks(
    prev: Map<String, Track>,
    conns: List<NetSnapshot.Conn>,
    now: Long,
    keepDeadMs: Long
): Map<String, Track> {
    val seen = HashSet<String>(conns.size * 2)
    val out = LinkedHashMap<String, Track>(prev.size + conns.size)
    for (c in conns) {
        val key = trackKey(c)
        seen.add(key)
        val old = prev[key]
        out[key] = if (old == null) {
            Track(c, now, now, 1, true)
        } else {
            Track(c, old.firstSeen, now, old.samples + 1, true)
        }
    }
    if (keepDeadMs > 0) {
        for ((key, t) in prev) {
            if (seen.contains(key)) continue
            if (now - t.lastSeen > keepDeadMs) continue
            out[key] = t.copy(live = false)
        }
        if (out.size > 600) {
            val keep = out.entries.sortedByDescending { it.value.lastSeen }.take(600)
            val trimmed = LinkedHashMap<String, Track>(keep.size)
            for (e in keep) trimmed[e.key] = e.value
            return trimmed
        }
    }
    return out
}

private suspend fun readSnapshot(): ShellRunner.Result =
    withContext(Dispatchers.IO) { ShellRunner.exec(NetSnapshot.COMMAND, 20_000) }

/** uid -> 包名、uid -> 标签；包名表只拉一次，标签按需补齐 */
private suspend fun resolveUids(
    pm: PackageManager,
    conns: List<NetSnapshot.Conn>,
    knownPkgs: Map<Int, List<String>>,
    knownLabels: Map<Int, String>
): Pair<Map<Int, List<String>>, Map<Int, String>> {
    val pkgs = if (knownPkgs.isEmpty()) {
        withContext(Dispatchers.IO) {
            ShellRunner.listPackageUids()
                .entries
                .groupBy { it.value }
                .mapValues { e -> e.value.map { it.key }.sorted() }
        }
    } else {
        knownPkgs
    }
    val need = conns.map { it.uid }.distinct().filter { it > 0 && knownLabels[it] == null }
    if (need.isEmpty()) return pkgs to knownLabels

    val extra = withContext(Dispatchers.IO) {
        need.associateWith { uid ->
            val pkg = pkgs[uid].orEmpty().firstOrNull()
                ?: runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
            val label = pkg?.let {
                runCatching { pm.getApplicationInfo(it, 0).loadLabel(pm).toString().trim() }.getOrNull()
            }?.takeIf { it.isNotEmpty() }
            label ?: pkg.orEmpty()
        }
    }
    return pkgs to (knownLabels + extra)
}
/**
 * 网络快照：把 /proc/net 的 TCP/UDP 连接表按应用摊开。
 *
 * 两种视角：默认「目标应用」（按 uid 过滤），可切「全局」看整机。
 * 开「时间轴录制」后每 1.4 秒采一次并做 diff，短连接也能留下痕迹，
 * 并给每条连接算出存活时长、什么时候消失、SYN_SENT 卡了多久。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetScreen(
    selected: AppRepository.AppItem?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    // 语言取自 Compose 的配置快照（LocalConfiguration）而不是 Locale.getDefault()：
    // 后者在 Composable 里读是「不可观测」的，切换语言不会触发重组（lint NonObservableLocale）。
    val locale = LocalConfiguration.current.locales[0]
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    var global by remember { mutableStateOf(selected == null) }
    var recording by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf<Map<String, Track>>(emptyMap()) }
    var lastSampleAt by remember { mutableStateOf(0L) }
    var samples by remember { mutableStateOf(0) }
    var uidPkgs by remember { mutableStateOf<Map<Int, List<String>>>(emptyMap()) }
    var uidLabels by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var tick by remember { mutableStateOf(0) }

    val targetUid = selected?.uid ?: -1
    val listState = rememberLazyListState()
    val pm = context.packageManager

    // 切视角回到顶部，否则锚点消失时 Compose 会自动挪位置
    LaunchedEffect(global) { listState.scrollToItem(0) }

    // 单次采样：只在没开录制时用（进页面、点刷新）
    LaunchedEffect(tick, recording) {
        if (recording) return@LaunchedEffect
        loading = true
        errorMsg = null
        val result = try {
            readSnapshot()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
        if (!isActive) return@LaunchedEffect
        if (result == null) {
            errorMsg = "读取失败"
        } else if (result.stdout.isBlank()) {
            errorMsg = "读不到 /proc/net：" + result.stderr.trim().ifBlank { "空输出，确认 Shizuku 仍在运行" }
            tracks = emptyMap()
        } else {
            val parsed = NetSnapshot.parse(result.stdout)
            val now = System.currentTimeMillis()
            // 单次采样也要在已有轨迹上合并：录制停下来时会走到这里，如果从 emptyMap() 重算，
            // 那些「刚消失」的连接（90 秒留痕）会被当场清空，等于停录制就丢历史
            tracks = mergeTracks(tracks, parsed, now, KEEP_DEAD_MS)
            lastSampleAt = now
            samples = samples + 1
            val pair = resolveUids(pm, parsed, uidPkgs, uidLabels)
            uidPkgs = pair.first
            uidLabels = pair.second
        }
        loading = false
    }

    // 录制：循环采样 + 合并历史。绑在 RESUMED 上：按 Home 后别再每 1.4 秒跑一次 shell
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(recording, lifecycleOwner) {
        if (!recording) return@LaunchedEffect
        loading = true
        errorMsg = null
        var firstRound = true
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                val result = try {
                    readSnapshot()
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    null
                }
                if (!isActive) return@repeatOnLifecycle
                if (result == null) {
                    errorMsg = "读取失败"
                } else if (result.stdout.isBlank()) {
                    errorMsg = "读不到 /proc/net：" + result.stderr.trim().ifBlank { "空输出，确认 Shizuku 仍在运行" }
                } else {
                    val parsed = NetSnapshot.parse(result.stdout)
                    val now = System.currentTimeMillis()
                    tracks = mergeTracks(tracks, parsed, now, KEEP_DEAD_MS)
                    lastSampleAt = now
                    samples = if (firstRound) 1 else samples + 1
                    val pair = resolveUids(pm, parsed, uidPkgs, uidLabels)
                    uidPkgs = pair.first
                    uidLabels = pair.second
                    errorMsg = null
                }
                if (firstRound) {
                    loading = false
                    firstRound = false
                }
                delay(SAMPLE_GAP_MS)
            }
        }
    }

    val visible = tracks.values.filter { global || it.conn.uid == targetUid }
    val sorted = visible.sortedWith(object : Comparator<Track> {
        override fun compare(a: Track, b: Track): Int {
            if (a.live != b.live) return if (a.live) -1 else 1
            if (a.live) {
                val ra = stateRank(a.conn.state)
                val rb = stateRank(b.conn.state)
                if (ra != rb) return ra - rb
                if (a.conn.uid != b.conn.uid) return a.conn.uid - b.conn.uid
                return a.conn.remote.compareTo(b.conn.remote)
            }
            return when {
                a.lastSeen != b.lastSeen -> if (a.lastSeen > b.lastSeen) -1 else 1
                a.conn.uid != b.conn.uid -> a.conn.uid - b.conn.uid
                else -> a.conn.remote.compareTo(b.conn.remote)
            }
        }
    })
    val groups: Map<Int, List<Track>> = if (global) sorted.groupBy { it.conn.uid } else emptyMap()

    val liveRows = visible.count { it.live }
    val established = visible.count { it.live && it.conn.isAlive }
    val listening = visible.count { it.live && it.conn.isListen }
    val deadRows = visible.size - liveRows
    val stuck = visible.count {
        it.live && it.conn.state == "SYN_SENT" && lastSampleAt - it.firstSeen >= STUCK_HANDSHAKE_MS
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("网络快照", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                        Text(
                            text = buildString {
                                append(if (global) "整机连接表 · 按应用归堆" else "只看 " + (selected?.label ?: "目标应用"))
                                if (recording) append(" · 录制中")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1
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
                    when {
                        recording -> Box(modifier = Modifier.padding(end = 12.dp)) {
                            StatusChip("REC", palette.error)
                        }

                        loading -> CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(20.dp),
                            color = colors.primary
                        )

                        else -> IconButton(onClick = { tick += 1 }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "重新采样", tint = colors.onSurface)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = !global,
                    onClick = { global = false },
                    label = { Text("目标应用") },
                    enabled = selected != null
                )
                FilterChip(
                    selected = global,
                    onClick = { global = true },
                    label = { Text("全局") }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (lastSampleAt > 0) {
                        "采样 " + SimpleDateFormat("HH:mm:ss", locale).format(Date(lastSampleAt))
                    } else {
                        "未采样"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp)
                ) {
                    StatCell(
                        if (recording) "存活" else "连接",
                        liveRows.toString(),
                        palette.info,
                        Modifier.weight(1f)
                    )
                    StatCell("已建立", established.toString(), palette.debug, Modifier.weight(1f))
                    if (stuck > 0) {
                        StatCell("卡住", stuck.toString(), palette.error, Modifier.weight(1f))
                    } else {
                        StatCell("监听", listening.toString(), palette.warn, Modifier.weight(1f))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    SettingRow(
                        title = "时间轴录制",
                        subtitle = if (recording) {
                            "每 1.4 秒采一次，已消失的连接留 90 秒；再点一次停止"
                        } else {
                            "每 1.4 秒采一次，一闪而过的短连接也能留下来"
                        },
                        checked = recording,
                        onCheckedChange = { recording = it }
                    )
                }
            }
            if (recording || deadRows > 0 || stuck > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        if (samples > 0 && recording) {
                            append("样本 ")
                            append(samples)
                        }
                        if (deadRows > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("已消失 ")
                            append(deadRows)
                            append(" 条")
                        }
                        if (stuck > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(stuck)
                            append(" 条 SYN_SENT 卡住超过 3 秒")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (stuck > 0) palette.error else colors.onSurfaceVariant,
                    maxLines = 2,
                    lineHeight = 16.sp
                )
            }

            errorMsg?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = palette.error.copy(alpha = 0.12f))
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.error,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            if (sorted.isEmpty() && !loading) {
                EmptyHint(
                    text = if (global) {
                        "整机此刻没有 TCP/UDP 连接，或者 Shizuku 没在跑。\n点右上角刷新，或打开上面的时间轴录制再观察一会儿。"
                    } else {
                        "这个应用当前没有连接——可能空闲、被系统冻结，或者它走了别的通道。\n打开时间轴录制后停在这页，短连接也能被抓到。"
                    }
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                    if (global) {
                        groups.forEach { entry ->
                            val uid = entry.key
                            val list = entry.value
                            item(key = "h" + uid) {
                                GroupHeader(
                                    uid = uid,
                                    pkgs = uidPkgs[uid].orEmpty(),
                                    label = uidLabels[uid].orEmpty(),
                                    count = list.count { it.live },
                                    dead = list.count { !it.live }
                                )
                            }
                            list.forEach { t ->
                                item(key = "c" + trackKey(t.conn)) {
                                    ConnRow(t, lastSampleAt, palette)
                                }
                            }
                        }
                    } else {
                        item(key = "h-target") {
                            GroupHeader(
                                uid = targetUid,
                                pkgs = listOfNotNull(selected?.packageName),
                                label = selected?.label.orEmpty(),
                                count = liveRows,
                                dead = deadRows
                            )
                        }
                        sorted.forEach { t ->
                            item(key = "c" + trackKey(t.conn)) {
                                ConnRow(t, lastSampleAt, palette)
                            }
                        }
                    }
                    item {
                        Text(
                            text = "数据来自内核的 /proc/net/{tcp,tcp6,udp,udp6}，端口和 uid 都是原文。" +
                                "它能证明「谁在连哪、通没通、活了多久」，看不到请求正文——TLS/QUIC 加密之后，" +
                                "正文不在任何日志里。SYN_SENT 超过 3 秒按握手失败处理。",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 14.dp, bottom = 24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 18.sp
        )
    }
}
@Composable
private fun GroupHeader(
    uid: Int,
    pkgs: List<String>,
    label: String,
    count: Int,
    dead: Int
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    // 标签可能来自 shell 侧的 pm 表，也可能是空的；再用本机 PackageManager 兜一次底
    val resolved = remember(uid, pkgs, label) {
        val pm = context.packageManager
        val clean = pkgs.filter { it.isNotBlank() }
        val pkg = clean.firstOrNull() ?: runCatching {
            pm.getPackagesForUid(uid)?.firstOrNull()
        }.getOrNull()
        val title = label.trim().takeIf { it.isNotEmpty() }
            ?: pkg?.let {
                runCatching { pm.getApplicationInfo(it, 0).loadLabel(pm).toString().trim() }.getOrNull()
            }?.takeIf { it.isNotEmpty() }
            ?: pkg
            ?: "未关联应用"
        clean to title
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(packageName = resolved.first.firstOrNull().orEmpty(), size = 34, corner = 10)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            val meta = buildString {
                append("  uid ")
                append(uid)
                append(" · ")
                append(count)
                append(" 条")
                if (dead > 0) {
                    append(" · 已消失 ")
                    append(dead)
                }
                if (resolved.first.size > 1) {
                    append(" · 共用 ")
                    append(resolved.first.size)
                    append(" 个应用")
                }
            }
            Text(
                text = buildAnnotatedString {
                    pushStyle(SpanStyle(color = colors.onSurface, fontWeight = FontWeight.Medium))
                    append(resolved.second)
                    pop()
                    pushStyle(SpanStyle(color = colors.onSurfaceVariant))
                    append(meta)
                    pop()
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                lineHeight = 19.sp
            )
            if (resolved.first.size > 1) {
                Text(
                    text = "共用同一 uid：" + resolved.first.take(3).joinToString("、") +
                        if (resolved.first.size > 3) " 等 " + resolved.first.size + " 个" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun ConnRow(t: Track, now: Long, palette: LogPalette) {
    val colors = MaterialTheme.colorScheme
    val c = t.conn
    val stuck = t.live && c.state == "SYN_SENT" && now - t.firstSeen >= STUCK_HANDSHAKE_MS
    val tint = when {
        !t.live -> colors.onSurfaceVariant
        stuck -> palette.error
        else -> stateColor(c.state, palette)
    }
    val peerless = c.remote.endsWith(":0")
    val dim = if (t.live) 1f else 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = dim))
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (c.isListen || peerless) c.local else c.remote,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurface.copy(alpha = dim),
                maxLines = 1
            )
            Text(
                text = when {
                    c.isListen -> c.proto + " · 监听端口 " + portOf(c.local)
                    peerless -> c.proto + " · 本地端口 " + portOf(c.local) + " · 无对端"
                    else -> c.proto + " · 本地端口 " + portOf(c.local)
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant.copy(alpha = dim),
                maxLines = 1
            )
            if (stuck || !t.live || t.samples > 1) {
                Text(
                    text = buildString {
                        when {
                            stuck -> append("握手已卡 ").append(fmtSpan(now - t.firstSeen))
                            !t.live -> append("存在 ").append(fmtSpan(t.lastSeen - t.firstSeen))
                                .append("，").append(fmtSpan(now - t.lastSeen)).append("前消失")
                            else -> append("存活 ").append(fmtSpan(now - t.firstSeen))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (stuck) palette.error.copy(alpha = 0.9f) else colors.onSurfaceVariant.copy(alpha = dim),
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            !t.live -> StatusChip("已断开", colors.onSurfaceVariant)
            stuck -> StatusChip("握手卡住", palette.error)
            else -> StatusChip(c.state, stateColor(c.state, palette))
        }
    }
}

private fun portOf(addr: String): String = addr.substringAfterLast(':')

private fun fmtSpan(ms: Long): String {
    if (ms < 0) return "0 秒"
    val totalSec = ms / 1000
    return if (totalSec < 60) {
        String.format(Locale.US, "%.1f 秒", ms / 1000.0)
    } else {
        val m = totalSec / 60
        val s = totalSec % 60
        m.toString() + " 分 " + s.toString().padStart(2, '0') + " 秒"
    }
}

private fun stateRank(state: String): Int = when (state) {
    "ESTABLISHED" -> 0
    "SYN_SENT", "SYN_RECV" -> 1
    "LISTEN" -> 2
    else -> 3
}

private fun stateColor(state: String, palette: LogPalette): Color = when (state) {
    "ESTABLISHED" -> palette.debug
    "SYN_SENT", "SYN_RECV" -> palette.warn
    "LISTEN", "UDP" -> palette.info
    "TIME_WAIT", "CLOSE_WAIT", "FIN_WAIT1", "FIN_WAIT2", "LAST_ACK", "CLOSING" ->
        palette.warn.copy(alpha = 0.75f)

    else -> palette.raw
}

