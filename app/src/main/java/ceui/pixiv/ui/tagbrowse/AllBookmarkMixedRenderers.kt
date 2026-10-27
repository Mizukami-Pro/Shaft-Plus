package ceui.pixiv.ui.tagbrowse

import android.content.Intent
import android.content.res.ColorStateList
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.RecyNovelBinding
import ceui.lisa.helper.IllustNovelFilter
import ceui.lisa.utils.GlideUtil
import ceui.lisa.utils.Params
import ceui.loxia.Novel
import ceui.pixiv.actions.PixivActions
import ceui.pixiv.feeds.FeedCell
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.feedRenderer
import ceui.pixiv.ui.bookmark.SelectTagBottomSheet
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.common.NovelMuteStore
import ceui.pixiv.ui.common.openNovelDetail
import ceui.pixiv.ui.common.openUserActivity
import ceui.pixiv.ui.common.renderSpoilerParticles
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.novel.NovelSeriesFragment
import ceui.pixiv.ui.recommend.bindTrendingScore
import ceui.pixiv.utils.playLikePressHaptic
import ceui.pixiv.utils.playUnlikeHaptic
import ceui.pixiv.utils.ppppx
import ceui.pixiv.utils.setOnClick
import ceui.pixiv.witstudio.theme.V3Palette
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions.bitmapTransform
import jp.wasabeef.glide.transformations.BlurTransformation

/**
 * 混排列表里的「小说行」渲染器：整行占满（[FeedRenderer.fullSpan]），布局复用小说列表的
 * 主力卡 [RecyNovelBinding]（recy_novel）。
 *
 * ## 为什么整行
 *
 * 本页的列表是瀑布流（列数跟随「插画列表布局 / 每行几列」设置，[ceui.pixiv.ui.common.IllustFeedFragment]
 * 负责装配 LayoutManager），插画卡按比例错落排；小说卡没有宽高比、又承载标题/作者/标签流，
 * 塞进一列会窄到不可读。所以它和作品详情页的大图/评论区块一样走 `fullSpan`，让
 * StaggeredGridLayoutManager / 齐行 GridLayoutManager 都把它当整行。两种 LayoutManager 都
 * 支持整行条目（后者见 [ceui.pixiv.ui.common.JustifiedLayoutManager.isFullSpanAt]）。
 *
 * ## 为什么是「复制」而不是复用 NovelFeedFragment.novelCardRenderer
 *
 * Kotlin 单继承：本页要拿 [ceui.pixiv.ui.common.IllustFeedFragment] 的插画卡 / 详情跳转 /
 * 收藏广播，就不可能同时继承 [ceui.pixiv.ui.common.NovelFeedFragment]，而
 * `novelCardRenderer` 是它的 protected 成员。这里只复刻**卡片外观与关键行为**（同一张
 * recy_novel 布局、同一套字号/间距/数据绑定），不是另画一套小说卡：
 * - 点击整卡进小说详情（「直接进阅读器」设置由 `openNovelDetail` 统一判定）；
 * - 封面看大图 / 头像·作者进画师页 / 系列进系列页 / 爱心收藏 + 长按按标签收藏；
 * - 收藏走 [PixivActions] 队列（乐观翻心 + LIKED_NOVEL 广播回流由页面绑定
 *   [ceui.pixiv.ui.common.feedLikeSync] 承接），失败回滚也在队列侧；
 * - 「屏蔽此作品」仍是遮罩（模糊封面 + 粒子层），不是过滤——对齐 [NovelMuteStore] 的立场；
 * - 长按菜单（批量操作 / 下载这一篇 / 稍后再看）**不提供**：本页是「按标签翻收藏」的
 *   浏览页，菜单挂在 NovelFeedFragment 上，复制一份动作清单会与那边各自漂移；需要的
 *   动作点进详情页即可。
 *
 * 卡片布局仍是 recy_novel 原文件，所以字号、留白、标签流、封面比例与其它小说列表逐像素一致。
 */

/** 小说行「只有收藏态 / 收藏数变了」的局部重绑 payload（对齐 NovelFeedFragment 的同名标记）。 */
internal val PAYLOAD_TAG_DETAIL_NOVEL_LIKE = Any()

/** 小说行屏蔽态变化的局部重绑 payload（不来自 DiffUtil，由名单版本号驱动）。 */
internal val PAYLOAD_TAG_DETAIL_NOVEL_SPOILER = Any()

