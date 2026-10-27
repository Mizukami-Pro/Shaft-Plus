package ceui.pixiv.db.mirror

import androidx.sqlite.db.SupportSQLiteQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AllBookmarkMirrorQuery] 的形状与安全性（纯 JVM，不跑真库）。
 *
 * 与 [BookmarkMirrorQueryTest] 同一个理由，只是对象换成了跨书架查询：
 *  1. 拼 SQL 的地方一旦把用户输入拼进字符串就是注入 —— 书架 key 是本地拼的、标签/关键词
 *     是用户输入，这条必须被机器盯着；
 *  2. 五种排序与 `targetId DESC` 兜底键是分页「不重不漏」的唯一保证，改坏了编译器不会说话。
 *
 * 「真的能查出对的结果」由 `AllBookmarkRepositoryTest` 用 Room in-memory 覆盖。
 */
class AllBookmarkMirrorQueryTest {

    private val illustPublic = BookmarkShelf(12345L, MirrorContentType.ILLUST, MirrorRestrict.PUBLIC).key
    private val illustPrivate = BookmarkShelf(12345L, MirrorContentType.ILLUST, MirrorRestrict.PRIVATE).key
    private val novelPublic = BookmarkShelf(12345L, MirrorContentType.NOVEL, MirrorRestrict.PUBLIC).key
    private val novelPrivate = BookmarkShelf(12345L, MirrorContentType.NOVEL, MirrorRestrict.PRIVATE).key
    private val shelfKeys = listOf(illustPublic, illustPrivate, novelPublic, novelPrivate)

    private fun filter(
        tagName: String? = null,
        keyword: String = "",
        workTypes: List<String> = emptyList(),
    ): AllBookmarkFilter = AllBookmarkFilter(
        shelfKeys = shelfKeys,
        keyword = keyword,
        tagName = tagName,
        workTypes = workTypes,
    )

    /** 每个查询都必须满足：`?` 的数量 == 绑定值数量（参数错位在真库上是运行期才炸的）。 */
    private fun assertBindingCount(query: SupportSQLiteQuery) {
        assertEquals(query.sql, query.sql.count { it == '?' }, query.captureArgs().size)
    }

    @Test
    fun `跨书架范围是 IN 绑定，书架 key 不拼进 SQL`() {
        val query = AllBookmarkMirrorQuery.rows(filter(), AllBookmarkSort.NEWEST_PUBLISHED, 30, 0)
        assertTrue(query.sql, query.sql.contains("shelfKey IN (?,?,?,?)"))
        val args = query.captureArgs()
        shelfKeys.forEach { key ->
            assertTrue("绑定值里应有书架 $key: $args", args.contains(key))
            assertFalse("书架 key 不该拼进 SQL: ${query.sql}", query.sql.contains(key))
        }
        assertBindingCount(query)
    }

    @Test
    fun `五种排序都按作品维度排序并追 targetId 兜底`() {
        val now = 1_700_000_000_000L
        // 断言的是 " ORDER BY " 之后、" LIMIT" 之前的排序子句，所以 expected 里不带前缀。
        val expected = mapOf(
            AllBookmarkSort.NEWEST_PUBLISHED to "createDateMs DESC, targetId DESC, shelfKey ASC",
            AllBookmarkSort.OLDEST_PUBLISHED to "createDateMs ASC, targetId DESC, shelfKey ASC",
            AllBookmarkSort.MOST_BOOKMARKED to "totalBookmarks DESC, targetId DESC, shelfKey ASC",
            AllBookmarkSort.MOST_VIEWED to "totalView DESC, targetId DESC, shelfKey ASC",
            AllBookmarkSort.TRENDING to
                "(totalBookmarks * 1.0 / MAX(1.0, ($now - createDateMs) / 86400000.0)) DESC, " +
                "targetId DESC, shelfKey ASC",
        )
        AllBookmarkSort.entries.forEach { sort ->
            val sql = AllBookmarkMirrorQuery.rows(filter(), sort, 30, 0, now).sql
            assertEquals(sort.name, expected.getValue(sort), sql.substringAfter(" ORDER BY ").substringBefore(" LIMIT"))
            // 跨书架全序还需要 shelfKey：插画 id 与小说 id 可能撞号。
            assertTrue(sql, sql.contains(", targetId DESC, shelfKey ASC"))
            assertBindingCount(AllBookmarkMirrorQuery.rows(filter(), sort, 30, 0, now))
        }
    }

