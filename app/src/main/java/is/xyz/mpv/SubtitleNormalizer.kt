package `is`.xyz.mpv

import android.content.Context
import android.net.Uri
import android.util.Log
import `is`.xyz.mpv.MPVLib.MpvEvent
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

object SubtitleNormalizer : MPVLib.EventObserver {

    private const val TAG = "SubtitleNormalizer"
    private val PUNCT_SET = setOf('.', '!', '?', '؟', '،', ':', ';', '؛')

    private var lastRawSub: String = ""
    private var previewMode: Boolean = false

    // مقادیر استایل که از SubtitleStyleManager دریافت می‌شوند
    @Volatile private var styleFontSize: Int = 55
    @Volatile private var styleSubPos: Int = 100
    @Volatile private var styleBorderSize: Double = 3.0
    @Volatile private var styleBold: Boolean = false
    @Volatile private var styleColorHex: String = "#FFFFFF"
    @Volatile private var styleBgId: String = "transparent"
    @Volatile private var styleAssOverride: Boolean = true

    fun updateStyleConfig(
        size: Int,
        pos: Int,
        border: Double,
        bold: Boolean,
        colorHex: String,
        bgId: String,
        assOverride: Boolean,
        refreshNow: Boolean = true
    ) {
        styleFontSize = size.coerceIn(20, 100)
        styleSubPos = pos.coerceIn(0, 100)
        styleBorderSize = border.coerceIn(0.0, 6.0)
        styleBold = bold
        styleColorHex = colorHex
        styleBgId = bgId
        styleAssOverride = assOverride

        if (refreshNow) {
            refreshCurrentSubtitle()
        }
    }

    fun setPreviewMode(enabled: Boolean) {
        previewMode = enabled
        refreshCurrentSubtitle()
    }

    fun refreshCurrentSubtitle() {
        // عمداً از حافظه داخلی (lastRawSub) استفاده می‌شود تا ترد فراخواننده
        // (مثلاً UI در دیالوگ شخصی‌سازی) با فراخوانی مسدودکننده mpv قفل نشود؛
        // lastRawSub با هر تغییر sub-text به‌روز نگه داشته می‌شود.
        renderSubtitleInternal(lastRawSub)
    }

    fun attachToMpv() {
        MPVLib.removeObserver(this)
        MPVLib.addObserver(this)
    }

    fun detachFromMpv() {
        MPVLib.removeObserver(this)
        lastRawSub = ""
        previewMode = false
    }

    fun updateLiveSubtitle(rawSubText: String?) {
        val text = rawSubText ?: ""
        if (text == lastRawSub && !previewMode) return
        lastRawSub = text
        renderSubtitleInternal(text)
    }

    private fun renderSubtitleInternal(rawText: String) {
        val effectiveText = if (rawText.isBlank() && previewMode) {
            "نمونه متن زیرنویس فارسی - ۱۲۳!"
        } else {
            rawText
        }

        if (effectiveText.isBlank()) {
            MpvBg.post { MPVLib.command(arrayOf("script-message", "norm-sub", "")) }
            return
        }

        val assEvents = formatForAssOverlay(effectiveText)
        // ارسال به mpv روی ترد پس‌زمینه تا ترد فراخواننده (UI یا event) بلاک نشود؛
        // صف تک‌تردی ترتیب به‌روزرسانی‌ها را حفظ می‌کند.
        MpvBg.post { MPVLib.command(arrayOf("script-message", "norm-sub", assEvents)) }
    }

    /**
     * تبدیل کد رنگ استاندارد #RRGGBB به فرمت &HBBGGRR& در استاندارد ASS
     */
    private fun hexToAssBgr(hex: String): String {
        val clean = hex.trim().removePrefix("#")
        if (clean.length == 6) {
            val r = clean.substring(0, 2)
            val g = clean.substring(2, 4)
            val b = clean.substring(4, 6)
            return "&H${b}${g}${r}&"
        }
        return "&HFFFFFF&"
    }

