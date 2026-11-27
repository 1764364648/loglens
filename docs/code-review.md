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

> 行号是 2026-11 评审当时的快照。`ui/LogScreen.kt` 在之后做过一次拆分（1086 行巨型 composable → `LogScreenUi` 状态容器 + 顶栏/底栏/筛选区/提示区/列表/弹窗六个子 composable），该文件在 2026-11-27 又拆了一次：2471 行拆成 `LogScreen.kt` / `LogScreenChrome.kt` / `LogScreenTools.kt` / `LogScreenPanels.kt` / `LogScreenList.kt` / `LogScreenFormat.kt` 六个文件，行号已彻底不可考，下面按 `LogScreen.kt` 的引用只保证文件存在，定位请按同一行给出的标识符/函数名检索。2026-11-27 之前其余文件未动，行号仍然准确。

以下问题均能在代码中直接定位，但要么会改变用户可见的界面/交互（本工单没有 `emulator-5560`，也没有在真机上构造完整采集链路；UI 行为只验证了权限弹窗这一种状态），要么需要跨模块设计取舍，因此没有直接改代码。

### 高优先级（功能实质性失效）

1. `app/src/main/java/dev/loglens/data/ProcNames.kt:29`、`data/LogFilter.kt:203` — `ProcNames.refresh()` 全仓库零调用，`table` 永远是空 Map，`package:` / `process:` / `pkg:` / `proc:` 查询恒匹配不到。需要在采集会话里用 IO 协程周期性刷新并让过滤结果可观察，否则加了刷新也不会触发 UI 重新过滤。
2. `app/src/main/java/dev/loglens/ui/LogScreen.kt:394-395`（配合 `logcat/LogcatEngine.kt:474-486`）— 实时日志的堆栈续行是 `level=RAW, isContinuation=true`，在 `viewData` 里被 `priority < VERBOSE` 提前过滤，导致实时堆栈永远不显示、`FoldKind.STACK` 永不触发；离线导入的续行会继承上一条级别，可证明这是实时路径的遗漏。修复需要让续行继承父行级别并调整过滤/计数分支，属于会改变列表内容的 UI 行为改动，需真机用真实崩溃验证。
3. `app/src/main/java/dev/loglens/ui/SettingsScreen.kt:74,167-191` — “崩溃前预录”只读写 `prefs.preRecord`，应用启动路径从不读取它：重启后开关显示开，但没有任何预录在跑；正式采集中打开也会静默失效。需要把“预录是否在跑”改成可观察状态，并决定是否在启动时自动开启（产品行为）。
4. `app/src/main/java/dev/loglens/capture/CaptureController.kt:134-147` — `startPassive()` 不启动 `CaptureService`，被动预录没有前台服务与常驻通知；切后台后进程可被回收，预录的意义被削弱。需要决定预录是否也常驻通知并补上服务启停与对应的停止路径。
5. `app/src/main/java/dev/loglens/ui/NetScreen.kt:192-213` — 停止“时间轴录制”会触发一次单次采样并用 `mergeTracks(emptyMap(), ...)` 合并，已消失连接立即被清空，与页面/README 承诺的“保留 90 秒”矛盾。改为用现有 `tracks` + `KEEP_DEAD_MS` 合并即可，但会改变页面数据表现。
6. `app/src/main/java/dev/loglens/ui/HomeScreen.kt:279-291` — Shizuku 未运行（`alive=false`）时状态卡仍写“已连上，还没拿到 shell 身份”，与同页底部“打开 Shizuku”按钮互相矛盾。这是纯文案/状态修复，但属于用户可见 UI 改动，需要前后截图证据。

### 中优先级（主线程 IO / 生命周期 / 资源）

> 行号说明：以下条目是评审当时的原文记录。`LogScreen` 已在 2026-11-27 拆成 6 个文件（`LogScreen.kt` / `LogScreenChrome.kt` / `LogScreenTools.kt` / `LogScreenPanels.kt` / `LogScreenList.kt` / `LogScreenFormat.kt`），原行号不再有意义，按函数名检索即可。

