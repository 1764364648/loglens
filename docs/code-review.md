# LogLens 全仓库代码审查

- 审查基线：`0af6996`（审查开始时的分支起点），范围为本仓库全部 Kotlin/Android 代码与配置。提交前已 rebase 到当前 `origin/main`（`9023458`），本 PR 不含 README 变更。
- 审查方式：先跑 `gradlew.bat assembleDebug` 与 `gradlew.bat lintDebug` 拿一手结果，再逐模块通读界面、采集服务、日志读取、取证包、权限与 Shizuku 交互。只修能从代码直接确认的问题；风格、命名、格式化与纯重构一律未动。
- 验证结果：
  - 基线 `assembleDebug`：`BUILD SUCCESSFUL`。
  - 基线 `lintDebug`：`FAILED`，21 errors / 92 warnings / 12 hints。错误集中在 `CaptureService` 的 NewApi（17 条，多数是新 API 调用放在 helper 里、lint 无法跨函数识别调用点的 `SDK_INT` 守卫）、`values/strings.xml` 缺英文翻译（2 条）、`NetScreen` 在 Composable 里读 `Locale.getDefault()`（1 条）。
  - 修复后 `assembleDebug`：`BUILD SUCCESSFUL`（Kotlin 编译通过，只有既有的 `versionCode` deprecated 警告）。
  - 运行时冒烟（本机 adb 设备 `127.0.0.1:58526`，Android 17 / API 37，Desktop Subsystem for Android）：安装 debug 包并启动，`MainActivity` 进入 resumed；授予 Shizuku 权限后选中目标应用开始捕获，日志页出现采集条目、`files/capture/*.log` 生成、常驻通知带 `PROMOTED_ONGOING` 与“正在捕获”；点“停止捕获”后通知与前台服务都被撤掉；全程 `logcat` 无 `FATAL EXCEPTION`、无 `dev.loglens` 进程崩溃。该设备不是工单提到的 `emulator-5560`，也没有完整第三方应用崩溃现场，因此只作为冒烟验证，不作为“所有真机行为已验证”的声明。
  - 修复后 `lintDebug`：3 errors / 89 warnings / 12 hints（基线为 21 / 92 / 12）；剩余 3 条都是既有 UI 规范问题（2 条缺英文翻译、1 条 Composable 读 Locale），不是本次改动引入的运行时缺陷，详见文末「附：lint 结果」。
- 说明：本工单环境没有 `emulator-5560`；本机可用的 Android 17 设备已用于上面的安装/启动/采集冒烟与权限弹窗前后截图，但没有在真机上构造“目标应用崩溃 + Shizuku 重启 + 存储写满”等极端场景，因此涉及这些条件的条目仍放在「建议修复但未动」中。
- 改动范围：17 个 Kotlin 文件，全部是逻辑/生命周期/资源清理/API 守卫类修改；没有改布局、颜色或静态文案，只有一处权限引导提示的显示时机修复（见 §1.19，附前后截图），也没有改 README，没有无关重构。

## 一、已修复

### 1. 自身崩溃留档：外部目录写失败会连带吞掉内部兜底
- `app/src/main/java/dev/loglens/LogApp.kt:26-40`
- 依据：外部 `self_crash`、内部 `filesDir/self_crash` 与 `prefs.lastCrash` 原来共用同一个 `runCatching`，外部存储不可写时后两步不会执行。
- 修复：外部目录可空判断 + 外部/内部/Prefs 三步各自独立兜底；崩溃堆栈改用同步写盘的 `setLastCrashSync`。

### 2. 边抓边落盘：轮转异常不再杀进程，写盘失败不再假装成功
- `app/src/main/java/dev/loglens/logcat/FileLogWriter.kt:51-86`、`:101-144`
- 依据：写线程的 `rotate()` 直接调用 `FileWriter`，`FileNotFoundException/IOException` 会逃出线程体；`LogApp` 又挂了全局未捕获处理器，任何线程未捕获异常都会杀进程（ENOSPC、外部存储被卸载时可复现）。原代码里 `if (writer == null)` 的分支也永远走不到，因为 `FileWriter` 异常先抛出了。
- 修复：`openNext()` 返回成功/失败并捕获 I/O 异常；轮转失败时置 `alive=false`、清空待写队列并记录原因；首次 `start()` 失败抛出可读异常由上层展示；写线程里的 `rotate()` 再兜一层 `runCatching`。

