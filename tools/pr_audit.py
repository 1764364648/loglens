#!/usr/bin/env python3
"""LogLens PR 审计：合并前把这个 PR 的 diff 从头到尾过一遍。

只读 diff 文本，不执行 PR 里的任何代码、不 checkout PR、不加载 PR 的任何东西。

    python tools/pr_audit.py --pr 12                     # 查线上 PR（需要 gh 登录或 GITHUB_TOKEN）
    python tools/pr_audit.py --range origin/main..HEAD   # 查本地分支

退出码：0 没有阻断项；1 有阻断项（main 上的分支保护会拒绝合并）。
规则来源见 security/audit-rules.md。
"""
import argparse
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

BLOCK = "阻断"
WARN = "需人工确认"

CAT_REPO = "仓库结构与供应链文件"
CAT_PATH = "文件名与路径"
CAT_BIN = "二进制与伪装文件"
CAT_MANIFEST = "AndroidManifest 权限与组件"
CAT_BUILD = "构建脚本与依赖"
CAT_NET = "网络与通信"
CAT_TELEM = "上报与遥测"
CAT_EXEC = "动态加载与命令执行"
CAT_DATA = "数据访问与敏感能力"
CAT_PERSIST = "持久化与后台常驻"
CAT_OBF = "混淆、编码与不可见字符"
CAT_SECRET = "疑似密钥与凭据"
CAT_META = "PR 元信息"
CAT_GRAPH = "提交级检查"

CATEGORIES = [
    CAT_REPO, CAT_PATH, CAT_BIN, CAT_MANIFEST, CAT_BUILD,
    CAT_NET, CAT_TELEM, CAT_EXEC, CAT_DATA, CAT_PERSIST,
    CAT_OBF, CAT_SECRET, CAT_META, CAT_GRAPH,
]

BIN_EXTS = {
    ".jar", ".aar", ".apk", ".aab", ".dex", ".so", ".dll", ".exe", ".bin",
    ".class", ".jks", ".keystore", ".p12", ".pfx", ".zip", ".7z", ".rar",
    ".tar", ".gz", ".bz2", ".xz", ".wasm", ".dylib", ".msi", ".scr", ".pyc",
    ".img", ".iso", ".dmg", ".cab", ".dat", ".pak",
}
CODE_EXTS = {
    ".kt", ".java", ".kts", ".gradle", ".xml", ".pro", ".toml",
    ".properties", ".py", ".sh", ".bash", ".yml", ".yaml",
}
SCRIPT_EXTS = {".sh", ".bash", ".ps1", ".bat", ".cmd", ".py", ".zsh", ".fish"}
OTHER_ECOSYSTEM = re.compile(
    r"(^|/)(package\.json|package-lock\.json|yarn\.lock|pnpm-lock\.yaml|"
    r"Cargo\.toml|Cargo\.lock|go\.mod|go\.sum|requirements\.txt|Pipfile|pyproject\.toml)$"
)

ALLOWED_PERMISSIONS = {
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.POST_PROMOTED_NOTIFICATIONS",
    "android.permission.WAKE_LOCK",
    "android.permission.SYSTEM_ALERT_WINDOW",
}

DENY_PATHS = [
    (r"^\.github/(workflows|actions)/", CAT_REPO, "改动了 CI 工作流 / action", "工作流能直接拿到令牌，必须维护者单独处理"),
    (r"(^|/)CODEOWNERS$", CAT_REPO, "改动了 CODEOWNERS", "这是审查豁免的门闩"),
    (r"^gradle/wrapper/", CAT_REPO, "改动了 Gradle Wrapper", "wrapper 决定构建器本身"),
    (r"(^|/)gradlew(\.bat)?$", CAT_REPO, "改动了 gradlew 启动脚本", "构建入口脚本被注入过有先例"),
    (r"^keystore/|\.jks$|\.keystore$|^keystore\.properties$|^local\.properties$", CAT_REPO, "碰到签名材料或本机配置", ""),
    (r"verification-metadata\.xml$", CAT_REPO, "改动了依赖校验清单", "新增/替换依赖必须维护者本地重生成"),
    (r"(^|/)\.gitmodules$", CAT_REPO, "引入了 git submodule", "子模块 = 未经审查的外部代码"),
    (r"^(buildSrc|gradle/conventions)/", CAT_REPO, "引入了构建逻辑目录", "构建逻辑能以守护进程身份执行任意代码"),
    (r"^(\.devcontainer|\.vscode|\.idea)/|^Dockerfile$|^docker-compose|\.pre-commit-config", CAT_REPO, "改动开发环境/钩子配置", "打开仓库时可能自动执行"),
    (r"^tools/pr_audit\.py$", CAT_REPO, "改动了审计脚本自身", "闸门不能自己给自己开口子"),
]
WARN_PATHS = [
    (r"^\.github/ISSUE_TEMPLATE/", CAT_REPO, "改动了 issue 表单", "表单会把恶意链接直接送到维护者眼前"),
    (r"^security/dependency-allowlist\.txt$", CAT_REPO, "改动依赖白名单", "任何新增都要维护者确认"),
    (r"^app/proguard-rules\.pro$", CAT_BUILD, "改动了混淆规则", ""),
    (r"^gradle\.properties$", CAT_BUILD, "改动了 gradle.properties", "可能改构建器参数，如 jvmArgs"),
    (r"^\.gitattributes$|^\.gitignore$", CAT_REPO, "改动仓库属性/忽略项", ""),
]

