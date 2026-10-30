package `is`.xyz.mpv

import android.content.Context
import android.os.SystemClock
import android.view.LayoutInflater
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.doAfterTextChanged
import androidx.preference.PreferenceManager

object SkipRangeManager {
    data class TimeRange(val startSec: Double, val endSec: Double)

    enum class SkipMode {
        SKIP_SPECIFIED_RANGES, // پرش از شروع بازه به پایان همان بازه
        PLAY_ONLY_SPECIFIED_RANGES // پخش فقط بازه‌های مشخص شده و پرش از فواصل بین آن‌ها
    }

    // کلیدهای SharedPreferences برای ماندگاری بعد از بستن برنامه
    private const val PREF_SKIP_ENABLED = "skip_enabled"
    private const val PREF_SKIP_MODE = "skip_mode"
    private const val PREF_SKIP_RANGES_TEXT = "skip_ranges_text"

    var isEnabled: Boolean = true
    var mode: SkipMode = SkipMode.SKIP_SPECIFIED_RANGES
    var rawInputText: String = ""
        private set
    private var ranges: List<TimeRange> = emptyList()
    private var prefsLoaded = false

    // جلوگیری از ارسال دستورات Seek تکراری در کسری از ثانیه
    private var lastSeekTimestampMs: Long = 0L
    private const val SEEK_COOLDOWN_MS = 900L

