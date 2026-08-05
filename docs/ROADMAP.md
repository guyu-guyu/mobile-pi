# Mobile Pi 版本路线图

## 1. 路线图原则

版本按“风险被消除的顺序”推进，而不是按功能数量推进。每个版本只有满足退出条件后才能进入下一阶段；未完成的目标不能仅通过改版本号后移并宣布当前版本完成。

优先级顺序：

1. 证明 Android 内嵌 Linux 能稳定运行 Pi RPC。
2. 证明用户可以在受控文件范围内完成真实工作。
3. 接入 Pi packages 和 extensions 生态。
4. 补齐移动端工作流与高级能力。
5. 完成可靠性、安全、许可证和发行工程。

## 2. 版本总览

| 版本 | 定位 | 核心结论 |
|---|---|---|
| `0.1.0` | 可行性验证 | 这条运行链路是否真的可用 |
| `0.2.0` | 单工作区 MVP | 用户能否安全完成一次真实文件任务 |
| `0.3.0` | Pi 生态兼容 | packages、resources 和标准 extension UI 是否可用 |
| `0.4.0` | 移动端工作台 | 多工作区、文件和会话工作流是否顺畅 |
| `0.5.0` | 公开 Beta | 是否达到可分发、可升级、可诊断的质量 |
| `1.0.0` | 稳定版 | 核心契约和兼容性承诺是否稳定 |

版本号不代表固定日期。任何时间计划都应在完成前一版本实测后单独制定。

## 3. `0.1.0`：可行性验证

### 3.1 唯一目标

在一台 ARM64 Android 真机上跑通并重复验证以下闭环：

```text
安装 APK
  -> 解压 Ubuntu rootfs
  -> 安装 Node.js 24 和固定 Pi
  -> 非 PTY 启动 pi --mode rpc
  -> 调用真实模型
  -> Pi 使用工具创建文件
  -> Android 独立读取并验证文件
  -> 中止、停止并重启 Agent
```

这个版本回答“方案是否成立”，不回答“产品是否完整”。

### 3.2 最小功能范围

#### Android 工程

- 一个 application module。
- 一个 TerminalCore fork/submodule。
- 一个 Pi runtime/RPC module。
- 一个 Compose Activity、一个主界面，不建立完整导航体系。
- 最低 Android 8.0，仅构建 `arm64-v8a`。

#### 运行环境

- APK 内置 TerminalCore 的 Ubuntu Noble ARM64 rootfs。
- 首次运行解压至应用私有目录。
- 联网安装 Node.js 24、Git、CA certificates 和固定 Pi `0.81.1`。
- 展示当前安装步骤、成功或失败及脱敏日志。
- 提供“重试”和开发用途的“清除后重装”。

#### Pi 进程

- 使用非 PTY raw process，stdin/stdout/stderr 完全分离。
- 启动参数禁用会话、extensions、skills、prompt templates、themes 和 context files。
- 工作目录固定为应用私有 `workspaces/poc/files`。
- 只运行一个 Agent 进程。
- 支持启动、RPC abort、停止和重启。

#### RPC

- 实现严格 LF JSONL decoder。
- 实现请求 ID 关联和基础超时。
- 命令只实现 `get_state`、`prompt`、`abort`。
- 事件只处理文本流、工具开始/结束、Agent settled 和错误。
- stderr 只进入诊断区域，不渲染为聊天内容。

#### UI

- 运行环境状态与安装按钮。
- provider、model、API key 三个开发输入项。
- API key 仅保存在内存，应用退出后重新输入。
- 简单对话列表、输入框、发送和中止。
- 工具调用只显示名称、运行中/成功/失败，不做高级结果渲染。

### 3.3 明确不做

以下内容即使实现很简单，也不进入 `0.1.0`：

- SAF、共享存储、`MANAGE_EXTERNAL_STORAGE` 或文件选择器。
- 会话保存、恢复、历史列表和数据库。
- package install、extension、skill、prompt 和 theme。
- extension UI request/response。
- Markdown 完整渲染、代码高亮和图片消息。
- 模型列表、OAuth、多个 provider profile。
- foreground service、通知和后台任务。
- Terminal UI、SSH、FTP、SSHD。
- 多窗口、多会话、多工作区。
- 自动更新、离线 runtime bundle 和应用商店发布。
- 完整视觉设计、国际化、无障碍专项优化。

