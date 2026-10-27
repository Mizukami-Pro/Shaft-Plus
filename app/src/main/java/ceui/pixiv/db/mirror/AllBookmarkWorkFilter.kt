package ceui.pixiv.db.mirror

/**
 * 「全收藏分类 → 标签作品列表」的完整筛选条件（对应 pixiv 搜索筛选的功能子集）。
 *
 * 契约文件（Lead 维护）：UI 侧（tag-detail-ui）只依赖本文件的字段与枚举做面板状态；
 * 数据层（mirror-data）负责把本对象翻译成跨书架 SQL，落在 [AllBookmarkRepository] 的
 * 新重载上（见文件末尾的接口说明，具体实现由 mirror-data 完成）。
 *
 * ## 与搜索筛选（SearchFilterV3）的对应关系
 * - 作品类别 → [workTypes]（**数据库 workType 原始值**：illust / manga / ugoira / novel；
 *   空 = 不限。注意与旧 API 的 `resolveWorkTypes` 不同：这里不做 illust→ugoira 的自动合并，
 *   由 UI 决定「插画、动图」这种组合时显式传 ["illust","ugoira"]）。
 * - 投稿期间 → [createdFromMs] / [createdToMs]（作品发布时间闭区间，epoch ms）。
 * - 喜欢！数 → [minBookmarks] / [maxBookmarks]（作品总收藏数闭区间；null = 该端不限）。
 * - users入り 标签 → [usersTagMin]（>0 时只保留带 `{N}users入り` 标签且 N >= 该值的作品；
 *   N 由镜像标签倒排表的 tagName 解析，大小写已归一）。
 * - 其他条件（**不含「过滤已收藏」**，本页面看的本来就是自己的收藏）→
 *   [ai] / [age] / [ratio] / [resolution] / [pages] / [minTextLength] / [maxTextLength]。
 *
 * 所有条件之间是 AND；null / ANY / 空列表均表示「不限」。
 */
data class AllBookmarkWorkFilter(
    /** 必须是作品标签（小写归一后的 tagName）；null/空 = 不按标签过滤。 */
    val tagName: String? = null,
    /** 作品内搜索词：标题 / 作者名 / 标签（含译名）/ 简介，空格分词逐词 AND。 */
    val keyword: String = "",
    /** 数据库 `workType` 原始值集合：illust / manga / ugoira / novel；空 = 不限。 */
    val workTypes: List<String> = emptyList(),
    /** 投稿期间起点（作品发布时间，epoch ms，闭区间）。 */
    val createdFromMs: Long? = null,
    /** 投稿期间终点（作品发布时间，epoch ms，闭区间）。 */
    val createdToMs: Long? = null,
    /** 喜欢！数下限（作品总收藏数，含）。 */
    val minBookmarks: Int? = null,
    /** 喜欢！数上限（作品总收藏数，含）。 */
    val maxBookmarks: Int? = null,
    /** users入り 标签下限：0 = 不限；>0 时匹配 `{N}users入り` 且 N >= 该值。 */
    val usersTagMin: Int = 0,
    /** AI 作品三档。 */
    val ai: AllBookmarkAiFilter = AllBookmarkAiFilter.ANY,
    /** R-18 限制三档。 */
    val age: AllBookmarkAgeFilter = AllBookmarkAgeFilter.ANY,
    /** 长宽比（横/纵/方）。 */
    val ratio: AllBookmarkRatioFilter = AllBookmarkRatioFilter.ANY,
    /** 分辨率（宽高同时满足档位区间）。 */
    val resolution: AllBookmarkResolutionFilter = AllBookmarkResolutionFilter.ANY,
    /** 页数（单页 / 多页）。 */
    val pages: AllBookmarkPageFilter = AllBookmarkPageFilter.ANY,
    /** 正文长度下限（仅小说有意义；插画行 textLength=0）。 */
    val minTextLength: Int? = null,
    /** 正文长度上限。 */
    val maxTextLength: Int? = null,
) {
    /** 除了「这个标签」本身，用户还加了条件吗（筛选入口的徽标计数 / 空态文案用）。 */
    val activeConditionCount: Int
        get() = listOf(
            keyword.isNotBlank(),
            workTypes.isNotEmpty(),
            createdFromMs != null || createdToMs != null,
            minBookmarks != null || maxBookmarks != null,
            usersTagMin > 0,
            ai != AllBookmarkAiFilter.ANY,
            age != AllBookmarkAgeFilter.ANY,
            ratio != AllBookmarkRatioFilter.ANY,
            resolution != AllBookmarkResolutionFilter.ANY,
            pages != AllBookmarkPageFilter.ANY,
            minTextLength != null || maxTextLength != null,
        ).count { it }

    val hasAnyCondition: Boolean get() = activeConditionCount > 0
}