### 3. 采集启停：停止/暂停不再让启动协程“复活”会话
- `app/src/main/java/dev/loglens/capture/CaptureController.kt:51-56`、`:102-128`、`:159-165`、`:172-243`、`:335-352`
- 依据：`launchEngine` 在 IO 线程里要经历多次 Shizuku/shell 往返（`shell exec` 还固定带 0.6 秒 `sleep`），而通知里的“停止”从准备阶段就存在。`stop()` 只清字段、不取消在途启动，旧协程随后会重新建 writer、拉起 logcat，把状态写回 `Capturing`，造成“服务已停、采集复活且没有前台服务”。
- 修复：引入会话令牌 `session`，`start/resume/stop` 自增；启动协程在每个阻塞点后校验令牌，失效时只清理本次创建的 writer/engine 后退出；`onStarted` 也校验令牌；`stop()` 同时复位 `restarting`，避免上一次换代卡死后续 `onExit` 处理。

### 4. 日志流结束/失败：events 子进程与看门狗线程不再残留
- `app/src/main/java/dev/loglens/capture/CaptureController.kt:259-285`
- 依据：events 是独立一条 `logcat` 进程；主 logcat 自然退出时 `onExit` 只停 writer、把 `engine` 置空，从未调用 `engine.stop()`，events 进程和阻塞读线程会失去引用永久残留。
- 修复：`onFailure`/`onExit` 先调用 `self.stop()` 做完整清理，再处理 writer 与状态。

### 5. 停止采集：主线程不再 `join(1500)` 等写盘线程
- `app/src/main/java/dev/loglens/capture/CaptureController.kt:335-352`
- 依据：`FileLogWriter.stop()` 会 `join(1.5s)`；`CaptureController.stop()` 由首页按钮和通知“停止”在主线程调用，日志量大时界面最长冻结 1.5 秒。
- 修复：`stop()` 把 writer 收尾投递到 `Dispatchers.IO` 的控制器作用域，主线程立即返回。

### 6. “最新 N 行”取反：悬浮窗显示最旧 14 行、崩溃快照保存最旧 200 行
- `app/src/main/java/dev/loglens/data/LogRepository.kt:85-100`
- `app/src/main/java/dev/loglens/capture/CaptureController.kt:374-384`
- `app/src/main/java/dev/loglens/service/OverlayLogService.kt:199-207`
- 依据：`LogRepository.snapshot()` 返回“新→旧”，但两个调用方把它当“旧→新”使用并调用 `takeLast(n)`，实际拿到的是缓冲区最旧的一段。悬浮窗因此在新日志到来时仍显示最早的 14 行；崩溃快照的“崩溃前最近 200 行”也不是崩溃现场。
- 修复：新增 `LogRepository.recent(n)`（旧→新返回最近 n 条），悬浮窗与崩溃快照改用它。

### 7. 崩溃通知节流表的并发访问
- `app/src/main/java/dev/loglens/logcat/CrashNotifier.kt:25-26`
- 依据：`notifyCrash` 会被主日志流与 events 流两条读线程同时调用，原 `HashMap` 的读写没有同步。
- 修复：改为 `ConcurrentHashMap`。

### 8. Android 16（API 36.1）通知提升守卫与前台标志时序
- `app/src/main/java/dev/loglens/service/CaptureService.kt:228-241`、`:331-343`
- 依据：`Notification.Builder#setRequestPromotedOngoing` 在 SDK 平台自带的 api-versions.xml 里是 `since="36.1"`，Android 16 初版（36.0）没有该方法；原代码只判断 `SDK_INT >= 36`，在 36.0 上会抛 `NoSuchMethodError`（被 `runCatching` 吞掉，但整段提升逻辑失效且每次更新都抛）。`startForegroundCompat` 先把 `foregroundStarted=true` 再调用 `startForeground`，失败时会误以为已在前台。
- 修复：改用 `Build.VERSION.SDK_INT_FULL >= VERSION_CODES_FULL.BAKLAVA_1` 判断 36.1；`foregroundStarted` 移到 `startForeground` 成功之后。

### 9. `ShellRunner.exec` 超时/异常路径的流与进程清理
- `app/src/main/java/dev/loglens/shizuku/ShellRunner.kt:60-85`
- 依据：超时只 `destroy()` 且不 close；`ShizukuRemoteProcess` 在 binder 断连时 `destroy()/waitFor()` 会抛 `RuntimeException` 被吞掉，两个 daemon 读线程可能永久阻塞在管道上，远端 shell 也可能残留。
- 修复：返回前统一关闭 stdin/stdout/stderr、再 `destroy()`，并给两个读线程 200ms `join` 机会；所有步骤各自 `runCatching`，不影响返回结果。