### 3.4 实施任务

| 顺序 | 任务 | 完成证据 |
|---|---|---|
| 1 | 创建最小 Android 工程并接入固定 TerminalCore fork | Debug APK 可安装 |
| 2 | 初始化 rootfs | Ubuntu 命令退出码为 0 |
| 3 | 安装固定 Node/Pi | `node --version`、`pi --version` 通过 |
| 4 | 建立 raw process | stdout/stderr 分离测试通过 |
| 5 | 实现 JSONL decoder | 边界单元测试通过 |
| 6 | 启动 Pi RPC | `get_state` 得到合法 response |
| 7 | 接入真实 provider | UI 收到流式文本事件 |
| 8 | 验证 Pi 文件工具 | `proof.txt` 内容校验通过 |
| 9 | 验证取消和重启 | 无残留 Node/Pi 子进程 |
| 10 | 形成可行性报告 | 指标、故障和 go/no-go 结论完整 |

### 3.5 验收脚本

在一台目标真机上执行：

1. 卸载旧 APK 和应用数据，安装新的 Debug APK。
2. 完成 rootfs、Node 和 Pi 首装。
3. 确认 health check 显示正确架构、Node 版本和 Pi `0.81.1`。
4. 输入 provider、model 和临时 API key，启动 Agent。
5. 应用生成随机 nonce，要求 Pi 在当前工作区创建 `proof.txt`，内容必须精确等于该 nonce。
6. Android 代码绕过 Pi，直接读取 `proof.txt` 并比对内容。
7. 发送一个会触发多段文本流的普通 prompt，确认 UI 连续更新且没有 JSON 解析错误。
8. 在一次生成过程中执行 abort，确认最终回到可再次输入状态。
9. 停止 Agent，确认外层 bash、PRoot、Node/Pi 进程全部退出。
10. 不清除 runtime，连续重启 Agent 并完成三次 prompt。
11. 强制关闭并重新打开应用，健康检查仍通过，Agent 可重新启动。

### 3.6 退出条件

必须同时满足：

- fresh install 能从 APK 完成 Ubuntu、Node 和 Pi 安装。
- Pi RPC stdout 中没有非 JSONL 内容，验收期间协议解析错误为 0。
- 真实模型请求能流式完成。
- Pi 能使用内置工具创建文件，Android 能独立验证结果。
- abort 后进程仍可继续使用，停止后无残留子进程。
- 同一 runtime 上至少三次连续启动和任务成功。
- 已记录 APK 大小、磁盘占用、安装耗时、冷/温启动耗时和内存峰值。
- 所有失败都能看到明确阶段、退出码和脱敏 stderr。
- 已提交一份 go/no-go 报告，列出 ARM64、Android 版本和已知限制。

### 3.7 停止或调整条件

出现以下情况时，不进入 `0.2.0`，先调整架构：

- `ProcessBuilder` 和可控 JNI raw launcher 均无法稳定提供干净管道。
- Pi 的必需依赖没有可替代的 Linux ARM64 实现。
- PRoot 下 Pi 内置文件或 Shell 工具存在无法规避的语义错误。
- 每次停止都会遗留无法由应用 UID 清理的关键子进程。
- 在多个复现中 stdout 仍被不可控内容污染。
- 最低目标设备的内存或磁盘成本明显不可接受。

`0.1.0` 的交付物是 Debug APK、测试、日志和可行性结论，不对外发布。

## 4. `0.2.0`：单工作区 MVP

### 4.1 目标

让一个内部测试用户能够选择一个手机目录，在一个持久会话中让 Pi 完成实际任务，并安全地把修改同步回原目录。

### 4.2 功能范围

- 通过 SAF 选择一个目录并保存持久授权。
- 建立一个托管工作区，支持首次导入、差异预览、确认写回和冲突提示。
- 一次只允许一个活动工作区和一个活动 Session。
- 启用 Pi session 持久化、恢复和新建会话。
- API key 通过 Android Keystore 加密保存。
- provider/model 使用手动配置 profile，不要求完整 provider 浏览器。
- 使用 foreground service 承载执行中的 Agent，并提供停止通知。
- 支持 Activity 重建、应用切到后台和进程意外退出后的可解释恢复。
- 基础 Markdown、代码块和工具结果展示。
- 展示 token/cost（provider 返回时）和当前工作区。
- 运行环境安装失败可恢复，支持安全重装但保留用户数据。

