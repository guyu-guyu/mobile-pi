# 0.2.0 开发进度与设备验收

> 更新日期：2026-08-06

## 结论

`0.2.0` 的功能代码、JVM 测试和 Debug APK 已完成。版本尚不能按路线图标记为完成，因为 SAF 持久授权、真实 provider、Activity/应用重启、10 分钟 foreground service、凭据泄漏检查和双设备/API 矩阵必须在 ARM64 真机上验收。

## 已实现

- SAF `ACTION_OPEN_DOCUMENT_TREE` 目录选择、读写持久授权和授权失效状态。
- 递归 DocumentsContract 扫描、普通文件 SHA-256、1000 文件进度。
- 私有托管副本、版本化活动工作区元数据和原子同步基线。
- 三方 diff、首次导入、双向新增/修改/删除、确认写回、预览过期拒绝和冲突阻止。
- 每个工作区独立的 Pi Session 目录、最近 Session 恢复、消息恢复和新建 Session。
- `get_session_stats` token、cost 和 context 使用率。
- Android Keystore AES-GCM provider profile；密文位于 `noBackupFilesDir`。
- 非 exported `dataSync` foreground service、低优先级通知和停止 action。
- Activity 重建后重新绑定服务；异常进程退出保留可解释恢复标记。
- 基础 Markdown、代码块和工具累计结果展示。
- runtime 安全重装不删除工作区、Session 或 Keystore profile。

## 自动验证

| 项目 | 结果 |
|---|---|
| 工作区 JVM 测试 | 15 个通过，含 1000 文件导入、diff 和双向写回 |
| Pi runtime JVM 测试 | 26 个通过 |
| TerminalCore JVM 测试 | 2 个通过 |
| 本次模块 lint | `app`、`runtime:pi`、`feature:workspaces` 全部通过 |
| Debug APK | 构建通过，91,261,391 bytes |
| 补丁检查 | `git diff --check` 通过 |

根级 `lintDebug` 仍会被未修改的 TerminalCore fork 阻断：当前 Android/Compose lint 对该模块报告 157 个既有错误和 79 个警告。项目没有为这些问题生成 baseline；CI 只对本次维护的三个模块执行 lint。

## 路线图退出条件

| 退出条件 | 当前证据 |
|---|---|
| SAF grant 在设备重启后仍可使用 | 代码完成，待真机重启 |
| 1000 文件导入、diff、写回及进度 | JVM 通过，待真实 SAF provider |
| 同文件双边修改不会静默覆盖 | 单元测试通过，待真机 UI |
| 新增、修改、删除确认后正确写回 | 单元测试通过，待真机 provider |
| Activity 重建和应用重启后恢复 Session | 代码/解析测试通过，待真机 |
| 后台执行 10 分钟且通知可停止 | manifest/service 完成，待真机 |
| API key 不进入日志、命令行、rootfs/诊断包 | 代码边界完成，待设备泄漏检查 |
| 两台 ARM64、两个 Android API 大版本 | 待设备矩阵 |

## 真机验收脚本

1. 在设备 A fresh install Debug APK，安装 runtime，选择含 1000 个普通文本文件的目录。
2. 确认导入进度完成，重启设备，再打开应用并执行一次同步预览。
3. 分别在托管副本和所选目录制造新增、修改、删除，确认预览后写回并逐项比对。
4. 同时修改同一文件两侧，确认 UI 显示冲突且没有 Apply 写回入口。
5. 保存 provider profile，强制停止并重开应用，确认 provider/model/API key 可恢复且只以密码形式显示。
6. 启动 Agent，完成两轮对话和一次文件工具调用；旋转 Activity、切后台、重开应用，确认消息与 Session 恢复。
7. 新建 Session，确认消息清空且后续重启恢复新 Session，而不是旧 Session。
8. 执行至少 10 分钟的模型任务，确认 foreground notification 持续存在并可从通知停止。
9. 在运行中强制终止应用进程，重开后确认显示可解释恢复状态，并能从持久 Session 继续。
10. 执行 runtime 清除和重装，确认工作区、Session、SAF 元数据和 provider profile 均保留。
11. 使用设备日志、进程命令行、应用私有 rootfs 和诊断输出搜索测试 API key，结果必须为零。
12. 在设备 B 的另一个 Android API 大版本重复步骤 2、4、6、8 和 11。

## 设备记录

| 设备 | Android/API | ABI | SAF provider | 结果 | 备注 |
|---|---|---|---|---|---|
| A | 待填写 | | | 未执行 | |
| B | 待填写 | | | 未执行 | |

本机仅检测到 `emulator-5554`（x86_64、API 28）；当前 APK 只打包 `arm64-v8a`，因此该模拟器不能用于安装或替代真机矩阵。

只有上述设备矩阵全部通过，才能把结论改为“继续”并将 `0.2.0` 标记完成。