CODE_RULES = [
    (CAT_NET, BLOCK, r"HttpURLConnection|\bokhttp3?\b|\bRetrofit\b|\bKtor\b|\bFuel\b|\bVolley\b|"
                      r"java\.net\.|URLConnection|openConnection|\bURL\s*\(|SSLSocket|DatagramSocket|SocketChannel|WebSocket|"
                      r"InetAddress|getByName\(|SSLContext", "网络通信 API"),
    (CAT_NET, BLOCK, r"\bWebView\b|loadUrl\(|evaluateJavascript|WebViewClient", "WebView"),
    (CAT_TELEM, BLOCK, r"Firebase|Crashlytics|Sentry|Bugly|Umeng|Appsflyer|AppMetrica|Adjust|"
                       r"getAdvertisingId|oaid|openudid|installReferrer|trackEvent", "第三方统计/上报"),
    (CAT_EXEC, BLOCK, r"DexClassLoader|PathClassLoader|InMemoryDexClassLoader|BaseDexClassLoader|loadDex\(",
     "动态加载 dex"),
    (CAT_EXEC, BLOCK, r"\bpm install\b|cmd package install|\bsettings put\b|/system/bin/su|/system/xbin/su|"
                      r"\bsu\s+-c\b", "提权/静默装包命令"),
    (CAT_EXEC, WARN, r"Runtime\.getRuntime\(\)\.exec|\bProcessBuilder\b|\.exec\(", "起子进程"),
    (CAT_EXEC, WARN, r"System\.loadLibrary|System\.load\(", "加载 native 库"),
    (CAT_EXEC, WARN, r"Class\.forName|setAccessible\(|MethodHandles", "反射"),
    (CAT_DATA, BLOCK, r"ContactsContract|SmsManager|TelephonyManager|CallLog|content://sms|"
                      r"MediaStore\.Audio|READ_CONTACTS|READ_SMS", "通讯录/短信访问"),
    (CAT_DATA, WARN, r"LocationManager|FusedLocationProvider|CameraManager|SensorManager|"
                     r"ClipboardManager|AccountManager", "定位/相机/剪贴板/账号"),
    (CAT_PERSIST, WARN, r"AlarmManager|JobScheduler|WorkManager|setExactAndAllowWhileIdle|"
                        r"setPeriodic\(|PeriodicWorkRequest", "后台常驻调度"),
    (CAT_OBF, BLOCK, r"[A-Za-z0-9+/]{180,}={0,2}", "大段 Base64"),
    (CAT_OBF, BLOCK, r"\b(?:[0-9a-fA-F]{2}){80,}\b", "大段十六进制"),
    (CAT_OBF, WARN, r"\\x[0-9a-fA-F]{2}(?:\\x[0-9a-fA-F]{2}){8,}", "长串转义字节"),
]
SECRET_PAT = (r"(?i)(aws_secret_access_key|ghp_[A-Za-z0-9]{20,}|github_pat_|AKIA[0-9A-Z]{16}|"
              r"-----BEGIN [A-Z ]*PRIVATE KEY-----|xox[bap]-|AIza[0-9A-Za-z_\-]{30,}|sk-[A-Za-z0-9]{20,})")

BIDI_CHARS = "\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069\u200e\u200f\u061c"
INVISIBLE = "\u200b\u200c\u200d\u2060\ufeff\u00ad"
CYRILLIC_GREEK = re.compile(r"[\u0400-\u04FF\u0370-\u03FF]")
CJK = re.compile(r"[\u4e00-\u9fff]")
CMD_IN_NAME = re.compile(r"[`$]|\$\{IFS\}|\|\||&&|;|\||>|<|\|\s*sh\b")