### 4.3 明确不做

- 不启用用户安装的 packages 和 extensions。
- 不提供直接工作区和全文件权限。
- 不支持多工作区并行或自动实时同步。
- 不支持 Pi session tree、fork、compaction 高级 UI。
- 不公开发布。

### 4.4 退出条件

- SAF grant 在设备重启后仍可使用。
- 1000 个普通文本文件的导入、diff 和写回可以完成，并有进度状态。
- 外部与私有副本同时修改同一文件时不会静默覆盖，能够产生冲突。
- 新增、修改、删除三类操作都在用户确认后正确写回。
- Session 在 Activity 重建和应用重启后可恢复消息。
- 后台执行 10 分钟任务时 foreground service 状态正确，用户可以停止。
- API key 不出现在日志、命令行、rootfs 配置文件和诊断导出中。
- 至少覆盖两台 ARM64 真机和两个 Android API 大版本。

## 5. `0.3.0`：Pi 生态兼容

### 5.1 目标

在明确的信任模型下支持 Pi packages 和 resources，并兼容 RPC 能表达的标准 extension UI。

### 5.2 功能范围

- 列出、安装、移除和更新 npm、Git、本地路径 Pi packages。
- 显示 package 来源、固定版本、安装日志、资源清单和更新状态。
- 支持 extensions、skills、prompt templates 和 themes 的发现与启停。
- 支持用户级和工作区级配置，并保留 Pi project trust 语义。
- 实现 `get_commands` 和 extension command 调用。
- 实现 `select`、`confirm`、`input`、`editor`、`notify`、`setStatus`、`setWidget`、`setTitle` 和 `set_editor_text`。
- 对 `ctx.ui.custom()` 及其他 TUI-only 能力返回清晰的不支持状态。
- 建立受控测试 packages，覆盖无 UI extension、自定义工具、标准 UI、npm 依赖和 extension error。
- 提供 Pi/Node/package 兼容矩阵和故障隔离入口。

### 5.3 安全要求

- 安装前明确提示 package 和 npm scripts 可执行任意同 UID 代码。
- 默认不加载未信任工作区中的本地资源。
- extension UI 请求带来源、会话和超时信息，过期请求不能操作新会话。
- package 安装与 Agent 运行互斥，失败时不破坏上一可用版本。
- 支持禁用全部第三方资源的恢复模式。

### 5.4 退出条件

- npm、Git、本地三种 package 来源均通过安装/升级/卸载测试。
- 受控兼容套件中的无 UI 和标准 UI extension 全部通过。
- extension UI 取消、超时、应用旋转和 Agent 退出没有悬挂请求。
- 不支持 TUI 的 extension 能失败退出，不会卡死 Agent。
- 恶意/错误 package 安装失败后，Pi 核心仍能以恢复模式启动。
- 兼容矩阵记录每个测试 package 的版本、架构和限制。

## 6. `0.4.0`：移动端工作台

### 6.1 目标

把已验证的 Agent 和生态能力组织成适合手机重复使用的工作流。

### 6.2 功能范围

- 多个托管工作区的创建、切换、归档和存储占用管理。
- 多个 Session 可分别选择工作区；活动 Session 使用独立 Agent 进程，并可在设备资源上限内并发运行。
- 每个 Agent 将自己的宿主工作区映射为 `/workspace`，所有 Agent 共享同一 Pi 全局配置目录。
- 空闲 Session 可通过停止并重启 Agent 切换工作区；运行中的切换必须拒绝或先由用户确认中止。
- 文件浏览、搜索、diff、冲突处理和单文件导入/导出。
- 从 Android 分享菜单接收文本、图片和文档。
- 图片 prompt 与相机/相册选择。
- Session 列表、重命名、fork、tree、统计和 compaction。
- 模型列表、provider profiles 和 thinking level。
- steer、follow-up 队列及其状态展示。
- 可选诊断终端，只用于用户主动打开的排障场景。
- `full` 侧载 flavor 的直接工作区试验；`play` flavor 继续只使用托管工作区。
- 存储清理、session/export 和用户数据备份入口。

### 6.3 退出条件

