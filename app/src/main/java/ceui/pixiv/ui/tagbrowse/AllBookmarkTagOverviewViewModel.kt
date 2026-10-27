package ceui.pixiv.ui.tagbrowse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ceui.lisa.activities.Shaft
import ceui.pixiv.db.mirror.AllBookmarkRepository
import ceui.pixiv.db.mirror.AllBookmarkStats
import ceui.pixiv.db.mirror.AllBookmarkTag
import ceui.pixiv.db.mirror.BookmarkMirrorStateEntity
import ceui.pixiv.db.mirror.BookmarkShelf
import ceui.pixiv.db.mirror.MirrorRestrict
import ceui.pixiv.services.appServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

/**
 * 「全收藏分类查看」总览页的一次性快照。页面只读它，不自己查库。
 *
 * 刻意把「同步中 / 已同步 / 搜索 / 可用性」都塞进同一个 StateFlow：
 * 一次发射就能把整页画对，不会出现「列表已经换成私密数据、chip 还停在公开态」这类撕裂。
 */
data class AllBookmarkTagOverviewUiState(
    /** 本地标签 / 统计的首轮读取是否已结束（决定搜索框是否可用、显示加载还是空态）。 */
    val loaded: Boolean = false,
    /** 未登录：整页换成「去登录」。 */
    val unloggedIn: Boolean = false,
    /** 设置里关掉了收藏镜像：整页换成「去设置」，绝不白屏。 */
    val mirrorDisabled: Boolean = false,
    /** 「包含私密收藏」开关（持久化在 [TagBrowseContracts]）。 */
    val includePrivate: Boolean = true,
    /** 私密书架是否已经完成过首次全量（false 时开关下方提示「首次开启会在后台同步」）。 */
    val privateEverSynced: Boolean = false,
    val stats: AllBookmarkStats = AllBookmarkStats(),
    /** 搜索过滤后、按作品数降序的标签。 */
    val tags: List<AllBookmarkTag> = emptyList(),
    /** 搜索过滤前的标签总数（统计头「标签 M 个」与海报都用全量）。 */
    val tagTotal: Int = 0,
    /** 当前搜索词（trim 过）。 */
    val query: String = "",
    /**
     * 相关书架里还有没完成首次全量的（含刚注册、状态行还没落库的瞬间）。
     *
     * 初值刻意是 true：首帧还没观察到镜像状态，此刻说「已全部同步」是假话，
     * 统计头应显示「准备同步…」直到第一份状态落地。
     */
    val syncing: Boolean = true,
    /** 相关书架全部完成过首次全量。 */
    val allSynced: Boolean = false,
    /**
     * 还没完成首次全量的书架（按书架注册顺序）。
     *
     * 页面用它说清「正在同步哪几本书」——pixiv 的收藏列表只能一页页顺着翻（最新→最旧），
     * 四本书各自有独立断点，用户需要知道进度不是一个笼统的圈。
     */
    val syncingShelves: List<BookmarkShelf> = emptyList(),
    /** 已同步作品数：优先用统计，统计还没算出来时退回到镜像观察到的行数。 */
    val syncedCount: Int = 0,
    /** 撞上 pixiv 429 冷却中。 */
    val cooldown: Boolean = false,
    /** 用户主动同步中（驱动下拉刷新圈）。 */
    val refreshing: Boolean = false,
    /** 本地读取失败（给列表区一个「重试」）。 */
    val loadFailed: Boolean = false,
) {
    /** 有没有任何标签（不受搜索影响）。 */
    val hasAnyTag: Boolean get() = tagTotal > 0
}

