#!/usr/bin/env python3
"""LogLens PR 审计：合并前自动查一遍「这个 PR 有没有偷偷加东西」。

只看 diff 的文本，不执行 PR 里的任何代码。两种用法：

    python tools/pr_audit.py --pr 12                  # 查 GitHub 上的 PR（需要 gh 登录或 GITHUB_TOKEN）
    python tools/pr_audit.py --range origin/main..HEAD  # 查本地还没推的分支

退出码：0 = 没有阻断项；1 = 有阻断项，别合。

阻断项代表「这个项目本来不该出现的东西」：联网能力、新权限、新依赖、新仓库、
动构建脚本与签名、二进制文件、动 wrapper 与校验文件、大段 base64。
其余可疑但可能是误伤的（exec、Class.forName、URL 字面量）只做人工确认提示。
"""
import argparse
import base64
import json
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request
from pathlib import Path

SEV_BLOCK = "阻断"
SEV_WARN = "需人工确认"

# 这个 App 不联网，所以下列能力出现即阻断（不看上下文）
NETWORK_PATTERNS = [
    (r"HttpURLConnection", "JDK 原生 HTTP"),
    (r"\bokhttp3?\b", "OkHttp"),
    (r"java\.net\.(?:Socket|URL|HttpURLConnection|InetAddress|DatagramSocket)", "java.net 网络类"),
    (r"\bWebView\b", "WebView"),
    (r"\bRetrofit\b|\bKtor\b|\bFuel\b", "HTTP 客户端库"),
    (r"libcore\.io\.IoBridge", "底层 socket"),
]
TELEMETRY_PATTERNS = [
    (r"\bFirebase\b|\bCrashlytics\b|\bSentry\b|\bBugly\b|\bUmeng\b|\bAppsflyer\b", "第三方统计/崩溃上报 SDK"),
    (r"Telemetry|telemetry", "自命名上报"),
]
# 这些可能是既有合法用法（本仓库用 exec 拉 logcat），只提示
RISKY_PATTERNS = [
    (r"Runtime\.getRuntime\(\)\.exec|\bProcessBuilder\b", "起子进程"),
    (r"\bsu\s+-c\b|/system/bin/su\b|/system/xbin/su\b", "调 su"),
    (r"DexClassLoader|PathClassLoader|InMemoryDexClassLoader|System\.loadLibrary", "动态加载代码"),
    (r"Class\.forName", "反射加载类"),
    (r"Base64\.decode|Base64\.Default\.decode", "Base64 解码"),
    (r"https?://[^\s\"')]+", "硬编码 URL"),
]

BIN_EXTS = {
    ".jar", ".aar", ".apk", ".aab", ".dex", ".so", ".dll", ".exe", ".bin",
    ".class", ".jks", ".keystore", ".p12", ".pfx", ".zip", ".7z", ".tar",
    ".gz", ".wasm", ".dylib", ".msi", ".scr", ".pyc",
}

# 仓库现在声明的权限，新增任意一个都要阻断
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

DEP_LINE = re.compile(
    r"^\s*(implementation|api|debugImplementation|releaseImplementation|runtimeOnly|"
    r"compileOnly|ksp|kapt|annotationProcessor|testImplementation)\s*\(?\s*[\"']([^\"']+)[\"']"
)
COORD = re.compile(r"^([A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+)")


def die(msg, code=2):
    print(msg, file=sys.stderr)
    sys.exit(code)


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


def api(url, tok):
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "loglens-pr-audit",
        **({"Authorization": "Bearer " + tok} if tok else {}),
    })
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def files_from_github(repo, pr, tok):
    meta = api(f"https://api.github.com/repos/{repo}/pulls/{pr}", tok)
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


def files_from_git(rng):
    parts = rng.split("..")
    if len(parts) != 2:
        die("--range 要写成 BASE..HEAD，例如 origin/main..HEAD")
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
        body = extract_patch(patch, path) if st != "removed" else ""
        files.append({"filename": path, "status": st, "patch": body})
    meta = {"title": f"本地范围 {rng}", "number": None}
    return meta, files


def extract_patch(full, path):
    marker = "diff --git a/" + path
    pos = full.find(marker)
    if pos < 0:
        # 被重命名时路径可能出现在 b/ 侧
        marker = " b/" + path
        pos = full.rfind(marker)
        if pos < 0:
            return ""
    end = full.find("diff --git ", pos + 10)
    return full[pos:end if end > 0 else len(full)]


