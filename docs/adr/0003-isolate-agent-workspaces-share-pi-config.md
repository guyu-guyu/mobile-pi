---
status: accepted
---

# 隔离 Agent 工作区并共享 Pi 全局配置

Mobile Pi 的每个活动会话使用独立 Agent 进程，将该会话选择的宿主工作区映射为统一的 guest `/workspace`；所有 Agent 同时把同一个宿主 Pi 目录映射为 `/mobile-pi/pi`，共享用户级设置与资源。相比为每个会话发明不同的 guest 路径，这能保持 Pi 的工作目录契约稳定，同时由独立 PRoot 映射提供工作区隔离。

## 影响

工作区切换需要在 Agent 空闲时停止并重启对应进程，不能依赖进程内 `cd`。会话历史和运行态按 `SessionId` 隔离；package 安装、升级和全局配置迁移需要应用级独占锁，并与所有 Agent 运行互斥。并发 Agent 数量必须受设备资源策略限制。rootfs 中的 `/workspace` 与 `/mobile-pi/pi` 实体目录只是持续使用的 guest 挂载目标，不是工作数据或可清理的历史遗留目录；必需挂载失败时进程必须拒绝启动。

未绑定诊断终端不属于 Agent 进程或会话，是上述共享映射的明确例外：它使用 TerminalCore 基础运行环境，不附加工作区或 Pi 共享目录挂载。TerminalCore 的运行配置需要支持按 terminal session 隔离，防止未绑定终端继承会话配置。
