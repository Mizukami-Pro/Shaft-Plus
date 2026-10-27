package ceui.pixiv.ui.tagbrowse

import ceui.lisa.activities.Shaft
import ceui.pixiv.db.mirror.AllBookmarkAgeFilter
import ceui.pixiv.db.mirror.AllBookmarkAiFilter
import ceui.pixiv.db.mirror.AllBookmarkRepository
import ceui.pixiv.db.mirror.BookmarkMirrorEntity
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedPage
import ceui.pixiv.feeds.FeedPagingPolicy
import ceui.pixiv.feeds.FeedSource
import ceui.pixiv.ui.common.IllustFeedItem
import ceui.pixiv.ui.common.NovelFeedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 标签详情页的数据源：**纯本地**，一次查一页镜像表（跨 4 个书架聚合）。
 *
 * ## 游标为什么是 String
 *
 * 本页复用 [ceui.pixiv.ui.common.IllustFeedFragment]（白拿标准 waterfall 插画卡、长按菜单、
 * 详情回传、收藏红心广播同步），而它把游标类型钉死成 `String`。本地分页真正需要的是
 * 已消费行数，所以这里把 offset 编码成字符串。页面同时把
 * [AllBookmarkTagDetailFragment.detailContinuationCursor] 覆写成 null —— 详情页的续拉会把
 * 游标当 `@Url` 直接请求，本地 offset 绝不能流到那条路上去（对齐
 * [ceui.pixiv.ui.library.BookmarkLibraryFeedSource] 的同款取舍）。
 *
 * ## 分页边界
 *
 * SQL 的 LIMIT/OFFSET 用的是数据层五种排序**都带全序兜底键**的 ORDER BY
 * （主排序键 + `targetId DESC` + `shelfKey ASC`，见 `AllBookmarkMirrorQuery.orderClause`）：
 * 插画 id 与小说 id 来自两套编号可能撞号，没有兜底键时并列行的次序在两次查询之间会抖动，
 * LIMIT/OFFSET 就会重/漏。有了全序键，翻页只受「边翻边同步导致行号漂移」影响 —— 那种
 * 漂移在收藏库页同样存在，可接受（用户下拉刷新就能重新对齐）。
 *
 * ⚠️「趋势」排序的速率表达式里带查询时刻 `now`：两页之间隔了几秒，理论上边界附近两条
 * 可能换位。feeds 的 [ceui.pixiv.feeds.FeedViewModel.loadMore] 按条目身份去重，重复条目
 * 不会上屏；极小概率漏掉一条边界条目，与 `BookmarkLibrary` 的本地分页是同一量级的取舍。
 *
 * ## 条件全部在 SQL 里
 *
 * 顶部作品类别 chips、搜索框、筛选面板（投稿期间 / 喜欢！数 / users入り / AI / R-18 /
 * 长宽比 / 分辨率 / 页数）都收进一个 [ceui.pixiv.db.mirror.AllBookmarkWorkFilter]，由 VM
 * （[AllBookmarkTagDetailViewModel.resolvedFilter]）注入页面固定的标签名后整份下推新重载
 * `loadWorks(uid, includePrivate, filter, sort, limit, offset)`：筛选发生在索引扫描里，
 * 列表里拿到的每一条都已经是命中结果，翻页也不会被「整页被筛空」拖住。
 *
 * 条件的初值由 [AllBookmarkTagDetailViewModel] 在**构造期**从 SavedStateHandle 恢复
 * （进程死亡重建后回到用户离开前那套条件）；该 VM 先于 FeedViewModel 构造，所以本方法
 * **第一次**被调用时读到的就是恢复后的条件，不存在「先按默认条件查一页再被页面纠正」的
 * 中间态，筛选面板的 draft（由 DialogFragment 自己 saved state 恢复）也不会和列表对不上。
 *
 * ## 内容过滤跟着「它替代的那个页面」走
 *
 * 本页是「我的插画/小说收藏 + 标签筛选」，所以过滤链与 [ceui.pixiv.ui.collection.LikeIllustFeedFragment]
 * / [ceui.pixiv.ui.collection.LikeNovelFeedFragment] 逐条对齐：屏蔽标签 / 屏蔽画师 / R18 /
 * AI 屏蔽照常生效（那几条是用户对特定内容的显式表态，换个入口绕过去等于偷偷放回来），
 * 「过滤无效收藏」开关决定失效作品显不显示，小说侧额外让步反刷屏阈值（issue #743）。
 *
 * 唯一让步与收藏库页同款：用户在筛选面板里**显式选了** AI / R-18 档位时，条目层不再套
 * 全局同名过滤——否则「仅看 AI」「仅 R-18」会被全局开关清空，变成两个永远筛出 0 条的死按钮。
 *
 * 计数（`countWorks`）是 SQL 口径，不经过这层过滤，所以「共 N 件」与屏幕上已加载的条目数
 * 可能有出入 —— 与收藏库页的既有行为一致。
 */
