# Shizuku Logger

借 Shizuku 的 shell 身份（uid 2000）拉起 `logcat`，实时查看**任意指定应用**的日志。无需 root、无需解锁 BL、不需要 Xposed。

- 包名：`com.marvis.shizukulog`
- 适用：Android 8.0+（minSdk 26），targetSdk 36
- UI：Jetpack Compose + Miuix（HyperOS 观感），深色为主

> 个人自用工具，侧载安装，不上架应用商店。

## 它解决什么

系统自带 logcat 和常见日志查看器，要么必须连着电脑，要么只能看本应用。这里的做法是：先用 Shizuku 拿到 shell，再以 `--uid=<uid>`（失败降级 `--pid`）只抓目标进程的日志——崩溃、ANR、binder 报错、私有目录写入，都能在手机上直接看到，并且能落盘、能导出、能发给同事。

## 主要功能

| 模块 | 能力 |
| --- | --- |
| 首页 | Shizuku 六种状态与授权引导、目标应用按 uid 选择、日志落盘开关、崩溃高亮、全量 / 单应用模式、一键开始捕获 |
| 日志页 | 关键字 / 正则搜索、tag 包含与排除、只看崩溃、书签、级别计数、折叠堆栈、折叠刷屏、markText 高亮、紧凑行、小地图、暂停跟随、导出 txt / csv / json / zip、存图、悬浮窗、离线导入、日志通道受限引导、崩溃归集、问题体检 |
| 网络快照 | 目标应用或整机；采样时间轴录制（1.4s 一轮，已消失的连接保留 90s）；存活 / 已建立 / 监听 / SYN_SENT 卡住 / 已消失 五格统计；按应用分组；SYN_SENT 超过 3s 判为连接失败 |
| 工具箱 | 入口页，含深度诊断、取证面板、网络快照 |
| 深度诊断 | 九个分区：应用档案、进程是否生效、137 退出、ANR、events、native crash、binder、私有目录、全量 logcat |
| 取证面板 | 落盘路径与 tag、复制到 Download、四步流水、结论三态、FileProvider 产物 |
| 设置 | 采集行为、日志显示（字号 9–20 / 最大行数 / 小地图 / 常亮）、存储（单文件上限 / 保留份数）、关于 |

## 常驻通知与「灵动岛」

采集期间是一个 `specialUse` 类型的前台服务，通知在 Android 16 起申请 Promoted Ongoing（各家系统里的常驻胶囊 / 灵动胶囊）。

- **开始捕获**：通知上岛，胶囊显示采集时长、已捕获条数、崩溃数
- **暂停**：真的停采集（logcat 断流），通知与胶囊同步变成「已暂停采集」，胶囊收起
- **继续**：按原参数重新拉起采集，胶囊重新上岛
- **停止**：撤通知 + 撤胶囊 + 结束服务，不会留下残影

暂停期间不抓日志，因此这段时间的日志不会补回来——暂停就是“先不看了”。“暂停跟随”是另一回事，它只控制列表要不要自动滚到最新。

## 构建

需要 JDK 17、Android SDK（compileSdk 37 平台）。

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

release 构建默认开 R8。要出可安装的 release 包，在仓库根目录放一个 `keystore.properties`（已在 `.gitignore` 中排除）：

```properties
storeFile=/abs/path/to/release.jks
storePassword=******
keyAlias=release
keyPassword=******
```

放好后再 `./gradlew assembleRelease`，签名配置自动接上；没有这个文件时 release 只产出未签名包。

## 已知限制

- 必须有 Shizuku 在跑（无线调试方式启动），Shizuku 被系统杀掉后采集会断流
- 部分 ROM（荣耀 / 华为）默认关掉 AP 日志，需要在拨号盘输工程码打开，App 内会给出引导
- 不做完整抓包：VpnService + 自签 CA 会撞 pinning，不值得，网络页只做连接快照
- 暂停期间不补日志

## 隐私

所有日志只留在本机：内存环形缓冲，以及 `Android/data/com.marvis.shizukulog/files/` 下的落盘与导出文件。App 不联网，不上传任何数据。

## 截图

`docs/screenshots/` 下有首页、网络快照、工具箱与设置的实机截图。

## 许可

[PolyForm Noncommercial License 1.0.0](LICENSE)——一句话：**任何人都可以免费使用、修改、分发；但不得用于任何商业目的**，包括改名、换图标、换包名之后收费上架、卖源码、挂广告或作为付费服务的一部分。

- 这不是 OSI 认可的开源许可，属于 source-available：GitHub 上会显示 License: Other
- 改了再发布，必须带上同一份许可全文与 `Required Notice` 版权行，不能抹掉出处
- 想商用请先联系作者拿书面授权

## 致谢

Shizuku（RikkaApps）提供 shell 通道；Miuix 提供 HyperOS 风格组件。
