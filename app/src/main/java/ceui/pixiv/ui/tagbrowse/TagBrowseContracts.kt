package ceui.pixiv.ui.tagbrowse

import android.content.Context
import android.content.Intent
import ceui.lisa.activities.Shaft
import ceui.lisa.activities.TemplateActivity
import ceui.pixiv.ui.navigation.TemplateRoute

/**
 * 「全收藏分类查看」的跨页面契约：入口跳转、Intent extra key、以及「包含私密收藏」偏好的读写。
 *
 * 归属：Lead 维护。页面（总览页 / 标签详情页 / 海报）不要自己拼 Intent 或直接读写 MMKV，
 * 一律走本对象，避免三处各自定义 key 之后对不上。
 */
object TagBrowseContracts {

    /** 标签详情页：标签展示名（原始大小写）。 */
    const val EXTRA_TAG_NAME = "all_bookmark_tag_name"

    /** 标签详情页：标签译名（可空）。 */
    const val EXTRA_TAG_TRANSLATED_NAME = "all_bookmark_tag_translated_name"

    /** 标签详情页：该标签在全部收藏里的作品数（用于标题副文案，可空）。 */
    const val EXTRA_TAG_COUNT = "all_bookmark_tag_count"

    /** 标签详情页：是否包含悄悄收藏（与总览页开关一致）。 */
    const val EXTRA_INCLUDE_PRIVATE = "all_bookmark_include_private"

    private const val MMKV_INCLUDE_PRIVATE = "all_bookmark_include_private"

    /** 「包含私密收藏」偏好。默认 true：用户要求的是「全部收藏」。 */
    @JvmStatic
    fun includePrivate(): Boolean = try {
        Shaft.getMMKV().decodeBool(MMKV_INCLUDE_PRIVATE, true)
    } catch (t: Throwable) {
        true
    }

    @JvmStatic
    fun setIncludePrivate(value: Boolean) {
        try {
            Shaft.getMMKV().encode(MMKV_INCLUDE_PRIVATE, value)
        } catch (t: Throwable) {
            // 偏好读失败不应影响功能，下一次写会再试。
        }
    }

    /** 打开「全收藏分类查看」总览页。 */
    @JvmStatic
    fun openOverview(context: Context) {
        context.startActivity(
            Intent(context, TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.ALL_BOOKMARK_TAGS.key)
                putExtra("hideStatusBar", false)
            }
        )
    }

    /** 打开某个标签的作品列表页。 */
    @JvmStatic
    fun openTagDetail(
        context: Context,
        tagName: String,
        translatedName: String?,
        count: Int,
        includePrivate: Boolean,
    ) {
        context.startActivity(
            Intent(context, TemplateActivity::class.java).apply {
                putExtra(TemplateActivity.EXTRA_FRAGMENT, TemplateRoute.ALL_BOOKMARK_TAG_DETAIL.key)
                putExtra(EXTRA_TAG_NAME, tagName)
                putExtra(EXTRA_TAG_TRANSLATED_NAME, translatedName)
                putExtra(EXTRA_TAG_COUNT, count)
                putExtra(EXTRA_INCLUDE_PRIVATE, includePrivate)
                putExtra("hideStatusBar", false)
            }
        )
    }
}
