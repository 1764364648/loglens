package dev.loglens.capture

import android.content.Context
import dev.loglens.data.AppGraph
import dev.loglens.data.LogRepository
import dev.loglens.logcat.FileLogWriter
import dev.loglens.logcat.LogSink
import dev.loglens.logcat.LogcatEngine
import dev.loglens.logcat.LogcatSource
import dev.loglens.service.CaptureService
import dev.loglens.shizuku.ShellRunner
import dev.loglens.shizuku.ShizukuHelper

/**
 * 采集会话需要的外部能力：Shizuku 检查、uid/pid 查询、日志源、落盘、前台服务、进程表。
 *
 * 全部是 lambda，默认值指向真实实现；单测替换成假对象即可，不需要 `init(context)`
 * 之类的全局后门，也不需要 `skipShizukuChecks` 这类静态开关。
 */
internal class CaptureDeps(
    /** 应用 context。单测可以是 null：此时不落盘、不起前台服务、不做崩溃通知。 */
    val appContext: Context? = null,
    val logs: () -> LogRepository = { AppGraph.logs },
    /** 返回 ps 表里的进程数；失败返回 null。为 null 表示这个会话不刷新进程表（单测用）。 */
    val procRefresh: (() -> Int?)? = {
        if (runCatching { AppGraph.procs.refresh() }.getOrDefault(false)) {
            AppGraph.procs.snapshot().size
        } else {
            null
        }
    },
    val binderAlive: () -> Boolean = { ShizukuHelper.isBinderAlive() },
    val permissionGranted: () -> Boolean = { ShizukuHelper.isPermissionGranted() },
    val uidOf: (String) -> Int? = { runCatching { ShellRunner.uidOf(it) }.getOrNull() },
    val pidsFor: (String, Int) -> List<Int> = { pkg, uid ->
        runCatching { ShellRunner.pidsFor(pkg, uid) }.getOrDefault(emptyList())
    },
    /** 日志源工厂。默认 [LogcatEngine]，单测换成假引擎。 */
    val newSource: (LogcatEngine.Listener) -> LogcatSource = { LogcatEngine(it) },
    /** 落盘目标工厂；null 时用真实的 [FileLogWriter]（需要 context）。 */
    val newSink: (() -> LogSink)? = null,
    /** 拉起前台服务；null 时走真实的 [CaptureService.start]（需要 context）。 */
    val startService: (() -> Unit)? = null,
) {

    companion object {
        fun production(context: Context): CaptureDeps {
            val app = context.applicationContext
            return CaptureDeps(
                appContext = app,
                newSink = { FileLogWriter(app) },
                startService = { CaptureService.start(app) },
            )
        }
    }
}
