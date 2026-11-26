package dev.loglens.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import dev.loglens.data.LogEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把当前视图渲染成一张 PNG 存证图。
 * 比系统截图实用：只有日志、没有状态栏和聊天气泡，直接甩群里就能看。
 */
object SnapshotImage {

    fun render(
        context: Context,
        entries: List<LogEntry>,
        title: String,
        maxLines: Int = 80
    ): File? = runCatching {
        val rows = if (entries.size > maxLines) entries.takeLast(maxLines) else entries
        val density = context.resources.displayMetrics.density
        val padX = (14 * density).toInt()
        val padY = (12 * density).toInt()
        val lineH = (15 * density).toInt()
        val headerH = (46 * density).toInt()

        val mono = Typeface.MONOSPACE
        val measure = Paint().apply { typeface = mono; textSize = 9.5f * density }
        val width = maxOf((560 * density).toInt(), maxLineWidth(rows, measure) + padX * 2).coerceAtMost((1400 * density).toInt())
        val height = headerH + padY * 2 + rows.size * lineH

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#0F1115"))

        val header = Paint().apply {
            color = Color.parseColor("#161A20")
            isAntiAlias = true
        }
        canvas.drawRect(0f, 0f, width.toFloat(), headerH.toFloat(), header)

        val titlePaint = Paint().apply {
            color = Color.parseColor("#8AB4FF")
            textSize = 12f * density
            typeface = Typeface.create(mono, Typeface.BOLD)
            isAntiAlias = true
        }
        canvas.drawText(title.take(52), padX.toFloat(), (26 * density), titlePaint)

        val subPaint = Paint().apply {
            color = Color.parseColor("#8B95A5")
            textSize = 9f * density
            typeface = mono
            isAntiAlias = true
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        canvas.drawText("$stamp · 共 ${entries.size} 条 · 末 ${rows.size} 条", padX.toFloat(), (39 * density), subPaint)

        val body = Paint().apply {
            typeface = mono
            textSize = 9.5f * density
            isAntiAlias = true
        }
        val maxChars = ((width - padX * 2) / (body.textSize * 0.6f)).toInt().coerceAtLeast(24)
        var y = headerH + padY + 9f * density
        for (e in rows) {
            body.color = colorOf(e.level.priority)
            val text = (if (e.time.isNotBlank()) e.time.takeLast(12) + " " else "") +
                e.level.letter + " " + e.tag + " " + e.message.replace("\n", " ")
            canvas.drawText(clip(text, maxChars), padX.toFloat(), y, body)
            y += lineH
        }

        val dir = ExportFormats.exportDir(context)
        if (!dir.exists()) dir.mkdirs()
        val name = "log-snapshot-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png"
        val file = File(dir, name)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        file
    }.getOrNull()

    private fun maxLineWidth(rows: List<LogEntry>, paint: Paint): Int {
        var max = 0f
        for (e in rows) {
            val w = paint.measureText("00:00:00.000 E ${e.tag} ${e.message.replace("\n", " ")}")
            if (w > max) max = w
        }
        return max.toInt()
    }

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    private fun colorOf(priority: Int): Int = when {
        priority >= 6 -> Color.parseColor("#FF5C7A")
        priority >= 5 -> Color.parseColor("#FF8497")
        priority == 4 -> Color.parseColor("#F77E7E")
        priority == 3 -> Color.parseColor("#F6C177")
        priority == 2 -> Color.parseColor("#68D391")
        priority == 1 -> Color.parseColor("#63B3ED")
        else -> Color.parseColor("#7F8B99")
    }
}