7. `app/src/main/java/dev/loglens/ui/LogScreen.kt` — SAF 导入与历史文件回放直接用无上限 `readText()`，1–2GB 文件会 OOM；应按文件大小拒绝或改为流式解析。
8. `app/src/main/java/dev/loglens/ui/LogScreen.kt` — “历史文件”列表与删除在主线程 `listFiles/length/delete`；应移到 `Dispatchers.IO`。
9. `app/src/main/java/dev/loglens/shizuku/ShizukuHelper.kt:24,84-101` — 授权结果监听器与静态 `lastResult` 在页面销毁后不注销，回调 lambda 捕获 Activity context；需要在 `HomeScreen` 加 `DisposableEffect` 调用 `releaseListener()`，并让 `releaseListener()` 同步清空 `lastResult`。
10. `app/src/main/java/dev/loglens/ui/NetScreen.kt:216-244` — 时间轴录制协程只绑定 composition，按 Home 后仍每 1.4 秒跑 shell 采样；应改用 `repeatOnLifecycle(RESUMED)` 或页面 stop 时暂停。
11. `app/src/main/java/dev/loglens/ui/NetScreen.kt:222-236` — 停止录制时被取消的采样仍可能把假的“读取失败”写回页面（`runCatching` 吞掉 `CancellationException`）；应重新抛出取消异常并在写状态前检查 `isActive`。
12. `app/src/main/java/dev/loglens/ui/DiagScreen.kt:584-592` — 文件预览关闭后，异步 `readHead` 完成会重新弹出 Dialog，快速切换文件还可能旧内容覆盖新预览；应给预览请求加递增 id 或改为 `LaunchedEffect(previewPath)`。
13. `app/src/main/java/dev/loglens/ui/SettingsScreen.kt:91-95`、`ui/NetScreen.kt:547-560`、`ui/ForensicsScreen.kt:418` — 分别在 `remember`/composition 里做 `getPackageInfo`、`getPackagesForUid/loadLabel`、`File.length()`，都是主线程同步调用；应复用已有的 IO 协程结果或预计算字段。
14. `app/src/main/java/dev/loglens/diag/Forensics.kt:236-286` — 取证落盘的多处 `writeText` 仍被裸 `runCatching` 吞掉；外部目录 null 已兜底，但磁盘满/只读时用户仍只看到“文件列表为空”而没有原因。建议把失败写成一个 NG `Step` 并显示到结论里。
15. `app/src/main/java/dev/loglens/logcat/FileLogWriter.kt:122-131` — ENOSPC 时 `writer?.write/flush` 的 IOException 被吞掉，`alive` 仍为 true，界面继续显示“落盘中”，实际已停止写入；需要把写失败上报到 `CaptureController._notice` 并停止接收。当前提交只修掉了“异常杀进程”与轮转失败停止写入，日常写失败仍静默。

### 低优先级（状态/边界）

