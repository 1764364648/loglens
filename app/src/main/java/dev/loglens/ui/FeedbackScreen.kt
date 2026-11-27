package dev.loglens.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.loglens.capture.CaptureController
import dev.loglens.data.Prefs
import dev.loglens.diag.SelfLog
import dev.loglens.shizuku.ShizukuHelper
import dev.loglens.ui.mx.Button
import dev.loglens.ui.mx.Card
import dev.loglens.ui.mx.CardDefaults
import dev.loglens.ui.mx.ExperimentalMaterial3Api
import dev.loglens.ui.mx.Icon
import dev.loglens.ui.mx.IconButton
import dev.loglens.ui.mx.OutlinedButton
import dev.loglens.ui.mx.Scaffold
import dev.loglens.ui.mx.Text
import dev.loglens.ui.mx.TextButton
import dev.loglens.ui.mx.TopAppBar
import dev.loglens.ui.mx.TopAppBarDefaults
import dev.loglens.ui.theme.LocalLogPalette
import dev.loglens.util.ExportFormats

/**
 * 问题反馈页：把 LogLens 自己的运行日志摊开，一键复制 / 分享 / 去提 issue。
 *
 * 采集的是别的应用时，用户手上只有「它坏了」这种描述；这个页面负责把
 * 「LogLens 侧发生了什么」变成一段能贴进 issue 的文本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(
    prefs: Prefs,
    state: CaptureController.State,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val palette = LocalLogPalette.current

    var tick by remember { mutableIntStateOf(0) }
    val header = remember(tick, state) { buildHeader(context, prefs, state) }
    // 不缓存：进页面就读一遍文件（几百行，很便宜），否则第二次打开还停在第一次的快照上
    val tailText = SelfLog.tail(24)

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "问题反馈",
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.onSurface
                        )
                        Text(
                            "带上运行日志，出问题才好查",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
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

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "这里记的是 LogLens 自己的动作：启动、授权、采集启停、Shizuku 断连、落盘失败、" +
                            "通道被系统限制、自身崩溃。不抄被采集应用的日志内容，不联网，只落在本机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Text(
                        "遇到采集拉不起来、悬浮窗不出现、崩溃这几个问题，把下面这段和「发生了什么」一起贴到 " +
                            "GitHub Issues 就行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        header,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        val text = header + "\n\n--- 运行日志 ---\n" +
                            SelfLog.tail(500).ifBlank { "(空：本次安装后还没有记录)" }
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        runCatching { cm?.setPrimaryClip(ClipData.newPlainText("LogLens 诊断信息", text)) }
                        Toast.makeText(context, "已复制，直接粘贴到 issue 里", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("复制诊断")
                }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val f = SelfLog.export(context, header)
                            ExportFormats.shareFile(context, f)
                        }.onFailure {
                            Toast.makeText(context, "导出失败：${it.message ?: "未知原因"}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("分享日志")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/1764364648/loglens/issues/new")
                                )
                            )
                        }.onFailure {
                            Toast.makeText(context, "没有可打开网页的应用", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("去提 Issue")
                }
                TextButton(
                    onClick = {
                        SelfLog.clear()
                        tick++
                        Toast.makeText(context, "已清空运行日志", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                ) {
                    Text("清空日志", color = palette.verbose)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "最近 24 行（完整日志用上面的「复制诊断」或「分享日志」）",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.info,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Text(
                        tailText.ifBlank { "(暂无记录：LogLens 还没有跑过什么关键动作)" },
                        modifier = Modifier.heightIn(max = 520.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant,
                        lineHeight = 16.sp
                    )
                }
            }

            Text(
                "文件：files/self_log/loglens.log（最多 2 份 × 512 KB，自动滚动）",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 18.dp)
            )
        }
    }
}

private fun buildHeader(
    context: Context,
    prefs: Prefs,
    state: CaptureController.State
): String = buildString {
    runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        appendLine("LogLens v${pi.versionName} (code ${pi.longVersionCode})")
    }
    appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine(
        "Shizuku：${if (ShizukuHelper.isBinderAlive()) "已连接" else "未连接"}，" +
            "授权${if (runCatching { ShizukuHelper.isPermissionGranted() }.getOrDefault(false)) "已授予" else "未授予"}"
    )
    val stateText = when (state) {
        is CaptureController.State.Idle -> "空闲"
        is CaptureController.State.Preparing -> "正在启动（${state.label}）"
        is CaptureController.State.Capturing -> "采集中 ${state.target.label} / ${state.mode}"
        is CaptureController.State.Failed -> "失败：${state.message}"
    }
    appendLine(
        "采集：$stateText，预录=${CaptureController.isPassive()}，暂停=${CaptureController.isPaused()}，" +
            "落盘=${CaptureController.logFile.value ?: "未开"}"
    )
    appendLine("上次目标：${prefs.lastLabel.ifBlank { "-" }} / ${prefs.lastPackage.ifBlank { "-" }}")
}
