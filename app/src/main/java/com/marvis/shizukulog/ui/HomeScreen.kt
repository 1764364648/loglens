package com.marvis.shizukulog.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marvis.shizukulog.capture.CaptureController
import com.marvis.shizukulog.data.AppRepository
import com.marvis.shizukulog.data.Prefs
import com.marvis.shizukulog.shizuku.ShizukuHelper
import com.marvis.shizukulog.ui.mx.Button
import com.marvis.shizukulog.ui.mx.ButtonDefaults
import com.marvis.shizukulog.ui.mx.Card
import com.marvis.shizukulog.ui.mx.CardDefaults
import com.marvis.shizukulog.ui.mx.ExperimentalMaterial3Api
import com.marvis.shizukulog.ui.mx.HorizontalDivider
import com.marvis.shizukulog.ui.mx.Icon
import com.marvis.shizukulog.ui.mx.IconButton
import com.marvis.shizukulog.ui.mx.OutlinedButton
import com.marvis.shizukulog.ui.mx.Scaffold
import com.marvis.shizukulog.ui.mx.Surface
import com.marvis.shizukulog.ui.mx.Text
import com.marvis.shizukulog.ui.mx.TextButton
import com.marvis.shizukulog.ui.mx.TopAppBar
import com.marvis.shizukulog.ui.mx.TopAppBarDefaults
import com.marvis.shizukulog.ui.theme.LocalLogPalette
import kotlinx.coroutines.delay

/**
 * 首页只回答一个问题：现在该做什么。
 *
 * 结构按状态收敛：
 * - Shizuku 没跑 / 没授权：首页只留一张复述状态的卡，主操作（打开 Shizuku / 请求授权）吸底常驻；
 * - 就绪：目标应用 + 三个高频开关 + 工具箱入口，最多三张卡；
 * - 低频项（events 缓冲、崩溃前预录、落盘轮转）收进「采集设置」二级页，诊断与取证收进「工具箱」二级页。
 *
 * 主操作不放进页面流，而是固定底部栏：拇指区常驻，不用滑到底才够得着。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    prefs: Prefs,
    selected: AppRepository.AppItem?,
    state: CaptureController.State,
    onPickApp: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTools: () -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    var alive by remember { mutableStateOf(ShizukuHelper.isBinderAlive()) }
    var granted by remember { mutableStateOf(ShizukuHelper.isPermissionGranted()) }
    var autoRequested by remember { mutableStateOf(false) }
    var lastCrash by remember { mutableStateOf(prefs.lastCrash) }

    // 高频开关留在首页，改一次立刻写回
    var saveToFile by remember { mutableStateOf(prefs.saveToFile) }
    var highlightCrash by remember { mutableStateOf(prefs.highlightCrash) }
    var globalMode by remember { mutableStateOf(prefs.globalMode) }

    LaunchedEffect(Unit) {
        while (true) {
            alive = ShizukuHelper.isBinderAlive()
            granted = alive && ShizukuHelper.isPermissionGranted()
            // 服务一就绪就弹一次授权，免得多点一步
            if (alive && !granted && !autoRequested) {
                autoRequested = true
                ShizukuHelper.requestPermission(context) { ok -> granted = ok }
            }
            delay(1200)
        }
    }

    val capturing = state is CaptureController.State.Capturing
    val preparing = state is CaptureController.State.Preparing
    val ready = alive && granted
    val needManual = alive && !granted && ShizukuHelper.needManualGrant(context)

    fun openShizuku() {
        if (!ShizukuHelper.openShizukuManager(context)) {
            Toast.makeText(context, "没找到 Shizuku，请先装好并用无线调试启动", Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "日志捕获",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface
                    )
                },
                actions = {
                    StatusChip(
                        label = when {
                            capturing -> "采集中"
                            preparing -> "启动中"
                            else -> "空闲"
                        },
                        color = when {
                            capturing -> palette.info
                            preparing -> palette.warn
                            else -> colors.onSurfaceVariant
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = "设置",
                            tint = colors.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
            )
        },
        bottomBar = {
            HomeActionBar(
                alive = alive,
                granted = granted,
                needManualGrant = needManual,
                selected = selected,
                capturing = capturing,
                preparing = preparing,
                onOpenShizuku = ::openShizuku,
                onRequestPermission = {
                    ShizukuHelper.requestPermission(context) { ok ->
                        granted = ok
                        Toast.makeText(
                            context,
                            if (ok) "授权成功，可以开始捕获" else "授权被拒绝，读不到日志",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onStart = onStart,
                onStop = onStop,
                onOpenLogs = onOpenLogs
            )
        }
    ) { innerPadding ->
        HomeContent(
            innerPadding = innerPadding,
            ready = ready,
            granted = granted,
            needManual = needManual,
            selected = selected,
            lastCrash = lastCrash,
            state = state,
            capturing = capturing,
            saveToFile = saveToFile,
            highlightCrash = highlightCrash,
            globalMode = globalMode,
            onSaveToFileChange = { saveToFile = it; prefs.saveToFile = it },
            onHighlightCrashChange = { highlightCrash = it; prefs.highlightCrash = it },
            onGlobalModeChange = { globalMode = it; prefs.globalMode = it },
            onPickApp = onPickApp,
            onOpenSettings = onOpenSettings,
            onOpenTools = onOpenTools,
            onClearCrash = { prefs.lastCrash = ""; lastCrash = "" }
        )
    }
}
/* ---------------- 内容区 ---------------- */

