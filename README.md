# shaft+

**更方便地管理自己收藏的所有内容。**

收藏多了之后，只记得某个角色、题材或作者，却不记得当时把作品收藏在哪里。shaft+ 把插画、漫画、动图和小说收藏汇总到「全收藏分类」，先按作品标签找到一批相关收藏，再用搜索、筛选和排序找回目标作品。

本项目基于 Pixiv-Shaft classic 4.9.7，重点增加收藏管理能力。这里介绍的是 shaft+ 新增内容；原版已有功能请参考[原项目](https://github.com/CeuiLiSA/Pixiv-Shaft)。

[下载 APK](https://github.com/Mizukami-Pro/Shaft-Plus/releases) · [源码](https://github.com/Mizukami-Pro/Shaft-Plus/tree/shaft-plus) · [构建与发布](docs/shaft-plus-publishing.md)

[简体中文](README/README.zh-CN.md) · [日本語](README/README.ja.md)

## 全收藏分类：先看清自己都收藏了什么

从侧边栏进入 **全收藏分类**，应用会逐页同步当前账号的收藏，把不同类型作品已有的标签汇总到同一个页面。你不必分别打开插画收藏和小说收藏，也不用先手工给每件作品重新分类。

页面按命中作品数排列标签，并显示收藏总量、作品类型统计和标签数量。例如，你收藏的插画、漫画和小说中都有「初音ミク」，这里会汇总这个标签；点击后就能一起查看相关作品，再按需要切换作品类型。

- **搜索标签**：输入标签原名或译名，例如「初音」或「原创」，快速找到对应分类。
- **选择统计范围**：通过「包含非公开收藏」开关，决定是否把非公开收藏一起纳入当前列表和统计。
- **边同步边查看**：首次收藏量较大时，页面会显示同步进度。已经同步的内容可以先查找，完整统计需要等同步完成。

这里的分类来自**作品已有标签**，提供汇总和查找入口；不会自动修改作品标签或收藏的公开状态。

<p align="center">
  <a href="docs/images/shaftplus/collection-overview.png"><img src="docs/images/shaftplus/collection-overview.png" width="320" alt="全收藏分类总览：标签搜索、非公开收藏开关、作品统计和标签排行"></a>
</p>

## 搜索与筛选：在一大批收藏中找回想要的作品

点击某个标签后，详情页会列出你已收藏、且带有该标签的作品。你可以继续按**标题、作者或标签文字**搜索，也可以在「全部 / 插画 / 漫画 / 小说 / 动图」之间切换，把范围缩小到自己要找的内容。

例如，想找「以前收藏过的某个角色的漫画」，可以先搜索角色标签，点进标签后选择「漫画」，再输入记得的作者或标题词。如果记不清名字，还可以用投稿时间、页数等条件继续筛选。

筛选面板支持把多个条件组合使用：

| 条件 | 适合怎么找 |
| --- | --- |
| 投稿期间 | 按作品发布时间选择最近一周、一年或自定义日期范围。 |
| 收藏数 | 例如只看全站收藏数达到 1000 的作品，或自定义一个收藏数区间。 |
| users入り 标签 | 按作品携带的 `1000users入り` 等标签阈值筛选；这是标签条件，与上面的作品收藏数分开。 |
| 比例、分辨率 | 找横图、竖图、正方形图，或按图像尺寸范围筛选。 |
| 页数 | 区分单页作品和多页作品。 |
| AI、R18 | 根据作品信息选择 AI / 非 AI，以及安全内容 / R18 内容范围。 |

这些条件用于**当前标签下已同步到本地的个人收藏**。列表与命中数量会随条件变化更新，适合逐步缩小范围。

<table>
  <tr><th width="50%">标签内搜索、作品分类与排序</th><th width="50%">组合多个筛选条件</th></tr>
  <tr>
    <td valign="top" align="center"><a href="docs/images/shaftplus/tag-detail.png"><img src="docs/images/shaftplus/tag-detail.png" width="320" alt="标签详情：作品搜索、全部插画漫画小说动图分类与五种排序"></a></td>
    <td valign="top" align="center"><a href="docs/images/shaftplus/collection-filters.png"><img src="docs/images/shaftplus/collection-filters.png" width="320" alt="筛选面板：投稿时间、收藏数、users入り、比例、分辨率、页数、AI 和 R18"></a></td>
  </tr>
</table>

## 五种排序：换个角度重新浏览收藏

同一个标签下，可以按不同方式重新排列作品。想回顾早期投稿、先看热门作品，或找相对较新的高收藏作品，都可以切换排序：

| 页面选项 | 排序依据 |
| --- | --- |
| 最新发布 | 作品投稿时间从新到旧。 |
| 最早发布 | 作品投稿时间从旧到新。 |
| 最多收藏 | 作品在 Pixiv 上的总收藏数从多到少。 |
| 热度 | 作品浏览量从多到少。 |
| 趋势 | 用作品总收藏数除以发布至今的天数，估算平均收藏速率。 |

时间排序依据是**作品投稿时间**。例如「最早发布」会把较早投稿的作品排在前面，不代表你最早收藏的作品；「趋势」是平均速率估算，不是实时新增收藏榜。

## 我的 Tag 排行：把收藏偏好做成一张海报

在全收藏分类页打开 **我的 Tag 排行**，应用会统计你收藏的作品最常带有哪些标签，并生成可以保存、分享的海报。它展示的是「我的收藏偏好」，不是 Pixiv 全站的热门标签排行。

- **TOP 6**：突出出现最多的六个标签，适合分享一眼能看懂的偏好摘要。
- **TOP 20**：展示更多标签，适合回顾自己的题材、角色和画风偏好。
- **可选账号和头像**：生成前决定是否在海报上展示个人信息。
- **保存与分享**：保存到相册，或通过系统分享菜单发送给其他应用。

例如，排行中的「初音ミク · 23 件作品」，表示当前统计范围内有 23 件已同步收藏带这个标签。一件作品可以带多个标签，因此各标签的数量相加不等于收藏总数。建议首次同步完成后再生成海报，让结果覆盖全部收藏。

<table>
  <tr><th width="50%">TOP 6：看最鲜明的偏好</th><th width="50%">TOP 20：展开更多收藏主题</th></tr>
  <tr>
    <td valign="top" align="center"><a href="docs/images/shaftplus/tag-ranking-top6.png"><img src="docs/images/shaftplus/tag-ranking-top6.png" width="320" alt="我的 Tag 排行 TOP 6 演示海报"></a></td>
    <td valign="top" align="center"><a href="docs/images/shaftplus/tag-ranking-top20.png"><img src="docs/images/shaftplus/tag-ranking-top20.png" width="320" alt="我的 Tag 排行 TOP 20 演示海报"></a></td>
  </tr>
</table>

以上图片来自开发期模拟器与应用导出的海报，使用演示数据，数量、标签和素材仅作示意；部分界面为英文。点击图片可查看大图。

## 另外两个便捷入口

- **详情页预览图 / 原图切换**：看作品时直接切换图片清晰度，需要查看细节时打开完整分辨率图片，也可切回预览图。
- **小说阅读器翻译快捷入口**：把已有翻译能力放到阅读器底栏，减少进入菜单查找的步骤，可进入翻译及原文 / 译文显示选项。

## 开始使用

1. 登录自己的 Pixiv 账号，从侧边栏打开「全收藏分类」。
2. 按需选择是否包含非公开收藏，等待后台同步；收藏较多时可以先浏览已同步内容。
3. 搜索或点选标签，在详情页组合使用作品类型、关键词、筛选和排序。
4. 想回顾整体偏好时，回到总览页生成「我的 Tag 排行」海报。

## 安装与验证

- Android 7.0（API 24）及以上，当前提供 `arm64-v8a` APK。
- 显示名：**shaft+**；独立正式包名：`ceui.pixiv.shaftplus`，使用本项目专属签名。
- 可以与原版并存；原版或旧 debug 包的应用数据不会自动迁移。
- 后续更新使用本仓库 Releases 中相同签名的 APK。

本次新增及相关功能的 **125 项 JVM / Robolectric 回归测试已通过**；本地正式 release 构建、R8、lintVital 和 APK 签名校验已通过。真实账号的完整同步、原图加载及翻译结果仍需要登录回归。

## 来源与许可

shaft+ 基于 [CeuiLiSA/Pixiv-Shaft](https://github.com/CeuiLiSA/Pixiv-Shaft) 的 classic 分支开发。原作者、贡献者信息与版权声明保留在 Git 历史及源码中，沿用 **GPL-2.0**，完整条款见 [LICENSE](LICENSE)。发布 APK 时提供对应源码。

本项目与 Pixiv、PixShaft 及原版维护者无隶属或合作关系。作品版权归各自作者或 Pixiv；相关名称、标志和服务归各自权利人所有。

可选 PixShaft 托管功能保持现有入口，其可用性受服务运营方政策限制。本项目不包含原版签名私钥或 events HMAC 密钥；已移除原版 Firebase Analytics / Crashlytics SDK 与配置。

## 构建与发布

使用 JDK 17、Android SDK 36、NDK `27.0.12077973`、CMake `3.22.1` 和项目自带 Gradle Wrapper：

```sh
./gradlew :app:assembleGithubDebug
```

正式包需要自行配置 `keystore.properties` 或 `SHAFT_KEYSTORE_*` 环境变量；密钥和密码不得提交。详细步骤见[发布说明](docs/shaft-plus-publishing.md)。
