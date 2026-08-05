# Mobile Pi

[![CI](https://github.com/guyu-guyu/mobile-pi/actions/workflows/ci.yml/badge.svg)](https://github.com/guyu-guyu/mobile-pi/actions/workflows/ci.yml)
[![Release](https://github.com/guyu-guyu/mobile-pi/actions/workflows/release.yml/badge.svg)](https://github.com/guyu-guyu/mobile-pi/actions/workflows/release.yml)

Mobile Pi 是一款无需安装 Termux、在 Android 应用内部运行 Pi coding agent 的应用。项目当前处于架构设计与可行性验证阶段。

## 开发状态

第一阶段开发已经启动。当前仓库包含：

- Android/Compose application module；
- 固定在已核对提交的 TerminalCore submodule；
- `runtime/pi` 安装、健康检查、非 PTY PRoot launcher 和严格 JSONL RPC client；
- 第一阶段真实对话、流式文本、工具状态、`proof.txt` 验证和诊断单屏界面；
- abort、停止、崩溃后重启和进程树清理；
- Debug 构建中的交互式 Ubuntu PTY 终端，供设备侧诊断；
- JSONL、请求关联、RPC 事件、Agent 状态机、进程参数和健康检查 JVM 测试。

0.1.0 功能代码已经完成，Debug APK 已可构建；阶段退出仍需在 ARM64 真机使用有效 provider 凭据完成真实模型、文件工具、abort 和三次连续重启验收。详见 [第一阶段开发进度](docs/PHASE_0.1_PROGRESS.md)。

## 本地构建

要求 Android SDK 36、JDK 17+、Android NDK 和 CMake 3.22.1。首次构建时，Android Gradle Plugin 可以在已接受 SDK license 的环境中安装缺失的 NDK/CMake。

```powershell
git submodule update --init --recursive
./gradlew.bat :runtime:pi:testDebugUnitTest :app:assembleDebug
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
- 可行性版本只使用应用私有工作区；手机文件访问在后续版本中按明确的存储策略实现。

## 运行目录

应用私有文件根目录通常为 `/data/user/0/dev.mobilepi/files`，也可通过等价路径 `/data/data/dev.mobilepi/files` 访问。PRoot 为 Pi 与诊断终端提供以下目录映射：

| 用途 | Android 宿主路径（相对应用文件根目录） | Ubuntu/PRoot 路径 |
|---|---|---|
| Ubuntu rootfs | `usr/var/lib/proot-distro/installed-rootfs/ubuntu` | `/` |
| 当前 PoC 工作区 | `workspaces/poc/files` | `/workspace` |
| Pi 共享目录 | `pi` | `/mobile-pi/pi` |
| Pi 全局配置 | `pi/config` | `/mobile-pi/pi/config` |
| 共享临时目录 | `tmp` | `/tmp` |

终端执行 `cd /` 后看到的是组合后的 Ubuntu guest 根目录，不是 Android 应用文件根目录。rootfs 中的 `/workspace` 与 `/mobile-pi/pi` 是仍在使用的挂载目标，不是可删除的历史遗留目录；运行时内容分别来自宿主工作区与 Pi 共享目录。详细生命周期和未来多会话映射见 [完整技术设计](docs/TECHNICAL_DESIGN.md#63-目录布局与挂载契约)。

## 文档索引

- [领域术语](CONTEXT.md)
- [完整技术设计](docs/TECHNICAL_DESIGN.md)
- [版本路线图](docs/ROADMAP.md)
- [CI/CD 与版本发布](docs/RELEASING.md)
- [ADR 0001：复用 OperitTerminalCore](docs/adr/0001-reuse-operit-terminal-core.md)
- [ADR 0002：Pi RPC 使用非 PTY 进程](docs/adr/0002-use-non-pty-pi-rpc.md)
- [ADR 0003：隔离 Agent 工作区并共享 Pi 全局配置](docs/adr/0003-isolate-agent-workspaces-share-pi-config.md)

## 第一阶段

`0.1.0` 是可行性验证版本，不是 MVP。只有当一台 ARM64 Android 真机能够完成以下闭环时，第一阶段才算通过：安装内嵌运行环境、启动 Pi RPC、流式显示一次真实模型回复、让 Pi 在应用私有工作区创建文件，并在重启 Agent 进程后继续得到干净的 RPC 消息。

第一阶段明确不实现公共存储访问、会话持久化、包管理、扩展 UI、后台常驻、多工作区和生产级更新。

## 已核对基线

本设计于 2026-08-03 基于以下版本完成核对：

- Operit：`17df7f9e9586e1f4d9e2a82aa56c78aeffd4ca96`
- OperitTerminalCore：`f85be57944b806de4d863dee8b10d80d04daa236`
- `@earendil-works/pi-coding-agent`：`0.81.1`，要求 Node.js `>=22.19.0`

这些版本是设计验证基线，不是浮动依赖。正式实现必须固定精确版本，并通过兼容性测试后才能升级。
