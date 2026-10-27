package ceui.pixiv.ui.tagbrowse.poster

import android.Manifest
import android.app.Dialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import ceui.lisa.R
import ceui.lisa.utils.Common
import ceui.lisa.utils.GlideUrlChild
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.dp
import ceui.pixiv.witstudio.theme.dpF
import ceui.pixiv.witstudio.theme.hairlinePx
import ceui.pixiv.witstudio.theme.pillButton
import ceui.pixiv.witstudio.theme.ripple
import ceui.pixiv.witstudio.theme.shape
import ceui.pixiv.witstudio.theme.v3Font
import com.bumptech.glide.Glide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 「我的 Tag 排行」海报的预览弹窗。
 *
 * 生命周期/重建策略：
 *  - 海报数据（标签 + 统计 + 账号文案 + 头像 URL）在 [TagRankPosterEntry] 里编成 JSON 放进
 *    arguments，旋屏或系统回收重建后仍能原样恢复，不依赖上层页面；
 *  - 「TOP 6 / TOP 20」与两个展示开关的选择进 `onSaveInstanceState`；
 *  - 位图不跨实例缓存：重建后按当前选择重新画一次（渲染只有几十毫秒，头像走 Glide 磁盘缓存）。
 *
 * 弹窗只做「预览 + 选择 + 保存/分享」，不查询镜像库、不依赖全收藏页面的 ViewModel。
 */
class TagRankPosterDialogFragment : DialogFragment(R.layout.dialog_tag_rank_poster) {

