package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * کش دیسک بندانگشتی‌ها — مشترک بین مرورگر و پلیر.
 *
 * - کلید فقط شناسه ویدیو است تا هم مرورگر (که dateModified دارد) و هم پلیر
 *   (که ندارد) بتوانند بخوانند/بنویسند.
 * - لیست سیاه ماندگار: ویدیوهایی که ساخت بندانگشتی‌شان ناموفق بود، تا رفرش
 *   بعدی دوباره امتحان نمی‌شوند (جلوگیری از سوختن CPU/باتری در هر اجرا).
 */
object ThumbDiskCache {
    private const val TAG = "ThumbDiskCache"
    private const val DIR = "video_thumbs_v2"
    private const val PREFS = "thumb_cache_v1"
    private const val KEY_FAILED = "failed_ids"

    fun file(context: Context, videoId: Long): File {
        // حافظه دائمی filesDir: برخلاف cacheDir توسط سیستم/بهینه‌سازها پاک نمی‌شود
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        return File(dir, "thumb_$videoId.jpg")
    }

    fun exists(context: Context, videoId: Long): Boolean {
        val f = file(context, videoId)
        return f.exists() && f.length() > 0
    }

    fun load(context: Context, videoId: Long): Bitmap? {
        return try {
            val f = file(context, videoId)
            if (f.exists() && f.length() > 0) BitmapFactory.decodeFile(f.absolutePath) else null
        } catch (e: Exception) {
            null
        }
    }

    /** ذخیره با حداکثر عرض 560 (متناسب با کادر بندانگشتی) و کیفیت JPEG 92% */
    fun save(context: Context, videoId: Long, bmp: Bitmap): Boolean {
        return try {
            val out = if (bmp.width > 560) {
                val h = (560f * bmp.height / bmp.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, 560, h, true)
            } else bmp
            FileOutputStream(file(context, videoId)).use { fos ->
                out.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
            if (out !== bmp) out.recycle()
            true
        } catch (e: Exception) {
            Log.w(TAG, "save failed for $videoId", e)
            false
        }
    }

    private fun failedSet(context: Context): MutableSet<String> {
        return try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_FAILED, emptySet())?.toMutableSet() ?: mutableSetOf()
        } catch (e: Exception) {
            mutableSetOf()
        }
    }

    fun isFailed(context: Context, videoId: Long): Boolean =
        failedSet(context).contains(videoId.toString())

    fun markFailed(context: Context, videoId: Long) {
        try {
            val s = failedSet(context)
            if (s.add(videoId.toString())) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putStringSet(KEY_FAILED, s).apply()
            }
        } catch (e: Exception) {
        }
    }

    fun clearFailed(context: Context, videoId: Long) {
        try {
            val s = failedSet(context)
            if (s.remove(videoId.toString())) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putStringSet(KEY_FAILED, s).apply()
            }
        } catch (e: Exception) {
        }
    }

    fun clearAllFailed(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_FAILED).apply()
        } catch (e: Exception) {
        }
    }

    /** تشخیص فریم سیاه/تاریک (نسخه سبک برای استفاده پلیر) */
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
