package dev.loglens.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.loglens.BuildConfig
import dev.loglens.data.LogEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Exporter {

    fun formatEntry(entry: LogEntry): String = if (entry.isContinuation) {
        "    ${entry.message}"
    } else {
        "${entry.time}  ${entry.pid}  ${entry.tid}  ${entry.level.letter} ${entry.tag}: ${entry.message}"
    }

    fun dump(entries: List<LogEntry>): String = buildString {
        entries.forEach { appendLine(formatEntry(it)) }
    }

    /** 写入 app 专属外部目录，返回文件；失败返回 null */
    fun exportToFile(context: Context, entries: List<LogEntry>, packageName: String): File? {
        if (entries.isEmpty()) return null
        return runCatching {
            val dir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "${packageName.ifBlank { "logcat" }}_$stamp.txt")
            file.writeText(dump(entries), Charsets.UTF_8)
            file
        }.getOrNull()
    }

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(
        context,
        "${BuildConfig.APPLICATION_ID}.fileprovider",
        file
    )

    fun share(context: Context, file: File, title: String = "分享日志") {
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(intent, "分享日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("logcat", text))
    }
}