16. `app/src/main/java/dev/loglens/data/CrashStats.kt:76`、`ui/LogScreen.kt` — `sample` 截断到 600 字符，跳转却用完整 `message == sample`，超长消息永远跳不到；建议增加 entry id 或比较时同样截断。
17. `app/src/main/java/dev/loglens/data/FilterPreset.kt:55,101-115` — `builtins()` 六条内置预设全仓库零调用，首次安装的预设 chip 为空；需要把内置预设拼进 `PresetStore.load` 的返回值（会改变工具条展示）。
18. `app/src/main/java/dev/loglens/ui/LogScreen.kt:134-158,390-392` — 书签只用裸 `id` 做键，离线导入 id 与实时 id 都从 1 开始，会串收藏；需要带来源的复合键。
19. `app/src/main/java/dev/loglens/ui/LogScreen.kt` — “下一个命中”把命中序号直接当 `LazyColumn` 行号（且折叠后 `visible` 与 `displayRows` 行数不同），单条命中时会滚到列表另一头。
20. `app/src/main/java/dev/loglens/ui/LogScreen.kt` — 跳转到被折叠区间的非头行时只检查 `Fold.head.id`，必然提示“该条不在当前视图内”；需要保存折叠区间并在展开后定位。
21. `app/src/main/java/dev/loglens/ui/LogScreen.kt`（`ui/LogExtras.kt:120-129`）— 迷你图用 `visible` 着色、却按 `displayRows.size` 求跳转下标，折叠时坐标错位；`pointerInput` key 也没有包含折叠后的行数。
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
- **Shizuku 通道**：`ShizukuProcess` 通过反射按「方法名 + 三个参数」命中 `newProcess(String[], String[], String)`；该方法在库里是 private，`proguard-rules.pro` 用 `*** newProcess(...)`（不带 `public`）显式保护——写成 `public static ***` 匹配不到 private，会静默失效；权限回调经 Shizuku 主线程 Handler 派发，Home 回调里写 Compose state/Toast 不存在无 Looper 崩溃；权限状态六种分支的文案与底部动作基本自洽（仅 `alive=false` 文案问题已单列）。
- **logcat 解析/采集**：`LogcatParser` 的年份缓存有 `@Synchronized`；stdout/events 两条流并发解析不会破坏共享状态；`LogcatEngine` 的 argv 数组交给 Shizuku，不经过 shell 字符串拼接；`--uid=` 只由整数 uid 构成，不存在注入；客户端 pid 复核与服务端 `--uid` 双保险逻辑一致；`FileLogWriter` 队列有界、轮转/保留份数已实现。
- **数据层**：`LogRepository` 的 buffer/pending/统计由同一把锁保护，`attach()` 原子清空 pending 避免 LazyColumn 重复 key；`AppRepository.loadInstalled` 在 `Dispatchers.IO`；`Prefs` 的数值项都有 `coerceIn`，无明文凭据；`IssueRules` 预筛关键词与规则表数量有 `require` 校验、正则只编译一次；`HighlightRules` 非法正则/颜色会安全跳过。
- **导出/分享**：`ExportFormats` 的 TXT/CSV/JSON/HTML/ZIP/PDF 都有上限或异常兜底；CSV 有 BOM 与引号转义，JSON/HTML 有转义；分享统一走 FileProvider + `FLAG_GRANT_READ_URI_PERMISSION`；文件名只由包名/时间戳组成，无路径穿越。
- **诊断/取证**：`DiagRepo` 的 `ps` 字段索引、`/proc/<pid>/stat` 第 22 字段处理、`ApplicationExitInfo` 多行块聚合、`Forensics` 的 POSIX 引号函数与 `run-as` 路径处理均正确；`readHead` 对 shell 单引号做了转义；`AnrRepo` 的 dumpsys 通道是纯文本解析，失败只写进说明字段，不抛出。
- **网络快照**：`NetSnapshot` 的 `/proc/net` 列下标、32 位字小端翻转、IPv4/IPv6 长度判断、端口 `toIntOrNull(16)` 都对；解析失败跳过而不猜；不依赖 `ss`/`netstat` 文本。
- **Compose 生命周期**：`LaunchedEffect`/`rememberCoroutineScope` 随 composition 取消；没有动态注册 Receiver/ContentObserver；暂停/继续状态与前台通知状态同步；返回回调与 `enableOnBackInvokedCallback` 兼容；`Components`/`Theme`/`MiuixCompat` 未发现崩溃或状态问题。

## 附：lint 结果

修复后 `gradlew.bat lintDebug --offline --no-build-cache`：**0 errors / 90 warnings / 11 hints**（基线 21 / 92 / 12）。两条 `MissingTranslation` 的处理方式见上：删掉 `values-en` 空壳，界面只保留中文一套文案（`res/values/strings.xml` 顶部有注释，避免再建空语言目录）；`NetScreen` 的 `NonObservableLocale` 已改用 `LocalConfiguration.current.locales[0]`。

基线里的 17 条 `NewApi`（`MetricStyle`/`ProgressStyle`/语义常量）已通过 `@android.annotation.TargetApi(36/37)` 标注说明“只在 guarded 分支调用”而消警；其中真实存在的 API 36.1 守卫错误（`setRequestPromotedOngoing`）已经改代码修复，不是单纯消警。

## 四、第三轮外部评审（2026-11-27）

外部 AI 就仓库现状给了两组意见：代码质量 7 条，评审管线 6 条。逐条处理如下。

### 已改

