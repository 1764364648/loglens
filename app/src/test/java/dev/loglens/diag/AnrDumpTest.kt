package dev.loglens.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `dumpsys activity lastanr` 文本解析的单测。
 *
 * 锁的是三件事：ANR 进程名/包名的宽松匹配（`ANR in <pkg> (<process>)` 两种写法都见过）、
 * Reason 文本到 AnrTypes 的推断（推错比推不出更糟，所以只做确定性关键字）、
 * 以及「最近一次 ANR 不属于目标包」时必须让卡片空着而不是串包。
 */
class AnrDumpTest {

    private val pkg = "com.example.demo"

    private val inputDispatch = """
        ACTIVITY MANAGER LAST ANR (dumpsys activity lastanr)
          ANR in com.example.demo (com.example.demo/.MainActivity)
          PID: 4321
          Reason: Input dispatching timed out (com.example.demo/com.example.demo/.MainActivity is not responding. Waited 5000ms for MotionEvent)
          Parent: com.example.demo/.MainActivity
    """.trimIndent()

    @Test
    fun `input dispatching 抓出进程 pid 类型和 5000ms 阈值`() {
        val (items, err) = AnrRepo.parseLastAnr(inputDispatch, pkg)
        assertNull(err)
        assertEquals(1, items.size)
        val a = items[0]
        assertEquals("com.example.demo/.MainActivity", a.process)
        assertEquals(2, a.anrType)
        assertEquals("INPUT_DISPATCH", a.typeName)
        assertEquals(5000L, a.timeoutMs)
        assertTrue("PID 要带进描述里", a.description.contains("PID 4321"))
        assertEquals(AnrRepo.SOURCE_SHELL, a.source)
        assertEquals(-1, a.anrId)
    }

    @Test
    fun `前台广播超时 推断类型但没有阈值`() {
        val dump = """
            ACTIVITY MANAGER LAST ANR (dumpsys activity lastanr)
              ANR in com.example.demo (com.example.demo:push)
              PID: 900
              Reason: Broadcast of Intent { act=android.intent.action.BOOT_COMPLETED }
        """.trimIndent()
        val (items, _) = AnrRepo.parseLastAnr(dump, pkg)
        assertEquals(1, items.size)
        assertEquals(3, items[0].anrType)
        assertEquals("com.example.demo:push", items[0].process)
        assertEquals(-1L, items[0].timeoutMs)
        assertEquals("未给出", items[0].timeoutText)
    }

    @Test
    fun `开机以来没有 ANR 时卡片留空并给出原因`() {
        val dump = """
            ACTIVITY MANAGER LAST ANR (dumpsys activity lastanr)
              <no ANR has occurred since boot>
        """.trimIndent()
        val (items, err) = AnrRepo.parseLastAnr(dump, pkg)
        assertTrue(items.isEmpty())
        assertEquals("开机以来没有发生过 ANR", err)
    }

    @Test
    fun `最近一次 ANR 属于别的包时不许串包`() {
        val dump = """
            ACTIVITY MANAGER LAST ANR (dumpsys activity lastanr)
              ANR in com.other.app (com.other.app/.Main)
              PID: 777
              Reason: Input dispatching timed out (Waited 5000ms for MotionEvent)
        """.trimIndent()
        val (items, err) = AnrRepo.parseLastAnr(dump, pkg)
        assertTrue("别的包的 ANR 不能算到目标包头上", items.isEmpty())
        assertTrue(err.orEmpty().contains("不是目标包"))
    }

    @Test
    fun `service 超时与 15000ms 阈值`() {
        val dump = """
            ACTIVITY MANAGER LAST ANR (dumpsys activity lastanr)
              ANR in com.example.demo (com.example.demo)
              PID: 12
              Reason: executing service com.example.demo/.SyncService, waited 15000 ms
        """.trimIndent()
        val (items, _) = AnrRepo.parseLastAnr(dump, pkg)
        assertEquals(1, items.size)
        assertEquals(5, items[0].anrType)
        assertEquals("EXECUTE_SERVICE", items[0].typeName)
        assertEquals(15000L, items[0].timeoutMs)
    }

    @Test
    fun `reason 认不出来时归因为负一而不是瞎猜`() {
        assertEquals(-1, AnrRepo.typeFromReason("something totally unknown"))
        assertEquals(-1L, AnrRepo.timeoutFromReason("no number here"))
    }
}
