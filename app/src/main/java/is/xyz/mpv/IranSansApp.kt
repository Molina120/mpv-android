package `is`.xyz.mpv

import android.app.Application
import android.widget.Toast

class IranSansApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // TODO(debug): remove this toast once the playback crash is resolved
        Toast.makeText(this, "IranSans b6", Toast.LENGTH_SHORT).show()
        CrashLogger.checkPreviousExit(this)
        CrashLogger.install(this)
    }
}
