package dev.loglens

import android.app.Application
import dev.loglens.capture.CaptureController
import dev.loglens.data.AppGraph
import dev.loglens.data.Prefs
import dev.loglens.diag.SelfLog
import dev.loglens.service.CaptureService
import dev.loglens.util.versionCodeCompat

class LogApp : Application() {

    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        // 全局实例只在这里装一次：service / UI / 引擎都从 AppGraph 取，测试可注入替身
        AppGraph.install()
        CaptureController.init(this)
        CaptureService.ensureChannel(this)
        SelfLog.init(this)
        runCatching {
            val pi = packageManager.getPackageInfo(packageName, 0)
            SelfLog.i(
                "app",
                "启动 v${pi.versionName} (${pi.versionCodeCompat()}) " +
                    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / API ${android.os.Build.VERSION.SDK_INT}"
            )
        }

        // 本应用自身崩溃也留档：外部目录落文件（无需 adb 也能导），并写进 Prefs 供首页展示
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stamp = System.currentTimeMillis()
                val head = "thread=${thread.name}\ntime=$stamp\n\n" + throwable.stackTraceToString()
                // 先写进自身运行日志：用户反馈时导出这一个文件就能带上崩溃现场
                SelfLog.e("crash", "未捕获异常 thread=${thread.name}", throwable)
                // 外部目录可能拿不到（存储已卸载/未挂载）：两份写入各自兜底，
                // 外部目录失败不能挡住内部目录和 Prefs 里的崩溃记录
                getExternalFilesDir(null)?.let { external ->
                    runCatching {
                        val dir = java.io.File(external, "self_crash").apply { mkdirs() }
                        java.io.File(dir, "crash_$stamp.txt").writeText(head)
                    }
                }
                runCatching {
                    java.io.File(filesDir, "self_crash").apply { mkdirs() }
                        .let { java.io.File(it, "crash_$stamp.txt").writeText(head) }
                }
                if (::prefs.isInitialized) {
                    runCatching { prefs.setLastCrashSync(head.take(8000)) }
                }
            }
            default?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        lateinit var instance: LogApp
            private set
    }
}