@Composable
private fun HomeContent(
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    ready: Boolean,
    granted: Boolean,
    needManual: Boolean,
    selected: AppRepository.AppItem?,
    lastCrash: String,
    state: CaptureController.State,
    capturing: Boolean,
    saveToFile: Boolean,
    highlightCrash: Boolean,
    globalMode: Boolean,
    onSaveToFileChange: (Boolean) -> Unit,
    onHighlightCrashChange: (Boolean) -> Unit,
    onGlobalModeChange: (Boolean) -> Unit,
    onPickApp: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTools: () -> Unit,
    onClearCrash: () -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            // 吸底栏是浮在内容上的，留出对应高度，滚到底时不至于被挡住
            .padding(bottom = if (capturing) 164.dp else 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // ── 1. Shizuku 状态 ─────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(
                        icon = if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.Terminal,
                        tint = if (granted) palette.info else colors.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Shizuku 服务",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = when {
                                !granted -> "已连上，还没拿到 shell 身份"
                                else -> "就绪，shell 身份可用"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    StatusChip(
                        label = if (granted) "已就绪" else "未授权",
                        color = if (granted) palette.info else palette.error
                    )
                }

                // 只有「拒绝且不再询问」这一种情况需要额外解释，其余靠底部主按钮就够
                if (needManual) {
                    Text(
                        "你之前拒绝过授权并勾选了「不再询问」，系统不会再弹窗。" +
                            "请到 Shizuku 应用里找到 ShizukuLogger 手动勾选授权。",
                        color = palette.warn,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        // ── 2. 目标应用 ────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .alpha(if (ready) 1f else 0.55f)
                .clickable(enabled = ready && !capturing) { onPickApp() },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selected != null) {
                    AppIcon(selected.packageName, size = 44, corner = 12)
                } else {
                    IconBadge(Icons.Rounded.BugReport, colors.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = selected?.label ?: "选择应用",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    if (selected == null) {
                        Text(
                            text = if (ready) "按 uid 过滤，覆盖该应用的全部进程" else "先完成 Shizuku 授权再选",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1
                        )
                    } else {
                        Text(
                            text = selected.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = "uid ${selected.uid} · 按 uid 过滤全部进程",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // ── 3. 常用采集设置 ────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (ready) 1f else 0.55f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                SectionHeader(if (capturing) "采集设置" else "常用设置")
                if (capturing) {
                    Text(
                        "采集中只读：要改这几项，得先停止捕获再重开，改动才会带上。",
                        color = palette.warn,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    HorizontalDivider(color = colors.outlineVariant)
                } else {
                    SettingRow(
                        title = "日志落盘",
                        subtitle = "持续追加到本地 .log，中途断开也不丢",
                        checked = saveToFile,
                        onCheckedChange = onSaveToFileChange
                    )
                    HorizontalDivider(color = colors.outlineVariant)
                    SettingRow(
                        title = "崩溃行高亮",
                        subtitle = "FATAL、ANR、信号崩溃标红",
                        checked = highlightCrash,
                        onCheckedChange = onHighlightCrashChange
                    )
                    HorizontalDivider(color = colors.outlineVariant)
                    SettingRow(
                        title = "全量模式",
                        subtitle = "整机镜像，用来区分系统层还是应用层问题",
                        checked = globalMode,
                        onCheckedChange = onGlobalModeChange
                    )
                    HorizontalDivider(color = colors.outlineVariant)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = ready) { onOpenSettings() }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "更多设置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "日志显示 · 落盘轮转 · 关于",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        // ── 4. 工具箱（诊断 / 取证） ───────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onOpenTools() },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconBadge(Icons.Rounded.BugReport, palette.info)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "工具箱",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "深度诊断（应用档案 / 137 时间轴 / binder 聚类）、服务端日志取证",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // ── 5. 上次闪退（有才显示） ────────────────────────────
        if (lastCrash.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.errorContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "上次闪退堆栈",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm?.setPrimaryClip(ClipData.newPlainText("crash", lastCrash))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("复制", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = onClearCrash) {
                            Text("清除", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = lastCrash,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onErrorContainer,
                        maxLines = 8
                    )
                }
            }
        }

        // ── 6. 启动失败 ────────────────────────────────────────
        if (state is CaptureController.State.Failed) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.errorContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "启动失败",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onErrorContainer
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onErrorContainer
                    )
                }
            }
        }

        Text(
            text = "Shizuku 重启后要重新激活 · 采集期间通知会常驻",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 12.dp)
        )
    }
}

/* ---------------- 底部主操作栏 ---------------- */

/**
 * 一个状态永远对应一个主按钮，常驻拇指区。
 * 采集中把「停止捕获」放在主按钮上面：破坏性操作够得着，但需要多伸一点。
 */
@Composable
private fun HomeActionBar(
    alive: Boolean,
    granted: Boolean,
    needManualGrant: Boolean,
    selected: AppRepository.AppItem?,
    capturing: Boolean,
    preparing: Boolean,
    onOpenShizuku: () -> Unit,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenLogs: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    Surface(color = colors.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            when {
                !alive -> Button(
                    onClick = onOpenShizuku,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("打开 Shizuku")
                }

                !granted && needManualGrant -> Button(
                    onClick = onOpenShizuku,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("去 Shizuku 授权")
                }

                !granted -> Button(
                    onClick = onRequestPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("请求授权")
                }

                capturing -> {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, palette.error.copy(alpha = 0.55f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.error)
                    ) {
                        Text("停止捕获")
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onOpenLogs,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Terminal, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("查看实时日志")
                    }
                }

                preparing -> Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("正在启动…")
                }

                else -> Column {
                    if (selected == null) {
                        Text(
                            "先选一个目标应用",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        )
                    }
                    Button(
                        onClick = onStart,
                        enabled = selected != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("开始捕获")
                    }
                }
            }
        }
    }
}
