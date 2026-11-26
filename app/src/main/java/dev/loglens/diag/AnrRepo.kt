package dev.loglens.diag

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import dev.loglens.shizuku.ShellRunner

/**
 * ANR 官方归因（API 37 / Android 17 起 ApplicationExitInfo.getAnrInfo()）。
 *
 * 为什么不再只靠正则解析 dumpsys 文本：`dumpsys activity exit-info` 只给 reason=ANR 这一层，
 * 「哪类 ANR / 超时阈值多少 / 用户是否可感知 / anrId 是多少」这四件事在文本里没有，
 * 以前只能靠 events 里 am_anr 的 reason 字符串猜。API 37 起系统自己就带了结构化字段。
 *
 * 三条读取通道，按可信度从高到低：
 *  1. API 直读：本 App 进程直接调 ActivityManager.getHistoricalProcessExitReasons() + getAnrInfo()。
 *     查别的包需要 DUMP 或「使用情况访问」权限，普通 App 通常会被 SecurityException 拦下——
 *     因此失败不算异常，只作为一条「为什么没读到」的说明保留。
 *  2. shell 直读：借 Shizuku 的 shell(uid 2000) 跑 app_process，用同一套 API 读（shell 持有 DUMP）。
 *     这一步才是不加权限也能拿到第三方包 AnrInfo 的通道。
 *  3. 文本兜底：前两条都失效时，退回到 dumpsys 文本里的 reason=ANR 记录，只保留时间与进程，
 *     归因字段留空并明确标注「文本兜底，无归因字段」。
 *
 * 全部反射执行，不依赖 android.jar 里的隐藏 IActivityManager，低版本系统上也不会类校验失败。
 */
object AnrRepo {

    const val SOURCE_API = "API 直读"
    const val SOURCE_SHELL = "shell 直读"
    const val SOURCE_TEXT = "文本兜底"

    /** 一条 ANR 归因记录。anrId/anrType/timeoutMs 为 -1 表示该通道没给出来。 */
    data class Anr(
        val at: Long,
        val process: String,
        val anrId: Int,
        val anrType: Int,
        val timeoutMs: Long,
        val userPerceptible: Boolean,
        val description: String,
        val source: String
    ) {
        val typeName: String get() = typeNameOf(anrType)
        val timeoutText: String get() = if (timeoutMs <= 0L) "未给出" else "${timeoutMs} ms"
        val typeHint: String get() = hintOf(anrType)
    }

    data class Outcome(
        val items: List<Anr>,
        val apiError: String?,
        val shellError: String?,
        val raw: String
    ) {
        val structuredCount: Int get() = items.count { it.source != SOURCE_TEXT }
        val textCount: Int get() = items.count { it.source == SOURCE_TEXT }
        val byType: List<Pair<String, Int>>
            get() = items.filter { it.anrType >= 0 }
                .groupingBy { it.typeName }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .map { it.key to it.value }
        val hasStructure: Boolean get() = structuredCount > 0
        val whyEmpty: String
            get() = buildString {
                if (items.isEmpty()) {
                    appendLine("没有读到 ANR 记录（近 40 条退出记录里没有 reason=ANR）。")
                    apiError?.let { appendLine("· API 直读：$it") }
                    shellError?.let { appendLine("· shell 直读：$it") }
                } else {
                    if (!hasStructure) appendLine("只有文本兜底记录，归因字段（类型/阈值/可感知）缺失。")
                    apiError?.let { appendLine("· API 直读失败：$it") }
                    shellError?.let { appendLine("· shell 直读失败：$it") }
                }
            }
    }

    // ───────────────────────── 入口 ─────────────────────────

    fun collect(context: Context, pkg: String, fallbackExits: List<DiagRepo.Exit> = emptyList()): Outcome {
        val notes = StringBuilder()

        val api = viaApp(context, pkg)
        api.second?.let { notes.appendLine("api: $it") }

        var shellList = emptyList<Anr>()
        var shellErr: String? = null
        if (api.first.isEmpty()) {
            val shell = viaShell(context, pkg)
            shellList = shell.first
            shellErr = shell.second
            shell.second?.let { notes.appendLine("shell: $it") }
        }

        val structured = (api.first + shellList).distinctBy { "${it.at}/${it.process}/${it.anrId}/${it.anrType}" }
        val text = if (structured.isEmpty()) fromExits(fallbackExits) else emptyList()

        return Outcome(
            items = (structured + text).sortedBy { it.at },
            apiError = api.second,
            shellError = shellErr,
            raw = notes.toString().trim()
        )
    }

    /** 文本兜底：reason=ANR 的退出记录转成无归因字段的 ANR 记录。 */
    fun fromExits(exits: List<DiagRepo.Exit>): List<Anr> = exits.filter { it.reason.contains("ANR", true) }.map {
        Anr(
            at = it.at ?: 0L,
            process = it.process,
            anrId = -1,
            anrType = -1,
            timeoutMs = -1L,
            userPerceptible = false,
            description = "",
            source = SOURCE_TEXT
        )
    }

    // ───────────────────────── 通道 1：本进程 API 直读 ─────────────────────────