def coords_allowlist(root="."):
    path = Path(root) / "security" / "dependency-allowlist.txt"
    if not path.exists():
        return set()
    out = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"^([A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+)", line)
        out.add(m.group(1) if m else line)
    return out


def edit_distance(a, b, cap=3):
    if abs(len(a) - len(b)) > cap:
        return cap + 1
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, cb in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb))
        prev = cur
    return prev[-1]


def added_lines(patch):
    out = []
    for raw in (patch or "").splitlines():
        if raw.startswith("+++") or raw.startswith("---"):
            continue
        if raw.startswith("+"):
            out.append(raw[1:])
    return out


def removed_lines(patch):
    out = []
    for raw in (patch or "").splitlines():
        if raw.startswith("---"):
            continue
        if raw.startswith("-"):
            out.append(raw[1:])
    return out


class Finding:
    def __init__(self, cat, sev, title, detail):
        self.cat = cat
        self.sev = sev
        self.title = title
        self.detail = detail

    def key(self):
        return (self.cat, self.sev, self.title, self.detail)


def audit_file(entry, allow, findings):
    """检查单个文件。entry 需要 filename / status / patch。"""
    path = entry.get("filename") or ""
    status = entry.get("status", "modified")
    patch = entry.get("patch") or ""
    low = path.lower()
    ext = Path(low).suffix
    add = added_lines(patch)
    drop = removed_lines(patch)

    # 测试夹具与规则说明里本来就写着各种危险样例，这些文件里只降级不阻断
    meta_soft = bool(re.match(r"(^|/)(test_pr_audit\.py|audit-rules\.md)$", path))

    def add_f(cat, sev, title, detail=""):
        if meta_soft and sev == BLOCK and cat in (
                CAT_NET, CAT_TELEM, CAT_EXEC, CAT_DATA, CAT_PERSIST, CAT_OBF, CAT_SECRET):
            title = "样例文件里的危险写法：" + title
            sev = WARN
        findings.append(Finding(cat, sev, title, detail))

    # 1) 路径黑名单
    for pat, cat, title, note in DENY_PATHS:
        if re.search(pat, path):
            add_f(cat, BLOCK, title, f"`{path}`" + (f" —— {note}" if note else ""))
    for pat, cat, title, note in WARN_PATHS:
        if re.search(pat, path):
            add_f(cat, WARN, title, f"`{path}`" + (f" —— {note}" if note else ""))

    if OTHER_ECOSYSTEM.search(path):
        add_f(CAT_REPO, BLOCK, "引入别的包管理生态", f"`{path}` —— 新生态等于新的安装时执行链")

    # 2) 文件名/路径攻击
    if ".." in path or path.startswith("/") or "\\" in path:
        add_f(CAT_PATH, BLOCK, "路径穿越", f"`{path}`")
    if CMD_IN_NAME.search(path):
        add_f(CAT_PATH, BLOCK, "文件名带命令注入字符", f"`{path}`")
    if any(ch in path for ch in BIDI_CHARS) or any(ch in path for ch in INVISIBLE):
        add_f(CAT_PATH, BLOCK, "文件名含不可见/双向控制字符", f"`{path}`")
    if CYRILLIC_GREEK.search(path):
        add_f(CAT_PATH, BLOCK, "文件名含西里尔/希腊同形字符", f"`{path}`")

    # 3) 二进制与资源
    if ext in BIN_EXTS:
        add_f(CAT_BIN, BLOCK, "提交了二进制/压缩包", f"`{path}` —— diff 里没法审内容")
    if ext in SCRIPT_EXTS and status == "added":
        add_f(CAT_REPO, WARN, "新增可执行脚本", f"`{path}` —— 本仓库原本没有脚本，确认它要做什么")
    if re.search(r"(^|/)src/main/(assets|res/raw|jniLibs)/", path) and status == "added":
        add_f(CAT_BIN, WARN, "新增资源文件", f"`{path}` —— 资源同样能被加载执行，确认来源")

    # 4) Manifest
    if low.endswith("androidmanifest.xml"):
        for ln in add:
            for perm in re.findall(r"android\.permission\.([A-Z_0-9]+)", ln):
                full = "android.permission." + perm
                if full not in ALLOWED_PERMISSIONS:
                    add_f(CAT_MANIFEST, BLOCK, "新增权限", f"`{path}` 新增 `{full}`")
            if re.search(r"android:exported\s*=\s*\"true\"", ln):
                add_f(CAT_MANIFEST, BLOCK, "组件导出", f"`{ln.strip()[:100]}` —— 任意应用可拉起")
            if re.search(r"android:debuggable\s*=\s*\"true\"", ln):
                add_f(CAT_MANIFEST, BLOCK, "开启 debuggable", f"`{ln.strip()[:100]}`")
            if re.search(r"usesCleartextTraffic\s*=\s*\"true\"|networkSecurityConfig", ln):
                add_f(CAT_MANIFEST, BLOCK, "放开明文/网络安全配置", f"`{ln.strip()[:100]}`")
            if re.search(r"android:sharedUserId", ln):
                add_f(CAT_MANIFEST, BLOCK, "声明 sharedUserId", f"`{ln.strip()[:100]}`")
            if re.search(r"tools:node\s*=\s*\"(replace|remove)\"", ln):
                add_f(CAT_MANIFEST, BLOCK, "清单节点替换/删除", f"`{ln.strip()[:100]}` —— 常用来自顶父级约束")
            if re.search(r"BOOT_COMPLETED|RECEIVE_BOOT_COMPLETED", ln):
                add_f(CAT_PERSIST, BLOCK, "开机自启", f"`{ln.strip()[:100]}`")
            if re.search(r"AccessibilityService|DeviceAdmin|NotificationListenerService|"
                         r"VpnService|InputMethodService|MediaProjection", ln):
                add_f(CAT_DATA, BLOCK, "高权限系统服务", f"`{ln.strip()[:100]}` —— 能读全屏/通知/按键")
            if re.search(r"<(receiver|service|provider|activity)\b|<intent-filter", ln):
                add_f(CAT_MANIFEST, WARN, "新增组件/意图过滤", f"`{ln.strip()[:100]}`")
            if re.search(r"android:process\s*=", ln):
                add_f(CAT_PERSIST, WARN, "独立进程", f"`{ln.strip()[:100]}`")
            if re.search(r"<queries", ln):
                add_f(CAT_MANIFEST, WARN, "扩大可见包范围", f"`{ln.strip()[:100]}`")

    # 5) 构建脚本
    if low.endswith((".gradle", ".gradle.kts")) or low.endswith("libs.versions.toml"):
        for ln in add:
            if re.search(r"maven\s*\(\s*[\"']|mavenCentral\(\)|google\(\)|gradlePluginPortal|"
                         r"jitpack|mavenLocal\(\)|flatDir|ivy\s*\{|exclusiveContent", ln):
                add_f(CAT_BUILD, BLOCK, "新增/改动仓库源", f"`{ln.strip()[:100]}`（{path}）")
            if re.search(r"buildscript\s*\{|apply\(plugin|apply plugin", ln):
                add_f(CAT_BUILD, BLOCK, "注入构建脚本/插件", f"`{ln.strip()[:100]}`")
            if re.search(r"resolutionStrategy|dependencySubstitution|eachDependency|force\s*\(|"
                         r"capabilities|componentSelection|substitute\(", ln):
                add_f(CAT_BUILD, BLOCK, "替换/劫持依赖解析", f"`{ln.strip()[:100]}` —— 可把可信坐标换成任意构件")
            if re.search(r"commandLine|providers\.exec|\bexec\s*\{|ProcessBuilder|Runtime\.|"
                         r"System\.exec|gradle\.startParameter", ln):
                add_f(CAT_BUILD, BLOCK, "构建期执行外部命令", f"`{ln.strip()[:100]}` —— 在本机以你的身份跑")
            if re.search(r"signingConfig|storeFile|storePassword|keyAlias|keyPassword|"
                         r"applicationId|namespace", ln):
                add_f(CAT_BUILD, BLOCK, "改动签名/应用标识", f"`{ln.strip()[:100]}`")
            if re.search(r"^\s*(classpath|id\s*\(|id\s+\")", ln) and "version" in ln:
                add_f(CAT_BUILD, BLOCK, "新增构建插件", f"`{ln.strip()[:100]}`")
            if re.search(r"isMinifyEnabled\s*=\s*false|minifyEnabled\s*false|isShrinkResources\s*=\s*false|"
                         r"useLegacyPackaging", ln):
                add_f(CAT_BUILD, WARN, "关闭混淆/收缩", f"`{ln.strip()[:100]}` —— 常用于方便逆向")
            if re.search(r"buildConfigField|resValue|packaging\s*\{|jniLibs|sourceSets\s*\{|assets", ln):
                add_f(CAT_BUILD, WARN, "改动打包内容/源集", f"`{ln.strip()[:100]}`")
            if re.search(r"abortOnError\s*=\s*false", ln):
                add_f(CAT_BUILD, WARN, "放宽 lint", f"`{ln.strip()[:100]}`")
            if re.search(r"systemProperty|environment\s*\(|jvmArgs|org\.gradle\.jvmargs", ln):
                add_f(CAT_BUILD, WARN, "改动构建 JVM 环境", f"`{ln.strip()[:100]}`")

            if re.search(r"(files|fileTree)\s*\(", ln) and re.search(
                    r"(implementation|api|runtimeOnly|compileOnly|debugImplementation)", ln):
                add_f(CAT_BUILD, BLOCK, "非注册源依赖", f"`{ln.strip()[:100]}` —— 本地 jar 无法审计来源")

            dep = re.match(r"^\s*(implementation|api|debugImplementation|releaseImplementation|"
                           r"runtimeOnly|compileOnly|ksp|kapt|annotationProcessor|testImplementation)"
                           r"\s*\(?\s*[\"']([^\"']+)[\"']", ln)
            if dep:
                coord = dep.group(2)
                m = re.match(r"^([A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+)", coord)
                name = m.group(1) if m else coord
                if re.search(r"^(https?://|git\+|s3://|file:)|files\(|fileTree\(", coord):
                    add_f(CAT_BUILD, BLOCK, "非注册源依赖", f"`{coord}` —— URL/本地文件依赖无法审计来源")
                elif name not in allow:
                    near = [c for c in allow if edit_distance(name, c) <= 2 and name != c]
                    if near:
                        add_f(CAT_BUILD, BLOCK, "疑似仿冒依赖", f"`{name}` 近似 `{near[0]}`（拼写/大小写差异）")
                    else:
                        add_f(CAT_BUILD, BLOCK, "新增依赖", f"`{name}` 不在白名单里")
        for ln in drop:
            if re.search(r"mavenCentral|google\(\)|verification-metadata|dependency-verification", ln):
                add_f(CAT_BUILD, BLOCK, "移除安全配置", f"`{ln.strip()[:100]}`（{path}）")

    # 6) 代码能力 / 密钥 / 不可见字符
    if ext in CODE_EXTS or ext == "":
        for ln in add:
            hits = []
            for cat, sev, pat, label in CODE_RULES:
                flags = re.IGNORECASE if cat == CAT_TELEM else 0
                if re.search(pat, ln, flags):
                    hits.append((cat, sev, label))
            seen = set()
            for cat, sev, label in hits:
                if (cat, sev, label) in seen:
                    continue
                seen.add((cat, sev, label))
                add_f(cat, sev, label, f"`{path}` → `{ln.strip()[:120]}`")

            if any(ch in ln for ch in BIDI_CHARS):
                add_f(CAT_OBF, BLOCK, "双向控制字符（Trojan Source）",
                      f"`{path}` —— 行内有 bidi 控制符，显示顺序与编译顺序不一致")
            if sum(ln.count(ch) for ch in INVISIBLE) >= 2:
                add_f(CAT_OBF, BLOCK, "零宽/不可见字符", f"`{path}` → `{ln.strip()[:80]}`")
            if CYRILLIC_GREEK.search(ln) and not CJK.search(ln):
                add_f(CAT_OBF, BLOCK, "同形字符混排",
                      f"`{path}` → `{ln.strip()[:80]}` —— 西里尔/希腊字母冒充拉丁字母")
            if ln.count("\\u") >= 6:
                add_f(CAT_OBF, WARN, "大量 Unicode 转义", f"`{path}` → `{ln.strip()[:80]}`")
            if len(ln) > 3000:
                add_f(CAT_OBF, WARN, "超长单行", f"`{path}` → {len(ln)} 字符")

    # 6.5) 密钥：所有非二进制文件都查，文档里贴 token 同样要拦
    if ext not in BIN_EXTS:
        for ln in add:
            if re.search(SECRET_PAT, ln):
                add_f(CAT_SECRET, BLOCK, "疑似密钥/凭据", f"`{path}` -> `{ln.strip()[:120]}`")

    # 7) proguard
    if low.endswith("proguard-rules.pro"):
        for ln in add:
            if re.search(r"-dontobfuscate|-dontshrink|-dontoptimize", ln):
                add_f(CAT_BUILD, WARN, "削弱混淆", f"`{ln.strip()[:100]}`")
        for ln in drop:
            if re.match(r"^-keep", ln.strip()):
                add_f(CAT_BUILD, WARN, "删除 keep 规则", f"`{ln.strip()[:100]}`")

    # 8) 未知类型 / 截断
    if ext not in BIN_EXTS and ext not in CODE_EXTS and ext not in (".md", ".txt") and ext != "":
        if status == "added":
            add_f(CAT_BIN, WARN, "新增未知类型文件", f"`{path}` —— 确认它不是可执行/资源载荷")
    if not patch and status in ("added", "modified") and ext not in BIN_EXTS:
        add_f(CAT_BIN, WARN, "diff 被 GitHub 截断", f"`{path}` —— 文件过大，需本地看全文")


