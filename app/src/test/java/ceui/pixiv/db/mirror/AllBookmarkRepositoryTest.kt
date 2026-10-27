package ceui.pixiv.db.mirror

import android.app.Application
import androidx.room.Room
import ceui.lisa.activities.Shaft
import ceui.lisa.database.AppDatabase
import com.google.gson.Gson
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * 全收藏分类数据层的**真库**行为：Robolectric + Room in-memory。
 *
 * 与 `AllBookmarkMirrorQueryTest`（只看 SQL 形状）互补，这里验证「查出来的东西对不对」：
 * 跨书架标签去重计数、5 种排序、标签/关键词/类型过滤、分页不重不漏、includePrivate 开关。
 *
 * 数据用最朴素的方式直接构造实体行（不经网络模型 / Gson）：被测的是查询与聚合语义，
 * 镜像写入路径由 `BookmarkMirrorMapper`/`BookmarkMirrorSerialTest` 自己覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AllBookmarkRepositoryTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private lateinit var db: AppDatabase
    private lateinit var dao: BookmarkMirrorDao

    private val uid = 42L
    private val illustPublic = BookmarkShelf(uid, MirrorContentType.ILLUST, MirrorRestrict.PUBLIC)
    private val illustPrivate = BookmarkShelf(uid, MirrorContentType.ILLUST, MirrorRestrict.PRIVATE)
    private val novelPublic = BookmarkShelf(uid, MirrorContentType.NOVEL, MirrorRestrict.PUBLIC)
    private val novelPrivate = BookmarkShelf(uid, MirrorContentType.NOVEL, MirrorRestrict.PRIVATE)

    /** 测试内统一的时间锚点；排序断言都用相对天数，避免依赖真实时钟的绝对值。 */
    private val base = System.currentTimeMillis()

    private companion object {
        const val DAY_MS = 86_400_000L
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // AllBookmarkRepository 内部走 AppDatabase.getAppDatabase(Shaft.getContext())，
        // 把单例指到内存库上（与 BookmarkMirrorReconciliationTest 同一套接法）。
        ReflectionHelpers.setStaticField(AppDatabase::class.java, "INSTANCE", db)
        ReflectionHelpers.setStaticField(Shaft::class.java, "sContext", app)
        Shaft.sGson = Gson()
        dao = db.bookmarkMirrorDao()
    }

    @After
    fun tearDown() {
        AppDatabase.destroyInstance()
        db.close()
    }

    // ─────────────────────────── 数据构造 ───────────────────────────

    private data class WorkSpec(
        val shelf: BookmarkShelf,
        val id: Long,
        val workType: String = "illust",
        /** 标签：displayName to translatedName（tagName 由 displayName 小写归一）。 */
        val tags: List<Pair<String, String>> = emptyList(),
        val createDateMs: Long = 0L,
        val totalBookmarks: Int = 0,
        val totalView: Int = 0,
        val title: String = "work-$id",
        val searchText: String? = null,
        // 搜索筛选（AllBookmarkWorkFilter）各维度用到的列；默认值与旧用例一致，不影响既有断言。
        val width: Int = 100,
        val height: Int = 100,
        val pageCount: Int = 1,
        val textLength: Int = 0,
        val aiType: Int = 0,
        val xRestrict: Int = 0,
    )

    private fun insertAll(specs: List<WorkSpec>) {
        specs.groupBy { it.shelf }.forEach { (shelf, group) ->
            val rows = group.map { buildRow(it) }
            dao.writePage(rows.map { it.row }, rows.flatMap { it.tags })
        }
    }

    private fun buildRow(spec: WorkSpec): MirrorRow {
        val tagRows = spec.tags.map { (displayName, translatedName) ->
            BookmarkMirrorTagEntity(
                shelfKey = spec.shelf.key,
                targetId = spec.id,
                tagName = displayName.lowercase(Locale.ROOT),
                displayName = displayName,
                translatedName = translatedName,
            )
        }
        val aspectRatio = if (spec.width > 0 && spec.height > 0) {
            spec.width.toFloat() / spec.height.toFloat()
        } else {
            0f
        }
        val row = BookmarkMirrorEntity(
            shelfKey = spec.shelf.key,
            targetId = spec.id,
            ownerUid = spec.shelf.ownerUid,
            contentType = spec.shelf.contentType.code,
            restrictCode = spec.shelf.restrict.code,
            bookmarkSeq = -spec.id,
            payloadJson = "{}",
            title = spec.title,
            authorId = 1_000L,
            authorName = "author",
            workType = spec.workType,
            pageCount = spec.pageCount,
            width = spec.width,
            height = spec.height,
            aspectRatio = aspectRatio,
            orientation = orientationOf(aspectRatio),
            totalBookmarks = spec.totalBookmarks,
            totalView = spec.totalView,
            textLength = spec.textLength,
            createDateMs = spec.createDateMs,
            aiType = spec.aiType,
            xRestrict = spec.xRestrict,
            sanityLevel = 0,
            isVisible = true,
            isMuted = false,
            seriesId = 0L,
            tagCount = tagRows.size,
            searchText = ((spec.searchText ?: spec.title) + " " +
                spec.tags.joinToString(" ") { "${it.first} ${it.second}" }).lowercase(Locale.ROOT),
            syncedAt = 0L,
            generation = 1,
        )
        return MirrorRow(row, tagRows)
    }

    /** 与 [BookmarkMirrorMapper] 同口径的画幅分档（测试里直接构造行，不走 mapper）。 */
    private fun orientationOf(ratio: Float): Int = when {
        ratio > 1.05f -> BookmarkMirrorMapper.ORIENTATION_LANDSCAPE
        ratio < 0.95f -> BookmarkMirrorMapper.ORIENTATION_PORTRAIT
        else -> BookmarkMirrorMapper.ORIENTATION_SQUARE
    }

    private fun works(
        sort: AllBookmarkSort = AllBookmarkSort.NEWEST_PUBLISHED,
        tagName: String? = null,
        keyword: String = "",
        workTypes: List<String> = emptyList(),
        includePrivate: Boolean = true,
        limit: Int = 50,
        offset: Int = 0,
    ): List<BookmarkMirrorEntity> = runBlocking {
        AllBookmarkRepository.loadWorks(uid, includePrivate, tagName, keyword, workTypes, sort, limit, offset)
    }

    private fun workIds(
        sort: AllBookmarkSort = AllBookmarkSort.NEWEST_PUBLISHED,
        tagName: String? = null,
        keyword: String = "",
        workTypes: List<String> = emptyList(),
        includePrivate: Boolean = true,
    ): List<Long> = works(
        sort = sort, tagName = tagName, keyword = keyword, workTypes = workTypes, includePrivate = includePrivate,
    ).map { it.targetId }

    /** 搜索筛选版重载的查询入口。 */
    private fun filterWorks(
        filter: AllBookmarkWorkFilter,
        sort: AllBookmarkSort = AllBookmarkSort.NEWEST_PUBLISHED,
        includePrivate: Boolean = true,
        limit: Int = 100,
        offset: Int = 0,
    ): List<BookmarkMirrorEntity> = runBlocking {
        AllBookmarkRepository.loadWorks(uid, includePrivate, filter, sort, limit, offset)
    }

    /** 搜索筛选结果的 targetId（升序，方便断言集合）。 */
    private fun filterIds(filter: AllBookmarkWorkFilter, includePrivate: Boolean = true): List<Long> =
        filterWorks(filter, includePrivate = includePrivate).map { it.targetId }.sorted()

    private fun filterCount(filter: AllBookmarkWorkFilter, includePrivate: Boolean = true): Int = runBlocking {
        AllBookmarkRepository.countWorks(uid, includePrivate, filter)
    }

    // ─────────────────────────── 书架集合 ───────────────────────────

    @Test
    fun `书架集合是插画小说乘公开悄悄`() {
        assertEquals(
            listOf("0:0:42", "0:1:42", "1:0:42", "1:1:42"),
            AllBookmarkRepository.shelvesFor(uid, includePrivate = true).map { it.key },
        )
        assertEquals(
            listOf("0:0:42", "1:0:42"),
            AllBookmarkRepository.shelvesFor(uid, includePrivate = false).map { it.key },
        )
        assertTrue(AllBookmarkRepository.shelvesFor(0L, includePrivate = true).isEmpty())
    }

    @Test
    fun `作品类型映射把 ugoira 归入插画`() {
        assertEquals(emptyList<String>(), AllBookmarkRepository.resolveWorkTypes(emptyList()))
        assertEquals(listOf("illust", "ugoira"), AllBookmarkRepository.resolveWorkTypes(listOf("ILLUST")))
        assertEquals(listOf("illust", "ugoira"), AllBookmarkRepository.resolveWorkTypes(listOf("插画")))
        assertEquals(listOf("manga"), AllBookmarkRepository.resolveWorkTypes(listOf("manga")))
        assertEquals(listOf("novel"), AllBookmarkRepository.resolveWorkTypes(listOf("小说")))
        // 未知值原样下推（而不是忽略成「全部」）
        assertEquals(listOf("weird"), AllBookmarkRepository.resolveWorkTypes(listOf("Weird")))
    }

    // ─────────────────────────── 标签云 / 统计 ───────────────────────────

    @Test
    fun `标签云跨书架聚合且按作品去重计数`() {
        insertAll(
            listOf(
                WorkSpec(illustPublic, 1, tags = listOf("Miku" to "初音未来", "Alpha" to "阿尔法")),
                // 同一件作品在公开/悄悄各留一行（改可见性途中的过渡态）：计数必须去重成 1
                WorkSpec(illustPrivate, 1, tags = listOf("Miku" to "初音未来")),
                WorkSpec(novelPublic, 100, workType = "novel", tags = listOf("Miku" to "初音未来", "Zeta" to "泽塔")),
            )
        )

        val tags = runBlocking { AllBookmarkRepository.loadTags(uid, includePrivate = true) }
        // 命中数降序；并列按 tagName 升序（alpha < zeta）
        assertEquals(listOf("miku", "alpha", "zeta"), tags.map { it.tagName })
        assertEquals(2, tags[0].count) // illust#1 + novel#100，而不是 3 行
        assertEquals("Miku", tags[0].displayName)
        assertEquals("初音未来", tags[0].translatedName)
        assertEquals(1, tags[1].count)
        assertEquals("阿尔法", tags[1].translatedName)

        val stats = runBlocking { AllBookmarkRepository.loadStats(uid, includePrivate = true) }
        assertEquals(3, stats.totalWorks)
        assertEquals(2, stats.illustCount)
        assertEquals(0, stats.mangaCount)
        assertEquals(1, stats.novelCount)
        assertEquals(3, stats.distinctTagCount)

        // 关掉「包含私密收藏」：悄悄那一行不再参与统计
        val publicStats = runBlocking { AllBookmarkRepository.loadStats(uid, includePrivate = false) }
        assertEquals(2, publicStats.totalWorks)
        assertEquals(1, publicStats.illustCount)
        assertEquals(1, publicStats.novelCount)
    }

    @Test
    fun `标签云可按原名或译名搜索并按 limit 截断`() {
        insertAll(
            listOf(
                WorkSpec(illustPublic, 1, tags = listOf("Miku" to "初音未来")),
                WorkSpec(illustPublic, 2, tags = listOf("Rin" to "铃")),
                WorkSpec(illustPublic, 3, tags = listOf("オリジナル" to "原创")),
            )
        )
        // 原名（ASCII 大小写不敏感）
        assertEquals(listOf("miku"), runBlocking { AllBookmarkRepository.loadTags(uid, true, "MIKU") }.map { it.tagName })
        // 译名
        assertEquals(listOf("miku"), runBlocking { AllBookmarkRepository.loadTags(uid, true, "初音") }.map { it.tagName })
        assertEquals(listOf("オリジナル"), runBlocking { AllBookmarkRepository.loadTags(uid, true, "原创") }.map { it.tagName })
        // 没命中 / limit
        assertTrue(runBlocking { AllBookmarkRepository.loadTags(uid, true, "不存在的标签") }.isEmpty())
        assertEquals(1, runBlocking { AllBookmarkRepository.loadTags(uid, true, limit = 1) }.size)
        // trim 后空串 = 不过滤
        assertEquals(3, runBlocking { AllBookmarkRepository.loadTags(uid, true, "   ") }.size)
    }

    // ─────────────────────────── 排序 ───────────────────────────

    @Test
    fun `五种排序都按作品维度生效且稳定`() {
        insertAll(
            listOf(
                // id 10：一天前发布，热度最低
                WorkSpec(illustPublic, 10, createDateMs = base - 1 * DAY_MS, totalBookmarks = 3, totalView = 100),
                // id 20：100 天前发布，收藏数最高
                WorkSpec(illustPublic, 20, createDateMs = base - 100 * DAY_MS, totalBookmarks = 2_000, totalView = 10),
                // id 30：10 天前发布的小说，浏览量最高
                WorkSpec(novelPublic, 30, workType = "novel", createDateMs = base - 10 * DAY_MS, totalBookmarks = 100, totalView = 1_000),
                // id 40：发布时间未知（createDateMs=0）的失效作品，不能崩、不能在「最新」里排前面
                WorkSpec(illustPublic, 40, createDateMs = 0, totalBookmarks = 500, totalView = 500),
            )
        )

        assertEquals(listOf(10L, 30L, 20L, 40L), workIds(AllBookmarkSort.NEWEST_PUBLISHED))
        assertEquals(listOf(40L, 20L, 30L, 10L), workIds(AllBookmarkSort.OLDEST_PUBLISHED))
        assertEquals(listOf(20L, 40L, 30L, 10L), workIds(AllBookmarkSort.MOST_BOOKMARKED))
        assertEquals(listOf(30L, 40L, 10L, 20L), workIds(AllBookmarkSort.MOST_VIEWED))
        // 收藏速率：2000/100天=20 > 100/10天=10 > 3/1天=3 > 500/约两万天≈0
        assertEquals(listOf(20L, 30L, 10L, 40L), workIds(AllBookmarkSort.TRENDING))
    }

    @Test
    fun `TRENDING 在 createDateMs 为零及未来时间下不崩溃`() {
        insertAll(
            listOf(
                WorkSpec(illustPublic, 1, createDateMs = 0, totalBookmarks = 10_000),
                WorkSpec(illustPublic, 2, createDateMs = base + 10 * DAY_MS, totalBookmarks = 1),
                WorkSpec(illustPublic, 3, createDateMs = base - 1 * DAY_MS, totalBookmarks = 5),
            )
        )
        // 未来时间按「1 天」兜底，不会出现负速率；未知时间趋近 0 排在最后
        assertEquals(listOf(3L, 2L, 1L), workIds(AllBookmarkSort.TRENDING))
    }

    // ─────────────────────────── 过滤 ───────────────────────────

    @Test
    fun `标签关键词与作品类型过滤可组合`() {
        insertAll(
            listOf(
                WorkSpec(
                    illustPublic, 1, workType = "illust", createDateMs = base - 5 * DAY_MS,
                    tags = listOf("Miku" to "初音未来"), searchText = "hatsune miku 初音ミク",
                ),
                WorkSpec(
                    illustPublic, 2, workType = "manga", createDateMs = base - 4 * DAY_MS,
                    tags = listOf("Manga" to "漫画"), searchText = "comic book",
                ),
                WorkSpec(
                    illustPublic, 3, workType = "ugoira", createDateMs = base - 3 * DAY_MS,
                    tags = listOf("Miku" to "初音未来"), searchText = "animated miku",
                ),
                WorkSpec(
                    novelPublic, 4, workType = "novel", createDateMs = base - 2 * DAY_MS,
                    tags = listOf("Miku" to "初音未来"), searchText = "novel story miku",
                ),
                WorkSpec(
                    illustPublic, 5, workType = "illust", createDateMs = base - 1 * DAY_MS,
                    tags = listOf("Rin" to "铃"), searchText = "kagamine rin",
                ),
            )
        )

        // 类型：illust 含 ugoira；默认按发布时间新→旧
        assertEquals(listOf(5L, 3L, 1L), workIds(workTypes = listOf("illust")))
        assertEquals(listOf(2L), workIds(workTypes = listOf("manga")))
        assertEquals(listOf(4L), workIds(workTypes = listOf("novel")))
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), workIds(workTypes = emptyList()))

        // 标签：输入可以是展示名（大小写混）也可以是归一后的 tagName
        assertEquals(listOf(4L, 3L, 1L), workIds(tagName = "Miku"))
        assertEquals(listOf(4L, 3L, 1L), workIds(tagName = "miku"))
        assertEquals(listOf(2L), workIds(tagName = "MANGA"))
        assertTrue(works(tagName = "不存在的标签").isEmpty())

        // 关键词：空格分词逐词 AND（命中 searchText 里的标题/标签/译名）
        assertEquals(listOf(4L, 3L, 1L), workIds(keyword = "miku"))
        assertEquals(listOf(4L, 3L, 1L), workIds(keyword = "初音"))
        assertEquals(listOf(5L), workIds(keyword = "kagamine rin"))
        assertTrue(works(keyword = "miku comic").isEmpty())

        // 组合：小说 + Miku 标签
        assertEquals(listOf(4L), workIds(tagName = "Miku", workTypes = listOf("novel")))

        // 「共 N 件」与列表同条件，必须完全一致
        assertEquals(3, runBlocking { AllBookmarkRepository.countWorks(uid, true, null, "", listOf("illust")) })
        assertEquals(5, runBlocking { AllBookmarkRepository.countWorks(uid, true, null, "", emptyList()) })
        assertEquals(3, runBlocking { AllBookmarkRepository.countWorks(uid, true, null, "miku", emptyList()) })
        assertEquals(1, runBlocking { AllBookmarkRepository.countWorks(uid, true, "Miku", "", listOf("novel")) })
    }

    @Test
    fun `includePrivate 关闭后悄悄收藏不参与查询`() {
        insertAll(
            listOf(
                WorkSpec(illustPublic, 1, tags = listOf("Miku" to "初音未来")),
                WorkSpec(illustPrivate, 2, tags = listOf("Miku" to "初音未来")),
                WorkSpec(novelPrivate, 3, workType = "novel", tags = listOf("Miku" to "初音未来")),
            )
        )
        assertEquals(listOf(1L, 2L, 3L), workIds(includePrivate = true).sorted())
        assertEquals(listOf(1L), workIds(includePrivate = false))
        // 含悄悄时：三件都带 miku，去重后 3 件；关掉后只剩公开那件
        assertEquals(3, runBlocking { AllBookmarkRepository.loadTags(uid, true) }.first().count)
        assertEquals(1, runBlocking { AllBookmarkRepository.loadTags(uid, false) }.first().count)
    }

    // ─────────────────────────── 分页 ───────────────────────────

    /**
     * 分页不重不漏：每页 3 条翻完，把 `(shelfKey, targetId)` 收集起来必须恰好等于全量结果。
     *
     * 特意让多条作品的排序键完全相同（包括**插画 id 与小说 id 撞号**：id=7 两边都有）：
     * 只靠 `targetId DESC` 兜底时这两行仍然并列，分页就会抖；`shelfKey ASC` 才是全序的最后一块。
     */
    @Test
    fun `每种排序翻页都不重不漏`() {
        insertAll(
            listOf(
                WorkSpec(illustPublic, 1, createDateMs = base - 1 * DAY_MS, totalBookmarks = 10, totalView = 10),
                WorkSpec(illustPublic, 2, createDateMs = base - 2 * DAY_MS, totalBookmarks = 20, totalView = 20),
                WorkSpec(illustPublic, 3, workType = "manga", createDateMs = 0, totalBookmarks = 30, totalView = 30),
                WorkSpec(illustPrivate, 4, createDateMs = base - 3 * DAY_MS, totalBookmarks = 40, totalView = 40),
                WorkSpec(novelPublic, 5, workType = "novel", createDateMs = base - 4 * DAY_MS, totalBookmarks = 50, totalView = 50),
                WorkSpec(novelPublic, 6, workType = "novel", createDateMs = base - 4 * DAY_MS, totalBookmarks = 50, totalView = 50),
                WorkSpec(illustPublic, 7, createDateMs = base - 4 * DAY_MS, totalBookmarks = 50, totalView = 50),
                // 与插画 #7 撞号：主键是 (shelfKey, targetId)，只按 targetId 排序无法区分
                WorkSpec(novelPublic, 7, workType = "novel", createDateMs = base - 4 * DAY_MS, totalBookmarks = 50, totalView = 50),
            )
        )
        val total = 8
        assertEquals(total, runBlocking { AllBookmarkRepository.countWorks(uid, true, null, "", emptyList()) })

        AllBookmarkSort.entries.forEach { sort ->
            val all = works(sort = sort, limit = 100, offset = 0).map { "${it.shelfKey}#${it.targetId}" }
            assertEquals("全量查询应含 $total 行: $sort", total, all.size)

            val paged = mutableListOf<String>()
            var offset = 0
            while (true) {
                val page = works(sort = sort, limit = 3, offset = offset)
                if (page.isEmpty()) break
                paged += page.map { "${it.shelfKey}#${it.targetId}" }
                offset += 3
            }
            assertEquals("$sort 翻页行数", total, paged.size)
            assertEquals("$sort 翻页出现重复", total, paged.distinct().size)
            assertEquals("$sort 翻页与全量结果不一致", all, paged)
        }
    }

    @Test
    fun `limit 非正直接返回空页`() {
        insertAll(listOf(WorkSpec(illustPublic, 1)))
        assertTrue(works(limit = 0).isEmpty())
        assertTrue(works(limit = -1).isEmpty())
    }

    // ─────────────────────────── 空账号 ───────────────────────────

    @Test
    fun `未登录账号所有查询都是空结果`() {
        insertAll(listOf(WorkSpec(illustPublic, 1)))
        assertTrue(runBlocking { AllBookmarkRepository.loadStats(0L, true) }.let {
            it.totalWorks == 0 && it.distinctTagCount == 0 && it.illustCount == 0
        })
        assertTrue(runBlocking { AllBookmarkRepository.loadTags(0L, true) }.isEmpty())
        assertTrue(runBlocking { AllBookmarkRepository.loadWorks(0L, true, null, "", emptyList(), AllBookmarkSort.NEWEST_PUBLISHED, 10, 0) }.isEmpty())
        assertEquals(0, runBlocking { AllBookmarkRepository.countWorks(0L, true, null, "", emptyList()) })
    }

    // ─────────────── 搜索筛选（AllBookmarkWorkFilter）真库行为 ───────────────
    //
    // 这一组只验证「条件真的能筛出正确的行」与「count 与列表同口径、分页不重不漏」；
    // SQL 形状 / 绑定值顺序由 `AllBookmarkWorkFilterQueryTest`（纯 JVM）覆盖。

    /** 新重载用**原始 DB workType 值**，不做 illust→ugoira 合并；旧重载的合并语义必须保持。 */
    @Test
    fun `搜索筛选 workTypes 用原始值且旧重载合并语义不变`() {
        insertAll(
            listOf(
                WorkSpec(illustPublic, 1, workType = "illust"),
                WorkSpec(illustPublic, 2, workType = "ugoira"),
                WorkSpec(illustPublic, 3, workType = "manga"),
                WorkSpec(novelPublic, 4, workType = "novel"),
            )
        )
        assertEquals(listOf(1L), filterIds(AllBookmarkWorkFilter(workTypes = listOf("illust"))))
        assertEquals(listOf(2L), filterIds(AllBookmarkWorkFilter(workTypes = listOf("ugoira"))))
        assertEquals(listOf(1L, 2L), filterIds(AllBookmarkWorkFilter(workTypes = listOf("illust", "ugoira"))))
        assertEquals(listOf(3L), filterIds(AllBookmarkWorkFilter(workTypes = listOf("manga"))))
        assertEquals(listOf(4L), filterIds(AllBookmarkWorkFilter(workTypes = listOf("novel"))))
        assertEquals(listOf(1L, 2L, 3L, 4L), filterIds(AllBookmarkWorkFilter()))

        // 兼容入口：旧重载的 resolveWorkTypes 合并（illust 含 ugoira）逐条不变
        assertEquals(listOf(1L, 2L), workIds(workTypes = listOf("illust")).sorted())
        assertEquals(2, runBlocking { AllBookmarkRepository.countWorks(uid, true, null, "", listOf("illust")) })
        // 旧重载转新重载后 count 与列表仍一致
        assertEquals(workIds(tagName = null, workTypes = listOf("illust")).size,
            runBlocking { AllBookmarkRepository.countWorks(uid, true, null, "", listOf("illust")) })
    }

    @Test
    fun `搜索筛选 投稿期间是闭区间且排除未知时间`() {
        insertAll(
            listOf(
                WorkSpec(illustPublic, 101, createDateMs = base - 1 * DAY_MS),
                WorkSpec(illustPublic, 102, createDateMs = base - 30 * DAY_MS),
                WorkSpec(illustPublic, 103, createDateMs = base - 400 * DAY_MS),
                WorkSpec(illustPublic, 104, createDateMs = 0), // 发布时间未知（失效作品）
            )
        )
        // 只设下限：未知时间行不会因为 createDateMs=0 混进来
        assertEquals(listOf(101L, 102L), filterIds(AllBookmarkWorkFilter(createdFromMs = base - 60 * DAY_MS)))
        // 只设上限：未知时间行被 createDateMs > 0 挡掉（否则它 <= 任何上限）
        assertEquals(listOf(102L, 103L), filterIds(AllBookmarkWorkFilter(createdToMs = base - 5 * DAY_MS)))
        // 闭区间
        assertEquals(listOf(102L), filterIds(
            AllBookmarkWorkFilter(createdFromMs = base - 40 * DAY_MS, createdToMs = base - 2 * DAY_MS),
        ))
        assertEquals(listOf(103L), filterIds(
            AllBookmarkWorkFilter(createdFromMs = base - 500 * DAY_MS, createdToMs = base - 300 * DAY_MS),
        ))
        assertEquals(0, filterCount(AllBookmarkWorkFilter(createdFromMs = base + 1 * DAY_MS)))
    }

    @Test
    fun `搜索筛选 喜欢数闭区间与 users入り跨类型成对命中`() {
        insertAll(
            listOf(
                WorkSpec(illustPublic, 21, totalBookmarks = 500, tags = listOf("1000users入り" to "")),
                WorkSpec(illustPublic, 22, totalBookmarks = 100, tags = listOf("50users入り" to "")),
                WorkSpec(novelPublic, 23, workType = "novel", totalBookmarks = 2_000, tags = listOf("5000users入り" to "")),
                WorkSpec(novelPublic, 24, workType = "novel", totalBookmarks = 50),
                // ⚠️ 跨内容类型 id 撞号：插画 #7 带 users入り、小说 #7 不带。
                // users入り 若只按 targetId 匹配就会把小说 #7 误算进来。
                WorkSpec(illustPublic, 7, totalBookmarks = 50, tags = listOf("1000users入り" to "")),
                WorkSpec(novelPublic, 7, workType = "novel", totalBookmarks = 50, tags = listOf("50users入り" to "")),
            )
        )
        assertEquals(listOf(21L, 22L), filterIds(AllBookmarkWorkFilter(minBookmarks = 100, maxBookmarks = 1_000)))
        assertEquals(listOf(23L), filterIds(AllBookmarkWorkFilter(minBookmarks = 1_000)))
        assertTrue(filterIds(AllBookmarkWorkFilter(maxBookmarks = 49)).isEmpty())

        assertEquals(listOf(23L), filterIds(AllBookmarkWorkFilter(usersTagMin = 5_000)))
        assertEquals(listOf(7L, 7L, 21L, 22L, 23L), filterIds(AllBookmarkWorkFilter(usersTagMin = 1)))
        // #7 只应命中插画行（(shelfKey,targetId) 成对），小说 #7 的 50users入り不算
        val usersHits = filterWorks(AllBookmarkWorkFilter(usersTagMin = 1_000))
            .map { "${it.shelfKey}#${it.targetId}" }
            .sorted()
        assertEquals(
            listOf("${illustPublic.key}#21", "${illustPublic.key}#7", "${novelPublic.key}#23").sorted(),
            usersHits,
        )
        assertEquals(usersHits.size, filterCount(AllBookmarkWorkFilter(usersTagMin = 1_000)))
    }

    @Test
    fun `搜索筛选 AI 与 R18 三档`() {
        insertAll(
            listOf(
                WorkSpec(illustPublic, 31, aiType = 0, xRestrict = 0),
                WorkSpec(illustPublic, 32, aiType = 2, xRestrict = 0),
                WorkSpec(illustPublic, 33, aiType = 0, xRestrict = 1),
                WorkSpec(illustPublic, 34, aiType = 2, xRestrict = 2),
            )
        )
        assertEquals(listOf(32L, 34L), filterIds(AllBookmarkWorkFilter(ai = AllBookmarkAiFilter.ONLY_AI)))
        // EXCLUDE 用 !=2：aiType=0（未知）归到「非 AI」一侧
        assertEquals(listOf(31L, 33L), filterIds(AllBookmarkWorkFilter(ai = AllBookmarkAiFilter.EXCLUDE_AI)))
        assertEquals(listOf(31L, 32L), filterIds(AllBookmarkWorkFilter(age = AllBookmarkAgeFilter.SAFE_ONLY)))
        // R18_ONLY 用 xRestrict > 0：R-18G 一并算进去
        assertEquals(listOf(33L, 34L), filterIds(AllBookmarkWorkFilter(age = AllBookmarkAgeFilter.R18_ONLY)))
        // 组合：非 AI + 全年龄
        assertEquals(listOf(31L), filterIds(
            AllBookmarkWorkFilter(ai = AllBookmarkAiFilter.EXCLUDE_AI, age = AllBookmarkAgeFilter.SAFE_ONLY),
        ))
    }

    @Test
    fun `搜索筛选 长宽比与分辨率档位`() {
        insertAll(
            listOf(
                WorkSpec(illustPublic, 41, width = 1_600, height = 900), // 横
                WorkSpec(illustPublic, 42, width = 800, height = 1_200), // 竖
                WorkSpec(illustPublic, 43, width = 1_000, height = 1_000), // 方
                WorkSpec(illustPublic, 44, width = 3_200, height = 4_000), // 竖 + 超高分辨率
                WorkSpec(illustPublic, 45, width = 900, height = 800), // 横 + 低分辨率
            )
        )
        assertEquals(listOf(41L, 45L), filterIds(AllBookmarkWorkFilter(ratio = AllBookmarkRatioFilter.LANDSCAPE)))
        assertEquals(listOf(42L, 44L), filterIds(AllBookmarkWorkFilter(ratio = AllBookmarkRatioFilter.PORTRAIT)))
        assertEquals(listOf(43L), filterIds(AllBookmarkWorkFilter(ratio = AllBookmarkRatioFilter.SQUARE)))

        assertEquals(listOf(44L), filterIds(
            AllBookmarkWorkFilter(resolution = AllBookmarkResolutionFilter.ABOVE_3000),
        ))
        assertEquals(listOf(43L), filterIds(
            AllBookmarkWorkFilter(resolution = AllBookmarkResolutionFilter.BETWEEN_1000_AND_2999),
        ))
        assertEquals(listOf(45L), filterIds(
            AllBookmarkWorkFilter(resolution = AllBookmarkResolutionFilter.BELOW_1000),
        ))
        // 组合：竖图 + 1000~2999（#42 竖但 1200x800? 不，42 是 800x1200 → 高 1200 在区间、宽 800 不在）
        assertEquals(emptyList<Long>(), filterIds(
            AllBookmarkWorkFilter(
                ratio = AllBookmarkRatioFilter.PORTRAIT,
                resolution = AllBookmarkResolutionFilter.BETWEEN_1000_AND_2999,
            ),
        ))
    }

    @Test
    fun `搜索筛选 页数与正文长度`() {
        insertAll(
            listOf(
                WorkSpec(illustPublic, 51, pageCount = 1),
                WorkSpec(illustPublic, 52, workType = "manga", pageCount = 8),
                WorkSpec(novelPublic, 53, workType = "novel", pageCount = 1, textLength = 5_000),
                WorkSpec(novelPublic, 54, workType = "novel", pageCount = 1, textLength = 30_000),
            )
        )
        assertEquals(listOf(51L, 53L, 54L), filterIds(
            AllBookmarkWorkFilter(pages = AllBookmarkPageFilter.SINGLE_PAGE),
        ))
        assertEquals(listOf(52L), filterIds(AllBookmarkWorkFilter(pages = AllBookmarkPageFilter.MULTI_PAGE)))
        assertEquals(listOf(53L, 54L), filterIds(AllBookmarkWorkFilter(minTextLength = 4_000)))
        // 插画行 textLength=0，天然满足「<= 上限」；只限上限时它们会和短篇小说一起出现
        assertEquals(listOf(51L, 52L, 53L), filterIds(AllBookmarkWorkFilter(maxTextLength = 10_000)))
        assertEquals(listOf(54L), filterIds(
            AllBookmarkWorkFilter(minTextLength = 10_000, maxTextLength = 40_000),
        ))
        // 插画行 textLength=0：设了正文长度下限就被自然排除（前端只看小说时也已限定 workTypes）
        assertTrue(filterIds(AllBookmarkWorkFilter(minTextLength = 1)).all { it > 52L })
    }

    @Test
    fun `搜索筛选 tagName 大小写不敏感且可与 keyword 组合`() {
        insertAll(
            listOf(
                WorkSpec(illustPublic, 61, title = "初音ミクのイラスト", tags = listOf("オリジナル" to "原创")),
                WorkSpec(illustPublic, 62, title = "オリジナル漫画", tags = listOf("オリジナル" to "原创", "CLIP STUDIO PAINT" to "")),
                WorkSpec(illustPublic, 63, title = "別の作品", tags = listOf("猫" to "猫")),
            )
        )
        // tagName 既接受展示名（原始大小写）也接受小写口径
        assertEquals(listOf(61L, 62L), filterIds(AllBookmarkWorkFilter(tagName = "オリジナル")))
        assertEquals(listOf(62L), filterIds(AllBookmarkWorkFilter(tagName = "clip studio paint")))
        assertEquals(listOf(62L), filterIds(AllBookmarkWorkFilter(tagName = "  CLIP STUDIO PAINT  ")))
        // keyword 逐词 AND（复用 MirrorKeywordSql 口径）
        assertEquals(listOf(61L, 62L), filterIds(AllBookmarkWorkFilter(keyword = "オリジナル")))
        assertEquals(listOf(61L), filterIds(AllBookmarkWorkFilter(keyword = "初音 イラスト")))
        assertEquals(listOf(62L), filterIds(AllBookmarkWorkFilter(tagName = "オリジナル", keyword = "CLIP")))
        assertTrue(filterIds(AllBookmarkWorkFilter(tagName = "オリジナル", keyword = "猫")).isEmpty())
    }

    @Test
    fun `搜索筛选 组合条件 count 与列表一致且分页不重不漏`() {
        insertAll(
            listOf(
                WorkSpec(illustPublic, 71, createDateMs = base - 1 * DAY_MS, totalBookmarks = 100, tags = listOf("1000users入り" to "")),
                WorkSpec(illustPublic, 72, createDateMs = base - 3 * DAY_MS, totalBookmarks = 300, tags = listOf("5000users入り" to "")),
                WorkSpec(illustPublic, 73, workType = "manga", createDateMs = base - 2 * DAY_MS, totalBookmarks = 200, tags = listOf("1000users入り" to "")),
                // 被条件排除的行：AI / R18 / 无 users入り / 发布时间未知
                WorkSpec(illustPublic, 74, createDateMs = base - 1 * DAY_MS, totalBookmarks = 400, aiType = 2, tags = listOf("1000users入り" to "")),
                WorkSpec(illustPublic, 75, createDateMs = base - 2 * DAY_MS, totalBookmarks = 500, xRestrict = 1, tags = listOf("1000users入り" to "")),
                WorkSpec(illustPublic, 76, createDateMs = base - 2 * DAY_MS, totalBookmarks = 600),
                WorkSpec(illustPublic, 77, createDateMs = 0, totalBookmarks = 700, tags = listOf("1000users入り" to "")),
                // 满足全部条件、用于验证分页的多行
                WorkSpec(illustPublic, 78, createDateMs = base - 4 * DAY_MS, totalBookmarks = 50, tags = listOf("5000users入り" to "")),
                WorkSpec(illustPublic, 79, createDateMs = base - 5 * DAY_MS, totalBookmarks = 60, tags = listOf("10000users入り" to "")),
            )
        )
        val filter = AllBookmarkWorkFilter(
            workTypes = listOf("illust", "manga"),
            createdFromMs = base - 10 * DAY_MS,
            usersTagMin = 1_000,
            ai = AllBookmarkAiFilter.EXCLUDE_AI,
            age = AllBookmarkAgeFilter.SAFE_ONLY,
        )
        val expectedIds = listOf(71L, 72L, 73L, 78L, 79L)
        assertEquals(expectedIds.sorted(), filterIds(filter))
        assertEquals(expectedIds.size, filterCount(filter))

        // 分页：每页 2 条翻完，多个排序下都必须与全量结果逐条一致、不重不漏
        listOf(
            AllBookmarkSort.NEWEST_PUBLISHED,
            AllBookmarkSort.OLDEST_PUBLISHED,
            AllBookmarkSort.MOST_BOOKMARKED,
            AllBookmarkSort.TRENDING,
        ).forEach { sort ->
            val full = filterWorks(filter, sort = sort, limit = 100)
                .map { "${it.shelfKey}#${it.targetId}" }
            assertEquals("$sort 全量行数", expectedIds.size, full.size)

            val paged = mutableListOf<String>()
            var offset = 0
            while (true) {
                val page = filterWorks(filter, sort = sort, limit = 2, offset = offset)
                if (page.isEmpty()) break
                paged += page.map { "${it.shelfKey}#${it.targetId}" }
                offset += 2
            }
            assertEquals("$sort 翻页行数", full.size, paged.size)
            assertEquals("$sort 翻页出现重复", full.size, paged.distinct().size)
            assertEquals("$sort 翻页与全量顺序不一致", full, paged)
        }
    }

    @Test
    fun `搜索筛选 limit 非正直接返回空页且未登录返回空`() {
        insertAll(listOf(WorkSpec(illustPublic, 1)))
        assertTrue(filterWorks(AllBookmarkWorkFilter(), limit = 0).isEmpty())
        assertTrue(filterWorks(AllBookmarkWorkFilter(), limit = -1).isEmpty())
        assertEquals(0, runBlocking { AllBookmarkRepository.countWorks(0L, true, AllBookmarkWorkFilter()) })
        assertTrue(runBlocking {
            AllBookmarkRepository.loadWorks(
                0L, true, AllBookmarkWorkFilter(), AllBookmarkSort.NEWEST_PUBLISHED, 10, 0,
            )
        }.isEmpty())
    }
}
