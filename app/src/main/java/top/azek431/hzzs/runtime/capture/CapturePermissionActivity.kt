package top.azek431.hzzs.runtime.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat

class CapturePermissionActivity : Activity() {
    private val launcherCode = 4310
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(manager.createScreenCaptureIntent(), launcherCode)
    }
    @Deprecated("Deprecated in Android SDK but retained for API 24 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == launcherCode && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, MediaProjectionCaptureService::class.java).apply {
                action = MediaProjectionCaptureService.ACTION_START
                putExtra(MediaProjectionCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(MediaProjectionCaptureService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, service)
            CapturePreferences.setMode(this, CaptureMode.MEDIA_PROJECTION)
        }
        finish()
    }
}