    /**
     * بارگذاری تنظیمات ذخیره‌شده از SharedPreferences.
     * فقط یک بار در هر session اجرا می‌شود؛ بعد از آن مقادیر در حافظه هستند.
     */
    fun loadFromPrefs(context: Context) {
        if (prefsLoaded) return
        prefsLoaded = true
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            isEnabled = prefs.getBoolean(PREF_SKIP_ENABLED, true)
            val modeName = prefs.getString(PREF_SKIP_MODE, SkipMode.SKIP_SPECIFIED_RANGES.name)
            mode = try {
                SkipMode.valueOf(modeName ?: SkipMode.SKIP_SPECIFIED_RANGES.name)
            } catch (e: Exception) {
                SkipMode.SKIP_SPECIFIED_RANGES
            }
            rawInputText = prefs.getString(PREF_SKIP_RANGES_TEXT, "") ?: ""
            ranges = parseRanges(rawInputText)
        } catch (e: Exception) {
            // در صورت خطا، مقادیر پیش‌فرض باقی می‌مانند
        }
    }

    /** ذخیره تنظیمات در SharedPreferences تا بعد از بستن برنامه باقی بمانند */
    private fun saveToPrefs(context: Context) {
        try {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PREF_SKIP_ENABLED, isEnabled)
                .putString(PREF_SKIP_MODE, mode.name)
                .putString(PREF_SKIP_RANGES_TEXT, rawInputText)
                .apply()
        } catch (e: Exception) {
            // نادیده بگیر
        }
    }

    /**
     * تبدیل اعداد فارسی/عربی به انگلیسی و پارس کردن رشته بازه‌ها
     */
    fun parseRanges(input: String): List<TimeRange> {
        val normalized = input
            .replace('۰', '0').replace('۱', '1').replace('۲', '2')
            .replace('۳', '3').replace('۴', '4').replace('۵', '5')
            .replace('۶', '6').replace('۷', '7').replace('۸', '8').replace('۹', '9')
            .replace('٠', '0').replace('١', '1').replace('٢', '2')
            .replace('٣', '3').replace('٤', '4').replace('٥', '5')
            .replace('٦', '6').replace('٧', '7').replace('٨', '8').replace('٩', '9')

        val result = mutableListOf<TimeRange>()
        // جداسازی بر اساس کاما، سمی‌کالن یا خط جدید
        val tokens = normalized.split(Regex("[,;\\n]+"))

        for (rawToken in tokens) {
            // حذف فاصله‌ها و علامت + در ابتدای هر بخش (مثل ,+ 00:35:40-00:39:10)
            val cleaned = rawToken.trim().removePrefix("+").trim()
            if (cleaned.isEmpty()) continue

            val parts = cleaned.split("-")
            if (parts.size != 2) continue

            val start = parseTimeToSeconds(parts[0].trim())
            val end = parseTimeToSeconds(parts[1].trim())

            if (start != null && end != null && end > start) {
                result.add(TimeRange(start, end))
            }
        }

        return result.sortedBy { it.startSec }
    }

    /**
     * تبدیل فرمت HH:MM:SS یا MM:SS یا SS به ثانیه
     */
    private fun parseTimeToSeconds(timeStr: String): Double? {
        if (timeStr.isEmpty()) return null
        val parts = timeStr.split(":")
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toDouble()
                    val m = parts[1].toDouble()
                    val s = parts[2].toDouble()
                    h * 3600.0 + m * 60.0 + s
                }
                2 -> {
                    val m = parts[0].toDouble()
                    val s = parts[1].toDouble()
                    m * 60.0 + s
                }
                1 -> parts[0].toDouble()
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * بررسی موقعیت فعلی پخش و انجام پرش در صورت رسیدن به نقاط تعیین‌شده
     */
    fun checkAndSkipIfNeeded(currentPositionSec: Int, onSkipTriggered: (Double) -> Unit) {
        if (!isEnabled || ranges.isEmpty()) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastSeekTimestampMs < SEEK_COOLDOWN_MS) return

        val pos = currentPositionSec.toDouble()

        when (mode) {
            SkipMode.SKIP_SPECIFIED_RANGES -> {
                // اگر زمان فعلی داخل یکی از بازه‌های حذفی [start, end) قرار گرفت، بپر به end
                val matched = ranges.firstOrNull { pos >= it.startSec && pos < (it.endSec - 0.8) }
                if (matched != null) {
                    lastSeekTimestampMs = now
                    onSkipTriggered(matched.endSec)
                }
            }
            SkipMode.PLAY_ONLY_SPECIFIED_RANGES -> {
                // اگر زمان فعلی داخل هیچ‌کدام از بازه‌های مجاز نیست، بپر به شروعِ بازه بعدی
                val insideAny = ranges.any { pos >= (it.startSec - 0.8) && pos < it.endSec }
                if (!insideAny) {
                    val nextRange = ranges.firstOrNull { it.startSec > pos }
                    if (nextRange != null) {
                        lastSeekTimestampMs = now
                        onSkipTriggered(nextRange.startSec)
                    }
                }
            }
        }
    }

    fun clear() {
        rawInputText = ""
        ranges = emptyList()
    }

    fun showSkipDialog(context: Context, onDismiss: () -> Unit) {
        // بارگذاری مقادیر ذخیره‌شده (اگر قبلاً لود نشده)
        loadFromPrefs(context)

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_skip_ranges, null)
        val switchSkipEnabled = view.findViewById<SwitchCompat>(R.id.switchSkipEnabled)
        val editSkipRanges = view.findViewById<EditText>(R.id.editSkipRanges)
        val radioGroupSkipMode = view.findViewById<RadioGroup>(R.id.radioGroupSkipMode)
        val radioSkipInside = view.findViewById<RadioButton>(R.id.radioSkipInside)
        val radioKeepOnly = view.findViewById<RadioButton>(R.id.radioKeepOnly)
        val txtSkipStatus = view.findViewById<TextView>(R.id.txtSkipStatus)

        switchSkipEnabled.isChecked = isEnabled
        editSkipRanges.setText(rawInputText)
        if (mode == SkipMode.SKIP_SPECIFIED_RANGES) {
            radioSkipInside.isChecked = true
        } else {
            radioKeepOnly.isChecked = true
        }

        fun updateStatusPreview(text: String) {
            val parsed = parseRanges(text)
            txtSkipStatus.text = if (parsed.isEmpty()) {
                context.getString(R.string.skip_no_valid)
            } else {
                context.getString(R.string.skip_valid_count, parsed.size)
            }
        }

        updateStatusPreview(rawInputText)
        editSkipRanges.doAfterTextChanged {
            updateStatusPreview(it?.toString() ?: "")
        }

        // وقتی auto-skip خاموش است، گزینه‌های حالت خاکستری/غیرفعال باشند
        fun updateModeEnabled(enabled: Boolean) {
            radioSkipInside.isEnabled = enabled
            radioKeepOnly.isEnabled = enabled
        }
        updateModeEnabled(switchSkipEnabled.isChecked)
        switchSkipEnabled.setOnCheckedChangeListener { _, isChecked ->
            updateModeEnabled(isChecked)
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.skip_title)
            .setView(view)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                isEnabled = switchSkipEnabled.isChecked
                rawInputText = editSkipRanges.text.toString()
                ranges = parseRanges(rawInputText)
                mode = if (radioGroupSkipMode.checkedRadioButtonId == R.id.radioSkipInside) {
                    SkipMode.SKIP_SPECIFIED_RANGES
                } else {
                    SkipMode.PLAY_ONLY_SPECIFIED_RANGES
                }
                lastSeekTimestampMs = 0L
                // ذخیره دائمی تا بعد از بستن برنامه باقی بماند
                saveToPrefs(context)
            }
            .setNeutralButton(R.string.skip_clear) { _, _ ->
                clear()
                // پاکسازی را هم ذخیره کن
                saveToPrefs(context)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .setOnDismissListener { onDismiss() }
            .create()
            .show()
    }
}
