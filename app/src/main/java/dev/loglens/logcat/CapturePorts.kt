package dev.loglens.logcat

import dev.loglens.data.LogBuffer
import dev.loglens.data.LogEntry
import java.io.File

/**
 * 采集会话的两个「端口」：日志源与落盘目标。
 *
 * 存在的原因只有一个：让 [dev.loglens.capture.CaptureController] 的状态机能在纯 JVM
 * 单测里跑起来。注入一个不碰 Shizuku、不碰文件的假实现，就能验证「启动中被停止」
 * 「旧引擎晚到的 onExit」这类平时只会在真机上偶发、靠人手点不出来的时间窗问题。
 *
 * 生产路径完全不变：默认实现仍是 [LogcatEngine] 与 [FileLogWriter]。
 */
interface LogcatSource {
    fun start(
        packageName: String,
        uid: Int?,
        pid: Int?,
        includeEvents: Boolean,
        globalMode: Boolean,
        buffers: Set<LogBuffer>
    )

    fun stop()
}

interface LogSink {
    /** 落盘彻底失败（ENOSPC / 存储被卸载）时回调一次 */
    var onFatal: ((String) -> Unit)?

    /** 当前写入的文件；为 null 表示还没开出来 */
    val currentFile: File?

    fun start(packageName: String)

    fun enqueue(entry: LogEntry)

    fun stop()
}
