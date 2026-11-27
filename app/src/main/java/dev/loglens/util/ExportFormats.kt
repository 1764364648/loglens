package dev.loglens.util

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import dev.loglens.BuildConfig
import dev.loglens.data.LogEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 导出格式。ZIP 会额外塞一份设备信息，方便贴到 issue 里 */
enum class ExportFormat(val id: String, val label: String, val note: String, val ext: String, val mime: String) {
    TXT("txt", "TXT", "纯文本，和 logcat 输出一致", "txt", "text/plain"),
    CSV("csv", "CSV", "表格软件可直接打开", "csv", "text/csv"),
    JSON("json", "JSON", "结构化，便于脚本处理", "json", "application/json"),
    ZIP("zip", "ZIP", "打包文本 + 设备信息", "zip", "application/zip"),
    HTML("html", "HTML", "浏览器打开，级别配色 + 崩溃高亮", "html", "text/html"),
    PDF("pdf", "PDF", "打印归档，适合直接转发同事", "pdf", "application/pdf"),
    ;

    companion object {
        fun of(id: String?): ExportFormat = entries.firstOrNull { it.id == id } ?: TXT
    }
}

object ExportFormats {

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun exportDir(context: Context): File =
        File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }

    fun captureDir(context: Context): File =
        File(context.getExternalFilesDir(null), "capture").apply { mkdirs() }

    /**
     * 按格式导出到 app 专属 exports 目录；失败或没有内容时返回 null。
     * 写盘统一 UTF-8，CSV 带 BOM 便于 Excel 正确识别中文。
     */
    fun export(
        context: Context,
        entries: List<LogEntry>,
        packageName: String,
        format: ExportFormat,
    ): File? {
        if (entries.isEmpty()) return null
        return runCatching {
            val base = "${packageName.ifBlank { "logcat" }}_${stamp()}"
            when (format) {
                ExportFormat.TXT -> File(exportDir(context), "$base.txt").apply {
                    writeText(toText(entries), Charsets.UTF_8)
                }

                ExportFormat.CSV -> File(exportDir(context), "$base.csv").apply {
                    writeText("\uFEFF" + toCsv(entries), Charsets.UTF_8)
                }

                ExportFormat.JSON -> File(exportDir(context), "$base.json").apply {
                    writeText(toJson(entries, packageName), Charsets.UTF_8)
                }

                ExportFormat.ZIP -> File(exportDir(context), "$base.zip").apply {
                    writeZip(this, entries, context, packageName)
                }
                ExportFormat.HTML -> File(exportDir(context), "$base.html").apply {
                    writeText(toHtml(entries, packageName, context), Charsets.UTF_8)
                }

                ExportFormat.PDF -> File(exportDir(context), "$base.pdf").apply {
                    writePdf(context, entries, packageName, this)
                }
            }
        }.getOrNull()
    }

    fun toText(entries: List<LogEntry>): String = buildString {
        entries.forEach { appendLine(Exporter.formatEntry(it)) }
    }

    fun toCsv(entries: List<LogEntry>): String = buildString {
        appendLine("time,pid,tid,level,tag,message,crash,raw")
        entries.forEach { e ->
            append(csvCell(e.time)).append(',')
            append(e.pid).append(',')
            append(e.tid).append(',')
            append(csvCell(e.level.letter)).append(',')
            append(csvCell(e.tag)).append(',')
            append(csvCell(e.message)).append(',')
            append(if (e.isCrash) 1 else 0).append(',')
            append(if (e.isRaw) 1 else 0)
            appendLine()
        }
    }

    fun toJson(entries: List<LogEntry>, packageName: String): String = buildString {
        append("{\"package\":").append(jsonString(packageName))
        append(",\"exportedAt\":").append(System.currentTimeMillis())
        append(",\"count\":").append(entries.size)
        append(",\"entries\":[")
        entries.forEachIndexed { index, e ->
            if (index > 0) append(',')
            append('{')
            append("\"time\":").append(jsonString(e.time)).append(',')
            append("\"pid\":").append(e.pid).append(',')
            append("\"tid\":").append(e.tid).append(',')
            append("\"level\":").append(jsonString(e.level.letter)).append(',')
            append("\"tag\":").append(jsonString(e.tag)).append(',')
            append("\"message\":").append(jsonString(e.message)).append(',')
            append("\"crash\":").append(e.isCrash).append(',')
            append("\"continuation\":").append(e.isContinuation).append(',')
            append("\"raw\":").append(e.isRaw)
            append('}')
        }
        append("]}")
    }

    private fun writeZip(file: File, entries: List<LogEntry>, context: Context, packageName: String) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("log.txt", toText(entries))
            put("log.csv", "\uFEFF" + toCsv(entries))
            put("device-info.txt", deviceInfo(context, packageName, entries.size))
            put("meta.json", toJson(entries, packageName))
            put("log.html", toHtml(entries, packageName, context))
        }
    }

    /** 设备信息：贴 issue 时最常被追问的就是这几项 */
    fun deviceInfo(context: Context, packageName: String, count: Int): String = buildString {
        appendLine("app=LogLens ${BuildConfig.VERSION_NAME}")
        appendLine("targetPackage=${packageName.ifBlank { "unknown" }}")
        appendLine("entries=$count")
        appendLine("exportedAt=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("manufacturer=${Build.MANUFACTURER}")
        appendLine("brand=${Build.BRAND}")
        appendLine("model=${Build.MODEL}")
        appendLine("device=${Build.DEVICE}")
        appendLine("product=${Build.PRODUCT}")
        appendLine("android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        appendLine("buildId=${Build.ID}")
        appendLine("fingerprint=${Build.FINGERPRINT}")
        appendLine("abi=${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine("locale=${Locale.getDefault()}")
    }

    private fun csvCell(raw: String): String {
        if (raw.isEmpty()) return ""
        val needQuote = raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = raw.replace("\"", "\"\"")
        return if (needQuote) "\"$escaped\"" else escaped
    }

    private fun jsonString(raw: String): String = buildString {
        append('"')
        raw.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** HTML 导出：级别配色 + 崩溃高亮，双击浏览器就能看，不依赖任何 App。 */
    fun toHtml(entries: List<LogEntry>, packageName: String, context: Context? = null): String = buildString {
        appendLine("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        appendLine("<title>logcat ${packageName.ifBlank { "logcat" }}</title>")
        appendLine("<style>")
        appendLine("body{margin:0;background:#0f1115;color:#e6e6e6;font:12px/1.55 ui-monospace,Menlo,Consolas,monospace}")
        appendLine("header{position:sticky;top:0;background:#161a20;border-bottom:1px solid #2a2f38;padding:10px 14px;z-index:9}")
        appendLine("header b{color:#8ab4ff;font-weight:600}header span{color:#8b95a5;margin-right:14px}")
        appendLine("table{border-collapse:collapse;width:100%}td{padding:2px 8px;vertical-align:top;white-space:pre-wrap;word-break:break-word}")
        appendLine("tr:hover{background:#171c24}td.t{color:#6b7684;white-space:nowrap}")
        appendLine(".v{color:#7f8b99}.d{color:#63b3ed}.i{color:#68d391}.w{color:#f6c177}.e{color:#f77}.f{color:#ff5c7a}")
        appendLine("tr.crash{background:#2a1218}tr.crash td{color:#ff8497}")
        appendLine("td.tag{color:#b794f4;white-space:nowrap}")
        appendLine("</style></head><body><header><b>${packageName.ifBlank { "全部应用" }}</b> ")
        appendLine("<span>${entries.size} 条</span><span>${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}</span>")
        if (context != null) appendLine("<span>${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}</span>")
        appendLine("</header><table>")
        entries.forEach { e ->
            val cls = when (e.level.letter) {
                "V" -> "v"; "D" -> "d"; "I" -> "i"; "W" -> "w"; "E", "F", "A" -> "e"; else -> ""
            }
            append("<tr").append(if (e.isCrash) " class=\"crash\"" else "").append(">")
            append("<td class=\"t\">").append(htmlEscape(e.time)).append("</td>")
            append("<td class=\"").append(cls).append("\">").append(e.level.letter).append("</td>")
            append("<td class=\"tag\">").append(htmlEscape(e.tag)).append("</td>")
            append("<td>").append(htmlEscape(if (e.isContinuation) "    " + e.message else e.message)).append("</td>")
            appendLine("</tr>")
        }
        appendLine("</table></body></html>")
    }

    private fun htmlEscape(raw: String): String = raw
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** PDF 导出：A4 等宽排版，超长行自动折行，一页放不下自动翻页。 */
    private fun writePdf(context: Context, entries: List<LogEntry>, packageName: String, file: File) {
        val doc = PdfDocument()
        try {
            val body = Paint().apply {
                typeface = Typeface.MONOSPACE
                textSize = 7.4f
                isAntiAlias = true
            }
            val head = Paint(body).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textSize = 8.2f
            }
            val pageW = 842
            val pageH = 595
            val margin = 22f
            val lineH = 9.6f
            val maxChars = ((pageW - margin * 2) / (body.textSize * 0.62f)).toInt().coerceAtLeast(60)

            var pageNo = 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
            var canvas = page.canvas
            var y = margin + 8f

            fun newPage() {
                doc.finishPage(page)
                pageNo += 1
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
                canvas = page.canvas
                y = margin + 8f
            }

            canvas.drawText("LogLens  ${packageName.ifBlank { "all apps" }}  ${entries.size} entries", margin, y, head)
            y += lineH + 2f
            canvas.drawText(
                "${Build.MANUFACTURER} ${Build.MODEL}  Android ${Build.VERSION.RELEASE} (${Build.ID})  " +
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
                margin, y, body
            )
            y += lineH * 1.4f

            val limit = entries.size.coerceAtMost(40_000)
            for (i in 0 until limit) {
                val e = entries[i]
                val prefix = "${e.time} ${e.level.letter} ${e.tag}: "
                val text = prefix + e.message.replace("\r", " ")
                var rest = text
                while (rest.isNotEmpty()) {
                    val cut = if (rest.length <= maxChars) rest.length else {
                        val slice = rest.substring(0, maxChars)
                        val brk = slice.lastIndexOf(' ')
                        if (brk > maxChars * 0.6) brk else maxChars
                    }
                    if (y > pageH - margin) newPage()
                    canvas.drawText(rest.substring(0, cut), margin, y, body)
                    y += lineH
                    rest = rest.substring(cut).trimStart()
                }
            }
            if (entries.isEmpty()) canvas.drawText("(no entries)", margin, y, body)
            doc.finishPage(page)
            file.outputStream().use { doc.writeTo(it) }
        } finally {
            // 中途排版/写文件抛异常时也必须释放 PdfDocument 的 native 资源
            runCatching { doc.close() }
        }
    }

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(
        context,
        "${BuildConfig.APPLICATION_ID}.fileprovider",
        file
    )

    /** 分享任意本地文件（exports / capture 目录都在 FileProvider 白名单内） */
    fun shareFile(context: Context, file: File, mime: String = "text/plain") {
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 ${file.name}"))
    }

    /** 用系统应用打开（日志一般交给文本查看器） */
    fun openFile(context: Context, file: File) {
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }
    }
}
