package dev.loglens.data

import dev.loglens.shizuku.ShellRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程名表的单测：取数方式从构造函数注入，用固定输出顶掉真正的 Shizuku shell。
 *
 * 锁三件事：两种 ps 列序都能解析、`ps -A -o PID,NAME` 失败会退到老式 `ps -A`、
 * 以及拉取失败时保留旧表（过滤条件不会突然全空）。
 */
class ProcNamesTest {

    /** 记录调用过的命令，按脚本返回 */
    private class FakeShell(vararg script: Pair<String, ShellRunner.Result>) {
        val calls = mutableListOf<String>()
        private val map = script.toMap()

        fun exec(cmd: String, @Suppress("UNUSED_PARAMETER") timeout: Long): ShellRunner.Result {
            calls.add(cmd)
            return map[cmd] ?: ShellRunner.Result(1, "", "not scripted")
        }
    }

    @Test
    fun `新式 PID NAME 列序能解析`() {
        val shell = FakeShell(
            "ps -A -o PID,NAME" to ShellRunner.Result(
                0,
                """
                    PID NAME
                    1 init
                    4321 com.tencent.mm
                    4322 com.tencent.mm:push
                """.trimIndent(),
                ""
            )
        )
        val procs = ProcNames { cmd, t -> shell.exec(cmd, t) }
        assertTrue(procs.refresh())
        assertEquals("com.tencent.mm", procs.nameOf(4321))
        assertEquals("com.tencent.mm:push", procs.nameOf(4322))
        assertNull(procs.nameOf(9999))
        assertEquals(listOf("ps -A -o PID,NAME"), shell.calls)
    }

    @Test
    fun `新式命令失败时退回老式 ps -A`() {
        val shell = FakeShell(
            "ps -A -o PID,NAME" to ShellRunner.Result(1, "", "unknown option"),
            "ps -A" to ShellRunner.Result(
                0,
                """
                    USER      PID   PPID  NAME
                    root        1     0   init
                    u0_a86   20915  1   com.marvis.shizukulog
                """.trimIndent(),
                ""
            )
        )
        val procs = ProcNames { cmd, t -> shell.exec(cmd, t) }
        assertTrue(procs.refresh())
        assertEquals("com.marvis.shizukulog", procs.nameOf(20915))
    }

    @Test
    fun `两条命令都失败时保留旧表`() {
        val ok = ShellRunner.Result(0, "PID NAME\n1 init\n42 com.a", "")
        var respond: ShellRunner.Result = ok
        val procs = ProcNames { _, _ -> respond }
        assertTrue(procs.refresh())
        assertEquals("com.a", procs.nameOf(42))

        respond = ShellRunner.Result(1, "", "Shizuku 断连")
        assertFalse(procs.refresh())
        assertEquals("com.a", procs.nameOf(42))
    }

    @Test
    fun `空表算过期，拉过一次后不算`() {
        var respond = ShellRunner.Result(0, "PID NAME\n1 init", "")
        val procs = ProcNames { _, _ -> respond }
        assertTrue(procs.isStale())
        assertTrue(procs.refresh())
        assertFalse(procs.isStale())

        procs.clear()
        assertTrue(procs.isStale())
        respond = ShellRunner.Result(0, "", "")
        assertFalse(procs.refresh())
    }
}
