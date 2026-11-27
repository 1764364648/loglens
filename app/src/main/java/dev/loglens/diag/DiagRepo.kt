package dev.loglens.diag

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import dev.loglens.shizuku.ShellRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 疑难场景取证：全部走 Shizuku 的 shell（uid 2000）通道，每项独立执行，单项失败不影响其它项。
 *
 * 覆盖能力：
 *  1. 目标应用档案（版本号 / 安装时间 / codePath / debuggable）—— 判断「新版代码到底有没有装进去」
 *  2. 进程表 + 进程启动时间（/proc/<pid>/stat 第 22 字段 + /proc/uptime）—— 判断「跑的是不是新进程」
 *  3. ApplicationExitInfo（dumpsys activity exit-info）—— 137（128+SIGKILL）的创建到失效时间轴
 *  4. events buffer 时间轴（am_proc_start / am_kill / am_proc_died / am_anr）—— 进程生命周期与被谁杀
 *  5. crash buffer 现场（native crash / FATAL / tombstone 头部）
 *  5b. dropbox 崩溃存档（dumpsys dropbox --print data_app_crash / data_app_anr 等）—— logcat 滚掉后仍在
 *  6. binder 相关告警聚类 + dumpsys binder_calls_stats（shell 视角）
 *  7. 应用私有目录（/sdcard/Android/data/<pkg> 与 run-as 内部 files/、cache/）—— 找 server 端 logToFile 落点
 *
 * 明确拿不到：server 进程的标准输出（app_process 启动且未重定向时只有运行中 logcat 可见）、
 * 需要 root 的内核节点（binder 内核级统计细节、tombstone 全量内容）。
 */
object DiagRepo {

    data class Pkg(
        val pkg: String,
        val label: String,
        val versionName: String,
        val versionCode: Long,
        val lastUpdateMs: Long,
        val codePath: String,
        val dataDir: String,
        val debuggable: Boolean,
        val uid: Int,
        val targetSdk: Int
    )

    data class Proc(val pid: Int, val name: String, val startMs: Long)

    /** level: 0 普通 / 1 启动 / 2 终止 / 3 异常 */
    data class Mark(val at: Long?, val tag: String, val text: String, val level: Int)

    data class Exit(val at: Long?, val process: String, val reason: String, val status: Int, val raw: String) {
        /** 137 = 128 + SIGKILL(9)：进程被 -9 强杀 */
        val is137: Boolean get() = reason.contains("SIGNALED") && status == 9
        val exitCodeText: String get() = if (reason.contains("SIGNALED") && status > 0) "exit=${128 + status}" else "exit=$status"
    }

    data class FileRow(val path: String, val name: String, val size: Long, val time: String, val isDir: Boolean)

    data class DirListing(val title: String, val base: String, val rows: List<FileRow>, val raw: String)

    data class Binder(val tag: String, val count: Int, val sample: String)

    // ───────────────────────── 1. 应用档案 ─────────────────────────

    fun pkgInfo(context: Context, pkg: String): Pkg {
        var last: Throwable? = null
        // 刚装完/刚重装时 PackageManager 的扫描缓存可能还没刷新，重试几次并把真实异常抛给上层
        repeat(5) { i ->
            try {
                return readPkg(context.packageManager, pkg)
            } catch (t: Throwable) {
                last = t
                if (i < 4) runCatching { Thread.sleep(400) }
            }
        }
        throw last ?: IllegalStateException("getPackageInfo($pkg) 返回 null")
    }