class AllBookmarkTagDetailFeedSource(
    private val viewModel: AllBookmarkTagDetailViewModel,
) : FeedSource<String> {

    override suspend fun load(cursor: String?): FeedPage<String> {
        val offset = cursor?.toIntOrNull() ?: 0
        // 条件每次现读：用户在 chips / 搜索框 / 筛选面板改了条件，页面调一次 refresh() 就是
        // 新结果，数据源不必重建（同 BookmarkLibraryFeedSource 的约定）。
        if (viewModel.uid <= 0L || viewModel.tagName.isEmpty()) {
            // 未登录 / 入口没给标签名：没有可查的范围。给一页空的，页面显示空态而不是错误态。
            // ⚠️ 标签名为空时绝不能把 tagName 当 null 下推——那是「不限标签」，会在标签详情页里
            // 把所有收藏都列出来，用户点的是 #某个标签，看到的却是全部收藏。
            return FeedPage(emptyList(), null)
        }
        // 页面固定的标签名由 VM 注入 filter；workTypes / keyword / 面板条件也在这一个对象里。
        // 新重载只吃 filter，旧重载（tagName/keyword/workTypes 三段参数）保留但本页不再用。
        val filter = viewModel.resolvedFilter()
        val rows: List<BookmarkMirrorEntity> = AllBookmarkRepository.loadWorks(
            uid = viewModel.uid,
            includePrivate = viewModel.includePrivate,
            filter = filter,
            sort = viewModel.resolvedSort(),
            limit = PAGE_SIZE,
            offset = offset,
        )
        // 用户在筛选面板里**显式选了** AI / R-18 档位时，条目层的全局过滤链要让路：
        // 否则「仅看 AI」会被全局「不显示 AI 作品」清空、「仅 R-18」会被全局 R18 过滤清空，
        // 那个控件就成了永远筛出 0 条的死按钮。判定与收藏库页（BookmarkLibraryFeedSource）
        // 的同名让步逐字一致。
        val skipR18Filter = filter.age != AllBookmarkAgeFilter.ANY
        val skipAiFilter = filter.ai != AllBookmarkAiFilter.ANY
        // ⚠️ 必须切线程：[FeedViewModel] 是在 viewModelScope（主线程）里直接 await
        // source.load() 的，这里没有 withContext 包住的部分都是主线程代码。一页 60 条完整
        // Illust/Novel JSON 的 gson 反序列化在中端机上是几十到上百毫秒，落在滚动路径上就是
        // 肉眼可见的掉帧（同 BookmarkLibraryFeedSource / PixivFeedSource 的契约）。
        val items: List<FeedItem> = withContext(Dispatchers.Default) {
            val filterInvalid = Shaft.sSettings.isFilterInvalidBookmarks
            rows.mapNotNull { row ->
                row.toFeedItem(
                    filterInvalid = filterInvalid,
                    skipR18Filter = skipR18Filter,
                    skipAiFilter = skipAiFilter,
                )
            }
        }
        // 不足一页 = 到底了。判据是**查回来的行数**而不是映射后的条目数：中间夹着几条反序列化
        // 失败 / 被全局过滤掉的行时，用条目数会提前判定到底，把后面的作品全吞掉。
        val nextCursor = if (rows.size < PAGE_SIZE) null else (offset + rows.size).toString()
        return FeedPage(items, nextCursor)
    }

    /**
     * 本地翻页没有网络代价，所以**间隔闸门**（`minPageIntervalMs`）可以整个去掉 ——
     * 那一条是为了别把 pixiv 打成 429 才存在的，对着自己的 SQLite 只会让用户滑到一半白等。
     *
     * 但**页数预算必须留着**，不能直接用 [FeedPagingPolicy.Unlimited]：预算除了限流，还是
     * 唯一约束「列表能长到多大」的东西。[ceui.pixiv.feeds.FeedViewModel] 把翻过的页**累加**
     * 在内存里，收藏几万件的用户在这里一路甩下去是零成本的 —— 3 万条 `Illust`（每条带
     * tags / user / image_urls）能吃掉上百 MB，直接 OOM。连着甩满 [BURST_PAGE_BUDGET] 页
     * （约 1800 件）就停下来等用户点 footer，停手超过 `burstIdleResetMs` 预算归零。
     */
    override fun pagingPolicy(): FeedPagingPolicy = LOCAL_POLICY

    private companion object {
        /** 本地一页 60 条：查询是索引扫描，反序列化才是大头，60 条约几毫秒。 */
        const val PAGE_SIZE = 60

        /** 一串连着翻最多累积多少页（× [PAGE_SIZE] ≈ 1800 件）。 */
        const val BURST_PAGE_BUDGET = 30

        val LOCAL_POLICY = FeedPagingPolicy(
            maxAutoPages = BURST_PAGE_BUDGET,
            minPageIntervalMs = 0L,
        )
    }
}

