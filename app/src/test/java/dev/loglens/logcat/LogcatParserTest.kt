package dev.loglens.logcat

import dev.loglens.data.LogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * logcat 文本解析的单测。
 *
 * 这些用例都喂死字符串、不碰 Android、不需要真机：`threadtime` 正则、events 的 `brief` 格式、
 * 续行判定、跨年补全，全在这里锁住。跨年那条尤其重要——它是启发式（m < lastMonth - 6），
 * 写错的表现是「日志顺序全乱」，在真机上极难复现。
 */
class LogcatParserTest {

    private var nextId = 0L

    @Before
    fun setUp() {
        nextId = 0
        LogcatParser.resetClock()
    }

    private fun parse(line: String) = LogcatParser.parse(line) { nextId++ }

    @Test
    fun `threadtime 行解析出全部字段`() {
        val e = parse("11-27 14:03:22.123  1234  1250 I ActivityManager: Start proc 4321:com.tencent.mm/u0a123")
        assertNotNull(e)
        e!!
        assertEquals(1234, e.pid)
        assertEquals(1250, e.tid)
        assertEquals(LogLevel.INFO, e.level)
        assertEquals("ActivityManager", e.tag)
        assertEquals("Start proc 4321:com.tencent.mm/u0a123", e.message)
        assertFalse(e.isRaw)
        assertTrue(e.time.endsWith("14:03:22.123"))
        // id 由调用方给，必须连续
        assertEquals(0L, e.id)
    }

    @Test
    fun `tag 两侧空白被裁掉`() {
        val e = parse("11-27 14:03:22.123  1234  1250 W SomeTag   : hello")
        assertEquals("SomeTag", e!!.tag)
        assertEquals("hello", e.message)
    }

    @Test
    fun `tag 为空时退化成短横线`() {
        val e = parse("11-27 14:03:22.123  1234  1250 I   : msg")
        assertEquals("-", e!!.tag)
    }

    @Test
    fun `events brief 格式解析`() {
        val e = parse("11-27 14:03:22.123 I/am_proc_start( 1234): [0,4321,com.tencent.mm,content_provider]")
        e!!
        assertEquals(1234, e.pid)
        assertEquals(0, e.tid)
        assertEquals(LogLevel.INFO, e.level)
        assertEquals("am_proc_start", e.tag)
        assertTrue(e.message.contains("com.tencent.mm"))
    }

    @Test
    fun `没有时间戳的行返回 null 交给调用方当续行`() {
        assertNull(parse("    at com.example.Foo.bar(Foo.kt:42)"))
        assertNull(parse("Caused by: java.lang.NullPointerException"))
    }

    @Test
    fun `有时间戳但格式不认识的行按原始行保留`() {
        val e = parse("11-27 14:03:22.123 这不是 threadtime 也不是 brief")
        e!!
        assertTrue(e.isRaw)
        assertEquals(LogLevel.RAW, e.level)
        assertEquals("raw", e.tag)
        assertEquals("", e.time)
    }

    @Test
    fun `崩溃标记命中`() {
        assertTrue(LogcatParser.isCrash("FATAL EXCEPTION: main"))
        assertTrue(LogcatParser.isCrash("ANR in com.tencent.mm"))
        assertTrue(LogcatParser.isCrash("signal 11 (SIGSEGV)"))
        assertFalse(LogcatParser.isCrash("just a normal line"))

        val e = parse("11-27 14:03:22.123  1234  1250 E AndroidRuntime: FATAL EXCEPTION: main")
        assertTrue(e!!.isCrash)
    }

    @Test
    fun `跨年推断：11 月之后遇到 1 月要进位到下一年`() {
        val nov = parse("11-20 10:00:00.000  1  1 I T: a")!!
        val jan = parse("01-05 10:00:00.000  1  1 I T: b")!!
        val novYear = nov.time.substring(0, 4).toInt()
        val janYear = jan.time.substring(0, 4).toInt()
        assertEquals(novYear + 1, janYear)
        assertEquals("01-05", jan.time.substring(5, 10))
    }

    @Test
    fun `跨年推断：12 月之后遇到 1 月同样进位`() {
        val dec = parse("12-31 23:59:59.000  1  1 I T: a")!!
        val jan = parse("01-01 00:00:01.000  1  1 I T: b")!!
        assertEquals(dec.time.substring(0, 4).toInt() + 1, jan.time.substring(0, 4).toInt())
    }

    @Test
    fun `同一个月内的日志年份不变`() {
        val a = parse("11-20 10:00:00.000  1  1 I T: a")!!
        val b = parse("11-25 10:00:00.000  1  1 I T: b")!!
        assertEquals(a.time.substring(0, 4), b.time.substring(0, 4))
    }

    @Test
    fun `时间戳前缀判断`() {
        assertTrue(LogcatParser.startsWithTimestamp("11-27 14:03:22.123 whatever"))
        assertFalse(LogcatParser.startsWithTimestamp("    at foo(Bar.kt:1)"))
    }

    @Test
    fun `pid 非数字时归零而不是抛异常`() {
        val e = parse("11-27 14:03:22.123  abc  def I T: msg")
        // pid/tid 非数字的行不匹配 threadtime 正则，因此落到「原始行」分支
        assertTrue(e!!.isRaw)
    }

    // ── -v year：行首多一个 yyyy-，解析器必须认，否则整份文件会被并成一条续行 ──

    @Test
    fun `带年份的 threadtime 行按年份解析，不再按单调不减猜`() {
        val e = parse("2026-11-27 14:03:22.123  1234  1250 I ActivityManager: Start proc 1:x/u0a1")!!
        assertEquals("2026-11-27 14:03:22.123", e.time)
        assertEquals(1234, e.pid)
        assertEquals("ActivityManager", e.tag)
        assertFalse(e.isRaw)
    }

    @Test
    fun `带年份的 events brief 也能解析`() {
        val e = parse("2026-11-27 14:03:22.123 I/am_proc_start( 1234): [0,4321,com.a,activity]")!!
        assertEquals("2026-11-27 14:03:22.123", e.time)
        assertEquals("am_proc_start", e.tag)
        assertEquals(0, e.tid)
    }

    @Test
    fun `带年份的行不会被当成续行`() {
        assertTrue(LogcatParser.startsWithTimestamp("2026-11-27 14:03:22.123 x"))
        val e = parse("2026-11-27 14:03:22.123 不是 threadtime 也不是 brief")!!
        assertTrue(e.isRaw)
    }

    @Test
    fun `带年份与不带年份混排时年份不串`() {
        val withYear = parse("2026-11-27 14:03:22.123  1  1 I T: a")!!
        assertEquals("2026", withYear.time.substring(0, 4))
        // 后一行没有年份，应沿用刚由 -v year 确立的年份，而不是回落到「今年」
        val noYear = parse("11-28 10:00:00.000  1  1 I T: b")!!
        assertEquals("2026", noYear.time.substring(0, 4))
    }
}
