package ceui.pixiv.ui.tagbrowse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.pixiv.db.mirror.AllBookmarkAgeFilter
import ceui.pixiv.db.mirror.AllBookmarkAiFilter
import ceui.pixiv.db.mirror.AllBookmarkPageFilter
import ceui.pixiv.db.mirror.AllBookmarkRatioFilter
import ceui.pixiv.db.mirror.AllBookmarkRepository
import ceui.pixiv.db.mirror.AllBookmarkResolutionFilter
import ceui.pixiv.db.mirror.AllBookmarkSort
import ceui.pixiv.db.mirror.AllBookmarkWorkFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

/**
 * 作品类别筛选:页面顶部的「全部 / 插画 / 漫画 / 小说 / 动图」五档。
 *
 * [workTypes] 直接是**数据库 `workType` 原始值**（交给 [AllBookmarkWorkFilter.workTypes]），
 * 不做任何合并：动图单独一档，选「插画」不再包含动图。这与旧 [AllBookmarkRepository.loadWorks]
 * 重载里 `resolveWorkTypes` 的「illust 自动并入 ugoira」不同——那条映射只服务旧接口，
 * 新契约要求 UI 自己给出组合（见 AllBookmarkWorkFilter 的 KDoc）。
 */
enum class AllBookmarkWorkType(val workTypes: List<String>) {
    ALL(emptyList()),
    ILLUST(listOf("illust")),
    MANGA(listOf("manga")),
    NOVEL(listOf("novel")),
    UGOIRA(listOf("ugoira"));

    companion object {
        /** 反查：chip 选中态渲染用。空 = 全部；认不出的组合按「全部」处理。 */
        fun of(workTypes: List<String>): AllBookmarkWorkType =
            entries.firstOrNull { it.workTypes == workTypes } ?: ALL
    }
}

/**
 * 标签详情页的查询条件：排序 + 完整的作品筛选（[AllBookmarkWorkFilter]）。
 *
 * ## 为什么用 AllBookmarkWorkFilter 而不是一堆散字段
 *
 * 数据层（mirror-data）的新重载直接吃这个对象，UI 侧也把它当**唯一条件真源**：作品类别
 * （workTypes）、搜索词（keyword）、投稿期间、喜欢！数、users入り、AI、R-18、长宽比、
 * 分辨率、页数都在里面。标签名（tagName）由页面固定、[AllBookmarkTagDetailViewModel] 在
 * 下推数据层时统一注入，不放进这个可编辑对象，免得筛选面板能把它改掉。
 *
 * ## 为什么默认排序是「作品发布时间」而不是「收藏时间」
 *
 * 这个页面跨四个书架（插画公开/悄悄 + 小说公开/悄悄）聚合。收藏顺序在镜像表里是
 * [ceui.pixiv.db.mirror.BookmarkMirrorEntity.bookmarkSeq]，但那是**每个书架各自从 0 递减
 * 编号**的本地序号 —— 公开书架的第 -100 条和悄悄书架的 -100 条之间没有任何可比性，
 * 跨书架 ORDER BY 它只会得到一条「先按书架分组、组内有序」的假顺序。pixiv 又不下发收藏
 * 时间，所以「收藏时间新→旧」在跨书架场景下**算不出来**，与其给一个假的，不如不给：
 * 默认按**作品发布时间**新→旧，五种排序全部用作品维度的键（见 [AllBookmarkSort]）。
 */
data class AllBookmarkTagQuery(
    /** 默认「作品发布时间：新 → 旧」。 */
    val sort: AllBookmarkSort = AllBookmarkSort.NEWEST_PUBLISHED,
    /** 作品维度筛选条件（含 workTypes / keyword）。 */
    val filter: AllBookmarkWorkFilter = AllBookmarkWorkFilter(),
) {
    /** 搜索框内容（作品内搜索词）。 */
    val keyword: String get() = filter.keyword

    /** 顶部作品类别 chips 的选中档。 */
    val type: AllBookmarkWorkType get() = AllBookmarkWorkType.of(filter.workTypes)

    /** 有搜索词 / 类别 / 任何筛选条件 —— 空态要区分「条件没命中」和「这个标签下本来没有」。 */
    val hasAnyCondition: Boolean get() = filter.activeConditionCount > 0

    /**
     * 筛选入口 chip 的徽标数：**除作品类别（顶部 chips 管）与搜索词（顶部搜索框管）之外**
     * 的已启用条件数。筛选面板里的每一行各算一项（区间两端算一项，同
     * [AllBookmarkWorkFilter.activeConditionCount] 的口径）。
     */
    val panelConditionCount: Int
        get() = filter.activeConditionCount -
                (if (filter.workTypes.isNotEmpty()) 1 else 0) -
                (if (filter.keyword.isNotBlank()) 1 else 0)
}

