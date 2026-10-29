package `is`.xyz.mpv

import android.content.Context
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

    private const val DEFAULT_SIZE = 55 // بازه 20 تا 100
    private const val DEFAULT_POS = 100 // بازه 20 تا 150
    private const val DEFAULT_BORDER = 3.0 // بازه 0.0 تا 6.0
    private const val DEFAULT_BOLD = false
    private const val DEFAULT_COLOR = "#FFFFFF"
    private const val DEFAULT_BG_COLOR = "#00000000"
    private const val DEFAULT_ASS_OVERRIDE = true

    data class ColorOption(val name: String, val hex: String)

    private val textColors = listOf(
        ColorOption("سفید (پیش‌فرض)", "#FFFFFF"),
        ColorOption("زرد", "#FFFF00"),
        ColorOption("طلایی", "#FFD700"),
        ColorOption("فیروزه‌ای", "#00FFFF"),
        ColorOption("سبز روشن", "#00FF66"),
        ColorOption("نارنجی", "#FFA500"),
        ColorOption("صورتی", "#FF69B4")
    )

    private val bgColors = listOf(
        ColorOption("بدون کادر پس‌زمینه (شفاف)", "#00000000"),
        ColorOption("مشکی نیمه‌شفاف (۵۰٪)", "#80000000"),
        ColorOption("مشکی تیره (۷۵٪)", "#BF000000"),
        ColorOption("مشکی کامل (۱۰۰٪)", "#FF000000")
    )

    /**
     * فراخوانی در MPVView.initOptions() قبل از MPVLib.init()
     */
    fun applyInitialOptions(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val size = prefs.getInt(PREF_SUB_SIZE, DEFAULT_SIZE)
        val pos = prefs.getInt(PREF_SUB_POS, DEFAULT_POS)
        val border = prefs.getFloat(PREF_SUB_BORDER, DEFAULT_BORDER.toFloat()).toDouble()
        val bold = prefs.getBoolean(PREF_SUB_BOLD, DEFAULT_BOLD)
        val color = prefs.getString(PREF_SUB_COLOR, DEFAULT_COLOR) ?: DEFAULT_COLOR
        val bgColor = prefs.getString(PREF_SUB_BG_COLOR, DEFAULT_BG_COLOR) ?: DEFAULT_BG_COLOR
        val assOverride = prefs.getBoolean(PREF_SUB_ASS_OVERRIDE, DEFAULT_ASS_OVERRIDE)

        MPVLib.setOptionString("sub-font", FontInstaller.FONT_FAMILY)
        MPVLib.setOptionString("sub-font-size", size.toString())
        MPVLib.setOptionString("sub-pos", pos.toString())
        MPVLib.setOptionString("sub-border-size", border.toString())
        MPVLib.setOptionString("sub-bold", if (bold) "yes" else "no")
        MPVLib.setOptionString("sub-color", color)
        MPVLib.setOptionString("sub-back-color", bgColor)
        MPVLib.setOptionString("sub-ass-override", if (assOverride) "force" else "yes")
    }

    /**
     * اعمال آنی تغییرات در حین پخش ویدیو
     */
    private fun applyLiveProperties(
        size: Int,
        pos: Int,
        border: Double,
        bold: Boolean,
        color: String,
        bgColor: String,
        assOverride: Boolean
    ) {
        MPVLib.setPropertyString("sub-font", FontInstaller.FONT_FAMILY)
        MPVLib.setPropertyInt("sub-font-size", size)
        MPVLib.setPropertyInt("sub-pos", pos)
        MPVLib.setPropertyDouble("sub-border-size", border)
        MPVLib.setPropertyBoolean("sub-bold", bold)
        MPVLib.setPropertyString("sub-color", color)
        MPVLib.setPropertyString("sub-back-color", bgColor)
        MPVLib.setPropertyString("sub-ass-override", if (assOverride) "force" else "yes")
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

        var currentSize = prefs.getInt(PREF_SUB_SIZE, DEFAULT_SIZE)
        var currentPos = prefs.getInt(PREF_SUB_POS, DEFAULT_POS)
        var currentBorder = prefs.getFloat(PREF_SUB_BORDER, DEFAULT_BORDER.toFloat()).toDouble()
        var currentBold = prefs.getBoolean(PREF_SUB_BOLD, DEFAULT_BOLD)
        var currentColor = prefs.getString(PREF_SUB_COLOR, DEFAULT_COLOR) ?: DEFAULT_COLOR
        var currentBgColor = prefs.getString(PREF_SUB_BG_COLOR, DEFAULT_BG_COLOR) ?: DEFAULT_BG_COLOR
        var currentAssOverride = prefs.getBoolean(PREF_SUB_ASS_OVERRIDE, DEFAULT_ASS_OVERRIDE)

        fun syncAndSave() {
            txtSubSize.text = "اندازه زیرنویس: $currentSize"
            txtSubPos.text = "موقعیت عمودی (ارتفاع): $currentPos%"
            txtSubBorder.text = "ضخامت حاشیه (Outline): ${"%.1f".format(currentBorder)}"

            applyLiveProperties(
                currentSize, currentPos, currentBorder,
                currentBold, currentColor, currentBgColor, currentAssOverride
            )

            prefs.edit()
                .putInt(PREF_SUB_SIZE, currentSize)
                .putInt(PREF_SUB_POS, currentPos)
                .putFloat(PREF_SUB_BORDER, currentBorder.toFloat())
                .putBoolean(PREF_SUB_BOLD, currentBold)
                .putString(PREF_SUB_COLOR, currentColor)
                .putString(PREF_SUB_BG_COLOR, currentBgColor)
                .putBoolean(PREF_SUB_ASS_OVERRIDE, currentAssOverride)
                .apply()
        }

        // تنظیم مقادیر اولیه UI
        seekSubSize.progress = (currentSize - 20).coerceIn(0, 80)
        seekSubPos.progress = (currentPos - 20).coerceIn(0, 130)
        seekSubBorder.progress = (currentBorder * 10).toInt().coerceIn(0, 60)
        switchSubBold.isChecked = currentBold
        switchAssOverride.isChecked = currentAssOverride

        spinnerSubColor.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            textColors.map { it.name }
        )
        spinnerSubColor.setSelection(
            textColors.indexOfFirst { it.hex.equals(currentColor, true) }.coerceAtLeast(0)
        )

        spinnerSubBgColor.adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_dropdown_item,
            bgColors.map { it.name }
        )
        spinnerSubBgColor.setSelection(
            bgColors.indexOfFirst { it.hex.equals(currentBgColor, true) }.coerceAtLeast(0)
        )

        syncAndSave()

        // لیسنرها
        seekSubSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentSize = 20 + progress
                syncAndSave()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekSubPos.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentPos = 20 + progress
                syncAndSave()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekSubBorder.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentBorder = progress / 10.0
                syncAndSave()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        spinnerSubColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                currentColor = textColors[position].hex
                syncAndSave()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerSubBgColor.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                currentBgColor = bgColors[position].hex
                syncAndSave()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        switchSubBold.setOnCheckedChangeListener { _, isChecked ->
            currentBold = isChecked
            syncAndSave()
        }

        switchAssOverride.setOnCheckedChangeListener { _, isChecked ->
            currentAssOverride = isChecked
            syncAndSave()
        }

        AlertDialog.Builder(context)
            .setTitle("شخصی‌سازی زیرنویس")
            .setView(view)
            .setPositiveButton(R.string.dialog_ok) { dialog, _ -> dialog.dismiss() }
            .setNeutralButton(R.string.dialog_reset) { _, _ ->
                currentSize = DEFAULT_SIZE
                currentPos = DEFAULT_POS
                currentBorder = DEFAULT_BORDER
                currentBold = DEFAULT_BOLD
                currentColor = DEFAULT_COLOR
                currentBgColor = DEFAULT_BG_COLOR
                currentAssOverride = DEFAULT_ASS_OVERRIDE
                syncAndSave()
            }
            .setOnDismissListener { onDismiss() }
            .create()
            .show()
    }
}