    private fun formatForAssOverlay(rawText: String): String {
        val hasExplicitTopTag = rawText.contains("\\an8") || rawText.contains("\\a6")
        val lines = rawText.split(Regex("\\r?\\n"))
        val normalizedLines = mutableListOf<String>()
        val measureLines = mutableListOf<String>()

        for (rawLine in lines) {
            val cleanLine = rawLine
                .replace(Regex("\\{[^}]*\\}"), "")
                .replace(Regex("<[^>]+>"), "")
                .trim()

            if (cleanLine.isNotEmpty()) {
                val norm = normalizeSubtitleLine(cleanLine)
                // نسخه بدون اسکیپ برای اندازه‌گیری عرض واقعی متن
                measureLines.add(norm)
                normalizedLines.add(
                    norm.replace("\\", "\\\\")
                        .replace("{", "\\{")
                        .replace("}", "\\}")
                )
            }
        }

        if (normalizedLines.isEmpty()) return ""

        val joinedText = normalizedLines.joinToString("\\N")

        // محاسبهٔ موقعیت عمودی در رزولوشن 1280x720 بر اساس درصد (0 = بالا، 100 = پایین)
        val isTopAligned: Boolean
        val yPos: Int
        if (hasExplicitTopTag && !styleAssOverride) {
            isTopAligned = true
            yPos = 50
        } else {
            yPos = (36 + (styleSubPos / 100.0) * (720 - 72)).toInt().coerceIn(36, 684)
            isTopAligned = styleSubPos < 15
        }
        val alignAndPos = (if (isTopAligned) "\\an8" else "\\an2") + "\\pos(640,$yPos)"

        // رنگ متن اصلی
        val primaryColor = hexToAssBgr(styleColorHex)

        // وزن فونت (در libass مقدار 900 باعث اعمال قطعی Synthetic Bold روی فونت می‌شود)
        val boldTag = if (styleBold) "\\b900" else "\\b0"

        // حاشیه و سایه متن؛ کادر جداگانه libass برای هر خط غیرفعال است
        // (استایل OSD همیشه outline-and-shadow) و کادر یکدست پایین‌تر رسم می‌شود
        val bordStr = String.format(Locale.US, "%.1f", styleBorderSize)
        val shadVal = if (styleBorderSize > 0.0) (styleBorderSize * 0.4).coerceIn(0.4, 2.5) else 0.0
        val shadStr = String.format(Locale.US, "%.1f", shadVal)
        val textTags = "\\bord$bordStr\\shad$shadStr\\3c&H000000&\\3a&H00&\\4c&H000000&\\4a&H50&"

        val stylePrefix = "{$alignAndPos\\fn${FontInstaller.FONT_FAMILY}\\fs$styleFontSize$boldTag$textTags\\1c$primaryColor\\1a&H00&\\q0}"
        val textEvent = stylePrefix + joinedText

        // کادر پس‌زمینه یکدست: یک مستطیل واحد پشت کل بلوک چندخطی
        // (به‌جای کادر جداگانه libass برای هر خط که روی هم می‌افتادند و ناهماهنگ بودند)
        val bgAlpha: String? = when (styleBgId) {
            "quarter_25" -> "C0"
            "semi_50" -> "80"
            "dark_75" -> "40"
            "solid_100" -> "00"
            else -> null
        }
        if (bgAlpha == null) return textEvent

        val boxPad = styleBorderSize.coerceAtLeast(3.5)
        val padH = (boxPad * 2.0).toInt().coerceAtLeast(6)
        val padV = boxPad.toInt().coerceAtLeast(4)

        val paint = android.graphics.Paint().apply {
            typeface = FontInstaller.subtitleTypeface
            textSize = styleFontSize.toFloat()
        }
        var maxW = 0f
        for (ml in measureLines) {
            val m = ml.replace("\u200F", "").replace("\u202B", "").replace("\u202C", "")
            if (paint.measureText(m) > maxW) maxW = paint.measureText(m)
        }
        val fm = paint.fontMetrics
        val lineH = (fm.descent - fm.top).coerceAtLeast(styleFontSize * 1.2f)
        val blockH = lineH * measureLines.size

        val x0 = (640 - maxW / 2 - padH).toInt().coerceIn(8, 1272)
        val x1 = (640 + maxW / 2 + padH).toInt().coerceIn(8, 1272)
        val y0: Int
        val y1: Int
        if (isTopAligned) {
            y0 = (yPos - padV).coerceIn(4, 716)
            y1 = (yPos + blockH + padV).toInt().coerceIn(4, 716)
        } else {
            y1 = (yPos + padV).coerceIn(4, 716)
            y0 = (yPos - blockH - padV).toInt().coerceIn(4, 716)
        }

        val rectEvent =
            "{\\an7\\pos(0,0)\\p1\\1c&H000000&\\1a&H$bgAlpha&}m $x0 $y0 l $x1 $y0 l $x1 $y1 l $x0 $y1{\\p0}"
        // مستطیل اول (پشت) و سپس متن، هر کدام یک ایونت جدا در همان اورلی
        return rectEvent + "\n" + textEvent
    }

