package ceui.pixiv.db.mirror

import androidx.sqlite.db.SupportSQLiteQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AllBookmarkWorkFilter] → 跨书架 SQL 的形状与安全性（纯 JVM，不跑真库）。
 *
 * 与 [AllBookmarkMirrorQueryTest]（旧入口）互补：这里逐维度断言「面板上的每个筛选档
 * 到底落成了什么 SQL、绑定了什么值、有没有把用户输入拼进字符串」。真库行为（各维度组合
 * 过滤出什么结果）由 `AllBookmarkRepositoryTest` 的搜索筛选用例覆盖。
 *
 * 最值得被机器盯着的一条：**空筛选必须与旧入口生成逐字相同的 SQL** —— 标签详情页默认
 * 状态（只带 tagName）走的就是这条路径，口径一旦和旧实现分叉，同一个标签在「默认打开
 * 详情页」和「旧收藏库」里会看到不同的数字。
 */
class AllBookmarkWorkFilterQueryTest {

    private val illustPublic = BookmarkShelf(12345L, MirrorContentType.ILLUST, MirrorRestrict.PUBLIC).key
    private val illustPrivate = BookmarkShelf(12345L, MirrorContentType.ILLUST, MirrorRestrict.PRIVATE).key
    private val novelPublic = BookmarkShelf(12345L, MirrorContentType.NOVEL, MirrorRestrict.PUBLIC).key
    private val novelPrivate = BookmarkShelf(12345L, MirrorContentType.NOVEL, MirrorRestrict.PRIVATE).key
    private val shelfKeys = listOf(illustPublic, illustPrivate, novelPublic, novelPrivate)

    private fun query(
        filter: AllBookmarkWorkFilter,
        sort: AllBookmarkSort = AllBookmarkSort.NEWEST_PUBLISHED,
        limit: Int = 30,
        offset: Int = 0,
        now: Long = 1_700_000_000_000L,
    ): SupportSQLiteQuery = AllBookmarkMirrorQuery.rows(filter, shelfKeys, sort, limit, offset, now)

    private fun whereOf(query: SupportSQLiteQuery): String =
        query.sql.substringAfter(" WHERE ").substringBefore(" ORDER BY ")

    private fun assertBindingCount(query: SupportSQLiteQuery) {
        assertEquals(query.sql, query.sql.count { it == '?' }, query.captureArgs().size)
    }

    @Test
    fun `空筛选与旧入口生成逐字相同的 SQL`() {
        val newRows = query(AllBookmarkWorkFilter()).sql
        val oldRows = AllBookmarkMirrorQuery
            .rows(AllBookmarkFilter(shelfKeys), AllBookmarkSort.NEWEST_PUBLISHED, 30, 0).sql
        assertEquals(oldRows, newRows)

        val newCount = AllBookmarkMirrorQuery.count(AllBookmarkWorkFilter(), shelfKeys).sql
        val oldCount = AllBookmarkMirrorQuery.count(AllBookmarkFilter(shelfKeys)).sql
        assertEquals(oldCount, newCount)
    }

    @Test
    fun `workTypes 用数据库原始值 IN 绑定且不合并 illust ugoira`() {
        val query = query(AllBookmarkWorkFilter(workTypes = listOf("illust", "ugoira")))
        assertTrue(query.sql, query.sql.contains("workType IN (?,?)"))
        // SQL 层只出现一次 workType 条件；合并（illust→ugoira）是旧重载的职责，不在这里
        assertEquals(1, Regex("workType IN").findAll(query.sql).count())
        val args = query.captureArgs()
        assertTrue(args.toString(), args.contains("illust"))
        assertTrue(args.toString(), args.contains("ugoira"))
        assertFalse(query.sql, query.sql.contains("illust"))
        assertBindingCount(query)
    }

    @Test
    fun `投稿期间是闭区间且排除时间未知的行`() {
        val query = query(AllBookmarkWorkFilter(createdFromMs = 1_000L, createdToMs = 2_000L))
        assertTrue(query.sql, query.sql.contains("createDateMs > 0"))
        assertTrue(query.sql, query.sql.contains("createDateMs >= ?"))
        assertTrue(query.sql, query.sql.contains("createDateMs <= ?"))
        val args = query.captureArgs()
        assertTrue(args.toString(), args.contains(1_000L))
        assertTrue(args.toString(), args.contains(2_000L))
        assertBindingCount(query)

        // 只给一端时也只绑一个值
        val fromOnly = query(AllBookmarkWorkFilter(createdFromMs = 500L))
        assertTrue(fromOnly.sql, fromOnly.sql.contains("createDateMs >= ?"))
        assertFalse(fromOnly.sql, fromOnly.sql.contains("createDateMs <= ?"))
        assertBindingCount(fromOnly)
    }

