package dev.loglens.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库行为单测。
 *
 * 锁的是三件容易回归的事：环形缓冲按 capacity 裁、attach 与 drainPending 不重复发同一批、
 * 以及「两个实例互不影响」——最后一条正是把 object 改成 class 的目的：用例之间不再互相污染。
 */
class LogRepositoryTest {

    private fun entry(id: Long, crash: Boolean = false): LogEntry = LogEntry(
        id = id,
        time = "11-27 14:03:22.123",
        pid = 100,
        tid = 101,
        level = if (crash) LogLevel.FATAL else LogLevel.INFO,
        tag = "T",
        message = "m$id",
        isCrash = crash
    )

    @Test
    fun `超过 capacity 时裁掉最旧的`() {
        val repo = LogRepository(defaultCapacity = 3)
        repeat(5) { repo.add(entry(it.toLong())) }
        assertEquals(3, repo.snapshot().size)
        // snapshot 是新→旧
        assertEquals(listOf(4L, 3L, 2L), repo.snapshot().map { it.id })
    }

    @Test
    fun `recent 按旧到新给最后 N 条`() {
        val repo = LogRepository(defaultCapacity = 10)
        repeat(6) { repo.add(entry(it.toLong())) }
        assertEquals(listOf(3L, 4L, 5L), repo.recent(3).map { it.id })
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), repo.recent(6).map { it.id })
    }

    @Test
    fun `attach 之后 drainPending 不会重复发同一批`() {
        val repo = LogRepository()
        repo.add(entry(1))
        repo.add(entry(2))
        val seeded = repo.attach()
        assertEquals(listOf(1L, 2L), seeded.map { it.id })
        assertTrue(repo.drainPending().isEmpty())
        repo.add(entry(3))
        assertEquals(listOf(3L), repo.drainPending().map { it.id })
    }

    @Test
    fun `capacity 调小立刻生效`() {
        val repo = LogRepository()
        repeat(10) { repo.add(entry(it.toLong())) }
        repo.capacity = 2
        repo.add(entry(10))
        assertTrue(repo.snapshot().size <= 2)
    }

    @Test
    fun `统计崩溃条数`() {
        val repo = LogRepository()
        repo.add(entry(1))
        repo.add(entry(2, crash = true))
        assertEquals(2L, repo.statsSnapshot().total)
        assertEquals(1, repo.statsSnapshot().crashes)
        repo.clear()
        assertEquals(0L, repo.statsSnapshot().total)
    }

    @Test
    fun `两个实例互不代表同一份缓冲`() {
        val a = LogRepository()
        val b = LogRepository()
        a.add(entry(1))
        assertTrue(b.snapshot().isEmpty())
        assertNotEquals(a.nextId(), 0L)
        // b 的序号独立，从 1 开始数
        assertEquals(1L, b.nextId())
    }
}