1. **pid 刷新性能债**（`logcat/LogcatEngine.kt`）：原来固定 4 秒跑一次完整 `ps -A`，而 `ShellRunner` 每条命令尾部都挂 `sleep 0.6` 保活，稳定采集时等于一直在创建 shell 进程。改成 15 秒轮询：先走 `pidof -s 包名` 快路径（一行输出）；包还有进程就不跑 ps；包没进程了、或距上次完整 ps 超过 40 秒，才落回 `ShellRunner.pidsFor` 的 ARGS 解析（`包名:子进程` 不会漏）。pidof 不可用的 ROM 上 `pidsOf` 返回空，自动落回完整 ps，不卡在快路径。
2. **restartCount 数据竞争**（`logcat/LogcatEngine.kt`）：看门狗线程读+自增、读取线程 `emit()` 写 0，普通 `Int` 的 `++` 不是原子操作，读到脏值会让「重启 3 次就放弃」判错。改成 `AtomicInteger`，自增用 `incrementAndGet()`，诊断文本用的也是自增后的值。
3. **全局锁里跑正则**（`data/LogRepository.kt`）：`nextId()` 原来在仓库锁内自增，每行日志在解析与入队时各拿一次锁；`add()` 的锁里还跑 `CrashStats.record()` 的 EXCEPTION 正则与 `ConcurrentHashMap.compute`。改成 `AtomicLong` 无锁发号，崩溃聚合移到锁外——聚合只关心同一签名累计几次，与入队顺序无关。
4. **文档数字漂移**（`README.md` / `security/audit-rules.md`）：README 的「70 条单测」实际 76；audit-rules 的「556 个构件」实际 559。两处已改正。
5. **计数没人校对**（`tools/check_docs.py`）：原来只校验「文件:行号」，数字靠人眼。新增 `check_counts()`：README 的单测数对 `app/src/test` 下的 `@Test` 计数，audit-rules 的构件数对 `gradle/verification-metadata.xml` 的 `<component` 计数，对不上直接红。这一轮就是先让它报错、再改的数字。
6. **AI 评审闭卷**（`tools/pr_review.py`）：原来只等 `pr-audit` 的结论，完全不读 `build` job，所以编译失败、单测挂一半的 PR 照样能拿到「结构性很好、建议合并」。新增 `ci_snapshot` / `ci_verdict` / `ci_fact`：同时读 `文档引用` 与 `编译 + 单测` 两个 check-run；构建失败就不出评审（只写「等 CI 绿了再评」）；构建还在跑则照常评审，但在提示词里写明「本轮结论不含编译结果」。自测 48 → 52 条，`.github/workflows/pr-review.yml` 与 `security/ai-review.md` 的数字同步。
7. **结论模型漂移不披露**（`tools/pr_review.py` / `security/ai-review.md`）：模型队列换人等于换能力档位，同一份 diff 可能从「合理」变成「存疑」；标签一旦由「当时轮到谁」决定就成了噪音。现在结论仍由首选模型给出，一旦降级到队列后面，评论脚注显式标「⚠ 判据与上几轮不可比」。
8. **信任模型与放行边界写进规则**（`SECURITY.md` / `security/ai-review.md`）：明确「默认免费网关会收到 PR 标题、描述、提交信息与 diff 全文，这和 App 里『日志不出本机』是两套信任模型」；并把「AI 评审不构成 approve，合并前必须 CI 全绿 + 真机冒烟」从默契变成仓库规则。

### 9. `CaptureController` 状态机测试（假引擎注入）—— 原来推掉，本轮补上

先抽端口再注入，不改生产行为：

- `logcat/CapturePorts.kt`：新增 `LogcatSource`（start/stop）与 `LogSink`（onFatal/currentFile/start/enqueue/stop）两个接口，`LogcatEngine` 与 `FileLogWriter` 原样实现它们（后者的 `currentFile` 由私有 `curFile` 只读暴露）。
- `capture/CaptureHooks.kt`：单测注入点（`allowWithoutContext` / `skipShizukuChecks` / `skipProcRefresh` / `sourceFactory` / `sinkFactory`），默认全空/全 false 时走真实实现，`CaptureController` 里只有读取、没有写入。
- `CaptureControllerTest` 4 条：①启动中途 `stop()` 必须回 `Idle`、写入器只关一次、不得拉起引擎；②正常启动后 `stop()` 引擎只 stop 一次；③`stop()` 之后旧引擎才回调 `onStarted` 不得把采集复活；④上一个引擎晚到的 `onExit` 不得把新会话清成 `Idle`。fixture 里显式 `AppGraph.install()`，没有引入 Robolectric。

### 10. 拆 `LogScreen.kt` —— 原来推掉，本轮补上

2471 行拆成 6 个文件：`LogScreen.kt` 672（状态容器 + 书签 + 主屏）、`LogScreenChrome.kt` 393（顶栏/底栏/提示条）、`LogScreenTools.kt` 333（工具条 + 导出弹窗）、`LogScreenPanels.kt` 503（五个弹窗 + 筛选面板）、`LogScreenList.kt` 622（列表区/空态/行/操作栏）、`LogScreenFormat.kt` 344（纯函数：着色/格式化/折叠）。纯搬移不改逻辑，顶层 `private` 统一改 `internal` 以跨文件可见，嵌套成员原样；`compileDebugKotlin` 与 `lint` 全绿，WSA 上首页/日志页/弹窗照常渲染。

### 11. 大 PR 的取样：截断之后怎么办

原来 `collect_diff` 是「每个文件从头截 2 万字符 + 全 PR 总量 11 万字符」，两个上限叠加的结果是
**前 5 个文件就把预算吃光**，其余文件只写一句「未展开」。更糟的是单文件从头截断：模型只看到文件
前半段，后半段的 hunk 会被当成「没改」，于是「没问题」成了假结论。