    /**
     * TRENDING 的 `now` 是本地 Long，允许内联（不是用户输入），但**绝不能**因此把用户输入
     * 的内联口子开出去；同时表达式必须防 `createDateMs = 0` 的除零/负速率。
     */
    @Test
    fun `TRENDING now 内联且用 MAX 防除零`() {
        val now = 1_700_000_000_000L
        val query = AllBookmarkMirrorQuery.rows(filter(), AllBookmarkSort.TRENDING, 30, 0, now)
        assertTrue(query.sql, query.sql.contains("MAX(1.0, ($now - createDateMs) / 86400000.0)"))
        assertFalse("now 不应作为绑定值: ${query.captureArgs()}", query.captureArgs().contains(now))
        assertBindingCount(query)
    }

    @Test
    fun `关键词逐词 AND 且绑定值小写归一`() {
        val query = AllBookmarkMirrorQuery.rows(
            filter(keyword = "  初音  MIKU  miku "), AllBookmarkSort.NEWEST_PUBLISHED, 30, 0,
        )
        assertEquals(2, Regex("searchText LIKE \\? ESCAPE").findAll(query.sql).count())
        val args = query.captureArgs()
        assertTrue(args.toString(), args.contains("%初音%"))
        assertTrue(args.toString(), args.contains("%miku%"))
        assertFalse("用户输入不该出现在 SQL 里: ${query.sql}", query.sql.contains("初音"))
        assertBindingCount(query)
    }

    @Test
    fun `纯数字关键词同时命中作品 id 和作者 uid`() {
        val query = AllBookmarkMirrorQuery.rows(filter(keyword = "38297201"), AllBookmarkSort.NEWEST_PUBLISHED, 30, 0)
        assertTrue(query.sql, query.sql.contains("targetId = ? OR authorId = ?"))
        val args = query.captureArgs()
        assertEquals(2, args.count { it == 38297201L })
        assertBindingCount(query)
    }

    @Test
    fun `关键词里的 LIKE 通配符被转义`() {
        val args = AllBookmarkMirrorQuery
            .rows(filter(keyword = "50%_off"), AllBookmarkSort.NEWEST_PUBLISHED, 30, 0)
            .captureArgs()
        assertTrue(args.toString(), args.contains("%50\\%\\_off%"))
    }

    /**
     * 标签过滤必须按 `(shelfKey, targetId)` 成对命中：插画 id 与小说 id 来自两套编号，
     * 只按 `targetId IN (…)` 会把另一边同号的作品误算进来。
     */
    @Test
    fun `标签过滤用相关 EXISTS 按书架成对命中`() {
        val query = AllBookmarkMirrorQuery.rows(filter(tagName = "オリジナル"), AllBookmarkSort.NEWEST_PUBLISHED, 30, 0)
        assertTrue(
            query.sql,
            query.sql.contains(
                "EXISTS (SELECT 1 FROM bookmark_mirror_tag_table t " +
                    "WHERE t.shelfKey = bookmark_mirror_table.shelfKey " +
                    "AND t.targetId = bookmark_mirror_table.targetId AND t.tagName = ?)",
            ),
        )
        assertFalse("标签名不该拼进 SQL: ${query.sql}", query.sql.contains("オリジナル"))
        assertTrue(query.captureArgs().contains("オリジナル"))
        assertBindingCount(query)
    }

    @Test
    fun `作品类型过滤是 workType IN 绑定`() {
        val query = AllBookmarkMirrorQuery.rows(
            filter(workTypes = listOf("illust", "ugoira", "novel")), AllBookmarkSort.NEWEST_PUBLISHED, 30, 0,
        )
        assertTrue(query.sql, query.sql.contains("workType IN (?,?,?)"))
        assertBindingCount(query)
        val plain = AllBookmarkMirrorQuery.rows(filter(), AllBookmarkSort.NEWEST_PUBLISHED, 30, 0).sql
        assertFalse(plain, plain.contains("workType IN"))
    }

