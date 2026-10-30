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

    // ارتفاع خط فاصله (بر حسب واحد ASS) بین کادرهای دو خط. با ۸ تقریباً بدون فاصله و بدون هم‌پوشانی،
    // با ۱۴ حدود ۶ پیکسل فاصله. اگر هنوز کادرها روی هم رفتند عدد را بیشتر کنید.
    private const val BOX_GAP_FS = 14
    private val PUNCT_SET = setOf('.', '!', '?', '؟', '،', ':', ';', '؛')

    private var lastRawSub: String = ""
    private var previewMode: Boolean = false

    /**
     * حالت عبور ASS: وقتی ترک زیرنویس فعلی ASS/SSA است، شخصی‌سازی و نرمالایز
     * هیچ تأثیری ندارد و mpv آن را دقیقاً با استایل خودش رندر می‌کند.
     */
    @Volatile
    var assPassthrough: Boolean = false
        private set

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
        // در حالت ASS، اورلی خاموش است و mpv با استایل اصلی فایل رندر می‌کند
        if (assPassthrough) {
            if (text != lastRawSub) {
                lastRawSub = text
                MpvBg.postLatest("norm-sub") { MPVLib.command(arrayOf("script-message", "norm-sub", "")) }
            }
            return
        }
        if (text == lastRawSub && !previewMode) return
        lastRawSub = text
        renderSubtitleInternal(text)
    }

    /**
     * بررسی ASS بودن ترک زیرنویس فعلی و به‌روزرسانی حالت رندر.
     * در حالت ASS: اورلی خاموش و sub-ass-override=no تا استایل اصلی فایل اعمال شود.
     * در غیر این صورت: sub-ass-override=strip و رندر با اورلی شخصی‌سازی‌شده.
     * هنگام تغییر ترک (sid) و لود فایل صدا زده می‌شود.
     */
    fun updateAssMode() {
        val isAss = isCurrentSubAss()
        if (isAss == assPassthrough) return
        assPassthrough = isAss
        Log.d(TAG, "ASS passthrough mode: $isAss")
        MpvBg.post {
            try {
                MPVLib.setPropertyString("sub-ass-override", if (isAss) "no" else "strip")
            } catch (e: Exception) {
                Log.w(TAG, "sub-ass-override switch failed", e)
            }
        }
        if (isAss) {
            lastRawSub = ""
            MpvBg.postLatest("norm-sub") { MPVLib.command(arrayOf("script-message", "norm-sub", "")) }
        } else {
            refreshCurrentSubtitle()
        }
    }

    private fun isCurrentSubAss(): Boolean {
        try {
            val n = MPVLib.getPropertyInt("track-list/count") ?: return false
            for (i in 0 until n) {
                if (MPVLib.getPropertyString("track-list/$i/type") != "sub") continue
                if (MPVLib.getPropertyBoolean("track-list/$i/selected") != true) continue
                val codec = MPVLib.getPropertyString("track-list/$i/codec") ?: ""
                if (codec.equals("ass", ignoreCase = true) || codec.equals("ssa", ignoreCase = true))
                    return true
                val extFile = MPVLib.getPropertyString("track-list/$i/external-filename") ?: ""
                if (extFile.endsWith(".ass", ignoreCase = true) || extFile.endsWith(".ssa", ignoreCase = true))
                    return true
                return false
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * جبران نبود mirror در bidi موتور libass: در متن راست‌به‌چپ، جفت‌کاراکترهای
     * آینه‌ای را از قبل جابه‌جا می‌کنیم تا خروجی نهایی درست دیده شود.
     * (تست‌شده با libass واقعی: بدون این کار پرانتزها جابه‌جا نمایش داده می‌شوند)
     */
    private fun mirrorForLibass(s: String): String {
        var hasRtl = false
        for (c in s) {
            val d = Character.getDirectionality(c)
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                hasRtl = true
                break
            }
        }
        if (!hasRtl) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    '(' -> ')'
                    ')' -> '('
                    '[' -> ']'
                    ']' -> '['
                    '{' -> '}'
                    '}' -> '{'
                    '«' -> '»'
                    '»' -> '«'
                    '‹' -> '›'
                    '›' -> '‹'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    private fun renderSubtitleInternal(rawText: String) {
        // شخصی‌سازی روی ASS اثر ندارد
        if (assPassthrough) {
            MpvBg.postLatest("norm-sub") { MPVLib.command(arrayOf("script-message", "norm-sub", "")) }
            return
        }
        val effectiveText = if (rawText.isBlank() && previewMode) {
            "نمونه متن زیرنویس فارسی - ۱۲۳!"
        } else {
            rawText
        }

        if (effectiveText.isBlank()) {
            MpvBg.postLatest("norm-sub") { MPVLib.command(arrayOf("script-message", "norm-sub", "")) }
            return
        }

        val assEvents = formatForAssOverlay(effectiveText)
        // ارسال به mpv روی ترد پس‌زمینه تا ترد فراخواننده (UI یا event) بلاک نشود؛
        // با کلید norm-sub فقط آخرین رندر اجرا می‌شود و صف انباشته نمی‌شود.
        MpvBg.postLatest("norm-sub") { MPVLib.command(arrayOf("script-message", "norm-sub", assEvents)) }
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

    private fun boxAlphaFor(bgId: String): String? = when (bgId) {
        "quarter_25" -> "BF"
        "semi_50" -> "80"
        "dark_75" -> "40"
        "solid_100" -> "00"
        else -> null // transparent: outline + shadow, no box
    }

    /**
     * در حالت کادر، libass کادر هر خط را با پدینگ \bord می‌کشد و خطوط بدون فاصله کنار هم می‌آیند،
     * پس کادر خط دوم روی خط اول می‌افتد. بین خطوط یک خط فاصلهٔ کاملاً شفاف می‌گذاریم.
     */
    private fun joinLines(lines: List<String>, bgId: String): String {
        val boxAlpha = boxAlphaFor(bgId)
        if (boxAlpha == null || lines.size < 2) return lines.joinToString("\\N")

        val sb = StringBuilder()
        for ((i, line) in lines.withIndex()) {
            if (i > 0) {
                // خط فاصله: متن و کادرش شفاف‌اند و فقط ارتفاع فاصله می‌سازند
                sb.append("\\N{\\fs$BOX_GAP_FS\\1a&HFF&\\3a&HFF&\\4a&HFF&} \\N")
                // ویژگی‌های خط اصلی را برای خط بعدی برمی‌گردانیم
                sb.append("{\\fs$styleFontSize\\1a&H00&\\3a&H$boxAlpha&\\4a&H$boxAlpha&}")
            }
            sb.append(line)
        }
        return sb.toString()
    }

    private fun formatForAssOverlay(rawText: String): String {
        val hasExplicitTopTag = rawText.contains("\\an8") || rawText.contains("\\a6")
        val lines = rawText.split(Regex("\\r?\\n"))
        val normalizedLines = mutableListOf<String>()

        for (rawLine in lines) {
            val cleanLine = rawLine
                .replace(Regex("\\{[^}]*\\}"), "")
                .replace(Regex("<[^>]+>"), "")
                .trim()

            if (cleanLine.isNotEmpty()) {
                val norm = mirrorForLibass(normalizeSubtitleLine(cleanLine))
                    .replace("\\", "\\\\")
                    .replace("{", "\\{")
                    .replace("}", "\\}")
                normalizedLines.add(norm)
            }
        }

        if (normalizedLines.isEmpty()) return ""

        val joinedText = joinLines(normalizedLines, styleBgId)

        // محاسبهٔ موقعیت عمودی در رزولوشن 1280x720 بر اساس درصد (0 = بالا، 100 = پایین)
        val alignAndPos = if (hasExplicitTopTag && !styleAssOverride) {
            "\\an8\\pos(640,50)"
        } else {
            val yPos = (36 + (styleSubPos / 100.0) * (720 - 72)).toInt().coerceIn(36, 684)
            val alignment = if (styleSubPos < 15) "\\an8" else "\\an2"
            "$alignment\\pos(640,$yPos)"
        }

        // رنگ متن اصلی
        val primaryColor = hexToAssBgr(styleColorHex)

        // وزن فونت (در libass مقدار 900 باعث اعمال قطعی Synthetic Bold روی فونت می‌شود)
        val boldTag = if (styleBold) "\\b900" else "\\b0"

        // تنظیم حاشیه، سایه و کادر پس‌زمینه
        val borderAndBgTags = when (styleBgId) {
            "quarter_25" -> {
                val boxPad = styleBorderSize.coerceAtLeast(3.5)
                val bordStr = String.format(Locale.US, "%.1f", boxPad)
                "\\bord$bordStr\\shad0\\3c&H000000&\\3a&HBF&\\4c&H000000&\\4a&HBF&"
            }
            "semi_50" -> {
                val boxPad = styleBorderSize.coerceAtLeast(3.5)
                val bordStr = String.format(Locale.US, "%.1f", boxPad)
                "\\bord$bordStr\\shad0\\3c&H000000&\\3a&H80&\\4c&H000000&\\4a&H80&"
            }
            "dark_75" -> {
                val boxPad = styleBorderSize.coerceAtLeast(3.5)
                val bordStr = String.format(Locale.US, "%.1f", boxPad)
                "\\bord$bordStr\\shad0\\3c&H000000&\\3a&H40&\\4c&H000000&\\4a&H40&"
            }
            "solid_100" -> {
                val boxPad = styleBorderSize.coerceAtLeast(3.5)
                val bordStr = String.format(Locale.US, "%.1f", boxPad)
                "\\bord$bordStr\\shad0\\3c&H000000&\\3a&H00&\\4c&H000000&\\4a&H00&"
            }
            else -> {
                // حالت شفاف (بدون کادر، دارای حاشیه و سایه متناسب با اسلایدر کاربر)
                val bordStr = String.format(Locale.US, "%.1f", styleBorderSize)
                val shadVal = if (styleBorderSize > 0.0) (styleBorderSize * 0.4).coerceIn(0.4, 2.5) else 0.0
                val shadStr = String.format(Locale.US, "%.1f", shadVal)
                "\\bord$bordStr\\shad$shadStr\\3c&H000000&\\3a&H00&\\4c&H000000&\\4a&H50&"
            }
        }

        val stylePrefix = "{$alignAndPos\\fn${FontInstaller.FONT_FAMILY}\\fs$styleFontSize$boldTag$borderAndBgTags\\1c$primaryColor\\1a&H00&\\q0}"
        return stylePrefix + joinedText
    }

    // --- متدهای مربوط به پردازش فایل‌های زیرنویس جداگانه (External SRT) ---

    fun processSubtitlePath(context: Context, pathOrUri: String): String {
        // ASS/SSA با استایل اصلی خودش نمایش داده می‌شود؛ هیچ پردازشی روی آن انجام نمی‌شود
        if (pathOrUri.endsWith(".ass", ignoreCase = true) ||
            pathOrUri.endsWith(".ssa", ignoreCase = true)) {
            return pathOrUri
        }
        try {
            val rawBytes: ByteArray? = if (pathOrUri.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(pathOrUri))?.use { it.readBytes() }
            } else {
                val f = File(pathOrUri)
                if (f.exists() && f.isFile) f.readBytes() else null
            }

            if (rawBytes == null || rawBytes.isEmpty()) return pathOrUri

            val content = readSubtitleText(rawBytes)
            // تشخیص ASS از روی محتوا (برای content:// بدون پسوند)
            if (content.trimStart().startsWith("[Script Info]", ignoreCase = true)) return pathOrUri
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
            if (property == "sid") updateAssMode()
            if (!assPassthrough || property == "sid") {
                val current = MPVLib.getPropertyString("sub-text") ?: ""
                updateLiveSubtitle(current)
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (property == "sub-text") {
            updateLiveSubtitle(value)
        } else if (property == "sid") {
            updateAssMode()
            if (!assPassthrough) {
                val current = MPVLib.getPropertyString("sub-text") ?: ""
                updateLiveSubtitle(current)
            }
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
