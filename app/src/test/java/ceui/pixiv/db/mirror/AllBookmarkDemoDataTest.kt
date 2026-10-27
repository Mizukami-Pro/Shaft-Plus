package ceui.pixiv.db.mirror

import ceui.lisa.activities.Shaft
import ceui.loxia.Novel
import ceui.pixiv.api.model.Illust
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AllBookmarkDemoData] 的纯 JVM 单测（不需要 Robolectric / Room）。
 *
 * 演示数据是给模拟器验收截图用的，本身不查库、不发网络请求，所以可以直接在 JVM 上验证：
 * 统计与验收口径一致、标签云排序与搜索、作品过滤（标签/关键词/类型）、五种排序单调、
 * 分页不重不漏、payloadJson 能被真实模型反序列化。
 *
 * 另外顺带验证 [AllBookmarkRepository] 的 demo 分支：统一入口在 debug 演示模式下返回内存数据，
 * 且 `ensureShelves/syncNow/shelvesFor` 安全空转（不触碰镜像库与网络）。
 */
class AllBookmarkDemoDataTest {

    private val gson = Gson()

    @After
    fun tearDown() {
        // 演示开关是进程级静态量，测试之间必须还原，否则后续测试会被串场。
        AllBookmarkDemoData.enabled = false
    }

    private fun works(
        tagName: String? = null,
        keyword: String = "",
        workTypes: List<String> = emptyList(),
        sort: AllBookmarkSort = AllBookmarkSort.NEWEST_PUBLISHED,
        limit: Int = 500,
        offset: Int = 0,
    ): List<BookmarkMirrorEntity> =
        AllBookmarkDemoData.works(tagName, keyword, workTypes, sort, limit, offset)

    @Test
    fun `统计与标签云满足验收口径`() {
        val stats = AllBookmarkDemoData.stats(includePrivate = true)
        assertEquals(126, stats.totalWorks)
        assertEquals(78, stats.illustCount)
        assertEquals(21, stats.mangaCount)
        assertEquals(27, stats.novelCount)
        assertEquals(34, stats.distinctTagCount)

        val tags = AllBookmarkDemoData.tags(includePrivate = true)
        assertEquals(34, tags.size)
        // 命中数从多到少
        tags.zipWithNext().forEach { (a, b) ->
            assertTrue("${a.tagName}(${a.count}) 应 >= ${b.tagName}(${b.count})", a.count >= b.count)
        }
        // 验收清单里点名的 12 个分类都要在，且带译名
        val required = listOf(
            "初音ミク" to "初音未来",
            "オリジナル" to "原创",
            "女の子" to "女孩子",
            "風景" to "风景",
            "東方Project" to "东方Project",
            "CLIP STUDIO PAINT" to "CLIP STUDIO PAINT",
            "百合" to "百合",
            "猫" to "猫",
            "水着" to "泳装",
            "制服" to "制服",
            "ファンタジー" to "奇幻",
            "落書き" to "涂鸦",
        )
        required.forEach { (display, translated) ->
            val tag = tags.firstOrNull { it.displayName == display }
            assertNotNull("标签云缺少 $display", tag)
            assertEquals("$display 的小写归一", display.lowercase(), tag!!.tagName)
            assertEquals("$display 的译名", translated, tag.translatedName)
            assertTrue("$display 应至少命中一件作品", tag.count >= 1)
        }
    }

    @Test
    fun `标签云可按原名译名搜索并按 limit 截断`() {
        // 原名（日文）
        assertEquals(
            listOf("初音ミク"),
            AllBookmarkDemoData.tags(includePrivate = true, query = "初音").map { it.displayName },
        )
        // 译名
        assertEquals(
            listOf("オリジナル"),
            AllBookmarkDemoData.tags(includePrivate = true, query = "原创").map { it.displayName },
        )
        // 拉丁名大小写不敏感
        assertEquals(
            listOf("CLIP STUDIO PAINT"),
            AllBookmarkDemoData.tags(includePrivate = true, query = "clip studio").map { it.displayName },
        )
        assertTrue(AllBookmarkDemoData.tags(includePrivate = true, query = "不存在的分类").isEmpty())
        assertEquals(5, AllBookmarkDemoData.tags(includePrivate = true, limit = 5).size)
        assertEquals(34, AllBookmarkDemoData.tags(includePrivate = true, limit = 0).size)
    }

