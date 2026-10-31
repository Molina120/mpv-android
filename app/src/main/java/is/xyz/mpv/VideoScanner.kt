package `is`.xyz.mpv

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * پیدا کردن ویدیوهای دستگاه از طریق MediaStore و گروه‌بندی بر اساس پوشه
 * (نمای «Folders» به سبک MX Player) همراه با کش دیسک و اسکن دو مرحله‌ای.
 */
object VideoScanner {
    private const val TAG = "VideoScanner"
    private const val CACHE_FILE_NAME = "folders_cache_v1.bin"
    private const val CACHE_MAGIC = 0x4D505631 // "MPV1"

    data class Video(
        val id: Long,
        val uri: Uri,
        val name: String,
        val durationMs: Long,
        val sizeBytes: Long,
        val dateModifiedSec: Long,
        val folder: String,
        val hasSubtitle: Boolean,
        val hasDubbedAudio: Boolean,
        val dataPath: String? = null
    )

    data class Folder(val name: String, val videos: List<Video>) {
        val totalSize: Long = videos.sumOf { it.sizeBytes }
    }

    /** تشخیص زیرنویس‌دار / دوبله بودن از روی نام فایل (مثل Subbed/Dubbed در ریلیزهای فارسی) */
    private val SUB_REGEX = Regex("(^|[^a-z0-9])(subbed|softsub|hardsub|subtitles|subtitle|srt)([^a-z0-9]|$)")
    private val DUB_REGEX = Regex("(^|[^a-z0-9])(dubbed|duble|dub)([^a-z0-9]|$)")

    private fun detectNameFlags(fileName: String): Pair<Boolean, Boolean> {
        val lower = fileName.lowercase()
        val sub = SUB_REGEX.containsMatchIn(lower) || lower.contains("زیرنویس")
        val dub = DUB_REGEX.containsMatchIn(lower) || lower.contains("دوبله")
        return sub to dub
    }

