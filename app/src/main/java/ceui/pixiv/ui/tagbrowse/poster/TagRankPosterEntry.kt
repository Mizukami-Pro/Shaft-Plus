package ceui.pixiv.ui.tagbrowse.poster

import androidx.fragment.app.FragmentManager
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.pixiv.db.mirror.AllBookmarkStats
import ceui.pixiv.db.mirror.AllBookmarkTag
import ceui.pixiv.session.SessionManager
import timber.log.Timber

/**
 * 「我的Tag排行」海报的入口（签名由 Lead 固定，tag-overview 页面直接调用）。
 *
 * 职责只有三件事：取前 6 个标签、拼装一份可序列化的 [TagRankPosterPayload]（含当前账号的
 * 昵称/ID/头像 URL）、弹出 [TagRankPosterDialogFragment] 预览。查询与统计全部来自
 * [ceui.pixiv.db.mirror.AllBookmarkRepository]，这里不碰数据库。
 *
 * 未登录时 `SessionManager.loggedInUser` 为 null，payload 里账号字段全空，弹窗会把
 * 「显示账号昵称/ID」「显示头像」两个开关置灰、海报也自动不画账号区，不会出现空白的假头像。
 *
 * @param tags 已按「收藏作品数」从多到少排序的全量标签，内部只取前 [TagRankPosterData.MAX_TAGS] 个。
 * @param stats 全收藏统计（作品总数 / 标签总数），用于海报底部文案。
 */
object TagRankPosterEntry {

    private const val TAG = "TagRankPosterEntry"

    @JvmStatic
    fun show(
        fragmentManager: FragmentManager,
        tags: List<AllBookmarkTag>,
        stats: AllBookmarkStats,
    ) {
        // 防御性重排：调用方已按 count 降序，但排序契约万一变化也不该让海报顺序错乱。
        // 入口一次最多带 20 个标签（TOP 20 选项要用）；具体画 6 行还是 20 行由弹窗里的选择决定。
        val topTags = tags.asSequence()
            .filter { it.count > 0 }
            .sortedByDescending { it.count }
            .take(TagRankPosterData.MAX_TAGS)
            .toList()
        if (topTags.isEmpty()) {
            Common.showToast(R.string.tag_poster_empty)
            return
        }
        if (fragmentManager.isStateSaved) {
            Timber.tag(TAG).w("skip poster dialog: fragment manager state already saved")
            return
        }
        if (fragmentManager.findFragmentByTag(TagRankPosterDialogFragment.TAG) != null) return

        val user = SessionManager.loggedInUser
        val images = user?.profile_image_urls
        // medium 是 pixiv 头像的标准中图；缺失时退回官方推荐的「最大一张」。
        val avatarUrl = images?.medium?.takeIf { it.isNotBlank() }
            ?: images?.findMaxSizeUrl().orEmpty()
        val payload = TagRankPosterPayload(
            tags = topTags,
            totalWorks = stats.totalWorks,
            distinctTagCount = stats.distinctTagCount,
            userName = user?.name.orEmpty(),
            userAccount = user?.account.orEmpty().removePrefix("@"),
            userId = user?.id ?: SessionManager.loggedInUid,
            avatarUrl = avatarUrl,
            // 默认 TOP 6；弹窗切到 TOP 20 后会把选择存进自己的实例状态。
            tagCount = TagRankPosterData.DEFAULT_TAG_COUNT,
        )
        try {
            TagRankPosterDialogFragment.newInstance(payload)
                .show(fragmentManager, TagRankPosterDialogFragment.TAG)
        } catch (error: IllegalStateException) {
            // 极少数情况下调用点正好落在 onSaveInstanceState 之后，弹窗不该把宿主页面带崩。
            Timber.tag(TAG).w(error, "show poster dialog failed")
        }
    }
}
