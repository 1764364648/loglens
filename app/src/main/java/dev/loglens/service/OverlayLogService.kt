package dev.loglens.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.loglens.data.LogEntry
import dev.loglens.data.AppGraph

/**
 * 悬浮日志窗：不用切回 App 就能看实时日志尾巴。
 *
 * 走 TYPE_APPLICATION_OVERLAY，所以必须先在设置里给「显示在其他应用上层」。
 * 窗口整体可拖动，点右上角关闭即停服务。
 */
class OverlayLogService : Service() {

    companion object {
        @Volatile
        var alive: Boolean = false
            private set

        const val ACTION_START = "dev.loglens.overlay.START"
        const val ACTION_STOP = "dev.loglens.overlay.STOP"

        fun canDraw(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true

        fun start(context: Context) {
            val intent = Intent(context, OverlayLogService::class.java).setAction(ACTION_START)
            // 这里**不能**用 startForegroundService：本服务只做悬浮窗，不打算常驻通知栏，
            // 而 startForegroundService 要求 5 秒内必须 startForeground，否则系统直接抛
            // ForegroundServiceDidNotStartInTimeException 把进程带走（targetSdk 34+ 还会因为
            // manifest 没声明 foregroundServiceType 再抛 MissingForegroundServiceTypeException）。
            // 入口在日志页（Activity 前台），普通 startService 完全够用；采集期间进程已有
            // CaptureService 的常驻前台服务保活，悬浮窗不会被回收。
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayLogService::class.java).setAction(ACTION_STOP))
        }
    }

    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var body: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 350L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            else -> if (!canDraw(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (root == null) attach()
        handler.removeCallbacks(tick)
        handler.post(tick)
        alive = true
        // 用 START_NOT_STICKY：悬浮窗是用户手动开的东西，进程被系统干掉后不该
        // 自动「复活」成一个没有日志来源的空浮窗；需要时用户自己再点一次即可
        return START_NOT_STICKY
    }

    private fun attach() {
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val card = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#E6111418"))
                setStroke(dp(1), Color.parseColor("#3A4048"))
            }
            setPadding(dp(10), dp(6), dp(10), dp(8))
        }

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "实时日志"
            setTextColor(Color.parseColor("#8AB4FF"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
        }
        val live = TextView(this).apply {
            text = "● LIVE"
            setTextColor(Color.parseColor("#68D391"))
            textSize = 9f
            setPadding(dp(8), 0, 0, 0)
        }
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#8B95A5"))
            textSize = 13f
            setPadding(dp(10), 0, dp(2), 0)
            setOnClickListener { shutdown() }
        }
        header.addView(title)
        header.addView(live)
        header.addView(spacer)
        header.addView(close)

        body = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 8.5f
            setTextColor(Color.parseColor("#D6D6D6"))
            setLineSpacing(0f, 1.15f)
            maxLines = 14
            setPadding(0, dp(3), 0, 0)
        }

        column.addView(header)
        column.addView(body)
        card.addView(column)

        val lp = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }
        params = lp

        // 拖动：按住头部移动，松手保持在屏幕内
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - downX).toInt()
                    lp.y = startY + (event.rawY - downY).toInt()
                    runCatching { manager.updateViewLayout(card, lp) }
                    true
                }
                else -> true
            }
        }

        root = card
        runCatching { manager.addView(card, lp) }
    }

    private fun render() {
        val view = body ?: return
        val tail = AppGraph.logs.recent(14)
        if (tail.isEmpty()) {
            view.text = "等待日志…"
            return
        }
        view.text = tail.joinToString("\n") { line(it) }
    }

    private fun line(entry: LogEntry): String {
        val tag = if (entry.tag.length > 14) entry.tag.take(14) else entry.tag
        val msg = entry.message.replace("\n", " ")
        val cut = if (msg.length > 90) msg.take(90) + "…" else msg
        return "${entry.time.takeLast(12)} ${entry.level.letter} $tag $cut"
    }

    private fun shutdown() {
        handler.removeCallbacks(tick)
        root?.let { view -> runCatching { wm?.removeView(view) } }
        root = null
        body = null
        alive = false
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        root?.let { view -> runCatching { wm?.removeView(view) } }
        root = null
        alive = false
        super.onDestroy()
    }
}
