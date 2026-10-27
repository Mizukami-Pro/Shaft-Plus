package ceui.pixiv.ui.tagbrowse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import ceui.lisa.R
import ceui.lisa.databinding.AllBookmarkTagDetailFilterSheetBinding
import ceui.lisa.databinding.CellSearchFilterRowBinding
import ceui.pixiv.db.mirror.AllBookmarkAgeFilter
import ceui.pixiv.db.mirror.AllBookmarkAiFilter
import ceui.pixiv.db.mirror.AllBookmarkPageFilter
import ceui.pixiv.db.mirror.AllBookmarkRatioFilter
import ceui.pixiv.db.mirror.AllBookmarkResolutionFilter
import ceui.pixiv.ui.search.v3.BookmarkRangeSpec
import ceui.pixiv.ui.search.v3.DateRangePickerSheet
import ceui.pixiv.ui.search.v3.DurationBucket
import ceui.pixiv.ui.search.v3.DurationPickerSheet
import ceui.pixiv.ui.search.v3.KeywordUsersBucket
import ceui.pixiv.ui.search.v3.NumberRangeInputSheet
import ceui.pixiv.ui.search.v3.SearchFilterV3
import ceui.pixiv.ui.search.v3.SimplePickerSheet
import ceui.pixiv.ui.search.v3.V3BottomSheetBase
import ceui.pixiv.utils.setOnClick
import java.time.LocalDate
import java.time.ZoneId

/**
 * 标签详情页的「筛选」面板：把搜索页筛选器里**与本地收藏集合有关**的那部分搬过来。
 *
 * ## 行结构（两段设置卡，行本体全部复用 `cell_search_filter_row`）
 *
 * A 段（作品条件）：
 * 1. 投稿期间 —— 复用 [DurationPickerSheet] 的相对档 + [DateRangePickerSheet] 自定义区间，
 *    结果折算成 epoch ms 闭区间（`createdFromMs` / `createdToMs`）；
 * 2. 喜欢！数 —— [BookmarkRangeSpec.DEFAULT_PRESETS] 预设档 + [NumberRangeInputSheet]
 *    自定义区间（`minBookmarks` / `maxBookmarks`）；
 * 3. users入り 标签 —— `KeywordUsersBucket` 档位 → `usersTagMin`（带 N users入り 及以上标签）；
 * 4. 长宽比 → `ratio`；5. 分辨率 → `resolution`；6. 页数 → `pages`。
 * B 段（其他条件，**刻意不含「过滤已收藏」**——本页看的本来就是自己的收藏）：
 * 7. AI 作品三档 → `ai`；8. R-18 限制三档 → `age`。
 *
 * ## 「选择即生效」
 *
 * 面板里的每次选择都立刻通过 FragmentResult 回传给页面（[notifyParent]），页面当场
 * refresh + 重算「共 N 件」；底部「完成」只是关面板，不承担提交。「重置」把面板负责的
 * 8 个字段清零（顶部作品类别 chips 与搜索框不归面板管，保持不动）。这样做的理由：
 * 面板里的每一项都是**一次明确的用户选择**，不像搜索页那样要凑一堆条件再统一发请求；
 * 即时反馈（背后的列表与计数当场变化）比一个「应用」按钮更符合本地 SQL 的响应速度。
 *
 * ## 与搜索筛选（SearchFilterV3）的关系
 *
 * 只借它的 picker 与文案，**不共享状态**：页面的条件真源是 [AllBookmarkWorkFilter]，
 * 面板接收/回传的是只含面板字段的 [AllBookmarkTagFilterPatch]。picker 的
 * `newInstance(requestKey, current: SearchFilterV3)` 需要一个 SearchFilterV3，这里用一个
 * 只填「投稿期间」相关字段的临时对象适配（[pickerFilterAdapter]），其余字段全是默认值，
 * picker 也只看它读的那两个字段。
 *
 * ## 时区
 *
 * 投稿期间的预设档按 [DurationBucket.toDateRange] 的「今天 − N」算成日期，再折算成
 * **本地时区**当天 00:00:00 / 23:59:59.999 的 epoch ms 边界。收藏镜像里的 `createDateMs`
 * 是作品发布时间（pixiv 的 ISO 串解析出的绝对时刻），用本地日界只是为了符合用户「今天/
 * 一周内」的直觉；跨时区用户看到的边界会随设备时区走，与搜索页按本地日期发 start_date /
 * end_date 的口径一致。
 */
