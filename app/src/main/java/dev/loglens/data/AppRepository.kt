package dev.loglens.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 已安装应用枚举。
 *
 * 两处代价必须省掉，否则「选择应用」要转好几秒（实测目标机 523 个包）：
 * 1. 不给 getInstalledApplications 传 GET_META_DATA —— 那会让 system 为每个包解析一遍
 *    manifest 里的 <meta-data>，几百个包累加就是秒级开销；这里只要 label / uid / flags。
 * 2. label 是逐个包读资源取的，串行必然慢，按 4 路并发摊开。
 * 结果在内存里缓存一份，再次进选择页直接命中；装/卸载后靠 [invalidate] 或 TTL 失效。
 */
object AppRepository {

    data class AppItem(
        val packageName: String,
        val label: String,
        val uid: Int,
        val isSystem: Boolean
    )

    private class Snapshot(val at: Long, val items: List<AppItem>)

    @Volatile
    private var snapshot: Snapshot? = null

    /** 装/卸应用后想让列表立刻刷新时调用 */
    fun invalidate() {
        snapshot = null
    }

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
        snapshot?.let {
            if (SystemClock.elapsedRealtime() - it.at < CACHE_TTL_MS) return@withContext it.items
        }

        val pm = context.packageManager
        val self = context.packageName
        val infos = runCatching {
            pm.getInstalledApplications(0)
        }.getOrDefault(emptyList())

        val items = buildItems(pm, infos, self)
        snapshot = Snapshot(SystemClock.elapsedRealtime(), items)
        items
    }

    private suspend fun buildItems(
        pm: PackageManager,
        infos: List<ApplicationInfo>,
        self: String
    ): List<AppItem> = coroutineScope {
        val live = infos.filter { it.packageName != self }
        val workers = PARALLEL_WORKERS.coerceAtMost(live.size.coerceAtLeast(1))
        val chunkSize = (live.size + workers - 1) / workers
        live.chunked(chunkSize.coerceAtLeast(1))
            .map { chunk ->
                async(Dispatchers.IO) {
                    chunk.map { info ->
                        val label = runCatching { info.loadLabel(pm).toString().trim() }
                            .getOrNull()
                            ?.takeIf { it.isNotEmpty() }
                            ?: info.packageName
                        AppItem(
                            packageName = info.packageName,
                            label = label,
                            uid = info.uid,
                            isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    }
                }
            }
            .awaitAll()
            .flatten()
            .sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
    }

    private const val PARALLEL_WORKERS = 4
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
}