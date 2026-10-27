package ceui.pixiv.ui.tagbrowse

import ceui.pixiv.db.mirror.AllBookmarkStats
import ceui.pixiv.db.mirror.AllBookmarkTag

/**
 * 「我的Tag排行」海报可用的一份数据快照：**把 tags + stats 和它们所属的收藏范围绑定在一起**。
 *
 * 为什么不能直接把 `fullTags` / `state.stats` 递给海报：
 * - 这两个字段是「上一次成功读取」留下的，切换「包含私密收藏」后要等新查询回来才会变；
 * - 在那段窗口里（debounce 500ms、首次开启私密要建书架、查询失败后无限挂起）点开海报，
 *   会把**用户刚刚离开的隐私范围**的数据导成图片 —— 关掉私密却导出悄悄收藏的标签排行，
 *   这是隐私事故，不只是数据不新鲜；
 * - tags 与 stats 还是两次独立读取，正常刷新时也可能一个新一个旧，海报底部文案会对不上。
 *
 * 所以对外只暴露「同一批查询结果 + 查询时的 uid/includePrivate」的不可变快照。
 */
data class AllBookmarkPosterSnapshot(
    val uid: Long,
    val includePrivate: Boolean,
    /** 过滤前、按作品数降序的全量标签。 */
    val tags: List<AllBookmarkTag>,
    /** 与 [tags] 同批查出来的统计。 */
    val stats: AllBookmarkStats,
)

/**
 * 海报快照门闸：保证「可导出的海报数据」永远是**当前账号 × 当前收藏范围**的一份完整快照。
 *
 * 两条铁律（也是测试用例圈定的行为）：
 * 1. **切范围先失效**：[updateScope] 一旦发现 uid 或 includePrivate 变化，立刻丢掉旧快照，
 *    不等新查询回来；新数据没提交前 [snapshot] 一律 null。
 * 2. **只收当前范围的提交**：[commit] 只接受与当前范围一致的 `(uid, includePrivate)`。
 *    读取途中切了范围/换号，迟到的旧结果提交会被拒绝（返回 false），不会把旧私密数据放回来。
 *
 * 失败/延迟语义因此是安全的：查询失败时不会提交任何东西，快照保持 null；重试成功后才恢复。
 * 单纯 [invalidate]（同一范围内的主动刷新/失败保护）不改变范围，成功重读后仍可继续导出。
 *
 * 无 Android 依赖，便于纯 JVM 单测（见 `AllBookmarkPosterSnapshotGateTest`）。
 */
internal class AllBookmarkPosterSnapshotGate {

    /** 当前收藏范围：谁的收藏 × 是否包含悄悄收藏。 */
    data class Scope(val uid: Long, val includePrivate: Boolean)

    private var scope = Scope(uid = 0L, includePrivate = true)
    private var snapshot: AllBookmarkPosterSnapshot? = null

    /** 当前范围（只读，供日志/断言用）。 */
    val currentScope: Scope get() = scope

    /**
     * 对齐当前收藏范围。范围真的变化时才撤销快照并返回 true；
     * 重复对齐同一范围返回 false，不动快照（避免「回到前台」这类无意义重对齐把海报资格清掉）。
     */
    fun updateScope(uid: Long, includePrivate: Boolean): Boolean {
        if (scope.uid == uid && scope.includePrivate == includePrivate) return false
        scope = Scope(uid, includePrivate)
        snapshot = null
        return true
    }

    /** 无条件撤销海报资格（同一范围内的主动刷新、读取失败等场景）。 */
    fun invalidate() {
        snapshot = null
    }

    /** 这批读取结果是否仍属于当前范围；读取途中切了范围/换号时应丢弃结果。 */
    fun isCurrentScope(uid: Long, includePrivate: Boolean): Boolean =
        scope.uid == uid && scope.includePrivate == includePrivate

    /**
     * 原子提交一次成功读取的 tags + stats。只接受当前范围；
     * 旧范围的迟到结果返回 false 且**不会触碰现有快照**，所以更早提交的当前范围快照不受影响。
     */
    fun commit(
        uid: Long,
        includePrivate: Boolean,
        tags: List<AllBookmarkTag>,
        stats: AllBookmarkStats,
    ): Boolean {
        if (!isCurrentScope(uid, includePrivate)) return false
        snapshot = AllBookmarkPosterSnapshot(uid, includePrivate, tags, stats)
        return true
    }

    /** 当前可交给海报的快照；没有、或范围对不上一律 null。 */
    fun snapshot(): AllBookmarkPosterSnapshot? {
        val current = snapshot ?: return null
        return current.takeIf { isCurrentScope(it.uid, it.includePrivate) }
    }
}
