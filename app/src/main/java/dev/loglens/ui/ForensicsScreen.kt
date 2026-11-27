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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import dev.loglens.ui.mx.Button
import dev.loglens.ui.mx.Card
import dev.loglens.ui.mx.CardDefaults
import dev.loglens.ui.mx.CircularProgressIndicator
import dev.loglens.ui.mx.ExperimentalMaterial3Api
import dev.loglens.ui.mx.HorizontalDivider
import dev.loglens.ui.mx.Icon
import dev.loglens.ui.mx.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import dev.loglens.ui.mx.OutlinedTextField
import dev.loglens.ui.mx.Scaffold
import dev.loglens.ui.mx.Switch
import dev.loglens.ui.mx.Text
import dev.loglens.ui.mx.TextButton
import dev.loglens.ui.mx.TopAppBar
import dev.loglens.ui.mx.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.loglens.data.Prefs
import dev.loglens.diag.Forensics
import dev.loglens.util.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 取证包：一键跑完「探测 → 关键句抽取 → tag 过滤 → 尾部导出 → 分享」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForensicsScreen(
    prefs: Prefs,
    pkg: String,
    label: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var logPath by remember {
        mutableStateOf(prefs.forensicLogPath.ifBlank { Forensics.DEFAULT_LOG })
    }
    var tagsText by remember {
        mutableStateOf(prefs.forensicTags.ifBlank { Forensics.DEFAULT_TAGS })
    }
    var copyDownload by remember { mutableStateOf(prefs.forensicCopyDownload) }

    var running by remember { mutableStateOf(false) }
    var steps by remember { mutableStateOf(listOf<Forensics.Step>()) }
    var result by remember { mutableStateOf<Forensics.Result?>(null) }
    var expanded by remember { mutableStateOf(-1) }
    var listing by remember { mutableStateOf<List<Pair<String, Long>>?>(null) }

    fun persist() {
        prefs.forensicLogPath = logPath
        prefs.forensicTags = tagsText
        prefs.forensicCopyDownload = copyDownload
    }

    fun start() {
        persist()
        running = true
        steps = emptyList()
        result = null
        expanded = -1
        val targetPkg = pkg.ifBlank { prefs.lastPackage }
        val tags = tagsText.split(",", "，", " ").map { it.trim() }.filter { it.isNotEmpty() }
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                Forensics.run(
                    context = context,
                    pkg = targetPkg,
                    logPath = logPath,
                    tags = tags,
                    copyToDownload = copyDownload,
                    onStep = { s ->
                        // 回调发生在 IO 线程，切回主线程增量刷新
                        scope.launch(Dispatchers.Main) { steps = steps + s }
                    }
                )
            }
            steps = r.steps
            result = r
            running = false
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("取证包", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { if (!running) start() }, enabled = !running) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "重新取证")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface,
                    titleContentColor = colors.onSurface,
                    navigationIconContentColor = colors.onSurface
                )
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .background(colors.background),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 目标 ─────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            label.ifBlank { "未选择应用" },
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface
                        )
                        Text(
                            pkg.ifBlank { "包名为空，请先回首页选应用" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "把「设备上某个服务自己写的日志文件」和「指定 tag 的 logcat 尾部」收成一份带结论的包，可直接分享。" +
                                "默认适配「屏幕采集 / 虚拟显示」这类服务（无 binder 客户端后自退、宿主随后被 137）：路径与 tag 都能改成你自己的。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }

            // ── 参数 ─────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("取证参数", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = logPath,
                            onValueChange = { logPath = it },
                            label = { Text("服务日志文件路径") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        TextButton(
                            onClick = {
                                val parent = logPath.substringBeforeLast('/', "/data/local/tmp").ifBlank { "/data/local/tmp" }
                                scope.launch {
                                    listing = withContext(Dispatchers.IO) { Forensics.listServerLogs(parent) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("不确定路径？列出该目录下的文件", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedTextField(
                            value = tagsText,
                            onValueChange = { tagsText = it },
                            label = { Text("logcat tag（逗号分隔）") },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("同时整份复制到 /sdcard/Download", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "文件管理器可直接拿到原文件；大文件会慢一点",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = copyDownload,
                                onCheckedChange = {
                                    copyDownload = it
                                    prefs.forensicCopyDownload = it
                                }
                            )
                        }
                        Button(
                            onClick = { start() },
                            enabled = !running,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.onPrimary
                                )
                                Spacer(Modifier.size(10.dp))
                                Text("取证中…（大日志需要十几秒）")
                            } else {
                                Text("开始取证并生成报告")
                            }
                        }
                    }
                }
            }

            // ── 结论 ─────────────────────────────────────────
            result?.let { r ->
                item {
                    val bg = when (r.level) {
                        2 -> colors.errorContainer
                        1 -> colors.secondaryContainer
                        else -> colors.surfaceVariant
                    }
                    val fg = when (r.level) {
                        2 -> colors.onErrorContainer
                        1 -> colors.onSecondaryContainer
                        else -> colors.onSurfaceVariant
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = bg)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                when (r.level) {
                                    2 -> "结论：已命中问题特征"
                                    1 -> "结论：待核"
                                    else -> "结论：本次未命中"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = fg
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(r.verdict, style = MaterialTheme.typography.bodySmall, color = fg)
                            if (r.hits.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider(color = fg.copy(alpha = 0.2f))
                                Spacer(Modifier.height(8.dp))
                                r.hits.forEach { h ->
                                    Text(
                                        "· ${h.keyword} × ${h.count}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = fg
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row {
                                TextButton(onClick = { Exporter.copy(context, r.report) }) {
                                    Text("复制报告", color = fg)
                                }
                                TextButton(onClick = { Exporter.shareText(context, r.report) }) {
                                    Text("分享报告文本", color = fg)
                                }
                            }
                        }
                    }
                }
            }

            // ── 步骤 ─────────────────────────────────────────
            if (steps.isNotEmpty()) {
                item {
                    Text(
                        "取证步骤",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                itemsIndexed(steps) { index, s ->
                    val open = expanded == index
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface)
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            if (s.ok) colors.primary else colors.error,
                                            RoundedCornerShape(4.dp)
                                        )
                                )
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    s.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { expanded = if (open) -1 else index }) {
                                    Text(if (open) "收起" else "展开")
                                }
                            }
                            Text(
                                "$ ${s.cmd}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = colors.onSurfaceVariant
                            )
                            if (s.out.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                val body = if (open) s.out else s.out.lineSequence().take(6).joinToString("\n")
                                Text(
                                    body,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.onSurface
                                )
                                if (!open && s.out.lineSequence().count() > 6) {
                                    Text(
                                        "…共 ${s.out.lineSequence().count()} 行，点展开看全部",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 产物 ─────────────────────────────────────────
            result?.let { r ->
                if (r.files.isNotEmpty()) {
                    item {
                        Text(
                            "已生成文件（点击分享）",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    itemsIndexed(r.files) { index, f ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.surface)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        f.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        Forensics.human(r.fileSizes.getOrElse(index) { 0L }),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { Exporter.share(context, f, f.name) }) {
                                    Text("分享")
                                }
                            }
                        }
                    }
                }
            }

            // ── 底部说明 ─────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
                ) {
                    Row(Modifier.padding(14.dp)) {
                        Icon(
                            Icons.Rounded.Info,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "产物写在 Android/data/dev.loglens/files/forensics/ 下，走 FileProvider 分享，" +
                                "不需要存储权限。服务日志尾部只带 2 万行，整份原件走 ⑤ 的 /sdcard/Download 复制。" +
                                "日志路径不可读时看 ④b 的 run-as 兜底结果。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (listing != null) {
            ForensicsPickDialog(
                files = listing ?: emptyList(),
                onPick = { logPath = it; persist(); listing = null },
                onDismiss = { listing = null }
            )
        }
    }
}

@Composable
private fun ForensicsPickDialog(
    files: List<Pair<String, Long>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceVariant,
        title = { Text("选择服务日志文件", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (files.isEmpty()) {
                    Text(
                        "这个目录下没读到文件。可能是 Shizuku 没授权、目录为空，或者服务根本没往这里写日志——那就手动填绝对路径。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                } else {
                    files.forEach { (path, size) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(path) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    path,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.onSurface
                                )
                                Text(
                                    Forensics.human(size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            Text(
                                "选它",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.primary
                            )
                        }
                        HorizontalDivider(color = colors.outlineVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