/** 封面 Glide 请求去重 key（存在 ImageView.tag 上）：url + 请求尺寸 + 是否模糊。 */
private data class NovelRowImageKey(
    val cacheKey: String?,
    val width: Int,
    val height: Int,
    val blurred: Boolean,
)

private const val NOVEL_ROW_BLUR_RADIUS = 25
private const val NOVEL_ROW_BLUR_SAMPLING = 3

/** 封面请求尺寸（与 recy_novel 里 80×119dp 的封面位一致）。 */
private val NOVEL_COVER_WIDTH = 80.ppppx
private val NOVEL_COVER_HEIGHT = 119.ppppx

/**
 * 小说行渲染器。扩展在 [AllBookmarkTagDetailFragment] 上而不是做成顶层函数：renderer 只在
 * `onCreateRenderers()` 里建一次、随 view 生死（[ceui.pixiv.feeds.FeedFragment.onDestroyView]
 * 清 adapter），引用 Fragment 安全。
 */
internal fun AllBookmarkTagDetailFragment.novelRowRenderer():
        FeedRenderer<NovelFeedItem, RecyNovelBinding> =
    feedRenderer<NovelFeedItem, RecyNovelBinding>(
        inflate = RecyNovelBinding::inflate,
        // 整行：瀑布流两列时也按一行铺满，小说信息才读得开。
        fullSpan = true,
        create = { cell ->
            cell.binding.root.setOnClick {
                val tapped = cell.itemOrNull ?: return@setOnClick
                // 已屏蔽的卡：点一下 = 取消屏蔽，再点才打开（对齐插画卡与小说列表）。
                unmuteNovelOr(tapped.novel) { openNovelDetail(tapped.novel.id) }
            }
            cell.binding.cover.setOnClick {
                val tapped = cell.itemOrNull ?: return@setOnClick
                unmuteNovelOr(tapped.novel) { openNovelCover(tapped.novel) }
            }
            cell.binding.userHead.setOnClick {
                val tapped = cell.itemOrNull ?: return@setOnClick
                unmuteNovelOr(tapped.novel) { openNovelAuthor(tapped.novel) }
            }
            cell.binding.author.setOnClick {
                val tapped = cell.itemOrNull ?: return@setOnClick
                unmuteNovelOr(tapped.novel) { openNovelAuthor(tapped.novel) }
            }
            cell.binding.like.setOnClick { toggleNovelRowLike(cell) }
            // 爱心长按 → 按标签收藏（对齐 NovelFeedFragment 的小说卡）
            cell.binding.like.setOnLongClickListener {
                openNovelTagBookmark(cell.item.novel)
                true
            }
        },
        recycle = { cell ->
            // 淡入淡出可能正跑到一半就被回收：连 animator 一起停掉并把 alpha 归位，
            // 否则复用到下一条目时粒子层带着半透明残值出场（对齐插画卡 / 小说卡）。
            cell.binding.spoilerParticles.animate().cancel()
            cell.binding.spoilerParticles.alpha = 1f
            cell.binding.spoilerParticles.setParticleAnimationRunning(false)
            illustGlide.clear(cell.binding.cover)
            cell.binding.cover.tag = null
            illustGlide.clear(cell.binding.userHead)
        },
        // detach 停了逐帧模拟，就必须有对称的 attach 把它开回来：RecyclerView 的 mCachedViews
        // 里的 holder 是「只 detach、不重新 onBind」的，滚出去一点点再滚回来不会走绑定。
        attach = { cell ->
            val spoilered = cell.itemOrNull?.let { isNovelRowSpoilered(it.novel) }
            if (spoilered == true) {
                cell.binding.spoilerParticles.setParticleAnimationRunning(true)
            }
        },
        detach = { cell ->
            cell.binding.spoilerParticles.setParticleAnimationRunning(false)
        },
        changePayload = { old, new ->
            // 只有收藏态 / 收藏数变了 → 局部重绑爱心与数字；其它字段（含热度分）变则回退全量绑定。
            if (old.trendingScore == new.trendingScore &&
                old.novel.copy(
                    is_bookmarked = new.novel.is_bookmarked,
                    total_bookmarks = new.novel.total_bookmarks,
                ) == new.novel
            ) {
                PAYLOAD_TAG_DETAIL_NOVEL_LIKE
            } else {
                null
            }
        },
        bindPayloads = { cell, payloads ->
            val known = payloads.all {
                it === PAYLOAD_TAG_DETAIL_NOVEL_LIKE || it === PAYLOAD_TAG_DETAIL_NOVEL_SPOILER
            }
            if (known) {
                if (payloads.any { it === PAYLOAD_TAG_DETAIL_NOVEL_LIKE }) {
                    val novel = cell.item.novel
                    renderNovelRowLike(cell.binding.like, novel.is_bookmarked == true)
                    cell.binding.bookmarkCount.text = (novel.total_bookmarks ?: 0).toString()
                }
                if (payloads.any { it === PAYLOAD_TAG_DETAIL_NOVEL_SPOILER }) {
                    val novel = cell.item.novel
                    val spoilered = isNovelRowSpoilered(novel)
                    loadNovelRowCover(cell.binding.cover, novel, spoilered)
                    renderSpoilerParticles(
                        cell.binding.spoilerParticles, show = spoilered, animate = true,
                    )
                    // 掩码涉及多个 view（文字换占位条 / 次级信息隐藏），跟全量绑定共用同一分支。
                    bindNovelRowContent(
                        cell.binding, novel, cell.item.trendingScore, spoilered,
                    )
                }
                true
            } else {
                false
            }
        },
    ) { cell ->
        val novel = cell.item.novel
        // 打码与否的真源是本地屏蔽名单 + AI 屏蔽强度，bind 时现读：别的页面改了屏蔽 / 设置，
        // 本页滑动复用一次就跟上（没被回收的卡由页面订阅 NovelMuteStore.revisionLive 补绑）。
        val spoilered = isNovelRowSpoilered(novel)
        loadNovelRowCover(cell.binding.cover, novel, spoilered)
        renderSpoilerParticles(cell.binding.spoilerParticles, show = spoilered, animate = false)
        bindNovelRowContent(cell.binding, novel, cell.item.trendingScore, spoilered)
    }

