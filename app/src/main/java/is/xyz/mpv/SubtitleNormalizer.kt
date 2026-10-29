package `is`.xyz.mpv

/**
 * نرمالایز کردن علائم نگارشی زیرنویس‌های فارسی/راست‌به‌چپ.
 * پورت مستقیم از normalizeSubtitleLine نسخه WVC (رسیور + گوشی v8)، شامل:
 * - انتقال علائم نگارشی ابتدایی (. ! ? ؟ ،) به انتهای منطقی خط
 * - انتقال خط تیره محاوره‌ای انتهایی " -" به ابتدای خط به‌صورت "- "
 * - فیکس گیومه‌های نامتوازن « » (نسخه v8)
 * - عدم دست‌کاری خطوط .../… و خطوط纯 لاتین و کنترل‌های bidi
 */
object SubtitleNormalizer {

    private val BIDI_CONTROLS = setOf(
        '\u200E', // LRM
        '\u200F', // RLM
        '\u202A', // LRE
        '\u202B', // RLE
        '\u202C', // PDF
        '\u202D', // LRO
        '\u202E', // RTO
        '\u2066', // LRI
        '\u2067', // RLI
        '\u2068', // FSI
        '\u2069', // PDI
        '\uFEFF'  // ZWNBSP
    )

    private fun isBidiControl(c: Char): Boolean = c in BIDI_CONTROLS

    /** ایندکس اولین کاراکتر محتوایی (رد کردن کنترل‌های bidi ابتدایی) */
    private fun contentStart(s: String): Int {
        var i = 0
        while (i < s.length && isBidiControl(s[i])) i++
        return i
    }

    fun normalizeSubtitleLine(input: String?): String {
        if (input.isNullOrEmpty()) return input ?: ""

        // v8: idempotency — اگر قبلاً پردازش شده، دست نزن
        if (input.startsWith("\u200F\u202B")) return input

        var line = input
        var len = line.length

        // بررسی وجود کاراکتر راست‌به‌چپ
        var hasRtl = false
        for (c in line) {
            val dir = Character.getDirectionality(c)
            if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                hasRtl = true
                break
            }
        }
        if (!hasRtl) return line // خط纯 لاتین: دست نزن

        val cs = contentStart(line)

        // v8: فیکس گیومه نامتوازن انتهایی » (مثلاً «،نابغه‌های واقعی»)
        // شرط: تمام شود با » ولی شروع نشود با «
        if (line.endsWith("»")) {
            val startsWithOpenQuote = cs < len && line[cs] == '«'
            if (!startsWithOpenQuote) {
                // » انتهایی را حذف کن
                line = line.substring(0, len - 1)
                len = line.length
                // علائم ابتدایی را به انتها منتقل کن (منطق اصلی)
                line = moveLeadingPunctToEnd(line, len)
                len = line.length
                // « را به ابتدا اضافه کن (بعد از کنترل‌های bidi)
                val prefix = line.substring(0, contentStart(line))
                val rest = line.substring(contentStart(line))
                line = prefix + "«" + rest
                len = line.length
                // ادامه به منطق خط تیره
                line = fixTrailingDash(line, len)
                return "\u200F\u202B" + line
            }
        }

        // v8: فیکس گیومه نامتوازن ابتدایی « (مثلاً «کسایی‌ان...)
        // شرط: شروع شود با « ولی تمام نشود با »
        if (cs < len && line[cs] == '«' && !line.endsWith("»")) {
            val prefix = line.substring(0, cs)
            val rest = line.substring(cs + 1) // حذف « ابتدایی
            line = prefix + rest + "»"
            len = line.length
            // ادامه به منطق اصلی (علائم و خط تیره)
            line = moveLeadingPunctToEnd(line, len)
            len = line.length
            line = fixTrailingDash(line, len)
            return "\u200F\u202B" + line
        }

        // منطق اصلی: انتقال علائم نگارشی ابتدایی به انتها
        line = moveLeadingPunctToEnd(line, len)
        len = line.length

        // منطق خط تیره محاوره‌ای انتهایی
        line = fixTrailingDash(line, len)

