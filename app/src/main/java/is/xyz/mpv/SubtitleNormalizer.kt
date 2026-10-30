package `is`.xyz.mpv

import android.content.Context
import android.net.Uri
import android.util.Log
import `is`.xyz.mpv.MPVLib.MpvEvent
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object SubtitleNormalizer : MPVLib.EventObserver {

    private const val TAG = "SubtitleNormalizer"
    private val PUNCT_SET = setOf('.', '!', '?', '؟', '،', ':', ';', '؛')

    private var lastRawSub: String? = null

    fun attachToMpv() {
        MPVLib.removeObserver(this)
        MPVLib.addObserver(this)
    }

    fun detachFromMpv() {
        MPVLib.removeObserver(this)
        lastRawSub = null
    }

    /**
     * رندر زیرنویس نرمالیزه‌شده (سافت‌ساب و اکسترنال) در لحظه از طریق موتور libass داخلی mpv
     */
    fun updateLiveSubtitle(rawSubText: String?) {
        val text = rawSubText ?: ""
        if (text == lastRawSub) return
        lastRawSub = text

        if (text.isBlank()) {
            MPVLib.command(arrayOf("script-message", "norm-sub", ""))
            return
        }

        val assEvents = formatForAssOverlay(text)
        MPVLib.command(arrayOf("script-message", "norm-sub", assEvents))
    }

    private fun formatForAssOverlay(rawText: String): String {
        val isTopAligned = rawText.contains("\\an8") || rawText.contains("\\a6")
        val lines = rawText.split(Regex("\\r?\\n"))
        val normalizedLines = mutableListOf<String>()

        for (rawLine in lines) {
            // حذف تگ‌های احتمالی HTML یا ASS از متن خام برای نرمالیزه شدن دقیق علائم
            val cleanLine = rawLine
                .replace(Regex("\\{[^}]*\\}"), "")
                .replace(Regex("<[^>]+>"), "")
                .trim()

            if (cleanLine.isNotEmpty()) {
                val norm = normalizeSubtitleLine(cleanLine)
                    .replace("\\", "\\\\")
                    .replace("{", "\\{")
                    .replace("}", "\\}")
                normalizedLines.add(norm)
            }
        }

        if (normalizedLines.isEmpty()) return ""

        val joinedText = normalizedLines.joinToString("\\N")
        val alignAndPos = if (isTopAligned) {
            "\\an8\\pos(640,55)"
        } else {
            "\\an2\\pos(640,680)"
        }

        // استایل استاندارد libass منطبق با فونت IRANSansX
        val stylePrefix = "{$alignAndPos\\fnIRANSansX\\fs42\\b0\\bord2.2\\shad1.2\\1c&HFFFFFF&\\3c&H000000&\\4c&H000000&\\q0}"
        return stylePrefix + joinedText
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

        // ۵. انتقال سایر علائم نگارشی ابتدایی به انتها
        line = moveLeadingPunctToEnd(line)

        // ۶. برگرداندن خط تیره دیالوگ به ابتدای صحیح جمله
        if (isDialogue) {
            line = "- $line"
        }

        // قفل کردن هر دو سمت خط با کاراکترهای قوی RTL برای جلوگیری از پرش علائم در libass
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