改法（`tools/pr_review.py`）：

- **先保底再回填**：第一轮按「源码 > 其他 > 测试 > 文档」的顺序，每个文件先给 1200 字符保底；
  第二轮把剩余预算按同一顺序补回被裁掉的部分。预算紧张时先饿死文档，不饿死源码。
- **按 hunk 取样，不从头截断**：`.kt` 之类的大文件按 `@@` 切 hunk，每个 hunk 都保留开头；
  单个巨型 hunk 保头保尾，中间写「本 hunk 中间省略」。文件后半段因此仍在视野里。
- **覆盖要有账**：`collect_diff(stats=...)` 记录总文件数、实际读到的、只给节选的；评论脚注直接写
  「读了 85/90 个文件，其中 85 个只给了节选，没展开的部分不等于没问题」。
- **提示词同步**：出现「省略」「未展开」标记时，要求模型在 `scope` 里写明覆盖范围，未覆盖部分不许下结论。

实测（90 个文件、diff 合计 27 万字符的模拟 PR）：旧逻辑覆盖 35/90，新逻辑覆盖 85/90，
喂给模型的仍是约 11 万字符（≈3.6 万 token）。自测 52 → 58 条。

行业里也是这个方向：CodeRabbit 对 50+ 文件的 PR 明确「优先评关键改动、其余只给轻量摘要」，
Qwen Code 在大 diff 时按 ~400 行切块且「只在顶层声明处切、绝不切进函数内部」，
Anthropic 的多遍模式是「每文件一遍 + 跨文件合并去重」。真要做全量，下一步是把单次调用改成
「分批调用 + 合并去重」，那需要额外的模型配额，不是这一次能改完的。

> 本轮守门结果：`assembleDebug` + `testDebugUnitTest` + `lintDebug` 全绿（0 error / 87 warning / 12 hint），单测 80 条（新增 `CaptureControllerTest` 4 条），`python tools/check_docs.py` = 108 处引用 + 2 处计数全部有效，`python tools/pr_review.py --selftest` = 52 条全过。

### 12. 不给 PR 打分：1–5 分换成「评审负担」

原来评论里有一行 `写得怎么样：4/5`。问题不是数字难看，而是**语义错了**：它读起来像「这个 PR 值 4 分」，
是一种总体质量判断，和仓库「AI 不构成 approve」的规矩直接冲突——既然机器人无权决定能不能合，
它就没有资格给这次改动定级。

查了一轮大公司口径，没有一家给 PR 打质量分：

- **GitHub Copilot**：给每条评论贴 `High / Medium / Low` 严重度，同类评论分组折叠；永远只发 Comment，从不 Approve 或 Request changes；
- **Amazon CodeGuru**：只出行内、可执行的建议，不进总体结论；
- **Google AutoCommenter**：AI 只承担约 15% 的评论量，衡量指标是「被作者标为有用」的比例（42%，独立评分 60%，推广门槛 80%），不是 PR 评分；
- **Uber uReview**：75% usefulness、65% 评论被处理（人类 51%），主攻方向是压误报，不是打总分；
- 唯一存在的「分」是路由信息：Meta 的 **Diff Risk Score** 决定这个 diff 要不要跳过人工评审，CodeRabbit 的 **estimated review effort** 告诉人该花多久看。都回答「怎么处理这次改动」，不回答「这次改动值几分」。

改法（`tools/pr_review.py`）：

- 模型输出字段 `quality.score`（1–5 整数）换成 `quality.load`（`轻读` / `正常` / `细看`），提示词写明「这是给人排优先级的，不是质量分，也不会被用来自动放行」，并要求 issues 只写有把握、能指到具体行的；
- `normalize_load` 做归一（兼容 `low/high/中/重` 这类变体），同时**保留旧 1–5 分的映射**（1–2 → 轻读，3 → 正常，4–5 → 细看），老输出不会因为换口径炸掉；
- `sev_key` 把严重度收敛成高 / 中 / 低三档（认 `critical/blocker/P0` 与 `nit/info/minor`），问题表按「高 → 中 → 低」排序，**低级项收进 `<details>` 折叠**，先砍噪声；
- 评论正文写 `**评审负担**：细看`，下面一行小字说明「不是质量评分，也不作为放行依据」。

自测 58 → 63 条（新增：`load` 英文归一、未知值回落正常、旧 1–5 分兼容映射、严重度归一、按严重度排序、低级折叠、负担文案）。

