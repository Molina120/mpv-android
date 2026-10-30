package `is`.xyz.mpv

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * پیدا کردن ویدیوهای دستگاه از طریق MediaStore و گروه‌بندی بر اساس پوشه
 * (نمای «Folders» به سبک MX Player).
 */
object VideoScanner {
    private const val TAG = "VideoScanner"

    data class Video(
        val id: Long,
        val uri: Uri,
        val name: String,
        val durationMs: Long,
        val sizeBytes: Long,
        val dateModifiedSec: Long,
        val folder: String,
        val hasSubtitle: Boolean,
        val hasDubbedAudio: Boolean
    )

    data class Folder(val name: String, val videos: List<Video>) {
        val totalSize: Long get() = videos.sumOf { it.sizeBytes }
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

    private class RawVideo(
        val id: Long,
        val uri: Uri,
        val name: String,
        val durationMs: Long,
        val sizeBytes: Long,
        val dateModifiedSec: Long,
        val folder: String,
        val dataPath: String?,
        val nameSub: Boolean,
        val nameDub: Boolean
    )

    fun scan(context: Context): List<Folder> {
        val raw = mutableListOf<RawVideo>()
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
                    raw.add(
                        RawVideo(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            name = name,
                            durationMs = duration,
                            sizeBytes = size,
                            dateModifiedSec = dateMod,
                            folder = bucket,
                            dataPath = dataPath,
                            nameSub = nameSub,
                            nameDub = nameDub
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore scan failed", e)
        }
        // فایل‌های زیرنویس هر پوشه فقط یک‌بار خوانده می‌شوند (نه به‌ازای هر ویدیو)
        val subFilesByDir = mutableMapOf<String, Set<String>>()
        fun subtitleFilesOf(dirPath: String): Set<String> =
            subFilesByDir.getOrPut(dirPath) {
                try {
                    java.io.File(dirPath).listFiles { _, n ->
                        val ln = n.lowercase()
                        ln.endsWith(".srt") || ln.endsWith(".ass") ||
                                ln.endsWith(".ssa") || ln.endsWith(".vtt")
                    }?.map { it.name.lowercase() }?.toSet() ?: emptySet()
                } catch (e: Exception) {
                    emptySet()
                }
            }
        val videos = raw.map { r ->
            // فایل زیرنویس هم‌نام کنار فیلم (movie.srt / movie.fa.srt / ...)
            val siblingSub = !r.nameSub && r.dataPath?.let { dp ->
                val dir = try { java.io.File(dp).parent } catch (e: Exception) { null }
                if (dir == null) false else {
                    val base = r.name.substringBeforeLast('.').lowercase()
                    subtitleFilesOf(dir).any { it.startsWith(base) }
                }
            } == true
            Video(
                id = r.id,
                uri = r.uri,
                name = r.name,
                durationMs = r.durationMs,
                sizeBytes = r.sizeBytes,
                dateModifiedSec = r.dateModifiedSec,
                folder = r.folder,
                hasSubtitle = r.nameSub || siblingSub,
                hasDubbedAudio = r.nameDub
            )
        }
        return videos.groupBy { it.folder }
            .map { (name, list) -> Folder(name, list) }
            .sortedBy { it.name.lowercase() }
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