    @SuppressLint("NewApi")
    private fun viaApp(context: Context, pkg: String): Pair<List<Anr>, String?> {
        if (Build.VERSION.SDK_INT < 37) {
            return emptyList<Anr>() to "系统 < Android 17（API 37），本机根本没有 AnrInfo 字段"
        }
        return try {
            val am = context.getSystemService(ActivityManager::class.java)
                ?: return emptyList<Anr>() to "ActivityManager 拿不到"
            val hist = am.getHistoricalProcessExitReasons(pkg, 0, 40)
            val out = ArrayList<Anr>()
            hist.forEach { e ->
                if (e.reason != ApplicationExitInfo.REASON_ANR) return@forEach
                val ai = e.anrInfo ?: return@forEach
                out.add(
                    Anr(
                        at = e.timestamp,
                        process = e.processName ?: "?",
                        anrId = ai.anrId,
                        anrType = ai.anrType,
                        timeoutMs = ai.timeoutMillis,
                        userPerceptible = ai.isUserPerceptible,
                        description = "",
                        source = SOURCE_API
                    )
                )
            }
            out to null
        } catch (t: Throwable) {
            emptyList<Anr>() to (t.javaClass.simpleName + "：" + (t.message ?: "无描述"))
        }
    }

    // ───────────────────────── 通道 2：shell(uid 2000) app_process 直读 ─────────────────────────

    private fun viaShell(context: Context, pkg: String): Pair<List<Anr>, String?> {
        if (Build.VERSION.SDK_INT < 37) {
            return emptyList<Anr>() to "系统 < Android 17（API 37），shell 通道同样没有 AnrInfo"
        }
        val selfApk = runCatching {
            context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir
        }.getOrNull() ?: return emptyList<Anr>() to "拿不到本应用 APK 路径"
        if (selfApk.isBlank()) return emptyList<Anr>() to "本应用 APK 路径为空"

        val cmd = buildString {
            append("CLASSPATH='").append(selfApk).append("' ")
            append("/system/bin/app_process /system/bin ")
            append("dev.loglens.diag.cli.AnrCli '").append(pkg).append("'")
            append(" 2>&1")
        }
        val r = runCatching { ShellRunner.exec(cmd, 20_000) }.getOrElse {
            return emptyList<Anr>() to ("执行失败：" + (it.message ?: it.javaClass.simpleName))
        }
        val text = (r.stdout + "\n" + r.stderr).trim()
        val out = ArrayList<Anr>()
        var err: String? = null
        var sawOk = false
        text.lineSequence().forEach { line ->
            val t = line.trim()
            when {
                t.startsWith("ANR|") -> {
                    val f = t.split("|")
                    if (f.size >= 7) {
                        out.add(
                            Anr(
                                at = f[1].toLongOrNull() ?: 0L,
                                process = f[2].ifBlank { "?" },
                                anrId = f[3].toIntOrNull() ?: -1,
                                anrType = f[4].toIntOrNull() ?: -1,
                                timeoutMs = f[5].toLongOrNull() ?: -1L,
                                userPerceptible = f[6] == "1",
                                description = f.getOrNull(7)?.trim().orEmpty(),
                                source = SOURCE_SHELL
                            )
                        )
                    }
                }
                t.startsWith("OK|") -> sawOk = true
                t.startsWith("ERR|") -> err = t.removePrefix("ERR|").trim()
            }
        }
        if (!sawOk && err == null) {
            val tail = text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(3).joinToString(" / ")
            err = "app_process 没有返回任何结构化结果：" + tail.ifBlank { "无输出" }
        }
        return out to err
    }

    // ───────────────────────── AnrTypes 映射 ─────────────────────────

    fun typeNameOf(code: Int): String = when (code) {
        0 -> "OTHER"
        1 -> "INPUT_DISPATCH_NO_FOCUSED_WINDOW"
        2 -> "INPUT_DISPATCH"
        3 -> "BROADCAST_OF_INTENT"
        4 -> "START_FOREGROUND_SERVICE"
        5 -> "EXECUTE_SERVICE"
        6 -> "CONTENT_PROVIDER_NOT_RESPONDING"
        7 -> "APP_TRIGGERED"
        8 -> "FOREGROUND_SHORT_SERVICE_TIMEOUT"
        9 -> "JOB_SERVICE_START"
        10 -> "APPLICATION_START"
        else -> "未归因"
    }

    fun hintOf(code: Int): String = when (code) {
        1 -> "触摸落到了没有焦点的窗口，主线程 5s 内没接住"
        2 -> "输入分发超时（最常见）：主线程被堵，5s 内没处理完 input 事件"
        3 -> "前台广播 10s / 后台广播 60s 内没执行完 onReceive"
        4 -> "startForegroundService 后 10s 内没调 startForeground"
        5 -> "Service 生命周期回调超时（onCreate/onStart/onBind 20s 级）"
        6 -> "ContentProvider 发布或查询超时"
        7 -> "应用自己调 ActivityManager.appNotResponding() 主动上报"
        8 -> "短前台服务（shortForegroundService）超时"
        9 -> "JobService 启动超时"
        10 -> "应用启动（bindApplication / Activity 启动）阶段超时"
        else -> "系统未给细分类型，需要结合主线程栈判断"
    }
}