/**
 * 筛选面板与页面之间的条件补丁：**只含面板负责的字段**。
 *
 * 面板（[AllBookmarkTagFilterSheet]）不碰 workTypes / keyword / tagName，所以它回传的
 * 永远是这个 patch，由 [AllBookmarkTagDetailViewModel] 合并进当前的
 * [AllBookmarkWorkFilter]——这样「顶部 chips + 搜索框 + 面板」三处各管各的，不会互相覆盖。
 *
 * 实现 Serializable：面板是 DialogFragment，结果走 FragmentResult / arguments，需要能进
 * Bundle（旋转、进程重建都不丢）。
 */
data class AllBookmarkTagFilterPatch(
    /** 投稿期间起点（作品发布时间，epoch ms，闭区间）。 */
    val createdFromMs: Long? = null,
    /** 投稿期间终点（epoch ms，闭区间）。 */
    val createdToMs: Long? = null,
    /** 喜欢！数下限（作品总收藏数，含）。 */
    val minBookmarks: Int? = null,
    /** 喜欢！数上限（含）。 */
    val maxBookmarks: Int? = null,
    /** users入り 标签下限：0 = 不限。 */
    val usersTagMin: Int = 0,
    val ai: AllBookmarkAiFilter = AllBookmarkAiFilter.ANY,
    val age: AllBookmarkAgeFilter = AllBookmarkAgeFilter.ANY,
    val ratio: AllBookmarkRatioFilter = AllBookmarkRatioFilter.ANY,
    val resolution: AllBookmarkResolutionFilter = AllBookmarkResolutionFilter.ANY,
    val pages: AllBookmarkPageFilter = AllBookmarkPageFilter.ANY,
) : java.io.Serializable {

    /** 合并进一个 filter：只覆盖面板字段，其它字段（workTypes / keyword / tagName）原样保留。 */
    fun applyTo(filter: AllBookmarkWorkFilter): AllBookmarkWorkFilter = filter.copy(
        createdFromMs = createdFromMs,
        createdToMs = createdToMs,
        minBookmarks = minBookmarks,
        maxBookmarks = maxBookmarks,
        usersTagMin = usersTagMin,
        ai = ai,
        age = age,
        ratio = ratio,
        resolution = resolution,
        pages = pages,
    )

    companion object {
        /** 从当前 filter 抽出面板字段（打开面板时的初始状态）。 */
        fun of(filter: AllBookmarkWorkFilter): AllBookmarkTagFilterPatch =
            AllBookmarkTagFilterPatch(
                createdFromMs = filter.createdFromMs,
                createdToMs = filter.createdToMs,
                minBookmarks = filter.minBookmarks,
                maxBookmarks = filter.maxBookmarks,
                usersTagMin = filter.usersTagMin,
                ai = filter.ai,
                age = filter.age,
                ratio = filter.ratio,
                resolution = filter.resolution,
                pages = filter.pages,
            )

        /** 空补丁 = 面板全部条件清零（「重置」按钮用）。 */
        val CLEARED = AllBookmarkTagFilterPatch()
    }
}

