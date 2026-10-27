package ceui.pixiv.ui.tagbrowse.poster

import android.content.Context
import android.graphics.Bitmap
import ceui.lisa.R
import ceui.pixiv.db.mirror.AllBookmarkTag
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 海报绘制所需的一份纯数据。
 *
 * 与 UI 完全解耦：渲染器只读这个对象，不碰 Fragment / ViewModel / 数据库，Robolectric 单测
 * 可以直接构造它产出样张。
 *
 * @param tags 已按收藏作品数从多到少排序的标签（最多 [MAX_TAGS] 个）；渲染器只画前
 *   [tagCount] 行，标签不足时按实际个数画。
 * @param totalWorks 当前账号已镜像的收藏作品总数。
 * @param distinctTagCount 去重后的标签总数。
 * @param userName 账号昵称（pixiv name）。
 * @param userAccount 账号 ID（pixiv account，不带 @）。
 * @param userId 账号数字 UID。
 * @param avatar 已经下载好的方形头像；圆形裁剪由渲染器完成。
 * @param showAccount 是否展示账号昵称/ID 区（关闭时整块账号卡都不画）。
 * @param showAvatar 是否展示头像。账号区关闭时头像也无从附着，两个开关是主从关系。
 * @param tagCount 用户选择的排行数量（[TAG_COUNT_6] / [TAG_COUNT_20]）；超过 6 行时渲染器
 *   切换为「紧凑多行」版式。徽标数字取实际画出的行数（如只有 12 个标签时显示 TOP 12）。
 */
data class TagRankPosterData(
    val tags: List<AllBookmarkTag>,
    val totalWorks: Int,
    val distinctTagCount: Int,
    val userName: String = "",
    val userAccount: String = "",
    val userId: Long = 0L,
    val avatar: Bitmap? = null,
    val showAccount: Boolean = true,
    val showAvatar: Boolean = true,
    val tagCount: Int = DEFAULT_TAG_COUNT,
) {

    /** 是否真的会画出账号卡：开关打开且至少有一项账号信息（或头像）可展示。 */
    val showsAccountCard: Boolean
        get() = showAccount &&
            (showAvatar && avatar != null || userName.isNotBlank() || userAccount.isNotBlank() || userId > 0L)

    /** 按当前选择实际要画的标签行（不足则全画）。 */
    val visibleTags: List<AllBookmarkTag>
        get() = tags.take(tagCountForLayout())

    /** 实际要画的行数：选 20 但只有 12 个标签时是 12；选 6 只有 3 个时是 3。 */
    fun tagCountForLayout(): Int = when {
        tags.isEmpty() -> 0
        else -> tags.size.coerceAtMost(tagCount.coerceIn(TAG_COUNT_6, MAX_TAGS))
    }

    companion object {
        /** 用户可选的两种排行数量。 */
        const val TAG_COUNT_6 = 6
        const val TAG_COUNT_20 = 20

        /** 海报最多展示 20 行。 */
        const val MAX_TAGS = TAG_COUNT_20

        /** 默认仍是 TOP 6。 */
        const val DEFAULT_TAG_COUNT = TAG_COUNT_6

        /** 只接受 6 / 20 两个档位；其它值按 6 处理。 */
        fun sanitizeTagCount(value: Int): Int =
            if (value == TAG_COUNT_20) TAG_COUNT_20 else TAG_COUNT_6
    }
}

/**
 * 可塞进 Fragment arguments 的载荷。
 *
 * [AllBookmarkTag] 既不是 Parcelable 也不是 Serializable，所以这里手工编成 JSON 字符串：
 * 进程内重建、系统回收后恢复、旋屏重建都只依赖一段文本，不需要改动 mirror 包的数据模型。
 * 用 org.json 而不是 Gson，是为了不依赖 `Shaft.sGson` 的初始化时序（单测里也能直接跑）。
 */
