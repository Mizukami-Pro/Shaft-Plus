package ceui.pixiv.db.mirror

import android.content.Intent
import ceui.lisa.BuildConfig
import ceui.loxia.ImageUrls
import ceui.loxia.Novel
import ceui.loxia.Tag
import ceui.loxia.User
import ceui.pixiv.api.model.Illust
import com.google.gson.Gson
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 「全收藏分类查看」的**演示数据**：没有 pixiv 账号的模拟器/隔离环境里，用一份内存假数据
 * 把总览页、标签详情页、海报真正跑起来，方便验收截图。
 *
 * ## 边界（为什么必须记住这几个约束）
 *
 * - **仅 debug 生效**：[enabled] 只能由 [enableFromIntent] 打开，而它 `&& BuildConfig.DEBUG`；
 *   release 包即使收到 `all_bookmark_demo=true` 也永远停在 false。
 * - **不碰网络、不碰镜像库**：[AllBookmarkRepository] 在 demo 模式下所有读取都直接返回本文件
 *   生成的数据；`ensureShelves/syncNow/shelvesFor` 安全空转 —— 演示账号在 pixiv 上不存在，
 *   真去注册书架只会让限速引擎白打请求。
 * - **进程内开关**：`enabled` 是 `@Volatile` 静态开关，由入口路由（TemplateRouteFactory）在
 *   打开页面时调用 [enableFromIntent] 决定；同一进程后续进入的页面沿用该值。
 *
 * ## 数据长什么样
 *
 * - 126 件作品 = 插画 78 + 漫画 21 + 小说 27（对齐 [stats] 的固定口径）；
 * - 34 个标签，覆盖「初音ミク / オリジナル / 女の子 / 風景 / 東方Project / CLIP STUDIO PAINT /
 *   百合 / 猫 / 水着 / 制服 / ファンタジー / 落書き」等常见分类；
 * - 搜索筛选维度也有数据可筛：每 3 件一件带 `{N}users入り`（N=1000/5000/10000）、约 1/7 标记为
 *   AI 作品，宽高/页数/正文长度各不相同（R-18 维度支持筛选但不造限制级数据）；
 * - payloadJson 用**真实模型**（[Illust] / [Novel]）经 Gson 序列化，所以 UI 侧
 *   `AllBookmarkRepository.toIllust/toNovel` 解析出来就是可渲染的卡片（封面走
 *   picsum.photos 的随机图，不依赖 pixiv 的 Referer 防盗链）；
 * - 排序/筛选全部在内存里做（数据量小），语义与 SQL 版一致：五种排序都带
 *   `targetId DESC, shelfKey ASC` 兜底，保证分页不重不漏。
 *
 * ## 怎么触发（debug 包）
 *
 * ```bash
 * adb shell am start -n ceui.pixiv.cshaft/ceui.lisa.activities.TemplateActivity \
 *   -e dataType "全收藏分类查看" --ez all_bookmark_demo true
 * ```
 *
 * 之后侧边栏进入「全收藏分类查看」也会沿用 demo 数据（同一进程内开关保持，详情页/海报都有效；
 * 杀进程即复位）。演示只影响「全收藏分类」这套读取入口，普通页面不受影响。
 */
object AllBookmarkDemoData {

    /** 打开演示模式的 Intent extra（boolean 用 `--ez`，也兼容字符串 true/1）。 */
    const val EXTRA_ALL_BOOKMARK_DEMO = "all_bookmark_demo"

    /** 演示账号 uid：不在 pixiv 上存在，只用于让各页面认为「已登录」。 */
    const val DEMO_UID = 990000001L

    private const val ILLUST_COUNT = 78
    private const val MANGA_COUNT = 21
    private const val NOVEL_COUNT = 27
    private const val TOTAL_COUNT = ILLUST_COUNT + MANGA_COUNT + NOVEL_COUNT

    private const val DAY_MS = 86_400_000L

    /** 演示作品的时间锚点：2023-07-22T16:26:40+09:00 附近，往后每件作品早 4 天。 */
    private const val BASE_CREATE_MS = 1_690_000_000_000L

    private const val DEMO_ILLUST_SHELF = "0:0:990000001"
    private const val DEMO_NOVEL_SHELF = "1:0:990000001"

    private val gson = Gson()

