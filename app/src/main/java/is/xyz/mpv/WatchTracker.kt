package `is`.xyz.mpv

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * مدیریت بهینه وضعیت تماشای ویدیوها (کش در رم + ذخیره‌سازی غیرمسدودکننده):
 * - در حال پخش (آبی): آخرین ویدیوی پخش‌شده که هنوز به پایان نرسیده است
 * - تمام‌شده (خاکستری و کمرنگ): ویدیویی که به پایان رسیده است (با پخش مجدد به حالت در حال پخش برمی‌گردد)
 * - جدید (New): ویدیوهای تازه اضافه‌شده تا زمانی که برای اولین بار پخش شوند
 */
object WatchTracker {
    private const val TAG = "WatchTracker"
    private const val PREFS = "watch_tracker_v1"
    private const val KEY_DATA = "data"
    private const val FINISH_THRESHOLD = 0.92 // ۹۲٪ زمان ویدیو (پوشش تیتراژ پایانی)

    data class State(val positionMs: Long, val durationMs: Long, val watched: Boolean)

    @Volatile private var initialized = false
    private val statesMap = ConcurrentHashMap<Long, State>()
    private val knownIdsSet = ConcurrentHashMap.newKeySet<Long>()
    private val newIdsSet = ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var cachedLastId: Long? = null

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (initialized) return
        initialized = true
        try {
            val s = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DATA, null)
            if (s.isNullOrEmpty()) return
            val root = JSONObject(s)

            val last = root.optLong("last", -1L)
            cachedLastId = if (last >= 0L) last else null

            root.optJSONArray("known")?.let { arr ->
                for (i in 0 until arr.length()) knownIdsSet.add(arr.optLong(i))
            }
            root.optJSONArray("new_ids")?.let { arr ->
                for (i in 0 until arr.length()) newIdsSet.add(arr.optLong(i))
            }

            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.startsWith("v")) {
                    val id = k.substring(1).toLongOrNull() ?: continue
                    val o = root.optJSONObject(k) ?: continue
                    statesMap[id] = State(
                        positionMs = o.optLong("pos", 0L),
                        durationMs = o.optLong("dur", 0L),
                        watched = o.optBoolean("watched", false)
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed", e)
        }
    }

    @Synchronized
    private fun persist(context: Context) {
        try {
            val root = JSONObject()
            cachedLastId?.let { root.put("last", it) }
            if (knownIdsSet.isNotEmpty()) {
                root.put("known", JSONArray(knownIdsSet.toList()))
            }
            if (newIdsSet.isNotEmpty()) {
                root.put("new_ids", JSONArray(newIdsSet.toList()))
            }
            for ((id, st) in statesMap) {
                val o = JSONObject()
                o.put("pos", st.positionMs)
                o.put("dur", st.durationMs)
                o.put("watched", st.watched)
                root.put("v$id", o)
            }
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DATA, root.toString())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "persist failed", e)
        }
    }

    /** استخراج شناسه ویدیو از اینتنت (پشتیبانی از extra مستقیم یا URI استاندارد MediaStore) */
    fun videoIdFromIntent(intent: Intent): Long? {
        val explicitId = intent.getLongExtra("video_id", -1L)
        if (explicitId >= 0L) return explicitId
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
        ensureLoaded(context)
        return statesMap[videoId]
    }

    fun lastPlayedId(context: Context): Long? {
        ensureLoaded(context)
        return cachedLastId
    }

    fun getNewIds(context: Context): Set<Long> {
        ensureLoaded(context)
        return newIdsSet.toSet()
    }

    /** به محض شروع پخش یک ویدیو، برچسب New از روی آن برداشته می‌شود و به عنوان آخرین پخش ثبت می‌گردد */
    fun onPlaybackStarted(context: Context, videoId: Long) {
        ensureLoaded(context)
        cachedLastId = videoId
        newIdsSet.remove(videoId)
        persist(context)
    }

    /**
     * ذخیره وضعیت هنگام توقف یا اتمام پخش:
     * - اگر ویدیو به انتها رسیده باشد (eofReached یا عبور از ۹۲٪ مدت): watched = true و pos = 0L
     * - اگر ویدیو قبلاً تمام شده بوده ولی الان مجدداً از اول یا وسط در حال پخش است: watched = false می‌شود تا دوباره آبی شود
     */
    fun onPlaybackStopped(
        context: Context,
        videoId: Long,
        posMs: Long,
        durMs: Long,
        eofReached: Boolean
    ) {
        ensureLoaded(context)
        val prev = statesMap[videoId]
        val effectiveDur = if (durMs > 0L) durMs else (prev?.durationMs ?: 0L)
        val finished = eofReached || (effectiveDur > 0L && posMs >= (effectiveDur * FINISH_THRESHOLD).toLong())

        val newState = if (finished) {
            State(positionMs = 0L, durationMs = effectiveDur, watched = true)
        } else {
            // در صورت پخش مجدد یک ویدیوی تمام‌شده، وضعیت آن از خاکستری (watched=true) به در حال پخش (watched=false) تغییر می‌کند
            State(positionMs = posMs.coerceAtLeast(0L), durationMs = effectiveDur, watched = false)
        }

        statesMap[videoId] = newState
        cachedLastId = videoId
        newIdsSet.remove(videoId)
        persist(context)
    }

    /** ریست کردن موقعیت هنگام انتخاب گزینه «پخش از ابتدا» (تغییر وضعیت از تمام‌شده به در حال پخش) */
    fun clearPosition(context: Context, videoId: Long) {
        ensureLoaded(context)
        val prev = statesMap[videoId]
        statesMap[videoId] = State(
            positionMs = 0L,
            durationMs = prev?.durationMs ?: 0L,
            watched = false
        )
        cachedLastId = videoId
        newIdsSet.remove(videoId)
        persist(context)
    }

    /**
     * تشخیص ویدیوهای جدید نسبت به اسکن قبلی و حفظ برچسب New تا زمانی که ویدیو پخش شود.
     */
    fun updateKnownIds(context: Context, currentIds: Set<Long>): Set<Long> {
        if (currentIds.isEmpty()) return emptySet()
        ensureLoaded(context)
        return try {
            if (knownIdsSet.isEmpty()) {
                // اولین اجرای برنامه: همه ویدیوهای موجود شناخته‌شده تلقی می‌شوند
                knownIdsSet.addAll(currentIds)
                persist(context)
                emptySet()
            } else {
                val added = currentIds - knownIdsSet
                // فقط ویدیوهایی که واقعاً تازه اضافه شده‌اند و هنوز پخش نشده‌اند برچسب New می‌گیرند
                for (id in added) {
                    if (!statesMap.containsKey(id)) {
                        newIdsSet.add(id)
                    }
                }
                // حذف شناسه‌هایی که فایلشان از دستگاه پاک شده است
                newIdsSet.retainAll(currentIds)
                knownIdsSet.clear()
                knownIdsSet.addAll(currentIds)
                persist(context)
                newIdsSet.toSet()
            }
        } catch (e: Exception) {
            emptySet()
        }
    }
}