    @Test
    fun `后端分页参数永远收尾`() {
        val query = AllBookmarkMirrorQuery.rows(filter(), AllBookmarkSort.NEWEST_PUBLISHED, 60, 120)
        assertTrue(query.sql, query.sql.contains("LIMIT ? OFFSET ?"))
        assertEquals(listOf<Any?>(60L, 120L), query.captureArgs().takeLast(2))
        assertBindingCount(query)
    }

    @Test
    fun `标签云按作品去重计数并按命中数降序`() {
        val query = AllBookmarkMirrorQuery.tagFacets(shelfKeys, limit = 120)
        assertTrue(query.sql, query.sql.contains("COUNT(DISTINCT (substr(t.shelfKey"))
        assertTrue(query.sql, query.sql.contains("GROUP BY t.tagName"))
        assertTrue(query.sql, query.sql.contains("ORDER BY hitCount DESC, t.tagName ASC"))
        assertTrue(query.sql, query.sql.contains("shelfKey IN (?,?,?,?)"))
        assertEquals(listOf<Any?>(120L), query.captureArgs().takeLast(1))
        assertBindingCount(query)
    }

    @Test
    fun `标签云搜索同时匹配原名与译名且通配符转义`() {
        val query = AllBookmarkMirrorQuery.tagFacets(shelfKeys, query = "  初音  ")
        assertTrue(query.sql, query.sql.contains("t.displayName LIKE ? ESCAPE"))
        assertTrue(query.sql, query.sql.contains("t.translatedName LIKE ? ESCAPE"))
        val args = query.captureArgs()
        assertEquals(2, args.count { it == "%初音%" })
        assertFalse(query.sql, query.sql.contains("初音"))
        val evil = AllBookmarkMirrorQuery.tagFacets(shelfKeys, query = "50%_off").captureArgs()
        assertEquals(2, evil.count { it == "%50\\%\\_off%" })
        assertBindingCount(AllBookmarkMirrorQuery.tagFacets(shelfKeys, query = "50%_off"))
    }

    /** limit <= 0 表示不限制：不能拼出 `LIMIT 0`（那是「一条都不要」的意思）。 */
    @Test
    fun `标签云 limit 非正时不拼 LIMIT`() {
        listOf(0, -1).forEach { limit ->
            val query = AllBookmarkMirrorQuery.tagFacets(shelfKeys, limit = limit)
            assertFalse("limit=$limit: ${query.sql}", query.sql.contains("LIMIT"))
            assertBindingCount(query)
        }
    }

    @Test
    fun `统计口径 ugoira 归入插画且标签数去重`() {
        val query = AllBookmarkMirrorQuery.stats(shelfKeys)
        assertTrue(query.sql, query.sql.contains("workType IN ('illust','ugoira')"))
        assertTrue(query.sql, query.sql.contains("COUNT(DISTINCT tagName)"))
        assertTrue(query.sql, query.sql.contains("shelfKey IN (?,?,?,?)"))
        // 子查询 + 主 WHERE 各一份书架绑定
        assertEquals(8, query.captureArgs().size)
        assertBindingCount(query)
    }

    @Test
    fun `空书架集合不会拼出非法 IN`() {
        emptyList<String>().let { keys ->
            listOf(
                AllBookmarkMirrorQuery.rows(AllBookmarkFilter(keys), AllBookmarkSort.NEWEST_PUBLISHED, 30, 0),
                AllBookmarkMirrorQuery.count(AllBookmarkFilter(keys)),
                AllBookmarkMirrorQuery.tagFacets(keys),
                AllBookmarkMirrorQuery.stats(keys),
            ).forEach { query ->
                assertFalse("非法 IN ()：${query.sql}", query.sql.contains("IN ()"))
                assertBindingCount(query)
            }
        }
    }

    @Test
    fun `单引号不会拼进 SQL —— 注入面在这里闭合`() {
        val evil = "'; DROP TABLE bookmark_mirror_table; --"
        val query = AllBookmarkMirrorQuery.rows(
            filter(tagName = evil, keyword = evil, workTypes = listOf(evil)), AllBookmarkSort.NEWEST_PUBLISHED, 30, 0,
        )
        assertFalse(query.sql, query.sql.contains("DROP"))
        assertFalse(query.sql, query.sql.contains("--"))
        assertTrue(query.captureArgs().contains(evil))
        assertBindingCount(query)
    }
}
