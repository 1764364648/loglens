# Shizuku
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# 反射入口（ShizukuProcess 用 declaredMethods + isAccessible 反射拿 newProcess）。
# 只需保住这个第三方方法名；原来那条 -keep class dev.loglens.** { *; }
# 会把自有类名和方法名全部保留，等于没开混淆，已删。
-keepclassmembers class rikka.shizuku.Shizuku {
    *** newProcess(...);
}

# R8：发布包剥离 Log.v / Log.d / Log.i 与 isLoggable，保留 w / e / wtf
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static boolean isLoggable(java.lang.String, int);
}
