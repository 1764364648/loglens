package dev.loglens.logcat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.loglens.MainActivity
import dev.loglens.data.LogEntry

/**
 * 崩溃 / ANR 通知。仅在看日志时出现，做了节流：
 * 30 秒内同一 tag 只提醒一次，避免一段崩溃刷屏炸通知栏。
 */
object CrashNotifier {

    private const val CHANNEL_ID = "crash"
    private const val CHANNEL_NAME = "崩溃与 ANR 提醒"
    private const val CHANNEL_DESC = "目标应用出现 Java 崩溃、ANR 或 native crash 时提醒"
    private const val NOTI_ID = 2001
    private const val THROTTLE_MS = 30_000L

    private val lastNotifiedAt = HashMap<String, Long>()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = CHANNEL_DESC
                setShowBadge(true)
                enableVibration(true)
            }
        )
    }

    fun notifyCrash(context: Context, entry: LogEntry, enabled: Boolean) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        // 按「类别 + tag」分桶：ANR 与普通崩溃各自计时，不会互相压制 30 秒
        val kind = when {
            entry.tag.contains("ANR", true) -> "anr"
            entry.tag.contains("native", true) -> "native"
            else -> "crash"
        }
        val key = "$kind:${entry.tag.ifBlank { "-" }}"
        val last = lastNotifiedAt[key] ?: 0L
        if (now - last < THROTTLE_MS) return
        lastNotifiedAt[key] = now
        ensureChannel(context)

        val title = when {
            entry.tag.contains("ANR", true) -> "检测到 ANR"
            entry.tag.contains("native", true) -> "检测到 native 崩溃"
            else -> "检测到崩溃"
        }
        val body = (if (entry.tag.isBlank()) entry.message else "${entry.tag}: ${entry.message}")
            .lineSequence()
            .filter { it.isNotBlank() }
            .firstOrNull()
            ?.trim()
            ?.take(120)
            ?: "查看详情"

        val open = PendingIntent.getActivity(
            context,
            201,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(NOTI_ID, notification)
        }
    }

    fun reset() = lastNotifiedAt.clear()
}
