package dev.loglens.logcat

import dev.loglens.data.LogBuffer
import dev.loglens.data.LogEntry
import dev.loglens.data.LogLevel
import dev.loglens.data.LogRepository
import dev.loglens.shizuku.ShellRunner
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 日志抓取引擎。
 *
 * 设计原则（按 logcat skill 规范）：
 * 1. **过滤尽量下沉到 logcat 层**：能拿到应用 uid 且设备接受 `--uid=` 时，由 logcat 服务端过滤
 *    （`logcat --uid=<uid>`，应用重启、多进程都自动覆盖，不依赖 tag 命名）；设备不支持或静默忽略
 *    时（荣耀/华为等魔改 logcat，探测见 ShellRunner.supportsUidFilter）自动退回客户端按 pid 过滤。
 *    服务端过滤生效时客户端仍按 pid 复核，双保险，避免被静默忽略后变成全量 dump。
 * 2. **绝不做 host 侧 grep**：过滤只发生在 logcat 参数或逐行 pid 比对，不拼接 grep/sh 管道，
 *    避免 shell 引号与 glob（`*:S` 之类）在 Shizuku 的 sh -c 下失效。
 * 3. **缓冲区分流**：main/system/crash 一条流；events 单独一条流（其记录由 system_server 写入，
 *    pid 不是目标应用，混进 pid 过滤会被全部丢掉）。
 * 4. **pid 集合定时刷新**：应用重启、新开子进程（多进程应用）后自动跟上。
 * 5. **任何内部异常都不吞掉**：统一以 tag=LogLens 的诊断条目写进日志列表，
 *    用户截图即可看到命令、进程号、退出码、stderr。
 * 6. **读取线程死掉 / 长时间无输出自动重启**，避免卡在「实时」状态假死。
 */
class LogcatEngine(private val listener: Listener) {

    interface Listener {
        fun onStarted(mode: String, command: String)
        fun onEntry(entry: LogEntry)
        fun onNotice(text: String)
        fun onFailure(message: String)
        fun onExit(code: Int)

        /** 判定为「系统在丢弃/加密日志」（荣耀华为 AP 日志未开）时回调，供界面弹引导 */
        fun onLogChannelBlocked(hint: String) {}
    }

    private val running = AtomicBoolean(false)
    private val scanned = AtomicLong(0)
    private val matched = AtomicLong(0)

    private var process: Process? = null
    private var outThread: Thread? = null
    private var errThread: Thread? = null
    private var pidThread: Thread? = null
    private var watchThread: Thread? = null

    /** events buffer 单独一条流：它的记录由 system_server 写，pid 不是目标应用，混在 pid 过滤里会被全部丢弃 */
    private var eventProcess: Process? = null
    private var eventThread: Thread? = null

    @Volatile
    private var eventRestart: Int = 0

    @Volatile
    private var generation = 0

    /** 设备 logcat 接受 `--uid=` 时走服务端过滤（客户端仍按 pid 复核，双保险） */
    @Volatile
    private var serverUidFilter: Boolean = false

    @Volatile
    private var pidSet: Set<Int> = emptySet()

    @Volatile
    private var stderrTail: String = ""

    @Volatile
    private var lastDataAt: Long = 0L

    @Volatile
    private var lastIdleWarnAt: Long = 0L

    @Volatile
    private var restartCount: Int = 0

    @Volatile
    private var pidLockedLogged: Boolean = false

    /** 日志通路是否被系统限制（荣耀/华为 AP 日志未开时的典型状态） */
    @Volatile
    private var apBlocked: Boolean = false

    @Volatile
    private var lastProbeAt: Long = 0L

    fun isLogChannelBlocked(): Boolean = apBlocked

    private var packageName: String = ""
    private var targetUid: Int = 0
    private var includeEvents: Boolean = true

    /** 用户选择的 logcat 缓冲区集合（events 仍单独起一条流） */
    private var buffers: Set<LogBuffer> = LogBuffer.DEFAULT

    /** 全量模式：不按 uid/pid 过滤，连 events 一起抓（含事件缓冲，等于整机镜像） */
    private var globalMode: Boolean = false

    fun isRunning(): Boolean = running.get()