### 10. 崩溃统计“今日”起点算错一天
- `app/src/main/java/dev/loglens/data/CrashStats.kt:124-141`
- 依据：`now - now % 86400000 - 时区偏移` 是“UTC 当日零点减偏移”，在 UTC+8 凌晨会得到昨天零点、UTC-5 晚间会得到次日零点，导致“今日崩溃”多算/恒为 0。
- 修复：用 `Calendar` 把当前时间清零时分秒得到本地日界。

### 11. 离线导入：`logcat -v time` 格式实际不被支持
- `app/src/main/java/dev/loglens/data/OfflineImport.kt:13-27`、`:42-96`
- 依据：KDoc 声称支持 threadtime / time / brief，但 `time` 格式（`MM-dd HH:mm:ss.SSS L/Tag( pid): message`）三条正则都不匹配，整行落到 RAW；日志页又会过滤 RAW，导入后出现“提示成功但列表为空”。
- 修复：新增 `logcatTime` 正则与解析分支（日期、级别、tag、pid、message）。

### 12. 查询语法：`pkg~:` / `process~:` 的正则在逐行求值里编译且可抛未捕获异常
- `app/src/main/java/dev/loglens/data/QuerySyntax.kt:203-236`
- 依据：`compileOrThrow(value)` 写在 `Node` 求值 lambda 里，非法正则会从主线程过滤循环冒出，同时每条日志都重新编译一次正则。
- 修复：解析期就编译并复用；解析失败按 `ParseException` 走原有的关键字/正则降级路径。

### 13. 查询语法：`level:raw` / `is:raw` 恒真
- `app/src/main/java/dev/loglens/data/QuerySyntax.kt:241-266`
- 依据：`LogLevel.RAW.priority = -1`，而 `level`/`is` 使用 `priority >= p`，对 V/D/I/W/E/F 全部为真。
- 修复：`raw` 改为等值匹配，其余级别维持 `>=` 语义。

### 14. `age:` 过滤忽略日志自带的年份
- `app/src/main/java/dev/loglens/data/QuerySyntax.kt:337-378`
- 依据：`LogTime.millisOf` 只取 `MM-dd HH:mm:ss.SSS`，用当前年份补全；跨年时去年 12 月会被算成今年 12 月（未来），`age:30s` 会误命中。
- 修复：正则改为可选解析 `yyyy-`，带年份时按日志年份构造时间，并先 `clear()` 再 `set()` 避免 Calendar 字段残留。

### 15. 取证包：用户可控目录/tag 未加引号拼进 shell，外部目录为 null 时落盘位置错误
- `app/src/main/java/dev/loglens/diag/Forensics.kt:66-90`、`:115-171`、`:258-286`
- 依据：`listServerLogs` 的目录来自可编辑并持久化的 `logPath`，原样拼进 `ls -l $dir`（含空格会列错目录，`;`/`$()` 会以 shell(2000) 执行）；`logcat -s $tagsArg` 的 tag 同样来自用户输入且未加引号。`getExternalFilesDir(null)` 为 null 时 `File(null, child)` 会变成不可写的相对路径。
- 修复：目录用既有 POSIX 单引号函数 `q()`；tag 拆成“显示用原文”和“执行用逐项引号”两份；`dirOf` 在外部目录为空时退回 `filesDir`。

### 16. 诊断事件时间轴：无年份时间戳跨年偏一年
- `app/src/main/java/dev/loglens/diag/DiagRepo.kt:421-436`
- 依据：`parseLcTime` 用当前年份补全 `MM-dd`，1 月读到去年 12 月的事件会被解析成未来时间，`ago()` 为空、时间轴错序。
- 修复：解析结果超过当前时间一天以上时回退一年。

### 17. PDF 导出异常时 `PdfDocument` 未关闭
- `app/src/main/java/dev/loglens/util/ExportFormats.kt:216-282`
- 依据：`drawText`/`writeTo` 抛异常时不会执行末尾的 `doc.close()`，native 页面资源要等 finalizer；反复失败会累积。
- 修复：整个排版/写文件过程放进 `try`，`finally` 里 `runCatching { doc.close() }`。