/** AI 作品筛选：与 `BookmarkMirrorEntity.aiType`（0=未知 1=否 2=是）对应。 */
enum class AllBookmarkAiFilter {
    ANY,
    /** 不显示 AI 作品（aiType != 2）。 */
    EXCLUDE_AI,
    /** 仅看 AI 作品（aiType == 2）。 */
    ONLY_AI,
}

/** R-18 筛选：与 `BookmarkMirrorEntity.xRestrict`（0=全年龄 1=R-18 2=R-18G）对应。 */
enum class AllBookmarkAgeFilter {
    ANY,
    /** 仅安全（xRestrict == 0）。 */
    SAFE_ONLY,
    /** 仅 R-18（xRestrict > 0，含 R-18G）。 */
    R18_ONLY,
}

/** 长宽比筛选：与 `BookmarkMirrorEntity.orientation`（1=横 2=竖 3=方）对应。 */
enum class AllBookmarkRatioFilter {
    ANY,
    LANDSCAPE,
    PORTRAIT,
    SQUARE,
}

/**
 * 分辨率筛选：宽、高两个维度同时落在同一档位内（闭区间）。
 * 阈值对齐 pixiv 搜索「分辨率」档位：≥3000 / 1000~2999 / ≤999。
 */
enum class AllBookmarkResolutionFilter(
    val widthMin: Int?,
    val widthMax: Int?,
    val heightMin: Int?,
    val heightMax: Int?,
) {
    ANY(null, null, null, null),
    ABOVE_3000(3000, null, 3000, null),
    BETWEEN_1000_AND_2999(1000, 2999, 1000, 2999),
    BELOW_1000(null, 999, null, 999),
}

/** 页数筛选：与 `BookmarkMirrorEntity.pageCount`（小说恒 1）对应。 */
enum class AllBookmarkPageFilter {
    ANY,
    SINGLE_PAGE,
    MULTI_PAGE,
}

// ─────────────────────────────────────────────────────────────────────────────
// 数据层需要新增的仓库重载（mirror-data 实现；签名以此为准，旧重载保留兼容）：
//
//   suspend fun loadWorks(
//       uid: Long,
//       includePrivate: Boolean,
//       filter: AllBookmarkWorkFilter,
//       sort: AllBookmarkSort,
//       limit: Int,
//       offset: Int,
//   ): List<BookmarkMirrorEntity>
//
//   suspend fun countWorks(
//       uid: Long,
//       includePrivate: Boolean,
//       filter: AllBookmarkWorkFilter,
//   ): Int
//
// SQL 落点（镜像表列名）：
//   workTypes   → workType IN (?,…)
//   created*    → createDateMs >= / <=
//   bookmarks   → totalBookmarks >= / <=
//   usersTagMin → EXISTS(SELECT 1 FROM bookmark_mirror_tag_table t
//                        WHERE t.shelfKey = <shelf> AND t.targetId = <row>
//                          AND t.tagName LIKE '%users入り'
//                          AND CAST(REPLACE(t.tagName,'users入り','') AS INTEGER) >= ?)
//   ai          → aiType = 2 / aiType != 2
//   age         → xRestrict = 0 / xRestrict > 0
//   ratio       → orientation = 1 / 2 / 3
//   resolution  → width/height 的 min/max 条件（任一阈值 null 表示该端不限）
//   pages       → pageCount <= 1 / pageCount > 1
//   textLength  → textLength >= / <=
//
// 注意：tagName 过滤沿用现有相关 EXISTS 语义（按 (shelfKey,targetId) 成对命中）；
// 所有排序仍走现有 [AllBookmarkSort] 的「targetId DESC + shelfKey ASC」全序兜底。
// ─────────────────────────────────────────────────────────────────────────────
