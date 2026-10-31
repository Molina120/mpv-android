package `is`.xyz.mpv

import android.content.Context
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.doAfterTextChanged
import androidx.preference.PreferenceManager
import kotlin.math.max

object SkipRangeManager {
    data class TimeRange(val startSec: Double, val endSec: Double)

    enum class SkipMode {
        SKIP_SPECIFIED_RANGES,
        PLAY_ONLY_SPECIFIED_RANGES
    }

    private const val PREF_SKIP_ENABLED = "skip_enabled"
    private const val PREF_SKIP_MODE = "skip_mode"
    private const val PREF_SKIP_RANGES_TEXT = "skip_ranges_text"

    @Volatile
    var isEnabled: Boolean = false
    @Volatile
    var mode: SkipMode = SkipMode.SKIP_SPECIFIED_RANGES
    var rawInputText: String = ""
        private set
    @Volatile
    private var ranges: List<TimeRange> = emptyList()
    private var prefsLoaded = false

    private var lastSeekTimestampMs: Long = 0L
    private var lastSkippedTargetSec: Double = -1.0
    private const val SEEK_COOLDOWN_MS = 950L

    fun loadFromPrefs(context: Context) {
        if (prefsLoaded) return
        prefsLoaded = true
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            isEnabled = prefs.getBoolean(PREF_SKIP_ENABLED, false)
            val modeName = prefs.getString(PREF_SKIP_MODE, SkipMode.SKIP_SPECIFIED_RANGES.name)
            mode = try {
                SkipMode.valueOf(modeName ?: SkipMode.SKIP_SPECIFIED_RANGES.name)
            } catch (e: Exception) {
                SkipMode.SKIP_SPECIFIED_RANGES
            }
            rawInputText = prefs.getString(PREF_SKIP_RANGES_TEXT, "") ?: ""
            ranges = parseRanges(rawInputText)
        } catch (_: Exception) {
        }
    }

    private fun saveToPrefs(context: Context) {
        try {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PREF_SKIP_ENABLED, isEnabled)
                .putString(PREF_SKIP_MODE, mode.name)
                .putString(PREF_SKIP_RANGES_TEXT, rawInputText)
                .apply()
        } catch (_: Exception) {
        }
    }

    /**
     * تبدیل اعداد فارسی/عربی به انگلیسی، پارس کردن بازه‌ها و ادغام بازه‌های هم‌پوشان
     */
    fun parseRanges(input: String): List<TimeRange> {
        val normalized = input
            .replace('۰', '0').replace('۱', '1').replace('۲', '2')
            .replace('۳', '3').replace('۴', '4').replace('۵', '5')
            .replace('۶', '6').replace('۷', '7').replace('۸', '8').replace('۹', '9')
            .replace('٠', '0').replace('١', '1').replace('٢', '2')
            .replace('٣', '3').replace('٤', '4').replace('٥', '5')
            .replace('٦', '6').replace('٧', '7').replace('٨', '8').replace('٩', '9')
            .replace('–', '-').replace('—', '-')

        val rawList = mutableListOf<TimeRange>()
        val tokens = normalized.split(Regex("[,;\\n]+"))

        for (rawToken in tokens) {
            val cleaned = rawToken.trim().removePrefix("+").trim()
            if (cleaned.isEmpty()) continue

            val parts = cleaned.split("-")
            if (parts.size != 2) continue

            val start = parseTimeToSeconds(parts[0].trim())
            val end = parseTimeToSeconds(parts[1].trim())

            if (start != null && end != null && end > start) {
                rawList.add(TimeRange(start, end))
            }
        }

        if (rawList.size <= 1) return rawList

        // مرتب‌سازی و ادغام بازه‌های هم‌پوشان یا چسبیده به هم
        val sorted = rawList.sortedBy { it.startSec }
        val merged = mutableListOf<TimeRange>()
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.startSec <= current.endSec + 0.5) {
                current = TimeRange(current.startSec, max(current.endSec, next.endSec))
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private fun parseTimeToSeconds(timeStr: String): Double? {
        if (timeStr.isEmpty()) return null
        val parts = timeStr.split(":")
        return try {
            when (parts.size) {
                3 -> parts[0].toDouble() * 3600.0 + parts[1].toDouble() * 60.0 + parts[2].toDouble()
                2 -> parts[0].toDouble() * 60.0 + parts[1].toDouble()
                1 -> parts[0].toDouble()
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun checkAndSkipIfNeeded(currentPositionSec: Int, onSkipTriggered: (Double) -> Unit) {
        val activeRanges = ranges
        if (!isEnabled || activeRanges.isEmpty()) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastSeekTimestampMs < SEEK_COOLDOWN_MS) return

        val pos = currentPositionSec.toDouble()

        // اگر کاربر به عقب برگشته، قفل هدف قبلی را آزاد کن
        if (pos < lastSkippedTargetSec - 3.0 || pos > lastSkippedTargetSec + 2.0) {
            lastSkippedTargetSec = -1.0
        }

        when (mode) {
            SkipMode.SKIP_SPECIFIED_RANGES -> {
                val matched = activeRanges.firstOrNull {
                    pos >= it.startSec && pos < (it.endSec - 0.8)
                }
                if (matched != null && matched.endSec != lastSkippedTargetSec) {
                    lastSeekTimestampMs = now
                    lastSkippedTargetSec = matched.endSec
                    onSkipTriggered(matched.endSec)
                }
            }
            SkipMode.PLAY_ONLY_SPECIFIED_RANGES -> {
                val insideAny = activeRanges.any {
                    pos >= (it.startSec - 0.8) && pos < it.endSec
                }
                if (!insideAny) {
                    val nextRange = activeRanges.firstOrNull { it.startSec > pos }
                    if (nextRange != null && nextRange.startSec != lastSkippedTargetSec) {
                        lastSeekTimestampMs = now
                        lastSkippedTargetSec = nextRange.startSec
                        onSkipTriggered(nextRange.startSec)
                    }
                }
            }
        }
    }

    fun clear() {
        rawInputText = ""
        ranges = emptyList()
        lastSkippedTargetSec = -1.0
    }

    fun showSkipDialog(context: Context, onDismiss: () -> Unit) {
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

        // اسکرول جهت‌آگاه: کادر متن هر وقت بتواند در جهت حرکت انگشت اسکرول کند،
        // والد (منو) دخالت نمی‌کند؛ فقط وقتی به انتهای محتوا رسیده‌ایم و کاربر
        // همچنان در همان جهت می‌کشد، اسکرول به منو واگذار می‌شود.
        var lastTouchY = 0f
        editSkipRanges.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lastTouchY = event.y
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - lastTouchY
                    lastTouchY = event.y
                    // dy > 0 یعنی انگشت به پایین می‌رود و محتوا به بالا اسکرول می‌شود
                    val canScroll = if (dy > 0) v.canScrollVertically(-1) else v.canScrollVertically(1)
                    v.parent?.requestDisallowInterceptTouchEvent(canScroll)
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        fun updateModeEnabled(enabled: Boolean) {
            radioSkipInside.isEnabled = enabled
            radioKeepOnly.isEnabled = enabled
            editSkipRanges.isEnabled = enabled
        }
        updateModeEnabled(switchSkipEnabled.isChecked)
        switchSkipEnabled.setOnCheckedChangeListener { _, isChecked ->
            updateModeEnabled(isChecked)
        }

        val alertDialog = AlertDialog.Builder(context)
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
                lastSkippedTargetSec = -1.0
                saveToPrefs(context)
            }
            .setNeutralButton(R.string.skip_clear, null) // پاکسازی درجا بدون پرش یا بستن ناخواسته
            .setNegativeButton(R.string.dialog_cancel, null)
            .setOnDismissListener { onDismiss() }
            .create()

        alertDialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        )

        SubtitleStyleManager.showImmersiveDialog(context, alertDialog, maxHeightRatio = 0.90)

        // عملکرد دکمه پاکسازی درجا بدون بستن اجباری دیالوگ
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            editSkipRanges.setText("")
            clear()
            saveToPrefs(context)
            updateStatusPreview("")
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(editSkipRanges.windowToken, 0)
        }
    }
}
