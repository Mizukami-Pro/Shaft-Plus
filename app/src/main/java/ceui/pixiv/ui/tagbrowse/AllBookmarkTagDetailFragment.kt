package ceui.pixiv.ui.tagbrowse

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import ceui.lisa.R
import ceui.lisa.activities.Shaft
import ceui.lisa.databinding.FragmentAllBookmarkTagDetailBinding
import ceui.lisa.utils.Common
import ceui.lisa.utils.DensityUtil
import ceui.lisa.utils.Params
import ceui.pixiv.api.model.Illust
import ceui.pixiv.db.mirror.AllBookmarkRepository
import ceui.pixiv.db.mirror.AllBookmarkSort
import ceui.pixiv.feeds.FeedItem
import ceui.pixiv.feeds.FeedRenderer
import ceui.pixiv.feeds.FeedUiState
import ceui.pixiv.feeds.FeedViewModel
import ceui.pixiv.feeds.LoadState
import ceui.pixiv.feeds.feedViewModels
import ceui.pixiv.services.appServices
import ceui.pixiv.ui.common.IllustFeedFragment
import ceui.pixiv.ui.common.NovelFeedItem
import ceui.pixiv.ui.common.NovelMuteStore
import ceui.pixiv.ui.common.feedLikeSync
import ceui.pixiv.ui.common.staggerIllustRenderer
import ceui.pixiv.ui.common.viewBinding
import com.blankj.utilcode.util.BarUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 「全收藏分类查看」标签详情页：某个标签下收藏的**全部作品**（插画 / 漫画 / 小说混排）。
 *
 * 入口是 [TagBrowseContracts.openTagDetail]（Intent extras 由 Lead 的路由层塞进 arguments），
 * 本页只做页面本体与数据接线。
 *
 * ## 页面结构
 *
 * 顶栏（返回 + `#标签原名` + 译名）→ 作品内搜索框（标题 / 作者 / 标签含译名，300ms 防抖）→
 * 类型 chips（全部 / 插画 / 漫画 / 小说）→ 排序 chips（最新发布 = 默认 / 最早发布 / 最多收藏 /
 * 热度 / 趋势）→ 命中总数「共 N 件」→ 混排列表 → 空态。
 *
 * ## 混排怎么落地
 *
 * 继承 [IllustFeedFragment] 白拿：标准瀑布流插画卡（点击进详情、收藏红心广播同步、长按菜单）、
 * 「插画列表布局 / 每行几列」设置（瀑布流 / 方格 / 齐行 / 单列）、详情 pager 回传与列表跟随。
 * 再在 [onCreateRenderers] 里拼上 [novelRowRenderer]（整行 fullSpan 的小说卡，复用 recy_novel
 * 布局），得到「插画卡按比例错落、小说卡整行」的混排列表。两种 LayoutManager 都支持整行条目。
 *
 * ## 数据与分页
 *
 * [AllBookmarkTagDetailFeedSource] 做本地 SQL LIMIT/OFFSET 分页（PAGE_SIZE=60），条件从
 * [AllBookmarkTagDetailViewModel] 现读；排序 / 类型 / 搜索词变化 → `refresh()` 整代替换 +
 * 回顶（等新一代真正提交上屏才拨回顶部，见 [onListCommitted]，理由同
 * [ceui.pixiv.ui.library.BookmarkLibraryUi.applyFilterChange]）。默认排序是**作品发布时间
 * 新→旧**而不是收藏时间，取舍见 [AllBookmarkTagQuery] 的 KDoc。
 *
 * ## 同步
 *
 * 打开页面即调 [AllBookmarkRepository.ensureShelves]（幂等）确保四个书架已注册、后台开始
 * 回填；镜像行数变化时自动重算命中数，补齐完成时在顶部静默重查一次。下拉刷新会额外
 * `syncNow` 去服务端对表头（对齐收藏库页：下拉 = 「我在别处收藏了，去看看」）。
 */
