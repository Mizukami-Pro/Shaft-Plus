package ceui.pixiv.ui.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #T9：详情页在外部切换「原图 / 预览图」偏好后，回到本页要不要按新清晰度重取的判定。
 *
 * 平板舞台（[ArtworkTabletStage.syncOriginalMode]）与手机列表 adapter
 * （`ArtworkV3Fragment.syncOriginalModeFromSettings`）共用这一份语义：
 * - 首次渲染（rendered == null）不重取，交给 bind / adapter 自己按当前偏好出图；
 * - 模式没变不重取，避免每次 onResume 都白闪一次；
 * - 模式变了必须重取，否则画面会停在旧分辨率（平板下舞台原本永远不重取就是这个坑）。
 */
class OriginalModeReloadTest {

    @Test
    fun `first render does not trigger an extra reload`() {
        assertFalse(shouldReloadPagesForOriginalMode(rendered = null, current = true))
        assertFalse(shouldReloadPagesForOriginalMode(rendered = null, current = false))
    }

    @Test
    fun `same mode is a no-op`() {
        assertFalse(shouldReloadPagesForOriginalMode(rendered = true, current = true))
        assertFalse(shouldReloadPagesForOriginalMode(rendered = false, current = false))
    }

    @Test
    fun `changed mode must be reloaded`() {
        assertTrue(shouldReloadPagesForOriginalMode(rendered = true, current = false))
        assertTrue(shouldReloadPagesForOriginalMode(rendered = false, current = true))
    }
}
