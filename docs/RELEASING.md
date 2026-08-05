# CI/CD 与版本发布

仓库包含两个 GitHub Actions 工作流：

- `.github/workflows/ci.yml`：所有分支的提交、面向 `main` 的 Pull Request
  以及手动触发时，运行 JVM 单元测试并构建 Debug APK。
- `.github/workflows/release.yml`：收到语义化版本 tag 后，重新运行测试、构建
  正式签名 APK、验证签名、生成 SHA-256，并创建 GitHub Release。

两个工作流都会按父仓库记录的精确提交初始化 `runtime/terminal-core`
子模块，不会自动跟随子模块远程分支。

## 配置 Release 签名

Android 应用后续升级必须始终使用同一签名密钥。密钥丢失后，已安装应用将无法
通过新版本覆盖升级。请在安全的离线位置备份 keystore、alias 和两个密码，
不要把 keystore 或密码提交到 Git。

如尚无正式签名密钥，可在 JDK 17 环境生成：

```bash
keytool -genkeypair -v \
  -keystore mobile-pi-release.jks \
  -alias mobile-pi \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

将 keystore 转换为单行 Base64。PowerShell：

```powershell
[Convert]::ToBase64String(
    [IO.File]::ReadAllBytes("mobile-pi-release.jks")
) | Set-Clipboard
```

Linux：

```bash
base64 -w 0 mobile-pi-release.jks
```

### 在 GitHub 添加 Repository secrets

这里需要添加 **四条独立的 Repository secret**，不能把四项合并成一条，
也不要添加为 Environment secret。具体步骤：

1. 打开 `https://github.com/guyu-guyu/mobile-pi`；
2. 进入 `Settings -> Secrets and variables -> Actions`；
3. 在 `Repository secrets` 区域点击 `New repository secret`；
4. 在 `Name` 中填写下表中的 Secret 名称，在 `Secret` 中填写对应内容，
   然后点击 `Add secret`；
5. 重复第 3、4 步，直到下表四条 Secret 都已创建。

Secret 名称区分大小写，必须与下表完全一致。值中不要额外添加引号或首尾空格。

| Secret | 内容 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | keystore 文件的单行 Base64 内容，不是文件路径 |
| `RELEASE_KEYSTORE_PASSWORD` | 生成 keystore 时输入的 keystore 密码 |
| `RELEASE_KEY_ALIAS` | 生成密钥时设置的 alias，例如 `mobile-pi` |
| `RELEASE_KEY_PASSWORD` | alias 对应的私钥密码 |

即使 keystore 密码与私钥密码相同，`RELEASE_KEYSTORE_PASSWORD` 和
`RELEASE_KEY_PASSWORD` 也必须分别创建。配置完成后，GitHub 页面应显示上述
四个名称；出于安全原因，GitHub 不会再次显示 Secret 的原始值。

Release 工作流在任一 Secret 缺失时会立即失败，不会发布 unsigned APK。

## 创建版本发布

发布 tag 格式为 `vMAJOR.MINOR.PATCH`，也支持
`vMAJOR.MINOR.PATCH-prerelease`。例如：

```bash
git switch main
git pull --ff-only origin main
git tag -a v0.1.0 -m "Mobile Pi 0.1.0"
git push origin v0.1.0
```

预发布 tag（例如 `v0.2.0-beta.1`）会创建 GitHub Pre-release。正式 tag
会创建普通 Release。工作流使用 tag 去掉 `v` 后的内容作为 Android
`versionName`，并按以下公式生成 `versionCode`：

```text
MAJOR * 1,000,000 + MINOR * 1,000 + PATCH
```

`MAJOR` 不得超过 2099，`MINOR` 和 `PATCH` 不得超过 999，且计算结果
必须大于 0。版本号应只递增，不要复用或移动已经推送的发布 tag。

成功后，Release 包含：

- `mobile-pi-VERSION-arm64-v8a.apk`：已验证签名的 ARM64 Release APK；
- 同名 `.sha256` 文件：用于下载后校验完整性。

## 发布前检查

推送 tag 前确认：

1. tag 指向准备发布的 `main` 提交；
2. CI 已在该提交上通过；
3. TerminalCore 子模块提交已经推送到其远程仓库；
4. 四个 Release Secret 已配置；
5. 本地已安全备份正式签名密钥及密码。