### 18. 首页“清除崩溃”不再主线程同步写 SharedPreferences
- `app/src/main/java/dev/loglens/data/Prefs.kt:159-173`、`app/src/main/java/dev/loglens/LogApp.kt:38-40`
- 依据：`lastCrash` 的 setter 固定 `commit()`，崩溃处理器需要同步落盘，但首页按钮复用同一个 setter 时会在主线程做同步写盘。
- 修复：普通 setter 改为 `apply()`；新增 `setLastCrashSync()` 只给崩溃处理器使用。

### 19. Shizuku 授权：弹窗等待中就被误判成“拒绝且不再询问”
- `app/src/main/java/dev/loglens/shizuku/ShizukuHelper.kt:65-99`
- 依据：`requestPermission` 在发起请求前就调用 `markAsked()` 写 `asked_once=true`。请求弹窗尚未返回时 `shouldShowRationale()` 也是 false，`needManualGrant()` 于是返回 true，首页会在“要允许日志透镜使用 Shizuku 吗”的弹窗后面显示“你之前拒绝过授权并勾选了不再询问”的错误引导（已在 Android 17 设备上复现，见 `review/before-permission-warning.png`）。
- 修复：把 `markAsked()` 移到权限结果回调里，且只在**真正被拒绝**时记录；授权成功、请求异常、弹窗未返回都不再误记。修复后同一场景截图见 `review/after-permission-warning.png`（弹窗仍在，错误引导不再出现）。

### UI 验证截图（已修复项 19）

- `docs/review/before-permission-warning.png`：修复前（同一分支、修复权限引导前的构建），Android 17 / 1272×2800 / 深色主题 / 全新安装 / Shizuku 运行中，授权弹窗后面的错误“不再询问”提示。
- `docs/review/after-permission-warning.png`：修复后，其他条件相同；弹窗仍在，错误提示消失。

## 二、建议修复但未动

> **2026-11-27 补齐**：本节 24 条已逐条改代码并合入 main —— 高优先级 6 条（续行随父行显示、预录改为可观察运行状态且重启后自动接回、预录走前台服务、Shizuku 未运行时的文案三态）、中优先级 9 条（历史文件 IO 移出主线程、授权监听随页面销毁注销、时间轴录制绑 RESUMED、取消异常重新抛出、预览请求加序号、`getPackageInfo`/`label`/`length` 移出组合、取证落盘失败记 NG 步骤、`FileLogWriter` 写失败上报 notice）、低优先级 9 条（崩溃跳转按截断长度比较、书签改用内容签名键、下一个命中/折叠区间/迷你图按下标换行号、高亮与崩溃归集可滚动、浮窗存活改 StateFlow、诊断页刷新按钮 busy 禁用）。下面原文保留，作为问题索引与定位参考。

> 行号是 2026-11 评审当时的快照。`ui/LogScreen.kt` 在之后做过一次拆分（1086 行巨型 composable → `LogScreenUi` 状态容器 + 顶栏/底栏/筛选区/提示区/列表/弹窗六个子 composable），该文件的行号已整体位移，下面按 `LogScreen.kt:NNN` 的引用只保证文件存在且行号在范围内，定位请按同一行给出的标识符/函数名检索。其余文件未动，行号仍然准确。

以下问题均能在代码中直接定位，但要么会改变用户可见的界面/交互（本工单没有 `emulator-5560`，也没有在真机上构造完整采集链路；UI 行为只验证了权限弹窗这一种状态），要么需要跨模块设计取舍，因此没有直接改代码。

### 高优先级（功能实质性失效）