这条和 §11 的取样改造是一套：先保证「看到的范围」说得清，再保证「给结论的粒度」不越权。

### 13. 评论区当数据源：采纳率统计 + 改动分布

国内几家的做法给了两个很具体的参照：

- **字节 BitsAI-CR**（FSE 2025）：核心指标是 **Outdated Rate**——评论过时率，作者是否真的改掉；上线 18 周精准率 25% → 75%，Go 场景过时率 26.7%，1.2 万周活开发者。
- **阿里云效 AI 助手**：行内评论直接带「采纳 / 误报」按钮，评审报告按 Blocker / Critical / Major / Minor 统计；还有变更时序图。

我们没有面板，就用评论区本身当数据源：

- 问题表加 `#` 序号列（含折叠里的低级项，全局连续编号），评论底部写一行：有用回 `useful`，误报回 `noise 2,5`；
- `parse_issue_rows` 从渲染过的评论里反解 `{序号: 严重度}`，`parse_feedback` 解析回复，`--stats` 读评论算误报率与按严重度的命中率——**不调模型、不花钱**；
- 分界线用 `updated_at` 不是 `created_at`：每次 push 都是 PATCH 同一条评论，`created_at` 停在第一轮，用它会把旧反馈算进来，把误报率稀释掉。这一条是实测踩出来的：先用 `created_at`，三次测试的旧反馈被算进新评论，误报率从 33.3% 虚高成 66.7%。

改动分布（对应阿里的「变更时序图」能抄的那一半）：`module_of` 按路径把文件归到 `capture` / `logcat` / `ui` / `tools` / `docs` 这类模块，`render_change_map` 输出「模块 + 文件数 + 文件名」，跨 2 个以上模块加一句「注意接口两端」。**只按路径归组，不代表调用关系**——机器人只看 diff，不假装知道谁调谁。

实测（PR #9，样例评论 3 条问题 + 一条 `noise 2` 回复）：

```
PR #9：最近一次评审（更新于 2026-11-27T13:52:50Z）给了 3 条问题（高 1 / 中 1 / 低 1）
标 noise 1 条 → 误报率 33.3%；明确 useful 0 次。
  高：0/1 被标 noise
  中：1/1 被标 noise
  低：0/1 被标 noise
```

自测 63 → 70 条（新增：模块归组三种路径、跨模块提示、序号列、序号回读、反馈解析）。

### 14. 第五轮外部评审：R8 是假的、年份口径、两处小事、日志里的假状态、定位表述

五条逐条对照源码，代码四条全部成立，第五条是叙事问题。

**B. R8 混淆名不副实。** `proguard-rules.pro` 原来第 8 行是 `-keep class dev.loglens.** { *; }`——自有类名与方法名全保留，R8 只剩「剥 `Log.v/d/i` + 删无用依赖 + 压缩资源」。而 README 却写「release 走 R8 混淆，崩溃堆栈用 `mapping.txt` 还原」，自己的类根本不会出现在 mapping 里。全仓库唯一的反射入口是 `ShizukuProcess.kt:13` 的 `Class.forName("rikka.shizuku.Shizuku")` + `declaredMethods`，`rikka.shizuku.**` 的 keep 已覆盖；依赖里没有 Room / Hilt / kotlinx.serialization，也没有 JNI。删掉宽 keep 后 `assembleRelease` 通过，实测量化结果：

```
dev.loglens.logcat.LogcatParser       -> t4.j        （字段 lastYear -> b）
dev.loglens.capture.CaptureController -> q4.l
dev.loglens.diag.DiagRepo             -> s4.o
rikka.shizuku.Shizuku                 -> rikka.shizuku.Shizuku   （保住）
dev.loglens.LogApp / MainActivity     -> 原名         （Manifest 组件由 AGP 自动保留）
```

README 的 R8 描述同步改成「自有类名与方法名会被重命名，只保留 Shizuku 反射要用的那一个第三方方法名」。

**C. `-v year` 时整份文件塌成一条。** `THREADTIME` / `BRIEF` / `DATE_PREFIX` 三处都按 `^\d{2}-\d{2}` 锚定，而 `QuerySyntax.kt:355` 的 `LogTime.full` 早就支持四位年份。带年份的行三个正则都不匹配 → `startsWithTimestamp` 为 false → `parse()` 返回 null → 调用方按续行处理，整份文件挤成一条巨型堆栈。修法：三处正则加 `(?:\d{4}-)?` 前缀；`fullDate()` 加 `parts.size == 3` 直通分支（logcat 自己给了年份就不再按「单调不减」猜），同时同步 `lastYear` / `lastMonth` 缓存，使后续无年份的行仍落在同一年。测试补 4 条（带年份 threadtime、带年份 brief、带年份行不被当续行、带年 / 不带年混排不串年）。`LogcatParserTest` 原来 13 条用例没有一条覆盖年份行，同期 `LogFilterTest` / `QuerySyntaxTest` 却在用 `"2026-11-27 ..."`，测试口径先于实现。

