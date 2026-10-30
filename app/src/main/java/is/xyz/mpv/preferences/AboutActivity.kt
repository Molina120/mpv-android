package `is`.xyz.mpv.preferences

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import `is`.xyz.mpv.BuildConfig
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVLib.MpvLogLevel
import `is`.xyz.mpv.MpvVersionCache
import `is`.xyz.mpv.R
import `is`.xyz.mpv.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity(), MPVLib.LogObserver {
    private lateinit var binding: ActivityAboutBinding
    private var logs = ""
    private var mpvDestroyed = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (preferences.getBoolean("material_you_theming", false))
            DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.elevation = 0f
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        logs = "mpv-android ${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE} (${BuildConfig.BUILD_TYPE})\n"

        // اگر قبلاً در این فرایند پلیر اجرا شده، اطلاعات نسخه کش شده و نیازی به
        // ساخت نمونه دوم mpv نیست (ساخت دوباره بعد از destroy کرش می‌داد)
        val cached = MpvVersionCache.info
        if (cached != null) {
            binding.logs.text = logs + cached
            return
        }

        if (tempInstanceConsumed) {
            // در این فرایند قبلاً یک نمونه موقت ساخته و نابود شده؛ ساخت نمونه
            // دوم کرش می‌دهد. کش نسخه از همان نمونه اول پر شده است.
            binding.logs.text = logs + (MpvVersionCache.info ?: "")
            return
        }

        // نمونه موقت را فقط یک‌بار در هر فرایند می‌سازیم؛ کش نسخه را هم ثبت
        // می‌کنیم تا بازدیدهای بعدی بدون ساخت نمونه کار کنند
        MpvVersionCache.registerOnce()
        tempInstanceConsumed = true

        // create mpv context to capture version info from log
        MPVLib.create(this)
        mpvDestroyed = false
        MPVLib.addLogObserver(this)
        MPVLib.init()
    }

    private fun updateLog() {
        runOnUiThread {
            binding.logs.text = logs
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!mpvDestroyed) {
            MPVLib.destroy()
            mpvDestroyed = true
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (prefix != "cplayer")
            return
        if (level == MpvLogLevel.MPV_LOG_LEVEL_V)
            logs += text

        if (text.startsWith("List of enabled features:", true)) {
            // stop receiving log messages and populate text field
            MPVLib.removeLogObserver(this)
            updateLog()
        }
    }

    companion object {
        // نمونه موقت mpv فقط یک‌بار در هر فرایند ساخته می‌شود؛ دومی کرش می‌دهد
        private var tempInstanceConsumed = false
    }
}
