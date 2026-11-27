package dev.loglens.capture

import dev.loglens.data.AppGraph
import dev.loglens.data.AppRepository
import dev.loglens.data.LogBuffer
import dev.loglens.data.LogEntry
import dev.loglens.logcat.LogSink
import dev.loglens.logcat.LogcatEngine
import dev.loglens.logcat.LogcatSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `CaptureController` 状态机单测。
 *
 * 这个 object 之前零测试，因为它直接吃 `Context` + Shizuku + 真实 logcat 进程，
 * 只能靠真机手动点。用 [CaptureDeps] 注入假引擎与假写入器后，能把「启动中的
 * 时间窗」拉长到几秒，把三类只会在真机上偶发的错钉死：
 *
 *  1. 点了开始、还没等 logcat 起来就点停止 —— 必须回 Idle，且不留写入器；
 *  2. 停止之后旧引擎才回调 onStarted —— 采集不能「复活」；
 *  3. 上一个引擎晚到的 onExit —— 不能把新会话清成 Idle。
 */
class CaptureControllerTest {

    private val sources = CopyOnWriteArrayList<FakeSource>()

    private class FakeSource(private val listener: LogcatEngine.Listener) : LogcatSource {
        var starts = 0
        var stops = 0

        override fun start(
            packageName: String,
            uid: Int?,
            pid: Int?,
            includeEvents: Boolean,
            globalMode: Boolean,
            buffers: Set<LogBuffer>
        ) {
            starts++
        }

        override fun stop() {
            stops++
        }

        fun started(mode: String = "main", command: String = "logcat ...") =
            listener.onStarted(mode, command)

        fun exited(code: Int = 0) = listener.onExit(code)
    }

    private class FakeSink(private val gate: CountDownLatch?) : LogSink {
        override var onFatal: ((String) -> Unit)? = null
        override val currentFile: File = File("fake-sink.log")

        var starts = 0
        var stops = 0
        val startEntered = CountDownLatch(1)

        override fun start(packageName: String) {
            starts++
            startEntered.countDown()
            // 卡在这里模拟「文件还没开完」的窗口，测试据此在启动中途点停止
            gate?.await(3, TimeUnit.SECONDS)
        }

        override fun enqueue(entry: LogEntry) {}

        override fun stop() {
            stops++
        }
    }

    private val app = AppRepository.AppItem(
        packageName = "dev.loglens.test",
        label = "测试应用",
        uid = 10_123,
        isSystem = false
    )

    /** 每个用例可在这里换掉落盘目标（有的用例要卡在 start 中途） */
    private var sinkFactory: () -> LogSink = { FakeSink(null) }

    private lateinit var session: CaptureSession

    @Before
    fun setUp() {
        // 采集会话里 AppGraph.logs 是 lateinit：真机上由 Application 装好，单测里显式装一次
        AppGraph.install()
        sources.clear()
        sinkFactory = { FakeSink(null) }
        // 依赖全部显式给：没有 init(context)、没有全局静态开关，会话自己就是一份完整状态
        session = CaptureSession(
            CaptureDeps(
                appContext = null,
                logs = { AppGraph.logs },
                procRefresh = null,
                binderAlive = { true },
                permissionGranted = { true },
                uidOf = { app.uid },
                pidsFor = { _, _ -> listOf(4242) },
                newSource = { listener -> FakeSource(listener).also { sources.add(it) } },
                newSink = { sinkFactory() },
                startService = {},
            )
        )
        CaptureController.install(session)
    }

    @After
    fun tearDown() {
        CaptureController.stop()
    }