/** 内容区绑定的两分支入口：屏蔽态走掩码（占位条 + 隐藏次级信息），否则走正常文本。 */
private fun AllBookmarkTagDetailFragment.bindNovelRowContent(
    b: RecyNovelBinding,
    novel: Novel,
    trendingScore: Float?,
    spoilered: Boolean,
) {
    applyNovelRowMask(b, novel, masked = spoilered)
    if (!spoilered) renderNovelRowText(b, novel, trendingScore)
}

/** 正常态文本：标题 / 系列 / 作者 / 日期 / 收藏数 / 字数 / 标签流 / 热度 pill / AI 角标。 */
private fun AllBookmarkTagDetailFragment.renderNovelRowText(
    b: RecyNovelBinding,
    novel: Novel,
    trendingScore: Float?,
) {
    val ctx = b.root.context
    val palette = V3Palette.from(ctx)

    // 系列：强调色文本 + 点击进小说系列页（整行唯一的常驻彩色文本，同 NovelFeedFragment）。
    val series = novel.series
    if (series != null && !series.title.isNullOrEmpty()) {
        b.series.isVisible = true
        b.series.setTextColor(palette.textAccent)
        b.series.text = ctx.getString(R.string.string_184, series.title)
        b.series.setOnClick { openNovelSeries(series.id) }
    } else {
        b.series.isVisible = false
        b.series.setOnClickListener(null)
    }

    b.title.text = novel.title ?: ""
    b.author.text = novel.user?.name ?: ""
    b.date.text = novel.create_date?.take(10) ?: ""
    b.bookmarkCount.text = (novel.total_bookmarks ?: 0).toString()
    b.howManyWord.text = ctx.getString(
        R.string.v3_novel_word_count, (novel.text_length ?: 0).toString(),
    )
    b.trendingScore.bindTrendingScore(trendingScore)
    b.badgeAi.isVisible = novel.novel_ai_type == 2

    // 标签流：尊重「显示标签」设置；compact + 去 # 前缀 + searchIndex=1（搜索页「小说」tab），
    // 译名与折叠跟随小说卡的既有设置（#1038 / #1089）。
    val tags = if (Shaft.sSettings.isShowNovelCardTags()) novel.tags.orEmpty() else emptyList()
    b.novelTag.compact = true
    b.novelTag.searchIndex = 1
    b.novelTag.showHashPrefix = false
    b.novelTag.showTranslation = Shaft.sSettings.isShowNovelCardTagTranslations()
    b.novelTag.maxTags = if (Shaft.sSettings.isCollapseNovelCardTags()) 6 else -1
    b.novelTag.setTags(tags)
    b.novelTag.isVisible = tags.isNotEmpty()

    novel.user?.let { illustGlide.load(GlideUtil.getHead(it)).into(b.userHead) }
    renderNovelRowLike(b.like, novel.is_bookmarked == true)
}

