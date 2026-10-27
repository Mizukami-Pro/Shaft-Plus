package ceui.pixiv.db.mirror

import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import ceui.loxia.Novel
import ceui.pixiv.api.model.Illust
import ceui.pixiv.services.appServices
import ceui.pixiv.session.SessionManager
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 「全收藏分类查看」的数据门面：跨书架（插画公开/悄悄 + 小说公开/悄悄）聚合镜像表。
 *
 * 归属人：mirror-data 队友。UI 层（tag-overview / tag-detail / poster）只依赖本文件里
 * 声明的公开 API，不直接拼 SQL。
 *
 * 书架集合由 [shelvesFor] 决定；所有查询都以「书架 key 列表」为范围，因此天然支持
 * 「包含私密收藏」开关。排序跨书架时不能再用 bookmarkSeq（每个书架各自编号），
 * 所以默认按作品发布时间、其余按作品维度指标（见 [AllBookmarkSort]）。
 *
 * ## 演示数据模式
 *
 * 每个读取入口开头都有一段 `AllBookmarkDemoData.enabled` 分支：命中时直接返回
 * [AllBookmarkDemoData] 的内存假数据，不查库、不碰网络。这是给没有 pixiv 账号的
 * 模拟器验收截图用的，只在 debug 包由入口 Intent 打开（细节见 [AllBookmarkDemoData]）。
 * 生产路径一行不受影响。
 */
enum class AllBookmarkSort {
    /** 作品发布时间：新 → 旧。默认。 */
    NEWEST_PUBLISHED,

    /** 作品发布时间：旧 → 新。 */
    OLDEST_PUBLISHED,

    /** 作品总收藏数：多 → 少。 */
    MOST_BOOKMARKED,

    /** 作品浏览量（热度）：多 → 少。 */
    MOST_VIEWED,

    /** 趋势：单位时间（天）新增收藏数 = totalBookmarks / 距今天数，高 → 低。 */
    TRENDING,
}

/** 页面顶部的汇总统计（跨书架）。 */
data class AllBookmarkStats(
    /** 已镜像的作品总数。 */
    val totalWorks: Int = 0,
    val illustCount: Int = 0,
    val mangaCount: Int = 0,
    val novelCount: Int = 0,
    /** 去重后的标签个数。 */
    val distinctTagCount: Int = 0,
)

/** 标签云里的一项：标签 + 命中作品数（跨书架去重计数）。 */
data class AllBookmarkTag(
    /** 小写归一的标签名，查询与去重都按它。 */
    val tagName: String,
    /** 原始大小写的展示名。 */
    val displayName: String,
    /** pixiv 译名，没有则空串。 */
    val translatedName: String,
    /** 收藏作品里带这个标签的作品数（插画 + 漫画 + 小说一起算）。 */
    val count: Int,
)

/**
 * 全收藏分类的数据入口。
 *
 * 所有方法 main-safe（内部切 IO）。不要在主线程直接查库。
 */
object AllBookmarkRepository {

    private const val TAG = "AllBookmark"

    /**
     * UI 侧可传的作品类型 → 数据库 `workType` 值。
     *
     * 数据库里实际出现的值是 `illust` / `manga` / `ugoira` / `novel`（见
     * [BookmarkMirrorMapper]），而页面只给用户「插画 / 漫画 / 小说」三档，所以这里做一次映射：
     *
     * - `illust`（插画）→ `illust` + `ugoira`：动图本质就是动态插画，用户不会把它理解成
     *   第四类；顶部统计的 illustCount 也是同一口径，标签/筛选/列表三处数字才对得上。
     * - `manga` / `novel` 一对一。
     * - 也接受 `ugoira` 单列（内部/调试可用）与中文别名，大小写不敏感；
     * - 不认识的值**原样下推**（而不是忽略）：忽略等于静默把筛选放宽成「全部」，
     *   那会让一个拼错的值看起来「能用但没效果」，比返回 0 条更难排查。
     *
     * 空列表 = 不限（返回空列表）。
     */
    @JvmStatic
    fun resolveWorkTypes(workTypes: List<String>): List<String> {
        if (workTypes.isEmpty()) return emptyList()
        val resolved = LinkedHashSet<String>()
        workTypes.forEach { raw ->
            when (val value = raw.trim().lowercase(Locale.ROOT)) {
                // 「全部」的显式写法：等价于不加类型条件
                "", "all", "全部" -> Unit
                "illust", "插画", "イラスト" -> {
                    resolved += "illust"
                    resolved += "ugoira"
                }
                "ugoira", "动图", "动画" -> resolved += "ugoira"
                "manga", "漫画", "マンガ" -> resolved += "manga"
                "novel", "小说", "小説" -> resolved += "novel"
                else -> resolved += value
            }
        }
        return resolved.toList()
    }

