package `is`.xyz.mpv

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat

/*
 * v18: تغییرات برای رفع گیر کردن دیالوگ
 *  - هر تغییر فقط یک «استایل» جدید می‌سازد؛ اگر با آخرین استایل اعمال‌شده یکی باشد، هیچ کاری روی mpv نمی‌شود.
 *  - osd-border-style / osd-back-color فقط وقتی پس‌زمینه عوض شده ست می‌شوند (قبلاً هر تغییری این‌ها را هم ست می‌کرد).
 *  - باز شدن دیالوگ دیگر اعمال اضافه ندارد (قبلاً applyAndSave و کال‌بک اولیهٔ اسپینرها چند کار سنگین می‌ساختند).
 *  - کشیدن اسلایدر با حداکثر یک پیش‌نمایش در هر PREVIEW_THROTTLE_MS؛ رها کردن = اعمال فوری.
 *  - همه‌چیز از طریق MpvBg.postLatest با یک کلید می‌رود، پس فقط آخرین حالت اجرا می‌شود.
 *  - ریست، اسپینر رنگ/پس‌زمینه را هم به‌روز می‌کند (قبلاً فقط اسلایدرها و سوئیچ‌ها ریست می‌شدند).
 */
object SubtitleStyleManager {
    private const val PREF_SUB_SIZE = "custom_sub_font_size"
    private const val PREF_SUB_POS = "custom_sub_pos"
    private const val PREF_SUB_BORDER = "custom_sub_border_size"
    private const val PREF_SUB_BOLD = "custom_sub_bold"
    private const val PREF_SUB_COLOR = "custom_sub_color"
    private const val PREF_SUB_BG_COLOR = "custom_sub_bg_color"
    private const val PREF_SUB_ASS_OVERRIDE = "custom_sub_ass_override"

    private const val DEFAULT_SIZE = 55
    private const val DEFAULT_POS = 100
    private const val DEFAULT_BORDER = 3.0
    private const val DEFAULT_BOLD = false
    private const val DEFAULT_COLOR = "#FFFFFF"
    private const val DEFAULT_BG_COLOR = "transparent"
    private const val DEFAULT_ASS_OVERRIDE = true

    /** فاصلهٔ پیش‌نمایش زنده هنگام کشیدن اسلایدر (ms). بزرگ‌تر = سبک‌تر روی گوشی‌های ضعیف */
    private const val PREVIEW_THROTTLE_MS = 250L

    /** کلید صف MpvBg: فقط آخرین استایل اجرا می‌شود */
    private const val KEY_STYLE = "sub-style"

    data class ColorOption(val nameRes: Int, val hex: String)

    private val textColors = listOf(
        ColorOption(R.string.color_white_default, "#FFFFFF"),
        ColorOption(R.string.color_yellow, "#FFFF00"),
        ColorOption(R.string.color_gold, "#FFD700"),
        ColorOption(R.string.color_cyan, "#00FFFF"),
        ColorOption(R.string.color_light_green, "#00FF66"),
        ColorOption(R.string.color_orange, "#FFA500"),
        ColorOption(R.string.color_pink, "#FF69B4")
    )

    data class BgOption(val nameRes: Int, val id: String)

    private val bgOptions = listOf(
        BgOption(R.string.bg_transparent, "transparent"),
        BgOption(R.string.bg_quarter_25, "quarter_25"),
        BgOption(R.string.bg_semi_50, "semi_50"),
        BgOption(R.string.bg_dark_75, "dark_75"),
        BgOption(R.string.bg_solid_100, "solid_100")
    )

    /** نمای کامل استایل. مقایسهٔ data class یعنی «آیا چیزی عوض شده؟» */
    private data class Style(
        val size: Int,
        val pos: Int,
        val border: Double,
        val bold: Boolean,
        val color: String,
        val bgId: String,
        val assOverride: Boolean
    )

    /** آخرین استایلی که روی mpv اعمال شده. فقط روی ترد MpvBg تغییر می‌کند */
    @Volatile private var lastApplied: Style? = null

    private fun readStyle(prefs: SharedPreferences) = Style(
        size = prefs.getInt(PREF_SUB_SIZE, DEFAULT_SIZE).coerceIn(20, 100),
        pos = prefs.getInt(PREF_SUB_POS, DEFAULT_POS).coerceIn(0, 100),
        border = prefs.getFloat(PREF_SUB_BORDER, DEFAULT_BORDER.toFloat()).toDouble().coerceIn(0.0, 6.0),
        bold = prefs.getBoolean(PREF_SUB_BOLD, DEFAULT_BOLD),
        color = prefs.getString(PREF_SUB_COLOR, DEFAULT_COLOR) ?: DEFAULT_COLOR,
        bgId = prefs.getString(PREF_SUB_BG_COLOR, DEFAULT_BG_COLOR) ?: DEFAULT_BG_COLOR,
        assOverride = prefs.getBoolean(PREF_SUB_ASS_OVERRIDE, DEFAULT_ASS_OVERRIDE)
    )