/**
 * 屏蔽掩码开关（遮盖式伪模糊，全 API 一致；逐项对齐 NovelFeedFragment.applyNovelSpoilerMask）。
 *
 * [masked]=true：标题 / 作者 / 系列文字换成圆角占位条，日期 / 收藏数 / 字数 / AI 角标 /
 * 热度 pill / 标签 / 头像 / 爱心全部隐藏；[masked]=false：把这些复位（正常分支随后会重新
 * 赋真实文本与可见性）。封面仍由 [loadNovelRowCover] 出模糊位图，整卡粒子层由调用方铺。
 */
private fun AllBookmarkTagDetailFragment.applyNovelRowMask(
    b: RecyNovelBinding,
    novel: Novel,
    masked: Boolean,
) {
    if (masked) {
        // 系列这一行的可见性平时由 renderNovelRowText 按有无系列切；屏蔽分支不管的话，
        // 同一本小说长什么样就取决于这个 holder 上一条是谁。
        val series = novel.series
        b.series.isVisible = series != null && !series.title.isNullOrEmpty()
        // 屏蔽态下系列不再跳系列页，但也不能只 setOnClickListener(null)：那不会把 clickable
        // 复位回 false，view 会继续吃掉触摸事件又什么都不做。跟卡片其它区域一样先取消屏蔽。
        b.series.setOnClickListener { unmuteNovelOr(novel) }
    }
    listOf(b.title, b.author, b.series).forEach { tv ->
        if (masked) {
            // 单个空格 + wrap_content：占位条高度自然等于该行文字行高。
            tv.text = " "
            tv.setBackgroundResource(R.drawable.bg_novel_spoiler_bar)
        } else {
            tv.background = null
        }
    }
    listOf(
        b.date, b.bookmarkCount, b.howManyWord, b.badgeAi, b.trendingScore,
        b.novelTag, b.userHead, b.like,
    ).forEach { it.isVisible = !masked }
}

/** 打码态下任何「想点开内容」的入口都先取消屏蔽（对齐 NovelFeedFragment.unmuteOr）。 */
private fun AllBookmarkTagDetailFragment.unmuteNovelOr(novel: Novel, action: () -> Unit = {}) {
    if (NovelMuteStore.isMuted(novel.id)) {
        setNovelRowMuted(novel, false)
    } else {
        action()
    }
}

/** 开关某本小说的屏蔽：写 [NovelMuteStore]（当帧生效、Room 异步落地），再重绑本页那一行。 */
internal fun AllBookmarkTagDetailFragment.setNovelRowMuted(novel: Novel, muted: Boolean) {
    if (!NovelMuteStore.setMuted(novel.id, muted) { novel }) return
    // 记下水位：页面订阅 revisionLive 的补绑回调会为这次变更再跑一遍，重绑两次只是白闪一遍。
    novelMuteRevision = NovelMuteStore.revision
    notifyNovelItemsChanged(PAYLOAD_TAG_DETAIL_NOVEL_SPOILER)
}

/**
 * 封面图加载：屏蔽态直接让 Glide 出一张模糊位图（变换进 cacheKey，滚回来是缓存命中；
 * 不用 View 层模糊——那类做法在列表复用里每帧都要重算）。
 * 请求 key 存 tag，真没变时跳过重新加载，避免局部重绑时白发一次请求。
 */
private fun AllBookmarkTagDetailFragment.loadNovelRowCover(
    cover: ImageView,
    novel: Novel,
    spoilered: Boolean,
) {
    val url = GlideUtil.getUrl(novel.resolvedCoverUrl())
    val requestKey = NovelRowImageKey(
        cacheKey = url?.cacheKey,
        width = NOVEL_COVER_WIDTH,
        height = NOVEL_COVER_HEIGHT,
        blurred = spoilered,
    )
    if (cover.tag == requestKey) return
    cover.tag = requestKey
    var request = illustGlide.load(url).override(NOVEL_COVER_WIDTH, NOVEL_COVER_HEIGHT)
    if (spoilered) {
        request = request.apply(
            bitmapTransform(BlurTransformation(NOVEL_ROW_BLUR_RADIUS, NOVEL_ROW_BLUR_SAMPLING))
        )
    }
    request
        .placeholder(R.color.v3_surface_2)
        .error(R.color.v3_surface_2)
        .transition(DrawableTransitionOptions.withCrossFade())
        .into(cover)
}