1. `app/src/main/java/dev/loglens/data/ProcNames.kt:29`、`data/LogFilter.kt:203` — `ProcNames.refresh()` 全仓库零调用，`table` 永远是空 Map，`package:` / `process:` / `pkg:` / `proc:` 查询恒匹配不到。需要在采集会话里用 IO 协程周期性刷新并让过滤结果可观察，否则加了刷新也不会触发 UI 重新过滤。
2. `app/src/main/java/dev/loglens/ui/LogScreen.kt:394-395`（配合 `logcat/LogcatEngine.kt:474-486`）— 实时日志的堆栈续行是 `level=RAW, isContinuation=true`，在 `viewData` 里被 `priority < VERBOSE` 提前过滤，导致实时堆栈永远不显示、`FoldKind.STACK` 永不触发；离线导入的续行会继承上一条级别，可证明这是实时路径的遗漏。修复需要让续行继承父行级别并调整过滤/计数分支，属于会改变列表内容的 UI 行为改动，需真机用真实崩溃验证。
3. `app/src/main/java/dev/loglens/ui/SettingsScreen.kt:74,167-191` — “崩溃前预录”只读写 `prefs.preRecord`，应用启动路径从不读取它：重启后开关显示开，但没有任何预录在跑；正式采集中打开也会静默失效。需要把“预录是否在跑”改成可观察状态，并决定是否在启动时自动开启（产品行为）。
4. `app/src/main/java/dev/loglens/capture/CaptureController.kt:134-147` — `startPassive()` 不启动 `CaptureService`，被动预录没有前台服务与常驻通知；切后台后进程可被回收，预录的意义被削弱。需要决定预录是否也常驻通知并补上服务启停与对应的停止路径。
5. `app/src/main/java/dev/loglens/ui/NetScreen.kt:192-213` — 停止“时间轴录制”会触发一次单次采样并用 `mergeTracks(emptyMap(), ...)` 合并，已消失连接立即被清空，与页面/README 承诺的“保留 90 秒”矛盾。改为用现有 `tracks` + `KEEP_DEAD_MS` 合并即可，但会改变页面数据表现。
6. `app/src/main/java/dev/loglens/ui/HomeScreen.kt:279-291` — Shizuku 未运行（`alive=false`）时状态卡仍写“已连上，还没拿到 shell 身份”，与同页底部“打开 Shizuku”按钮互相矛盾。这是纯文案/状态修复，但属于用户可见 UI 改动，需要前后截图证据。

### 中优先级（主线程 IO / 生命周期 / 资源）

7. `app/src/main/java/dev/loglens/ui/LogScreen.kt:177-195`、`:860-863` — SAF 导入与历史文件回放直接用无上限 `readText()`，1–2GB 文件会 OOM；应按文件大小拒绝或改为流式解析。
8. `app/src/main/java/dev/loglens/ui/LogScreen.kt:760-762`、`:881-885` — “历史文件”列表与删除在主线程 `listFiles/length/delete`；应移到 `Dispatchers.IO`。
9. `app/src/main/java/dev/loglens/shizuku/ShizukuHelper.kt:24,84-101` — 授权结果监听器与静态 `lastResult` 在页面销毁后不注销，回调 lambda 捕获 Activity context；需要在 `HomeScreen` 加 `DisposableEffect` 调用 `releaseListener()`，并让 `releaseListener()` 同步清空 `lastResult`。
10. `app/src/main/java/dev/loglens/ui/NetScreen.kt:216-244` — 时间轴录制协程只绑定 composition，按 Home 后仍每 1.4 秒跑 shell 采样；应改用 `repeatOnLifecycle(RESUMED)` 或页面 stop 时暂停。
11. `app/src/main/java/dev/loglens/ui/NetScreen.kt:222-236` — 停止录制时被取消的采样仍可能把假的“读取失败”写回页面（`runCatching` 吞掉 `CancellationException`）；应重新抛出取消异常并在写状态前检查 `isActive`。
12. `app/src/main/java/dev/loglens/ui/DiagScreen.kt:584-592` — 文件预览关闭后，异步 `readHead` 完成会重新弹出 Dialog，快速切换文件还可能旧内容覆盖新预览；应给预览请求加递增 id 或改为 `LaunchedEffect(previewPath)`。
13. `app/src/main/java/dev/loglens/ui/SettingsScreen.kt:91-95`、`ui/NetScreen.kt:547-560`、`ui/ForensicsScreen.kt:418` — 分别在 `remember`/composition 里做 `getPackageInfo`、`getPackagesForUid/loadLabel`、`File.length()`，都是主线程同步调用；应复用已有的 IO 协程结果或预计算字段。
14. `app/src/main/java/dev/loglens/diag/Forensics.kt:236-286` — 取证落盘的多处 `writeText` 仍被裸 `runCatching` 吞掉；外部目录 null 已兜底，但磁盘满/只读时用户仍只看到“文件列表为空”而没有原因。建议把失败写成一个 NG `Step` 并显示到结论里。
15. `app/src/main/java/dev/loglens/logcat/FileLogWriter.kt:122-131` — ENOSPC 时 `writer?.write/flush` 的 IOException 被吞掉，`alive` 仍为 true，界面继续显示“落盘中”，实际已停止写入；需要把写失败上报到 `CaptureController._notice` 并停止接收。当前提交只修掉了“异常杀进程”与轮转失败停止写入，日常写失败仍静默。

### 低优先级（状态/边界）