    /** 演示模式开关。默认关；只有 [enableFromIntent] 能在 debug 包把它打开。 */
    @Volatile
    var enabled: Boolean = false

    /**
     * 从页面入口的 Intent 里读取演示开关。路由在打开页面时调用：
     * 带 `all_bookmark_demo=true` 就把开关**打开**（debug 包内），没带则保持现状。
     *
     * ## 为什么是「只开不关」
     *
     * 总览页点进「标签详情」是一个**新 Activity + 新 Intent**，那条 Intent 里没有演示 extra
     * （`TagBrowseContracts.openTagDetail` 只带标签信息）。如果这里每次都按「本次 Intent 有没有
     * extra」覆盖开关，详情页一打开就会把演示模式关掉，列表直接变空态 —— 验收必挂。
     * 所以语义定为进程级：打开过一次就保持到进程结束（重启 app 自动复位）。演示数据只影响
     * 「全收藏分类」这套读取入口，普通页面完全不受影响，保持着的唯一代价是 debug 包里这个
     * 页面一直显示假数据。
     *
     * release 包永远无效：`if (!BuildConfig.DEBUG) return`。
     */
    @JvmStatic
    fun enableFromIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        if (readsDemoFlag(intent)) enabled = true
    }

    private fun readsDemoFlag(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.getBooleanExtra(EXTRA_ALL_BOOKMARK_DEMO, false)) return true
        @Suppress("DEPRECATION")
        val raw = intent.extras?.get(EXTRA_ALL_BOOKMARK_DEMO)
        return when (raw) {
            is Boolean -> raw
            is String -> raw.trim().equals("true", ignoreCase = true) || raw.trim() == "1"
            else -> false
        }
    }

    /**
     * 演示数据对应的「作品模板」：主表行 + 用于内存过滤的标签名/全文检索串。
     * UI 只拿到 [entity]（与真镜像行完全同构），所以页面代码不需要为 demo 写分支。
     */
    private class DemoWork(
        val entity: BookmarkMirrorEntity,
        /** 小写归一的**全部**标签名（含 users入り），供 tagName 过滤用。 */
        val tagNames: List<String>,
        /** 只含标签云池子里的标签；users入り 这类不参与标签云（对齐 pixiv 的标签云口径）。 */
        val cloudTagNames: List<String>,
        /** 作品带的 `{N}users入り` 的 N 列表，供 usersTagMin 过滤。 */
        val usersTags: List<Int>,
        /** 小写归一的「标题 + 作者 + 标签原名 + 译名」，供 keyword 过滤用。 */
        val searchable: String,
    )

    /** 一个演示标签：展示名 + 译名。 */
    private class DemoTags(val display: String, val translated: String)

    /**
     * 34 个演示标签。前 12 个是验收清单里点名的分类，后面补齐到 34 个让标签云有真实密度。
     * 顺序即「初始权重」：生成作品时前面的标签会被额外多带一次，标签云顶部就是它们。
     */
    private val TAG_POOL: List<DemoTags> = listOf(
        DemoTags("初音ミク", "初音未来"),
        DemoTags("オリジナル", "原创"),
        DemoTags("女の子", "女孩子"),
        DemoTags("風景", "风景"),
        DemoTags("東方Project", "东方Project"),
        DemoTags("CLIP STUDIO PAINT", "CLIP STUDIO PAINT"),
        DemoTags("百合", "百合"),
        DemoTags("猫", "猫"),
        DemoTags("水着", "泳装"),
        DemoTags("制服", "制服"),
        DemoTags("ファンタジー", "奇幻"),
        DemoTags("落書き", "涂鸦"),
        DemoTags("イラスト", "插画"),
        DemoTags("漫画", "漫画"),
        DemoTags("アニメ", "动画"),
        DemoTags("桜", "樱花"),
        DemoTags("空", "天空"),
        DemoTags("海", "海洋"),
        DemoTags("花", "花"),
        DemoTags("可愛い", "可爱"),
        DemoTags("男の子", "男孩子"),
        DemoTags("帽子", "帽子"),
        DemoTags("眼鏡", "眼镜"),
        DemoTags("笑顔", "笑容"),
        DemoTags("背景", "背景"),
        DemoTags("和服", "和服"),
        DemoTags("花火", "烟花"),
        DemoTags("星空", "星空"),
        DemoTags("雨", "雨"),
        DemoTags("森", "森林"),
        DemoTags("街", "街道"),
        DemoTags("光", "光"),
        DemoTags("影", "影"),
        DemoTags("練習", "练习"),
    )

    private val AUTHORS = listOf(
        "星野ミナ", "月見ソラ", "雪村アオ", "花園リリ",
        "風間ユウ", "海原ナミ", "森野コハク", "夜桜シオン",
        "朝比奈ヒカリ", "白鳥ツバサ", "綾瀬カナデ", "神楽ミコト",
    )

    /** 演示作品混入的 `{N}users入り` 阈值：让「users入り」筛选档在模拟器上也能验证。 */
    private val USERS_TAG_THRESHOLDS = listOf(1_000, 5_000, 10_000)

    /** 全部演示作品（懒生成一次）。payloadJson 由真实模型序列化，UI 可直接反序列化渲染。 */
    private val allWorks: List<DemoWork> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { buildWorks() }

    // ─────────────────────────── 对外数据出口 ───────────────────────────

    /**
     * 顶部统计。[includePrivate] 在演示数据里没有意义（演示集市书全部是公开书架，
     * 不模拟悄悄收藏），保留参数只为与 [AllBookmarkRepository.loadStats] 签名对齐。
     */
    fun stats(@Suppress("UNUSED_PARAMETER") includePrivate: Boolean): AllBookmarkStats {
        val works = allWorks
        return AllBookmarkStats(
            totalWorks = works.size,
            illustCount = works.count { it.entity.workType == "illust" || it.entity.workType == "ugoira" },
            mangaCount = works.count { it.entity.workType == "manga" },
            novelCount = works.count { it.entity.workType == "novel" },
            distinctTagCount = works.flatMap { it.cloudTagNames }.distinct().size,
        )
    }

    /**
     * 标签云：从演示作品反推每个标签的作品数（跨作品去重），命中数降序、并列按 tagName 升序，
     * 与 SQL 版 [AllBookmarkMirrorQuery.tagFacets] 的排序口径一致。
     *
     * @param query 非空时按 displayName / translatedName 模糊过滤（大小写不敏感），供页面搜索标签。
     * @param limit <=0 表示不限制。
     */
    fun tags(includePrivate: Boolean, query: String = "", limit: Int = 0): List<AllBookmarkTag> {
        if (allWorks.isEmpty()) return emptyList()
        val counts = LinkedHashMap<String, Int>()
        allWorks.forEach { work ->
            work.cloudTagNames.distinct().forEach { name -> counts[name] = (counts[name] ?: 0) + 1 }
        }
        val needle = query.trim().lowercase(Locale.ROOT)
        val result = counts.entries.mapNotNull { (tagName, count) ->
            val def = TAG_POOL.firstOrNull { it.display.lowercase(Locale.ROOT) == tagName } ?: return@mapNotNull null
            AllBookmarkTag(
                tagName = tagName,
                displayName = def.display,
                translatedName = def.translated,
                count = count,
            )
        }.filter { tag ->
            needle.isEmpty() ||
                tag.displayName.lowercase(Locale.ROOT).contains(needle) ||
                tag.tagName.lowercase(Locale.ROOT).contains(needle) ||
                tag.translatedName.lowercase(Locale.ROOT).contains(needle)
        }.sortedWith(compareByDescending<AllBookmarkTag> { it.count }.thenBy { it.tagName })
        // includePrivate 只影响真实书架；演示数据没有私密作品，保留参数仅为签名对齐。
        return if (limit > 0) result.take(limit) else result
    }

    /**
     * 跨书架作品列表的演示版（**旧入口**：tag / keyword / workTypes）。
     *
     * 兼容保留：内部转成 [AllBookmarkWorkFilter] 委托给筛选版重载。注意旧入口的 `workTypes`
     * 是 `AllBookmarkRepository.resolveWorkTypes` 已有的“插画含 ugoira”口径（调用方传进来
     * 之前就已合并），筛选版按原始 DB 值匹配，所以旧行为逐条不变。
     */
    fun works(
        tagName: String?,
        keyword: String,
        workTypes: List<String>,
        sort: AllBookmarkSort,
        limit: Int,
        offset: Int,
    ): List<BookmarkMirrorEntity> = works(
        filter = AllBookmarkWorkFilter(tagName = tagName, keyword = keyword, workTypes = workTypes),
        sort = sort,
        limit = limit,
        offset = offset,
    )

    /**
     * 跨书架作品列表的演示版（**搜索筛选版**）：内存过滤 [AllBookmarkWorkFilter] 的全部维度
     * → 五种排序 → LIMIT/OFFSET。
     *
     * 排序兜底与 SQL 版一致：主排序键相同时按 `targetId DESC, shelfKey ASC`，因此分页是
     * 严格全序，翻页不会重复或漏条目。各维度语义见 [matchesFilter]。
     */
    fun works(
        filter: AllBookmarkWorkFilter,
        sort: AllBookmarkSort,
        limit: Int,
        offset: Int,
    ): List<BookmarkMirrorEntity> {
        if (limit <= 0) return emptyList()
        return filtered(filter)
            .sortedWith(comparatorOf(sort))
            .drop(offset.coerceAtLeast(0))
            .take(limit)
            .map { it.entity }
    }

    /** 与 [works]（旧入口）相同条件下的命中总数。 */
    fun countWorks(tagName: String?, keyword: String, workTypes: List<String>): Int =
        countWorks(AllBookmarkWorkFilter(tagName = tagName, keyword = keyword, workTypes = workTypes))

    /** 与 [works]（搜索筛选版）相同条件下的命中总数。 */
    fun countWorks(filter: AllBookmarkWorkFilter): Int = filtered(filter).size

    // ─────────────────────────── 内存过滤/排序 ───────────────────────────

    /**
     * 演示数据的筛选管道：逐维度对齐 SQL 版 [AllBookmarkMirrorQuery] 的 WHERE 语义。
     *
     * - 标签/keyword 复用生产口径：tagName 小写归一、关键词走 [MirrorKeywordSql] 分词 AND；
     * - workTypes 按**数据库原始值**比较（不做 illust→ugoira 合并，与筛选版 SQL 一致）；
     * - 投稿期间与 SQL 版一样先要求 `createDateMs > 0`；
     * - users入り 按 `{N}users入り` 的 N 下限过滤；
     * - 分辨率是宽高同时落在枚举给出的区间里（闭区间，null 端不限）。
     */
    private fun filtered(filter: AllBookmarkWorkFilter): List<DemoWork> {
        val tag = filter.tagName?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        val types = filter.workTypes.toSet()
        val terms = MirrorKeywordSql.tokenize(filter.keyword)
        return allWorks.filter { work ->
            (tag == null || work.tagNames.contains(tag)) &&
                (terms.isEmpty() || terms.all { term ->
                    val numericId = term.toLongOrNull()?.takeIf { it > 0L }
                    work.searchable.contains(term) ||
                        (numericId != null &&
                            (work.entity.targetId == numericId || work.entity.authorId == numericId))
                }) &&
                (types.isEmpty() || work.entity.workType in types) &&
                matchesFilter(work, filter)
        }
    }

    /** 作品维度条件（不含标签/keyword/workTypes，那三项由 [filtered] 先过）。 */
    private fun matchesFilter(work: DemoWork, filter: AllBookmarkWorkFilter): Boolean {
        val row = work.entity
        if (filter.createdFromMs != null || filter.createdToMs != null) {
            // 与 SQL 版一致：发布时间未知（0）的行不属于任何投稿期间。
            if (row.createDateMs <= 0L) return false
            if (filter.createdFromMs != null && row.createDateMs < filter.createdFromMs) return false
            if (filter.createdToMs != null && row.createDateMs > filter.createdToMs) return false
        }
        if (filter.minBookmarks != null && row.totalBookmarks < filter.minBookmarks) return false
        if (filter.maxBookmarks != null && row.totalBookmarks > filter.maxBookmarks) return false
        if (filter.usersTagMin > 0 && work.usersTags.none { it >= filter.usersTagMin }) return false
        when (filter.ai) {
            AllBookmarkAiFilter.ANY -> Unit
            AllBookmarkAiFilter.ONLY_AI -> if (row.aiType != 2) return false
            AllBookmarkAiFilter.EXCLUDE_AI -> if (row.aiType == 2) return false
        }
        when (filter.age) {
            AllBookmarkAgeFilter.ANY -> Unit
            AllBookmarkAgeFilter.SAFE_ONLY -> if (row.xRestrict != 0) return false
            AllBookmarkAgeFilter.R18_ONLY -> if (row.xRestrict <= 0) return false
        }
        val expectedOrientation = when (filter.ratio) {
            AllBookmarkRatioFilter.ANY -> null
            AllBookmarkRatioFilter.LANDSCAPE -> BookmarkMirrorMapper.ORIENTATION_LANDSCAPE
            AllBookmarkRatioFilter.PORTRAIT -> BookmarkMirrorMapper.ORIENTATION_PORTRAIT
            AllBookmarkRatioFilter.SQUARE -> BookmarkMirrorMapper.ORIENTATION_SQUARE
        }
        if (expectedOrientation != null && row.orientation != expectedOrientation) return false
        val resolution = filter.resolution
        if (resolution != AllBookmarkResolutionFilter.ANY && (row.width <= 0 || row.height <= 0)) return false
        if (resolution.widthMin != null && row.width < resolution.widthMin) return false
        if (resolution.widthMax != null && row.width > resolution.widthMax) return false
        if (resolution.heightMin != null && row.height < resolution.heightMin) return false
        if (resolution.heightMax != null && row.height > resolution.heightMax) return false
        when (filter.pages) {
            AllBookmarkPageFilter.ANY -> Unit
            AllBookmarkPageFilter.SINGLE_PAGE -> if (row.pageCount > 1) return false
            AllBookmarkPageFilter.MULTI_PAGE -> if (row.pageCount <= 1) return false
        }
        if (filter.minTextLength != null && row.textLength < filter.minTextLength) return false
        if (filter.maxTextLength != null && row.textLength > filter.maxTextLength) return false
        return true
    }

    private fun comparatorOf(sort: AllBookmarkSort): Comparator<DemoWork> {
        val primary: Comparator<DemoWork> = when (sort) {
            AllBookmarkSort.NEWEST_PUBLISHED -> compareByDescending { it.entity.createDateMs }
            AllBookmarkSort.OLDEST_PUBLISHED -> compareBy { it.entity.createDateMs }
            AllBookmarkSort.MOST_BOOKMARKED -> compareByDescending { it.entity.totalBookmarks }
            AllBookmarkSort.MOST_VIEWED -> compareByDescending { it.entity.totalView }
            AllBookmarkSort.TRENDING -> {
                // now 取一次而不是逐条取：同一次排序内所有行用同一个「现在」，比较才自洽。
                val now = System.currentTimeMillis()
                compareByDescending { trendingRate(it.entity, now) }
            }
        }
        // 与 AllBookmarkMirrorQuery.orderClause 的全序兜底一致：(shelfKey, targetId) 才是唯一键。
        return primary
            .thenByDescending { it.entity.targetId }
            .thenBy { it.entity.shelfKey }
    }

    /** 收藏速率 = 总收藏数 / max(1, 距今天数)，与 SQL 版 TRENDING 表达式同口径。 */
    private fun trendingRate(row: BookmarkMirrorEntity, now: Long): Double {
        val days = ((now - row.createDateMs) / 86_400_000.0).coerceAtLeast(1.0)
        return row.totalBookmarks / days
    }

    // ─────────────────────────── 假数据生成 ───────────────────────────

    private fun buildWorks(): List<DemoWork> {
        require(TAG_POOL.size == 34) { "演示标签池应为 34 个，实际 ${TAG_POOL.size}" }
        return (0 until TOTAL_COUNT).map { index -> buildWork(index) }
    }

    private fun buildWork(index: Int): DemoWork {
        val workType: String
        val contentType: Int
        val shelfKey: String
        when {
            index < ILLUST_COUNT -> {
                workType = "illust"
                contentType = MirrorContentType.ILLUST.code
                shelfKey = DEMO_ILLUST_SHELF
            }
            index < ILLUST_COUNT + MANGA_COUNT -> {
                workType = "manga"
                contentType = MirrorContentType.ILLUST.code
                shelfKey = DEMO_ILLUST_SHELF
            }
            else -> {
                workType = "novel"
                contentType = MirrorContentType.NOVEL.code
                shelfKey = DEMO_NOVEL_SHELF
            }
        }

        val tagIndexes = tagIndexesOf(index)
        val tags = tagIndexes.map { TAG_POOL[it] }
        // users入り：每 3 件一件，N 在 1000/5000/10000 之间轮转。只用于筛选/详情真实感，
        // 不进标签云（tagNames 里保留、cloudTagNames 不含）——对齐 pixiv 标签云的展示口径。
        val usersTags = if (index % 3 == 0) {
            listOf(USERS_TAG_THRESHOLDS[(index / 3) % USERS_TAG_THRESHOLDS.size])
        } else {
            emptyList()
        }
        val payloadTags = tags.map { Tag(name = it.display, translated_name = it.translated) } +
            usersTags.map { Tag(name = "${it}users入り") }
        // AI 作品：约 1/7 的演示作品标记为 AI 生成，让「AI 作品」筛选档在模拟器上也看得出效果。
        val aiType = if (index % 7 == 3) 2 else 0
        val targetId = 900_000L + index
        val authorIndex = index % AUTHORS.size
        val authorName = AUTHORS[authorIndex]
        val authorId = 800_000_000L + authorIndex
        val createDateMs = BASE_CREATE_MS - index * 4L * DAY_MS
        // 收藏数/浏览量用互质步长打散：五种排序看到的是明显不同的顺序，验收时一眼能分辨。
        val totalBookmarks = 120 + (index * 37 + 11) % 4_000
        val totalView = 300 + (index * 101 + 7) % 50_000
        val width = if (workType == "novel") 0 else if (index % 3 == 0) 1_600 else 900
        val height = if (workType == "novel") 0 else if (index % 4 == 0) 900 else 1_400
        val pageCount = when (workType) {
            "manga" -> 4 + index % 8
            "illust" -> 1 + index % 3
            else -> 1
        }
        val textLength = if (workType == "novel") 4_000 + (index * 7_919) % 40_000 else 0
        val title = when (workType) {
            "manga" -> "${tags.first().display} の漫画 #${index + 1}"
            "novel" -> "${tags.first().display} の物語 #${index + 1}"
            else -> "${tags.first().display} のイラスト #${index + 1}"
        }
        val payloadJson = if (workType == "novel") {
            gson.toJson(
                buildNovel(
                    index, targetId, title, authorId, authorName, payloadTags,
                    createDateMs, textLength, totalBookmarks, totalView, aiType,
                ),
            )
        } else {
            gson.toJson(
                buildIllust(
                    index, targetId, title, authorId, authorName, payloadTags, workType,
                    width, height, pageCount, createDateMs, totalBookmarks, totalView, aiType,
                ),
            )
        }
        val row = BookmarkMirrorEntity(
            shelfKey = shelfKey,
            targetId = targetId,
            ownerUid = DEMO_UID,
            contentType = contentType,
            restrictCode = MirrorRestrict.PUBLIC.code,
            // 演示里序号无意义（跨书架比较不了），给个稳定值即可。
            bookmarkSeq = -index.toLong(),
            payloadJson = payloadJson,
            title = title,
            authorId = authorId,
            authorName = authorName,
            workType = workType,
            pageCount = pageCount,
            width = width,
            height = height,
            aspectRatio = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 0f,
            orientation = orientationOf(width, height),
            totalBookmarks = totalBookmarks,
            totalView = totalView,
            textLength = textLength,
            createDateMs = createDateMs,
            aiType = aiType,
            xRestrict = 0,
            sanityLevel = 2,
            isVisible = true,
            isMuted = false,
            seriesId = 0L,
            tagCount = tags.size + usersTags.size,
            searchText = buildString {
                append(title).append(' ').append(authorName)
                tags.forEach { append(' ').append(it.display).append(' ').append(it.translated) }
                usersTags.forEach { append(' ').append(it).append("users入り") }
            }.lowercase(Locale.ROOT),
            syncedAt = System.currentTimeMillis(),
            generation = 1,
        )
        val searchable = buildString {
            append(title).append(' ').append(authorName)
            tags.forEach { append(' ').append(it.display).append(' ').append(it.translated) }
            usersTags.forEach { append(' ').append(it).append("users入り") }
        }.lowercase(Locale.ROOT)
        return DemoWork(
            entity = row,
            tagNames = tags.map { it.display.lowercase(Locale.ROOT) } +
                usersTags.map { "${it}users入り" },
            cloudTagNames = tags.map { it.display.lowercase(Locale.ROOT) },
            usersTags = usersTags,
            searchable = searchable,
        )
    }

    /**
     * 一件作品的标签下标：主标签 + 次标签（打散覆盖全部 34 个），偶数下标再补一个
     * 「池子前 8 个」里的高权重标签 —— 标签云因此有明显的高低差，顶部就是最热分类。
     */
    private fun tagIndexesOf(index: Int): List<Int> {
        val picked = LinkedHashSet<Int>()
        picked += index % TAG_POOL.size
        picked += (index * 7 + 3) % TAG_POOL.size
        if (index % 2 == 0) picked += index % 8
        return picked.toList()
    }

    private fun buildIllust(
        index: Int,
        targetId: Long,
        title: String,
        authorId: Long,
        authorName: String,
        tags: List<Tag>,
        workType: String,
        width: Int,
        height: Int,
        pageCount: Int,
        createDateMs: Long,
        totalBookmarks: Int,
        totalView: Int,
        aiType: Int,
    ): Illust = Illust(
        caption = "Shaft 演示数据（仅 debug 生效）#${index + 1}",
        create_date = isoDate(createDateMs),
        height = height,
        id = targetId,
        image_urls = coverUrls(targetId),
        is_bookmarked = true,
        illust_ai_type = aiType,
        is_muted = false,
        page_count = pageCount,
        sanity_level = 2,
        tags = tags,
        title = title,
        total_bookmarks = totalBookmarks,
        total_view = totalView,
        type = workType,
        user = demoUser(authorId, authorName),
        visible = true,
        width = width,
        x_restrict = 0,
    )

    private fun buildNovel(
        index: Int,
        targetId: Long,
        title: String,
        authorId: Long,
        authorName: String,
        tags: List<Tag>,
        createDateMs: Long,
        textLength: Int,
        totalBookmarks: Int,
        totalView: Int,
        aiType: Int,
    ): Novel {
        val cover = coverUrls(targetId)
        return Novel(
            caption = "Shaft 演示数据（仅 debug 生效）#${index + 1}",
            create_date = isoDate(createDateMs),
            id = targetId,
            image_urls = cover,
            is_bookmarked = true,
            page_count = 1,
            tags = tags,
            text_length = textLength,
            title = title,
            total_bookmarks = totalBookmarks,
            total_view = totalView,
            user = demoUser(authorId, authorName),
            visible = true,
            x_restrict = 0,
            novel_ai_type = aiType,
            coverUrl = cover.large,
            content_count = 1,
            total_character_count = textLength,
        )
    }

    private fun demoUser(authorId: Long, authorName: String): User =
        User(id = authorId, name = authorName, account = "demo_$authorId", is_followed = false)

    /**
     * 封面/插图 URL：用 picsum.photos 的固定 seed，不依赖 pixiv 的 Referer 防盗链，
     * 模拟器联网就能看到图；同一件作品各尺寸用同一 seed，视觉上一致。
     */
    private fun coverUrls(targetId: Long): ImageUrls {
        val seed = "shaft$targetId"
        val medium = "https://picsum.photos/seed/$seed/600/800"
        val large = "https://picsum.photos/seed/$seed/1200/1600"
        return ImageUrls(
            url = medium,
            large = large,
            medium = medium,
            original = large,
            square_medium = "https://picsum.photos/seed/$seed/400/400",
        )
    }

    /** epoch ms → pixiv 同款 ISO 时间串（`2023-07-22T16:26:40+09:00`，按东京时间）。 */
    private fun isoDate(epochMs: Long): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.ofHours(9)),
        )

    private fun orientationOf(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return BookmarkMirrorMapper.ORIENTATION_UNKNOWN
        val ratio = width.toFloat() / height.toFloat()
        return when {
            ratio > 1.05f -> BookmarkMirrorMapper.ORIENTATION_LANDSCAPE
            ratio < 0.95f -> BookmarkMirrorMapper.ORIENTATION_PORTRAIT
            else -> BookmarkMirrorMapper.ORIENTATION_SQUARE
        }
    }
}
