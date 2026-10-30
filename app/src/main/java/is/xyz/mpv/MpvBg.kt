package `is`.xyz.mpv

import android.util.Log
import java.util.concurrent.Executors

/**
 * صف تک‌تردی پس‌زمینه برای تمام فراخوانی‌های مسدودکننده mpv
 * (command / getProperty* / setProperty*).
 *
 * این فراخوانی‌ها JNI و همگام هستند؛ اگر روی ترد UI اجرا شوند،
 * رابط کاربری قفل می‌شود و لمس‌ها گم می‌شوند (علت گیر کردن
 * دیالوگ شخصی‌سازی زیرنویس و لگ هنگام پرش خودکار).
 * تک‌تردی بودن صف، ترتیب اعمال تغییرات را حفظ می‌کند.
 */
object MpvBg {
    private const val TAG = "MpvBg"

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mpv-bg").apply { isDaemon = true }
    }

    fun post(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "background mpv task failed", e)
            }
        }
    }
}