    companion object {
        internal const val TAG = "TagRankPosterDialog"

        private const val ARG_PAYLOAD = "payload_json"
        private const val STATE_SHOW_ACCOUNT = "state_show_account"
        private const val STATE_SHOW_AVATAR = "state_show_avatar"
        private const val STATE_TAG_COUNT = "state_tag_count"

        /** 头像最大解码边长：账号卡里只占 86px，256 已经足够且省内存。 */
        private const val AVATAR_DECODE_SIZE = 256
        private const val AVATAR_TIMEOUT_SECONDS = 15L

        private val FILE_TIME_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        internal fun newInstance(payload: TagRankPosterPayload): TagRankPosterDialogFragment =
            TagRankPosterDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PAYLOAD, payload.toJson())
                }
            }
    }

    private val payload: TagRankPosterPayload by lazy {
        TagRankPosterPayload.fromJson(arguments?.getString(ARG_PAYLOAD))
    }

    private var showAccount = true
    private var showAvatar = true

    /** 当前选择的排行数量：默认 TOP 6，可切 TOP 20；实际行数受 payload 里标签总数限制。 */
    private var selectedTagCount = TagRankPosterData.DEFAULT_TAG_COUNT

    private var poster: Bitmap? = null
    private var avatar: Bitmap? = null
    private var avatarRequested = false
    private var generation: Job? = null
    private var saving = false

    private lateinit var panel: LinearLayout
    private lateinit var previewFrame: FrameLayout
    private lateinit var previewImage: ImageView
    private lateinit var loadingView: View
    private lateinit var loadingLabel: TextView
    private lateinit var errorView: TextView
    private lateinit var switchAccount: SwitchCompat
    private lateinit var switchAvatar: SwitchCompat
    private lateinit var count6Option: TextView
    private lateinit var count20Option: TextView
    private lateinit var actions: LinearLayout
    private lateinit var saveButton: TextView
    private lateinit var shareButton: TextView

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            persistPoster()
        } else if (isAdded) {
            Common.showToast(getString(R.string.tag_poster_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 未登录 / 账号信息缺失时账号区没有意义：两个开关直接收起，避免「打开却画不出来」。
        val hasAccountInfo = hasAccountInfo()
        showAccount = hasAccountInfo &&
            (savedInstanceState?.getBoolean(STATE_SHOW_ACCOUNT, true) ?: true)
        showAvatar = hasAccountInfo &&
            (savedInstanceState?.getBoolean(STATE_SHOW_AVATAR, true) ?: true)
        selectedTagCount = TagRankPosterData.sanitizeTagCount(
            savedInstanceState?.getInt(STATE_TAG_COUNT, payload.tagCount) ?: payload.tagCount,
        )
    }

    private fun hasAccountInfo(): Boolean =
        payload.userName.isNotBlank() ||
            payload.userAccount.isNotBlank() ||
            payload.userId > 0L ||
            payload.avatarUrl.isNotBlank()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.64f)
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.dialog_tag_rank_poster, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        panel = view.findViewById(R.id.tag_poster_panel)
        previewFrame = view.findViewById(R.id.tag_poster_preview_frame)
        previewImage = view.findViewById(R.id.tag_poster_preview)
        loadingView = view.findViewById(R.id.tag_poster_loading)
        loadingLabel = view.findViewById(R.id.tag_poster_loading_label)
        errorView = view.findViewById(R.id.tag_poster_error)
        switchAccount = view.findViewById(R.id.tag_poster_switch_account)
        switchAvatar = view.findViewById(R.id.tag_poster_switch_avatar)
        count6Option = view.findViewById(R.id.tag_poster_count_6)
        count20Option = view.findViewById(R.id.tag_poster_count_20)
        actions = view.findViewById(R.id.tag_poster_actions)

        val context = requireContext()
        val palette = V3Palette.from(context)
        val title = view.findViewById<TextView>(R.id.tag_poster_title)
        title.typeface = context.v3Font(700)
        val close = view.findViewById<ImageButton>(R.id.tag_poster_close)
        close.imageTintList = ColorStateList.valueOf(palette.textSecondary)
        close.setOnClickListener { dismissAllowingStateLoss() }

        val progress = view.findViewById<ProgressBar>(R.id.tag_poster_loading_progress)
        progress.indeterminateTintList = ColorStateList.valueOf(palette.textAccent)
        loadingLabel.typeface = context.v3Font(500)
        errorView.typeface = context.v3Font(500)
        errorView.setTextColor(context.getColor(R.color.v3_danger))

        bindSwitches(palette)
        bindTagCountSelector(palette)
        bindActions()
        restrictPanelSize()
        applyWindowInsets(view)
        renderPoster()
    }

    /** 全屏浮层在挖孔/手势导航机上给面板留出系统栏距离，避免按钮贴到导航条。 */
    private fun applyWindowInsets(root: View) {
        val gap = requireContext().dp(16)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                bars.left + gap,
                bars.top + gap,
                bars.right + gap,
                bars.bottom + gap,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onStart() {
        super.onStart()
        // 全屏式浮层：宽度铺满再靠内边距收边，矮屏幕上高度也铺满，预览区靠 weight 自适应。
        dialog?.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SHOW_ACCOUNT, showAccount)
        outState.putBoolean(STATE_SHOW_AVATAR, showAvatar)
        outState.putInt(STATE_TAG_COUNT, selectedTagCount)
    }

    override fun onDestroyView() {
        generation?.cancel()
        generation = null
        previewImage.setImageBitmap(null)
        poster?.recycle()
        poster = null
        // 头像只服务本视图的渲染：这里只丢引用并允许下次重建后重新加载。
        // 不 recycle —— ①Bitmap 可能来自 Glide 内存缓存，回收会污染缓存；②取消 generation
        // 后仍可能有正在跑的 render 在读取它；不可达后它由 GC 回收（API 26+ 像素内存随 Bitmap 释放）。
        avatar = null
        avatarRequested = false
        super.onDestroyView()
    }

    // ───────────────────────────────────────────────────────────────
    // UI 装配
    // ───────────────────────────────────────────────────────────────

    private fun bindSwitches(palette: V3Palette) {
        val context = requireContext()
        val accountAvailable = hasAccountInfo()

        val checked = intArrayOf(android.R.attr.state_checked)
        val unchecked = intArrayOf()
        listOf(switchAccount, switchAvatar).forEach { item ->
            item.typeface = context.v3Font(500)
            item.thumbTintList = ColorStateList(
                arrayOf(checked, unchecked),
                intArrayOf(Color.WHITE, palette.textSecondary),
            )
            item.trackTintList = ColorStateList(
                arrayOf(checked, unchecked),
                intArrayOf(palette.primary, palette.alpha30),
            )
            item.isEnabled = accountAvailable
            item.alpha = if (accountAvailable) 1f else 0.42f
        }

        switchAccount.isChecked = showAccount
        switchAvatar.isChecked = showAvatar
        syncAvatarSwitchState()

        switchAccount.setOnCheckedChangeListener { _, isChecked ->
            if (showAccount == isChecked) return@setOnCheckedChangeListener
            showAccount = isChecked
            syncAvatarSwitchState()
            renderPoster()
        }
        switchAvatar.setOnCheckedChangeListener { _, isChecked ->
            if (showAvatar == isChecked) return@setOnCheckedChangeListener
            showAvatar = isChecked
            renderPoster()
        }
    }

    /** 头像依附账号卡：账号区关掉时头像开关置灰（不清空勾选，重新打开账号区即恢复）。 */
    private fun syncAvatarSwitchState() {
        // 没有下载到头像也能画首字母占位圆，所以只要有账号信息就允许开「显示头像」。
        val enabled = showAccount && hasAccountInfo()
        switchAvatar.isEnabled = enabled
        switchAvatar.alpha = if (enabled) 1f else 0.42f
    }

    /**
     * 排行数量选项：TOP 6 / TOP 20 两颗 V3 胶囊，选中为实色主色。
     *
     * 标签总数不足 7 个时 TOP 20 与 TOP 6 等价，直接置灰并强制回到 TOP 6，避免「选了没变化」。
     */
    private fun bindTagCountSelector(palette: V3Palette) {
        val context = requireContext()
        val canShowMore = payload.tags.size > TagRankPosterData.TAG_COUNT_6
        if (!canShowMore) selectedTagCount = TagRankPosterData.TAG_COUNT_6

        listOf(count6Option, count20Option).forEach { option ->
            option.typeface = context.v3Font(600)
            option.gravity = Gravity.CENTER
            option.isClickable = true
            option.isFocusable = true
            option.minHeight = context.dp(48)
            option.setPadding(context.dp(18), 0, context.dp(18), 0)
        }
        count6Option.setOnClickListener { selectTagCount(TagRankPosterData.TAG_COUNT_6) }
        count20Option.setOnClickListener { selectTagCount(TagRankPosterData.TAG_COUNT_20) }
        count20Option.isEnabled = canShowMore
        count20Option.alpha = if (canShowMore) 1f else 0.42f
        updateTagCountSelectionUi(palette)
    }

    private fun selectTagCount(count: Int) {
        val sanitized = TagRankPosterData.sanitizeTagCount(count)
        if (selectedTagCount == sanitized) return
        selectedTagCount = sanitized
        updateTagCountSelectionUi()
        renderPoster()
    }

    private fun updateTagCountSelectionUi(palette: V3Palette = V3Palette.from(requireContext())) {
        val context = requireContext()
        val radius = context.dpF(24f)
        fun apply(option: TextView, selected: Boolean) {
            val fill = if (selected) palette.primary else palette.alpha10
            val stroke = if (selected) null else palette.alpha30
            val content = shape(radius, fill, stroke, context.hairlinePx())
            option.background = context.ripple(content, shape(radius, Color.WHITE))
            option.setTextColor(if (selected) palette.onPrimary else palette.textSecondary)
            option.isSelected = selected
        }
        apply(count6Option, selectedTagCount == TagRankPosterData.TAG_COUNT_6)
        apply(count20Option, selectedTagCount == TagRankPosterData.TAG_COUNT_20)
    }

    private fun bindActions() {
        val context = requireContext()
        saveButton = context.pillButton(
            text = getString(R.string.tag_poster_save),
            primary = true,
            icon = R.drawable.ic_tag_poster_save,
        ) { requestSave() }
        shareButton = context.pillButton(
            text = getString(R.string.tag_poster_share),
            primary = false,
            icon = R.drawable.ic_tag_poster_share,
        ) { sharePoster() }
        val gap = context.dp(12)
        // 高度用 wrap_content + pill 自带 48dp 最小高度：英文/大字体下按钮文字折行时按钮跟着长高，
        // 不会像固定 50dp 那样把第二行文字裁掉。
        actions.addView(
            saveButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        actions.addView(
            shareButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = gap
            },
        )
        setActionsEnabled(false)
    }

    /** 平板上限宽 460dp；高度交给父容器（全屏浮层内边距以内），预览区靠 weight 自适应。 */
    private fun restrictPanelSize() {
        val context = requireContext()
        val maxWidth = (resources.displayMetrics.widthPixels - context.dp(32))
            .coerceAtMost(context.dp(460))
        panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
            width = maxWidth
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        saveButton.isEnabled = enabled
        shareButton.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.45f
        saveButton.alpha = alpha
        shareButton.alpha = alpha
    }

    // ───────────────────────────────────────────────────────────────
    // 生成
    // ───────────────────────────────────────────────────────────────

    private fun renderPoster() {
        generation?.cancel()
        poster?.let { old ->
            previewImage.setImageBitmap(null)
            old.recycle()
        }
        poster = null
        showLoading()
        setActionsEnabled(false)
        if (payload.tags.isEmpty()) {
            showError(getString(R.string.tag_poster_empty))
            return
        }

        val context = requireContext()
        generation = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val avatarBitmap = resolveAvatar(context)
                    TagRankPosterRenderer.render(
                        data = TagRankPosterData(
                            tags = payload.tags,
                            totalWorks = payload.totalWorks,
                            distinctTagCount = payload.distinctTagCount,
                            userName = payload.userName,
                            userAccount = payload.userAccount,
                            userId = payload.userId,
                            avatar = avatarBitmap,
                            showAccount = showAccount,
                            showAvatar = showAvatar,
                            tagCount = selectedTagCount,
                        ),
                        copy = TagRankPosterCopy.from(
                            context,
                            payload.tags.take(selectedTagCount).size.coerceAtLeast(1),
                        ),
                        typography = TagRankPosterTypography.from(context),
                    )
                }
                if (!isAdded) {
                    result.recycle()
                    return@launch
                }
                poster = result
                previewImage.setImageBitmap(result)
                loadingView.visibility = View.GONE
                errorView.visibility = View.GONE
                setActionsEnabled(true)
                syncAvatarSwitchState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Timber.tag(TAG).e(error, "render poster failed")
                if (isAdded) {
                    showError(
                        getString(
                            R.string.tag_poster_generate_failed,
                            error.userMessage(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * 只为「展示头像」下载一次；失败后不再重试，直接走首字母占位。
     *
     * 这个请求跑在 render generation 里，而切换 TOP 6/20、账号开关都会 cancel 当前 generation；
     * 所以 `avatarRequested` 不能在发起请求前就永久置真：如果请求在下载途中被取消，必须复位，
     * 下一次 render 才会重新加载。否则一次取消就会让头像永远停在占位圆/缺失状态。
     */
    private suspend fun resolveAvatar(context: Context): Bitmap? {
        if (!showAccount || !showAvatar) return avatar
        avatar?.let { return it }
        if (avatarRequested) return null
        if (payload.avatarUrl.isBlank()) {
            avatarRequested = true
            return null
        }
        avatarRequested = true
        val loaded = try {
            withContext(Dispatchers.IO) {
                runCatching {
                    Glide.with(context.applicationContext)
                        .asBitmap()
                        .load(GlideUrlChild(payload.avatarUrl))
                        .submit(AVATAR_DECODE_SIZE, AVATAR_DECODE_SIZE)
                        .get(AVATAR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }.onFailure {
                    Timber.tag(TAG).w(it, "avatar load failed")
                }.getOrNull()
            }
        } catch (cancelled: CancellationException) {
            // 取消不算「已尝试过」：允许下一次 generation 重新发起头像请求。
            avatarRequested = false
            throw cancelled
        }
        avatar = loaded
        return loaded
    }

    private fun showLoading() {
        loadingView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        loadingLabel.text = getString(R.string.tag_poster_generating)
    }

    private fun showError(message: String) {
        loadingView.visibility = View.GONE
        errorView.text = message
        errorView.visibility = View.VISIBLE
        setActionsEnabled(false)
    }

    // ───────────────────────────────────────────────────────────────
    // 保存
    // ───────────────────────────────────────────────────────────────

    /**
     * 导出用的独占快照。
     *
     * 保存/分享的 PNG 压缩跑在 IO 线程，而这段时间里用户仍可切 TOP 6/20、切账号开关或直接关闭
     * 弹窗 —— 这些路径都会在 [renderPoster] / [onDestroyView] 里 recycle 共享的 `poster`。
     * 直接把 `poster` 交给 IO 压缩，迟早会压到一张已经被回收的 Bitmap。这里在主线程同步
     * `copy()` 一份只属于本次导出的 Bitmap，IO 只用快照，写完后由 IO 侧 finally 回收，
     * 和预览图的生灭彻底解耦（具体实现与单测见 [TagRankPosterExporter.snapshotForExport]）。
     */
    private fun snapshotPoster(): Bitmap? = TagRankPosterExporter.snapshotForExport(poster)

    private fun requestSave() {
        val context = context ?: return
        if (poster == null || saving) return
        val needsLegacyPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsLegacyPermission) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        persistPoster()
    }

    private fun persistPoster() {
        val context = context ?: return
        if (saving) return
        val snapshot = snapshotPoster() ?: return
        saving = true
        setActionsEnabled(false)
        saveButton.text = getString(R.string.tag_poster_saving)
        // 先取出所有文案：写盘期间弹窗可能被关掉，之后就不能再安全地 getString 了。
        val savedText = getString(R.string.tag_poster_saved)
        val failedTemplate = getString(R.string.tag_poster_save_failed)
        val saveText = getString(R.string.tag_poster_save)
        viewLifecycleOwner.lifecycleScope.launch {
            var recycledInIo = false
            try {
                val uri = withContext(Dispatchers.IO) {
                    try {
                        TagRankPosterExporter.saveToGallery(context, snapshot, posterFileName())
                    } finally {
                        // 快照的回收固定在 IO 侧：写盘结束（成功或失败）立刻释放 11MB 级内存。
                        recycledInIo = true
                        snapshot.recycle()
                    }
                }
                Timber.tag(TAG).i("poster saved uri=%s", uri)
                Common.showToast(savedText)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Timber.tag(TAG).e(error, "save poster failed")
                Common.showToast(String.format(failedTemplate, error.userMessage()))
            } finally {
                // 协程若在进入 IO 前就被取消（视图已销毁），IO 的 finally 不会执行，这里兜底。
                if (!recycledInIo) runCatching { snapshot.recycle() }
                saving = false
                if (isAdded && view != null) {
                    saveButton.text = saveText
                    setActionsEnabled(poster != null)
                }
            }
        }
    }

    // ───────────────────────────────────────────────────────────────
    // 分享
    // ───────────────────────────────────────────────────────────────

    private fun sharePoster() {
        val context = context ?: return
        val snapshot = snapshotPoster() ?: return
        setActionsEnabled(false)
        val chooserTitle = getString(R.string.share)
        val failedTemplate = getString(R.string.tag_poster_share_failed)
        viewLifecycleOwner.lifecycleScope.launch {
            var recycledInIo = false
            try {
                val uri = withContext(Dispatchers.IO) {
                    try {
                        TagRankPosterExporter.shareCacheUri(context, snapshot, posterFileName())
                    } finally {
                        recycledInIo = true
                        snapshot.recycle()
                    }
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(context.contentResolver, "tag poster", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, chooserTitle))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Timber.tag(TAG).e(error, "share poster failed")
                Common.showToast(String.format(failedTemplate, error.userMessage()))
            } finally {
                if (!recycledInIo) runCatching { snapshot.recycle() }
                if (isAdded && view != null) setActionsEnabled(poster != null)
            }
        }
    }

    private fun posterFileName(): String {
        val stamp = FILE_TIME_FORMAT.format(Date())
        return "Shaft_TagRank_$stamp.png"
    }

    private fun Throwable.userMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
