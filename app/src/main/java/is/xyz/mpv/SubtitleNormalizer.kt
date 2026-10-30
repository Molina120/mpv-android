package `is`.xyz.mpv

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

object SubtitleNormalizer {

    private val BIDI_CONTROLS = setOf(
        '\u200E', '\u200F', '\u202A', '\u202B', '\u202C',
        '\u202D', '\u202E', '\u2066', '\u2067', '\u2068',
        '\u2069', '\uFEFF'
    )

    private fun isBidiControl(c: Char): Boolean = c in BIDI_CONTROLS

    private fun contentStart(s: String): Int {
        var i = 0
        while (i < s.length && isBidiControl(s[i])) i++
        return i
    }

    fun normalizeSubtitleLine(input: String?): String {
        if (input.isNullOrEmpty()) return input ?: ""

        if (input.startsWith("\u200F\u202B")) return input

        var line = input
        var len = line.length

        var hasRtl = false
        for (c in line) {
            val dir = Character.getDirectionality(c)
            if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                hasRtl = true
                break
            }
        }
        if (!hasRtl) return line

        val cs = contentStart(line)

        if (line.endsWith("»")) {
            val startsWithOpenQuote = cs < len && line[cs] == '«'
            if (!startsWithOpenQuote) {
                line = line.substring(0, len - 1)
                len = line.length
                line = moveLeadingPunctToEnd(line, len)
                len = line.length
                val prefix = line.substring(0, contentStart(line))
                val rest = line.substring(contentStart(line))
                line = prefix + "«" + rest
                len = line.length
                line = fixTrailingDash(line, len)
                return "\u200F\u202B" + line
            }
        }

        if (cs < len && line[cs] == '«' && !line.endsWith("»")) {
            val prefix = line.substring(0, cs)
            val rest = line.substring(cs + 1)
            line = prefix + rest + "»"
            len = line.length
            line = moveLeadingPunctToEnd(line, len)
            len = line.length
            line = fixTrailingDash(line, len)
            return "\u200F\u202B" + line
        }

        line = moveLeadingPunctToEnd(line, len)
        len = line.length
        line = fixTrailingDash(line, len)

        return "\u200F\u202B" + line
    }

    private fun moveLeadingPunctToEnd(line: String, len: Int): String {
        if (len == 0) return line
        val cs = contentStart(line)
        if (cs >= len) return line

        val first = line[cs]
        if (first == '.') {
            if (cs + 1 < len && line[cs + 1] == '.') return line
        } else if (first == '…') {
            return line
        }

        var punctCount = 0
        while (cs + punctCount < len) {
            val c = line[cs + punctCount]
            if (c == '.' || c == '!' || c == '?' || c == '؟' || c == '،' || c == ':') {
                punctCount++
            } else {
                break
            }
        }
        if (punctCount == 0) return line

        val prefix = line.substring(0, cs)
        val punct = line.substring(cs, cs + punctCount)
        val rest = line.substring(cs + punctCount)
        return prefix + rest + punct
    }

    private fun fixTrailingDash(line: String, len: Int): String {
        if (len < 2) return line
        if (line[len - 2] != ' ' || line[len - 1] != '-') return line

        val cs = contentStart(line)
        if (cs < len && line[cs] == '-') {
            if (cs + 1 < len && line[cs + 1] == ' ') {
                return line.substring(0, len - 2)
            }
        }

        val body = line.substring(0, len - 2)
        return "- $body"
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

            if (lines.size >= 2 && lines[0].trim().matches(Regex("\\d+")) &&
                lines[1].contains("-->")) {
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

        val startTagRegex = Regex("^(\\s*(<[^>]+>\\s*)+)")
        val startMatch = startTagRegex.find(core)
        if (startMatch != null) {
            startTags = startMatch.value
            core = core.substring(startTags.length)
        }

        val endTagRegex = Regex("((\\s*<[^>]+>)+\\s*)$")
        val endMatch = endTagRegex.find(core)
        if (endMatch != null) {
            endTags = endMatch.value
            core = core.substring(0, core.length - endTags.length)
        }

        if (core.isBlank()) return line

        val normalized = normalizeSubtitleLine(core)
        // نشانگر راست‌به‌چپ (RLM + RLE) حتماً در ابتدای خط حفظ شود
        return "\u200F\u202B" + startTags + normalized.removePrefix("\u200F\u202B") + endTags
    }

    /**
     * بررسی، نرمالایز و ذخیره فایل موقت برای استفاده مستقیم در mpv
     */
    fun processSubtitlePath(context: Context, pathOrUri: String): String {
        try {
            var rawContent: String? = null
            var fileName = "sub.srt"

            if (pathOrUri.startsWith("content://")) {
                val uri = Uri.parse(pathOrUri)
                rawContent = context.contentResolver.openInputStream(uri)?.use {
                    it.bufferedReader().readText()
                }
                fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "sub.srt"
            } else {
                val file = File(pathOrUri)
                if (file.exists() && file.isFile) {
                    rawContent = file.readText(Charsets.UTF_8)
                    fileName = file.name
                }
            }

            if (rawContent.isNullOrEmpty() || !fileName.endsWith(".srt", ignoreCase = true)) {
                return pathOrUri
            }

            val fixedContent = normalizeSrt(rawContent)
            val cacheFile = File(context.cacheDir, "norm_${System.currentTimeMillis()}_$fileName")
            cacheFile.writeText(fixedContent, Charsets.UTF_8)
            return cacheFile.absolutePath
        } catch (e: Exception) {
            Log.e("SubtitleNormalizer", "Failed to normalize subtitle", e)
            return pathOrUri
        }
    }
}
