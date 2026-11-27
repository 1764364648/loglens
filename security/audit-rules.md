# pr_audit 审计规则清单

这份文档是 `tools/pr_audit.py` 的说明书：每个分类查什么、为什么查、依据是哪个真实事件。
规则和自测（`tools/test_pr_audit.py`，50 条用例）在同一次提交里维护；改规则必须同时补用例，否则自测会失败。

## 闸门的基本约束

- 只读 PR 的 diff 文本，**不 checkout PR、不执行 PR 里的任何代码、不加载 PR 的依赖**。
- CI 用的脚本来自 **base 分支**（`pull_request_target` + 按 `base.sha` 取文件），PR 改不到自己的裁判。
- 权限只有 `contents: read` / `pull-requests: read`，没有任何 `uses:`（第三方 action 在本仓库被组织级策略禁掉）。
- 有阻断项 → 退出码 1 → 检查失败 → `main` 的分支保护拒绝合并。
- `--pr` 模式默认逐提交复查；`--range` 模式只查净 diff（本地分支的净 diff就是事实）。

## 依据：这些手法都真实发生过

| 手法 | 真实案例 / 出处 | 本仓库的对策 |
| --- | --- | --- |
| 先加后撤：PR 里放恶意提交 X，再放一个撤销 X 的提交 Y，净 diff 干净 | SLSA 威胁目录 "Commit graph attacks" | 逐提交复查（提交级检查），单个提交里有阻断项就整体阻断 |
| 把命令注入写进**文件名**：`m$(echo${IFS}Y3VybC...\|base64 -d\|bash).md` | Datadog 公开的 hackerbot-claw 事件（datadog-iac-scanner） | 文件名命令注入字符、路径穿越、不可见字符检查 |
| "pwn request"：`pull_request_target` 里 checkout 了 fork 的代码，令牌与 secrets 一起被拿走 | GitHub Security Lab《Preventing pwn requests》 | 工作流从不 checkout PR；只取基线脚本；令牌只读 |
| 把表达式直接拼进 `run:` 造成注入（PR 标题/分支名/文件名都算输入） | 同上事件的安全复盘 | 工作流只用 `env:` 传参，从不把事件字段拼进命令 |
| 未固定版本 / 第三方 action 被投毒 | 同上 | Actions 策略 `local_only`（第三方 action 不可用）+ 默认令牌只读 |
| Trojan Source：用 Unicode 双向控制符让"审阅者看到的顺序"和"编译器读到的顺序"不一致（CVE-2021-42574） | trojansource.codes / NVD | 行内 bidi 控制字符即阻断 |
| 同形字符：西里尔/希腊字母冒充拉丁字母，两个标识符看起来一样（CVE-2021-42694） | 同上论文 | 非中文行里出现西里尔/希腊字符即阻断 |
| 依赖混淆 / 仿冒包名（把内部包名发布到公共仓库，或只差一个字符） | Alex Birsan 2021 披露（Microsoft/Apple/PayPal 等 35 家） | 依赖白名单 + 编辑距离 ≤2 判仿冒 + 非注册源依赖阻断 |
| 依赖哈希缺失，构建时拉到被替换的构件 | SLSA / 供应链实践 | `gradle/verification-metadata.xml`（556 个构件 SHA-256），改它即阻断 |
| 安装器路径穿越（版本号里的 `../` 被当路径拼接，最终以 root 执行） | Homebrew cask 类漏洞复盘 | 文件名/路径穿越检查 |
| 二进制载荷混进仓库（jar/dex/so/压缩包），diff 里看不见内容 | 多起 npm/PyPI 投毒事件的共同特征 | 二进制后缀一律阻断，资源目录新增降级为待确认 |
| 借新权限/导出组件/无障碍服务把 App 变成采集器 | Android 恶意软件常见组合 | Manifest 专项检查 |
| 构建期执行命令（`exec {}`、`commandLine`、`ProcessBuilder`）在维护者机器上以本人身份跑 | 供应链攻击通用手法 | 构建脚本里的构建期执行一律阻断 |

## 分类与规则

严重度：**阻断** = 不允许合并；**待确认** = 可以合并，但合并前必须有人看一眼并在 PR 里说明。

### 1. 仓库结构与供应链文件（阻断）

