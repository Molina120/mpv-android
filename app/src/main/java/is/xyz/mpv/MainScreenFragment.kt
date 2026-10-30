package `is`.xyz.mpv

import `is`.xyz.mpv.preferences.PreferenceActivity
import `is`.xyz.mpv.databinding.FragmentMainScreenBinding
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * صفحه اصلی: مرورگر ویدیوها به سبک MX Player — پوشه‌ها و ویدیوها
 * به‌صورت خودکار از MediaStore پیدا و مرتب نمایش داده می‌شوند.
 */
class MainScreenFragment : Fragment(R.layout.fragment_main_screen) {
    private lateinit var binding: FragmentMainScreenBinding

    private lateinit var documentTreeOpener: ActivityResultLauncher<Uri?>
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var playerLauncher: ActivityResultLauncher<Intent>
    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    private val scanner = Executors.newSingleThreadExecutor()
    // تردپول مجزا و سریع برای خواندن فوری بندانگشتی‌های ذخیره‌شده از حافظه دائمی
    private val diskCacheExecutor = Executors.newFixedThreadPool(4)
    // تردپول مجزا برای استخراج فریم ویدیوهای جدید (جلوگیری از اشباع دیکدر سخت‌افزاری)
    private val thumbDecodeExecutor = Executors.newFixedThreadPool(2)

    /** وضعیت تماشا (به سبک MX Player) */
    private var newVideoIds: Set<Long> = emptySet()
    private var lastPlayedId: Long? = null

    /** متن جستجو در مرورگر */
    private var searchQuery: String = ""

    /** کش بندانگشتی‌ها در رم: حداکثر ~32 مگابایت */
    private val thumbCache = object : LruCache<Long, Bitmap>(32 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }

    /** ویدیوهایی که در حال حاضر در صف ساخت بندانگشتی هستند (جلوگیری از پردازش تکراری) */
    private val thumbInProgress = Collections.synchronizedSet(mutableSetOf<Long>())

    /** ویدیوهایی که ساخت بندانگشتی‌شان در این نشست ناموفق بود */
    private val thumbFailed = Collections.synchronizedSet(mutableSetOf<Long>())

    private var folders: List<VideoScanner.Folder> = emptyList()
    private var openFolder: VideoScanner.Folder? = null
    private var loadedOnce = false

    private var lastPath = ""

    /** مرتب‌سازی لیست‌ها */
    private enum class SortBy { TITLE, DATE, DURATION, SIZE }
    private var sortBy: SortBy = SortBy.DATE
    private var sortDescending: Boolean = true

    private fun loadSortPref() {
        val prefs = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(requireContext())
        sortBy = try {
            SortBy.valueOf(prefs.getString("browser_sort_by", SortBy.DATE.name) ?: SortBy.DATE.name)
        } catch (e: Exception) { SortBy.DATE }
        sortDescending = prefs.getBoolean("browser_sort_desc", true)
    }

