package ceui.pixiv.db.mirror

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * 「全收藏分类查看」的跨书架筛选条件。
 *
 * [BookmarkFilter] 是单书架（一个 shelfKey）的口径，本类只保留跨书架聚合真正需要的四个维度：
 * 书架范围、作品内关键词、单个标签、作品类型。收藏顺序（[BookmarkMirrorEntity.bookmarkSeq]）
 * 刻意**不出现**——每个书架各自编号，跨书架比较没有意义，见 [AllBookmarkSort] 的取舍。
 *
 * @property shelfKeys 参与聚合的书架 key（`type:restrict:uid`，见 [BookmarkShelf.key]）；
 *   空列表 = 没有任何书架，查询必然 0 行（调用方通常在 [AllBookmarkRepository] 里提前返回）。
 * @property keyword 作品内搜索词，空格分词后逐词 AND 匹配 [BookmarkMirrorEntity.searchText]
 *   （语义与单书架完全一致，共用 [MirrorKeywordSql]）。
 * @property tagName 必须命中的标签（**小写归一**后的 tagName）；null/空串 = 不限。
 * @property workTypes 数据库 `workType` 的取值集合（`illust` / `manga` / `ugoira` / `novel`），
 *   空 = 不限。UI 侧的 `illust`/`manga`/`novel` 到这些值的映射在
 *   [AllBookmarkRepository.resolveWorkTypes] 里做（ugoira 归入插画）。
 */
data class AllBookmarkFilter(
    val shelfKeys: List<String>,
    val keyword: String = "",
    val tagName: String? = null,
    val workTypes: List<String> = emptyList(),
) {
    /** 除了书架范围，用户还额外加了条件吗。 */
    val hasAnyCondition: Boolean
        get() = keyword.isNotBlank() || !tagName.isNullOrBlank() || workTypes.isNotEmpty()
}

/**
 * 跨书架 `stats` 查询的投影（[AllBookmarkMirrorQuery.stats] 的结果，一行）。
 *
 * 用单独的投影而不是直接映射 [AllBookmarkStats]：后者是给 UI 的、字段名更语义化
 * （`totalWorks`/`count`），而 RawQuery 的列名必须是 SQL 里的别名，两者解耦后
 * 改 UI 模型不会牵动 SQL。
 */
data class AllBookmarkMirrorStatsRow(
    val total: Int,
    val illustCount: Int,
    val mangaCount: Int,
    val novelCount: Int,
    val distinctTagCount: Int,
)

/**
 * [AllBookmarkFilter] + [AllBookmarkSort] → 可执行的跨书架 SQL。
 *
 * 与单书架的 [BookmarkMirrorQuery] 是**同构**的：WHERE 片段只由常量字符串拼成，用户输入
 * （书架 key、关键词、标签名、类型）一律 `?` 绑定，没有一处字符串拼接用户值。差别只有两个：
 *
 * 1. 分区条件从 `shelfKey = ?` 泛化成 `shelfKey IN (?,?,…)`，一次查 2~4 个书架；
 * 2. 作品身份从「书架内唯一」变成「`(shelfKey, targetId)` 唯一」——插画 id 与小说 id
 *    来自两套编号，可能撞号，所以标签筛选用**按行关联**的 EXISTS 而不是 `targetId IN (…)`，
 *    排序兜底也补上 `shelfKey ASC` 让全序真正唯一（否则 LIMIT/OFFSET 分页会重/漏）。
 *
 * 四个出口共用同一段 WHERE：
 * - [rows]       作品列表本体（LIMIT/OFFSET 分页）
 * - [count]      命中总数（页面上的「共 N 件」）
 * - [tagFacets]  标签云（跨书架聚合后的标签 + 去重计数）
 * - [stats]      顶部汇总（总作品数、类型分布、去重标签数）
 *
 * 另有以 [AllBookmarkWorkFilter] 为条件的 [rows]/[count] 重载：标签详情页「筛选」面板的
 * 搜索筛选级别条件。它与上面的入口共用书架范围 / 关键词 / 标签片段与同一条排序全序兜底，
 * 只额外追加作品维度条件（works 见 [appendWorkFilter]）。
 */
object AllBookmarkMirrorQuery {

    private const val ROWS = "bookmark_mirror_table"
    private const val TAGS = "bookmark_mirror_tag_table"

