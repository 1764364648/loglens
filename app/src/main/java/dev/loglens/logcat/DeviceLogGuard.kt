package dev.loglens.logcat

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.loglens.shizuku.ShellRunner

/**
 * 国产 ROM（重点 荣耀 / 华为）日志通路的自检与引导。
 *
 * 实测与官方口径都已确认的机制：
 * 1. 荣耀 / 华为机型默认关闭「AP 日志」（应用日志）。此时无论是 `adb logcat` 还是 Shizuku
 *    shell 里的 logcat，都只会吐出极少量系统日志（常常就 3 条左右），应用层日志根本不下发。
 *    现象与「代码过滤写错了」几乎一样，但改代码永远修不好。
 * 2. 打开方式：拨号盘输入 `*#*#2846579#*#*` → 后台设置 → LOG 设置 / AP LOG 设置 → 打开 AP 日志。
 *    MagicOS 11 仍然有效（荣耀官方社区 2026 年仍在用这条路径指导用户开日志）。
 *    注意：部分机型重启后会复原该开关，需要重新打开。
 * 3. 华为另有 `persist.log.tag` 控制默认日志级别，可用 `setprop persist.log.tag V` 放宽
 *    （shell 身份通常无权写 persist 属性，会失败，失败原因也一并回报）。
 *
 * 因此本类只做三件事：读设备档案、做「写入即读回」的通路探针、给出一键引导。不做任何猜测性修复。
 */
object DeviceLogGuard {

    /** 荣耀 / 华为工程菜单码，拨号盘输入自动进入 */
    const val ENGINEER_CODE = "*#*#2846579#*#*"

    const val GUIDE: String =
        "荣耀/华为 ROM 默认关闭「AP 日志」，此时 logcat 只有极少量系统日志。" +
            "修复：拨号键盘输入 $ENGINEER_CODE → 后台设置 → LOG设置 / AP LOG设置 → 打开 AP日志，" +
            "然后回到本 App 重新开始捕获（部分机型重启后会复原该开关，需重新打开）。"

    data class Profile(
        val brand: String,
        val manufacturer: String,
        val model: String,
        val magicOs: String,
        val emui: String,
        val android: String,
        val logTag: String,
        val buffer: String
    ) {
        val isHonorHuawei: Boolean
            get() = listOf(brand, manufacturer, magicOs, emui).any {
                it.contains("honor", true) || it.contains("huawei", true) ||
                    it.contains("magic", true) || it.contains("emui", true)
            }

        fun describe(): String = buildString {
            append(brand.ifBlank { "未知品牌" })
            if (model.isNotBlank()) append(' ').append(model)
            val ver = when {
                magicOs.isNotBlank() -> "MagicOS $magicOs"
                emui.isNotBlank() -> "EMUI $emui"
                else -> ""
            }
            if (ver.isNotBlank()) append(" · ").append(ver)
            if (android.isNotBlank()) append(" · Android ").append(android)
            append(" · persist.log.tag=").append(logTag.ifBlank { "空" })
            if (buffer.isNotBlank()) append(" · ").append(buffer)
        }
    }

    data class SelfTest(val ok: Boolean, val detail: String)

    @Volatile
    var lastProfile: Profile? = null
        private set

    fun getprop(key: String): String = runCatching {
        ShellRunner.exec("getprop $key", 6_000).stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }.getOrDefault("")

    /** 一次性读取设备档案（品牌 / 机型 / MagicOS 或 EMUI 版本 / 日志级别属性 / 缓冲区大小） */
    fun probe(): Profile {
        val keys = listOf(
            "ro.product.brand",
            "ro.product.manufacturer",
            "ro.product.model",
            "ro.build.version.magic",
            "ro.build.version.emui",
            "ro.build.version.release",
            "persist.log.tag"
        )
        val lines = runCatching {
            ShellRunner.exec(keys.joinToString("; ") { "getprop $it" }, 15_000)
                .stdout.lineSequence().map { it.trim() }.toList()
        }.getOrDefault(emptyList())
        fun at(i: Int) = lines.getOrNull(i).orEmpty()

        val buffer = runCatching {
            val r = ShellRunner.exec("logcat -g 2>&1", 8_000)
            (r.stdout + r.stderr).lineSequence()
                .firstOrNull { it.trimStart().startsWith("main:") }
                ?.substringAfter("ring buffer is", "")
                ?.substringBefore(",")
                ?.trim()
                .orEmpty()
        }.getOrDefault("")

        val profile = Profile(
            brand = at(0),
            manufacturer = at(1),
            model = at(2),
            magicOs = at(3),
            emui = at(4),
            android = at(5),
            logTag = at(6),
            buffer = if (buffer.isBlank()) "" else "main 缓冲 $buffer"
        )
        lastProfile = profile
        return profile
    }

    /**
     * 通路探针：以 shell 身份写一条带随机 token 的日志，再立刻从 logcat 读回来。
     * 读不回来 = 系统正在丢弃 / 加密日志输出（荣耀华为的 AP 日志关闭就是这种表现）。
     */
    fun selfTest(): SelfTest {
        val token = "SLPROBE" + System.currentTimeMillis().toString().takeLast(6)

        val write = runCatching { ShellRunner.exec("log -p i -t LogLens $token", 8_000) }
            .getOrNull() ?: return SelfTest(true, "探针写入未执行，跳过通道判定")
        val writeText = write.stdout + write.stderr
        if (writeText.contains("not found") || writeText.contains("inaccessible") ||
            writeText.contains("Permission denied")
        ) {
            return SelfTest(true, "系统未提供 log 写入命令，跳过通道判定")
        }

        runCatching { Thread.sleep(700) }

        // 用 grep 服务端筛出探针那一行，避免高刷场景下 token 被最近 N 行冲掉造成误判
        val text = runCatching {
            val r = ShellRunner.exec(
                "logcat -d -b main,system,crash -t 8000 2>&1 | grep -F $token | head -n 3",
                20_000
            )
            r.stdout + r.stderr
        }.getOrDefault("")

        return if (text.contains(token)) {
            SelfTest(true, "日志通道自检通过：shell 写入的探针日志可被 logcat 读回")
        } else {
            SelfTest(false, "日志通道自检失败：探针日志无法从 logcat 读回，系统在丢弃/加密日志输出")
        }
    }

    /** 尝试把默认日志级别放宽到 verbose（华为 persist.log.tag）；失败也返回真实原因 */
    fun raiseLevel(): String {
        val before = getprop("persist.log.tag")
        if (before.equals("V", true) || before.equals("Verbose", true)) {
            return "persist.log.tag 已是 $before，无需调整"
        }
        val result = runCatching { ShellRunner.exec("setprop persist.log.tag V", 8_000) }.getOrNull()
        val after = getprop("persist.log.tag")
        val msg = ((result?.stdout ?: "") + (result?.stderr ?: "")).trim()
        return if (after.equals("V", true)) {
            "已将 persist.log.tag 由「${before.ifBlank { "空" }}」提升为 V（重启后仍生效）"
        } else {
            "提升日志等级失败：${msg.ifBlank { "shell 无权写 persist 属性，需在工程菜单里调日志级别" }}" +
                "（当前 ${after.ifBlank { "空" }}）"
        }
    }

    /** 跳到拨号盘并预填工程菜单码，用户按一下拨号键即可进入 */
    fun openDialer(context: Context): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(ENGINEER_CODE)))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
