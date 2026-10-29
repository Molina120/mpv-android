package `is`.xyz.mpv

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Installs the bundled IranSansX font as the default subtitle font.
 *
 * Must be called after MPVLib.create() but before MPVLib.init(),
 * because mpv options have to be set before initialization.
 */
object FontInstaller {
    private const val TAG = "FontInstaller"
    private const val ASSET_PATH = "fonts/IRANSansX-Medium.ttf"
    private const val FILE_NAME = "IRANSansX-Medium.ttf"
    private const val FONT_FAMILY = "IRANSansX"

    fun install(context: Context) {
        try {
            val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
            val dest = File(fontsDir, FILE_NAME)
            if (!dest.exists()) {
                context.assets.open(ASSET_PATH).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            MPVLib.setOptionString("sub-fonts-dir", fontsDir.absolutePath)
            MPVLib.setOptionString("sub-font", FONT_FAMILY)
        } catch (e: Exception) {
            Log.w(TAG, "failed to install subtitle font", e)
        }
    }
}