    fun start(
        packageName: String,
        uid: Int?,
        pid: Int?,
        includeEvents: Boolean,
        globalMode: Boolean = false,
        buffers: Set<LogBuffer> = LogBuffer.DEFAULT
    ) {
        stop()

        this.packageName = packageName
        this.targetUid = uid ?: 0
        this.buffers = buffers.ifEmpty { LogBuffer.DEFAULT }
        this.includeEvents = includeEvents && LogBuffer.hasEvents(this.buffers)
        this.globalMode = globalMode
        this.restartCount = 0
        this.pidLockedLogged = false
        this.apBlocked = false
        this.lastProbeAt = 0L
        this.stderrTail = ""
        this.lastIdleWarnAt = 0L
        scanned.set(0)
        matched.set(0)

        val gen = ++generation
        running.set(true)

        // ① 先拿到目标进程集合（客户端过滤的唯一依据）
        val pids = runCatching { ShellRunner.pidsFor(packageName, targetUid) }.getOrDefault(emptyList())
        pidSet = pids.toSet()

        diag(
            "开始捕获 $packageName（uid=${if (targetUid > 0) targetUid.toString() else "未知"}）" +
                "，进程=${if (pids.isEmpty()) "未找到" else pids.toString()}"
        )

        // ② 全量模式直接跳过服务端过滤，抓整机镜像（判断问题在系统层还是应用层时用）
        if (globalMode) {
            serverUidFilter = false
            diag("全量模式：不按 uid/pid 过滤，抓 main+system+crash+events 全部日志")
        } else {
        // 服务端过滤优先：设备 logcat 支持 `--uid=` 时交给 logcat 自己筛（省掉整机日志的搬运与解析），
        //    不支持（荣耀/华为等魔改 logcat 常见）才退化为全量输出 + 客户端 pid 过滤。
        //    两种模式下客户端都会再按 pid 复核一遍，避免 ROM「接受参数但静默忽略」时把整机日志刷出来。
        serverUidFilter = targetUid > 0 && runCatching {
            ShellRunner.supportsUidFilter(targetUid)
        }.getOrDefault(false)
        diag(
            if (serverUidFilter) "logcat 支持 --uid 服务端过滤，按 uid=$targetUid 抓取（多进程与重启自动覆盖）"
            else "logcat 不支持 --uid 过滤，改为全量输出 + 客户端按 pid 过滤"
        )
        }

        // ③ 注意：这里绝不能调用 `logcat -G 16M`。
        //    -G 重新配置环形缓冲区会**清空全部历史日志**，导致刚点「开始捕获」时列表里
        //    只剩自己写的几条诊断，看上去像「系统不给日志」，实测踩过一次。
        //    实时场景有客户端 5000 条环形缓冲兜底，不需要靠改系统缓冲大小。

        // ④ 拉起 logcat（服务端能过滤时带 --uid，否则全量输出后由客户端筛）
        if (!spawn(gen)) return

        // ⑤ 通路自检：设备档案 → 「写入即读回」探针 → 荣耀/华为专属结论
        Thread({
            val profile = runCatching { DeviceLogGuard.probe() }.getOrNull()
            if (profile != null) {
                diag("设备档案：${profile.describe()}")
                if (profile.isHonorHuawei) diag(DeviceLogGuard.GUIDE)
            }

            val sample = runCatching {
                val probe = ShellRunner.exec("logcat -d -t 200 -v threadtime 2>&1", 12_000)
                (probe.stdout + probe.stderr).trim()
            }.getOrDefault("")
            val sampleLines = sample.lineSequence().count { it.isNotBlank() }
            diag(
                if (sample.isEmpty()) "自检：logcat -d 读不到任何内容"
                else "自检：logcat 可读（采样 $sampleLines 行）"
            )

            // 先确认 shell 通道本身有输出，再谈「日志通道是否被系统限制」。
            // 通道异常时绝不能报「AP 日志未开」，否则会把用户往工程菜单上白跑。
            val shellOk = runCatching { DeviceLogGuard.getprop("ro.product.brand").isNotBlank() }
                .getOrDefault(false)
            if (!shellOk) {
                diag("自检：shell 通道无回显，跳过日志通道判定（与 AP 日志开关无关）")
            } else {
                val test = runCatching { DeviceLogGuard.selfTest() }.getOrNull()
                if (test != null) {
                    diag("通路自检：${test.detail}")
                    if (!test.ok) markBlocked(profile?.isHonorHuawei == true)
                }
            }
            lastProbeAt = System.currentTimeMillis()
        }, "logcat-guard").apply { isDaemon = true; start() }

        if (pids.isEmpty()) {
            diag("目标应用当前没有运行中的进程，已开始监听，打开该应用后会自动接上")
        }

        // ⑥ 进程集合刷新（应用重启 / 多进程）
        pidThread = Thread({
            while (running.get() && generation == gen) {
                try {
                    Thread.sleep(4_000)
                } catch (t: InterruptedException) {
                    break
                }
                if (!running.get() || generation != gen) break
                val fresh = runCatching { ShellRunner.pidsFor(packageName, targetUid).toSet() }
                    .getOrDefault(emptySet())
                if (fresh != pidSet) {
                    pidSet = fresh
                    if (fresh.isNotEmpty()) {
                        if (!pidLockedLogged) {
                            pidLockedLogged = true
                            diag("已锁定目标进程 ${fresh.sorted()}，开始输出该应用的日志")
                        }
                    } else if (pidLockedLogged) {
                        diag("目标进程已退出，继续监听等待其重新启动")
                    }
                }
            }
        }, "logcat-pids").apply { isDaemon = true; start() }

        // ⑦ 看门狗：读取线程挂掉或长时间无输出时自愈
        watchThread = Thread({
            while (running.get() && generation == gen) {
                try {
                    Thread.sleep(5_000)
                } catch (t: InterruptedException) {
                    break
                }
                if (!running.get() || generation != gen) break

                val reader = outThread
                if (reader == null || !reader.isAlive) {
                    if (restartCount < 3) {
                        restartCount++
                        diag("读取线程已退出（重启 $restartCount/3）${stderrNote()}")
                        if (!spawn(gen)) return@Thread
                    } else {
                        diag("读取线程反复退出，停止重试${stderrNote()}")
                        running.set(false)
                        listener.onFailure(
                            "日志流反复中断：${stderrTail.trim().take(300).ifBlank { "未知原因" }}"
                        )
                        return@Thread
                    }
                    continue
                }

                // events 流单独看护：它挂掉不影响主日志，但不能静默失联
                if (includeEvents) {
                    val ev = eventThread
                    if (ev == null || !ev.isAlive) {
                        if (eventRestart < 3) {
                            eventRestart++
                            diag("events 读取线程已退出（重启 $eventRestart/3）")
                            runCatching { eventProcess?.destroy() }
                            eventProcess = null
                            spawnEvents(gen)
                        }
                    }
                }

                val now = System.currentTimeMillis()

                // 通路被限制时每 30s 复检：用户在工程菜单开完 AP 日志，本 App 会自动接上
                if (apBlocked && now - lastProbeAt > 30_000) {
                    lastProbeAt = now
                    val retest = runCatching { DeviceLogGuard.selfTest() }.getOrNull()
                    if (retest != null && retest.ok) {
                        apBlocked = false
                        lastDataAt = now
                        diag("日志通道已恢复（${retest.detail}），继续输出目标应用日志")
                        listener.onNotice("日志通道已恢复，继续输出日志")
                    }
                }

                if (lastDataAt > 0 && now - lastDataAt > 20_000 && now - lastIdleWarnAt > 20_000) {
                    lastIdleWarnAt = now
                    if (apBlocked && matched.get() == 0L) {
                        diag("日志通道被系统限制（AP 日志未开），打开后会自动恢复，无需重启本 App")
                    } else {
                        diag(
                            "已 ${(now - lastDataAt) / 1000} 秒没有任何日志输出（进程存活，" +
                                "已扫描 ${scanned.get()} 行 / 命中 ${matched.get()} 行）"
                        )
                    }
                }
            }
        }, "logcat-watch").apply { isDaemon = true; start() }
    }