def audit(meta, files, commit_entries=None):
    allow = coords_allowlist()
    findings = []
    for entry in files:
        audit_file(entry, allow, findings)

    total_add = sum(int(f.get("additions") or 0) for f in files)
    if len(files) > 50:
        findings.append(Finding(CAT_META, WARN, "改动文件偏多", f"{len(files)} 个文件"))
    if total_add > 1500:
        findings.append(Finding(CAT_META, WARN, "改动量偏大", f"新增 {total_add} 行 —— 大改更容易夹带东西"))

    user = meta.get("user") if isinstance(meta.get("user"), dict) else None
    login = (user or {}).get("login")
    age = meta.get("author_age_days")
    if login and isinstance(age, int) and age < 90:
        findings.append(Finding(CAT_META, WARN, "新账号提交", f"@{login} 账号仅 {age} 天"))
    assoc = meta.get("author_association")
    if assoc and assoc not in ("OWNER", "MEMBER", "COLLABORATOR"):
        findings.append(Finding(CAT_META, WARN, "外部贡献者", f"author_association={assoc}"))
    commits = meta.get("commits")
    if isinstance(commits, int) and commits > 5:
        findings.append(Finding(CAT_META, WARN, "提交数偏多",
                                f"{commits} 个提交 —— 注意「先加后撤」，净 diff 干净不代表过程干净"))

    if commit_entries:
        for sha, short, entries in commit_entries:
            local = []
            for e in entries:
                audit_file(e, allow, local)
            for f in local:
                if f.sev == BLOCK:
                    findings.append(Finding(CAT_GRAPH, BLOCK, "单个提交内含阻断项",
                                            f"{short} · {f.title}：{f.detail}"))

    uniq, seen = [], set()
    for f in findings:
        if f.key() not in seen:
            seen.add(f.key())
            uniq.append(f)
    return uniq


