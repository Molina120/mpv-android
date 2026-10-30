package `is`.xyz.mpv

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * صف تک‌تردی پس‌زمینه برای فراخوانی‌های مسدودکنندهٔ mpv (command / setProperty / getProperty).
 *
 * postLatest(key, block):
 *   اگر کاری با همین کلید هنوز اجرا نشده، با این یکی جایگزین می‌شود.
 *   یعنی فقط آخرین حالت اجرا می‌شود و صف با رندرهای قدیمی انباشته نمی‌شود.
 *
 * post(block):
 *   کارهای بدون کلید، همیشه به ترتیب و بدون حذف اجرا می‌شوند.
 *
 * هیچ‌وقت دو کار هم‌زمان اجرا نمی‌شوند (یک ترد)، و کارهایی که داخل خودِ یک کار
 * پست می‌شوند بعد از همان دسته اجرا می‌شوند (گم نمی‌شوند و بلاک هم نمی‌کنند).
 */
object MpvBg {
    private const val TAG = "MpvBg"

    private val lock = Any()
    private val pending = LinkedHashMap<String, () -> Unit>()
    private var draining = false
    private val uniq = AtomicLong(0)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mpv-bg").apply { isDaemon = true }
    }

    fun post(block: () -> Unit) {
        postLatest("#" + uniq.incrementAndGet(), block)
    }

    fun postLatest(key: String, block: () -> Unit) {
        synchronized(lock) {
            // remove+put: کلید به انتهای صف می‌رود تا ترتیب آخرین درخواست حفظ شود
            pending.remove(key)
            pending[key] = block
            if (draining) return
            draining = true
        }
        executor.execute { drain() }
    }

    private fun drain() {
        while (true) {
            val batch: List<() -> Unit> = synchronized(lock) {
                if (pending.isEmpty()) {
                    draining = false
                    return
                }
                val list = pending.values.toList()
                pending.clear()
                list
            }
            for (task in batch) {
                try {
                    task()
                } catch (e: Exception) {
                    Log.w(TAG, "background mpv task failed", e)
                }
            }
        }
    }
}