- 改动 `.github/workflows/`：工作流能拿到令牌，必须维护者自己改，不能走 PR。
- 改动 `.github/CODEOWNERS`：这是审查豁免的规则本身。
- 改动 `gradle/wrapper/`、`gradlew`、`gradlew.bat`：wrapper 决定"用什么构建器"，注入过有先例。
- 碰到签名材料（`keystore/`、`*.jks`、`*.keystore`、`keystore.properties`）或 `local.properties`。
- 改动 `gradle/verification-metadata.xml`：新增/替换依赖必须维护者在本地重新生成哈希。
- 引入 `.gitmodules`（子模块 = 未经审查的外部代码）。
- 引入 `buildSrc/` 或 `gradle/conventions/`：构建逻辑能以 Gradle 守护进程身份执行任意代码。
- 引入 `.devcontainer/`、`.vscode/`、`.idea/`、`Dockerfile`、`docker-compose*`、`.pre-commit-config*`：打开仓库时可能自动执行。
- 改动 `tools/pr_audit.py` 自身：闸门不能给自己开口子，改规则只能由维护者直接提。
- 出现别的包管理生态（`package.json`、`Cargo.toml`、`go.mod`、`requirements.txt`…）：新生态 = 新的安装期执行链。

### 2. 文件名与路径（阻断）

- 路径穿越：路径里含 `..`、以 `/` 开头、或含反斜杠。
- 文件名里有命令注入字符：`$`、反引号、`${IFS}`、`;`、`|`、`&&`、`||`、重定向符。
- 文件名的不可见/双向控制字符；文件名里的西里尔/希腊同形字符。

### 3. 二进制与伪装文件

- 阻断：`.jar .aar .apk .aab .dex .so .dll .exe .bin .class .jks .keystore .zip .7z .rar .tar .gz .xz .wasm .dylib .msi .scr .pyc .img .iso .dmg .cab .dat .pak`。
- 夜里也好理解：这些文件在 diff 里看不到内容，合并前等于闭眼。
- 待确认：`src/main/assets|res/raw|jniLibs` 下新增文件；新增未知后缀文件；diff 被 GitHub 截断（文件太大，需要本地看全文）。

### 4. AndroidManifest（阻断为主）

- 新增权限（只要不在白名单里，无论多"无害"）。
- `android:exported="true"`；`android:debuggable="true"`；`usesCleartextTraffic="true"`；`networkSecurityConfig`。
- `android:sharedUserId`；`tools:node="replace|remove"`（用来顶掉父级约束）。
- `BOOT_COMPLETED`。
- 无障碍 / 设备管理 / 通知监听 / VPN / 输入法 / MediaProjection 相关声明。
- 待确认：新增组件或 `intent-filter`、`android:process`、`<queries>`。

### 5. 构建脚本与依赖

阻断：

- 新增/改动仓库源（`maven(...)`、`mavenCentral()`、`google()`、`gradlePluginPortal`、`jitpack`、`mavenLocal()`、`flatDir`、`ivy`、`exclusiveContent`）。
- `buildscript {}`、`apply(plugin ...)`：往构建脚本里塞代码。
- 劫持依赖解析：`resolutionStrategy`、`dependencySubstitution`、`eachDependency`、`force(...)`、`capabilities`、`componentSelection`、`substitute(`。
- 构建期执行：`commandLine`、`providers.exec`、`exec {}`、`ProcessBuilder`、`Runtime.`、`System.exec`。
- 改动签名或标识：`signingConfig`、`storeFile`、`storePassword`、`keyAlias`、`keyPassword`、`applicationId`、`namespace`。
- 新增构建插件（`classpath` / `id(...)` 带 version）。
- 非注册源依赖：URL / `git+` / `file:` / `files()` / `fileTree()` 引本地 jar。
- 新增依赖不在 `security/dependency-allowlist.txt`；或名字与白名单里某条编辑距离 ≤2（仿冒）。
- 删除 `mavenCentral`、`google()`、`verification-metadata` 之类的安全配置。

待确认：关闭混淆/收缩（`isMinifyEnabled = false` 等）、改 `buildConfigField`/`resValue`/`packaging`/`jniLibs`/`sourceSets`/`assets`、放宽 lint（`abortOnError = false`）、改构建 JVM 参数、削弱 `proguard-rules.pro`、删 `-keep` 规则。

### 6. 网络与通信（阻断）

`HttpURLConnection`、OkHttp、Retrofit、Ktor、Fuel、Volley、`java.net.*`、`openConnection`、`URL(...)`、Socket/SSL/WebSocket/InetAddress、`WebView` / `loadUrl` / `evaluateJavascript` / `WebViewClient`。

这个 App 的全部用途是本地读日志，**它不需要联网**。所以任何联网能力都是"这个 PR 想干什么"的证据。

### 7. 上报与遥测（阻断）

Firebase、Crashlytics、Sentry、Bugly、友盟、AppsFlyer、AppMetrica、Adjust、广告 ID（`getAdvertisingId`/oaid/openudid）、installReferrer、`trackEvent`。

