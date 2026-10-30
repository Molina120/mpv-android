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
        val folder: String
    )

    data class Folder(val name: String, val videos: List<Video>) {
        val totalSize: Long get() = videos.sumOf { it.sizeBytes }
    }

    fun scan(context: Context): List<Folder> {
        val videos = mutableListOf<Video>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
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
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: continue
                    val duration = try { c.getLong(durCol) } catch (e: Exception) { 0L }
                    val size = try { c.getLong(sizeCol) } catch (e: Exception) { 0L }
                    val dateMod = try { c.getLong(dateCol) } catch (e: Exception) { 0L }
                    val bucket = c.getString(bucketCol).takeUnless { it.isNullOrEmpty() } ?: "-"
                    videos.add(
                        Video(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            name = name,
                            durationMs = duration,
                            sizeBytes = size,
                            dateModifiedSec = dateMod,
                            folder = bucket
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore scan failed", e)
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
}