/**
 * 「全收藏分类查看」总览页的全部接线（VM 归属 tag-overview-ui；数据来自
 * [AllBookmarkRepository]，镜像状态来自 [ceui.pixiv.db.mirror.BookmarkMirrorService]）。
 *
 * 职责：
 * 1. 打开即 [AllBookmarkRepository.ensureShelves] —— 这是「自动获取全部收藏」的触发点；
 * 2. 订阅镜像状态 / 行数 Flow，**debounce 约 500ms** 后重查统计与标签（镜像回填会持续写入，
 *    每页都刷一次会让列表一直抖，500ms 是「看得出在动、又不闪」的折中）；
 * 3. 搜索是纯本地过滤：首屏一次拿全量标签存在内存里，200ms debounce 由 Fragment 侧做，
 *    这里只负责按 name / 译名做大小写不敏感 contains；
 * 4. 「包含私密收藏」切换 = 写偏好 + 注册私密书架 + 立刻重查；切换后同步状态也要重算；
 * 5. 已完成态下 `syncNow` 让用户主动去服务端对一次表头（网页端新收藏的唯一出路）；
 * 6. 海报只吃 [posterSnapshot] 给的**原子快照**（tags + stats 同批查询、绑定 uid/includePrivate）：
 *    切范围/换账号立刻撤销资格，新数据提交后才恢复 —— 旧私密范围的数据不会被导成海报。
 *
 * 所有换书架 / 换开关都走 [scheduleReload]：它先取消上一次在途读取，避免旧条件的结果
 * 覆盖新条件（真机最典型的症状是切到「仅公开」后列表闪现一下私密数据）。
 */
class AllBookmarkTagOverviewViewModel : ViewModel() {

    private val _state = MutableStateFlow(AllBookmarkTagOverviewUiState())
    val state: StateFlow<AllBookmarkTagOverviewUiState> = _state.asStateFlow()

    private var uid: Long = 0L
    private var started = false

    /** 搜索过滤前的全量标签（count 降序）。搜索 / 切私密开关 / 同步刷新都从它重算。 */
    private var fullTags: List<AllBookmarkTag> = emptyList()

    /**
     * 海报快照门闸：把「海报能拿到的 tags + stats」与「查询时的 uid / includePrivate」绑定。
     *
     * 不能用 [fullTags] 直接喂海报：切「包含私密收藏」后它是旧范围的残留数据，
     * 且新查询可能延迟或失败。所有范围变化都先进这个门闸，见 [AllBookmarkPosterSnapshotGate]。
     */
    private val posterGate = AllBookmarkPosterSnapshotGate()

    /** 最近一次观察到的镜像状态行（该账号的全部书架），用于同步进度与「私密是否同步过」。 */
    private var mirrorStates: List<BookmarkMirrorStateEntity> = emptyList()

    /** [observeOwnerCount] 的最新值：统计尚未算出来时的「已同步 N 件」兜底。 */
    private var ownerCount: Int = 0

    private var observerJob: Job? = null
    private var reloadJob: Job? = null

    // ─────────────────────────── 对外入口 ───────────────────────────

    /** 首次进入页面调用（幂等）。 */
    fun start() {
        if (started) return
        started = true
        _state.value = _state.value.copy(includePrivate = TagBrowseContracts.includePrivate())
        ensureUsable()
    }

    /**
     * 页面回到前台。必须再对一次：用户很可能刚去设置里打开了「收藏镜像」开关，
     * 或者换了个账号回来 —— 只挂在 onViewCreated 上的话，回来看到的还是不可用态。
     */
    fun onResumed() {
        if (!started) {
            start()
            return
        }
        ensureUsable()
    }

    /** 搜索词变化（Fragment 已做 200ms debounce，清空时立即调用）。 */
    fun setQuery(raw: String) {
        val query = raw.trim()
        if (_state.value.query == query) return
        publish { it.copy(query = query, tags = filteredTags(query)) }
    }

    /** 「包含私密收藏」开关。隐私边界同收藏库：只有用户主动打开，才会去拉悄悄收藏。 */
    fun setIncludePrivate(value: Boolean) {
        val current = _state.value
        if (current.includePrivate == value || current.unloggedIn || current.mirrorDisabled) return
        TagBrowseContracts.setIncludePrivate(value)
        // **先撤销海报资格，再改状态**：从这一行起，无论新查询是延迟还是失败，
        // 都不可能再拿旧范围的 tags/stats 导出海报；等 [reload] 成功提交新快照才恢复。
        posterGate.updateScope(uid, value)
        publish { it.copy(includePrivate = value, tags = filteredTags(current.query)) }
        if (uid > 0L) {
            runCatching {
                AllBookmarkRepository.ensureShelves(uid, value, REASON_PRIVATE_SWITCH)
            }.onFailure { Timber.tag(TAG).w(it, "注册私密书架失败") }
        }
        publishSyncState()
        scheduleReload(delayMs = 0L)
    }