internal data class TagRankPosterPayload(
    val tags: List<AllBookmarkTag>,
    val totalWorks: Int,
    val distinctTagCount: Int,
    val userName: String,
    val userAccount: String,
    val userId: Long,
    val avatarUrl: String,
    /** 弹窗打开时的初始排行数量（默认 TOP 6）；用户切换后由弹窗自己保存选择。 */
    val tagCount: Int = TagRankPosterData.DEFAULT_TAG_COUNT,
) {

    fun toJson(): String = JSONObject().apply {
        put(KEY_VERSION, VERSION)
        put(KEY_TOTAL_WORKS, totalWorks)
        put(KEY_DISTINCT_TAGS, distinctTagCount)
        put(KEY_USER_NAME, userName)
        put(KEY_USER_ACCOUNT, userAccount)
        put(KEY_USER_ID, userId)
        put(KEY_AVATAR_URL, avatarUrl)
        put(KEY_TAG_COUNT, tagCount)
        put(
            KEY_TAGS,
            JSONArray().apply {
                tags.forEach { tag ->
                    put(
                        JSONObject().apply {
                            put(KEY_TAG_NAME, tag.tagName)
                            put(KEY_DISPLAY_NAME, tag.displayName)
                            put(KEY_TRANSLATED_NAME, tag.translatedName)
                            put(KEY_COUNT, tag.count)
                        },
                    )
                }
            },
        )
    }.toString()

    companion object {
        private const val VERSION = 1
        private const val KEY_VERSION = "v"
        private const val KEY_TOTAL_WORKS = "totalWorks"
        private const val KEY_DISTINCT_TAGS = "distinctTagCount"
        private const val KEY_USER_NAME = "userName"
        private const val KEY_USER_ACCOUNT = "userAccount"
        private const val KEY_USER_ID = "userId"
        private const val KEY_AVATAR_URL = "avatarUrl"
        private const val KEY_TAG_COUNT = "tagCount"
        private const val KEY_TAGS = "tags"
        private const val KEY_TAG_NAME = "tagName"
        private const val KEY_DISPLAY_NAME = "displayName"
        private const val KEY_TRANSLATED_NAME = "translatedName"
        private const val KEY_COUNT = "count"

        private val EMPTY = TagRankPosterPayload(emptyList(), 0, 0, "", "", 0L, "")

        fun fromJson(raw: String?): TagRankPosterPayload {
            if (raw.isNullOrBlank()) return EMPTY
            return runCatching {
                val json = JSONObject(raw)
                if (json.optInt(KEY_VERSION, 0) != VERSION) return@runCatching EMPTY
                val array = json.optJSONArray(KEY_TAGS) ?: JSONArray()
                val tags = ArrayList<AllBookmarkTag>(array.length().coerceAtMost(TagRankPosterData.MAX_TAGS))
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    tags += AllBookmarkTag(
                        tagName = item.optString(KEY_TAG_NAME),
                        displayName = item.optString(KEY_DISPLAY_NAME),
                        translatedName = item.optString(KEY_TRANSLATED_NAME),
                        count = item.optInt(KEY_COUNT),
                    )
                }
                TagRankPosterPayload(
                    tags = tags,
                    totalWorks = json.optInt(KEY_TOTAL_WORKS),
                    distinctTagCount = json.optInt(KEY_DISTINCT_TAGS),
                    userName = json.optString(KEY_USER_NAME),
                    userAccount = json.optString(KEY_USER_ACCOUNT),
                    userId = json.optLong(KEY_USER_ID),
                    avatarUrl = json.optString(KEY_AVATAR_URL),
                    tagCount = TagRankPosterData.sanitizeTagCount(
                        json.optInt(KEY_TAG_COUNT, TagRankPosterData.DEFAULT_TAG_COUNT),
                    ),
                )
            }.getOrDefault(EMPTY)
        }
    }
}

/**
 * 海报上的固定文案与排版语种。
 *
 * 海报是独立 Bitmap，不跟随 View 主题；文案跟随 App 当前语言，配色固定为品牌深色方案，
 * 这样同一张图在深/浅色模式下长得一样，分享到站外也不会因为别人的手机主题变样。
 */
data class TagRankPosterCopy(
    val eyebrow: String,
    val titleHead: String,
    val titleAccent: String,
    val titleTail: String,
    val subtitle: String,
    val badgeTop: String,
    val badgeTail: String,
    val badgeNumber: String,
    val countUnit: String,
    val statsTotalTemplate: String,
    val statsTagsTemplate: String,
    val sourcePrefix: String,
    val sourceSuffix: String,
    val uidTemplate: String,
    val locale: Locale,
) {

    companion object {
        fun from(context: Context, tagCount: Int): TagRankPosterCopy {
            val count = tagCount.coerceIn(1, TagRankPosterData.MAX_TAGS)
            return TagRankPosterCopy(
                eyebrow = context.getString(R.string.tag_poster_eyebrow),
                titleHead = context.getString(R.string.tag_poster_title_head),
                titleAccent = context.getString(R.string.tag_poster_title_accent),
                titleTail = context.getString(R.string.tag_poster_title_tail),
                subtitle = context.getString(R.string.tag_poster_subtitle),
                badgeTop = context.getString(R.string.tag_poster_badge_top),
                badgeTail = context.getString(R.string.tag_poster_badge_tail),
                badgeNumber = count.toString(),
                countUnit = context.getString(R.string.tag_poster_rank_count_unit),
                statsTotalTemplate = context.getString(R.string.tag_poster_stats_total),
                statsTagsTemplate = context.getString(R.string.tag_poster_stats_tags),
                sourcePrefix = context.getString(R.string.tag_poster_source_prefix),
                sourceSuffix = context.getString(R.string.tag_poster_source_suffix),
                uidTemplate = context.getString(R.string.tag_poster_uid),
                locale = currentLocale(context),
            )
        }

        /** `Configuration.locales` 在 minSdk 24 上可读；越界时退回默认语言。 */
        private fun currentLocale(context: Context): Locale {
            val locales = context.resources.configuration.locales
            return if (locales.isEmpty) Locale.getDefault() else locales[0]
        }
    }
}
