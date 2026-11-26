package com.marvis.shizukulog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import com.marvis.shizukulog.ui.mx.Button
import com.marvis.shizukulog.ui.mx.Card
import com.marvis.shizukulog.ui.mx.CardDefaults
import com.marvis.shizukulog.ui.mx.CircularProgressIndicator
import com.marvis.shizukulog.ui.mx.ExperimentalMaterial3Api
import com.marvis.shizukulog.ui.mx.HorizontalDivider
import com.marvis.shizukulog.ui.mx.Icon
import com.marvis.shizukulog.ui.mx.IconButton
import androidx.compose.material3.MaterialTheme
import com.marvis.shizukulog.ui.mx.OutlinedTextField
import com.marvis.shizukulog.ui.mx.Scaffold
import com.marvis.shizukulog.ui.mx.Switch
import com.marvis.shizukulog.ui.mx.Text
import com.marvis.shizukulog.ui.mx.TextButton
import com.marvis.shizukulog.ui.mx.TopAppBar
import com.marvis.shizukulog.ui.mx.TopAppBarDefaults
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
import com.marvis.shizukulog.data.Prefs
import com.marvis.shizukulog.diag.Forensics
import com.marvis.shizukulog.util.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 服务端日志取证页：一键跑完「探测 → 关键句抽取 → tag 过滤 → 尾部导出 → 分享」。
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
                title = { Text("服务端日志取证", style = MaterialTheme.typography.titleMedium) },
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
                            "针对「虚拟显示服务在无 binder 客户端后自退，导致宿主被 137」这一类问题的固定取证流程。" +
                                "一键跑完：日志探测 → 关键句抽取 → main buffer 按 tag 过滤 → 尾部导出，最后直接分享文件。",
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
                            label = { Text("server 日志路径") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
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
                    itemsIndexed(r.files) { _, f ->
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
                                        Forensics.human(f.length()),
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
                            "产物写在 Android/data/com.marvis.shizukulog/files/forensics/ 下，走 FileProvider 分享，" +
                                "不需要存储权限。server 日志尾部只带 2 万行，整份原件走 ⑤ 的 /sdcard/Download 复制。" +
                                "日志路径不可读时看 ④b 的 run-as 兜底结果。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
