package com.marvis.shizukulog.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 已安装应用枚举。uid 需要 shell 权限才能精确拿到，这里先给出 PackageManager 侧信息，
 * 真正的 uid 在开始捕获时由 ShellRunner 补全。
 */
object AppRepository {

    data class AppItem(
        val packageName: String,
        val label: String,
        val uid: Int,
        val isSystem: Boolean
    )

    /** 依据包名还原 AppItem，用于恢复上次选择 */
    fun resolve(context: Context, packageName: String): AppItem? {
        if (packageName.isBlank()) return null
        return runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            AppItem(
                packageName = packageName,
                label = pm.getApplicationLabel(info).toString(),
                uid = info.uid,
                isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }.getOrNull()
    }

    suspend fun loadInstalled(context: Context): List<AppItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val infos: List<ApplicationInfo> = runCatching {
            pm.getInstalledApplications(flags)
        }.getOrDefault(emptyList())

        infos.asSequence()
            .filter { it.packageName != context.packageName }
            .map { info ->
                val label = runCatching { pm.getApplicationLabel(info).toString() }
                    .getOrDefault(info.packageName)
                AppItem(
                    packageName = info.packageName,
                    label = label,
                    uid = info.uid,
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
            .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
            .toList()
    }
}
