# shaft+

**更方便地管理自己收藏的所有内容。**

把插画、漫画、动图和小说收藏汇总到「全收藏分类」，按标签整理、搜索与筛选，快速找到自己收藏过的内容。shaft+ 基于 [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft) classic 4.9.7；以下只介绍本分支新增内容，并保留 **GPL-2.0**。

[下载 APK](https://github.com/Mizukami-Pro/Shaft-Plus/releases) · [仓库首页](../README.md) · [构建与发布](../docs/shaft-plus-publishing.md)

## 全收藏分类：把不同类型的收藏放到一起找

从侧边栏进入「全收藏分类」，应用会逐页同步当前账号的收藏，将插画、漫画、动图和小说已有的作品标签汇总到一个页面，并显示各标签下有多少件收藏。你可以搜索标签原名或译名，也可以选择是否包含非公开收藏。

例如，你收藏的插画和小说都带有「初音ミク」，点开这个标签就能一起看到相关作品，再切换到需要的作品类型。分类使用作品已有标签，不会自动改写标签或收藏的公开状态。首次同步时可以先查看已经同步的内容；同步完成后，统计才覆盖全部收藏。

## 搜索与筛选：缩小范围找回作品

点击标签后，在详情页按标题、作者、标签文字继续搜索，或切换「全部 / 插画 / 漫画 / 小说 / 动图」。例如想找某个角色的漫画，可以先进入角色标签，再选择漫画，并输入记得的作者或标题词。

还可以同时使用多项条件：投稿日期、作品在 Pixiv 上的总收藏数、users入り 标签阈值、长宽比、分辨率、单页 / 多页、AI 和 R18 范围。列表与命中数量随条件更新；这些操作都在当前标签下已经同步的个人收藏中进行。

## 五种排序：按自己的目的重新浏览

| 选项 | 实际含义 |
| --- | --- |
| 最新发布 / 最早发布 | 按作品投稿时间排序，适合看较新作品或回顾早期投稿。 |
| 最多收藏 | 按作品在 Pixiv 的总收藏数排序。 |
| 热度 | 按作品浏览量排序。 |
| 趋势 | 用总收藏数除以发布天数，估算平均收藏速率。 |

时间排序不是你收藏作品的时间；趋势也不是实时新增收藏榜。

## 我的 Tag 排行：展示自己的收藏偏好

总览页的「我的 Tag 排行」统计收藏作品最常出现的标签，并生成 TOP 6 / TOP 20 海报。TOP 6 适合看最鲜明的偏好，TOP 20 则展开更多主题；可以选择是否显示账号与头像，保存到相册或通过系统分享。

例如「初音ミク · 23 件作品」，表示当前范围内有23件已同步收藏带这个标签。单件作品可有多个标签，各标签数量相加不等于收藏总数；建议同步完成后再生成海报。

## 另外两个便捷入口

- **预览图 / 原图切换**：在详情页直接选择完整分辨率图片查看细节，再切回预览。
- **小说翻译快捷入口**：将已有翻译能力放在阅读器底栏，更快进入翻译及原文 / 译文显示选项。

## 功能演示

开发期模拟器截图与应用导出的海报，使用演示数据；数量、标签和素材仅作示意，部分界面为英文。点击图片可查看大图。

| 全收藏标签总览 | 标签内搜索与排序 | 精细筛选 |
| --- | --- | --- |
| [<img src="../docs/images/shaftplus/collection-overview.png" width="260" alt="全收藏标签总览">](../docs/images/shaftplus/collection-overview.png) | [<img src="../docs/images/shaftplus/tag-detail.png" width="260" alt="标签内作品搜索与排序">](../docs/images/shaftplus/tag-detail.png) | [<img src="../docs/images/shaftplus/collection-filters.png" width="260" alt="收藏精细筛选">](../docs/images/shaftplus/collection-filters.png) |

| TOP 6 收藏偏好海报 | TOP 20 收藏偏好海报 |
| --- | --- |
| [<img src="../docs/images/shaftplus/tag-ranking-top6.png" width="320" alt="TOP 6 Tag 排行海报">](../docs/images/shaftplus/tag-ranking-top6.png) | [<img src="../docs/images/shaftplus/tag-ranking-top20.png" width="320" alt="TOP 20 Tag 排行海报">](../docs/images/shaftplus/tag-ranking-top20.png) |

## 安装与验证

Android 7.0 以上，当前交付 arm64-v8a APK。正式包名 `ceui.pixiv.shaftplus`，显示名称 **shaft+**，使用独立 release 签名。可与原版并存，但不会自动迁移原版或旧 debug 包的数据。

本地 release 构建、R8、lintVital 和签名校验已通过；真实账号同步、原图和翻译结果仍需登录回归。

## 来源与许可

原项目的作者与贡献者信息保留在源码和 Git 历史中。完整许可证见 [LICENSE](../LICENSE)。发布 APK 应同时提供对应源码。

本项目与 Pixiv、PixShaft 及原版维护者无隶属关系。作品版权归作者或 Pixiv；名称、标志及服务归各自权利人和运营方所有。

可选 PixShaft 托管功能保持现有入口，可用性遵循服务运营方政策；本分支不含原版签名私钥或 events HMAC 密钥，已移除原版 Firebase Analytics / Crashlytics SDK 与配置。