def added_lines(patch):
    out = []
    for raw in (patch or "").splitlines():
        if raw.startswith("+++") or raw.startswith("---"):
            continue
        if raw.startswith("+"):
            out.append(raw[1:])
    return out


def load_allowlist(root="."):
    path = Path(root) / "security" / "dependency-allowlist.txt"
    if not path.exists():
        return set()
    lines = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        lines.append(COORD.match(line).group(1) if COORD.match(line) else line)
    return set(lines)


def audit(meta, files):
    findings = []          # (sev, title, detail)
    allow = load_allowlist()
    touched = set()
    total_add = 0

    def add(sev, title, detail=""):
        findings.append((sev, title, detail))

    for f in files:
        path = f.get("filename") or ""
        status = f.get("status", "modified")
        patch = f.get("patch") or ""
        touched.add(path)
        total_add += int(f.get("additions") or 0)
        low = path.lower()
        ext = Path(low).suffix
        lines = added_lines(patch)

        # 1. 路径与二进制
        if low.startswith(".github/workflows/"):
            add(SEV_BLOCK, "改动了 CI 工作流", f"`{path}` —— 工作流只在维护者手工审阅后才会被动；PR 里改它一律先停")
        if low.startswith("gradle/wrapper/") or low.endswith("gradlew") or low.endswith("gradlew.bat"):
            add(SEV_BLOCK, "改动了 Gradle Wrapper", f"`{path}` —— wrapper 决定构建器本身，必须由维护者独立升级")
        if low in ("keystore.properties", "local.properties") or low.startswith("keystore/"):
            add(SEV_BLOCK, "碰到签名材料或本机配置", f"`{path}`")
        if low.endswith(".gradle/verification-metadata.xml") or low.endswith("verification-metadata.xml"):
            add(SEV_BLOCK, "改动了依赖校验清单", f"`{path}` —— 新增依赖必须由维护者本地重新生成并核对，不能在 PR 里顺手改")
        if ext in BIN_EXTS:
            add(SEV_BLOCK, "提交了二进制/压缩包", f"`{path}` —— 这类文件无法在 diff 里审查")

        # 2. 权限
        if low.endswith("androidmanifest.xml"):
            for ln in lines:
                for perm in re.findall(r"android\.permission\.([A-Z_0-9]+)", ln):
                    full_perm = "android.permission." + perm
                    if full_perm not in ALLOWED_PERMISSIONS:
                        add(SEV_BLOCK, "新增敏感权限", f"`{path}` 新增 `{full_perm}`")

        # 3. 依赖与仓库
        if low.endswith((".gradle", ".gradle.kts", "libs.versions.toml")):
            for ln in lines:
                dep = DEP_LINE.match(ln)
                if dep:
                    coords = COORD.match(dep.group(2))
                    coord = coords.group(1) if coords else dep.group(2)
                    if coord not in allow:
                        add(SEV_BLOCK, "新增依赖", f"`{coord}`（{path}）—— 不在 security/dependency-allowlist.txt 里")
                if re.search(r"^\s*(classpath|id)\s*[(\"]", ln) or "version \"" in ln:
                    add(SEV_BLOCK, "新增构建插件", f"`{ln.strip()[:90]}`")
                if re.search(r"maven\s*\(\s*[\"']|mavenCentral\(\)|google\(\)|jitpack", ln):
                    add(SEV_BLOCK, "改动仓库源", f"`{ln.strip()[:90]}`（{path}）")
                if "repositoriesMode" in ln:
                    add(SEV_BLOCK, "改动仓库策略", f"`{ln.strip()[:90]}`")

        # 4. 签名与应用标识
        if low.endswith("build.gradle.kts") and low.startswith("app/"):
            for ln in lines:
                if re.search(r"applicationId|namespace|signingConfig|storeFile|storePassword|keyAlias|keyPassword|versionCode|versionName", ln):
                    add(SEV_WARN, "改动应用标识/签名/版本字段", f"`{ln.strip()[:100]}`")

        # 5. 代码能力
        if low.endswith((".kt", ".java", ".kts", ".xml")) or ext == "":
            for ln in lines:
                for pat, label in NETWORK_PATTERNS:
                    if re.search(pat, ln):
                        add(SEV_BLOCK, "引入网络能力", f"{label}：`{path}` → `{ln.strip()[:110]}`")
                for pat, label in TELEMETRY_PATTERNS:
                    if re.search(pat, ln):
                        add(SEV_BLOCK, "引入上报/统计", f"{label}：`{path}` → `{ln.strip()[:110]}`")
                if low.endswith((".kt", ".java")):
                    for pat, label in RISKY_PATTERNS:
                        if re.search(pat, ln):
                            add(SEV_WARN, "需要看一眼的能力", f"{label}：`{path}` → `{ln.strip()[:110]}`")
                if re.search(r"[A-Za-z0-9+/]{200,}={0,2}", ln):
                    add(SEV_BLOCK, "超长 Base64 块", f"`{path}` —— 疑似塞了编码过的载荷")
                if ln.count("\\u") >= 8:
                    add(SEV_WARN, "大量 Unicode 转义", f"`{path}` —— 常见于隐藏字符串")

        # 6. 规则与忽略项
        if low in (".gitignore", ".gitattributes", ".gitmodules"):
            add(SEV_WARN, "改动仓库忽略/属性配置", f"`{path}`")
        if low.endswith("proguard-rules.pro"):
            for ln in lines:
                if ln.strip().startswith("-keep") or ln.strip().startswith("-dontwarn"):
                    add(SEV_WARN, "改动混淆规则", f"`{ln.strip()[:100]}`")

        if not patch and status == "modified" and ext not in BIN_EXTS:
            add(SEV_WARN, "diff 被 GitHub 截断", f"`{path}` —— 文件太大，审计看不到全文，需本地看")

    if total_add > 1200:
        add(SEV_WARN, "改动量偏大", f"新增 {total_add} 行 —— 大改更容易夹带东西，建议要求拆小")
    if len(files) > 40:
        add(SEV_WARN, "改动文件偏多", f"{len(files)} 个文件")

    # 去重
    uniq = []
    for item in findings:
        if item not in uniq:
            uniq.append(item)
    return uniq, touched