    /** 拉起 logcat 进程及其读线程。返回 false 表示彻底失败。 */
    private fun spawn(gen: Int): Boolean {
        runCatching { process?.destroy() }
        process = null
        runCatching { eventProcess?.destroy() }
        eventProcess = null

        // events buffer 不并入主命令：它的记录由 system_server 写入，pid 永远是 1000 一类系统进程，
        // 与被采集应用无关。混在一起做 pid 过滤会把 am_* / wm_* 事件全部丢光（等于开关失效）。
        val args = buildList {
            add("logcat")
            add("-b")
            add(if (globalMode) "main,system,crash,events" else LogBuffer.mainSources(buffers))
            add("-v"); add("threadtime")
            if (serverUidFilter && targetUid > 0) add("--uid=$targetUid")
        }.toTypedArray()
        val command = args.joinToString(" ")

        val proc = runCatching { ShellRunner.spawn(args) }.getOrNull()
        if (proc == null) {
            running.set(false)
            listener.onFailure("启动 logcat 失败：Shizuku 拒绝创建进程，请确认服务在运行且已授权。")
            return false
        }

        process = proc
        stderrTail = ""
        lastDataAt = System.currentTimeMillis()

        listener.onStarted(
            when {
                globalMode -> "GLOBAL"
                serverUidFilter -> "UID"
                else -> "PID"
            },
            command
        )
        diag(
            "logcat 已启动：$command（" +
                when {
                    globalMode -> "全量，不做任何过滤）"
                    serverUidFilter -> "服务端按 uid 过滤 + 客户端 pid 复核）"
                    else -> "客户端按 pid 过滤）"
                }
        )

        outThread = Thread({ readStdout(proc, gen) }, "logcat-stdout").apply {
            isDaemon = true
            start()
        }
        errThread = Thread({ readStderr(proc) }, "logcat-stderr").apply {
            isDaemon = true
            start()
        }

        Thread({
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            if (!running.get() || generation != gen) return@Thread
            running.set(false)
            diag("logcat 进程已退出（exit=$code）${stderrNote()}")
            runCatching { listener.onExit(code) }
        }, "logcat-exit").apply { isDaemon = true; start() }

        if (includeEvents && !globalMode) spawnEvents(gen)

        return true
    }