    @Test
    fun `喜欢数走 totalBookmarks 闭区间`() {
        val query = query(AllBookmarkWorkFilter(minBookmarks = 100, maxBookmarks = 500))
        assertTrue(query.sql, query.sql.contains("totalBookmarks >= ?"))
        assertTrue(query.sql, query.sql.contains("totalBookmarks <= ?"))
        val args = query.captureArgs()
        assertTrue(args.toString(), args.contains(100L))
        assertTrue(args.toString(), args.contains(500L))
        assertBindingCount(query)
    }

    /** 跨内容类型 id 可能撞号，users入り 必须按 (shelfKey, targetId) 成对命中。 */
    @Test
    fun `users入り按 N 解析并按书架成对命中`() {
        val query = query(AllBookmarkWorkFilter(usersTagMin = 1_000))
        val expected = "EXISTS (SELECT 1 FROM bookmark_mirror_tag_table t " +
            "WHERE t.shelfKey = bookmark_mirror_table.shelfKey " +
            "AND t.targetId = bookmark_mirror_table.targetId " +
            "AND t.tagName LIKE '%users入り' " +
            "AND CAST(REPLACE(t.tagName, 'users入り', '') AS INTEGER) >= ?)"
        assertTrue(query.sql, query.sql.contains(expected))
        assertTrue(query.captureArgs().toString(), query.captureArgs().contains(1_000L))
        assertFalse("用户输入不该拼进 SQL: ${query.sql}", query.sql.contains("1000"))
        assertBindingCount(query)
    }

    @Test
    fun `AI 三档`() {
        val only = query(AllBookmarkWorkFilter(ai = AllBookmarkAiFilter.ONLY_AI))
        assertTrue(only.sql, only.sql.contains("aiType = 2"))
        val exclude = query(AllBookmarkWorkFilter(ai = AllBookmarkAiFilter.EXCLUDE_AI))
        assertTrue(exclude.sql, exclude.sql.contains("aiType != 2"))
        val any = query(AllBookmarkWorkFilter(ai = AllBookmarkAiFilter.ANY))
        assertFalse(any.sql, any.sql.contains("aiType"))
    }

    @Test
    fun `R18 三档`() {
        val safe = query(AllBookmarkWorkFilter(age = AllBookmarkAgeFilter.SAFE_ONLY))
        assertTrue(safe.sql, safe.sql.contains("xRestrict = 0"))
        val r18 = query(AllBookmarkWorkFilter(age = AllBookmarkAgeFilter.R18_ONLY))
        assertTrue(r18.sql, r18.sql.contains("xRestrict > 0"))
        val any = query(AllBookmarkWorkFilter(age = AllBookmarkAgeFilter.ANY))
        assertFalse(any.sql, any.sql.contains("xRestrict"))
    }

    @Test
    fun `长宽比三档`() {
        listOf(
            AllBookmarkRatioFilter.LANDSCAPE to "orientation = 1",
            AllBookmarkRatioFilter.PORTRAIT to "orientation = 2",
            AllBookmarkRatioFilter.SQUARE to "orientation = 3",
        ).forEach { (filter, fragment) ->
            val query = query(AllBookmarkWorkFilter(ratio = filter))
            assertTrue(query.sql, query.sql.contains(fragment))
            assertBindingCount(query)
        }
        val any = query(AllBookmarkWorkFilter(ratio = AllBookmarkRatioFilter.ANY))
        assertFalse(any.sql, any.sql.contains("orientation"))
    }

    @Test
    fun `分辨率三档都是宽高同时成立的闭区间`() {
        val above = query(AllBookmarkWorkFilter(resolution = AllBookmarkResolutionFilter.ABOVE_3000))
        assertTrue(above.sql, above.sql.contains("width >= ?"))
        assertTrue(above.sql, above.sql.contains("height >= ?"))
        assertEquals(2, above.captureArgs().count { it == 3_000L })

        val between = query(AllBookmarkWorkFilter(resolution = AllBookmarkResolutionFilter.BETWEEN_1000_AND_2999))
        assertTrue(between.sql, between.sql.contains("width >= ?"))
        assertTrue(between.sql, between.sql.contains("width <= ?"))
        assertTrue(between.sql, between.sql.contains("height >= ?"))
        assertTrue(between.sql, between.sql.contains("height <= ?"))
        val betweenArgs = between.captureArgs()
        assertEquals(2, betweenArgs.count { it == 1_000L })
        assertEquals(2, betweenArgs.count { it == 2_999L })

        val below = query(AllBookmarkWorkFilter(resolution = AllBookmarkResolutionFilter.BELOW_1000))
        assertTrue(below.sql, below.sql.contains("width > 0 AND height > 0"))
        assertTrue(below.sql, below.sql.contains("width <= ?"))
        assertTrue(below.sql, below.sql.contains("height <= ?"))
        assertEquals(2, below.captureArgs().count { it == 999L })

        // 空区间 = 不限，一个阈值都不绑
        val any = query(AllBookmarkWorkFilter(resolution = AllBookmarkResolutionFilter.ANY))
        assertFalse(any.sql, any.sql.contains("width"))
        assertFalse(any.sql, any.sql.contains("height"))
        assertBindingCount(above)
        assertBindingCount(between)
        assertBindingCount(below)
    }

