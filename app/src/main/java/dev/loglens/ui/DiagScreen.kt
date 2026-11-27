package dev.loglens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Timeline
import dev.loglens.ui.mx.Card
import dev.loglens.ui.mx.CardDefaults
import dev.loglens.ui.mx.CircularProgressIndicator
import dev.loglens.ui.mx.ExperimentalMaterial3Api
import dev.loglens.ui.mx.HorizontalDivider
import dev.loglens.ui.mx.Icon
import dev.loglens.ui.mx.IconButton
import androidx.compose.material3.MaterialTheme
import dev.loglens.ui.mx.Scaffold
import dev.loglens.ui.mx.Surface
import dev.loglens.ui.mx.Text
import dev.loglens.ui.mx.TextButton
import dev.loglens.ui.mx.TopAppBar
import dev.loglens.ui.mx.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.loglens.data.Prefs
import dev.loglens.diag.AnrRepo
import dev.loglens.diag.DiagRepo
import dev.loglens.ui.theme.LocalLogPalette
import dev.loglens.util.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class DiagBundle(
    val info: DiagRepo.Pkg?,
    val procs: List<DiagRepo.Proc>,
    val exits: List<DiagRepo.Exit>,
    val exitRaw: String,
    val anr: AnrRepo.Outcome?,
    val marks: List<DiagRepo.Mark>,
    val crash: String,
    val archive: List<DiagRepo.Crash>,
    val archiveRaw: String,
    val binder: List<DiagRepo.Binder>,
    val binderRaw: String,
    val dirs: List<DiagRepo.DirListing>,
    val errors: List<String>
)

private fun collect(context: android.content.Context, pkg: String): DiagBundle {
    val errors = ArrayList<String>()

    val info = runCatching { DiagRepo.pkgInfo(context, pkg) }
        .onFailure { errors.add("应用档案：${it.message ?: it.javaClass.simpleName}") }
        .getOrNull()
    val uid = info?.uid ?: 0

    val procs = runCatching { DiagRepo.processes(pkg, uid) }
        .onFailure { errors.add("进程表：${it.message ?: it.javaClass.simpleName}") }
        .getOrDefault(emptyList())

    val exitPair = runCatching { DiagRepo.exitInfo(pkg) }
        .onFailure { errors.add("exit-info：${it.message ?: it.javaClass.simpleName}") }
        .getOrElse { Pair(emptyList<DiagRepo.Exit>(), "") }

    val anr = runCatching { AnrRepo.collect(context, pkg, exitPair.first) }
        .onFailure { errors.add("ANR 归因：${it.message ?: it.javaClass.simpleName}") }
        .getOrNull()

    val marks = runCatching { DiagRepo.timeline(pkg) }
        .onFailure { errors.add("events：${it.message ?: it.javaClass.simpleName}") }
        .getOrDefault(emptyList())

    val crash = runCatching { DiagRepo.crashTail(pkg) }
        .onFailure { errors.add("crash：${it.message ?: it.javaClass.simpleName}") }
        .getOrDefault("")

    val archivePair = runCatching { DiagRepo.crashArchive(pkg) }
        .onFailure { errors.add("dropbox：${it.message ?: it.javaClass.simpleName}") }
        .getOrElse { Pair(emptyList<DiagRepo.Crash>(), "") }

    val binderPair = runCatching { DiagRepo.binderStats(procs.firstOrNull()?.pid) }
        .onFailure { errors.add("binder：${it.message ?: it.javaClass.simpleName}") }
        .getOrElse { Pair(emptyList<DiagRepo.Binder>(), "") }

    val dirs = runCatching { DiagRepo.appFiles(pkg) }
        .onFailure { errors.add("私有目录：${it.message ?: it.javaClass.simpleName}") }
        .getOrDefault(emptyList())

    return DiagBundle(
        info = info,
        procs = procs,
        exits = exitPair.first,
        exitRaw = exitPair.second,
        anr = anr,
        marks = marks,
        crash = crash,
        archive = archivePair.first,
        archiveRaw = archivePair.second,
        binder = binderPair.first,
        binderRaw = binderPair.second,
        dirs = dirs,
        errors = errors
    )
}