class AllBookmarkTagDetailFragment :
    IllustFeedFragment(R.layout.fragment_all_bookmark_tag_detail) {

    private val binding by viewBinding(FragmentAllBookmarkTagDetailBinding::bind)

    /**
     * 条件真源。**SavedStateHandle 由默认 ViewModel 工厂注入**：进程被系统回收后重建时，
     * VM 构造期就从 saved state 恢复完整 query（keyword / 排序 / 类别 / 面板 8 项），
     * 因此下面 `feedViewModels {}` 里构造出的 FeedSource 第一次查询用的就是恢复后的条件
     * （详见 [AllBookmarkTagDetailViewModel] 的类 KDoc）。旋转不重建 VM，行为不变。
     */
    private val detailViewModel: AllBookmarkTagDetailViewModel by viewModels()

    /** 入口给的标签展示名（原始大小写，只用于顶栏标题）。 */
    private val rawTagName: String by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(TagBrowseContracts.EXTRA_TAG_NAME).orEmpty()
    }

    /** 标签译名（可空）。 */
    private val tagTranslatedName: String? by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getString(TagBrowseContracts.EXTRA_TAG_TRANSLATED_NAME)
            ?.takeIf { it.isNotBlank() }
    }

    /** 总览页给的件数：命中数查回来之前先用它占位，避免「共 — 件」闪一下。 */
    private val initialCount: Int by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getInt(TagBrowseContracts.EXTRA_TAG_COUNT, 0)
    }

    /** 是否包含悄悄收藏；入口没传时回落到全局偏好（默认 true）。 */
    private val includePrivate: Boolean by lazy(LazyThreadSafetyMode.NONE) {
        requireArguments().getBoolean(
            TagBrowseContracts.EXTRA_INCLUDE_PRIVATE,
            TagBrowseContracts.includePrivate(),
        )
    }

    override val feedViewModel by feedViewModels<String> {
        // 零捕获：只捕获兄弟 VM（同一 ViewModelStore、同生命周期），不碰 Fragment 的视图。
        // bind 是幂等的，谁先初始化都行。
        val vm = detailViewModel.also { it.bind(uid(), rawTagName, includePrivate) }
        AllBookmarkTagDetailFeedSource(vm)
    }

    private fun uid(): Long {
        val fromArgs = arguments?.getLong(Params.USER_ID, 0L) ?: 0L
        return fromArgs.takeIf { it > 0L } ?: AllBookmarkRepository.currentUid()
    }

    /**
     * 给同包 renderer（AllBookmarkMixedRenderers.kt）用的列表 VM 访问口。
     *
     * `feedViewModel` 继承自 [ceui.pixiv.feeds.FeedFragment]，可见性是 **protected**
     * （Kotlin 的 override 默认沿用基类可见性，并不是 public），而扩展函数访问不到 protected
     * 成员。小说行 renderer 是写在扩展函数里的（renderer 随 view 生死，引用 Fragment 安全），
     * 所以这里开一个 internal 的口给它。
     */
    internal fun feedVm(): FeedViewModel<String> = feedViewModel

    // ─────────────────────────── 列表契约 ───────────────────────────

    /**
     * 本地分页游标（已读行数）绝不能流到详情页 pager 那条续拉链上 —— 它会把游标当 `@Url`
     * 直接请求。对齐 [ceui.pixiv.ui.library.BookmarkLibraryFragment] 的同款覆写。
     */
    override val detailContinuationCursor: String? get() = null

    /**
     * 镜像行里的 bean 是**冻结的 JSON 快照**（收藏当时的数据），喂进 ObjectPool 会拿旧值盖掉
     * 用户这次会话里刚改出来的收藏 / 关注态。所以本页不合池（同收藏库页 / 稍后再看页）。
     */
    override fun poolableBeansOf(item: FeedItem): List<Illust> = emptyList()

    /**
     * 本页是「我的收藏」的另一个入口，所以要尊重「收藏页隐藏收藏按钮」——同一批内容换个入口
     * 就多出一排爱心，是前后不一致（对齐 [ceui.pixiv.ui.library.BookmarkLibraryFragment]）。
     */
    override val hideLikeButton: Boolean
        get() = super.hideLikeButton ||
                (detailViewModel.uid > 0L && detailViewModel.uid == AllBookmarkRepository.currentUid() &&
                        Shaft.sSettings.isHideStarButtonAtMyCollection())

    /** 插画卡 + 整行小说卡。小说卡的实现在 [novelRowRenderer]（同包 AllBookmarkMixedRenderers.kt）。 */
    override fun onCreateRenderers(): List<FeedRenderer<out FeedItem, out ViewBinding>> {
        return listOf(staggerIllustRenderer(), novelRowRenderer())
    }

    override val emptyStateText: CharSequence
        get() {
            val query = detailViewModel.query.value
            return when {
                // 未登录（理论上前一页就挡住了，但入口可以被别的页面/深链直接拉起）：
                // 别拿「镜像还在补齐」去糊弄人，用户需要的是去登录。
                detailViewModel.uid <= 0L ->
                    getString(R.string.all_bookmark_tag_detail_empty_login)
                // 有搜索词 / 类型条件时的「空」是条件没筛到，和「这个标签下没有作品」是两回事。
                query.hasAnyCondition -> getString(R.string.all_bookmark_tag_detail_empty_filtered)
                // 镜像还在后台回填：现在空不代表以后空，说清楚，别让用户以为页面坏了。
                !mirrorComplete -> getString(R.string.all_bookmark_tag_detail_empty_syncing)
                else -> getString(R.string.all_bookmark_tag_detail_empty)
            }
        }

    // ─────────────────────────── 视图态 ───────────────────────────

    /** 搜索框的防抖任务；视图销毁时要撤掉，否则回调会打到已销毁的绑定上。 */
    private var pendingSearch: Runnable? = null

    /** 搜索框引用：onDestroyView 里 binding 已经不可靠，撤回调要用它。 */
    private var searchInput: EditText? = null

    /** chips：类型 / 排序 → 按钮。只用于渲染选中态，查询条件真源在 VM。 */
    private val typeChips = LinkedHashMap<AllBookmarkWorkType, TextView>()
    private val sortChips = LinkedHashMap<AllBookmarkSort, TextView>()

    /**
     * 「筛选 / 筛选 · N 项」入口：放在「作品类别」小节标题行右侧（布局内固定），任何屏宽 /
     * 字号下都在首屏可见——曾经放在排序行末尾会被 5 个排序 chip 挤出屏幕（QA 实测只剩 19px）。
     * 文本与点亮态随面板条件数变化。
     */
    private var filterChip: TextView? = null

    /**
     * 触发换条件时列表所处的「代号」。等到提交上来的代号**变了**（= 新一代真的落地了）
     * 才把列表拨回顶部，见 [onListCommitted]。
     */
    private var resetAfterGeneration: Int? = null

    /** 四个书架是否都已完成过首次同步；空态文案与补齐后的自动重查用它。 */
    private var mirrorComplete = false

    /**
     * 回填补齐完成时用户正停在列表中间：先记下来，等他滚回顶部再静默重查。
     * 补齐往往发生在用户已经看了一会儿之后，此刻整代重查会把人从列表深处拽回顶部。
     */
    private var pendingRefreshWhileScrolled = false

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (pendingRefreshWhileScrolled && !recyclerView.canScrollVertically(-1)) {
                pendingRefreshWhileScrolled = false
                feedViewModel.refresh()
            }
        }
    }

    /** 本页小说行上一次渲染时的 [NovelMuteStore.revision]（见 [observeNovelMuteRevision]）。 */
    internal var novelMuteRevision: Int = NovelMuteStore.revision

    // ─────────────────────────── 生命周期 ───────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 即使 feedViewModel 这一帧还没被访问过，也先把 VM 的条件源绑定好（幂等）。
        detailViewModel.bind(uid(), rawTagName, includePrivate)
        setUpChrome()
        setUpSearch()
        setUpChips()
        observeFilterPanelResult()
        bindNovelLikeSync()
        observeNovelMuteRevision()
        setUpPullToRefresh()
        feedBinding.feedListView.addOnScrollListener(scrollListener)
        observeState()
        // 打开这个标签页 = 用户要看这里的内容：确保参与聚合的四个书架都已注册、开始后台回填。
        // ensureShelf 幂等，已注册的书架只会排一次增量维护。
        AllBookmarkRepository.ensureShelves(
            detailViewModel.uid, detailViewModel.includePrivate, "打开标签作品列表",
        )
    }

    override fun onDestroyView() {
        pendingSearch?.let { task -> searchInput?.removeCallbacks(task) }
        pendingSearch = null
        searchInput = null
        typeChips.clear()
        sortChips.clear()
        filterChip = null
        feedBinding.feedListView.removeOnScrollListener(scrollListener)
        pendingRefreshWhileScrolled = false
        super.onDestroyView()
    }

    /**
     * 换条件（排序 / 类型 / 搜索词）之后重新出列表。
     *
     * **不用 [forceRefresh]**：它先 `smoothScrollToPosition(0)` 再刷新，平滑滚动的结束时机和
     * 新一代数据落地是两件事；而且整代替换留在 SGLM 里的列偏移不会清，新一代首屏某一列顶部
     * 会空出一块。这里记下当前代号，等新一代真正提交完再确定性地复位（同收藏库页）。
     */
    private fun applyQueryChange() {
        resetAfterGeneration = feedViewModel.uiState.value.refreshGeneration
        // 新条件会整代替换列表，回填补齐的「等回顶再重查」被这一代刷新取代了。
        pendingRefreshWhileScrolled = false
        feedViewModel.refresh()
    }

    override fun onListCommitted(state: FeedUiState) {
        super.onListCommitted(state)
        // 认代号而不是认布尔标记：onListCommitted 每次提交都会来（包括往下滑追加的页），
        // 把「条件变更待回顶」这件事误当成「这一页也要回顶」就会把用户从列表深处拽回顶部。
        val target = resetAfterGeneration ?: return
        if (state.refreshGeneration == target) return
        resetAfterGeneration = null
        // 空的一代没有顶可回；而且**不能**碰 SGLM：空列表上留下的滚动目标会让它早退、
        // 不清各列的行缓存，下一代数据进来时一张卡都排不出。
        if (state.items.isEmpty()) return
        resetListToTop()
    }

    /** 把列表确定性地拨回顶部（清 SGLM 跨代残留，理由见 FeedFragment.resetToTop）。 */
    private fun resetListToTop() {
        val listView = feedBinding.feedListView
        when (val manager = listView.layoutManager) {
            is StaggeredGridLayoutManager -> {
                // invalidateSpanAssignments 只清「位置 → 列」那张表，清不掉各列自己的行缓存
                //（Span.mCachedStart / mCachedEnd）—— 后者要靠下面的 setSpanCount 抖动重建。
                val animator = listView.itemAnimator
                listView.itemAnimator = null
                manager.invalidateSpanAssignments()
                val firstVisible = manager.findViewByPosition(0)
                val firstStart = firstVisible?.let {
                    manager.getDecoratedTop(it) -
                            ((it.layoutParams as? RecyclerView.LayoutParams)?.topMargin ?: 0)
                }
                if (firstStart != null && firstStart < listView.paddingTop) {
                    val spanCount = manager.spanCount
                    manager.setSpanCount(spanCount + 1)
                    manager.setSpanCount(spanCount)
                }
                manager.scrollToPositionWithOffset(0, 0)
                if (animator != null) {
                    listView.post { listView.itemAnimator = animator }
                }
            }
            is LinearLayoutManager -> manager.scrollToPositionWithOffset(0, 0)
            else -> manager?.scrollToPosition(0)
        }
    }

    // ─────────────────────────── 接线 ───────────────────────────

    /** 顶栏（状态栏 inset / 返回 / 标题 + 译名）和列表底部安全区。 */
    private fun setUpChrome() {
        val b = binding
        b.toolbar.updatePadding(top = BarUtils.getStatusBarHeight())
        b.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        b.toolbarTitle.text = getString(
            R.string.all_bookmark_tag_detail_title, detailViewModel.displayTagName,
        )
        val translated = tagTranslatedName
        if (translated != null) {
            b.toolbarSubtitle.text = getString(
                R.string.all_bookmark_tag_detail_subtitle, translated,
            )
            b.toolbarSubtitle.isVisible = true
        } else {
            b.toolbarSubtitle.isVisible = false
        }
        // 状态栏 inset 由根布局（TopInsetLinearLayout）吃成 paddingTop；这里只把导航栏 inset
        // 补给列表底部，让最后一条能滚出手势条（同收藏库页）。
        ViewCompat.setOnApplyWindowInsetsListener(b.coordinator) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            feedBinding.feedListView.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * 作品内搜索：标题 / 作者 / 标签（含译名）逐词 AND，300ms 防抖后 `refresh()` 回顶。
     *
     * 搜索词的真源在 VM（[AllBookmarkTagDetailViewModel.query]），输入框只是它的编辑器：
     * 旋转重建后从 VM 回填，用户不用重新敲一遍。
     */
    private fun setUpSearch() {
        val input = binding.searchInput
        searchInput = input
        val current = detailViewModel.query.value.keyword
        if (current.isNotEmpty() && input.text.toString() != current) input.setText(current)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val keyword = s?.toString().orEmpty()
                pendingSearch?.let { input.removeCallbacks(it) }
                pendingSearch = null
                val task = Runnable {
                    pendingSearch = null
                    // 视图可能在这一跳之前就销毁了（切页 / 返回），别打到一个已卸的列表上。
                    if (view == null) return@Runnable
                    if (detailViewModel.setKeyword(keyword)) {
                        applyQueryChange()
                    }
                }
                pendingSearch = task
                input.postDelayed(task, SEARCH_DEBOUNCE_MS)
            }
        })
    }

    private fun setUpChips() {
        val typeRow = binding.typeChipRow
        typeRow.removeAllViews()
        typeChips.clear()
        listOf(
            AllBookmarkWorkType.ALL to R.string.all_bookmark_tag_detail_type_all,
            AllBookmarkWorkType.ILLUST to R.string.all_bookmark_tag_detail_type_illust,
            AllBookmarkWorkType.MANGA to R.string.all_bookmark_tag_detail_type_manga,
            AllBookmarkWorkType.NOVEL to R.string.all_bookmark_tag_detail_type_novel,
            AllBookmarkWorkType.UGOIRA to R.string.all_bookmark_tag_detail_type_ugoira,
        ).forEach { (type, labelRes) ->
            typeChips[type] = addChip(typeRow, getString(labelRes)) {
                if (detailViewModel.setWorkType(type)) applyQueryChange()
            }
        }

        val sortRow = binding.sortChipRow
        sortRow.removeAllViews()
        sortChips.clear()
        listOf(
            AllBookmarkSort.NEWEST_PUBLISHED to R.string.all_bookmark_tag_detail_sort_newest,
            AllBookmarkSort.OLDEST_PUBLISHED to R.string.all_bookmark_tag_detail_sort_oldest,
            AllBookmarkSort.MOST_BOOKMARKED to R.string.all_bookmark_tag_detail_sort_bookmarked,
            AllBookmarkSort.MOST_VIEWED to R.string.all_bookmark_tag_detail_sort_viewed,
            AllBookmarkSort.TRENDING to R.string.all_bookmark_tag_detail_sort_trending,
        ).forEach { (sort, labelRes) ->
            sortChips[sort] = addChip(sortRow, getString(labelRes)) {
                if (detailViewModel.setSort(sort)) applyQueryChange()
            }
        }
        // 筛选入口在「作品类别」小节标题行右侧（布局里固定，始终可见）：这里只挂点击，
        // 文本与点亮态交给 renderChips。N 只数面板里的条件（不含顶部类别 chips 与搜索词）。
        filterChip = binding.filterChip.apply {
            setOnClickListener { openFilterPanel() }
        }
        renderChips()
    }

    /** 一个 chip：胶囊 shape + 18 号选中态全部由 bg_bookmark_chip / bookmark_chip_text 承担。 */
    private fun addChip(row: ViewGroup, text: String, onClick: () -> Unit): TextView =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTextColor(requireContext().resources.getColorStateList(R.color.bookmark_chip_text, null))
            setBackgroundResource(R.drawable.bg_bookmark_chip)
            updatePadding(
                left = DensityUtil.dp2px(14f),
                right = DensityUtil.dp2px(14f),
                top = DensityUtil.dp2px(7f),
                bottom = DensityUtil.dp2px(7f),
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also { it.rightMargin = DensityUtil.dp2px(8f) }
            row.addView(this)
        }

    /** 选中态：形状与颜色一起变（bg_bookmark_chip 认 state_activated，不认 selected）。 */
    private fun renderChips() {
        val query = detailViewModel.query.value
        typeChips.forEach { (type, chip) -> chip.isActivated = query.type == type }
        sortChips.forEach { (sort, chip) -> chip.isActivated = query.sort == sort }
        val panelCount = query.panelConditionCount
        filterChip?.apply {
            text = if (panelCount > 0) {
                getString(R.string.search_filter_v3_entry_with_count, panelCount)
            } else {
                getString(R.string.search_filter_v3_entry)
            }
            isActivated = panelCount > 0
        }
    }

    /**
     * 打开筛选面板。**选择即生效**：面板每次改动都会把只含面板字段的
     * [AllBookmarkTagFilterPatch] 通过 FragmentResult 发回来，这里合并进 VM 并重刷列表。
     *
     * sheet 挂在 `parentFragmentManager`（宿主 Activity 的 FM）上，与搜索页筛选器一致；
     * 结果监听也用同一个 FM（[observeFilterPanelResult]）。
     */
    private fun openFilterPanel() {
        // 打开面板前收起键盘：面板里的行值才是接下来要点的东西，软键盘会挡住下半屏。
        binding.searchInput.clearFocus()
        Common.hideKeyboard(requireActivity())
        AllBookmarkTagFilterSheet.newInstance(
            REQUEST_FILTER_PANEL,
            AllBookmarkTagFilterPatch.of(detailViewModel.query.value.filter),
        ).show(parentFragmentManager, "AllBookmarkTagFilterSheet")
    }

    /** 筛选面板回传（每次选择一条）：合并进 VM，变了就整代刷新 + 回顶（同 chips/搜索）。 */
    private fun observeFilterPanelResult() {
        parentFragmentManager.setFragmentResultListener(
            REQUEST_FILTER_PANEL, viewLifecycleOwner,
        ) { _, bundle ->
            @Suppress("DEPRECATION")
            val patch = bundle.getSerializable(AllBookmarkTagFilterSheet.KEY_PATCH)
                    as? AllBookmarkTagFilterPatch ?: return@setFragmentResultListener
            if (detailViewModel.applyPanelFilter(patch)) applyQueryChange()
        }
    }

    /** 命中总数。null = 还在算 / 算失败：**没有筛选条件**时先用入口给的件数占位，否则整行收起。 */
    private fun renderCount(count: Int?) {
        // 切了类型 / 加了搜索词之后，入口那个「全标签件数」已经不是这个条件下的答案了：
        // 宁可不显示，也不能让用户以为筛选没生效。
        val fallback = initialCount.takeIf {
            it > 0 && !detailViewModel.query.value.hasAnyCondition
        }
        val value = count ?: fallback
        if (value == null) {
            binding.countText.isVisible = false
            return
        }
        binding.countText.isVisible = true
        binding.countText.text = getString(
            R.string.all_bookmark_tag_detail_count, formatCount(value),
        )
    }

    private fun formatCount(value: Int): String =
        String.format(Locale.getDefault(), "%,d", value)

    /** 下拉刷新：除了重查本地，还要去服务端对一次表头（「我在别处收藏了，去看看」）。 */
    private fun setUpPullToRefresh() {
        val refreshLayout = feedBinding.feedRefreshLayout
        // 顶栏收起时先把下拉交给 AppBarLayout 展开，否则列表已在顶部时会抢先触发刷新。
        refreshLayout.setOnChildScrollUpCallback { _, _ ->
            binding.appBar.top < 0 || feedBinding.feedListView.canScrollVertically(-1)
        }
        refreshLayout.setOnRefreshListener {
            AllBookmarkRepository.syncNow(
                detailViewModel.uid, detailViewModel.includePrivate, "标签作品下拉刷新",
            )
            feedViewModel.refresh()
        }
    }

    /** 小说收藏态广播回流（插画侧由 IllustFeedFragment 自己接好）。 */
    private fun bindNovelLikeSync() {
        feedLikeSync<NovelFeedItem>(
            feedViewModel = feedViewModel,
            action = Params.LIKED_NOVEL,
            idOf = { it.novel.id },
            transform = { item, liked -> item.withBookmarked(liked) },
        ).bind(requireContext(), viewLifecycleOwner)
    }

    /**
     * 小说「屏蔽此作品」名单的补绑：屏蔽态不在条目上，别的页面（详情页取消屏蔽、屏蔽记录页删除）
     * 改完名单后，已经绑好的卡不会自己重绑。订阅版本号，变了一次性重绑本页所有小说行。
     */
    private fun observeNovelMuteRevision() {
        NovelMuteStore.revisionLive.observe(viewLifecycleOwner) { revision ->
            if (revision == novelMuteRevision) return@observe
            novelMuteRevision = revision
            notifyNovelItemsChanged(PAYLOAD_TAG_DETAIL_NOVEL_SPOILER)
        }
    }

    /**
     * 给 currentList 里**每一段连续的 [NovelFeedItem]** 发 [payload]。
     * 逐段发而不是 `notifyItemRangeChanged(0, itemCount)`：插画卡不认识这个 payload，
     * 会被框架退回全量重绑（等于给满屏插画白发一轮 Glide 请求）。
     */
    internal fun notifyNovelItemsChanged(payload: Any) {
        val adapter = feedAdapter ?: return
        val items = adapter.currentList
        var start = -1
        items.forEachIndexed { index, item ->
            if (item is NovelFeedItem) {
                if (start < 0) start = index
            } else if (start >= 0) {
                adapter.notifyItemRangeChanged(start, index - start, payload)
                start = -1
            }
        }
        if (start >= 0) {
            adapter.notifyItemRangeChanged(start, items.size - start, payload)
        }
    }

    private fun observeState() {
        // 镜像服务与书架集合相关：整段都从当前 Context 取，别在协程里反复 getContext()（可空）。
        val mirror = requireContext().appServices().bookmarkMirror
        val ownerUid = detailViewModel.uid
        // 只有**本次范围**内的书架（includePrivate=false 时不含悄悄收藏）的补齐进度才算数：
        // 悄悄书架可能是上一轮通过总览页开关注册过的、一直停在 NEVER，拿它参与判断会让本页
        // 永远以为「还在补齐」。
        val scopeKeys: Set<String> = AllBookmarkRepository
            .shelvesFor(ownerUid, detailViewModel.includePrivate)
            .map { it.key }
            .toHashSet()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    detailViewModel.query.collectLatest { renderChips() }
                }
                launch {
                    detailViewModel.resultCount.collectLatest { renderCount(it) }
                }
                launch {
                    // 刷新失败时不会提交新代号：把「等回顶」的标记清掉，否则下一次滑到底
                    // 追加页提交时会被误判成「条件变更的那一代」而把用户拽回顶部。
                    feedViewModel.uiState.collectLatest { state ->
                        if (state.refresh is LoadState.Error) resetAfterGeneration = null
                    }
                }
                launch {
                    mirror.observeState(ownerUid).collectLatest { states ->
                        val scoped = states.filter { it.shelfKey in scopeKeys }
                        // 本次范围内书架都完成过首次同步才算「补齐」；一条状态都还没有时不算。
                        updateMirrorComplete(
                            scoped.isNotEmpty() && scoped.none { !it.isFirstSyncDone },
                        )
                    }
                }
                launch {
                    // 后台又回填进一批：命中数跟着变；屏幕上还空着时顺手重查一次列表。
                    mirror.observeOwnerCount(ownerUid).collectLatest { onMirrorRowsChanged() }
                }
            }
        }
    }

    private fun onMirrorRowsChanged() {
        detailViewModel.refreshCount()
        val state = feedViewModel.uiState.value
        if (state.items.isEmpty() && state.refresh is LoadState.Idle) {
            feedViewModel.refresh()
        }
    }

    /**
     * 镜像补齐状态变化。空态文案依赖它、而 [FeedUiState] 本身没变（render 不会自己重来一次），
     * 所以空态挂着时手动重设一次文案；从「补齐中」翻成「补齐完成」时在顶部静默重查一次，
     * 把回填期间新增的作品带上来。
     */
    private fun updateMirrorComplete(complete: Boolean) {
        val was = mirrorComplete
        if (was == complete) return
        mirrorComplete = complete
        if (feedViewModel.uiState.value.showEmptyState) {
            feedBinding.feedStateText.text = emptyStateText
        }
        if (!was && complete) {
            detailViewModel.refreshCount()
            if (feedViewModel.uiState.value.items.isEmpty() ||
                !feedBinding.feedListView.canScrollVertically(-1)
            ) {
                pendingRefreshWhileScrolled = false
                feedViewModel.refresh()
            } else {
                // 用户正停在列表中间：不打断浏览，等他滚回顶部再由 scrollListener 静默重查。
                pendingRefreshWhileScrolled = true
            }
        }
    }

    private companion object {
        /** 搜索防抖：每敲一个字都查一次 SQLite 是没意义的，300ms 是「打完一个词」的量级。 */
        const val SEARCH_DEBOUNCE_MS = 300L

        /** 筛选面板回传 FragmentResult 的 request key（面板在 parentFragmentManager 上）。 */
        const val REQUEST_FILTER_PANEL = "all_bookmark_tag_detail_filter_panel"
    }
}