    @Test
    fun `作品按标签关键词与类型过滤`() {
        // 标签：大小写/首尾空白不影响结果
        val byTag = works(tagName = "初音ミク")
        assertTrue(byTag.isNotEmpty())
        assertEquals(AllBookmarkDemoData.DEMO_UID, byTag.first().ownerUid)
        byTag.forEach { row ->
            val tagNames = payloadTagNames(row)
            assertTrue("targetId=${row.targetId} 应带初音ミク: $tagNames", tagNames.contains("初音ミク"))
        }
        assertEquals(byTag.size, works(tagName = "  初音ミク  ").size)
        assertEquals(works(tagName = "CLIP STUDIO PAINT").size, works(tagName = "clip studio paint").size)
        assertTrue(works(tagName = "不存在的标签").isEmpty())

        // 关键词：命中标题/作者/标签，逐词 AND
        val byAuthor = works(keyword = "星野ミナ")
        assertTrue(byAuthor.isNotEmpty())
        byAuthor.forEach { assertEquals("星野ミナ", it.authorName) }
        assertTrue(works(keyword = "初音").all { it.searchText.contains("初音") })
        assertTrue(works(keyword = "星野ミナ 初音").size <= byAuthor.size)
        assertTrue(works(keyword = "绝对不存在的关键词").isEmpty())

        // 类型：illust 含 ugoira（演示数据无 ugoira）、空 = 全部
        val illusts = works(workTypes = listOf("illust"))
        assertEquals(78, illusts.size)
        illusts.forEach { assertEquals("illust", it.workType) }
        assertEquals(21, works(workTypes = listOf("manga")).size)
        assertEquals(27, works(workTypes = listOf("novel")).size)
        assertEquals(126, works(workTypes = emptyList()).size)
        // 组合：小说 + 初音ミク
        works(tagName = "初音ミク", workTypes = listOf("novel")).forEach {
            assertEquals("novel", it.workType)
            assertTrue(payloadTagNames(it).contains("初音ミク"))
        }
    }

    @Test
    fun `五种排序的单调性正确`() {
        fun rows(sort: AllBookmarkSort) = works(sort = sort)
        rows(AllBookmarkSort.NEWEST_PUBLISHED).zipWithNext().forEach { (a, b) ->
            assertTrue("${a.createDateMs} >= ${b.createDateMs}", a.createDateMs >= b.createDateMs)
        }
        rows(AllBookmarkSort.OLDEST_PUBLISHED).zipWithNext().forEach { (a, b) ->
            assertTrue("${a.createDateMs} <= ${b.createDateMs}", a.createDateMs <= b.createDateMs)
        }
        rows(AllBookmarkSort.MOST_BOOKMARKED).zipWithNext().forEach { (a, b) ->
            assertTrue("${a.totalBookmarks} >= ${b.totalBookmarks}", a.totalBookmarks >= b.totalBookmarks)
        }
        rows(AllBookmarkSort.MOST_VIEWED).zipWithNext().forEach { (a, b) ->
            assertTrue("${a.totalView} >= ${b.totalView}", a.totalView >= b.totalView)
        }
        // 趋势：收藏速率 = totalBookmarks / max(1, 距今天数)，同样必须单调不增。
        // 比较用测试时刻重算，留 1e-6 容差吸收「查询时刻 vs 断言时刻」的毫秒级漂移。
        val now = System.currentTimeMillis()
        val rates = rows(AllBookmarkSort.TRENDING).map { row ->
            row.totalBookmarks / ((now - row.createDateMs) / 86_400_000.0).coerceAtLeast(1.0)
        }
        rates.zipWithNext().forEach { (a, b) ->
            assertTrue("趋势应单调不增：$a, $b", a + 1e-6 >= b)
        }
        // 五种排序应当真的不同（否则演示页切排序看不出变化）
        val signatures = AllBookmarkSort.entries.map { sort -> rows(sort).map { it.targetId } }
        assertTrue("五种排序的结果顺序不应完全相同", signatures.distinct().size >= 4)
    }

    @Test
    fun `主排序键并列时按 targetId 与 shelfKey 兜底 —— 分页不重不漏`() {
        val full = works(sort = AllBookmarkSort.MOST_BOOKMARKED).map { "${it.shelfKey}#${it.targetId}" }
        assertEquals(126, full.size)

        val paged = mutableListOf<String>()
        var offset = 0
        while (true) {
            val page = works(sort = AllBookmarkSort.MOST_BOOKMARKED, limit = 7, offset = offset)
            if (page.isEmpty()) break
            paged += page.map { "${it.shelfKey}#${it.targetId}" }
            offset += 7
        }
        assertEquals("分页总数", full.size, paged.size)
        assertEquals("分页不应有重复", full.size, paged.distinct().size)
        assertEquals("分页应与全量顺序一致", full, paged)

        // 非正 limit / 越界 offset
        assertTrue(works(limit = 0).isEmpty())
        assertTrue(works(offset = 10_000).isEmpty())
    }

