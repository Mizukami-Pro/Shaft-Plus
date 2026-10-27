# shaft+

**更方便地管理自己收藏的所有内容。**

把插画、漫画、动图和小说收藏汇总到「全收藏分类」，按标签整理、搜索与筛选，快速找到自己收藏过的内容。shaft+ 基于 [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft) classic 4.9.7；以下只介绍本分支新增内容，并保留 **GPL-2.0**。

[下载 APK](https://github.com/Mizukami-Pro/Shaft-Plus/releases) · [仓库首页](../README.md) · [构建与发布](../docs/shaft-plus-publishing.md)

## 新增功能

- **全收藏分类**：汇总插画、漫画、动图与小说的标签，可选择非公开收藏，支持标签搜索与标签内作品搜索。
- **五种排序**：最新投稿、最早投稿、收藏数、浏览量和收藏速率。
- **我的 Tag 排行海报**：TOP 6 / TOP 20，可选账号和头像，支持保存、分享。
- **标签详情筛选**：投稿时间、收藏数、users入り、AI / R18、比例、分辨率、页数和作品分类。
- **预览图 / 原图切换**：在详情页选择完整分辨率图片。
- **小说翻译入口**：阅读器内提供翻译快捷入口。
- **移除商店评分弹窗和入口**。

## 安装与验证

Android 7.0 以上，当前交付 arm64-v8a APK。正式包名 `ceui.pixiv.shaftplus`，显示名称 **shaft+**，使用独立 release 签名。可与原版并存，但不会自动迁移原版或旧 debug 包的数据。

本地 release 构建、R8、lintVital 和签名校验已通过；真实账号同步、原图和翻译结果仍需登录回归。

## 来源与许可

原项目的作者与贡献者信息保留在源码和 Git 历史中。完整许可证见 [LICENSE](../LICENSE)。发布 APK 应同时提供对应源码。

本项目与 Pixiv、PixShaft 及原版维护者无隶属关系。作品版权归作者或 Pixiv；名称、标志及服务归各自权利人和运营方所有。

可选 PixShaft 托管功能保持现有入口，可用性遵循服务运营方政策；本分支不含原版签名私钥或 events HMAC 密钥，已移除原版 Firebase Analytics / Crashlytics SDK 与配置。