def render(meta, files, findings):
    blocks = [f for f in findings if f.sev == BLOCK]
    warns = [f for f in findings if f.sev == WARN]
    num = meta.get("number")
    title = meta.get("title") or ""
    head = f"PR #{num} {title}".strip() if num else title
    out = [f"# PR 审计：{head}", ""]
    if blocks:
        out.append(f"**结论：不要合并** —— {len(blocks)} 项阻断，{len(warns)} 项待确认。")
    elif warns:
        out.append(f"**结论：没有阻断项，{len(warns)} 处需要人工看。**")
    else:
        out.append("**结论：通过。**")
    out.append("")
    out.append(f"共 {len(files)} 个文件改动；只读 diff 文本，不执行 PR 里的代码。")
    kinds = {}
    for f in files:
        p = (f.get("filename") or "").lower()
        ext = Path(p).suffix or "(无后缀)"
        kinds[ext] = kinds.get(ext, 0) + 1
    st = {}
    for f in files:
        st[f.get("status", "modified")] = st.get(f.get("status", "modified"), 0) + 1
    kind_txt = "、".join(f"{k} {v} 个" for k, v in sorted(kinds.items(), key=lambda x: -x[1])[:6])
    st_txt = "、".join(f"{k} {v}" for k, v in st.items())
    out.append(f"改动构成：{st_txt}；文件类型：{kind_txt}。")
    out.append("")
    out.append("")

    def dump(group, marker):
        for cat in CATEGORIES:
            items = [f for f in group if f.cat == cat]
            if not items:
                continue
            out.append(f"### {cat}")
            for f in items:
                out.append(f"{marker}**{f.title}**：{f.detail}" if marker == "- " else f"- **{f.title}**：{f.detail}")
        out.append("")

    if blocks:
        out.append("## 阻断项")
        dump(blocks, "- ")
    if warns:
        out.append("## 待确认")
        dump(warns, "- ")

    out.append("## 覆盖面")
    out.append("")
    out.append("| 分类 | 查什么 |")
    out.append("| --- | --- |")
    out.append("| " + CAT_REPO + " | workflow / CODEOWNERS / wrapper / gradlew / 签名材料 / 校验清单 / submodule / buildSrc / devcontainer / 审计脚本自身 / 其他生态 |")
    out.append("| " + CAT_PATH + " | 路径穿越、文件名命令注入、不可见字符、同形字符 |")
    out.append("| " + CAT_BIN + " | jar/aar/apk/dex/so/exe/zip 等二进制、资源目录新增、二进制伪装成文本、超长 diff |")
    out.append("| " + CAT_MANIFEST + " | 新增权限、组件导出、debuggable、明文流量、sharedUserId、清单节点替换、高权限服务、开机自启 |")
    out.append("| " + CAT_BUILD + " | 仓库源、buildscript、插件、依赖解析劫持、构建期 exec、签名/applicationId、本地 jar、白名单与仿冒依赖 |")
    out.append("| " + CAT_NET + " | 网络 API、HTTP 客户端库、WebView |")
    out.append("| " + CAT_TELEM + " | 统计/崩溃上报 SDK、广告标识、埋点 |")
    out.append("| " + CAT_EXEC + " | dex 动态加载、提权命令、子进程、native 加载、反射 |")
    out.append("| " + CAT_DATA + " | 通讯录/短信、定位/相机/剪贴板/账号、无障碍/设备管理等系统服务 |")
    out.append("| " + CAT_PERSIST + " | 闹钟/Job/WorkManager、独立进程、开机自启 |")
    out.append("| " + CAT_OBF + " | Base64/十六进制大块、bidi 控制符、零宽字符、同形字符、Unicode 转义、超长行 |")
    out.append("| " + CAT_SECRET + " | AWS/gh/GitHub token/私钥/Google API key 等凭据形态 |")
    out.append("| " + CAT_META + " | 作者账号年龄、贡献者身份、提交数、改动量 |")
    out.append("| " + CAT_GRAPH + " | 逐提交复查（抓先加后撤） |")
    return "\n".join(out)


