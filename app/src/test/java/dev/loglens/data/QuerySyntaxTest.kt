package dev.loglens.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查询语法的单测（14KB 自研解析器，之前一条测试都没有）。
 *
 * 覆盖：key-value 各字段、`-` 取反、`&`/`|`/括号、带空格的短语、正则/等值模式、
 * 解析失败的退化行为，以及高亮词提取。全部纯 JVM，不需要 Android。
 */
class QuerySyntaxTest {

    private val env = QuerySyntax.Env(
        procNames = mapOf(1000 to "com.tencent.mm", 2000 to "dev.loglens"),
        nowMs = 1_700_000_000_000L
    )

    private fun entry(
        tag: String = "ActivityManager",
        message: String = "Start proc 4321:com.tencent.mm/u0a123",
        pid: Int = 1000,
        tid: Int = 1001,
        level: LogLevel = LogLevel.INFO,
        time: String = "2026-11-27 14:03:22.123",
        isCrash: Boolean = false,
        isContinuation: Boolean = false
    ) = LogEntry(
        id = 1, time = time, pid = pid, tid = tid, level = level,
        tag = tag, message = message, isCrash = isCrash, isContinuation = isContinuation
    )

    private fun match(query: String, e: LogEntry = entry()): Boolean {
        val program = QuerySyntax.parse(query) ?: return false
        return program.eval(e, env)
    }

    @Test
    fun `tag 包含匹配且忽略大小写`() {
        assertTrue(match("tag:activitymanager"))
        assertFalse(match("tag:WindowManager"))
    }

    @Test
    fun `tag 等值模式不吃前缀`() {
        assertTrue(match("tag=:ActivityManager"))
        assertFalse(match("tag=:Activity"))
    }

    @Test
    fun `tag 正则模式`() {
        assertTrue(match("tag~:\"^(Activity|Window)Manager$\""))
        assertFalse(match("tag~:\"^Window\""))
    }

    @Test
    fun `message 字段与裸词等价`() {
        assertTrue(match("message:Start"))
        assertTrue(match("Start"))
    }

    @Test
    fun `裸词也匹配 tag 和时间`() {
        assertTrue(match("ActivityManager"))
        // 时间只在裸词没有冒号时命中；带冒号的会被当成 key:value 语法
        assertTrue(match("2026-11-27"))
    }

    @Test
    fun `- 前缀取反`() {
        assertFalse(match("-tag:ActivityManager"))
        assertTrue(match("-tag:WindowManager"))
    }

    @Test
    fun `与号与相邻词都是 AND`() {
        assertTrue(match("tag:ActivityManager & message:Start"))
        assertTrue(match("tag:ActivityManager message:Start"))
        assertFalse(match("tag:ActivityManager message:Window"))
    }

    @Test
    fun `竖线表示或`() {
        assertTrue(match("tag:WindowManager | tag:ActivityManager"))
        assertFalse(match("tag:WindowManager | tag:Foo"))
    }

    @Test
    fun `括号改变优先级`() {
        // tag:A | tag:B & message:C   ⇒  A | (B & C)
        assertTrue(match("tag:Foo | tag:ActivityManager & message:Start"))
        assertFalse(match("(tag:Foo | tag:ActivityManager) & message:Window"))
    }

    @Test
    fun `带空格的短语用引号包住`() {
        assertTrue(match("\"Start proc\""))
        assertFalse(match("\"Start  proc\""))
    }

    @Test
    fun `level 是大于等于语义`() {
        assertFalse(match("level:e", entry(level = LogLevel.WARN)))
        assertTrue(match("level:e", entry(level = LogLevel.ERROR)))
        assertTrue(match("level:v", entry(level = LogLevel.VERBOSE)))
    }

    @Test
    fun `raw 级别按等值判 不能按大于等于`() {
        assertFalse(match("level:raw", entry(level = LogLevel.VERBOSE)))
        assertTrue(match("level:raw", entry(level = LogLevel.RAW)))
    }

    @Test
    fun `pid 支持逗号分隔多个`() {
        assertTrue(match("pid:1000,2000"))
        assertFalse(match("pid:2000"))
        assertTrue(match("tid:1001"))
    }

    @Test
    fun `package 走进程表 没有表就匹配不到`() {
        assertTrue(match("package:tencent"))
        assertFalse(match("package:tencent", entry(pid = 9999)))
    }

    @Test
    fun `is crash 命中崩溃标记和 FATAL`() {
        assertTrue(match("is:crash", entry(isCrash = true)))
        assertTrue(match("is:crash", entry(level = LogLevel.FATAL)))
        assertFalse(match("is:crash"))
    }

    @Test
    fun `is stacktrace 命中续行和 at 开头`() {
        assertTrue(match("is:stack", entry(message = "at com.example.Foo.bar(Foo.kt:42)")))
        assertTrue(match("is:stack", entry(message = "Caused by: java.lang.Exception")))
        assertTrue(match("is:stack", entry(isContinuation = true)))
        assertFalse(match("is:stack"))
    }

    @Test
    fun `解析失败返回 null 而不是抛异常`() {
        assertNull(QuerySyntax.parse("("))
        assertNull(QuerySyntax.parse("tag:"))
        assertNull(QuerySyntax.parse("\"未闭合"))
        assertNull(QuerySyntax.parse("pid:abc"))
        assertNull(QuerySyntax.parse(""))
    }

    @Test
    fun `非法正则不会炸 只是解析失败`() {
        assertNull(QuerySyntax.parse("tag~:["))
    }

    @Test
    fun `高亮词包含裸词与字段值 不包含正则`() {
        val p = QuerySyntax.parse("tag:Activity message:Start tag~:^Act")!!
        assertTrue(p.highlightTerms.contains("Activity"))
        assertTrue(p.highlightTerms.contains("Start"))
        assertFalse(p.highlightTerms.contains("^Act"))
    }

    @Test
    fun `age 依赖 Env 的当前时间`() {
        val now = 1_700_000_000_000L
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        val p = QuerySyntax.parse("age:5m")!!
        assertTrue(p.usesAge)
        val recent = entry(time = fmt.format(java.util.Date(now - 60_000)))
        val old = entry(time = fmt.format(java.util.Date(now - 600_000)))
        assertTrue(p.eval(recent, QuerySyntax.Env(nowMs = now)))
        assertFalse(p.eval(old, QuerySyntax.Env(nowMs = now)))
    }

    @Test
    fun `不认识的 key 当普通文本查`() {
        // 不认识的 key 会连冒号一起当字面量，不做字段映射
        assertFalse(match("foo:Start"))
        assertEquals(1, QuerySyntax.parse("foo:bar")!!.highlightTerms.size)
    }
}


