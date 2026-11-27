package dev.loglens.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ps 表解析的单测。
 *
 * 这里锁的是「收不到日志」的高发原因：两种列格式（`PID UID ARGS` 与 `USER PID PPID ... NAME`）、
 * `u0_a123` 用户名换 uid、`包名:子进程` 的前缀匹配，以及 ARGS 带路径/参数时的取值。
 */
class PsTableTest {

    private val pkg = "com.tencent.mm"
    private val uid = 10123

    @Test
    fun `PID UID ARGS 格式 精确名与子进程都收`() {
        val ps = """
            PID   UID   ARGS
            4321  10123 com.tencent.mm
            4322  10123 com.tencent.mm:push
            4323  10123 com.tencent.mm:appbrand0
        """.trimIndent()
        val got = ShellRunner.parsePsTable(ps, pkg, uid)
        assertEquals(listOf(4321, 4322, 4323), got)
    }

    @Test
    fun `ARGS 带路径和参数时取首段再取文件名`() {
        val ps = """
            PID UID ARGS
            4321 10123 /data/app/~~xx==/com.tencent.mm-yy==/base.apk --arg 1
        """.trimIndent()
        assertEquals(listOf(4321), ShellRunner.parsePsTable(ps, pkg, uid))
    }

    @Test
    fun `USER PID PPID 兜底格式`() {
        val ps = """
            USER      PID   PPID  NAME
            u0_a123   4321  1000  com.tencent.mm
            u0_a123   4322  1000  com.tencent.mm:push
        """.trimIndent()
        assertEquals(listOf(4321, 4322), ShellRunner.parsePsTable(ps, pkg, uid))
    }

    @Test
    fun `u0_a123 换算成 uid 10123`() {
        // 名字对不上，只能靠 uid 命中
        val ps = """
            USER      PID   PPID  NAME
            u0_a123   4321  1000  some.other.process
        """.trimIndent()
        assertEquals(listOf(4321), ShellRunner.parsePsTable(ps, pkg, uid))
    }

    @Test
    fun `别的应用不会被收进来`() {
        val ps = """
            PID   UID   ARGS
            4321  10123 com.tencent.mm
            5000  10200 com.example.other
        """.trimIndent()
        assertEquals(listOf(4321), ShellRunner.parsePsTable(ps, pkg, uid))
    }

    @Test
    fun `前缀匹配不能误伤相似包名`() {
        val ps = """
            PID   UID   ARGS
            4321  10123 com.tencent.mm
            4322  10200 com.tencent.mmfoo
        """.trimIndent()
        assertEquals(listOf(4321), ShellRunner.parsePsTable(ps, pkg, uid))
    }

    @Test
    fun `pid 0 和 1 被跳过`() {
        val ps = """
            PID   UID   ARGS
            1     10123 com.tencent.mm
            0     10123 com.tencent.mm
            4321  10123 com.tencent.mm
        """.trimIndent()
        assertEquals(listOf(4321), ShellRunner.parsePsTable(ps, pkg, uid))
    }

    @Test
    fun `表头和不完整行被忽略`() {
        val ps = """
            PID UID ARGS
            garbage
            4321 10123 com.tencent.mm
        """.trimIndent()
        assertEquals(listOf(4321), ShellRunner.parsePsTable(ps, pkg, uid))
    }

    @Test
    fun `uid 为 0 时不靠 uid 命中 只靠名字`() {
        val ps = """
            PID   UID   ARGS
            4321  10123 com.tencent.mm
        """.trimIndent()
        assertEquals(listOf(4321), ShellRunner.parsePsTable(ps, pkg, 0))
    }

    @Test
    fun `空表返回空列表`() {
        assertTrue(ShellRunner.parsePsTable("", pkg, uid).isEmpty())
        assertTrue(ShellRunner.parsePsTable("PID UID ARGS", pkg, uid).isEmpty())
    }

    @Test
    fun `结果不重复`() {
        val ps = """
            PID   UID   ARGS
            4321  10123 com.tencent.mm
            4321  10123 com.tencent.mm:push
        """.trimIndent()
        assertEquals(listOf(4321), ShellRunner.parsePsTable(ps, pkg, uid))
    }
}
