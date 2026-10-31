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
import android.graphics.drawable.GradientDrawable
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
    // تردپول سریع برای خواندن فوری بندانگشتی‌های ذخیره‌شده از حافظه دائمی
    private val diskCacheExecutor = Executors.newFixedThreadPool(4)
    // تردپول برای استخراج فریم ویدیوهای جدید
    private val thumbDecodeExecutor = Executors.newFixedThreadPool(2)

    /** وضعیت تماشا (به سبک MX Player) */
    private var newVideoIds: Set<Long> = emptySet()
    private var lastPlayedId: Long? = null

    /** متن جستجو در مرورگر */
    private var searchQuery: String = ""
    private var searchMenuItem: MenuItem? = null

    /** کش بندانگشتی‌ها در رم: حداکثر ~32 مگابایت */
    private val thumbCache = object : LruCache<Long, Bitmap>(32 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }

    /** ویدیوهایی که در حال حاضر در صف ساخت بندانگشتی هستند */
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

    /** لیبل دکمه تغییر جهت، متناسب با نوع مرتب‌سازی */
    private fun directionLabelRes(criterion: SortBy): Int = when (criterion) {
        SortBy.TITLE -> if (sortDescending) R.string.sort_order_za else R.string.sort_order_az
        SortBy.DATE -> if (sortDescending) R.string.sort_order_newest_first else R.string.sort_order_oldest_first
        SortBy.DURATION -> if (sortDescending) R.string.sort_order_longest_first else R.string.sort_order_shortest_first
        SortBy.SIZE -> if (sortDescending) R.string.sort_order_largest_first else R.string.sort_order_smallest_first
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // اگر سرچ باز است، اول سرچ بسته شود نه خروج از برنامه
            val si = searchMenuItem
            if (si != null && si.isActionViewExpanded) {
                si.collapseActionView()
                return
            }
            if (openFolder != null) showFolders() else {
                isEnabled = false
                requireActivity().onBackPressed()
            }
        }
    }

    private fun updateBackCallback() {
        backCallback.isEnabled =
            openFolder != null || searchMenuItem?.isActionViewExpanded == true
    }

    /** تنظیم رنگ متن، رنگ هایلایت سلکت، دستگیره‌های انتخاب و کرسر سفید چشمک‌زن */
    private fun styleSearchEditText(searchView: SearchView): EditText? {
        if (!isAdded) return null
        val et = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text) ?: return null

        et.setTextColor(Color.WHITE)
        et.setHintTextColor(Color.parseColor("#B3FFFFFF"))
        // رنگ آبی فیروزه‌ای نیمه‌شفاف و کاملاً مشخص برای پس‌زمینه متن سلکت‌شده روی تولبار بنفش
        et.highlightColor = Color.parseColor("#8800E5FF")

        // ساخت کرسر سفید مستقل از تم (غیرقابل تغییر رنگ توسط MIUI/Android)
        try {
            val density = resources.displayMetrics.density
            val cursorDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                setSize((2f * density).toInt().coerceAtLeast(2), (22f * density).toInt())
                setTintList(null)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                et.textCursorDrawable = cursorDrawable
                // روشن کردن رنگ دستگیره‌های چپ، راست و وسطِ انتخاب متن
                val handleColor = Color.parseColor("#00E5FF")
                et.textSelectHandle?.mutate()?.let {
                    it.setTint(handleColor)
                    et.setTextSelectHandle(it)
                }
                et.textSelectHandleLeft?.mutate()?.let {
                    it.setTint(handleColor)
                    et.setTextSelectHandleLeft(it)
                }
                et.textSelectHandleRight?.mutate()?.let {
                    it.setTint(handleColor)
                    et.setTextSelectHandleRight(it)
                }
            } else {
                val editorField = TextView::class.java.getDeclaredField("mEditor")
                editorField.isAccessible = true
                val editor = editorField.get(et)
                val cursorField = editor.javaClass.getDeclaredField("mCursorDrawable")
                cursorField.isAccessible = true
                cursorField.set(editor, arrayOf(cursorDrawable, cursorDrawable))
            }
        } catch (_: Exception) { }

        return et
    }

    /** فوکوس روی فیلد سرچ، باز کردن کیبورد و راه‌اندازی مجدد چشمک‌زدن کرسر */
    private fun focusSearchField(searchView: SearchView) {
        val et = styleSearchEditText(searchView) ?: return
        et.isFocusable = true
        et.isFocusableInTouchMode = true
        et.requestFocus()
        // خاموش و روشن کردن isCursorVisible باعث ریست شدن تایمر Blink در Editor اندروید می‌شود
        et.isCursorVisible = false
        et.isCursorVisible = true
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
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
        searchMenuItem = searchItem
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)

        // اعمال اولیه استایل کرسر و رنگ انتخاب متن به محض ساخته شدن SearchView
        styleSearchEditText(searchView)

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = true
            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText?.trim() ?: ""
                refreshDisplay()
                // حفظ فوکوس و نشانگر چشمک‌زن پس از به‌روزرسانی لیست ویدیوها
                if (searchItem.isActionViewExpanded) {
                    val et = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
                    if (et != null && !et.hasFocus()) {
                        et.requestFocus()
                    }
                }
                return true
            }
        })
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                searchView.isIconified = false
                searchView.post { focusSearchField(searchView) }
                searchView.postDelayed({ focusSearchField(searchView) }, 120)
                updateBackCallback()
                return true
            }
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchQuery = ""
                refreshDisplay()
                updateBackCallback()
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
        newVideoIds = WatchTracker.getNewIds(ctx)
        updateFab()
        // رفرش کامل لیست برای اطمینان از نمایش وضعیت به‌روز (به‌جای فقط notifyDataSetChanged)
        if (openFolder != null) {
            val updated = folders.find { it.name == openFolder!!.name }
            if (updated != null) showVideos(updated) else showFolders()
        } else {
            // فقط اگر لیست خالی نیست، دوباره نمایش بده تا اسکرول به‌هم نریزد
            if (folders.isNotEmpty()) showFolders()
            else (binding.videoList.adapter as? BrowserAdapter)?.notifyDataSetChanged()
        }
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
                val ctx = itemView.context
                name.text = folder.name
                count.text = resources.getQuantityString(
                    R.plurals.browser_videos_count, folder.videos.size, folder.videos.size)
                size.text = VideoScanner.formatSize(ctx, folder.totalSize)

                val lastVid = if (lastPlayedId != null) {
                    folder.videos.find { it.id == lastPlayedId }
                } else null
                val isLastInProgress = lastVid != null &&
                        WatchTracker.state(ctx, lastVid.id)?.watched != true
                val allWatched = folder.videos.isNotEmpty() &&
                        folder.videos.all { WatchTracker.state(ctx, it.id)?.watched == true }

                name.setTextColor(
                    ContextCompat.getColor(ctx, when {
                        isLastInProgress -> R.color.watch_last
                        allWatched -> R.color.watch_done
                        else -> android.R.color.white
                    })
                )
                name.alpha = if (allWatched && !isLastInProgress) 0.65f else 1.0f
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
                val ctx = itemView.context
                name.text = video.name
                thumbDuration.text = VideoScanner.formatDuration(video.durationMs)
                badgeSub.isVisible = video.hasSubtitle
                badgeDub.isVisible = video.hasDubbedAudio
                badgeNew.isVisible = video.id in newVideoIds
                badgeSize.text = VideoScanner.formatSize(ctx, video.sizeBytes)
                val dateStr = VideoScanner.formatDate(video.dateModifiedSec)
                badgeDate.isVisible = dateStr.isNotEmpty()
                badgeDate.text = dateStr

                val st = WatchTracker.state(ctx, video.id)
                val watched = st?.watched == true
                val isPlayingOrLast = video.id == lastPlayedId && !watched

                name.setTextColor(
                    ContextCompat.getColor(ctx, when {
                        isPlayingOrLast -> R.color.watch_last
                        watched -> R.color.watch_done
                        else -> android.R.color.white
                    })
                )
                // کمرنگ شدن محسوس آیتم‌های تمام‌شده و بازگشت به شفافیت کامل در صورت پخش مجدد
                name.alpha = if (watched) 0.6f else 1.0f
                thumb.alpha = if (watched) 0.55f else 1.0f

                loadThumb(video, thumb)
                itemView.setOnClickListener { onVideoTap(video) }
            }
        }
    }

    // ---------- مدیریت پیشرفته و دائمی بندانگشتی‌ها (پشتیبانی از 10-bit x265 و 4K HDR10+) ----------

    /**
     * کش دیسک مشترک با پلیر (ThumbDiskCache — حافظه دائمی filesDir تا با بستن
     * برنامه یا پاکسازی کش سیستم حذف نشود). کلید فقط شناسه ویدیو است.
     */
    private fun getDiskCacheFile(appCtx: Context, video: VideoScanner.Video): File =
        ThumbDiskCache.file(appCtx, video.id)

    private fun preloadDiskThumbnails(appCtx: Context, allFolders: List<VideoScanner.Folder>) {
        diskCacheExecutor.execute {
            for (folder in allFolders) {
                for (video in folder.videos) {
                    if (thumbCache.get(video.id) != null) continue
                    ThumbDiskCache.load(appCtx, video.id)?.let { bmp ->
                        thumbCache.put(video.id, bmp)
                    }
                }
            }
        }
    }

    private fun loadThumb(video: VideoScanner.Video, imageView: ImageView) {
        imageView.tag = video.id

        thumbCache.get(video.id)?.let {
            imageView.setImageBitmap(it)
            return
        }

        imageView.setImageDrawable(null)
        if (video.id in thumbFailed) return

        val appCtx = requireContext().applicationContext
        val cacheFile = getDiskCacheFile(appCtx, video)

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
                    cacheFile.delete()
                    extractAndSaveThumb(appCtx, video, cacheFile, imageView)
                }
            }
            return
        }

        extractAndSaveThumb(appCtx, video, cacheFile, imageView)
    }

    private fun extractAndSaveThumb(
        appCtx: Context,
        video: VideoScanner.Video,
        cacheFile: File,
        imageView: ImageView
    ) {
        if (!thumbInProgress.add(video.id)) return

        thumbDecodeExecutor.execute {
            try {
                // ۱. تلاش اول با MediaMetadataRetriever (سریع برای ویدیوهای ۸ بیتی استاندارد)
                var rawBmp: Bitmap? = retrieverFrame(appCtx, video)

                // ۲. تلاش دوم با MediaCodec در حالت ByteBuffer پردازنده (پشتیبانی از 10-bit P010 و 8-bit)
                if (rawBmp == null || isMostlyBlack(rawBmp)) {
                    val codecBmp = decodeFrameWithCpuMediaCodec(appCtx, video)
                    if (codecBmp != null && (!isMostlyBlack(codecBmp) || rawBmp == null)) {
                        if (rawBmp != null && rawBmp !== codecBmp) rawBmp.recycle()
                        rawBmp = codecBmp
                    }
                }

                // ۳. تلاش نهایی با بندانگشتی پیش‌فرض سیستم
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
                        Log.w(TAG, "Failed to save thumbnail: ${video.name}", e)
                    }

                    imageView.post {
                        if (isAdded && imageView.tag == video.id) {
                            imageView.setImageBitmap(finalBmp)
                        } else if (isAdded) {
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

    /** مرحله ۱: استخراج فریم با MediaMetadataRetriever */
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
                    15_000_000L
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
        } catch (_: Exception) {
            null
        }
    }

    /**
     * مرحله ۲: دیکد در حالت ByteBuffer پردازنده (بدون Surface گرافیکی)
     * با ارسال فوری BUFFER_FLAG_END_OF_STREAM پس از فریم کلیدی برای دور زدن بافر B-Frame در انکودهای PSA
     * و تبدیل مستقیم فرمت‌های ۱۰ بیتی (P010 / I010) و ۸ بیتی (YUV_420_888) به Bitmap.
     */
    private fun decodeFrameWithCpuMediaCodec(appCtx: Context, video: VideoScanner.Video): Bitmap? {
        val extractor = MediaExtractor()
        var pfd: android.os.ParcelFileDescriptor? = null
        var codec: MediaCodec? = null

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
            var origFormat: MediaFormat? = null
            var origMime = ""
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) {
                    videoTrackIndex = i
                    origFormat = f
                    origMime = m
                    break
                }
            }
            if (videoTrackIndex < 0 || origFormat == null) return null

            extractor.selectTrack(videoTrackIndex)

            val width = origFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = origFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = when {
                video.durationMs > 0 -> video.durationMs * 1000L
                origFormat.containsKey(MediaFormat.KEY_DURATION) -> origFormat.getLong(MediaFormat.KEY_DURATION)
                else -> 0L
            }

            // تبدیل Dolby Vision به HEVC و ساخت فرمت تمیز بدون تگ‌های پروفایل HDR10+ که باعث خطای configure می‌شوند
            val targetMime = if (origMime == "video/dolby-vision") {
                MediaFormat.MIMETYPE_VIDEO_HEVC
            } else {
                origMime
            }

            val cleanFormat = MediaFormat.createVideoFormat(targetMime, width, height)
            for (csdKey in arrayOf("csd-0", "csd-1", "csd-2")) {
                if (origFormat.containsKey(csdKey)) {
                    origFormat.getByteBuffer(csdKey)?.let { buf ->
                        cleanFormat.setByteBuffer(csdKey, buf.duplicate())
                    }
                }
            }

            codec = if (targetMime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                try {
                    MediaCodec.createByCodecName("c2.android.hevc.decoder")
                } catch (_: Exception) {
                    try {
                        MediaCodec.createByCodecName("OMX.google.hevc.decoder")
                    } catch (_: Exception) {
                        MediaCodec.createDecoderByType(targetMime)
                    }
                }
            } else {
                MediaCodec.createDecoderByType(targetMime)
            }

            try {
                codec.configure(cleanFormat, null, null, 0)
            } catch (_: Exception) {
                codec.configure(origFormat, null, null, 0)
            }
            codec.start()

            val seekTargetsUs = if (durationUs > 10_000_000L) {
                longArrayOf((durationUs * 0.15).toLong(), (durationUs * 0.28).toLong(), 0L)
            } else {
                longArrayOf(0L)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var fallbackBmp: Bitmap? = null

            for (seekUs in seekTargetsUs) {
                // ابتدا با ارسال فقط ۱ فریم کلیدی + EOS فوری تست می‌کنیم (بسیار سریع)،
                // و اگر فریم کلیدی CRA بود که نیاز به چند بسته بیشتر داشت، تا ۱۲ بسته می‌فرستیم.
                for (maxPackets in intArrayOf(2, 12)) {
                    try {
                        codec.flush()
                        extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        if (extractor.sampleTime < 0L && seekUs > 0L) {
                            extractor.seekTo(0L, MediaExtractor.SEEK_TO_NEXT_SYNC)
                        }
                    } catch (_: Exception) {
                        extractor.seekTo(0L, MediaExtractor.SEEK_TO_NEXT_SYNC)
                    }

                    var packetsSent = 0
                    var inputEos = false
                    var outputDone = false
                    var gotFrameInPass = false
                    var loops = 0

                    while (!outputDone && loops < 80) {
                        loops++
                        if (!inputEos) {
                            val inIdx = codec.dequeueInputBuffer(15_000L)
                            if (inIdx >= 0) {
                                if (packetsSent >= maxPackets) {
                                    // ارسال فوری END_OF_STREAM تا دیکدر بدون انتظار برای 8 فریم B-Frame، فریم کلیدی را خروجی دهد
                                    codec.queueInputBuffer(
                                        inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputEos = true
                                } else {
                                    val inBuf = codec.getInputBuffer(inIdx)
                                    val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                                    if (sampleSize < 0) {
                                        codec.queueInputBuffer(
                                            inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                        inputEos = true
                                    } else {
                                        val pts = extractor.sampleTime.coerceAtLeast(0L)
                                        codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                        packetsSent++
                                        extractor.advance()
                                    }
                                }
                            }
                        }

                        val outIdx = codec.dequeueOutputBuffer(bufferInfo, 20_000L)
                        if (outIdx >= 0) {
                            if (bufferInfo.size > 0) {
                                val bmp = convertOutputToBitmap(codec, outIdx, bufferInfo)
                                codec.releaseOutputBuffer(outIdx, false)
                                if (bmp != null) {
                                    gotFrameInPass = true
                                    if (!isMostlyBlack(bmp)) {
                                        fallbackBmp?.recycle()
                                        return bmp
                                    }
                                    if (fallbackBmp == null) fallbackBmp = bmp else bmp.recycle()
                                }
                                outputDone = true
                            } else {
                                codec.releaseOutputBuffer(outIdx, false)
                            }
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                    if (gotFrameInPass) break
                }
            }
            fallbackBmp
        } catch (e: Exception) {
            null
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            try { extractor.release() } catch (_: Exception) { }
            try { pfd?.close() } catch (_: Exception) { }
        }
    }

    /** تبدیل بافر خروجی MediaCodec (چه 8-bit YUV_420_888 و چه 10-bit P010 / I010) به Bitmap */
    private fun convertOutputToBitmap(
        codec: MediaCodec,
        outIdx: Int,
        bufferInfo: MediaCodec.BufferInfo
    ): Bitmap? {
        try {
            val image = codec.getOutputImage(outIdx)
            if (image != null) {
                try {
                    if (image.planes.size >= 3) {
                        return yuvImageToBitmap(image)
                    }
                } finally {
                    image.close()
                }
            }
        } catch (_: Exception) { }

        return try {
            val outBuf = codec.getOutputBuffer(outIdx) ?: return null
            val fmt = codec.outputFormat
            rawYuvBufferToBitmap(outBuf, bufferInfo, fmt)
        } catch (_: Exception) {
            null
        }
    }

    /** تبدیل Image سه صفحه‌ای (8-bit YUV_420_888 یا 10-bit YCBCR_P010) به Bitmap */
    private fun yuvImageToBitmap(image: Image): Bitmap? {
        val crop = image.cropRect
        val srcW = if (crop != null && crop.width() > 0) crop.width() else image.width
        val srcH = if (crop != null && crop.height() > 0) crop.height() else image.height
        val cropLeft = crop?.left ?: 0
        val cropTop = crop?.top ?: 0
        if (srcW <= 0 || srcH <= 0) return null

        val planes = image.planes
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRowStride = planes[0].rowStride
        val yPixStride = planes[0].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixStride = planes[2].pixelStride

        // تشخیص ۱۰ بیتی بودن (فرمت 54 = YCBCR_P010 یا گام پیکسلی Y برابر ۲ بایت)
        val is16Bit = image.format == 54 || yPixStride >= 2 && (yRowStride >= image.width * 2)

        val dstW = THUMB_WIDTH
        val dstH = (THUMB_WIDTH.toFloat() * srcH / srcW).toInt().coerceIn(1, THUMB_WIDTH * 2)
        val pixels = IntArray(dstW * dstH)

        var shift10 = 8
        if (is16Bit) {
            // بررسی تراز بودن ۱۰ بیت در بیت‌های بالا (P010: shift 8) یا بیت‌های پایین (I010: shift 2)
            var maxSample = 0
            val midY = cropTop + srcH / 2
            for (i in 0 until 16) {
                val sx = cropLeft + (srcW * i / 16)
                val pos = midY * yRowStride + sx * yPixStride
                if (pos + 1 < yBuf.limit()) {
                    val v16 = (yBuf.get(pos).toInt() and 0xFF) or
                            ((yBuf.get(pos + 1).toInt() and 0xFF) shl 8)
                    if (v16 > maxSample) maxSample = v16
                }
            }
            shift10 = if (maxSample > 1024) 8 else 2
        }

        var outIdx = 0
        for (dy in 0 until dstH) {
            val sy = cropTop + (dy * srcH / dstH)
            val yRowOffset = sy * yRowStride
            val uvY = sy shr 1
            val uRowOffset = uvY * uRowStride
            val vRowOffset = uvY * vRowStride

            for (dx in 0 until dstW) {
                val sx = cropLeft + (dx * srcW / dstW)
                val uvX = sx shr 1

                val yVal: Int
                val uVal: Int
                val vVal: Int

                if (is16Bit) {
                    val yPos = yRowOffset + sx * yPixStride
                    val uPos = uRowOffset + uvX * uPixStride
                    val vPos = vRowOffset + uvX * vPixStride
                    val y16 = (yBuf.get(yPos).toInt() and 0xFF) or ((yBuf.get(yPos + 1).toInt() and 0xFF) shl 8)
                    val u16 = (uBuf.get(uPos).toInt() and 0xFF) or ((uBuf.get(uPos + 1).toInt() and 0xFF) shl 8)
                    val v16 = (vBuf.get(vPos).toInt() and 0xFF) or ((vBuf.get(vPos + 1).toInt() and 0xFF) shl 8)
                    yVal = (y16 ushr shift10) and 0xFF
                    uVal = (u16 ushr shift10) and 0xFF
                    vVal = (v16 ushr shift10) and 0xFF
                } else {
                    yVal = yBuf.get(yRowOffset + sx * yPixStride).toInt() and 0xFF
                    uVal = uBuf.get(uRowOffset + uvX * uPixStride).toInt() and 0xFF
                    vVal = vBuf.get(vRowOffset + uvX * vPixStride).toInt() and 0xFF
                }

                pixels[outIdx++] = yuvToArgb(yVal, uVal, vVal)
            }
        }

        return Bitmap.createBitmap(pixels, dstW, dstH, Bitmap.Config.ARGB_8888)
    }

    /** تبدیل ByteBuffer خام (برای اندرویدهای قدیمی‌تر که getOutputImage روی P010 مقدار null می‌دهد) */
    private fun rawYuvBufferToBitmap(
        buf: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        fmt: MediaFormat
    ): Bitmap? {
        val width = fmt.getInteger(MediaFormat.KEY_WIDTH)
        val height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
        if (width <= 0 || height <= 0) return null

        val cropLeft = if (fmt.containsKey("crop-left")) fmt.getInteger("crop-left") else 0
        val cropTop = if (fmt.containsKey("crop-top")) fmt.getInteger("crop-top") else 0
        val cropRight = if (fmt.containsKey("crop-right")) fmt.getInteger("crop-right") else width - 1
        val cropBottom = if (fmt.containsKey("crop-bottom")) fmt.getInteger("crop-bottom") else height - 1
        val srcW = (cropRight - cropLeft + 1).coerceIn(1, width)
        val srcH = (cropBottom - cropTop + 1).coerceIn(1, height)

        val rawStride = if (fmt.containsKey(MediaFormat.KEY_STRIDE)) fmt.getInteger(MediaFormat.KEY_STRIDE) else width
        val sliceHeight = if (fmt.containsKey(MediaFormat.KEY_SLICE_HEIGHT)) {
            fmt.getInteger(MediaFormat.KEY_SLICE_HEIGHT).coerceAtLeast(height)
        } else height
        val colorFormat = if (fmt.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
            fmt.getInteger(MediaFormat.KEY_COLOR_FORMAT)
        } else 0

        val is16Bit = colorFormat == 54 || bufferInfo.size >= width * height * 2
        val dstW = THUMB_WIDTH
        val dstH = (THUMB_WIDTH.toFloat() * srcH / srcW).toInt().coerceIn(1, THUMB_WIDTH * 2)
        val pixels = IntArray(dstW * dstH)
        val baseOffset = bufferInfo.offset

        if (is16Bit) {
            val rowStrideBytes = if (rawStride >= width * 2) rawStride else rawStride * 2
            val uvBase = baseOffset + rowStrideBytes * sliceHeight

            var maxSample = 0
            val testRow = baseOffset + (cropTop + srcH / 2) * rowStrideBytes
            for (i in 0 until 16) {
                val pos = testRow + (cropLeft + srcW * i / 16) * 2
                if (pos + 1 < buf.limit()) {
                    val v16 = (buf.get(pos).toInt() and 0xFF) or ((buf.get(pos + 1).toInt() and 0xFF) shl 8)
                    if (v16 > maxSample) maxSample = v16
                }
            }
            val shift10 = if (maxSample > 1024) 8 else 2

            var outIdx = 0
            for (dy in 0 until dstH) {
                val sy = cropTop + (dy * srcH / dstH)
                val yRow = baseOffset + sy * rowStrideBytes
                val uvRow = uvBase + (sy shr 1) * rowStrideBytes
                for (dx in 0 until dstW) {
                    val sx = cropLeft + (dx * srcW / dstW)
                    val yPos = yRow + sx * 2
                    val uvPos = uvRow + (sx and -2) * 2
                    if (yPos + 1 >= buf.limit() || uvPos + 3 >= buf.limit()) {
                        pixels[outIdx++] = Color.BLACK
                        continue
                    }
                    val y16 = (buf.get(yPos).toInt() and 0xFF) or ((buf.get(yPos + 1).toInt() and 0xFF) shl 8)
                    val u16 = (buf.get(uvPos).toInt() and 0xFF) or ((buf.get(uvPos + 1).toInt() and 0xFF) shl 8)
                    val v16 = (buf.get(uvPos + 2).toInt() and 0xFF) or ((buf.get(uvPos + 3).toInt() and 0xFF) shl 8)
                    pixels[outIdx++] = yuvToArgb(
                        (y16 ushr shift10) and 0xFF,
                        (u16 ushr shift10) and 0xFF,
                        (v16 ushr shift10) and 0xFF
                    )
                }
            }
        } else {
            val rowStride = rawStride.coerceAtLeast(width)
            val ySize = rowStride * sliceHeight
            val isSemiPlanar = colorFormat == 21 || colorFormat == 0x7FA30C00 || colorFormat == 0x7FA30C04
            val uBase = baseOffset + ySize
            val vBase = uBase + (ySize / 4)

            var outIdx = 0
            for (dy in 0 until dstH) {
                val sy = cropTop + (dy * srcH / dstH)
                val yRow = baseOffset + sy * rowStride
                for (dx in 0 until dstW) {
                    val sx = cropLeft + (dx * srcW / dstW)
                    val yPos = yRow + sx
                    if (yPos >= buf.limit()) {
                        pixels[outIdx++] = Color.BLACK
                        continue
                    }
                    val yVal = buf.get(yPos).toInt() and 0xFF
                    val uVal: Int
                    val vVal: Int
                    if (isSemiPlanar) {
                        val uvPos = uBase + (sy shr 1) * rowStride + (sx and -2)
                        if (uvPos + 1 < buf.limit()) {
                            uVal = buf.get(uvPos).toInt() and 0xFF
                            vVal = buf.get(uvPos + 1).toInt() and 0xFF
                        } else {
                            uVal = 128; vVal = 128
                        }
                    } else {
                        val uvOffset = (sy shr 1) * (rowStride shr 1) + (sx shr 1)
                        if (vBase + uvOffset < buf.limit()) {
                            uVal = buf.get(uBase + uvOffset).toInt() and 0xFF
                            vVal = buf.get(vBase + uvOffset).toInt() and 0xFF
                        } else {
                            uVal = 128; vVal = 128
                        }
                    }
                    pixels[outIdx++] = yuvToArgb(yVal, uVal, vVal)
                }
            }
        }

        return Bitmap.createBitmap(pixels, dstW, dstH, Bitmap.Config.ARGB_8888)
    }

    private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val c = (y - 16).coerceAtLeast(0)
        val d = u - 128
        val e = v - 128
        val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
        val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
        val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
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
        val y = ((scaled.height - targetW) / 2).coerceAtLeast(0)
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
        binding.loading.isVisible = false

        val appCtx = requireContext().applicationContext

        // ۱. اگر کش در رم نبود، کش فوری دیسک را بخوان تا در فریم اول صفحه سیاه نماند
        if (cachedFolders.isEmpty()) {
            val diskCached = VideoScanner.loadDiskCache(appCtx)
            if (diskCached.isNotEmpty()) {
                cachedFolders = diskCached
            }
        }

        // نمایش فوری بدون هیچ مکثی در صورت وجود کش (رم یا دیسک)
        if (cachedFolders.isNotEmpty() && folders.isEmpty()) {
            folders = cachedFolders
            loadedOnce = true
            lastPlayedId = WatchTracker.lastPlayedId(appCtx)
            updateFab()
            showFolders()
        }

        scanner.execute {
            // ۲. اسکن سریع اولیه فقط از روی MediaStore (بدون درگیر کردن File.listFiles)
            val fastResult = VideoScanner.scanFast(appCtx)
            val hadInitialUI = cachedFolders.isNotEmpty()
            cachedFolders = fastResult

            if (!isAdded) return@execute

            // اگر دفعه اول نصب است و کش دیسک وجود نداشت، بلافاصله نتیجه سریع را رندر کن
            if (!hadInitialUI) {
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    folders = fastResult
                    loadedOnce = true
                    val ctx = requireContext()
                    newVideoIds = WatchTracker.updateKnownIds(
                        ctx, fastResult.asSequence().flatMap { it.videos }.map { it.id }.toSet()
                    )
                    lastPlayedId = WatchTracker.lastPlayedId(ctx)
                    updateFab()
                    if (openFolder != null) {
                        val updated = folders.find { it.name == openFolder!!.name }
                        if (updated != null) showVideos(updated) else showFolders()
                    } else {
                        showFolders()
                    }
                }
            }

            // ۳. تکمیل اطلاعات زیرنویس‌های هم‌نام (.srt/.ass) و ذخیره در کش دیسک در پس‌زمینه
            val fullResult = VideoScanner.enrichAndCache(appCtx, fastResult)
            cachedFolders = fullResult

            if (isAdded) {
                requireActivity().runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    folders = fullResult
                    loadedOnce = true
                    val ctx = requireContext()
                    newVideoIds = WatchTracker.updateKnownIds(
                        ctx, fullResult.asSequence().flatMap { it.videos }.map { it.id }.toSet()
                    )
                    lastPlayedId = WatchTracker.lastPlayedId(ctx)
                    updateFab()
                    if (openFolder != null) {
                        val updated = folders.find { it.name == openFolder!!.name }
                        if (updated != null) showVideos(updated) else showFolders()
                    } else {
                        showFolders()
                    }
                }
            }

            // ۴. پیش‌بارگذاری تامبنیل‌ها بعد از نمایش UI (هرگز نباید نمایش لیست را مسدود کند)
            preloadDiskThumbnails(appCtx, fullResult)
        }
    }

    private fun showFolders() {
        openFolder = null
        updateBackCallback()
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
        // هنگام تایپ در سرچ، فوکوس از روی کیبورد و کرسر برداشته نشود
        if (searchMenuItem?.isActionViewExpanded != true) {
            binding.videoList.scrollToPosition(0)
        }
    }

    private fun showVideos(folder: VideoScanner.Folder) {
        openFolder = folder
        updateBackCallback()
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
        // هنگام تایپ در سرچ، فوکوس از روی کیبورد و کرسر برداشته نشود
        if (searchMenuItem?.isActionViewExpanded != true) {
            binding.videoList.scrollToPosition(0)
        }
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
            .setSingleChoiceItems(labels, selected) { d, which ->
                selected = which
                // لیبل دکمه جهت، هم‌زمان با انتخاب نوع مرتب‌سازی به‌روز شود
                (d as? AlertDialog)?.getButton(AlertDialog.BUTTON_NEUTRAL)?.text =
                    getString(directionLabelRes(criteria[which]))
            }
            .setPositiveButton(R.string.dialog_ok) { d, _ ->
                sortBy = criteria[selected]
                // تایتل: پیش‌فرض صعودی (A اول) — بقیه: نزولی
                if (sortBy == SortBy.TITLE) sortDescending = false
                saveSortPref()
                refreshDisplay()
                d.dismiss()
            }
            .setNeutralButton(directionLabelRes(sortBy)) { d, _ ->
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
        val canResume = st != null && !st.watched && st.positionMs > 5_000L &&
                (st.durationMs <= 0L || st.positionMs < (st.durationMs * 0.92).toLong())

        if (canResume) {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.resume_title)
                .setMessage(getString(
                    R.string.resume_message,
                    VideoScanner.formatDuration(st!!.positionMs)))
                .setPositiveButton(R.string.resume_continue) { d, _ ->
                    playFile(video.uri.toString(), st.positionMs, video.name, video.id, video.durationMs)
                    d.dismiss()
                }
                .setNegativeButton(R.string.resume_from_start) { d, _ ->
                    WatchTracker.clearPosition(ctx, video.id)
                    playFile(video.uri.toString(), 0L, video.name, video.id, video.durationMs)
                    d.dismiss()
                }
                .show()
        } else {
            // اگر ویدیو قبلاً تمام شده بود (watched=true) و کاربر دوباره روی آن زد، وضعیتش به «در حال پخش» تغییر می‌کند
            if (st?.watched == true) {
                WatchTracker.clearPosition(ctx, video.id)
            }
            playFile(video.uri.toString(), 0L, video.name, video.id, video.durationMs)
        }
    }

    private fun playFile(
        filepath: String,
        resumeMs: Long = 0L,
        displayName: String? = null,
        videoId: Long = -1L,
        durationMs: Long = 0L
    ) {
        val i: Intent = if (filepath.startsWith("content://")) {
            Intent(Intent.ACTION_VIEW, Uri.parse(filepath))
        } else {
            Intent().apply { putExtra("filepath", filepath) }
        }
        i.setClass(requireContext(), MPVActivity::class.java)
        if (resumeMs > 0L) i.putExtra("resume_position_ms", resumeMs)
        if (!displayName.isNullOrEmpty()) i.putExtra("video_display_name", displayName)
        if (videoId >= 0L) i.putExtra("video_id", videoId)
        if (durationMs > 0L) i.putExtra("video_duration_ms", durationMs)
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
        @Volatile
        private var cachedFolders: List<VideoScanner.Folder> = emptyList()
    }
}
