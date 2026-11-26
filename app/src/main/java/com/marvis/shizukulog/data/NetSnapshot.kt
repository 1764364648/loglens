package com.marvis.shizukulog.data

import java.net.InetAddress

/**
 * 连接快照：解析 `/proc/net/{tcp,tcp6,udp,udp6}`。
 *
 * 只拿元数据（谁和谁连、什么状态、属于哪个 uid），拿不到请求内容——
 * 那是 TLS/QUIC 加密之后的字节，不在这张表里。
 *
 * 这四张表每行都带 uid 字段，所以**不需要进程名**就能按应用归类，
 * 对 shell 身份（读不到别的 App 的 /proc/<pid>/fd）特别合适。
 */
object NetSnapshot {

    data class Conn(
        val proto: String,
        val local: String,
        val remote: String,
        val state: String,
        val uid: Int,
        val inode: Long
    ) {
        val isListen: Boolean get() = state == "LISTEN"
        val isAlive: Boolean get() = state == "ESTABLISHED" || state == "SYN_SENT" || state == "SYN_RECV"
    }

    data class Snapshot(
        val conns: List<Conn>,
        val takenAt: Long
    ) {
        fun forUid(uid: Int): List<Conn> = conns.filter { it.uid == uid }
    }

    private val STATE = mapOf(
        "01" to "ESTABLISHED",
        "02" to "SYN_SENT",
        "03" to "SYN_RECV",
        "04" to "FIN_WAIT1",
        "05" to "FIN_WAIT2",
        "06" to "TIME_WAIT",
        "07" to "CLOSE",
        "08" to "CLOSE_WAIT",
        "09" to "LAST_ACK",
        "0A" to "LISTEN",
        "0B" to "CLOSING"
    )

    /** 拉一次全表：四个文件各打一个 `##名字` 标记，方便按协议归类 */
    const val COMMAND =
        "for f in tcp tcp6 udp udp6; do echo \"##\$f\"; cat /proc/net/\$f; done"

    fun parse(raw: String): List<Conn> {
        val out = ArrayList<Conn>(256)
        var proto = "TCP"
        for (line0 in raw.lineSequence()) {
            val line = line0.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("##")) {
                proto = when (line.removePrefix("##")) {
                    "tcp" -> "TCP"
                    "tcp6" -> "TCP6"
                    "udp" -> "UDP"
                    "udp6" -> "UDP6"
                    else -> proto
                }
                continue
            }
            if (line.startsWith("sl") || line.contains("local_address")) continue
            val f = line.split(Regex("\\s+"))
            if (f.size < 8) continue
            val local = decode(f[1]) ?: continue
            val remote = decode(f[2]) ?: continue
            val uid = f[7].toIntOrNull() ?: continue
            val inode = f.getOrNull(9)?.toLongOrNull() ?: 0L
            val state = if (proto.startsWith("UDP")) {
                "UDP"
            } else {
                STATE[f[3]] ?: f[3]
            }
            out.add(Conn(proto, local, remote, state, uid, inode))
        }
        return out
    }

    /** `0100007F:1F90` -> `127.0.0.1:8080`；解析失败返回 null（跳过这一行，不猜） */
    private fun decode(field: String): String? {
        val idx = field.lastIndexOf(':')
        if (idx <= 0) return null
        val hex = field.substring(0, idx)
        val port = field.substring(idx + 1).toIntOrNull(16) ?: return null
        val host = hostOf(hex) ?: return null
        return if (hex.length == 32) "[$host]:$port" else "$host:$port"
    }

    /**
     * /proc/net 里的地址是按 32 位字做小端存放的：
     * IPv4 一个 8 位十六进制字，IPv6 四个字。逐字翻转字节后交给
     * InetAddress 做格式化，比自己拼 IPv6 压缩写法靠谱。
     */
    private fun hostOf(hex: String): String? {
        val bytes = ByteArray(hex.length / 2)
        if (hex.length != 8 && hex.length != 32) return null
        var wordStart = 0
        var bi = 0
        while (wordStart < hex.length) {
            val word = hex.substring(wordStart, wordStart + 8)
            for (i in 3 downTo 0) {
                val h = word.substring(i * 2, i * 2 + 2)
                bytes[bi++] = h.toInt(16).toByte()
            }
            wordStart += 8
        }
        return runCatching { InetAddress.getByAddress(bytes).hostAddress }.getOrNull()
    }

    /** 汇总用：按 uid 归堆 */
    fun byUid(conns: List<Conn>): Map<Int, List<Conn>> =
        conns.groupBy { it.uid }
}