    /**
     * مرحله ۱: اسکن بسیار سریع فقط از دیتابیس MediaStore بدون I/O کند فایل‌سیستم (File.listFiles).
     */
    fun scanFast(context: Context): List<Folder> {
        val videos = mutableListOf<Video>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        val sortOrder = "${MediaStore.Video.Media.DISPLAY_NAME} ASC"
        try {
            context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: continue
                    val duration = try { c.getLong(durCol) } catch (e: Exception) { 0L }
                    val size = try { c.getLong(sizeCol) } catch (e: Exception) { 0L }
                    val dateMod = try { c.getLong(dateCol) } catch (e: Exception) { 0L }
                    val dataPath = try { c.getString(dataCol) } catch (e: Exception) { null }
                    val bucket = c.getString(bucketCol).takeUnless { it.isNullOrEmpty() } ?: "-"
                    val (nameSub, nameDub) = detectNameFlags(name)
                    videos.add(
                        Video(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            name = name,
                            durationMs = duration,
                            sizeBytes = size,
                            dateModifiedSec = dateMod,
                            folder = bucket,
                            hasSubtitle = nameSub,
                            hasDubbedAudio = nameDub,
                            dataPath = dataPath
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore fast scan failed", e)
        }

        return videos.groupBy { it.folder }
            .map { (name, list) -> Folder(name, list) }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * مرحله ۲: بررسی فایل‌های زیرنویس کنار فیلم‌ها در پس‌زمینه و ذخیرهٔ نتیجه در کش دیسک.
     */
    fun enrichAndCache(context: Context, fastFolders: List<Folder>): List<Folder> {
        val subFilesByDir = mutableMapOf<String, Set<String>>()
        fun subtitleFilesOf(dirPath: String): Set<String> =
            subFilesByDir.getOrPut(dirPath) {
                try {
                    File(dirPath).listFiles { _, n ->
                        val ln = n.lowercase()
                        ln.endsWith(".srt") || ln.endsWith(".ass") ||
                                ln.endsWith(".ssa") || ln.endsWith(".vtt")
                    }?.map { it.name.lowercase() }?.toSet() ?: emptySet()
                } catch (e: Exception) {
                    emptySet()
                }
            }

        var changed = false
        val enrichedFolders = fastFolders.map { folder ->
            val updatedVideos = folder.videos.map { v ->
                if (v.hasSubtitle || v.dataPath == null) {
                    v
                } else {
                    val dir = try { File(v.dataPath).parent } catch (e: Exception) { null }
                    val siblingSub = if (dir != null) {
                        val base = v.name.substringBeforeLast('.').lowercase()
                        subtitleFilesOf(dir).any { it.startsWith(base) }
                    } else false

                    if (siblingSub) {
                        changed = true
                        v.copy(hasSubtitle = true)
                    } else {
                        v
                    }
                }
            }
            if (changed) Folder(folder.name, updatedVideos) else folder
        }

        saveDiskCache(context, enrichedFolders)
        return enrichedFolders
    }

    /** حفظ سازگاری با فراخوانی‌های قدیمی احتمالی در سایر کلاس‌ها */
    fun scan(context: Context): List<Folder> {
        val fast = scanFast(context)
        return enrichAndCache(context, fast)
    }

    /** خواندن فوری لیست پوشه‌ها از کش باینری دیسک (کمتر از ۳ میلی‌ثانیه برای ۸۰۰ ویدیو) */
    fun loadDiskCache(context: Context): List<Folder> {
        val file = File(context.cacheDir, CACHE_FILE_NAME)
        if (!file.exists()) return emptyList()
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { inp ->
                if (inp.readInt() != CACHE_MAGIC) return emptyList()
                val folderCount = inp.readInt()
                val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val folders = ArrayList<Folder>(folderCount)
                for (i in 0 until folderCount) {
                    val folderName = inp.readUTF()
                    val videoCount = inp.readInt()
                    val videos = ArrayList<Video>(videoCount)
                    for (j in 0 until videoCount) {
                        val id = inp.readLong()
                        val name = inp.readUTF()
                        val durationMs = inp.readLong()
                        val sizeBytes = inp.readLong()
                        val dateMod = inp.readLong()
                        val hasSub = inp.readBoolean()
                        val hasDub = inp.readBoolean()
                        videos.add(
                            Video(
                                id = id,
                                uri = ContentUris.withAppendedId(collection, id),
                                name = name,
                                durationMs = durationMs,
                                sizeBytes = sizeBytes,
                                dateModifiedSec = dateMod,
                                folder = folderName,
                                hasSubtitle = hasSub,
                                hasDubbedAudio = hasDub
                            )
                        )
                    }
                    folders.add(Folder(folderName, videos))
                }
                folders
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read folder disk cache", e)
            emptyList()
        }
    }

    private fun saveDiskCache(context: Context, folders: List<Folder>) {
        val file = File(context.cacheDir, CACHE_FILE_NAME)
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
                out.writeInt(CACHE_MAGIC)
                out.writeInt(folders.size)
                for (folder in folders) {
                    out.writeUTF(folder.name)
                    out.writeInt(folder.videos.size)
                    for (v in folder.videos) {
                        out.writeLong(v.id)
                        out.writeUTF(v.name)
                        out.writeLong(v.durationMs)
                        out.writeLong(v.sizeBytes)
                        out.writeLong(v.dateModifiedSec)
                        out.writeBoolean(v.hasSubtitle)
                        out.writeBoolean(v.hasDubbedAudio)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write folder disk cache", e)
        }
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun formatSize(context: Context, bytes: Long): String {
        return android.text.format.Formatter.formatShortFileSize(context, bytes)
    }

    fun formatDate(dateModifiedSec: Long): String {
        if (dateModifiedSec <= 0) return ""
        return java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            .format(java.util.Date(dateModifiedSec * 1000))
    }
}
