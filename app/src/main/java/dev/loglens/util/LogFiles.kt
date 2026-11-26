package dev.loglens.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史日志文件管理：录制落盘的、导出过的，都在这里统一列出来。
 * 竞品普遍能把存过的日志再翻出来看，这块补齐。
 */
object LogFiles {

    data class Item(
        val file: File,
        val name: String,
        val sizeText: String,
        val timeText: String,
        val fromCapture: Boolean
    )

    fun list(context: Context, limit: Int = 200): List<Item> {
        val dirs = listOf(ExportFormats.exportDir(context) to false, ExportFormats.captureDir(context) to true)
        val out = ArrayList<Item>()
        for ((dir, cap) in dirs) {
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                out.add(
                    Item(
                        file = f,
                        name = f.name,
                        sizeText = size(f.length()),
                        timeText = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(f.lastModified())),
                        fromCapture = cap
                    )
                )
            }
        }
        out.sortByDescending { it.file.lastModified() }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun share(context: Context, item: Item) {
        ExportFormats.shareFile(context, item.file, mimeOf(item.file))
    }

    fun open(context: Context, item: Item) {
        ExportFormats.openFile(context, item.file)
    }

    fun remove(item: Item): Boolean = runCatching { item.file.delete() }.getOrDefault(false)

    fun mimeOf(file: File): String = when (file.extension.lowercase(Locale.US)) {
        "txt", "log" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "zip" -> "application/zip"
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        "png", "jpg" -> "image/*"
        else -> "application/octet-stream"
    }
}
