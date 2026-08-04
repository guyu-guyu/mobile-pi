# Mobile Pi

Mobile Pi 让用户能够在 Android 设备本地运行 Pi，并以受控方式访问工作文件。本文只定义 Android UI、运行环境集成和 Pi 功能之间共享的产品术语，不记录具体实现技术。

## 统一语言

**Mobile Pi**：
承载 Pi，并向用户呈现会话、工作区和资源的 Android 应用。
_避免使用_：Operit fork、Pi 终端

**内嵌运行环境（Embedded Runtime）**：
由应用管理、用于运行 Pi 及其命令行依赖的 Linux 环境。
_避免使用_：Termux、虚拟机、容器

**运行环境安装（Runtime Installation）**：
一份具有明确版本、归应用所有的内嵌运行环境与 Pi 工具链安装实例。
_避免使用_：终端配置、Linux 实例

**工作区（Workspace）**：
提供给 Pi 处理的一组文件，也是会话的当前工作目录。
_避免使用_：项目、仓库、文件夹

**托管工作区（Managed Workspace）**：
从用户授权的 Android 文档目录导入，并由 Mobile Pi 负责同步的私有工作副本。
_避免使用_：SAF 挂载、缓存目录

**直接工作区（Direct Workspace）**：
Pi 直接原地访问、不经过导入和同步的文件系统目录。
_避免使用_：挂载项目、共享目录

**会话（Session）**：
一段 Pi 对话及其历史、模型配置、工作区和当前 Agent 状态。
_避免使用_：聊天、终端会话、进程

**Agent 进程（Agent Process）**：
服务于一个活动会话，并与 Mobile Pi 通信的 Pi 运行实例。
_避免使用_：会话、Shell

**Pi 资源（Pi Resource）**：
Pi 能够发现和加载的 extension、skill、prompt template 或 theme。
_避免使用_：插件、附加组件

**Pi 包（Pi Package）**：
通过 npm、Git 或本地路径安装，包含一个或多个 Pi 资源及可选运行时依赖的发布单元。
_避免使用_：Android 包、APK、插件

**项目信任（Project Trust）**：
允许 Pi 加载工作区本地配置、包和可执行资源的决定；它不是沙箱，也不限制 Pi 工具的文件操作能力。
_避免使用_：权限授权、安全模式

**文件授权（File Grant）**：
Android 授予 Mobile Pi 读取或写入用户所选文档目录或文件的能力。
_避免使用_：Root 权限、存储权限

**运行环境健康检查（Runtime Health Check）**：
可重复验证当前运行环境能否启动 Shell、Node.js 和指定 Pi 版本的检查过程。
_避免使用_：配置完成、Ping