def pr_comments(pr, repo, tok):
    url = f"https://api.github.com/repos/{repo}/issues/{pr}/comments?per_page=100"
    try:
        return api(url, tok)
    except Exception:
        return []


def upsert_comment(pr, repo, tok, body):
    marker = "<!-- pr-audit-report -->"
    full = marker + "\n" + body
    for c in pr_comments(pr, repo, tok):
        if marker in (c.get("body") or ""):
            try:
                return api(f"https://api.github.com/repos/{repo}/issues/{pr}/comments/{c['id']}", tok,
                           method="PATCH", payload={"body": full})
            except Exception:
                pass
    return api(f"https://api.github.com/repos/{repo}/issues/{pr}/comments", tok,
               method="POST", payload={"body": full})


def sync_label(pr, repo, tok, good):
    want = "pr-audit: passed" if good else "pr-audit: blocked"
    other = "pr-audit: blocked" if good else "pr-audit: passed"
    try:
        api(f"https://api.github.com/repos/{repo}/issues/{pr}/labels", tok,
            method="POST", payload={"labels": [want]})
    except Exception:
        pass
    try:
        api(f"https://api.github.com/repos/{repo}/issues/{pr}/labels/{other}", tok, method="DELETE")
    except Exception:
        pass


def token():

    for key in ("GITHUB_TOKEN", "GH_TOKEN"):
        if os.environ.get(key):
            return os.environ[key]
    try:
        out = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, timeout=15)
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout.strip()
    except Exception:
        pass
    return None


