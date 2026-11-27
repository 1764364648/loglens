package dev.loglens.capture

import android.content.Context
import dev.loglens.data.AppRepository
import dev.loglens.data.LogBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 采集会话的唯一入口。Activity / Service 都通过它操作，避免 Binder 与生命周期耦合。
 *
 * 这里只剩门面：一个 [CaptureSession] 实例持有全部会话状态，本对象只把调用转发过去。
 * 之所以还留着一层 object，是因为 88 个调用点都写成 `CaptureController.xxx`；
 * 把状态搬进实例之后，单测可以 `install(CaptureSession(假依赖))` 而不必再改全局开关。
 *
 * 未 `init` 时（理论上只有 Application 之前才会发生）所有方法静默不做事，
 * 状态流给一个恒为 Idle 的占位。
 */
object CaptureController {

    data class Target(val packageName: String, val label: String, val uid: Int)

    sealed interface State {
        data object Idle : State
        data class Preparing(val label: String) : State
        data class Capturing(
            val target: Target,
            val mode: String,
            val command: String,
            val startedAt: Long
        ) : State

        data class Failed(val message: String) : State
    }

    /** 采集会话的启停参数，暂停后据此原样恢复，不重新清空已捕获日志 */
    data class StartArgs(
        val app: AppRepository.AppItem,
        val saveToFile: Boolean,
        val includeEvents: Boolean,
        val globalMode: Boolean,
        val buffers: Set<LogBuffer>
    )

    private var impl: CaptureSession? = null

    // init 之前的占位流。init 在 Application.onCreate 最早执行，UI 之后才开始收集，不会撞上。
    private val idleState = MutableStateFlow<State>(State.Idle)
    private val idleFalse = MutableStateFlow(false)
    private val idleNotice = MutableStateFlow<String?>(null)
    private val idleFile = MutableStateFlow<String?>(null)

    /** 由 `LogApp.onCreate` 调用，装上真实依赖（Shizuku / logcat / 落盘 / 前台服务）。 */
    fun init(context: Context) {
        impl = CaptureSession(CaptureDeps.production(context))
    }

    /** 单测注入独立会话。生产代码不调用。 */
    internal fun install(session: CaptureSession) {
        impl = session
    }

    val state: StateFlow<State> get() = impl?.state ?: idleState
    val passiveState: StateFlow<Boolean> get() = impl?.passiveState ?: idleFalse
    val paused: StateFlow<Boolean> get() = impl?.paused ?: idleFalse
    val notice: StateFlow<String?> get() = impl?.notice ?: idleNotice
    val logFile: StateFlow<String?> get() = impl?.logFile ?: idleFile
    val logChannelBlocked: StateFlow<Boolean> get() = impl?.logChannelBlocked ?: idleFalse

    fun dismissChannelBlocked() {
        impl?.dismissChannelBlocked()
    }

    fun isCapturing(): Boolean = impl?.isCapturing() ?: false

    fun consumeNotice(): String? = impl?.consumeNotice()

    fun start(
        app: AppRepository.AppItem,
        saveToFile: Boolean,
        includeEvents: Boolean,
        globalMode: Boolean = false,
        buffers: Set<LogBuffer> = LogBuffer.DEFAULT
    ) {
        impl?.start(app, saveToFile, includeEvents, globalMode, buffers)
    }

    fun startPassive(app: AppRepository.AppItem) {
        impl?.startPassive(app)
    }

    fun isPassive(): Boolean = impl?.isPassive() ?: false

    fun pause() {
        impl?.pause()
    }

    fun resume() {
        impl?.resume()
    }

    fun isPaused(): Boolean = impl?.isPaused() ?: false

    fun stop() {
        impl?.stop()
    }

    fun dismissFailure() {
        impl?.dismissFailure()
    }
}