def render(meta, files, findings):
    blocks = [f for f in findings if f[0] == SEV_BLOCK]
    warns = [f for f in findings if f[0] == SEV_WARN]
    title = meta.get("title") or ""
    num = meta.get("number")
    head = f"PR #{num} {title}".strip() if num else title

    out = []
    out.append(f"# PR 审计：{head}")
    out.append("")
    if blocks:
        out.append(f"**结论：不要合并** —— {len(blocks)} 项阻断，{len(warns)} 项待确认。")
    elif warns:
        out.append(f"**结论：没有阻断项，但有 {len(warns)} 处要人工看。**")
    else:
        out.append("**结论：通过。** 没有联网、新权限、新依赖、二进制或构建脚本改动。")
    out.append("")
    out.append(f"共 {len(files)} 个文件改动。审计只读 diff 文本，不执行 PR 里的代码。")
    out.append("")

    if blocks:
        out.append("## 阻断项")
        for sev, t, d in blocks:
            out.append(f"- **{t}**：{d}")
        out.append("")
    if warns:
        out.append("## 待确认")
        for sev, t, d in warns:
            out.append(f"- {t}：{d}")
        out.append("")
    out.append("## 查过的项目")
    out.append("")
    out.append("- 路径白名单：工作流 / wrapper / 签名材料 / 校验清单")
    out.append("- 二进制与压缩包扩展名")
    out.append("- `AndroidManifest.xml` 新增权限")
    out.append("- 依赖坐标、构建插件、仓库源")
    out.append("- 网络能力与第三方上报 SDK")
    out.append("- Base64 大块与 Unicode 转义")
    out.append("- 混淆规则与忽略配置")
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser(description="LogLens PR 审计")
    ap.add_argument("--pr", type=int, help="GitHub PR 编号")
    ap.add_argument("--range", help="本地范围，例如 origin/main..HEAD")
    ap.add_argument("--repo", default="1764364648/loglens")
    ap.add_argument("--summary-file", help="把 Markdown 报告写到这里")
    args = ap.parse_args()

    if not args.pr and not args.range:
        die("要么 --pr，要么 --range")

    if args.pr:
        tok = token()
        if not tok:
            die("没有 token：先 gh auth login，或设置 GITHUB_TOKEN")
        meta, files = files_from_github(args.repo, args.pr, tok)
    else:
        meta, files = files_from_git(args.range)

    findings, _ = audit(meta, files)
    report = render(meta, files, findings)
    print(report)
    if args.summary_file:
        Path(args.summary_file).write_text(report + "\n", encoding="utf-8")
    return 1 if any(f[0] == SEV_BLOCK for f in findings) else 0


if __name__ == "__main__":
    sys.exit(main())