def api(url, tok, method="GET", payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "loglens-pr-audit",
        "Content-Type": "application/json",
        **({"Authorization": "Bearer " + tok} if tok else {}),
    })
    with urllib.request.urlopen(req, timeout=40) as r:
        raw = r.read()
        return json.loads(raw) if raw else {}


def account_age_days(login, tok):
    try:
        u = api(f"https://api.github.com/users/{login}", tok)
        created = u.get("created_at")
        if not created:
            return None
        from datetime import datetime, timezone
        dt = datetime.fromisoformat(created.replace("Z", "+00:00"))
        return (datetime.now(timezone.utc) - dt).days
    except Exception:
        return None


def files_from_github(repo, pr, tok):
    meta = api(f"https://api.github.com/repos/{repo}/pulls/{pr}", tok)
    user = meta.get("user") if isinstance(meta.get("user"), dict) else None
    if user and user.get("login"):
        age = account_age_days(user["login"], tok)
        if age is not None:
            meta["author_age_days"] = age
    files, page = [], 1
    while True:
        chunk = api(f"https://api.github.com/repos/{repo}/pulls/{pr}/files?per_page=100&page={page}", tok)
        if not chunk:
            break
        files.extend(chunk)
        if len(chunk) < 100:
            break
        page += 1
    return meta, files


