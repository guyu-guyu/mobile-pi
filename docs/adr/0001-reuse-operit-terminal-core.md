---
status: accepted
---

# 复用 OperitTerminalCore，不 fork 完整 Operit

Mobile Pi 将维护一个固定版本的 `OperitTerminalCore` fork，并自行实现 Android 产品层。TerminalCore 已解决 ARM64 Ubuntu、PRoot、native Shell、PTY、Service 和 DocumentsProvider 等高成本问题；完整 Operit 应用还包含与本项目无关的 Agent 内核、模型层、工具、记忆、自动化、权限和 UI，移除这些内容并持续合并上游的长期成本更高。

该 fork 必须位于 Mobile Pi 自有运行环境接口之后，避免 Operit 类型扩散到整个应用。上游版本只能通过显式兼容性测试后升级，不能自动跟随。发布 fork 时必须制定 LGPLv3 合规方案，提供对应源码和修改、保留版权与许可声明，并审计运行环境内其他独立组件的许可证。

## 考虑过的方案

- fork 完整 Operit：制作视觉原型最快，但长期耦合和替换成本最高。
- 复用 OperitTerminalCore：保留最困难的运行环境能力，同时控制产品层和 Pi 集成层。
- 完全重写运行环境：所有权边界最清晰，但在验证产品之前需要投入大量 native Android 工作。

## 影响

已核对的 TerminalCore 基线设置了 `minSdk = 26` 且只包含 `arm64-v8a`，因此初期产品仅支持 Android 8.0 及以上的 ARM64 设备。fork 中的改动要小而集中，便于持续对比上游。许可证合规是发布门槛，不是发布后的补充工作。