16. `app/src/main/java/dev/loglens/data/CrashStats.kt:76`、`ui/LogScreen.kt:1215` — `sample` 截断到 600 字符，跳转却用完整 `message == sample`，超长消息永远跳不到；建议增加 entry id 或比较时同样截断。
17. `app/src/main/java/dev/loglens/data/FilterPreset.kt:55,101-115` — `builtins()` 六条内置预设全仓库零调用，首次安装的预设 chip 为空；需要把内置预设拼进 `PresetStore.load` 的返回值（会改变工具条展示）。
18. `app/src/main/java/dev/loglens/ui/LogScreen.kt:134-158,390-392` — 书签只用裸 `id` 做键，离线导入 id 与实时 id 都从 1 开始，会串收藏；需要带来源的复合键。
19. `app/src/main/java/dev/loglens/ui/LogScreen.kt:721-726` — “下一个命中”把命中序号直接当 `LazyColumn` 行号（且折叠后 `visible` 与 `displayRows` 行数不同），单条命中时会滚到列表另一头。
20. `app/src/main/java/dev/loglens/ui/LogScreen.kt:420-434` — 跳转到被折叠区间的非头行时只检查 `Fold.head.id`，必然提示“该条不在当前视图内”；需要保存折叠区间并在展开后定位。
21. `app/src/main/java/dev/loglens/ui/LogScreen.kt:1117-1127`（`ui/LogExtras.kt:120-129`）— 迷你图用 `visible` 着色、却按 `displayRows.size` 求跳转下标，折叠时坐标错位；`pointerInput` key 也没有包含折叠后的行数。
22. `app/src/main/java/dev/loglens/ui/LogExtras.kt:459-520,678-733` — 高亮规则/崩溃归集放在定高 `Column` 且没有滚动，超过约 6/7 条后的记录不可达；建议改为定高 `LazyColumn` 或加 `verticalScroll`。
23. `app/src/main/java/dev/loglens/ui/LogScreen.kt:255,730-751`（`service/OverlayLogService.kt:130-136,217-224`）— `overlayAlive` 只是进入页面时的本地快照；从浮窗自身关闭后日志页仍显示“浮窗已开”，需要把 `alive` 改成 `StateFlow` 或回调同步。
24. `app/src/main/java/dev/loglens/ui/DiagScreen.kt:313`（刷新按钮）— 刷新/重采集按钮在 `busy` 时未禁用，连续点击会并发跑多份阻塞型 `dumpsys/logcat`，造成资源浪费。

### 需要真机/环境才能定性的项

> **2026-11-27 更新**：其中可离线落地的不再挂着 —— CSV 公式注入已加前导单引号；`Forensics` 复制到 Download 的文件名改为带时间戳（不再覆盖同名）；浮窗拖动松手收敛回屏幕内；`NetScreen`/`AppPickerScreen` 补 `imePadding`；`LogcatParser.resetClock()` 在每次 `LogcatEngine.start()` 时调用。
>
> 同日再补两条：`AnrRepo` 的 shell 通道换成 `dumpsys activity lastanr`（`app_process` + 反射隐藏 `IActivityManager` 那条已删除，理由见下）；`/proc/net` 的可读性查清并实测，不再按「取决于 ROM」挂着。现在这一节只剩「需要真实 ANR/翻转事件才能观察」的观感项，不再有「技术上不知道能不能用」的项。
>
> - `dumpsys activity lastanr` 是 ActivityManagerService 自带的公开 dump 参数，shell(uid 2000) 直接可调，不需要 DUMP 权限、不碰 hidden API。本机实测（`adb shell dumpsys activity lastanr`，API 37）返回 `ACTIVITY MANAGER LAST ANR` 段与 `<no ANR has occurred since boot>` 空槽，说明命令与格式都在。
> - 原来的 `app_process` 路线确认是死路：Android 10 起非 SDK 接口屏蔽把 `IActivityManager$Stub.asInterface` 从反射方法表里摘掉（Android 9 有、10 起 `NoSuchMethodException`），`AnrCli` 又靠 `CLASSPATH=<apk>` 复用 App 的 dex，ART 初始化在非 zygote 进程里还会撞 `Typeface`/`Paint.nInit`。文件已删。
> - `/proc/net/{tcp,tcp6,udp,udp6}` 的封锁是 SELinux 层：AOSP `system/sepolicy/public/app.te` 写的是 `neverallow { appdomain -shell } proc_net_tcp_udp:file *`，即除 shell 域外的所有应用域禁止读 —— **shell 被显式排除在封锁之外**。本机实测同一条命令能看到 `uid 1000` 的连接（shell 自身是 2000），跨 uid 可见，`NetSnapshot` 现有的 shell 读取路径是成立的设计。
> - 仍属「看实际表现」而非「能不能用」：真正发生 ANR 时 `lastanr` 段里 Reason 的措辞各版本不同（只做确定性关键字推断，认不出就留空）；预录自动接回、折叠区间跳转这类要靠 Shizuku 实时采集链路才能观察。

