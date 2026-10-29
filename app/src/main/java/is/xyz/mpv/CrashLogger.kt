package `is`.xyz.mpv

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes crash reports into the Download folder for debugging. */
object CrashLogger : Thread.UncaughtExceptionHandler {
    private var fallback: Thread.UncaughtExceptionHandler? = null
    private lateinit var appCtx: Context

    fun install(context: Context) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is CrashLogger)
            return
        appCtx = context.applicationContext
        fallback = current
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    /**
     * Writes a report if the previous process death was a crash (Java or native).
     * Call this early in Application.onCreate().
     */
    fun checkPreviousExit(context: Context) {
        if (Build.VERSION.SDK_INT < 30)
            return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
                ?: return
            val reason = info.reason
            if (reason != ApplicationExitInfo.REASON_CRASH &&
                reason != ApplicationExitInfo.REASON_CRASH_NATIVE)
                return
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("=== mpv-iransans previous-exit report (b6) ===")
            pw.println("reason: $reason")
            pw.println(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp)))
            pw.println("--- description ---")
            pw.println(info.description ?: "(none)")
            pw.flush()
            val name = "mpv-iransans-exitinfo-b6-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
            saveText(context, name, sw.toString())
        } catch (_: Exception) {
        }
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("=== mpv-iransans crash report (b6) ===")
            pw.println(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            pw.println("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}")
            pw.println("breadcrumb: ${SkipManager.lastBreadcrumb}")
            e.printStackTrace(pw)
            pw.flush()
            val name = "mpv-iransans-crash-b6-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
            saveText(appCtx, name, sw.toString())
        } catch (_: Exception) {
        }
        fallback?.uncaughtException(t, e) ?: run {
            Process.killProcess(Process.myPid())
            System.exit(1)
        }
    }

    private fun saveText(context: Context, name: String, text: String) {
        try {
            if (writeViaMediaStore(context, name, text))
                return
            writeViaExternalFiles(context, name, text)
        } catch (_: Exception) {
        }
    }

    private fun writeViaMediaStore(context: Context, name: String, text: String): Boolean {
        if (Build.VERSION.SDK_INT < 29)
            return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri, "w")?.use { it.write(text.toByteArray()) }
                ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun writeViaExternalFiles(context: Context, name: String, text: String) {
        try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            dir.mkdirs()
            dir.resolve(name).writeText(text)
        } catch (_: Exception) {
        }
    }
}
