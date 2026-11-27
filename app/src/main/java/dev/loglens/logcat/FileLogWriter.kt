package dev.loglens.logcat

import android.content.Context
import dev.loglens.data.LogEntry
import dev.loglens.data.Prefs
import dev.loglens.diag.SelfLog
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 边抓边落盘。写线程 + 有界队列，避免磁盘 IO 阻塞日志读取线程。
 * 文件位于 app 专属外部目录，卸载即清除，不污染用户存储。
 *
 * 单文件到达上限后自动轮转成 xxx.1.log / xxx.2.log …，并只保留最近几份，
 * 避免长时间开着采集把手机存储写满（原来是无上限一直追加）。
 */
class FileLogWriter(private val context: Context) {

    private val queue = LinkedBlockingQueue<String>(200_000)
    private val alive = AtomicBoolean(false)
    private var writer: BufferedWriter? = null
    private var thread: Thread? = null
    private var dir: File? = null
    private var baseName: String = "log"
    private var seq = 0
    private var sinceCheck = 0

    /** 最近一次轮转原因，UI 可以拿来提示 */
    @Volatile
    var lastRotateReason: String? = null
        private set

    @Volatile
    var currentFile: File? = null
        private set

    private fun limitBytes(): Long = runCatching {
        Prefs(context).logFileMaxMb.toLong() * 1024L * 1024L
    }.getOrDefault(32L * 1024 * 1024)

    private fun keepFiles(): Int = runCatching {
        Prefs(context).logFileKeep
    }.getOrDefault(3).coerceIn(1, 20)

    fun start(packageName: String) {
        stop()
        val d = File(context.getExternalFilesDir(null), "capture").apply { mkdirs() }
        dir = d
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        baseName = "${packageName}_$stamp"
        seq = 0
        sinceCheck = 0
        lastRotateReason = null
        if (!openNext()) {
            // 拿不到可写目录：不要把「以为在落盘」的状态留给上层
            SelfLog.e("writer", "落盘启动失败：${lastRotateReason}")
            throw java.io.IOException(lastRotateReason ?: "无法创建日志文件")
        }
        alive.set(true)
        thread = Thread({ loop() }, "log-file-writer").apply {
            isDaemon = true
            start()
        }
    }

    /** 打开下一份文件；失败返回 false（存储满 / 被卸载），由调用方决定停止写入 */
    private fun openNext(): Boolean {
        val d = dir ?: return false
        val f = if (seq == 0) File(d, "$baseName.log") else File(d, "$baseName.$seq.log")
        currentFile = f
        writer = try {
            BufferedWriter(FileWriter(f, true), 64 * 1024)
        } catch (t: Throwable) {
            lastRotateReason = "打开日志文件失败：${t.message ?: t.javaClass.simpleName}"
            null
        }
        if (writer == null) return false
        pruneOld()
        return true
    }

    /** 只保留最近 keepFiles() 份（含当前文件），超出的旧分片直接删掉 */
    private fun pruneOld() {
        val d = dir ?: return
        val keep = keepFiles()
        val mine = d.listFiles { f ->
            f.isFile && (f.name == "$baseName.log" || f.name.startsWith("$baseName.") && f.name.endsWith(".log"))
        } ?: return
        if (mine.size <= keep) return
        mine.sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { f -> if (f != currentFile) runCatching { f.delete() } }
    }

    /** 写线程里调用，单线程持有 writer，无需加锁 */
    private fun rotate() {
        if (!alive.get()) return
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        seq++
        if (!openNext()) {
            // 轮转失败（存储满 / 被卸载）：停止写线程并清掉待排队列，避免继续假装落盘
            alive.set(false)
            queue.clear()
            SelfLog.e("writer", "轮转失败，已停止落盘：${lastRotateReason}")
            return
        }
        lastRotateReason = "单文件达到 ${limitBytes() / 1024 / 1024} MB，已切到新分片"
    }

    private fun loop() {
        // 写失败只报一次：存储满时每条日志都会失败，不能让 SelfLog 自己被刷屏
        var writeFailed = false
        while (alive.get() || queue.isNotEmpty()) {
            val line: String? = try {
                queue.poll(300L, TimeUnit.MILLISECONDS)
            } catch (t: InterruptedException) {
                null
            }
            if (line != null) {
                if (!writeFailed) {
                    val err = runCatching {
                        writer?.write(line)
                        writer?.write("\n")
                    }.exceptionOrNull()
                    if (err != null) {
                        // 之前这里把 IOException 吞掉、alive 仍是 true：界面显示「落盘中」，实际早停了
                        writeFailed = true
                        alive.set(false)
                        queue.clear()
                        SelfLog.e("writer", "写入失败，已停止落盘", err)
                        break
                    }
                }
                sinceCheck++
                if (sinceCheck >= 200) {
                    sinceCheck = 0
                    runCatching { writer?.flush() }
                    val size = currentFile?.length() ?: 0L
                    if (size >= limitBytes()) {
                        // rotate 内部已兜底 I/O 异常；这里再兜一层，绝不让写线程带着异常退出
                        runCatching { rotate() }.onFailure {
                            alive.set(false)
                            queue.clear()
                            lastRotateReason = "轮转失败：${it.message ?: it.javaClass.simpleName}"
                            SelfLog.e("writer", "轮转异常，已停止落盘", it)
                        }
                    }
                }
            } else {
                runCatching { writer?.flush() }
            }
        }
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
    }

    fun enqueue(entry: LogEntry) {
        if (!alive.get()) return
        val text = if (entry.isContinuation) {
            "    ${entry.message}"
        } else {
            "${entry.time}  ${entry.pid}  ${entry.tid}  ${entry.level.letter} ${entry.tag}: ${entry.message}"
        }
        queue.offer(text)
    }

    fun stop() {
        alive.set(false)
        runCatching { thread?.join(1_500) }
        thread = null
        // 关键：不清队列的话，上一会话没写完的行会跟着写进下一个会话的文件里
        queue.clear()
    }

    fun currentSize(): Long = currentFile?.takeIf { it.exists() }?.length() ?: 0L
}
