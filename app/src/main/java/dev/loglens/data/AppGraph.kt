package dev.loglens.data

/**
 * 手写依赖入口：进程里唯一的几个可变状态从这里取。
 *
 * 不为引入框架，只为把「谁在用全局状态」变成一行显式代码：
 * UI / Service / 采集引擎都从 [logs]、[procs] 拿实例，测试里可以
 * `AppGraph.install(LogRepository(), ProcNames { _, _ -> fakeResult })` 注入替身，
 * 不必再清 object 的静态字段。
 */
object AppGraph {

    @Volatile
    lateinit var logs: LogRepository
        private set

    @Volatile
    lateinit var procs: ProcNames
        private set

    /** 幂等安装；重复调用（例如测试之间）直接换成新的实例 */
    fun install(
        logs: LogRepository = LogRepository(),
        procs: ProcNames = ProcNames()
    ) {
        this.logs = logs
        this.procs = procs
    }
}
