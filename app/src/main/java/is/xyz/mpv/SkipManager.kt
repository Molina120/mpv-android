package `is`.xyz.mpv

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.widget.EditText
import android.widget.Toast
import java.io.File

/**
 * Per-file auto-skip of user-defined time ranges.
 *
 * Skip lists live in <filesDir>/skips/<sanitized filename>.skip with the format:
 *   00:01:00-00:02:00,+00:05:00-00:05:30
 *
 * A background thread polls "time-pos" every 300 ms and seeks past the end of
 * any range the playback position falls into.
 *
 * Lifecycle: start() after MPVLib.init(), stop() before MPVLib.destroy().
 */
object SkipManager {
    private const val TAG = "SkipManager"

    @Volatile
    var lastBreadcrumb: String? = null

    var appContext: Context? = null

    private var watcher: SkipWatcher? = null

    /** Must be called after MPVLib.init(). Safe to call repeatedly. */
    @Synchronized
    fun start() {
        val w = watcher
        if (w != null && w.running)
            return
        val ctx = appContext ?: return
        SkipWatcher(ctx).also {
            watcher = it
            it.start()
        }
    }

    /** Must be called before MPVLib.destroy(). */
    @Synchronized
    fun stop() {
        val w = watcher ?: return
        w.running = false
        w.interrupt()
        try {
            w.join(2000)
        } catch (_: InterruptedException) {
        }
        watcher = null
    }

    /** Forces the watcher to reload the current file's skip list. */
    @Synchronized
    fun invalidate() {
        watcher?.invalidate()
    }

    private class SkipWatcher(ctx: Context) : Thread() {
        private val appCtx = ctx.applicationContext

        @Volatile
        var running = true

        @Volatile
        private var curKey: String? = null

        private var ranges: List<Pair<Double, Double>> = emptyList()

        fun invalidate() {
            curKey = null
        }

        override fun run() {
            while (running) {
                try {
                    lastBreadcrumb = "watcher:poll"
                    val filename = MPVLib.getPropertyString("filename")
                    if (filename.isNullOrEmpty()) {
                        curKey = null
                        ranges = emptyList()
                    } else {
                        val key = sanitizeKey(filename)
                        if (key != curKey) {
                            curKey = key
                            ranges = loadRanges(key)
                        }
                        val pos = MPVLib.getPropertyDouble("time-pos") ?: Double.NaN
                        if (!pos.isNaN()) {
                            for ((start, end) in ranges) {
                                if (pos >= start && pos < end) {
                                    lastBreadcrumb = "watcher:seek"
                                    MPVLib.command(arrayOf("seek", end.toString(), "absolute"))
                                    break
                                }
                            }
                        }
                    }
                    sleep(300)
                } catch (_: InterruptedException) {
                    // fall through to the running check
                } catch (_: Exception) {
                    try {
                        sleep(300)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }

        private fun loadRanges(key: String): List<Pair<Double, Double>> {
            return try {
                val f = File(File(appCtx.filesDir, "skips"), "$key.skip")
                if (!f.exists())
                    return emptyList()
                parseRanges(f.readText())
            } catch (e: Exception) {
                Log.w(TAG, "loadRanges failed", e)
                emptyList()
            }
        }
    }

    fun sanitizeKey(filename: String): String =
        filename.replace(Regex("[/\\\\]"), "_")

    fun parseRanges(text: String): List<Pair<Double, Double>> {
        if (text.isBlank())
            return emptyList()
        return text.split(Regex("[,\\+\\s]+")).mapNotNull { token ->
            val parts = token.split("-")
            if (parts.size != 2)
                return@mapNotNull null
            val s = parseTime(parts[0]) ?: return@mapNotNull null
            val e = parseTime(parts[1]) ?: return@mapNotNull null
            if (s < e) s to e else null
        }
    }

    fun parseTime(s: String): Double? {
        return try {
            var v = 0.0
            for (part in s.trim().split(":"))
                v = v * 60 + part.toDouble()
            v
        } catch (_: Exception) {
            null
        }
    }

    /** Editor dialog for the currently playing file. */
    fun showEditor(activity: MPVActivity) {
        val filename = MPVLib.getPropertyString("filename")
        if (filename.isNullOrEmpty()) {
            Toast.makeText(activity, "اول یک ویدیو باز کن", Toast.LENGTH_SHORT).show()
            return
        }
        val key = sanitizeKey(filename)
        val ctx = activity.applicationContext
        val dir = File(ctx.filesDir, "skips").apply { mkdirs() }
        val file = File(dir, "$key.skip")
        val existing = try {
            if (file.exists()) file.readText() else ""
        } catch (_: Exception) {
            ""
        }

        val edit = EditText(activity).apply {
            setText(existing)
            hint = "00:01:00-00:02:00,+00:05:00-00:05:30"
            minLines = 5
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(activity)
            .setTitle("پرش خودکار: $filename")
            .setView(edit)
            .setPositiveButton("ذخیره") { _, _ ->
                try {
                    val text = edit.text.toString().trim()
                    if (text.isEmpty())
                        file.delete()
                    else
                        file.writeText(text)
                    invalidate()
                    Toast.makeText(activity, "ذخیره شد", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.w(TAG, "save failed", e)
                }
            }
            .setNegativeButton("انصراف", null)
            .show()
    }
}
