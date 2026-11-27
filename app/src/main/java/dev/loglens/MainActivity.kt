package dev.loglens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.loglens.capture.CaptureController
import dev.loglens.data.AppRepository
import dev.loglens.data.LogBuffer
import dev.loglens.data.Prefs
import dev.loglens.service.CaptureService
import dev.loglens.shizuku.ShizukuHelper
import dev.loglens.ui.AppPickerScreen
import dev.loglens.ui.DiagScreen
import dev.loglens.ui.FeedbackScreen
import dev.loglens.ui.ForensicsScreen
import dev.loglens.ui.HomeScreen
import dev.loglens.ui.LogScreen
import dev.loglens.ui.NetScreen
import dev.loglens.ui.SettingsScreen
import dev.loglens.ui.ToolsScreen
import dev.loglens.ui.theme.LogLensTheme

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var launchApp: LogApp? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        launchApp = application as LogApp
        ensureNotificationPermission()

        setContent {
            LogLensTheme {
                AppRoot(launchApp ?: (application as LogApp))
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}

private enum class Screen { Home, Picker, Logs, Diag, Forensic, Settings, Tools, Net, Feedback }

@Composable
private fun AppRoot(host: LogApp) {
    val context = LocalContext.current
    val prefs: Prefs = remember { host.prefs }
    val state by CaptureController.state.collectAsState()

    var screen by remember { mutableStateOf(Screen.Home) }
    var selected by remember {
        mutableStateOf(AppRepository.resolve(context, prefs.lastPackage))
    }
    var pendingStart by remember { mutableStateOf(false) }

    // 预录开关在冷启动路径上真正生效：之前只有进设置页拨开关才会拉起，
    // 重启后开关亮着却没有任何预录在跑（ADR 里那条已知缺陷）。
    LaunchedEffect(Unit) {
        val target = selected
        if (prefs.preRecord && target != null &&
            CaptureController.state.value is CaptureController.State.Idle &&
            ShizukuHelper.isBinderAlive() &&
            runCatching { ShizukuHelper.isPermissionGranted() }.getOrDefault(false)
        ) {
            CaptureController.startPassive(target)
        }
    }

    // 点击开始后，等引擎真正跑起来再进日志页，失败则留在首页看原因
    LaunchedEffect(state, pendingStart) {
        if (!pendingStart) return@LaunchedEffect
        when (state) {
            is CaptureController.State.Capturing -> {
                pendingStart = false
                screen = Screen.Logs
            }

            is CaptureController.State.Failed -> {
                pendingStart = false
                CaptureService.stop(context)
            }

            else -> Unit
        }
    }

    // 二级页返回：从诊断/取证回来落回工具箱，其余一律回首页
    BackHandler(enabled = screen != Screen.Home) {
        screen = when (screen) {
            Screen.Diag, Screen.Forensic, Screen.Net, Screen.Feedback -> Screen.Tools
            else -> Screen.Home
        }
    }

    when (screen) {
        Screen.Home -> HomeScreen(
            prefs = prefs,
            selected = selected,
            state = state,
            onPickApp = { screen = Screen.Picker },
            onStart = {
                selected?.let { app ->
                    CaptureService.start(context)
                    CaptureController.start(
                        app = app,
                        saveToFile = prefs.saveToFile,
                        includeEvents = prefs.includeEvents,
                        globalMode = prefs.globalMode,
                        buffers = LogBuffer.parse(prefs.buffers)
                    )
                    pendingStart = true
                }
            },
            onStop = {
                CaptureController.stop()
                CaptureService.stop(context)
                // 服务那边只要有一环没走到，通知就会留成「残岛」，这里先兜底撤一次
                CaptureService.cancelSticky(context)
            },
            onOpenLogs = { screen = Screen.Logs },
            onOpenSettings = { screen = Screen.Settings },
            onOpenTools = {
                if (selected == null && prefs.lastPackage.isNotBlank()) {
                    selected = AppRepository.resolve(context, prefs.lastPackage)
                }
                screen = Screen.Tools
            }
        )

        Screen.Picker -> AppPickerScreen(
            current = selected?.packageName ?: "",
            onBack = { screen = Screen.Home },
            onSelect = { app ->
                selected = app
                prefs.lastPackage = app.packageName
                prefs.lastLabel = app.label
                screen = Screen.Home
            }
        )

        Screen.Settings -> SettingsScreen(
            prefs = prefs,
            selected = selected,
            onBack = { screen = Screen.Home }
        )

        Screen.Tools -> ToolsScreen(
            prefs = prefs,
            selected = selected,
            onOpenDiag = {
                if (selected == null && prefs.lastPackage.isNotBlank()) {
                    selected = AppRepository.resolve(context, prefs.lastPackage)
                }
                screen = Screen.Diag
            },
            onOpenForensics = {
                if (selected == null && prefs.lastPackage.isNotBlank()) {
                    selected = AppRepository.resolve(context, prefs.lastPackage)
                }
                screen = Screen.Forensic
            },
            onOpenNet = {
                if (selected == null && prefs.lastPackage.isNotBlank()) {
                    selected = AppRepository.resolve(context, prefs.lastPackage)
                }
                screen = Screen.Net
            },
            onOpenFeedback = { screen = Screen.Feedback },
            onBack = { screen = Screen.Home }
        )

        Screen.Net -> NetScreen(
            selected = selected ?: prefs.lastPackage.takeIf { it.isNotBlank() }
                ?.let { AppRepository.resolve(context, it) },
            onBack = { screen = Screen.Tools }
        )

        Screen.Feedback -> FeedbackScreen(
            prefs = prefs,
            state = state,
            onBack = { screen = Screen.Tools }
        )

        Screen.Logs -> {
            val label = when (val s = state) {
                is CaptureController.State.Capturing -> s.target.label
                is CaptureController.State.Preparing -> s.label
                else -> prefs.lastLabel
            }
            val pkg = when (val s = state) {
                is CaptureController.State.Capturing -> s.target.packageName
                else -> prefs.lastPackage
            }
            LogScreen(
                prefs = prefs,
                targetLabel = label,
                targetPkg = pkg,
                state = state,
                onBack = { screen = Screen.Home }
            )
        }

        Screen.Diag -> {
            val label = when (val s = state) {
                is CaptureController.State.Capturing -> s.target.label
                is CaptureController.State.Preparing -> s.label
                else -> selected?.label ?: prefs.lastLabel
            }
            val pkg = when (val s = state) {
                is CaptureController.State.Capturing -> s.target.packageName
                else -> selected?.packageName ?: prefs.lastPackage
            }
            DiagScreen(
                prefs = prefs,
                pkg = pkg,
                label = label.ifBlank { "未选择应用" },
                onBack = { screen = Screen.Tools }
            )
        }

        Screen.Forensic -> {
            val label = when (val s = state) {
                is CaptureController.State.Capturing -> s.target.label
                is CaptureController.State.Preparing -> s.label
                else -> selected?.label ?: prefs.lastLabel
            }
            val pkg = when (val s = state) {
                is CaptureController.State.Capturing -> s.target.packageName
                else -> selected?.packageName ?: prefs.lastPackage
            }
            ForensicsScreen(
                prefs = prefs,
                pkg = pkg,
                label = label.ifBlank { "未选择应用" },
                onBack = { screen = Screen.Tools }
            )
        }
    }
}
