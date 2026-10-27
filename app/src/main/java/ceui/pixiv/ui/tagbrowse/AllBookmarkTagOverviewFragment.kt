package ceui.pixiv.ui.tagbrowse

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import ceui.lisa.R
import ceui.lisa.activities.TemplateActivity
import ceui.lisa.databinding.FragmentAllBookmarkTagsBinding
import ceui.lisa.fragments.SettingsCatalog
import ceui.pixiv.db.mirror.BookmarkShelf
import ceui.pixiv.db.mirror.MirrorContentType
import ceui.pixiv.db.mirror.MirrorRestrict
import ceui.pixiv.ui.common.BottomDividerDecoration
import ceui.pixiv.ui.common.viewBinding
import ceui.pixiv.ui.navigation.TemplateRoute
import ceui.pixiv.ui.tagbrowse.poster.TagRankPosterEntry
import ceui.pixiv.ui.v3.setupV3Toolbar
import ceui.pixiv.widgets.applyV3RefreshTheme
import ceui.pixiv.widgets.scrollUpFrom
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.color
import ceui.pixiv.witstudio.theme.pressScale
import ceui.pixiv.witstudio.theme.v3Font
import com.hjq.toast.Toaster
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 「全收藏分类查看」总览页：把登录账号收藏的全部插画 / 漫画 / 小说按作品标签聚合，
 * 标签按收藏作品数从多到少排列，支持本地搜索，点标签进该标签的作品列表页。
 *
 * 页面骨架（V3「管理 / 内容」配方）：
 * ```
 * [toolbar：全收藏分类        排行图标]
 * [搜索标签或译名                    ×]
 * [包含私密收藏]（首次开启时下面补一行说明）
 * ┌ 列表 ─────────────────────────────┐
 * │ 统计头：1,860 件收藏      同步状态 ⟳ │
 * │         插画 1,200 · 漫画 300 …     │
 * │         512 个标签 · 按作品数排序    │
 * │ ── 01  #原神 / げんしん    1,203 件 ─│
 * │ ── 02  #ブルーアーカイブ     987 件 ─│
 * └───────────────────────────────────┘
 * ```
 *
 * 数据全部来自 [ceui.pixiv.db.mirror.AllBookmarkRepository]（镜像 SQLite），本页不直接拼 SQL；
 * 同步状态来自镜像服务的 Flow。页面状态与接线在 [AllBookmarkTagOverviewViewModel]，
 * 列表与统计头在 [AllBookmarkTagRowAdapter]。
 *
 * 五种页面状态都有明确呈现：
 * 1. 同步中：统计头转圈 +「正在后台同步 · 已同步 N 件」（空列表时列表区也解释原因）；
 * 2. 完成：「已全部同步」语义绿 + 手动刷新按钮（下拉刷新同样可用）；
 * 3. 空：真的没有收藏 / 搜索无结果分开措辞；
 * 4. 未登录：整页「登录后查看全收藏分类」+ 去登录；
 * 5. 镜像开关关闭：整页提示 + 去设置（深链到「设置 · 收藏与互动」的镜像开关）。
 */
class AllBookmarkTagOverviewFragment : Fragment(R.layout.fragment_all_bookmark_tags) {

    private val binding by viewBinding(FragmentAllBookmarkTagsBinding::bind)

    private val viewModel: AllBookmarkTagOverviewViewModel by viewModels()

    private lateinit var adapter: AllBookmarkTagRowAdapter

    /** 搜索是本地过滤：200ms debounce，清空立即生效（同收藏标签页的既有交互）。 */
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val palette = V3Palette.from(requireContext())

