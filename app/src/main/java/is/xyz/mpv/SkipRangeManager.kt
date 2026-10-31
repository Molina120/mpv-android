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

    /**
     * تنظیمات پرش خودکار هر ویدیو جداگانه ذخیره می‌شود (کلید بر اساس شناسه ویدیو).
     */
    data class SkipConfig(
        var isEnabled: Boolean = false,
        var mode: SkipMode = SkipMode.SKIP_SPECIFIED_RANGES,
        var rawInputText: String = "",
        var ranges: List<TimeRange> = emptyList(),
        // وضعیت زمان‌بندی پرش (جلوگیری از پرش‌های پیاپی) — مختص هر ویدیو
        var lastSeekTimestampMs: Long = 0L,
        var lastSkippedTargetSec: Double = -1.0
    )

    private const val PREF_SKIP_ENABLED_PREFIX = "skip_enabled_"
    private const val PREF_SKIP_MODE_PREFIX = "skip_mode_"
    private const val PREF_SKIP_RANGES_TEXT_PREFIX = "skip_ranges_text_"

    private val configCache = mutableMapOf<Long, SkipConfig>()
    private val cacheLock = Any()

    private const val SEEK_COOLDOWN_MS = 950L

    fun getConfig(context: Context, videoId: Long): SkipConfig {
        synchronized(cacheLock) {
            configCache[videoId]?.let { return it }
            val cfg = SkipConfig()
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                cfg.isEnabled = prefs.getBoolean(PREF_SKIP_ENABLED_PREFIX + videoId, false)
                val modeName = prefs.getString(PREF_SKIP_MODE_PREFIX + videoId, SkipMode.SKIP_SPECIFIED_RANGES.name)
                cfg.mode = try {
                    SkipMode.valueOf(modeName ?: SkipMode.SKIP_SPECIFIED_RANGES.name)
                } catch (e: Exception) {
                    SkipMode.SKIP_SPECIFIED_RANGES
                }
                cfg.rawInputText = prefs.getString(PREF_SKIP_RANGES_TEXT_PREFIX + videoId, "") ?: ""
                cfg.ranges = parseRanges(cfg.rawInputText)
            } catch (_: Exception) {
            }
            configCache[videoId] = cfg
            return cfg
        }
    }

    private fun saveConfig(context: Context, videoId: Long, cfg: SkipConfig) {
        try {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(PREF_SKIP_ENABLED_PREFIX + videoId, cfg.isEnabled)
                .putString(PREF_SKIP_MODE_PREFIX + videoId, cfg.mode.name)
                .putString(PREF_SKIP_RANGES_TEXT_PREFIX + videoId, cfg.rawInputText)
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

    fun checkAndSkipIfNeeded(context: Context, videoId: Long, currentPositionSec: Int, onSkipTriggered: (Double) -> Unit) {
        val cfg = getConfig(context, videoId)
        val activeRanges = cfg.ranges
        if (!cfg.isEnabled || activeRanges.isEmpty()) return

        val now = SystemClock.elapsedRealtime()
        if (now - cfg.lastSeekTimestampMs < SEEK_COOLDOWN_MS) return

        val pos = currentPositionSec.toDouble()

        // اگر کاربر به عقب برگشته، قفل هدف قبلی را آزاد کن
        if (pos < cfg.lastSkippedTargetSec - 3.0 || pos > cfg.lastSkippedTargetSec + 2.0) {
            cfg.lastSkippedTargetSec = -1.0
        }

        when (cfg.mode) {
            SkipMode.SKIP_SPECIFIED_RANGES -> {
                val matched = activeRanges.firstOrNull {
                    pos >= it.startSec && pos < (it.endSec - 0.8)
                }
                if (matched != null && matched.endSec != cfg.lastSkippedTargetSec) {
                    cfg.lastSeekTimestampMs = now
                    cfg.lastSkippedTargetSec = matched.endSec
                    onSkipTriggered(matched.endSec)
                }
            }
            SkipMode.PLAY_ONLY_SPECIFIED_RANGES -> {
                val insideAny = activeRanges.any {
                    pos >= (it.startSec - 0.8) && pos < it.endSec
                }
                if (!insideAny) {
                    val nextRange = activeRanges.firstOrNull { it.startSec > pos }
                    if (nextRange != null && nextRange.startSec != cfg.lastSkippedTargetSec) {
                        cfg.lastSeekTimestampMs = now
                        cfg.lastSkippedTargetSec = nextRange.startSec
                        onSkipTriggered(nextRange.startSec)
                    }
                }
            }
        }
    }

    fun clear(context: Context, videoId: Long) {
        val cfg = getConfig(context, videoId)
        cfg.rawInputText = ""
        cfg.ranges = emptyList()
        cfg.lastSkippedTargetSec = -1.0
        saveConfig(context, videoId, cfg)
    }

    fun showSkipDialog(context: Context, videoId: Long, onDismiss: () -> Unit) {
        val cfg = getConfig(context, videoId)

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_skip_ranges, null)
        val switchSkipEnabled = view.findViewById<SwitchCompat>(R.id.switchSkipEnabled)
        val editSkipRanges = view.findViewById<EditText>(R.id.editSkipRanges)
        val radioGroupSkipMode = view.findViewById<RadioGroup>(R.id.radioGroupSkipMode)
        val radioSkipInside = view.findViewById<RadioButton>(R.id.radioSkipInside)
        val radioKeepOnly = view.findViewById<RadioButton>(R.id.radioKeepOnly)
        val txtSkipStatus = view.findViewById<TextView>(R.id.txtSkipStatus)

        switchSkipEnabled.isChecked = cfg.isEnabled
        editSkipRanges.setText(cfg.rawInputText)
        if (cfg.mode == SkipMode.SKIP_SPECIFIED_RANGES) {
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

        updateStatusPreview(cfg.rawInputText)
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

        view.setBackgroundResource(R.drawable.dialog_dark_bg)
        val alertDialog = AlertDialog.Builder(context, R.style.AppTheme_DarkDialog)
            .setTitle(R.string.skip_title)
            .setView(view)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                cfg.isEnabled = switchSkipEnabled.isChecked
                cfg.rawInputText = editSkipRanges.text.toString()
                cfg.ranges = parseRanges(cfg.rawInputText)
                cfg.mode = if (radioGroupSkipMode.checkedRadioButtonId == R.id.radioSkipInside) {
                    SkipMode.SKIP_SPECIFIED_RANGES
                } else {
                    SkipMode.PLAY_ONLY_SPECIFIED_RANGES
                }
                cfg.lastSeekTimestampMs = 0L
                cfg.lastSkippedTargetSec = -1.0
                saveConfig(context, videoId, cfg)
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
            clear(context, videoId)
            updateStatusPreview("")
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(editSkipRanges.windowToken, 0)
        }
    }
}
