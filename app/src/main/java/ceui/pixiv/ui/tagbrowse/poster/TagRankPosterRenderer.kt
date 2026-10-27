package ceui.pixiv.ui.tagbrowse.poster

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.PathParser
import ceui.pixiv.db.mirror.AllBookmarkTag
import ceui.pixiv.witstudio.theme.v3Font
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 海报字体阶梯：拉丁字母与数字走 Montserrat 真实字重，中日韩文走系统字体回退。
 *
 * 与 `ArtworkPosterTypography`（作品海报）同一套取字规则，但这里把「拉丁」和「CJK」分开：
 * 标签名可能是 `original` 也可能是 `オリジナル`，同一行里按字符串实际内容选字体，避免
 * Montserrat 缺字时回退字形与字号基线不稳。
 */
internal data class TagRankPosterTypography(
    val latinExtraBold: Typeface,
    val latinBold: Typeface,
    val latinSemiBold: Typeface,
    val latinMedium: Typeface,
    val cjkBold: Typeface,
    val cjkMedium: Typeface,
) {
    companion object {
        fun from(context: Context): TagRankPosterTypography = TagRankPosterTypography(
            latinExtraBold = context.v3Font(800),
            latinBold = context.v3Font(700),
            latinSemiBold = context.v3Font(600),
            latinMedium = context.v3Font(500),
            cjkBold = Typeface.create("sans-serif", Typeface.BOLD),
            cjkMedium = Typeface.create("sans-serif", Typeface.NORMAL),
        )

        fun system(): TagRankPosterTypography = TagRankPosterTypography(
            latinExtraBold = Typeface.create("sans-serif-black", Typeface.BOLD),
            latinBold = Typeface.create("sans-serif", Typeface.BOLD),
            latinSemiBold = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            latinMedium = Typeface.create("sans-serif", Typeface.NORMAL),
            cjkBold = Typeface.create("sans-serif", Typeface.BOLD),
            cjkMedium = Typeface.create("sans-serif", Typeface.NORMAL),
        )
    }
}

/**
 * 把 [TagRankPosterData] 画成一张 1080px 宽的品牌海报。
 *
 * 排版全部是输出像素，不读 displayMetrics：同一份数据在任何密度、任何系统字体缩放级别下
 * 都产出同样的图。两种版式：
 *  - **TOP 6**（默认）：行高 112/118，成品 1080×1512 / 1610，视觉与已验收版本完全一致；
 *  - **TOP 20**：行高收紧到 88、间距 8，标签名 28、译名 18、徽章 40、作品数 24/16、
 *    进度条 4px，高度按实际行数动态计算（20 行时约 2646-2790），保证不溢出、不挤成一坨。
 *
 * 视觉分层（从外到内）：
 *  1. 深色渐变底 + 品牌光斑 / 圆环 / 星芒装饰 —— 社交传播的「封面感」；
 *  2. 顶部 pixiv wordmark 与 pixiv.net 胶囊 —— 明确这是 pixiv 网站上的收藏；
 *  3. 「我的收藏 TAG 排行」大标题 + TOP N 渐变数字（N = 实际画出的行数）；
 *  4. N 行玻璃排行卡：前三名金银铜徽章，标签名 + 译名 + 作品数 + 相对长度进度条；
 *  5. 可选账号卡（头像 / 昵称 / @ID / UID）；
 *  6. 底部「共 N 件收藏 · M 个标签」与「数据来自我的 pixiv 收藏 · Shaft」。
 */
internal object TagRankPosterRenderer {

    /** 输出宽度固定 1080，与云端分享模板、微信/微博卡片的常见宽度一致。 */
    const val CANVAS_WIDTH = 1080

    // ───────────────────────────────────────────────────────────────
    // 入口
    // ───────────────────────────────────────────────────────────────

    fun render(
        data: TagRankPosterData,
        copy: TagRankPosterCopy,
        typography: TagRankPosterTypography = TagRankPosterTypography.system(),
    ): Bitmap {
        // tags 已按 count 降序；visibleTags 按用户选择截取（不足则按实际个数）。
        val tags = data.visibleTags
        val compact = tags.size > TagRankPosterData.TAG_COUNT_6
        val hasAccount = tags.isNotEmpty() && data.showsAccountCard
        val layout = layoutFor(hasAccount, compact, tags.size)
        val bitmap = Bitmap.createBitmap(CANVAS_WIDTH, layout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, layout.height)
        drawDecorations(canvas, layout.height)
        drawBrandRow(canvas, typography)
        // 徽标数字 = 实际画出的行数：选 TOP 20 但只有 12 个标签时显示 TOP 12。
        val badgeNumber = if (tags.isEmpty()) {
            data.tagCount.coerceIn(TagRankPosterData.TAG_COUNT_6, TagRankPosterData.MAX_TAGS)
        } else {
            tags.size
        }
        drawTitleBlock(canvas, typography, copy, badgeNumber)
        if (tags.isEmpty()) {
            drawEmptyState(canvas, typography, copy, layout)
        } else {
            drawRankRows(canvas, tags, typography, copy, layout)
        }
        if (hasAccount) {
            drawAccountCard(canvas, data, typography, copy, layout.accountTop)
        }
        drawFooter(canvas, data, typography, copy, layout)
        return bitmap
    }

    // ───────────────────────────────────────────────────────────────
    // 版式
    // ───────────────────────────────────────────────────────────────

    private data class Layout(
        val height: Int,
        val rowsTop: Float,
        val rowHeight: Float,
        val rowGap: Float,
        val style: RowStyle,
        val accountTop: Float,
        val dividerY: Float,
        val statsTop: Float,
        val sourceTop: Float,
        val footerTop: Float,
    )

