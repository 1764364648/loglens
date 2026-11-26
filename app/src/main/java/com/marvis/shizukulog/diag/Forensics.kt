package com.marvis.shizukulog.diag

import android.content.Context
import android.os.Build
import com.marvis.shizukulog.shizuku.ShellRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 服务端日志取证（一键）。
 *
 * 场景固定为「屏幕采集服务（Shower / 虚拟显示）在无 binder 客户端后自行退出，
 * 导致宿主进程被 137 杀掉」这一类问题：
 *
 * 1. 看 server 日志是否存在、多大、多少行（确认 shell 身份能否读到 /data/local/tmp 下的文件）；
 * 2. 按关键句抽尾部 300 行事件序列（server started / Created virtual display /
 *    Registered DisplaySession / Releasing active session / releaseDisplay /
 *    No active Binder clients / Failed to init InputController / setDisplayCapture）；
 * 3. 按 tag 抽 main buffer 尾部 800 行（过滤下沉到 logcat，不做主机侧 grep 全量）；
 * 4. 兜底把 server 日志尾部 2 万行带回来（避免几十万行直接灌进 binder 管道）；
 * 5. 全部落到 app 专属外部目录 forensics/ 下，走 FileProvider 直接分享；
 * 6. 可选再把整份 server 日志 cp 到 /sdcard/Download，方便用文件管理器取。
 *
 * 判定口径：出现 "No active Binder clients for 1500ms！" 即确认服务端主动回收会话，
 * 137（SIGKILL）属于该链路的结果，不是被杀错。
 */
object Forensics {

    const val DEFAULT_LOG = "/data/local/tmp/shower.log"
    const val DEFAULT_TAGS = "ShowerController,ShowerSurfaceView,PhoneAgent,DisplayCapture"

    /** 关键句，顺序即报告顺序 */
    val DEFAULT_PATTERN = listOf(
        "server started",
        "Created virtual display",
        "Registered DisplaySession",
        "Releasing active session",
        "releaseDisplay",
        "No active Binder clients",
        "Failed to init InputController",
        "setDisplayCapture"
    )

    /** 单个关键字命中情况 */
    data class Hit(val keyword: String, val count: Int, val last: String)

    data class Step(val title: String, val cmd: String, val out: String, val ok: Boolean)

    data class Result(
        /** 报告目录，失败时为 null */
        val dir: File?,
        val steps: List<Step>,
        val hits: List<Hit>,
        val verdict: String,
        /** 0 正常 / 1 待核 / 2 命中问题特征 */
        val level: Int,
        val files: List<File>,
        val report: String
    )

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun dirOf(context: Context, pkg: String, at: Long = System.currentTimeMillis()): File =
        File(context.getExternalFilesDir(null), "forensics/${pkg.ifBlank { "unknown" }}_${stampOf(at)}")
            .apply { mkdirs() }

    private fun stampOf(at: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(at))