- `diag/AnrRepo.kt`：shell 通道已改为 `dumpsys activity lastanr` 解析（`parseLastAnr`/`typeFromReason`/`timeoutFromReason` 有 6 条单测覆盖）。类型与阈值是按 Reason 文本推断的，记录标 `dumpsys lastanr` 来源，不冒充 API 字段；`ANR in ...` 的进程名两种写法（`<pkg>` 与 `<pkg> (<process>)`）都吃，最近一次不属于目标包时明确留空、不串包。
- `data/NetSnapshot.kt:50-51`：shell 读 `/proc/net/{tcp,tcp6,udp,udp6}` 已按上面的 sepolicy 依据核实可行，代码保留「读不到 /proc/net」兜底以应对厂商额外收紧。
- `ui/NetScreen.kt:216-244` 与 `ui/AppPickerScreen.kt`：`imePadding` 已补，仍建议在 Android 15/16 真机上看一眼键盘遮挡的实际观感。
- `util/ExportFormats.kt:159-164`：CSV 前导单引号已加（见上一节 24 条的第 24 号）。
- `diag/Forensics.kt:194`：Download 文件名已带时间戳。
- `service/OverlayLogService.kt:155-193`：拖动已收敛回屏幕内。
- `CaptureService` 的 `State.Failed` 不再是「挂着但不干活」：失败即 `stopForegroundCompat()` + `stopSelf()`，另发一条普通通知（`NOTI_ID_FAILED`）说明原因，用户可随时划掉。依据是官方两条口径：Android 13 起用户可以滑动划掉前台服务通知，也能在通知栏「使用中的应用」里一键停止（那一键会直接杀掉整个进程且不给应用任何回调）；Android 14 起连 `setOngoing(true)` 也拦不住划掉（仅锁屏与「全部清除」例外）。所以失败后继续占着前台身份没有任何收益，只会让通知与实际状态不一致。
- `LogcatParser` 的跨年推断状态为进程级单例：`resetClock()` 已在每次 `LogcatEngine.start()` 调用（见上一节 24 条的第 18 号）。

### lint 相关（当时剩 3 条未改，后续已全部修掉）

- `CaptureService.kt` 的 `MetricStyle`/`ProgressStyle` 调用在 `if (SDK_INT >= 36/37)` 分支内，lint 不做跨函数守卫分析；已给三个 helper 加 `@android.annotation.TargetApi(36/37)` 说明调用前提，17 条 NewApi 全部消警，运行时行为未变。
- `res/values/strings.xml:4-5` 的两条通知渠道文案（`MissingTranslation`）——当时为了消警给 `values-en` 补了英文，但界面本身是 800 多处硬编码中文，补 3 条英文等于假装支持国际化。最终处理是删掉 `values-en`，不再维持空壳（见 172 行后记）。
- `ui/NetScreen.kt:348` 在 Composable 里读 `Locale.getDefault()`（`NonObservableLocale`）；应改用 `LocalConfiguration`/`LocalLocale`，属于 Compose 规范问题，不影响当前中文界面。

> 后记：以上 3 条已在后续提交处理——`MissingTranslation` 这条最终没有走「补英文」而是**删掉 `values-en` 目录**：界面只有中文一套，留 3 条英文只会让人以为支持英文，`res/values/strings.xml` 顶部已写注释说明「真要做国际化再把文案搬进去」；`NetScreen` 改用 `LocalConfiguration.current.locales[0]`；两处 `PackageInfo.longVersionCode`（API 28+，minSdk 26）统一走 `dev/loglens/util/AppVersion.kt` 的 `versionCodeCompat()`，26/27 退回 `versionCode`。现在 `lintDebug` 是 0 error（warning 数随本机与 CI 的 lint 版本略有浮动，本机 90、CI 93），并已进 CI 闸门。

## 三、已检查无问题