    /**
     * 一组「每行排行卡」的度量。TOP 6 与 TOP 20 只在数值上不同，绘制代码完全共用，
     * 避免两套分支各自漂移（上次修好的裁切/徽章/进度条渐变在两种模式下行为一致）。
     */
    private class RowStyle(
        val cardRadius: Float,
        val sidePadding: Float,
        val badgeSize: Float,
        val badgeRadius: Float,
        val rankNumberSize: Float,
        val glassNumberSize: Float,
        val badgeToText: Float,
        val nameSize: Float,
        val translationSize: Float,
        val nameBaselineWithTranslation: Float,
        val nameBaselineOnly: Float,
        val translationOffset: Float,
        val countNumberSize: Float,
        val countUnitSize: Float,
        val countGap: Float,
        val countBaseline: Float,
        val rightPadding: Float,
        val textToCountGap: Float,
        val barHeight: Float,
        val barBottomInset: Float,
        val barRadius: Float,
    )

    /** TOP 6 舒适档：数值与已验收版本逐项一致，不要动。 */
    private fun comfortableRowStyle(): RowStyle = RowStyle(
        cardRadius = 24f,
        sidePadding = 24f,
        badgeSize = 50f,
        badgeRadius = 17f,
        rankNumberSize = 32f,
        glassNumberSize = 30f,
        badgeToText = 24f,
        nameSize = 36f,
        translationSize = 22f,
        nameBaselineWithTranslation = 44f,
        nameBaselineOnly = 59f,
        translationOffset = 30f,
        countNumberSize = 32f,
        countUnitSize = 20f,
        countGap = 10f,
        countBaseline = 52f,
        rightPadding = 26f,
        textToCountGap = 20f,
        barHeight = 6f,
        barBottomInset = 26f,
        barRadius = 3f,
    )

    /** TOP 20 紧凑档：行高 88/间距 9，标签名 28、译名 18、徽章 40、作品数 24/16、条 4px。 */
    private fun compactRowStyle(): RowStyle = RowStyle(
        cardRadius = 20f,
        sidePadding = 20f,
        badgeSize = 40f,
        badgeRadius = 13f,
        rankNumberSize = 26f,
        glassNumberSize = 24f,
        badgeToText = 18f,
        nameSize = 28f,
        translationSize = 18f,
        nameBaselineWithTranslation = 34f,
        nameBaselineOnly = 46f,
        translationOffset = 24f,
        countNumberSize = 24f,
        countUnitSize = 16f,
        countGap = 8f,
        countBaseline = 38f,
        rightPadding = 22f,
        textToCountGap = 16f,
        barHeight = 4f,
        barBottomInset = 18f,
        barRadius = 2f,
    )

    /**
     * 统一的纵向节奏。标题区按固定顶部锚点排版，排行 / 账号 / 页脚按固定间距接在 rowsTop
     * 之后。行数决定高度：TOP 6 为 1512/1610，TOP 20 约 2665-2810。
     */
    private fun layoutFor(hasAccount: Boolean, compact: Boolean, rowCount: Int): Layout {
        val rows = rowCount.coerceAtLeast(1)
        val rowHeight = when {
            compact -> COMPACT_ROW_HEIGHT
            hasAccount -> 112f
            else -> 118f
        }
        val rowGap = when {
            compact -> COMPACT_ROW_GAP
            hasAccount -> 12f
            else -> 14f
        }
        val style = if (compact) compactRowStyle() else comfortableRowStyle()
        val rowsTop = rankRowsTop()
        val rowsBottom = rowsTop + rowHeight * rows + rowGap * (rows - 1)
        var cursor = rowsBottom
        val accountTop: Float
        if (hasAccount) {
            accountTop = cursor + 26f
            cursor = accountTop + ACCOUNT_HEIGHT
        } else {
            accountTop = -1f
        }
        val dividerY = if (hasAccount) cursor + 30f else cursor + 36f
        val statsTop = dividerY + 24f
        val sourceTop = statsTop + 68f
        val footerTop = sourceTop + 44f
        val height = (footerTop + 36f + 54f).roundToInt()
        return Layout(
            height = height,
            rowsTop = rowsTop,
            rowHeight = rowHeight,
            rowGap = rowGap,
            style = style,
            accountTop = accountTop,
            dividerY = dividerY,
            statsTop = statsTop,
            sourceTop = sourceTop,
            footerTop = footerTop,
        )
    }

    /** 标题区结束后第一行排行卡的顶部。用固定值而不是实测文本高度：不同设备 CJK 字体的
     * ascent/descent 差异很大（Noto Sans CJK 比 Roboto 高约 20%），按实测排会让样张和真机
     * 的留白观感不一致。固定值 + 每个文本块内部按基线对齐，视觉更稳。
     */
    private fun rankRowsTop(): Float = 472f

    private const val COMPACT_ROW_HEIGHT = 88f
    private const val COMPACT_ROW_GAP = 8f

    // ───────────────────────────────────────────────────────────────
    // 背景与装饰
    // ───────────────────────────────────────────────────────────────

