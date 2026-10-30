package `is`.xyz.mpv

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * حافظه ترک‌های خارجی (زیرنویس/صدای دوبله) برای هر ویدیو.
 *
 * وقتی کاربر از منوی بالا یک زیرنویس یا صدای جداگانه به فیلم اضافه می‌کند،
 * اینجا (videoKey -> لیست ترک‌ها) ذخیره می‌شود تا دفعه بعد که همان ویدیو
 * باز شد، به‌صورت خودکار دوباره اضافه شوند و لازم نباشد از اول انتخاب شوند.
 *
 * videoKey همان path ویدیو است (مسیر فایل یا content URI).
 * برای زیرنویس، مسیر *اصلی* (قبل از نرمالایز) ذخیره می‌شود تا هنگام
 * بارگذاری خودکار دوباره نرمالایز شود.
 */
object ExternalTrackMemory {
    private const val TAG = "ExternalTrackMemory"
    private const val PREF_KEY = "external_tracks_v1"

    data class Track(val kind: String, val path: String) {
        val isSub: Boolean get() = kind == "sub"
    }

    fun remember(context: Context, videoKey: String, kind: String, path: String) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val root = JSONObject(prefs.getString(PREF_KEY, "{}") ?: "{}")
            val arr = root.optJSONArray(videoKey) ?: JSONArray()
            val filtered = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("kind") == kind && o.optString("path") == path) continue
                filtered.put(o)
            }
            filtered.put(JSONObject().put("kind", kind).put("path", path))
            root.put(videoKey, filtered)
            prefs.edit().putString(PREF_KEY, root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "remember failed", e)
        }
    }

    fun getTracks(context: Context, videoKey: String): List<Track> {
        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val root = JSONObject(prefs.getString(PREF_KEY, "{}") ?: "{}")
            val arr = root.optJSONArray(videoKey) ?: return emptyList()
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Track(o.getString("kind"), o.getString("path"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "getTracks failed", e)
            emptyList()
        }
    }

    fun forgetVideo(context: Context, videoKey: String) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val root = JSONObject(prefs.getString(PREF_KEY, "{}") ?: "{}")
            root.remove(videoKey)
            prefs.edit().putString(PREF_KEY, root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "forgetVideo failed", e)
        }
    }
}
