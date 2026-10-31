package `is`.xyz.mpv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object SubtitleStyleManager {
    private const val PREF_SUB_SIZE = "custom_sub_font_size"
    private const val PREF_SUB_POS = "custom_sub_pos"
    private const val PREF_SUB_BORDER = "custom_sub_border_size"
    private const val PREF_SUB_BOLD = "custom_sub_bold"
    private const val PREF_SUB_COLOR = "custom_sub_color"
    private const val PREF_SUB_BG_COLOR = "custom_sub_bg_color"

    private const val DEFAULT_SIZE = 55
    private const val DEFAULT_POS = 100
    private const val DEFAULT_BORDER = 3.0
    private const val DEFAULT_BOLD = false
    private const val DEFAULT_COLOR = "#FFFFFF"
    private const val DEFAULT_BG_COLOR = "transparent"

    private const val PREVIEW_THROTTLE_MS = 180L
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

    private data class Style(
        val size: Int,
        val pos: Int,
        val border: Double,
        val bold: Boolean,
        val color: String,
        val bgId: String,
    )

    @Volatile
    private var lastApplied: Style? = null

    /**
     * محاسبه ارتفاع ثابت صفحه با نادیده گرفتن وضعیت فعلی (مخفی/ظاهر) نوار ژست حرکتی
     * تا ارتفاع دیالوگ هرگز با ژست‌های حرکتی گوشی کم و زیاد نشود.
     */
    fun stableWindowBounds(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val w = (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(320)
            val h = (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(240)
            return Pair(w, h)
        }
        val dm = context.resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    /**
     * نمایش دیالوگ بدون خروج از حالت تمام‌صفحه (Immersive) و بدون تغییر سایز در اثر ژست‌های حرکتی
     */
    fun showImmersiveDialog(context: Context, dialog: AlertDialog, maxHeightRatio: Double = 0.90) {
        val win: Window? = dialog.window
        if (win != null) {
            // جلوگیری از فوکوس‌گیری اولیه که باعث پرش نوار ژست حرکتی می‌شود
            win.setFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
            WindowCompat.setDecorFitsSystemWindows(win, false)
        }

        dialog.show()

        if (win != null) {
            // همگام‌سازی وضعیت Immersive با اکتیویتی اصلی و بازگرداندن فوکوس برای کارکرد روان دکمه‌ها
            (context as? Activity)?.window?.decorView?.systemUiVisibility?.let {
                @Suppress("DEPRECATION")
                win.decorView.systemUiVisibility = it
            }
            val controller = WindowCompat.getInsetsController(win, win.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())

            win.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

            // قفل کردن حداکثر ارتفاع ثابت
            val (availW, availH) = stableWindowBounds(context)
            val maxH = (availH * maxHeightRatio).toInt()
            val maxW = if (availW > availH) (availW * 0.78).toInt().coerceAtMost(dp(context, 600f))
            else (availW * 0.94).toInt()

            val lp = win.attributes
            lp.width = maxW
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.CENTER
            win.attributes = lp

            win.decorView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (v.height > maxH && win.attributes.height != maxH) {
                    v.post {
                        val attr = win.attributes
                        attr.height = maxH
                        win.attributes = attr
                    }
                }
            }
        }
    }

    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics
        ).toInt()

    private fun readStyle(prefs: SharedPreferences) = Style(
        size = prefs.getInt(PREF_SUB_SIZE, DEFAULT_SIZE).coerceIn(20, 100),
        pos = prefs.getInt(PREF_SUB_POS, DEFAULT_POS).coerceIn(0, 100),
        border = prefs.getFloat(PREF_SUB_BORDER, DEFAULT_BORDER.toFloat()).toDouble().coerceIn(0.0, 6.0),
        bold = prefs.getBoolean(PREF_SUB_BOLD, DEFAULT_BOLD),
        color = prefs.getString(PREF_SUB_COLOR, DEFAULT_COLOR) ?: DEFAULT_COLOR,
        bgId = prefs.getString(PREF_SUB_BG_COLOR, DEFAULT_BG_COLOR) ?: DEFAULT_BG_COLOR
    )

    private fun saveStyle(prefs: SharedPreferences, s: Style) {
        prefs.edit()
            .putInt(PREF_SUB_SIZE, s.size)
            .putInt(PREF_SUB_POS, s.pos)
            .putFloat(PREF_SUB_BORDER, s.border.toFloat())
            .putBoolean(PREF_SUB_BOLD, s.bold)
            .putString(PREF_SUB_COLOR, s.color)
            .putString(PREF_SUB_BG_COLOR, s.bgId)
            .apply()
    }

    fun applyInitialOptions(context: Context) {
        val s = readStyle(PreferenceManager.getDefaultSharedPreferences(context))

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

    private fun applyOnBg(s: Style) {
        val prev = lastApplied
        if (prev == s) return

        if (prev == null || prev.bgId != s.bgId) {
            setOsdBox(s.bgId, isInit = false)
        }
        lastApplied = s

        SubtitleNormalizer.updateStyleConfig(
            size = s.size,
            pos = s.pos,
            border = s.border,
            bold = s.bold,
            colorHex = s.color,
            bgId = s.bgId,
            refreshNow = true
        )
    }

    private fun requestApply(s: Style) {
        MpvBg.postLatest(KEY_STYLE) { applyOnBg(s) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun lockSeekBarTouch(seekBar: SeekBar) {
        seekBar.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    fun showStyleDialog(context: Context, onDismiss: () -> Unit) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_subtitle_style, null)

        val scrollSubStyle = view.findViewById<ScrollView>(R.id.scrollSubStyle)
        val txtSubSize = view.findViewById<TextView>(R.id.txtSubSize)
        val seekSubSize = view.findViewById<SeekBar>(R.id.seekSubSize)
        val btnSizeMinus = view.findViewById<View>(R.id.btnSizeMinus)
        val btnSizePlus = view.findViewById<View>(R.id.btnSizePlus)

        val txtSubPos = view.findViewById<TextView>(R.id.txtSubPos)
        val seekSubPos = view.findViewById<SeekBar>(R.id.seekSubPos)
        val btnPosMinus = view.findViewById<View>(R.id.btnPosMinus)
        val btnPosPlus = view.findViewById<View>(R.id.btnPosPlus)

        val txtSubBorder = view.findViewById<TextView>(R.id.txtSubBorder)
        val seekSubBorder = view.findViewById<SeekBar>(R.id.seekSubBorder)
        val btnBorderMinus = view.findViewById<View>(R.id.btnBorderMinus)
        val btnBorderPlus = view.findViewById<View>(R.id.btnBorderPlus)

        val colorChipsContainer = view.findViewById<LinearLayout>(R.id.colorChipsContainer)
        val bgChipsContainer = view.findViewById<LinearLayout>(R.id.bgChipsContainer)
        val switchSubBold = view.findViewById<CheckBox>(R.id.switchSubBold)

        // جلوگیری از سرقت لمس اسلایدرها توسط ScrollView
        lockSeekBarTouch(seekSubSize)
        lockSeekBarTouch(seekSubPos)
        lockSeekBarTouch(seekSubBorder)

        val saved = readStyle(prefs)
        var currentSize = saved.size
        var currentPos = saved.pos
        var currentBorder = saved.border
        var currentBold = saved.bold
        var currentColor = saved.color
        var currentBgId = saved.bgId

        var updatingUiProgrammatically = false

        fun snapshot() = Style(
            currentSize, currentPos, currentBorder, currentBold,
            currentColor, currentBgId
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

        fun schedulePreview() {
            if (!previewQueued) {
                previewQueued = true
                handler.postDelayed(previewRunnable, PREVIEW_THROTTLE_MS)
            }
        }

        fun commitNow() {
            if (previewQueued) {
                handler.removeCallbacks(previewRunnable)
                previewQueued = false
            }
            val s = snapshot()
            saveStyle(prefs, s)
            requestApply(s)
        }

        // ساخت دایره‌های رنگی تک‌کلیکی (جایگزین سریع و بدون باگ Spinner)
        val colorViews = mutableListOf<TextView>()
        fun refreshColorChips() {
            colorViews.forEachIndexed { idx, tv ->
                val opt = textColors[idx]
                val selected = opt.hex.equals(currentColor, ignoreCase = true)
                val fill = Color.parseColor(opt.hex)
                // چیپ سفید روی دیالوگ سفید: همیشه حلقه خاکستری مرئی داشته باشد
                val isWhiteChip = opt.hex.equals("#FFFFFF", ignoreCase = true)
                tv.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(fill)
                    setStroke(
                        dp(context, if (selected) 3f else 1.5f),
                        when {
                            selected && isWhiteChip -> Color.parseColor("#555555")
                            selected -> Color.WHITE
                            else -> Color.parseColor("#999999")
                        }
                    )
                }
                tv.text = if (selected) "✓" else ""
                tv.setTextColor(if (isWhiteChip) Color.parseColor("#555555") else Color.BLACK)
                tv.scaleX = if (selected) 1.12f else 1.0f
                tv.scaleY = if (selected) 1.12f else 1.0f
            }
        }

        val chipSize = dp(context, 34f)
        val chipMargin = dp(context, 5f)
        textColors.forEach { opt ->
            val tv = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(chipSize, chipSize).apply {
                    marginEnd = chipMargin
                    marginStart = chipMargin
                }
                gravity = Gravity.CENTER
                setTextColor(Color.BLACK)
                textSize = 15f
                paint.isFakeBoldText = true
                contentDescription = context.getString(opt.nameRes)
                setOnClickListener {
                    if (!opt.hex.equals(currentColor, ignoreCase = true)) {
                        currentColor = opt.hex
                        refreshColorChips()
                        commitNow()
                    }
                }
            }
            colorViews.add(tv)
            colorChipsContainer.addView(tv)
        }

        // ساخت دکمه‌های انتخاب کادر پس‌زمینه (تک‌کلیکی بدون باز کردن منوی کشویی)
        val bgViews = mutableListOf<TextView>()
        fun refreshBgChips() {
            bgViews.forEachIndexed { idx, tv ->
                val opt = bgOptions[idx]
                val selected = opt.id == currentBgId
                tv.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(context, 8f).toFloat()
                    // روی دیالوگ سفید: گزینه انتخاب‌نشده خاکستری روشن با متن تیره باشد تا دیده شود
                    setColor(
                        if (selected) Color.parseColor("#7E57C2")
                        else Color.parseColor("#E8E8E8")
                    )
                    setStroke(
                        dp(context, 1f),
                        if (selected) Color.WHITE else Color.parseColor("#BDBDBD")
                    )
                }
                tv.setTextColor(if (selected) Color.WHITE else Color.parseColor("#424242"))
                tv.paint.isFakeBoldText = selected
            }
        }

        bgOptions.forEach { opt ->
            val tv = TextView(context).apply {
                // عرض wrap_content تا متن کامل با درصد دیده شود (داخل HorizontalScrollView)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 36f)
                ).apply {
                    marginStart = dp(context, 3f)
                    marginEnd = dp(context, 3f)
                }
                gravity = Gravity.CENTER
                text = context.getString(opt.nameRes)
                textSize = 11.5f
                maxLines = 1
                setPadding(dp(context, 12f), 0, dp(context, 12f), 0)
                setOnClickListener {
                    if (opt.id != currentBgId) {
                        currentBgId = opt.id
                        refreshBgChips()
                        commitNow()
                    }
                }
            }
            bgViews.add(tv)
            bgChipsContainer.addView(tv)
        }

        fun syncControls() {
            updatingUiProgrammatically = true
            seekSubSize.progress = (currentSize - 20).coerceIn(0, 80)
            seekSubPos.progress = currentPos.coerceIn(0, 100)
            seekSubBorder.progress = (currentBorder * 10).toInt().coerceIn(0, 60)
            switchSubBold.isChecked = currentBold
            refreshColorChips()
            refreshBgChips()
            updatingUiProgrammatically = false
        }

        SubtitleNormalizer.setPreviewMode(true)
        updateLabels()
        syncControls()

        // اسلایدرها + دکمه‌های کمکی - و +
        seekSubSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingUiProgrammatically) return
                currentSize = 20 + progress
                updateLabels()
                schedulePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { commitNow() }
        })
        btnSizeMinus.setOnClickListener {
            currentSize = (currentSize - 2).coerceIn(20, 100)
            syncControls(); updateLabels(); commitNow()
        }
        btnSizePlus.setOnClickListener {
            currentSize = (currentSize + 2).coerceIn(20, 100)
            syncControls(); updateLabels(); commitNow()
        }

        seekSubPos.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingUiProgrammatically) return
                currentPos = progress
                updateLabels()
                schedulePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { commitNow() }
        })
        btnPosMinus.setOnClickListener {
            currentPos = (currentPos - 2).coerceIn(0, 100)
            syncControls(); updateLabels(); commitNow()
        }
        btnPosPlus.setOnClickListener {
            currentPos = (currentPos + 2).coerceIn(0, 100)
            syncControls(); updateLabels(); commitNow()
        }

        seekSubBorder.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingUiProgrammatically) return
                currentBorder = progress / 10.0
                updateLabels()
                schedulePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) { commitNow() }
        })
        btnBorderMinus.setOnClickListener {
            currentBorder = ((currentBorder * 10).toInt() - 5).coerceIn(0, 60) / 10.0
            syncControls(); updateLabels(); commitNow()
        }
        btnBorderPlus.setOnClickListener {
            currentBorder = ((currentBorder * 10).toInt() + 5).coerceIn(0, 60) / 10.0
            syncControls(); updateLabels(); commitNow()
        }

        switchSubBold.setOnCheckedChangeListener { _, isChecked ->
            if (updatingUiProgrammatically || isChecked == currentBold) return@setOnCheckedChangeListener
            currentBold = isChecked
            commitNow()
        }

        val alertDialog = AlertDialog.Builder(context)
            .setTitle(R.string.subtitle_style_title)
            .setView(view)
            .setPositiveButton(R.string.dialog_ok) { dialog, _ -> dialog.dismiss() }
            .setNeutralButton(R.string.dialog_reset, null) // جلوگیری از بسته شدن خودکار دیالوگ هنگام ریست
            .setOnDismissListener {
                handler.removeCallbacks(previewRunnable)
                previewQueued = false
                val finalStyle = snapshot()
                saveStyle(prefs, finalStyle)
                requestApply(finalStyle)
                SubtitleNormalizer.setPreviewMode(false)
                onDismiss()
            }
            .create()

        showImmersiveDialog(context, alertDialog, maxHeightRatio = 0.90)

        // بازنشانی درجا بدون بستن دیالوگ
        alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            currentSize = DEFAULT_SIZE
            currentPos = DEFAULT_POS
            currentBorder = DEFAULT_BORDER
            currentBold = DEFAULT_BOLD
            currentColor = DEFAULT_COLOR
            currentBgId = DEFAULT_BG_COLOR
            syncControls()
            updateLabels()
            commitNow()
        }

        scrollSubStyle.post { scrollSubStyle.scrollTo(0, 0) }
    }
}
