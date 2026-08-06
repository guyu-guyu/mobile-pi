# Mobile Pi

[![CI](https://github.com/guyu-guyu/mobile-pi/actions/workflows/ci.yml/badge.svg)](https://github.com/guyu-guyu/mobile-pi/actions/workflows/ci.yml)
[![Release](https://github.com/guyu-guyu/mobile-pi/actions/workflows/release.yml/badge.svg)](https://github.com/guyu-guyu/mobile-pi/actions/workflows/release.yml)

Mobile Pi 是一款无需安装 Termux、在 Android 应用内部运行 Pi coding agent 的应用。项目当前处于 `0.2.0` 单工作区 MVP 内部验证阶段。

## 开发状态

当前仓库包含：

- Android/Compose application module；
- 固定在已核对提交的 TerminalCore submodule；
- `runtime/pi` 安装、健康检查、非 PTY PRoot launcher 和严格 JSONL RPC client；
- SAF 持久目录授权、托管工作区导入、三方差异预览、确认写回和冲突阻止；
- 按工作区隔离的 Pi Session 保存、恢复、新建会话、token/cost 状态；
- Android Keystore AES-GCM 加密的手动 provider profile；
- 承载 Agent 的 `dataSync` foreground service、停止通知和 Activity 重连；
- 流式对话、基础 Markdown/代码块、工具结果、`proof.txt` 验证和诊断界面；
- abort、停止、异常退出后的可解释恢复和进程树清理；
- Debug 构建中的交互式 Ubuntu PTY 终端，供设备侧诊断；
- JSONL、请求关联、RPC/Session、同步规划与执行、Agent 状态机、进程参数和健康检查 JVM 测试。

`0.2.0` 功能代码和 Debug APK 已可构建。路线图退出仍需在两台 ARM64 真机、两个 Android API 大版本上完成 SAF 重启授权、1000 文件真实 provider、Session 重启恢复、Keystore 泄漏检查和 10 分钟前台执行验收。详见 [0.2 开发进度与设备验收](docs/PHASE_0.2_PROGRESS.md)。

## 本地构建

要求 Android SDK 36、JDK 17+、Android NDK 和 CMake 3.22.1。首次构建时，Android Gradle Plugin 可以在已接受 SDK license 的环境中安装缺失的 NDK/CMake。

```powershell
git submodule update --init --recursive
./gradlew.bat :feature:workspaces:testDebugUnitTest :runtime:pi:testDebugUnitTest :runtime:terminal-core:testDebugUnitTest :app:lintDebug :runtime:pi:lintDebug :feature:workspaces:lintDebug :app:assembleDebug
```

Debug APK 输出至 `app/build/outputs/apk/debug/app-debug.apk`。

## CI/CD 与发布

仓库提交和 Pull Request 会自动运行 JVM 测试并构建 Debug APK。推送
`vMAJOR.MINOR.PATCH` 格式的 tag 后，GitHub Actions 会构建正式签名 APK、
校验签名和 SHA-256，并创建 GitHub Release。首次发布前必须配置签名 Secrets，
详见 [CI/CD 与版本发布](docs/RELEASING.md)。

## 技术方向

- fork 并固定 [OperitTerminalCore](https://github.com/AAswordman/OperitTerminalCore)，作为内嵌 Ubuntu/PRoot 运行环境。
- 自行实现轻量 Android 应用和 Pi 集成层，不以完整 Operit 应用作为长期产品基线。
- 通过独立的非 PTY 进程运行 `pi --mode rpc`，严格分离 stdin、stdout 和 stderr。
- 通过 SAF 访问用户选择的目录；Pi 只直接处理应用私有托管副本，写回必须经过差异预览和确认。

## 运行目录

应用私有文件根目录通常为 `/data/user/0/dev.mobilepi/files`，也可通过等价路径 `/data/data/dev.mobilepi/files` 访问。PRoot 为当前 Agent 提供以下目录映射：

| 用途 | Android 宿主路径（相对应用文件根目录） | Ubuntu/PRoot 路径 |
|---|---|---|
| Ubuntu rootfs | `usr/var/lib/proot-distro/installed-rootfs/ubuntu` | `/` |
| 当前托管工作区 | `workspaces/<workspace-id>/files` | `/workspace` |
| 当前 Session 目录 | `sessions/<workspace-id>` | `/mobile-pi/sessions` |
| Pi 共享目录 | `pi` | `/mobile-pi/pi` |
| Pi 全局配置 | `pi/config` | `/mobile-pi/pi/config` |
| 共享临时目录 | `tmp` | `/tmp` |

用户选择的 SAF 目录没有可供 Pi 使用的 POSIX 路径。应用先把普通文件导入托管工作区；同步时按持久基线比较托管副本与 SAF 目录，冲突不会静默覆盖。Debug 诊断终端继续使用受限的 PoC 挂载，不继承当前 Agent 的动态工作区。

## 文档索引

- [领域术语](CONTEXT.md)
- [完整技术设计](docs/TECHNICAL_DESIGN.md)
- [版本路线图](docs/ROADMAP.md)
- [CI/CD 与版本发布](docs/RELEASING.md)
- [0.2 开发进度与设备验收](docs/PHASE_0.2_PROGRESS.md)
- [ADR 0001：复用 OperitTerminalCore](docs/adr/0001-reuse-operit-terminal-core.md)
- [ADR 0002：Pi RPC 使用非 PTY 进程](docs/adr/0002-use-non-pty-pi-rpc.md)
- [ADR 0003：隔离 Agent 工作区并共享 Pi 全局配置](docs/adr/0003-isolate-agent-workspaces-share-pi-config.md)

## 0.2 单工作区 MVP

`0.2.0` 面向单个活动工作区和单个活动 Session。它不启用用户 packages/extensions，不提供直接工作区或全文件权限，也不支持多工作区并行。只有设备矩阵中的全部退出条件有实测记录后，版本才可标记完成。

## 已核对基线

本设计于 2026-08-03 基于以下版本完成核对：

- Operit：`17df7f9e9586e1f4d9e2a82aa56c78aeffd4ca96`
- OperitTerminalCore：`f85be57944b806de4d863dee8b10d80d04daa236`
- `@earendil-works/pi-coding-agent`：`0.81.1`，要求 Node.js `>=22.19.0`

这些版本是设计验证基线，不是浮动依赖。正式实现必须固定精确版本，并通过兼容性测试后才能升级。
