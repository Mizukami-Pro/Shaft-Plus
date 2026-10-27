# shaft+ 构建与发布

## 项目身份

- 上游：[CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft)，classic 4.9.7 基线。
- Fork：[Mizukami-Pro/Shaft-Plus](https://github.com/Mizukami-Pro/Shaft-Plus)，默认开发分支 `shaft-plus`。
- 保留 [GPL-2.0](../LICENSE)、上游版权与 Git 历史；发布 APK 时提供对应源码。
- 显示名：`shaft+`；release 包名：`ceui.pixiv.shaftplus`；debug 包名：`ceui.pixiv.cshaft`。
- 当前交付 ABI：`arm64-v8a`；最低 Android 7.0（API 24）。

## 本地构建

需要 JDK 17、Android SDK 36、NDK `27.0.12077973`、CMake `3.22.1`。使用仓库自带 Gradle Wrapper：

```sh
./gradlew :app:assembleGithubDebug
./gradlew :app:testGithubDebugUnitTest
```

本工作区的 Windows 构建需走外部环境脚本，其他检出不依赖该脚本：

```powershell
pwsh -File D:\shaft+\_env\build_env.ps1 :app:assembleGithubRelease
```

正式包输出：`app/build/outputs/apk/github/release/app-github-release.apk`。

## 专属签名

为自己的分支生成独立签名，在仓库根目录创建被忽略的 `keystore.properties`：

```properties
storeFile=/absolute/path/to/your/shaftplus-release.p12
storePassword=<local secret>
keyAlias=shaftplus
keyPassword=<local secret>
```

也可使用环境变量 `SHAFT_KEYSTORE_FILE`、`SHAFT_KEYSTORE_PASSWORD`、`SHAFT_KEY_ALIAS`、`SHAFT_KEY_PASSWORD`。

发布签名的文件、密码及 alias 必须做离线加密备份；后续更新保持包名和签名不变。不要把私钥、密码或导出密钥放进源码、issue 或构建日志。

实际 release 打包任务会检查签名材料，覆盖 `assembleRelease` 等聚合入口；只有显式的 x86_64 release instrumentation 测试模式允许临时 debug 签名，测试产物不得分发。

## GitHub Actions

- Push / pull request 到 `shaft-plus` 会运行现有单元测试、lint 和构建检查；CI 设备测试使用 GitHub 云端模拟器。
- 不注入上游 events HMAC 密钥。
- 本次签名保留在本机，不会自动上传到 GitHub Secrets。
- 若以后要启用 tag 的云端正式构建，先配置自己的 `SHAFT_KEYSTORE_B64`、`SHAFT_KEYSTORE_PASSWORD`、`SHAFT_KEY_ALIAS`、`SHAFT_KEY_PASSWORD` Secrets，再将仓库变量 `SHAFTPLUS_CI_RELEASE` 设为 `true`。
- CI 正式构建生成 Release 草稿，核验签名、测试和源码后再公开。

## 发布核验

1. 审核 diff，确认所有新增源码、资源和测试已提交；确认本机配置、私钥和中间产物未加入。
2. 只推送 `shaftplus` remote 的 `shaft-plus` 分支，保留 `origin` 供追踪上游。
3. 从对应提交构建 arm64 release，校验包名、非 debug 状态、ABI、证书和 SHA-256。
4. 新版本使用未占用的纯数字版本，例如 `v4.9.7.1`，并同步 APK 的 versionName / versionCode；不覆盖上游标签。
5. Release APK 资产名为 `ShaftPlus_<version>_arm64.apk`，与应用内更新解析器一致。
6. 将校验值和验证范围写入 Release，保留 GitHub 对应 tag 的源码下载；如附完整源码包，只包含 Git 跟踪内容及必要子模块源码。

## 验证与服务范围

release 构建与签名成功不等于完成真实账号验证。收藏同步、原图加载及翻译结果仍需要登录回归；不在自动流程中使用本机连接设备。

可选 PixShaft 托管功能保留原有入口，使用和可用性受运营方政策限制。此分支不包含上游签名私钥或 events HMAC 密钥，已移除上游 Firebase Analytics / Crashlytics SDK 与配置。
