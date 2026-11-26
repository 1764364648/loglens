package dev.loglens.diag.cli

/**
 * shell 侧 ANR 归因读取器：由 Shizuku（uid 2000）以
 *
 *   CLASSPATH=<本应用 APK> /system/bin/app_process /system/bin dev.loglens.diag.cli.AnrCli <pkg>
 *
 * 拉起。shell 持有 DUMP，所以能读到第三方包的 ApplicationExitInfo，再从中取 getAnrInfo()。
 *
 * 输出协议（一行一条，App 侧只认前缀）：
 *   ANR|<timestamp ms>|<process>|<anrId>|<anrType>|<timeoutMs>|<userPerceptible 0/1>|<description>
 *   OK|<扫描到的退出记录条数>
 *   ERR|<失败原因>
 *
 * 全程反射：不引用 android.app.IActivityManager 这类隐藏类，也不直接引用 API 37 才有的
 * getAnrInfo()，保证这份代码在低版本系统上被类加载时不会炸。
 */
object AnrCli {

    private const val REASON_ANR = 6 // ApplicationExitInfo.REASON_ANR

    @JvmStatic
    fun main(args: Array<String>) {
        val pkg = args.firstOrNull()?.trim().orEmpty()
        if (pkg.isEmpty()) {
            println("ERR|缺少包名参数")
            return
        }
        try {
            val iam = activityManager() ?: throw IllegalStateException("activity 服务的 binder 为 null")
            // 不同 Android 版本 IActivityManager 的重载不一样（(String,int,int) / (String,int,int,int) …），
            // 硬绑 3 参数会让 Android 17 直接 NoSuchMethodException，这里按实际重载逐个尝试。
            val candidates = iam.javaClass.methods.filter {
                it.name == "getHistoricalProcessExitReasons" &&
                    it.parameterTypes.isNotEmpty() &&
                    it.parameterTypes[0] == String::class.java
            }
            if (candidates.isEmpty()) {
                val scanned = iam.javaClass.methods.count {
                    it.name.contains("ExitReasons", ignoreCase = true)
                }
                throw NoSuchMethodException(
                    "IActivityManager 上没有 getHistoricalProcessExitReasons（同名近似方法 $scanned 个）"
                )
            }

            var resolved: List<Any?>? = null
            var lastErr: Throwable? = null
            val sigLog = StringBuilder()
            for (m in candidates) {
                sigLog.append(m.parameterTypes.joinToString("/") { it.simpleName }).append(' ')
                try {
                    resolved = (m.invoke(iam, *buildArgs(m.parameterTypes, pkg)) as? List<Any?>).orEmpty()
                    break
                } catch (t: Throwable) {
                    lastErr = t.cause ?: t
                }
            }
            val read: List<Any?> = resolved ?: throw IllegalStateException(
                "所有重载都失败：$sigLog|${lastErr?.javaClass?.simpleName}: ${lastErr?.message}"
            )

            var scanned = 0
            read.forEach { e ->
                if (e == null) return@forEach
                scanned++
                val cls = e.javaClass
                val reason = (readInt(cls, e, "getReason")) ?: return@forEach
                if (reason != REASON_ANR) return@forEach

                val at = (readLong(cls, e, "getTimestamp")) ?: 0L
                val proc = (readString(cls, e, "getProcessName") ?: "?").replace("|", "/")

                var anrId = -1
                var anrType = -1
                var timeout = -1L
                var perceptible = false
                var desc = ""

                val anrInfo = runCatching { cls.getMethod("getAnrInfo").invoke(e) }.getOrNull()
                if (anrInfo != null) {
                    val ac = anrInfo.javaClass
                    anrId = readInt(ac, anrInfo, "getAnrId") ?: -1
                    anrType = readInt(ac, anrInfo, "getAnrType") ?: -1
                    timeout = readLong(ac, anrInfo, "getTimeoutMillis") ?: -1L
                    perceptible = runCatching { ac.getMethod("isUserPerceptible").invoke(anrInfo) as? Boolean }
                        .getOrNull() ?: false
                    desc = readDescription(ac, anrInfo)
                }
                println("ANR|$at|$proc|$anrId|$anrType|$timeout|${if (perceptible) 1 else 0}|$desc")
            }
            println("OK|$scanned")
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            println("ERR|${cause.javaClass.simpleName}: ${cause.message ?: "无描述"}")
        }
    }

    /** ServiceManager.getService("activity") → IActivityManager$Stub.asInterface()，全部走反射。 */
    private fun activityManager(): Any? {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "activity") ?: return null
        val stub = Class.forName("android.app.IActivityManager\$Stub")
        return stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder)
    }

    private fun readInt(cls: Class<*>, target: Any, name: String): Int? =
        runCatching { cls.getMethod(name).invoke(target) as? Int }.getOrNull()

    /**
     * 按参数类型构造实参：第一个 String 传包名，其后的 int 依次给 0（userId）、40（maxNum）、0。
     * 这样 (String,int,int) / (String,int,int,int) 等重载都能覆盖，不必知道确切签名。
     */
    private fun buildArgs(types: Array<Class<*>>, pkg: String): Array<Any?> {
        val ints = intArrayOf(0, 40, 0)
        var intIdx = 0
        var strDone = false
        return types.map { t ->
            when {
                t == String::class.java && !strDone -> {
                    strDone = true
                    pkg as Any?
                }
                t == Integer.TYPE -> {
                    val v = ints[intIdx.coerceAtMost(ints.size - 1)]
                    intIdx++
                    v as Any?
                }
                t == java.lang.Long.TYPE -> 0L as Any?
                t == java.lang.Boolean.TYPE -> false as Any?
                else -> null
            }
        }.toTypedArray()
    }

    private fun readLong(cls: Class<*>, target: Any, name: String): Long? =
        runCatching { cls.getMethod(name).invoke(target) as? Long }.getOrNull()

    private fun readString(cls: Class<*>, target: Any, name: String): String? =
        runCatching { cls.getMethod(name).invoke(target) as? String }.getOrNull()

    /** 新版系统上 AnrInfo 可能带一句人类可读描述，有就带上，没有就留空。 */
    private fun readDescription(cls: Class<*>, target: Any): String {
        val candidates = listOf("getDescription", "getAnrDescription", "toString")
        candidates.forEach { name ->
            val v = runCatching { cls.getMethod(name).invoke(target) as? String }.getOrNull()
            if (!v.isNullOrBlank() && name != "toString") return v.replace("|", "/")
        }
        return ""
    }
}