    /** 用户主动同步：去服务端对一次表头，不受增量节流影响（下拉刷新 / 刷新按钮）。 */
    fun syncNow() {
        val current = _state.value
        if (uid <= 0L || current.unloggedIn || current.mirrorDisabled) return
        runCatching {
            AllBookmarkRepository.syncNow(uid, current.includePrivate, REASON_MANUAL_SYNC)
        }.onFailure { Timber.tag(TAG).w(it, "手动同步失败") }
        publish { it.copy(refreshing = true) }
        // 同步本身受全局限速，几秒后才有可能写库；本地重查不必等它，先把圈收掉。
        scheduleReload(delayMs = REFRESH_SPINNER_MS)
    }

    /** 列表区「重试」：重新读一次本地统计与标签。 */
    fun retryLoad() {
        publish { it.copy(loadFailed = false, loaded = false) }
        scheduleReload(delayMs = 0L)
    }

    /**
     * 「我的Tag排行」海报可用的一份**原子快照**（tags 与 stats 同批查询，并绑定 uid/includePrivate）。
     *
     * 返回 null = 现在不允许导出海报，调用方必须按「拒绝」处理、**不能用列表里的旧数据兜底**：
     * - 首次读取还没完成；
     * - 刚切过「包含私密收藏」，新范围的 tags+stats 还在路上（debounce / 私密书架正在注册）；
     * - 上一次读取失败，新范围的完整数据还没拿到。
     *
     * 这正是修复「切到仅公开后仍能导出旧私密标签排行」的关键：旧快照在切换瞬间就被门闸撤销，
     * 只有当前范围的完整一批数据提交后才重新可用。
     */
    fun posterSnapshot(): AllBookmarkPosterSnapshot? {
        val snapshot = posterGate.snapshot() ?: return null
        // 双保险：门闸之外再对一次界面状态里的账号与收藏范围。任何不一致都按「不可用」处理，
        // 宁可让用户稍后重试，也不导出一张范围对不上的海报。
        return snapshot.takeIf {
            it.uid == uid && it.includePrivate == _state.value.includePrivate
        }
    }

    // ─────────────────────────── 可用性 ───────────────────────────

    private fun ensureUsable() {
        val currentUid = runCatching { AllBookmarkRepository.currentUid() }
            .getOrElse { 0L }
        val enabled = mirrorEnabled()
        if (currentUid <= 0L) {
            uid = 0L
            stopObserving()
            // 退出登录：海报快照必须立刻失效，不能留着上一个账号的数据。
            posterGate.updateScope(0L, _state.value.includePrivate)
            posterGate.invalidate()
            publish {
                it.copy(
                    unloggedIn = true,
                    mirrorDisabled = false,
                    loaded = false,
                    tags = emptyList(),
                    tagTotal = 0,
                    stats = AllBookmarkStats(),
                    syncing = false,
                    allSynced = false,
                    syncingShelves = emptyList(),
                    syncedCount = 0,
                )
            }
            return
        }
        if (!enabled) {
            uid = currentUid
            stopObserving()
            // 镜像关掉后页面整页变成不可用态，海报当然也不能再导出任何旧快照。
            posterGate.updateScope(currentUid, _state.value.includePrivate)
            posterGate.invalidate()
            publish {
                it.copy(
                    unloggedIn = false,
                    mirrorDisabled = true,
                    loaded = false,
                    tags = emptyList(),
                    tagTotal = 0,
                    stats = AllBookmarkStats(),
                    syncing = false,
                    allSynced = false,
                    syncingShelves = emptyList(),
                    syncedCount = 0,
                )
            }
            return
        }

        val needRebuild = uid != currentUid || _state.value.unloggedIn || _state.value.mirrorDisabled
        uid = currentUid
        publish { it.copy(unloggedIn = false, mirrorDisabled = false) }
        val includePrivate = _state.value.includePrivate
        // 换账号 / 从不可用态恢复：把门闸对齐到当前范围（真的变了就撤销旧快照）。
        // 只是普通回到前台（uid 与范围都没变）时这里什么都不做，海报资格不受影响。
        posterGate.updateScope(uid, includePrivate)
        runCatching {
            AllBookmarkRepository.ensureShelves(uid, includePrivate, REASON_OPEN)
        }.onFailure { Timber.tag(TAG).w(it, "注册收藏书架失败") }
        if (needRebuild || observerJob?.isActive != true) {
            restartObservers(uid)
            publishSyncState()
            scheduleReload(delayMs = 0L)
        } else {
            // 回到前台：让引擎补一次增量（网页端新收藏能尽快出现），本地也重查一次。
            scheduleReload(delayMs = 0L)
        }
    }

