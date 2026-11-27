package dev.loglens.capture

import android.content.Context
import dev.loglens.data.AppRepository
import dev.loglens.data.AppGraph
import dev.loglens.data.LogBuffer
import dev.loglens.data.LogEntry
import dev.loglens.data.Prefs
import dev.loglens.diag.SelfLog
import dev.loglens.logcat.CrashNotifier
import dev.loglens.logcat.FileLogWriter
import dev.loglens.logcat.LogcatEngine
import dev.loglens.logcat.LogcatSource
import dev.loglens.logcat.LogSink
import dev.loglens.service.CaptureService
import dev.loglens.shizuku.ShizukuHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 采集会话的唯一入口。Activity / Service 都通过它操作，避免 Binder 与生命周期耦合。
 */
object CaptureController {

    data class Target(val packageName: String, val label: String, val uid: Int)

    sealed interface State {
        data object Idle : State
        data class Preparing(val label: String) : State
        data class Capturing(
            val target: Target,
            val mode: String,
            val command: String,
            val startedAt: Long
        ) : State

        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: LogcatSource? = null
    private var writer: LogSink? = null
    private var appContext: Context? = null

    /** 预录模式：在跑但没落盘，随时可以升级成正式采集 */
    private var passive = false

    /** 预录是否真的在跑。设置页开关读这个，而不是读偏好：偏好开着但预录没跑过就是假开。 */
    private val _passive = MutableStateFlow(false)
    val passiveState: StateFlow<Boolean> = _passive

    private fun setPassive(v: Boolean) {
        passive = v
        _passive.value = v
    }

    /** 正在换代：主动停旧引擎时 onExit 不能把新会话的状态清掉 */
    private var restarting = false

    /**
     * 会话令牌：start/resume/stop 都会递增。启动协程里有多处 Shizuku 往返，
     * 用户可能在「正在启动」时从通知点停止；令牌对不上就让旧启动流程自行退出，
     * 否则会出现「服务已停、采集却复活且没有前台服务」的残局。
     */
    private val session = java.util.concurrent.atomic.AtomicLong(0)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 采集会话的启停参数，暂停后据此原样恢复，不重新清空已捕获日志 */
    data class StartArgs(
        val app: AppRepository.AppItem,
        val saveToFile: Boolean,
        val includeEvents: Boolean,
        val globalMode: Boolean,
        val buffers: Set<LogBuffer>
    )

    private var lastArgs: StartArgs? = null

    /** 暂停开关：为 true 时 logcat 已停止读取，但会话、文件写入器与前台通知都保留 */
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    private val _logFile = MutableStateFlow<String?>(null)
    val logFile: StateFlow<String?> = _logFile

    /** 日志通路被系统限制（荣耀/华为 AP 日志未开）时为 true，界面据此常驻展示引导 */
    private val _logChannelBlocked = MutableStateFlow(false)
    val logChannelBlocked: StateFlow<Boolean> = _logChannelBlocked

