package ceui.pixiv.ui.tagbrowse

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import ceui.lisa.R
import ceui.lisa.databinding.RecyAllBookmarkTagBinding
import ceui.lisa.databinding.RecyAllBookmarkTagHeaderBinding
import ceui.pixiv.db.mirror.AllBookmarkStats
import ceui.pixiv.db.mirror.AllBookmarkTag
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.card
import ceui.pixiv.witstudio.theme.color
import java.util.Locale

/** 千分位数字：「1,203」。列表计数与统计头共用同一套格式化，避免两处写法漂移。 */
internal fun formatTagCount(value: Int): String =
    String.format(Locale.getDefault(), "%,d", value)

/**
 * 「全收藏分类」标签云列表的 adapter（**不是** feeds 框架：本页是一份有界的本地标签集合，
 * 没有分页 / 骨架 / 下拉追加，普通 RecyclerView 更直接可靠）。
 *
 * 列表第 0 项固定是统计头 [Header]（统计 + 同步状态 + 手动刷新），之后才是标签行。
 * 统计头随列表滚走；搜索框和私密开关是操作，留在布局里常驻。
 *
 * 更新方式刻意用 `notifyDataSetChanged()`：镜像回填期间每 500ms 会整份换一次列表，
 * DiffUtil 的开销与收益在这种「几百到几千行、可见项只有十来个」的场景下并不划算，
 * 而整套替换必须保证「名字 / 数量 / 名次」一起换，不能出现半新半旧的行。
 */
class AllBookmarkTagRowAdapter(
    private val onTagClick: (AllBookmarkTag) -> Unit,
    private val onRefreshClick: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** 统计头的全部数据。标签总数用全量（[tagCount]），不受搜索影响。 */
    data class Header(
        val stats: AllBookmarkStats,
        val tagCount: Int,
        val syncing: Boolean,
        val allSynced: Boolean,
        val syncedCount: Int,
    )

    private var header: Header = Header(AllBookmarkStats(), 0, true, false, 0)
    private var tags: List<AllBookmarkTag> = emptyList()

    fun submit(header: Header, tags: List<AllBookmarkTag>) {
        this.header = header
        this.tags = tags
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = tags.size + 1

    override fun getItemViewType(position: Int): Int =
        if (position == HEADER_POSITION) TYPE_HEADER else TYPE_TAG

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val binding = RecyAllBookmarkTagHeaderBinding.inflate(inflater, parent, false)
            // 22dp 主题 tint 卡片：cardFill + 12% 主题色 hairline，运行时算，日夜与十套主题色都跟随。
            binding.statsPanel.background = parent.context.card(22)
            HeaderHolder(binding)
        } else {
            TagHolder(RecyAllBookmarkTagBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> holder.bind(header)
            is TagHolder -> {
                val index = position - 1
                if (index in tags.indices) holder.bind(tags[index], position)
            }
        }
    }

    /** 统计头：统计数字 → 类型拆分 → 标签数 / 排序说明 → 同步状态。 */
    private inner class HeaderHolder(
        private val binding: RecyAllBookmarkTagHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val context = binding.root.context
        private val palette = V3Palette.from(context)

        init {
            binding.statsRefresh.imageTintList = ColorStateList.valueOf(palette.textAccent)
            binding.statsRefresh.setOnClickListener { onRefreshClick() }
        }

        fun bind(header: Header) {
            val res = context.resources
            binding.statsTotal.text = formatTagCount(header.stats.totalWorks)
            binding.statsBreakdown.text = res.getString(
                R.string.tag_browse_stat_types,
                formatTagCount(header.stats.illustCount),
                formatTagCount(header.stats.mangaCount),
                formatTagCount(header.stats.novelCount),
            )
            // 统计里的去重标签数偶尔会比本地标签表晚一拍（同一轮查询、不同语句），
            // 取两者大的那个，避免「列表已有 512 个标签、头上却写 0 个」。
            val tagCount = maxOf(header.tagCount, header.stats.distinctTagCount)
            binding.statsTags.text =
                res.getString(R.string.tag_browse_stat_tags, formatTagCount(tagCount))

            // 同步进行中的详细进度在列表上方的常驻横幅里，这里只给一句状态；
            // 补齐后就地报结果「已全部同步（N 件作品）」——这是用户等待整份回填的回报。
            binding.syncStatus.text = when {
                header.syncing -> res.getString(R.string.tag_browse_sync_header_syncing)
                header.allSynced -> res.getString(
                    R.string.tag_browse_sync_done_count,
                    formatTagCount(header.syncedCount),
                )
                else -> res.getString(R.string.tag_browse_sync_preparing)
            }
            // 完成态用语义绿（成功 = 绿），同步中保持次要文字色。
            binding.syncStatus.setTextColor(
                if (!header.syncing && header.allSynced) context.color(R.color.v3_green)
                else context.color(R.color.v3_text_2),
            )
            // 同步中「刷新」变淡：此时点它只会排一次增量，用户大半是在等而不是要催。
            binding.statsRefresh.alpha = if (header.syncing) 0.6f else 1f
        }
    }

    /** 标签行：名次 → 标签原文（+ 译名）→ 收藏作品数。 */
    private inner class TagHolder(
        private val binding: RecyAllBookmarkTagBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val context = binding.root.context
        private val palette = V3Palette.from(context)

        fun bind(tag: AllBookmarkTag, adapterPosition: Int) {
            val rank = adapterPosition - HEADER_POSITION
            binding.tagRank.text = String.format(Locale.US, "%02d", rank)
            binding.tagRank.setTextColor(
                if (rank <= TOP_RANK_ACCENT) palette.textAccent
                else context.color(R.color.v3_text_3),
            )

            val name = tag.displayName.ifEmpty { tag.tagName }
            binding.tagName.text = context.getString(R.string.tag_browse_row_title, name)
            val translated = tag.translatedName
            binding.tagTranslated.isVisible = translated.isNotEmpty()
            binding.tagTranslated.text = translated
            binding.tagCount.text = formatTagCount(tag.count)

            // 整行是一个读屏焦点：把「名次 + 名字 + 译名 + 件数」合成一句话，
            // 免得读屏把「01」「1,203」「件」拆成三个无意义的朗读单元。
            val spokenName = if (translated.isNotEmpty()) "$name / $translated" else name
            binding.root.contentDescription = context.getString(
                R.string.tag_browse_row_description,
                spokenName,
                formatTagCount(tag.count),
            )
            binding.root.setOnClickListener { onTagClick(tag) }
        }
    }

    private companion object {
        const val HEADER_POSITION = 0
        const val TYPE_HEADER = 0
        const val TYPE_TAG = 1

        /** 前 3 名用主题强调色点名次，与「我的Tag排行」海报的 TOP 榜呼应。 */
        const val TOP_RANK_ACCENT = 3
    }
}