    /** 作品列表：跨书架行 + 排序 + LIMIT/OFFSET。 */
    fun rows(
        filter: AllBookmarkFilter,
        sort: AllBookmarkSort,
        limit: Int,
        offset: Int,
        /** [AllBookmarkSort.TRENDING] 的「现在」。允许内联进 SQL：它是本机 Long，不是用户输入。 */
        now: Long = System.currentTimeMillis(),
    ): SupportSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = buildWhere(filter, args)
        val sql = buildString {
            append("SELECT * FROM ").append(ROWS)
            append(" WHERE ").append(where)
            append(" ORDER BY ").append(orderClause(sort, now))
            append(" LIMIT ? OFFSET ?")
        }
        args += limit
        args += offset
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    /** 与 [rows] 完全相同的 WHERE 下的命中总数。 */
    fun count(filter: AllBookmarkFilter): SupportSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = buildWhere(filter, args)
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM $ROWS WHERE $where", args.toTypedArray())
    }

    // ─────────────── 搜索筛选级别（AllBookmarkWorkFilter）的跨书架查询 ───────────────
    //
    // 标签详情页的「筛选」面板对齐 pixiv 搜索筛选的功能子集（契约见 AllBookmarkWorkFilter）。
    // 与上面的 rows/count 相比，这里只多了若干**作品维度**的 AND 条件；书架范围、关键词分词、
    // 标签相关 EXISTS 与排序全序兜底全部复用同一段实现，避免两套口径漂移。

    /**
     * 作品列表（搜索筛选版）：[filter] 的每个维度都会落成 AND 条件，排序/分页与 [rows]
     * 完全一致 —— 主排序键之后仍是 `targetId DESC, shelfKey ASC` 全序兜底，LIMIT/OFFSET
     * 翻页不重不漏。
     *
     * @param shelfKeys 参与聚合的书架 key（[BookmarkShelf.key]），语义同 [AllBookmarkFilter.shelfKeys]。
     */
    fun rows(
        filter: AllBookmarkWorkFilter,
        shelfKeys: List<String>,
        sort: AllBookmarkSort,
        limit: Int,
        offset: Int,
        /** [AllBookmarkSort.TRENDING] 的「现在」。允许内联进 SQL：本机 Long，不是用户输入。 */
        now: Long = System.currentTimeMillis(),
    ): SupportSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = buildWhere(filter, shelfKeys, args)
        val sql = buildString {
            append("SELECT * FROM ").append(ROWS)
            append(" WHERE ").append(where)
            append(" ORDER BY ").append(orderClause(sort, now))
            append(" LIMIT ? OFFSET ?")
        }
        args += limit
        args += offset
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    /** 与 [rows]（搜索筛选版）完全相同的 WHERE 下的命中总数。 */
    fun count(filter: AllBookmarkWorkFilter, shelfKeys: List<String>): SupportSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = buildWhere(filter, shelfKeys, args)
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM $ROWS WHERE $where", args.toTypedArray())
    }

    /**
     * 标签云：书架范围内按 `tagName` 分组，按「内容类型 + targetId」去重计数。
     * 同一件作品在公开/悄悄两个书架里各留过一行时，前缀内容类型相同会合并；插画/小说
     * 即使 targetId 撞号，内容类型前缀不同也会分别计数。users入り 标签仅用于筛选，不进入标签云。
     *
     * [query] 非空时按 `displayName` / `translatedName` 做 LIKE 模糊过滤。两条都要：
     * 中文用户搜「初音」要能命中 `初音ミク` 的译名，搜 `miku` 要能命中原名。
     * LIKE 本身对 ASCII 大小写不敏感（SQLite 默认），CJK 没有大小写问题。
     *
     * @param limit `<= 0` 表示不限制（页面需要全量标签做本地搜索 / 海报取 TOP N）。
     */
    fun tagFacets(shelfKeys: List<String>, query: String = "", limit: Int = 0): SupportSQLiteQuery {
        val keys = shelfKeys.distinct()
        val args = mutableListOf<Any?>()
        val sql = StringBuilder()
        sql.append(
            "SELECT t.tagName AS tagName, MAX(t.displayName) AS displayName, " +
                "MAX(t.translatedName) AS translatedName, " +
                "COUNT(DISTINCT (substr(t.shelfKey, 1, instr(t.shelfKey, ':') - 1) || ':' || t.targetId)) AS hitCount " +
                "FROM $TAGS t WHERE ",
        )
        appendShelfScope(sql, args, keys, "t.shelfKey")
        sql.append(" AND t.tagName NOT LIKE '%users入り'")
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            val pattern = "%${MirrorKeywordSql.escapeLike(trimmed)}%"
            sql.append(" AND (t.displayName LIKE ? ESCAPE '\\' OR t.translatedName LIKE ? ESCAPE '\\')")
            args += pattern
            args += pattern
        }
        // 并列的标签按名字升序：分页稳定，且用户扫列表时顺序不随机跳动。
        sql.append(" GROUP BY t.tagName ORDER BY hitCount DESC, t.tagName ASC")
        if (limit > 0) {
            sql.append(" LIMIT ?")
            args += limit
        }
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    /**
     * 顶部汇总：总行数、`illust`/`manga`/`novel` 分布、去重标签数。
     *
     * 类型分桶口径与 [AllBookmarkRepository.resolveWorkTypes] 对齐：**ugoira 算插画**
     * （它本质是动态插画；UI 只给「全部/插画/漫画/小说」三档，多一档没有对应的交互）。
     * `createDateMs=0` 等失效作品照常计入总数，和列表看到的行数保持一致。
     *
     * `distinctTagCount` 不依赖主表：标签倒排表本身就是「作品 × 标签」的全集，
     * 直接 `COUNT(DISTINCT tagName)` 即可（孤儿标签由镜像引擎在删行时清掉）。
     */
    fun stats(shelfKeys: List<String>): SupportSQLiteQuery {
        val keys = shelfKeys.distinct()
        val args = mutableListOf<Any?>()
        val sql = StringBuilder()
        sql.append(
            "SELECT COUNT(*) AS total, " +
                "IFNULL(SUM(CASE WHEN workType IN ('illust','ugoira') THEN 1 ELSE 0 END), 0) AS illustCount, " +
                "IFNULL(SUM(CASE WHEN workType = 'manga' THEN 1 ELSE 0 END), 0) AS mangaCount, " +
                "IFNULL(SUM(CASE WHEN workType = 'novel' THEN 1 ELSE 0 END), 0) AS novelCount, " +
                "(SELECT COUNT(DISTINCT tagName) FROM $TAGS WHERE ",
        )
        // ⚠️ 绑定值顺序必须与 `?` 在 SQL 文本里出现的顺序一致：子查询在 SELECT 里、
        // 排在主 WHERE 之前，所以它的书架 key 要先入 args。
        appendShelfScope(sql, args, keys, "shelfKey")
        sql.append(" AND tagName NOT LIKE '%users入り'")
        sql.append(") AS distinctTagCount FROM ").append(ROWS).append(" WHERE ")
        appendShelfScope(sql, args, keys, "shelfKey")
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    // ───────────────────────────── 内部 ─────────────────────────────

    /**
     * 排序子句。
     *
     * 五种排序**统统**以 `targetId DESC` 收尾（需求硬性要求），但跨书架时仅靠它还不够：
     * 同一次查询里插画与小说可能撞 id（两套编号），此时 `(targetId)` 仍有并列。所以再补一列
     * `shelfKey ASC` —— 主键是 `(shelfKey, targetId)`，补上它以后排序键在结果集内**严格全序**，
     * LIMIT/OFFSET 翻页不会出现「某条重复出现 / 某条永远看不到」。
     *
     * [AllBookmarkSort.TRENDING] 是唯一的表达式排序：收藏速率 = 总收藏数 / 距今天数。
     * `MAX(1.0, …)` 保证：
     * - `createDateMs = 0`（发布时间未知的失效作品）时天数是一个很大的正数，速率自然趋近 0、排到最后，
     *   不会除零、不会把失效作品顶上来；
     * - 未来时间戳 / 时钟回拨导致天数为负时，按 1 天处理，不会出现负速率。
     * `now` 是本机 Long（非用户输入），可以安全内联；其余绑定值都走 `?`。
     */
    private fun orderClause(sort: AllBookmarkSort, now: Long): String {
        val primary = when (sort) {
            AllBookmarkSort.NEWEST_PUBLISHED -> "createDateMs DESC"
            AllBookmarkSort.OLDEST_PUBLISHED -> "createDateMs ASC"
            AllBookmarkSort.MOST_BOOKMARKED -> "totalBookmarks DESC"
            AllBookmarkSort.MOST_VIEWED -> "totalView DESC"
            AllBookmarkSort.TRENDING ->
                "(totalBookmarks * 1.0 / MAX(1.0, (${now.coerceAtLeast(0L)} - createDateMs) / 86400000.0)) DESC"
        }
        return "$primary, targetId DESC, shelfKey ASC"
    }

    /** 拼 WHERE（旧入口：书架范围 + 关键词 + 标签 + 原始 workType），绑定值按序追加进 [args]。 */
    private fun buildWhere(filter: AllBookmarkFilter, args: MutableList<Any?>): String {
        val clauses = baseClauses(filter.shelfKeys, filter.keyword, filter.tagName, args)
        appendInPlaceholders(clauses, args, "workType", filter.workTypes)
        return clauses.joinToString(" AND ")
    }

    /**
     * 拼 WHERE（搜索筛选入口）：共用片段 + [appendWorkFilter] 的全部作品维度条件。
     *
     * 绑定值顺序 = `?` 在 SQL 文本里的出现顺序（书架 key → 关键词 → 标签 → workTypes →
     * 投稿期间 → 收藏数 → users入り → …），逐段 append 不会错位；
     * [AllBookmarkWorkFilterQueryTest] 用「占位符数量 == 绑定值数量」守住这条。
     */
    private fun buildWhere(
        filter: AllBookmarkWorkFilter,
        shelfKeys: List<String>,
        args: MutableList<Any?>,
    ): String {
        val clauses = baseClauses(shelfKeys, filter.keyword, filter.tagName, args)
        appendWorkFilter(filter, clauses, args)
        return clauses.joinToString(" AND ")
    }

    /** 两个入口共用的 WHERE 前缀：书架范围 + 关键词逐词 AND + 标签相关 EXISTS。 */
    private fun baseClauses(
        shelfKeys: List<String>,
        keyword: String,
        tagName: String?,
        args: MutableList<Any?>,
    ): MutableList<String> {
        val clauses = mutableListOf<String>()

        val keys = shelfKeys.distinct()
        if (keys.isEmpty()) {
            // 非法 SQL `IN ()` 会让整条查询抛异常；「没有书架」的正确语义就是 0 行。
            clauses += "0"
        } else {
            clauses += "shelfKey IN (${keys.joinToString(",") { "?" }})"
            args.addAll(keys)
        }

        MirrorKeywordSql.append(keyword, clauses, args)

        tagName?.takeIf { it.isNotBlank() }?.let { name ->
            // 用相关 EXISTS 而不是 `targetId IN (SELECT targetId …)`：插画 id 与小说 id
            // 来自两套编号，可能撞号，只按 targetId 会把另一边同号的作品误算进来。
            // 按 (shelfKey, targetId) 成对命中才是「这件作品带这个标签」的准确语义。
            clauses += "EXISTS (SELECT 1 FROM $TAGS t WHERE t.shelfKey = $ROWS.shelfKey " +
                "AND t.targetId = $ROWS.targetId AND t.tagName = ?)"
            args += name
        }

        return clauses
    }

    /**
     * [AllBookmarkWorkFilter] 的作品维度条件（全部 AND、用户输入全部 `?` 绑定）。
     *
     * 几处有意的口径（与搜索端 / 单书架筛选保持一致，不各写一套）：
     *
     * - **workTypes 用数据库原始值**（illust/manga/ugoira/novel），**不做 illust→ugoira 合并**：
     *   面板把「插画、动图」做成两个可勾选项，自动合并会把用户显式取消的 ugoira 又带回来
     *   （旧 API 的合并语义仍保留在 [AllBookmarkRepository.resolveWorkTypes] / 旧重载里）。
     * - **投稿期间**额外要求 `createDateMs > 0`：发布时间未知（被删/不可见的失效作品）不属于
     *   任何时间段，与单书架 `BookmarkFilter` 的既有口径一致；否则只设上限时它们会混进结果。
     * - **users入り** 用相关 EXISTS 按 `(shelfKey, targetId)` 成对命中（跨内容类型 id 可能撞号），
     *   `CAST(REPLACE(t.tagName,'users入り','') AS INTEGER)` 解析 `{N}users入り` 的 N；
     *   tagName 已小写归一，`'%users入り'` 的 LIKE 只对 ASCII 部分做大小写折叠。
     * - **AI** 的 EXCLUDE 用 `aiType != 2`：0=未知归到「非 AI」一侧，与搜索端客户端过滤一致。
     * - **R-18** 的 R18_ONLY 用 `xRestrict > 0`，把 R-18G 一并算进去（pixiv 的「R-18」含 R-18G）。
     * - **分辨率** 是宽、高**同时**落在同一档位（闭区间）；任一阈值为 null 表示该端不限。
     */
    private fun appendWorkFilter(
        filter: AllBookmarkWorkFilter,
        clauses: MutableList<String>,
        args: MutableList<Any?>,
    ) {
        appendInPlaceholders(clauses, args, "workType", filter.workTypes)

        if (filter.createdFromMs != null || filter.createdToMs != null) {
            clauses += "createDateMs > 0"
            filter.createdFromMs?.let { clauses += "createDateMs >= ?"; args += it }
            filter.createdToMs?.let { clauses += "createDateMs <= ?"; args += it }
        }

        appendRange(clauses, args, "totalBookmarks", filter.minBookmarks, filter.maxBookmarks)

        if (filter.usersTagMin > 0) {
            clauses += "EXISTS (SELECT 1 FROM $TAGS t WHERE t.shelfKey = $ROWS.shelfKey " +
                "AND t.targetId = $ROWS.targetId AND t.tagName LIKE '%users入り' " +
                "AND CAST(REPLACE(t.tagName, 'users入り', '') AS INTEGER) >= ?)"
            args += filter.usersTagMin
        }

        when (filter.ai) {
            AllBookmarkAiFilter.ANY -> Unit
            AllBookmarkAiFilter.ONLY_AI -> clauses += "aiType = 2"
            AllBookmarkAiFilter.EXCLUDE_AI -> clauses += "aiType != 2"
        }

        when (filter.age) {
            AllBookmarkAgeFilter.ANY -> Unit
            AllBookmarkAgeFilter.SAFE_ONLY -> clauses += "xRestrict = 0"
            AllBookmarkAgeFilter.R18_ONLY -> clauses += "xRestrict > 0"
        }

        when (filter.ratio) {
            AllBookmarkRatioFilter.ANY -> Unit
            AllBookmarkRatioFilter.LANDSCAPE ->
                clauses += "orientation = ${BookmarkMirrorMapper.ORIENTATION_LANDSCAPE}"
            AllBookmarkRatioFilter.PORTRAIT ->
                clauses += "orientation = ${BookmarkMirrorMapper.ORIENTATION_PORTRAIT}"
            AllBookmarkRatioFilter.SQUARE ->
                clauses += "orientation = ${BookmarkMirrorMapper.ORIENTATION_SQUARE}"
        }

        val resolution = filter.resolution
        if (resolution != AllBookmarkResolutionFilter.ANY) {
            // 小说和未知作品的 width/height=0 不代表低分辨率，必须先排除无图像尺寸的行。
            clauses += "width > 0 AND height > 0"
            // 枚举把「宽/高 + 上下限」都给定死了，这里只负责按固定顺序绑定；null 即该端不限。
            resolution.widthMin?.let { clauses += "width >= ?"; args += it }
            resolution.widthMax?.let { clauses += "width <= ?"; args += it }
            resolution.heightMin?.let { clauses += "height >= ?"; args += it }
            resolution.heightMax?.let { clauses += "height <= ?"; args += it }
        }

        when (filter.pages) {
            AllBookmarkPageFilter.ANY -> Unit
            AllBookmarkPageFilter.SINGLE_PAGE -> clauses += "pageCount <= 1"
            AllBookmarkPageFilter.MULTI_PAGE -> clauses += "pageCount > 1"
        }

        appendRange(clauses, args, "textLength", filter.minTextLength, filter.maxTextLength)
    }

    /** `column IN (?,…)`；空集合不追加条件（= 不限）。 */
    private fun appendInPlaceholders(
        clauses: MutableList<String>,
        args: MutableList<Any?>,
        column: String,
        values: List<Any>,
    ) {
        if (values.isEmpty()) return
        val distinct = values.distinct()
        clauses += "$column IN (${distinct.joinToString(",") { "?" }})"
        args.addAll(distinct)
    }

    /** 闭区间：任一 [min]/[max] 为 null 表示该端不限。 */
    private fun appendRange(
        clauses: MutableList<String>,
        args: MutableList<Any?>,
        column: String,
        min: Number?,
        max: Number?,
    ) {
        if (min != null) {
            clauses += "$column >= ?"
            args += min
        }
        if (max != null) {
            clauses += "$column <= ?"
            args += max
        }
    }

    /**
     * 追加一整个书架范围的过滤条件（`shelfKey IN (…)`；空集合时追加恒假条件）。
     *
     * [column] 允许带表别名（`t.shelfKey`）：标签云/统计的子查询里要写清楚是谁的列。
     */
    private fun appendShelfScope(
        sql: StringBuilder,
        args: MutableList<Any?>,
        keys: List<String>,
        column: String,
    ) {
        if (keys.isEmpty()) {
            sql.append("0")
            return
        }
        sql.append(column).append(" IN (").append(keys.joinToString(",") { "?" }).append(')')
        args.addAll(keys)
    }
}