    private fun mirrorEnabled(): Boolean = try {
        Shaft.sSettings.isBookmarkMirrorEnabled
    } catch (t: Throwable) {
        false
    }

    // ─────────────────────────── 观察镜像 ───────────────────────────

    private fun restartObservers(ownerUid: Long) {
        stopObserving()
        val mirror = try {
            Shaft.getContext().appServices().bookmarkMirror
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "取不到收藏镜像服务")
            return
        }
        observerJob = viewModelScope.launch {
            // 状态表：同步进度即时上屏；数据重查走 500ms debounce（回填每页都会写状态）。
            launch {
                mirror.observeState(ownerUid).collectLatest { states ->
                    mirrorStates = states
                    publishSyncState()
                    scheduleReload()
                }
            }
            // 行数：标签 / 统计跟着镜像行数变；同样是持续写入，统一 debounce 后重查。
            launch {
                mirror.observeOwnerCount(ownerUid).collectLatest { count ->
                    ownerCount = count
                    publishSyncState()
                    scheduleReload()
                }
            }
        }
    }

    private fun stopObserving() {
        observerJob?.cancel()
        observerJob = null
        mirrorStates = emptyList()
        ownerCount = 0
    }

    /**
     * 从镜像状态行算出同步展示态。
     *
     * 只看当前开关选中的那些书架：只公开时，悄悄收藏没同步完也不该说「正在同步」；
     * 打开包含私密后，私密书架一注册（即使状态行还没落库，相关状态为空）就立刻算「进行中」。
     */
    private fun publishSyncState() {
        if (uid <= 0L) return
        val includePrivate = _state.value.includePrivate
        val relevantShelves = runCatching {
            AllBookmarkRepository.shelvesFor(uid, includePrivate)
        }.getOrElse { emptyList() }
        val doneShelfKeys = mirrorStates
            .filter { it.isFirstSyncDone }
            .map { it.shelfKey }
            .toSet()
        // 刚注册、状态行还没落库的书架也算「同步中」：注册到第一页落地之间必然有这一段，
        // 此时若判成「已完成」会让横幅闪一下又回来。
        val syncingShelves = relevantShelves.filter { it.key !in doneShelfKeys }
        // 没有「待同步书架」就算完成。真实账号（uid > 0）的书架集合恒非空，所以这里只有
        // 两种可能：全部完成，或者演示数据模式（AllBookmarkDemoData 的 shelvesFor 返回空）。
        // 后者不该让页面永远停在「正在获取全部收藏…」——那是假数据环境的锅，不是用户网速慢。
        val allSynced = syncingShelves.isEmpty()
        val syncing = !allSynced
        val syncingKeys = syncingShelves.map { it.key }.toSet()
        val cooldown = mirrorStates
            .filter { it.shelfKey in syncingKeys }
            .any { it.cooldownUntil > System.currentTimeMillis() }
        publish {
            it.copy(
                syncing = syncing,
                allSynced = allSynced,
                syncingShelves = syncingShelves,
                cooldown = cooldown && syncing,
                privateEverSynced = privateShelvesAreSynced(),
                syncedCount = if (it.stats.totalWorks > 0) it.stats.totalWorks else ownerCount,
            )
        }
    }

    private fun privateShelvesAreSynced(): Boolean {
        if (uid <= 0L) return true
        val privateShelves = runCatching {
            AllBookmarkRepository.shelvesFor(uid, includePrivate = true)
                .filter { it.restrict == MirrorRestrict.PRIVATE }
        }.getOrElse { return true }
        if (privateShelves.isEmpty()) return true
        return privateShelves.all { shelf ->
            mirrorStates.any { it.shelfKey == shelf.key && it.isFirstSyncDone }
        }
    }

    // ─────────────────────────── 读取本地 ───────────────────────────

    /** debounce 后重查本地统计与标签；[delayMs] = 0 表示立刻。 */
    private fun scheduleReload(delayMs: Long = SYNC_DEBOUNCE_MS) {
        if (uid <= 0L) return
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            if (delayMs > 0L) delay(delayMs)
            reload()
        }
    }

    private suspend fun reload() {
        if (uid <= 0L) return
        // 记下这一批读取所属的账号与范围：读回来的数据只能给这个范围用。
        val loadUid = uid
        val includePrivate = _state.value.includePrivate
        try {
            val stats = AllBookmarkRepository.loadStats(loadUid, includePrivate)
            val tags = AllBookmarkRepository.loadTags(loadUid, includePrivate)
            val sortedTags = tags.sortedByDescending { it.count }
            // 读取途中切了「包含私密收藏」或换了账号：这批结果属于旧范围，直接丢弃 ——
            // 既不上屏，也不提交海报快照（否则刚关掉私密就又能导出私密标签排行）。
            if (uid != loadUid ||
                _state.value.includePrivate != includePrivate ||
                !posterGate.isCurrentScope(loadUid, includePrivate)
            ) {
                Timber.tag(TAG).d(
                    "丢弃旧范围读取结果 uid=%d includePrivate=%b（当前 uid=%d includePrivate=%b）",
                    loadUid, includePrivate, uid, _state.value.includePrivate,
                )
                return
            }
            fullTags = sortedTags
            // 先原子提交海报快照（tags + stats 是同一批数据），再上屏：不存在
            // 「列表已经是公开范围、海报还是私密范围」或「标签新、统计旧」的窗口。
            posterGate.commit(loadUid, includePrivate, sortedTags, stats)
            val query = _state.value.query
            publish {
                it.copy(
                    loaded = true,
                    loadFailed = false,
                    stats = stats,
                    tags = filteredTags(query),
                    tagTotal = fullTags.size,
                    syncedCount = if (stats.totalWorks > 0) stats.totalWorks else ownerCount,
                    refreshing = false,
                )
            }
            publishSyncState()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "读取全收藏标签失败")
            // 失败不提交任何快照：如果刚切过范围，海报资格保持「撤销」直到重试成功；
            // 若期间范围又变了，这次失败也不属于当前页面，交给新范围的读取去报错。
            if (uid == loadUid && _state.value.includePrivate == includePrivate) {
                publish { it.copy(loaded = true, refreshing = false, loadFailed = true) }
            }
        }
    }

    private fun filteredTags(query: String): List<AllBookmarkTag> {
        if (query.isEmpty()) return fullTags
        val needle = query.lowercase(Locale.getDefault())
        return fullTags.filter { tag ->
            tag.displayName.lowercase(Locale.getDefault()).contains(needle) ||
                tag.tagName.lowercase(Locale.getDefault()).contains(needle) ||
                tag.translatedName.lowercase(Locale.getDefault()).contains(needle)
        }
    }

    private fun publish(transform: (AllBookmarkTagOverviewUiState) -> AllBookmarkTagOverviewUiState) {
        _state.value = transform(_state.value)
    }

    private companion object {
        const val TAG = "AllBookmarkTagOverview"
        const val SYNC_DEBOUNCE_MS = 500L
        const val REFRESH_SPINNER_MS = 800L
        const val REASON_OPEN = "打开全收藏分类"
        const val REASON_PRIVATE_SWITCH = "切换包含私密收藏"
        const val REASON_MANUAL_SYNC = "全收藏分类手动刷新"
    }
}