    private fun readPkg(pm: android.content.pm.PackageManager, pkg: String): Pkg {
        val ai = pm.getApplicationInfo(pkg, 0)
        val pi = pm.getPackageInfo(pkg, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
        return Pkg(
            pkg = pkg,
            label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg),
            versionName = pi.versionName ?: "?",
            versionCode = code,
            lastUpdateMs = pi.lastUpdateTime,
            codePath = ai.sourceDir ?: "?",
            dataDir = ai.dataDir ?: "?",
            debuggable = (ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            uid = ai.uid,
            targetSdk = ai.targetSdkVersion
        )
    }

    // ───────────────────────── 2. 进程与启动时间 ─────────────────────────

    /** ps -A -o PID,UID,ARGS：拿 uid / 名称做双条件匹配，比 pidof 更能覆盖 :remote 子进程 */
    private fun psRows(): List<Triple<Int, Int, String>> {
        val r = ShellRunner.exec("ps -A -o PID,UID,ARGS 2>/dev/null", 12_000)
        val out = ArrayList<Triple<Int, Int, String>>()
        r.stdout.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("PID")) return@forEach
            val parts = t.split(Regex("\\s+"), 3)
            if (parts.size < 3) return@forEach
            val pid = parts[0].toIntOrNull() ?: return@forEach
            val uid = parts[1].toIntOrNull() ?: return@forEach
            out.add(Triple(pid, uid, parts[2].trim()))
        }
        return out
    }

    private fun bootMs(): Long? {
        val r = ShellRunner.exec("cat /proc/uptime; date +%s", 8_000)
        val lines = r.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val up = lines.getOrNull(0)?.substringBefore(' ')?.toDoubleOrNull() ?: return null
        val nowSec = lines.getOrNull(1)?.toLongOrNull() ?: (System.currentTimeMillis() / 1000)
        return nowSec * 1000L - (up * 1000).toLong()
    }

    /** /proc/<pid>/stat 的 comm 可能含空格与括号，必须从最后一个 ')' 往后切 */
    private fun startTicks(stat: String): Long? {
        val close = stat.lastIndexOf(')')
        if (close < 0 || close + 2 >= stat.length) return null
        val rest = stat.substring(close + 2).trim().split(Regex("\\s+"))
        // rest[0] = state（字段 3），starttime 是字段 22 → 下标 19
        if (rest.size < 20) return null
        return rest[19].toLongOrNull()
    }

    fun processes(pkg: String, uid: Int): List<Proc> {
        val rows = psRows().filter { (_, u, name) ->
            (uid > 0 && u == uid) || name == pkg || name.startsWith("$pkg:")
        }
        if (rows.isEmpty()) return emptyList()
        val boot = bootMs()
        val pids = rows.map { it.first }
        val cmd = "for p in " + pids.joinToString(" ") +
            "; do echo \"@@\$p\"; cat /proc/\$p/stat 2>/dev/null; done"
        val r = ShellRunner.exec(cmd, 12_000)
        val starts = HashMap<Int, Long>()
        var cur = -1
        r.stdout.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.startsWith("@@")) {
                cur = t.removePrefix("@@").toIntOrNull() ?: -1
                return@forEach
            }
            if (cur > 0 && t.isNotEmpty()) {
                val ticks = startTicks(t)
                if (ticks != null && boot != null) starts[cur] = boot + ticks * 10L
            }
        }
        return rows.map { Proc(it.first, it.third, starts[it.first] ?: 0L) }
    }

    // ───────────────────────── 3. ApplicationExitInfo（137 时间轴） ─────────────────────────

    fun exitInfo(pkg: String): Pair<List<Exit>, String> {
        val r = ShellRunner.exec("dumpsys activity exit-info $pkg 2>&1", 25_000)
        val text = (r.stdout + r.stderr).trim()
        val out = ArrayList<Exit>()
        // Android 17（API 37）的 dumpsys 输出是多行缩进块：
        //   ApplicationExitInfo #0:
        //     timestamp=2026-11-21 20:54:36.186 pid=18514 ...
        //     process=... reason=16 (PACKAGE UPDATED) subreason=0 (UNKNOWN) status=0
        //     importance=... description=... anrInfo=null
        // 老版本是单行 ApplicationExitInfo{...}。只按「行内含 ApplicationExitInfo」匹配，
        // 在 API 37 上只能命中块首那一行（只有序号），字段全空 → 时间未知 + REASON_0。
        // 因此先按块聚合，再把块压成单行后统一解析。
        val blocks = ArrayList<String>()
        val cur = StringBuilder()
        val head = Regex("^(ApplicationExitInfo\\b|#\\d+:|\\d+:\\s)")
        text.lineSequence().forEach { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> if (cur.isNotEmpty()) {
                    blocks.add(cur.toString())
                    cur.setLength(0)
                }

                head.containsMatchIn(t) -> {
                    if (cur.isNotEmpty()) blocks.add(cur.toString())
                    cur.setLength(0)
                    cur.append(t)
                }

                cur.isNotEmpty() -> cur.append(' ').append(t)
            }
        }
        if (cur.isNotEmpty()) blocks.add(cur.toString())
        blocks.map { it.replace(Regex("\\s+"), " ").trim() }.forEach { raw ->
            val proc = Regex("process=([^\\s]+)").find(raw)?.groupValues?.get(1) ?: "?"
            val pid = Regex("pid=(-?\\d+)").find(raw)?.groupValues?.get(1)
            val reasonNum = Regex("reason=(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val reasonName = Regex("reason=\\d+\\s*\\(([^)]*)\\)").find(raw)?.groupValues?.get(1)?.trim()
                ?: reasonName(reasonNum)
            val status = Regex("status=(-?\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val ts = Regex("timestamp=(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?)")
                .find(raw)?.groupValues?.get(1)
            val desc = Regex("description=(.*?)\\s+anrInfo=").find(raw)?.groupValues?.get(1)?.trim()
                ?: Regex("description=([^,}]*)").find(raw)?.groupValues?.get(1)?.trim()
            out.add(
                Exit(
                    at = parseFullTime(ts),
                    process = proc + (pid?.let { " (pid=$it)" } ?: "") + (desc?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    reason = reasonName,
                    status = status,
                    raw = raw.trim()
                )
            )
        }
        return out.takeLast(40) to text
    }

    private fun reasonName(code: Int): String = when (code) {
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        else -> "REASON_$code"
    }

    // ───────────────────────── 4. events 时间轴 ─────────────────────────

    fun timeline(pkg: String, limit: Int = 8000): List<Mark> {
        val r = ShellRunner.exec("logcat -b events -d -v threadtime -t $limit 2>&1", 25_000)
        val out = ArrayList<Mark>()
        r.stdout.lineSequence().forEach { line ->
            if (!line.contains(pkg)) return@forEach
            val m = EVENT_LINE.find(line.trim()) ?: return@forEach
            val at = parseLcTime(m.groupValues[1])
            val tag = m.groupValues[2].trim()
            val payload = m.groupValues[3].trim()
            out.add(Mark(at, tag, describeEvent(tag, payload), levelOf(tag)))
        }
        return out.takeLast(300)
    }

    private val EVENT_LINE = Regex(
        "^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+([^:]+):\\s?(.*)$"
    )

    private fun fields(payload: String): List<String> =
        payload.trim().removePrefix("[").removeSuffix("]").split(",").map { it.trim() }

    private fun describeEvent(tag: String, payload: String): String {
        val f = fields(payload)
        return when (tag) {
            "am_proc_start" -> "进程启动 pid=${f.getOrNull(1) ?: "?"} 类型=${f.getOrNull(3) ?: "?"} 组件=${f.getOrNull(4) ?: "?"}"
            "am_kill" -> "被 kill pid=${f.getOrNull(1) ?: "?"} oomAdj=${f.getOrNull(3) ?: "?"} 原因=${f.getOrNull(4) ?: "?"}"
            "am_proc_died" -> "进程死亡 pid=${f.getOrNull(1) ?: "?"} 状态=${f.getOrNull(3) ?: "?"}"
            "am_anr" -> "ANR pid=${f.getOrNull(1) ?: "?"} ${f.getOrNull(4) ?: ""} ${f.getOrNull(5) ?: ""}".trim()
            "am_crash" -> "Java 崩溃 pid=${f.getOrNull(1) ?: "?"} ${f.getOrNull(4) ?: ""} ${f.getOrNull(5) ?: ""}".trim()
            "am_low_memory" -> "内存低水位，系统开始回收 存活进程=${f.getOrNull(0) ?: "?"}"
            "am_restart_activity" -> "Activity 重启 ${f.getOrNull(2) ?: ""}"
            else -> "$tag $payload"
        }
    }

    private fun levelOf(tag: String): Int = when (tag) {
        "am_proc_start" -> 1
        "am_kill", "am_proc_died" -> 2
        "am_anr", "am_crash", "am_low_memory" -> 3
        else -> 0
    }

    // ───────────────────────── 5. crash 现场 ─────────────────────────

    fun crashTail(pkg: String, limit: Int = 5000): String {
        val r = ShellRunner.exec("logcat -b crash -d -v threadtime -t $limit 2>&1", 25_000)
        val all = (r.stdout + r.stderr).trim()
        if (all.isEmpty()) return "crash 缓冲为空（可能未配置或已被清空）"
        val lines = all.lines()
        val keep = HashSet<Int>()
        lines.forEachIndexed { i, l ->
            val hit = l.contains(pkg) ||
                l.contains("Fatal signal") ||
                l.contains("*** *** ***") ||
                l.contains("FATAL EXCEPTION") ||
                l.contains("backtrace:")
            if (hit) {
                val from = (i - 4).coerceAtLeast(0)
                val to = (i + 22).coerceAtMost(lines.lastIndex)
                for (j in from..to) keep.add(j)
            }
        }
        if (keep.isEmpty()) {
            return "crash 缓冲里没有与 $pkg 相关的现场。\n" +
                "若是 137（SIGKILL）死亡，本来就不会有 Java/native 栈——请看「137 / 进程终止」与「事件时间轴」两节。"
        }
        return keep.sorted().joinToString("\n") { lines[it] }
    }

    // ───────────────────────── 5b. dropbox 崩溃存档 ─────────────────────────

    /** 系统在崩溃那刻自己抄的一份（/data/system/dropbox）。logcat 被后续日志滚掉之后，这里还在。 */
    data class Crash(val at: Long?, val tag: String, val summary: String, val body: String) {
        val kind: String
            get() = when (tag) {
                "data_app_crash" -> "Java 崩溃"
                "data_app_native_crash" -> "native 崩溃"
                "data_app_anr" -> "ANR"
                "system_app_crash" -> "系统应用崩溃"
                else -> tag
            }
    }

    /**
     * 按包名筛 dropbox 里的崩溃存档。
     * 全部走 `dumpsys dropbox --print <tag>`：user 版机器不允许 adb pull /data/system/dropbox，
     * 但 shell 有 DUMP 权限，`--print` 能把它解压打印出来，所以不需要 root。
     * 输出按时间升序，取尾部 5000 行（dropbox 上限 1000 条，全量拉回可能十几万行）。
     */
    fun crashArchive(pkg: String): Pair<List<Crash>, String> {
        val tags = listOf("data_app_crash", "data_app_native_crash", "data_app_anr", "system_app_crash")
        val out = ArrayList<Crash>()
        val sb = StringBuilder()
        tags.forEach { tag ->
            val r = ShellRunner.exec("dumpsys dropbox --print $tag 2>&1 | tail -n 5000", 30_000)
            val text = (r.stdout + r.stderr).trim()
            sb.append("## ").append(tag).append('\n').append(text).append("\n\n")
            out.addAll(parseDropbox(text, pkg))
        }
        return out.sortedBy { it.at ?: 0L }.takeLast(30) to sb.toString()
    }

    /** dropbox 块里的固定字段行，用来跳过它们去找摘要（异常那行 / Abort message） */
    private val DROPBOX_META = Regex(
        "^(SystemUptimeMs|Process|PID|UID|Frozen|Timestamp|Flags|Package|Foreground|Process-Runtime|" +
            "Activity|ErrorId|Build|DefaultScreen-State|Keyguard-Locked|Crash-Handler|Loading-Progress|" +
            "Dropped-Count|Subject|Reason|Parent|Board|Brand|Device|Display|Fingerprint|Hardware|Host|" +
            "Kernel|Manufacturer|Model|Product|Radio|Revision|Security-Patch-Level|Serial|Type|Version|User):"
    )

    private fun parseDropbox(text: String, pkg: String): List<Crash> {
        val out = ArrayList<Crash>()
        var header: String? = null
        val body = StringBuilder()

        fun flush() {
            val h = header ?: return
            val raw = body.toString().trim()
            header = null
            body.setLength(0)
            val m = Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s+(\\S+)").find(h) ?: return
            val tag = m.groupValues[2]
            val pkgField = Regex("(?m)^Package: ([^\\s]+)").find(raw)?.groupValues?.get(1)
            val procField = Regex("(?m)^Process: ([^\\s]+)").find(raw)?.groupValues?.get(1)
            val hit = when {
                pkgField != null -> pkgField == pkg
                procField != null -> procField == pkg || procField.startsWith("$pkg:")
                else -> raw.contains(pkg)
            }
            if (!hit) return
            val ts = Regex("(?m)^Timestamp: (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\.(\\d+)")
                .find(raw)?.groupValues?.get(1) ?: m.groupValues[1]
            val summary = crashSummary(raw)
            out.add(Crash(parseFullTime(ts), tag, summary, raw.take(24_000)))
        }

        text.lineSequence().forEach { line ->
            val t = line.trim()
            when {
                t.length >= 8 && t.all { it == '=' } -> flush()
                header == null && t.matches(Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} .+")) -> header = t
                header != null -> body.append(t).append('\n')
            }
        }
        flush()
        return out
    }

    /**
     * 一行摘要。不同标签的块结构完全不同：
     *  - Java 崩溃：正文第一行就是异常（`java.lang.Xxx: ...`）
     *  - native 崩溃：开头是一串 `***` 横幅，真正的因在 `Abort message:` / `signal 6 (SIGABRT)`
     *  - ANR：开头是一堆 /proc/pressure 观测，正文里通常只有 `Subject:`/`Reason:`，否则只能靠 Activity + ErrorId
     */
    private fun crashSummary(raw: String): String {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        val priority = listOf(
            Regex("^Abort message:"),
            Regex("^signal \\d+"),
            Regex("^Subject:"),
            Regex("^Reason:"),
            Regex("^(FATAL EXCEPTION|java\\.|android\\.|kotlin\\.|com\\.|org\\.).*(Exception|Error)")
        )
        priority.forEach { p -> lines.firstOrNull { p.containsMatchIn(it) }?.let { return it } }
        val activity = Regex("(?m)^Activity: (\\S+)").find(raw)?.groupValues?.get(1)
        val errorId = Regex("(?m)^ErrorId: (\\S+)").find(raw)?.groupValues?.get(1)
        if (activity != null) {
            return "ANR / 卡死：$activity" + (errorId?.let { "  · errorId=$it" } ?: "")
        }
        return lines.firstOrNull {
            !DROPBOX_META.containsMatchIn(it) &&
                !it.startsWith("***") && !it.startsWith("-----") && !it.startsWith("#")
        } ?: "（没有摘要行）"
    }

    // ───────────────────────── 6. binder 压力 ─────────────────────────

    fun binderStats(onlyPid: Int? = null): Pair<List<Binder>, String> {
        val r = ShellRunner.exec("logcat -b main,system -d -v threadtime -t 15000 2>&1", 30_000)
        val lines = r.stdout.lineSequence().mapNotNull { line ->
            val t = line.trim()
            val m = MAIN_LINE.find(t) ?: return@mapNotNull null
            val tag = m.groupValues[2].trim()
            val msg = m.groupValues[3]
            if (!tag.contains("binder", true) && !msg.contains("binder", true)) return@mapNotNull null
            val pid = PID_OF.find(t)?.groupValues?.get(1)?.toIntOrNull()
            Triple(pid, tag, "${m.groupValues[1]}  $tag: ${msg.take(160)}")
        }.toList()
        // 优先只统计目标进程自己的 binder 行，拿不到再回退全量
        val scoped = if (onlyPid != null && onlyPid > 0) {
            lines.filter { it.first == onlyPid }
        } else {
            emptyList()
        }
        val picked = if (scoped.isNotEmpty()) scoped else lines
        val counts = LinkedHashMap<String, Int>()
        val samples = HashMap<String, String>()
        picked.forEach { row ->
            counts[row.second] = (counts[row.second] ?: 0) + 1
            samples.putIfAbsent(row.second, row.third)
        }
        val top = counts.entries.sortedByDescending { it.value }.take(12).map {
            Binder(it.key, it.value, samples[it.key] ?: "")
        }
        val d = ShellRunner.exec("dumpsys binder_calls_stats 2>&1 | head -n 60", 20_000)
        return top to (d.stdout + d.stderr).trim()
    }

    private val MAIN_LINE = Regex(
        "^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+([^:]+):\\s?(.*)$"
    )

    /** 只取 threadtime 行里的 pid，用于把 binder 聚类收敛到目标进程 */
    private val PID_OF = Regex(
        "^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+(\\d+)\\s+\\d+\\s+[VDIWEF]\\s+"
    )

    // ───────────────────────── 7. 应用私有目录 ─────────────────────────

    fun appFiles(pkg: String): List<DirListing> {
        val ext = "/sdcard/Android/data/$pkg"
        val sections = listOf(
            Triple("shell · $ext/files", "$ext/files", "ls -l $ext/files 2>&1"),
            Triple("shell · $ext/cache", "$ext/cache", "ls -l $ext/cache 2>&1"),
            Triple("run-as · files/", "/data/data/$pkg/files", "run-as $pkg ls -l files 2>&1"),
            Triple("run-as · cache/", "/data/data/$pkg/cache", "run-as $pkg ls -l cache 2>&1"),
            Triple(
                "递归找日志（深度 6）",
                ext,
                "find $ext -maxdepth 6 -type f 2>/dev/null | head -n 80"
            )
        )
        return sections.map { (title, base, cmd) ->
            val r = ShellRunner.exec(cmd, 20_000)
            val raw = (r.stdout + r.stderr).trim()
            DirListing(title, base, parseLs(raw, base), raw)
        }
    }

    private val LS_LINE = Regex(
        "^([-dl][rwxsStT-]{9})\\s+\\d+\\s+(\\S+)\\s+(\\S+)\\s+(\\d+)\\s+(\\S+\\s+\\S+)\\s+(.+)$"
    )

    private fun parseLs(raw: String, base: String): List<FileRow> {
        val out = ArrayList<FileRow>()
        raw.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty()) return@forEach
            val m = LS_LINE.find(t)
            if (m == null) {
                // find 的输出只有路径
                if (t.startsWith("/")) {
                    val name = t.substringAfterLast('/')
                    out.add(FileRow(t, name, -1L, "", false))
                }
                return@forEach
            }
            val perms = m.groupValues[1]
            val size = m.groupValues[4].toLongOrNull() ?: -1L
            val time = m.groupValues[5]
            val name = m.groupValues[6].trim()
            if (name == "." || name == "..") return@forEach
            out.add(FileRow("$base/$name", name, size, time, perms.startsWith("d")))
        }
        return out
    }

    /**
     * 只读预览：head 截断，避免把几百 MB 日志读进内存。
     * /data/data/<pkg> 下的文件只有应用自己可读，必须借 run-as 才能取（要求目标 debuggable）。
     */
    fun readHead(path: String, pkg: String, maxBytes: Int = 180_000): String {
        val safe = path.replace("'", "'\\''")
        val internalPrefix = "/data/data/$pkg/"
        val cmd = if (path.startsWith(internalPrefix)) {
            val rel = path.removePrefix(internalPrefix).replace("'", "'\\''")
            "run-as $pkg head -c $maxBytes '$rel' 2>&1"
        } else {
            "head -c $maxBytes '$safe' 2>&1"
        }
        val r = ShellRunner.exec(cmd, 25_000)
        return (r.stdout + r.stderr)
    }

    // ───────────────────────── 时间工具 ─────────────────────────

    private fun parseFullTime(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val cleaned = text.trim().let { if (it.length == 16) "$it.000" else it }
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).parse(cleaned)?.time
        }.getOrNull()
    }

    private fun parseLcTime(text: String): Long? = runCatching {
        val year = SimpleDateFormat("yyyy", Locale.US).format(Date())
        val parsed = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .parse("$year-${text.trim()}") ?: return@runCatching null
        // logcat 的 MM-dd 无年份；跨年时按当前年补全会把去年 12 月算成未来时间，回退一年
        if (parsed.time > System.currentTimeMillis() + 86_400_000L) {
            java.util.Calendar.getInstance().apply {
                timeInMillis = parsed.time
                add(java.util.Calendar.YEAR, -1)
            }.timeInMillis
        } else {
            parsed.time
        }
    }.getOrNull()

    fun fmt(ms: Long?): String =
        if (ms == null || ms <= 0) "时间未知"
        else SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    fun fmtFull(ms: Long?): String =
        if (ms == null || ms <= 0) "时间未知"
        else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    fun ago(ms: Long?): String {
        if (ms == null || ms <= 0) return ""
        val d = System.currentTimeMillis() - ms
        if (d < 0) return ""
        val m = d / 60_000
        return when {
            m < 1 -> "刚刚"
            m < 60 -> "${m} 分钟前"
            m < 60 * 24 -> "${m / 60} 小时前"
            else -> "${m / (60 * 24)} 天前"
        }
    }
}
