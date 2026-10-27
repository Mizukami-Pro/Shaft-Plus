package ceui.pixiv.ui.tagbrowse.poster

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * 海报的落盘出口：保存到相册、写分享缓存并换取 FileProvider Uri。
 *
 * 两条链路都和 App 现有的分享实现保持一致（见 `ShareFirstImage` / `MediaStoreUtils`）：
 *  - 相册：Q+ 走 `MediaStore.Images`（`RELATIVE_PATH = Pictures/Shaft`，先 `IS_PENDING` 再提交）；
 *    Q 以下写公共 Pictures/Shaft 目录并用 [MediaScannerConnection] 通知图库；
 *  - 分享：写 `cacheDir/images/`，命中 AndroidManifest 里 FileProvider 的
 *    `shared_images_internal` 路径，authority 为 `${applicationId}.provider`。
 *
 * 全部方法是阻塞 IO，调用方必须在工作线程执行。
 */
internal object TagRankPosterExporter {

    private const val TAG = "TagRankPosterExport"

    /** 相册里的子目录，与 App 其它图片导出保持一致。 */
    private const val GALLERY_FOLDER = "Shaft"

    /** FileProvider `<cache-path name="shared_images_internal" path="images/"/>`。 */
    private const val SHARE_CACHE_DIR = "images"

    private const val PNG_MIME = "image/png"

    /**
     * 生成导出专用的独占 Bitmap 快照（在主线程调用）。
     *
     * 预览 Bitmap 会在切换 TOP 6/20、账号开关或关闭弹窗时被 recycle，而 PNG 压缩跑在 IO 线程；
     * 把预览位图直接交给导出路径，迟早会压到一张已被回收的 Bitmap。这里做一次内存拷贝
     * （1080×2600 约 11MB、几毫秒），导出只读写快照，调用方负责在 IO 写完后 finally 里
     * `snapshot.recycle()`，因此快照的生命周期与预览图完全解耦。
     *
     * @return 独占快照；[source] 为 null 或拷贝失败（如内存不足）时返回 null。
     */
    fun snapshotForExport(source: Bitmap?): Bitmap? =
        source?.let {
            runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }
                .onFailure { error -> Timber.tag(TAG).w(error, "poster snapshot failed") }
                .getOrNull()
        }

    /**
     * 把 [bitmap] 写进系统相册。
     *
     * @return 新图片的 content Uri（Q 以下返回 file Uri，仅用于日志/校验）。
     * @throws IOException 写盘失败、目录创建失败或 MediaStore 拒绝插入时抛出。
     */
    fun saveToGallery(context: Context, bitmap: Bitmap, displayName: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, bitmap, displayName)
        } else {
            saveViaLegacyPictures(context, bitmap, displayName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
    ): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, PNG_MIME)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法在相册中创建图片（MediaStore 拒绝插入）")
        try {
            val stream = resolver.openOutputStream(uri)
                ?: throw IOException("无法打开相册图片输出流")
            stream.use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("海报 PNG 编码失败")
                }
            }
            // 清 pending：提交后才允许其它应用/图库读到这张图。
            val ready = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, ready, null, null)
            return uri
        } catch (error: Throwable) {
            // 半成品不能留在相册里等系统扫描成 0 字节坏图。
            runCatching { resolver.delete(uri, null, null) }
            throw if (error is IOException) error else IOException(error.message, error)
        }
    }

    @Suppress("DEPRECATION")
    private fun saveViaLegacyPictures(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
    ): Uri {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val folder = File(pictures, GALLERY_FOLDER)
        if (!folder.exists() && !folder.mkdirs()) {
            throw IOException("无法创建相册目录 Pictures/$GALLERY_FOLDER")
        }
        val file = uniqueFile(folder, displayName)
        file.outputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("海报 PNG 编码失败")
            }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf(PNG_MIME),
            null,
        )
        return Uri.fromFile(file)
    }

    /**
     * 写一份分享用的 PNG 到 `cacheDir/images/`，返回可授予读权限的 content Uri。
     *
     * 每次分享重新生成文件名，避免旧图被接收方缓存串味。
     */
    fun shareCacheUri(context: Context, bitmap: Bitmap, displayName: String): Uri {
        val folder = File(context.cacheDir, SHARE_CACHE_DIR)
        if (!folder.exists() && !folder.mkdirs()) {
            throw IOException("无法创建分享缓存目录")
        }
        val file = uniqueFile(folder, displayName)
        file.outputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw IOException("海报 PNG 编码失败")
            }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    /** 同名文件已存在时补一个序号，绝不覆盖已有相册图或缓存图。 */
    private fun uniqueFile(folder: File, displayName: String): File {
        val direct = File(folder, displayName)
        if (!direct.exists()) return direct
        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "")
        for (index in 1..999) {
            val suffix = if (extension.isEmpty()) "_$index" else "_$index.$extension"
            val candidate = File(folder, base + suffix)
            if (!candidate.exists()) return candidate
        }
        return File(folder, "${base}_${System.currentTimeMillis()}${if (extension.isEmpty()) "" else ".$extension"}")
    }
}