/**
 * 镜像行 → 混排条目。逐条对齐收藏页（[ceui.pixiv.ui.collection.LikeIllustFeedFragment] /
 * [ceui.pixiv.ui.collection.LikeNovelFeedFragment]）的映射与过滤链。
 *
 * @param filterInvalid 「过滤无效收藏」设置。开启时挡住 `visible=false` 的失效作品和
 *   user 缺失 / id=0 的残缺条目；关闭时让失效作品照常显示（灰卡），用户自己还能点进去
 *   看到「已删除」——对齐收藏页的既有立场。
 * @param skipR18Filter 用户在筛选面板显式选了 R-18 档位时为 true：条目层不再套全局 R18 过滤，
 *   否则「仅 R-18」会被全局开关清空。判定见 load() 里的说明。
 * @param skipAiFilter 同上，对应 AI 三档。
 */
private fun BookmarkMirrorEntity.toFeedItem(
    filterInvalid: Boolean,
    skipR18Filter: Boolean,
    skipAiFilter: Boolean,
): FeedItem? {
    // ⚠️ 必须块体而不是 `= when (...)` 表达式体：分支里用 `return null` 跳过坏行 / 被挡住的
    // 行，而 Kotlin 不允许表达式体函数里出现 return。
    return when (contentType) {
        MirrorContentType.ILLUST.code -> {
            val illust = AllBookmarkRepository.toIllust(this) ?: return null
            if (filterInvalid && (illust.user == null || illust.user.id == 0L)) return null
            IllustFeedItem.of(
                illust,
                skipR18Filter = skipR18Filter,
                skipAiFilter = skipAiFilter,
                skipVisibleFilter = !filterInvalid,
            )
        }
        MirrorContentType.NOVEL.code -> {
            val novel = AllBookmarkRepository.toNovel(this) ?: return null
            // visible 用 `== false` 而不是 `!= true`：字段缺失不是「作品失效」的证据，
            // 只认服务端明确说的 false（同 LikeNovelFeedFragment 的取舍）。
            if (filterInvalid &&
                (novel.visible == false || novel.user == null || novel.user?.id == 0L)
            ) {
                return null
            }
            // 收藏夹让步「小说自动屏蔽」（issue #743）：字数/超长 tag 阈值是冲着发现面上的
            // 刷屏广告去的，不该把用户自己收藏过的短文从藏书里抹掉。
            NovelFeedItem.of(
                novel,
                skipR18Filter = skipR18Filter,
                skipAiFilter = skipAiFilter,
                skipSpamFilter = true,
            )
        }
        // 收藏镜像只会有插画/小说两类书架（关注书架不参与「全收藏分类」）；
        // 读到脏行直接跳过。
        else -> null
    }
}