        // 顶栏：统一 toolbar_layout + setupV3Toolbar（状态栏 inset / 返回键 / 底部安全区都归它）。
        val toolbar = setupV3Toolbar(
            binding.root,
            getString(R.string.tag_browse_title),
            content = binding.pageContainer,
        )
        setUpMenu(toolbar)
        setUpSearch()
        setUpFilter()
        setUpList()
        setUpActions(palette)
        observeState()
        viewModel.start()
    }

    override fun onResume() {
        super.onResume()
        // 从设置里打开镜像开关 / 登录成功后回到本页时，状态必须重新判断，不能停在旧的不可用态。
        viewModel.onResumed()
    }

    override fun onDestroyView() {
        searchHandler.removeCallbacksAndMessages(null)
        pendingSearch = null
        super.onDestroyView()
    }

    // ─────────────────────────── 装配 ───────────────────────────

    /** toolbar 右侧「我的Tag排行」海报入口。图标是白色实心（AppBarOverlay 深色顶栏）。 */
    private fun setUpMenu(toolbar: Toolbar) {
        toolbar.menu.add(0, MENU_TAG_RANK, 0, getString(R.string.tag_browse_poster_entry))
            .setIcon(R.drawable.ic_tag_browse_leaderboard)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == MENU_TAG_RANK) {
                openTagRankPoster()
                true
            } else {
                false
            }
        }
    }

    private fun setUpSearch() {
        binding.searchClear.setOnClickListener { binding.searchInput.setText("") }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                binding.searchClear.isVisible = text.isNotEmpty()
                pendingSearch?.let { searchHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    pendingSearch = null
                    if (view != null) viewModel.setQuery(text)
                }
                pendingSearch = runnable
                // 清空立即恢复全量（用户明确意图），其余 200ms debounce。
                searchHandler.postDelayed(runnable, if (text.isBlank()) 0L else SEARCH_DEBOUNCE_MS)
            }
        })
    }

    private fun setUpFilter() {
        binding.includePrivateChip.pressScale()
        binding.includePrivateChip.setOnClickListener {
            viewModel.setIncludePrivate(!viewModel.state.value.includePrivate)
        }
        // chip 的实底是主题色，文字必须用运行时算出的 onPrimary（盛夏黄等浅主题白字会糊）。
        val palette = V3Palette.from(requireContext())
        binding.includePrivateChip.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_activated), intArrayOf()),
                intArrayOf(palette.onPrimary, requireContext().color(R.color.v3_text_2)),
            )
        )
    }

    private fun setUpList() {
        adapter = AllBookmarkTagRowAdapter(
            onTagClick = { tag ->
                TagBrowseContracts.openTagDetail(
                    requireContext(),
                    // 详情页标题用原始大小写展示；查询侧归一化由数据层负责（loadWorks 的 tagName 大小写不敏感）。
                    tag.displayName.ifEmpty { tag.tagName },
                    tag.translatedName,
                    tag.count,
                    viewModel.state.value.includePrivate,
                )
            },
            onRefreshClick = { triggerManualSync(showToast = true) },
        )
        binding.tagList.layoutManager = LinearLayoutManager(requireContext())
        binding.tagList.adapter = adapter
        binding.tagList.addItemDecoration(
            BottomDividerDecoration(requireContext(), R.drawable.hairline_divider),
        )
        // 与广场 / 下载管理同样的取舍：更新直接就位，不做入场 / 交叉淡化动画。
        binding.tagList.itemAnimator = null

        binding.refreshLayout.applyV3RefreshTheme()
        binding.refreshLayout.scrollUpFrom(binding.tagList)
        binding.refreshLayout.setOnRefreshListener { triggerManualSync(showToast = false) }
    }

    private fun setUpActions(palette: V3Palette) {
        val accent = palette.textAccent
        val density = resources.displayMetrics.density
        binding.stateIcon.imageTintList =
            ColorStateList.valueOf(V3Palette.withAlpha(accent, STATE_ICON_ALPHA))
        binding.pageStateIcon.imageTintList =
            ColorStateList.valueOf(V3Palette.withAlpha(accent, PAGE_ICON_ALPHA))
        binding.stateLoading.indeterminateTintList = ColorStateList.valueOf(palette.primary)
        binding.syncSpinner.indeterminateTintList = ColorStateList.valueOf(palette.primary)
        listOf(binding.stateAction, binding.pageStateAction).forEach {
            it.setTextColor(palette.onPrimary)
            it.typeface = requireContext().v3Font(600)
            it.pressScale()
        }
        // 「立即刷新」是同步横幅里的次级胶囊：主题浅底 + 强调文字，日夜/十套主题色都跟随。
        binding.syncNowAction.apply {
            background = palette.pillSecondary(999f * density, 1)
            setTextColor(accent)
            typeface = requireContext().v3Font(600)
            pressScale()
            setOnClickListener { triggerManualSync(showToast = true) }
        }
        binding.stateText.typeface = requireContext().v3Font(400)
        binding.pageStateMessage.typeface = requireContext().v3Font(400)
        binding.privateSyncHint.typeface = requireContext().v3Font(400)
        binding.syncDetail.typeface = requireContext().v3Font(400)
        binding.syncNote.typeface = requireContext().v3Font(400)
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    // ─────────────────────────── 渲染 ───────────────────────────

    private fun render(state: AllBookmarkTagOverviewUiState) {
        // 未登录 / 镜像关闭：整页状态页，隐藏搜索与列表，绝不白屏。
        val fullPageState = state.unloggedIn || state.mirrorDisabled
        binding.contentColumn.isVisible = !fullPageState
        binding.pageStateView.isVisible = fullPageState
        if (fullPageState) {
            renderFullPageState(state)
            return
        }

        // 搜索框只在首轮本地读取结束后可用：之前敲字也筛不出东西。同步中**不禁用**——
        // 搜索只过滤已经同步到本地的标签，用户等不及的时候可以先搜已加载的部分。
        binding.searchInput.isEnabled = state.loaded
        binding.includePrivateChip.isActivated = state.includePrivate
        // 私密书架要单独回填一次，首次打开开关时把这件事说清楚。
        binding.privateSyncHint.isVisible = state.includePrivate && !state.privateEverSynced
        renderSyncBanner(state)

        adapter.submit(
            AllBookmarkTagRowAdapter.Header(
                stats = state.stats,
                tagCount = state.tagTotal,
                syncing = state.syncing,
                allSynced = state.allSynced,
                syncedCount = state.syncedCount,
            ),
            state.tags,
        )
        renderBodyState(state)
        binding.refreshLayout.isRefreshing = state.refreshing
    }

    /**
     * 常驻同步横幅：只在还没补齐时出现。
     *
     * 用户对「等」的接受度取决于三件事说清楚没有：在等什么（正在获取全部收藏）、
     * 等到哪了（已同步 N 件 + 哪几本书正在同步）、为什么这么慢（慢速安全拉取避免限流）。
     * 补齐后整块收起，完成态交给列表头的「已全部同步（N 件作品）」。
     */
    private fun renderSyncBanner(state: AllBookmarkTagOverviewUiState) {
        binding.syncBanner.isVisible = state.syncing
        if (!state.syncing) return
        val ctx = requireContext()
        binding.syncTitle.text = if (state.cooldown) {
            ctx.getString(R.string.tag_browse_sync_cooldown_title)
        } else {
            ctx.getString(R.string.tag_browse_sync_fetching_title)
        }
        // 限流冷却时别让圈一直转：那不是「马上就好」，是引擎在故意等。
        binding.syncSpinner.isVisible = !state.cooldown
        val shelfNames = shelfDisplayNames(state.syncingShelves)
        binding.syncDetail.text = if (shelfNames.isEmpty()) {
            ctx.getString(
                R.string.tag_browse_sync_fetching_unknown,
                formatTagCount(state.syncedCount),
            )
        } else {
            ctx.getString(
                R.string.tag_browse_sync_fetching_detail,
                formatTagCount(state.syncedCount),
                shelfNames,
            )
        }
        binding.syncNote.setText(R.string.tag_browse_sync_safe_note)
    }

    /** 书架 → 界面名称：「插画（公开）、小说（悄悄）」。顺序即书架注册顺序。 */
    private fun shelfDisplayNames(shelves: List<BookmarkShelf>): String = shelves.joinToString("、") {
        val nameRes = when (it.contentType) {
            MirrorContentType.NOVEL ->
                if (it.restrict == MirrorRestrict.PRIVATE) R.string.tag_browse_shelf_novel_private
                else R.string.tag_browse_shelf_novel_public
            else ->
                if (it.restrict == MirrorRestrict.PRIVATE) R.string.tag_browse_shelf_illust_private
                else R.string.tag_browse_shelf_illust_public
        }
        getString(nameRes)
    }

    /** 列表区空态：加载 / 读取失败 / 搜索无结果 / 同步中暂无标签 / 真的没有收藏。 */
    private fun renderBodyState(state: AllBookmarkTagOverviewUiState) {
        if (state.tags.isNotEmpty()) {
            binding.stateContainer.isVisible = false
            return
        }
        val ctx = requireContext()
        val loading = !state.loaded && !state.loadFailed
        val text: CharSequence = when {
            loading -> ctx.getString(R.string.tag_browse_loading)
            state.loadFailed -> ctx.getString(R.string.tag_browse_load_failed)
            state.query.isNotEmpty() ->
                ctx.getString(R.string.tag_browse_empty_search, state.query)
            state.syncing -> if (state.syncedCount > 0) {
                ctx.getString(
                    R.string.tag_browse_loading_syncing,
                    formatTagCount(state.syncedCount),
                )
            } else {
                ctx.getString(R.string.tag_browse_loading_fetching)
            }
            // 有收藏但标签表还没聚合出东西（如只剩未分类作品）：别谎报「还没有收藏」。
            state.stats.totalWorks > 0 -> ctx.getString(R.string.tag_browse_empty_no_tags)
            else -> ctx.getString(R.string.tag_browse_empty_all_hint)
        }

        binding.stateContainer.isVisible = true
        binding.stateLoading.isVisible = loading
        binding.stateIcon.isVisible = !loading
        binding.stateText.text = text
        if (state.loadFailed) {
            binding.stateAction.text = ctx.getString(R.string.tag_browse_retry)
            binding.stateAction.isVisible = true
            binding.stateAction.setOnClickListener { viewModel.retryLoad() }
        } else {
            binding.stateAction.isVisible = false
            binding.stateAction.setOnClickListener(null)
        }
    }

    /** 整页不可用态：未登录 / 收藏镜像开关关闭，各给唯一的、真能解决问题的动作。 */
    private fun renderFullPageState(state: AllBookmarkTagOverviewUiState) {
        val ctx = requireContext()
        if (state.unloggedIn) {
            binding.pageStateTitle.text = ctx.getString(R.string.tag_browse_unlogged_title)
            binding.pageStateMessage.text = ctx.getString(R.string.tag_browse_unlogged_message)
            binding.pageStateAction.text = ctx.getString(R.string.tag_browse_login_action)
            binding.pageStateAction.setOnClickListener { openLogin() }
        } else {
            binding.pageStateTitle.text = ctx.getString(R.string.tag_browse_disabled_title)
            binding.pageStateMessage.text = ctx.getString(R.string.tag_browse_disabled_message)
            binding.pageStateAction.text = ctx.getString(R.string.tag_browse_disabled_action)
            binding.pageStateAction.setOnClickListener { openBookmarkMirrorSettings() }
        }
    }

    // ─────────────────────────── 动作 ───────────────────────────

    /**
     * 手动同步 = 去服务端对一次表头，而不是只重查本地库。
     *
     * 本页读的就是镜像表，不真的去同步一次的话，「在网页端收藏了几张 → 回 app 看看」
     * 这个最典型的动作会永远看不到新东西。下拉刷新与统计头上的刷新图标都走这里。
     */
    private fun triggerManualSync(showToast: Boolean) {
        viewModel.syncNow()
        if (showToast) Toaster.show(getString(R.string.tag_browse_sync_started))
    }

    /**
     * 「我的Tag排行」海报。
     *
     * 数据只来自 [AllBookmarkTagOverviewViewModel.posterSnapshot] —— 一份与
     * uid / includePrivate **绑定、且 tags+stats 原子提交**的快照。切换「包含私密收藏」的瞬间
     * 旧快照即被撤销，新数据落地前这里必须拒绝，不能拿列表里的旧数据兜底：
     * 那会把用户刚关掉的悄悄收藏导出成图片。
     */
    private fun openTagRankPoster() {
        val snapshot = viewModel.posterSnapshot()
        if (snapshot == null) {
            // 首屏还没读完 / 刚切完收藏范围并且新数据还在路上 / 读取失败。
            Toaster.show(getString(R.string.tag_browse_poster_unavailable))
            return
        }
        if (snapshot.tags.isEmpty()) {
            Toaster.show(getString(R.string.tag_browse_poster_not_ready))
            return
        }
        TagRankPosterEntry.show(parentFragmentManager, snapshot.tags, snapshot.stats)
    }

    private fun openLogin() {
        startActivity(
            Intent(requireContext(), TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.LOGIN.key)
            },
        )
    }

    /**
     * 深链到「设置 · 收藏与互动」并高亮收藏镜像开关（[SettingsCatalog.maybeHighlight] 认行 id 名）。
     * 万一设置目录改版导致跳转失败，退回到设置首页，不让按钮变成死路。
     */
    private fun openBookmarkMirrorSettings() {
        try {
            SettingsCatalog.open(
                requireContext(),
                SettingsCatalog.BOOKMARKS,
                MIRROR_SWITCH_ID_NAME,
            )
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "打开收藏设置失败，退回设置首页")
            startActivity(
                Intent(requireContext(), TemplateActivity::class.java).apply {
                    putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.SETTINGS.key)
                },
            )
        }
    }

    private companion object {
        const val TAG = "AllBookmarkTagOverview"
        const val SEARCH_DEBOUNCE_MS = 200L

        /** 自己的 toolbar 菜单 id，本页只有这一项，不会与别处冲突。 */
        const val MENU_TAG_RANK = 0x0A11

        /** [ceui.lisa.fragments.SettingsCatalog] 高亮用的行 id 名（设置布局里的 view id）。 */
        const val MIRROR_SWITCH_ID_NAME = "bookmark_mirror_rela"

        const val STATE_ICON_ALPHA = 0.55f
        const val PAGE_ICON_ALPHA = 0.80f
    }
}