    private fun drawBackground(canvas: Canvas, height: Int) {
        val background = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(BG_TOP, BG_MID, BG_LOW, BG_BOTTOM),
                floatArrayOf(0f, 0.42f, 0.74f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, CANVAS_WIDTH.toFloat(), height.toFloat(), background)

        // 三团品牌光：右上 pixiv 蓝、左下品牌粉、中左紫，低透明度叠在深底上像舞台灯。
        drawGlow(canvas, 948f, 108f, 660f, GLOW_BLUE)
        drawGlow(canvas, 36f, height - 200f, 560f, GLOW_PINK)
        drawGlow(canvas, -140f, height * 0.54f, 540f, GLOW_VIOLET)
    }

    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(color, withAlpha(color, 0f)),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, radius, paint)
    }

    private fun drawDecorations(canvas: Canvas, height: Int) {
        // 右上同心圆环：给标题区一个几何锚点，也不抢排行内容的注意力。
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
            color = withAlpha(WHITE, 0.075f)
        }
        canvas.drawCircle(994f, 236f, 298f, ring)
        ring.strokeWidth = 1.4f
        ring.color = withAlpha(WHITE, 0.04f)
        canvas.drawCircle(994f, 236f, 384f, ring)

        // 左下角一段细弧，和右上圆环形成对角呼应。
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.6f
            strokeCap = Paint.Cap.ROUND
            color = withAlpha(BRAND_BLUE_LIGHT, 0.16f)
        }
        canvas.drawArc(
            RectF(58f, height - 584f, 536f, height - 78f),
            152f, 124f, false, arc,
        )
        arc.strokeWidth = 1.4f
        arc.color = withAlpha(BRAND_PINK, 0.10f)
        canvas.drawArc(
            RectF(120f, height - 500f, 500f, height - 120f),
            168f, 92f, false, arc,
        )

        // 星芒：只放在标题区与四角留白里，不落在排行卡/文字上（贴着进度条或页脚文字会被当成脏点）。
        drawSparkle(canvas, 142f, 262f, 13f, withAlpha(WHITE, 0.34f))
        drawSparkle(canvas, 884f, 430f, 10f, withAlpha(BRAND_BLUE_LIGHT, 0.46f))
        drawSparkle(canvas, 1036f, 150f, 8f, withAlpha(BRAND_PINK, 0.42f))
        drawSparkle(canvas, 42f, height - 300f, 11f, withAlpha(BRAND_BLUE_LIGHT, 0.30f))
        drawSparkle(canvas, 1036f, height - 120f, 9f, withAlpha(BRAND_PINK, 0.32f))

        // 极淡的内描边，像打印出来的卡片边。
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f
            color = withAlpha(WHITE, 0.055f)
        }
        canvas.drawRoundRect(
            RectF(18f, 18f, CANVAS_WIDTH - 18f, height - 18f),
            44f, 44f, frame,
        )
    }

    private fun drawSparkle(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        val r = radius
        val squeeze = r * 0.10f
        val path = Path().apply {
            moveTo(cx, cy - r)
            quadTo(cx + squeeze, cy - squeeze, cx + r, cy)
            quadTo(cx + squeeze, cy + squeeze, cx, cy + r)
            quadTo(cx - squeeze, cy + squeeze, cx - r, cy)
            quadTo(cx - squeeze, cy - squeeze, cx, cy - r)
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    // ───────────────────────────────────────────────────────────────
    // 顶部品牌区
    // ───────────────────────────────────────────────────────────────

    private fun drawBrandRow(canvas: Canvas, typography: TagRankPosterTypography) {
        val wordmark = paint(typography.latinExtraBold, 52f, BRAND_BLUE).apply {
            letterSpacing = -0.035f
        }
        val wordmarkBaseline = BRAND_TOP - wordmark.fontMetrics.ascent
        canvas.drawText(PIXIV_WORDMARK, CONTENT_LEFT, wordmarkBaseline, wordmark)

        // 右上角：图标 + pixiv.net 胶囊，第一时间告诉看图的人数据来自 pixiv 网站。
        val label = paint(typography.latinMedium, 23f, withAlpha(WHITE, 0.70f)).apply {
            letterSpacing = 0.012f
        }
        val iconSize = 20f
        val gap = 9f
        val padH = 20f
        val pillHeight = 46f
        val pillWidth = padH + iconSize + gap + label.measureText(PIXIV_HOST) + padH
        val centerY = BRAND_TOP + (textHeight(wordmark) / 2f)
        val pillRect = RectF(
            CONTENT_RIGHT - pillWidth,
            centerY - pillHeight / 2f,
            CONTENT_RIGHT,
            centerY + pillHeight / 2f,
        )
        canvas.drawRoundRect(pillRect, pillHeight / 2f, pillHeight / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(WHITE, 0.055f)
        })
        canvas.drawRoundRect(pillRect, pillHeight / 2f, pillHeight / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f
            color = withAlpha(WHITE, 0.14f)
        })
        drawIcon(
            canvas,
            PATH_BOOKMARK,
            RectF(
                pillRect.left + padH,
                centerY - iconSize / 2f,
                pillRect.left + padH + iconSize,
                centerY + iconSize / 2f,
            ),
            withAlpha(WHITE, 0.72f),
        )
        val labelBaseline = centerY - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2f
        canvas.drawText(
            PIXIV_HOST,
            pillRect.left + padH + iconSize + gap,
            labelBaseline,
            label,
        )
    }

    // ───────────────────────────────────────────────────────────────
    // 标题区
    // ───────────────────────────────────────────────────────────────

    private fun drawTitleBlock(
        canvas: Canvas,
        typography: TagRankPosterTypography,
        copy: TagRankPosterCopy,
        badgeNumber: Int,
    ) {
        // eyebrow：装饰性英文小标，给出「这是一份收藏报告」的语气。
        val eyebrow = paint(typography.latinSemiBold, 21f, withAlpha(WHITE, 0.38f)).apply {
            letterSpacing = 0.24f
        }
        canvas.drawText(
            copy.eyebrow.uppercase(copy.locale),
            CONTENT_LEFT,
            EYEBROW_TOP - eyebrow.fontMetrics.ascent,
            eyebrow,
        )

        // 第一行：我的收藏（中文走系统字体，英文走 Montserrat）
        val head = paint(
            if (containsCjk(copy.titleHead)) typography.cjkBold else typography.latinBold,
            56f,
            withAlpha(WHITE, 0.96f),
        )
        val headBaseline = TITLE_LINE1_TOP - head.fontMetrics.ascent
        canvas.drawText(copy.titleHead, CONTENT_LEFT, headBaseline, head)

        // 第二行：TAG（Montserrat ExtraBold + 蓝粉渐变）+ 排行
        val accent = paint(typography.latinExtraBold, 80f, WHITE).apply {
            letterSpacing = -0.025f
            shader = LinearGradient(
                CONTENT_LEFT, 0f, CONTENT_LEFT + 380f, 0f,
                intArrayOf(BRAND_BLUE_LIGHT, BRAND_BLUE, BRAND_PINK),
                floatArrayOf(0f, 0.56f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        val tail = paint(
            if (containsCjk(copy.titleTail)) typography.cjkBold else typography.latinBold,
            56f,
            withAlpha(WHITE, 0.96f),
        )
        val line2Baseline = TITLE_LINE2_TOP - accent.fontMetrics.ascent
        canvas.drawText(copy.titleAccent, CONTENT_LEFT, line2Baseline, accent)
        canvas.drawText(
            copy.titleTail,
            CONTENT_LEFT + accent.measureText(copy.titleAccent) + 18f,
            line2Baseline,
            tail,
        )

        // 右侧 TOP 6 徽标：小标 + 超大渐变数字 + 收尾小标。
        val badgeTopPaint = paint(typography.latinSemiBold, 21f, withAlpha(WHITE, 0.45f)).apply {
            textAlign = Paint.Align.RIGHT
            letterSpacing = 0.30f
        }
        canvas.drawText(
            copy.badgeTop,
            CONTENT_RIGHT,
            BADGE_TOP - badgeTopPaint.fontMetrics.ascent,
            badgeTopPaint,
        )
        val badgeNumberTop = BADGE_TOP + textHeight(badgeTopPaint) + 2f
        val number = paint(typography.latinExtraBold, 128f, WHITE).apply {
            textAlign = Paint.Align.RIGHT
            letterSpacing = -0.03f
            shader = LinearGradient(
                CONTENT_RIGHT - 190f, 0f, CONTENT_RIGHT, 0f,
                intArrayOf(BRAND_BLUE_LIGHT, BRAND_PINK),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawText(
            badgeNumber.toString(),
            CONTENT_RIGHT,
            badgeNumberTop - number.fontMetrics.ascent,
            number,
        )
        val badgeTailPaint = paint(typography.latinSemiBold, 19f, withAlpha(WHITE, 0.32f)).apply {
            textAlign = Paint.Align.RIGHT
            letterSpacing = 0.22f
        }
        canvas.drawText(
            copy.badgeTail,
            CONTENT_RIGHT,
            badgeNumberTop + textHeight(number) + 2f - badgeTailPaint.fontMetrics.ascent,
            badgeTailPaint,
        )

        // 副标题：pixiv（wordmark 色）+ 说明，坐实「pixiv 网站收藏」这个前提。
        val brandSegment = paint(typography.latinExtraBold, 27f, BRAND_BLUE).apply {
            letterSpacing = -0.02f
        }
        val subtitle = paint(
            if (containsCjk(copy.subtitle)) typography.cjkMedium else typography.latinMedium,
            27f,
            withAlpha(WHITE, 0.62f),
        )
        val subtitleBaseline = SUBTITLE_TOP - subtitle.fontMetrics.ascent
        var x = CONTENT_LEFT
        canvas.drawText(PIXIV_WORDMARK, x, subtitleBaseline, brandSegment)
        x += brandSegment.measureText(PIXIV_WORDMARK) + 10f
        canvas.drawText(copy.subtitle, x, subtitleBaseline, subtitle)
    }

    // ───────────────────────────────────────────────────────────────
    // 排行行
    // ───────────────────────────────────────────────────────────────

    private fun drawRankRows(
        canvas: Canvas,
        tags: List<AllBookmarkTag>,
        typography: TagRankPosterTypography,
        copy: TagRankPosterCopy,
        layout: Layout,
    ) {
        val maxCount = tags.maxOf { it.count }.coerceAtLeast(1)
        var top = layout.rowsTop
        tags.forEachIndexed { index, tag ->
            drawRankRow(canvas, index, tag, maxCount, typography, copy, layout, top)
            top += layout.rowHeight + layout.rowGap
        }
    }

    private fun drawRankRow(
        canvas: Canvas,
        index: Int,
        tag: AllBookmarkTag,
        maxCount: Int,
        typography: TagRankPosterTypography,
        copy: TagRankPosterCopy,
        layout: Layout,
        top: Float,
    ) {
        val rowHeight = layout.rowHeight
        val rowStyle = layout.style
        val accent = RANK_ACCENTS.getOrNull(index)
        val card = RectF(CONTENT_LEFT, top, CONTENT_RIGHT, top + rowHeight)

        // 卡底：前三名铺一层该名次的强调色微光，其余是中性玻璃。
        canvas.drawRoundRect(card, rowStyle.cardRadius, rowStyle.cardRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = when (accent) {
                null -> withAlpha(WHITE, 0.055f)
                else -> withAlpha(accent, 0.085f)
            }
        })
        canvas.drawRoundRect(card, rowStyle.cardRadius, rowStyle.cardRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
            color = when (accent) {
                null -> withAlpha(WHITE, 0.10f)
                else -> withAlpha(accent, 0.26f)
            }
        })

        // 名次徽章：前三名是金银铜渐变方块；其余（TOP 20 时是 4-20 名）用中性玻璃徽章 ——
        // 同样是「圆角方块 + 数字」，只是没有名次色，全部行形状一致。
        val badgeSize = rowStyle.badgeSize
        val badgeLeft = CONTENT_LEFT + rowStyle.sidePadding
        val badgeTop = top + (rowHeight - badgeSize) / 2f + 2f
        val badgeRect = RectF(badgeLeft, badgeTop, badgeLeft + badgeSize, badgeTop + badgeSize)
        if (accent != null) {
            val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    badgeRect.left, badgeRect.top, badgeRect.right, badgeRect.bottom,
                    intArrayOf(lighten(accent, 0.22f), accent),
                    null, Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRoundRect(badgeRect, rowStyle.badgeRadius, rowStyle.badgeRadius, badgeFill)
            val number = paint(typography.latinExtraBold, rowStyle.rankNumberSize, RANK_BADGE_INK).apply {
                textAlign = Paint.Align.CENTER
            }
            val baseline = badgeRect.centerY() - (number.fontMetrics.ascent + number.fontMetrics.descent) / 2f
            canvas.drawText((index + 1).toString(), badgeRect.centerX(), baseline, number)
        } else {
            canvas.drawRoundRect(badgeRect, rowStyle.badgeRadius, rowStyle.badgeRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha(WHITE, 0.07f)
            })
            canvas.drawRoundRect(badgeRect, rowStyle.badgeRadius, rowStyle.badgeRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.2f
                color = withAlpha(WHITE, 0.16f)
            })
            val number = paint(typography.latinExtraBold, rowStyle.glassNumberSize, withAlpha(WHITE, 0.62f)).apply {
                textAlign = Paint.Align.CENTER
            }
            val baseline = badgeRect.centerY() - (number.fontMetrics.ascent + number.fontMetrics.descent) / 2f
            canvas.drawText((index + 1).toString(), badgeRect.centerX(), baseline, number)
        }

        // 右侧「N 件作品」：先量出「数字 + 间距 + 单位」的整块宽度，再从右向左定位，
        // 数字本身左对齐排在块首 —— 右对齐数字再按单位宽度反推会压到数字上（曾把 "23 works"
        // 画成 "wo23"）。整块必须落在 1008 的卡内右边界以内。
        val count = formatCount(tag.count, copy.locale)
        val countPaint = paint(typography.latinExtraBold, rowStyle.countNumberSize, withAlpha(WHITE, 0.96f)).apply {
            letterSpacing = -0.01f
        }
        val unitPaint = paint(
            if (containsCjk(copy.countUnit)) typography.cjkMedium else typography.latinMedium,
            rowStyle.countUnitSize,
            withAlpha(WHITE, 0.52f),
        )
        val countRight = CONTENT_RIGHT - rowStyle.rightPadding
        val numberWidth = countPaint.measureText(count)
        val unitWidth = unitPaint.measureText(copy.countUnit)
        val countBlockWidth = numberWidth + rowStyle.countGap + unitWidth
        val countLeft = countRight - countBlockWidth
        val countBaseline = top + rowStyle.countBaseline
        canvas.drawText(count, countLeft, countBaseline, countPaint)
        canvas.drawText(copy.countUnit, countLeft + numberWidth + rowStyle.countGap, countBaseline, unitPaint)

        // 标签名（主）+ 译名（次）；标签名过长时省略，不挤压右侧数字。
        val textLeft = badgeRect.right + rowStyle.badgeToText
        val textRight = countLeft - rowStyle.textToCountGap
        val namePaint = paint(
            if (containsCjk(tag.displayName)) typography.cjkBold else typography.latinBold,
            rowStyle.nameSize,
            withAlpha(WHITE, 0.97f),
        )
        val rawName = tag.displayName.ifBlank { tag.tagName }
        val name = ellipsize(rawName, namePaint, max(60f, textRight - textLeft))
        val translated = tag.translatedName.trim()
        val showsTranslation = translated.isNotEmpty() && translated != rawName.trim()
        val translationPaint = paint(
            if (containsCjk(translated)) typography.cjkMedium else typography.latinMedium,
            rowStyle.translationSize,
            withAlpha(WHITE, 0.45f),
        )
        val nameBaseline = top + if (showsTranslation) {
            rowStyle.nameBaselineWithTranslation
        } else {
            rowStyle.nameBaselineOnly
        }
        canvas.drawText(name, textLeft, nameBaseline, namePaint)
        if (showsTranslation) {
            val translation = ellipsize(translated, translationPaint, max(60f, textRight - textLeft))
            canvas.drawText(translation, textLeft, nameBaseline + rowStyle.translationOffset, translationPaint)
        }

        // 相对长度条：宽度 = 该标签作品数 / 本张海报里最多的标签。
        val ratio = (tag.count.toFloat() / maxCount.toFloat()).coerceIn(0.06f, 1f)
        val barLeft = textLeft
        val barRight = CONTENT_RIGHT - rowStyle.rightPadding
        val barTop = top + rowHeight - rowStyle.barBottomInset
        val barBottom = barTop + rowStyle.barHeight
        val barRect = RectF(barLeft, barTop, barRight, barBottom)
        canvas.drawRoundRect(barRect, rowStyle.barRadius, rowStyle.barRadius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(WHITE, 0.075f)
        })
        val fillRight = barLeft + (barRight - barLeft) * ratio
        if (fillRight > barLeft + 4f) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // 渐变按「填充段」铺而不是整条轨道：短条也能完整呈现蓝→紫，不会只有最左一段蓝。
                shader = LinearGradient(
                    barLeft, 0f, fillRight, 0f,
                    intArrayOf(barStartColor(index), barEndColor(index)),
                    null, Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRoundRect(
                RectF(barLeft, barTop, fillRight, barBottom),
                rowStyle.barRadius, rowStyle.barRadius, fill,
            )
        }
    }

    private fun barStartColor(index: Int): Int = RANK_ACCENTS.getOrNull(index)?.let { lighten(it, 0.18f) }
        ?: BRAND_BLUE

    private fun barEndColor(index: Int): Int = RANK_ACCENTS.getOrNull(index)?.let { darken(it, 0.10f) }
        ?: BRAND_PURPLE

    // ───────────────────────────────────────────────────────────────
    // 账号卡
    // ───────────────────────────────────────────────────────────────

    private fun drawAccountCard(
        canvas: Canvas,
        data: TagRankPosterData,
        typography: TagRankPosterTypography,
        copy: TagRankPosterCopy,
        top: Float,
    ) {
        val card = RectF(CONTENT_LEFT, top, CONTENT_RIGHT, top + ACCOUNT_HEIGHT)
        canvas.drawRoundRect(card, ACCOUNT_RADIUS, ACCOUNT_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(WHITE, 0.055f)
        })
        canvas.drawRoundRect(card, ACCOUNT_RADIUS, ACCOUNT_RADIUS, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
            color = withAlpha(WHITE, 0.11f)
        })

        val avatarSize = 86f
        val avatarLeft = card.left + 22f
        val avatarCenterY = card.centerY()
        val avatarRect = RectF(
            avatarLeft,
            avatarCenterY - avatarSize / 2f,
            avatarLeft + avatarSize,
            avatarCenterY + avatarSize / 2f,
        )
        val hasAvatar = data.showAvatar
        var textLeft = card.left + 28f
        if (hasAvatar) {
            val avatar = data.avatar
            if (avatar != null && avatar.width > 0 && avatar.height > 0) {
                drawCircleBitmap(canvas, avatar, avatarRect)
            } else {
                drawAvatarFallback(canvas, avatarRect, data.userName, typography)
            }
            // 头像外圈：白色细环 + 一点点品牌色内发光。
            canvas.drawCircle(
                avatarRect.centerX(), avatarRect.centerY(), avatarSize / 2f + 1.5f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    color = withAlpha(WHITE, 0.22f)
                },
            )
            textLeft = avatarRect.right + 24f
        }

        val name = data.userName.ifBlank { data.userAccount.ifBlank { PIXIV_HOST } }
        val namePaint = paint(
            if (containsCjk(name)) typography.cjkBold else typography.latinSemiBold,
            34f,
            withAlpha(WHITE, 0.97f),
        )
        val textRight = card.right - 30f
        canvas.drawText(
            ellipsize(name, namePaint, max(60f, textRight - textLeft)),
            textLeft,
            card.top + 50f,
            namePaint,
        )

        // 第二行：@account · UID xxxxx，把「哪个 pixiv 账号」交代清楚。
        val subParts = buildList {
            val account = data.userAccount.trim().removePrefix("@")
            if (account.isNotEmpty()) add("@$account")
            if (data.userId > 0L) add(String.format(copy.locale, copy.uidTemplate, data.userId.toString()))
            if (isEmpty()) add(PIXIV_HOST)
        }
        val subPaint = paint(
            if (containsCjk(subParts.joinToString(" "))) typography.cjkMedium else typography.latinMedium,
            23f,
            withAlpha(WHITE, 0.48f),
        )
        canvas.drawText(
            ellipsize(subParts.joinToString("  ·  "), subPaint, max(60f, textRight - textLeft)),
            textLeft,
            card.top + 86f,
            subPaint,
        )

        // 右端小 wordmark：账号卡里的 pixiv 身份锚点。
        val mark = paint(typography.latinExtraBold, 26f, withAlpha(BRAND_BLUE, 0.92f)).apply {
            textAlign = Paint.Align.RIGHT
            letterSpacing = -0.03f
        }
        canvas.drawText(
            PIXIV_WORDMARK,
            card.right - 26f,
            card.centerY() - (mark.fontMetrics.ascent + mark.fontMetrics.descent) / 2f,
            mark,
        )
    }

    private fun drawAvatarFallback(
        canvas: Canvas,
        rect: RectF,
        name: String,
        typography: TagRankPosterTypography,
    ) {
        canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(BRAND_BLUE, BRAND_PURPLE),
                null, Shader.TileMode.CLAMP,
            )
        })
        val trimmed = name.trim()
        val initial = if (trimmed.isEmpty()) {
            "P"
        } else {
            String(Character.toChars(trimmed.codePointAt(0))).uppercase(Locale.getDefault())
        }
        val paint = paint(typography.latinExtraBold, 38f, WHITE).apply {
            textAlign = Paint.Align.CENTER
        }
        val baseline = rect.centerY() - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        canvas.drawText(initial, rect.centerX(), baseline, paint)
    }

    private fun drawCircleBitmap(canvas: Canvas, bitmap: Bitmap, rect: RectF) {
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max(rect.width() / bitmap.width, rect.height() / bitmap.height)
        val dx = rect.centerX() - bitmap.width * scale / 2f
        val dy = rect.centerY() - bitmap.height * scale / 2f
        shader.setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        })
        canvas.drawCircle(
            rect.centerX(), rect.centerY(), rect.width() / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader },
        )
    }

    // ───────────────────────────────────────────────────────────────
    // 页脚：统计 + 数据来源 + 品牌
    // ───────────────────────────────────────────────────────────────

    private fun drawFooter(
        canvas: Canvas,
        data: TagRankPosterData,
        typography: TagRankPosterTypography,
        copy: TagRankPosterCopy,
        layout: Layout,
    ) {
        // 分隔线：两端淡出，避免把画面切成硬邦邦的两块。
        val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                CONTENT_LEFT, 0f, CONTENT_RIGHT, 0f,
                intArrayOf(withAlpha(WHITE, 0f), withAlpha(WHITE, 0.16f), withAlpha(WHITE, 0f)),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(CONTENT_LEFT, layout.dividerY, CONTENT_RIGHT, layout.dividerY + 1.2f, divider)

        // 统计行：共 N 件收藏 · M 个标签
        val statsNumber = paint(typography.latinExtraBold, 42f, withAlpha(WHITE, 0.96f)).apply {
            letterSpacing = -0.02f
        }
        val statsLabel = paint(
            if (containsCjk(copy.statsTotalTemplate)) typography.cjkMedium else typography.latinMedium,
            25f,
            withAlpha(WHITE, 0.55f),
        )
        val statsBaseline = layout.statsTop - statsNumber.fontMetrics.ascent
        drawStatSegment(canvas, CONTENT_LEFT, statsBaseline, copy.statsTotalTemplate, formatCount(data.totalWorks, copy.locale), statsNumber, statsLabel)
        // 第二个统计量从第一段宽度之后接上，间距用中文间隔点。
        val firstWidth = statSegmentWidth(copy.statsTotalTemplate, formatCount(data.totalWorks, copy.locale), statsNumber, statsLabel)
        val dot = paint(typography.latinBold, 26f, withAlpha(WHITE, 0.28f))
        canvas.drawText(STATS_SEPARATOR, CONTENT_LEFT + firstWidth + 18f, statsBaseline - 2f, dot)
        val secondLeft = CONTENT_LEFT + firstWidth + 18f + dot.measureText(STATS_SEPARATOR) + 18f
        drawStatSegment(canvas, secondLeft, statsBaseline, copy.statsTagsTemplate, formatCount(data.distinctTagCount, copy.locale), statsNumber, statsLabel)

        // 数据来源：数据来自我的 pixiv 收藏 · Shaft
        val sourceLabel = paint(
            if (containsCjk(copy.sourcePrefix)) typography.cjkMedium else typography.latinMedium,
            24f,
            withAlpha(WHITE, 0.52f),
        )
        val sourceBaseline = layout.sourceTop - sourceLabel.fontMetrics.ascent
        var x = CONTENT_LEFT
        canvas.drawText(copy.sourcePrefix, x, sourceBaseline, sourceLabel)
        x += sourceLabel.measureText(copy.sourcePrefix) + 8f
        val sourceBrand = paint(typography.latinExtraBold, 26f, withAlpha(BRAND_BLUE, 0.95f)).apply {
            letterSpacing = -0.02f
        }
        canvas.drawText(PIXIV_WORDMARK, x, sourceBaseline, sourceBrand)
        x += sourceBrand.measureText(PIXIV_WORDMARK) + 8f
        canvas.drawText(copy.sourceSuffix, x, sourceBaseline, sourceLabel)

        // 底栏品牌：左 pixiv wordmark，右 Shaft 署名。
        val footerMark = paint(typography.latinExtraBold, 30f, withAlpha(BRAND_BLUE, 0.92f)).apply {
            letterSpacing = -0.035f
        }
        val footerBaseline = layout.footerTop - footerMark.fontMetrics.ascent
        canvas.drawText(PIXIV_WORDMARK, CONTENT_LEFT, footerBaseline, footerMark)

        val footerDate = paint(typography.latinMedium, 20f, withAlpha(WHITE, 0.30f)).apply {
            textAlign = Paint.Align.RIGHT
            letterSpacing = 0.02f
        }
        canvas.drawText(
            PIXIV_HOST,
            CONTENT_RIGHT,
            footerBaseline - 4f,
            footerDate,
        )
        val footerBrand = paint(typography.latinSemiBold, 22f, withAlpha(WHITE, 0.42f)).apply {
            textAlign = Paint.Align.RIGHT
            letterSpacing = 0.06f
        }
        canvas.drawText(FOOTER_BRAND, CONTENT_RIGHT, footerBaseline + 26f, footerBrand)
    }

    private fun drawStatSegment(
        canvas: Canvas,
        left: Float,
        baseline: Float,
        template: String,
        value: String,
        numberPaint: Paint,
        labelPaint: Paint,
    ) {
        // 模板形如「共 %1$s 件收藏」：占位符前面的文字用标签字，数字用大号数字字。
        val markerIndex = template.indexOf("%1\$s")
        val prefix = if (markerIndex >= 0) template.substring(0, markerIndex) else template
        val suffix = if (markerIndex >= 0) template.substring(markerIndex + 4) else ""
        var x = left
        if (prefix.isNotEmpty()) {
            canvas.drawText(prefix, x, baseline + 6f, labelPaint)
            x += labelPaint.measureText(prefix)
        }
        canvas.drawText(value, x, baseline, numberPaint)
        x += numberPaint.measureText(value)
        if (suffix.isNotEmpty()) {
            canvas.drawText(suffix, x, baseline + 6f, labelPaint)
        }
    }

    private fun statSegmentWidth(
        template: String,
        value: String,
        numberPaint: Paint,
        labelPaint: Paint,
    ): Float {
        val markerIndex = template.indexOf("%1\$s")
        val prefix = if (markerIndex >= 0) template.substring(0, markerIndex) else template
        val suffix = if (markerIndex >= 0) template.substring(markerIndex + 4) else ""
        return labelPaint.measureText(prefix) + numberPaint.measureText(value) + labelPaint.measureText(suffix)
    }

    // ───────────────────────────────────────────────────────────────
    // 空态
    // ───────────────────────────────────────────────────────────────

    private fun drawEmptyState(
        canvas: Canvas,
        typography: TagRankPosterTypography,
        copy: TagRankPosterCopy,
        layout: Layout,
    ) {
        val hint = paint(typography.cjkMedium, 28f, withAlpha(WHITE, 0.56f)).apply {
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            copy.subtitle,
            CANVAS_WIDTH / 2f,
            layout.rowsTop + 220f,
            hint,
        )
    }

    // ───────────────────────────────────────────────────────────────
    // 小工具
    // ───────────────────────────────────────────────────────────────

    private fun paint(typeface: Typeface, size: Float, color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = size
            this.color = color
        }

    private fun textHeight(paint: Paint): Float =
        paint.fontMetrics.descent - paint.fontMetrics.ascent

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (maxWidth <= 0f || text.isEmpty()) return ""
        val textPaint = TextPaint(paint).apply {
            // 渐变 shader 会让 measureText 走 shader 路径，这里只需要字宽。
            shader = null
        }
        return TextUtils.ellipsize(text, textPaint, maxWidth, TextUtils.TruncateAt.END).toString()
    }

    private fun drawIcon(canvas: Canvas, pathData: String, rect: RectF, color: Int) {
        val path = PathParser.createPathFromPathData(pathData) ?: return
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        val scale = min(rect.width() / bounds.width(), rect.height() / bounds.height())
        val dx = rect.left + (rect.width() - bounds.width() * scale) / 2f - bounds.left * scale
        val dy = rect.top + (rect.height() - bounds.height() * scale) / 2f - bounds.top * scale
        path.transform(Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        })
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun formatCount(value: Int, locale: Locale): String =
        String.format(locale, "%,d", value.coerceAtLeast(0))

    private fun containsCjk(text: String): Boolean {
        for (char in text) {
            val block = Character.UnicodeBlock.of(char) ?: continue
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA ||
                block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
            ) {
                return true
            }
        }
        return false
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun lighten(color: Int, amount: Float): Int = mix(color, Color.WHITE, amount)

    private fun darken(color: Int, amount: Float): Int = mix(color, Color.BLACK, amount)

    private fun mix(color: Int, target: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val r = (Color.red(color) + (Color.red(target) - Color.red(color)) * t).roundToInt()
        val g = (Color.green(color) + (Color.green(target) - Color.green(color)) * t).roundToInt()
        val b = (Color.blue(color) + (Color.blue(target) - Color.blue(color)) * t).roundToInt()
        return Color.rgb(r, g, b)
    }

    // ───────────────────────────────────────────────────────────────
    // 常量
    // ───────────────────────────────────────────────────────────────

    private const val CONTENT_LEFT = 72f
    private const val CONTENT_RIGHT = 1008f

    // 各区块顶部（像素，1080 宽画布）
    private const val BRAND_TOP = 58f
    private const val EYEBROW_TOP = 152f
    private const val TITLE_LINE1_TOP = 190f
    private const val TITLE_LINE2_TOP = 272f
    private const val SUBTITLE_TOP = 392f
    private const val BADGE_TOP = 192f
    private const val ACCOUNT_HEIGHT = 124f
    private const val ACCOUNT_RADIUS = 26f
    private const val STATS_SEPARATOR = "·"

    private const val PIXIV_WORDMARK = "pixiv"
    private const val PIXIV_HOST = "pixiv.net"
    private const val FOOTER_BRAND = "Pixiv-Shaft"

    // 品牌色：pixiv 蓝 + 粉紫点缀 + 金银铜
    private val BRAND_BLUE = Color.rgb(0x00, 0x96, 0xFA)
    private val BRAND_BLUE_LIGHT = Color.rgb(0x6E, 0xC8, 0xFF)
    private val BRAND_PURPLE = Color.rgb(0x8B, 0x6B, 0xFF)
    private val BRAND_PINK = Color.rgb(0xFF, 0x5C, 0x9E)
    private val GOLD = Color.rgb(0xFF, 0xC9, 0x4A)
    private val SILVER = Color.rgb(0xD8, 0xDF, 0xEA)
    private val BRONZE = Color.rgb(0xE5, 0x9B, 0x66)
    private val WHITE = Color.WHITE

    /** 前 3 名的金银铜强调色；第 4-6 名用中性白 + 品牌蓝紫进度条。声明在颜色之后。 */
    private val RANK_ACCENTS = intArrayOf(GOLD, SILVER, BRONZE)

    // 背景渐变
    private val BG_TOP = Color.rgb(0x0A, 0x0F, 0x1C)
    private val BG_MID = Color.rgb(0x10, 0x1B, 0x33)
    private val BG_LOW = Color.rgb(0x0C, 0x13, 0x24)
    private val BG_BOTTOM = Color.rgb(0x08, 0x0C, 0x16)

    // 光斑
    private val GLOW_BLUE = Color.argb(0x4A, 0x00, 0x96, 0xFA)
    private val GLOW_PINK = Color.argb(0x3A, 0xFF, 0x5C, 0x9E)
    private val GLOW_VIOLET = Color.argb(0x30, 0x8B, 0x6B, 0xFF)

    /** 金银铜徽章上的深色数字：三种强调色都偏亮，压深字才够读。 */
    private val RANK_BADGE_INK = Color.rgb(0x14, 0x18, 0x22)

    private const val PATH_BOOKMARK =
        "M17,3H7C5.9,3 5.01,3.9 5.01,5L5,21L12,18L19,21V5C19,3.9 18.1,3 17,3Z"
}