        return "\u200F\u202B" + line
    }

    /**
     * انتقال علائم . ! ? ؟ ، از ابتدای خط به انتهای منطقی.
     * خطوطی که با .. یا … شروع می‌شوند دست نمی‌خورند.
     */
    private fun moveLeadingPunctToEnd(line: String, len: Int): String {
        if (len == 0) return line
        val cs = contentStart(line)
        if (cs >= len) return line

        val first = line[cs]
        // اگر با .. یا … شروع شود، نادیده بگیر
        if (first == '.') {
            if (cs + 1 < len && line[cs + 1] == '.') return line
        } else if (first == '…') {
            return line
        }

        // شمارش علائم نگارشی ابتدایی
        var punctCount = 0
        while (cs + punctCount < len) {
            val c = line[cs + punctCount]
            if (c == '.' || c == '!' || c == '?' || c == '؟' || c == '،') {
                punctCount++
            } else {
                break
            }
        }
        if (punctCount == 0) return line

        val prefix = line.substring(0, cs)          // کنترل‌های bidi
        val punct = line.substring(cs, cs + punctCount) // علائم
        val rest = line.substring(cs + punctCount)     // بقیه
        return prefix + rest + punct
    }

    /**
     * اگر خط با " -" تمام شود، آن را به ابتدای خط منتقل کن به‌صورت "- ".
     * اگر هم با "- " شروع شده باشد، فقط " -" انتهایی حذف می‌شود.
     */
    private fun fixTrailingDash(line: String, len: Int): String {
        if (len < 2) return line
        if (line[len - 2] != ' ' || line[len - 1] != '-') return line

        val cs = contentStart(line)
        // بررسی شروع با "- "
        if (cs < len && line[cs] == '-') {
            if (cs + 1 < len && line[cs + 1] == ' ') {
                // هم شروع با "- " هم پایان با " -": فقط انتهایی را حذف کن
                return line.substring(0, len - 2)
            }
        }

        // انتقال " -" انتهایی به ابتدا به‌صورت "- "
        val body = line.substring(0, len - 2)
        return "- $body"
    }

    /**
     * نرمالایز کردن یک فایل SRT کامل.
     * تگ‌های HTML ساده (مثل <i>) حفظ می‌شوند؛ فقط متن نرمالایز می‌شود.
     */
    fun normalizeSrt(content: String): String {
        // حذف BOM اگر وجود دارد
        var text = content
        if (text.startsWith("\uFEFF")) text = text.substring(1)

        // تقسیم به بلاک‌ها (جداکننده: خط خالی)
        val blocks = text.split(Regex("\\r?\\n\\r?\\n"))
        val out = StringBuilder()

        for ((bi, block) in blocks.withIndex()) {
            if (bi > 0) out.append("\n\n")
            val lines = block.split(Regex("\\r?\\n"))
            if (lines.isEmpty()) continue

            // خط اول: شماره، خط دوم: تایم‌استمپ
            // اگر بلاک با شماره شروع نشود یا تایم‌استمپ نداشته باشد، دست نزن
            if (lines.size >= 2 && lines[0].trim().matches(Regex("\\d+")) &&
                lines[1].contains("-->")) {
                out.append(lines[0]).append("\n")
                out.append(lines[1])
                // خطوط متن
                for (i in 2 until lines.size) {
                    out.append("\n")
                    out.append(normalizeSrtTextLine(lines[i]))
                }
            } else {
                // بلاک نامعتبر: دست نزن
                out.append(block.replace("\r\n", "\n").replace("\r", "\n"))
            }
        }
        return out.toString()
    }

    /**
     * نرمالایز یک خط متن SRT با حفظ تگ‌های ساده.
     * تگ‌ها موقتاً جدا می‌شوند، متن نرمالایز می‌شود، تگ‌ها برمی‌گردند.
     */
    private fun normalizeSrtTextLine(line: String): String {
        if (line.isEmpty()) return line

        // استخراج تگ‌های ابتدایی و انتهایی ساده
        var startTags = ""
        var endTags = ""
        var core = line

        // تگ‌های ابتدایی: <i>, <b>, <u>, <font ...>
        val startTagRegex = Regex("^(\\s*(<[^>]+>\\s*)+)")
        val startMatch = startTagRegex.find(core)
        if (startMatch != null) {
            startTags = startMatch.value
            core = core.substring(startTags.length)
        }

        // تگ‌های انتهایی: </i>, </b>, </u>, </font>
        val endTagRegex = Regex("((\\s*<[^>]+>)+\\s*)$")
        val endMatch = endTagRegex.find(core)
        if (endMatch != null) {
            endTags = endMatch.value
            core = core.substring(0, core.length - endTags.length)
        }

        // اگر هسته خالی شد یا فقط تگ بود، دست نزن
        if (core.isBlank()) return line

        val normalized = normalizeSubtitleLine(core)
        // حذف مارکر idempotency از داخل (چون تگ‌ها دورش هستند، مشکلی نیست ولی تمیزتر است)
        // در واقع مارکر باید بیرون تگ‌ها باشد؛ ساده نگه می‌داریم
        return startTags + normalized.removePrefix("\u200F\u202B") + endTags
    }
}