    fun dismissChannelBlocked() {
        _logChannelBlocked.value = false
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isCapturing(): Boolean = _state.value is State.Capturing

    fun consumeNotice(): String? {
        val n = _notice.value
        _notice.value = null
        return n
    }

    fun start(
        app: AppRepository.AppItem,
        saveToFile: Boolean,
        includeEvents: Boolean,
        globalMode: Boolean = false,
        buffers: Set<LogBuffer> = LogBuffer.DEFAULT
    ) {
        if (appContext == null && !CaptureHooks.allowWithoutContext) {
            setPassive(false)
            return
        }
        // 正在预录时点「开始捕获」＝升级为正式采集：不打断流、保留已抓到的内容、只把落盘接上
        val upgrading = passive && saveToFile && _state.value is State.Capturing
        if ((_state.value is State.Capturing || _state.value is State.Preparing) && !upgrading) return
        if (upgrading) {
            restarting = true
            engine?.stop()
            engine = null
            setPassive(false)
        } else {
            // 上一次换代若被中途停止，restarting 可能还挂着；新会话必须清掉
            restarting = false
        }

        val args = StartArgs(app, saveToFile, includeEvents, globalMode, buffers)
        lastArgs = args
        _paused.value = false
        if (!upgrading) _state.value = State.Preparing(app.label)
        SelfLog.i(
            "capture",
            "开始 target=${app.packageName} uid=${app.uid} save=$saveToFile events=$includeEvents " +
                "global=$globalMode buffers=${buffers.joinToString(",")} upgrade=$upgrading"
        )
        launchEngine(args, clearFirst = !upgrading, sid = session.incrementAndGet())
    }

    /**
     * 预录：不落盘、只在内存里滚动保留最近若干行。
     * 用户还没决定要正式录制时，崩溃也能靠快照把「崩溃前发生了什么」留下来。
     */
    fun startPassive(app: AppRepository.AppItem) {
        if (appContext == null && !CaptureHooks.allowWithoutContext) return
        if (_state.value is State.Capturing || _state.value is State.Preparing) return
        setPassive(true)
        start(
            app = app,
            saveToFile = false,
            includeEvents = false,
            globalMode = false,
            buffers = LogBuffer.DEFAULT
        )
        // 预录也要走前台服务：否则切后台被系统回收，等真崩溃时上文早就没了
        appContext?.let { CaptureService.start(it) }
        SelfLog.i("capture", "预录开始 target=${app.packageName}")
    }

    fun isPassive(): Boolean = passive

    /** 暂停：停掉 logcat 读取，保留会话参数、文件写入器与前台通知，随时可继续 */
    fun pause() {
        if (_state.value !is State.Capturing || _paused.value) return
        // 先置位：engine.stop() 会回调 onExit，用该标记区分「主动暂停」与「日志流意外结束」
        _paused.value = true
        engine?.stop()
        engine = null
        _notice.value = "已暂停采集，可在通知或岛上点「继续」恢复"
        SelfLog.i("capture", "暂停")
    }

    /** 继续：按暂停前的参数重新拉起 logcat，不清空已捕获日志 */
    fun resume() {
        val args = lastArgs ?: return
        if (!_paused.value) return
        _paused.value = false
        SelfLog.i("capture", "继续 target=${args.app.packageName}")
        launchEngine(args, clearFirst = false, sid = session.incrementAndGet())
    }

    fun isPaused(): Boolean = _paused.value

    private fun launchEngine(args: StartArgs, clearFirst: Boolean, sid: Long) {
        val ctx = appContext
        if (ctx == null && !CaptureHooks.allowWithoutContext) return
        val app = args.app

        if (!CaptureHooks.skipShizukuChecks && !ShizukuHelper.isBinderAlive()) {
            if (session.get() != sid) return
            restarting = false
            _state.value = State.Failed("Shizuku 服务未运行。请先启动 Shizuku（无线调试方式），再回到本页重试。")
            SelfLog.w("capture", "启动中止：Shizuku binder 不可用 target=${app.packageName}")
            return
        }
        if (!CaptureHooks.skipShizukuChecks && !ShizukuHelper.isPermissionGranted()) {
            if (session.get() != sid) return
            restarting = false
            _state.value = State.Failed("尚未获得 Shizuku 授权。请点击「请求授权」并在弹窗中允许。")
            SelfLog.w("capture", "启动中止：未获得 Shizuku 授权 target=${app.packageName}")
            return
        }

        // pid → 进程名表：`package:` / `process:` 这类查询全靠它，之前全仓没人调用 refresh，
        // 表永远是空的。会话期间每 10 秒刷一次，退出循环的唯一条件是令牌换代。
        if (!CaptureHooks.skipProcRefresh) scope.launch {
            var lastCount = -1
            var lastFailAt = 0L
            while (isActive && session.get() == sid) {
                val ok = runCatching { AppGraph.procs.refresh() }.getOrDefault(false)
                if (!ok) {
                    // Shizuku 忙时 ps 会偶发超时；表会沿用上一次的，日志里一分钟最多记一条
                    val now = System.currentTimeMillis()
                    if (now - lastFailAt > 60_000L) {
                        SelfLog.w("procs", "ps 表刷新失败（沿用上一次，Shizuku 可能忙或已断）")
                        lastFailAt = now
                    }
                } else {
                    val n = AppGraph.procs.snapshot().size
                    if (n != lastCount) {
                        SelfLog.i("procs", "ps 表就绪，${n} 个进程")
                        lastCount = n
                    }
                    lastFailAt = 0L
                }
                delay(10_000L)
            }
        }

        scope.launch {
            var createdWriter: LogSink? = null
            try {
                if (session.get() != sid) return@launch
                if (clearFirst) {
                    AppGraph.logs.clear()
                    _logFile.value = null
                }
                _logChannelBlocked.value = false

                // uid 优先用 PackageManager 给出的真实 uid；异常时回退 shell 查询
                val uid = app.uid.takeIf { it > 0 }
                    ?: runCatching { dev.loglens.shizuku.ShellRunner.uidOf(app.packageName) }
                        .getOrNull()
                    ?: 0
                val target = Target(app.packageName, app.label, uid)

                val pids = runCatching {
                    dev.loglens.shizuku.ShellRunner.pidsFor(app.packageName, uid)
                }.getOrDefault(emptyList())
                // 这些 shell 调用可能耗时几百毫秒，期间用户可能已经从通知点了停止
                if (session.get() != sid) return@launch

                if (args.saveToFile && writer == null) {
                    val w = CaptureHooks.sinkFactory?.invoke() ?: run {
                        val c = ctx ?: return@launch
                        FileLogWriter(c)
                    }
                    // 落盘失败（存储满 / 存储被卸载）要让人看见：之前只写 SelfLog，界面一直显示「落盘中」
                    w.onFatal = { msg ->
                        _notice.value = msg
                        _logFile.value = null
                        SelfLog.w("capture", "落盘已停止：$msg")
                    }
                    createdWriter = w
                    w.start(app.packageName)
                    writer = w
                    _logFile.value = w.currentFile?.absolutePath
                }
                if (session.get() != sid) {
                    if (writer === createdWriter) writer = null
                    createdWriter?.stop()
                    return@launch
                }

                // 旧引擎的线程可能晚于新会话才回调 onExit，用归属校验挡住，
                // 否则它会把刚建好的 writer 一起 stop 掉（表现：采集跑一会儿就不再写文件）
                var self: LogcatSource? = null
                val listener = object : LogcatEngine.Listener {
                    override fun onStarted(mode: String, command: String) {
                        if (engine !== self || session.get() != sid) return
                        restarting = false
                        _state.value = State.Capturing(
                            target = target,
                            mode = mode,
                            command = command,
                            startedAt = System.currentTimeMillis()
                        )
                        SelfLog.i("capture", "日志流就绪 target=${target.packageName} mode=$mode cmd=$command")
                    }

                    override fun onEntry(entry: LogEntry) {
                        if (engine !== self) return
                        AppGraph.logs.add(entry)
                        writer?.enqueue(entry)
                        val c = ctx
                        if (entry.isCrash && c != null) {
                            runCatching { saveCrashSnapshot(c, entry) }
                                .onFailure { _notice.value = "崩溃快照失败：${it.message ?: it.javaClass.simpleName}" }
                            runCatching {
                                CrashNotifier.notifyCrash(
                                    c,
                                    entry,
                                    runCatching { Prefs(c).crashNotify }.getOrDefault(true)
                                )
                            }.onFailure { _notice.value = "崩溃通知失败：${it.message ?: it.javaClass.simpleName}" }
                        }
                    }

                    override fun onNotice(text: String) {
                        if (engine !== self) return
                        _notice.value = text
                    }

                    override fun onFailure(message: String) {
                        if (engine !== self) return
                        // 主 logcat 失败时 events 流、看门狗线程还活着，必须一起收掉，否则会泄漏进程
                        self?.stop()
                        setPassive(false)
                        _state.value = State.Failed(message)
                        SelfLog.e("capture", "采集失败 target=${target.packageName} msg=$message")
                        writer?.stop()
                        writer = null
                    }

                    override fun onExit(code: Int) {
                        if (engine !== self) return
                        // logcat 自然退出时 events 子流不会自己结束；先做一次完整收摊
                        self?.stop()
                        SelfLog.i("capture", "日志流结束 exit=$code paused=${_paused.value} restarting=$restarting")
                        // 主动暂停 / 预录升级换代引发的退出：保留写入器与参数，别把新会话清掉
                        if (_paused.value || restarting) return
                        setPassive(false)
                        writer?.stop()
                        writer = null
                        engine = null
                        _paused.value = false
                        lastArgs = null
                        if (_state.value is State.Capturing) {
                            _state.value = State.Idle
                            _notice.value = "日志流已结束（exit=$code），可重新开始捕获"
                        }
                    }

                    override fun onLogChannelBlocked(hint: String) {
                        if (engine !== self) return
                        _logChannelBlocked.value = true
                        _notice.value = "日志通道被系统限制，点日志页上方的提示条查看解决办法"
                        SelfLog.w("capture", "日志通道被限制 hint=$hint")
                    }
                }
                val eng = CaptureHooks.sourceFactory?.invoke(listener) ?: LogcatEngine(listener)
                self = eng
                engine = eng
                if (session.get() != sid) {
                    eng.stop()
                    if (engine === eng) engine = null
                    if (writer === createdWriter) writer = null
                    createdWriter?.stop()
                    return@launch
                }
                eng.start(
                    packageName = app.packageName,
                    uid = uid.takeIf { it > 0 },
                    pid = pids.firstOrNull(),
                    includeEvents = args.includeEvents,
                    globalMode = args.globalMode,
                    buffers = args.buffers
                )
                // start() 内部还要跑几次 shell；期间被停止时把刚拉起的进程再收掉
                if (session.get() != sid) {
                    eng.stop()
                    if (engine === eng) {
                        engine = null
                        // 收掉可能被 onStarted 抢写回的 Capturing 残状态
                        if (_state.value is State.Capturing) _state.value = State.Idle
                    }
                    if (writer === createdWriter) writer = null
                    createdWriter?.stop()
                }
            } catch (t: Throwable) {
                // 换代或首次启动失败：必须复位 restarting，否则旧引擎的 onExit 会被永久吞掉
                restarting = false
                SelfLog.e("capture", "启动协程失败 target=${args.app.packageName}", t)
                if (session.get() == sid) {
                    _state.value = State.Failed("启动失败：${t.message ?: t.javaClass.simpleName}")
                }
                // 只清理本协程创建的对象，避免把新会话的 writer 一起关掉
                if (writer === createdWriter) writer = null
                createdWriter?.stop()
            }
        }
    }

    fun stop() {
        session.incrementAndGet()
        restarting = false
        engine?.stop()
        engine = null
        // FileLogWriter.stop() 内部会 join 写线程（最多 1.5s）；本方法从 UI / 服务命令
        // 调用时在主线程，不能同步等文件收尾，否则点「停止」会卡住界面。
        val toClose = writer
        writer = null
        setPassive(false)
        _paused.value = false
        lastArgs = null
        if (_state.value !is State.Failed) {
            _state.value = State.Idle
        }
        SelfLog.i("capture", "停止（含收尾写盘）fromState=${_state.value.javaClass.simpleName}")
        scope.launch { runCatching { toClose?.stop() } }
    }

    fun dismissFailure() {
        if (_state.value is State.Failed) _state.value = State.Idle
    }

    // ───────────────────────── 崩溃现场快照 ─────────────────────────

    private var lastSnapshotAt = 0L

    /**
     * 崩溃一发生就把「崩溃前 200 行 + 崩溃行」单独存一份。
     * 缓冲区本来会被后来的日志滚掉，这份快照是事后唯一还能翻到现场的东西。
     */
    private fun saveCrashSnapshot(ctx: Context, entry: LogEntry) {
        val prefs = runCatching { Prefs(ctx) }.getOrNull() ?: return
        if (!prefs.crashSnapshot) return
        val now = System.currentTimeMillis()
        // 同一波崩溃（SIGABRT 会连着刷十几行）只存一份
        if (now - lastSnapshotAt < 20_000L) return
        lastSnapshotAt = now

        // 外部目录可能拿不到（存储已卸载/未挂载）：崩溃快照退回内部目录，别让功能静默失效
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dir = java.io.File(base, "crash").apply { mkdirs() }
        runCatching {
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(now))
        val target = (state.value as? State.Capturing)?.target?.label ?: "未指定应用"
        val recent = AppGraph.logs.recent(200)
        val f = java.io.File(dir, "crash_$stamp.log")
        f.bufferedWriter().use { w ->
            w.write("===== 崩溃现场快照 $stamp =====")
            w.newLine()
            w.write("目标应用：$target")
            w.newLine()
            w.write("崩溃行：${entry.level.letter} ${entry.tag}: ${entry.message.take(200)}")
            w.newLine()
            w.write("—— 崩溃前最近 ${recent.size} 行 ——")
            w.newLine()
            recent.forEach { e ->
                val line = if (e.isContinuation) {
                    "    ${e.message}"
                } else {
                    "${e.time}  ${e.pid}  ${e.tid}  ${e.level.letter} ${e.tag}: ${e.message}"
                }
                w.write(line)
                w.newLine()
            }
        }
        _notice.value = "已自动保存崩溃现场：${f.name}"
    }
}