/**
 * 标签详情页的状态中枢。
 *
 * **查询条件归 VM**（对齐 [ceui.pixiv.ui.library.BookmarkLibraryViewModel] 的约定）：
 * 旋转 / 视图重建后用户刚调的排序、类别和筛选面板条件都不丢；
 * [AllBookmarkTagDetailFeedSource] 也只从这里读条件，因此数据源可以长期持有、不必捕获 Fragment。
 *
 * ## 进程死亡恢复（SavedStateHandle）
 *
 * 旋转只是重建视图，VM 实例还在；但**进程被系统回收后再回来**，VM 会重新构造——如果条件
 * 只放内存字段，它会退回默认值：列表按默认条件查询，而筛选面板（DialogFragment 由
 * FragmentManager 从自己的 saved state 恢复 draft）却显示着用户上次选的条件，两者对不上。
 * 所以完整 query（搜索词 / 排序 / 作品类别 / 面板 8 项条件）在每次变更时写进
 * [SavedStateHandle]，并在**构造函数里**读回 [AllBookmarkTagQuery]：
 * `feedViewModels {}` 里的 FeedViewModel 是在 Fragment 首次访问时才构造的，而它构造完
 * 立刻发起首屏 `source.load()`；`sourceProvider` 第一步就是取出本 VM（带恢复后的 query），
 * 因此**第一次 FeedSource 查询用的就是恢复出来的条件**，不必等页面再补一次刷新。
 *
 * 旋转路径不经过 SavedStateHandle 的写入/读回（同一 VM 实例继续用），行为与之前完全一致。
 *
 * 「共 N 件」与列表是**两次独立查询**：计数回答「这套条件一共命中多少件」，列表只加载
 * 看得见的那一页。分开跑，列表才不会为了显示一个数字把所有命中行都捞出来。
 */
class AllBookmarkTagDetailViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 当前登录账号 id；未登录为 0（此时列表恒空）。 */
    var uid: Long = 0L
        private set

    /** 查询用的标签名：**小写归一**（镜像标签表的 `tagName` 口径）。 */
    var tagName: String = ""
        private set

    /** 展示用的标签名：保留原始大小写，只用于顶栏标题。 */
    var displayTagName: String = ""
        private set

    var includePrivate: Boolean = true
        private set

    /** 幂等绑定：Fragment 与 FeedSource 都会走到，谁先谁后都行。 */
    private var bound = false

    /**
     * 查询条件真源。初值从 [savedStateHandle] 恢复（进程死亡后回到用户离开前的那套条件）。
     * 必须放在字段初始化里：VM 构造完成时 query 就是终态，FeedSource 第一次 `load()` 才
     * 不会读到默认条件（见类 KDoc）。
     */
    private val _query = MutableStateFlow(restoreQuery())
    val query: StateFlow<AllBookmarkTagQuery> = _query.asStateFlow()

    /** 当前条件命中的件数；null = 还在算 / 算失败。 */
    private val _resultCount = MutableStateFlow<Int?>(null)
    val resultCount: StateFlow<Int?> = _resultCount.asStateFlow()

    private var countJob: Job? = null

    /**
     * 绑定本次页面要看的标签。重复调用直接返回（旋转重建后拿到的是同一个 VM）。
     *
     * @param rawTagName 入口给的展示名（原始大小写）；查询统一转小写，与镜像标签表对齐。
     *   防御性地去掉可能被入口带上的 `#` 前缀——顶栏自己会补一个，不然会显示成 `##tag`。
     */
    fun bind(uid: Long, rawTagName: String, includePrivate: Boolean) {
        if (bound) return
        bound = true
        this.uid = uid
        displayTagName = rawTagName.trim().removePrefix("#").trim()
        tagName = displayTagName.lowercase(Locale.ROOT)
        this.includePrivate = includePrivate
        Timber.tag(TAG).d("绑定标签 %s（uid=%d includePrivate=%b）", tagName, uid, includePrivate)
        refreshCount()
    }

    /**
     * 下推给数据层的最终 filter：把页面固定的标签名注入进去。
     * 数据源与计数都走这一个口，两边条件永远一致。
     */
    fun resolvedFilter(): AllBookmarkWorkFilter =
        _query.value.filter.copy(tagName = tagName.takeIf { it.isNotEmpty() })

    fun resolvedSort(): AllBookmarkSort = _query.value.sort

    /**
     * 改查询条件。返回 true 表示真的变了（调用方据此决定要不要重刷列表）——
     * 点一下又点回来是很常见的，白刷一次列表会让滚动位置无谓地跳回顶部。
     *
     * 排序变化不影响命中数（它不减少结果），其余条件一律重算计数。
     */
    fun updateQuery(transform: (AllBookmarkTagQuery) -> AllBookmarkTagQuery): Boolean {
        val old = _query.value
        val next = transform(old)
        if (next == old) return false
        _query.value = next
        // 进程随时可能被回收：条件一变就落进 SavedStateHandle（纯内存写，几微秒），
        // 下次重建时 [restoreQuery] 读到的就是它。
        persistQuery(next)
        Timber.tag(TAG).d(
            "条件变更 sort=%s types=%s conditions=%d kw='%s'",
            next.sort, next.filter.workTypes, next.panelConditionCount, next.filter.keyword,
        )
        if (next.filter != old.filter) refreshCount()
        return true
    }

    /** 顶部作品类别 chips。 */
    fun setWorkType(type: AllBookmarkWorkType): Boolean =
        updateQuery { it.copy(filter = it.filter.copy(workTypes = type.workTypes)) }

    /** 顶部排序 chips。 */
    fun setSort(sort: AllBookmarkSort): Boolean =
        updateQuery { it.copy(sort = sort) }

    /** 搜索框关键词。 */
    fun setKeyword(keyword: String): Boolean =
        updateQuery { it.copy(filter = it.filter.copy(keyword = keyword)) }

    /**
     * 筛选面板提交：只替换面板负责的那些字段（投稿期间 / 喜欢！数 / users入り / AI / R-18 /
     * 长宽比 / 分辨率 / 页数），workTypes、keyword、tagName 原样保留——面板不碰顶部
     * chips 与搜索框，也不允许覆盖标签名。「重置」按钮也走这里（回传一个空 patch）。
     */
    fun applyPanelFilter(patch: AllBookmarkTagFilterPatch): Boolean =
        updateQuery { query -> query.copy(filter = patch.applyTo(query.filter)) }

    // ─────────────────────── 进程死亡恢复（SavedStateHandle） ───────────────────────

    /**
     * 构造期恢复完整 query。任何一项缺失 / 无法识别（用户降级、字段改名、旧的 saved state）
     * 都回退到该项的默认值，**绝不抛**——恢复失败最多丢一次筛选，崩掉页面则连列表都没了。
     *
     * 只存 Bundle 原生支持的标量（String / Long / Int），不塞 Serializable 对象：
     * 枚举存 `name`，可空数值存哨兵（[UNSET_LONG] / [UNSET_INT]）。
     */
    private fun restoreQuery(): AllBookmarkTagQuery {
        val filter = AllBookmarkWorkFilter(
            keyword = readString(KEY_KEYWORD).orEmpty(),
            // 作品类别统一由 chip 的单选枚举表达；反查回 workTypes 列表。
            workTypes = readEnum(KEY_WORK_TYPE, AllBookmarkWorkType.ALL).workTypes,
            createdFromMs = readUnsetLong(KEY_CREATED_FROM),
            createdToMs = readUnsetLong(KEY_CREATED_TO),
            minBookmarks = readUnsetInt(KEY_MIN_BOOKMARKS),
            maxBookmarks = readUnsetInt(KEY_MAX_BOOKMARKS),
            usersTagMin = (readInt(KEY_USERS_TAG_MIN) ?: 0).coerceAtLeast(0),
            ai = readEnum(KEY_AI, AllBookmarkAiFilter.ANY),
            age = readEnum(KEY_AGE, AllBookmarkAgeFilter.ANY),
            ratio = readEnum(KEY_RATIO, AllBookmarkRatioFilter.ANY),
            resolution = readEnum(KEY_RESOLUTION, AllBookmarkResolutionFilter.ANY),
            pages = readEnum(KEY_PAGES, AllBookmarkPageFilter.ANY),
        )
        return AllBookmarkTagQuery(
            sort = readEnum(KEY_SORT, AllBookmarkSort.NEWEST_PUBLISHED),
            filter = filter,
        )
    }

    /** 每次条件变更时落盘（纯内存 map 写，随 Activity 的 saved state 一起写进 Bundle）。 */
    private fun persistQuery(query: AllBookmarkTagQuery) {
        val filter = query.filter
        savedStateHandle[KEY_KEYWORD] = filter.keyword
        savedStateHandle[KEY_WORK_TYPE] = query.type.name
        savedStateHandle[KEY_SORT] = query.sort.name
        savedStateHandle[KEY_CREATED_FROM] = filter.createdFromMs ?: UNSET_LONG
        savedStateHandle[KEY_CREATED_TO] = filter.createdToMs ?: UNSET_LONG
        savedStateHandle[KEY_MIN_BOOKMARKS] = filter.minBookmarks ?: UNSET_INT
        savedStateHandle[KEY_MAX_BOOKMARKS] = filter.maxBookmarks ?: UNSET_INT
        savedStateHandle[KEY_USERS_TAG_MIN] = filter.usersTagMin
        savedStateHandle[KEY_AI] = filter.ai.name
        savedStateHandle[KEY_AGE] = filter.age.name
        savedStateHandle[KEY_RATIO] = filter.ratio.name
        savedStateHandle[KEY_RESOLUTION] = filter.resolution.name
        savedStateHandle[KEY_PAGES] = filter.pages.name
    }

    /**
     * 枚举按 name 恢复；名字对不上（版本降级 / 字段改名）回退默认档。
     *
     * 读取一律先 `as? String` / `as? Long` 再判断：saved state 里如果因为降级/手改出现
     * 类型不符的值，`get<T>` 的泛型擦除会把这个错误推迟到某个想不到的地方变成
     * ClassCastException；这里显式安全转换，最差只是回到默认档。
     */
    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T {
        val name = readString(key) ?: return default
        return enumValues<T>().firstOrNull { it.name == name } ?: default
    }

    private fun readString(key: String): String? =
        savedStateHandle.get<Any?>(key) as? String

    private fun readInt(key: String): Int? =
        savedStateHandle.get<Any?>(key) as? Int

    private fun readUnsetLong(key: String): Long? =
        (savedStateHandle.get<Any?>(key) as? Long)?.takeIf { it != UNSET_LONG }

    private fun readUnsetInt(key: String): Int? =
        (savedStateHandle.get<Any?>(key) as? Int)?.takeIf { it != UNSET_INT }

    /** 重算「共 N 件」。单飞：条件连着改时只留最后一次查询。 */
    fun refreshCount() {
        countJob?.cancel()
        if (uid <= 0L || tagName.isEmpty()) {
            // 未登录 / 入口没给标签名：没有可查的范围，计数就是 0，不必白跑一次 SQL。
            _resultCount.value = 0
            return
        }
        val filter = resolvedFilter()
        countJob = viewModelScope.launch {
            try {
                _resultCount.value = AllBookmarkRepository.countWorks(
                    uid = uid,
                    includePrivate = includePrivate,
                    filter = filter,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // 计数只服务标题下方的一个数字，失败显示「—」就够，不该把页面搞崩。
                Timber.tag(TAG).w(t, "标签作品计数失败")
                _resultCount.value = null
            }
        }
    }

    /**
     * SavedStateHandle 的 key 与哨兵。companion 设成 internal（而不是 private）是为了让
     * `AllBookmarkTagDetailQueryRestoreTest` 能直接按 key 注入损坏/缺省值验证回退逻辑，
     * 不必在测试里抄一份字符串字面量（抄了迟早跟这里漂移）。
     */
    internal companion object {
        const val TAG = "AllBookmarkTagDetail"

        /** 搜索框关键词。 */
        internal const val KEY_KEYWORD = "all_bookmark_tag_detail_query_keyword"

        /** 作品类别 chip（[AllBookmarkWorkType] 的 name）。 */
        internal const val KEY_WORK_TYPE = "all_bookmark_tag_detail_query_work_type"

        /** 排序（[AllBookmarkSort] 的 name）。 */
        internal const val KEY_SORT = "all_bookmark_tag_detail_query_sort"

        // 筛选面板 8 项条件（投稿期间 / 喜欢！数 / users入り / AI / R-18 / 长宽比 / 分辨率 / 页数）。
        internal const val KEY_CREATED_FROM = "all_bookmark_tag_detail_query_created_from"
        internal const val KEY_CREATED_TO = "all_bookmark_tag_detail_query_created_to"
        internal const val KEY_MIN_BOOKMARKS = "all_bookmark_tag_detail_query_min_bookmarks"
        internal const val KEY_MAX_BOOKMARKS = "all_bookmark_tag_detail_query_max_bookmarks"
        internal const val KEY_USERS_TAG_MIN = "all_bookmark_tag_detail_query_users_tag_min"
        internal const val KEY_AI = "all_bookmark_tag_detail_query_ai"
        internal const val KEY_AGE = "all_bookmark_tag_detail_query_age"
        internal const val KEY_RATIO = "all_bookmark_tag_detail_query_ratio"
        internal const val KEY_RESOLUTION = "all_bookmark_tag_detail_query_resolution"
        internal const val KEY_PAGES = "all_bookmark_tag_detail_query_pages"

        /** 「没有限制」的哨兵：Bundle 对可空 Long/Int 支持不统一，用不可能的取值表达 null。 */
        internal const val UNSET_LONG = Long.MIN_VALUE
        internal const val UNSET_INT = Int.MIN_VALUE
    }
}
