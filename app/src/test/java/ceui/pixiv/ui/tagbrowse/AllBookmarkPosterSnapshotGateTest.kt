package ceui.pixiv.ui.tagbrowse

import ceui.pixiv.db.mirror.AllBookmarkStats
import ceui.pixiv.db.mirror.AllBookmarkTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [AllBookmarkPosterSnapshotGate] 的回归测试：海报数据必须与「账号 × 收藏范围」绑定。
 *
 * 覆盖 task-8 要求的全部语义：
 * 1. **切范围立即失效**：includePrivate 一变，旧快照立刻取不到，不等新查询回来；
 * 2. **旧范围不可提交**：读取途中切范围/换账号，迟到的旧结果提交被拒绝，旧私密数据不会回流；
 * 3. **失败/延迟**：切换后查询失败（不提交）期间海报保持不可用，重试成功提交后才恢复；
 * 4. **原子性**：tags + stats 必须是同一次 commit 的一整批数据，不能一新一旧。
 *
 * 纯 JVM 测试，不依赖 Robolectric/Android：门闸本身没有 Android 依赖，VM 只负责在
 * 范围变化处调用 gate（见 `AllBookmarkTagOverviewViewModel`）。
 */
class AllBookmarkPosterSnapshotGateTest {

    private fun tag(name: String, count: Int = 1): AllBookmarkTag = AllBookmarkTag(
        tagName = name.lowercase(Locale.ROOT),
        displayName = name,
        translatedName = "",
        count = count,
    )

    private fun stats(total: Int, tagCount: Int = 1): AllBookmarkStats = AllBookmarkStats(
        totalWorks = total,
        illustCount = total,
        mangaCount = 0,
        novelCount = 0,
        distinctTagCount = tagCount,
    )

    private val privateTags = listOf(tag("初音ミク", 23), tag("悄悄收藏专用", 2))
    private val publicTags = listOf(tag("初音ミク", 21))
    private val privateStats = stats(total = 126, tagCount = 34)
    private val publicStats = stats(total = 80, tagCount = 20)

    @Test
    fun `初始状态没有可导出的快照`() {
        val gate = AllBookmarkPosterSnapshotGate()
        assertNull(gate.snapshot())
        assertEquals(0L, gate.currentScope.uid)
        assertTrue(gate.currentScope.includePrivate)
    }

    @Test
    fun `提交后能取到绑定当前范围的快照`() {
        val gate = AllBookmarkPosterSnapshotGate()
        assertTrue(gate.updateScope(uid = 12345L, includePrivate = true))
        assertTrue(gate.commit(12345L, true, privateTags, privateStats))

        val snapshot = gate.snapshot()
        assertNotNull(snapshot)
        assertEquals(12345L, snapshot!!.uid)
        assertTrue(snapshot.includePrivate)
        assertSame(privateTags, snapshot.tags)
        assertSame(privateStats, snapshot.stats)
    }

    @Test
    fun `切到仅公开范围立即撤销旧私密快照`() {
        val gate = AllBookmarkPosterSnapshotGate()
        gate.updateScope(12345L, true)
        assertTrue(gate.commit(12345L, true, privateTags, privateStats))
        assertNotNull(gate.snapshot())

        // 用户关掉「包含私密收藏」：不等新查询回来，旧快照必须先失效。
        assertTrue(gate.updateScope(12345L, false))
        assertNull("切换范围后旧快照必须立刻不可用", gate.snapshot())
        assertFalse(gate.isCurrentScope(12345L, true))
    }

    @Test
    fun `旧范围的迟到提交被拒绝且不覆盖当前快照`() {
        val gate = AllBookmarkPosterSnapshotGate()
        gate.updateScope(12345L, true)
        assertTrue(gate.commit(12345L, true, privateTags, privateStats))

        // 切到仅公开，接着旧范围的查询结果迟到了。
        gate.updateScope(12345L, false)
        assertFalse("旧范围（含私密）的结果不能在切范围后提交", gate.commit(12345L, true, privateTags, privateStats))
        assertNull(gate.snapshot())

        // 新范围的完整数据到达后才恢复；快照里不能残留私密标签。
        assertTrue(gate.commit(12345L, false, publicTags, publicStats))
        val snapshot = gate.snapshot()
        assertNotNull(snapshot)
        assertFalse(snapshot!!.includePrivate)
        assertSame(publicTags, snapshot.tags)
        assertSame(publicStats, snapshot.stats)
        assertFalse(snapshot.tags.any { it.displayName == "悄悄收藏专用" })
    }