    private fun saveSortPref() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
            .edit()
            .putString("browser_sort_by", sortBy.name)
            .putBoolean("browser_sort_desc", sortDescending)
            .apply()
    }

    private fun sortedFolders(): List<VideoScanner.Folder> {
        val cmp: Comparator<VideoScanner.Folder> = when (sortBy) {
            SortBy.TITLE -> compareBy { it.name.lowercase() }
            SortBy.DATE -> compareBy { f -> f.videos.maxOfOrNull { it.dateModifiedSec } ?: 0L }
            SortBy.DURATION -> compareBy { f -> f.videos.sumOf { it.durationMs } }
            SortBy.SIZE -> compareBy { it.totalSize }
        }
        val sorted = folders.sortedWith(cmp)
        return if (sortDescending) sorted.reversed() else sorted
    }

    private fun sortedVideos(folder: VideoScanner.Folder): List<VideoScanner.Video> =
        sortVideoList(folder.videos)

    private fun sortVideoList(videos: List<VideoScanner.Video>): List<VideoScanner.Video> {
        val cmp: Comparator<VideoScanner.Video> = when (sortBy) {
            SortBy.TITLE -> compareBy { it.name.lowercase() }
            SortBy.DATE -> compareBy { it.dateModifiedSec }
            SortBy.DURATION -> compareBy { it.durationMs }
            SortBy.SIZE -> compareBy { it.sizeBytes }
        }
        val sorted = videos.sortedWith(cmp)
        return if (sortDescending) sorted.reversed() else sorted
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (openFolder != null) showFolders() else {
                isEnabled = false
                requireActivity().onBackPressed()
            }
        }
    }

    private val videoPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), videoPermission) ==
                PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        documentTreeOpener = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            it?.let { root ->
                requireContext().contentResolver.takePersistableUriPermission(
                    root, Intent.FLAG_GRANT_READ_URI_PERMISSION)

                val i = Intent(context, FilePickerActivity::class.java)
                i.putExtra("skip", FilePickerActivity.DOC_PICKER)
                i.putExtra("root", root.toString())
                i.putExtra("allow_dir", true)
                filePickerLauncher.launch(i)
            }
        }
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            it.data?.getStringExtra("last_path")?.let { path ->
                lastPath = path
            }
            it.data?.getStringExtra("path")?.let { path ->
                playFile(path)
                return@registerForActivityResult
            }
            it.data?.getStringArrayExtra("paths")?.let { paths ->
                if (paths.size == 1) {
                    playFile(paths[0])
                } else {
                    val memoryUri = Utils.makeMemoryPlaylist(paths.asIterable())
                    playFile(memoryUri)
                }
            }
        }
        playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            Log.v(TAG, "returned from player ($it)")
        }
        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadVideos() else showPermissionView()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_main_screen, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = FragmentMainScreenBinding.bind(view)

        Utils.handleInsetsAsPadding(binding.root)

        binding.videoList.layoutManager = LinearLayoutManager(requireContext())
        binding.videoList.adapter = BrowserAdapter()

        binding.toolbar.title = getString(R.string.browser_folders)
        binding.toolbar.inflateMenu(R.menu.browser_menu)
        val searchItem = binding.toolbar.menu.findItem(R.id.menu_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = true
            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText?.trim() ?: ""
                refreshDisplay()
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchQuery = ""
                refreshDisplay()
                return true
            }
        })
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_refresh -> {
                    thumbFailed.clear()
                    ThumbDiskCache.clearAllFailed(requireContext().applicationContext)
                    loadVideos()
                    true
                }
                R.id.menu_sort -> { showSortDialog(); true }
                R.id.menu_open_file -> { openLegacyFilePicker(); true }
                R.id.menu_open_folder -> { openDocumentTree(); true }
                R.id.menu_open_url -> { openUrlDialog(); true }
                R.id.menu_settings -> {
                    startActivity(Intent(context, PreferenceActivity::class.java)); true
                }
                else -> false
            }
        }
        binding.toolbar.setNavigationOnClickListener { showFolders() }

        binding.grantBtn.setOnClickListener {
            permissionLauncher.launch(videoPermission)
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        loadSortPref()
        if (hasPermission()) loadVideos() else showPermissionView()
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission() && !loadedOnce &&
            binding.permissionView.isVisible) {
            loadVideos()
        } else if (loadedOnce) {
            refreshWatchStates()
        }
    }

    private fun refreshWatchStates() {
        if (!isAdded) return
        val ctx = requireContext()
        lastPlayedId = WatchTracker.lastPlayedId(ctx)
        updateFab()
        (binding.videoList.adapter as? BrowserAdapter)?.notifyDataSetChanged()
    }

    private fun updateFab() {
        val id = lastPlayedId
        val target = folders.asSequence().flatMap { it.videos }.find { it.id == id }
        if (target != null) {
            binding.lastVideoFab.isVisible = true
            binding.lastVideoFab.setOnClickListener { onVideoTap(target) }
        } else {
            binding.lastVideoFab.isVisible = false
        }
    }

    // ---------- مرورگر ----------

    private sealed class BrowserItem {
        data class FolderRow(val folder: VideoScanner.Folder) : BrowserItem()
        data class VideoRow(val video: VideoScanner.Video) : BrowserItem()
    }

    private inner class BrowserAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<BrowserItem> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemViewType(position: Int): Int =
            if (items[position] is BrowserItem.FolderRow) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                val v = inflater.inflate(R.layout.item_video_folder, parent, false)
                FolderVH(v)
            } else {
                val v = inflater.inflate(R.layout.item_video_file, parent, false)
                VideoVH(v)
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is BrowserItem.FolderRow -> (holder as FolderVH).bind(item.folder)
                is BrowserItem.VideoRow -> (holder as VideoVH).bind(item.video)
            }
        }

        inner class FolderVH(v: View) : RecyclerView.ViewHolder(v) {
            private val name: TextView = v.findViewById(R.id.folderName)
            private val count: TextView = v.findViewById(R.id.folderCount)
            private val size: TextView = v.findViewById(R.id.folderSize)
            private val badgeNew: TextView = v.findViewById(R.id.folderBadgeNew)
            fun bind(folder: VideoScanner.Folder) {
                name.text = folder.name
                count.text = resources.getQuantityString(
                    R.plurals.browser_videos_count, folder.videos.size, folder.videos.size)
                size.text = VideoScanner.formatSize(itemView.context, folder.totalSize)
                val isLast = lastPlayedId != null &&
                        folder.videos.any { it.id == lastPlayedId }
                name.setTextColor(
                    ContextCompat.getColor(itemView.context,
                        if (isLast) R.color.watch_last else android.R.color.white))
                badgeNew.isVisible = folder.videos.any { it.id in newVideoIds }
                itemView.setOnClickListener { showVideos(folder) }
            }
        }

        inner class VideoVH(v: View) : RecyclerView.ViewHolder(v) {
            private val thumb: ImageView = v.findViewById(R.id.videoThumb)
            private val thumbDuration: TextView = v.findViewById(R.id.thumbDuration)
            private val name: TextView = v.findViewById(R.id.videoName)
            private val badgeSub: TextView = v.findViewById(R.id.badgeSub)
            private val badgeDub: TextView = v.findViewById(R.id.badgeDub)
            private val badgeNew: TextView = v.findViewById(R.id.badgeNew)
            private val badgeSize: TextView = v.findViewById(R.id.badgeSize)
            private val badgeDate: TextView = v.findViewById(R.id.badgeDate)
            fun bind(video: VideoScanner.Video) {
                name.text = video.name
                thumbDuration.text = VideoScanner.formatDuration(video.durationMs)
                badgeSub.isVisible = video.hasSubtitle
                badgeDub.isVisible = video.hasDubbedAudio
                badgeNew.isVisible = video.id in newVideoIds
                badgeSize.text = VideoScanner.formatSize(itemView.context, video.sizeBytes)
                val dateStr = VideoScanner.formatDate(video.dateModifiedSec)
                badgeDate.isVisible = dateStr.isNotEmpty()
                badgeDate.text = dateStr
                val ctx = itemView.context
                val watched = WatchTracker.state(ctx, video.id)?.watched == true
                name.setTextColor(
                    ContextCompat.getColor(ctx, when {
                        watched -> R.color.watch_done
                        video.id == lastPlayedId -> R.color.watch_last
                        else -> android.R.color.white
                    }))
                loadThumb(video, thumb)
                itemView.setOnClickListener { onVideoTap(video) }
            }
        }
    }

    // ---------- مدیریت پیشرفته و دائمی بندانگشتی‌ها (پشتیبانی کامل از x265) ----------

    /**
     * کش دیسک مشترک با پلیر (ThumbDiskCache — حافظه دائمی filesDir تا با بستن
     * برنامه یا پاکسازی کش سیستم حذف نشود). کلید فقط شناسه ویدیو است.
     */
    private fun getDiskCacheFile(appCtx: Context, video: VideoScanner.Video): File =
        ThumbDiskCache.file(appCtx, video.id)

    /** پیش‌بارگذاری بندانگشتی‌های موجود روی دیسک به درون رم برای نمایش آنی */
    private fun preloadDiskThumbnails(appCtx: Context, allFolders: List<VideoScanner.Folder>) {
        diskCacheExecutor.execute {
            for (folder in allFolders) {
                for (video in folder.videos) {
                    if (thumbCache.get(video.id) != null) continue
                    if (ThumbDiskCache.load(appCtx, video.id)?.let { thumbCache.put(video.id, it); true } == true) {
                        continue
                    }
                }
            }
        }
    }

    /** بارگذاری بندانگشتی با اولویت: ۱. کش رم ۲. کش دیسک (فوری) ۳. استخراج و ذخیره دائمی */
    private fun loadThumb(video: VideoScanner.Video, imageView: ImageView) {
        imageView.tag = video.id

        // ۱. اگر در رم است، بلافاصله بدون پرش تصویر ست شود
        thumbCache.get(video.id)?.let {
            imageView.setImageBitmap(it)
            return
        }

        imageView.setImageDrawable(null)
        if (video.id in thumbFailed) return

        val appCtx = requireContext().applicationContext
        val cacheFile = getDiskCacheFile(appCtx, video)

        // ۲. اگر فایل قبلاً روی دیسک ذخیره شده، در تردپول سریع دیسک (بدون انتظار در صف دیکد ویدیو) لود شود
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            diskCacheExecutor.execute {
                val cachedBmp = thumbCache.get(video.id)
                    ?: ThumbDiskCache.load(appCtx, video.id)?.also {
                        thumbCache.put(video.id, it)
                    }
                if (cachedBmp != null) {
                    imageView.post {
                        if (imageView.tag == video.id) imageView.setImageBitmap(cachedBmp)
                    }
                } else {
                    // اگر فایل دیسک خراب بود، حذف و بازسازی شود
                    cacheFile.delete()
                    extractAndSaveThumb(appCtx, video, cacheFile, imageView)
                }
            }
            return
        }

        // ۳. اگر روی دیسک نبود، استخراج و برای همیشه روی دیسک ذخیره شود
        extractAndSaveThumb(appCtx, video, cacheFile, imageView)
    }

    private fun extractAndSaveThumb(
        appCtx: Context,
        video: VideoScanner.Video,
        cacheFile: File,
        imageView: ImageView
    ) {
        // جلوگیری از ارسال هم‌زمان چند تسک دیکد برای یک ویدیو
        if (!thumbInProgress.add(video.id)) return

        thumbDecodeExecutor.execute {
            try {
                // توجه: حتی اگر کاربر اسکرول کند، استخراج و ذخیره روی دیسک را متوقف نمی‌کنیم
                // تا تصویر برای همیشه ذخیره شود و دفعه بعد از اول لود نشود.
                var rawBmp: Bitmap? = retrieverFrame(appCtx, video)

                // اگر MediaMetadataRetriever شکست خورد (به‌ویژه در ویدیوهای x265 / HEVC 10-bit)
                // از دیکدر مستقیم MediaCodec + سطح گرافیکی OpenGL استفاده می‌کنیم
                if (rawBmp == null || isMostlyBlack(rawBmp)) {
                    val codecBmp = decodeFrameWithMediaCodec(appCtx, video)
                    if (codecBmp != null && (!isMostlyBlack(codecBmp) || rawBmp == null)) {
                        if (rawBmp != null && rawBmp !== codecBmp) rawBmp.recycle()
                        rawBmp = codecBmp
                    }
                }

                // راه‌حل سوم: بندانگشتی سیستم
                if (rawBmp == null || isMostlyBlack(rawBmp)) {
                    val sysBmp = loadSystemThumbnail(appCtx, video)
                    if (sysBmp != null && (!isMostlyBlack(sysBmp) || rawBmp == null)) {
                        if (rawBmp != null && rawBmp !== sysBmp) rawBmp.recycle()
                        rawBmp = sysBmp
                    }
                }

                if (rawBmp != null) {
                    val finalBmp = scaleCenterCrop(rawBmp, THUMB_WIDTH, THUMB_HEIGHT)
                    if (finalBmp !== rawBmp) rawBmp.recycle()

                    thumbCache.put(video.id, finalBmp)

                    // ذخیره اتمیک روی حافظه دائمی (ابتدا در فایل موقت تا فایل ناقص ایجاد نشود)
                    try {
                        val tmpFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
                        FileOutputStream(tmpFile).use { out ->
                            finalBmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            out.flush()
                        }
                        if (tmpFile.exists() && tmpFile.length() > 0L) {
                            tmpFile.renameTo(cacheFile)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to save thumbnail to disk: ${video.name}", e)
                    }

                    imageView.post {
                        if (isAdded && imageView.tag == video.id) {
                            imageView.setImageBitmap(finalBmp)
                        } else {
                            // اگر ردیف اسکرول شده بود و دوباره برگشت، آداپتر را مطلع کن
                            binding.videoList.post {
                                val adapter = binding.videoList.adapter as? BrowserAdapter ?: return@post
                                val idx = adapter.items.indexOfFirst {
                                    it is BrowserItem.VideoRow && it.video.id == video.id
                                }
                                if (idx != -1) adapter.notifyItemChanged(idx)
                            }
                        }
                    }
                } else {
                    thumbFailed.add(video.id)
                }
            } catch (e: Exception) {
                Log.w(TAG, "thumbnail failed: ${video.name}", e)
                thumbFailed.add(video.id)
            } finally {
                thumbInProgress.remove(video.id)
            }
        }
    }

    /** مرحله ۱: استخراج فریم با MediaMetadataRetriever بهینه‌شده برای x265 و فایل‌های حجیم */
    private fun retrieverFrame(appCtx: Context, video: VideoScanner.Video): Bitmap? {
        val r = MediaMetadataRetriever()
        var pfd: android.os.ParcelFileDescriptor? = null
        return try {
            var sourceSet = false
            try {
                pfd = appCtx.contentResolver.openFileDescriptor(video.uri, "r")
                if (pfd != null) {
                    val realPath = Utils.findRealPath(pfd.fd)
                    if (realPath != null) {
                        r.setDataSource(realPath)
                        sourceSet = true
                    } else {
                        r.setDataSource(pfd.fileDescriptor)
                        sourceSet = true
                    }
                }
            } catch (_: Exception) { }

            if (!sourceSet) {
                r.setDataSource(appCtx, video.uri)
            }

            val durationMs = if (video.durationMs > 0) {
                video.durationMs
            } else {
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }

            val candidatesUs = if (durationMs > 10_000L) {
                longArrayOf(
                    durationMs * 150L, // 15%
                    durationMs * 250L, // 25%
                    durationMs * 100L, // 10%
                    15_000_000L,       // 15s
                    5_000_000L         // 5s
                )
            } else {
                longArrayOf(3_000_000L, 1_000_000L, 0L)
            }

            var fallbackFrame: Bitmap? = null
            for (timeUs in candidatesUs) {
                val frame = extractSingleFrame(r, timeUs) ?: continue
                if (!isMostlyBlack(frame)) {
                    if (fallbackFrame != null && fallbackFrame !== frame) fallbackFrame.recycle()
                    return frame
                }
                if (fallbackFrame == null) fallbackFrame = frame else frame.recycle()
            }

            // در فایل‌های x265 MKV که جدول Seek مشکل دارد، استخراج فریم پیش‌فرض (-1) یا با ایندکس مستقیم
            val defaultFrame = extractDefaultOrIndexedFrame(r)
            if (defaultFrame != null) {
                if (!isMostlyBlack(defaultFrame) || fallbackFrame == null) {
                    if (fallbackFrame != null && fallbackFrame !== defaultFrame) fallbackFrame.recycle()
                    return defaultFrame
                } else {
                    defaultFrame.recycle()
                }
            }

            fallbackFrame
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) { }
            try { pfd?.close() } catch (_: Exception) { }
        }
    }

    private fun extractSingleFrame(r: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val params = MediaMetadataRetriever.BitmapParams().apply {
                preferredConfig = Bitmap.Config.ARGB_8888
            }
            // در x265 10-bit، گاهی getScaledFrameAtTime شکست می‌خورد اما getFrameAtTime با ARGB_8888 کار می‌کند
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    r.getScaledFrameAtTime(
                        timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640, params
                    )?.let { return it }
                }
            } catch (_: Exception) { }

            try {
                r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, params)
                    ?.let { return it }
            } catch (_: Exception) { }

            try {
                r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC, params)
                    ?.let { return it }
            } catch (_: Exception) { }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                r.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640
                )?.let { return it }
            } catch (_: Exception) { }
        }

        return try {
            r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
        } catch (_: Exception) {
            null
        }
    }

    /** استخراج فریم بدون نیاز به Seek (مخصوص فایل‌های x265 با Open-GOP یا فاقد Cue) */
    private fun extractDefaultOrIndexedFrame(r: MediaMetadataRetriever): Bitmap? {
        try {
            r.getFrameAtTime(-1L)?.let { if (!isMostlyBlack(it)) return it }
        } catch (_: Exception) { }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val params = MediaMetadataRetriever.BitmapParams().apply {
                preferredConfig = Bitmap.Config.ARGB_8888
            }
            for (idx in intArrayOf(60, 24, 5, 0)) {
                try {
                    val bmp = r.getFrameAtIndex(idx, params)
                    if (bmp != null) {
                        if (!isMostlyBlack(bmp) || idx == 0) return bmp
                        bmp.recycle()
                    }
                } catch (_: Exception) { }
            }
        }
        return null
    }

    /**
     * مرحله ۲ (مخصوص ویدیوهای x265 / HEVC 10-bit و MKV):
     * بازگشایی مستقیم استریم با MediaExtractor + MediaCodec روی سطح گرافیکی EGL14.
     * این متد مشکل عدم پشتیبانی ColorConverter از فرمت‌های ۱۰ بیتی x265 را به‌طور کامل دور می‌زند.
     */
    private fun decodeFrameWithMediaCodec(appCtx: Context, video: VideoScanner.Video): Bitmap? {
        // ابتدا دیکدر پیش‌فرض (سخت‌افزاری) و در صورت خطا، دیکدر نرم‌افزاری HEVC اندروید را تست می‌کند
        return decodeWithCodecInternal(appCtx, video, preferSoftware = false)
            ?: decodeWithCodecInternal(appCtx, video, preferSoftware = true)
    }

    private fun decodeWithCodecInternal(
        appCtx: Context,
        video: VideoScanner.Video,
        preferSoftware: Boolean
    ): Bitmap? {
        val extractor = MediaExtractor()
        var pfd: android.os.ParcelFileDescriptor? = null
        var codec: MediaCodec? = null
        var eglSurface: OffscreenEglSurface? = null

        return try {
            var sourceSet = false
            try {
                pfd = appCtx.contentResolver.openFileDescriptor(video.uri, "r")
                if (pfd != null) {
                    val realPath = Utils.findRealPath(pfd.fd)
                    if (realPath != null) {
                        extractor.setDataSource(realPath)
                        sourceSet = true
                    } else {
                        extractor.setDataSource(pfd.fileDescriptor)
                        sourceSet = true
                    }
                }
            } catch (_: Exception) { }

            if (!sourceSet) {
                extractor.setDataSource(appCtx, video.uri, null)
            }

            var videoTrackIndex = -1
            var format: MediaFormat? = null
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) {
                    videoTrackIndex = i
                    format = f
                    mime = m
                    break
                }
            }
            if (videoTrackIndex < 0 || format == null) return null

            extractor.selectTrack(videoTrackIndex)

            val durationUs = when {
                video.durationMs > 0 -> video.durationMs * 1000L
                format.containsKey(MediaFormat.KEY_DURATION) -> format.getLong(MediaFormat.KEY_DURATION)
                else -> 0L
            }

            eglSurface = OffscreenEglSurface(THUMB_WIDTH, THUMB_HEIGHT)

            codec = if (preferSoftware && mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                try {
                    MediaCodec.createByCodecName("c2.android.hevc.decoder")
                } catch (_: Exception) {
                    MediaCodec.createByCodecName("OMX.google.hevc.decoder")
                }
            } else {
                MediaCodec.createDecoderByType(mime)
            }

            codec.configure(format, eglSurface.surface, null, 0)
            codec.start()

            val seekTargetsUs = if (durationUs > 10_000_000L) {
                longArrayOf((durationUs * 0.15).toLong(), (durationUs * 0.30).toLong(), 0L)
            } else {
                longArrayOf(0L)
            }

            var resultBmp: Bitmap? = null
            val bufferInfo = MediaCodec.BufferInfo()

            for (seekUs in seekTargetsUs) {
                try {
                    codec.flush()
                    extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    if (extractor.sampleTime < 0L && seekUs > 0L) {
                        extractor.seekTo(0L, MediaExtractor.SEEK_TO_NEXT_SYNC)
                    }
                } catch (_: Exception) {
                    extractor.seekTo(0L, MediaExtractor.SEEK_TO_NEXT_SYNC)
                }

                var inputEos = false
                var outputDone = false
                var decodedFrames = 0
                var steps = 0

                while (!outputDone && steps < 120) {
                    steps++
                    if (!inputEos) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(
                                        inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputEos = true
                                } else {
                                    val pts = extractor.sampleTime.coerceAtLeast(0L)
                                    codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    if (outIdx >= 0) {
                        val doRender = bufferInfo.size > 0
                        codec.releaseOutputBuffer(outIdx, doRender)
                        if (doRender) {
                            decodedFrames++
                            val bmp = eglSurface.awaitAndReadBitmap()
                            if (bmp != null) {
                                if (!isMostlyBlack(bmp)) {
                                    resultBmp?.recycle()
                                    return bmp
                                }
                                if (resultBmp == null) {
                                    resultBmp = bmp
                                } else {
                                    bmp.recycle()
                                }
                            }
                            if (decodedFrames >= 2) {
                                outputDone = true
                            }
                        }
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            resultBmp
        } catch (e: Exception) {
            null
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { eglSurface?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
            try { pfd?.close() } catch (_: Exception) { }
        }
    }

    /**
     * سطح گرافیکی مخفی (Offscreen EGL14 + OES Texture) برای تبدیل سخت‌افزاری فریم‌های ۱۰ بیتی x265 به Bitmap
     */
    private class OffscreenEglSurface(val width: Int, val height: Int) :
        SurfaceTexture.OnFrameAvailableListener {

        private var eglDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface = EGL14.EGL_NO_SURFACE
        private var textureId = 0
        private var program = 0
        var surfaceTexture: SurfaceTexture? = null
        var surface: Surface? = null

        private val frameSyncObj = Object()
        private var frameAvailable = false
        private val stMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)

        private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                // مختصات صفحه (x, y) و مختصات بافت (u, v با وارونگی عمودی برای glReadPixels)
                put(floatArrayOf(
                    -1f, -1f, 0f, 1f,
                     1f, -1f, 1f, 1f,
                    -1f,  1f, 0f, 0f,
                     1f,  1f, 1f, 0f
                ))
                position(0)
            }

        init {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(
                eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0
            )

            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
            )
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfaceAttribs, 0)
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat()
            )
            GLES20.glTexParameterf(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat()
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
            )

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            Matrix.setIdentityM(mvpMatrix, 0)

            surfaceTexture = SurfaceTexture(textureId).apply {
                setOnFrameAvailableListener(this@OffscreenEglSurface, Handler(Looper.getMainLooper()))
            }
            surface = Surface(surfaceTexture)
        }

        override fun onFrameAvailable(st: SurfaceTexture?) {
            synchronized(frameSyncObj) {
                frameAvailable = true
                frameSyncObj.notifyAll()
            }
        }

        fun awaitAndReadBitmap(): Bitmap? {
            synchronized(frameSyncObj) {
                if (!frameAvailable) {
                    try { frameSyncObj.wait(400L) } catch (_: InterruptedException) { }
                }
                frameAvailable = false
            }

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(stMatrix)

            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
            val texHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
            val mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            val stHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")

            vertexBuffer.position(0)
            GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(posHandle)

            vertexBuffer.position(2)
            GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
            GLES20.glEnableVertexAttribArray(texHandle)

            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
            GLES20.glUniformMatrix4fv(stHandle, 1, false, stMatrix, 0)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glFinish()

            val buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            buf.rewind()

            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            return bmp
        }

        fun release() {
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            surface?.release()
            surfaceTexture?.release()
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            return prog
        }

        private fun loadShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }

        companion object {
            private const val VERTEX_SHADER = """
                uniform mat4 uMVPMatrix;
                uniform mat4 uSTMatrix;
                attribute vec4 aPosition;
                attribute vec4 aTextureCoord;
                varying vec2 vTextureCoord;
                void main() {
                    gl_Position = uMVPMatrix * aPosition;
                    vTextureCoord = (uSTMatrix * aTextureCoord).xy;
                }
            """
            private const val FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTextureCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTextureCoord);
                }
            """
        }
    }

    private fun loadSystemThumbnail(appCtx: Context, video: VideoScanner.Video): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appCtx.contentResolver.loadThumbnail(video.uri, Size(560, 360), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    appCtx.contentResolver, video.id,
                    MediaStore.Video.Thumbnails.MINI_KIND, null
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /** تشخیص فریم‌های سیاه یا بسیار تاریک */
    private fun isMostlyBlack(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return true

        val grid = 6
        var totalLum = 0
        var brightPixels = 0
        var count = 0

        for (gx in 1..grid) {
            val x = (w * gx / (grid + 1)).coerceIn(0, w - 1)
            for (gy in 1..grid) {
                val y = (h * gy / (grid + 1)).coerceIn(0, h - 1)
                val pixel = bmp.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                totalLum += lum
                if (lum > 28) brightPixels++
                count++
            }
        }
        val avgLum = totalLum / count
        return avgLum < 14 && brightPixels < 3
    }

    /** برش و تغییر سایز متناسب با کادر 112dp × 72dp */
    private fun scaleCenterCrop(src: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val srcW = src.width
        val srcH = src.height
        if (srcW <= 0 || srcH <= 0) return src
        if (srcW == targetW && srcH == targetH) return src

        val scale = max(targetW.toFloat() / srcW, targetH.toFloat() / srcH)
        val scaledW = (scale * srcW).toInt().coerceAtLeast(targetW)
        val scaledH = (scale * srcH).toInt().coerceAtLeast(targetH)

        val scaled = if (scaledW == srcW && scaledH == srcH) {
            src
        } else {
            Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        }

        val x = ((scaled.width - targetW) / 2).coerceAtLeast(0)
        val y = ((scaled.height - targetH) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(
            scaled,
            x,
            y,
            targetW.coerceAtMost(scaled.width - x),
            targetH.coerceAtMost(scaled.height - y)
        )
        if (scaled !== src && scaled !== cropped) {
            scaled.recycle()
        }
        return cropped
    }

    private fun loadVideos() {
        binding.permissionView.isVisible = false
        binding.emptyView.isVisible = false
        binding.loading.isVisible = true
        val appCtx = requireContext().applicationContext
        scanner.execute {
            val result = VideoScanner.scan(appCtx)
            // پیش‌بارگذاری فوری تصاویر بندانگشتی ذخیره‌شده روی دیسک به درون رم
            preloadDiskThumbnails(appCtx, result)
            if (!isAdded) return@execute
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                folders = result
                loadedOnce = true
                val ctx = requireContext()
                newVideoIds = WatchTracker.updateKnownIds(
                    ctx, result.asSequence().flatMap { it.videos }.map { it.id }.toSet())
                lastPlayedId = WatchTracker.lastPlayedId(ctx)
                updateFab()
                binding.loading.isVisible = false
                if (openFolder != null) {
                    val updated = folders.find { it.name == openFolder!!.name }
                    if (updated != null) showVideos(updated) else showFolders()
                } else {
                    showFolders()
                }
            }
        }
    }

    private fun showFolders() {
        openFolder = null
        backCallback.isEnabled = false
        binding.toolbar.navigationIcon = null
        val adapter = binding.videoList.adapter as BrowserAdapter
        val q = searchQuery
        if (q.isEmpty()) {
            binding.toolbar.title = getString(R.string.browser_folders)
            binding.toolbar.subtitle = null
            val list = sortedFolders()
            adapter.items = list.map { BrowserItem.FolderRow(it) }
            binding.emptyView.isVisible = list.isEmpty()
        } else {
            // جستجو: ویدیوهای همه پوشه‌ها (نه فقط اسم پوشه)
            binding.toolbar.title = getString(R.string.browser_search_results)
            binding.toolbar.subtitle = q
            val list = sortVideoList(folders.flatMap { it.videos })
                .filter { it.name.contains(q, ignoreCase = true) }
            adapter.items = list.map { BrowserItem.VideoRow(it) }
            binding.emptyView.isVisible = list.isEmpty()
        }
        binding.videoList.scrollToPosition(0)
    }

    private fun showVideos(folder: VideoScanner.Folder) {
        openFolder = folder
        backCallback.isEnabled = true
        binding.toolbar.title = folder.name
        binding.toolbar.subtitle = resources.getQuantityString(
            R.plurals.browser_videos_count, folder.videos.size, folder.videos.size)
        binding.toolbar.navigationIcon =
            ContextCompat.getDrawable(requireContext(), R.drawable.ic_back_24dp)
        val adapter = binding.videoList.adapter as BrowserAdapter
        val q = searchQuery
        val list = sortedVideos(folder)
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
        adapter.items = list.map { BrowserItem.VideoRow(it) }
        binding.emptyView.isVisible = list.isEmpty()
        binding.videoList.scrollToPosition(0)
    }

    private fun showSortDialog() {
        val criteria = arrayOf(SortBy.TITLE, SortBy.DATE, SortBy.DURATION, SortBy.SIZE)
        val labels = arrayOf(
            getString(R.string.sort_title),
            getString(R.string.sort_date),
            getString(R.string.sort_duration),
            getString(R.string.sort_size)
        )
        var selected = criteria.indexOf(sortBy).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.sort_by)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(R.string.dialog_ok) { d, _ ->
                sortBy = criteria[selected]
                // تایتل: پیش‌فرض صعودی (A اول) — بقیه: نزولی (جدیدترین اول)
                if (sortBy == SortBy.TITLE) sortDescending = false
                saveSortPref()
                refreshDisplay()
                d.dismiss()
            }
            .setNeutralButton(
                if (sortBy == SortBy.TITLE) {
                    if (sortDescending) R.string.sort_order_za else R.string.sort_order_az
                } else {
                    if (sortDescending) R.string.sort_order_newest_first else R.string.sort_order_oldest_first
                }
            ) { d, _ ->
                sortDescending = !sortDescending
                saveSortPref()
                refreshDisplay()
                d.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun refreshDisplay() {
        val folder = openFolder
        if (folder != null) {
            val updated = folders.find { it.name == folder.name }
            if (updated != null) showVideos(updated) else showFolders()
        } else {
            showFolders()
        }
    }

    private fun showPermissionView() {
        binding.loading.isVisible = false
        binding.emptyView.isVisible = false
        binding.permissionView.isVisible = true
    }

    // ---------- اکشن‌های قبلی (منوی تولبار) ----------

    private fun openLegacyFilePicker() {
        val i = Intent(context, FilePickerActivity::class.java)
        i.putExtra("skip", FilePickerActivity.FILE_PICKER)
        i.putExtra("allow_dir", true)
        if (lastPath != "")
            i.putExtra("default_path", lastPath)
        filePickerLauncher.launch(i)
    }

    private fun openDocumentTree() {
        try {
            documentTreeOpener.launch(null)
        } catch (e: ActivityNotFoundException) {
        }
    }

    private fun openUrlDialog() {
        val helper = Utils.OpenUrlDialog(requireContext())
        with (helper) {
            builder.setPositiveButton(R.string.dialog_ok) { _, _ ->
                playFile(helper.text)
            }
            builder.setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
            create().show()
        }
    }

    private fun onVideoTap(video: VideoScanner.Video) {
        val ctx = requireContext()
        val st = WatchTracker.state(ctx, video.id)
        val canResume = st != null && !st.watched && st.positionMs > 10_000 &&
                (st.durationMs <= 0 || st.positionMs < st.durationMs * 0.95)
        if (canResume) {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.resume_title)
                .setMessage(getString(
                    R.string.resume_message,
                    VideoScanner.formatDuration(st!!.positionMs)))
                .setPositiveButton(R.string.resume_continue) { d, _ ->
                    playFile(video.uri.toString(), st.positionMs)
                    d.dismiss()
                }
                .setNegativeButton(R.string.resume_from_start) { d, _ ->
                    WatchTracker.clearPosition(ctx, video.id)
                    playFile(video.uri.toString(), 0L)
                    d.dismiss()
                }
                .show()
        } else {
            playFile(video.uri.toString(), 0L)
        }
    }

    private fun playFile(filepath: String, resumeMs: Long = 0L) {
        val i: Intent
        if (filepath.startsWith("content://")) {
            i = Intent(Intent.ACTION_VIEW, Uri.parse(filepath))
        } else {
            i = Intent()
            i.putExtra("filepath", filepath)
        }
        i.setClass(requireContext(), MPVActivity::class.java)
        if (resumeMs > 0) i.putExtra("resume_position_ms", resumeMs)
        playerLauncher.launch(i)
    }

    override fun onDestroy() {
        scanner.shutdown()
        diskCacheExecutor.shutdown()
        thumbDecodeExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainScreenFragment"
        private const val THUMB_WIDTH = 560
        private const val THUMB_HEIGHT = 360
    }
}