    fun human(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.2f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * 跑完整取证流程。onStep 每完成一步回调一次，UI 直接增量渲染。
     */
    fun run(
        context: Context,
        pkg: String,
        logPath: String,
        tags: List<String>,
        copyToDownload: Boolean,
        onStep: (Step) -> Unit = {}
    ): Result {
        val steps = ArrayList<Step>()
        val started = System.currentTimeMillis()

        fun step(title: String, cmd: String, timeoutMs: Long = 40_000L): Step {
            val r = runCatching { ShellRunner.exec(cmd, timeoutMs) }
                .getOrElse { ShellRunner.Result(-1, "", it.message ?: it.javaClass.simpleName) }
            val sb = StringBuilder(r.stdout.trimEnd())
            if (r.stderr.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append("\n[stderr]\n")
                sb.append(r.stderr.trimEnd())
            }
            val out = sb.toString()
            val s = Step(
                title = title,
                cmd = cmd,
                out = out,
                ok = r.code == 0 && out.isNotBlank() && !out.contains("No such file")
            )
            steps.add(s)
            onStep(s)
            return s
        }

        val path = logPath.trim().ifBlank { DEFAULT_LOG }
        val tagList = tags.map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { DEFAULT_TAGS.split(",") }

        // 1. 存在性 / 大小 / 行数
        val probe = step(
            "① 日志文件探测",
            "ls -l ${q(path)} 2>&1 ; echo '---' ; wc -l ${q(path)} 2>&1"
        )
        val missing = probe.out.contains("No such file") || probe.out.contains("Permission denied")

        // 2. 关键句事件序列
        val pattern = DEFAULT_PATTERN.joinToString("|")
        val key = if (missing) {
            Step("② 关键句抽取", "已跳过：日志不可读", "日志不存在或无权限，见 ①", false)
                .also { steps.add(it); onStep(it) }
        } else {
            step(
                "② 关键句抽取（尾部 300 行）",
                "grep -nE ${q(pattern)} ${q(path)} 2>&1 | tail -n 300"
            )
        }

        // 3. main buffer 按 tag 过滤（过滤下沉到 logcat，不用主机侧 grep 全量）
        val tagsArg = tagList.joinToString(" ") { "${it}:V" }
        val main = step(
            "③ main buffer 按 tag 抽尾部 800 行",
            "logcat -b main -d -v threadtime -t 800 -s $tagsArg 2>&1"
        )

        // 4. server 日志尾部（限 2 万行，避免几十万行灌进 binder 管道）
        val tailStep = if (missing) {
            Step("④ server 日志尾部", "已跳过：日志不可读", "", false)
                .also { steps.add(it); onStep(it) }
        } else {
            step(
                "④ server 日志尾部 2 万行",
                "tail -n 20000 ${q(path)} 2>&1",
                90_000L
            )
        }

        // 4b. 日志不存在时的 run-as 兜底：找应用私有目录下可能的分身日志
        val fallback = if (!missing) null else step(
            "④b run-as 兜底查找",
            "run-as ${q(pkg)} find files cache -name '*shower*' -o -name '*.log' 2>&1 | head -n 80"
        )

        // 5. 可选：整份 cp 到 /sdcard/Download
        val copy = if (copyToDownload && !missing) step(
            "⑤ 整份复制到 /sdcard/Download",
            "cp ${q(path)} /sdcard/Download/shower.log 2>&1 ; ls -l /sdcard/Download/shower.log 2>&1",
            120_000L
        ) else null

        // ── 命中统计 ────────────────────────────────────────
        val keyOut = key.out
        val hits = DEFAULT_PATTERN.mapNotNull { kw ->
            var count = 0
            var last = ""
            keyOut.lineSequence().forEach { line ->
                if (line.contains(kw, ignoreCase = false)) {
                    count++
                    last = line.trim()
                }
            }
            if (count > 0) Hit(kw, count, last) else null
        }

        val noClient = hits.firstOrNull { it.keyword == "No active Binder clients" }
        val released = hits.firstOrNull {
            it.keyword == "Releasing active session" || it.keyword == "releaseDisplay"
        }

        val level: Int
        val verdict: String
        when {
            noClient != null -> {
                level = 2
                verdict = "已确认：服务端出现「No active Binder clients」，虚拟显示会话被自行回收，" +
                    "随后进程 137（SIGKILL）是该链路的正常结果 —— 不是日志工具或系统误杀。" +
                    "改法应落在会话保活（延长 idle 超时 / 捕获期间持有 display 引用），而非去查 SIGKILL 来源。"
            }

            released != null -> {
                level = 1
                verdict = "有会话释放记录（${released.keyword} × ${released.count}），但没有抓到 " +
                    "「No active Binder clients」关键句：释放原因待核 —— 检查 ④/⑤ 导出的 server 日志尾部是否已被截断，或缩短取证间隔重跑。"
            }

            missing -> {
                level = 1
                verdict = "server 日志读不到（不存在或无权限）：服务端可能没有以 shell 身份写入 /data/local/tmp，" +
                    "或已随进程重启被清空。看 ④b 的 run-as 结果，或改用应用私有目录里的分身日志。"
            }

            else -> {
                level = 0
                verdict = "未命中任何关键句：本轮窗口内服务端可能没有重启过，或日志已被轮转/清空。" +
                    "建议复现问题后立刻重跑一审。"
            }
        }

        // ── 落盘 ────────────────────────────────────────────
        val dir = dirOf(context, pkg, started)
        val files = ArrayList<File>()
        runCatching {
            File(dir, "server_keyevents.txt").writeText(
                buildString {
                    appendLine("# server 关键句抽取　来源：$path")
                    appendLine("# 关键字：$pattern")
                    appendLine("# 采集：${DiagRepo.fmtFull(started)}　设备：${Build.MANUFACTURER} ${Build.MODEL}　ROM：${Build.DISPLAY}")
                    appendLine("# 命令：${key.cmd}")
                    appendLine("#".repeat(72))
                    appendLine(key.out)
                },
                Charsets.UTF_8
            )
            File(dir, "main_filtered.log").writeText(
                buildString {
                    appendLine("# main buffer 按 tag 过滤　tags：$tagsArg")
                    appendLine("# 命令：${main.cmd}")
                    appendLine("#".repeat(72))
                    appendLine(main.out)
                },
                Charsets.UTF_8
            )
            if (!missing && tailStep.out.isNotBlank()) {
                File(dir, "server_tail.log").writeText(tailStep.out, Charsets.UTF_8)
            }
            fallback?.let { File(dir, "runas_find.txt").writeText(it.out, Charsets.UTF_8) }
            copy?.let { File(dir, "download_copy.txt").writeText(it.out, Charsets.UTF_8) }
        }

        val report = buildString {
            appendLine("===== ShizukuLogger 服务端日志取证 =====")
            appendLine("目标：$pkg")
            appendLine("时间：${DiagRepo.fmtFull(started)}")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ROM：${Build.DISPLAY}")
            appendLine("日志路径：$path")
            appendLine("tags：$tagsArg")
            appendLine()
            appendLine("── 结论 ──")
            appendLine(verdict)
            appendLine()
            appendLine("── 关键句命中 ──")
            if (hits.isEmpty()) {
                appendLine("（无）")
            } else {
                hits.forEach { appendLine("${it.keyword} × ${it.count}\n  末次：${it.last}") }
            }
            appendLine()
            appendLine("── 步骤明细 ──")
            steps.forEach { s ->
                appendLine("【${s.title}】${if (s.ok) "OK" else "NG"}")
                appendLine("$ ${s.cmd}")
                appendLine(if (s.out.isBlank()) "（无输出）" else s.out.take(6000))
                appendLine()
            }
        }

        runCatching { File(dir, "report.txt").writeText(report, Charsets.UTF_8) }

        runCatching {
            dir.listFiles()?.filter { it.isFile && it.length() > 0 }
                ?.sortedBy { it.name }
                ?.forEach { files.add(it) }
        }

        return Result(
            dir = dir.takeIf { it.exists() },
            steps = steps,
            hits = hits,
            verdict = verdict,
            level = level,
            files = files,
            report = report
        )
    }
}
