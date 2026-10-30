package `is`.xyz.mpv

import `is`.xyz.mpv.MPVLib.LogObserver
import `is`.xyz.mpv.MPVLib.MpvLogLevel

/**
 * کش اطلاعات نسخه mpv برای صفحه About.
 *
 * صفحه About قبلاً برای گرفتن این اطلاعات یک نمونه دوم mpv می‌ساخت که بعد از
 * یک‌بار پخش ویدیو (create → destroy → create دوباره) برنامه را کرش می‌داد.
 * حالا این ناظر سبک هنگام اولین init پلیر لاگ‌های نسخه را می‌گیرد و نگه می‌دارد
 * تا About دیگر نیازی به ساخت نمونه دوم نداشته باشد.
 */
object MpvVersionCache : LogObserver {
    @Volatile
    var info: String? = null
        private set

    private val sb = StringBuilder()

    /** فقط وقتی کش خالی است ثبت می‌شود؛ تکراری ثبت نمی‌کنیم */
    fun registerOnce() {
        if (info == null) MPVLib.addLogObserver(this)
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (info != null) return
        if (prefix != "cplayer" || level != MpvLogLevel.MPV_LOG_LEVEL_V) return
        synchronized(sb) {
            if (info != null) return
            sb.append(text)
            if (text.startsWith("List of enabled features:", true)) {
                info = sb.toString()
                MPVLib.removeLogObserver(this)
            }
        }
    }
}