private fun report(bundle: DiagBundle?, pkg: String, label: String): String {
    if (bundle == null) return "目标：$label ($pkg)\n尚未采集到数据"
    val sb = StringBuilder()
    sb.appendLine("===== LogLens 深度诊断 =====")
    sb.appendLine("目标：$label ($pkg)")
    sb.appendLine("采集时间：${DiagRepo.fmtFull(System.currentTimeMillis())}")
    bundle.info?.let {
        sb.appendLine()
        sb.appendLine("[应用档案]")
        sb.appendLine("版本：${it.versionName} (${it.versionCode})")
        sb.appendLine("安装/更新时间：${DiagRepo.fmtFull(it.lastUpdateMs)}")
        sb.appendLine("codePath：${it.codePath}")
        sb.appendLine("dataDir：${it.dataDir}")
        sb.appendLine("debuggable：${it.debuggable}  uid：${it.uid}  targetSdk：${it.targetSdk}")
    }
    sb.appendLine()
    sb.appendLine("[进程]")
    if (bundle.procs.isEmpty()) sb.appendLine("无进程在跑") else bundle.procs.forEach {
        sb.appendLine("pid=${it.pid} ${it.name} 启动=${DiagRepo.fmtFull(it.startMs)}")
    }
    sb.appendLine()
    sb.appendLine("[ApplicationExitInfo]")
    if (bundle.exits.isEmpty()) {
        sb.appendLine(bundle.exitRaw.take(1500).ifBlank { "无记录" })
    } else bundle.exits.forEach {
        sb.appendLine("${DiagRepo.fmtFull(it.at)} ${it.process} reason=${it.reason} status=${it.status} ${it.exitCodeText}${if (it.is137) "  ← 137" else ""}")
    }
    sb.appendLine()
    sb.appendLine("[ANR 归因 AnrInfo]")
    val anr = bundle.anr
    if (anr == null || anr.items.isEmpty()) {
        sb.appendLine(anr?.whyEmpty?.trim()?.ifBlank { "无 ANR 记录" } ?: "未采集")
    } else {
        sb.appendLine("结构化 ${anr.structuredCount} 条 / dumpsys ${anr.dumpCount} 条 / 文本兜底 ${anr.textCount} 条")
        anr.byType.forEach { (t, c) -> sb.appendLine("  $t × $c") }
        anr.items.forEach { a ->
            sb.appendLine(
                "${if (a.at <= 0L) "时间未给出" else DiagRepo.fmtFull(a.at)} ${a.process}  type=${a.typeName}(${a.anrType}) " +
                    "anrId=${a.anrId} 超时阈值=${a.timeoutText} 用户可感知=${a.userPerceptible} [${a.source}]" +
                    (if (a.description.isBlank()) "" else " · ${a.description}")
            )
        }
        if (!anr.hasStructure) sb.appendLine("注：以上不是系统给的 AnrInfo 字段，dumpsys 那行按 reason 文本推断，可能为空。")
    }
    sb.appendLine()
    sb.appendLine("[事件时间轴]")
    if (bundle.marks.isEmpty()) sb.appendLine("无相关事件") else bundle.marks.forEach {
        sb.appendLine("${DiagRepo.fmt(it.at)} [${it.tag}] ${it.text}")
    }
    sb.appendLine()
    sb.appendLine("[crash 现场]")
    sb.appendLine(bundle.crash)
    sb.appendLine()
    sb.appendLine("[dropbox 崩溃存档]")
    if (bundle.archive.isEmpty()) {
        sb.appendLine("无与目标包相关的记录")
    } else {
        bundle.archive.reversed().forEach {
            sb.appendLine("${DiagRepo.fmtFull(it.at)} [${it.kind} / ${it.tag}] ${it.summary}")
            sb.appendLine(it.body.take(4000))
            sb.appendLine()
        }
    }
    sb.appendLine()
    sb.appendLine("[binder 聚类]")
    if (bundle.binder.isEmpty()) sb.appendLine("无 binder 相关日志") else bundle.binder.forEach {
        sb.appendLine("${it.count}× ${it.tag}    ${it.sample}")
    }
    sb.appendLine()
    sb.appendLine("[dumpsys binder_calls_stats]")
    sb.appendLine(bundle.binderRaw)
    sb.appendLine()
    sb.appendLine("[应用私有目录]")
    bundle.dirs.forEach { d ->
        sb.appendLine("-- ${d.title}")
        if (d.rows.isEmpty()) sb.appendLine(d.raw.ifBlank { "（空）" })
        else d.rows.forEach { r -> sb.appendLine("${if (r.isDir) "d" else "-"} ${r.name}  ${if (r.size >= 0) r.size.toString() else "?"}  ${r.time}") }
    }
    if (bundle.errors.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("[采集告警]")
        bundle.errors.forEach { sb.appendLine(it) }
    }
    return sb.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagScreen(
    prefs: Prefs,
    pkg: String,
    label: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    var bundle by remember { mutableStateOf<DiagBundle?>(null) }
    var busy by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var globalMode by remember { mutableStateOf(prefs.globalMode) }
    var preview by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    // 预览请求序号：异步读完时如果用户已经关掉或换了文件，这次结果直接作废
    var previewSeq by remember { mutableStateOf(0) }
    var traceBusy by remember { mutableStateOf(false) }
    var trace by remember { mutableStateOf<DiagRepo.AnrTrace?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pkg, reload) {
        if (pkg.isBlank()) {
            busy = false
            return@LaunchedEffect
        }
        busy = true
        var b = withContext(Dispatchers.IO) { collect(context, pkg) }
        // 首次进入时 PackageManager / shell 通道常常还没预热，档案与 exit-info 会整块读空；
        // 这里自动重采最多两轮，避免用户必须手动点「刷新」才看得到数据。
        var tries = 0
        while ((b.info == null || b.exitRaw.isBlank()) && tries < 2) {
            tries++
            delay(900L * tries)
            b = withContext(Dispatchers.IO) { collect(context, pkg) }
        }
        bundle = b
        busy = false
    }

    preview?.let { (title, body, _) ->
        Dialog(onDismissRequest = { preview = null }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = colors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = body,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            Exporter.copy(context, body)
                            preview = null
                        }) { Text("复制") }
                        TextButton(onClick = { Exporter.shareText(context, body) }) { Text("分享") }
                        TextButton(onClick = { preview = null }) { Text("关闭") }
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("深度诊断", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                        Text(
                            text = "$label · $pkg",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = colors.onSurface)
                    }
                },
                actions = {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = palette.info
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = { reload++ }, enabled = !busy) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = colors.onSurface)
                    }
                    IconButton(onClick = {
                        Exporter.shareText(context, report(bundle, pkg, label))
                    }) {
                        Icon(Icons.Rounded.Info, contentDescription = "导出全部", tint = colors.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (pkg.isBlank()) {
                item {
                    DiagCard("没有目标应用", "先在首页选一个应用，再回来看这一页。", palette.warn)
                }
                return@LazyColumn
            }

            bundle?.errors?.takeIf { it.isNotEmpty() }?.let { errs ->
                item {
                    DiagCard("采集告警 ${errs.size} 条", errs.joinToString("\n"), palette.warn)
                }
            }

            // ── 1. 应用档案 ─────────────────────────────
            item {
                val info = bundle?.info
                val body = if (info == null) {
                    "读不到 PackageInfo（应用可能已卸载，或包名不对）"
                } else buildString {
                    appendLine("版本：${info.versionName} (${info.versionCode})")
                    appendLine("安装/更新：${DiagRepo.fmtFull(info.lastUpdateMs)}  ${DiagRepo.ago(info.lastUpdateMs)}")
                    appendLine("codePath：${info.codePath}")
                    appendLine("dataDir：${info.dataDir}")
                    appendLine("debuggable：${info.debuggable}   uid：${info.uid}   targetSdk：${info.targetSdk}")
                    append("label：${info.label}")
                }
                DiagCard("1. 目标应用档案", body, palette.info) {
                    bundle?.let { b ->
                        TextButton(onClick = {
                            preview = Triple("应用档案", report(b, pkg, label), "")
                        }) { Text("完整报告") }
                    }
                }
            }

            // ── 2. 进程 / 新版代码是否生效 ───────────────
            item {
                val info = bundle?.info
                val procs = bundle?.procs ?: emptyList()
                val body = buildString {
                    if (procs.isEmpty()) {
                        appendLine("当前没有这个应用的进程在跑（已死、被冻结或还没启动）。")
                    } else {
                        procs.forEach { p ->
                            appendLine("pid=${p.pid}  ${p.name}")
                            appendLine("    启动于 ${DiagRepo.fmtFull(p.startMs)}  ${DiagRepo.ago(p.startMs)}")
                        }
                    }
                    if (info != null && procs.isNotEmpty()) {
                        appendLine()
                        val newest = procs.maxOfOrNull { it.startMs } ?: 0L
                        append(
                            when {
                                newest <= 0L -> "判定：拿不到启动时间（/proc 被限制），无法判断新旧。"
                                newest > info.lastUpdateMs ->
                                    "判定：进程启动晚于 APK 更新时间 → 现在跑的是新版代码。"
                                else ->
                                    "判定：进程启动早于 APK 更新时间 → 老进程还没换掉，新代码没生效，先强杀进程再起。"
                            }
                        )
                    } else if (info != null && procs.isEmpty()) {
                        appendLine()
                        append("判定：下次启动必然加载 ${DiagRepo.fmtFull(info.lastUpdateMs)} 这一版。")
                    }
                }
                DiagCard("2. 进程与「新版代码是否生效」", body, palette.debug)
            }

            // ── 3. 137 / 进程终止 ──────────────────────
            item {
                val exits = bundle?.exits ?: emptyList()
                val raw = bundle?.exitRaw.orEmpty()
                val body = buildString {
                    if (exits.isEmpty()) {
                        appendLine("dumpsys activity exit-info 没有返回可解析记录。")
                        if (raw.isNotBlank()) append(raw.take(600))
                    } else {
                        val killCount = exits.count { it.is137 }
                        appendLine("共 ${exits.size} 条退出记录，其中 SIGKILL(137) $killCount 次。")
                        appendLine()
                        exits.reversed().take(15).forEach { e ->
                            appendLine("${DiagRepo.fmtFull(e.at)}  ${e.process}")
                            appendLine("    reason=${e.reason}  status=${e.status}  ${e.exitCodeText}${if (e.is137) "  ← 137 被系统 -9" else ""}")
                        }
                    }
                }
                DiagCard("3. 137 / 进程终止时间轴", body, if ((bundle?.exits ?: emptyList()).any { it.is137 }) palette.error else palette.verbose) {
                    if (raw.isNotBlank()) {
                        TextButton(onClick = { preview = Triple("exit-info 原始输出", raw, "") }) {
                            Text("原始输出")
                        }
                    }
                }
            }

            // ── 4. ANR 归因（API 37 AnrInfo）─────────
            item {
                val anr = bundle?.anr
                val body = when {
                    anr == null -> "未采集到 ANR 归因（采集阶段异常，见本页顶部「采集告警」）。"
                    anr.items.isEmpty() -> anr.whyEmpty.trim()
                    else -> buildString {
                        appendLine("结构化 ${anr.structuredCount} 条（API 37 AnrInfo） · dumpsys ${anr.dumpCount} 条 · 文本兜底 ${anr.textCount} 条")
                        if (anr.byType.isNotEmpty()) {
                            appendLine()
                            appendLine("类型分布：")
                            anr.byType.forEach { (t, c) -> appendLine("    $t × $c") }
                        }
                        appendLine()
                        anr.items.reversed().take(10).forEach { a ->
                            appendLine("${if (a.at <= 0L) "时间未给出" else DiagRepo.fmtFull(a.at)}  ${a.process}")
                            appendLine("    type=${a.typeName}(${a.anrType})  anrId=${a.anrId}  超时阈值=${a.timeoutText}  用户可感知=${a.userPerceptible}  [${a.source}]")
                            appendLine("    ${a.typeHint}")
                        }
                        if (!anr.hasStructure) {
                            appendLine()
                            appendLine("注：没有拿到系统 AnrInfo 字段。dumpsys lastanr 那行是按 Reason 文本推断的（类型/阈值由关键字匹配），推断不到就留空。")
                        }
                        val why = anr.whyEmpty.trim()
                        if (why.isNotEmpty()) {
                            appendLine()
                            appendLine("通道情况：")
                            append(why)
                        }
                        appendLine()
                        appendLine()
                        append("归因只说「哪类 ANR」。要看卡在哪个函数，点下面「抓 ANR 原文」：跑一次 bugreportz（约 1 分钟、生成几十 MB 的包），只解 FS/data/anr 里 Cmd line 是本包的那几段线程栈。")
                    }
                }
                DiagCard(
                    "4. ANR 归因（AnrInfo）",
                    body,
                    if (anr?.items?.isNotEmpty() == true) palette.error else palette.verbose
                ) {
                    TextButton(
                        enabled = !traceBusy,
                        onClick = {
                            scope.launch {
                                traceBusy = true
                                val r = withContext(Dispatchers.IO) {
                                    runCatching { DiagRepo.anrTraceDump(pkg) }.getOrNull()
                                }
                                traceBusy = false
                                trace = r
                                preview = if (r == null) {
                                    Triple("ANR 原文", "bugreport 没跑成功，先确认 Shizuku 还在、shell 通道没断。", "")
                                } else {
                                    Triple(
                                        "ANR 原文（bugreport）",
                                        buildString {
                                            appendLine(r.note)
                                            appendLine("zip：${r.zip}")
                                            appendLine("包里另有 ${r.tombstones} 个 tombstone（native 崩溃原文）")
                                            if (r.blocks.isNotEmpty()) {
                                                appendLine()
                                                append(r.blocks.joinToString("\n\n"))
                                            }
                                        },
                                        ""
                                    )
                                }
                            }
                        }
                    ) {
                        Text(if (traceBusy) "取原文中… 约 1 分钟" else "抓 ANR 原文")
                    }
                    trace?.zip?.takeIf { it.isNotEmpty() }?.let { zip ->
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { DiagRepo.deleteBugreport(zip) }
                                trace = null
                            }
                        }) { Text("删掉这个包") }
                    }
                }
            }

            // ── 5. 进程事件时间轴 ─────────────────────
            item {
                val marks = bundle?.marks ?: emptyList()
                val body = if (marks.isEmpty()) {
                    "events 缓冲里没有与 $pkg 相关的事件（缓冲可能已滚动，或被 ROM 过滤）。"
                } else buildString {
                    marks.takeLast(40).forEach { m ->
                        appendLine("${DiagRepo.fmt(m.at)}  [${m.tag}]")
                        appendLine("    ${m.text}")
                    }
                }
                DiagCard("5. 进程生命周期（events）", body, palette.raw) {
                    if (marks.isNotEmpty()) {
                        TextButton(onClick = {
                            preview = Triple(
                                "events 时间轴",
                                marks.joinToString("\n") { "${DiagRepo.fmtFull(it.at)} [${it.tag}] ${it.text}" },
                                ""
                            )
                        }) { Text("全部 ${marks.size} 条") }
                    }
                }
            }

            // ── 5. crash 现场 ────────────────────────
            item {
                val crash = bundle?.crash.orEmpty()
                DiagCard("6. native crash / 死亡现场", crash.ifBlank { "读取中…" }, palette.warn) {
                    if (crash.isNotBlank()) {
                        TextButton(onClick = { preview = Triple("crash 现场", crash, "") }) { Text("查看全部") }
                    }
                }
            }

            // ── 7. dropbox 崩溃存档 ─────────────────
            item {
                val archive = bundle?.archive ?: emptyList()
                val body = buildString {
                    if (archive.isEmpty()) {
                        appendLine("dropbox 里没有与 $pkg 相关的崩溃存档。")
                        appendLine()
                        append(
                            "dropbox 是系统在崩溃发作那一刻自己抄的一份（按标签分 Java 崩溃 / native 崩溃 / ANR），" +
                                "上限 1000 条。这里为空，通常说明这台机器上它没崩过，或崩溃不在系统留存范围内。"
                        )
                    } else {
                        val kinds = archive.groupingBy { it.kind }.eachCount()
                        appendLine("共 ${archive.size} 条：" + kinds.entries.joinToString(" · ") { "${it.key} ${it.value}" })
                        appendLine()
                        archive.reversed().take(8).forEach { c ->
                            appendLine("${DiagRepo.fmtFull(c.at)}  [${c.kind}]")
                            appendLine("    ${c.summary.take(160)}")
                        }
                        appendLine()
                        append("这些是系统侧存档，logcat 滚没了也还在。")
                    }
                }
                DiagCard(
                    "7. 系统崩溃存档（dropbox）",
                    body,
                    if (archive.isEmpty()) palette.verbose else palette.error
                ) {
                    if (archive.isNotEmpty()) {
                        TextButton(onClick = {
                            preview = Triple(
                                "dropbox 崩溃存档",
                                archive.reversed().joinToString("\n\n") {
                                    "${DiagRepo.fmtFull(it.at)} [${it.kind} / ${it.tag}]\n${it.body}"
                                },
                                ""
                            )
                        }) { Text("全部 ${archive.size} 条") }
                    }
                }
            }

            // ── 8. binder 压力 ───────────────────────
            item {
                val binders = bundle?.binder ?: emptyList()
                val raw = bundle?.binderRaw.orEmpty()
                val pid = bundle?.procs?.firstOrNull()?.pid
                val body = buildString {
                    if (binders.isEmpty()) {
                        appendLine("近 1.5 万行 main/system 日志里没有 binder 相关告警。")
                    } else {
                        if (pid != null && pid > 0) {
                            appendLine("按 tag 聚类（优先 pid=$pid，取不到再回退近 1.5 万行 main/system）：")
                        } else {
                            appendLine("按 tag 聚类（目标进程没在跑，这里看的是近 1.5 万行 main/system）：")
                        }
                        binders.forEach { appendLine("${it.count}×  ${it.tag}") }
                        appendLine()
                        append(binders.first().sample)
                    }
                    appendLine()
                    appendLine("说明：内核级 binder 统计需要 root，这里给的是 dumpsys binder_calls_stats 的 shell 视角。")
                }
                DiagCard("8. binder 打满来源", body, palette.error) {
                    if (raw.isNotBlank()) {
                        TextButton(onClick = { preview = Triple("binder_calls_stats", raw, "") }) { Text("dumpsys 原始输出") }
                    }
                }
            }

            // ── 7. 私有目录 ─────────────────────────
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    (bundle?.dirs ?: emptyList()).forEach { d ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconBadge(Icons.Rounded.Folder, palette.info, size = 32)
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        d.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = colors.onSurface,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1
                                    )
                                    Text(
                                        "${d.rows.count { !it.isDir }} 文件",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                if (d.rows.isEmpty()) {
                                    val emptyRaw = d.raw.trim()
                                    val emptyText = if (
                                        emptyRaw.isEmpty() ||
                                        emptyRaw.lineSequence().all {
                                            it.isBlank() || it.trim().startsWith("total ")
                                        }
                                    ) {
                                        "（空目录）"
                                    } else if (emptyRaw.contains("No such file or directory")) {
                                        "（目录不存在）"
                                    } else if (emptyRaw.contains("not debuggable", true) ||
                                        emptyRaw.contains("Permission denied", true)
                                    ) {
                                        "（没有读取权限：$emptyRaw）".take(500)
                                    } else {
                                        emptyRaw.take(500)
                                    }
                                    Text(
                                        emptyText,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.onSurfaceVariant
                                    )
                                } else {
                                    d.rows.take(12).forEach { r ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(enabled = !r.isDir) {
                                                    previewSeq += 1
                                                    val seq = previewSeq
                                                    preview = Triple(r.name, "读取中…", r.path)
                                                    scope.launch {
                                                        val text = withContext(Dispatchers.IO) {
                                                            runCatching { DiagRepo.readHead(r.path, pkg) }
                                                                .getOrDefault("读取失败")
                                                        }
                                                        // 用户已关掉预览或换了别的文件：结果作废，
                                                        // 否则关闭后会被异步回调重新弹出来，或旧内容盖掉新预览
                                                        if (seq == previewSeq && preview?.third == r.path) {
                                                            preview = Triple(r.name, text, r.path)
                                                        }
                                                    }
                                                }
                                                .padding(vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (r.isDir) "[目录] ${r.name}" else r.name,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontFamily = FontFamily.Monospace,
                                                color = if (r.isDir) colors.onSurfaceVariant else colors.onSurface,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1
                                            )
                                            if (r.size >= 0) {
                                                Text(
                                                    humanSize(r.size),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = colors.onSurfaceVariant
                                                )
                                            }
                                        }
                                        HorizontalDivider(color = colors.outlineVariant)
                                    }
                                    if (d.rows.size > 12) {
                                        TextButton(onClick = { preview = Triple(d.title, d.raw, "") }) {
                                            Text("还有 ${d.rows.size - 12} 项，看原始列表")
                                        }
                                    }
                                }
                                if (d.base.startsWith("/data/data/") && d.rows.isEmpty() && runAsBlocked(d)) {
                                    Text(
                                        "内部目录需要目标应用 debuggable（run-as 才能进）。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = palette.warn
                                    )
                                }
                            }
                        }
                    }
                    if ((bundle?.dirs ?: emptyList()).isEmpty()) {
                        DiagCard("9. 应用私有目录", "读取中…", palette.info)
                    }
                }
            }

            // ── 9. 全局 logcat ───────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        SectionHeader("10. 全局 logcat（不按应用过滤）")
                        SettingRow(
                            title = "全量模式",
                            subtitle = "抓 main+system+crash+events 全部日志，用来判断「问题到底在系统层还是应用层」。量大，别长时间开。",
                            checked = globalMode
                        ) {
                            globalMode = it
                            prefs.globalMode = it
                        }
                        HorizontalDivider(color = colors.outlineVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "开启后回首页点「开始捕获」即生效：不做 uid/pid 过滤，也不再单独挂 events 流。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }

            // ── 拿不到的东西 ─────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.Memory, palette.warn, size = 32)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Shizuku 拿不到的两类东西",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "1）server 进程的标准输出。app_process 启动时没重定向 stdout，只有运行期 logcat 能看到它自己打的日志。\n\n" +
                                "2）需要 root 的内核节点：binder 内核级统计细节、tombstone 全量内容（这里只能读到 crash 缓冲里的头部）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                Exporter.copy(context, report(bundle, pkg, label))
                            }) { Text("复制完整报告") }
                            TextButton(onClick = {
                                Exporter.shareText(context, report(bundle, pkg, label))
                            }) { Text("分享完整报告") }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** 只有 run-as 通道真的被拒（非 debuggable / 无权限）才提示；目录本身为空不提示 */
private fun runAsBlocked(d: DiagRepo.DirListing): Boolean {
    val r = d.raw
    return r.contains("not debuggable", true) ||
        r.contains("run-as: ", true) ||
        r.contains("Permission denied", true) ||
        r.contains("Unknown package", true)
}

@Composable
private fun DiagCard(
    title: String,
    body: String,
    accent: Color,
    actions: @Composable (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(4.dp)
                        .background(accent, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Rounded.Timeline, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant,
                maxLines = 30
            )
            if (actions != null) {
                Spacer(Modifier.height(4.dp))
                Row { actions() }
            }
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