    private fun awaitTrue(timeoutMs: Long = 2_000, pred: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (pred()) return true
            Thread.sleep(5)
        }
        return pred()
    }

    private fun state() = CaptureController.state.value

    @Test
    fun stopDuringStartupLeavesIdleAndClosesSinkOnce() {
        val gate = CountDownLatch(1)
        val sinks = CopyOnWriteArrayList<FakeSink>()
        sinkFactory = { FakeSink(gate).also { sinks.add(it) } }

        CaptureController.start(app, saveToFile = true, includeEvents = false)
        assertTrue("sinks=${sinks.size} state=${state()}", awaitTrue { sinks.isNotEmpty() })
        assertTrue("写入器应卡在启动中", sinks.single().startEntered.await(2, TimeUnit.SECONDS))

        CaptureController.stop()
        gate.countDown()

        assertTrue("启动中被停止必须回 Idle", awaitTrue { state() is CaptureController.State.Idle })
        assertTrue("fake 写入器必须被关掉", awaitTrue { sinks.single().stops >= 1 })
        // 关键是次数：同一写入器被关两次说明清理路径重复了
        Thread.sleep(100)
        assertEquals("写入器只应关一次", 1, sinks.single().stops)
        assertEquals("提前返回不得拉起引擎", 0, sources.size)
    }

    @Test
    fun normalStartThenStopClosesEngineOnce() {
        CaptureController.start(app, saveToFile = true, includeEvents = false)
        assertTrue("sources=${sources.size} state=${state()}", awaitTrue { sources.isNotEmpty() })
        val eng = sources.single()
        eng.started()
        assertTrue(awaitTrue { state() is CaptureController.State.Capturing })

        CaptureController.stop()

        assertTrue("停止后必须回 Idle", awaitTrue { state() is CaptureController.State.Idle })
        assertTrue("引擎必须被 stop", awaitTrue { eng.stops >= 1 })
        assertEquals("只能拉起一个引擎", 1, sources.size)
        assertEquals("引擎只应 stop 一次", 1, eng.stops)
    }

    @Test
    fun staleStartedAfterStopDoesNotResurrectCapturing() {
        CaptureController.start(app, saveToFile = false, includeEvents = false)
        assertTrue(awaitTrue { sources.isNotEmpty() })
        val eng = sources.single()

        CaptureController.stop()
        assertTrue(awaitTrue { state() is CaptureController.State.Idle })

        // 真机时序：stop 之后 IO 线程才把 onStarted 回调出来
        eng.started()

        Thread.sleep(100)
        assertTrue("停止后的 onStarted 不得把采集复活", state() is CaptureController.State.Idle)
    }

    @Test
    fun lateExitFromPreviousEngineDoesNotClobberNewSession() {
        CaptureController.start(app, saveToFile = false, includeEvents = false)
        assertTrue("sources=${sources.size} state=${state()}", awaitTrue { sources.size == 1 })
        val first = sources[0]
        first.started()
        assertTrue(awaitTrue { state() is CaptureController.State.Capturing })

        first.exited()
        assertTrue(awaitTrue { state() is CaptureController.State.Idle })

        CaptureController.start(app, saveToFile = false, includeEvents = false)
        assertTrue(awaitTrue { sources.size == 2 })
        val second = sources[1]
        second.started()
        assertTrue(awaitTrue { state() is CaptureController.State.Capturing })

        // 上一个引擎的退出线程晚到：它不归属当前会话，不能把新会话清掉
        first.exited()

        Thread.sleep(150)
        assertTrue("旧引擎的 onExit 不得清掉新会话", state() is CaptureController.State.Capturing)
        assertEquals("新引擎不应被旧回调 stop", 0, second.stops)
    }

    /**
     * 这条是重构本身的验收点：会话状态属于实例，不再是全进程一份。
     * 两台互不相干的会话同时跑，门面里注入的那台停掉，另一台必须还在采。
     */
    @Test
    fun sessionStateIsPerInstanceNotGlobal() {
        val otherSources = CopyOnWriteArrayList<FakeSource>()
        val other = CaptureSession(
            CaptureDeps(
                appContext = null,
                logs = { AppGraph.logs },
                procRefresh = null,
                binderAlive = { true },
                permissionGranted = { true },
                uidOf = { app.uid },
                pidsFor = { _, _ -> emptyList() },
                newSource = { listener -> FakeSource(listener).also { otherSources.add(it) } },
                newSink = { FakeSink(null) },
                startService = {},
            )
        )

        other.start(app, saveToFile = false, includeEvents = false)
        assertTrue("otherSources=${otherSources.size}", awaitTrue { otherSources.isNotEmpty() })
        otherSources.single().started()
        assertTrue("另一台会话应进入 Capturing", awaitTrue { other.state.value is CaptureController.State.Capturing })

        // 门面里注入的是另一台，状态流互不可见
        assertTrue("门面不应看到另一台会话的状态", state() is CaptureController.State.Idle)

        CaptureController.stop()
        Thread.sleep(120)
        assertTrue("停掉门面那台不得影响另一台", other.state.value is CaptureController.State.Capturing)
        assertEquals("另一台的引擎不应被 stop", 0, otherSources.single().stops)

        other.stop()
        assertTrue("自己 stop 后才回 Idle", awaitTrue { other.state.value is CaptureController.State.Idle })
    }
}