- 切换工作区不会把 Session 命令发送到错误目录。
- 两个并发 Agent 分别修改各自工作区时不会发生跨工作区读写，且均能读取相同的 Pi 全局配置。
- package 安装、全局配置迁移与全部 Agent 运行互斥，并发 Agent 数量具有经过真机验证的上限。
- 文件同步在大文件、二进制文件、重命名、删除和 provider 异常下有明确结果。
- 分享进来的内容不会绕过工作区授权。
- Session tree/fork 与 Pi RPC 状态一致，应用重启后仍可恢复。
- `play` 与 `full` flavor 的权限和能力在构建、manifest 和 UI 中完全分离。
- 关键工作流通过手机和平板尺寸的 UI 自动化测试。

## 7. `0.5.0`：公开 Beta

### 7.1 目标

从“功能可用”提升到“可以让外部用户安装、升级和诊断”。

### 7.2 功能范围

- 校验过的 runtime bundle 或可靠的断点安装方案。
- rootfs、Node、Pi 的事务式升级和回滚。
- 应用数据库、Pi 配置、sessions 和 packages 的迁移测试。
- 崩溃报告和用户可导出的脱敏诊断包。
- 磁盘配额、日志轮转、缓存清理和安装前空间检查。
- 网络错误、provider 限流和自动 retry 的完整 UI。
- 启动、内存、耗电和长会话性能优化。
- 无障碍、国际化、深色/浅色主题和不同屏幕尺寸。
- CI 中构建两个 flavor、运行测试、生成 SBOM、扫描许可证和依赖漏洞。
- 开源许可页面、对应源码发布流程和第三方 notice。
- 隐私政策、数据删除、备份和恢复说明。

### 7.3 退出条件

- 从至少前两个内部版本升级不会丢失工作区、sessions 或凭据引用。
- runtime 升级中断后能够回滚或恢复，不留下不可启动状态。
- 24 小时压力测试无失控子进程、无持续 CPU 空转、无无限日志增长。
- 低存储、无网络、API key 失效、Android 杀进程都有可恢复路径。
- 完成 LGPL/第三方许可证审查、对应源码发布演练和 SBOM 验证。
- 安全检查确认 exported components、日志、备份和存储权限符合预期。
- Beta 支持文档包含已知不兼容能力，尤其是 TUI-only extensions。

## 8. `1.0.0`：稳定版

### 8.1 稳定承诺

- Android 8.0+ ARM64 的支持范围或新的明确最低版本。
- Runtime manifest、工作区元数据和 Session 索引有可迁移 schema。
- Pi RPC 适配层对已支持 Pi 版本有自动兼容测试。
- 托管工作区不会静默覆盖外部冲突。
- 标准 extension UI 契约稳定；TUI-only 能力明确排除。
- runtime、Pi、packages 和应用更新彼此独立且可回滚。
- 对外发布包同时提供对应开源源码、notices 和 SBOM。

### 8.2 发布门槛

- 所有 `0.5.0` 退出条件持续通过。
- 支持设备矩阵和 package 兼容矩阵公开且可复现。
- 没有 P0/P1 数据丢失、安全或进程生命周期缺陷。
- 崩溃率、Agent 启动成功率、同步成功率达到发布前单独确定的 SLO。
- 完成一次从 clean install、runtime 安装、真实任务、package 使用、升级到数据导出的全链路发布演练。

## 9. 跨版本质量门槛

每个版本都必须保持：

- TerminalCore fork 固定 commit，变更有来源记录。
- Pi 固定精确版本，不使用无测试的自动更新。
- JSONL parser 单元测试全部通过。
- stdout 不承载日志，stderr 不进入协议解析。
- API key 和认证 header 不出现在日志或诊断包。
- PRoot 不被描述为安全沙箱。
- 新增文件访问能力前先扩展威胁模型和数据丢失测试。
- 新增第三方二进制或 npm 依赖时同步更新 SBOM 和许可证清单。
- 发现版本目标过大时，删减当前范围或增加后续版本，不降低退出条件。

## 10. 版本完成记录模板

每个阶段结束时提交一份记录：

```markdown
# <版本> 完成记录

## 结论
继续 / 调整后继续 / 停止

## 已满足的退出条件
- ...

## 未满足项
- ...

## 实测环境
- 设备、Android API、ABI
- App、TerminalCore、rootfs、Node、Pi 版本

## 指标
- 安装、启动、内存、磁盘、耗电

## 已知限制
- ...

## 下一版本前必须解决
- ...
```

这个记录是进入下一版本的输入，不以口头确认替代。