    @Test
    fun `把私密范围重新打开时旧公开快照同样立刻失效`() {
        val gate = AllBookmarkPosterSnapshotGate()
        gate.updateScope(12345L, false)
        assertTrue(gate.commit(12345L, false, publicTags, publicStats))
        assertNotNull(gate.snapshot())

        assertTrue(gate.updateScope(12345L, true))
        assertNull(gate.snapshot())
        assertFalse(gate.commit(12345L, false, publicTags, publicStats))
        assertTrue(gate.commit(12345L, true, privateTags, privateStats))
        assertTrue(gate.snapshot()!!.includePrivate)
    }

    @Test
    fun `换账号会撤销快照并拒绝旧账号结果`() {
        val gate = AllBookmarkPosterSnapshotGate()
        gate.updateScope(uid = 111L, includePrivate = false)
        assertTrue(gate.commit(111L, false, publicTags, publicStats))
        assertNotNull(gate.snapshot())

        assertTrue(gate.updateScope(uid = 222L, includePrivate = false))
        assertNull(gate.snapshot())
        assertFalse("旧账号的查询结果不能提交到新账号", gate.commit(111L, false, publicTags, publicStats))
        assertNull(gate.snapshot())

        assertTrue(gate.commit(222L, false, publicTags, publicStats))
        assertEquals(222L, gate.snapshot()!!.uid)
    }

    @Test
    fun `切换后查询失败期间海报保持不可用重试成功才恢复`() {
        val gate = AllBookmarkPosterSnapshotGate()
        gate.updateScope(12345L, true)
        assertTrue(gate.commit(12345L, true, privateTags, privateStats))

        // 切到仅公开；新查询失败 —— 只调用了 updateScope，没有任何 commit。
        gate.updateScope(12345L, false)
        assertNull("查询失败/延迟期间不得回退到旧快照", gate.snapshot())

        // 重试失败也不会把旧快照放回来。
        assertNull(gate.snapshot())

        // 重试成功、完整数据提交后才恢复。
        assertTrue(gate.commit(12345L, false, publicTags, publicStats))
        assertNotNull(gate.snapshot())
    }

    @Test
    fun `同一范围重复对齐不会清掉海报资格`() {
        val gate = AllBookmarkPosterSnapshotGate()
        gate.updateScope(12345L, false)
        assertTrue(gate.commit(12345L, false, publicTags, publicStats))
        val before = gate.snapshot()

        // 例如页面回到前台 / 反复 onResume：范围没变，什么都不该发生。
        assertFalse(gate.updateScope(12345L, false))
        assertFalse(gate.updateScope(12345L, false))
        assertSame(before, gate.snapshot())
    }

    @Test
    fun `同范围主动失效后重读可恢复`() {
        val gate = AllBookmarkPosterSnapshotGate()
        gate.updateScope(12345L, true)
        assertTrue(gate.commit(12345L, true, privateTags, privateStats))

        gate.invalidate()
        assertNull(gate.snapshot())
        // 范围没变，重读成功的同一批数据可以重新提交。
        assertTrue(gate.commit(12345L, true, privateTags, privateStats))
        assertNotNull(gate.snapshot())
    }

    @Test
    fun `快照的标签与统计必须是同一次提交的整批数据`() {
        val gate = AllBookmarkPosterSnapshotGate()
        gate.updateScope(12345L, true)
        assertTrue(gate.commit(12345L, true, privateTags, privateStats))
        val first = gate.snapshot()!!

        // 第二批数据（比如维护后又读了一次）整体替换，不能出现「新标签 + 旧统计」。
        val newerTags = listOf(tag("新しいタグ", 9))
        val newerStats = stats(total = 130, tagCount = 35)
        assertTrue(gate.commit(12345L, true, newerTags, newerStats))
        val second = gate.snapshot()!!
        assertSame(newerTags, second.tags)
        assertSame(newerStats, second.stats)
        assertEquals(130, second.stats.totalWorks)
        assertEquals(35, second.stats.distinctTagCount)
        // 旧快照对象不共享引用，避免调用方改到已撤销的数据。
        assertFalse(first === second)
    }
}