    @Test
    fun `payloadJson 可被真实模型反序列化`() {
        val illustRow = works(workTypes = listOf("illust"), limit = 1).single()
        assertEquals("illust", illustRow.workType)
        val illust = gson.fromJson(illustRow.payloadJson, Illust::class.java)
        assertNotNull(illust)
        assertTrue(illust.id > 0L)
        assertNotNull(illust.title)
        assertNotNull("封面 medium 必须存在，UI 才能加载", illust.image_urls?.medium)
        assertTrue("illust.tags 不能为空", illust.tags.orEmpty().isNotEmpty())
        assertEquals(illustRow.targetId, illust.id)

        val mangaRow = works(workTypes = listOf("manga"), limit = 1).single()
        assertEquals("manga", mangaRow.workType)
        assertEquals("manga", gson.fromJson(mangaRow.payloadJson, Illust::class.java).type)

        val novelRow = works(workTypes = listOf("novel"), limit = 1).single()
        assertEquals("novel", novelRow.workType)
        val novel = gson.fromJson(novelRow.payloadJson, Novel::class.java)
        assertNotNull(novel)
        assertTrue(novel.id > 0L)
        assertNotNull("小说封面必须存在", novel.image_urls?.large)
        assertTrue("小说字数必须 > 0", (novel.text_length ?: 0) > 0)
        assertTrue(novel.tags.orEmpty().isNotEmpty())
    }

    /**
     * `AllBookmarkRepository` 的 demo 分支：统一入口拿到演示数据，且空转方法不碰镜像/网络。
     *
     * 这里在纯 JVM 里跑，所以 `toIllust` 依赖的 `Shaft.sGson` 需要手动注入（生产里由 Shaft
     * 启动流程赋值）。
     */
    @Test
    fun `repository 演示模式走内存数据且不触碰镜像`() {
        AllBookmarkDemoData.enabled = true
        Shaft.sGson = gson
        val uid = AllBookmarkDemoData.DEMO_UID

        assertEquals(uid, AllBookmarkRepository.currentUid())
        // 演示模式没有真实书架：空转，不注册、不联网
        assertTrue(AllBookmarkRepository.shelvesFor(uid, includePrivate = true).isEmpty())
        AllBookmarkRepository.ensureShelves(uid, includePrivate = true, reason = "unit-test")
        AllBookmarkRepository.syncNow(uid, includePrivate = true, reason = "unit-test")

        val stats = runBlocking { AllBookmarkRepository.loadStats(uid, includePrivate = true) }
        assertEquals(126, stats.totalWorks)
        val tags = runBlocking { AllBookmarkRepository.loadTags(uid, includePrivate = true, query = "初音") }
        assertEquals(listOf("初音ミク"), tags.map { it.displayName })

        val rows = runBlocking {
            AllBookmarkRepository.loadWorks(
                uid, includePrivate = true, tagName = "初音ミク", keyword = "", workTypes = emptyList(),
                sort = AllBookmarkSort.NEWEST_PUBLISHED, limit = 5, offset = 0,
            )
        }
        assertEquals(5, rows.size)
        assertTrue(runBlocking { AllBookmarkRepository.countWorks(uid, true, "初音ミク", "", emptyList()) } >= rows.size)
        // 统一入口返回的行能被 UI 的 toIllust 解析（坏行才返回 null）
        assertTrue(rows.any { AllBookmarkRepository.toIllust(it) != null })
    }

    /** 从 payloadJson 里取作品的标签原名（演示数据的 payload 是真实 Illust/Novel JSON）。 */
    private fun payloadTagNames(row: BookmarkMirrorEntity): List<String> = when (row.workType) {
        "novel" -> gson.fromJson(row.payloadJson, Novel::class.java).tags.orEmpty().mapNotNull { it.name }
        else -> gson.fromJson(row.payloadJson, Illust::class.java).tags.orEmpty().mapNotNull { it.name }
    }

    // ─────────────────────── 演示数据的搜索筛选（AllBookmarkWorkFilter）───────────────────────

