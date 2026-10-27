package ceui.pixiv.ui.novel.reader.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.SeekBar
import ceui.lisa.R
import ceui.lisa.databinding.LayoutReaderBottomBarBinding

class ReaderBottomBar(private val binding: LayoutReaderBottomBarBinding) {

    enum class Mode { Paged, VerticalScroll }

    /**
     * 一键翻译按钮的状态。文案由 [setTranslateState] 换算；Fragment 只负责把
     * [ceui.pixiv.ui.novel.reader.NovelReaderV3ViewModel.TranslationState] 翻成这里的状态。
     */
    enum class TranslateState { Idle, Running, Failed, Ready }

    val view: View get() = binding.root

    var onPrevChapter: (() -> Unit)? = null
    var onNextChapter: (() -> Unit)? = null
    var onChaptersClick: (() -> Unit)? = null
    var onSeriesClick: (() -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null
    var onThemeToggleClick: (() -> Unit)? = null
    var onSearchClick: (() -> Unit)? = null
    /** 底部栏「翻译」:未翻译直接开翻,已有译文打开显示方式,翻译中提示进度。 */
    var onTranslateClick: (() -> Unit)? = null
    var onSeekStart: (() -> Unit)? = null
    /** Paged mode: drag changes — pageIndex 0..total-1. */
    var onSeekChanged: ((pageIndex: Int) -> Unit)? = null
    /** Paged mode: drag end — pageIndex 0..total-1. */
    var onSeekCommit: ((pageIndex: Int) -> Unit)? = null
    /** Vertical-scroll mode: drag end — fraction in [0f, 1f]. */
    var onScrollSeekCommit: ((fraction: Float) -> Unit)? = null

    private var suppressSeekListener = false
    private var mode: Mode = Mode.Paged

    init {
        binding.btnPrevChapter.setOnClickListener { onPrevChapter?.invoke() }
        binding.btnNextChapter.setOnClickListener { onNextChapter?.invoke() }
        binding.btnChapters.setOnClickListener { onChaptersClick?.invoke() }
        binding.btnSeries.setOnClickListener { onSeriesClick?.invoke() }
        binding.btnSettings.setOnClickListener { onSettingsClick?.invoke() }
        binding.btnThemeToggle.setOnClickListener { onThemeToggleClick?.invoke() }
        binding.btnSearch.setOnClickListener { onSearchClick?.invoke() }
        binding.btnTranslate.setOnClickListener { onTranslateClick?.invoke() }

        binding.skProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || suppressSeekListener) return
                if (mode == Mode.Paged) onSeekChanged?.invoke(progress)
                // VerticalScroll mode emits only on commit — live drag would jitter the
                // ScrollView and cause re-layout storms.
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                onSeekStart?.invoke()
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                if (mode == Mode.Paged) {
                    onSeekCommit?.invoke(s.progress)
                } else {
                    val max = s.max.coerceAtLeast(1)
                    onScrollSeekCommit?.invoke(s.progress.toFloat() / max)
                }
            }
        })
    }

    fun setProgress(currentPage: Int, totalPages: Int) {
        mode = Mode.Paged
        val totalForBar = (totalPages - 1).coerceAtLeast(0)
        suppressSeekListener = true
        try {
            binding.skProgress.max = totalForBar
            binding.skProgress.progress = currentPage.coerceIn(0, totalForBar)
        } finally {
            suppressSeekListener = false
        }
        binding.txtProgress.text = if (totalPages == 0) {
            binding.root.context.getString(ceui.lisa.R.string.reader_progress_empty)
        } else {
            binding.root.context.getString(ceui.lisa.R.string.reader_progress_format, currentPage + 1, totalPages)
        }
    }

    /**
     * Vertical-scroll mode: keep the seekbar in sync with overall scroll progress.
     * Uses a fixed [SCROLL_MAX] resolution so a single pixel of drag corresponds
     * to a sensible fraction even on tall novels. Text shows "NN%".
     */
    fun setScrollProgress(fraction: Float) {
        mode = Mode.VerticalScroll
        val clamped = fraction.coerceIn(0f, 1f)
        suppressSeekListener = true
        try {
            binding.skProgress.max = SCROLL_MAX
            binding.skProgress.progress = (clamped * SCROLL_MAX).toInt()
        } finally {
            suppressSeekListener = false
        }
        val pct = (clamped * 100).toInt().coerceIn(0, 100)
        binding.txtProgress.text = "$pct%"
    }

    /** 当前小说有所属系列时调用 setSeriesVisible(true)，唤起 SeriesListSheet 切换其它单篇。 */
    fun setSeriesVisible(visible: Boolean) {
        binding.btnSeries.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setDarkMode(dark: Boolean) {
        binding.txtThemeToggle.text = binding.root.context.getString(
            if (dark) ceui.lisa.R.string.reader_btn_theme_day else ceui.lisa.R.string.reader_btn_theme_night,
        )
    }

    /**
     * 刷新「翻译」按钮的文案与强调色。
     *
     * - [TranslateState.Idle]：普通白色「翻译」；
     * - [TranslateState.Running]：强调色 + 「翻译中 45%」进度（[total] 为 0 时只显示「翻译中」）；
     * - [TranslateState.Failed]：强调色「继续翻译」（点击从断点续翻）；
     * - [TranslateState.Ready]：强调色「译文」（点击打开译文显示方式）。
     *
     * 底栏其余按钮是纯白/浅白，翻译按钮一旦有状态就换强调色，让「已经翻过」这件事在
     * 收起顶栏、只开底栏时也一眼可见（#T6）。
     */
    fun setTranslateState(state: TranslateState, done: Int = 0, total: Int = 0) {
        val ctx = binding.root.context
        val label = when (state) {
            TranslateState.Idle -> ctx.getString(R.string.reader_upgrade_translate_btn)
            TranslateState.Running ->
                if (total > 0) {
                    // 百分比而不是 x/y：底栏按钮等分宽度，长篇的四位数段数会把文案挤到省略号。
                    ctx.getString(R.string.reader_upgrade_translate_progress, (done * 100 / total).coerceIn(0, 99))
                } else {
                    ctx.getString(R.string.reader_upgrade_translate_running)
                }
            TranslateState.Failed -> ctx.getString(R.string.reader_upgrade_translate_failed)
            TranslateState.Ready -> ctx.getString(R.string.reader_upgrade_translate_ready)
        }
        val accent = state != TranslateState.Idle
        binding.txtTranslate.text = label
        binding.txtTranslate.setTextColor(if (accent) ACCENT else IDLE_TEXT)
        binding.imgTranslate.imageTintList = ColorStateList.valueOf(if (accent) ACCENT else Color.WHITE)
        binding.btnTranslate.contentDescription = label
    }

    private companion object {
        // Resolution for the scroll-mode seekbar — fine enough that a 1-pixel
        // drag of the thumb still produces a meaningful jump on a long novel.
        const val SCROLL_MAX = 1000

        /** 底栏是 #EE1F1F1F 的深色半透明条，暖金色在深底上对比度足够、又不抢主题色。 */
        const val ACCENT = 0xFFFFC857.toInt()

        /** 与 XML 里其它底栏文字一致的浅白。 */
        const val IDLE_TEXT = 0xCCFFFFFF.toInt()
    }
}
