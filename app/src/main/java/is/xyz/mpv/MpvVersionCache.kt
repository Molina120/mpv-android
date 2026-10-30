package `is`.xyz.mpv

import `is`.xyz.mpv.MPVLib.LogObserver
import `is`.xyz.mpv.MPVLib.MpvLogLevel

object MpvVersionCache : LogObserver {
    @Volatile
    var info: String? = null
        private set

    private val sb = StringBuilder()
    private var registered = false

    @Synchronized
    fun registerOnce() {
        if (info == null && !registered) {
            registered = true
            MPVLib.addLogObserver(this)
        }
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
