package dev.loglens.capture

import dev.loglens.logcat.LogSink
import dev.loglens.logcat.LogcatEngine
import dev.loglens.logcat.LogcatSource

/**
 * 单测注入点。生产代码从不写这些字段；取值全是 false / null 时行为与原来完全一致。
 *
 * 为什么需要它：`CaptureController` 的状态机错在「启动中的时间窗」——用户点了开始、
 * 还没等 logcat 起来就点停止，或者上一个引擎的 onExit 晚到。这类 bug 只能在真机上
 * 手动抢时机复现，或者注入假引擎把窗口拉长。这里选后者。
 */
internal object CaptureHooks {

    /** 允许没有 `init(context)` 也走启动流程（JVM 单测里没有真的 Context） */
    var allowWithoutContext: Boolean = false

    /** 跳过 Shizuku binder / 授权检查（单测里没有 Shizuku） */
    var skipShizukuChecks: Boolean = false

    /** 跳过会话期间每 10s 的 ps 表刷新（单测里没有 shell） */
    var skipProcRefresh: Boolean = false

    /** 日志源工厂；为空时用真实的 [LogcatEngine] */
    var sourceFactory: ((LogcatEngine.Listener) -> LogcatSource)? = null

    /** 落盘目标工厂；为空时用真实的 [dev.loglens.logcat.FileLogWriter] */
    var sinkFactory: (() -> LogSink)? = null

    fun reset() {
        allowWithoutContext = false
        skipShizukuChecks = false
        skipProcRefresh = false
        sourceFactory = null
        sinkFactory = null
    }
}
