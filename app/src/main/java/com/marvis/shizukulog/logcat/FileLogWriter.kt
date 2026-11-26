package com.marvis.shizukulog.logcat

import android.content.Context
import com.marvis.shizukulog.data.LogEntry
import com.marvis.shizukulog.data.Prefs
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
        openNext()
        alive.set(true)
        thread = Thread({ loop() }, "log-file-writer").apply {
            isDaemon = true
            start()
        }
    }

    private fun openNext() {
        val d = dir ?: return
        val f = if (seq == 0) File(d, "$baseName.log") else File(d, "$baseName.$seq.log")
        currentFile = f
        writer = BufferedWriter(FileWriter(f, true), 64 * 1024)
        pruneOld()
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
        openNext()
        // openNext 拿不到目录（存储被卸载）时不能假装继续写，置空让 enqueue 停手并留个原因
        if (writer == null) {
            lastRotateReason = "轮转失败：拿不到写入目录"
            return
        }
        lastRotateReason = "单文件达到 ${limitBytes() / 1024 / 1024} MB，已切到新分片"
    }

    private fun loop() {
        while (alive.get() || queue.isNotEmpty()) {
            val line: String? = try {
                queue.poll(300L, TimeUnit.MILLISECONDS)
            } catch (t: InterruptedException) {
                null
            }
            if (line != null) {
                runCatching {
                    writer?.write(line)
                    writer?.write("\n")
                }
                sinceCheck++
                if (sinceCheck >= 200) {
                    sinceCheck = 0
                    runCatching { writer?.flush() }
                    val size = currentFile?.length() ?: 0L
                    if (size >= limitBytes()) rotate()
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
