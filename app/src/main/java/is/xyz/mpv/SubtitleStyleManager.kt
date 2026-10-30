package `is`.xyz.mpv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat

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

    private const val APPLY_DEBOUNCE_MS = 250L

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

    data class BgOption(val nameRes: Int, val id: String, val assStyle: String)

    // تنظیم دقیق کادر و رنگ بر اساس سینتکس ASS
    private val bgOptions = listOf(
        BgOption(R.string.bg_transparent, "transparent", "BorderStyle=1"),
        BgOption(R.string.bg_semi_50, "semi_50", "BorderStyle=3,BackColour=&H80000000,OutlineColour=&H80000000,Outline=4"),
        BgOption(R.string.bg_dark_75, "dark_75", "BorderStyle=3,BackColour=&H40000000,OutlineColour=&H40000000,Outline=4"),
        BgOption(R.string.bg_solid_100, "solid_100", "BorderStyle=3,BackColour=&H00000000,OutlineColour=&H00000000,Outline=4")
    )

    fun applyInitialOptions(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val size = prefs.getInt(PREF_SUB_SIZE, DEFAULT_SIZE).coerceIn(20, 100)
        val pos = prefs.getInt(PREF_SUB_POS, DEFAULT_POS).coerceIn(0, 100)
        val border = prefs.getFloat(PREF_SUB_BORDER, DEFAULT_BORDER.toFloat()).toDouble().coerceIn(0.0, 6.0)
        val bold = prefs.getBoolean(PREF_SUB_BOLD, DEFAULT_BOLD)
        val color = prefs.getString(PREF_SUB_COLOR, DEFAULT_COLOR) ?: DEFAULT_COLOR
        val bgId = prefs.getString(PREF_SUB_BG_COLOR, DEFAULT_BG_COLOR) ?: DEFAULT_BG_COLOR
        val assOverride = prefs.getBoolean(PREF_SUB_ASS_OVERRIDE, DEFAULT_ASS_OVERRIDE)

        MPVLib.setOptionString("sub-font", FontInstaller.FONT_FAMILY)
        MPVLib.setOptionString("sub-font-size", size.toString())
        MPVLib.setOptionString("sub-pos", pos.toString())
        MPVLib.setOptionString("sub-border-size", border.toString())
        MPVLib.setOptionString("sub-bold", if (bold) "yes" else "no")
        MPVLib.setOptionString("sub-color", color)
        MPVLib.setOptionString("sub-ass-override", if (assOverride) "force" else "yes")

        val bgOpt = bgOptions.firstOrNull { it.id == bgId } ?: bgOptions[0]
        MPVLib.setOptionString("sub-ass-style-overrides", bgOpt.assStyle)
    }

    private var lastSize: Int? = null
    private var lastPos: Int? = null
    private var lastBorder: Double? = null
    private var lastBold: Boolean? = null
    private var lastColor: String? = null
    private var lastBgId: String? = null
    private var lastAssOverride: Boolean? = null

    private fun applyLiveProperties(
        size: Int,
        pos: Int,
        border: Double,
        bold: Boolean,
        color: String,
        bgId: String,
        assOverride: Boolean,
        forceAll: Boolean = false
    ) {
        if (forceAll || lastSize != size) {
            MPVLib.setPropertyInt("sub-font-size", size)
            lastSize = size
        }
        if (forceAll || lastPos != pos) {
            MPVLib.setPropertyInt("sub-pos", pos)
            lastPos = pos
        }
        if (forceAll || lastBorder != border) {
            MPVLib.setPropertyDouble("sub-border-size", border)
            lastBorder = border
        }
        if (forceAll || lastBold != bold) {
            MPVLib.setPropertyBoolean("sub-bold", bold)
            lastBold = bold
        }
        if (forceAll || lastColor != color) {
            MPVLib.setPropertyString("sub-color", color)
            lastColor = color
        }
        if (forceAll || lastAssOverride != assOverride) {
            MPVLib.setPropertyString("sub-ass-override", if (assOverride) "force" else "yes")
            lastAssOverride = assOverride
        }
        if (forceAll || lastBgId != bgId) {
            val bgOpt = bgOptions.firstOrNull { it.id == bgId } ?: bgOptions[0]
            MPVLib.setPropertyString("sub-ass-style-overrides", bgOpt.assStyle)
            reloadSubtitleTrack()
            lastBgId = bgId
        }
    }

    /**
     * ریلود کردن واقعی تِرک فعال برای اعمال آنی استایل کادر جدید
     */
    private fun reloadSubtitleTrack() {
        try {
            val sid = MPVLib.getPropertyInt("sid") ?: -1
            if (sid > 0) {
                MPVLib.setPropertyInt("sid", -1)
                MPVLib.setPropertyInt("sid", sid)
            }
        } catch (e: Exception) {
            // نادیده بگیر
        }
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

        var currentSize = prefs.getInt(PREF_SUB_SIZE, DEFAULT_SIZE).coerceIn(20, 100)
        var currentPos = prefs.getInt(PREF_SUB_POS, DEFAULT_POS).coerceIn(0, 100)
        var currentBorder = prefs.getFloat(PREF_SUB_BORDER, DEFAULT_BORDER.toFloat()).toDouble().coerceIn(0.0, 6.0)
        var currentBold = prefs.getBoolean(PREF_SUB_BOLD, DEFAULT_BOLD)
        var currentColor = prefs.getString(PREF_SUB_COLOR, DEFAULT_COLOR) ?: DEFAULT_COLOR
        var currentBgId = prefs.getString(PREF_SUB_BG_COLOR, DEFAULT_BG_COLOR) ?: DEFAULT_BG_COLOR
        var currentAssOverride = prefs.getBoolean(PREF_SUB_ASS_OVERRIDE, DEFAULT_ASS_OVERRIDE)

        fun updateLabels() {
            txtSubSize.text = context.getString(R.string.subtitle_size_label, currentSize)
            txtSubPos.text = context.getString(R.string.subtitle_pos_label, currentPos)
            txtSubBorder.text = context.getString(R.string.subtitle_border_label, currentBorder)
        }

        fun applyAndSave() {
            applyLiveProperties(
                currentSize, currentPos, currentBorder,
                currentBold, currentColor, currentBgId, currentAssOverride
            )

            prefs.edit()
                .putInt(PREF_SUB_SIZE, currentSize)
                .putInt(PREF_SUB_POS, currentPos)
                .putFloat(PREF_SUB_BORDER, currentBorder.toFloat())
                .putBoolean(PREF_SUB_BOLD, currentBold)
                .putString(PREF_SUB_COLOR, currentColor)
                .putString(PREF_SUB_BG_COLOR, currentBgId)
                .putBoolean(PREF_SUB_ASS_OVERRIDE, currentAssOverride)
                .apply()
        }

        val handler = Handler(Looper.getMainLooper())
        var pendingApply: Runnable? = null

        fun scheduleApply() {
            pendingApply?.let { handler.removeCallbacks(it) }
            pendingApply = Runnable { applyAndSave() }
            handler.postDelayed(pendingApply!!, APPLY_DEBOUNCE_MS)
        }

        fun flushApply() {
            pendingApply?.let {
                handler.removeCallbacks(it)
                pendingApply = null
                applyAndSave()
            }
        }

        fun syncSeekBars() {
            seekSubSize.progress = (currentSize - 20).coerceIn(0, 80)
            seekSubPos.progress = currentPos.coerceIn(0, 100)
            seekSubBorder.progress = (currentBorder * 10).toInt().coerceIn(0, 60)
            switchSubBold.isChecked = currentBold
            switchAssOverride.isChecked = currentAssOverride
        }

        syncSeekBars()
        updateLabels()

        spinnerSubColor.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            textColors.map { context.getString(it.nameRes) }
        )
        spinnerSubColor.setSelection(
            textColors.indexOfFirst { it.hex.equals(currentColor, true) }.coerceAtLeast(0)
        )

        spinnerSubBgColor.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            bgOptions.map { context.getString(it.nameRes) }
        )
        spinnerSubBgColor.setSelection(
            bgOptions.indexOfFirst { it.id == currentBgId }.coerceAtLeast(0)
        )

        applyAndSave()

        seekSubSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentSize = 20 + progress
                updateLabels()
                scheduleApply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { flushApply() }
        })

        seekSubPos.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentPos = progress
                updateLabels()
                scheduleApply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { flushApply() }
        })

        seekSubBorder.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentBorder = progress / 10.0
                updateLabels()
                scheduleApply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { flushApply() }
        })

        spinnerSubColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                currentColor = textColors[position].hex
                updateLabels()
                applyAndSave()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerSubBgColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                currentBgId = bgOptions[position].id
                updateLabels()
                applyAndSave()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        switchSubBold.setOnCheckedChangeListener { _, isChecked ->
            currentBold = isChecked
            applyAndSave()
        }

        switchAssOverride.setOnCheckedChangeListener { _, isChecked ->
            currentAssOverride = isChecked
            applyAndSave()
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
                syncSeekBars()
                updateLabels()
                applyAndSave()
            }
            .setOnDismissListener {
                flushApply()
                onDismiss()
            }
            .create()
            .show()
    }
}
