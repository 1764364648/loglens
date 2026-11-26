package com.marvis.shizukulog.ui

import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marvis.shizukulog.capture.CaptureController
import com.marvis.shizukulog.data.AppRepository
import com.marvis.shizukulog.data.LogRepository
import com.marvis.shizukulog.data.Prefs
import com.marvis.shizukulog.ui.mx.Card
import com.marvis.shizukulog.ui.mx.CardDefaults
import com.marvis.shizukulog.ui.mx.ExperimentalMaterial3Api
import com.marvis.shizukulog.ui.mx.HorizontalDivider
import com.marvis.shizukulog.ui.mx.Icon
import com.marvis.shizukulog.ui.mx.IconButton
import com.marvis.shizukulog.ui.mx.Scaffold
import com.marvis.shizukulog.ui.mx.Text
import com.marvis.shizukulog.ui.mx.TopAppBar
import com.marvis.shizukulog.ui.mx.TopAppBarDefaults
import com.marvis.shizukulog.ui.theme.LocalLogPalette

/**
 * 设置页（应用唯一的一个设置入口）。
 *
 * 分四组：采集行为 → 日志显示 → 存储与落盘 → 关于。
 * 首页只留三个高频采集开关，其余全部归到这里，日志页不再散落显示类开关。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: Prefs,
    selected: AppRepository.AppItem?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    var includeEvents by remember { mutableStateOf(prefs.includeEvents) }
    var globalMode by remember { mutableStateOf(prefs.globalMode) }
    var highlightCrash by remember { mutableStateOf(prefs.highlightCrash) }
    var preRecord by remember { mutableStateOf(prefs.preRecord) }

    var compactRows by remember { mutableStateOf(prefs.compactRows) }
    var showTime by remember { mutableStateOf(prefs.showTime) }
    var showPid by remember { mutableStateOf(prefs.showPid) }
    var collapseStacks by remember { mutableStateOf(prefs.collapseStacks) }
    var showMinimap by remember { mutableStateOf(prefs.showMinimap) }
    var keepScreenOn by remember { mutableStateOf(prefs.keepScreenOn) }
    var fontSize by remember { mutableStateOf(prefs.fontSize) }
    var maxRows by remember { mutableStateOf(prefs.maxRows) }

    var saveToFile by remember { mutableStateOf(prefs.saveToFile) }
    var crashNotify by remember { mutableStateOf(prefs.crashNotify) }
    var logFileMaxMb by remember { mutableStateOf(prefs.logFileMaxMb) }
    var logFileKeep by remember { mutableStateOf(prefs.logFileKeep) }

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface
                    )
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // ── 1. 采集行为 ──────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    SectionHeader("采集行为")
                    SettingRow(
                        title = "读 events 缓冲",
                        subtitle = "含 ActivityManager 的启动与 ANR 事件",
                        checked = includeEvents
                    ) {
                        includeEvents = it
                        prefs.includeEvents = it
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    SettingRow(
                        title = "全量模式（不按应用过滤）",
                        subtitle = "main + system + crash + events 整机镜像，量大，别久开",
                        checked = globalMode
                    ) {
                        globalMode = it
                        prefs.globalMode = it
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    SettingRow(
                        title = "崩溃行高亮",
                        subtitle = "FATAL、ANR、信号崩溃标红",
                        checked = highlightCrash
                    ) {
                        highlightCrash = it
                        prefs.highlightCrash = it
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    SettingRow(
                        title = "崩溃前预录（不落盘）",
                        subtitle = "内存里滚动保留最近若干行，崩溃时把上文存成快照",
                        checked = preRecord
                    ) { on ->
                        if (on && selected == null) {
                            Toast.makeText(context, "先选一个目标应用再开预录", Toast.LENGTH_SHORT).show()
                        } else {
                            preRecord = on
                            prefs.preRecord = on
                            if (on) {
                                selected?.let { CaptureController.startPassive(it) }
                            } else if (CaptureController.isPassive()) {
                                CaptureController.stop()
                            }
                        }
                    }
                    if (CaptureController.isPassive()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "预录运行中：只进内存不写文件。点「开始捕获」会升级为正式录制并落盘。",
                            color = palette.info,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }

            // ── 2. 日志显示 ──────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    SectionHeader("日志显示")
                    SettingRow(
                        title = "紧凑行",
                        subtitle = "单行塞更多信息，牺牲一点呼吸感",
                        checked = compactRows
                    ) {
                        compactRows = it
                        prefs.compactRows = it
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    SettingRow(
                        title = "显示时间",
                        subtitle = "每行带时间戳",
                        checked = showTime
                    ) {
                        showTime = it
                        prefs.showTime = it
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    SettingRow(
                        title = "显示 PID / TID",
                        subtitle = "排查多进程、多线程问题时有用",
                        checked = showPid
                    ) {
                        showPid = it
                        prefs.showPid = it
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    SettingRow(
                        title = "折叠连续堆栈",
                        subtitle = "调用栈行收成一行摘要，点一下再展开",
                        checked = collapseStacks
                    ) {
                        collapseStacks = it
                        prefs.collapseStacks = it
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    SettingRow(
                        title = "显示小地图",
                        subtitle = "列表右侧的密度条，快速跳到崩溃区",
                        checked = showMinimap
                    ) {
                        showMinimap = it
                        prefs.showMinimap = it
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    SettingRow(
                        title = "采集时屏幕常亮",
                        subtitle = "盯日志时不会被锁屏打断",
                        checked = keepScreenOn
                    ) {
                        keepScreenOn = it
                        prefs.keepScreenOn = it
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    StepperRow(
                        title = "字号",
                        subtitle = "9 – 20，等宽显示",
                        value = "$fontSize sp",
                        onMinus = {
                            if (fontSize > 9) {
                                fontSize -= 1
                                prefs.fontSize = fontSize
                            }
                        },
                        onPlus = {
                            if (fontSize < 20) {
                                fontSize += 1
                                prefs.fontSize = fontSize
                            }
                        }
                    )
                    HorizontalDivider(color = colors.outlineVariant)
                    StepperRow(
                        title = "最大行数",
                        subtitle = "超出后丢最旧的",
                        value = "$maxRows",
                        onMinus = {
                            if (maxRows > 500) {
                                maxRows -= 500
                                prefs.maxRows = maxRows
                                LogRepository.capacity = maxRows
                            }
                        },
                        onPlus = {
                            if (maxRows < 20000) {
                                maxRows += 500
                                prefs.maxRows = maxRows
                                LogRepository.capacity = maxRows
                            }
                        }
                    )
                }
            }

            // ── 3. 存储与落盘 ────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    SectionHeader("存储与落盘")
                    SettingRow(
                        title = "日志落盘",
                        subtitle = "持续追加到本地 .log 文件，中途断开也不丢",
                        checked = saveToFile
                    ) {
                        saveToFile = it
                        prefs.saveToFile = it
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    SettingRow(
                        title = "崩溃通知",
                        subtitle = "抓到 FATAL / ANR 时推一条通知，不用守着屏幕",
                        checked = crashNotify
                    ) {
                        crashNotify = it
                        prefs.crashNotify = it
                    }
                    HorizontalDivider(color = colors.outlineVariant)
                    StepperRow(
                        title = "单文件上限",
                        subtitle = "写满就切下一个文件",
                        value = "$logFileMaxMb MB",
                        onMinus = {
                            if (logFileMaxMb > 4) {
                                logFileMaxMb -= 4
                                prefs.logFileMaxMb = logFileMaxMb
                            }
                        },
                        onPlus = {
                            if (logFileMaxMb < 512) {
                                logFileMaxMb += 4
                                prefs.logFileMaxMb = logFileMaxMb
                            }
                        }
                    )
                    HorizontalDivider(color = colors.outlineVariant)
                    StepperRow(
                        title = "保留份数",
                        subtitle = "超出份数自动删最旧",
                        value = "$logFileKeep 份",
                        onMinus = {
                            if (logFileKeep > 1) {
                                logFileKeep -= 1
                                prefs.logFileKeep = logFileKeep
                            }
                        },
                        onPlus = {
                            if (logFileKeep < 20) {
                                logFileKeep += 1
                                prefs.logFileKeep = logFileKeep
                            }
                        }
                    )
                }
            }

            // ── 4. 关于 ──────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SectionHeader("关于")
                    AboutRow("版本", versionName)
                    AboutRow("包名", context.packageName, mono = true)
                    Text(
                        "日志读取依赖 Shizuku 的 shell 身份（uid 2000）。" +
                            "Shizuku 服务被系统杀掉后，需要重新用无线调试启动它，再回来授权一次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }

            Text(
                text = "改动立即写入。采集进行中改的项，要停止后重新开始才会带上。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String, mono: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurface,
            fontFamily = if (mono) FontFamily.Monospace else null,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

/** 数值项：− 值 + ，点一次立刻落盘，不做输入框 */
@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        StepButton("−", onMinus)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.widthIn(min = 62.dp),
            textAlign = TextAlign.Center
        )
        StepButton("+", onPlus)
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(44.dp)
            .background(colors.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = colors.primary
        )
    }
}
