package `is`.xyz.mpv

import android.content.Context
import android.util.Log
import java.io.File

object FontInstaller {
    private const val TAG = "FontInstaller"
    private const val ASSET_PATH = "fonts/IRANSansX-Medium.ttf"
    private const val FILE_NAME = "IRANSansX-Medium.ttf"
    const val FONT_FAMILY = "IRANSansX"
    private const val LUA_SCRIPT_NAME = "sub_normalizer.lua"

    /**
     * تایپ‌فیس فونت زیرنویس؛ برای اندازه‌گیری عرض واقعی متن
     * هنگام رسم کادر پس‌زمینه یکدست استفاده می‌شود.
     */
    var subtitleTypeface: android.graphics.Typeface? = null
        private set

    private val LUA_OVERLAY_SCRIPT = """
        local ov = nil
        if mp.create_osd_overlay then
            ov = mp.create_osd_overlay("ass-events")
            ov.res_x = 1280
            ov.res_y = 720
        end

        local function set_sub_overlay(ass_text)
            if ov then
                if not ass_text or ass_text == "" then
                    ov.data = ""
                    ov:remove()
                else
                    ov.data = ass_text
                    ov:update()
                end
            elseif mp.set_osd_ass then
                mp.set_osd_ass(1280, 720, ass_text or "")
            end
        end

        mp.register_script_message("norm-sub", function(ass_text)
            set_sub_overlay(ass_text)
        end)

        mp.register_event("end-file", function()
            set_sub_overlay("")
        end)
    """.trimIndent()

    fun install(context: Context) {
        try {
            val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
            val dest = File(fontsDir, FILE_NAME)
            context.assets.open(ASSET_PATH).use { input ->
                val assetSize = input.available().toLong()
                if (!dest.exists() || dest.length() != assetSize) {
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            // بارگذاری تایپ‌فیس برای اندازه‌گیری عرض متن در کادر پس‌زمینه یکدست
            subtitleTypeface = try {
                android.graphics.Typeface.createFromFile(dest)
            } catch (e: Exception) {
                Log.w(TAG, "failed to load subtitle typeface", e)
                null
            }

            // نصب اسکریپت Lua برای رندر زیرنویس نرمالیزه‌شده با libass
            val luaFile = File(context.filesDir, LUA_SCRIPT_NAME)
            luaFile.writeText(LUA_OVERLAY_SCRIPT, Charsets.UTF_8)

            // معرفی فونت به هر دو بخش sub و osd
            MPVLib.setOptionString("sub-fonts-dir", fontsDir.absolutePath)
            MPVLib.setOptionString("sub-font", FONT_FAMILY)
            MPVLib.setOptionString("osd-fonts-dir", fontsDir.absolutePath)
            MPVLib.setOptionString("osd-font", FONT_FAMILY)
            MPVLib.setOptionString("scripts", luaFile.absolutePath)

            // مخفی کردن لایهٔ خام زیرنویس (در حالی که دیکودر زیرنویس کاملاً فعال می‌ماند)
            MPVLib.setOptionString("sub-color", "0/0/0/0")
            MPVLib.setOptionString("sub-border-color", "0/0/0/0")
            MPVLib.setOptionString("sub-shadow-color", "0/0/0/0")
            MPVLib.setOptionString("sub-back-color", "0/0/0/0")
            MPVLib.setOptionString("sub-ass-override", "strip")
            MPVLib.setOptionString("sub-scale", "0.001")

            SubtitleNormalizer.attachToMpv()
        } catch (e: Exception) {
            Log.w(TAG, "failed to install subtitle font or normalizer script", e)
        }
    }
}