    @Test
    fun `页数与正文长度`() {
        val single = query(AllBookmarkWorkFilter(pages = AllBookmarkPageFilter.SINGLE_PAGE))
        assertTrue(single.sql, single.sql.contains("pageCount <= 1"))
        val multi = query(AllBookmarkWorkFilter(pages = AllBookmarkPageFilter.MULTI_PAGE))
        assertTrue(multi.sql, multi.sql.contains("pageCount > 1"))

        val length = query(AllBookmarkWorkFilter(minTextLength = 100, maxTextLength = 2_000))
        assertTrue(length.sql, length.sql.contains("textLength >= ?"))
        assertTrue(length.sql, length.sql.contains("textLength <= ?"))
        val args = length.captureArgs()
        assertTrue(args.toString(), args.contains(100L))
        assertTrue(args.toString(), args.contains(2_000L))
        assertBindingCount(length)
    }

    @Test
    fun `组合筛选的占位符与绑定值一一对应且用户输入不内联`() {
        val filter = AllBookmarkWorkFilter(
            tagName = "オリジナル",
            keyword = "初音 miku",
            workTypes = listOf("illust", "manga"),
            createdFromMs = 1_000L,
            createdToMs = 2_000L,
            minBookmarks = 10,
            maxBookmarks = 20,
            usersTagMin = 500,
            ai = AllBookmarkAiFilter.ONLY_AI,
            age = AllBookmarkAgeFilter.R18_ONLY,
            ratio = AllBookmarkRatioFilter.PORTRAIT,
            resolution = AllBookmarkResolutionFilter.BETWEEN_1000_AND_2999,
            pages = AllBookmarkPageFilter.MULTI_PAGE,
            minTextLength = 100,
            maxTextLength = 200,
        )
        val query = query(filter, AllBookmarkSort.TRENDING, limit = 40, offset = 80)
        assertBindingCount(query)
        assertFalse(query.sql, query.sql.contains("オリジナル"))
        assertFalse(query.sql, query.sql.contains("初音"))
        assertTrue(query.sql, query.sql.contains("ORDER BY (totalBookmarks * 1.0 / MAX(1.0, (1700000000000 - createDateMs) / 86400000.0)) DESC, targetId DESC, shelfKey ASC"))
        assertEquals(listOf<Any?>(40L, 80L), query.captureArgs().takeLast(2))
    }

    @Test
    fun `count 与 rows 共用同一段 WHERE`() {
        val filter = AllBookmarkWorkFilter(
            tagName = "初音ミク",
            keyword = "miku",
            workTypes = listOf("novel"),
            minBookmarks = 10,
            ai = AllBookmarkAiFilter.EXCLUDE_AI,
            pages = AllBookmarkPageFilter.SINGLE_PAGE,
        )
        val rows = query(filter)
        val count = AllBookmarkMirrorQuery.count(filter, shelfKeys)
        assertTrue(count.sql, count.sql.endsWith(" WHERE ${whereOf(rows)}"))
        // 计数没有 LIMIT/OFFSET，绑定值正好比 rows 少两个
        assertEquals(rows.captureArgs().size, count.captureArgs().size + 2)
    }

    @Test
    fun `空书架集合仍是恒假条件`() {
        val filter = AllBookmarkWorkFilter(tagName = "初音ミク", ai = AllBookmarkAiFilter.ONLY_AI)
        val rows = AllBookmarkMirrorQuery.rows(filter, emptyList(), AllBookmarkSort.NEWEST_PUBLISHED, 30, 0)
        assertFalse(rows.sql, rows.sql.contains("IN ()"))
        assertBindingCount(rows)
        val count = AllBookmarkMirrorQuery.count(filter, emptyList())
        assertFalse(count.sql, count.sql.contains("IN ()"))
        assertBindingCount(count)
    }

    @Test
    fun `标签名与关键词只作为绑定值出现`() {
        val evil = "'; DROP TABLE bookmark_mirror_table; --"
        val query = query(AllBookmarkWorkFilter(tagName = evil, keyword = evil))
        assertFalse(query.sql, query.sql.contains("DROP"))
        assertFalse(query.sql, query.sql.contains("--"))
        assertTrue(query.captureArgs().contains(evil))
        assertBindingCount(query)
    }
}