class AllBookmarkTagFilterSheet : V3BottomSheetBase() {

    private var _binding: AllBookmarkTagDetailFilterSheetBinding? = null
    private val binding get() = _binding!!

    /**
     * 面板当前草稿。**不是**条件真源（真源在页面 VM 的 [AllBookmarkWorkFilter] 里）：
     * 这里只镜像「面板负责的字段」，每次改动立刻回传页面。
     */
    private var draft: AllBookmarkTagFilterPatch = AllBookmarkTagFilterPatch.CLEARED

    private val requestKey: String
        get() = requireArguments().getString(ARG_REQUEST_KEY).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = savedInstanceState ?: requireArguments()
        @Suppress("DEPRECATION")
        val initial = source.getSerializable(ARG_INITIAL) as? AllBookmarkTagFilterPatch
        draft = initial ?: AllBookmarkTagFilterPatch.CLEARED
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 旋屏保留面板里已选的条件（真源在 VM，这里只是回显；写进 state 免得重开后行值回退）。
        outState.putSerializable(ARG_INITIAL, draft)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = AllBookmarkTagDetailFilterSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 底部动作：重置（次级胶囊）+ 完成（实色胶囊）。选择即生效，完成只关面板。
        binding.btnReset.setTextColor(palette.textAccent)
        binding.btnReset.setOnClick { resetPanel() }
        binding.btnDone.setOnClick { dismissAllowingStateLoss() }

        // 行点击 → 各维度 picker。
        binding.rowDuration.root.setOnClick { showDurationPicker() }
        binding.rowBookmark.root.setOnClick { showBookmarkPicker() }
        binding.rowKeywordBookmark.root.setOnClick { showKeywordUsersPicker() }
        binding.rowRatio.root.setOnClick { showRatioPicker() }
        binding.rowResolution.root.setOnClick { showResolutionPicker() }
        binding.rowPages.root.setOnClick { showPagesPicker() }
        binding.rowAi.root.setOnClick { showAiPicker() }
        binding.rowAge.root.setOnClick { showAgePicker() }

