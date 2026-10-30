package `is`.xyz.mpv

import `is`.xyz.mpv.preferences.PreferenceActivity
import `is`.xyz.mpv.databinding.FragmentMainScreenBinding
import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import java.util.concurrent.Executors

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
    private val thumbExecutor = Executors.newFixedThreadPool(3)

    /** وضعیت تماشا (به سبک MX Player) */
    private var newVideoIds: Set<Long> = emptySet()
    private var lastPlayedId: Long? = null

    /** متن جستجو در مرورگر */
    private var searchQuery: String = ""

    /** کش بندانگشتی‌ها: حداکثر ~16 مگابایت */
    private val thumbCache = object : LruCache<Long, Bitmap>(16 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
    }

    /** ویدیوهایی که ساخت بندانگشتی‌شان در این نشست ناموفق بود (تلاش مجدد بی‌فایده) */
    private val thumbFailed = mutableSetOf<Long>()

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

    private fun sortedVideos(folder: VideoScanner.Folder): List<VideoScanner.Video> {
        val cmp: Comparator<VideoScanner.Video> = when (sortBy) {
            SortBy.TITLE -> compareBy { it.name.lowercase() }
            SortBy.DATE -> compareBy { it.dateModifiedSec }
            SortBy.DURATION -> compareBy { it.durationMs }
            SortBy.SIZE -> compareBy { it.sizeBytes }
        }
        val sorted = folder.videos.sortedWith(cmp)
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
                // need to convert to playlist at this point
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
        // binding دستی چون لی‌اوت جدید دیگر شامل ویوهای قبلی نیست
        return inflater.inflate(R.layout.fragment_main_screen, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = FragmentMainScreenBinding.bind(view)

        Utils.handleInsetsAsPadding(binding.root)

        binding.videoList.layoutManager = LinearLayoutManager(requireContext())
        binding.videoList.adapter = BrowserAdapter()

        binding.toolbar.title = getString(R.string.browser_folders)
        binding.toolbar.inflateMenu(R.menu.browser_menu)
        // جستجو در مرورگر
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
                R.id.menu_refresh -> { loadVideos(); true }
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
        // اولین نمایش: اگر مجوز هست ولی هنوز لود نشده، لود کن
        if (hasPermission() && !loadedOnce &&
            binding.permissionView.isVisible) {
            loadVideos()
        } else if (loadedOnce) {
            // برگشت از پخش: وضعیت تماشا (ادامه/دیده‌شده/آخرین) را تازه کن
            refreshWatchStates()
        }
    }

    /** تازه‌سازی وضعیت تماشا بدون اسکن مجدد */
    private fun refreshWatchStates() {
        if (!isAdded) return
        val ctx = requireContext()
        lastPlayedId = WatchTracker.lastPlayedId(ctx)
        updateFab()
        (binding.videoList.adapter as? BrowserAdapter)?.notifyDataSetChanged()
    }

    /** دکمه شناور «آخرین ویدیو» به سبک MX Player */
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
                // آخرین پوشه پخش‌شده آبی می‌شود (مثل MX Player)
                val isLast = lastPlayedId != null &&
                        folder.videos.any { it.id == lastPlayedId }
                name.setTextColor(
                    ContextCompat.getColor(itemView.context,
                        if (isLast) R.color.watch_last else android.R.color.white))
                // بج New اگر ویدیوی جدید دارد
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
                // وضعیت تماشا: دیده‌شده خاکستری، آخرین ویدیو آبی (مثل MX Player)
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

    /** لود تنبل بندانگشتی با کش؛ فقط اگر ردیف هنوز همان ویدیو را نشان می‌دهد ست می‌شود */
    private fun loadThumb(video: VideoScanner.Video, imageView: ImageView) {
        imageView.tag = video.id
        imageView.setImageDrawable(null)
        thumbCache.get(video.id)?.let {
            imageView.setImageBitmap(it)
            return
        }
        if (video.id in thumbFailed) return
        val appCtx = requireContext().applicationContext
        thumbExecutor.execute {
            try {
                var bmp: Bitmap? = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        appCtx.contentResolver.loadThumbnail(video.uri, Size(480, 320), null)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Video.Thumbnails.getThumbnail(
                            appCtx.contentResolver, video.id,
                            MediaStore.Video.Thumbnails.MINI_KIND, null
                        )
                    }
                } catch (e: Exception) {
                    null
                }
                // راه دوم: فریم مستقیم از خود ویدیو (وقتی loadThumbnail ناکام می‌ماند)
                if (bmp == null) bmp = retrieverFrame(appCtx, video)
                if (bmp != null) {
                    thumbCache.put(video.id, bmp)
                    imageView.post {
                        if (imageView.tag == video.id) imageView.setImageBitmap(bmp)
                    }
                } else {
                    synchronized(thumbFailed) { thumbFailed.add(video.id) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "thumbnail failed: ${video.name}")
                synchronized(thumbFailed) { thumbFailed.add(video.id) }
            }
        }
    }

    /** گرفتن یک فریم از ویدیو با MediaMetadataRetriever (جایگزین loadThumbnail) */
    private fun retrieverFrame(appCtx: android.content.Context, video: VideoScanner.Video): Bitmap? {
        var r: MediaMetadataRetriever? = null
        return try {
            r = MediaMetadataRetriever()
            r.setDataSource(appCtx, video.uri)
            val atUs = if (video.durationMs > 0)
                (video.durationMs * 100L).coerceAtLeast(500_000L) // حدود ۱۰٪ ویدیو
            else 1_000_000L
            val frame = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            // کوچک‌سازی به سایز بندانگشتی برای مصرف حافظه کمتر
            if (frame != null && frame.width > 480) {
                val h = (480f * frame.height / frame.width).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(frame, 480, h, true)
                if (scaled !== frame) frame.recycle()
                scaled
            } else frame
        } catch (e: Exception) {
            null
        } finally {
            try { r?.release() } catch (e: Exception) { }
        }
    }

    private fun loadVideos() {
        binding.permissionView.isVisible = false
        binding.emptyView.isVisible = false
        binding.loading.isVisible = true
        scanner.execute {
            val result = VideoScanner.scan(requireContext())
            if (!isAdded) return@execute
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                folders = result
                loadedOnce = true
                // وضعیت تماشا: ویدیوهای جدید + آخرین ویدیو
                val ctx = requireContext()
                newVideoIds = WatchTracker.updateKnownIds(
                    ctx, result.asSequence().flatMap { it.videos }.map { it.id }.toSet())
                lastPlayedId = WatchTracker.lastPlayedId(ctx)
                updateFab()
                binding.loading.isVisible = false
                if (openFolder != null) {
                    // اگر پوشه‌ای باز است، همان را تازه کن
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
        binding.toolbar.title = getString(R.string.browser_folders)
        binding.toolbar.subtitle = null
        binding.toolbar.navigationIcon = null
        val adapter = binding.videoList.adapter as BrowserAdapter
        val q = searchQuery
        val list = sortedFolders()
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
        adapter.items = list.map { BrowserItem.FolderRow(it) }
        binding.emptyView.isVisible = list.isEmpty()
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

    /** دیالوگ مرتب‌سازی به سبک MX Player */
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
                saveSortPref()
                refreshDisplay()
                d.dismiss()
            }
            .setNeutralButton(
                if (sortDescending) R.string.sort_order_newest_first else R.string.sort_order_oldest_first
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
            // Android TV doesn't come with a document picker and certain versions just throw
            // instead of handling this gracefully
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

    /** تپ روی ویدیو: اگر قبلاً پخش شده، «ادامه» یا «از اول» می‌پرسد */
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
            // if we pass 'filepath' it will go directly to mpv without any processing
            i.putExtra("filepath", filepath)
        }
        i.setClass(requireContext(), MPVActivity::class.java)
        if (resumeMs > 0) i.putExtra("resume_position_ms", resumeMs)
        playerLauncher.launch(i)
    }

    override fun onDestroy() {
        scanner.shutdown()
        thumbExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainScreenFragment"
    }
}