- **权限/组件安全**：`AndroidManifest.xml` 中两个 Service 均 `exported=false`；`MainActivity` 只接 MAIN/LAUNCHER；`FileProvider` `exported=false` 且 `file_paths.xml` 只覆盖 app 私有/app-specific 目录；`ShizukuProvider` 由签名级 `INTERACT_ACROSS_USERS_FULL` 保护；未发现导出的 Receiver、明文密钥/Token、日志打印敏感凭据。
- **PendingIntent**：`CaptureService` 三个与 `CrashNotifier` 一个 PendingIntent 全部显式 `FLAG_IMMUTABLE` + `FLAG_UPDATE_CURRENT`，组件均为显式 Intent，Android 12+ 无可变性崩溃/劫持面。
- **前台服务/通知**：`specialUse` 类型、`FOREGROUND_SERVICE_SPECIAL_USE`、`PROPERTY_SPECIAL_USE_FGS_SUBTYPE`、API 34+ 三参 `startForeground`、`POST_NOTIFICATIONS` 运行时申请都已就位；`START_STICKY` 空 intent 分支会直接收摊，不会留“等待开始”的残岛。
- **Shizuku 通道**：`ShizukuProcess` 通过反射命中 `newProcess(String[], String[], String)`；`proguard-rules.pro` 的宽 keep 规则覆盖了该 private 方法；权限回调经 Shizuku 主线程 Handler 派发，Home 回调里写 Compose state/Toast 不存在无 Looper 崩溃；权限状态六种分支的文案与底部动作基本自洽（仅 `alive=false` 文案问题已单列）。
- **logcat 解析/采集**：`LogcatParser` 的年份缓存有 `@Synchronized`；stdout/events 两条流并发解析不会破坏共享状态；`LogcatEngine` 的 argv 数组交给 Shizuku，不经过 shell 字符串拼接；`--uid=` 只由整数 uid 构成，不存在注入；客户端 pid 复核与服务端 `--uid` 双保险逻辑一致；`FileLogWriter` 队列有界、轮转/保留份数已实现。
- **数据层**：`LogRepository` 的 buffer/pending/统计由同一把锁保护，`attach()` 原子清空 pending 避免 LazyColumn 重复 key；`AppRepository.loadInstalled` 在 `Dispatchers.IO`；`Prefs` 的数值项都有 `coerceIn`，无明文凭据；`IssueRules` 预筛关键词与规则表数量有 `require` 校验、正则只编译一次；`HighlightRules` 非法正则/颜色会安全跳过。
- **导出/分享**：`ExportFormats` 的 TXT/CSV/JSON/HTML/ZIP/PDF 都有上限或异常兜底；CSV 有 BOM 与引号转义，JSON/HTML 有转义；分享统一走 FileProvider + `FLAG_GRANT_READ_URI_PERMISSION`；文件名只由包名/时间戳组成，无路径穿越。
- **诊断/取证**：`DiagRepo` 的 `ps` 字段索引、`/proc/<pid>/stat` 第 22 字段处理、`ApplicationExitInfo` 多行块聚合、`Forensics` 的 POSIX 引号函数与 `run-as` 路径处理均正确；`readHead` 对 shell 单引号做了转义；`AnrRepo` 的 dumpsys 通道是纯文本解析，失败只写进说明字段，不抛出。
- **网络快照**：`NetSnapshot` 的 `/proc/net` 列下标、32 位字小端翻转、IPv4/IPv6 长度判断、端口 `toIntOrNull(16)` 都对；解析失败跳过而不猜；不依赖 `ss`/`netstat` 文本。
- **Compose 生命周期**：`LaunchedEffect`/`rememberCoroutineScope` 随 composition 取消；没有动态注册 Receiver/ContentObserver；暂停/继续状态与前台通知状态同步；返回回调与 `enableOnBackInvokedCallback` 兼容；`Components`/`Theme`/`MiuixCompat` 未发现崩溃或状态问题。

## 附：lint 结果

修复后 `gradlew.bat lintDebug --offline --no-build-cache`：**0 errors / 90 warnings / 11 hints**（基线 21 / 92 / 12）。两条 `MissingTranslation` 的处理方式见上：删掉 `values-en` 空壳，界面只保留中文一套文案（`res/values/strings.xml` 顶部有注释，避免再建空语言目录）；`NetScreen` 的 `NonObservableLocale` 已改用 `LocalConfiguration.current.locales[0]`。

基线里的 17 条 `NewApi`（`MetricStyle`/`ProgressStyle`/语义常量）已通过 `@android.annotation.TargetApi(36/37)` 标注说明“只在 guarded 分支调用”而消警；其中真实存在的 API 36.1 守卫错误（`setRequestPromotedOngoing`）已经改代码修复，不是单纯消警。



