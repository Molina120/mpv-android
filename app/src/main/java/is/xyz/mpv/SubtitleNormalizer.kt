package `is`.xyz.mpv

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object SubtitleNormalizer {

    private val PUNCT_CHARS = setOf('.', '!', '?', '؟', '،', ':', ';', '؛')

    /**
     * بررسی، نرمالایز و ذخیره فایل برای تحویل مستقیم به mpv
     */
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
            // اگر ساختار زمانی زیرنویس SRT را ندارد، دست نزن
            if (!content.contains("-->")) return pathOrUri

            val fixedContent = normalizeSrt(content)
            val cacheFile = File(context.cacheDir, "norm_sub_${System.currentTimeMillis()}.srt")
            cacheFile.writeText(fixedContent, Charsets.UTF_8)
            return cacheFile.absolutePath
        } catch (e: Exception) {
            Log.e("SubtitleNormalizer", "Error processing subtitle", e)
            return pathOrUri
        }
    }

    /**
     * خواندن متن با شناسایی خودکار UTF-8 ،UTF-16 و Windows-1256 (فارسی رایج)
     */
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
        return "\u200F\u202B" + startTags + normalized.removePrefix("\u200F\u202B") + endTags
    }

    fun normalizeSubtitleLine(input: String?): String {
        if (input.isNullOrEmpty()) return input ?: ""
        if (input.startsWith("\u200F\u202B")) return input

        var line = input.trim()
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

        // ۱. بررسی و جداسازی خط تیره محاوره‌ای در انتهای متن
        var isDialogue = false
        if (line.endsWith(" -")) {
            isDialogue = true
            line = line.substring(0, line.length - 2).trimEnd()
        } else if (line.endsWith("-") && line.length > 1 && line[line.length - 2] != '-') {
            isDialogue = true
            line = line.substring(0, line.length - 1).trimEnd()
        }

        // ۲. اصلاح گیومه‌های نامتوازن شکسته شده بین دو خط
        if (line.endsWith("»") && !line.startsWith("«")) {
            line = "«" + line.substring(0, line.length - 1)
        } else if (line.startsWith("«") && !line.endsWith("»")) {
            line = line.substring(1) + "»"
        }

        // ۳. انتقال علائم ابتدایی خط به انتها
        line = moveLeadingPunctToEnd(line)

        // ۴. بازگرداندن خط تیره محاوره‌ای به ابتدای صحیح جمله
        if (isDialogue && !line.startsWith("- ")) {
            line = "- $line"
        }

        return "\u200F\u202B$line"
    }

    private fun moveLeadingPunctToEnd(s: String): String {
        if (s.isEmpty()) return s

        val first = s[0]
        if (first == '.' && s.length > 1 && s[1] == '.') return s // سه‌نقطه دست نخورد
        if (first == '…') return s

        var pCount = 0
        while (pCount < s.length && s[pCount] in PUNCT_CHARS) {
            pCount++
        }
        if (pCount == 0) return s

        val punct = s.substring(0, pCount)
        val rest = s.substring(pCount).trimStart()
        return "$rest$punct"
    }
}
