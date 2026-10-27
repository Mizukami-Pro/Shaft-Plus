package ceui.pixiv.ui.tagbrowse.poster

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.LayoutInflater
import androidx.appcompat.view.ContextThemeWrapper
import ceui.lisa.R
import ceui.pixiv.db.mirror.AllBookmarkTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * 「我的Tag排行」海报的样张测试。
 *
 * 用真实渲染器画 1080px 海报并落 PNG 到 `app/build/reports/`，供人工目检；同时断言尺寸、
 * 非空、有无账号区的高度差，防止后续调视觉时把输出尺寸改坏。
 *
 * Robolectric 的 NATIVE graphics 里不一定带中文字形，若系统字体缺 CJK 字形就临时加载宿主
 * 机器上的中文字体（只影响这份样张，真机仍然走系统字体）。找不到任何中文字体时保持系统
 * 字体 —— 断言不依赖字形是否渲染成功，只检查位图结构。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TagRankPosterRenderTest {

    @Test
    fun `sample poster renders 1080 wide png for visual review`() {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = TagRankPosterRenderer.render(
            data = TagRankPosterData(
                tags = SAMPLE_TAGS,
                totalWorks = 4_218,
                distinctTagCount = 316,
                userName = "水上优择",
                userAccount = "minakami",
                userId = 12345678L,
                avatar = null,
                showAccount = true,
                showAvatar = true,
            ),
            copy = TagRankPosterCopy.from(context, SAMPLE_TAGS.size).copy(locale = Locale.CHINA),
            typography = sampleTypography(context),
        )
        try {
            assertEquals("海报宽度固定 1080", 1080, bitmap.width)
            assertTrue("海报高度应在 1440-1620 区间：${bitmap.height}", bitmap.height in 1440..1620)
            assertTrue("海报不应是纯色空图", hasVisualVariety(bitmap))
            val output = File(REPORT_DIR, "tag_rank_poster_sample.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            assertTrue("样张应已写出且不是空文件", output.isFile && output.length() > 20_000)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `poster without account section is shorter but keeps the same width`() {
        val context = RuntimeEnvironment.getApplication()
        val copy = TagRankPosterCopy.from(context, SAMPLE_TAGS.size).copy(locale = Locale.CHINA)
        val typography = sampleTypography(context)
        val withAccount = TagRankPosterRenderer.render(
            data = TagRankPosterData(
                tags = SAMPLE_TAGS,
                totalWorks = 4_218,
                distinctTagCount = 316,
                userName = "水上优择",
                userAccount = "minakami",
                userId = 12345678L,
            ),
            copy = copy,
            typography = typography,
        )
        val withoutAccount = TagRankPosterRenderer.render(
            data = TagRankPosterData(
                tags = SAMPLE_TAGS,
                totalWorks = 4_218,
                distinctTagCount = 316,
                showAccount = false,
                showAvatar = false,
            ),
            copy = copy,
            typography = typography,
        )
        try {
            assertEquals(1080, withoutAccount.width)
            assertTrue(
                "关掉账号区后海报应更矮：${withoutAccount.height} vs ${withAccount.height}",
                withoutAccount.height < withAccount.height,
            )
            assertTrue("无账号区版也应落在 1440-1620：${withoutAccount.height}", withoutAccount.height in 1440..1620)
            val output = File(REPORT_DIR, "tag_rank_poster_sample_no_account.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { stream ->
                bitmapPng(withoutAccount, stream)
            }
            assertTrue(output.isFile)
        } finally {
            withAccount.recycle()
            withoutAccount.recycle()
        }
    }

    @Test
    fun `poster with avatar renders and saves a second sample`() {
        val context = RuntimeEnvironment.getApplication()
        val avatar = syntheticAvatar()
        val bitmap = TagRankPosterRenderer.render(
            data = TagRankPosterData(
                tags = SAMPLE_TAGS,
                totalWorks = 4_218,
                distinctTagCount = 316,
                userName = "水上优择",
                userAccount = "minakami",
                userId = 12345678L,
                avatar = avatar,
                showAccount = true,
                showAvatar = true,
            ),
            copy = TagRankPosterCopy.from(context, SAMPLE_TAGS.size).copy(locale = Locale.CHINA),
            typography = sampleTypography(context),
        )
        try {
            assertEquals(1080, bitmap.width)
            assertTrue(hasVisualVariety(bitmap))
            val output = File(REPORT_DIR, "tag_rank_poster_sample_avatar.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { stream -> bitmapPng(bitmap, stream) }
            assertTrue(output.isFile)
        } finally {
            bitmap.recycle()
            avatar.recycle()
        }
    }

    @Test
    fun `top 20 poster renders dynamically tall png for visual review`() {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = TagRankPosterRenderer.render(
            data = TagRankPosterData(
                tags = SAMPLE_TAGS_20,
                totalWorks = 4_218,
                distinctTagCount = 316,
                userName = "水上优择",
                userAccount = "minakami",
                userId = 12345678L,
                showAccount = true,
                showAvatar = false,
                tagCount = TagRankPosterData.TAG_COUNT_20,
            ),
            copy = TagRankPosterCopy.from(context, SAMPLE_TAGS_20.size).copy(locale = Locale.CHINA),
            typography = sampleTypography(context),
        )
        try {
            assertEquals("海报宽度固定 1080", 1080, bitmap.width)
            assertTrue("TOP 20 海报高度应动态放大：${bitmap.height}", bitmap.height in 2200..2900)
            assertTrue("TOP 20 海报不应是纯色空图", hasVisualVariety(bitmap))
            val output = File(REPORT_DIR, "tag_rank_poster_sample_top20.png")
            output.parentFile?.mkdirs()
            output.outputStream().use { stream -> bitmapPng(bitmap, stream) }
            assertTrue(output.isFile)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `top 20 request with only 12 tags shows actual row count and stays compact`() {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = TagRankPosterRenderer.render(
            data = TagRankPosterData(
                tags = SAMPLE_TAGS_20.take(12),
                totalWorks = 4_218,
                distinctTagCount = 316,
                tagCount = TagRankPosterData.TAG_COUNT_20,
            ),
            copy = TagRankPosterCopy.from(context, 12).copy(locale = Locale.CHINA),
            typography = sampleTypography(context),
        )
        try {
            assertEquals(1080, bitmap.width)
            assertTrue("12 行紧凑版高度：${bitmap.height}", bitmap.height in 1700..2300)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `empty tag list still renders a placeholder poster`() {
        val context = RuntimeEnvironment.getApplication()
        val bitmap = TagRankPosterRenderer.render(
            data = TagRankPosterData(tags = emptyList(), totalWorks = 0, distinctTagCount = 0),
            copy = TagRankPosterCopy.from(context, 1).copy(locale = Locale.CHINA),
            typography = sampleTypography(context),
        )
        try {
            assertEquals(1080, bitmap.width)
            assertTrue(bitmap.height > 0)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `dialog layout inflates with every poster view`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_tag_rank_poster, null)
        assertNotNull(view.findViewById(R.id.tag_poster_panel))
        assertNotNull(view.findViewById(R.id.tag_poster_title))
        assertNotNull(view.findViewById(R.id.tag_poster_close))
        assertNotNull(view.findViewById(R.id.tag_poster_preview_frame))
        assertNotNull(view.findViewById(R.id.tag_poster_preview))
        assertNotNull(view.findViewById(R.id.tag_poster_loading))
        assertNotNull(view.findViewById(R.id.tag_poster_loading_label))
        assertNotNull(view.findViewById(R.id.tag_poster_error))
        assertNotNull(view.findViewById(R.id.tag_poster_switch_account))
        assertNotNull(view.findViewById(R.id.tag_poster_switch_avatar))
        assertNotNull(view.findViewById(R.id.tag_poster_count_label))
        assertNotNull(view.findViewById(R.id.tag_poster_count_6))
        assertNotNull(view.findViewById(R.id.tag_poster_count_20))
        assertNotNull(view.findViewById(R.id.tag_poster_actions))
    }

    @Test
    fun `payload survives a json round trip`() {
        val payload = TagRankPosterPayload(
            tags = SAMPLE_TAGS,
            totalWorks = 4_218,
            distinctTagCount = 316,
            userName = "水上优择",
            userAccount = "minakami",
            userId = 12345678L,
            avatarUrl = "https://i.pximg.net/user-profile/img/avatar.png",
            tagCount = TagRankPosterData.TAG_COUNT_20,
        )
        val restored = TagRankPosterPayload.fromJson(payload.toJson())
        assertEquals(payload.totalWorks, restored.totalWorks)
        assertEquals(payload.distinctTagCount, restored.distinctTagCount)
        assertEquals(payload.userName, restored.userName)
        assertEquals(payload.userAccount, restored.userAccount)
        assertEquals(payload.userId, restored.userId)
        assertEquals(payload.avatarUrl, restored.avatarUrl)
        assertEquals(TagRankPosterData.TAG_COUNT_20, restored.tagCount)
        assertEquals(payload.tags, restored.tags)
    }

    @Test
    fun `broken payload json degrades to empty payload instead of crashing`() {
        val restored = TagRankPosterPayload.fromJson("{not-json")
        assertTrue(restored.tags.isEmpty())
        assertNull(TagRankPosterPayload.fromJson(null).tags.firstOrNull())
    }

    /**
     * 回归：保存/分享必须用独占快照 —— 导出压缩期间用户切换 TOP 6/20、账号开关或关闭弹窗，
     * 预览 Bitmap 会被 recycle；正在压缩的导出图不能跟着失效。
     */
    @Test
    fun `export snapshot survives preview bitmap being recycled`() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val snapshot = requireNotNull(TagRankPosterExporter.snapshotForExport(source)) {
            "导出快照应创建成功"
        }
        try {
            assertNotSame("快照必须是独立的 Bitmap 实例", source, snapshot)
            source.recycle()
            assertFalse("源预览图回收后快照仍可用", snapshot.isRecycled)
            // 导出路径只碰快照：源图已回收，快照仍应能正常编码。
            val output = java.io.ByteArrayOutputStream()
            assertTrue(
                "源预览图回收后快照仍应能压缩",
                snapshot.compress(Bitmap.CompressFormat.PNG, 100, output),
            )
            assertTrue(output.size() > 0)
        } finally {
            if (!source.isRecycled) source.recycle()
            if (!snapshot.isRecycled) snapshot.recycle()
        }
    }

    @Test
    fun `export snapshot is null when there is no preview`() {
        assertNull(TagRankPosterExporter.snapshotForExport(null))
    }

    // ───────────────────────────────────────────────────────────────
    // 测试数据与工具
    // ───────────────────────────────────────────────────────────────

    private fun bitmapPng(bitmap: Bitmap, stream: java.io.OutputStream): Boolean =
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)

    private fun hasVisualVariety(bitmap: Bitmap): Boolean {
        val points = listOf(
            12 to 12,
            bitmap.width / 2 to bitmap.height / 2,
            bitmap.width - 20 to bitmap.height - 20,
            bitmap.width / 2 to bitmap.height / 4,
            bitmap.width / 2 to (bitmap.height * 3) / 4,
        )
        val colors = points.map { (x, y) -> bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1)) }
        return colors.toSet().size > 1
    }

    /**
     * 样张专用字体：正常用 [TagRankPosterTypography.from]；若当前 Robolectric 图形栈没有中文
     * 字形（`hasGlyph` 判据），尝试加载宿主机器上的中文字体，让样张能看清排版而不是一堆方框。
     */
    private fun sampleTypography(context: Context): TagRankPosterTypography {
        val base = TagRankPosterTypography.from(context)
        val probe = Paint().apply { typeface = base.cjkBold }
        if (probe.hasGlyph("收")) return base
        val hostFont = HOST_CJK_FONTS.map(::File).firstOrNull { it.isFile } ?: return base
        val cjk = runCatching { Typeface.createFromFile(hostFont) }.getOrNull() ?: return base
        return base.copy(cjkBold = cjk, cjkMedium = cjk)
    }

    /** 合成一张方形头像，验证圆形裁剪与账号卡排版（样张不需要真实网络）。 */
    private fun syntheticAvatar(): Bitmap {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                Color.rgb(93, 185, 255), Color.rgb(255, 92, 158),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        val initial = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 120f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        canvas.drawText("S", size / 2f, size * 0.68f, initial)
        return bitmap
    }

    private companion object {
        private val REPORT_DIR = File("build/reports")
        private val SAMPLE_TAGS = listOf(
            AllBookmarkTag("original", "original", "原创", 1_286),
            AllBookmarkTag("女の子", "女の子", "女孩", 942),
            AllBookmarkTag("イラスト", "イラスト", "插画", 731),
            AllBookmarkTag("风景", "风景", "风景", 468),
            AllBookmarkTag("fate", "Fate", "Fate", 305),
            AllBookmarkTag("落書き", "落書き", "涂鸦", 168),
        )

        /** TOP 20 样张用的 20 个假标签（含长标签/纯英文/日文混排，覆盖 ellipsize 与紧凑版式）。 */
        private val SAMPLE_TAGS_20 = listOf(
            AllBookmarkTag("original", "original", "原创", 1_286),
            AllBookmarkTag("女の子", "女の子", "女孩", 942),
            AllBookmarkTag("イラスト", "イラスト", "插画", 731),
            AllBookmarkTag("风景", "风景", "风景", 468),
            AllBookmarkTag("fate", "Fate", "Fate", 305),
            AllBookmarkTag("落書き", "落書き", "涂鸦", 168),
            AllBookmarkTag("clamp", "CLAMP", "CLAMP", 154),
            AllBookmarkTag("初音ミク", "初音ミク", "初音未来", 141),
            AllBookmarkTag("百合", "百合", "百合", 132),
            AllBookmarkTag("アニメ", "アニメ", "动画", 121),
            AllBookmarkTag("東方Project", "東方Project", "东方Project", 113),
            AllBookmarkTag("fantasy", "fantasy", "奇幻", 98),
            AllBookmarkTag("風景画", "風景画", "风景画", 87),
            AllBookmarkTag("女の子イラスト", "女の子イラスト", "女孩插画", 76),
            AllBookmarkTag("漫画", "漫画", "漫画", 65),
            AllBookmarkTag("sketch", "sketch", "速写", 54),
            AllBookmarkTag("原创角色", "原创角色", "原创角色", 43),
            AllBookmarkTag("ポートレート", "ポートレート", "肖像", 32),
            AllBookmarkTag("watercolor", "watercolor", "水彩", 21),
            AllBookmarkTag("背景", "背景", "背景", 10),
        )

        /** 真机不会用到：只在 Robolectric 缺 CJK 字形时借宿主机器的字体画样张。 */
        private val HOST_CJK_FONTS = listOf(
            "C:/Windows/Fonts/SourceHanSansJP-Bold.otf",
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/Deng.ttf",
            "C:/Windows/Fonts/simsun.ttc",
        )
    }
}