    private fun saveStyle(prefs: SharedPreferences, s: Style) {
        prefs.edit()
            .putInt(PREF_SUB_SIZE, s.size)
            .putInt(PREF_SUB_POS, s.pos)
            .putFloat(PREF_SUB_BORDER, s.border.toFloat())
            .putBoolean(PREF_SUB_BOLD, s.bold)
            .putString(PREF_SUB_COLOR, s.color)
            .putString(PREF_SUB_BG_COLOR, s.bgId)
            .putBoolean(PREF_SUB_ASS_OVERRIDE, s.assOverride)
            .apply()
    }

    fun applyInitialOptions(context: Context) {
        val s = readStyle(PreferenceManager.getDefaultSharedPreferences(context))

        // مخفی نگه داشتن لایهٔ خام زیرنویس mpv (چون رندر اصلی در SubtitleNormalizer انجام می‌شود)
        MPVLib.setOptionString("sub-font", FontInstaller.FONT_FAMILY)
        MPVLib.setOptionString("sub-color", "0/0/0/0")
        MPVLib.setOptionString("sub-border-color", "0/0/0/0")
        MPVLib.setOptionString("sub-shadow-color", "0/0/0/0")
        MPVLib.setOptionString("sub-back-color", "0/0/0/0")
        MPVLib.setOptionString("sub-ass-override", "strip")
        MPVLib.setOptionString("sub-scale", "0.001")

        setOsdBox(s.bgId, isInit = true)
        lastApplied = s
        SubtitleNormalizer.updateStyleConfig(
            size = s.size,
            pos = s.pos,
            border = s.border,
            bold = s.bold,
            colorHex = s.color,
            bgId = s.bgId,
            assOverride = s.assOverride,
            refreshNow = false
        )
    }

    private fun setOsdBox(bgId: String, isInit: Boolean) {
        val borderStyle = if (bgId == "transparent") "outline-and-shadow" else "opaque-box"
        val backColor = when (bgId) {
            "quarter_25" -> "#40000000"
            "semi_50" -> "#80000000"
            "dark_75" -> "#BF000000"
            "solid_100" -> "#FF000000"
            else -> "#00000000"
        }
        if (isInit) {
            MPVLib.setOptionString("osd-border-style", borderStyle)
            MPVLib.setOptionString("osd-back-color", backColor)
        } else {
            MPVLib.setPropertyString("osd-border-style", borderStyle)
            MPVLib.setPropertyString("osd-back-color", backColor)
        }
    }

    /** فقط روی ترد MpvBg اجرا می‌شود */
    private fun applyOnBg(s: Style) {
        val prev = lastApplied
        if (prev == s) return // هیچ تغییری نیست؛ هیچ کار اضافه‌ای روی mpv نمی‌شود

        if (prev == null || prev.bgId != s.bgId) {
            setOsdBox(s.bgId, isInit = false) // کادر OSD فقط وقتی پس‌زمینه عوض شده
        }
        lastApplied = s

        SubtitleNormalizer.updateStyleConfig(
            size = s.size,
            pos = s.pos,
            border = s.border,
            bold = s.bold,
            colorHex = s.color,
            bgId = s.bgId,
            assOverride = s.assOverride,
            refreshNow = true // یک رندر؛ داخل صف با کلید norm-sub با رندرهای دیگر یکی می‌شود
        )
    }

    private fun requestApply(s: Style) {
        MpvBg.postLatest(KEY_STYLE) { applyOnBg(s) }
    }

