package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

object ThumbDiskCache {
    private const val TAG = "ThumbDiskCache"
    private const val DIR = "video_thumbs_v2"
    private const val PREFS = "thumb_cache_v1"
    private const val KEY_FAILED = "failed_ids"

    @Volatile private var cachedDir: File? = null
    private val failedMemoryCache = ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var failedLoaded = false

    private fun ensureDir(context: Context): File {
        return cachedDir ?: synchronized(this) {
            cachedDir ?: File(context.applicationContext.filesDir, DIR).apply {
                mkdirs()
                cachedDir = this
            }
        }
    }

    private fun ensureFailedLoaded(context: Context) {
        if (failedLoaded) return
        synchronized(this) {
            if (failedLoaded) return
            try {
                val set = context.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getStringSet(KEY_FAILED, emptySet()) ?: emptySet()
                for (s in set) s.toLongOrNull()?.let { failedMemoryCache.add(it) }
            } catch (_: Exception) { }
            failedLoaded = true
        }
    }

    fun file(context: Context, videoId: Long): File =
        File(ensureDir(context), "thumb_$videoId.jpg")

    fun exists(context: Context, videoId: Long): Boolean {
        val f = file(context, videoId)
        return f.exists() && f.length() > 0L
    }

    fun load(context: Context, videoId: Long): Bitmap? {
        return try {
            val f = file(context, videoId)
            if (!f.exists() || f.length() <= 0L) return null
            val opts = BitmapFactory.Options().apply {
                // نصف شدن مصرف رم در LruCache و دو برابر شدن سرعت لود از دیسک
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(f.absolutePath, opts)
        } catch (e: Exception) {
            null
        }
    }

    fun save(context: Context, videoId: Long, bmp: Bitmap): Boolean {
        return try {
            val out = if (bmp.width > 560) {
                val h = (560f * bmp.height / bmp.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, 560, h, true)
            } else bmp

            val targetFile = file(context, videoId)
            val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            FileOutputStream(tmpFile).use { fos ->
                out.compress(Bitmap.CompressFormat.JPEG, 90, fos)
                fos.flush()
            }
            if (out !== bmp) out.recycle()

            if (tmpFile.exists() && tmpFile.length() > 0L) {
                tmpFile.renameTo(targetFile)
                clearFailed(context, videoId)
                true
            } else {
                tmpFile.delete()
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "save failed for $videoId", e)
            false
        }
    }

    fun isFailed(context: Context, videoId: Long): Boolean {
        ensureFailedLoaded(context)
        return failedMemoryCache.contains(videoId)
    }

    fun markFailed(context: Context, videoId: Long) {
        ensureFailedLoaded(context)
        if (failedMemoryCache.add(videoId)) {
            persistFailed(context)
        }
    }

    fun clearFailed(context: Context, videoId: Long) {
        ensureFailedLoaded(context)
        if (failedMemoryCache.remove(videoId)) {
            persistFailed(context)
        }
    }

    fun clearAllFailed(context: Context) {
        ensureFailedLoaded(context)
        failedMemoryCache.clear()
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_FAILED).apply()
        } catch (_: Exception) { }
    }

    private fun persistFailed(context: Context) {
        try {
            val strSet = failedMemoryCache.map { it.toString() }.toSet()
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_FAILED, strSet).apply()
        } catch (_: Exception) { }
    }

    fun isDark(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return true
        var totalLum = 0
        var bright = 0
        var count = 0
        for (gx in 1..4) {
            val x = (w * gx / 5).coerceIn(0, w - 1)
            for (gy in 1..4) {
                val y = (h * gy / 5).coerceIn(0, h - 1)
                val p = bmp.getPixel(x, y)
                val lum = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                totalLum += lum
                if (lum > 28) bright++
                count++
            }
        }
        return totalLum / count < 14 && bright < 2
    }
}
