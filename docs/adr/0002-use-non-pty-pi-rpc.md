---
status: accepted
---

# Pi RPC 使用非 PTY 进程

Mobile Pi 将通过独立 stdin、stdout、stderr 管道启动 `pi --mode rpc`，不使用 TerminalCore 的交互式 PTY。Pi RPC 采用严格的 LF 分隔 JSONL；终端回显、ANSI 控制序列、登录标记和 CRLF 转换都可能污染协议，使错误变得不可预测。

交互式 PTY 只保留为后续诊断功能。Mobile Pi 的运行环境 fork 需要提供 raw process launcher：进入同一个内嵌运行环境但不分配终端，单独采集 stderr，支持先发送 RPC `abort` 再终止整个进程树，并将进程退出与 RPC 事件分别上报。

## 影响

Android 集成层必须实现增量 UTF-8、严格 LF 的 JSONL 解码器和请求关联，不能复用通用终端行读取器。标准 Pi extension 对话框可以通过 RPC extension UI 子协议接入，但 `ctx.ui.custom()` 等仅适用于 TUI 的能力不会因为内嵌 Linux 环境而自动兼容。
