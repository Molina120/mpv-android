package `is`.xyz.mpv

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * وضعیت تماشای ویدیوها به سبک MX Player:
 * - ذخیره موقعیت پخش هر ویدیو برای «ادامه پخش»
 * - علامت‌گذاری ویدیوی تمام‌شده (خاکستری) و آخرین ویدیوی پخش‌شده (آبی)
 * - تشخیص ویدیوهای جدید نسبت به اسکن قبلی (بج New)
 */
object WatchTracker {
    private const val TAG = "WatchTracker"
    private const val PREFS = "watch_tracker_v1"
    private const val KEY_DATA = "data"

    data class State(val positionMs: Long, val durationMs: Long, val watched: Boolean)

    private fun load(context: Context): JSONObject {
        return try {
            val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DATA, null)
            if (s.isNullOrEmpty()) JSONObject() else JSONObject(s)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun save(context: Context, root: JSONObject) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_DATA, root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
        }
    }

    /** شناسه ویدیوی MediaStore از روی اینتنت (فقط content://media/.../video/<id>) */
    fun videoIdFromIntent(intent: Intent): Long? {
        return try {
            val data: Uri = intent.data ?: return null
            if (data.scheme != "content" || data.authority != "media") return null
            if (!data.pathSegments.contains("video")) return null
            ContentUris.parseId(data)
        } catch (e: Exception) {
            null
        }
    }

    fun state(context: Context, videoId: Long): State? {
        val o = load(context).optJSONObject("v$videoId") ?: return null
        return State(o.optLong("pos"), o.optLong("dur"), o.optBoolean("watched"))
    }

    fun lastPlayedId(context: Context): Long? {
        val id = load(context).optLong("last", -1L)
        return if (id < 0) null else id
    }

    /** ذخیره وضعیت هنگام توقف/خروج از پخش */
    fun onPlaybackStopped(
        context: Context, videoId: Long,
        posMs: Long, durMs: Long, eofReached: Boolean
    ) {
        try {
            val root = load(context)
            val wasWatched = root.optJSONObject("v$videoId")?.optBoolean("watched") == true
            val finished = eofReached || (durMs > 0 && posMs >= durMs * 0.95)
            val obj = JSONObject()
            obj.put("pos", if (finished) 0L else posMs)
            obj.put("dur", durMs)
            obj.put("watched", finished || wasWatched)
            root.put("v$videoId", obj)
            root.put("last", videoId)
            save(context, root)
        } catch (e: Exception) {
            Log.w(TAG, "onPlaybackStopped failed", e)
        }
    }

    fun clearPosition(context: Context, videoId: Long) {
        try {
            val root = load(context)
            root.optJSONObject("v$videoId")?.put("pos", 0L)
            save(context, root)
        } catch (e: Exception) {
        }
    }

    /**
     * ویدیوهای جدید نسبت به اسکن قبلی را برمی‌گرداند.
     * در اولین اسکن همه ویدیوها «شناخته‌شده» حساب می‌شوند (بج New نمی‌گیرند).
     */
    fun updateKnownIds(context: Context, currentIds: Set<Long>): Set<Long> {
        if (currentIds.isEmpty()) return emptySet()
        return try {
            val root = load(context)
            val knownArr = root.optJSONArray("known")
            if (knownArr == null) {
                root.put("known", JSONArray(currentIds.toList()))
                save(context, root)
                emptySet()
            } else {
                val known = mutableSetOf<Long>()
                for (i in 0 until knownArr.length()) known.add(knownArr.optLong(i))
                val newIds = currentIds - known
                // لیست شناخته‌شده‌ها را با وضعیت فعلی همگام می‌کنیم (حذف‌شده‌ها پاک می‌شوند)
                root.put("known", JSONArray(currentIds.toList()))
                save(context, root)
                newIds
            }
        } catch (e: Exception) {
            emptySet()
        }
    }
}