    // --- متدهای مربوط به پردازش فایل‌های زیرنویس جداگانه (External SRT) ---

    fun processSubtitlePath(context: Context, pathOrUri: String): String {
        try {
            val rawBytes: ByteArray? = if (pathOrUri.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(pathOrUri))?.use { it.readBytes() }
            } else {
                val f = File(pathOrUri)
                if (f.exists() && f.isFile) f.readBytes() else null
            }

            if (rawBytes == null || rawBytes.isEmpty()) return pathOrUri

            val content = readSubtitleText(rawBytes)
            if (!content.contains("-->")) return pathOrUri

            val fixedContent = normalizeSrt(content)
            val cacheFile = File(context.cacheDir, "norm_sub_${System.currentTimeMillis()}.srt")
            cacheFile.writeText(fixedContent, Charsets.UTF_8)
            return cacheFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error processing subtitle", e)
            return pathOrUri
        }
    }

    private fun readSubtitleText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        return try {
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.onMalformedInput(CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            try {
                String(bytes, java.nio.charset.Charset.forName("windows-1256"))
            } catch (e2: Exception) {
                String(bytes, Charsets.ISO_8859_1)
            }
        }
    }

    fun normalizeSrt(content: String): String {
        var text = content
        if (text.startsWith("\uFEFF")) text = text.substring(1)

        val blocks = text.split(Regex("\\r?\\n\\r?\\n"))
        val out = StringBuilder()

        for ((bi, block) in blocks.withIndex()) {
            if (bi > 0) out.append("\n\n")
            val lines = block.split(Regex("\\r?\\n"))
            if (lines.isEmpty()) continue

            if (lines.size >= 2 && lines[0].trim().matches(Regex("\\d+")) && lines[1].contains("-->")) {
                out.append(lines[0]).append("\n")
                out.append(lines[1])
                for (i in 2 until lines.size) {
                    out.append("\n")
                    out.append(normalizeSrtTextLine(lines[i]))
                }
            } else {
                out.append(block.replace("\r\n", "\n").replace("\r", "\n"))
            }
        }
        return out.toString()
    }

    private fun normalizeSrtTextLine(line: String): String {
        if (line.isEmpty()) return line

        var startTags = ""
        var endTags = ""
        var core = line

        val startMatch = Regex("^(\\s*(<[^>]+>\\s*)+)").find(core)
        if (startMatch != null) {
            startTags = startMatch.value
            core = core.substring(startTags.length)
        }

        val endMatch = Regex("((\\s*<[^>]+>)+\\s*)$").find(core)
        if (endMatch != null) {
            endTags = endMatch.value
            core = core.substring(0, core.length - endTags.length)
        }

        if (core.isBlank()) return line

        val normalized = normalizeSubtitleLine(core)
            .removePrefix("\u200F\u202B")
            .removeSuffix("\u202C\u200F")
        return "\u200F\u202B" + startTags + normalized + endTags + "\u202C\u200F"
    }

    fun normalizeSubtitleLine(input: String?): String {
        if (input.isNullOrEmpty()) return input ?: ""
        if (input.startsWith("\u200F\u202B")) return input

        var line = input
            .replace("\u200E", "")
            .replace("\u200F", "")
            .replace("\u202A", "")
            .replace("\u202B", "")
            .replace("\u202C", "")
            .trim()
        if (line.isEmpty()) return line

        var hasRtl = false
        for (c in line) {
            val dir = Character.getDirectionality(c)
            if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                hasRtl = true
                break
            }
        }
        if (!hasRtl) return line

        // ۱. حل مشکل خط تیره دیالوگ در انتها یا ابتدای خطوط
        var isDialogue = false
        val trailingDashRegex = Regex("[\\s\\u00A0\\u200C]*[-–—]+[\\s\\u00A0\\u200C]*$")
        if (trailingDashRegex.containsMatchIn(line)) {
            isDialogue = true
            line = line.replace(trailingDashRegex, "").trimEnd()
        } else if (line.startsWith("-") || line.startsWith("–") || line.startsWith("—")) {
            isDialogue = true
            line = line.substring(1).trimStart()
        }

        // ۲. حل مشکل سه‌نقطه در ابتدای خطوط
        if (line.startsWith("...") || line.startsWith("…")) {
            val prefix = if (line.startsWith("...")) "..." else "…"
            line = line.substring(prefix.length).trimStart() + prefix
        }

        // ۳. حل مشکل ترکیب نقطه با گیومه مثل .«من هولوگرام نیستم»
        if (Regex("^[.!?؟،:;؛]+«").containsMatchIn(line)) {
            val pMatch = Regex("^[.!?؟،:;؛]+").find(line)
            if (pMatch != null) {
                val punct = pMatch.value
                line = line.substring(punct.length) + punct
            }
        }

        // ۴. حل مشکل گیومه‌های نامتوازن در شکست خطوط
        if (line.endsWith("»") && !line.contains("«")) {
            line = "«" + line.substring(0, line.length - 1)
        } else if (line.startsWith("«") && !line.contains("»")) {
            line = line.substring(1) + "»"
        }

        // ۴-ب. جابه‌جایی علائم نگارشی چسبیده به داخل گیومه‌ها به انتهای خط
        // («،متن» یا «متن،» -> «متن»،)
        if (line.startsWith("«") && line.length > 2) {
            var qEnd = 1
            while (qEnd < line.length && line[qEnd] in PUNCT_SET) qEnd++
            if (qEnd > 1) {
                val innerPunct = line.substring(1, qEnd)
                val rest = line.substring(qEnd)
                if (rest.isNotEmpty() && rest.any { it !in PUNCT_SET && it != '«' && it != '»' }) {
                    line = "«" + rest + innerPunct
                }
            }
        }
        if (line.endsWith("»") && line.length > 2) {
            var qStart = line.length - 2
            while (qStart >= 0 && line[qStart] in PUNCT_SET) qStart--
            if (qStart < line.length - 2) {
                val innerPunct = line.substring(qStart + 1, line.length - 1)
                val rest = line.substring(0, qStart + 1)
                if (rest.isNotEmpty() && rest.any { it !in PUNCT_SET && it != '«' && it != '»' }) {
                    line = rest + "»" + innerPunct
                }
            }
        }

        // ۵. انتقال سایر علائم نگارشی ابتدایی به انتها
        line = moveLeadingPunctToEnd(line)

        // ۶. برگرداندن خط تیره دیالوگ به ابتدای صحیح جمله
        if (isDialogue) {
            line = "- $line"
        }

        return "\u200F\u202B$line\u202C\u200F"
    }

    private fun moveLeadingPunctToEnd(s: String): String {
        if (s.isEmpty()) return s

        var pCount = 0
        while (pCount < s.length && s[pCount] in PUNCT_SET) {
            pCount++
        }
        if (pCount == 0) return s

        val punct = s.substring(0, pCount)
        val rest = s.substring(pCount).trimStart()
        return "$rest$punct"
    }

    // --- EventObserver Callbacks ---

    override fun eventProperty(property: String) {
        if (property == "sub-text" || property == "sid") {
            val current = MPVLib.getPropertyString("sub-text") ?: ""
            updateLiveSubtitle(current)
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (property == "sub-text") {
            updateLiveSubtitle(value)
        } else if (property == "sid") {
            val current = MPVLib.getPropertyString("sub-text") ?: ""
            updateLiveSubtitle(current)
        }
    }

    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: Boolean) {}
    override fun eventProperty(property: String, value: Double) {}

    override fun event(eventId: Int) {
        when (eventId) {
            MpvEvent.MPV_EVENT_START_FILE,
            MpvEvent.MPV_EVENT_END_FILE -> {
                updateLiveSubtitle("")
            }
            MpvEvent.MPV_EVENT_SEEK,
            MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                val current = MPVLib.getPropertyString("sub-text") ?: ""
                updateLiveSubtitle(current)
            }
        }
    }
}