    /** 参与「全收藏」统计的书架。includePrivate=false 时只含公开书架。 */
    @JvmStatic
    fun shelvesFor(uid: Long, includePrivate: Boolean): List<BookmarkShelf> {
        // 演示模式没有真实书架（也不能让引擎去注册一个不存在的账号），安全空转。
        if (AllBookmarkDemoData.enabled) return emptyList()
        if (uid <= 0L) return emptyList()
        val restrict = if (includePrivate) MirrorRestrict.entries else listOf(MirrorRestrict.PUBLIC)
        val types = listOf(MirrorContentType.ILLUST, MirrorContentType.NOVEL)
        return types.flatMap { type -> restrict.map { BookmarkShelf(uid, type, it) } }
    }

    /** 当前登录账号；未登录返回 0。演示模式下返回 [AllBookmarkDemoData.DEMO_UID]。 */
    @JvmStatic
    fun currentUid(): Long =
        if (AllBookmarkDemoData.enabled) AllBookmarkDemoData.DEMO_UID else SessionManager.loggedInUid

    /**
     * 开启这些书架的镜像（首次访问即开始后台回填；已注册的书架只会排一次增量维护）。
     * 这是「自动获取全部收藏」的触发点。
     */
    @JvmStatic
    fun ensureShelves(uid: Long, includePrivate: Boolean, reason: String) {
        if (AllBookmarkDemoData.enabled) return
        if (uid <= 0L) return
        val service = Shaft.getContext().appServices().bookmarkMirror
        shelvesFor(uid, includePrivate).forEach { shelf ->
            service.ensureShelf(shelf, reason)
        }
    }

    /** 用户主动刷新：立刻对一次服务端表头，不受增量节流限制。 */
    @JvmStatic
    fun syncNow(uid: Long, includePrivate: Boolean, reason: String) {
        if (AllBookmarkDemoData.enabled) return
        if (uid <= 0L) return
        val service = Shaft.getContext().appServices().bookmarkMirror
        shelvesFor(uid, includePrivate).forEach { shelf ->
            service.syncNow(shelf, reason)
        }
    }

    /** 跨书架统计。 */
    suspend fun loadStats(uid: Long, includePrivate: Boolean): AllBookmarkStats =
        withContext(Dispatchers.IO) {
            if (AllBookmarkDemoData.enabled) return@withContext AllBookmarkDemoData.stats(includePrivate)
            val shelfKeys = shelfKeysFor(uid, includePrivate)
            if (shelfKeys.isEmpty()) return@withContext AllBookmarkStats()
            val row = dao.rawAllStats(AllBookmarkMirrorQuery.stats(shelfKeys))
            AllBookmarkStats(
                totalWorks = row.total,
                illustCount = row.illustCount,
                mangaCount = row.mangaCount,
                novelCount = row.novelCount,
                distinctTagCount = row.distinctTagCount,
            )
        }