    /** events buffer 单独一条流：只保留 payload 里出现目标包名的事件（am_proc_start / wm_* 等） */
    private fun spawnEvents(gen: Int) {
        val args = arrayOf("logcat", "-b", "events", "-v", "threadtime")
        val proc = runCatching { ShellRunner.spawn(args) }.getOrNull() ?: run {
            diag("events 缓冲读取失败：Shizuku 拒绝创建进程，已跳过该缓冲")
            return
        }
        eventProcess = proc
        eventThread = Thread({ readEvents(proc, gen) }, "logcat-events").apply {
            isDaemon = true
            start()
        }
        diag("events 缓冲已接入：${args.joinToString(" ")}（仅保留与目标包名相关的事件）")
    }

    private fun readEvents(proc: Process, gen: Int) {
        val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8), 16 * 1024)
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (!running.get() || generation != gen) break
                if (line.isEmpty()) continue
                // events 的 payload 形如 [0,4321,com.tencent.mm,u0a123,activity]，按包名筛即可
                if (!line.contains(packageName)) continue
                lastDataAt = System.currentTimeMillis()
                val entry = runCatching { LogcatParser.parse(line) { LogRepository.nextId() } }.getOrNull()
                if (entry != null) {
                    emit(entry.copy(tag = "evt/" + entry.tag))
                }
            }
        } catch (t: Throwable) {
            if (running.get() && generation == gen) {
                diag("events 流中断：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun readStdout(proc: Process, gen: Int) {
        val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8), 32 * 1024)
        var lastTime = ""
        var lastTag: String? = null
        var lastAccepted = false
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (!running.get() || generation != gen) break
                if (line.isEmpty()) continue

                lastDataAt = System.currentTimeMillis()
                scanned.incrementAndGet()

                // 单行解析失败绝不允许打断整条流
                try {
                    val pid = fastPid(line)
                    // 服务端已按 uid 过滤、且拿不到进程表时不能再按 pid 掐掉，否则会把日志全丢；
                    // 全量模式同理：整机镜像，pid 过滤一律不生效
                    val acceptAll = globalMode || (serverUidFilter && pidSet.isEmpty())
                    if (pid > 0) {
                        val accept = acceptAll || pidSet.contains(pid)
                        lastAccepted = accept
                        if (!accept) continue
                        val entry = LogcatParser.parse(line) { LogRepository.nextId() }
                        if (entry != null) {
                            lastTime = entry.time
                            lastTag = entry.tag
                            emit(entry)
                        } else {
                            // 解析失败也要把原始行吐出去，绝不吞日志
                            emit(
                                LogEntry(
                                    id = LogRepository.nextId(),
                                    time = line.take(18).trim(),
                                    pid = pid,
                                    tid = 0,
                                    level = LogLevel.RAW,
                                    tag = "",
                                    message = line,
                                    isCrash = LogcatParser.isCrash(line),
                                    isRaw = true
                                )
                            )
                        }
                        continue
                    }

                    // logcat 自己的分隔行
                    if (line.startsWith("---------") || line.startsWith("***")) {
                        lastAccepted = false
                        continue
                    }

                    val entry = LogcatParser.parse(line) { LogRepository.nextId() }
                    if (entry != null) {
                        if (entry.pid > 0 && pidSet.contains(entry.pid)) {
                            lastAccepted = true
                            lastTime = entry.time
                            lastTag = entry.tag
                            emit(entry)
                        } else {
                            lastAccepted = false
                        }
                    } else if (lastAccepted) {
                        // 续行：带上一行的时间 / tag
                        emit(
                            LogEntry(
                                id = LogRepository.nextId(),
                                time = lastTime,
                                pid = 0,
                                tid = 0,
                                level = LogLevel.RAW,
                                tag = lastTag ?: "",
                                message = line,
                                isCrash = LogcatParser.isCrash(line),
                                isContinuation = true
                            )
                        )
                    }
                } catch (t: Throwable) {
                    // 忽略单行异常
                }
            }
        } catch (t: Throwable) {
            if (running.get() && generation == gen) {
                diag("读取日志流中断：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun readStderr(proc: Process) {
        runCatching {
            BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8)).useLines { lines ->
                lines.forEach { stderrTail = (stderrTail + it + "\n").takeLast(1_500) }
            }
        }
    }

    private fun emit(entry: LogEntry) {
        matched.incrementAndGet()
        restartCount = 0
        listener.onEntry(entry)
    }

    /** 判定日志通路被封：把结论写进日志列表并通知界面给引导 */
    private fun markBlocked(honorHuawei: Boolean) {
        if (apBlocked) return
        apBlocked = true
        val hint = if (honorHuawei) {
            "检测到本机为荣耀/华为机型且日志通道被系统限制：请拨号输入 ${DeviceLogGuard.ENGINEER_CODE}" +
                " → 后台设置 → LOG设置 / AP LOG设置 → 打开 AP日志，然后重新开始捕获"
        } else {
            "日志通道被系统限制：写入的探针日志无法读回，系统正在丢弃或加密日志输出"
        }
        diag(hint)
        runCatching { listener.onLogChannelBlocked(hint) }
    }

    /** 把引擎内部状态以日志条目形式暴露到界面上，便于用户反馈问题 */
    private fun diag(text: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runCatching {
            listener.onEntry(
                LogEntry(
                    id = LogRepository.nextId(),
                    time = stamp,
                    pid = 0,
                    tid = 0,
                    level = LogLevel.INFO,
                    tag = "LogLens",
                    message = text
                )
            )
        }
    }

    private fun stderrNote(): String {
        val err = stderrTail.trim()
        return if (err.isEmpty()) "" else " · stderr: ${err.take(300)}"
    }

    /**
     * 取 threadtime 行首的 pid：跳过「日期 时间」两个字段后紧跟的就是 pid。
     * 兼容 2 位年份（10-27）与 4 位年份（2026-10-27）两种格式。
     */
    private fun fastPid(line: String): Int {
        val n = line.length
        var i = 0
        // 跳过日期
        while (i < n && line[i] != ' ') i++
        while (i < n && line[i] == ' ') i++
        // 跳过时间
        while (i < n && line[i] != ' ') i++
        while (i < n && line[i] == ' ') i++
        var value = 0
        var digits = 0
        while (i < n && line[i] in '0'..'9') {
            value = value * 10 + (line[i] - '0')
            i++
            digits++
        }
        return if (digits in 1..8) value else 0
    }

    fun stop() {
        running.set(false)
        generation++
        runCatching { process?.destroy() }
        process = null
        runCatching { eventProcess?.destroy() }
        eventProcess = null
        runCatching { pidThread?.interrupt() }
        runCatching { watchThread?.interrupt() }
        pidThread = null
        watchThread = null
        outThread = null
        errThread = null
        eventThread = null
        eventRestart = 0
    }
}
