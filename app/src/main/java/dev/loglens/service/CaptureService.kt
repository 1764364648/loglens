package dev.loglens.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import dev.loglens.MainActivity
import dev.loglens.R
import dev.loglens.capture.CaptureController
import dev.loglens.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.Locale

/**
 * 前台服务只做两件事：保住进程、把采集状态同步到常驻通知。
 * 真正的抓取逻辑在 CaptureController 里，Activity 与 Service 共享同一单例。
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    /** 是否已经 startForeground：不在前台时绝不能再 notify，否则没人负责撤 */
    private var foregroundStarted = false

    /** 上一次通知的「形态指纹」：变了就撤了重发，没变才原地更新 */
    private var lastPostKey: String? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                CaptureController.stop()
                shutdown()
                return START_NOT_STICKY
            }

            ACTION_PAUSE_TOGGLE -> {
                // 从通知或岛上点「暂停 / 继续」：只切采集流，服务与通知都留着
                if (CaptureController.isPaused()) {
                    CaptureController.resume()
                } else {
                    CaptureController.pause()
                }
                push(CaptureController.state.value, CaptureController.paused.value)
                return START_STICKY
            }

            else -> {
                // 系统按 START_STICKY 把服务拉起来时 intent 是 null：此刻没有会话，直接收摊。
                // 不能先发一条「等待开始」的通知——那会变成撤不掉的残岛。
                if (CaptureController.state.value is CaptureController.State.Idle) {
                    shutdown()
                    return START_NOT_STICKY
                }
                // 前台服务申请失败（个别 ROM 限制 specialUse）时不要连累整个进程
                val ok = runCatching {
                    startForegroundCompat(
                        buildNotification(CaptureController.state.value, CaptureController.paused.value)
                    )
                }.isSuccess
                if (ok) observe() else stopSelf()
            }
        }
        return START_STICKY
    }

    private fun observe() {
        if (observeJob != null) return
        observeJob = scope.launch {
            launch {
                CaptureController.state.collectLatest { state ->
                    when (state) {
                        is CaptureController.State.Idle -> {
                            // 会话结束（日志流自然退出 / 用户停止 / 进程被系统重建）：
                            // 宽限 1.5 秒让前台的「开始 → 跳日志页」切换不至于来回启停，
                            // 之后仍是空闲就收摊并撤掉常驻通知。
                            // 不这么做会留一条「等待开始」的前台通知永远挂着。
                            delay(1_500)
                            if (!CaptureController.isPaused() &&
                                CaptureController.state.value is CaptureController.State.Idle
                            ) {
                                shutdown()
                            }
                        }
                        // 失败不是「会话进行中」：立刻退出前台并结束服务，只留一条可划掉的说明通知。
                        is CaptureController.State.Failed -> failAndExit(state)
                        else -> push(state, CaptureController.paused.value)
                    }
                }
            }
            launch {
                CaptureController.paused.collectLatest { paused ->
                    push(CaptureController.state.value, paused)
                }
            }
            launch {
                while (isActive) {
                    val s = CaptureController.state.value
                    if (s is CaptureController.State.Capturing) {
                        push(s, CaptureController.paused.value)
                    }
                    delay(2_000)
                }
            }
        }
    }

    private fun push(state: CaptureController.State, paused: Boolean) {
        // 服务已经不在前台了就别再发通知：发出去就没人撤，岛上的胶囊会一直挂着
        if (!foregroundStarted) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (state is CaptureController.State.Idle) {
            // 会话结束：撤掉，而不是再推一条「等待开始」把胶囊重新点亮
            lastPostKey = null
            runCatching { nm.cancel(NOTI_ID) }
            return
        }
        val key = stateKey(state, paused)
        val notif = buildNotification(state, paused)
        if (key != lastPostKey) {
            lastPostKey = key
            // 暂停 / 继续 / 新会话属于「形态变化」：先撤再发，让系统当成一次全新的上岛请求。
            // 各家 ROM 对原地更新基本不理——文案不换、秒表照走、恢复后也不重新弹胶囊。
            runCatching { nm.cancel(NOTI_ID) }
            runCatching { startForegroundCompat(notif) }
        } else {
            // 同一种形态里的数值刷新（条数、崩溃数）：原地更新就够，别每 2 秒闪一下
            runCatching { nm.notify(NOTI_ID, notif) }
        }
    }

    private fun stateKey(state: CaptureController.State, paused: Boolean): String =
        "${state.javaClass.simpleName}|$paused"

    private fun formatCount(v: Long): String =
        if (v >= 10_000) String.format(Locale.US, "%.1f万", v / 10_000f) else v.toString()

    /** 通知文案：暂停与采集中要能一眼区分，岛（状态栏芯片）上取的就是标题与进度 */
    private fun titleFor(state: CaptureController.State, paused: Boolean): String = when (state) {
        is CaptureController.State.Capturing ->
            if (paused) "已暂停采集" else "正在捕获 ${state.target.label}"

        is CaptureController.State.Preparing -> "正在准备捕获"
        is CaptureController.State.Failed -> "捕获失败"
        CaptureController.State.Idle -> "日志透镜"
    }

    private fun bodyFor(state: CaptureController.State, paused: Boolean): String = when (state) {
        is CaptureController.State.Capturing -> {
            if (paused) {
                "会话保留 · 已捕获 ${formatCount(AppGraph.logs.statsSnapshot().total)} 条，点「继续」接着抓"
            } else {
                val stats = AppGraph.logs.statsSnapshot()
                // 这个数是「崩溃行数」（isCrash 是逐行关键字匹配，一条异常会贡献好几行），
                // 不是崩溃次数；按异常签名归集的次数在 CrashStats，两处措辞必须区分开
                "已捕获 ${formatCount(stats.total)} 条 · 崩溃行 ${stats.crashes}"
            }
        }

        is CaptureController.State.Preparing -> "${state.label} 启动中…"
        is CaptureController.State.Failed -> state.message.take(120)
        CaptureController.State.Idle -> "等待开始"
    }

    private fun buildNotification(
        state: CaptureController.State,
        paused: Boolean,
        // 要不要上「岛」：暂停后不再请求提升，胶囊会收起来，继续时重新请求再回来
        promote: Boolean = state is CaptureController.State.Capturing && !paused
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseToggle = PendingIntent.getService(
            this,
            102,
            Intent(this, CaptureService::class.java).setAction(ACTION_PAUSE_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            101,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setContentTitle(titleFor(state, paused))
            .setContentText(bodyFor(state, paused))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(state !is CaptureController.State.Failed)
            .setOnlyAlertOnce(true)

        // 只在会话进行中提供「暂停 / 继续」，只是一个停止键对暂停没有意义
        if (state is CaptureController.State.Capturing) {
            builder.addAction(
                Notification.Action.Builder(null, if (paused) "继续" else "暂停", pauseToggle).build()
            )
        }
        builder.addAction(Notification.Action.Builder(null, "停止", stop).build())

        // Android 16 起：Promoted Ongoing（即各家的「岛上」常驻胶囊/灵动胶囊通道）
        if (Build.VERSION.SDK_INT >= 36 && promote) {
            runCatching {
                // 17 有 MetricStyle 就上真数据，16 只能拿 ProgressStyle 装个假进度
                val metric = if (Build.VERSION.SDK_INT >= 37) metricStyleFor(state, paused) else null
                builder.setStyle(metric ?: progressStyle(paused))
                // setRequestPromotedOngoing 是 API 36.1（Android 16 QPR1）才有的方法，
                // 36.0 上不存在；按 SDK_INT_FULL 判断，避免在 Android 16 初版抛 NoSuchMethodError
                if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                    builder.setRequestPromotedOngoing(true)
                }
            }
        }
        return builder.build()
    }

    // 秒表基准：会话首发时锚定，暂停期间把基准往后推，保证计时不把暂停时长算进去
    private var timerKey: Long = 0L
    private var timerZeroElapsed: Long = 0L
    private var timerPausedAt: Long = 0L

    /**
     * Android 17 的 MetricStyle 模板：胶囊上直接显示结构化数值，
     * 时长走系统秒表（系统自己跳秒，不用我们每 2s 推一次），条数与崩溃数走定点整数。
     *
     * 仅在 [buildNotification] 的 SDK_INT >= 37 分支调用；注解用于让 lint 的 NewApi
     * 识别这是有意使用的高版本 API（否则跨函数守卫分析会误报整段）。
     */
    @android.annotation.TargetApi(37)
    private fun metricStyleFor(
        state: CaptureController.State,
        paused: Boolean
    ): Notification.MetricStyle? {
        val capturing = state as? CaptureController.State.Capturing ?: return null
        val stats = AppGraph.logs.statsSnapshot()

        val style = Notification.MetricStyle()
        style.addMetric(
            Notification.Metric(
                durationValue(capturing, paused),
                if (paused) "已暂停" else "采集时长",
                if (paused) Notification.SEMANTIC_STYLE_CAUTION else Notification.SEMANTIC_STYLE_INFO
            )
        )
        style.addMetric(
            Notification.Metric(
                Notification.Metric.FixedInt(
                    stats.total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                    "条"
                ),
                "已捕获",
                Notification.SEMANTIC_STYLE_SAFE
            )
        )
        style.addMetric(
            Notification.Metric(
                Notification.Metric.FixedInt(stats.crashes, "个"),
                "崩溃",
                if (stats.crashes > 0) Notification.SEMANTIC_STYLE_DANGER
                else Notification.SEMANTIC_STYLE_SAFE
            )
        )
        // 岛上主展示位：跑着看时长，暂停了看已抓多少
        style.setCriticalMetric(if (paused) 1 else 0)
        return style
    }

    @android.annotation.TargetApi(37)
    private fun durationValue(
        capturing: CaptureController.State.Capturing,
        paused: Boolean
    ): Notification.Metric.MetricValue {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (timerKey != capturing.startedAt) {
            // 新会话或服务被重建：用墙上时钟差反推秒表零点，别从 0 重新走
            timerKey = capturing.startedAt
            val wallElapsed = (System.currentTimeMillis() - capturing.startedAt)
                .coerceIn(0L, 24 * 3600_000L)
            timerZeroElapsed = nowElapsed - wallElapsed
            timerPausedAt = 0L
        }
        if (paused) {
            if (timerPausedAt == 0L) timerPausedAt = nowElapsed
            return Notification.Metric.TimeDifference.forPausedStopwatch(
                Duration.ofMillis((timerPausedAt - timerZeroElapsed).coerceAtLeast(0L)),
                Notification.Metric.TimeDifference.FORMAT_CHRONOMETER
            )
        }
        if (timerPausedAt != 0L) {
            // 继续：零点后移，中间停掉的那段不计入
            timerZeroElapsed += (nowElapsed - timerPausedAt)
            timerPausedAt = 0L
        }
        return Notification.Metric.TimeDifference.forStopwatch(
            timerZeroElapsed,
            Notification.Metric.TimeDifference.FORMAT_CHRONOMETER
        )
    }

    /** ProgressStyle 是 Android 16 提升为 Promoted Ongoing 的硬性前提（仅 SDK_INT >= 36 调用） */
    @android.annotation.TargetApi(36)
    private fun progressStyle(paused: Boolean): Notification.ProgressStyle {
        val style = Notification.ProgressStyle()
        style.setStyledByProgress(false)
        style.setProgressIndeterminate(!paused)
        style.setProgressSegments(listOf(Notification.ProgressStyle.Segment(100)))
        return style
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTI_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTI_ID, notification)
        }
        // 只有 startForeground 真的成功后才标记，否则 push() 会在「没进前台」时继续发通知
        foregroundStarted = true
    }

    private fun stopForegroundCompat() {
        foregroundStarted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // 各家「岛」是按 notify / cancel 记账的：只 stopForeground，有的 ROM 不撤胶囊，
        // 必须再显式 cancel 一次，岛才会收起来
        cancelNotification()
    }

    private fun cancelNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)?.let {
                it.cancel(NOTI_ID)
                it.cancel(NOTI_ID_FAILED)
            }
        }
    }

    /**
     * 采集失败后的收摊。
     *
     * 官方口径：Android 13 起用户能直接划掉前台通知，也能从通知栏「使用中的应用」里一键停止
     * （那一键是把整个进程杀掉，且不给应用任何回调）；Android 14 起连 `setOngoing(true)` 都拦不住划掉。
     * 所以「失败后继续挂在前台」只会造出一条写着「捕获失败」却仍在运行的服务——用户以为停了，
     * 实际还在占前台名额。这里退回原设计：退出前台、结束服务，另发一条普通（可划掉）通知说明原因。
     */
    private fun failAndExit(state: CaptureController.State.Failed) {
        stopForegroundCompat()          // 撤前台身份 + 撤常驻通知（各家「岛」也要靠 cancel 收起）
        lastPostKey = null
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTI_ID_FAILED, buildNotification(state, paused = false))
        }
        observeJob?.cancel()
        observeJob = null
        stopSelf()
    }

    /** 会话结束的统一收摊：撤常驻通知（含岛上的胶囊）、退出前台、结束服务 */
    private fun shutdown() {
        lastPostKey = null
        stopForegroundCompat()
        cancelNotification()
        stopSelf()
    }

    override fun onDestroy() {
        observeJob?.cancel()
        // 服务被回收时若已没有会话，别把常驻通知留给下一个人。
        // Failed 的说明通知要留着让用户看到原因，所以这里只清 Idle 的残岛。
        if (CaptureController.state.value is CaptureController.State.Idle) cancelNotification()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.loglens.START"
        const val ACTION_STOP = "dev.loglens.STOP"
        const val ACTION_PAUSE_TOGGLE = "dev.loglens.PAUSE_TOGGLE"
        private const val CHANNEL_ID = "capture"
        private const val NOTI_ID = 1001
        /** 失败说明用的普通通知：不带前台身份，用户随时可以划掉 */
        private const val NOTI_ID_FAILED = 1002

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            // 旧版本建的是 LOW 通道，达不到上岛门槛；仅本 App 的采集通道，删了按 DEFAULT 重建
            if (existing != null) {
                if (existing.importance >= NotificationManager.IMPORTANCE_DEFAULT) return
                runCatching { nm.deleteNotificationChannel(CHANNEL_ID) }
            }
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.capture_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        fun start(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }

        /** UI 侧停止后立刻补一枪撤通知：服务那边只要有一环没走到，通知就变「残岛」 */
        fun cancelSticky(context: Context) {
            runCatching {
                context.getSystemService(NotificationManager::class.java)?.let {
                    it.cancel(NOTI_ID)
                    it.cancel(NOTI_ID_FAILED)
                }
            }
        }
    }
}