### 8. 动态加载与命令执行

阻断：`DexClassLoader` / `PathClassLoader` / `InMemoryDexClassLoader` / `BaseDexClassLoader` / `loadDex`；提权或静默装包命令（`pm install`、`cmd package install`、`settings put`、`su -c`、`/system/bin/su`）。

待确认：`Runtime.exec`、`ProcessBuilder`、`.exec(`、`System.loadLibrary`、`Class.forName`、`setAccessible`、`MethodHandles`。

LogLens 自己要用 `su` 和 `Runtime.exec` 读日志，所以这里只降级为待确认，靠人判断**新增的那一行是不是在读日志**。

### 9. 数据访问与敏感能力

阻断：`ContactsContract`、`SmsManager`、`TelephonyManager`、`CallLog`、`content://sms`、`MediaStore.Audio`、`READ_CONTACTS`、`READ_SMS`。

待确认：定位、相机、传感器、剪贴板、账号。

### 10. 持久化与后台常驻

待确认：`AlarmManager`、`JobScheduler`、`WorkManager`、`setExactAndAllowWhileIdle`、`setPeriodic`、`PeriodicWorkRequest`。
阻断：清单里的 `BOOT_COMPLETED`（开机自启，配合后台任务就是常驻）。

### 11. 混淆、编码与不可见字符（阻断）

- 大段 Base64（≥180 个 base64 字符）或大段十六进制（≥160 个 hex 字符）：典型的一行装一个载荷。
- 行内 bidi 控制字符（Trojan Source）。
- 一行里 ≥2 个零宽/不可见字符。
- 非中文行出现西里尔/希腊字符（同形字符）。
- 待确认：大量 `\u` 转义（≥6 个）、超长单行（>3000 字符）。

### 12. 疑似密钥与凭据（阻断）

`aws_secret_access_key`、`ghp_`、`github_pat_`、`AKIA...`、`-----BEGIN ... PRIVATE KEY-----`、`xox[bap]-`、`AIza...`、`sk-...`。
所有非二进制文件都扫，包括文档——在 issue/文档里贴 token 也是泄露。

### 13. PR 元信息（待确认）

作者账号年龄 < 90 天、`author_association` 不是 OWNER/MEMBER/COLLABORATOR、提交数 > 5、改动文件 > 50、新增行 > 1500。
单条都不足以定罪，但"新账号 + 大量改动 + 多提交"的组合值得停下来看。

### 14. 提交级检查（阻断）

对 PR 里每个提交单独跑一遍文件规则（最多 20 个提交）。任何单个提交里出现阻断项 → 整体阻断。
目的就是防「先加后撤」：净 diff 干净，过程不干净。

## 怎么跑

```bash
python tools/pr_audit.py --pr 12 --repo 1764364648/loglens   # 线上 PR，默认逐提交复查
python tools/pr_audit.py --pr 12 --no-commits                # 只想看净 diff
python tools/pr_audit.py --range origin/main..HEAD           # 本地分支
python tools/test_pr_audit.py                                # 规则自测（50 条）
```

退出码 0 = 没有阻断项；1 = 有阻断项；2 = 参数/令牌问题。

## 改规则的正确姿势

1. 先改 `tools/pr_audit.py`，同时在 `tools/test_pr_audit.py` 里加至少一条用例（正例和反例都加，防止误报把正常贡献者全挡在门外）。
2. 本地跑 `python tools/test_pr_audit.py`，全绿再提交（CI 只跑审计本体，自测由维护者在提交前跑）。
3. 这个文件本身在阻断清单里，所以只能由维护者直接改，不能通过 PR 改——避免"先把闸门拆了再作案"。
4. 顺手在本文档里补一行说明：查什么、为什么。

## 明确不覆盖的部分

诚实列出来，避免给人"什么都拦得住"的错觉：

- **语义级分析**：看不懂 Kotlin/Java 的业务逻辑，只能识别危险 API 与结构特征。
- **运行时行为**：不执行 PR，也就不可能发现只有在运行时才暴露的问题。
- **上游包本身被投毒**：白名单只能保证"坐标没变"，构件内容靠 `verification-metadata.xml` 的 SHA-256；上游维护者自己作恶拦不住。
- **Gradle 插件内部逻辑**：新增插件已阻断，但既有插件版本升级的内容变化不在 diff 里。
- **混淆/加密载荷的意图**：只能标记"这里有大段编码内容"，是否恶意要人看。
- **社会工程**：冒充熟人、在 issue 里引导你点链接、在 PR 描述里催你"快合"，只能靠流程（分支保护 + 必须审查 + 必须自己看一眼 diff）。
