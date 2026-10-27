# shaft+

**更方便地管理自己收藏的所有内容。**

收藏越来越多，想找的作品却越来越难翻？shaft+ 把插画、漫画、动图和小说收藏汇总到「全收藏分类」，让你按标签整理、搜索和筛选，快速找到自己收藏过的内容。

本项目基于 Pixiv-Shaft classic 4.9.7，在原版上新增收藏管理等功能。这里重点介绍本分支新增的内容。

[下载 APK](https://github.com/Mizukami-Pro/Shaft-Plus/releases) · [源码](https://github.com/Mizukami-Pro/Shaft-Plus/tree/shaft-plus) · [原版项目](https://github.com/CeuiLiSA/Pixiv-Shaft) · [发布与构建](docs/shaft-plus-publishing.md)

[简体中文](README/README.zh-CN.md) · [日本語](README/README.ja.md)

## 让所有收藏更好管理

| 新增功能 | 你可以怎么用 |
| --- | --- |
| 全收藏标签分类 | 汇总插画、漫画、动图与小说的收藏标签，按需包含非公开收藏，不必在不同列表之间来回翻找。 |
| 搜索与精细筛选 | 搜索标签或标签内作品，再按投稿时间、收藏数、users入り、AI / R18、比例、分辨率、页数和作品分类缩小范围。 |
| 五种排序 | 用最新投稿、最早投稿、收藏数、浏览量和收藏速率，重新整理自己的收藏。 |
| 我的 Tag 排行 | 把自己的收藏偏好生成 TOP 6 / TOP 20 海报，可选择账号和头像，支持保存及分享。 |

从侧边栏进入 **全收藏分类**，同步后点选标签即可查看对应作品，再用搜索、筛选和排序找到目标内容。

其他新增：详情页预览图 / 原图切换、小说阅读器翻译快捷入口；本分支移除了应用商店评分弹窗及入口。

## 安装

- Android 7.0（API 24）及以上；当前提供 `arm64-v8a` APK。
- 应用显示名：**shaft+**。
- 独立正式包名：`ceui.pixiv.shaftplus`，使用本项目专属签名。
- 可以与原版并存；原版或旧 debug 包的应用数据不会自动迁移。
- 后续更新请使用本仓库 Releases 中相同签名的 APK。

## 验证范围

本地正式 release 构建、R8、lintVital 和 APK 签名校验已通过。真实账号的全量收藏同步、原图加载及翻译结果仍需要登录回归；这里不宣称完成全部端到端验证。

## 与原版的关系

shaft+ 基于 [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft) 的 **classic** 分支开发，仅增加社区功能与修复。原作者和贡献者信息保留在 Git 历史及源码中。

本项目与 Pixiv、PixShaft 及原版维护者无隶属或合作关系。Pixiv、PixShaft 名称、标志和服务归各自权利人所有；应用中的作品版权归各自作者或 Pixiv 所有。

可选 PixShaft 托管功能保持现有入口，其可用性受服务运营方政策限制。本项目不包含原版签名私钥或 events HMAC 密钥；本分支已移除原版 Firebase Analytics / Crashlytics SDK 与配置。

## 开源许可

沿用原项目的 **GPL-2.0**，完整条款见 [LICENSE](LICENSE)。发布 APK 时提供对应源码并保留上游版权声明。

## 构建与发布

使用 JDK 17、Android SDK 36、NDK `27.0.12077973`、CMake `3.22.1` 和项目自带 Gradle Wrapper：

```sh
./gradlew :app:assembleGithubDebug
```

正式包需要自行配置 `keystore.properties` 或 `SHAFT_KEYSTORE_*` 环境变量；密钥和密码不得提交。详细步骤见 [发布说明](docs/shaft-plus-publishing.md)。
