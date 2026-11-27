package dev.loglens.util

import android.content.pm.PackageInfo
import android.os.Build

/**
 * versionCode 的统一取值。
 *
 * API 28 起 `PackageInfo.longVersionCode` 才有，而本 App 的 minSdk 是 26；
 * 直接写 `longVersionCode` 在 26/27 上是 `NoSuchFieldError`（lint 报 NewApi 硬错误）。
 * 这里按 SDK 拆开，26/27 退回已废弃的 `versionCode`（数值上限 2^31 够用）。
 */
fun PackageInfo.versionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        longVersionCode
    } else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }
