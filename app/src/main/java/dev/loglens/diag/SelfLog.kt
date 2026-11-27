package dev.loglens.diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LogLens 自己的运行日志。
 *
 * 被采集应用出问题有 logcat 可看；LogLens 自己出问题（Shizuku 断连、采集拉不起来、
 * 落盘失败、悬浮窗没出来）用户手上什么都没有，只能口头描述。这个文件就是给反馈用的：
 * 只记 LogLens 自己的关键节点，不抄任何被采集应用的内容，落在 files/self_log/，
 * 最多 2 份 × 512 KB，不联网。
 */
object SelfLog {

    private const val MAX_BYTES = 512L * 1024
    private const val KEEP = 2
    private const val FILE_NAME = "loglens.log"

    private val lock = Any()
    private val stampFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "self_log").apply { runCatching { mkdirs() } }
    }

    fun file(): File? = dir?.let { File(it, FILE_NAME) }

    fun i(tag: String, msg: String) = write("I", tag, msg)

    fun w(tag: String, msg: String) = write("W", tag, msg)

    fun e(tag: String, msg: String, t: Throwable? = null) =
        write("E", tag, if (t == null) msg else "$msg | ${t.javaClass.simpleName}: ${t.message}")

    fun write(level: String, tag: String, msg: String) {
        val d = dir ?: return
        val line = "${stampFmt.format(Date())} $level/$tag: $msg\n"
        // 单行异常不能把调用方带崩：这个对象到处被调用，包括崩溃处理器
        synchronized(lock) {
            runCatching {
                val f = File(d, FILE_NAME)
                if (f.length() > MAX_BYTES) rotate(d)
                f.appendText(line)
            }.onFailure {
                // 自身日志写不进去时至少让 logcat 留一句，避免「反馈页永远空白」无从排查
                android.util.Log.w("SelfLog", "write failed: ${it.javaClass.simpleName} ${it.message}")
            }
        }
    }

    private fun rotate(d: File) {
        File(d, "$FILE_NAME.$KEEP").delete()
        for (i in KEEP - 1 downTo 1) {
            val src = File(d, "$FILE_NAME.$i")
            if (src.exists()) src.renameTo(File(d, "$FILE_NAME.${i + 1}"))
        }
        File(d, FILE_NAME).renameTo(File(d, "$FILE_NAME.1"))
    }

    /** 最近 maxLines 行（含上一份轮转文件），给反馈页和导出的 txt 用 */
    fun tail(maxLines: Int = 500): String {
        val d = dir ?: return ""
        val parts = ArrayList<File>(2)
        File(d, "$FILE_NAME.1").takeIf { it.exists() }?.let { parts.add(it) }
        File(d, FILE_NAME).takeIf { it.exists() }?.let { parts.add(it) }
        if (parts.isEmpty()) return ""
        val lines = ArrayList<String>(maxLines)
        for (f in parts) {
            runCatching {
                f.forEachLine { line -> lines.add(line) }
            }
        }
        val from = (lines.size - maxLines).coerceAtLeast(0)
        return lines.subList(from, lines.size).joinToString("\n")
    }

    fun clear() {
        val d = dir ?: return
        synchronized(lock) {
            runCatching {
                for (n in 0..KEEP) File(d, if (n == 0) FILE_NAME else "$FILE_NAME.$n").delete()
            }
        }
    }

    /** 导出用：把当前的日志尾巴写成一个带时间戳的 txt，返回文件 */
    fun export(context: Context, header: String): File {
        val out = File(context.cacheDir, "feedback").apply { runCatching { mkdirs() } }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(out, "loglens_log_$stamp.txt")
        val body = buildString {
            appendLine(header)
            appendLine()
            appendLine("--- LogLens 运行日志（末尾 ${500} 行）---")
            val t = tail(500)
            appendLine(t.ifBlank { "(空：本次安装后还没有记录)" })
        }
        f.writeText(body)
        return f
    }
}