    /**
     * 标签云：全部标签按 count 从多到少排序（并列按 tagName 升序，顺序稳定）。
     *
     * 设计上刻意「全量返回 + UI 本地过滤」也成立（[limit]=0 时一次给完）：标签总量是
     * 几千到几万条时，一次查询 + 内存 contains 比每次输入都查一次库更跟手；而 SQL 侧的
     * [query] 过滤同时保留，供不想要全量的调用方使用。
     *
     * @param query 非空时按 displayName / translatedName 模糊过滤（大小写不敏感）。
     * @param limit <=0 表示不限制（页面需要全量标签做本地搜索 / 海报取 TOP N）。
     */
    suspend fun loadTags(
        uid: Long,
        includePrivate: Boolean,
        query: String = "",
        limit: Int = 0,
    ): List<AllBookmarkTag> = withContext(Dispatchers.IO) {
        if (AllBookmarkDemoData.enabled) {
            return@withContext AllBookmarkDemoData.tags(includePrivate, query, limit)
        }
        val shelfKeys = shelfKeysFor(uid, includePrivate)
        if (shelfKeys.isEmpty()) return@withContext emptyList()
        dao.rawAllTagFacets(AllBookmarkMirrorQuery.tagFacets(shelfKeys, query, limit)).map { facet ->
            AllBookmarkTag(
                tagName = facet.tagName,
                displayName = facet.displayName,
                translatedName = facet.translatedName,
                count = facet.hitCount,
            )
        }
    }

    /**
     * 某个标签下的作品分页（跨书架）。返回原始镜像行，UI 侧自己解析 Illust/Novel。
     *
     * **兼容入口**：内部转成 [AllBookmarkWorkFilter] 后委托给搜索筛选版重载，行为与历史版本
     * 完全一致（`workTypes` 仍走 [resolveWorkTypes] 的「illust 含 ugoira」合并；
     * 需要面板那种“原始 DB 值、不做合并”的语义时请直接用新重载）。
     *
     * @param tagName 标签名，**大小写不敏感**（内部按标签表口径小写归一）。传入
     *   [AllBookmarkTag.displayName]（原始大小写）或 [AllBookmarkTag.tagName] 都可以，
     *   详情页从 Intent 里拿到的是展示名，不该逼它自己再归一一次。
     * @param keyword 作品内搜索（标题/作者/标签/简介全文，空格分词 AND）。
     * @param workTypes `illust` / `manga` / `novel` 的子集；空 = 不限。`illust` 含 ugoira
     *   （映射见 [resolveWorkTypes]）。
     * @param limit 每页条数，<=0 直接返回空（避免拼出 `LIMIT 0` 这种必然空结果）。
     */
    suspend fun loadWorks(
        uid: Long,
        includePrivate: Boolean,
        tagName: String?,
        keyword: String,
        workTypes: List<String>,
        sort: AllBookmarkSort,
        limit: Int,
        offset: Int,
    ): List<BookmarkMirrorEntity> = loadWorks(
        uid = uid,
        includePrivate = includePrivate,
        filter = AllBookmarkWorkFilter(
            tagName = tagName,
            keyword = keyword,
            // 旧 API 的「插画含 ugoira」合并语义保留在这一层，旧调用点零改动。
            workTypes = resolveWorkTypes(workTypes),
        ),
        sort = sort,
        limit = limit,
        offset = offset,
    )

