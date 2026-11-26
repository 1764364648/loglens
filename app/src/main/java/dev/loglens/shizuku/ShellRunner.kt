package dev.loglens.shizuku

import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 以 Shizuku 提供的 shell 身份执行一次性命令。
 */
object ShellRunner {

    data class Result(val code: Int, val stdout: String, val stderr: String)

    /**
     * 一次性命令。
     *
     * 关键修复（2026-10-27）：Shizuku 的 ShizukuRemoteProcess 在命令**毫秒级退出**时，
     * stdout 管道会在读线程真正调度起来之前就失效，读取抛 Bad file descriptor 被吞掉，
     * 结果是 `getprop` / `ps` / `logcat -d` / `logcat -g` 这类短命令**全部读到空串**。
     * 实测对照：耗时 1~3 秒的 `pm list packages` 输出正常，其余短命令全空；
     * 由此曾误判成「荣耀 AP 日志未开、系统在丢弃日志」，把用户往工程菜单上引，方向完全跑偏。
     *
     * 修法两条并用：
     * 1. 命令尾部挂 `sleep 0.6` 保活，保证进程存活到读线程读完（退出码原样透传）；
     * 2. 不先 waitFor，而是**以读线程读到 EOF 为准**，读完再取退出码，避免提前 destroy 把管道一起关掉。
     */
    fun exec(command: String, timeoutMs: Long = 12_000L): Result {
        val wrapped = "{ $command ; } ; __sl_rc=\$? ; sleep 0.6 ; exit \$__sl_rc"
        val process = ShizukuProcess.newProcess(arrayOf("sh", "-c", wrapped), null, null)
            ?: return Result(-1, "", "无法通过 Shizuku 创建进程")
        val out = StringBuilder()
        val err = StringBuilder()
        val outDone = CountDownLatch(1)
        val errDone = CountDownLatch(1)

        val outThread = Thread {
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { out.append(it).append('\n') }
                }
            } catch (t: Throwable) {
                // 管道已关闭是正常收尾，不吞真实数据以外的异常
            } finally {
                outDone.countDown()
            }
        }.apply { isDaemon = true; name = "cmd-stdout" }

        val errThread = Thread {
            try {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { err.append(it).append('\n') }
                }
            } catch (t: Throwable) {
                // 同上
            } finally {
                errDone.countDown()
            }
        }.apply { isDaemon = true; name = "cmd-stderr" }

        outThread.start()
        errThread.start()

        var code = -1
        try {
            if (outDone.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                if (process.waitFor(2, TimeUnit.SECONDS)) code = process.exitValue()
            } else {
                runCatching { process.destroy() }
            }
        } catch (t: Throwable) {
            runCatching { process.destroy() }
        }

        runCatching { errDone.await(1, TimeUnit.SECONDS) }

        return Result(code, out.toString(), err.toString())
    }

    /** 直接拉起一个长驻子进程（stdout 由调用方读取） */
    fun spawn(args: Array<String>): Process? = ShizukuProcess.newProcess(args, null, null)

    /** 解析 `pm list packages -U` 得到 包名 -> uid */
    fun listPackageUids(): Map<String, Int> {
        val result = exec("pm list packages -U --user 0", 20_000)
        if (result.stdout.isEmpty()) return emptyMap()
        val map = HashMap<String, Int>(512)
        result.stdout.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("package:")) return@forEach
            val body = trimmed.removePrefix("package:")
            val parts = body.split(' ')
            val pkg = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
            val uidPart = parts.firstOrNull { it.startsWith("uid:") } ?: return@forEach
            uidPart.removePrefix("uid:").toIntOrNull()?.let { map[pkg] = it }
        }
        return map
    }

    /** 单包 uid，失败返回 null */
    fun uidOf(packageName: String): Int? {
        val r = exec("dumpsys package $packageName | grep -m 1 userId=", 10_000)
        val m = Regex("userId=(\\d+)").find(r.stdout)
        return m?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * 目标应用主进程 pid。按 skill 的取法用 `pidof -s`（-s 只取一个 pid，避免多开时拿到一串
     * 无法区分主从进程的 pid）；它只认主进程名，`包名:子进程` 取不到，所以仅作兜底，
     * 正式路径走 [pidsFor] 的 ps 表解析。
     */
    fun pidsOf(packageName: String): List<Int> {
        val r = exec("pidof -s $packageName", 6_000)
        val text = r.stdout.ifBlank { r.stderr }
        return text.split(' ', '\n')
            .mapNotNull { it.trim().toIntOrNull() }
            .distinct()
    }

    private val WHITESPACE = Regex("\\s+")
    private val PS_UID_NAME = Regex("^u(\\d+)_a(\\d+)$")

    /** 依次尝试能从 ps 拿到「完整进程名」的列组合，`NAME` 列会被 toybox 截断，故 ARGS 优先 */
    private fun psTable(): String {
        for (cmd in listOf("ps -A -o PID,UID,ARGS", "ps -A -o PID,UID,NAME", "ps -A")) {
            val text = exec(cmd, 8_000).stdout
            if (text.lineSequence().any { it.contains("PID") }) return text
        }
        return ""
    }

    /**
     * ps 的 UID 列可能给数字，也可能给用户名（u0_a123）。用户名换算式：
     * uid = userId * 100000 + 10000 + appId。
     */
    private fun parseUidColumn(text: String): Int? {
        text.toIntOrNull()?.let { return it }
        val m = PS_UID_NAME.find(text) ?: return null
        val user = m.groupValues[1].toIntOrNull() ?: return null
        val appId = m.groupValues[2].toIntOrNull() ?: return null
        return user * 100_000 + 10_000 + appId
    }

    /** 进程名匹配：`包名` 或 `包名:子进程`；ARGS 可能带路径与参数，先取首段再去目录 */
    private fun matchesProcessName(name: String, packageName: String): Boolean {
        val head = name.substringBefore(' ').substringAfterLast('/')
        return head == packageName || head.startsWith("$packageName:")
    }

    /**
     * 目标应用当前所有进程 pid，兼容多进程应用（微信、QQ 等）。
     *
     * 命中条件：进程名精确/前缀匹配，或 UID 与目标一致（同一 uid 的子进程全收）。
     * 名称来源优先 ARGS（不被截断），避免 `com.tencent.mm:push` 被 `NAME` 列截成 `com.tencent.mm:` 后漏判。
     */
    fun pidsFor(packageName: String, uid: Int): List<Int> {
        val out = LinkedHashSet<Int>()
        val text = psTable()

        for (raw in text.lineSequence()) {
            val t = raw.trim().split(WHITESPACE)
            if (t.size < 2) continue

            val first = t[0].toIntOrNull()
            val pid: Int
            var uidCol: Int? = null
            val nameText: String
            if (first != null) {
                // 期望格式：PID UID NAME/ARGS
                pid = first
                uidCol = parseUidColumn(t.getOrNull(1).orEmpty())
                nameText = t.drop(2).joinToString(" ")
            } else {
                // 兜底格式：USER PID PPID ... NAME
                pid = t[1].toIntOrNull() ?: continue
                uidCol = parseUidColumn(t[0])
                nameText = t.last()
            }
            if (pid <= 1) continue

            val nameHit = nameText.isNotBlank() && matchesProcessName(nameText, packageName)
            val uidHit = uidCol != null && uid > 0 && uidCol == uid
            if (nameHit || uidHit) out.add(pid)
        }

        if (out.isEmpty()) out.addAll(pidsOf(packageName))
        return out.toList()
    }

    /**
     * 探测设备 logcat 是否支持服务端 `--uid` 过滤。
     *
     * 只判断「选项是否被接受」，不做静默忽略判定：即使 ROM 接受参数却不生效，
     * 客户端仍会再按 pid 过滤一遍，等于双保险，不会把整机日志刷出来。
     */
    fun supportsUidFilter(uid: Int): Boolean {
        if (uid <= 0) return false
        val r = exec("logcat -d --uid=$uid -t 1 2>&1", 15_000)
        val text = (r.stdout + "\n" + r.stderr).lowercase()
        if (text.isBlank()) return false
        val rejected = listOf(
            "unknown option", "unrecognized option", "invalid option",
            "unexpected argument", "usage: logcat", "not supported"
        )
        return rejected.none { text.contains(it) }
    }
}
