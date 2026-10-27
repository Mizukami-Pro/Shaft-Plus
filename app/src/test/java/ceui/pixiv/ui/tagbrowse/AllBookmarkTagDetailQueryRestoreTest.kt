package ceui.pixiv.ui.tagbrowse

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import ceui.pixiv.db.mirror.AllBookmarkAgeFilter
import ceui.pixiv.db.mirror.AllBookmarkAiFilter
import ceui.pixiv.db.mirror.AllBookmarkPageFilter
import ceui.pixiv.db.mirror.AllBookmarkRatioFilter
import ceui.pixiv.db.mirror.AllBookmarkResolutionFilter
import ceui.pixiv.db.mirror.AllBookmarkSort
import ceui.pixiv.db.mirror.AllBookmarkWorkFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 进程死亡恢复（task-10）：标签详情页的完整查询条件 —— 搜索词 / 排序 / 作品类别 / 筛选面板
 * 8 项 —— 必须存进 [SavedStateHandle]，并在 [AllBookmarkTagDetailViewModel] **构造期**恢复。
 *
 * 不复现这条恢复链的后果：进程被系统回收后重建，列表按默认条件查询，而筛选面板
 * （DialogFragment 的 draft 由它自己的 saved state 恢复）仍显示用户上次选的条件 —— 屏幕
 * 上下两处表达的条件不一致，用户以为筛选失效。
 *
 * 测试覆盖：
 * - 生产写入口（[AllBookmarkTagDetailViewModel.setKeyword] / [AllBookmarkTagDetailViewModel.setSort] /
 *   [AllBookmarkTagDetailViewModel.setWorkType] / [AllBookmarkTagDetailViewModel.applyPanelFilter]）
 *   → 模拟进程死亡重建 → 逐字段还原；
 * - 恢复后的 VM 在 `resolvedFilter()`（FeedSource 第一次查询读的口）上已带着标签名注入 +
 *   旧条件，即「首次查询前恢复」；
 * - 缺省 / 损坏 / 类型不符的 saved state 回退默认值、不崩；
 * - 旋转（同一 VM 实例，不经过 SavedStateHandle 往返）行为不回退。
 *
 * ⚠️ 「SaveState 的 Bundle 序列化」不直接手写：`SavedStateHandle.set` 在写入时就按
 * Bundle 可存类型做校验（塞入不支持的类型会立刻抛 IllegalArgumentException），所以这里
 * 只要通过生产写入口写一遍、再按 keys 搬到新 handle，就等价于 Bundle 往返后的重建。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AllBookmarkTagDetailQueryRestoreTest {

    /** 一套「每个维度都偏离默认值」的条件，任何一页漏存都会让某个断言失败。 */
    private fun fullQuery(): AllBookmarkTagQuery = AllBookmarkTagQuery(
        sort = AllBookmarkSort.TRENDING,
        filter = AllBookmarkWorkFilter(
            keyword = "初音 ミク",
            workTypes = AllBookmarkWorkType.NOVEL.workTypes,
            createdFromMs = 1_700_000_000_000L,
            createdToMs = 1_800_000_000_000L,
            minBookmarks = 1000,
            maxBookmarks = 2999,
            usersTagMin = 500,
            ai = AllBookmarkAiFilter.ONLY_AI,
            age = AllBookmarkAgeFilter.R18_ONLY,
            ratio = AllBookmarkRatioFilter.PORTRAIT,
            resolution = AllBookmarkResolutionFilter.ABOVE_3000,
            pages = AllBookmarkPageFilter.MULTI_PAGE,
        ),
    )

    /**
     * 模拟「进程死亡 → 系统用 saved Bundle 重建」：把 handle 里全部可存储键值搬到新 handle。
     * 值本身都是 Bundle 原生类型（String / Long / Int），不做任何转换。
     */
    private fun recreateAfterProcessDeath(handle: SavedStateHandle): SavedStateHandle {
        val snapshot = handle.keys().associateWith { key -> handle.get<Any?>(key) }
        return SavedStateHandle(snapshot)
    }

    @Test
    fun `restores complete query after process death`() {
        val handle = SavedStateHandle()
        val vm = AllBookmarkTagDetailViewModel(handle)
        // 走生产写入口：搜索框 / 类别 chips / 排序 chips / 筛选面板。
        assertTrue(vm.setKeyword("初音 ミク"))
        assertTrue(vm.setWorkType(AllBookmarkWorkType.NOVEL))
        assertTrue(vm.setSort(AllBookmarkSort.TRENDING))
        val patch = AllBookmarkTagFilterPatch.of(fullQuery().filter)
        assertTrue(vm.applyPanelFilter(patch))

        val restored = AllBookmarkTagDetailViewModel(recreateAfterProcessDeath(handle))

        // 整对象相等 + 逐字段（失败时能一眼看出掉的是哪一维）。
        assertEquals(fullQuery(), restored.query.value)
        assertEquals(AllBookmarkSort.TRENDING, restored.query.value.sort)
        assertEquals(AllBookmarkWorkType.NOVEL, restored.query.value.type)
        assertEquals("初音 ミク", restored.query.value.keyword)
        assertEquals(AllBookmarkWorkType.NOVEL.workTypes, restored.query.value.filter.workTypes)
        assertEquals(1_700_000_000_000L, restored.query.value.filter.createdFromMs)
        assertEquals(1_800_000_000_000L, restored.query.value.filter.createdToMs)
        assertEquals(1000, restored.query.value.filter.minBookmarks)
        assertEquals(2999, restored.query.value.filter.maxBookmarks)
        assertEquals(500, restored.query.value.filter.usersTagMin)
        assertEquals(AllBookmarkAiFilter.ONLY_AI, restored.query.value.filter.ai)
        assertEquals(AllBookmarkAgeFilter.R18_ONLY, restored.query.value.filter.age)
        assertEquals(AllBookmarkRatioFilter.PORTRAIT, restored.query.value.filter.ratio)
        assertEquals(
            AllBookmarkResolutionFilter.ABOVE_3000, restored.query.value.filter.resolution,
        )
        assertEquals(AllBookmarkPageFilter.MULTI_PAGE, restored.query.value.filter.pages)
    }

    /**
     * 首次 FeedSource 查询前恢复：[ceui.pixiv.ui.tagbrowse.AllBookmarkTagDetailFeedSource.load]
     * 只读 `resolvedFilter()` / `resolvedSort()`，FeedViewModel 构造后立刻发起首屏 load。恢复后的
     * VM 在这两个口上必须已经带着旧条件，页面不需要（也不应该）先按默认条件查一遍再纠正。
     */
    @Test
    fun `restored query is already visible through feed source read accessors`() {
        val handle = SavedStateHandle()
        val vm = AllBookmarkTagDetailViewModel(handle)
        vm.setKeyword("初音 ミク")
        vm.setWorkType(AllBookmarkWorkType.NOVEL)
        vm.setSort(AllBookmarkSort.TRENDING)
        vm.applyPanelFilter(AllBookmarkTagFilterPatch.of(fullQuery().filter))

        val restored = AllBookmarkTagDetailViewModel(recreateAfterProcessDeath(handle))
        // 页面固定标签名由 bind 注入。uid=0 让 refreshCount 走「无可查范围」短路（不起协程），
        // 单测因此不需要主线程调度器 / 数据层。
        restored.bind(0L, "#初音ミク", includePrivate = true)

        val filter = restored.resolvedFilter()
        assertEquals("初音ミク", filter.tagName)
        assertEquals("初音 ミク", filter.keyword)
        assertEquals(AllBookmarkWorkType.NOVEL.workTypes, filter.workTypes)
        assertEquals(1000, filter.minBookmarks)
        assertEquals(2999, filter.maxBookmarks)
        assertEquals(1_700_000_000_000L, filter.createdFromMs)
        assertEquals(1_800_000_000_000L, filter.createdToMs)
        assertEquals(500, filter.usersTagMin)
        assertEquals(AllBookmarkAiFilter.ONLY_AI, filter.ai)
        assertEquals(AllBookmarkAgeFilter.R18_ONLY, filter.age)
        assertEquals(AllBookmarkRatioFilter.PORTRAIT, filter.ratio)
        assertEquals(AllBookmarkResolutionFilter.ABOVE_3000, filter.resolution)
        assertEquals(AllBookmarkPageFilter.MULTI_PAGE, filter.pages)
        assertEquals(AllBookmarkSort.TRENDING, restored.resolvedSort())
    }

    @Test
    fun `restores defaults when nothing was saved`() {
        val vm = AllBookmarkTagDetailViewModel(SavedStateHandle())
        assertEquals(AllBookmarkTagQuery(), vm.query.value)
        assertEquals(AllBookmarkSort.NEWEST_PUBLISHED, vm.query.value.sort)
        assertEquals(AllBookmarkWorkType.ALL, vm.query.value.type)
        assertEquals(0, vm.query.value.panelConditionCount)
    }

    /** 版本降级 / 字段改名 / 外部手改 saved state：一律回退默认值，绝不抛。 */
    @Test
    fun `unknown or corrupted saved values fall back to defaults`() {
        val corrupted = SavedStateHandle(
            mapOf<String, Any>(
                AllBookmarkTagDetailViewModel.KEY_SORT to "NO_SUCH_SORT",
                AllBookmarkTagDetailViewModel.KEY_WORK_TYPE to 42,        // 类型都不对
                AllBookmarkTagDetailViewModel.KEY_AI to "BROKEN",
                AllBookmarkTagDetailViewModel.KEY_RATIO to true,
                AllBookmarkTagDetailViewModel.KEY_CREATED_FROM to "not-a-long",
                AllBookmarkTagDetailViewModel.KEY_MIN_BOOKMARKS to "bad",
                AllBookmarkTagDetailViewModel.KEY_USERS_TAG_MIN to -5,    // 负值要钳到 0
            ),
        )
        val query = AllBookmarkTagDetailViewModel(corrupted).query.value
        assertEquals(AllBookmarkSort.NEWEST_PUBLISHED, query.sort)
        assertEquals(AllBookmarkWorkType.ALL, query.type)
        assertEquals(AllBookmarkAiFilter.ANY, query.filter.ai)
        assertEquals(AllBookmarkRatioFilter.ANY, query.filter.ratio)
        assertNull(query.filter.createdFromMs)
        assertNull(query.filter.minBookmarks)
        assertEquals(0, query.filter.usersTagMin)
    }

    /** 可空区间「无限制」必须用哨兵表达并在恢复后还原为 null（不能变成 Long.MIN_VALUE）。 */
    @Test
    fun `cleared ranges restore as unrestricted`() {
        val handle = SavedStateHandle()
        val vm = AllBookmarkTagDetailViewModel(handle)
        vm.setKeyword("背景")
        vm.setWorkType(AllBookmarkWorkType.ILLUST)
        // 面板条件全空 = 之前有过的区间被「重置」清零。
        vm.applyPanelFilter(AllBookmarkTagFilterPatch.CLEARED)

        val restored = AllBookmarkTagDetailViewModel(recreateAfterProcessDeath(handle))
        assertNull(restored.query.value.filter.createdFromMs)
        assertNull(restored.query.value.filter.createdToMs)
        assertNull(restored.query.value.filter.minBookmarks)
        assertNull(restored.query.value.filter.maxBookmarks)
        assertEquals(0, restored.query.value.filter.usersTagMin)
        assertEquals(0, restored.query.value.panelConditionCount)
        // workType 与搜索词不属于面板「重置」范围，恢复后仍在。
        assertEquals(AllBookmarkWorkType.ILLUST, restored.query.value.type)
        assertEquals("背景", restored.query.value.keyword)
    }

    /**
     * 旋转回归：配置变更复用同一个 VM 实例，条件靠内存字段保留，不能被 SavedStateHandle
     * 恢复逻辑覆盖回默认值。
     */
    @Test
    fun `rotation keeps conditions without saved state round trip`() {
        val vm = AllBookmarkTagDetailViewModel(SavedStateHandle())
        assertTrue(vm.setSort(AllBookmarkSort.MOST_VIEWED))
        assertTrue(vm.setWorkType(AllBookmarkWorkType.MANGA))
        assertTrue(vm.setKeyword("ねこ"))
        assertEquals(AllBookmarkSort.MOST_VIEWED, vm.query.value.sort)
        assertEquals(AllBookmarkWorkType.MANGA, vm.query.value.type)
        assertEquals("ねこ", vm.query.value.keyword)
    }
}