**D1. events 按子串筛包名会串包。** `LogcatEngine` 用 `line.contains(packageName)`，events payload 形如 `[0,4321,com.tencent.mm,u0a123,activity]`，`com.a` 与 `com.abc` 互为前缀时会把别人的事件捞进来。改成边界匹配 `(?<![A-Za-z0-9_.])<pkg>(?![A-Za-z0-9_.])`（包名先 `Regex.escape`），即要求前后不是标识符字符。

**D2. exit-info 没加引号。** `DiagRepo` 里 `dumpsys activity exit-info $pkg 2>&1` 直接拼，而 `ShellRunner` 自己写明包名一律单引号包裹，同文件 `uidOf` / `pidsOf` 都遵守了。已补 `'$pkg'`。合法包名不含引号，所以这不是注入风险，是约定不一致。

**E. 诊断日志永远打印 `fromState=Idle`。** `CaptureController.stop()` 先把 `_state.value` 置 `Idle`，下一行才取 `_state.value.javaClass.simpleName` 打日志，所以除 Failed 分支外永远是 Idle，「从哪个状态停的」这条排查线索长期失效。改成先取 `val fromState = ...simpleName`，再改状态。

**第五条是定位，不是代码。** README 原写「现在只有三条路」+「LogLens 走的是第四条」。这个断言站不住：Shizuku + 手机端抓 logcat 的 Kotlin 工具（LogFox、ShizuLog 一类）已经存在两三年，能力不弱。已改为「常见的是三条路」，并紧跟一段明说「这个组合不是我们首创」，差异化落到**这条路做了多深**（按 uid 抓整族进程而非按 tag、暂停是真断流、网络快照不装 CA、取证包 + 十分区诊断）。不点名具体第三方 App 的策略不变，只承认「同类已存在」。

验证：单测条数 80 → 84（0 失败）、lint 0 error / 87 warning 不变、`assembleRelease` 通过（R8 真跑，mapping 改名如上）、`tools/check_docs.py` 113 处引用全部有效、`tools/pr_review.py --selftest` 70 条不变。

### 15. 第六轮外部评审：七条逐条核实，六条落地、一条明确不动

七条逐条对照源码核实：**六条成立并已改**，第四条成立但本轮不动（理由见该条）。另有一条（第 3 条后半）的指控不成立。

**① 一键清空整机日志缓冲没有二次确认 —— 成立。** `BufferDialog` 原来 `TextButton(onClick = onClearDevice)` 一下点就执行，落到 `LogScreenPanels.kt` 的 `Thread { ShellRunner.exec("logcat -b all -c") }`。这一步清的是**整机所有 App** 的缓冲，不只是被采集的那个，而 `LogcatEngine.kt` 自己写过注释「`-G` 会清空缓冲、实测踩过一次」。改成两步：先点「清空设备日志缓冲（`logcat -b all -c`）」，展开后红字说明「这会清掉整机所有 App 的日志缓冲（包括没在抓的），清完无法恢复」，再点「确认清空」。

**② FileProvider 用 `path="."` 暴露整个私有目录 —— 成立。** `res/xml/file_paths.xml` 原来是 3 条 `external-files-path` + `<files-path name="inner" path="." />` + `<cache-path name="cache" path="." />`，等于整个 `files/` 与 `cache/` 都能通过 `content://` 读出，而分享入口只有 `Exporter.kt:40` 与 `ExportFormats.kt:296` 两处。按实际目录清单收成显式白名单：外部 `exports/` `forensics/` `capture/` `crash/`；内部 `exports/` `forensics/` `capture/` `crash/` `self_log/` `self_crash/`；cache 只留 `feedback/`。文件头加一行「新增可分享目录必须显式加一行」。

**③ 通知里的「崩溃 N」是行数不是次数 —— 数确实打架，但「release 只有标题」不成立。** `CaptureService.kt` 的 `"崩溃 ${stats.crashes}"` 来自逐行 `isCrash()` 命中数（一条异常会占 `Process:` / `FATAL EXCEPTION` / `backtrace:` 好几行），而 `CrashStats` 按异常签名聚合出 today/week/total，于是同一个崩溃在通知里显示 5、在统计面板显示 1。已把通知改成「崩溃行 N」，`isCrash` 的 KDoc 补一句「它的计数是崩溃行数，次数在 CrashStats」。反过来，v1.2.5 的 release body 实际有 1705 字节（不是「只有一个标题」）；真正缺的是 v1.2.1–v1.2.4 四个旧 tag 的 release notes，本轮用 CHANGELOG 段落回填。