    fun showStyleDialog(context: Context, onDismiss: () -> Unit) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_subtitle_style, null)

        val txtSubSize = view.findViewById<TextView>(R.id.txtSubSize)
        val seekSubSize = view.findViewById<SeekBar>(R.id.seekSubSize)
        val txtSubPos = view.findViewById<TextView>(R.id.txtSubPos)
        val seekSubPos = view.findViewById<SeekBar>(R.id.seekSubPos)
        val txtSubBorder = view.findViewById<TextView>(R.id.txtSubBorder)
        val seekSubBorder = view.findViewById<SeekBar>(R.id.seekSubBorder)
        val spinnerSubColor = view.findViewById<Spinner>(R.id.spinnerSubColor)
        val spinnerSubBgColor = view.findViewById<Spinner>(R.id.spinnerSubBgColor)
        val switchSubBold = view.findViewById<SwitchCompat>(R.id.switchSubBold)
        val switchAssOverride = view.findViewById<SwitchCompat>(R.id.switchAssOverride)

        val saved = readStyle(prefs)
        var currentSize = saved.size
        var currentPos = saved.pos
        var currentBorder = saved.border
        var currentBold = saved.bold
        var currentColor = saved.color
        var currentBgId = saved.bgId
        var currentAssOverride = saved.assOverride

        fun snapshot() = Style(
            currentSize, currentPos, currentBorder, currentBold,
            currentColor, currentBgId, currentAssOverride
        )

        fun updateLabels() {
            txtSubSize.text = context.getString(R.string.subtitle_size_label, currentSize)
            txtSubPos.text = context.getString(R.string.subtitle_pos_label, currentPos)
            txtSubBorder.text = context.getString(R.string.subtitle_border_label, currentBorder)
        }

        val handler = Handler(Looper.getMainLooper())
        var previewQueued = false
        val previewRunnable = Runnable {
            previewQueued = false
            requestApply(snapshot())
        }

        // هنگام کشیدن اسلایدر: حداکثر یک پیش‌نمایش در هر PREVIEW_THROTTLE_MS
        fun schedulePreview() {
            if (!previewQueued) {
                previewQueued = true
                handler.postDelayed(previewRunnable, PREVIEW_THROTTLE_MS)
            }
        }

        // تغییر قطعی (رها کردن اسلایدر، سوئیچ، اسپینر، ریست، بستن): ذخیره + اعمال
        fun commitNow() {
            if (previewQueued) {
                handler.removeCallbacks(previewRunnable)
                previewQueued = false
            }
            val s = snapshot()
            saveStyle(prefs, s)
            requestApply(s)
        }

        // همگام‌سازی کنترل‌ها با متغیرهای فعلی (اسپینرها باید adapter داشته باشند)
        fun syncControls() {
            seekSubSize.progress = (currentSize - 20).coerceIn(0, 80)
            seekSubPos.progress = currentPos.coerceIn(0, 100)
            seekSubBorder.progress = (currentBorder * 10).toInt().coerceIn(0, 60)
            switchSubBold.isChecked = currentBold
            switchAssOverride.isChecked = currentAssOverride
            spinnerSubColor.setSelection(
                textColors.indexOfFirst { it.hex.equals(currentColor, true) }.coerceAtLeast(0)
            )
            spinnerSubBgColor.setSelection(
                bgOptions.indexOfFirst { it.id == currentBgId }.coerceAtLeast(0)
            )
        }

        // پیش‌نمایش زنده فعال؛ این تنها رندر باز شدن دیالوگ است (اعمال اضافه‌ای نیست)
        SubtitleNormalizer.setPreviewMode(true)

        spinnerSubColor.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            textColors.map { context.getString(it.nameRes) }
        )
        spinnerSubBgColor.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            bgOptions.map { context.getString(it.nameRes) }
        )

        updateLabels()
        syncControls() // کال‌بک اولیهٔ اسپینرها بعد از layout می‌آید و با گارد «مقدار یکسان» نادیده گرفته می‌شود

        seekSubSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentSize = 20 + progress
                updateLabels()
                schedulePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { commitNow() }
        })

        seekSubPos.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentPos = progress
                updateLabels()
                schedulePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { commitNow() }
        })

        seekSubBorder.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentBorder = progress / 10.0
                updateLabels()
                schedulePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { commitNow() }
        })

        spinnerSubColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val hex = textColors[position].hex
                // اندروید هنگام اولین layout همین کال‌بک را با مقدار فعلی صدا می‌زند؛ آن را نادیده بگیر
                if (hex.equals(currentColor, true)) return
                currentColor = hex
                commitNow()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerSubBgColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val id2 = bgOptions[position].id
                if (id2 == currentBgId) return
                currentBgId = id2
                commitNow()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        switchSubBold.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == currentBold) return@setOnCheckedChangeListener
            currentBold = isChecked
            commitNow()
        }

        switchAssOverride.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked == currentAssOverride) return@setOnCheckedChangeListener
            currentAssOverride = isChecked
            commitNow()
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.subtitle_style_title)
            .setView(view)
            .setPositiveButton(R.string.dialog_ok) { dialog, _ -> dialog.dismiss() }
            .setNeutralButton(R.string.dialog_reset) { _, _ ->
                currentSize = DEFAULT_SIZE
                currentPos = DEFAULT_POS
                currentBorder = DEFAULT_BORDER
                currentBold = DEFAULT_BOLD
                currentColor = DEFAULT_COLOR
                currentBgId = DEFAULT_BG_COLOR
                currentAssOverride = DEFAULT_ASS_OVERRIDE
                syncControls()
                updateLabels()
                commitNow()
            }
            .setOnDismissListener {
                commitNow()
                SubtitleNormalizer.setPreviewMode(false)
                onDismiss()
            }
            .create()
            .show()
    }
}
