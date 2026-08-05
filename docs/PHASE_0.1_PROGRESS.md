# 0.1.0 开发进度

> 更新日期：2026-08-03
>
> 状态：0.1.0 功能代码完成，ARM64 端到端验收待完成

## 当前结论

0.1.0 要求的 runtime 安装、非 PTY raw process、严格 JSONL RPC、真实对话 UI、流式文本、工具状态、`proof.txt` 独立验证、abort、停止和重启均已接入。用户已确认 runtime 可以正确安装；当前连接的 ADB 环境是 API 28 x86_64 仿真设备，而 APK/runtime 仅支持 ARM64，因此不能在该设备上完成 raw process 和真实 provider 的阶段退出验收。

## 实施任务

| 顺序 | 任务 | 状态 | 当前证据 |
|---|---|---|---|
| 1 | 创建最小 Android 工程并接入固定 TerminalCore | 已完成工程侧实现 | `:app:assembleDebug` 通过，submodule 固定 `f85be57...` |
| 2 | 初始化 rootfs | 已完成实现，用户已验证安装 | 安装状态机调用 TerminalCore 幂等初始化和 rootfs 安装 |
| 3 | 安装固定 Node/Pi | 已完成实现，用户已验证安装 | NodeSource 24、Pi `0.81.1` 安装命令、健康检查及 manifest 已接入 |
| 4 | 建立 raw process | 已完成实现，待 ARM64 验收 | `ProcessBuilder` 直接启动 PRoot，stdin/stdout/stderr 分离，`--kill-on-exit` 清理进程树 |
| 5 | 实现 JSONL decoder | 已完成 JVM 实现 | 任意拆包、UTF-8、LF/CRLF、超限、无效帧测试通过 |
| 6 | 启动 Pi RPC | 已完成实现，待 ARM64 验收 | `get_state` 请求关联、超时、协议失败和 `agent_end` 生命周期已接入 |
| 7 | 接入真实 provider | 已完成实现，待有效凭据验收 | provider/model 参数和 provider API key 环境变量映射已接入，密钥不进入参数或日志 |
| 8 | 验证 Pi 文件工具 | 已完成实现，待 ARM64 验收 | 随机 nonce、Pi `write` 指令和 Android 独立读取精确比对已接入 |
| 9 | 验证取消和重启 | 已完成实现，待 ARM64 验收 | abort 等待收敛、超时强制停止、stop 清理及 crashed restart 已接入 |
| 10 | 形成可行性报告 | 部分完成 | 工程证据和 x86 失败边界已记录，仍需 ARM64 指标及三次连续运行记录 |

## 已固定基线

- Android Gradle Plugin `8.13.2`
- Kotlin `2.2.21`
- Gradle `8.13`
- `compileSdk/targetSdk 36`、`minSdk 26`
- TerminalCore `f85be57944b806de4d863dee8b10d80d04daa236`
- Ubuntu Noble ARM64 rootfs `pd-v4.18.0`
- Node.js `24.x`
- Pi `0.81.1`
- 唯一 ABI `arm64-v8a`

## 当前验证结果

- JVM 单元测试：25 个通过。
- Debug APK：构建通过。
- APK 大小：103,138,974 bytes（当前 Debug 构建）。
- APK ABI：只包含 `arm64-v8a`。
- Manifest：没有共享存储权限，没有 Ubuntu DocumentsProvider；TerminalService 为 `exported=false`。
- API key：仅保留在 ViewModel 内存状态，不写入 manifest、runtime 或日志。
- Android lint：0 errors；22 warnings，均已分类为固定版本提示、目标 ABI 限定、Debug-only 条件或下述 TerminalCore fork 待办。
- UI：Start、Stop、Send、Abort 和 Verify file tool 已按 runtime/Agent 状态启用；流式消息、工具状态和 proof 结果可见。
- Debug UI：runtime READY 后可进入交互式 Ubuntu PTY 终端；支持 ANSI、输入、Ctrl+C、虚拟键盘和 TerminalCore 多会话；固定使用与 Agent 相同的 PRoot 模式并进入共享 `/workspace`，同时共享 `/mobile-pi/pi/config`；必需挂载失败时终端会报错退出，不会进入 rootfs 内的另一个目录。
- RPC：严格使用 LF JSONL，stdout 非协议内容立即失败；stderr 单独脱敏后进入 diagnostics；官方完成事件按 `agent_end` 解析。
- 当前 ADB：API 28、x86_64。TerminalCore ARM64 PTY 经 native bridge 启动后发生 `EIO`，未进入 rootfs 安装；该设备不属于 0.1.0 目标验收架构。

## 已知限制

- submodule 当前暂指已核对的上游仓库；项目自有 fork 地址尚未提供，正式分发前必须迁移到项目控制的 fork。
- TerminalCore 的隐藏命令仍基于 PTY，只用于安装和健康检查；Pi RPC 使用独立非 PTY launcher。
- 用户已确认 runtime 安装成功，但尚无本轮 ARM64 设备日志，不能声称 raw RPC、provider 调用或进程树清理已通过阶段验收。
- provider API key 默认不持久化，应用/Activity 重启后需要重新输入，这是 0.1.0 的明确范围。
- 尚未记录安装时间、冷/温启动、磁盘占用和内存峰值。
- TerminalCore 的 `libpty.so` 尚未满足 16 KB 页对齐检查，进入 Beta 前必须在 fork 中重建或替换。
- TerminalCore 当前传递引入未使用的 SSH/FTP 依赖，lint 会报告 Apache Mina 信任管理器风险；fork 裁剪远程终端代码时必须移除这些依赖并重新检查。

## 下一步

1. 在已安装 runtime 的 ARM64 真机覆盖安装当前 Debug APK，确认健康检查回到 READY。
2. 使用有效 provider/model/API key 启动 Agent，验证 `get_state` 和真实流式回复。
3. 执行 Verify file tool，确认 `proof.txt` nonce 精确匹配。
4. 在生成中 Abort 后再次发送 prompt，再执行 Stop，检查 PRoot/Node/Pi 无残留。
5. 同一 runtime 连续完成三次 Start、prompt、Stop，并记录冷/温启动、内存、磁盘和 stderr。