    /** 演示模式下「搜索筛选版」重载的统一入口（调用前测试已把 [AllBookmarkDemoData.enabled] 打开）。 */
    private fun demoFilterWorks(
        filter: AllBookmarkWorkFilter,
        sort: AllBookmarkSort = AllBookmarkSort.NEWEST_PUBLISHED,
        limit: Int = 1_000,
        offset: Int = 0,
    ): List<BookmarkMirrorEntity> = runBlocking {
        AllBookmarkRepository.loadWorks(
            AllBookmarkDemoData.DEMO_UID, includePrivate = true, filter = filter,
            sort = sort, limit = limit, offset = offset,
        )
    }

    private fun demoFilterCount(filter: AllBookmarkWorkFilter): Int = runBlocking {
        AllBookmarkRepository.countWorks(AllBookmarkDemoData.DEMO_UID, includePrivate = true, filter = filter)
    }

    /**
     * 演示数据的筛选管道必须能表达 `AllBookmarkWorkFilter` 的每个维度（标签详情页的筛选面板
     * 在模拟器上走的就是这条路径）。各维度语义与 SQL 版一致，见 [AllBookmarkDemoData.works]。
     */
    @Test
    fun `演示数据支持搜索筛选各维度`() {
        AllBookmarkDemoData.enabled = true
        val day = 86_400_000L
        val all = demoFilterWorks(AllBookmarkWorkFilter())
        assertEquals(126, all.size)
        val newest = all.maxOf { it.createDateMs }
        val oldest = all.minOf { it.createDateMs }

        // workTypes 用原始 DB 值（演示数据没有 ugoira 行，所以 ["illust"] 就是 78 件插画）
        assertEquals(78, demoFilterWorks(AllBookmarkWorkFilter(workTypes = listOf("illust"))).size)
        assertEquals(21, demoFilterWorks(AllBookmarkWorkFilter(workTypes = listOf("manga"))).size)
        assertEquals(27, demoFilterWorks(AllBookmarkWorkFilter(workTypes = listOf("novel"))).size)
        assertTrue(demoFilterWorks(AllBookmarkWorkFilter(workTypes = listOf("ugoira"))).isEmpty())

        // 投稿期间闭区间
        val recent = demoFilterWorks(AllBookmarkWorkFilter(createdFromMs = newest - 10 * day))
        assertTrue(recent.isNotEmpty())
        assertTrue(recent.all { it.createDateMs >= newest - 10 * day })
        assertTrue(recent.size < all.size)
        val window = demoFilterWorks(
            AllBookmarkWorkFilter(createdFromMs = newest - 30 * day, createdToMs = newest - 20 * day),
        )
        assertTrue(window.isNotEmpty())
        assertTrue(window.all { it.createDateMs in (newest - 30 * day)..(newest - 20 * day) })

        // 喜欢！数
        val popular = demoFilterWorks(AllBookmarkWorkFilter(minBookmarks = 3_000))
        assertTrue(popular.isNotEmpty())
        assertTrue(popular.all { it.totalBookmarks >= 3_000 })
        assertTrue(demoFilterWorks(AllBookmarkWorkFilter(maxBookmarks = 200)).all { it.totalBookmarks <= 200 })

        // users入り：每 3 件一件（N=1000/5000/10000 轮转）；标签云不收录这类标签
        assertEquals(126 / 3, demoFilterCount(AllBookmarkWorkFilter(usersTagMin = 1)))
        val usersHits = demoFilterWorks(AllBookmarkWorkFilter(usersTagMin = 5_000))
        assertTrue(usersHits.isNotEmpty())
        assertTrue(usersHits.size < 126 / 3)
        assertTrue(
            runBlocking { AllBookmarkRepository.loadTags(AllBookmarkDemoData.DEMO_UID, true) }
                .none { it.tagName.endsWith("users入り") },
        )

        // AI：约 1/7 的作品被标记为 AI
        val aiOnly = demoFilterWorks(AllBookmarkWorkFilter(ai = AllBookmarkAiFilter.ONLY_AI))
        assertEquals(18, aiOnly.size)
        assertTrue(aiOnly.all { it.aiType == 2 })
        assertEquals(126 - 18, demoFilterWorks(AllBookmarkWorkFilter(ai = AllBookmarkAiFilter.EXCLUDE_AI)).size)

        // R-18：演示数据全是全年龄（筛选管线支持，但演示不造限制级内容）
        assertEquals(126, demoFilterCount(AllBookmarkWorkFilter(age = AllBookmarkAgeFilter.SAFE_ONLY)))
        assertEquals(0, demoFilterCount(AllBookmarkWorkFilter(age = AllBookmarkAgeFilter.R18_ONLY)))

        // 长宽比：横/竖/方三档互斥且覆盖全部（演示数据宽高都已知）
        val landscape = demoFilterWorks(AllBookmarkWorkFilter(ratio = AllBookmarkRatioFilter.LANDSCAPE))
        val portrait = demoFilterWorks(AllBookmarkWorkFilter(ratio = AllBookmarkRatioFilter.PORTRAIT))
        val square = demoFilterWorks(AllBookmarkWorkFilter(ratio = AllBookmarkRatioFilter.SQUARE))
        assertTrue(landscape.isNotEmpty() && portrait.isNotEmpty() && square.isNotEmpty())
        assertTrue(landscape.all { it.orientation == BookmarkMirrorMapper.ORIENTATION_LANDSCAPE })
        assertTrue(portrait.all { it.orientation == BookmarkMirrorMapper.ORIENTATION_PORTRAIT })
        assertTrue(square.all { it.orientation == BookmarkMirrorMapper.ORIENTATION_SQUARE })
        assertEquals(99, landscape.size + portrait.size + square.size)
        assertEquals(27, all.count { it.width == 0 && it.height == 0 })

        // 分辨率：演示数据只有 900/1600 与 900/1400 两套尺寸
        assertTrue(demoFilterWorks(AllBookmarkWorkFilter(resolution = AllBookmarkResolutionFilter.ABOVE_3000)).isEmpty())
        val between = demoFilterWorks(AllBookmarkWorkFilter(resolution = AllBookmarkResolutionFilter.BETWEEN_1000_AND_2999))
        assertTrue(between.isNotEmpty())
        assertTrue(between.all { it.width in 1_000..2_999 && it.height in 1_000..2_999 })
        val below = demoFilterWorks(AllBookmarkWorkFilter(resolution = AllBookmarkResolutionFilter.BELOW_1000))
        assertTrue(below.isNotEmpty())
        assertTrue(below.all { it.width <= 999 && it.height <= 999 })

        // 页数
        assertTrue(demoFilterWorks(AllBookmarkWorkFilter(pages = AllBookmarkPageFilter.SINGLE_PAGE)).all { it.pageCount <= 1 })
        assertTrue(demoFilterWorks(AllBookmarkWorkFilter(pages = AllBookmarkPageFilter.MULTI_PAGE)).all { it.pageCount > 1 })

        // 正文长度：只有小说有正文（插画 textLength=0）
        val longNovels = demoFilterWorks(AllBookmarkWorkFilter(minTextLength = 10_000))
        assertTrue(longNovels.isNotEmpty())
        assertTrue(longNovels.all { it.textLength >= 10_000 })
        assertTrue(demoFilterWorks(AllBookmarkWorkFilter(minTextLength = 1)).all { it.workType == "novel" })

        // 标签 + 类型 + AI + 页数组合：count 与列表同口径，分页不重不漏
        val combo = AllBookmarkWorkFilter(
            tagName = "初音ミク",
            workTypes = listOf("illust", "novel"),
            ai = AllBookmarkAiFilter.EXCLUDE_AI,
            pages = AllBookmarkPageFilter.SINGLE_PAGE,
        )
        val comboFull = demoFilterWorks(combo)
        assertEquals(demoFilterCount(combo), comboFull.size)
        assertTrue(comboFull.isNotEmpty())
        assertTrue(comboFull.all { it.workType == "illust" || it.workType == "novel" })
        listOf(AllBookmarkSort.NEWEST_PUBLISHED, AllBookmarkSort.MOST_BOOKMARKED).forEach { sort ->
            val full = demoFilterWorks(combo, sort = sort).map { "${it.shelfKey}#${it.targetId}" }
            val paged = mutableListOf<String>()
            var offset = 0
            while (true) {
                val page = demoFilterWorks(combo, sort = sort, limit = 3, offset = offset)
                if (page.isEmpty()) break
                paged += page.map { "${it.shelfKey}#${it.targetId}" }
                offset += 3
            }
            assertEquals("$sort 分页行数", full.size, paged.size)
            assertEquals("$sort 分页重复", full.size, paged.distinct().size)
            assertEquals("$sort 分页与全量不一致", full, paged)
        }
    }
}
