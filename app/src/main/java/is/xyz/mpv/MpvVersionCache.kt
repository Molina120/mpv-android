package `is`.xyz.mpv

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import `is`.xyz.mpv.MPVLib.LogObserver
import `is`.xyz.mpv.MPVLib.MpvLogLevel

object MpvVersionCache : LogObserver {
    private const val PREF_KEY = "mpv_version_cache_${BuildConfig.VERSION_CODE}"

    @Volatile
    var info: String? = null
        private set

    private val sb = StringBuilder()
    private var appContext: Context? = null
    private var registered = false

    fun getCached(context: Context): String? {
        info?.let { return it }
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val saved = prefs.getString(PREF_KEY, null)
        if (!saved.isNullOrEmpty()) {
            info = saved
            return saved
        }
        return null
    }

    @Synchronized
    fun registerOnce(context: Context? = null) {
        if (context != null && appContext == null) {
            appContext = context.applicationContext
            getCached(appContext!!)
        }
        if (info != null || registered) return
        registered = true
        MPVLib.addLogObserver(this)
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (info != null) return
        if (prefix != "cplayer" || level != MpvLogLevel.MPV_LOG_LEVEL_V) return
        synchronized(sb) {
            if (info != null) return
            sb.append(text)
            if (text.startsWith("List of enabled features:", true)) {
                val result = sb.toString()
                info = result
                appContext?.let { ctx ->
                    PreferenceManager.getDefaultSharedPreferences(ctx)
                        .edit()
                        .putString(PREF_KEY, result)
                        .apply()
                }
                // حذف ناظر در ترد اصلی برای جلوگیری از ConcurrentModificationException در حلقه MPVLib.logMessage
                Handler(Looper.getMainLooper()).post {
                    MPVLib.removeLogObserver(this)
                }
            }
        }
    }
}