        registerPickerListeners()
        renderRows()
        // 被 FragmentManager 从 saved state 恢复（进程死亡回来 / 旋转）时，把面板自己的
        // draft 回灌一次页面：主路径是页面 VM 用 SavedStateHandle 恢复同一套条件，这时
        // updateQuery 判定「没变化」，不会触发任何刷新；这条只在两者因旧版本 saved state、
        // VM 恢复失败等原因对不上时兜底，保证「面板显示的条件 == 列表真实查询的条件」。
        if (savedInstanceState != null) notifyParent()
    }

    // ─────────────────────────── 状态 → UI ───────────────────────────

    /** 所有行的标题/值。每次草稿变化都重画一遍（8 行，成本可忽略）。 */
    private fun renderRows() {
        if (_binding == null) return
        bindRow(binding.rowDuration, R.string.search_filter_v3_row_duration, durationValue())
        bindRow(binding.rowBookmark, R.string.search_filter_v3_row_bookmark, bookmarkValue())
        bindRow(
            binding.rowKeywordBookmark,
            R.string.search_filter_v3_row_keyword_bookmark,
            usersValue(),
        )
        bindRow(binding.rowRatio, R.string.search_filter_v3_row_ratio, ratioValue())
        bindRow(binding.rowResolution, R.string.search_filter_v3_row_resolution, resolutionValue())
        bindRow(binding.rowPages, R.string.all_bookmark_tag_detail_row_pages, pagesValue())
        bindRow(binding.rowAi, R.string.search_filter_v3_section_ai, aiValue())
        bindRow(binding.rowAge, R.string.search_filter_v3_section_r18, ageValue())
    }

    private fun bindRow(row: CellSearchFilterRowBinding, titleRes: Int, value: String) {
        row.rowTitle.setText(titleRes)
        row.rowValue.text = value
    }

    /** 提交草稿：变了才重画 + 回传页面（「选择即生效」）。 */
    private fun updateDraft(transform: (AllBookmarkTagFilterPatch) -> AllBookmarkTagFilterPatch) {
        val next = transform(draft)
        if (next == draft) return
        draft = next
        renderRows()
        notifyParent()
    }

    private fun notifyParent() {
        // 页面在 onViewCreated 里用同一个 requestKey 登记 FragmentResultListener，
        // 收到就合并进 VM 并 refresh（见 AllBookmarkTagDetailFragment.onPanelFilterResult）。
        parentFragmentManager.setFragmentResult(requestKey, bundleOf(KEY_PATCH to draft))
    }

    /** 重置：只清面板负责的 8 个字段；作品类别 chips 与搜索框不动。 */
    private fun resetPanel() {
        if (draft == AllBookmarkTagFilterPatch.CLEARED) return
        draft = AllBookmarkTagFilterPatch.CLEARED
        renderRows()
        notifyParent()
    }

    // ─────────────────────────── 行值文案 ───────────────────────────

    private fun durationValue(): String {
        val from = draft.createdFromMs
        val to = draft.createdToMs
        if (from == null && to == null) return getString(R.string.search_filter_v3_duration_all)
        // 命中某个相对预设档 → 显示档名（按「今天」重算同款区间，与 picker 的互斥语义一致）。
        currentDurationBucket()?.let { bucket ->
            return getString(durationBucketLabelRes(bucket))
        }
        return getString(
            R.string.all_bookmark_tag_detail_duration_range,
            from?.let { formatDate(it) } ?: "—",
            to?.let { formatDate(it) } ?: "—",
        )
    }

    private fun bookmarkValue(): String {
        val min = draft.minBookmarks
        val max = draft.maxBookmarks
        return when {
            min == null && max == null ->
                getString(R.string.search_filter_v3_bookmark_unlimited_summary)
            min != null && max != null -> "$min ~ $max"
            min != null -> getString(R.string.search_filter_v3_bookmark_min, min)
            else -> "≤ $max"
        }
    }

    private fun usersValue(): String = if (draft.usersTagMin <= 0) {
        getString(R.string.search_filter_v3_duration_all)
    } else {
        getString(R.string.all_bookmark_tag_detail_users_min, draft.usersTagMin)
    }

    private fun ratioValue(): String = getString(
        when (draft.ratio) {
            AllBookmarkRatioFilter.ANY -> R.string.search_filter_v3_ratio_all
            AllBookmarkRatioFilter.LANDSCAPE -> R.string.search_filter_v3_ratio_landscape
            AllBookmarkRatioFilter.PORTRAIT -> R.string.search_filter_v3_ratio_portrait
            AllBookmarkRatioFilter.SQUARE -> R.string.search_filter_v3_ratio_square
        }
    )

    private fun resolutionValue(): String = getString(
        when (draft.resolution) {
            AllBookmarkResolutionFilter.ANY -> R.string.search_filter_v3_resolution_all
            AllBookmarkResolutionFilter.ABOVE_3000 ->
                R.string.search_filter_v3_resolution_above_3000
            AllBookmarkResolutionFilter.BETWEEN_1000_AND_2999 ->
                R.string.search_filter_v3_resolution_1000_2999
            AllBookmarkResolutionFilter.BELOW_1000 ->
                R.string.search_filter_v3_resolution_below_1000
        }
    )

    private fun pagesValue(): String = getString(
        when (draft.pages) {
            AllBookmarkPageFilter.ANY -> R.string.all_bookmark_tag_detail_pages_all
            AllBookmarkPageFilter.SINGLE_PAGE -> R.string.all_bookmark_tag_detail_pages_single
            AllBookmarkPageFilter.MULTI_PAGE -> R.string.all_bookmark_tag_detail_pages_multi
        }
    )

    private fun aiValue(): String = getString(
        when (draft.ai) {
            AllBookmarkAiFilter.ANY -> R.string.search_filter_v3_ai_all
            AllBookmarkAiFilter.EXCLUDE_AI -> R.string.search_filter_v3_ai_exclude
            AllBookmarkAiFilter.ONLY_AI -> R.string.search_filter_v3_ai_only
        }
    )

    private fun ageValue(): String = getString(
        when (draft.age) {
            AllBookmarkAgeFilter.ANY -> R.string.search_filter_v3_r18_all
            AllBookmarkAgeFilter.SAFE_ONLY -> R.string.search_filter_v3_r18_safe
            AllBookmarkAgeFilter.R18_ONLY -> R.string.search_filter_v3_r18_only
        }
    )

    // ─────────────────────────── picker 入口 ───────────────────────────

    private fun showDurationPicker() {
        DurationPickerSheet.newInstance(REQ_DURATION, pickerFilterAdapter())
            .show(childFragmentManager, REQ_DURATION)
    }

    private fun showDateRangePicker() {
        DateRangePickerSheet.newInstance(REQ_DURATION_DATES, pickerFilterAdapter())
            .show(childFragmentManager, REQ_DURATION_DATES)
    }

    private fun showBookmarkPicker() {
        val presets = BookmarkRangeSpec.DEFAULT_PRESETS
        val labels = listOf(getString(R.string.search_filter_v3_bookmark_unlimited_summary)) +
                presets.map { bookmarkRangeLabel(it) } +
                getString(R.string.search_filter_v3_bookmark_custom)
        val min = draft.minBookmarks
        val max = draft.maxBookmarks
        // 命中不到预设档的非空区间只能来自「指定点赞数」→ 高亮末位自定义行。
        val selected = when {
            min == null && max == null -> 0
            else -> presets.indexOfFirst { it.min == min && it.max == max }
                .takeIf { it >= 0 }?.plus(1) ?: labels.lastIndex
        }
        showSimplePicker(
            REQ_BOOKMARK,
            getString(R.string.search_filter_v3_row_bookmark),
            labels,
            selected,
        )
    }

    private fun showBookmarkCustomInput() {
        NumberRangeInputSheet.newInstance(
            REQ_BOOKMARK_CUSTOM,
            getString(R.string.search_filter_v3_bookmark_custom),
            "",   // 点赞数没有单位后缀
            draft.minBookmarks,
            draft.maxBookmarks,
        ).show(childFragmentManager, REQ_BOOKMARK_CUSTOM)
    }

    private fun showKeywordUsersPicker() {
        val buckets = KeywordUsersBucket.entries.filter { it != KeywordUsersBucket.None }
        val labels = listOf(getString(R.string.search_filter_v3_duration_all)) +
                buckets.map { getString(R.string.all_bookmark_tag_detail_users_min, it.min) }
        val selected = buckets.indexOfFirst { it.min == draft.usersTagMin }
            .takeIf { it >= 0 }?.plus(1) ?: 0
        showSimplePicker(
            REQ_KEYWORD_BOOKMARK,
            getString(R.string.search_filter_v3_row_keyword_bookmark),
            labels,
            selected,
        )
    }

    private fun showRatioPicker() {
        showEnumPicker(
            REQ_RATIO,
            getString(R.string.search_filter_v3_row_ratio),
            RATIO_VALUES,
            draft.ratio,
            { value -> ratioLabel(value) },
        )
    }

    private fun showResolutionPicker() {
        showEnumPicker(
            REQ_RESOLUTION,
            getString(R.string.search_filter_v3_row_resolution),
            RESOLUTION_VALUES,
            draft.resolution,
            { value -> resolutionLabel(value) },
        )
    }

    private fun showPagesPicker() {
        showEnumPicker(
            REQ_PAGES,
            getString(R.string.all_bookmark_tag_detail_row_pages),
            PAGE_VALUES,
            draft.pages,
            { value -> pagesLabel(value) },
        )
    }

    private fun showAiPicker() {
        showEnumPicker(
            REQ_AI,
            getString(R.string.search_filter_v3_section_ai),
            AI_VALUES,
            draft.ai,
            { value -> aiLabel(value) },
        )
    }

    private fun showAgePicker() {
        showEnumPicker(
            REQ_AGE,
            getString(R.string.search_filter_v3_section_r18),
            AGE_VALUES,
            draft.age,
            { value -> ageLabel(value) },
        )
    }

    private fun <T> showEnumPicker(
        requestKey: String,
        title: String,
        values: List<T>,
        current: T,
        label: (T) -> String,
    ) {
        showSimplePicker(requestKey, title, values.map(label), values.indexOf(current).coerceAtLeast(0))
    }

    private fun showSimplePicker(
        requestKey: String,
        title: String,
        labels: List<String>,
        selected: Int,
    ) {
        SimplePickerSheet.newInstance(requestKey, title, labels, selected)
            .show(childFragmentManager, requestKey)
    }

    /**
     * picker 需要 `SearchFilterV3` 作为「当前值」入参（见 [DurationPickerSheet.newInstance] /
     * [DateRangePickerSheet.newInstance]），而本页用的是 [AllBookmarkWorkFilter]。这里把
     * 投稿期间两字段适配过去，其余字段全是默认值 —— picker 只读 durationBucket / startDate /
     * endDate，不会看到别的字段。改成给 picker 加重载当然更干净，但那要动搜索页的共享文件，
     * 本任务不动它。
     */
    private fun pickerFilterAdapter(): SearchFilterV3 = SearchFilterV3(
        durationBucket = currentDurationBucket(),
        startDate = draft.createdFromMs?.let { formatDate(it) },
        endDate = draft.createdToMs?.let { formatDate(it) },
    )

    /** 当前区间命中哪个相对档（按「今天」重算区间比对）；自定义/不限返回 null。 */
    private fun currentDurationBucket(): DurationBucket? {
        val from = draft.createdFromMs ?: return null
        val to = draft.createdToMs ?: return null
        val today = LocalDate.now()
        return DurationBucket.entries.firstOrNull { bucket ->
            val (startDate, endDate) = bucket.toDateRange(today)
            from == dayStartMs(startDate) && to == dayEndMs(endDate)
        }
    }

    // ─────────────────────────── picker 结果 ───────────────────────────

    private fun registerPickerListeners() {
        val fm = childFragmentManager

        fm.setFragmentResultListener(REQ_DURATION, viewLifecycleOwner) { _, bundle ->
            @Suppress("DEPRECATION")
            val patch = bundle.getSerializable(DurationPickerSheet.KEY_PATCH)
                    as? DurationPickerSheet.Patch ?: return@setFragmentResultListener
            if (patch.openCustomRange) {
                // 「指定期间」→ 不动条件，接着弹日期范围 picker。
                showDateRangePicker()
            } else if (patch.bucket != null) {
                val (startDate, endDate) = patch.bucket.toDateRange(LocalDate.now())
                updateDraft {
                    it.copy(
                        createdFromMs = dayStartMs(startDate),
                        createdToMs = dayEndMs(endDate),
                    )
                }
            } else {
                // 「不限」：起止一起清掉。
                updateDraft { it.copy(createdFromMs = null, createdToMs = null) }
            }
        }

        fm.setFragmentResultListener(REQ_DURATION_DATES, viewLifecycleOwner) { _, bundle ->
            @Suppress("DEPRECATION")
            val patch = bundle.getSerializable(DateRangePickerSheet.KEY_PATCH)
                    as? DateRangePickerSheet.Patch ?: return@setFragmentResultListener
            updateDraft {
                it.copy(
                    createdFromMs = patch.startDate?.let { dayStartMs(it) },
                    createdToMs = patch.endDate?.let { dayEndMs(it) },
                )
            }
        }

        fm.setFragmentResultListener(REQ_BOOKMARK, viewLifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            val presets = BookmarkRangeSpec.DEFAULT_PRESETS
            when {
                idx == 0 -> updateDraft {
                    it.copy(minBookmarks = null, maxBookmarks = null)
                }
                idx <= presets.size -> {
                    val spec = presets[idx - 1]
                    updateDraft { it.copy(minBookmarks = spec.min, maxBookmarks = spec.max) }
                }
                // 末位「指定点赞数」→ 弹数字范围输入。
                else -> showBookmarkCustomInput()
            }
        }

        fm.setFragmentResultListener(REQ_BOOKMARK_CUSTOM, viewLifecycleOwner) { _, bundle ->
            @Suppress("DEPRECATION")
            val patch = bundle.getSerializable(NumberRangeInputSheet.KEY_PATCH)
                    as? NumberRangeInputSheet.Patch ?: return@setFragmentResultListener
            updateDraft { it.copy(minBookmarks = patch.min, maxBookmarks = patch.max) }
        }

        fm.setFragmentResultListener(REQ_KEYWORD_BOOKMARK, viewLifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            val buckets = KeywordUsersBucket.entries.filter { it != KeywordUsersBucket.None }
            val min = if (idx == 0) 0 else buckets.getOrNull(idx - 1)?.min ?: 0
            updateDraft { it.copy(usersTagMin = min) }
        }

        fm.setFragmentResultListener(REQ_RATIO, viewLifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            RATIO_VALUES.getOrNull(idx)?.let { value ->
                updateDraft { it.copy(ratio = value) }
            }
        }

        fm.setFragmentResultListener(REQ_RESOLUTION, viewLifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            RESOLUTION_VALUES.getOrNull(idx)?.let { value ->
                updateDraft { it.copy(resolution = value) }
            }
        }

        fm.setFragmentResultListener(REQ_PAGES, viewLifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            PAGE_VALUES.getOrNull(idx)?.let { value ->
                updateDraft { it.copy(pages = value) }
            }
        }

        fm.setFragmentResultListener(REQ_AI, viewLifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            AI_VALUES.getOrNull(idx)?.let { value ->
                updateDraft { it.copy(ai = value) }
            }
        }

        fm.setFragmentResultListener(REQ_AGE, viewLifecycleOwner) { _, bundle ->
            val idx = bundle.getInt(SimplePickerSheet.KEY_IDX)
            AGE_VALUES.getOrNull(idx)?.let { value ->
                updateDraft { it.copy(age = value) }
            }
        }
    }

    // ─────────────────────────── 文案 / 日期工具 ───────────────────────────

    private fun durationBucketLabelRes(bucket: DurationBucket): Int = when (bucket) {
        DurationBucket.Last24Hours -> R.string.search_filter_v3_duration_24h
        DurationBucket.LastWeek -> R.string.search_filter_v3_duration_week
        DurationBucket.LastMonth -> R.string.search_filter_v3_duration_month
        DurationBucket.LastHalfYear -> R.string.search_filter_v3_duration_half_year
        DurationBucket.LastYear -> R.string.search_filter_v3_duration_year
    }

    private fun bookmarkRangeLabel(spec: BookmarkRangeSpec): String = when {
        spec.min != null && spec.max != null -> "${spec.min} ~ ${spec.max}"
        spec.min != null -> getString(R.string.search_filter_v3_bookmark_min, spec.min)
        spec.max != null -> "~ ${spec.max}"
        else -> getString(R.string.search_filter_v3_bookmark_unlimited_summary)
    }

    private fun ratioLabel(value: AllBookmarkRatioFilter): String = getString(
        when (value) {
            AllBookmarkRatioFilter.ANY -> R.string.search_filter_v3_ratio_all
            AllBookmarkRatioFilter.LANDSCAPE -> R.string.search_filter_v3_ratio_landscape
            AllBookmarkRatioFilter.PORTRAIT -> R.string.search_filter_v3_ratio_portrait
            AllBookmarkRatioFilter.SQUARE -> R.string.search_filter_v3_ratio_square
        }
    )

    private fun resolutionLabel(value: AllBookmarkResolutionFilter): String = getString(
        when (value) {
            AllBookmarkResolutionFilter.ANY -> R.string.search_filter_v3_resolution_all
            AllBookmarkResolutionFilter.ABOVE_3000 ->
                R.string.search_filter_v3_resolution_above_3000
            AllBookmarkResolutionFilter.BETWEEN_1000_AND_2999 ->
                R.string.search_filter_v3_resolution_1000_2999
            AllBookmarkResolutionFilter.BELOW_1000 ->
                R.string.search_filter_v3_resolution_below_1000
        }
    )

    private fun pagesLabel(value: AllBookmarkPageFilter): String = getString(
        when (value) {
            AllBookmarkPageFilter.ANY -> R.string.all_bookmark_tag_detail_pages_all
            AllBookmarkPageFilter.SINGLE_PAGE -> R.string.all_bookmark_tag_detail_pages_single
            AllBookmarkPageFilter.MULTI_PAGE -> R.string.all_bookmark_tag_detail_pages_multi
        }
    )

    private fun aiLabel(value: AllBookmarkAiFilter): String = getString(
        when (value) {
            AllBookmarkAiFilter.ANY -> R.string.search_filter_v3_ai_all
            AllBookmarkAiFilter.EXCLUDE_AI -> R.string.search_filter_v3_ai_exclude
            AllBookmarkAiFilter.ONLY_AI -> R.string.search_filter_v3_ai_only
        }
    )

    private fun ageLabel(value: AllBookmarkAgeFilter): String = getString(
        when (value) {
            AllBookmarkAgeFilter.ANY -> R.string.search_filter_v3_r18_all
            AllBookmarkAgeFilter.SAFE_ONLY -> R.string.search_filter_v3_r18_safe
            AllBookmarkAgeFilter.R18_ONLY -> R.string.search_filter_v3_r18_only
        }
    )

    /** LocalDate（YYYY-MM-DD）→ 本地时区当天 00:00:00.000 的 epoch ms。 */
    private fun dayStartMs(date: String): Long =
        LocalDate.parse(date).atStartOfDay(ZONE).toInstant().toEpochMilli()

    /** LocalDate（YYYY-MM-DD）→ 本地时区当天 23:59:59.999 的 epoch ms。 */
    private fun dayEndMs(date: String): Long =
        LocalDate.parse(date).plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli() - 1L

    /** epoch ms → 本地日期串（YYYY-MM-DD），picker 适配与自定义区间回显共用。 */
    private fun formatDate(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs).atZone(ZONE).toLocalDate().toString()

    companion object {
        /** 结果 Bundle 里 [AllBookmarkTagFilterPatch] 的 key。 */
        const val KEY_PATCH = "patch"

        private const val ARG_REQUEST_KEY = "requestKey"
        private const val ARG_INITIAL = "initial"

        // 行 → picker 的 FragmentResult request keys（面板自己的 childFragmentManager 内流转）。
        private const val REQ_DURATION = "all_bookmark_tag_filter_duration"
        private const val REQ_DURATION_DATES = "all_bookmark_tag_filter_duration_dates"
        private const val REQ_BOOKMARK = "all_bookmark_tag_filter_bookmark"
        private const val REQ_BOOKMARK_CUSTOM = "all_bookmark_tag_filter_bookmark_custom"
        private const val REQ_KEYWORD_BOOKMARK = "all_bookmark_tag_filter_keyword_bookmark"
        private const val REQ_RATIO = "all_bookmark_tag_filter_ratio"
        private const val REQ_RESOLUTION = "all_bookmark_tag_filter_resolution"
        private const val REQ_PAGES = "all_bookmark_tag_filter_pages"
        private const val REQ_AI = "all_bookmark_tag_filter_ai"
        private const val REQ_AGE = "all_bookmark_tag_filter_age"

        /** 面板里的枚举档位顺序（picker 的 idx 与它对应；顺序即 UI 顺序）。 */
        private val RATIO_VALUES = listOf(
            AllBookmarkRatioFilter.ANY,
            AllBookmarkRatioFilter.LANDSCAPE,
            AllBookmarkRatioFilter.PORTRAIT,
            AllBookmarkRatioFilter.SQUARE,
        )
        private val RESOLUTION_VALUES = listOf(
            AllBookmarkResolutionFilter.ANY,
            AllBookmarkResolutionFilter.ABOVE_3000,
            AllBookmarkResolutionFilter.BETWEEN_1000_AND_2999,
            AllBookmarkResolutionFilter.BELOW_1000,
        )
        private val PAGE_VALUES = listOf(
            AllBookmarkPageFilter.ANY,
            AllBookmarkPageFilter.SINGLE_PAGE,
            AllBookmarkPageFilter.MULTI_PAGE,
        )
        private val AI_VALUES = listOf(
            AllBookmarkAiFilter.ANY,
            AllBookmarkAiFilter.EXCLUDE_AI,
            AllBookmarkAiFilter.ONLY_AI,
        )
        private val AGE_VALUES = listOf(
            AllBookmarkAgeFilter.ANY,
            AllBookmarkAgeFilter.SAFE_ONLY,
            AllBookmarkAgeFilter.R18_ONLY,
        )

        private val ZONE: ZoneId = ZoneId.systemDefault()

        fun newInstance(
            requestKey: String,
            initial: AllBookmarkTagFilterPatch,
        ): AllBookmarkTagFilterSheet = AllBookmarkTagFilterSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putSerializable(ARG_INITIAL, initial)
            }
        }
    }
}