    /**
     * 某个标签 / 筛选条件下的作品分页（跨书架，搜索筛选级别）。返回原始镜像行，UI 侧自己解析。
     *
     * [AllBookmarkWorkFilter] 的维度语义（含 workTypes **不做** illust→ugoira 合并、
     * users入り / AI / R-18 / 长宽比 / 分辨率 / 页数 / 正文长度）见契约文件与本类上方 KDoc；
     * SQL 落点见 [AllBookmarkMirrorQuery.count] 的同一套 WHERE。排序/分页与旧重载完全一致：
     * 排序键之后 `targetId DESC, shelfKey ASC` 全序兜底，LIMIT/OFFSET 翻页不重不漏。
     *
     * @param tagName 大小写不敏感（内部小写归一）；null/空 = 不按标签过滤。
     * @param limit 每页条数，<=0 直接返回空。
     */
    suspend fun loadWorks(
        uid: Long,
        includePrivate: Boolean,
        filter: AllBookmarkWorkFilter,
        sort: AllBookmarkSort,
        limit: Int,
        offset: Int,
    ): List<BookmarkMirrorEntity> = withContext(Dispatchers.IO) {
        val normalized = normalizedFilter(filter)
        if (AllBookmarkDemoData.enabled) {
            return@withContext AllBookmarkDemoData.works(normalized, sort, limit, offset)
        }
        val shelfKeys = shelfKeysFor(uid, includePrivate)
        if (shelfKeys.isEmpty() || limit <= 0) return@withContext emptyList()
        val rows = dao.rawAllRows(
            AllBookmarkMirrorQuery.rows(normalized, shelfKeys, sort, limit, offset.coerceAtLeast(0)),
        )
        Timber.tag(TAG).d(
            "跨书架筛选查询 %d 架 sort=%s 条件数=%d tag=%s types=%s → %d 行",
            shelfKeys.size, sort, normalized.activeConditionCount,
            normalized.tagName, normalized.workTypes, rows.size,
        )
        rows
    }

    /** 与旧版 [loadWorks] 相同条件下的命中总数（兼容入口，委托给筛选版重载）。 */
    suspend fun countWorks(
        uid: Long,
        includePrivate: Boolean,
        tagName: String?,
        keyword: String,
        workTypes: List<String>,
    ): Int = countWorks(
        uid = uid,
        includePrivate = includePrivate,
        filter = AllBookmarkWorkFilter(
            tagName = tagName,
            keyword = keyword,
            workTypes = resolveWorkTypes(workTypes),
        ),
    )

    /** 与搜索筛选版 [loadWorks] 相同条件下的命中总数（「共 N 件」与列表同口径）。 */
    suspend fun countWorks(
        uid: Long,
        includePrivate: Boolean,
        filter: AllBookmarkWorkFilter,
    ): Int = withContext(Dispatchers.IO) {
        val normalized = normalizedFilter(filter)
        if (AllBookmarkDemoData.enabled) {
            return@withContext AllBookmarkDemoData.countWorks(normalized)
        }
        val shelfKeys = shelfKeysFor(uid, includePrivate)
        if (shelfKeys.isEmpty()) return@withContext 0
        dao.rawAllCount(AllBookmarkMirrorQuery.count(normalized, shelfKeys))
    }

    /** 镜像行 → Illust（坏行返回 null）。 */
    fun toIllust(row: BookmarkMirrorEntity): Illust? = runCatching {
        Shaft.sGson.fromJson(row.payloadJson, Illust::class.java)
    }.onFailure {
        Timber.tag(TAG).w(it, "解析 Illust payload 失败 targetId=%d", row.targetId)
    }.getOrNull()

    /** 镜像行 → Novel（坏行返回 null）。 */
    fun toNovel(row: BookmarkMirrorEntity): Novel? = runCatching {
        Shaft.sGson.fromJson(row.payloadJson, Novel::class.java)
    }.onFailure {
        Timber.tag(TAG).w(it, "解析 Novel payload 失败 targetId=%d", row.targetId)
    }.getOrNull()

    // ─────────────────────────── 内部 ───────────────────────────

    private val dao: BookmarkMirrorDao
        get() = AppDatabase.getAppDatabase(Shaft.getContext()).bookmarkMirrorDao()

    private fun shelfKeysFor(uid: Long, includePrivate: Boolean): List<String> =
        shelvesFor(uid, includePrivate).map { it.key }

    /**
     * 统一入口的 tagName 归一：标签表的 `tagName` 是小写口径，而详情页从 Intent 里拿到的是
     * 展示名；这里做一次小写/trim，UI 不必自己归一（幂等，重复调用无副作用）。
     */
    private fun normalizedFilter(filter: AllBookmarkWorkFilter): AllBookmarkWorkFilter =
        filter.copy(
            tagName = filter.tagName?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() },
        )
}