/** 未收藏 = 灰，已收藏 = 红（爱心图标由布局给，这里只切 tint）。 */
private fun renderNovelRowLike(button: ImageButton, liked: Boolean) {
    val color = if (liked) R.color.has_bookmarked else R.color.not_bookmarked
    button.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(button.context, color))
}

/** 本页里这条小说的最新条目（VM 是收藏态真源）；已被刷新挤掉则回退到 cell 上的快照。 */
private fun AllBookmarkTagDetailFragment.currentNovelRowItem(novelId: Long): NovelFeedItem? =
    feedVm().uiState.value.items
        .firstOrNull { it is NovelFeedItem && it.novel.id == novelId } as? NovelFeedItem

/**
 * 收藏切换：点按当帧乐观翻心，写操作交给 [PixivActions] 队列（私密收藏设置 / 自动关注 /
 * 失败回滚 / LIKED_NOVEL 广播都在队列侧统一处理）。
 */
private fun AllBookmarkTagDetailFragment.toggleNovelRowLike(
    cell: FeedCell<NovelFeedItem, RecyNovelBinding>,
) {
    // 收藏态的真源是 VM 的当前状态，不是 cell.item —— 后者是 adapter 已提交的快照，
    // 连点两下时第二下会读到旧态、把「取消收藏」反转成「再收藏一次」。
    val tapped = cell.itemOrNull ?: return
    val novelId = tapped.novel.id
    val novel = currentNovelRowItem(novelId)?.novel ?: tapped.novel
    val target = novel.is_bookmarked != true
    // 乐观：当帧翻心（异步 updateItems 至少要等 ListAdapter diff 落地一两帧）。
    renderNovelRowLike(cell.binding.like, target)
    if (target) {
        playLikePressHaptic(cell.binding.like)
    } else {
        playUnlikeHaptic(cell.binding.like)
    }
    feedVm().updateItems(NovelFeedItem::class.java) { item ->
        if (item.novel.id == novelId) item.withBookmarked(target) else item
    }
    PixivActions.setNovelBookmark(novel, target)
    if (target) {
        // 收藏后自动关注作者：判重（池 + 作品自带状态）由门面统一做。
        PixivActions.autoFollowAuthor(novel.user?.id, novel.user?.is_followed)
    }
}

/** 爱心长按 → 「按标签收藏」sheet（对齐小说列表；目标 id 全链路 Long）。 */
private fun AllBookmarkTagDetailFragment.openNovelTagBookmark(novel: Novel) {
    SelectTagBottomSheet.show(
        this,
        novel.id,
        Params.TYPE_NOVEL,
        novel.tags.orEmpty().mapNotNull { it.name }.toTypedArray(),
    )
}

private fun AllBookmarkTagDetailFragment.openNovelAuthor(novel: Novel) {
    novel.user?.id?.takeIf { it > 0L }?.let { openUserActivity(it) }
}

private fun AllBookmarkTagDetailFragment.openNovelSeries(seriesId: Long) {
    if (seriesId <= 0L) return
    startActivity(
        Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(NovelSeriesFragment.ARG_SERIES_ID, seriesId)
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.NOVEL_SERIES.key)
        }
    )
}

/** 封面点击看封面大图（取最大图，对齐 NovelFeedFragment.openCoverImage）。 */
private fun AllBookmarkTagDetailFragment.openNovelCover(novel: Novel) {
    val url = novel.image_urls?.let {
        it.original ?: it.large ?: it.medium ?: it.square_medium
    } ?: return
    startActivity(
        Intent(requireContext(), TemplateActivity::class.java).apply {
            putExtra(Params.URL, GlideUtil.getUrl(url).toStringUrl())
            putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.IMAGE_DETAIL.key)
        }
    )
}

/** 本行当前是否处于屏蔽态（本地屏蔽名单 / AI 屏蔽强度）。 */
private fun isNovelRowSpoilered(novel: Novel): Boolean =
    NovelMuteStore.isMuted(novel.id) || IllustNovelFilter.shouldBlurAi(novel)
