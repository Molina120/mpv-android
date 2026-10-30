package `is`.xyz.mpv.preferences

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import `is`.xyz.mpv.BuildConfig
import `is`.xyz.mpv.MpvVersionCache
import `is`.xyz.mpv.R
import `is`.xyz.mpv.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (preferences.getBoolean("material_you_theming", false))
            DynamicColors.applyToActivityIfAvailable(this)
        enableEdgeToEdge()
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.elevation = 0f
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val header = "mpv-android ${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE} (${BuildConfig.BUILD_TYPE})\n"
        val details = MpvVersionCache.info ?: DEFAULT_MPV_INFO
        binding.logs.text = header + details
    }

    companion object {
        private val DEFAULT_MPV_INFO = """
            mpv v0.41.0-1049-g0b7ed670f Copyright © 2000-2026 mpv/MPlayer/mplayer2 projects
             built on Sep 17 2026 15:34:57
            libplacebo version: v7.371.0 (v7.360.0-124-g3330a515)
            FFmpeg version: N-126627-g094a2f8a2a
            FFmpeg library versions:
               libavcodec      63.13.100
               libavdevice     63.2.100
               libavfilter     12.4.100
               libavformat     63.7.100
               libavutil       61.7.100
               libswresample   7.3.100
               libswscale      10.2.100
            Configuration: -Ddefault_library=shared -Diconv=disabled -Dlua=enabled -Dlibcurl=enabled -Dlibmpv=true -Dcplayer=false -Dmanpage-build=disabled --cross-file=/var/tmp/mpv-android/buildscripts/prefix/arm64/crossfile.txt
            List of enabled features: aaudio android android-media-ndk audiotrack build-date clone cplugins dvbin egl-android ffmpeg gl glibc-thread-name gpl libass libavdevice libcurl libdl libplacebo linux-fstatfs lua memrchr opensles posix ppoll pthread-condattr-setclock vector vt.h zlib
        """.trimIndent() + "\n"
    }
}
