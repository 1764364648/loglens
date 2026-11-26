# Shizuku
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# 反射/服务端桩（ShizukuProcess 用 declaredMethods + isAccessible 反射 newProcess）
-keep class dev.loglens.** { *; }
-keepclassmembers class rikka.shizuku.Shizuku {
    public static *** newProcess(...);
}

# R8：发布包剥离 Log.v / Log.d / Log.i 与 isLoggable，保留 w / e / wtf
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static boolean isLoggable(java.lang.String, int);
}
