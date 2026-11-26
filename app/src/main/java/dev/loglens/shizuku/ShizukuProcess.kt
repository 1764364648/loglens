package dev.loglens.shizuku

import java.lang.reflect.Method

/**
 * Shizuku.newProcess 在 api 13.1.5 的 Kotlin 元数据里被标成了库内可见，
 * 直接调用编译不过，因此统一走反射。返回的都是 ShizukuRemoteProcess（继承 Process）。
 */
object ShizukuProcess {

    private val methods: List<Method> by lazy {
        runCatching {
            Class.forName("rikka.shizuku.Shizuku")
                .declaredMethods
                .filter { it.name == "newProcess" && it.parameterTypes.size == 3 }
                .onEach { runCatching { it.isAccessible = true } }
        }.getOrDefault(emptyList())
    }

    fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): Process? {
        if (methods.isEmpty()) return null
        for (m in methods) {
            val types = m.parameterTypes
            val ok = types[0].isAssignableFrom(Array<String>::class.java) &&
                (env == null || types[1].isAssignableFrom(Array<String>::class.java)) &&
                (dir == null || types[2].isAssignableFrom(String::class.java))
            if (!ok) continue
            val r = runCatching { m.invoke(null, cmd, env, dir) }.getOrNull()
            if (r is Process) return r
        }
        return runCatching { methods.first().invoke(null, cmd, env, dir) as? Process }.getOrNull()
    }
}
