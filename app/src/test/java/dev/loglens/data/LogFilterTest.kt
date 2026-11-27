package dev.loglens.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 过滤器单测：级别阈值、崩溃/书签开关、tag 白/黑名单、pid 集合、关键字与正则，
 * 以及「高亮区间最多 64 段」的上限。全部纯 JVM。
 */
class LogFilterTest {

    private fun entry(
        tag: String = "ActivityManager",
        message: String = "Start proc 4321",
        pid: Int = 1000,
        level: LogLevel = LogLevel.INFO,
        isCrash: Boolean = false
    ) = LogEntry(
        id = 1, time = "2026-11-27 14:03:22.123", pid = pid, tid = 1,
        level = level, tag = tag, message = message, isCrash = isCrash
    )

    private val env = QuerySyntax.Env()

    private fun accept(filter: LogFilter, e: LogEntry, bookmarked: Boolean = false) =
        filter.compile().accept(e, bookmarked, env)

    @Test
    fun `默认过滤器全放行`() {
        assertTrue(accept(LogFilter(), entry()))
    }

    @Test
    fun `级别阈值以下被拦`() {
        val f = LogFilter(minPriority = LogLevel.WARN.priority)
        assertFalse(accept(f, entry(level = LogLevel.INFO)))
        assertTrue(accept(f, entry(level = LogLevel.WARN)))
        assertTrue(accept(f, entry(level = LogLevel.ERROR)))
    }

    @Test
    fun `onlyCrash 只放崩溃`() {
        assertFalse(accept(LogFilter(onlyCrash = true), entry()))
        assertTrue(accept(LogFilter(onlyCrash = true), entry(isCrash = true)))
    }

    @Test
    fun `onlyBookmarked 只放书签`() {
        assertFalse(accept(LogFilter(onlyBookmarked = true), entry(), bookmarked = false))
        assertTrue(accept(LogFilter(onlyBookmarked = true), entry(), bookmarked = true))
    }

    @Test
    fun `tag 白名单是 OR 语义`() {
        val f = LogFilter(tagsInclude = listOf("activity", "Window"))
        assertTrue(accept(f, entry(tag = "ActivityManager")))
        assertTrue(accept(f, entry(tag = "WindowManager")))
        assertFalse(accept(f, entry(tag = "PackageManager")))
    }

    @Test
    fun `tag 黑名单优先于白名单`() {
        val f = LogFilter(tagsInclude = listOf("Manager"), tagsExclude = listOf("window"))
        assertTrue(accept(f, entry(tag = "ActivityManager")))
        assertFalse(accept(f, entry(tag = "WindowManager")))
    }

    @Test
    fun `pid 集合只放选中的进程`() {
        val f = LogFilter(pids = setOf(1000, 2000))
        assertTrue(accept(f, entry(pid = 1000)))
        assertFalse(accept(f, entry(pid = 3000)))
    }

    @Test
    fun `关键字默认忽略大小写`() {
        assertTrue(accept(LogFilter(query = "start"), entry(message = "Start proc")))
        assertFalse(accept(LogFilter(query = "start", caseSensitive = true), entry(message = "Start proc")))
    }

    @Test
    fun `关键字也搜 tag`() {
        assertTrue(accept(LogFilter(query = "activitymanager"), entry()))
    }

    @Test
    fun `正则开关对元字符串生效`() {
        val f = LogFilter(query = ".*Exception.*", regex = true)
        val compiled = f.compile()
        assertFalse(compiled.regexInvalid)
        assertTrue(compiled.accept(entry(message = "java.lang.NullPointerException"), false, env))
        assertFalse(compiled.accept(entry(message = "all good"), false, env))
    }

    @Test
    fun `正则写错时标记异常并退化成字面量`() {
        val compiled = LogFilter(query = "[", regex = true).compile()
        assertTrue(compiled.regexInvalid)
        // 退化成关键字，至少不会整页空白
        assertTrue(compiled.accept(entry(message = "a[b"), false, env))
    }

    @Test
    fun `括号没闭合时退化成本文子串`() {
        val compiled = LogFilter(query = "(tag:A").compile()
        assertTrue(compiled.accept(entry(message = "原始行里带着 (tag:A"), false, env))
    }

    @Test
    fun `高亮最多 64 段`() {
        val many = "x".repeat(130)
        val ranges = LogFilter(query = "x").compile().highlight(many)
        assertEquals(64, ranges.size)
    }

    @Test
    fun `高亮区间定位正确`() {
        val ranges = LogFilter(query = "Exception").compile().highlight("a Exception b Exception")
        assertEquals(2, ranges.size)
        assertEquals("Exception", "a Exception b Exception".substring(ranges[0].first, ranges[0].last + 1))
    }

    @Test
    fun `空查询不高亮`() {
        assertTrue(LogFilter().compile().highlight("anything").isEmpty())
    }

    @Test
    fun `splitTags 支持中英文逗号 空格 竖线`() {
        assertEquals(
            listOf("a", "b", "c", "d"),
            LogFilter.splitTags("a,b， c | d")
        )
        assertTrue(LogFilter.splitTags("   ").isEmpty())
    }
}

