package dev.loglens

import android.app.Application
import dev.loglens.capture.CaptureController
import dev.loglens.data.Prefs
import dev.loglens.service.CaptureService

class LogApp : Application() {

    lateinit var prefs: Prefs
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        CaptureController.init(this)
        CaptureService.ensureChannel(this)

        // 本应用自身崩溃也留档：外部目录落文件（无需 adb 也能导），并写进 Prefs 供首页展示
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stamp = System.currentTimeMillis()
                val head = "thread=${thread.name}\ntime=$stamp\n\n" + throwable.stackTraceToString()
                val dir = java.io.File(getExternalFilesDir(null), "self_crash").apply { mkdirs() }
                java.io.File(dir, "crash_$stamp.txt").writeText(head)
                runCatching {
                    java.io.File(filesDir, "self_crash").apply { mkdirs() }
                        .let { java.io.File(it, "crash_$stamp.txt").writeText(head) }
                }
                if (::prefs.isInitialized) {
                    prefs.lastCrash = head.take(8000)
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