**④ `CaptureController` 仍是 `object` 单例，改造只做了一半 —— 成立，本轮明确不动。** 现状是 `object CaptureController` + `fun init(context: Context)` + `CaptureHooks.allowWithoutContext` 测试后门，全仓库 `CaptureController.` 引用 88 处 / 9 个文件（`CaptureService` 40、`MainActivity` 16、`FeedbackScreen` 8、`HomeScreen` 7、`SettingsScreen` 6、`LogScreen` 5、`LogScreenChrome` 3、`LogScreenTools` 2、`LogApp` 1），`CaptureControllerTest` 正是靠 `init(context)` 塞假引擎。真做要动 88 个调用点，而这条链路（Shizuku 授权 → 起进程 → uid 抓流）的回归只能真机跑，本轮同时改了日志解析状态模型，两者叠加会让「谁坏了」无法定位——所以按「一轮只动一组」的原则推后。推荐形态（下一轮做）：`class CaptureSession(deps)` 承载全部逻辑，`object CaptureController` 退化成门面只做委托，`install(impl)` 供测试注入，调用点一行不用改、测试改 3 行。

**⑤ 年份推断状态全局可变、两路流共享 —— 成立。** `LogcatParser` 的 `lastYear/lastMonth/dateKey` 是 object 全局，而 `LogcatEngine` 的 `readEvents` 与 `readStdout` 是两个线程同时 parse：events 流里的 `-v year` 日期会把 `lastMonth` 顶到 12 月，主日志流下一次「1 月」行就会被判成跨年进位，整段年份错位。改法：年份状态抽成 `class Clock`（`reset()` / `fullDate()` 内部 `synchronized(this)`），object 持一个 `defaultClock` 供单测与老调用方用，引擎给两路各持一个 `stdoutClock` / `eventsClock`，`start()` 里三个一起 reset。**构建时踩到一个坑**：`parse` 原签名是 `(line, nextId, clock = defaultClock)`，而测试与部分调用写 `parse(line) { nextId++ }`——尾随 lambda 会绑到**最后一个**参数 `clock` 上，编译报 `() -> Long` 与 `Clock` 类型不符。参数顺序改为 `(line, clock = defaultClock, nextId)` 后自然解决。新增单测「年份状态按 clock 隔离：events 的日期不会带偏 main」。

**⑥ `ShellRunner.exec` 用 `{ cmd ; }` 分组 —— 成立。** 原 wrapped 是 `"{ $command ; } ; __sl_rc=$? ; sleep 0.6 ; exit $__sl_rc"`。`{ }` 是 shell 的分组语法，命令里只要出现 `}`（比如 `sh -c` 里拼 JSON、或包名/路径里带花括号），分组就提前闭合，剩下的半截被当新命令执行。改成按换行拼接：`"$command\n__sl_rc=$?\nsleep 0.6\nexit $__sl_rc"`，语义不变（`$?` 仍是前面命令的退出码），注释补明「调用方保证命令不以换行结尾、不中途 `exit`」。

**⑦ 缺 CONTRIBUTING / CHANGELOG / release notes —— 成立。** 根目录原来只有 README + SECURITY，`.github/` 里模板、CODEOWNERS、四个 workflow 都齐，唯独没有面向贡献者的说明与版本记录。新增 `CHANGELOG.md`（v1.2.1 → 1.2.5 + 未发布段，条目取自真实 tag 与提交，不编造）与 `CONTRIBUTING.md`（提 Issue 前先走「问题反馈」贴自身日志、一个 PR 只做一件事、CI 必须绿、不新增联网 SDK、依赖变更同步 `verification-metadata.xml`、纯逻辑补 JVM 单测、明确不接受 root / VpnService 抓包 / 换皮收费），README 的「许可」前加「更新记录与参与改进」两行链接。

验证：单测 84 → 85（0 失败，新增 clock 隔离用例）、lint 0 error / 87 warning、`assembleDebug` 通过、`tools/check_docs.py` 113 处引用全部有效、`tools/pr_review.py --selftest` 70 条。README 里「84 条单测」同步改成 85（`check_docs.py` 的计数校验抓到的就是这个）。