def commits_from_github(repo, pr, tok):
    out = []
    try:
        commits = api(f"https://api.github.com/repos/{repo}/pulls/{pr}/commits?per_page=50", tok)[:20]
    except Exception:
        return out
    for c in commits:
        sha = c["sha"]
        try:
            detail = api(f"https://api.github.com/repos/{repo}/commits/{sha}", tok)
            entries = detail.get("files") or []
            if entries:
                out.append((sha, sha[:8], entries))
        except Exception:
            continue
    return out


def files_from_git(rng):
    parts = rng.split("..")
    if len(parts) != 2:
        print("--range 要写成 BASE..HEAD，例如 origin/main..HEAD", file=sys.stderr)
        sys.exit(2)
    base, head = parts
    names = subprocess.run(["git", "diff", "--name-status", "-M", base, head],
                           capture_output=True, text=True, check=True).stdout.splitlines()
    patch = subprocess.run(["git", "diff", "--unified=3", "-M", base, head],
                           capture_output=True, text=True, check=True).stdout
    status_map = {"A": "added", "M": "modified", "D": "removed", "R": "renamed", "C": "copied"}
    files = []
    for line in names:
        cols = line.split("\t")
        st = status_map.get(cols[0][0], "modified")
        path = cols[-1]
        files.append({"filename": path, "status": st,
                      "patch": extract_patch(patch, path) if st != "removed" else ""})
    return {"title": f"本地范围 {rng}", "number": None}, files


def extract_patch(full, path):
    marker = "diff --git a/" + path
    pos = full.find(marker)
    if pos < 0:
        pos = full.rfind(" b/" + path)
        if pos < 0:
            return ""
    end = full.find("diff --git ", pos + 10)
    return full[pos:end if end > 0 else len(full)]


def main():
    ap = argparse.ArgumentParser(description="LogLens PR 审计")
    ap.add_argument("--pr", type=int, help="GitHub PR 编号")
    ap.add_argument("--range", help="本地范围，例如 origin/main..HEAD")
    ap.add_argument("--repo", default="1764364648/loglens")
    ap.add_argument("--summary-file", help="把 Markdown 报告写到这里")
    ap.add_argument("--commits", action="store_true", help="本地范围时忽略")
    ap.add_argument("--no-commits", action="store_true", help="跳过逐提交复查")
    ap.add_argument("--comment", action="store_true", help="把报告发成/更新成 PR 评论，并打上通过或阻断标签")
    args = ap.parse_args()

    if not args.pr and not args.range:
        print("要么 --pr，要么 --range", file=sys.stderr)
        return 2

    commit_entries = None
    if args.pr:
        tok = token()
        if not tok:
            print("没有 token：先 gh auth login，或设置 GITHUB_TOKEN", file=sys.stderr)
            return 2
        meta, files = files_from_github(args.repo, args.pr, tok)
        if not args.no_commits:
            commit_entries = commits_from_github(args.repo, args.pr, tok)
    else:
        meta, files = files_from_git(args.range)

    findings = audit(meta, files, commit_entries)
    report = render(meta, files, findings)
    print(report)
    if args.summary_file:
        Path(args.summary_file).write_text(report + "\n", encoding="utf-8")

    blocked = any(f.sev == BLOCK for f in findings)
    if args.comment and args.pr:
        tok = token()
        if tok:
            try:
                upsert_comment(args.pr, args.repo, tok, report)
                sync_label(args.pr, args.repo, tok, not blocked)
                print("已在 PR 留言并同步标签")
            except Exception as e:
                print(f"留言失败（不影响审计结论）：{e}", file=sys.stderr)
    return 1 if blocked else 0


if __name__ == "__main__":
    sys.exit(main())

# 缩短审计：只查明显问题
