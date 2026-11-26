package dev.loglens.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 权限与生命周期封装。
 *
 * 说明：Shizuku 13.1.5 的 `Shizuku.newProcess` 能以 shell(uid 2000) 身份拉起子进程，
 * 这正是读取第三方应用日志的唯一非 root 通道。
 */
object ShizukuHelper {

    const val PERMISSION_REQUEST_CODE = 4220

    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val SUI_PKG = "com.rikka.sui"

    private const val SP_NAME = "shizuku_state"
    private const val KEY_ASKED = "asked_once"

    private var pendingListener: Shizuku.OnRequestPermissionResultListener? = null
    private var lastResult: ((Boolean) -> Unit)? = null

    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    fun isPermissionGranted(): Boolean = try {
        isBinderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun shouldShowRationale(): Boolean = try {
        isBinderAlive() && Shizuku.shouldShowRequestPermissionRationale()
    } catch (t: Throwable) {
        false
    }

    /**
     * 用户已勾选「拒绝且不再询问」：此时 requestPermission 不会再弹窗，必须去 Shizuku 应用里
     * 手动勾选本应用，否则界面会一直停在「未授权」。
     *
     * 注意：首次使用、从未请求过时 shouldShowRationale 同样是 false，所以要用本地记录区分。
     */
    fun needManualGrant(context: Context): Boolean {
        if (!isBinderAlive() || isPermissionGranted()) return false
        val asked = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED, false)
        return asked && !shouldShowRationale()
    }

    private fun markAsked(context: Context) {
        runCatching {
            context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ASKED, true).apply()
        }
    }

    fun requestPermission(context: Context? = null, onResult: (Boolean) -> Unit) {
        if (!isBinderAlive()) {
            onResult(false)
            return
        }
        if (isPermissionGranted()) {
            onResult(true)
            return
        }
        context?.let { markAsked(it) }
        releaseListener()
        val listener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
            if (code == PERMISSION_REQUEST_CODE) {
                releaseListener()
                val cb = lastResult
                lastResult = null
                cb?.invoke(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        pendingListener = listener
        lastResult = onResult
        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (t: Throwable) {
            releaseListener()
            lastResult?.invoke(false)
            lastResult = null
        }
    }

    fun releaseListener() {
        pendingListener?.let {
            runCatching { Shizuku.removeRequestPermissionResultListener(it) }
        }
        pendingListener = null
    }

    /** 尝试拉起 Shizuku 或 Sui 管理器 */
    fun openShizukuManager(context: Context): Boolean {
        for (pkg in listOf(SHIZUKU_PKG, SUI_PKG)) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onSuccess { return true }
        }
        return false
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            true
        }.getOrDefault(false)
    }
}
